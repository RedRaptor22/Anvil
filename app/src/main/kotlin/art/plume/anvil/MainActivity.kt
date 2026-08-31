package art.plume.anvil

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import art.plume.core.Camera
import art.plume.core.Dedupe
import art.plume.core.GuidePainting
import art.plume.core.GuideScene
import art.plume.core.Guides
import art.plume.core.History
import art.plume.core.LiveStroke
import art.plume.core.MM
import art.plume.core.Ray
import art.plume.core.Rgba
import art.plume.core.Stabilizer
import art.plume.core.Step
import art.plume.core.Stroke
import art.plume.core.StrokePoint
import art.plume.core.Tune
import art.plume.core.Vec3
import art.plume.core.clamp

/**
 * The shell: one GL surface, the gesture layer, and just enough chrome to draw
 * without a keyboard.
 *
 * The controls here are deliberately the FLOOR, not the interface. A phone
 * wants bottom sheets and a radial menu rather than the desktop build's 58px
 * vertical rail, and designing that properly is its own piece of work — but a
 * build you cannot change brush size or undo in is a build nobody can judge, so
 * these five things exist now and will be replaced whole later.
 *
 * Everything with an opinion in it — where a pixel lands in the world, how the
 * camera moves, what undo costs, how much the stabiliser lags — is in `:core`
 * and under test. This file is wiring.
 */
class MainActivity : Activity(), Gestures.Listener {

    private lateinit var surface: GLSurfaceView
    private lateinit var renderer: SketchRenderer
    private lateinit var gestures: Gestures

    private val camera = Camera()
    private val history = History()
    private val stabilizer = Stabilizer()
    private val guides = GuideScene()

    /** What the next stroke does. */
    private enum class Mode { DRAW, GUIDE, FLAT }
    private var mode = Mode.DRAW

    /**
     * FACT (C.1, inferred): with no guide, the first stroke makes one. Kept as
     * a flag because it is an inference rather than documented behaviour, and
     * is the first thing to turn off if it proves wrong.
     */
    private var autoGuide = true

    /** The document: committed strokes, in draw order. */
    private val doc = ArrayList<Stroke>()

    private val liveBuffer = LiveStroke()
    private var live: Stroke? = null

    /** Current brush settings, the equivalent of the web build's `P.TOOL`. */
    private var brush = "pen"
    private var sizeMM = 14.0
    private var color = Rgba(0.106, 0.110, 0.129)

    private lateinit var undoButton: Button
    private lateinit var redoButton: Button
    private lateinit var sizeLabel: TextView
    private lateinit var modeButton: Button
    private lateinit var guideButton: Button

    private val scratch = Vec3()
    private val penRay = Ray()
    private var spinning = false

    /** Whether the gesture that just ended was an orbit; only an orbit coasts. */
    private var lastGestureOrbited = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        renderer = SketchRenderer()
        gestures = Gestures(this)

