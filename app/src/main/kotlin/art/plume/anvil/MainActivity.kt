package art.plume.anvil

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import art.plume.core.Camera
import art.plume.core.Dedupe
import art.plume.core.Document
import art.plume.core.DocumentEnv
import art.plume.core.DocumentTool
import art.plume.core.Editing
import art.plume.core.Export
import art.plume.core.Fill
import art.plume.core.GuidePainting
import art.plume.core.GuideScene
import art.plume.core.Guides
import art.plume.core.History
import art.plume.core.Liquify
import art.plume.core.LiveStroke
import art.plume.core.MM
import art.plume.core.Px
import art.plume.core.Ray
import art.plume.core.Rgba
import art.plume.core.Selection
import art.plume.core.Sketch
import art.plume.core.Stabilizer
import art.plume.core.Step
import art.plume.core.StyleChange
import art.plume.core.Stroke
import art.plume.core.StrokePoint
import art.plume.core.Tune
import art.plume.core.Vec3
import art.plume.core.clamp

/**
 * The shell: one GL surface, the gesture layer, and Plume's chrome over it.
 *
 * The interface itself lives in [Chrome] — a view-for-view port of the web
 * build's panels — and this file is what those panels are wired to. The split
 * is deliberate: [Chrome] can be read as "what Plume looks like" without any
 * strokes in it, and this file as "what the buttons do" without any pixels.
 *
 * Everything with an opinion in it — where a pixel lands, how far an eraser
 * reaches, what a pinch does to a curve — lives in `:core` and is under test.
 * This file is wiring, and the one thing it is careful about is that each
 * gesture becomes exactly ONE history step. A minute of pushing with Liquify
 * should undo at once rather than a hundred times.
 */
class MainActivity : Activity(), Gestures.Listener {

    private lateinit var surface: GLSurfaceView
    private lateinit var renderer: SketchRenderer
    private lateinit var gestures: Gestures

    private val camera = Camera()
    private val history = History()
    private val stabilizer = Stabilizer()
    private val guides = GuideScene()
    private val sketch = Sketch()

    private var tool = Tool.DRAW

    /**
     * FACT (C.1, inferred): with no guide, the first stroke makes one. Kept as
     * a flag because it is an inference rather than documented behaviour.
     */
    private var autoGuide = true

    private val liquifyCfg = Liquify.Settings()

    // ---- files ------------------------------------------------------------

    private val docEnv = DocumentEnv()
    private val docTool = DocumentTool()

    /**
     * Sections of the last opened file this build does not model yet — the
     * light, the post effects. Carried so a sketch made in the browser does
     * not come back re-lit after a trip through the phone.
     */
    private var carried: Document.Carried? = null

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var autosavePending = false

    /** The material file waiting for its own destination; see [exportObj]. */
    private var pendingMtl: String? = null

    private val liveBuffer = LiveStroke()
    private var live: Stroke? = null

    /** Current brush settings, the equivalent of the web build's `P.TOOL`. */
    private var brush = "pen"
    private var sizeMM = 14.0
    private var color = Rgba(0.106, 0.110, 0.129)

    private var opacity = 1.0

    private lateinit var chrome: Chrome

    private val scratch = Vec3()
    private val penRay = Ray()
    private var spinning = false
    private var lastGestureOrbited = false

    // ---- what a drag is doing --------------------------------------------

    /** The document as it was when this drag began, for a one-step undo. */
    private var dragStrokes: List<Stroke>? = null
    private var dragPositions: List<List<Vec3>>? = null
    private var dragTargets: List<Stroke>? = null
    private var dragSelection: List<Stroke>? = null
    private var sweep: Selection.Sweep? = null
    private val lasso = ArrayList<Px>()
    private var lastPen = Px(0.0, 0.0)
    private var dragMoved = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        renderer = SketchRenderer()
        gestures = Gestures(this)

