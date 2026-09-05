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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import art.plume.core.Bounds
import art.plume.core.BrushPreset
import art.plume.core.Camera
import art.plume.core.ColorSpace
import art.plume.core.Curves
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
import art.plume.core.Mirror
import art.plume.core.Selection
import art.plume.core.Shapes
import art.plume.core.Sketch
import art.plume.core.Stabilizer
import art.plume.core.Step
import art.plume.core.Stroke
import art.plume.core.StrokeGeometry
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
     * WHICH GLOBAL PLANES ARE MIRRORING, any combination of the three.
     *
     * FACT: Feather's Mirror icon reveals three axes below it — red X, green
     * Y, blue Z — and any number can be on at once. This was one axis at a
     * time, X or Z, with no Y at all: the left-right AND top-bottom symmetry
     * a chair or a face wants could not be asked for.
     *
     * A MODE, not a one-shot: every stroke drawn while it is on gets its
     * reflections, and — since the reflections now remember what they
     * reflect — every later change to the original reaches them too.
     */
    private val mirrorAxes = LinkedHashSet<String>()

    /** FACT (C.3): the pressure toggle lives in the Brush Panel. */
    private var pressureOn = true
    private var pressureTarget = "size"

    // ---- the transform gizmo -----------------------------------------------

    private var joyMode = Transform.Mode.MOVE
    private var joyAxis: Int? = null

    /**
     * THE STATE LIQUIFY STARTED FROM, and what it looked like a moment ago.
     *
     * FACT: liquify has its own bottom menu — "Undo All… to revert to the
     * state before liquify", "Tap and hold 'Compare'… to view the curves
     * before liquify", and a checkbox to apply. All three need the before to
     * still exist, so it is taken once when the tool picks up a selection and
     * held until the tool is put down.
     */
    private var liquifyBase: List<List<Vec3>>? = null
    private var liquifyTargets: List<Stroke>? = null
    private var liquifyPeek: List<List<Vec3>>? = null

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

    /** Device pixels per dp, for the gestures that mean a distance of hand. */
    private val density: Float by lazy { resources.displayMetrics.density }

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

    /**
     * Whether the hovering pen shows the nib it is about to lay down.
     *
     * On by default and switchable, because a preview under the pen is exactly
     * what some people do not want under the pen.
     */
    private var hoverNibOn = true

    /** Whether the pill names each action as you perform it. */
    private var actionPillOn = true

    /** A press-hold picked a guide, so the release is not also a tap select. */
    private var holdConsumedTap = false

    /** Where the press landed, so a tap can be told from a drag by distance. */
    private var dragStartX = 0f
    private var dragStartY = 0f

    /** Everything one joystick drag did to a guide, for a single undo step. */
    private var guideAccum: Mat4? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        renderer = SketchRenderer()
        gestures = Gestures(this)

        surface = object : GLSurfaceView(this) {
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                /*
                 * TOUCHING THE SKETCH PUTS THE CARDS AWAY.
                 *
                 * The web build closes every popover on any pointerdown
                 * outside one. Nothing here did, so the colour card, the brush
                 * grid and the size popover stayed up over the drawing until
                 * you found the button that opened them again — and on a phone
                 * they cover most of the canvas you are trying to draw on.
                 */
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) chrome.dismissPopovers()
                return gestures.onTouchEvent(ev)
            }
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
            /*
             * The CUTOUT, not the system bars. The bars are hidden, so padding
             * the chrome by them held it a status bar's height clear of a top
             * edge that nothing was occupying — the same empty strip the black
             * band came from, kept even after the band was fixed.
             *
             * A cutout is the one thing immersive mode cannot hide, so it is
             * the one thing the chrome still has to keep out of. This is
             * `env(safe-area-inset-*)`, which the stylesheet already uses for
             * the compact dock.
             */
            val safe = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        setContentView(root)

        chrome.setPalettes(readPalettes())
        /* the renderer defers the ground shadow when changes arrive faster
           than it can rebuild it, and a WHEN_DIRTY surface will not come back
           on its own — so it asks */
        renderer.needsFrame = { surface.requestRender() }
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
        val firstRun = !getPreferences(MODE_PRIVATE).getBoolean(PREF_WALKED, false) &&
            sketch.strokes.isEmpty()
        if (firstRun) {
            startWalk(0)
        } else {
            /*
             * OPEN ON THE SHELF, NOT ON THE PAGE.
             *
             * Once there is more than one drawing, dropping straight into
             * whichever was last touched is a guess — and the wrong guess costs
             * you a trip to a menu you did not know was there. A sketchbook
             * opens by being picked up and chosen from.
             *
             * The last work is still restored behind it, so closing the shelf
             * without choosing puts you back exactly where you were, and the
             * shelf is skipped entirely when there is nothing to choose
             * between.
             */
            val works = listWorks()
            if (works.isNotEmpty()) {
                chrome.setWorks(works, currentWorkId())
                chrome.setGallery(true)
            }
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

    /**
     * HIDING THE BARS IS NOT THE SAME AS TAKING THEIR SPACE.
     *
     * This asked for immersive mode and got it — and still left a black band
     * across the top of the screen, because hiding a bar and LAYING OUT UNDER
     * it are two different requests. The window was still sized to start below
     * the status bar, so when the bar went away what was left was the gap it
     * had been sitting in, painted with the window background.
     *
     * `setDecorFitsSystemWindows(false)` is the one that matters: it makes the
     * window the whole display and hands the bar positions over as insets
     * instead. The controller then hides the bars, and BY_SWIPE brings them
     * back on an edge swipe and takes them away again by itself, which is what
     * IMMERSIVE_STICKY meant.
     */
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, surface).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * Android's Back steps OUT of whatever is open rather than leaving: a
     * selection first, then an undo, and only then the app.
     */
    @Deprecated("Activity.onBackPressed")
    override fun onBackPressed() {
        if (chrome.galleryOpen()) { chrome.setGallery(false); return }
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
        chrome.onPalettes = { groups -> writePalettes(groups) }
        chrome.onOpenWork = { id -> openWork(id) }
        chrome.onDeleteWork = { id -> deleteWork(id) }
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
        /*
         * Long press on a row. `Tools.selectGroup`, including the two things
         * it says out loud: a hidden group cannot be selected INTO, and an
         * empty one says so rather than looking like a press that missed.
         *
         * Both mattered more once hiding started working — selecting a hidden
         * group put curves in the selection that the very next refresh dropped
         * again, so the press did nothing and explained nothing.
         */
        chrome.onGroupSelect = { id ->
            val g = sketch.groupById(id)
            val members = sketch.membersOf(id)
            when {
                g == null -> {}
                !g.visible -> toast(getString(R.string.group_is_hidden, g.name))
                members.isEmpty() -> toast(getString(R.string.group_is_empty, g.name))
                else -> {
                    val before = sketch.selection
                    sketch.selectOnly(members)
                    commitSelectionChange("Select group", before)
                    toast(getString(R.string.group_selected, members.size, g.name))
                }
            }
        }
        chrome.onGroupAssign = { id -> assignSelectionTo(id) }
        chrome.onGroupVisible = { id, visible -> setGroupVisible(id, visible) }
        chrome.onGroupOpacity = { id, v -> setGroupOpacity(id, v) }
        chrome.onGroupIsolate = { id -> isolateGroup(id) }
        chrome.onPresetAdd = { addPreset() }
        chrome.onPresetLoad = { i -> loadPreset(i) }
        chrome.onPresetDelete = { gone -> deletePresets(gone) }
        chrome.onLiquifyUndoAll = { undoAllLiquify() }
        chrome.onLiquifyCompare = { down -> peekBeforeLiquify(down) }
        chrome.onMirrorAxis = { axis -> toggleMirrorAxis(axis) }
        chrome.onMirrorOff = { toggleMirror() }
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
            chrome.setSymmetry(mirrorAxes.isNotEmpty() || radial > 1)
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
        chrome.onLiquifyApply = { applyLiquify(); setTool(Tool.DRAW) }
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
        chrome.onGuideColor = { argb ->
            guides.active?.let { g ->
                g.tint = argb?.let {
                    Rgba(
                        android.graphics.Color.red(it) / 255.0,
                        android.graphics.Color.green(it) / 255.0,
                        android.graphics.Color.blue(it) / 255.0,
                    )
                }
                pushGuides()
                surface.requestRender()
                scheduleAutosave()
            }
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
        /*
         * HOME IS THE GALLERY NOW, and reset-view moved to the F key and the
         * hold-on-empty-space gesture that already did it. A grid icon in the
         * top-left corner means "show me everything" in every app anyone has
         * used, and it meant "re-frame this one" here.
         */
        Action.HOME -> openGallery()
        Action.SELECTION_TO_GUIDE -> guideFromSelection()
        Action.EXPORT -> chooseExportFormat()
        Action.MENU -> chrome.setMenu(true)
        Action.HELP -> startWalk(0)
        /*
         * THE PILL NAMES THE STEP, NOT THE BUTTON.
         *
         * History knows what each step was called — "Draw", "Erase", "Move to
         * group" — and that is the useful thing to be told: five taps of undo
         * that all say "Undo" tell you nothing about how far back you have
         * gone, while "Erase / Smooth / Draw" tells you exactly.
         */
        Action.UNDO -> {
            val what = history.undoLabel() ?: ""
            if (history.undo()) {
                refreshScene()
                announce(getString(R.string.did_undo, what))
            }
            Unit
        }
        Action.REDO -> {
            val what = history.redoLabel() ?: ""
            if (history.redo()) {
                refreshScene()
                announce(getString(R.string.did_redo, what))
            }
            Unit
        }
        /*
         * TAP REVEALS THE THREE PLANES; TAP AGAIN PUTS THEM AWAY.
         *
         * FACT: "tap the Mirror icon, which will reveal three axes below it...
         * To deactivate the mirror, tap the Mirror icon in the Tool Menu
         * again." So the icon is the way in and the way out, and the axes
         * themselves are what you choose between.
         */
        Action.MIRROR -> {
            /*
             * The icon reveals the three planes and switches them off again,
             * and the strip stays up either way: it is how you choose an axis,
             * and hiding it the moment the mirror goes off would mean opening
             * it again to turn one back on. The axes themselves are
             * remembered, so the way back on really is one tap.
             */
            if (chrome.mirrorBarOpen()) toggleMirror() else chrome.setMirrorBar(true)
            Unit
        }
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
     * Switching tools, and the three that need something before they can run.
     *
     * Bend wants a guide to bend, Loft wants at least two curves already
     * selected, and Primitives stages one for you. Each says so and stays put
     * rather than switching to a tool that would do nothing.
     *
     * (This comment used to say Bend, Loft and Primitives were ported in
     * `:core` but had no interaction wired up here. That stopped being true
     * when they were wired, and a comment describing a gap that has been
     * filled is worse than no comment: it sends the next reader looking for
     * work that is already done.)
     */
    private fun setTool(t: Tool) {
        /* leaving a staging tool throws away what it was building */
        if (tool != t && (tool == Tool.LOFT || tool == Tool.PRIM)) cancelStaging()
        /* and leaving liquify keeps what it did: the drags are already in the
           history, so all that ends is the session you could compare against */
        if (tool != t && tool == Tool.LIQUIFY) endLiquifySession()

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

    /**
     * OVERDRAWN CURVES ARE ONE SECTION.
     *
     * A loft interpolates between its sections in the order they were picked,
     * so a bundle of strokes laid over each other — which is how a line gets
     * drawn — spent most of the surface crossing four millimetres and left the
     * span you actually wanted as a sliver. Curves close enough to be the same
     * line are averaged into the line they were aiming at, and the loft runs
     * from THAT to the next distinct curve.
     *
     * If everything merged into one there is nothing to loft between, and the
     * honest answer is the curves as they were: refusing a selection because
     * this step was too eager would be worse than a busy surface.
     */
    private fun previewLoft() {
        val merged = Curves.mergeStrokes(loftSel)
        stagedGuide = if (merged.size >= 2) {
            GuideEditing.loftFromCurves(merged, loftTension)
        } else {
            GuideEditing.loft(loftSel, loftTension)
        }
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
            InputToggle.HOVER_NIB -> {
                hoverNibOn = !hoverNibOn
                if (!hoverNibOn) chrome.hideHoverNib()
            }
            InputToggle.ACTION_PILL -> actionPillOn = !actionPillOn
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
            hoverNibOn,
            actionPillOn,
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
            val screen = Transform.screenAxis(camera, a, c)
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
        renderer.setFold(Symmetry.fold(Bounds.of(sketch), mirrorAxes, radial))
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
         * ALL THREE, WHENEVER THERE IS SOMETHING TO MOVE.
         *
         * An axis end-on to the camera used to be dimmed, because it has no
         * direction on the glass and dividing by that was how a one-pixel drag
         * sent the selection to the horizon. But dimming it takes away
         * whichever axis you are looking down — turn to face the front of a
         * model and the one direction you cannot move it is towards you. The
         * division is fixed where it belongs, in Transform.screenAxis, which
         * falls back to the vertical drag the depth strip already uses.
         */
        val usable = listOf(!nothing, !nothing, !nothing)
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

                /*
                 * A REFERENCE ARRIVES WITH SOMEWHERE TO DRAW ON IT.
                 *
                 * You import a picture or a model to draw OVER it, and the
                 * curves that come out of that belong together — they are a
                 * tracing of this thing, not part of whatever you were working
                 * on before. So the import opens a group named after the file
                 * and makes it active, and the next stroke lands there.
                 *
                 * In the SAME history step as the guide, because they are one
                 * action: an undo that took the reference away and left an
                 * empty group named after it would be an undo that half
                 * happened.
                 */
                val group = sketch.newGroup(name)
                val groupAt = sketch.indexOfGroup(group)
                val previousGroup = sketch.activeGroup

                history.run(
                    Step(
                        "Import reference",
                        onRedo = {
                            guides.save(g); guides.setActive(g)
                            sketch.restoreGroup(group, groupAt)
                            sketch.setActiveGroup(group.id)
                            pushGuides(); refreshResources(); refreshScene()
                        },
                        onUndo = {
                            guides.remove(g); guides.setActive(previous)
                            /* the curves drawn into it are NOT taken: undoing
                               an import undoes the import, and the tracing is
                               work of its own. They come out of the group as
                               the group goes, which is what deleting one does
                               everywhere else. */
                            for (st in sketch.membersOf(group.id)) st.group = previousGroup
                            sketch.deleteGroup(group)
                            sketch.setActiveGroup(previousGroup)
                            pushGuides(); refreshResources(); refreshScene()
                        },
                    ),
                )
                toast(getString(R.string.imported, name))
            }
        }
    }

    /**
     * THE PALETTES YOU MADE, one line per group: `name=aarrggbb,aarrggbb`.
     *
     * Preferences rather than the sketch file, for the reason a favourite was:
     * a palette belongs to the person, not to the drawing, and one that
     * vanished when you opened somebody else's file would not be worth
     * building. A newline separates groups because a name may contain
     * anything but that.
     */
    private fun readPalettes(): Map<String, List<Int>> {
        val raw = getPreferences(MODE_PRIVATE).getString(PREF_PALETTES, "").orEmpty()
        if (raw.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, List<Int>>()
        for (line in raw.split("\n")) {
            val at = line.indexOf('=')
            if (at <= 0) continue
            out[line.substring(0, at)] =
                line.substring(at + 1).split(",").mapNotNull { it.trim().toIntOrNull() }
        }
        return out
    }

    private fun writePalettes(groups: Map<String, List<Int>>) {
        val text = groups.entries.joinToString("\n") { (name, colors) ->
            name.replace("\n", " ").replace("=", " ") + "=" + colors.joinToString(",")
        }
        getPreferences(MODE_PRIVATE).edit().putString(PREF_PALETTES, text).apply()
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
                    g.id == sketch.activeGroup, g.opacity,
                    g.id == sketch.isolatedGroup,
                )
            },
        )
        refreshControls()
    }

    private fun newGroup() {
        /* FACT: "The new group will be created directly above the current
           active group" — beside the one you are working in, not at the top
           of a list you then have to hunt down. */
        val g = sketch.newGroup(
            getString(R.string.group_new, sketch.groups.size + 1),
            sketch.indexAboveActive(),
        )
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
                onRedo = { g.visible = visible; refreshScene() },
                onUndo = { g.visible = !visible; refreshScene() },
            ),
        )
    }

    /**
     * Fade a whole group, live.
     *
     * No history step, for the same reason the guide's own opacity slider has
     * none: it is a knob you hold and watch, and one entry per pixel of travel
     * would bury the drawing you did before it under a hundred fades. What
     * lands in the file is where you left it.
     */
    private fun setGroupOpacity(id: Int, v: Double) {
        val g = sketch.groupById(id) ?: return
        g.opacity = v.coerceIn(0.0, 1.0)
        pushStrokes()
        surface.requestRender()
        scheduleAutosave()
    }

    /**
     * Look at one group alone, or stop.
     *
     * FACT: "When a group is isolated, only the curves within that group are
     * visible. Tap another group to isolate and view only its curves. Tap and
     * hold the eyeball icon again to exit isolation."
     *
     * No history step. Isolation is a way of LOOKING at the drawing — nothing
     * about the document changes — and an undo stack full of "looked at group
     * 3" is an undo stack you cannot use to undo anything.
     */
    private fun isolateGroup(id: Int) {
        sketch.isolatedGroup = if (sketch.isolatedGroup == id) null else id
        refreshScene()
        announce(
            if (sketch.isolatedGroup == null) getString(R.string.isolation_off)
            else getString(R.string.isolation_on, sketch.groupById(id)?.name ?: ""),
        )
    }

    // ---- brush presets ------------------------------------------------------

    /**
     * The brushes this note was made with.
     *
     * FACT: "The brush preset saves the current brush type, color, size, and
     * opacity… Brush presets are saved per note." So they live beside the tool
     * state in the document, and a work opened tomorrow comes back with the
     * brushes it was drawn with rather than whatever was used last.
     */
    private val presets = ArrayList<BrushPreset>()

    private fun currentPreset() = BrushPreset(brush, color, sizeMM, opacity)

    private fun addPreset() {
        val fresh = currentPreset()
        /* the same brush twice is not two presets */
        if (presets.any { it.sameAs(fresh) }) return
        if (presets.size >= BrushPreset.LIMIT) {
            toast(getString(R.string.preset_full)); return
        }
        presets.add(fresh)
        pushPresets()
        announce(getString(R.string.preset_saved))
        scheduleAutosave()
    }

    private fun loadPreset(i: Int) {
        val p = presets.getOrNull(i) ?: return
        brush = p.brush
        color = p.color
        sizeMM = clamp(p.sizeMM, Tune.BRUSH_MIN_MM, Tune.BRUSH_MAX_MM)
        opacity = clamp(p.opacity, 0.0, 1.0)
        syncBrushControls()
        announce(
            getString(Chrome.BRUSH_NAMES[p.brush] ?: R.string.brush_pen).substringBefore(" —"),
        )
        scheduleAutosave()
    }

    /**
     * FACT: "Deleted brush presets cannot be recovered and will not return
     * even if you undo the action." Which this build takes literally rather
     * than improving on: an undo that brought back a preset would put it back
     * in a strip you are looking at, halfway through deciding what to keep.
     */
    private fun deletePresets(indices: List<Int>) {
        for (i in indices.sortedDescending()) if (i in presets.indices) presets.removeAt(i)
        pushPresets()
        scheduleAutosave()
    }

    private fun pushPresets() {
        chrome.setPresets(
            presets.map { p ->
                Chrome.PresetRow(
                    p.brush,
                    android.graphics.Color.rgb(
                        (p.color.r * 255).toInt().coerceIn(0, 255),
                        (p.color.g * 255).toInt().coerceIn(0, 255),
                        (p.color.b * 255).toInt().coerceIn(0, 255),
                    ),
                    p.sizeMM,
                    p.opacity,
                )
            },
        )
    }

    // ---- liquify's own before and after -------------------------------------

    /**
     * Hold to see the curves as they were; let go to come back.
     *
     * The current shape is put aside on the way down and restored on the way
     * up, so a comparison costs nothing and changes nothing — it is not an
     * undo, and it must not turn into one if the pen slips.
     */
    private fun peekBeforeLiquify(down: Boolean) {
        val targets = liquifyTargets ?: return
        if (down) {
            if (liquifyPeek != null) return
            val base = liquifyBase ?: return
            liquifyPeek = Editing.snapshot(targets)
            restorePoints(targets, base)
        } else {
            val now = liquifyPeek ?: return
            liquifyPeek = null
            restorePoints(targets, now)
        }
    }

    /** Back to the state liquify started from, as one undoable step. */
    private fun undoAllLiquify() {
        val targets = liquifyTargets ?: return
        val base = liquifyBase ?: return
        val now = Editing.snapshot(targets)
        history.run(
            Step(
                "Undo liquify", cost = targets.sumOf { it.pts.size },
                onRedo = { restorePoints(targets, base) },
                onUndo = { restorePoints(targets, now) },
            ),
        )
        announce(getString(R.string.lq_reverted))
    }

    /**
     * Keep it. Every drag is already in the history — "to undo step by step,
     * use the history panel" — so applying is about ending the SESSION: the
     * before is released, and the next drag starts a new one to compare
     * against.
     */
    private fun applyLiquify() {
        endLiquifySession()
        toast(getString(R.string.liquify_done))
    }

    private fun endLiquifySession() {
        liquifyPeek?.let { now -> liquifyTargets?.let { restorePoints(it, now) } }
        liquifyPeek = null
        liquifyBase = null
        liquifyTargets = null
    }

    private fun assignSelectionTo(id: Int) {
        val sel = sketch.selection
        if (sel.isEmpty()) { toast(getString(R.string.nothing_selected)); return }
        val g = sketch.groupById(id) ?: return
        val was = sel.map { it.group }
        history.run(
            Step(
                "Move to group",
                onRedo = { for (s in sel) sketch.assign(s, g); refreshScene() },
                onUndo = {
                    for (i in sel.indices) sel[i].group = was[i]
                    refreshScene()
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
                    sketch.setActiveGroup(copy.id); refreshScene()
                },
                onUndo = {
                    for (c in copies) sketch.remove(c)
                    sketch.deleteGroup(copy)
                    sketch.setActiveGroup(previous); refreshScene()
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
                    sketch.setActiveGroup(null); refreshScene()
                },
                onUndo = {
                    /* at the row it was on, not at the bottom: the list is a
                       stack you can read, and an undo that moved a group to
                       the end would not read as an undo */
                    sketch.restoreGroup(g, at)
                    /* and each curve at the depth it was drawn at, since draw
                       order is what decides who is on top */
                    for (i in members.indices) sketch.addAt(atStroke[i], members[i])
                    sketch.setActiveGroup(previous); refreshScene()
                },
            ),
        )
        toast(getString(R.string.group_deleted, members.size))
    }

    /** `UI.refresh` — push the model back at the chrome and let it re-derive. */
    private fun refreshControls() {
        chrome.setHistory(history.canUndo(), history.canRedo())
        val g = guides.active
        chrome.setGuide(
            g != null, g?.name ?: "", g?.opacity ?: 0.42,
            g?.tint?.let { c ->
                android.graphics.Color.rgb(
                    (c.r * 255).toInt().coerceIn(0, 255),
                    (c.g * 255).toInt().coerceIn(0, 255),
                    (c.b * 255).toInt().coerceIn(0, 255),
                )
            },
        )
        chrome.setSelection(sketch.selection.size)
        chrome.setGuideSelected(transformGuide != null)
        pushTransform()
        /* the count is of what you can SEE. Hiding a group and watching the
           number stay put says the hide did not work — which is exactly the
           impression the renderer used to give as well. */
        chrome.setViewInfo(
            camera.focal.toInt(), !camera.ortho, sketch.editable().size, camera.pinned,
        )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = chrome.toast(msg)

    /**
     * Name an action in the pill at the top of the screen.
     *
     * Distinct from [toast]: a toast explains, this only ever confirms. An
     * action that has something to EXPLAIN — a refusal, a hint, a count you
     * would not have guessed — still toasts.
     */
    private fun announce(msg: String) = chrome.announce(msg)

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
        dragStartX = x; dragStartY = y
        lastPen = Px(x.toDouble(), y.toDouble())

        /*
         * YOU CANNOT DRAW INTO A GROUP YOU CANNOT SEE.
         *
         * A new curve joins the ACTIVE group, and once hiding actually hid
         * things that opened a state with no way out of it: hide the group you
         * are working in, draw, and the live preview follows the pen the whole
         * way and then vanishes the instant it lifts. The stroke is really
         * there, in a group nothing draws.
         *
         * Refusing it is what every layer-based app does, and it is better
         * than the alternatives — unhiding the group behind your back, or
         * moving you to another one — because neither of those is what the
         * hand asked for, and both change state you did not touch.
         */
        if (tool == Tool.DRAW || tool == Tool.SHAPE) {
            val g = sketch.groupById(sketch.activeGroup)
            if (g != null && !g.visible) {
                toast(getString(R.string.group_hidden_cannot_draw, g.name))
                return
            }
        }

        when (tool) {
            /*
             * FACT: "When a group is hidden, you cannot add new curves to it
             * even if it is the active group." Refused at the START of the
             * stroke rather than at the end: a curve you were allowed to draw
             * and then told about is a curve you have to draw twice, and one
             * that vanishes on the pen-up looks like a crash.
             */
            Tool.DRAW, Tool.SHAPE, Tool.GUIDE, Tool.FLATGUIDE -> {
                if (sketch.groupById(sketch.activeGroup)?.visible == false) {
                    toast(getString(R.string.group_hidden_draw))
                } else {
                    beginStroke(x, y, pressure)
                }
            }

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
                /* the first touch of a session is what fixes the "before" */
                if (liquifyBase == null) {
                    liquifyTargets = sel
                    liquifyBase = Editing.snapshot(sel)
                }
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
        /*
         * A TAP IS A PRESS THAT DID NOT TRAVEL, not one that reported no moves.
         *
         * This was set on the FIRST move event, unconditionally — and a pen or
         * a finger always reports a move or two before it lifts, so every tap
         * was classified as a sweep. Select's tap path therefore never ran:
         * tapping a curve did not select it and tapping empty space did not
         * deselect, because a sweep that crosses nothing simply changes
         * nothing. The same slop the gesture layer uses for a tap.
         */
        if (kotlin.math.hypot(x - dragStartX, y - dragStartY) > Gestures.TAP_SLOP) dragMoved = true
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
        dragPositions?.let { snap -> dragTargets?.let { restorePoints(it, snap) } }
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
         * A press that never went anywhere has no shape to fit, so it seeds a
         * circle on the spot and hands the drag straight to its radius.
         *
         * Shape and BEND only. Bend redraws a guide's sweep from a new
         * viewpoint, and a circle is the one path that is a chore to draw by
         * hand and exact when it matters — a bent guide that closes on itself
         * has to CLOSE, and freehand does not. Pausing to steady your hand
         * before an ordinary stroke is ordinary, though, so Draw is left
         * alone: turning that into a circle would ruin it.
         */
        val travel = liveTravel()
        val seedsCircle = tool == Tool.SHAPE || tool == Tool.BEND
        val fitted = if (seedsCircle && travel <= STILL_PX) {
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

    /**
     * Put moved points back, AND DROP THE MESHES BUILT FROM THEM.
     *
     * The renderer keeps one uploaded mesh per stroke and holds onto it for as
     * long as the stroke is in the list — which is right, and is why moving a
     * curve has to say so. A point moves IN PLACE, so the stroke that owns it
     * is the same object it always was and setStrokes sees nothing to rebuild:
     * undo restored every coordinate and the screen kept drawing the curve
     * where the drag had left it. Every tool that nudges points rather than
     * adding or removing them undid nothing you could see — transform, smooth,
     * liquify, the lot — which is the report that "transform can't be undone".
     */
    private fun restorePoints(targets: List<Stroke>, snap: List<List<Vec3>>) {
        Editing.restore(targets, snap)
        for (s in targets) renderer.invalidate(s)
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
                onRedo = { restorePoints(targets, after) },
                onUndo = { restorePoints(targets, before) },
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

    /** Turn one of the three planes on or off, independently of the others. */
    private fun toggleMirrorAxis(axis: String) {
        if (!mirrorAxes.remove(axis)) mirrorAxes.add(axis)
        pushMirror()
        announce(
            if (mirrorAxes.isEmpty()) getString(R.string.mirror_off)
            else getString(
                R.string.mirror_on,
                Mirror.AXES.filter { it in mirrorAxes }.joinToString("").uppercase(),
            ),
        )
        scheduleAutosave()
    }

    /**
     * The planes you last used, kept while the mirror is switched off.
     *
     * FACT: "The previously used axes are saved, so you can quickly reactivate
     * the mirror with a single tap, making it very convenient." Clearing them
     * on the way out — which is what this did — turned a one-tap reactivation
     * into picking your axes again every time, and anyone working
     * symmetrically switches the mirror off and on constantly to check the
     * half they are drawing.
     */
    private val mirrorRemembered = LinkedHashSet<String>()

    /** Tapping the Mirror icon: off if any plane is live, else back on. */
    private fun toggleMirror() {
        if (mirrorAxes.isNotEmpty()) {
            mirrorRemembered.clear()
            mirrorRemembered.addAll(mirrorAxes)
            mirrorAxes.clear()
            announce(getString(R.string.mirror_off))
        } else {
            mirrorAxes.addAll(mirrorRemembered)
            if (mirrorAxes.isNotEmpty()) {
                announce(
                    getString(
                        R.string.mirror_on,
                        Mirror.AXES.filter { it in mirrorAxes }.joinToString("").uppercase(),
                    ),
                )
            }
        }
        pushMirror()
        scheduleAutosave()
    }

    private fun pushMirror() {
        pushFold()
        chrome.setSymmetry(mirrorAxes.isNotEmpty() || radial > 1)
        chrome.setMirrorAxes(mirrorAxes)
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
        /*
         * The reflections are LINKED to the stroke and the radial copies are
         * not, which is the honest split: a reflection has one curve it is the
         * reflection OF, and a rosette's sectors are peers with no original
         * among them. Editing a curve therefore updates its mirror images and
         * leaves the pinwheel alone.
         */
        val out = ArrayList<Stroke>()
        out.addAll(Mirror.copiesOf(s, mirrorAxes))
        val n = maxOf(1, radial)
        for (i in 1 until n) {
            val rot = Mat4.rotationY(i * Math.PI * 2 / n, Mat4())
            out.add(Selection.transformedCopy(s, rot))
            for (key in Mirror.keysFor(mirrorAxes)) {
                out.add(
                    Selection.transformedCopy(
                        s, Mat4.multiply(rot, Mirror.matrixFor(key), Mat4()),
                    ),
                )
            }
        }
        return out
    }

    /**
     * FACT: there are TWO symmetric duplicates, and this had neither exactly.
     *
     * "Symmetrically by View" reflects "based on the view direction. If the
     * sketch is skewed to the right, it will be duplicated to the left".
     * "Symmetrically by Mirror can only be used when the mirror is on. It
     * duplicates symmetrically based on the currently active mirror axis. If
     * multiple axes are active, multiple curves will be duplicated at once."
     *
     * So the mirror decides when it is on, and the view when it is not —
     * which also means the button never refuses: reflecting across the glass
     * is always a sensible reading of "the other side".
     */
    private fun mirrorSelection() {
        val before = sketch.selection
        if (before.isEmpty()) { toast(getString(R.string.nothing_selected)); return }
        val copies = if (mirrorAxes.isNotEmpty()) {
            Selection.mirrorAxesDuplicate(sketch, mirrorAxes)
        } else {
            Selection.viewMirroredDuplicate(sketch, camera)
        }
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

    /**
     * THE CURVES YOU HAVE ARE OFTEN THE SURFACE YOU WANT.
     *
     * Guides are made by drawing one, which means the shape you already spent
     * a minute getting right cannot become the thing you draw ON without
     * drawing it a second time. This takes the selection instead.
     *
     * One curve sweeps, exactly as Draw's own auto-guide does: extruded along
     * the view, which is the operation the whole app is built around. Two or
     * more LOFT — a surface stretched between them — because that is the only
     * reading of several curves that gives one surface, and it is the same
     * machinery the Loft tool already uses.
     *
     * The curves are left alone. They are not consumed by becoming a guide,
     * and a guide is scaffolding you put away afterwards; deleting the work to
     * make a surface out of it would be a trade nobody would take.
     */
    private fun guideFromSelection() {
        val sel = sketch.selection
        if (sel.isEmpty()) { toast(getString(R.string.nothing_selected)); return }

        val fwd = Vec3()
        camera.forward(fwd)
        val right = Vec3(); val up = Vec3(); val back = Vec3()
        camera.basis(right, up, back)

        /*
         * The same reading as the loft's: a bundle of overdrawn strokes is one
         * curve, and one curve makes a surface by being SWEPT ALONG THE VIEW —
         * the guide stands up out of the screen, facing you, which is the
         * surface you were about to draw the next stroke on. Two or more
         * distinct curves still loft between themselves, because a span
         * between them is a shape you can point at and a sweep of each would
         * only be several walls.
         */
        val merged = Curves.mergeStrokes(sel)
        val g = if (merged.size == 1) {
            Guides.createFromStroke(merged[0], fwd, right, camera.radius)
        } else {
            GuideEditing.loftFromCurves(merged, loftTension)
        }
        if (g == null) { toast(getString(R.string.guide_from_selection_failed)); return }

        val previous = guides.active
        history.run(
            Step(
                "Guide from selection",
                onRedo = { guides.setActive(g); pushGuides(); refreshScene() },
                onUndo = { guides.setActive(previous); pushGuides(); refreshScene() },
            ),
        )
        announce(getString(R.string.guide_from_selection, sel.size))
    }

    private fun openGallery() {
        writeAutosave()
        writeThumbnail(currentWorkId())
        chrome.setWorks(listWorks(), currentWorkId())
        chrome.setGallery(true)
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
                /* FILL'S CURVES JOIN THE ACTIVE GROUP like every other curve.
                   They were the one path that made ungrouped strokes, which
                   left them outside the group system entirely: not in any
                   count, not selectable by a group, and not hideable by any
                   row in the panel. */
                val into = sketch.ensureGroup().id
                for (st in r.strokes) st.group = into
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
        docTool.mirror = if (mirrorAxes.isEmpty()) null else mirrorAxes.joinToString("")
        docTool.presets.clear()
        docTool.presets.addAll(presets)
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
    /**
     * WHAT THE FILE SAYS THE TOOL WAS.
     *
     * Opening a work and recovering the autosave are the same act as far as
     * the tool is concerned, and they used to say so twice, in two copies of
     * this that had already drifted apart by a line. A tool state restored in
     * one path and not the other is a bug you only meet on the path nobody
     * edited, so there is one copy now and both callers use it.
     */
    private fun adoptTool(r: Document.Restored) {
        carried = r.carried
        brush = r.tool.brush
        color = r.tool.color
        sizeMM = clamp(r.tool.sizeMM, Tune.BRUSH_MIN_MM, Tune.BRUSH_MAX_MM)
        opacity = clamp(r.tool.opacity, 0.05, 1.0)
        pressureOn = r.tool.pressureOn
        pressureTarget = r.tool.pressureTarget
        mirrorAxes.clear()
        r.tool.mirror?.forEach { ch -> if (ch.toString() in Mirror.AXES) mirrorAxes.add(ch.toString()) }
        radial = maxOf(1, r.tool.radial)
        stableOn = r.tool.stableOn
        stableAmount = clamp(r.tool.stable, 0.0, Tune.STABLE_MAX)
        stabilizer.amount = if (stableOn) stableAmount else 0.0
        autoGuide = r.tool.autoGuide
        /* FACT: "Brush presets are saved per note", so a work opened comes
           back with its own strip and not the last one that was on screen. */
        presets.clear()
        presets.addAll(r.tool.presets)
        pushPresets()
        chrome.setSymmetry(mirrorAxes.isNotEmpty() || radial > 1)
        chrome.setMirrorAxes(mirrorAxes)
        chrome.setPressure(pressureOn, pressureTarget)
        pushSettings()
        applyEnvironment(r.env)
        refreshGroups()
    }

    private fun loadDocument(text: String) {
        val r = Document.restore(text, sketch, guides, camera)
        if (!r.ok) { toast(r.reason ?: "Not a Plume sketch"); return }
        adoptTool(r)
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

    // ---- the library -----------------------------------------------------

    /**
     * ONE FILE PER WORK, in a directory of them.
     *
     * There used to be a single autosave, which made "your sketch" a thing the
     * app had exactly one of: starting something new meant losing what was
     * there, and there was no way back to last week's drawing. A sketchbook
     * with one page in it is a sheet of paper.
     *
     * So a work is a file named by the moment it was started, and the app
     * remembers which one you are in. Autosave writes to THAT file, so nothing
     * about the save story changes except which name it lands under.
     */
    private fun worksDir() = java.io.File(filesDir, WORKS).also { it.mkdirs() }

    private fun workFile(id: String) = java.io.File(worksDir(), "$id.plume.json")

    private fun thumbFile(id: String) = java.io.File(worksDir(), "$id.png")

    /** Which work is open. Made on demand, so a first run has one. */
    private fun currentWorkId(): String {
        val prefs = getPreferences(MODE_PRIVATE)
        prefs.getString(PREF_WORK, null)?.let { return it }
        val id = newWorkId()
        prefs.edit().putString(PREF_WORK, id).apply()
        return id
    }

    /** One row of the gallery: what to show, and what to open. */
    class Work(
        val id: String,
        val title: String,
        val curves: Int,
        val modified: Long,
        val thumb: java.io.File?,
    )

    /**
     * Every work, newest first.
     *
     * The counts and titles are read out of the files rather than kept in a
     * separate index, because an index is a second copy of the truth and the
     * one that goes stale — a work deleted by the system, or restored from a
     * backup, would still be listed by an index and would not be listed here.
     */
    private fun listWorks(): List<Work> =
        worksDir().listFiles { f -> f.name.endsWith(".plume.json") }
            ?.mapNotNull { f ->
                val id = f.name.removeSuffix(".plume.json")
                val text = runCatching { f.readText() }.getOrNull() ?: return@mapNotNull null
                val n = Document.curveCount(text)
                Work(
                    id = id,
                    title = Document.titleOf(text) ?: readableDate(id),
                    curves = n,
                    modified = f.lastModified(),
                    thumb = thumbFile(id).takeIf { it.exists() },
                )
            }
            ?.sortedByDescending { it.modified }
            ?: emptyList()

    /** `20260902-134501` as something a person would say. */
    private fun readableDate(id: String): String = runCatching {
        val d = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).parse(id)
        java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(d!!)
    }.getOrDefault(id)

    /**
     * Put the current work down and pick up another, or start a fresh one.
     *
     * The one now open is written first, in full and synchronously — leaving
     * for another page is exactly the moment a debounced save has not fired
     * yet, and losing the last stroke of a drawing because you went to look at
     * a different one would be unforgivable.
     */
    private fun openWork(id: String?) {
        /*
         * WRITTEN BEFORE WE LEAVE, ON THIS THREAD.
         *
         * The ordinary autosave hands the write to the IO queue, which is
         * right while you are drawing and wrong here: switching A to B and
         * back to A fast enough would have A's restore read the file before
         * A's own write had come off the queue, and load the sketch as it was
         * two changes ago. A page change is rare and a lost drawing is not, so
         * this one waits.
         */
        saveWorkNow(currentWorkId())
        writeThumbnail(currentWorkId())

        val target = id ?: newWorkId()
        getPreferences(MODE_PRIVATE).edit().putString(PREF_WORK, target).apply()

        sketch.clear()
        for (g in guides.resources.toList()) guides.remove(g)
        guides.setActive(null)
        history.clear()
        if (id != null) restoreAutosave()
        refreshGroups()
        refreshResources()
        pushGuides()
        refreshScene()
        resetView(); pushCamera()
        chrome.setGallery(false)
        announce(
            getString(if (id == null) R.string.work_new else R.string.work_opened),
        )
    }

    /** The current document, written to [id]'s file before anything moves on. */
    private fun saveWorkNow(id: String) {
        if (sketch.strokes.isEmpty() && guides.active == null) return
        val text = currentDocumentText()
        val ok = runCatching { workFile(id).writeText(text) }.isSuccess
        setSaveState(if (ok) 0 else 2)
    }

    private fun deleteWork(id: String) {
        if (id == currentWorkId()) { toast(getString(R.string.work_is_open)); return }
        workFile(id).delete()
        thumbFile(id).delete()
        chrome.setWorks(listWorks(), currentWorkId())
        announce(getString(R.string.work_deleted))
    }

    /**
     * A picture of the work, for its row in the gallery.
     *
     * Taken from the GL thread at the end of a frame, so this is asked and
     * answered rather than called — the same route the PNG export takes. A
     * work with no thumbnail simply shows none; it is a convenience, and
     * blocking a page change on a frame would not be.
     */
    private fun writeThumbnail(id: String) {
        if (sketch.strokes.isEmpty()) return
        renderer.requestSnapshot { bitmap ->
            if (bitmap == null) return@requestSnapshot
            io.execute {
                runCatching {
                    val w = 320
                    val h = (bitmap.height.toFloat() / bitmap.width * w).toInt().coerceAtLeast(1)
                    val small = android.graphics.Bitmap.createScaledBitmap(bitmap, w, h, true)
                    thumbFile(id).outputStream().use {
                        small.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it)
                    }
                }
            }
        }
        surface.requestRender()
    }

    private fun newWorkId(): String =
        java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())

    private fun autosaveFile() = workFile(currentWorkId())

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
        /*
         * WHICH FILE IS DECIDED HERE, NOT ON THE IO THREAD.
         *
         * `autosaveFile()` used to be resolved inside the lambda, which was
         * harmless while there was one autosave and a data-loss bug the moment
         * there were many: leaving a work writes it, then changes which work
         * is current, and the write would land a beat later — putting the
         * OUTGOING sketch into the INCOMING work's file and destroying the
         * drawing you had just opened.
         *
         * The executor is single-threaded, so capturing the destination on the
         * calling thread also fixes the ordering: each work's write completes
         * before the next one's begins.
         */
        val into = autosaveFile()
        io.execute {
            val ok = runCatching { into.writeText(text) }.isSuccess
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
        adoptTool(r)
    }

    // ---- keeping the screen in step -------------------------------------------

    private fun setDocument(list: List<Stroke>) {
        sketch.clear()
        for (s in list) sketch.add(s)
    }

    private fun refreshScene() {
        /*
         * REFLECTIONS FIRST, BECAUSE EVERYTHING ELSE HERE IS DOWNSTREAM OF
         * THEM. This is the one function every change to the document passes
         * through, which is exactly why it is where the mirror is kept in
         * step: hanging it off the individual tools instead would mean every
         * new tool had to remember, and the tool that forgot would leave half
         * a drawing behind.
         *
         * What moved is invalidated by name. A reflection's points change
         * under a mesh the renderer is holding by identity, and a mesh nobody
         * told would go on drawing the curve where it used to be.
         */
        for (s in Mirror.resync(sketch)) renderer.invalidate(s)
        pushFold()
        pushStrokes()
        /*
         * THE PANEL COUNTS CURVES, SO IT HAS TO BE TOLD WHEN THERE ARE MORE.
         *
         * refreshGroups was called only by the group actions themselves — new,
         * delete, rename, assign — so drawing a stroke, erasing one, or
         * undoing either left the numbers on the rows at whatever they were
         * the last time somebody touched the panel. Open the Curves tab after
         * ten minutes of drawing and it still claimed the count from before.
         *
         * It also carries refreshControls, which is why that is no longer
         * called separately here.
         */
        refreshGroups()
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
        /* how strongly each group draws, alongside what there is to draw:
           the renderer holds the curves by identity and the fades by id, and
           they have to arrive together or a faded group flashes at full */
        renderer.setGroupFade(
            sketch.groups.associate { it.id to it.opacity.toFloat() },
            sketch.activeGroup ?: 0,
        )
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

        /*
         * A PIXEL IS NOT A UNIT OF HAND MOVEMENT.
         *
         * Orbit and the lens are ported from a web build whose deltas were CSS
         * pixels; here they arrive as device pixels, so on a 3x phone the same
         * swipe turned the camera three times as far and the whole thing
         * handled like ice. They are converted; PAN IS NOT, because panBy
         * already turns pixels into world units through the viewport and its
         * whole contract is that the sketch keeps up with your finger.
         */
        val ddx = dx / density
        val ddy = dy / density

        /* THREE FINGERS SET THE LENS, and nothing else: a focal change that
           also orbited would be two things at once, and it is a vertical
           gesture on purpose.
           FACT, and the guess it replaces was right: "swipe up to increase
           the field of view (FOV) or swipe down to reduce it. The FOV can be
           adjusted from 10mm to 500mm." Naming the range in MILLIMETRES is
           what settles it — the number being increased is the focal length,
           so up is a longer lens. Tune's limits are already that 10 to 500. */
        if (navFingers >= 3) {
            lastGestureOrbited = false
            camera.focal = clamp(
                camera.focal * exp(-ddy.toDouble() * 0.006),
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
        if (lastGestureOrbited) camera.orbitBy(ddx.toDouble(), ddy.toDouble())
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
        camera.addSpin(
            -dx.toDouble() / density * Tune.ORBIT_PER_DP,
            -dy.toDouble() / density * Tune.ORBIT_PER_DP,
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
        if (diagOn) {
            diagValues["hover"] = "yes"
            diagValues["pressure"] = String.format("%.2f", pressure)
            pushDiag()
        }
        showHoverNib(x, y)
    }

    override fun onHoverExit() {
        if (diagOn) { diagValues["hover"] = "no"; pushDiag() }
        chrome.hideHoverNib()
    }

    /**
     * THE NIB, AT THE SIZE IT WILL ACTUALLY BE.
     *
     * A brush size is in millimetres of WORLD, so how big the mark comes out
     * depends on how far away the thing you are drawing on is — and in a 3D
     * sketch that is not something you can judge by looking. Measuring it
     * where the pen is pointing is the whole value of the preview: the same
     * 14mm brush is a broad sweep on a guide under your nose and a hairline on
     * one across the room, and finding that out after the stroke has landed is
     * finding out too late.
     *
     * So the scale comes from the point the pen would actually hit — the
     * guide if there is one under it, the draw plane if not — rather than from
     * the pivot, which is the cheap answer and wrong by exactly the amount
     * that matters.
     */
    private fun showHoverNib(x: Float, y: Float) {
        if (hideUi || !hoverNibOn) return
        // only the tools that lay ink down have a nib to promise
        if (tool != Tool.DRAW && tool != Tool.SHAPE) { chrome.hideHoverNib(); return }

        camera.rayFrom(x.toDouble(), y.toDouble(), penRay)
        val at = guides.active
            ?.let { GuidePainting.project(it, penRay, clampOffSurface = clampOff)?.point }
            ?: camera.planePoint(x.toDouble(), y.toDouble(), scratch)
            ?: run { chrome.hideHoverNib(); return }

        val proto = Stroke(brush = brush, baseRadius = sizeMM * MM * 0.5)
        val perWorld = 1.0 / camera.pxToWorldAt(at)
        chrome.setHoverNib(
            x, y,
            (StrokeGeometry.halfWidth(proto, proto.baseRadius) * perWorld).toFloat(),
            (StrokeGeometry.halfThick(proto, proto.baseRadius) * perWorld).toFloat(),
            proto.cfg.square,
            argbOf(color),
        )
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

        /**
         * The palettes you made, as `name=argb,argb` lines.
         *
         * In preferences rather than in the sketch file: a favourite is a
         * property of the person, not of the drawing, and one that vanished
         * when you opened somebody else's file would not be worth keeping.
         */
        const val PREF_PALETTES = "colorPalettes"

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

        /** Where the works live, one file each. */
        const val WORKS = "works"

        /** Which work is open, by id. */
        const val PREF_WORK = "currentWork"
    }
}
