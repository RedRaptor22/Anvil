package art.plume.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Tangents and rotation-minimising frames along a polyline.
 *
 * This is the load-bearing maths of the whole sketchbook: every stroke is a
 * tube swept along its own points, and every guide is a profile swept along a
 * path, and both need a frame at each sample that does not spin as the curve
 * bends. Ported from `P.computeTangents` / `P.transportFrames` in `js/core.js`,
 * including two corrections that were expensive to find the first time:
 *
 *  - interior tangents are the bisector of the two UNIT chords, not the central
 *    difference. Clamping samples onto a guide bunches them, and where a path
 *    doubles back inside one sample the central difference points backwards,
 *    the ring built on it is inside out, and a wide nib turns that into a plate
 *    of paint standing off the surface.
 *
 *  - a closed path gets wrap-around endpoints and a closed frame. Transport
 *    around a loop does not return to where it started; the residual twist is
 *    unwound evenly along the loop so the two ends meet exactly instead of
 *    leaving a wedge at the seam.
 */
object Frames {

    /**
     * Unit tangents at every point.
     *
     * [closed] means the last point IS the first again — a ring, not a tube
     * with two ends. Its endpoints then take the same wrap-around bisector
     * every interior point gets, so the two ends agree instead of being
     * one-sided chords a full angular step apart. That step is exactly what
     * opens a wedge at a circle's seam: 360/64 = 5.625 degrees of tilt between
     * two caps stacked on the same point.
     */
    fun computeTangents(pts: List<Vec3>, closed: Boolean = false): List<Vec3> {
        val n = pts.size
        if (n == 0) return emptyList()
        if (n == 1) return listOf(Vec3(0.0, 0.0, 1.0))

        val t = ArrayList<Vec3>(n)
        val loop = closed && n >= 3
        val back = Vec3(); val fwd = Vec3(); val v = Vec3()

        for (i in 0 until n) {
            v.set(0.0, 0.0, 0.0)
            if (loop && (i == 0 || i == n - 1)) {
                // the neighbours either side of the joint
                back.set(pts[0] - pts[n - 2])
                fwd.set(pts[1] - pts[0])
                bisect(back, fwd, v)
            } else if (i == 0) {
                v.set(pts[1] - pts[0])
            } else if (i == n - 1) {
                v.set(pts[n - 1] - pts[n - 2])
            } else {
                back.set(pts[i] - pts[i - 1])
                fwd.set(pts[i + 1] - pts[i])
                bisect(back, fwd, v)
            }

            if (v.lengthSq() > Vec3.EPS) { t.add(v.copy().normalize()); continue }
            if (i > 0) { t.add(t[i - 1].copy()); continue }
            // everything coincident so far: look ahead for anything to point at
            var found: Vec3? = null
            var j = i + 1
            while (j < n && found == null) {
                val d = pts[j] - pts[i]
                if (d.lengthSq() > Vec3.EPS) found = d.normalize()
                j++
            }
            t.add(found ?: Vec3(0.0, 0.0, 1.0))
        }
        return t
    }

    /**
     * The bisector of two chords as UNIT vectors, which weights them equally
     * however uneven the spacing and can only fail on an exact 180-degree
     * hairpin — where carrying on forwards is the sane answer anyway.
     */
    private fun bisect(back: Vec3, fwd: Vec3, out: Vec3) {
        val lb = back.lengthSq(); val lf = fwd.lengthSq()
        if (lb > Vec3.EPS && lf > Vec3.EPS) {
            back.normalize(); fwd.normalize()
            out.set(back + fwd)
            if (out.lengthSq() <= Vec3.EPS) out.set(fwd)
        } else if (lf > Vec3.EPS) out.set(fwd)
        else if (lb > Vec3.EPS) out.set(back)
        else out.set(0.0, 0.0, 0.0)
    }

    /** Tangents and reference vectors together. */
    data class FrameSet(val t: List<Vec3>, val r: List<Vec3>)

