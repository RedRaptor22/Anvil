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

    /* Declared up here, not beside sliderRow: init{} builds the popover, and a
       property initialiser that runs after init{} would still be null then. */
    private val sliders = ArrayList<Pair<HSlider, () -> Double>>()

    private lateinit var ctxHint: TextView
    private lateinit var guideBar: LinearLayout
    private lateinit var guideNameLabel: TextView
    private lateinit var guideOpacityBar: VSlider
    private lateinit var sizeVal: DragValue
    private lateinit var opacityVal: DragValue
    private lateinit var colorDot: View
    private lateinit var vFocal: TextView
    private lateinit var vProj: TextView
    private lateinit var vCount: TextView
    private lateinit var sizePopVal: TextView
    private lateinit var opacityPopVal: TextView

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
        brushRail.addView(ico("brush", Action.PRESSURE, small = true).also { icons["pressure"] = it })
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
        guideOpacityBar = VSlider(act, t, 0.0, 0.92) { v ->
            guideOpacity = v; onGuideOpacity(v); refresh()
        }
        guideOpacityBar.layoutParams = LinearLayout.LayoutParams(
            t.dp(32f), t.px(R.dimen.icoSm),
        ).apply { marginStart = t.dp(6f) }
        guideBar.addView(guideOpacityBar)
        guideBar.addView(ico("eye", Action.GUIDE_SAVE, small = true))
        guideBar.addView(ico("close", Action.GUIDE_CLOSE, small = true))
        ctxBar.addView(guideBar)
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

    private fun buildStagePanel() {
        stagePanel.orientation = LinearLayout.VERTICAL
        val p = t.px(R.dimen.padCard)
        stagePanel.setPadding(p, p, p, p)
        stagePanel.addView(head(act.getString(R.string.stage)) { onAction(Action.STAGE) })
        for ((label, a) in listOf(
            R.string.new_sketch to Action.NEW,
            R.string.save to Action.SAVE,
            R.string.open to Action.OPEN,
            R.string.export_ to Action.EXPORT,
            R.string.clear to Action.CLEAR,
        )) {
            stagePanel.addView(
                TextButton(act, t, filled = true).apply {
                    text = act.getString(label)
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setOnClickListener { onAction(a) }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = t.dp(4f) }
                },
            )
        }
        stagePanel.visibility = View.GONE
    }

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
        /*
         * A VSlider laid out wider than it is tall still reads bottom-to-top,
         * so the popover uses a plain SeekBar-shaped horizontal control built
         * from the same primitive: it is the same 4px track and 16px thumb.
         */
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
     * `#colorCard` — the hex row and the swatch grid.
     *
     * The hue wheel is NOT here yet: it is a 200px canvas plus an HSV box, and
     * a wheel that cannot be sampled from the sketch is only half of the
     * control. The swatches are what the rail actually needs to be usable, so
     * they ship first and the wheel is a named gap, not a silent one.
     */
    private fun buildColorCard() {
        colorCard.orientation = LinearLayout.VERTICAL
        val p = t.px(R.dimen.padPop)
        colorCard.setPadding(p, p, p, p)
        colorCard.addView(head(act.getString(R.string.colour)) { closePopovers() })
        val grid = GridLayout(act).apply { columnCount = 5 }
        for (c in PALETTE) {
            grid.addView(
                View(act).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(c)
                        setStroke(t.dp(1f), t.line)
                    }
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = t.dp(28f); height = t.dp(28f)
                        setMargins(t.dp(4f), t.dp(4f), t.dp(4f), t.dp(4f))
                    }
                    setOnClickListener {
                        inkColor = c; onColor(c); closePopovers(); refresh()
                    }
                },
            )
        }
        colorCard.addView(grid)
        colorCard.visibility = View.GONE
        popovers.add(colorCard)
    }

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
        for ((label, a) in listOf(
            R.string.new_sketch to Action.NEW,
            R.string.save to Action.SAVE,
            R.string.open to Action.OPEN,
            R.string.export_ to Action.EXPORT,
            R.string.clear to Action.CLEAR,
        )) {
            sysMenu.addView(
                TextButton(act, t, filled = true).apply {
                    text = act.getString(label)
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setOnClickListener { setMenu(false); onAction(a) }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = t.dp(4f) }
                },
            )
        }
        sysMenu.visibility = View.GONE
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
        } else {
            ctxBar.layoutParams = lp(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, bottom = t.px(R.dimen.ctxBottom))
            toastCard.layoutParams = lp(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, bottom = t.px(R.dimen.toastBottom))
            selBar.layoutParams = lp(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, bottom = t.px(R.dimen.selBarBottom))
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
        guideBar.visibility = if (guideActive) View.VISIBLE else View.GONE
        guideNameLabel.text = guideName
        guideOpacityBar.value = guideOpacity
        ctxHint.text = act.getString(
            if (guideActive) R.string.hint_guide_active else R.string.hint_draw_a_stroke,
        )
        ctxHint.visibility = if (guideActive) View.GONE else View.VISIBLE
        selBar.visibility = if (selectionCount > 0) View.VISIBLE else View.GONE
        icons["stage"]?.on =
            if (compact) isSheetOpen(stagePanel) else stagePanel.visibility == View.VISIBLE
        icons["brushType"]?.on = brushGrid.visibility == View.VISIBLE
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
