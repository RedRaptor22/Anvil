package art.plume.core

import kotlin.math.sqrt

/**
 * MAKING A DRAWING SMALLER WITHOUT MAKING IT DIFFERENT.
 *
 * FACT: "Lighten — Select a note and tap the fifth icon to lighten it. A
 * lightened note is automatically optimized and decimated to reduce file size,
 * though it may differ slightly from the original."
 *
 * A pen at 240Hz lays down points far closer together than the line needs: a
 * straight stroke drawn slowly can carry two hundred samples that a beginning
 * and an end would describe exactly. Douglas-Peucker throws away every point
 * that lies within a tolerance of the line its neighbours already imply, which
 * is the one decimation rule that keeps corners — the alternative, dropping
 * every other point, rounds off exactly the features a drawing is made of.
 *
 * The tolerance is measured against the BRUSH, not in absolute units. A point
 * a tenth of a millimetre off the line matters on a hairline and is invisible
 * under a 30mm marker, so the same setting has to mean different distances for
 * different strokes or it is either destructive or useless.
 */
object Simplify {

    /** GUESS: a fraction of the stroke's half-width nobody can see. */
    const val OF_RADIUS = 0.12

    /** The floor, so a hairline is not simplified into a straight line. */
    const val MIN_MM = 0.05

    /** How far [p] is from the segment ab, in world units. */
    fun distanceToSegment(p: Vec3, a: Vec3, b: Vec3): Double {
        val vx = b.x - a.x; val vy = b.y - a.y; val vz = b.z - a.z
        val wx = p.x - a.x; val wy = p.y - a.y; val wz = p.z - a.z
        val vv = vx * vx + vy * vy + vz * vz
        val t = if (vv < 1e-18) 0.0 else clamp((wx * vx + wy * vy + wz * vz) / vv, 0.0, 1.0)
        val dx = wx - vx * t; val dy = wy - vy * t; val dz = wz - vz * t
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Which points to keep, as a mask, so the caller can carry everything a
     * point knows — pressure, its frame, the trim it was given by the guide it
     * was painted on — rather than rebuilding points from positions alone.
     *
     * Iterative rather than recursive: a stroke can be tens of thousands of
     * points long and the recursion depth is data, not code.
     */
    fun keepMask(points: List<Vec3>, tolerance: Double): BooleanArray {
        val keep = BooleanArray(points.size)
        if (points.size <= 2) { for (i in keep.indices) keep[i] = true; return keep }
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, points.size - 1))
        while (stack.isNotEmpty()) {
            val (lo, hi) = stack.removeLast()
            if (hi <= lo + 1) continue
            var worst = -1
            var worstD = tolerance
            for (i in lo + 1 until hi) {
                val d = distanceToSegment(points[i], points[lo], points[hi])
                if (d > worstD) { worstD = d; worst = i }
            }
            if (worst < 0) continue
            keep[worst] = true
            stack.addLast(intArrayOf(lo, worst))
            stack.addLast(intArrayOf(worst, hi))
        }
        return keep
    }

    /** The tolerance a stroke of this width deserves. */
    fun toleranceFor(stroke: Stroke): Double =
        maxOf(MIN_MM * MM, stroke.baseRadius * OF_RADIUS)

    /**
     * Decimate one stroke in place, returning how many points went.
     *
     * A closed loop keeps its ends because they are the same point: dropping
     * either would open it.
     */
    fun stroke(stroke: Stroke, tolerance: Double = toleranceFor(stroke)): Int {
        if (stroke.pts.size <= 2) return 0
        val keep = keepMask(stroke.pts.map { it.p }, tolerance)
        var gone = 0
        var w = 0
        for (i in stroke.pts.indices) {
            if (keep[i]) { stroke.pts[w] = stroke.pts[i]; w++ } else gone++
        }
        while (stroke.pts.size > w) stroke.pts.removeAt(stroke.pts.size - 1)
        return gone
    }

    /** Every curve in a sketch. Returns how many points it saved in total. */
    fun sketch(sketch: Sketch): Int {
        var gone = 0
        for (s in sketch.strokes) gone += stroke(s)
        return gone
    }
}
