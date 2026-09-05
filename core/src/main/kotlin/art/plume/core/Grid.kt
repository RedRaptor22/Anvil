package art.plume.core

/**
 * The ground grid and the global axis, as line lists.
 *
 * Ported from `buildGrid` / `buildAxis` in `js/camera.js`. One grid unit is
 * 1000 mm (FACT), and the helper spans 40 of them.
 *
 * Built in core rather than in the renderer because the colours are DERIVED,
 * not chosen: Feather couples the grid to the background ("both grid and guide
 * visuals respond to the background colour"), so the same background has to
 * produce the same grid on both builds or the two look different side by side
 * for a reason nobody would think to check. The renderer's only job is to
 * upload the arrays.
 */
object Grid {

    /** Positions and per-vertex colours for a GL_LINES draw. */
    class Lines(val positions: FloatArray, val colors: FloatArray) {
        val vertexCount: Int get() = positions.size / 3
    }

    /** FACT: red / green / blue for X / Y / Z. */
    private val AXIS_COLORS = arrayOf(
        Rgba(1.0, 0.302, 0.369),        // #ff4d5e
        Rgba(0.420, 0.863, 0.420),      // #6bdc6b
        Rgba(0.357, 0.616, 1.0),        // #5b9dff
    )

    /** Rec. 601 luma, the same weights the web build uses to pick a contrast. */
    fun luminance(c: Rgba): Double = c.r * 0.299 + c.g * 0.587 + c.b * 0.114

    private fun lerp(a: Rgba, b: Rgba, t: Double) = Rgba(
        a.r + (b.r - a.r) * t,
        a.g + (b.g - a.g) * t,
        a.b + (b.b - a.b) * t,
    )

    /**
     * Grid line colours for a background: a light page gets darker lines, a
     * dark one gets lighter, so the grid never disappears into the paper.
     *
     * THE MIX IS HALFWAY, NOT A THIRD. The ported values — 0.30 for the centre
     * lines and 0.14 for the rest — were chosen against a browser on a desk.
     * A phone held at arm's length, often in daylight, is a harder surface to
     * read a faint line on: at 0.14 the field of the grid was a suggestion of
     * a grid, and the whole use of it is judging where a stroke sits in space.
     * Raised until the lines are legible at a glance without competing with
     * the drawing, which is what keeps them below the ink rather than at it.
     */
    fun gridColors(bg: Rgba): Pair<Rgba, Rgba> {
        val toward = if (luminance(bg) > 0.5) Rgba(0.0, 0.0, 0.0) else Rgba(1.0, 1.0, 1.0)
        return lerp(bg, toward, 0.50) to lerp(bg, toward, 0.26)
    }

    /**
     * Guide fill and line colours for a background.
     *
     * Ported from `guideColors` in `js/guides.js`, per the v1.5 note that guide
     * visuals "respond to background colors for better visibility". A light
     * page gets a deep blue that reads against paper; a dark one gets a pale
     * blue that does not disappear into it.
     */
    fun guideColors(bg: Rgba): Pair<Rgba, Rgba> =
        if (luminance(bg) > 0.5) {
            Rgba(0.184, 0.373, 0.749) to Rgba(0.078, 0.200, 0.435)   // #2f5fbf / #14336f
        } else {
            Rgba(0.498, 0.659, 0.961) to Rgba(0.839, 0.902, 1.0)     // #7fa8f5 / #d6e6ff
        }

    /**
     * The ground plane grid, on y = 0, with the two centre lines picked out in
     * the stronger colour.
     */
    fun build(
        bg: Rgba,
        extent: Double = Tune.GRID_EXTENT,
        divisions: Int = Tune.GRID_DIVISIONS,
        opacity: Double = 0.85,
    ): Lines {
        val (major, minor) = gridColors(bg)
        val half = extent / 2.0
        val step = extent / divisions
        val lineCount = (divisions + 1) * 2
        val pos = FloatArray(lineCount * 2 * 3)
        val col = FloatArray(lineCount * 2 * 4)
        var p = 0
        var c = 0

        fun vertex(x: Double, z: Double, k: Rgba) {
            pos[p++] = x.toFloat(); pos[p++] = 0f; pos[p++] = z.toFloat()
            col[c++] = k.r.toFloat(); col[c++] = k.g.toFloat()
            col[c++] = k.b.toFloat(); col[c++] = opacity.toFloat()
        }

        for (i in 0..divisions) {
            val d = -half + i * step
            // the centre line of each direction is the one through the origin
            val k = if (kotlin.math.abs(d) < step * 1e-6) major else minor
            vertex(-half, d, k); vertex(half, d, k)
            vertex(d, -half, k); vertex(d, half, k)
        }
        return Lines(pos, col)
    }

    /**
     * The RGB global axis. FACT: it is off by default and toggled from the
     * Environment tab, so this is built once and its visibility flipped rather
     * than being rebuilt on each toggle.
     */
    /*
     * And the axis is drawn at nearly full strength. It is three coloured
     * lines through the origin that you turn ON when you want to know which
     * way is which — a thing asked for is a thing that should answer clearly,
     * and at 0.55 the red and blue washed into a mid-tone page.
     */
    fun axis(length: Double = Tune.AXIS_LENGTH, opacity: Double = 0.92): Lines {
        val pos = FloatArray(3 * 2 * 3)
        val col = FloatArray(3 * 2 * 4)
        val dirs = arrayOf(
            Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0),
        )
        var p = 0
        var c = 0
        for (i in 0..2) {
            val k = AXIS_COLORS[i]
            for (s in intArrayOf(-1, 1)) {
                pos[p++] = (dirs[i].x * length * s).toFloat()
                pos[p++] = (dirs[i].y * length * s).toFloat()
                pos[p++] = (dirs[i].z * length * s).toFloat()
                col[c++] = k.r.toFloat(); col[c++] = k.g.toFloat()
                col[c++] = k.b.toFloat(); col[c++] = opacity.toFloat()
            }
        }
        return Lines(pos, col)
    }
}
