package art.plume.anvil

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import art.plume.core.Dedupe
import art.plume.core.EditStack
import art.plume.core.Group
import art.plume.core.MM
import art.plume.core.Picking
import art.plume.core.Rgba
import art.plume.core.Sketch
import art.plume.core.Stroke
import art.plume.core.StrokePoint
import art.plume.core.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The shell: one GL surface, the gesture layer, and an undo stack.
 *
 * The chrome is deliberately thin, and what there is follows the rule the
 * porting notes set: a phone wants a bottom sheet and a radial menu, not a
 * 58px vertical rail. The one sheet here is [GroupsPanel].
 *
 * The scene itself lives in `:core` as a [Sketch] — this class used to keep an
 * `ArrayList<Stroke>` doing double duty as the scene and the undo stack, which
 * left groups, visibility and deletion with nowhere to record themselves.
 */
class MainActivity : Activity(), Gestures.Listener, GroupsPanel.Host {

    private lateinit var surface: GLSurfaceView
    private lateinit var renderer: SketchRenderer
    private lateinit var gestures: Gestures
    private lateinit var panel: GroupsPanel

    private val sketch = Sketch()
    private val history = EditStack()
    private var live: Stroke? = null

    /** current brush settings, the equivalent of the web build's `P.TOOL` */
    private var brush = "pen"
    private var sizeMM = 14.0
    private var color = Rgba(0.106, 0.110, 0.129)

    private val rayOrigin = FloatArray(3)
    private val rayDir = FloatArray(3)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        renderer = SketchRenderer()
        gestures = Gestures(this)

        surface = object : GLSurfaceView(this) {
            override fun onTouchEvent(ev: MotionEvent): Boolean = gestures.onTouchEvent(ev)
            override fun onHoverEvent(ev: MotionEvent): Boolean = gestures.onHoverEvent(ev)
        }.apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            // render on demand: a sketchbook is still most of the time, and a
            // continuous loop is the fastest way to flatten a phone battery
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
        panel = GroupsPanel(this, this)
        val panelToggle = Button(this).apply {
            text = "Groups"
            textSize = 12f
            setAllCaps(false)
            setTextColor(Color.parseColor("#1B1C21"))
            background = GradientDrawable().apply {
                cornerRadius = 14f * resources.displayMetrics.density
                setColor(Color.parseColor("#F7F6FA"))
            }
            setOnClickListener { setPanelOpen(panel.visibility != View.VISIBLE) }
        }

