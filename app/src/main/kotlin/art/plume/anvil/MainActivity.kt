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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import art.plume.core.Camera
import art.plume.core.Dedupe
import art.plume.core.Editing
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
 * The shell: one GL surface, the gesture layer, and a tool row.
 *
 * The chrome here is still the FLOOR, not the interface — a phone wants bottom
 * sheets and a radial menu rather than a row of buttons, and that is Phase 6.
 * What it does have to be is COMPLETE: every tool needs a way to reach it, or
 * the tool may as well not be ported.
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

    private enum class Tool { DRAW, ERASE, VACUUM, SMOOTH, SELECT, LASSO, LIQUIFY, GUIDE, FLAT }
    private var tool = Tool.DRAW

    /**
     * FACT (C.1, inferred): with no guide, the first stroke makes one. Kept as
     * a flag because it is an inference rather than documented behaviour.
     */
    private var autoGuide = true

    private val liquifyCfg = Liquify.Settings()

    private val liveBuffer = LiveStroke()
    private var live: Stroke? = null

    /** Current brush settings, the equivalent of the web build's `P.TOOL`. */
    private var brush = "pen"
    private var sizeMM = 14.0
    private var color = Rgba(0.106, 0.110, 0.129)

    private lateinit var undoButton: Button
    private lateinit var redoButton: Button
    private lateinit var sizeLabel: TextView
    private lateinit var guideButton: Button
    private val toolButtons = HashMap<Tool, Button>()

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
     * Android's Back steps OUT of whatever is open rather than leaving: a
     * selection first, then an undo, and only then the app.
     */
    @Deprecated("Activity.onBackPressed")
    override fun onBackPressed() {
        if (sketch.selection.isNotEmpty()) { deselectAll(); return }
        if (history.canUndo()) { history.undo(); refreshScene(); return }
        super.onBackPressed()
    }

    // ---- the floor of an interface ---------------------------------------

    private fun buildControls(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(215, 20, 20, 24))
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        bar.addView(buildToolRow())
        bar.addView(buildActionRow())

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
                    // Liquify's disc is set in pixels, and follows the brush so
                    // there is only one size to think about
                    liquifyCfg.size = camera.worldToPx(sizeMM * MM * 0.5) * 4
                    sizeLabel.text = sizeText()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        bar.addView(sizeLabel)
        bar.addView(size)
        bar.addView(buildSwatches())

        bar.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.BOTTOM }

        // keep the bar clear of the gesture pill and any display cutout
        ViewCompat.setOnApplyWindowInsetsListener(bar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(8) + bars.left, dp(6), dp(8) + bars.right, dp(6) + bars.bottom)
            insets
        }
        return bar
    }

    /** Nine tools do not fit across a phone, so the row scrolls. */
    private fun buildToolRow(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val labels = listOf(
            Tool.DRAW to R.string.tool_draw,
            Tool.ERASE to R.string.tool_erase,
            Tool.VACUUM to R.string.tool_vacuum,
            Tool.SMOOTH to R.string.tool_smooth,
            Tool.SELECT to R.string.tool_select,
            Tool.LASSO to R.string.tool_lasso,
            Tool.LIQUIFY to R.string.tool_liquify,
            Tool.GUIDE to R.string.tool_guide,
            Tool.FLAT to R.string.tool_flat,
        )
        for ((t, res) in labels) {
            val b = Button(this).apply {
                text = getString(res)
                setOnClickListener { setTool(t) }
            }
            toolButtons[t] = b
            row.addView(b)
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun buildActionRow(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        undoButton = Button(this).apply {
            text = getString(R.string.undo)
            setOnClickListener { history.undo(); refreshScene() }
        }
        redoButton = Button(this).apply {
            text = getString(R.string.redo)
            setOnClickListener { history.redo(); refreshScene() }
        }
        guideButton = Button(this).apply {
            text = getString(R.string.close_guide)
            setOnClickListener { closeActiveGuide() }
        }
        row.addView(undoButton)
        row.addView(redoButton)
        row.addView(guideButton)
        row.addView(
            Button(this).apply {
                text = getString(R.string.fill)
                setOnClickListener { fillActiveGuide() }
            },
        )
        row.addView(
            Button(this).apply {
                text = getString(R.string.duplicate)
                setOnClickListener { duplicateSelection() }
            },
        )
        row.addView(
            Button(this).apply {
                text = getString(R.string.mirror)
                setOnClickListener { mirrorSelection() }
            },
        )
        row.addView(
            Button(this).apply {
                text = getString(R.string.delete)
                setOnClickListener { deleteSelection() }
            },
        )
        row.addView(
            Button(this).apply {
                text = getString(R.string.clear)
                setOnClickListener { clearSketch() }
            },
        )
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
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
            row.addView(
                View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(
                            Color.rgb(
                                (ink.r * 255).toInt(), (ink.g * 255).toInt(), (ink.b * 255).toInt(),
                            ),
                        )
                        setStroke(dp(1), Color.argb(120, 255, 255, 255))
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                        marginEnd = dp(8)
                    }
                    setOnClickListener { applyColorToSelectionOrBrush(ink) }
                },
            )
        }
        return row
    }

    private fun setTool(t: Tool) {
        tool = t
        refreshControls()
    }

    private fun sizeText(): String = "Brush ${sizeMM.toInt()} mm"

    private fun refreshControls() {
        undoButton.isEnabled = history.canUndo()
        redoButton.isEnabled = history.canRedo()
        guideButton.isEnabled = guides.active != null
        for ((t, b) in toolButtons) {
            b.alpha = if (t == tool) 1f else 0.55f
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

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
            Tool.DRAW, Tool.GUIDE, Tool.FLAT -> beginStroke(x, y, pressure, tiltAz)

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
        }
        surface.requestRender()
    }

    override fun onDrawMove(x: Float, y: Float, pressure: Float, tiltAz: Float, tiltAlt: Float) {
        dragMoved = true
        when (tool) {
            Tool.DRAW, Tool.GUIDE, Tool.FLAT -> moveStroke(x, y, pressure, tiltAz)
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
        }
        lastPen = Px(x.toDouble(), y.toDouble())
        surface.requestRender()
    }

    override fun onDrawEnd() {
        when (tool) {
            Tool.DRAW, Tool.GUIDE, Tool.FLAT -> endStroke()
            Tool.ERASE, Tool.VACUUM -> commitDocumentChange(
                if (tool == Tool.ERASE) "Erase" else "Vacuum",
            )
            Tool.SMOOTH, Tool.LIQUIFY -> commitPointChange(
                if (tool == Tool.SMOOTH) "Smooth" else "Liquify",
            )
            Tool.SELECT -> endSelect()
            Tool.LASSO -> endLasso()
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
            Tool.FLAT -> Guides.createFlatFromStroke(pts, fwd, right)
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

    // ---- keeping the screen in step -------------------------------------------

    private fun setDocument(list: List<Stroke>) {
        sketch.clear()
        for (s in list) sketch.add(s)
    }

    private fun refreshScene() {
        renderer.setStrokes(sketch.strokes)
        refreshControls()
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
}
