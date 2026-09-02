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
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import art.plume.core.Bounds
import art.plume.core.Camera
import art.plume.core.ColorSpace
import art.plume.core.Dedupe
import art.plume.core.Document
import art.plume.core.DocumentEnv
import art.plume.core.DocumentTool
import art.plume.core.Editing
import art.plume.core.Export
import art.plume.core.Fill
import art.plume.core.Grid
import art.plume.core.Guide
import art.plume.core.GuideEditing
import art.plume.core.GuidePainting
import art.plume.core.GuideScene
import art.plume.core.GuideTransform
import art.plume.core.Guides
import art.plume.core.History
import art.plume.core.Import
import art.plume.core.Mat4
import art.plume.core.Liquify
import art.plume.core.LiveStroke
import art.plume.core.MM
import art.plume.core.Nib
import art.plume.core.Primitives
import art.plume.core.Px
import art.plume.core.Ray
import art.plume.core.Rgba
import art.plume.core.Selection
import art.plume.core.Shapes
import art.plume.core.Sketch
import art.plume.core.Stabilizer
import art.plume.core.Step
import art.plume.core.Stroke
import art.plume.core.Symmetry
import art.plume.core.StrokePoint
import art.plume.core.StyleChange
import art.plume.core.Transform
import art.plume.core.Tune
import art.plume.core.Vec3
import art.plume.core.clamp
import kotlin.math.exp

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

    /** `TOOL.shapeHold` — Hold shape, in the settings modal. */
    private var shapeHoldOn = true

    /**
     * FACT (A.9): curves the guide hides from where you are standing are
     * protected from the eraser and from selection. Isolate is that rule, and
     * it can be turned off.
     */
    private var isolate = true

    /** FACT: a stroke that leaves the guide clamps to its nearest point. */
    private var clampOff = true

    /** FACT (C.2): Stable Stroke is a stabiliser on the input, adjustable. */
    private var stableOn = true
    private var stableAmount = Tune.STABLE_DEFAULT

    /** Radial symmetry: 1 is off. */
    private var radial = 1

    private var hideUi = false

    /**
     * `TOOL.mirror` — null, "x" or "z". A MODE, not a one-shot: every stroke
     * drawn while it is on gets its reflection, which is what makes symmetry
     * useful for drawing rather than just for copying afterwards. The tool
     * pill's button cycles X, Z, off.
     */
    private var mirror: String? = null

    /** FACT (C.3): the pressure toggle lives in the Brush Panel. */
    private var pressureOn = true
    private var pressureTarget = "size"

    // ---- the transform gizmo -----------------------------------------------

    private var joyMode = Transform.Mode.MOVE
    private var joyAxis: Int? = null

    private val liquifyCfg = Liquify.Settings()

    /**
     * The disc's radius as the panel shows it, in screen pixels.
     *
     * Held separately from [Liquify.Settings.size] because that one is
     * recomputed from the brush at the start of every gesture: at the moment
     * the slider is first read the camera may not have been laid out, and a
     * disc sized against a 1x1 viewport is a tool that does nothing at all.
     * A number the user has actually set overrides that.
     */
    private var liquifySize = 120.0
    private var liquifySizeSet = false

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

    /** 0 saved, 1 a write still owed, 2 the last one failed. */
    private var saveState = 0

    /** The material file waiting for its own destination; see [exportObj]. */
    private var pendingMtl: String? = null

    private val liveBuffer = LiveStroke()
    private var live: Stroke? = null

    /** Current brush settings, the equivalent of the web build's `P.TOOL`. */
    private var brush = "pen"
    private var sizeMM = 14.0
    private var color = Rgba(0.106, 0.110, 0.129)

    private var opacity = 1.0

    // ---- staging: a guide being built but not yet committed ---------------

    /**
     * Loft and Primitives both build a guide you keep adjusting before you
     * accept it. It is drawn like any other guide but is not in [guides], so
     * closing it, saving it or painting on it are all impossible until Done.
     */
    private var stagedGuide: Guide? = null
    private val loftSel = ArrayList<Stroke>()
    private var loftTension = 1.0
    private var primKind = "cube"
    private var primSeg = 24
    private var primTaper = 1.0

    // ---- hold-to-shape ----------------------------------------------------

    /** The stroke's screen points, which is where a shape is fitted. */
    private val liveScreen = ArrayList<Px>()
    private var shapeHold: Runnable? = null
    private var holdAnchor: Px? = null
    private var adjusting: Shapes.Shape? = null
    private var adjustAnchor = Px(0.0, 0.0)

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

    /** A press-hold picked a guide, so the release is not also a tap select. */
    private var holdConsumedTap = false

    /** Everything one joystick drag did to a guide, for a single undo step. */
    private var guideAccum: Mat4? = null

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
        docEnv.background = if (tokens.dark) DARK_PAGE else LIGHT_PAGE
        renderer.setEnvironment(docEnv)
        renderer.setDensity(resources.displayMetrics.density)
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
        refreshGroups()
        refreshResources()
        pushLiquify()
        /*
         * FIRST RUN ONLY, and only on an empty page: someone whose autosave
         * restored a drawing has plainly been here before, whatever the flag
         * says.
         */
        if (!getPreferences(MODE_PRIVATE).getBoolean(PREF_WALKED, false) &&
            sketch.strokes.isEmpty()
        ) {
            startWalk(0)
        }
        pushSettings()
        refreshControls()
        syncBrushControls()
        pushStrokes()
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
        if (chrome.walkShowing()) { endWalk(); return }
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
        chrome.onOpacity = { o -> applyOpacityToSelectionOrBrush(o) }
        chrome.onBrush = { b -> brush = b }
        chrome.onColor = { argb -> applyColorToSelectionOrBrush(rgbaOf(argb)) }
        /* The hex field and the wheel serve whichever well the card is pointed
           at, so they go back through the card rather than straight at the
           brush — otherwise typing a hex while editing the background would
           recolour the ink. */
        chrome.onHex = { text ->
            val c = ColorSpace.parseHex(text)
            if (c == null) toast(getString(R.string.bad_hex)) else chrome.applyCardColor(argbOf(c))
        }
        chrome.onWheel = { c -> chrome.applyCardColor(argbOf(c)) }
        chrome.onEyedrop = {
            chrome.closePopovers()
            setTool(Tool.EYEDROP)
            toast(getString(R.string.eyedrop_hint))
        }
        chrome.onGroupPick = { id -> sketch.setActiveGroup(id); refreshGroups() }
        chrome.onGroupRename = { id, name ->
            sketch.groupById(id)?.let { g ->
                val was = g.name
                history.run(
                    Step(
                        "Rename group",
                        onRedo = { g.name = name; refreshGroups() },
                        onUndo = { g.name = was; refreshGroups() },
                    ),
                )
            }
        }
        chrome.onGroupSelect = { id ->
            val before = sketch.selection
            sketch.selectOnly(sketch.membersOf(id))
            commitSelectionChange("Select group", before)
        }
        chrome.onGroupAssign = { id -> assignSelectionTo(id) }
        chrome.onGroupVisible = { id, visible -> setGroupVisible(id, visible) }
        chrome.onGroupNew = { newGroup() }
        chrome.onGroupDuplicate = { duplicateActiveGroup() }
        chrome.onGroupDelete = { deleteActiveGroup() }
        chrome.onSelectAll = {
            val before = sketch.selection
            sketch.selectOnly(sketch.editable())
            commitSelectionChange("Select all", before)
        }
        chrome.onKeypad = { which, v ->
            when (which) {
                "size" -> { sizeMM = clamp(v, Tune.BRUSH_MIN_MM, Tune.BRUSH_MAX_MM) }
                "opacity" -> applyOpacityToSelectionOrBrush(clamp(v / 100.0, 0.05, 1.0))
            }
            syncBrushControls()
            scheduleAutosave()
        }
        chrome.onWalkNext = {
            if (walkStep >= WALK.size - 1) endWalk() else startWalk(walkStep + 1)
        }
        chrome.onWalkSkip = { endWalk() }
        chrome.onResourceActivate = { id -> activateResource(id) }
        chrome.onResourceVisible = { id, visible ->
            guides.byId(id)?.let { g -> guides.setResourceVisible(g, visible) }
            pushGuides(); refreshResources()
        }
        chrome.onResourceDelete = { id -> deleteResource(id) }
        chrome.onImportReference = {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                },
                REQ_IMPORT,
            )
        }
        chrome.onTransformMode = { m -> joyMode = m; pushTransform() }
        chrome.onTransformGrab = { axis ->
            joyAxis = axis
            /*
             * The whole drag is ONE history step. Snapshotting on every sample
             * would put a hundred entries in the stack for one gesture, and
             * undoing a nudge would take a hundred taps.
             */
            /*
             * The same drag snapshot every other point-moving tool uses, so a
             * gizmo drag lands in history the same shape as a Smooth or a
             * Liquify: one step, positions before and after.
             */
            val sel = sketch.selection
            if (transformGuide == null && sel.isNotEmpty()) {
                dragTargets = sel
                dragPositions = Editing.snapshot(sel)
                dragMoved = false
            }
            dragMoved = false
            pushTransform()
        }
        chrome.onTransformDrag = { axis, dx, dy, sweep, strip ->
            stepTransform(axis, dx.toDouble(), dy.toDouble(), sweep, strip)
        }
        chrome.onTransformEnd = {
            joyAxis = null
            commitGuideTransform()
            commitPointChange("Transform")
            clearDrag()
            pushTransform()
        }
        chrome.onPressure = { togglePressure() }
        chrome.onPressureTarget = { target ->
            pressureTarget = target
            chrome.setPressure(pressureOn, pressureTarget)
            scheduleAutosave()
        }
        chrome.onInput = { t -> flipInput(t) }
        chrome.onStable = { v -> stableAmount = v; stabilizer.amount = v; scheduleAutosave() }
        chrome.onRadial = { n ->
            radial = n
            pushFold()
            chrome.setSymmetry(mirror != null || radial > 1)
            scheduleAutosave()
        }
        chrome.onFocal = { mm ->
            camera.focal = clamp(mm, Tune.FOCAL_MIN, Tune.FOCAL_MAX)
            camera.apply(); pushCamera(); refreshControls()
        }
        chrome.onView = { i ->
            val v = Camera.ORTHO_VIEWS[i]
            camera.applyOrthoView(v)
            pushCamera()
            toast(getString(R.string.view_snapped, v.name))
        }
        chrome.onLiquifyMode = { m ->
            liquifyCfg.mode = when (m) {
                "pinch" -> Liquify.Mode.PINCH
                "comb" -> Liquify.Mode.COMB
                else -> Liquify.Mode.PUSH
            }
            pushLiquify()
        }
        chrome.onLiquifyValue = { which, v ->
            when (which) {
                "size" -> { liquifySize = v; liquifySizeSet = true }
                "range" -> liquifyCfg.range = v
                else -> liquifyCfg.strength = v
            }
        }
        /*
         * Apply just puts the tool away. Liquify has already changed the
         * curves — each drag is its own history step — so there is nothing
         * held back waiting to be committed, and a button that pretended
         * otherwise would suggest the work could still be cancelled.
         */
        chrome.onLiquifyApply = { setTool(Tool.DRAW); toast(getString(R.string.liquify_done)) }
        chrome.onLiquifyClose = { setTool(Tool.DRAW) }
        chrome.onStageValue = { which, v -> stageValue(which, v) }
        chrome.onPrimKind = { k -> primKind = k; previewPrimitive() }
        chrome.onStageDone = { commitStaging() }
        chrome.onStageCancel = { cancelStaging(); setTool(Tool.DRAW) }
        chrome.onEnv = { toggle -> flip(toggle) }
        chrome.onLight = { az, alt ->
            docEnv.light.az = az
            docEnv.light.alt = alt
            pushEnvironment()
        }
        /*
         * The intensity, ambient, f-stop, grain and block-size readouts are
         * dragged, so the panel owns the number while the finger is down and
         * hands it back here. Reading it out of the chrome rather than passing
         * it through the callback keeps one copy of each value.
         */
        chrome.onLightLevels = { chrome.readInto(docEnv); pushEnvironment() }
        chrome.onFx = { chrome.readInto(docEnv); pushEnvironment() }
        /* `P.ENV.bg.set(this.value); P.applyEnv();` — the background is not
           just the clear colour. The fog takes its colour from it, the ground
           grid picks its two line shades off its luminance, and the chrome
           theme follows it unless it has been overridden, so all of that has
           to be pushed rather than only the clear. */
        chrome.onBackground = { argb ->
            docEnv.background = rgbaOf(argb)
            pushEnvironment()
            scheduleAutosave()      // it travels in the file
        }
        chrome.onLightColour = { argb ->
            docEnv.light.color = rgbaOf(argb)
            pushEnvironment()
            scheduleAutosave()
        }
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
        /*
         * The BOUNDING SPHERE's radius, not the longest side. Framing to the
         * longest side leaves a sketch that is long on the diagonal poking out
         * of the corners of the view — the web build asks the box for its
         * sphere for exactly this reason, and now that core has a Bounds so
         * can this.
         */
        val b = Bounds.of(sketch)
        if (!b.empty) {
            b.centre(camera.pivot)
            val halfFov = camera.fovFromFocal(camera.focal) * Math.PI / 360.0
            camera.radius = clamp(
                maxOf(b.radius(), 0.05) / Math.tan(halfFov) * 1.15, 1.0, 200.0,
            )
        } else {
            camera.pivot.set(0.0, 0.0, 0.0)
        }
        camera.apply()
    }

    /**
     * `Tools.sample`. The two samplers differ in how much they take: the
     * INJECTOR picks up the whole brush — type, size, opacity and ink — while
     * the EYEDROPPER takes only the colour, so you can recolour without losing
     * the nib you had set up.
     */
    private fun sampleAt(x: Double, y: Double, whole: Boolean) {
        val hit = Selection.hitTest(sketch, camera, x, y, mask())
        if (hit == null) { toast(getString(R.string.nothing_under_that)); return }
        color = hit.color
        if (whole) {
            brush = hit.brush
            sizeMM = hit.baseRadius * 2.0 / MM
            opacity = hit.opacity
            if (hit.pressureTarget != "none") pressureTarget = hit.pressureTarget
        }
        syncBrushControls()
        toast(
            if (whole) getString(R.string.sampled, hit.brush)
            else getString(R.string.sampled_colour),
        )
        /* a sampler is a one-shot: it hands you back the tool you were using */
        setTool(Tool.DRAW)
    }

    /** The eye on the guide bar: keep this surface in the Resource tab. */
    private fun saveActiveGuide() {
        if (guides.save() == null) { toast(getString(R.string.no_guide)); return }
        pushGuides()
        refreshResources()
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
        Action.HELP -> startWalk(0)
        Action.UNDO -> { history.undo(); refreshScene() }
        Action.REDO -> { history.redo(); refreshScene() }
        Action.MIRROR -> cycleMirror()
        Action.STAGE -> chrome.toggleStage()
        Action.GUIDE_BEND -> { setTool(Tool.BEND); toast(getString(R.string.bend_hint)) }
        Action.GUIDE_SAVE -> saveActiveGuide()
        Action.GUIDE_CLOSE -> closeActiveGuide()
        Action.DUPLICATE -> duplicateSelection()
        Action.DUPLICATE_MIRROR -> mirrorSelection()
        Action.LIQUIFY -> setTool(Tool.LIQUIFY)
        Action.DELETE -> deleteSelection()
        Action.PRESSURE -> togglePressure()
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
        /* leaving a staging tool throws away what it was building */
        if (tool != t && (tool == Tool.LOFT || tool == Tool.PRIM)) cancelStaging()

        when (t) {
            Tool.BEND -> if (guides.active == null) {
                toast(getString(R.string.bend_needs_guide)); return
            }
            /*
             * LOFT TAKES THE SELECTION YOU ALREADY MADE. It used to be the
             * other way round in the web build — choose Loft, then pick curves
             * with it — which meant the selection in hand was thrown away at
             * the door and made again with a different tool.
             */
            Tool.LOFT -> {
                loftSel.clear()
                loftSel.addAll(sketch.selection)
                if (loftSel.size < 2) {
                    loftSel.clear()
                    toast(getString(R.string.loft_needs_two)); return
                }
                previewLoft()
            }
            Tool.PRIM -> previewPrimitive()
            else -> {}
        }
        tool = t
        /* Select is a tap tool, so its press carries a hold — holding it picks
           the guide underneath. Every drag tool's press IS the drag. */
        gestures.holdWhileDrawing = t == Tool.SELECT
        /* leaving Select puts down whatever guide it had hold of, or the
           joystick would keep driving a surface you can no longer see selected */
        if (t != Tool.SELECT) guides.active?.selected = false
        chrome.setTool(t)
        refreshControls()
    }

    // ---- staging ----------------------------------------------------------

    private fun previewLoft() {
        stagedGuide = GuideEditing.loft(loftSel, loftTension)
        pushGuides()
        showStaging()
    }

    private fun previewPrimitive() {
        /*
         * A primitive is rebuilt from scratch on every slider move rather than
         * deformed, because segments and taper change its topology. The web
         * build carries the staged object's matrix across; nothing here has
         * moved it yet, so there is nothing to carry.
         */
        stagedGuide = Primitives.create(primKind, primSeg, primTaper)
        pushGuides()
        showStaging()
    }

    private fun showStaging() {
        chrome.setStaging(
            when (tool) {
                Tool.LOFT -> Chrome.Staging(
                    getString(R.string.tension),
                    loftTension,
                    String.format("%.2f", loftTension),
                )
                Tool.PRIM -> Chrome.Staging(
                    getString(R.string.segments),
                    (primSeg - 3) / 45.0,
                    primSeg.toString(),
                    /* only a tube tapers; the others have nothing to taper */
                    secondLabel = if (primKind == "tube") getString(R.string.taper) else null,
                    value2 = primTaper,
                    readout2 = String.format("%.2f", primTaper),
                    kind = primKind,
                )
                else -> null
            },
        )
    }

    private fun stageValue(which: Int, v: Double) {
        when (tool) {
            Tool.LOFT -> { loftTension = v; previewLoft() }
            Tool.PRIM -> {
                if (which == 0) primSeg = (3 + v * 45).toInt().coerceAtLeast(3)
                else primTaper = v
                previewPrimitive()
            }
            else -> {}
        }
    }

    private fun cancelStaging() {
        if (stagedGuide == null && loftSel.isEmpty()) return
        stagedGuide = null
        for (st in loftSel) sketch.setSelected(st, false)
        loftSel.clear()
        chrome.setStaging(null)
        pushGuides()
        refreshScene()
    }

    /** Done: the staged guide becomes the active one, in one history step. */
    private fun commitStaging() {
        val g = stagedGuide ?: return
        val previous = guides.active
        stagedGuide = null
        for (st in loftSel) sketch.setSelected(st, false)
        loftSel.clear()
        chrome.setStaging(null)
        val label = if (tool == Tool.LOFT) "Loft" else "Primitive"
        history.run(
            Step(
                label,
                onRedo = { guides.setActive(g); pushGuides(); refreshScene() },
                onUndo = { guides.setActive(previous); pushGuides(); refreshScene() },
            ),
        )
        setTool(Tool.DRAW)
        toast(getString(if (label == "Loft") R.string.loft_made else R.string.prim_made))
    }

    /** Tapping a curve under Loft adds or removes it from the set. */
    private fun loftPick(x: Double, y: Double) {
        val hit = Selection.hitTest(sketch, camera, x, y, mask()) ?: return
        if (loftSel.remove(hit)) sketch.setSelected(hit, false)
        else { loftSel.add(hit); sketch.setSelected(hit, true) }
        if (loftSel.size >= 2) previewLoft() else { stagedGuide = null; pushGuides() }
        refreshScene()
    }

    /** After a load the brush came from the file, so the rail has to follow. */
    private fun syncBrushControls() {
        chrome.setSize(sizeMM)
        chrome.setOpacityValue(opacity)
        chrome.setBrush(brush)
        chrome.setColor(argbOf(color))
        chrome.setPressure(pressureOn, pressureTarget)
    }

    /**
     * `P.applyEnv` — the environment reaching the renderer, in one call.
     *
     * The light and the effects belong to the SKETCH, so a load brings them
     * with it. Copied into this activity's own [docEnv] rather than held by
     * reference, because [docEnv] is what a save writes back and a restore
     * hands back a fresh object each time.
     */
    private fun applyEnvironment(env: DocumentEnv) {
        docEnv.background = env.background
        docEnv.grid = env.grid
        docEnv.axis = env.axis
        docEnv.fog = env.fog
        docEnv.shaded = env.shaded
        docEnv.render = env.render
        docEnv.groundShadow = env.groundShadow
        docEnv.light.copyFrom(env.light)
        docEnv.fx.copyFrom(env.fx)
        pushEnvironment()
    }

    /**
     * A Scene switch thrown.
     *
     * Not undoable, deliberately. The environment is how you are LOOKING at
     * the sketch rather than part of it — turning the grid off and then
     * undoing twice should not put the grid back and leave your last stroke
     * erased. The web build treats them the same way. It does travel in the
     * file, which is a different question.
     */
    private fun flip(toggle: EnvToggle) {
        when (toggle) {
            EnvToggle.GRID -> docEnv.grid = !docEnv.grid
            EnvToggle.AXIS -> docEnv.axis = !docEnv.axis
            EnvToggle.FOG -> docEnv.fog = !docEnv.fog
            EnvToggle.SHADED -> docEnv.shaded = !docEnv.shaded
            EnvToggle.RENDER -> docEnv.render = !docEnv.render
            EnvToggle.SHADOW -> docEnv.groundShadow = !docEnv.groundShadow
            EnvToggle.TOON -> docEnv.light.toon = !docEnv.light.toon
            EnvToggle.DOF -> docEnv.fx.dofOn = !docEnv.fx.dofOn
            EnvToggle.GRAIN -> docEnv.fx.grainOn = !docEnv.fx.grainOn
            EnvToggle.PIXEL -> docEnv.fx.pixelOn = !docEnv.fx.pixelOn
        }
        pushEnvironment()
        scheduleAutosave()
    }

    /** Push [docEnv] at the renderer and at the panel showing it. */
    private fun pushEnvironment() {
        renderer.setEnvironment(docEnv)
        chrome.setEnvironment(docEnv)
        surface.requestRender()
    }

    // ---- groups (C.8) ------------------------------------------------------

    /** The Curves tab, rebuilt from the sketch. */
    /**
     * A settings switch. Like the environment, none of these is undoable: they
     * are how you are working rather than part of the drawing, and undoing
     * twice should not turn the stabiliser back on and lose your last stroke.
     */
    private fun flipInput(which: InputToggle) {
        when (which) {
            InputToggle.FINGER -> {
                gestures.fingerDraws = !gestures.fingerDraws
                /* their setting, not our guess about their hardware: a pen
                   landing later does not get to change it back */
                gestures.fingerDrawsIsOurs = false
                /* IT IS A MODE, SO IT SAYS WHICH ONE IT IS NOW. The whole
                   point of promoting it out of the settings list is that it
                   gets flipped often, and a mode you flip often has to
                   confirm itself — otherwise the only way to find out which
                   way it went is to put a finger down and see whether you
                   drew a line you did not want. */
                toast(
                    getString(
                        if (gestures.fingerDraws) R.string.finger_on else R.string.finger_off,
                    ),
                )
            }
            InputToggle.AUTO_GUIDE -> autoGuide = !autoGuide
            InputToggle.ISOLATE -> isolate = !isolate
            InputToggle.CLAMP -> clampOff = !clampOff
            InputToggle.HOLD_SHAPE -> shapeHoldOn = !shapeHoldOn
            InputToggle.STABLE -> {
                stableOn = !stableOn
                stabilizer.amount = if (stableOn) stableAmount else 0.0
            }
            InputToggle.ORTHO -> {
                camera.ortho = !camera.ortho
                camera.apply(); pushCamera()
                toast(
                    getString(
                        if (camera.ortho) R.string.projection_ortho
                        else R.string.projection_persp,
                    ),
                )
            }
            /*
             * The theme follows the page in the web build (applyEnv flips
             * body.dark), and follows the system here — a resource qualifier
             * is what Android gives you. Toggling it flips the PAGE, which the
             * chrome then follows, so the control means the same thing.
             */
            InputToggle.THEME -> {
                docEnv.background =
                    if (Grid.luminance(docEnv.background) > 0.5) DARK_PAGE else LIGHT_PAGE
                pushEnvironment()
                scheduleAutosave()
            }
            InputToggle.DIAG -> { diagOn = !diagOn; pushDiag() }
            InputToggle.HIDE_UI -> {
                hideUi = !hideUi
                chrome.root.visibility = if (hideUi) View.GONE else View.VISIBLE
                if (hideUi) { chrome.setMenu(false); toast(getString(R.string.hideui_hint)) }
            }
        }
        pushSettings()
        scheduleAutosave()
    }

    private fun togglePressure() {
        pressureOn = !pressureOn
        chrome.setPressure(pressureOn, pressureTarget)
        toast(getString(if (pressureOn) R.string.pressure_on else R.string.pressure_off))
        scheduleAutosave()
    }

    private fun pushSettings() {
        chrome.setSettings(
            gestures.fingerDraws, autoGuide, isolate, clampOff, shapeHoldOn,
            stableOn, stableAmount, radial, camera.focal, camera.ortho, hideUi, diagOn,
            getString(R.string.autosaves_here),
        )
    }

    /**
     * One step of a gizmo drag.
     *
     * The centre is the selection's own, recomputed each sample: a rotation
     * about a stale centre drifts the selection sideways as it turns.
     */
    private fun stepTransform(axis: Int?, dx: Double, dy: Double, sweep: Double, strip: Boolean) {
        val guide = transformGuide
        val targets = if (guide == null) dragTargets ?: return else null
        if (targets != null && targets.isEmpty()) return
        dragMoved = true

        val centre = Bounds()
        if (guide != null) guide.surface?.let { srf ->
            val p = srf.positions
            var i = 0
            while (i + 2 < p.size) {
                centre.add(Vec3(p[i].toDouble(), p[i + 1].toDouble(), p[i + 2].toDouble()))
                i += 3
            }
        } else {
            for (s in targets!!) for (p in s.pts) centre.add(p.p)
        }
        if (centre.empty) return
        val c = centre.centre()

        val m = if (axis == null) {
            Transform.free(camera, joyMode, dx, dy, c, strip)
        } else {
            val a = Transform.AXES[axis]
            val screen = Transform.axisOnScreen(camera, a, c) ?: return
            Transform.alongAxis(joyMode, a, screen, c, dx, dy, sweep)
        }

        if (guide != null) {
            GuideTransform.apply(guide, m)
            /* ONE HISTORY STEP FOR THE WHOLE DRAG, accumulated as a matrix.
               A guide has no point list to snapshot the way a selection does,
               so what is remembered is the transform itself — replayed to
               redo, inverted to undo. That is the web build's model too. */
            guideAccum = Mat4.multiply(m, guideAccum ?: Mat4().identity(), Mat4())
            pushGuides()
        } else {
            Selection.transform(targets!!, m)
            refreshStrokeMeshes(targets)
        }
        surface.requestRender()
    }

    /** Close a guide drag into one undoable step. */
    private fun commitGuideTransform() {
        val m = guideAccum ?: return
        guideAccum = null
        val g = transformGuide ?: return
        val inv = Mat4()
        if (!Mat4.invert(m, inv)) return        // a degenerate drag is not a step
        history.push(
            Step(
                "Move guide",
                onRedo = { GuideTransform.apply(g, m); pushGuides(); surface.requestRender() },
                onUndo = { GuideTransform.apply(g, inv); pushGuides(); surface.requestRender() },
            ),
        )
    }

    /**
     * WHAT THE JOYSTICK IS DRIVING.
     *
     * `Tools.transformTarget`: a guide you picked by holding on it outranks a
     * curve selection, because picking one puts the other down and the guide
     * is the more specific thing to have asked for.
     */
    private val transformGuide: Guide?
        get() = guides.active?.takeIf { it.selected }

    /** The fold is sized to the work, so it moves when the work does. */
    private fun pushFold() {
        renderer.setFold(Symmetry.fold(Bounds.of(sketch), mirror, radial))
        surface.requestRender()
    }

    private fun pushTransform() {
        val guide = transformGuide
        val sel = sketch.selection
        val nothing = guide == null && sel.isEmpty()

        val label = when {
            nothing -> getString(R.string.joy_nothing)
            joyAxis != null -> getString(
                R.string.joy_axis,
                getString(
                    when (joyMode) {
                        Transform.Mode.MOVE -> R.string.joy_move
                        Transform.Mode.ROTATE -> R.string.joy_turn
                        Transform.Mode.SCALE -> R.string.joy_size
                    },
                ),
                "XYZ"[joyAxis!!].toString(),
            )
            guide != null -> guide.name
            else -> getString(R.string.joy_count, sel.size)
        }
        /*
         * An axis pointing nearly at the camera has no usable screen
         * direction, so its arc is dimmed rather than left to send the
         * selection to the horizon on a one-pixel drag.
         */
        val usable = if (nothing) {
            listOf(false, false, false)
        } else {
            val b = Bounds()
            if (guide != null) {
                guide.surface?.let { srf ->
                    val p = srf.positions
                    var i = 0
                    while (i + 2 < p.size) {
                        b.add(Vec3(p[i].toDouble(), p[i + 1].toDouble(), p[i + 2].toDouble()))
                        i += 3
                    }
                }
            } else {
                for (s in sel) for (p in s.pts) b.add(p.p)
            }
            val c = if (b.empty) Vec3() else b.centre()
            Transform.AXES.map { Transform.axisOnScreen(camera, it, c) != null }
        }
        chrome.setTransform(joyMode, label, usable)
    }

    private fun pushLiquify() {
        chrome.setLiquify(
            liquifyCfg.mode.name.lowercase(),
            if (liquifySizeSet) liquifySize else liquifyCfg.size,
            liquifyCfg.range,
            liquifyCfg.strength,
        )
    }

    // ---- the walkthrough ---------------------------------------------------

    private var walkStep = -1

    private fun startWalk(i: Int) {
        walkStep = i
        val (title, body) = WALK[i]
        chrome.setWalk(
            i, WALK.size, getString(title),
            /*
             * Step 2 describes a gesture that differs between pen and finger
             * mode: with finger drawing on, one finger is busy and orbiting
             * takes two.
             */
            getString(
                if (i == 1 && !gestures.fingerDraws) R.string.walk_b2_pen else body,
            ),
            last = i == WALK.size - 1,
        )
    }

    private fun endWalk() {
        walkStep = -1
        chrome.setWalk(null, WALK.size, "", "", false)
        getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_WALKED, true).apply()
    }

    // ---- saved guides and references ---------------------------------------

    /** The Import tab, rebuilt from the guide scene. */
    private fun refreshResources() {
        chrome.setResources(
            guides.resources.map { g ->
                Chrome.ResourceRow(
                    g.id,
                    /*
                     * A reference carries the file's own name, so numbering it
                     * reads as nonsense — only the generic ones need telling
                     * apart.
                     */
                    if (GENERIC_NAMES.contains(g.name)) {
                        "${g.name} ${guides.indexOf(g) + 1}"
                    } else {
                        g.name
                    },
                    g.kind.name.lowercase(),
                    g.visible,
                    g === guides.active,
                )
            },
        )
    }

    private fun activateResource(id: Int) {
        val g = guides.byId(id) ?: return
        val previous = guides.active
        history.run(
            Step(
                "Activate guide",
                onRedo = { guides.setActive(g); pushGuides(); refreshResources() },
                onUndo = { guides.setActive(previous); pushGuides(); refreshResources() },
            ),
        )
        toast(getString(R.string.guide_activated))
    }

    /**
     * Throwing a reference away takes the PICTURE only — anything traced onto
     * it keeps its own curves — which is why it is one tap and undoable rather
     * than a dialog.
     */
    private fun deleteResource(id: Int) {
        val g = guides.byId(id) ?: return
        val at = guides.indexOf(g)
        val wasActive = guides.active === g
        val name = g.name
        history.run(
            Step(
                "Delete reference",
                onRedo = {
                    if (wasActive) guides.setActive(null)
                    guides.remove(g); pushGuides(); refreshResources()
                },
                onUndo = {
                    guides.restore(g, at, wasActive); pushGuides(); refreshResources()
                },
            ),
        )
        toast(getString(R.string.res_deleted, name))
    }

    /** An OBJ or STL becomes a guide you can paint on but not fill. */
    private fun importReference(uri: Uri) {
        io.execute {
            val bytes = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            val name = displayName(uri)
            val surface = when {
                bytes == null -> null
                Import.looksBinarySTL(bytes) -> Import.parseSTL(bytes)
                else -> {
                    val text = bytes.toString(Charsets.UTF_8)
                    Import.parseOBJ(text) ?: Import.parseSTL(bytes)
                }
            }
            main.post {
                if (surface == null) { toast(getString(R.string.import_failed)); return@post }
                val g = Import.asGuide(surface, name)
                val previous = guides.active
                history.run(
                    Step(
                        "Import reference",
                        onRedo = {
                            guides.save(g); guides.setActive(g)
                            pushGuides(); refreshResources()
                        },
                        onUndo = {
                            guides.remove(g); guides.setActive(previous)
                            pushGuides(); refreshResources()
                        },
                    ),
                )
                toast(getString(R.string.imported, name))
            }
        }
    }

    /** The file's own name, so a reference is not called "Model 3". */
    private fun displayName(uri: Uri): String {
        val fallback = uri.lastPathSegment?.substringAfterLast('/') ?: "Model"
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        }.getOrNull() ?: fallback
    }

    private fun refreshGroups() {
        sketch.ensureGroup()
        chrome.setGroups(
            sketch.groups.map { g ->
                Chrome.GroupRow(
                    g.id, g.name, sketch.membersOf(g.id).size, g.visible,
                    g.id == sketch.activeGroup,
                )
            },
        )
        refreshControls()
    }

    private fun newGroup() {
        val g = sketch.newGroup(getString(R.string.group_new, sketch.groups.size + 1))
        val at = sketch.indexOfGroup(g)
        val previous = sketch.activeGroup
        /*
         * THE STEP HAS TO OWN THE GROUP, NOT JUST THE ACTIVE ONE.
         *
         * This used to create the group here and let the step move the active
         * marker only — so undoing "New group" put you back in the group you
         * came from and LEFT THE NEW ONE SITTING THERE, empty. Two taps of new
         * and two of undo, and the panel had two groups nobody asked for.
         *
         * restoreGroup puts the same object back, ids and all, so the curves
         * a redo re-points at it still find it.
         */
        history.run(
            Step(
                "New group",
                onRedo = {
                    sketch.restoreGroup(g, at)
                    sketch.setActiveGroup(g.id); refreshGroups()
                },
                onUndo = {
                    sketch.deleteGroup(g)
                    sketch.setActiveGroup(previous); refreshGroups()
                },
            ),
        )
    }

    /**
     * Hiding a group hides its curves, so the meshes have to be rebuilt: the
     * renderer draws what it is given rather than asking whether each stroke
     * is visible.
     */
    private fun setGroupVisible(id: Int, visible: Boolean) {
        val g = sketch.groupById(id) ?: return
        history.run(
            Step(
                if (visible) "Show group" else "Hide group",
                onRedo = { g.visible = visible; refreshScene(); refreshGroups() },
                onUndo = { g.visible = !visible; refreshScene(); refreshGroups() },
            ),
        )
    }

    private fun assignSelectionTo(id: Int) {
        val sel = sketch.selection
        if (sel.isEmpty()) { toast(getString(R.string.nothing_selected)); return }
        val g = sketch.groupById(id) ?: return
        val was = sel.map { it.group }
        history.run(
            Step(
                "Move to group",
                onRedo = { for (s in sel) sketch.assign(s, g); refreshScene(); refreshGroups() },
                onUndo = {
                    for (i in sel.indices) sel[i].group = was[i]
                    refreshScene(); refreshGroups()
                },
            ),
        )
        toast(getString(R.string.moved_to_group, sel.size, g.name))
    }

    private fun duplicateActiveGroup() {
        val g = sketch.groupById(sketch.activeGroup) ?: return
        val (copy, copies) = sketch.duplicateGroup(g)
        val at = sketch.indexOfGroup(copy)
        val previous = sketch.activeGroup
        /* the copy's GROUP is part of the step too, for the reason above: undo
           took the copied curves away and left the empty copy behind */
        history.run(
            Step(
                "Duplicate group", cost = copies.sumOf { it.pts.size },
                onRedo = {
                    sketch.restoreGroup(copy, at)
                    for (c in copies) if (sketch.indexOf(c) < 0) sketch.add(c)
                    sketch.setActiveGroup(copy.id); refreshScene(); refreshGroups()
                },
                onUndo = {
                    for (c in copies) sketch.remove(c)
                    sketch.deleteGroup(copy)
                    sketch.setActiveGroup(previous); refreshScene(); refreshGroups()
                },
            ),
        )
        toast(getString(R.string.group_duplicated, copies.size))
    }

    /**
     * DELETING A GROUP TAKES ITS CURVES WITH IT, as a layer does.
     *
     * This used to free them instead — the group went, the work stayed — on
     * the argument that removing a folder should not remove what is in it and
     * that no undo prompt made the other reading safe. The reference does the
     * opposite and says why: "undo puts both back — which is the reason this
     * is not behind a confirmation dialog." It is one tap.
     *
     * Freeing them was also worse than it looked. `ensureGroup` adopts
     * anything ungrouped on the next refresh, so deleting a group did not
     * leave its curves loose — it quietly moved every one of them into another
     * group, which is neither of the two things anyone expected.
     */
    private fun deleteActiveGroup() {
        val g = sketch.groupById(sketch.activeGroup) ?: return
        if (sketch.groups.size <= 1) { toast(getString(R.string.last_group)); return }
        val members = sketch.membersOf(g.id)
        val at = sketch.indexOfGroup(g)          // put it back where it was
        val atStroke = members.map { sketch.indexOf(it) }
        val previous = sketch.activeGroup
        history.run(
            Step(
                "Delete group", cost = members.sumOf { it.pts.size },
                onRedo = {
                    for (s in members) sketch.remove(s)
                    sketch.deleteGroup(g)
                    sketch.setActiveGroup(null); refreshScene(); refreshGroups()
                },
                onUndo = {
                    /* at the row it was on, not at the bottom: the list is a
                       stack you can read, and an undo that moved a group to
                       the end would not read as an undo */
                    sketch.restoreGroup(g, at)
                    /* and each curve at the depth it was drawn at, since draw
                       order is what decides who is on top */
                    for (i in members.indices) sketch.addAt(atStroke[i], members[i])
                    sketch.setActiveGroup(previous); refreshScene(); refreshGroups()
                },
            ),
        )
        toast(getString(R.string.group_deleted, members.size))
    }

    /** `UI.refresh` — push the model back at the chrome and let it re-derive. */
    private fun refreshControls() {
        chrome.setHistory(history.canUndo(), history.canRedo())
        val g = guides.active
        chrome.setGuide(g != null, g?.name ?: "", g?.opacity ?: 0.42)
        chrome.setSelection(sketch.selection.size)
        pushTransform()
        chrome.setViewInfo(
            camera.focal.toInt(), !camera.ortho, sketch.strokes.size, camera.pinned,
        )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = chrome.toast(msg)

    // ---- the guide mask, which every tool honours -------------------------

    /**
     * FACT (A.9): a point the active guide hides from where you are standing is
     * protected from the eraser and from selection alike.
     */
    private fun mask(): (Vec3) -> Boolean {
        if (!isolate) return Editing.NO_MASK
        val g = guides.active ?: return Editing.NO_MASK
        val eye = camera.eye.copy()
        return { p -> GuidePainting.isMasked(g, eye, p) }
    }

    // ---- one gesture, one history step ------------------------------------

    override fun onDrawBegin(x: Float, y: Float, pressure: Float, tiltAz: Float, tiltAlt: Float) {
        if (diagOn) {
            /*
             * A stylus that reports no pressure, or reports it on an axis
             * nothing reads, looks exactly like a bug in the brush. This says
             * which it is.
             */
            diagValues["type"] = if (pressure != 0.5f) "stylus" else "finger"
            diagValues["pressure"] = String.format("%.2f", pressure)
            diagValues["tilt"] = String.format("%.2f / %.2f", tiltAz, tiltAlt)
            pushDiag()
        }
        camera.killSpin()
        dragMoved = false
        lastPen = Px(x.toDouble(), y.toDouble())

        when (tool) {
            Tool.DRAW, Tool.SHAPE, Tool.GUIDE, Tool.FLATGUIDE ->
                beginStroke(x, y, pressure)

            /*
             * The plane a bend stroke is drawn on: camera-facing, through the
             * ORANGE ANCHOR for a swept guide and through the centre of the
             * mesh for a deform. Drawn on the default plane instead, the new
             * path would land at the pivot's depth and the guide would jump
             * away from where it was.
             */
            Tool.BEND -> {
                val g = guides.active
                if (g == null) { toast(getString(R.string.bend_needs_guide)); return }
                camera.refreshDrawPlane(g.sweep?.anchor ?: centreOf(g))
                beginStroke(x, y, pressure)
            }

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
                liquifyCfg.size =
                    if (liquifySizeSet) liquifySize
                    else maxOf(24.0, camera.worldToPx(sizeMM * MM * 0.5) * 4)
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
            Tool.INJECT -> sampleAt(x.toDouble(), y.toDouble(), whole = true)
            Tool.EYEDROP -> sampleAt(x.toDouble(), y.toDouble(), whole = false)
            Tool.LOFT -> loftPick(x.toDouble(), y.toDouble())

            else -> {}
        }
        surface.requestRender()
    }

    override fun onDrawMove(x: Float, y: Float, pressure: Float, tiltAz: Float, tiltAlt: Float) {
        dragMoved = true
        when (tool) {
            Tool.DRAW, Tool.SHAPE, Tool.GUIDE, Tool.FLATGUIDE, Tool.BEND ->
                moveStroke(x, y, pressure)
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
            Tool.DRAW, Tool.SHAPE, Tool.GUIDE, Tool.FLATGUIDE -> endStroke()
            Tool.BEND -> finishBend()
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

    // ---- the keyboard ------------------------------------------------------

    /**
     * The web build's shortcuts, unchanged.
     *
     * An Android tablet with a keyboard is a real way to use this, and the
     * letters are worth keeping identical to the browser's so one set of
     * muscle memory covers both. `f` is View reset, which is why Fill is on
     * `k`; `[` and `]` step the lens by 15% a press.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (event.isCtrlPressed || event.isMetaPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_Z ->
                    if (event.isShiftPressed) { history.redo(); refreshScene() }
                    else { history.undo(); refreshScene() }
                KeyEvent.KEYCODE_Y -> { history.redo(); refreshScene() }
                else -> return super.onKeyDown(keyCode, event)
            }
            return true
        }

        when (keyCode) {
            KeyEvent.KEYCODE_D -> setTool(Tool.DRAW)
            KeyEvent.KEYCODE_R -> setTool(Tool.SHAPE)
            KeyEvent.KEYCODE_G -> setTool(Tool.GUIDE)
            KeyEvent.KEYCODE_B -> setTool(Tool.BEND)
            KeyEvent.KEYCODE_E -> setTool(Tool.ERASE)
            KeyEvent.KEYCODE_V -> setTool(Tool.VACUUM)
            KeyEvent.KEYCODE_S -> setTool(Tool.SELECT)
            KeyEvent.KEYCODE_L -> setTool(Tool.LASSO)
            KeyEvent.KEYCODE_M -> setTool(Tool.SMOOTH)
            KeyEvent.KEYCODE_K -> setTool(Tool.FILL)
            KeyEvent.KEYCODE_O -> flipInput(InputToggle.ORTHO)
            KeyEvent.KEYCODE_F -> {
                resetView(); pushCamera(); toast(getString(R.string.view_reset))
            }
            KeyEvent.KEYCODE_LEFT_BRACKET -> stepFocal(1 / 1.15)
            KeyEvent.KEYCODE_RIGHT_BRACKET -> stepFocal(1.15)
            KeyEvent.KEYCODE_ESCAPE -> { cancelStaging(); setTool(Tool.DRAW) }
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_6 -> {
                val v = Camera.ORTHO_VIEWS[keyCode - KeyEvent.KEYCODE_1]
                camera.applyOrthoView(v)
                pushCamera()
                toast(getString(R.string.view_snapped, v.name))
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun stepFocal(by: Double) {
        camera.focal = clamp(camera.focal * by, Tune.FOCAL_MIN, Tune.FOCAL_MAX)
        camera.apply()
        pushCamera()
        pushSettings()
    }

    // ---- taps and holds ---------------------------------------------------

    /**
     * FACT (B.1): a one-finger double-tap snaps to the nearest of the six
     * standard views; a three-finger double-tap toggles the projection.
     */
    override fun onDoubleTap(x: Float, y: Float, fingers: Int) {
        /* the first thing a double-tap does when the chrome is hidden is bring
           it back — that is the only way back from Hide UI */
        if (hideUi) { flipInput(InputToggle.HIDE_UI); return }
        when (fingers) {
            1 -> {
                val v = camera.nearestOrthoView()
                camera.applyOrthoView(v)
                pushCamera()
                toast(getString(R.string.view_snapped, v.name))
            }
            3 -> {
                camera.ortho = !camera.ortho
                camera.apply(); pushCamera(); pushSettings()
                toast(
                    getString(
                        if (camera.ortho) R.string.projection_ortho
                        else R.string.projection_persp,
                    ),
                )
            }
        }
    }

    /**
     * HOLDING ON A GUIDE WITH SELECT PICKS THE GUIDE.
     *
     * `Tools.longPressSelect`, which was never ported: Select is a tap tool,
     * so its press is free to mean something on its own, and what it means is
     * "this whole surface, not the curves on it". A guide picked this way is
     * what the joystick then drives.
     *
     * Only the ACTIVE guide can be picked, as in the web build — an inactive
     * one is scaffolding you have put away, and reaching through the thing you
     * are drawing on to grab it is not what the press meant.
     */
    override fun onDrawHold(x: Float, y: Float) {
        if (tool != Tool.SELECT) return
        val g = guides.active ?: return
        camera.rayFrom(x.toDouble(), y.toDouble(), penRay)
        if (GuidePainting.project(g, penRay, clampOffSurface = false) == null) return

        holdConsumedTap = true          // the press has been spent; it is not a tap
        g.selected = !g.selected
        /* a guide and a set of curves are two different things for the
           joystick to drive, so picking one puts the other down */
        if (g.selected) sketch.clearSelection()
        pushGuides()
        refreshScene()
        toast(
            getString(
                if (g.selected) R.string.guide_selected else R.string.guide_deselected,
            ),
        )
    }

    /**
     * Two fingers tapping undo, which is the one gesture this build adds.
     *
     * The web build's own note calls it "the documented rough edge worth
     * fixing" — Feather has no gesture undo at all — and it is the tap every
     * tablet drawing app has taught. Undoing is the one thing you reach for
     * with the pen still in your hand, so it should not cost a trip to the
     * rail.
     */
    override fun onTap(x: Float, y: Float, fingers: Int) {
        if (fingers != 2) return
        if (hideUi) { flipInput(InputToggle.HIDE_UI); return }
        if (chrome.closeTop()) return    // a sheet in the way is what you meant
        doAction(Action.UNDO)
    }

    /**
     * A real stylus arrived, so finger drawing stands down and the fingers go
     * back to navigating.
     */
    override fun onPenDetected() {
        pushSettings()
        toast(getString(R.string.pen_detected))
    }

    /**
     * FACT (B.2/B.3): a hold on a curve, the guide or the grid pins the orbit
     * point there; on empty space it unpins, or resets the view when it was
     * not pinned.
     *
     * The eye is held still across the change. Moving the pivot without it
     * would swing the camera round to keep its spherical coordinates, which
     * looks like the sketch jumping away from the finger that just touched it.
     */
    override fun onPressHold(x: Float, y: Float) {
        val eye = camera.eye.copy()
        camera.rayFrom(x.toDouble(), y.toDouble(), penRay)

        val hit = Selection.hitTest(sketch, camera, x.toDouble(), y.toDouble(), mask())
        val point = when {
            hit != null -> nearestPointOn(hit)
            else -> guides.active?.let { g ->
                GuidePainting.project(g, penRay, clampOffSurface = false)?.point
            } ?: groundPoint()
        }

        if (point != null) {
            camera.pivot.set(point)
            camera.pinned = true
            camera.lookFrom(eye)
            pushCamera()
            pushSettings()
            toast(getString(R.string.pivot_pinned))
            return
        }

        if (camera.pinned) {
            camera.pinned = false
            camera.pivot.set(0.0, 0.0, 0.0)
            camera.lookFrom(eye)
            pushCamera()
            toast(getString(R.string.pivot_released))
        } else {
            resetView()
            pushCamera()
            toast(getString(R.string.view_reset))
        }
    }

    /** Where the ray passes closest to a curve it hit. */
    private fun nearestPointOn(s: Stroke): Vec3? {
        var best: Vec3? = null
        var bestT = Double.MAX_VALUE
        val tmp = Vec3()
        for (pt in s.pts) {
            penRay.closestPointTo(pt.p, tmp)
            val t = (tmp - penRay.origin) dot penRay.direction
            val d = (tmp - pt.p).lengthSq()
            if (t > 0 && d < bestT) { bestT = d; best = pt.p.copy() }
        }
        return best
    }

    /** The grid, but only where it is drawn — 20 units either side of centre. */
    private fun groundPoint(): Vec3? {
        if (!docEnv.grid) return null
        val out = Vec3()
        val t = -penRay.origin.y / penRay.direction.y
        if (!t.isFinite() || t <= 0) return null
        out.set(
            penRay.origin.x + penRay.direction.x * t,
            0.0,
            penRay.origin.z + penRay.direction.z * t,
        )
        return if (kotlin.math.abs(out.x) < 20 && kotlin.math.abs(out.z) < 20) out else null
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

    private fun beginStroke(x: Float, y: Float, pressure: Float) {
        val s = Stroke(
            brush = brush, color = color, baseRadius = sizeMM * MM * 0.5, opacity = opacity,
        )
        /*
         * The target is stamped onto the STROKE rather than read from the tool
         * at draw time: a curve drawn with pressure on opacity has to keep
         * looking like that after the setting changes for the next one. Off
         * means "none" — the geometry then ignores pressure entirely.
         */
        s.pressureTarget = if (pressureOn) pressureTarget else "none"
        s.group = sketch.ensureGroup().id
        stabilizer.reset()
        stabilizer.next(x.toDouble(), y.toDouble())
        liveScreen.clear()
        adjusting = null
        synchronized(liveBuffer) {
            live = s
            liveBuffer.begin(s)
            if (appendAt(s, stabilizer.x, stabilizer.y, pressure)) liveBuffer.append(s)
        }
        liveScreen.add(Px(stabilizer.x, stabilizer.y))
        renderer.setLive(liveBuffer)
        armShapeHold()
    }

    private fun moveStroke(x: Float, y: Float, pressure: Float) {
        val s = live ?: return

        /*
         * Once a shape has been fitted the pen stops adding samples and drives
         * ONE parameter of it instead — the far end of a line, the radius of a
         * circle, the bow of a curve.
         */
        adjusting?.let { shape ->
            Shapes.adjust(shape, adjustAnchor, x.toDouble(), y.toDouble())
            rebuildFromShape(s, shape, pressure)
            return
        }

        // FACT (C.2): Stable Stroke smooths the INPUT, before it is projected
        if (!stabilizer.next(x.toDouble(), y.toDouble())) return
        synchronized(liveBuffer) {
            if (appendAt(s, stabilizer.x, stabilizer.y, pressure)) liveBuffer.append(s)
        }
        liveScreen.add(Px(stabilizer.x, stabilizer.y))
        armShapeHold()
    }

    // ---- bend --------------------------------------------------------------

    /**
     * The stroke just drawn becomes the guide's new path.
     *
     * A SWEPT guide bends by replacing its path outright. Anything else — a
     * loft, a primitive, an imported model — has no profile and path to
     * replace, so it bends as a curve DEFORM of its mesh instead: same
     * gesture, same "follow the line I drew" result, on any geometry.
     */
    private fun finishBend() {
        disarmShapeHold()
        adjusting = null
        val s = synchronized(liveBuffer) { live.also { live = null } } ?: return
        renderer.setLive(null)
        Dedupe.clean(s)
        val g = guides.active ?: return
        if (s.pts.size < 2) return
        val path = s.pts.map { it.p.copy() }

        if (g.sweep == null) {
            val wasBent = g.bendPath?.map { it.copy() }
            /*
             * A FLAT GUIDE STOPS BEING FLAT once it is bent. Drawing and
             * trimming carry on working — both read the mesh's own
             * parameterisation, which the deform moves but does not
             * invalidate — but the analytic plane behind Fill would be
             * describing a sheet that is no longer there, so it is given up
             * rather than left to lie. Fill then declines on this guide, which
             * is the honest answer until the sampler can walk a deformed sheet.
             */
            val wasPlane = g.plane
            if (!GuideEditing.bendMesh(g, path)) return
            g.plane = null
            val nowBent = g.bendPath?.map { it.copy() } ?: return
            history.run(
                Step(
                    "Bend guide",
                    onRedo = {
                        GuideEditing.bendMesh(g, nowBent); g.plane = null
                        pushGuides(); refreshScene()
                    },
                    onUndo = {
                        if (wasBent != null) GuideEditing.bendMesh(g, wasBent)
                        else GuideEditing.unbendMesh(g)
                        g.plane = wasPlane
                        pushGuides(); refreshScene()
                    },
                ),
            )
            return
        }

        /*
         * A Sweep is immutable in its shape — path and anchorIndex are both
         * vals, and `bend` builds a new one rather than editing the old. So
         * undo swaps the whole object back, which is also the only version
         * that is correct: bending moves the anchor to the start of the new
         * path, and putting the points back without the index would leave the
         * profile transported from the wrong place.
         */
        val before = g.sweep ?: return
        val beforeBend = g.bendPath?.map { it.copy() }
        if (!GuideEditing.bend(g, path)) return
        val after = g.sweep ?: return
        val afterBend = g.bendPath?.map { it.copy() }
        history.run(
            Step(
                "Bend guide",
                onRedo = {
                    g.sweep = after; g.bendPath = afterBend
                    Guides.rebuildSweep(g); pushGuides(); refreshScene()
                },
                onUndo = {
                    g.sweep = before; g.bendPath = beforeBend
                    Guides.rebuildSweep(g); pushGuides(); refreshScene()
                },
            ),
        )
    }

    /** The middle of a guide's mesh, for the plane a deform bend is drawn on. */
    private fun centreOf(g: Guide): Vec3 {
        val b = Bounds()
        g.surface?.let { surf ->
            var i = 0
            while (i + 2 < surf.positions.size) {
                b.add(
                    Vec3(
                        surf.positions[i].toDouble(),
                        surf.positions[i + 1].toDouble(),
                        surf.positions[i + 2].toDouble(),
                    ),
                )
                i += 3
            }
        }
        return if (b.empty) Vec3() else b.centre()
    }

    // ---- hold-to-shape (C.9) ---------------------------------------------

    /**
     * FACT (C.9): "Hold after drawing to adjust length/endpoint (lines) or
     * curvature (curves); press-hold-drag to size a circle."
     *
     * A pen resting on glass wanders a pixel or two, and the resample gate
     * admits anything past 2px as travel. Every such sample restarting the
     * clock would mean "hold the pen still" only ever fired for a perfectly
     * steady hand, so the clock survives jitter inside [STILL_PX] of wherever
     * it started.
     */
    private fun armShapeHold() {
        if (!shapeHoldOn) return
        if (tool !in HOLD_TOOLS) return
        val now = liveScreen.lastOrNull() ?: return
        val anchor = holdAnchor
        if (shapeHold != null && anchor != null &&
            Math.hypot(now.x - anchor.x, now.y - anchor.y) <= STILL_PX
        ) {
            return          // still inside the slop: let the running clock run
        }
        disarmShapeHold()
        holdAnchor = now
        val r = Runnable { enterShapeAdjust() }
        shapeHold = r
        main.postDelayed(r, Shapes.HOLD_MS)
    }

    private fun disarmShapeHold() {
        shapeHold?.let { main.removeCallbacks(it) }
        shapeHold = null
    }

    private fun enterShapeAdjust() {
        shapeHold = null
        val s = live ?: return
        if (adjusting != null || tool !in HOLD_TOOLS) return

        /*
         * A press that never went anywhere has no shape to fit, so under the
         * Shape tool it seeds a circle on the spot and hands the drag straight
         * to its radius. Only under Shape: pausing to steady your hand before
         * an ordinary stroke is ordinary, and turning that into a circle would
         * ruin it.
         */
        val travel = liveTravel()
        val fitted = if (tool == Tool.SHAPE && travel <= STILL_PX) {
            val a = liveScreen.firstOrNull() ?: return
            Shapes.Shape.Circle(a.x, a.y, SEED_R_PX)
        } else {
            if (liveScreen.size < 3) return
            Shapes.fitShape(liveScreen) ?: return
        }

        adjusting = fitted
        adjustAnchor = liveScreen.lastOrNull() ?: Px(0.0, 0.0)
        rebuildFromShape(s, fitted, 1f)

        val what = when (tool) {
            Tool.GUIDE -> getString(R.string.shape_profile)
            Tool.BEND -> getString(R.string.shape_sweep)
            else -> ""
        }
        toast(
            when (fitted) {
                is Shapes.Shape.Circle -> getString(R.string.shape_circle, what)
                is Shapes.Shape.Line -> getString(R.string.shape_line, what)
                else -> getString(R.string.shape_curve, what)
            },
        )
    }

    private fun liveTravel(): Double {
        var t = 0.0
        for (i in 1 until liveScreen.size) {
            t += Math.hypot(
                liveScreen[i].x - liveScreen[i - 1].x, liveScreen[i].y - liveScreen[i - 1].y,
            )
        }
        return t
    }

    /**
     * Throw the streaming buffer away and lay the whole stroke out again from
     * the shape.
     *
     * The incremental buffer exists to avoid rebuilding a growing stroke every
     * sample, and that is exactly the wrong shape here: an adjusted shape
     * changes at BOTH ends and in the middle at once, so there is no window to
     * rewrite. Rebuilding is also cheap — a shape is at most 65 points.
     */
    private fun rebuildFromShape(s: Stroke, shape: Shapes.Shape, pressure: Float) {
        synchronized(liveBuffer) {
            s.pts.clear()
            liveBuffer.begin(s)
            for (p in shape.points) {
                if (appendAt(s, p.x, p.y, pressure)) liveBuffer.append(s)
            }
        }
        surface.requestRender()
    }

    private fun endStroke() {
        disarmShapeHold()
        adjusting = null
        val s = synchronized(liveBuffer) { live.also { live = null } } ?: return
        renderer.setLive(null)
        Dedupe.clean(s)
        if (s.pts.size < 2) return

        /* FREEZE THE FRAMES BEFORE ANYTHING ELSE TOUCHES THE STROKE.
           This is the step that measures the nib against the surface it was
           painted on — which way it points, and how much of it the guide can
           actually take beside an edge — and writes the answer onto the
           points. Everything downstream (erase, bend, smooth, the joystick,
           an undo) moves points without knowing anything about surfaces, and
           the stroke keeps looking like itself because of this. */
        Nib.freezeFrames(s)

        /*
         * Which tools make a guide, matching `role` in Tools.begin. Shape is
         * NOT one of them: it is Draw with the fit turned on, so a shape
         * stroke is a curve. Only Draw gets the autoGuide rule — the
         * documented premise (C.1) that with nothing active your first stroke
         * becomes the guide.
         */
        val wantsGuide = tool == Tool.GUIDE || tool == Tool.FLATGUIDE ||
            (tool == Tool.DRAW && autoGuide && guides.active == null &&
                sketch.strokes.isEmpty())
        if (wantsGuide) makeGuideFrom(s) else commitStroke(s)
    }

    /**
     * Screen to world. With a guide active the pen paints ONTO it; otherwise
     * the point lands on a camera-facing plane through the pivot, which is the
     * web build's `refreshDrawPlane()` with no argument.
     */
    private fun appendAt(
        s: Stroke, px: Double, py: Double, pressure: Float,
    ): Boolean {
        val active = guides.active
        if (active != null && (tool == Tool.DRAW || tool == Tool.SHAPE)) {
            camera.rayFrom(px, py, penRay)
            val hit = GuidePainting.project(active, penRay, clampOffSurface = clampOff)
                ?: return false
            s.pts.lastOrNull()?.let { if (it.p.distanceTo(hit.point) < 0.0005) return false }
            /* THE NIB IS AIMED BY THE SURFACE, NOT BY THE PEN.
               `roll` used to be set from the stylus tilt azimuth here, and a
               rotation about the tangent is precisely what lifts a blade off
               the guide — which is why the wide brush stood out of the surface
               instead of lying on it. The surface normal and the surface's own
               frame go on the point instead, and Nib measures the roll from
               them at freeze time. Tilt is still recorded; it no longer turns
               the section. */
            val pt = StrokePoint(
                hit.point.copy(), pressure = pressure.toDouble(),
                nrm = hit.normal.copy(),
            )
            pt.surf = hit.frame          // spent by the freeze, after it trims the nib
            s.pts.add(pt)
            s.guideId = active.id
            return true
        }

        val p = camera.planePoint(px, py, scratch) ?: return false
        s.pts.lastOrNull()?.let { if (it.p.distanceTo(p) < 0.0005) return false }
        /* No guide: the draw plane faces the camera, so ITS normal is the view
           direction and a blade lies flat in the plane you are drawing on,
           which is how free-space strokes have always looked. */
        s.pts.add(
            StrokePoint(
                p.copy(), pressure = pressure.toDouble(),
                nrm = camera.drawPlane.normal.copy(),
            ),
        )
        return true
    }

    private fun commitStroke(s: Stroke) {
        /* the stroke and everything the current symmetry owes it, as ONE step:
           undoing a mirrored stroke should not leave its reflection behind */
        val copies = symmetryCopies(s)
        for (c in copies) c.group = s.group
        val all = listOf(s) + copies
        history.run(
            Step(
                "Draw", cost = s.pts.size * all.size,
                onRedo = { for (c in all) sketch.add(c); refreshScene() },
                onUndo = { for (c in all) sketch.remove(c); refreshScene() },
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
        pushStrokes()
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
        /* A HOLD THAT PICKED A GUIDE SPENT THE PRESS. Without this the same
           press then fell through as a tap and selected whatever curve was
           under it, so a guide picked by holding arrived with a stroke
           selected alongside it and the joystick had two targets. */
        if (holdConsumedTap) { holdConsumedTap = false; return }
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

    /** X, then Z, then off — the cycle the web build's button walks. */
    private fun cycleMirror() {
        mirror = when (mirror) {
            null -> "x"
            "x" -> "z"
            else -> null
        }
        pushFold()
        chrome.setSymmetry(mirror != null || radial > 1)
        toast(
            mirror?.let { getString(R.string.mirror_on, it.uppercase()) }
                ?: getString(R.string.mirror_off),
        )
        scheduleAutosave()
    }

    /**
     * Every copy the current symmetry owes a stroke, committed with it.
     *
     * Mirror and radial COMPOSE: with both on each of the n sectors carries
     * the stroke and its reflection, which is a rosette rather than a
     * pinwheel. The identity is never in the list — that is the stroke you
     * actually drew.
     */
    private fun symmetryCopies(s: Stroke): List<Stroke> {
        val mats = Selection.symmetryMatrices(mirror, radial)
        if (mats.isEmpty()) return emptyList()
        return mats.map { m -> Selection.transformedCopy(s, m) }
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

    /**
     * The same rule as the colour swatches, and for the same reason: in the web
     * build the brush panel restyles a live selection rather than only setting
     * what the next stroke will be (`applyStyle({opacity: …})` in dragValue).
     *
     * Committed on release rather than on every sample of the drag — the value
     * is read off the ORIGINAL opacities each time, so a slide that passes
     * through 40% on its way to 10% still undoes in one step back to where it
     * started, and cannot compound.
     */
    private fun applyOpacityToSelectionOrBrush(o: Double) {
        opacity = o
        val sel = sketch.selection
        if (sel.isEmpty()) return
        val was = sel.map { it.opacity }
        history.run(
            Step(
                "Opacity",
                onRedo = {
                    Selection.restyle(sel, StyleChange(opacity = o))
                    refreshScene()
                },
                onUndo = {
                    for (i in sel.indices) sel[i].opacity = was[i]
                    refreshScene()
                },
            ),
        )
    }

    private fun applyColorToSelectionOrBrush(ink: Rgba) {
        color = ink
        chrome.setColor(argbOf(ink))
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
        if (tool == Tool.GUIDE || tool == Tool.FLATGUIDE) setTool(Tool.DRAW)
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
        val proto = Stroke(
            brush = brush, color = color, baseRadius = sizeMM * MM * 0.5, opacity = opacity,
        )
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

    /**
     * The guides the renderer should draw: the scene's, plus the one being
     * staged. The staged one is deliberately NOT in [guides] — it cannot be
     * closed, saved or painted on until Done accepts it — so it is added here
     * at the last moment rather than by pretending it is part of the scene.
     */
    private fun pushGuides() {
        val list = guides.drawList()
        val staged = stagedGuide
        renderer.setGuides(if (staged == null) list else list + staged)
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
        /*
         * A PNG is a picture of the VIEW, so it is offered even with nothing
         * drawn — an empty page under a chosen background and grid is a
         * legitimate thing to want. The mesh formats are not: an OBJ with no
         * geometry in it is a file that will not open anywhere.
         */
        val names = arrayOf(
            "PNG snapshot", "OBJ + MTL (mm)", "STL binary (mm)", "glTF 2.0 (metres)",
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.export_)
            .setItems(names) { _, which ->
                if (which > 0 && sketch.strokes.isEmpty()) {
                    toast(getString(R.string.nothing_to_export)); return@setItems
                }
                val (req, ext, mime) = when (which) {
                    0 -> Triple(REQ_EXPORT_PNG, "png", "image/png")
                    1 -> Triple(REQ_EXPORT_OBJ, "obj", "model/obj")
                    2 -> Triple(REQ_EXPORT_STL, "stl", "model/stl")
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
            REQ_EXPORT_PNG -> exportPng(uri)
            REQ_IMPORT -> importReference(uri)
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
    /**
     * A PNG of what is on screen.
     *
     * The frame has to be taken by the GL thread, so this asks and waits: the
     * renderer answers at the end of its next frame, and the compress and the
     * write then go to the IO thread because both are slow enough to drop a
     * frame if they ran there.
     */
    private fun exportPng(uri: Uri) {
        renderer.requestSnapshot { bitmap ->
            if (bitmap == null) {
                main.post { toast(getString(R.string.snapshot_failed)) }
                return@requestSnapshot
            }
            io.execute {
                val ok = runCatching {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                    } ?: false
                }.getOrDefault(false)
                bitmap.recycle()
                main.post {
                    toast(
                        if (ok) getString(R.string.snapshot_saved)
                        else getString(R.string.could_not_write),
                    )
                }
            }
        }
        surface.requestRender()
    }

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
        docTool.opacity = opacity
        docTool.pressureOn = pressureOn
        docTool.pressureTarget = pressureTarget
        docTool.mirror = mirror
        docTool.radial = radial
        docTool.stableOn = stableOn
        docTool.stable = stableAmount
        docTool.autoGuide = autoGuide
        docTool.autoGuide = autoGuide
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
        opacity = clamp(r.tool.opacity, 0.05, 1.0)
        pressureOn = r.tool.pressureOn
        pressureTarget = r.tool.pressureTarget
        mirror = r.tool.mirror
        radial = maxOf(1, r.tool.radial)
        stableOn = r.tool.stableOn
        stableAmount = clamp(r.tool.stable, 0.0, Tune.STABLE_MAX)
        stabilizer.amount = if (stableOn) stableAmount else 0.0
        autoGuide = r.tool.autoGuide
        chrome.setSymmetry(mirror != null || radial > 1)
        chrome.setPressure(pressureOn, pressureTarget)
        pushSettings()
        autoGuide = r.tool.autoGuide
        applyEnvironment(r.env)
        refreshGroups()
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
    private fun setSaveState(state: Int) {
        if (saveState == state) return
        saveState = state
        chrome.setSaveState(state)
    }

    private fun scheduleAutosave() {
        setSaveState(1)
        if (autosavePending) return
        autosavePending = true
        main.postDelayed({ autosavePending = false; writeAutosave() }, 500)
    }

    private fun writeAutosave() {
        if (sketch.strokes.isEmpty() && guides.active == null) { setSaveState(0); return }
        val text = currentDocumentText()
        io.execute {
            val ok = runCatching { autosaveFile().writeText(text) }.isSuccess
            /*
             * A save you were told about that then quietly did not happen is
             * the one failure a sketchbook must never have, so the dot reports
             * what actually landed rather than what was attempted.
             */
            main.post { setSaveState(if (ok) 0 else 2) }
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
        opacity = clamp(r.tool.opacity, 0.05, 1.0)
        pressureOn = r.tool.pressureOn
        pressureTarget = r.tool.pressureTarget
        mirror = r.tool.mirror
        radial = maxOf(1, r.tool.radial)
        stableOn = r.tool.stableOn
        stableAmount = clamp(r.tool.stable, 0.0, Tune.STABLE_MAX)
        stabilizer.amount = if (stableOn) stableAmount else 0.0
        autoGuide = r.tool.autoGuide
        chrome.setSymmetry(mirror != null || radial > 1)
        chrome.setPressure(pressureOn, pressureTarget)
        pushSettings()
        autoGuide = r.tool.autoGuide
        applyEnvironment(r.env)
        refreshGroups()
    }

    // ---- keeping the screen in step -------------------------------------------

    private fun setDocument(list: List<Stroke>) {
        sketch.clear()
        for (s in list) sketch.add(s)
    }

    private fun refreshScene() {
        pushFold()
        pushStrokes()
        refreshControls()
        scheduleAutosave()
        surface.requestRender()
    }

    /**
     * WHAT THE RENDERER IS ALLOWED TO DRAW.
     *
     * The renderer draws what it is given rather than asking whether each
     * stroke is visible, which is why this is the only route to it — every
     * call site used to pass `sketch.strokes` and a hidden group went on
     * drawing. The row dimmed, the eye closed, and nothing left the screen.
     *
     * Hiding also drops the hidden curves from the selection: the joystick
     * would otherwise go on driving them, and the selection bar go on offering
     * to duplicate and delete curves nobody can see.
     */
    private fun pushStrokes() {
        if (sketch.dropHiddenFromSelection() > 0) chrome.setSelection(sketch.selection.size)
        renderer.setStrokes(sketch.editable())
    }

    /** Points moved in place, so the meshes built from them are stale. */
    private fun refreshStrokeMeshes(list: List<Stroke>) {
        for (s in list) renderer.invalidate(s)
        surface.requestRender()
    }

    // ---- camera ---------------------------------------------------------------

    /**
     * B.1's navigation set, and it SHIFTS BY ONE FINGER when a finger draws.
     *
     *   pen mode          1 orbit   2 pan + pinch + twist   3 vertical = lens
     *   finger-draw mode  1 draws   2 orbit + pinch + twist  3 pan
     *
     * That shift is the web build's and it is the whole point: with one finger
     * busy laying ink, every navigation gesture needs one more finger, and the
     * lens moves to its slider because there is no fourth-finger mapping worth
     * teaching. This used to be a single mapping — one and two fingers orbit,
     * three pans — which is neither of the two, so pan was unreachable with a
     * pen and the lens unreachable at all.
     */
    override fun onCamera(dx: Float, dy: Float, dScale: Float, dRotate: Float, fingers: Int) {
        camera.killSpin()
        val navFingers = if (gestures.fingerDraws) fingers - 1 else fingers

        /* THREE FINGERS SET THE LENS, and nothing else: a focal change that
           also orbited would be two things at once, and it is a vertical
           gesture on purpose.
           GUESS: up = a longer lens. The docs say "up = increase" without
           saying increase what, and longer focal is the reading that matches
           "increase FOV value". */
        if (navFingers >= 3) {
            lastGestureOrbited = false
            camera.focal = clamp(
                camera.focal * exp(-dy.toDouble() * 0.006),
                Tune.FOCAL_MIN, Tune.FOCAL_MAX,
            )
            camera.apply()
            pushCamera()
            chrome.setViewInfo(
                camera.focal.toInt(), !camera.ortho, sketch.strokes.size, camera.pinned,
            )
            return
        }

        lastGestureOrbited = navFingers <= 1
        if (lastGestureOrbited) camera.orbitBy(dx.toDouble(), dy.toDouble())
        else camera.panBy(dx.toDouble(), dy.toDouble())
        /* zoom and roll are what two fingers ADD; one finger has neither a
           span nor a twist to read, and Gestures reports 1 and 0 for them */
        if (dScale > 0f && dScale != 1f) camera.zoomBy(1.0 / dScale)
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

    /** What the last pointer reported, for the diagnostics panel. */
    private val diagValues = HashMap<String, String>()
    private var diagOn = false

    private fun pushDiag() {
        diagValues["curves"] = sketch.strokes.size.toString()
        chrome.setDiag(diagOn, diagValues)
    }

    /**
     * A hovering stylus reports what it is and how hard it is about to press,
     * which is what the diagnostics panel is for.
     */
    override fun onHover(x: Float, y: Float, pressure: Float) {
        if (!diagOn) return
        diagValues["hover"] = "yes"
        diagValues["pressure"] = String.format("%.2f", pressure)
        pushDiag()
    }

    override fun onHoverExit() {
        if (!diagOn) return
        diagValues["hover"] = "no"
        pushDiag()
    }

    private companion object {
        /**
         * Hold-to-shape is armed for anything drawn AS a stroke — a curve, a
         * guide profile, a bend path, a flat outline. FACT (C.9): Draw Shape
         * "also works to create/bend guides", and holding is how you reach it
         * without switching tools.
         */
        private val HOLD_TOOLS = setOf(
            Tool.DRAW, Tool.SHAPE, Tool.GUIDE, Tool.FLATGUIDE, Tool.BEND,
        )

        /** How far the pen may wander and still count as held still. */
        private const val STILL_PX = 6.0

        /** The circle a bare press starts at, before the drag sizes it. */
        private const val SEED_R_PX = 8.0

        /** --bg, light and dark: the same two values as the colour resources. */
        private val LIGHT_PAGE = Rgba(0.925, 0.918, 0.953)
        private val DARK_PAGE = Rgba(0.082, 0.086, 0.106)

        const val REQ_SAVE = 1
        const val REQ_OPEN = 2
        const val REQ_EXPORT_OBJ = 3
        const val REQ_EXPORT_STL = 4
        const val REQ_EXPORT_GLTF = 5
        const val REQ_EXPORT_MTL = 6
        const val REQ_EXPORT_PNG = 7
        const val REQ_IMPORT = 8

        /** Whether the first-run walkthrough has been seen. */
        const val PREF_WALKED = "walked"

        /** The six steps, as (title, body) string pairs. */
        private val WALK = listOf(
            R.string.walk_t1 to R.string.walk_b1,
            R.string.walk_t2 to R.string.walk_b2,
            R.string.walk_t3 to R.string.walk_b3,
            R.string.walk_t4 to R.string.walk_b4,
            R.string.walk_t5 to R.string.walk_b5,
            R.string.walk_t6 to R.string.walk_b6,
        )

        /**
         * Guide names that are a KIND rather than a name. A reference carries
         * the file's own, so numbering it would read as nonsense; only these
         * need telling apart in a list.
         */
        private val GENERIC_NAMES = setOf("Surface", "Loft", "Shape", "Image", "Model")
        const val AUTOSAVE = "autosave.plume.json"
    }
}
