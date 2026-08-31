package art.plume.core

import kotlin.math.ceil
import kotlin.math.max

/**
 * Filling a whole guide with paint.
 *
 * Rows of strokes a nib apart, laid across the surface in the surface's own
 * arc-length coordinates — which is the whole reason `uv` carries millimetres
 * rather than a 0..1 coordinate. Rows at chosen DISTANCES do not fall on grid
 * lines.
 */
object Fill {

    /** Rows this fraction of a nib apart, so there are no seams. */
    const val OVERLAP = 0.9

    /** A runaway fill is a hang; refuse instead. */
    const val MAX_ROWS = 400

    sealed class Result {
        class Filled(val strokes: List<Stroke>) : Result()
        /** Why it could not: a message the UI can show as-is. */
        class Refused(val reason: String) : Result()
    }

    /** [proto] supplies the brush, colour and size the fill is painted with. */
    fun fillGuide(guide: Guide, proto: Stroke): Result {
        val span = GuidePainting.surfaceSpan(guide)
            ?: return Result.Refused("This guide cannot be filled")

        val half = StrokeGeometry.halfWidth(proto, proto.baseRadius)
        val pitch = max(half * 2 * OVERLAP, 1e-5)

        // run the strokes the LONG way, so a fill is a few long curves rather
        // than hundreds of stubs
        val alongV = span.lv >= span.lu
        val lengthL = if (alongV) span.lv else span.lu
        val across = if (alongV) span.lu else span.lv
        if (!(lengthL > Vec3.EPS) || !(across > Vec3.EPS)) {
            return Result.Refused("This guide is too small to fill")
        }

        /*
         * CEIL, NOT ROUND. Rounding down leaves a step wider than the nib and
         * the rows stop touching: a 960mm guide under a 238mm nib rounded to 4
         * rows at 240mm apart and left a 2mm groove down every seam. Rounding
         * up can only make rows overlap more, which is invisible.
         */
        val rows = max(1, ceil(across / pitch).toInt())
        if (rows > MAX_ROWS) {
            return Result.Refused("Brush too fine to fill this guide — make it larger")
        }
        val step = across / rows

        /* Follow the surface at its own resolution along the stroke. A flat
           guide has no grid to follow, so sample it every few millimetres
           instead — fine enough to cut a row cleanly where it crosses the
           outline. */
        val nodes = if (alongV) span.nv else span.nu
        val steps = if (nodes >= 2) clamp(nodes, 2, 240)
        else clamp(ceil(lengthL / (2 * MM)).toInt(), 2, 240)

        val made = ArrayList<Stroke>()
        for (r in 0 until rows) {
            val lateral = (r + 0.5) * step
            /*
             * A ROW IS NOT ALWAYS ONE STROKE. Off a rectangle it is, but a
             * drawn outline can be concave or pinched, and a row crossing the
             * gap in a horseshoe leaves the shape and comes back. Skipping the
             * missing samples would join the two halves with a stroke straight
             * across the hole, so a row is broken into runs of consecutive
             * samples that are actually ON the surface, and each run becomes
             * its own stroke.
             */
            var run: Stroke? = null
            for (i in 0 until steps) {
                val along = lengthL * (i.toDouble() / (steps - 1))
                val su = if (alongV) lateral else along
                val sv = if (alongV) along else lateral
                val hit = GuidePainting.sampleSurface(guide, su, sv)
                if (hit == null) { run = closeRun(run, made); continue }
                if (run == null) {
                    run = proto.withPoints(emptyList())
                    run.guideId = guide.id
                }
                run.pts.add(
                    StrokePoint(hit.point.copy(), pressure = 1.0, nrm = hit.normal.copy()),
                )
            }
            closeRun(run, made)
        }
        return if (made.isEmpty()) Result.Refused("Nothing to fill") else Result.Filled(made)
    }

    private fun closeRun(run: Stroke?, into: MutableList<Stroke>): Stroke? {
        if (run != null && run.pts.size >= 2) into.add(run)
        return null
    }
}
