package art.plume.anvil

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.exp

/**
 * Plume's visual language as Android widgets.
 *
 * The stylesheet in plume.html opens by naming the language it encodes:
 *
 *     light-first — white cards floating over the canvas, no borders
 *     large radii (22px cards, 14px controls, circular swatches)
 *     soft diffuse shadow instead of outlines
 *     abstract monochrome line icons, never filled pictograms
 *     ACTIVE = a filled glyph on a soft tint, not a colour accent
 *
 * Every one of those five is a rule about a *drawable*, not about a layout,
 * so they live here, once, and `Chrome` only arranges them. The numbers all
 * come from res/values/dimens.xml, which is the CSS transcribed; a CSS pixel
 * and a dp are the same 1/96 inch, so nothing had to be reinterpreted.
 *
 * Two things the browser gets for free and Android does not:
 *
 *  · `currentColor`. A glyph in Plume inherits its button's colour, which is
 *    how one sprite serves a light card, a dark card and an inverted pill.
 *    Here the button re-tints the drawable on every state change instead.
 *  · The cascade. `button.on svg{fill:currentColor}` outranks the hollow
 *    default, so an active icon becomes a filled one. There is no cascade in
 *    a VectorDrawable, so the generator emitted a second file per icon and
 *    [IcoButton] swaps between them.
 */

/** The `:root` (and `body.dark`) custom properties, resolved once per window. */
class Tokens(ctx: Context) {
    private val res = ctx.resources
    private val theme = ctx.theme

    val bg = res.getColor(R.color.bg, theme)
    val panel = res.getColor(R.color.panel, theme)
    val panel2 = res.getColor(R.color.panel2, theme)
    val panel3 = res.getColor(R.color.panel3, theme)
    val ink = res.getColor(R.color.ink, theme)
    val dim = res.getColor(R.color.dim, theme)
    val dim2 = res.getColor(R.color.dim2, theme)
    val line = res.getColor(R.color.line, theme)
    val active = res.getColor(R.color.active, theme)
    val onActive = res.getColor(R.color.onActive, theme)
    val accent = res.getColor(R.color.accent, theme)
    val blue = res.getColor(R.color.blue, theme)
    val green = res.getColor(R.color.green, theme)
    val red = res.getColor(R.color.red, theme)
    val redWash = res.getColor(R.color.redWash, theme)
    val shadowTint = res.getColor(R.color.shadowTint, theme)
    val scrim = res.getColor(R.color.scrim, theme)

    val rCard = res.getDimension(R.dimen.rCard)
    val rBtn = res.getDimension(R.dimen.rBtn)
    val rIco = res.getDimension(R.dimen.rIco)
    val rIcoSm = res.getDimension(R.dimen.rIcoSm)
    val rTile = res.getDimension(R.dimen.rTile)
    val rSheet = res.getDimension(R.dimen.rSheet)

    val dark = (res.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    private val density = res.displayMetrics.density

    /** dp as a float — for radii and stroke widths, which are not integral. */
    fun dpf(v: Float): Float = v * density

    /** dp as a whole pixel — for sizes, paddings and margins. */
    fun dp(v: Float): Int = (v * density + 0.5f).toInt()

    fun px(id: Int): Int = res.getDimensionPixelSize(id)
}

/**
 * `.panel` — a floating card. Position is the caller's business; radius,
 * fill and the soft shadow are not.
 *
 * The CSS shadow is two-part (`0 8px 26px` over `0 1px 3px`). Android has one
 * shadow per elevation, cast from the view's outline, so the elevation is the
 * larger blur's offset and the colour carries what the second part added. On
 * API 28 the shadow colour is settable, which is what keeps a dark card's
 * shadow from being the light theme's blue-grey.
 */
fun panel(
    ctx: Context,
    t: Tokens,
    radius: Float = t.rCard,
    large: Boolean = false,
    corners: FloatArray? = null,
): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    background = GradientDrawable().apply {
        setColor(t.panel)
        if (corners != null) cornerRadii = corners else cornerRadius = radius
    }
    /*
     * A GradientDrawable only reports an outline for a UNIFORM radius, so a
     * corner-array panel (the rail tab, the bottom sheets) would cast no
     * shadow at all without one of its own.
     */
    if (corners != null) {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, corners.maxOrNull() ?: radius)
            }
        }
    }
    elevation = if (large) t.dpf(18f) else t.dpf(8f)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        outlineSpotShadowColor = t.shadowTint
        outlineAmbientShadowColor = t.shadowTint
    }
    val p = t.px(R.dimen.padPill)
    setPadding(p, p, p, p)
}

