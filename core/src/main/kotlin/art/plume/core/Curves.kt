package art.plume.core

import kotlin.math.max
import kotlin.math.min

/**
 * SEVERAL CURVES THAT ARE REALLY ONE.
 *
 * Drawing is repetitive on purpose. A line worth keeping is usually the third
 * or fourth attempt laid over the first three, and a form is often built from
 * a small bundle of nearly-parallel strokes — that is how the hand finds a
 * shape, and it is why a selection of "the curves along this edge" is rarely
 * one curve.
 *
 * Every tool that turns a selection into a SURFACE has to reckon with that. A
 * loft interpolates between its sections in order, so a bundle of four
 * near-identical strokes becomes four sections a millimetre apart: the surface
 * spends most of itself crossing that bundle and the shape you meant — the
 * span between the bundle and the next curve over — is a sliver at the end.
 * The same selection read as one averaged section and one far section is the
 * surface anyone would have drawn by hand.
 *
 * So the clustering is not a tidying step, it is the reading: curves close
 * enough to be the same line ARE the same line, and their average is the line
 * they were all aiming at.
 */
object Curves {

    /**
     * How many points a comparison and an average are made on.
     *
     * The same count a guide's profile is built at, because the averaged curve
     * goes straight into one: averaging at a coarser resolution would hand the
     * sweep a 24-segment polyline to smooth back up, and a merged bundle would
     * come out visibly more angular than any of the strokes that made it.
     */
    const val SAMPLES = Tune.GUIDE_PROFILE_SEG

    /**
     * Curves grouped by proximity, each group averaged into one.
     *
     * Order is preserved by first appearance, so a loft still runs through its
     * sections in the order they were selected — the bundle you drew first is
     * still the section it starts from.
     *
     * A group of one comes back UNTOUCHED, at its original points. Resampling
     * a curve that had nothing to be averaged with would round its corners for
     * no reason, and a single curve going into this must come out the same
     * curve or every caller has to care whether merging happened.
     */
    fun merge(
        curves: List<List<Vec3>>,
        fraction: Double = Tune.CURVE_MERGE_FRACTION,
    ): List<List<Vec3>> {
        if (curves.size < 2) return curves.map { c -> c.map { it.copy() } }

        val groups = ArrayList<Group>()
        for (raw in curves) {
            val even = Polyline.resample(raw, SAMPLES)
            if (even.size < SAMPLES) {                 // too short to compare
                groups.add(Group(raw, null))
                continue
            }
            val near = groups.firstOrNull { g ->
                g.mean != null && distance(g.mean!!, even) <= tolerance(g.mean!!, even, fraction)
            }
            if (near == null) {
                groups.add(Group(raw, even))
            } else {
                near.add(oriented(even, near.mean!!))
            }
        }
        return groups.map { it.result() }
    }

    /** The same, from the strokes a selection hands over. */
    fun mergeStrokes(
        strokes: List<Stroke>,
        fraction: Double = Tune.CURVE_MERGE_FRACTION,
    ): List<List<Vec3>> =
        merge(strokes.map { st -> st.pts.map { it.p.copy() } }, fraction)

    /**
     * How far apart two same-length sampled curves run, on average.
     *
     * Measured both ways round and the smaller taken: a curve drawn right to
     * left is the same curve as one drawn left to right, and comparing them
     * end-to-end would call two copies of one line the furthest apart things
     * in the selection.
     */
    fun distance(a: List<Vec3>, b: List<Vec3>): Double {
        val n = min(a.size, b.size)
        if (n == 0) return Double.MAX_VALUE
        var straight = 0.0
        var flipped = 0.0
        for (i in 0 until n) {
            straight += a[i].distanceTo(b[i])
            flipped += a[i].distanceTo(b[n - 1 - i])
        }
        return min(straight, flipped) / n
    }

    /**
     * How close counts as close, for this pair.
     *
     * A FRACTION OF THE CURVES' OWN LENGTH rather than a distance in
     * millimetres, because "close" is a statement about the drawing and not
     * about the world: two strokes 5mm apart are the same line on a
     * hand-sized form and two different lines on a fingernail. The longer of
     * the two sets the scale, so a short curve lying beside a long one is not
     * swallowed just for being short.
     */
    private fun tolerance(a: List<Vec3>, b: List<Vec3>, fraction: Double): Double =
        max(length(a), length(b)) * fraction

    private fun length(c: List<Vec3>): Double {
        var s = 0.0
        for (i in 1 until c.size) s += c[i - 1].distanceTo(c[i])
        return s
    }

    /** [c] the way round [ref] runs, so an average does not fold in half. */
    private fun oriented(c: List<Vec3>, ref: List<Vec3>): List<Vec3> {
        val n = min(c.size, ref.size)
        if (n == 0) return c
        var straight = 0.0
        var flipped = 0.0
        for (i in 0 until n) {
            straight += ref[i].distanceTo(c[i])
            flipped += ref[i].distanceTo(c[n - 1 - i])
        }
        return if (flipped < straight) c.reversed() else c
    }

    /**
     * One bundle: what it was made of, and the running mean it is matched on.
     *
     * The mean is kept up to date as members arrive rather than computed at
     * the end, so a curve is compared against where the bundle actually is
     * rather than against whichever member happened to be first — a bundle
     * that drifts across the form still reads as one bundle.
     */
    private class Group(private val first: List<Vec3>, var mean: List<Vec3>?) {
        private val members = ArrayList<List<Vec3>>()

        init { mean?.let { members.add(it) } }

        fun add(c: List<Vec3>) {
            members.add(c)
            val n = members[0].size
            mean = (0 until n).map { i ->
                var x = 0.0; var y = 0.0; var z = 0.0
                for (m in members) { x += m[i].x; y += m[i].y; z += m[i].z }
                Vec3(x / members.size, y / members.size, z / members.size)
            }
        }

        fun result(): List<Vec3> =
            if (members.size <= 1) first.map { it.copy() } else mean!!.map { it.copy() }
    }
}
