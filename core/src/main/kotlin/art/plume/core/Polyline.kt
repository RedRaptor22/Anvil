package art.plume.core

import kotlin.math.max

/**
 * Operations on a polyline that both builds need to agree on.
 *
 * Ported from `P.resample` and `P.smoothPolyline` in `js/core.js`. A guide's
 * profile is a resampled stroke, so if these two disagree between the builds
 * then every guide made from the same stroke has a different shape — which is
 * the sort of drift that shows up as "the phone draws it slightly bigger" and
 * is very hard to trace back here.
 */
object Polyline {

    /**
     * [n] points spread evenly BY ARC LENGTH along the polyline.
     *
     * Evenly by arc length, not by index: a freehand stroke is sampled by the
     * pen's speed, so its points bunch where the hand slowed down. Resampling
     * by index would carry that bunching into the guide's profile and put the
     * section lines wherever the drawing happened to hesitate.
     */
    fun resample(pts: List<Vec3>, n: Int): List<Vec3> {
        if (pts.isEmpty() || n <= 0) return emptyList()
        val out = ArrayList<Vec3>(n)
        if (pts.size == 1 || n == 1) {
            for (i in 0 until n) out.add(pts[0].copy())
            return out
        }
        val l = Frames.arcLengths(pts)
        val total = l[l.size - 1]
        if (total < Vec3.EPS) {
            for (i in 0 until n) out.add(pts[0].copy())
            return out
        }
        var j = 0
        for (i in 0 until n) {
            val target = total * (i.toDouble() / (n - 1))
            while (j < l.size - 2 && l[j + 1] < target) j++
            val span = l[j + 1] - l[j]
            val t = if (span < Vec3.EPS) 0.0 else (target - l[j]) / span
            out.add(
                Vec3(
                    pts[j].x + (pts[j + 1].x - pts[j].x) * t,
                    pts[j].y + (pts[j + 1].y - pts[j].y) * t,
                    pts[j].z + (pts[j + 1].z - pts[j].z) * t,
                ),
            )
        }
        return out
    }

    /**
     * Chaikin-style smoothing with the ENDPOINTS PINNED.
     *
     * Pinned because a guide's profile has to keep meeting the stroke that
     * made it: letting the ends creep inwards shortens the surface a little on
     * every pass, and a shape smoothed twice would no longer be the shape that
     * was drawn.
     */
    fun smooth(pts: List<Vec3>, iterations: Int, strength: Double = 0.5): List<Vec3> {
        var cur = pts.map { it.copy() }
        val s = clamp(strength, 0.0, 1.0)
        for (it0 in 0 until iterations) {
            if (cur.size < 3) break
            val next = ArrayList<Vec3>(cur.size)
            next.add(cur[0].copy())
            for (i in 1 until cur.size - 1) {
                val a = cur[i - 1]; val b = cur[i]; val c = cur[i + 1]
                next.add(
                    Vec3(
                        b.x + ((a.x + c.x) * 0.5 - b.x) * s,
                        b.y + ((a.y + c.y) * 0.5 - b.y) * s,
                        b.z + ((a.z + c.z) * 0.5 - b.z) * s,
                    ),
                )
            }
            next.add(cur[cur.size - 1].copy())
            cur = next
        }
        return cur
    }

    /** The centroid of a point list; the zero vector for an empty one. */
    fun centroid(pts: List<Vec3>, out: Vec3 = Vec3()): Vec3 {
        out.set(0.0, 0.0, 0.0)
        if (pts.isEmpty()) return out
        for (p in pts) { out.x += p.x; out.y += p.y; out.z += p.z }
        val k = 1.0 / pts.size
        out.x *= k; out.y *= k; out.z *= k
        return out
    }

    /**
     * Rotate [v] by the minimal rotation carrying [from] onto [to], both unit.
     *
     * The antipodal case has no minimal rotation — every half-turn works — so
     * the caller decides what to do there rather than getting an arbitrary
     * axis out of a degenerate cross product.
     */
    fun rotateBetween(from: Vec3, to: Vec3, v: Vec3, out: Vec3): Vec3 {
        val d = clamp(from dot to, -1.0, 1.0)
        if (d > 0.999999) return out.set(v)
        val axis = from cross to
        if (axis.lengthSq() < Vec3.EPS) return out.set(v)
        axis.normalize()
        return out.set(v).rotateAbout(axis, kotlin.math.acos(d))
    }

    /**
     * A cardinal spline segment. [tension] 0 is sharp (piecewise linear), 1 is
     * smooth. This is the interpolator behind Loft's tension slider.
     */
    fun catmullRom(
        p0: Vec3, p1: Vec3, p2: Vec3, p3: Vec3, t: Double, tension: Double = 1.0,
    ): Vec3 {
        val t2 = t * t
        val t3 = t2 * t
        val m1 = (p2 - p0) * (0.5 * tension)
        val m2 = (p3 - p1) * (0.5 * tension)
        val out = p1 * (2 * t3 - 3 * t2 + 1)
        out.addScaled(m1, t3 - 2 * t2 + t)
        out.addScaled(p2, -2 * t3 + 3 * t2)
        out.addScaled(m2, t3 - t2)
        return out
    }

    /** Sample a chain of control points with the cardinal spline, ends clamped. */
    fun sampleChain(ctrl: List<Vec3>, samples: Int, tension: Double = 1.0): List<Vec3> {
        val n = ctrl.size
        val out = ArrayList<Vec3>(samples)
        if (n == 0) return out
        if (n == 1) { for (i in 0 until samples) out.add(ctrl[0].copy()); return out }
        val segs = n - 1
        for (i in 0 until samples) {
            val u = (i.toDouble() / (samples - 1)) * segs
            val sIdx = kotlin.math.min(segs - 1, kotlin.math.floor(u).toInt())
            val t = u - sIdx
            out.add(
                catmullRom(
                    ctrl[max(0, sIdx - 1)], ctrl[sIdx],
                    ctrl[sIdx + 1], ctrl[kotlin.math.min(n - 1, sIdx + 2)], t, tension,
                ),
            )
        }
        return out
    }

    /** The largest distance from [centre] to any of [pts]. */
    fun extentFrom(pts: List<Vec3>, centre: Vec3): Double {
        var e = 0.0
        for (p in pts) e = max(e, p.distanceTo(centre))
        return e
    }
}