        setContentView(FrameLayout(this).apply {
            addView(surface, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(panelToggle, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).also {
                val m = (16 * resources.displayMetrics.density).toInt()
                it.topMargin = m; it.leftMargin = m
            })
            addView(panel, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ))
        })
        setPanelOpen(false)
        hideSystemBars()
    }

    private fun setPanelOpen(open: Boolean) {
        panel.visibility = if (open) View.VISIBLE else View.GONE
        if (open) panel.refresh()
    }

    private fun panelOpen() = panel.visibility == View.VISIBLE

    /**
     * Push the model's answer to the renderer and repaint.
     *
     * Called after every edit. It is the ONLY place the two are reconciled, so
     * a toggle cannot half-apply: whatever [Sketch] says right now is what the
     * next frame draws.
     */
    private fun syncDisplay(structural: Boolean = false) {
        if (structural) renderer.setStrokes(sketch.strokes)
        renderer.setDisplay(
            sketch.strokes.filterNot { sketch.isVisible(it) },
            sketch.selection,
        )
        if (panelOpen()) panel.refresh()
        surface.requestRender()
    }

    override fun onResume() { super.onResume(); surface.onResume(); surface.requestRender() }
    override fun onPause() { super.onPause(); surface.onPause() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        surface.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    /**
     * Android's Back is not a browser Back. It steps out of whatever is open —
     * a sheet, then a selection — and only leaves the app when there is nothing
     * left to close, which is what a person expects from the gesture.
     */
    @Deprecated("Activity.onBackPressed")
    override fun onBackPressed() {
        if (panelOpen()) { setPanelOpen(false); return }
        if (sketch.selection.isNotEmpty()) {
            sketch.clearSelection(); syncDisplay(); return
        }
        if (history.canUndo) { onUndo(); return }
        super.onBackPressed()
    }

    // ---- drawing --------------------------------------------------------

    override fun onDrawBegin(x: Float, y: Float, pressure: Float, tiltAz: Float, tiltAlt: Float) {
        val s = Stroke(brush = brush, color = color, baseRadius = sizeMM * MM * 0.5)
        live = s
        renderer.setLive(s)
        appendPoint(s, x, y, pressure, tiltAz, tiltAlt)
        surface.requestRender()
    }

    override fun onDrawMove(x: Float, y: Float, pressure: Float, tiltAz: Float, tiltAlt: Float) {
        val s = live ?: return
        appendPoint(s, x, y, pressure, tiltAz, tiltAlt)
        renderer.invalidate(s)
        surface.requestRender()
    }

    override fun onDrawEnd() {
        val s = live ?: return
        live = null
        renderer.setLive(null)
        // the same order the web build commits in: clean the samples, then build
        Dedupe.clean(s)
        if (s.pts.size >= 2) {
            history.push(sketch.addStroke(s))
            renderer.addStroke(s)
            syncDisplay()
        } else {
            surface.requestRender()
        }
    }

    override fun onDrawCancel() {
        live = null
        renderer.setLive(null)
        surface.requestRender()
    }

    /**
     * Screen to world.
     *
     * With no guide active this drops the point on a camera-facing plane
     * through the pivot — the web build's `refreshDrawPlane()` with no
     * argument. Projecting onto an actual guide surface is the next piece and
     * belongs in `:core` so both builds share it.
     */
    private fun appendPoint(s: Stroke, x: Float, y: Float, pressure: Float, az: Float, alt: Float) {
        renderer.screenToRay(x, y, rayOrigin, rayDir)
        val n = floatArrayOf(
            (sin(renderer.phi) * sin(renderer.theta)),
            cos(renderer.phi),
            (sin(renderer.phi) * cos(renderer.theta))
        )
        val px = renderer.pivot[0]; val py = renderer.pivot[1]; val pz = renderer.pivot[2]
        val denom = rayDir[0] * n[0] + rayDir[1] * n[1] + rayDir[2] * n[2]
        if (abs(denom) < 1e-6f) return
        val t = ((px - rayOrigin[0]) * n[0] + (py - rayOrigin[1]) * n[1] +
            (pz - rayOrigin[2]) * n[2]) / denom
        if (t <= 0f) return
        val p = Vec3(
            (rayOrigin[0] + rayDir[0] * t).toDouble(),
            (rayOrigin[1] + rayDir[1] * t).toDouble(),
            (rayOrigin[2] + rayDir[2] * t).toDouble()
        )
        // MIN_PX equivalent: drop samples the tube could not show apart anyway
        s.pts.lastOrNull()?.let { if (it.p.distanceTo(p) < 0.0005) return }
        s.pts.add(StrokePoint(p, pressure = pressure.toDouble(), roll = az.toDouble()))
    }

    // ---- camera ---------------------------------------------------------

    override fun onCamera(dx: Float, dy: Float, dScale: Float, dRotate: Float) {
        // two fingers do all three at once, which is how every map and photo
        // viewer on the platform behaves — separating them into modes is a
        // desktop habit that costs a gesture here
        renderer.theta -= dx * 0.005f
        renderer.phi = (renderer.phi - dy * 0.005f)
            .coerceIn(0.05f, (PI - 0.05).toFloat())
        renderer.radius = (renderer.radius / dScale).coerceIn(0.05f, 400f)
        renderer.roll += dRotate
        surface.requestRender()
    }

    override fun onHover(x: Float, y: Float, pressure: Float) { /* nib preview: next */ }
    override fun onHoverExit() { }

    // ---- history --------------------------------------------------------

    override fun onUndo() {
        if (history.undo() == null) return
        syncDisplay(structural = true)
    }

    override fun onRedo() {
        if (history.redo() == null) return
        syncDisplay(structural = true)
    }

    override fun canUndo() = history.canUndo
    override fun canRedo() = history.canRedo

    // ---- selection ------------------------------------------------------

    /**
     * Hold a curve to select it; hold empty space to clear the selection.
     *
     * The tap target is a finger's worth of PIXELS converted to world units
     * ([Picking.slackFor]) — a tolerance fixed in millimetres would make a
     * zoomed-out sketch untappable and a zoomed-in one indiscriminate. It is
     * converted at the orbit distance rather than at each curve's own depth:
     * one conversion for the whole query, and the pivot is where the drawing
     * is, so the two agree wherever it matters.
     */
    override fun onLongPress(x: Float, y: Float) {
        renderer.screenToRay(x, y, rayOrigin, rayDir)
        val origin = Vec3(rayOrigin[0].toDouble(), rayOrigin[1].toDouble(), rayOrigin[2].toDouble())
        val dir = Vec3(rayDir[0].toDouble(), rayDir[1].toDouble(), rayDir[2].toDouble())
        val slack = Picking.slackFor(
            renderer.radius.toDouble(), TAP_PIXELS,
            surface.height.toDouble(), Math.toRadians(50.0),
        )
        val hit = sketch.pick(origin, dir, slack)
        if (hit == null) {
            if (sketch.selection.isEmpty()) return
            sketch.clearSelection()
        } else {
            sketch.setSelected(hit, hit !in sketch.selection)
        }
        syncDisplay()
    }

    // ---- groups ---------------------------------------------------------

    override fun model(): Sketch = sketch

    override fun onToggleVisible(group: Group) {
        history.push(sketch.setVisible(group, !group.visible))
        syncDisplay()
    }

    override fun onSelectGroup(group: Group) {
        val members = sketch.membersOf(group)
        if (members.isEmpty()) return
        val already = members.all { it in sketch.selection }
        sketch.clearSelection()
        if (!already) sketch.setSelected(members[0], true)
        syncDisplay()
    }

    override fun onAssignTo(group: Group) {
        val edit = sketch.assignSelectionTo(group)
        if (edit == null) { toast("Already in ${group.name}"); return }
        history.push(edit)
        syncDisplay()
    }

    override fun onRename(group: Group, name: String) {
        history.push(sketch.renameGroup(group, name))
        syncDisplay()
    }

    override fun onGroup() {
        val edit = sketch.groupSelection()
        if (edit == null) { toast("Select two or more curves to group"); return }
        history.push(edit)
        syncDisplay()
    }

    override fun onUngroup() {
        val edit = sketch.ungroupSelection()
        if (edit == null) { toast("No group selected"); return }
        history.push(edit)
        syncDisplay()
    }

    override fun onDelete() {
        val edit = sketch.deleteSelection()
        if (edit == null) { toast("Nothing selected"); return }
        history.push(edit)
        syncDisplay(structural = true)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private companion object {
        /** Roughly a fingertip at typical density; the pick is forgiving on purpose. */
        const val TAP_PIXELS = 28.0
    }
}
