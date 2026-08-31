package art.plume.anvil

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import art.plume.core.ColorSpace
import art.plume.core.DocumentEnv
import art.plume.core.Rgba
import art.plume.core.Tune

/**
 * Plume's tool set, keyed by the `data-tool` value the web build uses, so a
 * button here and a button there can be compared by name.
 *
 * Six of these are *partners*: Draw/Shape, Select/Lasso and Erase/Vacuum share
 * one slot in the tool pill and swap on a repeat tap (spec D.1). The partner
 * stays addressable — on a phone the pill is a three-across grid with room for
 * all of them.
 */
enum class Tool(val key: String, val icon: String) {
    DRAW("draw", "draw"),
    SHAPE("shape", "shape"),
    SELECT("select", "select"),
    LASSO("lasso", "lasso"),
    SMOOTH("smooth", "smooth"),
    FILL("fill", "fill"),
    ERASE("erase", "erase"),
    VACUUM("vacuum", "vacuum"),
    GUIDE("guide", "guide"),
    FLATGUIDE("flatguide", "flatguide"),
    BEND("bend", "bend"),
    LOFT("loft", "loft"),
    PRIM("prim", "solid"),
    LIQUIFY("liquify", "liquify"),
    INJECT("inject", "inject"),
    EYEDROP("eyedrop", "pick"),
}

/**
 * The Scene tab's switches.
 *
 * Separate from [Action] because these are STATE the chrome renders back,
 * not one-shot commands: each one has an on and an off that the panel has to
 * show, and folding them into Action would mean Action carried both meanings.
 */
enum class EnvToggle { GRID, AXIS, FOG, SHADED, RENDER, SHADOW, TOON, DOF, GRAIN, PIXEL }

/** The settings modal's switches — input behaviour and the view. */
enum class InputToggle {
    FINGER, AUTO_GUIDE, ISOLATE, CLAMP, HOLD_SHAPE, STABLE, ORTHO, THEME, HIDE_UI,
}

/** Everything a chrome button asks for that is not a change of tool. */
enum class Action {
    HOME, EXPORT, MENU, HELP,
    UNDO, REDO,
    MIRROR, STAGE,
    GUIDE_BEND, GUIDE_SAVE, GUIDE_CLOSE,
    DUPLICATE, DUPLICATE_MIRROR, LIQUIFY, DELETE,
    PRESSURE,
    NEW, SAVE, OPEN, CLEAR,
}

/**
 * Plume's interface, rebuilt in Android views.
 *
 * This is a port, not an interpretation. Every panel below is one of the web
 * build's `.panel` divs, in the same corner, at the same offset, holding the
 * same buttons in the same order. Where a number appears it came out of the
 * stylesheet; where a behaviour appears it came out of ui.js.
 *
 * The one thing that *looks* like a departure and is not: on a screen narrower
 * than 720dp the rails become bottom sheets over a permanent dock. That is not
 * an Android concession — it is `body.compact`, which Plume itself switches to
 * at the same width, for the reason its stylesheet gives: "phones. Rails become
 * bottom sheets; the dock is the only permanent chrome."
 *
 * Chrome knows nothing about strokes, guides or the camera. It raises [onTool]
 * and [onAction] and it renders whatever state is pushed back into it, so the
 * whole of the interface can be read in this one file and the whole of the
 * behaviour in MainActivity.
 */
class Chrome(private val act: Activity, val t: Tokens) {

    // ---- what the activity listens to ------------------------------------

    var onTool: (Tool) -> Unit = {}
    var onAction: (Action) -> Unit = {}
    var onSizeMm: (Double) -> Unit = {}
    var onOpacity: (Double) -> Unit = {}
    var onGuideOpacity: (Double) -> Unit = {}
    var onBrush: (String) -> Unit = {}
    var onColor: (Int) -> Unit = {}

    /** The Scene tab. Each hands back what changed; the chrome renders the rest. */
    var onEnv: (EnvToggle) -> Unit = {}
    var onLight: (az: Double, alt: Double) -> Unit = { _, _ -> }
    var onLightLevels: () -> Unit = {}
    var onFx: () -> Unit = {}
    var onPickBackground: () -> Unit = {}
    var onPickLightColour: () -> Unit = {}

    /** The colour card. [onHex] gets raw text; a bad one leaves the colour alone. */
    var onHex: (String) -> Unit = {}
    var onWheel: (Rgba) -> Unit = {}
    var onEyedrop: () -> Unit = {}

    /** The Curves tab. */
    var onGroupPick: (Int) -> Unit = {}
    var onGroupRename: (id: Int, name: String) -> Unit = { _, _ -> }
    var onGroupSelect: (Int) -> Unit = {}
    var onGroupAssign: (Int) -> Unit = {}
    var onGroupVisible: (id: Int, visible: Boolean) -> Unit = { _, _ -> }
    var onGroupNew: () -> Unit = {}
    var onGroupDuplicate: () -> Unit = {}
    var onGroupDelete: () -> Unit = {}
    var onSelectAll: () -> Unit = {}

    /** The liquify strip. Its three numbers are dragged, like everything else. */
    var onLiquifyMode: (String) -> Unit = {}
    var onLiquifyValue: (which: String, value: Double) -> Unit = { _, _ -> }
    var onLiquifyApply: () -> Unit = {}
    var onLiquifyClose: () -> Unit = {}

    /** The settings modal. */
    var onInput: (InputToggle) -> Unit = {}
    var onStable: (Double) -> Unit = {}
    var onRadial: (Int) -> Unit = {}
    var onFocal: (Double) -> Unit = {}
    var onView: (Int) -> Unit = {}

    /** `#pressSeg` — the brush rail's pressure toggle and its target. */
    var onPressure: () -> Unit = {}
    var onPressureTarget: (String) -> Unit = {}

    /** The staging bar: Loft's tension, a primitive's segments and taper. */
    var onStageValue: (which: Int, value: Double) -> Unit = { _, _ -> }
    var onPrimKind: (String) -> Unit = {}
    var onStageDone: () -> Unit = {}
    var onStageCancel: () -> Unit = {}

    val root = FrameLayout(act)

    // ---- state the chrome renders ----------------------------------------

    private var tool = Tool.DRAW
    private var sizeMm = 14.0
    private var opacity = 1.0
    private var brush = "pen"
    private var inkColor = Color.rgb(27, 28, 33)
    private var guideName = ""
    private var guideActive = false
    private var guideOpacity = 0.42
    private var selectionCount = 0
    private var compact = false

    /** What the staging bar is showing, if anything. */
    private var staging: Staging? = null

    /*
     * The Scene tab's model. Held here and pushed back by setEnvironment so a
     * drag on a readout can update the number under the finger without waiting
     * for the round trip through the document.
     */
    private var envGrid = true
    private var envAxis = false
    private var envFog = false
    private var envShaded = true
    private var envRender = false
    private var envShadow = true
    private var envToon = false
    private var envDof = false
    private var envGrain = false
    private var envPixel = false
    private var lightIntensity = 1.0
    private var lightAmbient = 0.66
    private var fstop = 5.6
    private var grainLevel = 35.0
    private var pixelSize = 4.0
    private var backgroundColor = Color.rgb(236, 234, 243)
    private var lightColor = Color.WHITE

    /** Draw/Shape, Select/Lasso, Erase/Vacuum — which side of each pair shows. */
    private val partner = mapOf(
        Tool.DRAW to Tool.SHAPE, Tool.SHAPE to Tool.DRAW,
        Tool.SELECT to Tool.LASSO, Tool.LASSO to Tool.SELECT,
        Tool.ERASE to Tool.VACUUM, Tool.VACUUM to Tool.ERASE,
    )
    private val shownSide = HashMap<Tool, Tool>()

    // ---- the panels ------------------------------------------------------

    private val topLeft = panel(act, t)
    private val helpPanel = panel(act, t)
    private val viewInfo = panel(act, t)
    private val toolPill = panel(act, t)
    private val railTab = panel(
        act, t, corners = floatArrayOf(0f, 0f, t.rIco, t.rIco, t.rIco, t.rIco, 0f, 0f),
    )
    private val brushRail = panel(act, t)
    private val undoPill = panel(act, t)
    private val ctxBar = panel(act, t)
    private val selBar = panel(act, t)
    private val liquifyPanel = panel(act, t)
    private val stagePanel = panel(act, t, large = true)
    private val brushGrid = panel(act, t, large = true)
    private val slidePop = panel(act, t, large = true)
    private val colorCard = panel(act, t, large = true)
    private val sysMenu = panel(act, t, large = true)
    private val dock = panel(act, t, radius = 0f)
    private val scrim = View(act)
    private val toastCard = ToastCard(act, t)

    private val toolButtons = HashMap<Tool, IcoButton>()
    private val brushTiles = HashMap<String, IcoButton>()
    private val icons = HashMap<String, IcoButton>()
    private val dockButtons = HashMap<String, TextButton>()
    private val primButtons = HashMap<String, TextButton>()
    private val pressButtons = HashMap<String, TextButton>()

    /* Declared up here, not beside sliderRow: init{} builds the popover, and a
       property initialiser that runs after init{} would still be null then. */
    private val sliders = ArrayList<Pair<HSlider, () -> Double>>()

