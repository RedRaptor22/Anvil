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

    /**
     * THE NIB A FILL IS LAID WITH, WHATEVER BRUSH IS IN THE HAND.
     *
     * A fill is not brushwork. It is the guide's own shape in a colour, and
     * the brush you happen to be holding is not part of that shape — it was
     * chosen for the marks you draw BY hand, and a fill is the one mark you
     * are asking not to have to.
     *
     * Taking the held brush made a fill inherit everything the brush was for:
     * the sketch pencil filled a guide in grain and let the surface show
     * through it, glow filled it with light instead of paint, taper thinned
     * every row at both ends and left the outline ragged, and the round nibs
     * filled a flat sheet with tubes. None of that is the guide's shape.
     *
     * `flat` is the one that is: a hard-edged ribbon (`square` 1, so rows abut
     * instead of leaving scalloped seams) thin enough to read as a coat of
     * paint rather than a slab — it stands on the surface like everything else
     * does now (see [StrokeGeometry.standsOn]), by the least any brush can —
     * with no taper, no grain and no glow.
     *
     * Colour, size and opacity still come from the caller. Those are choices
     * about the fill; the brush was a choice about something else.
     */
    const val BRUSH = "flat"

    sealed class Result {
        class Filled(val strokes: List<Stroke>) : Result()
        /** Why it could not: a message the UI can show as-is. */
        class Refused(val reason: String) : Result()
    }

    /**
     * [proto] supplies the colour, size and opacity a fill is painted with.
     * Its BRUSH is deliberately not used — see [BRUSH].
     *
     * [eye] is where the fill is being watched from, and it decides which face
     * of the guide the paint stands on — the same question a hand-drawn sample
     * answers from its own pen ray. A fill covers the whole surface, including
     * parts of it turned away from you, so it is answered per row rather than
     * once: each run of paint stands on the side of the guide that was facing
     * the eye underneath it. Null leaves the surface's own winding to decide,
     * which is what a fill with no camera to consult can honestly do.
     */
    fun fillGuide(guide: Guide, proto: Stroke, eye: Vec3? = null): Result {
        val span = GuidePainting.surfaceSpan(guide)
            ?: return Result.Refused("This guide cannot be filled")

        /* the copy is what every row is cut from, so the substitution happens
           once, here, and the pitch below is measured off the nib that is
           actually going to be laid down */
        val nib = proto.withPoints(emptyList())
        nib.brush = BRUSH

        val half = StrokeGeometry.halfWidth(nib, nib.baseRadius)
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
                    run = nib.withPoints(emptyList())
                    run.guideId = guide.id
                }
                val n = hit.normal.copy()
                if (eye != null && ((n dot (eye - hit.point)) < 0.0)) {
                    n.set(-n.x, -n.y, -n.z)
                }
                run.pts.add(StrokePoint(hit.point.copy(), pressure = 1.0, nrm = n))
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