/** `.div` — the 1px vertical hairline between groups inside a pill. */
fun divider(ctx: Context, t: Tokens): View = View(ctx).apply {
    setBackgroundColor(t.line)
    layoutParams = LinearLayout.LayoutParams(t.px(R.dimen.hair), ViewGroup.LayoutParams.MATCH_PARENT)
        .apply {
            val mv = t.px(R.dimen.divMarginV)
            val mh = t.px(R.dimen.divMarginH)
            setMargins(mh, mv, mh, mv)
        }
}

/** `.sep` — the horizontal rule that splits the brush rail into three. */
fun separator(ctx: Context, t: Tokens): View = View(ctx).apply {
    setBackgroundColor(t.line)
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, t.px(R.dimen.hair),
    ).apply { setMargins(0, t.dp(3f), 0, t.dp(3f)) }
}

/**
 * `.ico` — a square icon-only button.
 *
 * Three states, and the CSS is worth quoting because the middle one is the
 * whole visual identity:
 *
 *     button:active { transform:scale(.96) }
 *     button.on     { background:var(--panel3) }
 *     button.on svg { fill:currentColor;stroke:currentColor }
 *
 * so ACTIVE is a filled glyph on a soft tint. The stylesheet's own comment
 * says why it is not a black pill: "a black pill fights the white cards".
 *
 * `solid` is the handful of controls that opt into inversion instead
 * (`button.solid`), and `dot` is the 3px marker Plume puts on the six tools
 * that hide a second mode behind a repeat tap.
 */
class IcoButton(
    ctx: Context,
    private val t: Tokens,
    size: Int = SIZE_NORMAL,
) : ImageView(ctx) {

    companion object {
        const val SIZE_NORMAL = 0
        const val SIZE_SMALL = 1     // .ico.sm — 32px box, 17px glyph
        const val SIZE_TINY = 2      // .grpRow .ico.sm — 28px
        const val SIZE_TAB = 3       // #railTab svg — 13px in a 20px column
    }

    /** The hollow glyph, and the filled one `button.on` swaps to. */
    private var iconOff = 0
    private var iconOn = 0

    var solid = false
        set(v) { field = v; sync() }

    /** `#tools button[data-tool=…]::after` — marks a tool with a partner. */
    var dot = false
        set(v) { field = v; invalidate() }

    var on = false
        set(v) { if (field != v) { field = v; sync() } }

    /** `#btnSelDelete{color:var(--red)}` — the one coloured control. */
    var danger = false
        set(v) { field = v; sync() }

    /** The button's own square, so a caller can restore it after a regrid. */
    val box: Int
    private var radius: Float
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * `#brushGrid button` and `.igrid button` sit on --panel2 with an 11px
     * radius rather than on nothing with 12px. Set these instead of assigning
     * a background: sync() rebuilds the background on every state change and
     * would throw an assigned one away.
     */
    fun tile(fill: Int, r: Float): IcoButton {
        restFill = fill
        radius = r
        sync()
        return this
    }

    private var restFill: Int? = null

    init {
        val glyph: Int
        when (size) {
            SIZE_TAB -> { box = t.px(R.dimen.railTabW); glyph = t.px(R.dimen.railTabGlyph); radius = 0f }
            SIZE_SMALL -> { box = t.px(R.dimen.icoSm); glyph = t.px(R.dimen.icoSmGlyph); radius = t.rIcoSm }
            SIZE_TINY -> { box = t.px(R.dimen.icoXs); glyph = t.px(R.dimen.icoSmGlyph); radius = t.rIcoSm }
            else -> { box = t.px(R.dimen.ico); glyph = t.px(R.dimen.icoGlyph); radius = t.rIco }
        }
        scaleType = ScaleType.FIT_CENTER
        val pad = (box - glyph) / 2
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(box, box)
        isClickable = true
        isFocusable = true
        sync()
    }

    /**
     * @param name the sprite id with `i-` dropped, e.g. "draw" or "lq_push".
     *   The `_on` twin is looked up by name so a caller never has to know
     *   whether an icon has one.
     */
    fun icon(name: String): IcoButton {
        val res = resources
        val pkg = context.packageName
        iconOff = res.getIdentifier("ic_$name", "drawable", pkg)
        iconOn = res.getIdentifier("ic_${name}_on", "drawable", pkg)
        if (iconOn == 0) iconOn = iconOff
        sync()
        return this
    }

    private fun sync() {
        if (iconOff != 0) setImageResource(if (on) iconOn else iconOff)
        val fg = when {
            danger -> t.red
            solid -> t.onActive
            else -> t.ink
        }
        imageTintList = android.content.res.ColorStateList.valueOf(fg)
        val fill = when {
            solid -> t.active
            on -> t.panel3
            else -> restFill ?: Color.TRANSPARENT
        }
        background = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius
        }
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.3f       // button:disabled{opacity:.3}
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        val s = if (pressed) 0.96f else 1f      // button:active{transform:scale(.96)}
        animate().scaleX(s).scaleY(s).setDuration(70).start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!dot) return
        /*
         * right:5px bottom:5px, 3px across — so the CENTRE sits 6.5px in from
         * each edge, not 5.
         */
        val r = t.dpf(1.5f)
        val inset = t.dpf(5f) + r
        dotPaint.color = if (on) t.onActive else t.dim2
        dotPaint.alpha = if (on) 140 else 255   // #tools button.on::after{opacity:.55}
        canvas.drawCircle(width - inset, height - inset, r, dotPaint)
    }
}