    private lateinit var ctxHint: TextView
    private lateinit var guideBar: LinearLayout
    private lateinit var guideNameLabel: TextView
    private lateinit var guideOpacityBar: HSlider
    private lateinit var sizeVal: DragValue
    private lateinit var opacityVal: DragValue
    private lateinit var colorDot: View
    private lateinit var vFocal: TextView
    private lateinit var vProj: TextView
    private lateinit var vCount: TextView
    private lateinit var sizePopVal: TextView
    private lateinit var opacityPopVal: TextView
    private lateinit var stageTabs: Tabs
    private lateinit var sceneOptions: OptionGrid
    private lateinit var fxOptions: OptionGrid
    private lateinit var toonButton: TextButton
    private lateinit var lightPad: LightPad
    private lateinit var intensityVal: DragValue
    private lateinit var ambientVal: DragValue
    private lateinit var fstopVal: DragValue
    private lateinit var grainVal: DragValue
    private lateinit var pixelVal: DragValue
    private lateinit var bgSwatch: View
    private lateinit var lightSwatch: View
    private lateinit var hexField: EditText
    private lateinit var colorWheel: ColorWheel
    private lateinit var groupList: LinearLayout
    private lateinit var lqSize: DragValue
    private lateinit var lqRange: DragValue
    private lateinit var lqStrength: DragValue
    private val lqModes = HashMap<String, IcoButton>()
    private var lqMode = "push"
    private var lqSizeV = 120.0
    private var lqRangeV = 60.0
    private var lqStrengthV = 55.0

    /* the settings modal's model */
    private var optFinger = true
    private var optAutoGuide = true
    private var optIsolate = true
    private var optClamp = true
    private var optHoldShape = true
    private var optStable = true
    private var optOrtho = false
    private var optHideUi = false
    private var stableAmt = Tune.STABLE_DEFAULT
    private var radialAmt = 1
    private var focalMm = 50.0
    private var saveText = ""
    private var symmetryOn = false

    /** The group being renamed, so a refresh cannot yank the field away. */
    private var renaming: Int? = null
    private lateinit var inputGrid: OptionGrid
    private lateinit var viewGrid: OptionGrid
    private lateinit var stableBar: HSlider
    private lateinit var radialBar: HSlider
    private lateinit var focalBar: HSlider
    private lateinit var stableValue: TextView
    private lateinit var radialValue: TextView
    private lateinit var focalValue: TextView
    private lateinit var saveState: TextView
    private lateinit var pressRow: LinearLayout
    private var pressureOn = true
    private var pressureTarget = "size"
    private lateinit var stageBar: LinearLayout
    private lateinit var stageRow2: LinearLayout
    private lateinit var primKinds: LinearLayout
    private lateinit var stageLabel: TextView
    private lateinit var stageLabel2: TextView
    private lateinit var stageSlider: HSlider
    private lateinit var stageSlider2: HSlider
    private lateinit var stageValue: TextView
    private lateinit var stageValue2: TextView

    /** `POPOVERS` in ui.js: only one of these is ever open. */
    private val popovers = ArrayList<View>()
    private var railHidden = false

    /*
     * Which sheet is open, tracked rather than read back off translationY: a
     * sheet that has not been laid out yet also sits at 0, so measuring it
     * would call every sheet open on the first frame.
     */
    private var openSheet: LinearLayout? = null

    init {
        buildTopLeft()
        buildViewInfo()
        buildToolPill()
        buildBrushRail()
        buildUndoPill()
        buildCtxBar()
        buildSelBar()
        buildLiquifyPanel()
        buildStagePanel()
        buildDock()
        buildBrushGrid()
        buildSlidePop()
        buildColorCard()
        buildSysMenu()
        place()
        applyMode()
        refresh()
    }

    // ======================================================================
    // construction
    // ======================================================================

    /** An `.ico` button that reports one Action. */
    private fun ico(name: String, act1: Action, small: Boolean = false): IcoButton =
        IcoButton(act, t, if (small) IcoButton.SIZE_SMALL else IcoButton.SIZE_NORMAL)
            .icon(name)
            .also {
                it.setOnClickListener { _ -> onAction(act1) }
                icons[name] = it
            }

    /** An `.ico` button that selects a tool, with D.1's repeat-tap partner swap. */
    private fun toolIco(which: Tool): IcoButton =
        IcoButton(act, t).icon(which.icon).also { b ->
            b.dot = partner.containsKey(which)
            b.setOnClickListener {
                val alt = partner[which]
                /*
                 * D.1: a repeat tap on an active tool swaps it for its partner.
                 * On a phone every partner already has a slot of its own, so a
                 * repeat tap there would swap a button the user can see for one
                 * they can also see — confusing rather than compact.
                 */
                if (alt != null && tool == which && !compact) select(alt) else select(which)
            }
            toolButtons[which] = b
        }

    private fun buildTopLeft() {
        topLeft.addView(ico("grid", Action.HOME))
        topLeft.addView(ico("export", Action.EXPORT))
        topLeft.addView(ico("menu", Action.MENU))
        helpPanel.addView(ico("help", Action.HELP))
    }

    /**
     * `#viewInfo` — lens, projection, pivot and curve count. The stylesheet
     * explains why it is on the top strip and not along the bottom: "the
     * bottom bar is centred and grows when a guide is active, which walked it
     * straight over a bottom-left readout."
     */
    private fun buildViewInfo() {
        fun lab(s: String) = TextView(act).apply {
            text = s; setTextColor(t.dim); textSize = 11f
        }
        fun value(s: String) = TextView(act).apply {
            text = s; setTextColor(t.ink); textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        vFocal = value("50")
        vProj = value("Persp")
        vCount = value("0")
        val pad = t.dp(13f)
        viewInfo.setPadding(pad, t.dp(7f), pad, t.dp(7f))
        viewInfo.addView(lab("lens "))
        viewInfo.addView(vFocal)
        viewInfo.addView(lab("mm   "))
        viewInfo.addView(vProj)
        viewInfo.addView(lab("   "))
        viewInfo.addView(vCount)
        viewInfo.addView(lab(" curves"))
    }

    private fun buildToolPill() {
        for (pair in listOf(Tool.DRAW to Tool.SHAPE, Tool.SELECT to Tool.LASSO)) {
            toolPill.addView(toolIco(pair.first))
            toolPill.addView(toolIco(pair.second))
            shownSide[pair.first] = pair.first
        }
        toolPill.addView(toolIco(Tool.SMOOTH))
        toolPill.addView(toolIco(Tool.FILL))
        toolPill.addView(toolIco(Tool.ERASE))
        toolPill.addView(toolIco(Tool.VACUUM))
        shownSide[Tool.ERASE] = Tool.ERASE
        toolPill.addView(divider(act, t))
        toolPill.addView(ico("mirror", Action.MIRROR))
        toolPill.addView(ico("stage", Action.STAGE))
    }

    /**
     * `#brush` — deliberately one column wide. The stylesheet's note: "colour,
     * size, opacity, pressure, sampler. Brush TYPE is a popover, the way
     * Feather keeps the rail to one column."
     */
    private fun buildBrushRail() {
        brushRail.orientation = LinearLayout.VERTICAL
        brushRail.gravity = Gravity.CENTER_HORIZONTAL
        val p = t.px(R.dimen.padRail)
        brushRail.setPadding(p, p, p, p)

        val typeBtn = IcoButton(act, t).icon("brush")
        typeBtn.setOnClickListener { togglePopover(brushGrid) }
        brushRail.addView(typeBtn)
        icons["brushType"] = typeBtn

        colorDot = View(act).apply {
            layoutParams = LinearLayout.LayoutParams(
                t.px(R.dimen.brushDot), t.px(R.dimen.brushDot),
            ).apply { topMargin = t.px(R.dimen.gapRail) }
            setOnClickListener { togglePopover(colorCard) }
        }
        brushRail.addView(colorDot)
        brushRail.addView(separator(act, t))

        val sizeBtn = IcoButton(act, t).icon("size")
        sizeBtn.setOnClickListener { togglePopover(slidePop) }
        brushRail.addView(sizeBtn)
        /*
         * SIZE_PER_PX = 0.011 and the multiply, not an add: the same travel is
         * the same PROPORTION whether the brush is 2mm or 200mm, which is the
         * only way one gesture can cover a 1..300 range.
         */
        sizeVal = DragValue(
            act, t, logarithmic = true, rate = 0.011,
            get = { sizeMm },
            set = { v -> sizeMm = v.coerceIn(Tune.BRUSH_MIN_MM, Tune.BRUSH_MAX_MM); onSizeMm(sizeMm); refresh() },
        )
        sizeVal.setOnClickListener { togglePopover(slidePop) }
        brushRail.addView(sizeVal, railValueParams())

        val opacityBtn = IcoButton(act, t).icon("opacity")
        opacityBtn.setOnClickListener { togglePopover(slidePop) }
        brushRail.addView(opacityBtn)
        opacityVal = DragValue(
            act, t, logarithmic = false, rate = 0.004,
            get = { opacity },
            set = { v -> opacity = v.coerceIn(0.05, 1.0); onOpacity(opacity); refresh() },
        )
        opacityVal.setOnClickListener { togglePopover(slidePop) }
        brushRail.addView(opacityVal, railValueParams())

        brushRail.addView(separator(act, t))
        val press = IcoButton(act, t, IcoButton.SIZE_SMALL).icon("brush").apply {
            setOnClickListener { onPressure() }
        }
        icons["pressure"] = press
        brushRail.addView(press)
        val inject = IcoButton(act, t, IcoButton.SIZE_SMALL).icon("inject")
        inject.setOnClickListener { select(Tool.INJECT) }
        brushRail.addView(inject)
        toolButtons[Tool.INJECT] = inject

        /* `#railTab` — the edge tab that slides the rail out of the way. */
        railTab.addView(
            IcoButton(act, t, IcoButton.SIZE_TAB).icon("chev").apply {
                imageTintList = android.content.res.ColorStateList.valueOf(t.dim)
            },
        )
        railTab.setPadding(0, 0, 0, 0)
        railTab.setOnClickListener { setRailHidden(!railHidden) }
    }

    private fun railValueParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = t.dp(2f) }