    /**
     * Rotation-minimising frames by double reflection (Wang et al. 2008).
     *
     * Used for stroke cross-sections AND as the parallel-transport sweep behind
     * Bend, so a profile carried round a curve does not twist. A Frenet frame
     * would flip at an inflection; this does not.
     */
    fun transportFrames(pts: List<Vec3>, seedRef: Vec3? = null, closed: Boolean = false): FrameSet {
        val t = computeTangents(pts, closed)
        val n = pts.size
        if (n == 0) return FrameSet(emptyList(), emptyList())

        val r = ArrayList<Vec3>(n)
        val r0 = Vec3()
        if (seedRef != null) {
            r0.set(seedRef).addScaled(t[0], -(seedRef dot t[0]))
            if (r0.lengthSq() < Vec3.EPS) Vec3.perpTo(t[0], r0) else r0.normalize()
        } else Vec3.perpTo(t[0], r0)
        r.add(r0)

        val v1 = Vec3(); val v2 = Vec3()
        val rL = Vec3(); val tL = Vec3(); val rN = Vec3()

        for (i in 0 until n - 1) {
            v1.set(pts[i + 1] - pts[i])
            val c1 = v1.lengthSq()
            if (c1 < Vec3.EPS) {
                rN.set(r[i])
            } else {
                rL.set(r[i]).addScaled(v1, -2.0 * (v1 dot r[i]) / c1)
                tL.set(t[i]).addScaled(v1, -2.0 * (v1 dot t[i]) / c1)
                v2.set(t[i + 1] - tL)
                val c2 = v2.lengthSq()
                rN.set(rL)
                if (c2 >= Vec3.EPS) rN.addScaled(v2, -2.0 * (v2 dot rL) / c2)
            }
            val rr = rN.copy().addScaled(t[i + 1], -(rN dot t[i + 1]))
            if (rr.lengthSq() < Vec3.EPS) Vec3.perpTo(t[i + 1], rr) else rr.normalize()
            r.add(rr)
        }

        /*
         * CLOSE THE FRAME. Transport around a loop does not come back to where
         * it started — the sweep accumulates a residual twist (holonomy), so
         * the last cross-section lands rotated against the first even though
         * both sit on the same point with the same tangent, and the tube
         * visibly steps at the joint. Unwind that angle evenly along the loop
         * by arc length: every section turns a little, none of them kinks, and
         * the ends meet exactly.
         */
        if (closed && n >= 3) {
            val bi = t[0] cross r[0]
            val theta = atan2(bi dot r[n - 1], r[0] dot r[n - 1])
            if (abs(theta) > 1e-12) {
                val l = arcLengths(pts)
                val total = l[n - 1]
                if (total > Vec3.EPS) {
                    for (q in 0 until n) {
                        r[q].rotateAbout(t[q], -theta * (l[q] / total)).normalize()
                    }
                }
            }
            r[n - 1].set(r[0])
            t[n - 1].set(t[0])
        }
        return FrameSet(t, r)
    }

    /** Cumulative arc length along the polyline; index 0 is always 0. */
    fun arcLengths(pts: List<Vec3>): DoubleArray {
        val l = DoubleArray(pts.size)
        for (i in 1 until pts.size) l[i] = l[i - 1] + pts[i].distanceTo(pts[i - 1])
        return l
    }

    fun polyLength(pts: List<Vec3>): Double {
        val l = arcLengths(pts)
        return if (l.isEmpty()) 0.0 else l[l.size - 1]
    }

    /**
     * A path whose last point is its first is a ring, not a tube that happens
     * to end where it began. Read off the geometry rather than carried as a
     * flag, so it survives save, undo and every transform — and covers every
     * route that makes a circle, not just the one that remembered to set it.
     */
    fun loopsClosed(pts: List<Vec3>): Boolean {
        val n = pts.size
        if (n < 8) return false
        val gap = pts[0].distanceTo(pts[n - 1])
        val step = pts[n - 2].distanceTo(pts[n - 1])
        return step > 0 && gap <= step * 1e-3
    }
}