/**
 * A text button — `button{border-radius:var(--rBtn);padding:6px 10px}`, with
 * the `.filled`, `.small` and `.on` variants the panels use.
 */
class TextButton(
    ctx: Context,
    private val t: Tokens,
    private val filled: Boolean = false,
    private val small: Boolean = false,
) : TextView(ctx) {

    var on = false
        set(v) { if (field != v) { field = v; sync() } }

    init {
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        textSize = if (small) 12f else 13f
        val px = t.dp(if (small) 9f else 10f)
        val py = t.dp(if (small) 5f else 6f)
        setPadding(px, py, px, py)
        minHeight = t.dp(if (small) 30f else 32f)
        sync()
    }

    private fun sync() {
        setTextColor(if (on) t.onActive else t.ink)
        background = GradientDrawable().apply {
            setColor(if (on) t.active else if (filled) t.panel2 else Color.TRANSPARENT)
            cornerRadius = t.rBtn
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.3f
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        val s = if (pressed) 0.96f else 1f
        animate().scaleX(s).scaleY(s).setDuration(70).start()
    }
}

/**
 * `.val.dragv` — a readout you adjust by sliding up or down on it.
 *
 * The stylesheet explains why it is not a label: "Feather adjusts size and
 * opacity by sliding up or down on the value itself, so it needs to look and
 * behave like a control rather than a label". The two rates and the slop are
 * Plume's own (SIZE_PER_PX, OPACITY_PER_PX, DRAG_SLOP in ui.js).
 *
 * Size is multiplicative — `from * exp(dy * 0.011)` — so the same travel is
 * the same *proportion* whether the brush is 2mm or 200mm. Opacity is
 * additive, because it is already a ratio.
 *
 * A drag is not a tap: the tap has to survive for the keypad, so a gesture
 * that never passed the slop still reports a click.
 */
class DragValue(
    ctx: Context,
    private val t: Tokens,
    private val logarithmic: Boolean,
    private val rate: Double,
    private val get: () -> Double,
    private val set: (Double) -> Unit,
) : TextView(ctx) {

    private var startY = 0f
    private var from = 0.0
    private var moved = false

    init {
        gravity = Gravity.CENTER
        setTextColor(t.dim)
        textSize = 9.5f
        setPadding(t.dp(4f), t.dp(2f), t.dp(4f), t.dp(2f))
        background = GradientDrawable().apply {
            setColor(t.panel2)
            cornerRadius = t.dpf(8f)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startY = e.rawY; from = get(); moved = false
                parent?.requestDisallowInterceptTouchEvent(true)
                isPressed = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = (startY - e.rawY).toDouble()          // up is more
                if (!moved && abs(dy) < t.dpf(3f)) return true
                moved = true
                set(if (logarithmic) from * exp(dy * rate) else from + dy * rate)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                if (!moved && e.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}

/**
 * `#toast` — a card that fades up from the bottom and back out.
 *
 * Deliberately not an Android Toast: on API 30+ those are drawn by the system
 * in the system's own style, and a rounded system capsule in the middle of
 * this interface is the one piece of chrome that would look borrowed. It also
 * has to sit above the dock on a phone, which a system toast cannot know.
 */
class ToastCard(ctx: Context, private val t: Tokens) : FrameLayout(ctx) {

    private val label = TextView(ctx).apply {
        setTextColor(t.ink)
        textSize = 13f
    }

    init {
        background = GradientDrawable().apply {
            setColor(t.panel)
            cornerRadius = t.rCard
        }
        elevation = t.dpf(18f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            outlineSpotShadowColor = t.shadowTint
            outlineAmbientShadowColor = t.shadowTint
        }
        setPadding(t.dp(18f), t.dp(10f), t.dp(18f), t.dp(10f))
        addView(label)
        alpha = 0f
        visibility = GONE
    }

    fun show(msg: String) {
        label.text = msg
        visibility = VISIBLE
        animate().cancel()
        translationY = t.dpf(8f)
        animate().alpha(1f).translationY(0f).setDuration(180).withEndAction {
            postDelayed({ hide() }, 1600)
        }.start()
    }

    private fun hide() {
        animate().alpha(0f).translationY(t.dpf(8f)).setDuration(180)
            .withEndAction { visibility = GONE }.start()
    }
}

/**
 * `input[type=range]` — every slider in the interface.
 *
 * The parts are the `::-webkit-slider` rules read literally: a 4px track in
 * the panel3 tone, a 16px thumb in the panel tone carrying
 * `0 1px 4px rgba(0,0,0,.28)`. Drawn rather than themed, because a platform
 * SeekBar brings a tick mark, a ripple and a splash colour that belong to a
 * different interface.
 *
 * There is deliberately no vertical twin. The stylesheet defines a `.vslider`
 * that rotates a range input -90deg so the rail could stay one column wide,
 * but nothing in the markup uses it — the rail adjusts by dragging the
 * readout instead. Porting the class would have been porting dead code.
 */
class HSlider(
    ctx: Context,
    private val t: Tokens,
    private val min: Double,
    private val max: Double,
    private val onChange: (Double) -> Unit,
) : View(ctx) {

    var value: Double = min
        set(v) { field = v.coerceIn(min, max); invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val cy = height * 0.5f
        val track = t.dpf(4f)
        val thumb = t.dpf(8f)
        val left = thumb
        val right = width - thumb
        paint.color = t.panel3
        rect.set(left, cy - track / 2, right, cy + track / 2)
        canvas.drawRoundRect(rect, track / 2, track / 2, paint)
        val f = ((value - min) / (max - min)).toFloat()
        val cx = left + f * (right - left)
        paint.color = t.shadowTint
        paint.alpha = 70
        canvas.drawCircle(cx, cy + t.dpf(1f), thumb, paint)
        paint.alpha = 255
        paint.color = t.panel
        canvas.drawCircle(cx, cy, thumb, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val thumb = t.dpf(8f)
                val f = ((e.x - thumb) / (width - 2 * thumb)).coerceIn(0f, 1f)
                value = min + f * (max - min)
                onChange(value)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                performClick()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
