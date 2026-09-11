package art.plume.core

import kotlin.math.abs

/**
 * Ear clipping for a simple polygon in 2D.
 *
 * The flat guide is triangulated TO ITS OUTLINE rather than built as a grid
 * clipped against it, so its edge is the curve that was drawn and not a
 * staircase of cells. That is the whole reason this exists: the web build gets
 * it from `THREE.ShapeGeometry`, and there is no equivalent here.
 *
 * Ear clipping is the right trade for this input. A freehand outline is a few
 * dozen points, so O(n²) is nothing, and unlike a sweep-line triangulation it
 * degrades gracefully — a self-crossing loop still produces triangles rather
 * than failing, which matters because a hand-drawn loop frequently is one.
 */
object Triangulate {

    /** Twice the signed area; positive when the polygon winds counter-clockwise. */
    fun signedArea2(poly: List<UV>): Double {
        var a = 0.0
        var j = poly.size - 1
        for (i in poly.indices) {
            a += (poly[j].u - poly[i].u) * (poly[j].v + poly[i].v)
            j = i
        }
        return a
    }

    /**
     * Triangle indices into [poly], or an empty array if it cannot be
     * triangulated at all.
     *
     * The result always winds the same way regardless of how the polygon was
     * drawn — a guide is drawn clockwise as often as not, and a surface whose
     * triangles face backwards is invisible from the side you drew it on.
     */
    fun earClip(poly: List<UV>): IntArray {
        val n = poly.size
        if (n < 3) return IntArray(0)

        // work on a copy of the index ring, wound counter-clockwise
        val idx = ArrayList<Int>(n)
        if (signedArea2(poly) > 0) for (i in 0 until n) idx.add(i)
        else for (i in n - 1 downTo 0) idx.add(i)

        val out = ArrayList<Int>((n - 2) * 3)
        var guard = 0
        var i = 0
        while (idx.size > 3) {
            /*
             * The bail-out matters. A self-crossing outline has no valid ear
             * anywhere, and without a bound this spins forever on input a
             * person can produce by accident in one gesture. Falling through
             * to a fan is not correct for such a polygon, but it is finite and
             * it covers the shape, which is what a guide needs.
             */
            if (guard++ > idx.size * idx.size + 16) break

            val prev = idx[(i + idx.size - 1) % idx.size]
            val cur = idx[i % idx.size]
            val next = idx[(i + 1) % idx.size]
            if (isEar(poly, idx, prev, cur, next)) {
                out.add(prev); out.add(cur); out.add(next)
                idx.removeAt(i % idx.size)
                guard = 0
                if (i >= idx.size) i = 0
            } else {
                i = (i + 1) % idx.size
            }
        }
        if (idx.size == 3) { out.add(idx[0]); out.add(idx[1]); out.add(idx[2]) }
        else if (idx.size > 3) {
            // the degenerate fall-through: a fan from the first survivor
            for (k in 1 until idx.size - 1) {
                out.add(idx[0]); out.add(idx[k]); out.add(idx[k + 1])
            }
        }
        return out.toIntArray()
    }

    private fun isEar(poly: List<UV>, ring: List<Int>, a: Int, b: Int, c: Int): Boolean {
        val ax = poly[a].u; val ay = poly[a].v
        val bx = poly[b].u; val by = poly[b].v
        val cx = poly[c].u; val cy = poly[c].v

        // convex in a counter-clockwise ring means a positive cross product
        val cross = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        if (cross <= 1e-15) return false

        for (k in ring) {
            if (k == a || k == b || k == c) continue
            if (pointInTriangle(poly[k].u, poly[k].v, ax, ay, bx, by, cx, cy)) return false
        }
        return true
    }

    private fun pointInTriangle(
        px: Double, py: Double,
        ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double,
    ): Boolean {
        val d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by)
        val d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy)
        val d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay)
        val neg = (d1 < 0) || (d2 < 0) || (d3 < 0)
        val pos = (d1 > 0) || (d2 > 0) || (d3 > 0)
        return !(neg && pos)
    }

    /** Drop consecutive duplicates, which a freehand loop is full of. */
    fun dedupe(poly: List<UV>, eps: Double = 1e-9): List<UV> {
        val out = ArrayList<UV>(poly.size)
        for (q in poly) {
            val last = out.lastOrNull()
            if (last != null && abs(q.u - last.u) < eps && abs(q.v - last.v) < eps) continue
            out.add(q)
        }
        return out
    }
}
