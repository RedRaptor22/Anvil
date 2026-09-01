package art.plume.core

import kotlin.math.max

/**
 * Where the symmetry folds.
 *
 * A stroke and its mirror meet in the middle, and that middle is a PLANE, not
 * a line: the midpoint of any point and its reflection lies on it, wherever on
 * the stroke you take it. Drawn edge-on — which is how you are looking at it
 * most of the time you are drawing — it collapses to exactly the faint line
 * down the middle it is meant to be, and when you orbit off-axis it stays
 * truthful instead of pretending the fold is somewhere it is not.
 *
 * Radial symmetry folds about a LINE rather than a plane, so that one is drawn
 * as a line: the upright axis every copy turns around.
 *
 * Both are bounded to what is actually on the page rather than running to the
 * horizon, so they read as part of this drawing and never compete with the
 * grid or the RGB axis.
 */
object Symmetry {

    /** So the fold is still there on an empty page. 160mm. */
    const val MIN_HALF = 0.16

    /** And always a little air around the work. */
    const val PAD_MIN = 0.05

    /** A fraction of the work, not a fixed distance. */
    const val PAD_FRACTION = 0.10

    /**
     * The fold, as triangles for the faint fill and line pairs for the edges.
     * [axisLine] is the radial turning axis, empty when radial is off.
     */
    class Fold(
        val fill: FloatArray,
        val edges: FloatArray,
        val axisLine: FloatArray,
    )

    /**
     * A minimum SIZE, centred on the work — not a minimum reach from the
     * origin. Forcing it to straddle the origin left the fold hanging below a
     * sketch that happened to sit above it, pointing at nothing.
     */
    private fun span(centre: Double, extent: Double, pad: Double): Pair<Double, Double> {
        val half = max(MIN_HALF, extent / 2 + pad)
        return (centre - half) to (centre + half)
    }

    /**
     * Build the indicator for [mirror] ("x", "z" or null) and [radial] copies.
     * Null when there is no symmetry to show.
     */
    fun fold(bounds: Bounds, mirror: String?, radial: Int): Fold? {
        if (mirror == null && radial <= 1) return null

        val sx = if (bounds.empty) 0.0 else bounds.maxX - bounds.minX
        val sy = if (bounds.empty) 0.0 else bounds.maxY - bounds.minY
        val sz = if (bounds.empty) 0.0 else bounds.maxZ - bounds.minZ
        val mid = if (bounds.empty) Vec3() else bounds.centre()
        val pad = max(PAD_MIN, max(sx, max(sy, sz)) * PAD_FRACTION)

        /* both the fold plane and the turning axis want the height of the work */
        val (y0, y1) = span(mid.y, sy, pad)

        var fill = FloatArray(0)
        var edges = FloatArray(0)
        if (mirror != null) {
            /*
             * The plane's in-plane horizontal direction: mirroring across X
             * leaves the Z axis lying IN the plane, and the other way round.
             */
            val acrossX = mirror == "x"
            val (lo, hi) =
                if (acrossX) span(mid.z, sz, pad) else span(mid.x, sx, pad)

            fun corner(u: Double, v: Double): FloatArray =
                if (acrossX) floatArrayOf(0f, v.toFloat(), u.toFloat())
                else floatArrayOf(u.toFloat(), v.toFloat(), 0f)

            val a = corner(lo, y0)
            val b = corner(hi, y0)
            val c = corner(hi, y1)
            val d = corner(lo, y1)
            fill = a + b + c + a + c + d
            edges = a + b + b + c + c + d + d + a
        }

        val axisLine = if (radial > 1) {
            floatArrayOf(0f, y0.toFloat(), 0f, 0f, y1.toFloat(), 0f)
        } else {
            FloatArray(0)
        }
        return Fold(fill, edges, axisLine)
    }
}
