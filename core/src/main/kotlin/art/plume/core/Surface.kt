package art.plume.core

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A triangle mesh a stroke can be painted onto, and the nearest-point query
 * that keeps it there.
 *
 * The query is the interesting part, and it is worth recording why it looks
 * like this. The first version indexed VERTICES and refined over the triangles
 * touching the nearest one. That is wrong on a coarse mesh: a guide here is
 * ~520 vertices carrying ~896 triangles, so a point can lie exactly ON a large
 * triangle while the nearest vertex is 67mm away and belongs to a different
 * one. Snapping then moved points that were already correct — still on the
 * guide, but slid 21mm along it, corrupting the drawing rather than repairing
 * it. It was caught by checking the query against brute force over every
 * triangle, which disagreed by 21.25mm.
 *
 * Indexing TRIANGLES agrees with brute force to 0.000mm.
 */
class SurfaceMesh(val positions: FloatArray, val indices: IntArray) {

    val triangleCount = indices.size / 3

    private var grid: Grid? = null

    private class Grid(
        val map: HashMap<Long, IntArray>,
        val minX: Double, val minY: Double, val minZ: Double,
        val stepX: Double, val stepY: Double, val stepZ: Double,
        val cells: Int,
    ) {
        val minStep = min(stepX, min(stepY, stepZ))
        val seen = IntArray(0)
    }

    private var seen = IntArray(0)
    private var stamp = 0

    /** cell -> one long. Packed so the search never builds a string key. */
    private fun key(x: Int, y: Int, z: Int): Long =
        ((x + 1024).toLong() * 2048L + (y + 1024).toLong()) * 2048L + (z + 1024).toLong()

    private fun buildGrid(): Grid {
        grid?.let { return it }
        var loX = Double.MAX_VALUE; var loY = Double.MAX_VALUE; var loZ = Double.MAX_VALUE
        var hiX = -Double.MAX_VALUE; var hiY = -Double.MAX_VALUE; var hiZ = -Double.MAX_VALUE
        var i = 0
        while (i < positions.size) {
            loX = min(loX, positions[i].toDouble()); hiX = max(hiX, positions[i].toDouble())
            loY = min(loY, positions[i + 1].toDouble()); hiY = max(hiY, positions[i + 1].toDouble())
            loZ = min(loZ, positions[i + 2].toDouble()); hiZ = max(hiZ, positions[i + 2].toDouble())
            i += 3
        }
        val cells = clamp(Math.round(cbrt(max(triangleCount, 1).toDouble())).toInt(), 2, 32)
        val sx = max((hiX - loX) / cells, 1e-6)
        val sy = max((hiY - loY) / cells, 1e-6)
        val sz = max((hiZ - loZ) / cells, 1e-6)

        val buckets = HashMap<Long, ArrayList<Int>>()
        for (t in 0 until triangleCount) {
            val a = indices[t * 3] * 3
            val b = indices[t * 3 + 1] * 3
            val c = indices[t * 3 + 2] * 3
            // every cell the triangle's own bounding box touches, so a long
            // triangle is findable from anywhere along it, not just its corners
            val x0 = floor((minOf(positions[a], positions[b], positions[c]) - loX) / sx).toInt()
            val x1 = floor((maxOf(positions[a], positions[b], positions[c]) - loX) / sx).toInt()
            val y0 = floor((minOf(positions[a + 1], positions[b + 1], positions[c + 1]) - loY) / sy).toInt()
            val y1 = floor((maxOf(positions[a + 1], positions[b + 1], positions[c + 1]) - loY) / sy).toInt()
            val z0 = floor((minOf(positions[a + 2], positions[b + 2], positions[c + 2]) - loZ) / sz).toInt()
            val z1 = floor((maxOf(positions[a + 2], positions[b + 2], positions[c + 2]) - loZ) / sz).toInt()
            for (gx in x0..x1) for (gy in y0..y1) for (gz in z0..z1) {
                buckets.getOrPut(key(gx, gy, gz)) { ArrayList() }.add(t)
            }
        }
        val map = HashMap<Long, IntArray>(buckets.size)
        for ((k, v) in buckets) map[k] = v.toIntArray()

        val g = Grid(map, loX, loY, loZ, sx, sy, sz, cells)
        grid = g
        seen = IntArray(triangleCount)
        return g
    }

    /** Drop the cached index after the mesh moves or is rebuilt. */
    fun invalidate() { grid = null }

    private val triA = Vec3(); private val triB = Vec3(); private val triC = Vec3()
    private val hit = Vec3(); private val best = Vec3()

