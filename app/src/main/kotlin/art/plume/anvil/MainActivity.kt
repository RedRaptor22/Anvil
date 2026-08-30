package art.plume.anvil

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import art.plume.core.Dedupe
import art.plume.core.MM
import art.plume.core.Rgba
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
 * There is deliberately no chrome here yet. The desktop build's rails and
 * panels are the part that should NOT be ported directly — a phone wants a
 * bottom sheet and a radial menu, not a 58px vertical rail — so the UI is
 * listed as the next piece of work rather than transliterated.
 */
class MainActivity : Activity(), Gestures.Listener {

    private lateinit var surface: GLSurfaceView
    private lateinit var renderer: SketchRenderer
    private lateinit var gestures: Gestures

    private var live: Stroke? = null
    private val undo = ArrayList<Stroke>()
    private val redo = ArrayList<Stroke>()

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
        setContentView(surface)
        hideSystemBars()
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
        if (undo.isNotEmpty()) { undoLast(); return }
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
            renderer.addStroke(s)
            undo.add(s)
            redo.clear()
        }
        surface.requestRender()
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

    private fun undoLast() {
        val s = undo.removeLastOrNull() ?: return
        redo.add(s)
        rebuildScene()
    }

    private fun rebuildScene() {
        renderer.clear()
        for (s in undo) renderer.addStroke(s)
        surface.requestRender()
    }
}