    private fun buildUndoPill() {
        undoPill.addView(ico("undo", Action.UNDO))
        undoPill.addView(ico("redo", Action.REDO))
    }

    /**
     * `#ctx` — the bottom context menu. FACT (D.1): the guide tools live here,
     * not in the tool pill.
     */
    private fun buildCtxBar() {
        for (g in listOf(Tool.GUIDE, Tool.FLATGUIDE, Tool.BEND, Tool.LOFT, Tool.PRIM)) {
            ctxBar.addView(toolIco(g))
        }
        ctxHint = TextView(act).apply {
            setTextColor(t.dim)
            textSize = 12f
            setPadding(t.dp(12f), 0, t.dp(12f), 0)
        }
        ctxBar.addView(ctxHint)

        guideBar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        guideBar.addView(divider(act, t))
        guideNameLabel = TextView(act).apply {
            setTextColor(t.dim2); textSize = 10f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(0, 0, t.dp(6f), 0)
        }
        guideBar.addView(guideNameLabel)
        guideBar.addView(
            TextButton(act, t, filled = true, small = true).apply {
                text = act.getString(R.string.guide_bend)
                setOnClickListener { onAction(Action.GUIDE_BEND) }
            },
        )
        /*
         * The web build's guide opacity is a horizontal range capped at 92%:
         * a guide you cannot see past is a guide you cannot draw on.
         */
        guideOpacityBar = HSlider(act, t, 0.0, 0.92) { v ->
            guideOpacity = v; onGuideOpacity(v); refresh()
        }
        guideOpacityBar.layoutParams = LinearLayout.LayoutParams(
            t.dp(76f), t.dp(22f),                 // style="width:76px" on #guideOpacity
        ).apply { marginStart = t.dp(6f) }
        guideBar.addView(guideOpacityBar)
        guideBar.addView(ico("eye", Action.GUIDE_SAVE, small = true))
        guideBar.addView(ico("close", Action.GUIDE_CLOSE, small = true))
        ctxBar.addView(guideBar)

        /*
         * `#ctxSlider` — the staging strip. Loft and Primitives both build a
         * guide you are still adjusting, so the bar grows a slider or two and a
         * Done/Cancel pair rather than committing on the first tap. The guide
         * bar and this are mutually exclusive: you are either editing a live
         * guide or staging a new one.
         */
        stageBar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        stageBar.addView(divider(act, t))
        stageLabel = lab("")
        stageBar.addView(stageLabel)
        stageSlider = HSlider(act, t, 0.0, 1.0) { v -> onStageValue(0, v); refresh() }
        stageSlider.layoutParams = LinearLayout.LayoutParams(t.dp(90f), t.dp(22f))
            .apply { marginStart = t.dp(6f) }
        stageBar.addView(stageSlider)
        stageValue = valueText()
        stageBar.addView(stageValue)

        stageRow2 = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        stageLabel2 = lab(act.getString(R.string.taper))
        stageRow2.addView(stageLabel2)
        stageSlider2 = HSlider(act, t, 0.0, 1.0) { v -> onStageValue(1, v); refresh() }
        stageSlider2.layoutParams = LinearLayout.LayoutParams(t.dp(70f), t.dp(22f))
            .apply { marginStart = t.dp(6f) }
        stageRow2.addView(stageSlider2)
        stageValue2 = valueText()
        stageRow2.addView(stageValue2)
        stageBar.addView(stageRow2)

        primKinds = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        for ((key, label) in listOf(
            "cube" to R.string.prim_cube, "pyramid" to R.string.prim_pyramid,
            "sphere" to R.string.prim_sphere, "torus" to R.string.prim_torus,
            "tube" to R.string.prim_tube,
        )) {
            val b = TextButton(act, t, filled = true, small = true).apply {
                text = act.getString(label)
                setOnClickListener { onPrimKind(key) }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = t.dp(2f) }
            }
            primButtons[key] = b
            primKinds.addView(b)
        }
        stageBar.addView(primKinds)