    /**
     * Nearest point on the surface to [p] — exact, not an approximation. Rings
     * of cells are scanned outwards and the search only stops once the best hit
     * is closer than anything an unscanned cell could still hold.
     */
    fun nearestPoint(p: Vec3, out: Vec3 = Vec3()): Vec3 {
        if (triangleCount == 0) return out.set(p)
        val g = buildGrid()
        stamp++

        var bestD = Double.MAX_VALUE
        var found = false

        /*
         * The search grows by WORLD RADIUS, not by rings of cells.
         *
         * Rings looked simpler and were wrong on a flat surface. A plane has no
         * extent in one axis, so that step collapses to the 1e-6 floor, a point
         * 50mm above it lands at cell index 50000, and clamping that back into
         * the grid leaves an empty range — the query then scanned nothing,
         * reported the point as already on the surface, and reprojection
         * silently did nothing. Growing a radius has no such failure: whatever
         * the cell sizes, once everything within `radius` has been tested and
         * the best hit is nearer than `radius`, nothing outside can beat it.
         */
        val hiX = g.minX + g.stepX * g.cells
        val hiY = g.minY + g.stepY * g.cells
        val hiZ = g.minZ + g.stepZ * g.cells
        val ox = max(0.0, max(g.minX - p.x, p.x - hiX))
        val oy = max(0.0, max(g.minY - p.y, p.y - hiY))
        val oz = max(0.0, max(g.minZ - p.z, p.z - hiZ))
        val outside = sqrt(ox * ox + oy * oy + oz * oz)

        val diagonal = sqrt(
            (hiX - g.minX) * (hiX - g.minX) +
                (hiY - g.minY) * (hiY - g.minY) +
                (hiZ - g.minZ) * (hiZ - g.minZ)
        )
        val limit = outside + diagonal + g.minStep
        var radius = max(g.minStep, outside + g.minStep)

        while (true) {
            val xa = clamp(floor((p.x - radius - g.minX) / g.stepX).toInt(), 0, g.cells)
            val xb = clamp(floor((p.x + radius - g.minX) / g.stepX).toInt(), 0, g.cells)
            val ya = clamp(floor((p.y - radius - g.minY) / g.stepY).toInt(), 0, g.cells)
            val yb = clamp(floor((p.y + radius - g.minY) / g.stepY).toInt(), 0, g.cells)
            val za = clamp(floor((p.z - radius - g.minZ) / g.stepZ).toInt(), 0, g.cells)
            val zb = clamp(floor((p.z + radius - g.minZ) / g.stepZ).toInt(), 0, g.cells)

            for (gx in xa..xb) for (gy in ya..yb) for (gz in za..zb) {
                val list = g.map[key(gx, gy, gz)] ?: continue
                for (t in list) {
                    // straddles cells, and survives across doublings: test once
                    if (seen[t] == stamp) continue
                    seen[t] = stamp
                    val a = indices[t * 3] * 3
                    val b = indices[t * 3 + 1] * 3
                    val c = indices[t * 3 + 2] * 3
                    triA.set(positions[a].toDouble(), positions[a + 1].toDouble(), positions[a + 2].toDouble())
                    triB.set(positions[b].toDouble(), positions[b + 1].toDouble(), positions[b + 2].toDouble())
                    triC.set(positions[c].toDouble(), positions[c + 1].toDouble(), positions[c + 2].toDouble())
                    closestOnTriangle(p, triA, triB, triC, hit)
                    val d = hit.distanceToSq(p)
                    if (d < bestD) { bestD = d; best.set(hit); found = true }
                }
            }
            if (found && sqrt(bestD) <= radius) break
            if (radius > limit) break
            radius *= 2
        }
        return if (found) out.set(best) else out.set(p)
    }

    /** Distance from [p] to the surface. */
    fun distanceTo(p: Vec3): Double = nearestPoint(p, Vec3()).distanceTo(p)

    companion object {
        /**
         * Closest point on a triangle to a point — Ericson, *Real-Time
         * Collision Detection* §5.1.5. Exact, branch per Voronoi region, no
         * iteration.
         */
        fun closestOnTriangle(p: Vec3, a: Vec3, b: Vec3, c: Vec3, out: Vec3): Vec3 {
            val ab = b - a; val ac = c - a; val ap = p - a
            val d1 = ab dot ap; val d2 = ac dot ap
            if (d1 <= 0 && d2 <= 0) return out.set(a)

            val bp = p - b
            val d3 = ab dot bp; val d4 = ac dot bp
            if (d3 >= 0 && d4 <= d3) return out.set(b)

            val vc = d1 * d4 - d3 * d2
            if (vc <= 0 && d1 >= 0 && d3 <= 0) return out.set(a).addScaled(ab, d1 / (d1 - d3))

            val cp = p - c
            val d5 = ab dot cp; val d6 = ac dot cp
            if (d6 >= 0 && d5 <= d6) return out.set(c)

            val vb = d5 * d2 - d1 * d6
            if (vb <= 0 && d2 >= 0 && d6 <= 0) return out.set(a).addScaled(ac, d2 / (d2 - d6))

            val va = d3 * d6 - d5 * d4
            if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) {
                return out.set(b).addScaled(c - b, (d4 - d3) / ((d4 - d3) + (d5 - d6)))
            }
            val den = 1.0 / (va + vb + vc)
            return out.set(a).addScaled(ab, vb * den).addScaled(ac, vc * den)
        }
    }
}

/**
 * Holding paint to the surface it was painted on.
 *
 * Smooth averages a point with its two neighbours, which cuts the corner off a
 * curved surface, and Liquify shoves points bodily; both work in free space.
 * Measured in the web build on a swept guide: paint that landed at 0.00mm came
 * away 6.41mm after a Smooth pass and 47.72mm after a Liquify one.
 */
object Reproject {
    /** Snap every point of [stroke] back onto [surface]. */
    fun toSurface(stroke: Stroke, surface: SurfaceMesh) {
        for (pt in stroke.pts) surface.nearestPoint(pt.p, pt.p)
    }
}