        surface = object : GLSurfaceView(this) {
            override fun onTouchEvent(ev: MotionEvent): Boolean = gestures.onTouchEvent(ev)
            override fun onHoverEvent(ev: MotionEvent): Boolean = gestures.onHoverEvent(ev)

            /*
             * The camera's viewport is set from the UI thread, not from the
             * renderer's onSurfaceChanged: this thread is the one that
             * unprojects pen samples, and a camera still holding the previous
             * size puts every point of the first stroke after a rotation in
             * the wrong place.
             */
            override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
                super.onSizeChanged(w, h, oldW, oldH)
                camera.resize(w, h)
                pushCamera()
            }
        }.apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }

        val root = FrameLayout(this)
        root.addView(
            surface,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        val tokens = Tokens(this)
        /*
         * Plume follows the CANVAS background, not a system setting: applyEnv
         * flips body.dark when the paper goes dark. Android resources work the
         * other way round, so the system decides and the canvas follows — the
         * two must not be allowed to disagree, or the chrome ends up light over
         * a black page. A document that carries its own background still wins
         * on load (see loadDocument), which is the same precedence the web
         * build has.
         */
        renderer.background = if (tokens.dark) DARK_PAGE else LIGHT_PAGE
        chrome = Chrome(this, tokens)
        wireChrome()
        root.addView(
            chrome.root,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        /*
         * Immersive mode hides the bars but not a display cutout, so the chrome
         * has to be inset by hand — this is `env(safe-area-inset-*)`, which the
         * stylesheet already uses for the compact dock.
         */
        ViewCompat.setOnApplyWindowInsetsListener(chrome.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)

        history.addListener { refreshControls() }
        restoreAutosave()
        refreshControls()
        syncBrushControls()
        renderer.setStrokes(sketch.strokes)
        pushGuides()
        hideSystemBars()
    }

    override fun onResume() { super.onResume(); surface.onResume(); pushCamera() }

    override fun onPause() {
        super.onPause()
        surface.onPause()
        /*
         * Write NOW rather than on the debounce. Android kills a backgrounded
         * process whenever it likes, and the whole point of an autosave is to
         * survive that — a save that was still waiting its half second is a
         * save that did not happen.
         */
        writeAutosave()
    }

    override fun onDestroy() { super.onDestroy(); io.shutdown() }

    /**
     * `UI.applyMode` runs on resize in the browser; the same trigger here is a
     * configuration change, which is what this activity already handles itself
     * (see `configChanges` in the manifest) rather than being recreated for.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        chrome.applyMode()
        refreshControls()
    }

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
     * Android's Back steps OUT of whatever is open rather than leaving: a
     * selection first, then an undo, and only then the app.
     */
    @Deprecated("Activity.onBackPressed")
    override fun onBackPressed() {
        if (chrome.closeTop()) return
        if (sketch.selection.isNotEmpty()) { deselectAll(); return }
        if (history.canUndo()) { history.undo(); refreshScene(); return }
        super.onBackPressed()
    }

    // ---- the chrome -------------------------------------------------------

    /**
     * Every panel in [Chrome] raises either a [Tool] or an [Action]; nothing in
     * there reaches into the sketch. This is the whole of the join.
     */
    private fun wireChrome() {
        chrome.onTool = { t -> setTool(t) }
        chrome.onAction = { a -> doAction(a) }
        chrome.onSizeMm = { mm -> sizeMM = mm }
        chrome.onOpacity = { o -> opacity = o }
        chrome.onBrush = { b -> brush = b }
        chrome.onColor = { argb -> applyColorToSelectionOrBrush(rgbaOf(argb)) }
        chrome.onGuideOpacity = { v ->
            guides.active?.let { g -> g.opacity = v; pushGuides(); surface.requestRender() }
        }
    }

    /**
     * `P.resetView` — frame the sketch, or go back to the default station when
     * there is nothing to frame. Plume animates the move; this one snaps,
     * which is the one visible difference and not a behavioural one.
     */
    private fun resetView() {
        camera.theta = Math.PI * 0.25
        camera.phi = Math.PI * 0.42
        camera.roll = 0.0
        camera.radius = Tune.RADIUS_DEFAULT
        camera.pinned = false
        var lo = Vec3(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
        var hi = Vec3(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        var any = false
        for (st in sketch.strokes) for (sp in st.pts) {
            any = true
            lo = Vec3(minOf(lo.x, sp.p.x), minOf(lo.y, sp.p.y), minOf(lo.z, sp.p.z))
            hi = Vec3(maxOf(hi.x, sp.p.x), maxOf(hi.y, sp.p.y), maxOf(hi.z, sp.p.z))
        }
        if (any) {
            camera.pivot.set((lo.x + hi.x) / 2, (lo.y + hi.y) / 2, (lo.z + hi.z) / 2)
            val r = 0.5 * maxOf(hi.x - lo.x, maxOf(hi.y - lo.y, hi.z - lo.z))
            val halfFov = camera.fovFromFocal(camera.focal) * Math.PI / 360.0
            camera.radius = clamp(r / Math.tan(halfFov) * 1.15, 1.0, 200.0)
        } else {
            camera.pivot.set(0.0, 0.0, 0.0)
        }
        camera.apply()
    }

    /** `Tools.sample` — the injector takes the whole brush, not just its ink. */
    private fun sampleAt(x: Double, y: Double) {
        val hit = Selection.hitTest(sketch, camera, x, y, mask())
        if (hit == null) { toast(getString(R.string.nothing_under_that)); return }
        brush = hit.brush
        color = hit.color
        sizeMM = hit.baseRadius * 2.0 / MM
        opacity = hit.opacity
        syncBrushControls()
        toast(getString(R.string.sampled, hit.brush))
    }

    /** The eye on the guide bar: keep this surface in the Resource tab. */
    private fun saveActiveGuide() {
        if (guides.save() == null) { toast(getString(R.string.no_guide)); return }
        pushGuides()
        refreshControls()
        toast(getString(R.string.guide_saved))
    }

    /** sRGB bytes to the linear-ish 0..1 the core carries. */
    private fun rgbaOf(argb: Int): Rgba = Rgba(
        Color.red(argb) / 255.0, Color.green(argb) / 255.0, Color.blue(argb) / 255.0,
    )

    private fun argbOf(c: Rgba): Int = Color.rgb(
        (c.r * 255).toInt().coerceIn(0, 255),
        (c.g * 255).toInt().coerceIn(0, 255),
        (c.b * 255).toInt().coerceIn(0, 255),
    )

    private fun doAction(a: Action) = when (a) {
        Action.HOME -> { resetView(); pushCamera(); refreshControls() }
        Action.EXPORT -> chooseExportFormat()
        Action.MENU -> chrome.setMenu(true)
        Action.HELP -> toast(getString(R.string.not_yet_help))
        Action.UNDO -> { history.undo(); refreshScene() }
        Action.REDO -> { history.redo(); refreshScene() }
        Action.MIRROR -> mirrorSelection()
        Action.STAGE -> chrome.toggleStage()
        Action.GUIDE_BEND -> toast(getString(R.string.not_yet_bend))
        Action.GUIDE_SAVE -> saveActiveGuide()
        Action.GUIDE_CLOSE -> closeActiveGuide()
        Action.DUPLICATE -> duplicateSelection()
        Action.DUPLICATE_MIRROR -> mirrorSelection()
        Action.LIQUIFY -> setTool(Tool.LIQUIFY)
        Action.DELETE -> deleteSelection()
        Action.PRESSURE -> toast(getString(R.string.not_yet_pressure))
        Action.NEW -> { clearSketch(); resetView(); pushCamera() }
        Action.SAVE -> chooseSaveTarget()
        Action.OPEN -> chooseOpenTarget()
        Action.CLEAR -> clearSketch()
    }

    /**
     * The tools with no behaviour behind them yet say so. A button that looks
     * live and does nothing is worse than one that is honest about the gap —
     * and the gap is real: Bend, Loft and Primitives are all ported in `:core`
     * and under test, but none of them has its interaction wired up here.
     */
    private fun setTool(t: Tool) {
        when (t) {
            Tool.SHAPE -> { toast(getString(R.string.not_yet_shape)); return }
            Tool.BEND -> { toast(getString(R.string.not_yet_bend)); return }
            Tool.LOFT -> { toast(getString(R.string.not_yet_loft)); return }
            Tool.PRIM -> { toast(getString(R.string.not_yet_prim)); return }
            else -> {}
        }
        tool = t
        chrome.setTool(t)
        refreshControls()
    }

    /** After a load the brush came from the file, so the rail has to follow. */
    private fun syncBrushControls() {
        chrome.setSize(sizeMM)
        chrome.setOpacityValue(opacity)
        chrome.setBrush(brush)
        chrome.setColor(argbOf(color))
    }

    /** `UI.refresh` — push the model back at the chrome and let it re-derive. */
    private fun refreshControls() {
        chrome.setHistory(history.canUndo(), history.canRedo())
        val g = guides.active
        chrome.setGuide(g != null, g?.name ?: "", g?.opacity ?: 0.42)
        chrome.setSelection(sketch.selection.size)
        chrome.setViewInfo(camera.focal.toInt(), !camera.ortho, sketch.strokes.size)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = chrome.toast(msg)

    // ---- the guide mask, which every tool honours -------------------------

    /**
     * FACT (A.9): a point the active guide hides from where you are standing is
     * protected from the eraser and from selection alike.
     */
    private fun mask(): (Vec3) -> Boolean {
        val g = guides.active ?: return Editing.NO_MASK
        val eye = camera.eye.copy()
        return { p -> GuidePainting.isMasked(g, eye, p) }
    }

    // ---- one gesture, one history step ------------------------------------

    override fun onDrawBegin(x: Float, y: Float, pressure: Float, tiltAz: Float, tiltAlt: Float) {
        camera.killSpin()
        dragMoved = false
        lastPen = Px(x.toDouble(), y.toDouble())

        when (tool) {
            Tool.DRAW, Tool.GUIDE, Tool.FLATGUIDE -> beginStroke(x, y, pressure, tiltAz)

            Tool.ERASE, Tool.VACUUM -> {
                dragStrokes = ArrayList(sketch.strokes)
                stepDestructive(x.toDouble(), y.toDouble())
            }

            Tool.SMOOTH -> {
                dragTargets = ArrayList(sketch.strokes)
                dragPositions = Editing.snapshot(dragTargets!!)
                stepSmooth(x.toDouble(), y.toDouble())
            }

            Tool.LIQUIFY -> {
                val sel = sketch.selection
                if (sel.isEmpty()) { toast("Select the curves to liquify first"); return }
                /*
                 * The disc is in pixels and follows the brush, so there is only
                 * one size to think about. Worked out HERE rather than cached
                 * when the slider moves: at that moment the camera may not have
                 * been laid out yet, and a disc sized against a 1x1 viewport is
                 * a tool that does nothing until you touch the slider again.
                 */
                liquifyCfg.size = maxOf(24.0, camera.worldToPx(sizeMM * MM * 0.5) * 4)
                dragTargets = sel
                dragPositions = Editing.snapshot(sel)
            }

            Tool.SELECT -> {
                dragSelection = sketch.selection
                sweep = Selection.beginSweep(Px(x.toDouble(), y.toDouble()))
            }

            Tool.LASSO -> {
                dragSelection = sketch.selection
                lasso.clear()
                Selection.appendLasso(lasso, x.toDouble(), y.toDouble())
                pushLasso()
            }

            /* Tools.begin: fill and the samplers act on the press itself and
               have no drag of their own. */
            Tool.FILL -> fillActiveGuide()
            Tool.INJECT -> sampleAt(x.toDouble(), y.toDouble())

            else -> {}
        }
        surface.requestRender()
    }

    override fun onDrawMove(x: Float, y: Float, pressure: Float, tiltAz: Float, tiltAlt: Float) {
        dragMoved = true
        when (tool) {
            Tool.DRAW, Tool.GUIDE, Tool.FLATGUIDE -> moveStroke(x, y, pressure, tiltAz)
            Tool.ERASE, Tool.VACUUM -> stepDestructive(x.toDouble(), y.toDouble())
            Tool.SMOOTH -> stepSmooth(x.toDouble(), y.toDouble())
            Tool.LIQUIFY -> {
                dragTargets?.let {
                    Liquify.step(
                        it, camera, liquifyCfg,
                        lastPen.x, lastPen.y, x.toDouble(), y.toDouble(), mask(),
                    )
                    refreshStrokeMeshes(it)
                }
            }
            Tool.SELECT -> sweep?.step(sketch, camera, x.toDouble(), y.toDouble(), mask())
            Tool.LASSO -> {
                if (Selection.appendLasso(lasso, x.toDouble(), y.toDouble())) pushLasso()
            }
            else -> {}
        }
        lastPen = Px(x.toDouble(), y.toDouble())
        surface.requestRender()
    }

    override fun onDrawEnd() {
        when (tool) {
            Tool.DRAW, Tool.GUIDE, Tool.FLATGUIDE -> endStroke()
            Tool.ERASE, Tool.VACUUM -> commitDocumentChange(
                if (tool == Tool.ERASE) "Erase" else "Vacuum",
            )
            Tool.SMOOTH, Tool.LIQUIFY -> commitPointChange(
                if (tool == Tool.SMOOTH) "Smooth" else "Liquify",
            )
            Tool.SELECT -> endSelect()
            Tool.LASSO -> endLasso()
            else -> {}
        }
        clearDrag()
        surface.requestRender()
    }

    override fun onDrawCancel() {
        // put back anything the drag had already changed
        dragPositions?.let { snap -> dragTargets?.let { Editing.restore(it, snap) } }
        dragStrokes?.let { setDocument(it) }
        dragSelection?.let { sketch.selectOnly(it) }
        synchronized(liveBuffer) { live = null }
        renderer.setLive(null)
        clearDrag()
        refreshScene()
    }

    private fun clearDrag() {
        dragStrokes = null
        dragPositions = null
        dragTargets = null
        dragSelection = null
        sweep = null
        lasso.clear()
        renderer.setOverlay(null)
    }

    // ---- drawing -----------------------------------------------------------

    private fun beginStroke(x: Float, y: Float, pressure: Float, tiltAz: Float) {
        val s = Stroke(brush = brush, color = color, baseRadius = sizeMM * MM * 0.5)
        stabilizer.reset()
        stabilizer.next(x.toDouble(), y.toDouble())
        synchronized(liveBuffer) {
            live = s
            liveBuffer.begin(s)
            if (appendAt(s, stabilizer.x, stabilizer.y, pressure, tiltAz)) liveBuffer.append(s)
        }
        renderer.setLive(liveBuffer)
    }

    private fun moveStroke(x: Float, y: Float, pressure: Float, tiltAz: Float) {
        val s = live ?: return
        // FACT (C.2): Stable Stroke smooths the INPUT, before it is projected
        if (!stabilizer.next(x.toDouble(), y.toDouble())) return
        synchronized(liveBuffer) {
            if (appendAt(s, stabilizer.x, stabilizer.y, pressure, tiltAz)) liveBuffer.append(s)
        }
    }

    private fun endStroke() {
        val s = synchronized(liveBuffer) { live.also { live = null } } ?: return
        renderer.setLive(null)
        Dedupe.clean(s)
        if (s.pts.size < 2) return

        val wantsGuide = tool != Tool.DRAW ||
            (autoGuide && guides.active == null && sketch.strokes.isEmpty())
        if (wantsGuide) makeGuideFrom(s) else commitStroke(s)
    }

    /**
     * Screen to world. With a guide active the pen paints ONTO it; otherwise
     * the point lands on a camera-facing plane through the pivot, which is the
     * web build's `refreshDrawPlane()` with no argument.
     */
    private fun appendAt(
        s: Stroke, px: Double, py: Double, pressure: Float, az: Float,
    ): Boolean {
        val active = guides.active
        if (active != null && tool == Tool.DRAW) {
            camera.rayFrom(px, py, penRay)
            val hit = GuidePainting.project(active, penRay, clampOffSurface = true) ?: return false
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

        val p = camera.planePoint(px, py, scratch) ?: return false
        s.pts.lastOrNull()?.let { if (it.p.distanceTo(p) < 0.0005) return false }
        s.pts.add(StrokePoint(p.copy(), pressure = pressure.toDouble(), roll = az.toDouble()))
        return true
    }

    private fun commitStroke(s: Stroke) {
        history.run(
            Step(
                "Draw", cost = s.pts.size,
                onRedo = { sketch.add(s); refreshScene() },
                onUndo = { sketch.remove(s); refreshScene() },
            ),
        )
    }

    // ---- the destructive tools ---------------------------------------------

    private fun stepDestructive(x: Double, y: Double) {
        val m = mask()
        if (tool == Tool.ERASE) {
            Editing.eraseScreen(sketch, camera, x, y, camera.worldToPx(sizeMM * MM * 0.5), m)
        } else {
            Editing.vacuumAt(sketch, camera, x, y, m)
        }
        renderer.setStrokes(sketch.strokes)
    }

    private fun stepSmooth(x: Double, y: Double) {
        val rPx = maxOf(12.0, camera.worldToPx(sizeMM * MM * 0.5) * 3)
        val touched = Editing.smoothStep(
            sketch, camera, x, y, rPx, mask(),
            reprojectOnto = { st -> guides.byId(st.guideId ?: -1)?.surface?.mesh },
        )
        refreshStrokeMeshes(touched)
    }

    /**
     * A whole drag becomes one step, by swapping the document between what it
     * was and what it became. Erasing a line in one sweep should undo in one
     * tap, not in fifty.
     */
    private fun commitDocumentChange(label: String) {
        val before = dragStrokes ?: return
        val after = ArrayList(sketch.strokes)
        if (before.size == after.size && before == after) return
        history.push(
            Step(
                label, cost = before.sumOf { it.pts.size },
                onRedo = { setDocument(after) },
                onUndo = { setDocument(before) },
            ),
        )
        refreshScene()
    }

    /** The same, for a tool that nudges points rather than adding or removing. */
    private fun commitPointChange(label: String) {
        val targets = dragTargets ?: return
        val before = dragPositions ?: return
        if (!dragMoved) return
        val after = Editing.snapshot(targets)
        history.push(
            Step(
                label, cost = targets.sumOf { it.pts.size },
                onRedo = { Editing.restore(targets, after); refreshScene() },
                onUndo = { Editing.restore(targets, before); refreshScene() },
            ),
        )
        refreshScene()
    }

    // ---- selection ----------------------------------------------------------

    private fun endSelect() {
        val before = dragSelection ?: return
        // a press that never moved is a tap; anything else was a sweep
        if (!dragMoved) Selection.tapSelect(sketch, camera, lastPen.x, lastPen.y, true, mask())
        commitSelectionChange(if (dragMoved) "Sweep select" else "Select", before)
    }

    private fun endLasso() {
        val before = dragSelection ?: return
        renderer.setOverlay(null)
        if (lasso.size < 3) return
        val hits = Selection.lassoSelect(sketch, camera, lasso, mask())
        toast(if (hits.isEmpty()) "Nothing inside the loop" else "${hits.size} selected")
        commitSelectionChange("Lasso select", before)
    }

    private fun commitSelectionChange(label: String, before: List<Stroke>) {
        val after = sketch.selection
        if (after == before) return
        history.push(
            Step(
                label,
                onRedo = { sketch.selectOnly(after); refreshScene() },
                onUndo = { sketch.selectOnly(before); refreshScene() },
            ),
        )
        refreshControls()
    }

    private fun deselectAll() {
        val before = sketch.selection
        if (before.isEmpty()) return
        history.run(
            Step(
                "Deselect",
                onRedo = { sketch.clearSelection(); refreshScene() },
                onUndo = { sketch.selectOnly(before); refreshScene() },
            ),
        )
    }

    private fun pushLasso() {
        val f = FloatArray(lasso.size * 2)
        for (i in lasso.indices) { f[i * 2] = lasso[i].x.toFloat(); f[i * 2 + 1] = lasso[i].y.toFloat() }
        renderer.setOverlay(f)
    }

    // ---- actions on a selection ---------------------------------------------

    private fun duplicateSelection() {
        val before = sketch.selection
        if (before.isEmpty()) { toast("Nothing selected"); return }
        val copies = Selection.duplicate(sketch, camera)
        pushReversible("Duplicate", copies, before)
    }

    private fun mirrorSelection() {
        val before = sketch.selection
        if (before.isEmpty()) { toast("Nothing selected"); return }
        val copies = Selection.mirroredDuplicate(sketch, "x")
        pushReversible("Mirrored duplicate", copies, before)
    }

    private fun pushReversible(label: String, copies: List<Stroke>, before: List<Stroke>) {
        history.push(
            Step(
                label, cost = copies.sumOf { it.pts.size },
                onRedo = {
                    for (c in copies) sketch.add(c)
                    sketch.selectOnly(copies); refreshScene()
                },
                onUndo = {
                    for (c in copies) sketch.remove(c)
                    sketch.selectOnly(before); refreshScene()
                },
            ),
        )
        toast("${copies.size} duplicated")
        refreshScene()
    }

    private fun deleteSelection() {
        val doomed = sketch.selection
        if (doomed.isEmpty()) { toast("Nothing selected"); return }
        val before = ArrayList(sketch.strokes)
        history.run(
            Step(
                "Delete", cost = doomed.sumOf { it.pts.size },
                onRedo = {
                    for (s in doomed) sketch.remove(s)
                    sketch.clearSelection(); refreshScene()
                },
                onUndo = { setDocument(before); sketch.selectOnly(doomed); refreshScene() },
            ),
        )
    }

    private fun applyColorToSelectionOrBrush(ink: Rgba) {
        color = ink
        val sel = sketch.selection
        if (sel.isEmpty()) return
        // FACT: the brush panel restyles a live selection rather than only
        // setting what the next stroke will be
        val was = sel.map { it.color }
        history.run(
            Step(
                "Recolour",
                onRedo = {
                    Selection.restyle(sel, StyleChange(color = ink))
                    refreshScene()
                },
                onUndo = {
                    for (i in sel.indices) sel[i].color = was[i]
                    refreshScene()
                },
            ),
        )
    }

    private fun clearSketch() {
        if (sketch.strokes.isEmpty()) return
        val before = ArrayList(sketch.strokes)
        history.run(
            Step(
                "Clear", cost = before.sumOf { it.pts.size },
                onRedo = { setDocument(emptyList()); refreshScene() },
                onUndo = { setDocument(before); refreshScene() },
            ),
        )
    }

    // ---- guides --------------------------------------------------------------

    private fun makeGuideFrom(s: Stroke) {
        val pts = s.pts.map { it.p.copy() }
        val fwd = Vec3()
        camera.forward(fwd)
        // only `right` matters: it fixes which way the guide's u axis runs, so
        // "across the guide" matches across the glass
        val right = Vec3(); val up = Vec3(); val back = Vec3()
        camera.basis(right, up, back)

        val g = when (tool) {
            Tool.FLATGUIDE -> Guides.createFlatFromStroke(pts, fwd, right)
            else -> Guides.createFromStroke(pts, fwd, right, camera.radius)
        } ?: run { commitStroke(s); return }

        val previous = guides.active
        history.run(
            Step(
                "Create guide", cost = s.pts.size,
                onRedo = { guides.setActive(g); pushGuides() },
                onUndo = { guides.setActive(previous); pushGuides() },
            ),
        )
        if (tool != Tool.DRAW) setTool(Tool.DRAW)
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

    private fun fillActiveGuide() {
        val g = guides.active ?: run { toast("Select a guide to fill"); return }
        val proto = Stroke(brush = brush, color = color, baseRadius = sizeMM * MM * 0.5)
        when (val r = Fill.fillGuide(g, proto)) {
            is Fill.Result.Refused -> toast(r.reason)
            is Fill.Result.Filled -> {
                history.run(
                    Step(
                        "Fill", cost = r.strokes.sumOf { it.pts.size },
                        onRedo = { for (s in r.strokes) sketch.add(s); refreshScene() },
                        onUndo = { for (s in r.strokes) sketch.remove(s); refreshScene() },
                    ),
                )
                toast("Filled with ${r.strokes.size} strokes")
            }
        }
    }

    private fun pushGuides() {
        renderer.setGuides(guides.drawList())
        refreshControls()
        surface.requestRender()
    }

    // ---- files -----------------------------------------------------------------

    /**
     * Save, open and export all go through the Storage Access Framework.
     *
     * An app writing into shared storage by path has not been the way to do
     * this since Android 10, and the picker is also the only route that lets
     * someone put a sketch in Drive or hand it to another app — which is the
     * point of having a portable format at all.
     */
    private fun chooseSaveTarget() {
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "sketch.plume.json")
            },
            REQ_SAVE,
        )
    }

    private fun chooseOpenTarget() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                // .plume.json files are often reported as octet-stream, so
                // asking only for application/json hides them in the picker
                type = "*/*"
            },
            REQ_OPEN,
        )
    }

    private fun chooseExportFormat() {
        if (sketch.strokes.isEmpty()) { toast("Nothing to export"); return }
        val names = arrayOf("OBJ + MTL (mm)", "STL binary (mm)", "glTF 2.0 (metres)")
        AlertDialog.Builder(this)
            .setTitle(R.string.export_)
            .setItems(names) { _, which ->
                val (req, ext, mime) = when (which) {
                    0 -> Triple(REQ_EXPORT_OBJ, "obj", "model/obj")
                    1 -> Triple(REQ_EXPORT_STL, "stl", "model/stl")
                    else -> Triple(REQ_EXPORT_GLTF, "gltf", "model/gltf+json")
                }
                startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = mime
                        putExtra(Intent.EXTRA_TITLE, "sketch.$ext")
                    },
                    req,
                )
            }
            .show()
    }

    @Deprecated("Activity.onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_SAVE -> writeText(uri, currentDocumentText(), "Saved")
            REQ_OPEN -> openDocument(uri)
            REQ_EXPORT_OBJ -> exportObj(uri)
            REQ_EXPORT_MTL -> pendingMtl?.let {
                writeText(uri, it, "Exported materials")
                pendingMtl = null
            }
            REQ_EXPORT_STL -> writeBytes(uri, Export.stlBinary(Export.collect(sketch)), "Exported STL")
            REQ_EXPORT_GLTF -> {
                // glTF is defined in METRES, and a world unit already is one
                val text = Export.gltfSource(Export.collect(sketch, scale = 1.0), "sketch")
                if (text == null) toast("Nothing to export") else writeText(uri, text, "Exported glTF")
            }
        }
    }

    /**
     * An OBJ loses its colours without the `.mtl` beside it, and the picker
     * only ever hands over one destination.
     *
     * So the materials get a picker of their own. Guessing a sibling URI by
     * string surgery is not something the Storage Access Framework promises
     * will work — it happens to on some providers and silently does not on
     * others, which is the worst of both. Two prompts is honest.
     */
    private fun exportObj(uri: Uri) {
        val out = Export.objSource(Export.collect(sketch), "sketch")
        writeText(uri, out.obj, "Exported OBJ")
        pendingMtl = out.mtl ?: return
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "model/mtl"
                putExtra(Intent.EXTRA_TITLE, "sketch.mtl")
            },
            REQ_EXPORT_MTL,
        )
    }

    private fun currentDocumentText(): String {
        docTool.brush = brush
        docTool.color = color
        docTool.sizeMM = sizeMM
        docTool.autoGuide = autoGuide
        docEnv.background = renderer.background
        return Document.toJsonText(sketch, guides, camera, docEnv, docTool, carried)
    }

    private fun writeText(uri: Uri, text: String, said: String) =
        writeBytes(uri, text.toByteArray(Charsets.UTF_8), said)

    private fun writeBytes(uri: Uri, bytes: ByteArray, said: String) {
        io.execute {
            val ok = runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null
            }.getOrDefault(false)
            main.post { toast(if (ok) "$said (${bytes.size / 1024} KB)" else "Could not write the file") }
        }
    }

    private fun openDocument(uri: Uri) {
        io.execute {
            val text = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
            main.post {
                if (text == null) { toast("Could not read the file"); return@post }
                loadDocument(text)
            }
        }
    }

    /**
     * Opening a file replaces the drawing, so it clears the history with it.
     * An undo stack whose steps refer to curves from a document that is no
     * longer open would put back strokes from someone else's sketch.
     */
    private fun loadDocument(text: String) {
        val r = Document.restore(text, sketch, guides, camera)
        if (!r.ok) { toast(r.reason ?: "Not a Plume sketch"); return }
        carried = r.carried
        brush = r.tool.brush
        color = r.tool.color
        sizeMM = clamp(r.tool.sizeMM, Tune.BRUSH_MIN_MM, Tune.BRUSH_MAX_MM)
        autoGuide = r.tool.autoGuide
        renderer.background = r.env.background
        renderer.showGrid = r.env.grid
        renderer.showAxis = r.env.axis
        history.clear()
        syncBrushControls()
        pushCamera()
        pushGuides()
        refreshScene()
        toast("Opened ${sketch.strokes.size} curves")
    }

    // ---- autosave -----------------------------------------------------------------

    private fun autosaveFile() = java.io.File(filesDir, AUTOSAVE)

    /**
     * Debounced, because every stroke would otherwise serialise the whole
     * document while the pen is still moving. Half a second of quiet is long
     * enough to be past the end of a gesture and short enough that almost
     * nothing is at risk.
     */
    private fun scheduleAutosave() {
        if (autosavePending) return
        autosavePending = true
        main.postDelayed({ autosavePending = false; writeAutosave() }, 500)
    }

    private fun writeAutosave() {
        if (sketch.strokes.isEmpty() && guides.active == null) return
        val text = currentDocumentText()
        io.execute {
            runCatching { autosaveFile().writeText(text) }
        }
    }

    private fun restoreAutosave() {
        val f = autosaveFile()
        if (!f.exists()) return
        val text = runCatching { f.readText() }.getOrNull() ?: return
        val r = Document.restore(text, sketch, guides, camera)
        if (!r.ok) return
        carried = r.carried
        brush = r.tool.brush
        color = r.tool.color
        sizeMM = clamp(r.tool.sizeMM, Tune.BRUSH_MIN_MM, Tune.BRUSH_MAX_MM)
        autoGuide = r.tool.autoGuide
        renderer.background = r.env.background
    }

    // ---- keeping the screen in step -------------------------------------------

    private fun setDocument(list: List<Stroke>) {
        sketch.clear()
        for (s in list) sketch.add(s)
    }

    private fun refreshScene() {
        renderer.setStrokes(sketch.strokes)
        refreshControls()
        scheduleAutosave()
        surface.requestRender()
    }

    /** Points moved in place, so the meshes built from them are stale. */
    private fun refreshStrokeMeshes(list: List<Stroke>) {
        for (s in list) renderer.invalidate(s)
        surface.requestRender()
    }

    // ---- camera ---------------------------------------------------------------

    override fun onCamera(dx: Float, dy: Float, dScale: Float, dRotate: Float, fingers: Int) {
        camera.killSpin()
        lastGestureOrbited = fingers < 3
        if (lastGestureOrbited) camera.orbitBy(dx.toDouble(), dy.toDouble())
        else camera.panBy(dx.toDouble(), dy.toDouble())
        if (dScale > 0f) camera.zoomBy(1.0 / dScale)
        if (dRotate != 0f) camera.rollBy(dRotate.toDouble())
        pushCamera()
    }

    override fun onCameraEnd(dx: Float, dy: Float) {
        // only an orbit coasts: a pan that kept sliding would drift the pivot
        // away from whatever you had just lined up
        if (!lastGestureOrbited) return
        camera.addSpin(-dx.toDouble() * Tune.ORBIT_PER_PX, -dy.toDouble() * Tune.ORBIT_PER_PX)
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

    private companion object {
        /** --bg, light and dark: the same two values as the colour resources. */
        private val LIGHT_PAGE = Rgba(0.925, 0.918, 0.953)
        private val DARK_PAGE = Rgba(0.082, 0.086, 0.106)

        const val REQ_SAVE = 1
        const val REQ_OPEN = 2
        const val REQ_EXPORT_OBJ = 3
        const val REQ_EXPORT_STL = 4
        const val REQ_EXPORT_GLTF = 5
        const val REQ_EXPORT_MTL = 6
        const val AUTOSAVE = "autosave.plume.json"
    }
}