        surface = object : GLSurfaceView(this) {
            override fun onTouchEvent(ev: MotionEvent): Boolean = gestures.onTouchEvent(ev)
            override fun onHoverEvent(ev: MotionEvent): Boolean = gestures.onHoverEvent(ev)

            /*
             * The camera's viewport has to be set from the UI thread, not from
             * the renderer's onSurfaceChanged: this thread is the one that
             * unprojects pen samples, and a camera still holding the previous
             * size puts every point of the first stroke after a rotation in the
             * wrong place.
             */
            override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
                super.onSizeChanged(w, h, oldW, oldH)
                camera.resize(w, h)
                pushCamera()
            }
        }.apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            /*
             * Render on demand. A sketchbook is still most of the time, and a
             * continuous loop is the fastest way to flatten a phone battery —
             * the one thing that needs frames of its own is release momentum,
             * which drives them from a Choreographer callback while it decays.
             */
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }

        val root = FrameLayout(this)
        root.addView(
            surface,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(buildControls())
        setContentView(root)

        history.addListener { refreshControls() }
        refreshControls()
        pushGuides()
        hideSystemBars()
    }

    override fun onResume() { super.onResume(); surface.onResume(); pushCamera() }
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
        if (history.canUndo()) { history.undo(); surface.requestRender(); return }
        super.onBackPressed()
    }

    // ---- the floor of an interface --------------------------------------

    private fun buildControls(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(210, 22, 22, 26))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        undoButton = Button(this).apply {
            text = getString(R.string.undo)
            setOnClickListener { history.undo(); surface.requestRender() }
        }
        redoButton = Button(this).apply {
            text = getString(R.string.redo)
            setOnClickListener { history.redo(); surface.requestRender() }
        }
        val clear = Button(this).apply {
            text = getString(R.string.clear)
            setOnClickListener { clearSketch() }
        }
        row.addView(undoButton); row.addView(redoButton); row.addView(clear)
        bar.addView(row)

        val guideRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        modeButton = Button(this).apply {
            text = modeLabel()
            setOnClickListener {
                mode = when (mode) {
                    Mode.DRAW -> Mode.GUIDE
                    Mode.GUIDE -> Mode.FLAT
                    Mode.FLAT -> Mode.DRAW
                }
                text = modeLabel()
            }
        }
        guideButton = Button(this).apply {
            text = getString(R.string.close_guide)
            setOnClickListener { closeActiveGuide() }
        }
        guideRow.addView(modeButton); guideRow.addView(guideButton)
        bar.addView(guideRow)

        sizeLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            text = sizeText()
        }
        val size = SeekBar(this).apply {
            // FACT: the brush panel runs 1mm - 300mm
            max = (Tune.BRUSH_MAX_MM - Tune.BRUSH_MIN_MM).toInt()
            progress = (sizeMM - Tune.BRUSH_MIN_MM).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    sizeMM = clamp(
                        Tune.BRUSH_MIN_MM + value, Tune.BRUSH_MIN_MM, Tune.BRUSH_MAX_MM,
                    )
                    sizeLabel.text = sizeText()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        bar.addView(sizeLabel)
        bar.addView(size)
        bar.addView(buildSwatches())

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.BOTTOM }
        bar.layoutParams = lp

        // keep the bar clear of the gesture pill and any display cutout
        ViewCompat.setOnApplyWindowInsetsListener(bar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(10) + bars.left, dp(8), dp(10) + bars.right, dp(8) + bars.bottom)
            insets
        }
        return bar
    }

    private fun buildSwatches(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val inks = listOf(
            Rgba(0.106, 0.110, 0.129),      // the default near-black ink
            Rgba(0.98, 0.98, 0.98),
            Rgba(0.85, 0.22, 0.26),
            Rgba(0.95, 0.60, 0.15),
            Rgba(0.30, 0.65, 0.35),
            Rgba(0.25, 0.50, 0.85),
            Rgba(0.55, 0.35, 0.75),
        )
        for (ink in inks) {
            val swatch = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(
                        Color.rgb(
                            (ink.r * 255).toInt(), (ink.g * 255).toInt(), (ink.b * 255).toInt(),
                        ),
                    )
                    setStroke(dp(1), Color.argb(120, 255, 255, 255))
                }
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                    marginEnd = dp(8)
                }
                setOnClickListener { color = ink }
            }
            row.addView(swatch)
        }
        return row
    }

    private fun sizeText(): String = "Brush ${sizeMM.toInt()} mm"

    private fun modeLabel(): String = when (mode) {
        Mode.DRAW -> getString(R.string.mode_draw)
        Mode.GUIDE -> getString(R.string.mode_guide)
        Mode.FLAT -> getString(R.string.mode_flat)
    }

    private fun refreshControls() {
        undoButton.isEnabled = history.canUndo()
        redoButton.isEnabled = history.canRedo()
        guideButton.isEnabled = guides.active != null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---- drawing --------------------------------------------------------

    override fun onDrawBegin(x: Float, y: Float, pressure: Float, tiltAz: Float, tiltAlt: Float) {
        camera.killSpin()
        val s = Stroke(brush = brush, color = color, baseRadius = sizeMM * MM * 0.5)
        stabilizer.reset()
        stabilizer.next(x.toDouble(), y.toDouble())
        synchronized(liveBuffer) {
            live = s
            liveBuffer.begin(s)
            if (appendAt(s, stabilizer.x, stabilizer.y, pressure, tiltAz)) liveBuffer.append(s)
        }
        renderer.setLive(liveBuffer)
        surface.requestRender()
    }

    override fun onDrawMove(x: Float, y: Float, pressure: Float, tiltAz: Float, tiltAlt: Float) {
        val s = live ?: return
        // FACT (C.2): Stable Stroke smooths the INPUT, before it is projected,
        // so the amount means the same thing at every zoom level
        if (!stabilizer.next(x.toDouble(), y.toDouble())) return
        synchronized(liveBuffer) {
            if (appendAt(s, stabilizer.x, stabilizer.y, pressure, tiltAz)) liveBuffer.append(s)
        }
        surface.requestRender()
    }

    override fun onDrawEnd() {
        val s = synchronized(liveBuffer) { live.also { live = null } } ?: return
        renderer.setLive(null)

        // the same order the web build commits in: clean the samples, then build
        Dedupe.clean(s)
        if (s.pts.size < 2) { surface.requestRender(); return }

        /*
         * A guide is built from the stroke's WORLD points, which for a stroke
         * drawn with no guide active lie on the camera-facing draw plane — so
         * the profile is the shape as drawn, in the plane it was drawn on.
         */
        val wantsGuide = mode != Mode.DRAW ||
            (autoGuide && guides.active == null && doc.isEmpty())
        if (wantsGuide) makeGuideFrom(s) else commit(s)
    }

    override fun onDrawCancel() {
        synchronized(liveBuffer) { live = null }
        renderer.setLive(null)
        surface.requestRender()
    }

    // ---- guides ---------------------------------------------------------

    /**
     * Turn the stroke just drawn into a guide.
     *
     * FACT (C.1, inferred): with nothing on the page and no guide, the first
     * stroke makes one — which is how Feather gets you onto a surface without
     * a mode switch. An explicit Guide mode does the same thing on demand.
     */
    private fun makeGuideFrom(s: Stroke) {
        val pts = s.pts.map { it.p.copy() }
        val fwd = Vec3()
        camera.forward(fwd)
        // only `right` matters here: it fixes which way the guide's u axis
        // runs, so "across the guide" matches across the glass
        val right = Vec3(); val up = Vec3(); val back = Vec3()
        camera.basis(right, up, back)

        val g = when (mode) {
            Mode.FLAT -> Guides.createFlatFromStroke(pts, fwd, right)
            else -> Guides.createFromStroke(pts, fwd, right, camera.radius)
        } ?: run {
            // nothing usable — keep the stroke rather than losing the gesture
            commit(s)
            return
        }

        val previous = guides.active
        history.run(
            Step(
                "Create guide", cost = s.pts.size,
                onRedo = { guides.setActive(g); pushGuides() },
                onUndo = { guides.setActive(previous); pushGuides() },
            ),
        )
        if (mode != Mode.DRAW) { mode = Mode.DRAW; modeButton.text = modeLabel() }
    }

    private fun closeActiveGuide() {
        val g = guides.active ?: return
        history.run(
            Step(
                "Close guide",
                onRedo = { guides.close(); pushGuides() },
                onUndo = { guides.setActive(g); pushGuides() },
            ),
        )
    }

    private fun pushGuides() {
        renderer.setGuides(guides.drawList())
        refreshControls()
        surface.requestRender()
    }

    /**
     * Screen to world.
     *
     * With no guide active this drops the point on a camera-facing plane
     * through the pivot — the web build's `refreshDrawPlane()` with no
     * argument. Projecting onto an actual guide surface is Phase 2, and belongs
     * in `:core` so both builds share it.
     */
    private fun appendAt(
        s: Stroke, px: Double, py: Double, pressure: Float, az: Float,
    ): Boolean {
        /*
         * With a guide active the pen paints ONTO it: the sample is a ray from
         * the eye through the pixel, met with the surface. Off the edge it
         * clamps back to the guide's nearest point, which is what the Clamp
         * setting already promises.
         */
        val active = guides.active
        if (active != null && mode == Mode.DRAW) {
            camera.rayFrom(px, py, penRay)
            val hit = GuidePainting.project(active, penRay, clampOffSurface = true)
            if (hit != null) {
                s.pts.lastOrNull()?.let { if (it.p.distanceTo(hit.point) < 0.0005) return false }
                s.pts.add(
                    StrokePoint(
                        hit.point.copy(), pressure = pressure.toDouble(), roll = az.toDouble(),
                        nrm = hit.normal.copy(),
                    ),
                )
                s.guideId = active.id
                return true
            }
            return false
        }

        val p = camera.planePoint(px, py, scratch) ?: return false
        // a second gate in WORLD units: two samples the tube could not show
        // apart are one sample, however far apart they were on screen
        s.pts.lastOrNull()?.let { if (it.p.distanceTo(p) < 0.0005) return false }
        s.pts.add(StrokePoint(p.copy(), pressure = pressure.toDouble(), roll = az.toDouble()))
        return true
    }

    private fun commit(s: Stroke) {
        history.run(
            Step(
                "Draw", cost = s.pts.size,
                onRedo = { doc.add(s); renderer.addStroke(s); surface.requestRender() },
                onUndo = { doc.remove(s); renderer.removeStroke(s); surface.requestRender() },
            ),
        )
    }

    private fun clearSketch() {
        if (doc.isEmpty()) return
        val removed = ArrayList(doc)
        history.run(
            Step(
                "Clear", cost = removed.sumOf { it.pts.size },
                onRedo = {
                    doc.clear(); renderer.setStrokes(doc); surface.requestRender()
                },
                onUndo = {
                    doc.clear(); doc.addAll(removed)
                    renderer.setStrokes(doc); surface.requestRender()
                },
            ),
        )
    }

    // ---- camera ---------------------------------------------------------

    override fun onCamera(dx: Float, dy: Float, dScale: Float, dRotate: Float, fingers: Int) {
        camera.killSpin()
        lastGestureOrbited = fingers < 3
        if (lastGestureOrbited) {
            camera.orbitBy(dx.toDouble(), dy.toDouble())
        } else {
            camera.panBy(dx.toDouble(), dy.toDouble())
        }
        // zoom and twist ride along with either, which is how every map and
        // photo viewer on the platform behaves
        if (dScale > 0f) camera.zoomBy(1.0 / dScale)
        if (dRotate != 0f) camera.rollBy(dRotate.toDouble())
        pushCamera()
    }

    override fun onCameraEnd(dx: Float, dy: Float) {
        // a flick keeps turning after the fingers leave, then decays. Only an
        // orbit coasts: a pan that kept sliding after release would drift the
        // pivot away from whatever you had just lined up
        if (!lastGestureOrbited) return
        camera.addSpin(
            -dx.toDouble() * Tune.ORBIT_PER_PX, -dy.toDouble() * Tune.ORBIT_PER_PX,
        )
        if (camera.spinning) startSpin()
    }

    private fun startSpin() {
        if (spinning) return
        spinning = true
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!camera.tickSpin()) { spinning = false; return }
                pushCamera()
                Choreographer.getInstance().postFrameCallback(this)
            }
        })
    }

    private fun pushCamera() {
        renderer.setCamera(camera)
        surface.requestRender()
    }

    override fun onHover(x: Float, y: Float, pressure: Float) { /* nib preview: Phase 6 */ }
    override fun onHoverExit() { }
}