        stageBar.addView(
            TextButton(act, t, small = true).apply {
                text = act.getString(R.string.done)
                on = true
                setOnClickListener { onStageDone() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = t.dp(6f) }
            },
        )
        stageBar.addView(
            TextButton(act, t, filled = true, small = true).apply {
                text = act.getString(R.string.cancel)
                setOnClickListener { onStageCancel() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = t.dp(2f) }
            },
        )
        ctxBar.addView(stageBar)
    }

    /** `label.lab` — the small uppercase caption the bars use. */
    private fun lab(text: String) = TextView(act).apply {
        this.text = text
        setTextColor(t.dim2)
        textSize = 10f
        letterSpacing = 0.08f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(t.dp(6f), 0, 0, 0)
    }

    private fun valueText() = TextView(act).apply {
        setTextColor(t.dim)
        textSize = 11f
        minWidth = t.dp(34f)
        gravity = Gravity.END
        setPadding(t.dp(4f), 0, 0, 0)
    }

    /**
     * `#selBar` — duplicate, duplicate symmetrically, liquify, delete.
     *
     * "Delete is the one red thing in the whole interface, which is exactly
     * how the reference uses colour."
     */
    private fun buildSelBar() {
        selBar.addView(ico("dup", Action.DUPLICATE))
        selBar.addView(ico("dupmir", Action.DUPLICATE_MIRROR))
        selBar.addView(
            IcoButton(act, t).icon("liquify").also {
                it.setOnClickListener { _ -> select(Tool.LIQUIFY) }
                toolButtons[Tool.LIQUIFY] = it
            },
        )
        selBar.addView(ico("trash", Action.DELETE).apply { danger = true })
        selBar.visibility = View.GONE
    }

    /**
     * `#stagePanel` — Curves, Import and Scene.
     *
     * Scene is complete: it is what Phase 5 exists for. The other two tabs say
     * what is missing rather than showing an empty list, because a Curves tab
     * with nothing in it looks like a sketch with no curves in it.
     */
    /**
     * `#liquifyPanel` — a strip above the context bar rather than a card at
     * the right, and the stylesheet says why: liquify always has a selection,
     * and a selection always has the transform panel, which lives on the right.
     */
    private fun buildLiquifyPanel() {
        liquifyPanel.setPadding(t.dp(10f), t.dp(8f), t.dp(10f), t.dp(8f))
        liquifyPanel.addView(
            TextView(act).apply {
                text = act.getString(R.string.liquify)
                setTextColor(t.dim2)
                textSize = 10f
                letterSpacing = 0.1f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 0, t.dp(6f), 0)
            },
        )
        for ((key, icon) in listOf(
            "push" to "lq_push", "pinch" to "lq_pinch", "comb" to "lq_comb",
        )) {
            val b = IcoButton(act, t).icon(icon).apply {
                setOnClickListener { onLiquifyMode(key) }
                layoutParams = LinearLayout.LayoutParams(t.dp(40f), t.dp(34f))
                    .apply { marginEnd = t.dp(4f) }
            }
            lqModes[key] = b
            liquifyPanel.addView(b)
        }
        /*
         * FACT: size, range and strength are each "adjusted by sliding up or
         * down". Size is a screen radius so it moves geometrically like the
         * brush; the other two are percentages and move linearly.
         */
        lqSize = DragValue(
            act, t, logarithmic = true, rate = 0.011,
            get = { lqSizeV },
            set = { v -> lqSizeV = v.coerceIn(8.0, 600.0); onLiquifyValue("size", lqSizeV); refresh() },
        )
        lqRange = DragValue(
            act, t, logarithmic = false, rate = 0.4,
            get = { lqRangeV },
            set = { v -> lqRangeV = v.coerceIn(0.0, 100.0); onLiquifyValue("range", lqRangeV); refresh() },
        )
        lqStrength = DragValue(
            act, t, logarithmic = false, rate = 0.4,
            get = { lqStrengthV },
            set = { v ->
                lqStrengthV = v.coerceIn(1.0, 100.0)
                onLiquifyValue("strength", lqStrengthV); refresh()
            },
        )
        for ((label, value) in listOf(
            R.string.lq_size to lqSize, R.string.lq_range to lqRange,
            R.string.lq_strength to lqStrength,
        )) {
            liquifyPanel.addView(
                TextView(act).apply {
                    text = act.getString(label)
                    setTextColor(t.dim)
                    textSize = 11f
                    setPadding(t.dp(6f), 0, t.dp(4f), 0)
                },
            )
            value.layoutParams = LinearLayout.LayoutParams(
                t.dp(38f), ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            liquifyPanel.addView(value)
        }
        liquifyPanel.addView(
            TextButton(act, t, filled = true, small = true).apply {
                text = act.getString(R.string.apply)
                setOnClickListener { onLiquifyApply() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = t.dp(8f) }
            },
        )
        liquifyPanel.addView(
            IcoButton(act, t, IcoButton.SIZE_SMALL).icon("close").apply {
                setOnClickListener { onLiquifyClose() }
            },
        )
        liquifyPanel.visibility = View.GONE
    }

    private fun buildStagePanel() {
        stagePanel.orientation = LinearLayout.VERTICAL
        val p = t.px(R.dimen.padCard)
        stagePanel.setPadding(p, p, p, p)
        stagePanel.addView(head(act.getString(R.string.stage)) { toggleStage() })

        val bodies = ArrayList<View>()
        stageTabs = Tabs(
            act, t,
            listOf(
                act.getString(R.string.tab_curves),
                act.getString(R.string.tab_import),
                act.getString(R.string.tab_scene),
            ),
        ) { i -> for ((j, b) in bodies.withIndex()) b.visibility = if (i == j) View.VISIBLE else View.GONE }
        stagePanel.addView(
            stageTabs,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = t.dp(10f) },
        )

        bodies.add(buildCurvesTab())
        bodies.add(gap(R.string.not_yet_import))
        bodies.add(buildSceneTab())
        for ((i, b) in bodies.withIndex()) {
            b.visibility = if (i == 2) View.VISIBLE else View.GONE
            stagePanel.addView(b)
        }
        stageTabs.selected = 2
        stagePanel.visibility = View.GONE
    }

    /** `.empty` — the note that stands in for a list nothing has filled yet. */
    private fun gap(res: Int): View = TextView(act).apply {
        text = act.getString(res)
        setTextColor(t.dim2)
        textSize = 11f
        setLineSpacing(0f, 1.55f)
        setPadding(t.dp(1f), t.dp(3f), t.dp(1f), t.dp(3f))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    /**
     * `#bodyGroup` — one row per group: the name, how many curves are in it,
     * the arrow that moves the selection in, and the eye.
     */
    private fun buildCurvesTab(): View {
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            IcoButton(act, t, IcoButton.SIZE_SMALL).icon("trash").apply {
                danger = true
                setOnClickListener { onGroupDelete() }
            },
        )
        head.addView(
            IcoButton(act, t, IcoButton.SIZE_SMALL).icon("dup").apply {
                setOnClickListener { onGroupDuplicate() }
            },
        )
        head.addView(
            View(act),
            LinearLayout.LayoutParams(0, 1, 1f),
        )
        head.addView(
            IcoButton(act, t, IcoButton.SIZE_SMALL).icon("plus").apply {
                setOnClickListener { onGroupNew() }
            },
        )
        col.addView(head, matchWrap(0))

        groupList = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(groupList, matchWrap(t.dp(6f)))

        col.addView(gap(R.string.group_hint))

        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, click) in listOf<Pair<Int, () -> Unit>>(
            R.string.select_all to { onSelectAll() },
            R.string.duplicate to { onAction(Action.DUPLICATE) },
            R.string.delete to { onAction(Action.DELETE) },
        )) {
            row.addView(
                TextButton(act, t, filled = true, small = true).apply {
                    text = act.getString(label)
                    setOnClickListener { click() }
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                    ).apply { marginEnd = t.dp(4f) }
                },
            )
        }
        col.addView(row, matchWrap(t.dp(6f)))
        return col
    }

    /** What the Curves tab is showing. */
    class GroupRow(
        val id: Int,
        val name: String,
        val count: Int,
        val visible: Boolean,
        val active: Boolean,
    )

    fun setGroups(rows: List<GroupRow>) {
        if (renaming != null) return          // never yank the box out mid-rename
        groupList.removeAllViews()
        for (g in rows) groupList.addView(groupRow(g))
    }

    /** `.grpRow` — the active one is outlined, a hidden one is dimmed. */
    private fun groupRow(g: Chrome.GroupRow): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(if (g.active) t.panel3 else t.panel2)
                cornerRadius = t.dpf(12f)
                setStroke(t.dp(1.5f), if (g.active) t.ink else 0x00000000)
            }
            setPadding(t.dp(8f), t.dp(5f), t.dp(4f), t.dp(5f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = t.dp(4f) }
        }

        val name = TextView(act).apply {
            text = g.name
            setTextColor(t.ink)
            textSize = 13f
            alpha = if (g.visible) 1f else 0.45f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(t.dp(2f), t.dp(3f), t.dp(2f), t.dp(3f))
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
            )
            /*
             * Tap the name of the group you are IN to rename it. On any other
             * row a tap selects that row first — the same slow double-tap
             * every file list uses, and it leaves the whole row as a target
             * for switching groups rather than a sliver beside the name.
             */
            setOnClickListener {
                if (g.active) beginRename(row, this, g) else onGroupPick(g.id)
            }
        }
        row.addView(name)

        row.addView(
            TextView(act).apply {
                text = if (g.count > 0) g.count.toString() else ""
                setTextColor(t.dim2)
                textSize = 10f
                alpha = if (g.visible) 1f else 0.45f
                setPadding(t.dp(2f), 0, t.dp(2f), 0)
            },
        )
        row.addView(
            IcoButton(act, t, IcoButton.SIZE_TINY).icon("enter").apply {
                setOnClickListener { onGroupAssign(g.id) }
            },
        )
        row.addView(
            IcoButton(act, t, IcoButton.SIZE_TINY)
                .icon(if (g.visible) "eye" else "eye_off").apply {
                    if (!g.visible) {
                        imageTintList = android.content.res.ColorStateList.valueOf(t.dim2)
                    }
                    setOnClickListener { onGroupVisible(g.id, !g.visible) }
                },
        )

        /* tap the row to make it active, hold to select everything in it */
        row.setOnClickListener { onGroupPick(g.id) }
        row.setOnLongClickListener { onGroupSelect(g.id); true }
        return row
    }

    /** Rename in place: the label becomes a field, and Done commits it. */
    private fun beginRename(row: LinearLayout, label: TextView, g: Chrome.GroupRow) {
        renaming = g.id
        val at = row.indexOfChild(label)
        val field = EditText(act).apply {
            setText(g.name)
            setSelection(g.name.length)
            setTextColor(t.ink)
            textSize = 13f
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            background = GradientDrawable().apply {
                setColor(t.panel)
                cornerRadius = t.dpf(7f)
                setStroke(t.dp(1.5f), t.ink)
            }
            setPadding(t.dp(4f), t.dp(3f), t.dp(4f), t.dp(3f))
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
            )
        }
        fun finish() {
            if (renaming == null) return
            renaming = null
            val text = field.text.toString().trim()
            row.removeView(field)
            row.addView(label, at)
            /* an empty name is not a rename: a row you cannot read is worse
               than the name you were trying to replace */
            if (text.isNotEmpty() && text != g.name) onGroupRename(g.id, text)
        }
        field.setOnEditorActionListener { _, _, _ -> finish(); true }
        field.setOnFocusChangeListener { _, has -> if (!has) finish() }
        row.removeView(label)
        row.addView(field, at)
        field.requestFocus()
    }

    private fun buildSceneTab(): View {
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        col.addView(labelRow(R.string.background, bgDot()))

        sceneOptions = OptionGrid(act, t, 3)
            .option("grid", act.getString(R.string.opt_grid)) { onEnv(EnvToggle.GRID) }
            .option("axis", act.getString(R.string.opt_axis)) { onEnv(EnvToggle.AXIS) }
            .option("fog", act.getString(R.string.opt_fog)) { onEnv(EnvToggle.FOG) }
            .option("shade", act.getString(R.string.opt_shade)) { onEnv(EnvToggle.SHADED) }
            .option("render", act.getString(R.string.opt_render)) { onEnv(EnvToggle.RENDER) }
            .option("shadow", act.getString(R.string.opt_shadow)) { onEnv(EnvToggle.SHADOW) }
        col.addView(sceneOptions, matchWrap(t.dp(4f)))

        /* ---- lighting ---- */
        toonButton = TextButton(act, t, filled = true, small = true).apply {
            text = act.getString(R.string.opt_toon)
            setOnClickListener { onEnv(EnvToggle.TOON) }
        }
        col.addView(labelRow(R.string.lighting, toonButton))

        val lightRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        lightPad = LightPad(act, t) { az, alt -> onLight(az, alt); refresh() }
        lightRow.addView(lightPad)
        val lightCol = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
            ).apply { marginStart = t.dp(10f) }
        }
        lightCol.addView(labelRow(R.string.colour, lightDot()))
        /*
         * The light's two numbers ride the same drag-a-readout mechanism the
         * brush size does, so there is one way to nudge a number in this app.
         * Both are linear — they are already percentages — unlike the brush
         * size, which is multiplicative because it spans 1 to 300.
         */
        intensityVal = DragValue(
            act, t, logarithmic = false, rate = 0.4,
            get = { lightIntensity * 100.0 },
            set = { v -> lightIntensity = clampTo(v / 100.0, 0.0, 3.0); onLightLevels(); refresh() },
        )
        ambientVal = DragValue(
            act, t, logarithmic = false, rate = 0.4,
            get = { lightAmbient * 100.0 },
            set = { v -> lightAmbient = clampTo(v / 100.0, 0.0, 1.0); onLightLevels(); refresh() },
        )
        lightCol.addView(labelRow(R.string.intensity, intensityVal))
        lightCol.addView(labelRow(R.string.ambient, ambientVal))
        lightRow.addView(lightCol)
        col.addView(lightRow, matchWrap(t.dp(8f)))

        /* ---- effects ---- */
        col.addView(labelRow(R.string.effects, null))
        fxOptions = OptionGrid(act, t, 3)
            .option("dof", act.getString(R.string.opt_dof)) { onEnv(EnvToggle.DOF) }
            .option("grain", act.getString(R.string.opt_grain)) { onEnv(EnvToggle.GRAIN) }
            .option("pixel", act.getString(R.string.opt_pixel)) { onEnv(EnvToggle.PIXEL) }
        col.addView(fxOptions, matchWrap(t.dp(4f)))

        /* f-stop and block size are geometric: 1.4 to 22 and 1 to 40 both span
           more than a decade, and a linear drag would spend most of its travel
           at the end where nothing changes */
        fstopVal = DragValue(
            act, t, logarithmic = true, rate = 0.011,
            get = { fstop },
            set = { v -> fstop = clampTo(v, 1.4, 22.0); onFx(); refresh() },
        )
        grainVal = DragValue(
            act, t, logarithmic = false, rate = 0.4,
            get = { grainLevel },
            set = { v -> grainLevel = clampTo(v, 0.0, 100.0); onFx(); refresh() },
        )
        pixelVal = DragValue(
            act, t, logarithmic = true, rate = 0.011,
            get = { pixelSize },
            set = { v -> pixelSize = clampTo(v, 1.0, 40.0); onFx(); refresh() },
        )
        col.addView(labelRow(R.string.fstop, fstopVal))
        col.addView(labelRow(R.string.grain_level, grainVal))
        col.addView(labelRow(R.string.block_size, pixelVal))
        return col
    }

    private fun clampTo(v: Double, lo: Double, hi: Double) = if (v < lo) lo else if (v > hi) hi else v

    private fun matchWrap(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = top }

    /** `label.lab` on the left, a control on the right. */
    private fun labelRow(label: Int, control: View?): View = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            TextView(act).apply {
                text = act.getString(label)
                setTextColor(t.dim2)
                textSize = 10f
                letterSpacing = 0.08f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                )
            },
        )
        if (control != null) {
            (control.parent as? ViewGroup)?.removeView(control)
            addView(control)
        }
        layoutParams = matchWrap(t.dp(8f))
    }

    private fun bgDot(): View = View(act).apply {
        layoutParams = LinearLayout.LayoutParams(t.dp(30f), t.dp(30f))
        setOnClickListener { onPickBackground() }
    }.also { bgSwatch = it }

    private fun lightDot(): View = View(act).apply {
        layoutParams = LinearLayout.LayoutParams(t.dp(30f), t.dp(30f))
        setOnClickListener { onPickLightColour() }
    }.also { lightSwatch = it }

    /** `.mhead` — a card title with a close button on the right. */
    private fun head(title: String, onClose: () -> Unit): View =
        LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(act).apply {
                    text = title
                    setTextColor(t.ink)
                    textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            addView(
                IcoButton(act, t, IcoButton.SIZE_SMALL).icon("close").apply {
                    setOnClickListener { onClose() }
                },
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = t.dp(6f) }
        }

    /** `#brushGrid` — two columns of 42x38 tiles, anchored beside the rail. */
    private fun buildBrushGrid() {
        val grid = GridLayout(act).apply { columnCount = 2 }
        for (name in listOf("pen", "sketch", "taper", "rectangle", "cube", "flat", "wide", "glow")) {
            val tile = IcoButton(act, t).icon("brush_$name").tile(t.panel2, t.rTile).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = t.dp(42f); height = t.dp(38f)
                    setMargins(t.dp(3f), t.dp(3f), t.dp(3f), t.dp(3f))
                }
                setOnClickListener {
                    brush = name; onBrush(name); closePopovers(); refresh()
                }
            }
            brushTiles[name] = tile
            grid.addView(tile)
        }
        brushGrid.setPadding(t.dp(8f), t.dp(8f), t.dp(8f), t.dp(8f))
        brushGrid.addView(grid)
        brushGrid.visibility = View.GONE
        popovers.add(brushGrid)
    }

    /** `#slidePop` — size 1..300, opacity 5..100. */
    private fun buildSlidePop() {
        slidePop.orientation = LinearLayout.VERTICAL
        val p = t.px(R.dimen.padPop)
        slidePop.setPadding(p, p, p, p)
        sizePopVal = TextView(act).apply { setTextColor(t.dim); textSize = 11f }
        opacityPopVal = TextView(act).apply { setTextColor(t.dim); textSize = 11f }
        slidePop.addView(
            sliderRow(sizePopVal, Tune.BRUSH_MIN_MM, Tune.BRUSH_MAX_MM, { sizeMm }) { v ->
                sizeMm = v; onSizeMm(v); refresh()
            },
        )
        slidePop.addView(
            sliderRow(opacityPopVal, 0.05, 1.0, { opacity }) { v ->
                opacity = v; onOpacity(v); refresh()
            },
        )
        /*
         * `#pressSeg` — which of the four a harder press drives. It lives in
         * this popover rather than on the rail because it is a setting you
         * choose once, not a control you reach for mid-stroke.
         */
        pressRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = t.dp(8f) }
        }
        for ((key, label) in listOf(
            "size" to R.string.press_size, "opacity" to R.string.press_opacity,
            "both" to R.string.press_both, "color" to R.string.press_colour,
        )) {
            val b = TextButton(act, t, filled = true, small = true).apply {
                text = act.getString(label)
                setOnClickListener { onPressureTarget(key) }
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                ).apply { marginEnd = t.dp(3f) }
            }
            pressButtons[key] = b
            pressRow.addView(b)
        }
        slidePop.addView(pressRow)

        slidePop.visibility = View.GONE
        popovers.add(slidePop)
    }

    private fun sliderRow(
        readout: TextView,
        min: Double,
        max: Double,
        get: () -> Double,
        set: (Double) -> Unit,
    ): View = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val bar = HSlider(act, t, min, max) { v -> set(v) }
        bar.value = get()
        addView(
            bar,
            LinearLayout.LayoutParams(0, t.dp(22f), 1f).apply { marginEnd = t.dp(8f) },
        )
        addView(readout)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = t.dp(5f) }
        sliders.add(bar to get)
    }

    /**
     * `#colorCard` — a hex row, the wheel, and the swatches.
     *
     * The wheel replaces the platform picker for the reason the web build
     * replaces the native input: a system colour dialog cannot be styled to
     * match, and dropping one into the middle of this interface is the single
     * piece of chrome that would look borrowed.
     */
    private fun buildColorCard() {
        colorCard.orientation = LinearLayout.VERTICAL
        val p = t.px(R.dimen.padPop)
        colorCard.setPadding(p, p, p, p)

        /* `#hexRow` — the field and the sample-from-sketch button */
        val hexRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(t.panel2); cornerRadius = t.dpf(11f)
            }
            setPadding(t.dp(10f), t.dp(5f), t.dp(6f), t.dp(5f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        hexField = EditText(act).apply {
            setTextColor(t.ink)
            textSize = 12f
            letterSpacing = 0.06f
            background = null
            setPadding(0, 0, 0, 0)
            isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(7))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
            )
            /*
             * Committed on Done, not on every keystroke. Parsing as you type
             * means four characters of a six-character hex is a colour, so the
             * swatch would jump somewhere wrong on the way to somewhere right.
             */
            setOnEditorActionListener { v, _, _ -> onHex(v.text.toString()); true }
        }
        hexRow.addView(hexField)
        hexRow.addView(
            IcoButton(act, t, IcoButton.SIZE_SMALL).icon("pick").apply {
                setOnClickListener { onEyedrop() }
            },
        )
        colorCard.addView(hexRow)

        colorWheel = ColorWheel(act, t) { c -> onWheel(c) }
        colorCard.addView(
            colorWheel,
            LinearLayout.LayoutParams(t.dp(ColorWheel.WHEEL_DP), t.dp(ColorWheel.WHEEL_DP))
                .apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = t.dp(10f) },
        )

        val grid = GridLayout(act).apply { columnCount = 8 }
        for (c in PALETTE) {
            grid.addView(
                View(act).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(c)
                        setStroke(t.dp(1f), t.line)
                    }
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = t.dp(19f); height = t.dp(19f)
                        setMargins(t.dp(3f), t.dp(3f), t.dp(3f), t.dp(3f))
                    }
                    setOnClickListener { inkColor = c; onColor(c); refresh() }
                },
            )
        }
        colorCard.addView(
            grid,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = t.dp(8f) },
        )
        colorCard.visibility = View.GONE
        popovers.add(colorCard)
    }

    /**
     * `#sysMenu` — Input, View and File.
     *
     * A centred modal rather than a left-anchored popover, and the stylesheet
     * says why: anchored to the menu button it sat exactly on top of the brush
     * rail.
     */
    private fun buildSysMenu() {
        scrim.setBackgroundColor(t.scrim)
        /*
         * Draw order on Android is elevation first, child order second, so a
         * flat scrim would sit UNDER every panel it is meant to cover. One dp
         * below the modal puts it over the chrome and under the card.
         */
        scrim.elevation = t.dpf(17f)
        scrim.visibility = View.GONE
        scrim.setOnClickListener { setMenu(false) }

        sysMenu.orientation = LinearLayout.VERTICAL
        val p = t.px(R.dimen.padModal)
        sysMenu.setPadding(p, p, p, p)
        sysMenu.addView(head(act.getString(R.string.settings)) { setMenu(false) })

        val body = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        body.addView(h4(R.string.input))
        inputGrid = OptionGrid(act, t, 3)
            .option("finger", act.getString(R.string.opt_finger)) { onInput(InputToggle.FINGER) }
            .option("autoguide", act.getString(R.string.opt_autoguide)) { onInput(InputToggle.AUTO_GUIDE) }
            .option("isolate", act.getString(R.string.opt_isolate)) { onInput(InputToggle.ISOLATE) }
            .option("clamp", act.getString(R.string.opt_clamp)) { onInput(InputToggle.CLAMP) }
            .option("holdshape", act.getString(R.string.opt_holdshape)) { onInput(InputToggle.HOLD_SHAPE) }
            .option("stable", act.getString(R.string.opt_stable)) { onInput(InputToggle.STABLE) }
        body.addView(inputGrid, matchWrap(t.dp(4f)))

        stableBar = HSlider(act, t, 0.0, Tune.STABLE_MAX) { v -> onStable(v); refresh() }
        stableValue = pairValue()
        body.addView(pairRow(R.string.stable_stroke, stableBar, stableValue))

        /*
         * Radial runs 1..16, and 1 reads "Off" rather than "1": one copy of a
         * stroke is no symmetry at all, and a control that says 1 invites you
         * to wonder what it is doing.
         */
        radialBar = HSlider(act, t, 1.0, 16.0) { v -> onRadial(v.toInt()); refresh() }
        radialValue = pairValue()
        body.addView(pairRow(R.string.radial, radialBar, radialValue))

        body.addView(sep())
        body.addView(h4(R.string.view))
        focalBar = HSlider(act, t, Tune.FOCAL_MIN, Tune.FOCAL_MAX) { v -> onFocal(v); refresh() }
        focalValue = pairValue()
        body.addView(pairRow(R.string.lens, focalBar, focalValue))

        viewGrid = OptionGrid(act, t, 4)
            .option("proj", act.getString(R.string.opt_ortho)) { onInput(InputToggle.ORTHO) }
            .option("theme", act.getString(R.string.opt_theme)) { onInput(InputToggle.THEME) }
            .option("hideui", act.getString(R.string.opt_hideui)) { onInput(InputToggle.HIDE_UI) }
            .option("walk", act.getString(R.string.opt_guide)) { onAction(Action.HELP) }
        body.addView(viewGrid, matchWrap(t.dp(4f)))

        val views = OptionGrid(act, t, 6)
        for ((i, name) in listOf(
            R.string.view_front, R.string.view_back, R.string.view_right,
            R.string.view_left, R.string.view_top, R.string.view_bottom,
        ).withIndex()) {
            views.option("v$i", act.getString(name)) { onView(i) }
        }
        body.addView(views, matchWrap(t.dp(4f)))

        body.addView(sep())
        body.addView(h4(R.string.file))
        val files = OptionGrid(act, t, 3)
        for ((label, a) in listOf(
            R.string.new_sketch to Action.NEW,
            R.string.save to Action.SAVE,
            R.string.open to Action.OPEN,
            R.string.export_ to Action.EXPORT,
            R.string.clear to Action.CLEAR,
        )) {
            files.option(act.getString(label), act.getString(label)) {
                setMenu(false); onAction(a)
            }
        }
        body.addView(files, matchWrap(t.dp(4f)))

        saveState = TextView(act).apply {
            setTextColor(t.dim2)
            textSize = 11f
            setPadding(t.dp(1f), t.dp(6f), 0, 0)
        }
        body.addView(saveState)

        /* the modal is taller than a phone, so it scrolls inside its card */
        sysMenu.addView(
            android.widget.ScrollView(act).apply {
                isFillViewport = true
                addView(body)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            },
        )
        sysMenu.visibility = View.GONE
    }

    /** `h4` — the small uppercase section heading. */
    private fun h4(res: Int) = TextView(act).apply {
        text = act.getString(res)
        setTextColor(t.dim2)
        textSize = 10f
        letterSpacing = 0.1f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        layoutParams = matchWrap(t.dp(8f))
    }

    /** `.sep` — a horizontal rule between sections. */
    private fun sep() = View(act).apply {
        setBackgroundColor(t.line)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, t.px(R.dimen.hair),
        ).apply { topMargin = t.dp(10f) }
    }

    /** `.pair` — a caption, a slider and a readout on one tinted row. */
    private fun pairRow(label: Int, bar: HSlider, value: TextView): View =
        LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(t.panel2); cornerRadius = t.dpf(12f)
            }
            setPadding(t.dp(10f), t.dp(3f), t.dp(3f), t.dp(3f))
            addView(
                TextView(act).apply {
                    text = act.getString(label)
                    setTextColor(t.ink)
                    textSize = 12f
                    minWidth = t.dp(78f)
                },
            )
            addView(bar, LinearLayout.LayoutParams(0, t.dp(22f), 1f))
            addView(value)
            layoutParams = matchWrap(t.dp(6f))
        }

    private fun pairValue() = TextView(act).apply {
        setTextColor(t.dim)
        textSize = 11f
        minWidth = t.dp(42f)
        gravity = Gravity.END
        setPadding(t.dp(4f), 0, t.dp(6f), 0)
    }

    /** `#dock` — the only permanent chrome on a phone. */
    private fun buildDock() {
        dock.setPadding(t.dp(8f), t.dp(7f), t.dp(8f), t.dp(7f))
        fun slot(label: Int, key: String, click: () -> Unit) {
            val b = TextButton(act, t, filled = true).apply {
                text = act.getString(label)
                textSize = 11f
                setOnClickListener { click() }
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                ).apply { marginEnd = t.dp(6f) }
            }
            dockButtons[key] = b
            dock.addView(b)
        }
        slot(R.string.undo, "undo") { onAction(Action.UNDO) }
        slot(R.string.redo, "redo") { onAction(Action.REDO) }
        slot(R.string.tools, "tools") { toggleSheet(toolPill) }
        slot(R.string.brush, "brush") { toggleSheet(brushRail) }
        slot(R.string.stage, "stage") { toggleSheet(stagePanel) }
        dock.visibility = View.GONE
    }

    // ======================================================================
    // placement — one call per `position:fixed` rule in the stylesheet
    // ======================================================================

    private fun lp(
        gravity: Int,
        left: Int = 0,
        top: Int = 0,
        right: Int = 0,
        bottom: Int = 0,
        width: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    ) = FrameLayout.LayoutParams(width, height).apply {
        this.gravity = gravity
        setMargins(left, top, right, bottom)
    }

    private fun place() {
        val e = t.px(R.dimen.edge)
        /* z order is child order in a FrameLayout, so this list IS the
           stylesheet's z-index ladder: 5 chrome · 6 tabs · 25 dock · 26/28
           popovers and docked cards · 29/30 modal · 31 slide · 50 toast */
        root.addView(ctxBar, lp(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, bottom = t.px(R.dimen.ctxBottom)))
        root.addView(toolPill, lp(Gravity.TOP or Gravity.END, top = e, right = e))
        root.addView(topLeft, lp(Gravity.TOP or Gravity.START, top = e, left = e))
        root.addView(helpPanel, lp(Gravity.TOP or Gravity.START, top = e, left = t.px(R.dimen.helpLeft)))
        root.addView(viewInfo, lp(Gravity.TOP or Gravity.START, top = e, left = t.px(R.dimen.viewInfoLeft)))
        root.addView(brushRail, lp(Gravity.START or Gravity.CENTER_VERTICAL, left = e, width = t.px(R.dimen.brushRailW)))
        root.addView(undoPill, lp(Gravity.BOTTOM or Gravity.START, left = e, bottom = t.px(R.dimen.undoBottom)))
        root.addView(selBar, lp(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, bottom = t.px(R.dimen.selBarBottom)))
        root.addView(
            railTab,
            lp(
                Gravity.START or Gravity.CENTER_VERTICAL,
                width = t.px(R.dimen.railTabW), height = t.px(R.dimen.railTabH),
            ),
        )
        root.addView(
            liquifyPanel,
            lp(
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                bottom = t.px(R.dimen.liquifyBottom),
            ),
        )
        root.addView(dock, lp(Gravity.BOTTOM, width = ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(brushGrid, lp(Gravity.START or Gravity.CENTER_VERTICAL, left = t.px(R.dimen.brushGridLeft)))
        root.addView(stagePanel, lp(Gravity.TOP or Gravity.END, top = t.px(R.dimen.stageTop), right = e, width = t.px(R.dimen.stagePanelW)))
        root.addView(scrim, lp(Gravity.CENTER, width = ViewGroup.LayoutParams.MATCH_PARENT, height = ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(sysMenu, lp(Gravity.CENTER, width = t.px(R.dimen.sysMenuW)))
        root.addView(colorCard, lp(Gravity.START or Gravity.CENTER_VERTICAL, left = t.px(R.dimen.brushGridLeft), width = t.px(R.dimen.colorCardW)))
        root.addView(slidePop, lp(Gravity.START or Gravity.CENTER_VERTICAL, left = t.px(R.dimen.brushGridLeft), width = t.px(R.dimen.slidePopW)))
        root.addView(toastCard, lp(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, bottom = t.px(R.dimen.toastBottom)))
    }

    // ======================================================================
    // desktop / compact — UI.applyMode
    // ======================================================================

    /**
     * `UI.applyMode`. COMPACT_MAX is 720, with the web build's own note on it:
     * "GUESS: below this the side rails stop fitting".
     */
    fun applyMode() {
        val narrow = act.resources.configuration.screenWidthDp < 720
        compact = narrow
        val e = t.px(if (narrow) R.dimen.edgeCompact else R.dimen.edge)

        topLeft.layoutParams = lp(Gravity.TOP or Gravity.START, top = e, left = e)
        helpPanel.visibility = if (narrow) View.GONE else View.VISIBLE
        /* opposite corner from the home cluster, since the tool pill is a
           sheet here and no longer owns the top right */
        viewInfo.layoutParams =
            if (narrow) lp(Gravity.TOP or Gravity.END, top = e, right = e)
            else lp(Gravity.TOP or Gravity.START, top = e, left = t.px(R.dimen.viewInfoLeft))

        railTab.visibility = if (narrow) View.GONE else View.VISIBLE
        undoPill.visibility = if (narrow) View.GONE else View.VISIBLE
        dock.visibility = if (narrow) View.VISIBLE else View.GONE

        for (sheet in listOf(toolPill, brushRail, stagePanel)) {
            if (narrow) asSheet(sheet) else asRail(sheet)
        }
        if (narrow) {
            ctxBar.layoutParams = lp(
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                left = e, right = e, bottom = t.px(R.dimen.dockH) + e,
            )
            toastCard.layoutParams = lp(
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                bottom = t.px(R.dimen.dockH) + t.dp(18f),
            )
            selBar.layoutParams = lp(
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                bottom = t.px(R.dimen.dockH) + t.dp(18f),
            )
            liquifyPanel.layoutParams = lp(
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                left = e, right = e, bottom = t.px(R.dimen.dockH) + t.dp(4f),
            )
        } else {
            ctxBar.layoutParams = lp(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, bottom = t.px(R.dimen.ctxBottom))
            toastCard.layoutParams = lp(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, bottom = t.px(R.dimen.toastBottom))
            selBar.layoutParams = lp(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, bottom = t.px(R.dimen.selBarBottom))
            liquifyPanel.layoutParams = lp(
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                bottom = t.px(R.dimen.liquifyBottom),
            )
        }
        refresh()
    }

    /** The desktop shape of a panel that is a bottom sheet when compact. */
    private fun asRail(v: LinearLayout) {
        v.translationY = 0f
        (v.background as? GradientDrawable)?.cornerRadius = t.rCard
        val e = t.px(R.dimen.edge)
        when (v) {
            toolPill -> {
                v.orientation = LinearLayout.HORIZONTAL
                v.layoutParams = lp(Gravity.TOP or Gravity.END, top = e, right = e)
                v.visibility = View.VISIBLE
                rebuildToolPill(twoColumns = false)
            }
            brushRail -> {
                v.orientation = LinearLayout.VERTICAL
                v.layoutParams = lp(
                    Gravity.START or Gravity.CENTER_VERTICAL,
                    left = e, width = t.px(R.dimen.brushRailW),
                )
                v.visibility = if (railHidden) View.INVISIBLE else View.VISIBLE
            }
            stagePanel -> {
                v.orientation = LinearLayout.VERTICAL
                v.layoutParams = lp(
                    Gravity.TOP or Gravity.END,
                    top = t.px(R.dimen.stageTop), right = e, width = t.px(R.dimen.stagePanelW),
                )
                v.visibility = View.GONE
            }
        }
        val p = t.px(if (v === toolPill) R.dimen.padPill else R.dimen.padRail)
        v.setPadding(p, p, p, p)
    }

    /**
     * The compact shape: full width, pinned above the dock, and translated
     * off the bottom until it is opened. The transform hides it rather than
     * `display:none`, because `display:none` would kill the slide.
     */
    private fun asSheet(v: LinearLayout) {
        (v.background as? GradientDrawable)?.cornerRadii = floatArrayOf(
            t.rSheet, t.rSheet, t.rSheet, t.rSheet, 0f, 0f, 0f, 0f,
        )
        v.layoutParams = lp(
            Gravity.BOTTOM, bottom = t.px(R.dimen.dockH),
            width = ViewGroup.LayoutParams.MATCH_PARENT,
        )
        val p = t.px(R.dimen.sheetPad)
        v.setPadding(p, p, p, p)
        v.visibility = View.VISIBLE
        v.post { if (!isSheetOpen(v)) v.translationY = offscreen(v) }
        if (v === toolPill) { v.orientation = LinearLayout.VERTICAL; rebuildToolPill(twoColumns = true) }
        if (v === brushRail) v.orientation = LinearLayout.VERTICAL
    }

    /**
     * On a phone the pill is a three-across grid — "because the pairs stay
     * merged here too — one Erase icon on the phone as well as on the desktop"
     * is what the CSS says about the DIVIDER, but the partners themselves all
     * get a slot, which is why the repeat-tap swap is disabled there.
     */
    private fun rebuildToolPill(twoColumns: Boolean) {
        toolPill.removeAllViews()
        val order = listOf(
            Tool.DRAW, Tool.SHAPE, Tool.SELECT, Tool.LASSO, Tool.SMOOTH,
            Tool.FILL, Tool.ERASE, Tool.VACUUM,
        )
        if (!twoColumns) {
            /*
             * A button coming back from the compact grid still carries the
             * grid's params — width 0 and a column weight — and a LinearLayout
             * would honour the zero. Every one of them gets its own square back.
             */
            for (tl in order) toolPill.addView(unGrid(toolButtons.getValue(tl)))
            toolPill.addView(divider(act, t))
            toolPill.addView(unGrid(icons.getValue("mirror")))
            toolPill.addView(unGrid(icons.getValue("stage")))
            for (tl in order) {
                val alt = partner[tl] ?: continue
                toolButtons.getValue(tl).visibility =
                    if (shownSide[minOf(tl, alt)] == tl) View.VISIBLE else View.GONE
            }
        } else {
            val grid = GridLayout(act).apply { columnCount = 3 }
            for (tl in order) {
                val b = reparent(toolButtons.getValue(tl))
                b.visibility = View.VISIBLE
                b.layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = t.px(R.dimen.toolTileCompact)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(t.dp(4f), t.dp(4f), t.dp(4f), t.dp(4f))
                }
                grid.addView(b)
            }
            for (extra in listOf("mirror", "stage")) {
                val b = reparent(icons.getValue(extra))
                b.layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = t.px(R.dimen.toolTileCompact)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(t.dp(4f), t.dp(4f), t.dp(4f), t.dp(4f))
                }
                grid.addView(b)
            }
            toolPill.addView(
                grid,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun reparent(v: View): View {
        (v.parent as? ViewGroup)?.removeView(v)
        return v
    }

    private fun unGrid(b: IcoButton): View {
        reparent(b)
        b.layoutParams = LinearLayout.LayoutParams(b.box, b.box)
        return b
    }

    // ======================================================================
    // open / close
    // ======================================================================

    private fun isSheetOpen(v: View) = openSheet === v

    /** Far enough down to clear the sheet AND its shadow. */
    private fun offscreen(v: View) = (v.height + t.dp(90f)).toFloat()

    fun toggleSheet(v: LinearLayout) {
        if (!compact) {
            if (v === stagePanel) {
                v.visibility = if (v.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                refresh()
            }
            return
        }
        val open = isSheetOpen(v)
        for (other in listOf(toolPill, brushRail, stagePanel)) {
            if (other !== v) other.animate().translationY(offscreen(other)).setDuration(220).start()
        }
        openSheet = if (open) null else v
        v.animate().translationY(if (open) offscreen(v) else 0f).setDuration(220).start()
        refresh()
    }

    private fun togglePopover(v: View) {
        val wasOpen = v.visibility == View.VISIBLE
        closePopovers()
        v.visibility = if (wasOpen) View.GONE else View.VISIBLE
        refresh()
    }

    fun closePopovers() {
        for (p in popovers) p.visibility = View.GONE
    }

    fun setMenu(open: Boolean) {
        sysMenu.visibility = if (open) View.VISIBLE else View.GONE
        scrim.visibility = if (open) View.VISIBLE else View.GONE
    }

    fun menuOpen() = sysMenu.visibility == View.VISIBLE

    fun toggleStage() = toggleSheet(stagePanel)

    private fun setRailHidden(hidden: Boolean) {
        railHidden = hidden
        val off = -(brushRail.width + t.px(R.dimen.edge)) * 1.35f
        brushRail.animate().translationX(if (hidden) off else 0f).alpha(if (hidden) 0f else 1f)
            .setDuration(200).start()
        undoPill.animate().translationX(if (hidden) off else 0f).alpha(if (hidden) 0f else 1f)
            .setDuration(200).start()
        railTab.getChildAt(0).animate().rotation(if (hidden) 180f else 0f).setDuration(180).start()
        if (hidden) closePopovers()
    }

    /** `UI.closeTopSheet` — what Android's Back steps out of, in order. */
    fun closeTop(): Boolean {
        if (menuOpen()) { setMenu(false); return true }
        for (p in popovers) if (p.visibility == View.VISIBLE) { closePopovers(); return true }
        if (compact) {
            for (s in listOf(toolPill, brushRail, stagePanel)) {
                if (isSheetOpen(s)) { toggleSheet(s); return true }
            }
        } else if (stagePanel.visibility == View.VISIBLE) {
            stagePanel.visibility = View.GONE
            return true
        }
        return false
    }

    // ======================================================================
    // state in
    // ======================================================================

    private fun select(which: Tool) {
        tool = which
        partner[which]?.let { alt -> shownSide[minOf(which, alt)] = which }
        if (compact) closeTop()          // picking a tool on a phone gets out of the way
        onTool(which)
        refresh()
    }

    fun setTool(which: Tool) {
        tool = which
        partner[which]?.let { alt -> shownSide[minOf(which, alt)] = which }
        refresh()
    }

    fun setBrush(name: String) { brush = name; refresh() }
    fun setSize(mm: Double) { sizeMm = mm; refresh() }
    fun setOpacityValue(o: Double) { opacity = o; refresh() }
    fun setColor(argb: Int) { inkColor = argb; refresh() }

    fun setHistory(canUndo: Boolean, canRedo: Boolean) {
        icons["undo"]?.isEnabled = canUndo
        icons["redo"]?.isEnabled = canRedo
        dockButtons["undo"]?.isEnabled = canUndo
        dockButtons["redo"]?.isEnabled = canRedo
    }

    fun setGuide(active: Boolean, name: String, opacityValue: Double) {
        guideActive = active
        guideName = name
        guideOpacity = opacityValue
        refresh()
    }

    fun setSelection(count: Int) { selectionCount = count; refresh() }

    /**
     * What the staging bar shows while Loft or Primitives is building a guide.
     *
     * `value` and `value2` are 0..1 fractions of each slider's own range, so
     * this class does not have to know that a primitive has 3..48 segments and
     * a loft has a tension. The caller maps them and supplies the readouts.
     */
    class Staging(
        val label: String,
        val value: Double,
        val readout: String,
        val secondLabel: String? = null,
        val value2: Double = 0.0,
        val readout2: String = "",
        val kind: String? = null,
    )

    fun setStaging(s: Staging?) { staging = s; refresh() }

    /** Everything the settings modal shows, from the tool and the camera. */
    fun setSettings(
        finger: Boolean, autoGuide: Boolean, isolate: Boolean, clamp: Boolean,
        holdShape: Boolean, stableOn: Boolean, stable: Double, radial: Int,
        focal: Double, ortho: Boolean, hideUi: Boolean, save: String,
    ) {
        optFinger = finger; optAutoGuide = autoGuide; optIsolate = isolate
        optClamp = clamp; optHoldShape = holdShape; optStable = stableOn
        stableAmt = stable; radialAmt = radial; focalMm = focal
        optOrtho = ortho; optHideUi = hideUi; saveText = save
        refresh()
    }

    /** The tool pill's Mirror button lights for either kind of symmetry. */
    fun setSymmetry(on: Boolean) { symmetryOn = on; refresh() }

    fun setPressure(on: Boolean, target: String) {
        pressureOn = on; pressureTarget = target; refresh()
    }

    fun setLiquify(mode: String, size: Double, range: Double, strength: Double) {
        lqMode = mode; lqSizeV = size; lqRangeV = range; lqStrengthV = strength
        refresh()
    }

    /** The whole Scene tab, from the document. */
    fun setEnvironment(env: DocumentEnv) {
        envGrid = env.grid
        envAxis = env.axis
        envFog = env.fog
        envShaded = env.shaded
        envRender = env.render
        envShadow = env.groundShadow
        envToon = env.light.toon
        envDof = env.fx.dofOn
        envGrain = env.fx.grainOn
        envPixel = env.fx.pixelOn
        lightIntensity = env.light.intensity
        lightAmbient = env.light.ambient
        fstop = env.fx.fstop
        grainLevel = env.fx.grain
        pixelSize = env.fx.pixel
        backgroundColor = rgb(env.background)
        lightColor = rgb(env.light.color)
        lightPad.az = env.light.az
        lightPad.alt = env.light.alt
        refresh()
    }

    /** What the panel currently reads, for the caller to fold back in. */
    fun readInto(env: DocumentEnv) {
        env.light.intensity = lightIntensity
        env.light.ambient = lightAmbient
        env.fx.fstop = fstop
        env.fx.grain = grainLevel
        env.fx.pixel = pixelSize
    }

    private fun rgb(c: art.plume.core.Rgba): Int = Color.rgb(
        (c.r * 255).toInt().coerceIn(0, 255),
        (c.g * 255).toInt().coerceIn(0, 255),
        (c.b * 255).toInt().coerceIn(0, 255),
    )

    fun setViewInfo(focalMm: Int, perspective: Boolean, curves: Int) {
        vFocal.text = focalMm.toString()
        vProj.text = act.getString(if (perspective) R.string.persp else R.string.ortho)
        vCount.text = curves.toString()
    }

    fun toast(msg: String) = toastCard.show(msg)

    /** `UI.refresh` — every button re-derives its own state from the model. */
    fun refresh() {
        for ((which, b) in toolButtons) b.on = which == tool
        for ((name, tile) in brushTiles) tile.solid = name == brush
        (colorDot.background as? GradientDrawable ?: GradientDrawable()).let { d ->
            d.shape = GradientDrawable.OVAL
            d.setColor(inkColor)
            /* box-shadow: 0 0 0 1px --line, inset 0 0 0 3px --panel */
            d.setStroke(t.dp(3f), t.panel)
            colorDot.background = d
        }
        sizeVal.text = "${sizeMm.toInt()}mm"
        opacityVal.text = "${(opacity * 100).toInt()}%"
        sizePopVal.text = "${sizeMm.toInt()}mm"
        opacityPopVal.text = "${(opacity * 100).toInt()}%"
        for ((bar, get) in sliders) bar.value = get()
        val st = staging
        stageBar.visibility = if (st != null) View.VISIBLE else View.GONE
        if (st != null) {
            stageLabel.text = st.label
            stageSlider.value = st.value
            stageValue.text = st.readout
            stageRow2.visibility = if (st.secondLabel != null) View.VISIBLE else View.GONE
            if (st.secondLabel != null) {
                stageLabel2.text = st.secondLabel
                stageSlider2.value = st.value2
                stageValue2.text = st.readout2
            }
            primKinds.visibility = if (st.kind != null) View.VISIBLE else View.GONE
            for ((k, b) in primButtons) b.on = k == st.kind
        }

        /* the guide bar and the staging bar are mutually exclusive: you are
           either editing a live guide or building a new one */
        guideBar.visibility = if (guideActive && st == null) View.VISIBLE else View.GONE
        guideNameLabel.text = guideName
        guideOpacityBar.value = guideOpacity
        ctxHint.text = act.getString(
            if (guideActive) R.string.hint_guide_active else R.string.hint_draw_a_stroke,
        )
        ctxHint.visibility = if (guideActive || st != null) View.GONE else View.VISIBLE
        selBar.visibility = if (selectionCount > 0 && tool != Tool.LIQUIFY) {
            View.VISIBLE
        } else {
            View.GONE
        }
        /* the strip only exists while the tool does, and it always has a
           selection to work on — that is what it is for */
        liquifyPanel.visibility =
            if (tool == Tool.LIQUIFY && selectionCount > 0) View.VISIBLE else View.GONE
        for ((k, b) in lqModes) b.on = k == lqMode
        lqSize.text = lqSizeV.toInt().toString()
        lqRange.text = lqRangeV.toInt().toString()
        lqStrength.text = lqStrengthV.toInt().toString()
        icons["pressure"]?.on = pressureOn
        for ((k, b) in pressButtons) b.on = k == pressureTarget
        /* with pressure off there is nothing for the target to target */
        pressRow.alpha = if (pressureOn) 1f else 0.3f
        for (b in pressButtons.values) b.isEnabled = pressureOn
        icons["mirror"]?.on = symmetryOn
        icons["stage"]?.on =
            if (compact) isSheetOpen(stagePanel) else stagePanel.visibility == View.VISIBLE

        sceneOptions.setOn("grid", envGrid)
        sceneOptions.setOn("axis", envAxis)
        sceneOptions.setOn("fog", envFog)
        sceneOptions.setOn("shade", envShaded)
        sceneOptions.setOn("render", envRender)
        sceneOptions.setOn("shadow", envShadow)
        /*
         * FACT: shadows and effects show accurately only in rendering mode. A
         * switch you can throw that then does nothing is worse than one that
         * says it is unavailable, so outside render mode they grey out rather
         * than lying.
         */
        sceneOptions.setUsable("shadow", envRender)
        fxOptions.setOn("dof", envDof)
        fxOptions.setOn("grain", envGrain)
        fxOptions.setOn("pixel", envPixel)
        for (k in listOf("dof", "grain", "pixel")) fxOptions.setUsable(k, envRender)

        toonButton.on = envToon
        /* toon bands a SHADED material; with shading off there is nothing to band */
        toonButton.isEnabled = envShaded

        intensityVal.text = "${(lightIntensity * 100).toInt()}%"
        ambientVal.text = "${(lightAmbient * 100).toInt()}%"
        fstopVal.text = "f/" + ((Math.round(fstop * 10.0)) / 10.0).toString()
        grainVal.text = "${grainLevel.toInt()}%"
        pixelVal.text = "${pixelSize.toInt()}px"
        inputGrid.setOn("finger", optFinger)
        inputGrid.setOn("autoguide", optAutoGuide)
        inputGrid.setOn("isolate", optIsolate)
        inputGrid.setOn("clamp", optClamp)
        inputGrid.setOn("holdshape", optHoldShape)
        inputGrid.setOn("stable", optStable)
        viewGrid.setOn("proj", optOrtho)
        viewGrid.setOn("hideui", optHideUi)
        stableBar.value = stableAmt
        stableValue.text = (stableAmt * 100).toInt().toString()
        radialBar.value = radialAmt.toDouble()
        /* 1 reads "Off": one copy of a stroke is no symmetry at all, and a
           control that says 1 invites you to wonder what it is doing */
        radialValue.text =
            if (radialAmt <= 1) act.getString(R.string.radial_off) else radialAmt.toString()
        focalBar.value = focalMm
        focalValue.text = "${focalMm.toInt()}mm"
        saveState.text = saveText

        swatch(bgSwatch, backgroundColor)
        swatch(lightSwatch, lightColor)

        /* don't fight the keyboard: only rewrite the field when it is not
           the thing being typed into */
        if (!hexField.hasFocus()) {
            hexField.setText(
                ColorSpace.toHex(
                    Rgba(
                        Color.red(inkColor) / 255.0,
                        Color.green(inkColor) / 255.0,
                        Color.blue(inkColor) / 255.0,
                    ),
                ),
            )
        }
        colorWheel.setColor(
            Rgba(
                Color.red(inkColor) / 255.0,
                Color.green(inkColor) / 255.0,
                Color.blue(inkColor) / 255.0,
            ),
        )
        icons["brushType"]?.on = brushGrid.visibility == View.VISIBLE
    }

    /** `input[type=color]` — a circular well with a --dim ring around it. */
    private fun swatch(v: View, argb: Int) {
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(argb)
            setStroke(t.dp(2f), t.dim)
        }
    }

    companion object {
        /** The rail's swatches. Index 0 is the default near-black ink. */
        val PALETTE = intArrayOf(
            0xFF1B1C21.toInt(), 0xFFFAFAFA.toInt(), 0xFF8C8C96.toInt(), 0xFFF2545B.toInt(),
            0xFFFF8A3D.toInt(), 0xFFF2C94C.toInt(), 0xFF4CC38A.toInt(), 0xFF2D8F6F.toInt(),
            0xFF5B9DFF.toInt(), 0xFF3B5BDB.toInt(), 0xFF8B5CF6.toInt(), 0xFFD6409F.toInt(),
            0xFF8B5E34.toInt(), 0xFFC9A227.toInt(), 0xFF4A5568.toInt(),
        )
    }
}
