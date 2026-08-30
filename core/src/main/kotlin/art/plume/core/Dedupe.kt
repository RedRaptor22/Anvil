package art.plume.core

import kotlin.math.abs
import kotlin.math.max

/**
 * Cleaning a stroke's samples before any frame maths touches them.
 *
 * Ported from `S.dedupe` in `js/strokes.js`, and it belongs in the pipeline
 * rather than being optional: [Frames.computeTangents] deliberately does NOT
 * try to rescue a folded sample, because the honest fix is to remove the fold.
 * A stroke that keeps one produces a tangent pointing backwards, the ring built
 * on it is inside out, and a wide nib turns that into a plate of paint standing
 * off the surface at a wild angle.
 */
object Dedupe {

    /**
     * Drops coincident points, then spurs.
     *
     * A point is a spur when the path REVERSES through it — the step in and the
     * step out point opposite ways — and cutting it out moves the path less
     * than this brush could draw anyway. That second clause is what keeps a
     * deliberate sharp corner: the tip of a real V stands most of an arm away
     * from the line joining its ends, whatever the brush.
     *
     * The excursion is measured to the SEGMENT, not to its infinite line. These
     * folds are very nearly straight backtracks, so the tip sits on the line but
     * well outside the span, and a line distance calls a 0.35mm step back zero.
     *
     * @return how many points were removed
     */
    fun clean(stroke: Stroke): Int {
        val pts = stroke.pts
        if (pts.isEmpty()) return 0
        val before = pts.size

        var out = ArrayList<StrokePoint>(pts.size)
        out.add(pts[0])
        for (i in 1 until pts.size) {
            if (pts[i].p.distanceToSq(out[out.size - 1].p) > 1e-10) out.add(pts[i])
        }

        val half = abs(stroke.baseRadius * stroke.cfg.wide)
        val flat = max(0.25 * MM, half * 0.01)

        var changed = true
        while (changed && out.size > 2) {
            changed = false
            val keep = ArrayList<StrokePoint>(out.size)
            keep.add(out[0])
            for (i in 1 until out.size - 1) {
                val a = keep[keep.size - 1].p
                val b = out[i].p
                val c = out[i + 1].p
                val ab = b - a
                val bc = c - b
                if ((ab dot bc) < 0) {
                    val ac = c - a
                    val len2 = ac.lengthSq()
                    val t = if (len2 > Vec3.EPS) clamp((ab dot ac) / len2, 0.0, 1.0) else 0.0
                    val off = ab.addScaled(ac, -t).length()
                    if (off <= flat) { changed = true; continue }
                }
                keep.add(out[i])
            }
            keep.add(out[out.size - 1])
            out = keep
        }

        if (out.size != before) { pts.clear(); pts.addAll(out) }
        return before - out.size
    }

    /** The same clean applied to a bare point list, for tests and tools. */
    fun clean(points: List<Vec3>, brushHalfWidth: Double): List<Vec3> {
        val s = Stroke(baseRadius = brushHalfWidth)
        points.forEach { s.pts.add(StrokePoint(it.copy())) }
        clean(s)
        return s.pts.map { it.p }
    }
}
