package art.plume.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Stroke geometry and the nearest-surface query.
 *
 * These mirror the properties the web suite asserts, so a divergence between
 * the two builds shows up here rather than on someone's phone.
 */
class GeometryTest {

    private fun strokeAlong(pts: List<Vec3>, brush: String, radius: Double): Stroke {
        val s = Stroke(brush = brush, baseRadius = radius)
        val closed = Frames.loopsClosed(pts)
        val f = Frames.transportFrames(pts, null, closed)
        pts.forEachIndexed { i, p ->
            s.pts.add(StrokePoint(p.copy(), tan = f.t[i].copy(), ref = f.r[i].copy()))
        }
        return s
    }

    private fun line(n: Int) = (0 until n).map { Vec3(it * 0.05, 0.0, 0.0) }
    private fun ring(n: Int, r: Double = 0.5) = (0..n).map {
        val a = it.toDouble() / n * 2 * PI
        Vec3(cos(a) * r, 0.0, sin(a) * r)
    }

    /** boundary edges, non-manifold edges, degenerate triangles */
    private fun shell(m: MeshData): Triple<Int, Int, Int> {
        // weld by position so coincident-but-distinct vertices count as one
        val idOf = HashMap<String, Int>()
        val w = IntArray(m.vertexCount)
        for (i in 0 until m.vertexCount) {
            val k = "${Math.round(m.positions[i * 3] * 1e5)}," +
                "${Math.round(m.positions[i * 3 + 1] * 1e5)}," +
                "${Math.round(m.positions[i * 3 + 2] * 1e5)}"
            w[i] = idOf.getOrPut(k) { idOf.size }
        }
        val edges = HashMap<Long, Int>()
        var degenerate = 0
        var t = 0
        while (t < m.indices.size) {
            val a = w[m.indices[t]]; val b = w[m.indices[t + 1]]; val c = w[m.indices[t + 2]]
            t += 3
            if (a == b || b == c || a == c) { degenerate++; continue }
            for ((u, v) in listOf(a to b, b to c, c to a)) {
                val lo = minOf(u, v).toLong(); val hi = maxOf(u, v).toLong()
                val k = lo * 1_000_000L + hi
                edges[k] = (edges[k] ?: 0) + 1
            }
        }
        var boundary = 0; var nonManifold = 0
        for (c in edges.values) { if (c == 1) boundary++ else if (c > 2) nonManifold++ }
        return Triple(boundary, nonManifold, degenerate)
    }

    @Test
    fun `every brush builds finite geometry`() {
        for (name in Brushes.table.keys) {
            val s = strokeAlong(line(24), name, 20 * MM)
            val m = StrokeGeometry.build(s)
            assertNotNull(m, "$name built nothing")
            for (v in m.positions) assertTrue(v.isFinite(), "$name has a non-finite position")
            for (v in m.normals) assertTrue(v.isFinite(), "$name has a non-finite normal")
            for (i in m.indices) assertTrue(i in 0 until m.vertexCount, "$name index out of range")
        }
    }

    @Test
    fun `an open stroke is a sealed tube with caps`() {
        val m = StrokeGeometry.build(strokeAlong(line(20), "pen", 15 * MM))!!
        val (boundary, nonManifold, _) = shell(m)
        assertTrue(boundary == 0, "an open stroke should still be closed by its caps, got $boundary")
        assertTrue(nonManifold == 0, "got $nonManifold non-manifold edges")
    }

    @Test
    fun `a ring is welded shut, with no caps and no open rim`() {
        for (name in Brushes.table.keys) {
            val pts = ring(48)
            assertTrue(Frames.loopsClosed(pts))
            val m = StrokeGeometry.build(strokeAlong(pts, name, 12 * MM))!!
            val (boundary, nonManifold, degenerate) = shell(m)
            assertTrue(boundary == 0, "$name left $boundary open rim edges at the seam")
            assertTrue(nonManifold == 0, "$name has $nonManifold non-manifold edges")
            assertTrue(degenerate == 0, "$name has $degenerate degenerate triangles")
        }
    }

    @Test
    fun `a ring costs fewer vertices than the open tube it replaces`() {
        val pts = ring(48)
        val s = strokeAlong(pts, "pen", 12 * MM)
        val closedMesh = StrokeGeometry.build(s)!!
        // the duplicate ring is dropped, so it is one ring lighter
        val seg = StrokeGeometry.segmentsFor(s)
        assertTrue(
            closedMesh.vertexCount == 2 + (pts.size - 1) * seg,
            "expected the duplicate ring to be dropped"
        )
    }

    @Test
    fun `the square section is a rectangle and the round one is a circle`() {
        // square 1 -> Chebyshev: the corner sits at sqrt(2) of the flat
        val flat = StrokeGeometry.sectionPoint(0.0, 1.0)
        val corner = StrokeGeometry.sectionPoint(PI / 4, 1.0)
        assertTrue(abs(hypot(flat.first, flat.second) - 1.0) < 1e-12)
        assertTrue(abs(hypot(corner.first, corner.second) - kotlin.math.sqrt(2.0)) < 1e-9)
        // square 0 -> every angle is on the unit circle
        for (i in 0 until 32) {
            val (x, y) = StrokeGeometry.sectionPoint(i / 32.0 * 2 * PI, 0.0)
            assertTrue(abs(hypot(x, y) - 1.0) < 1e-12)
        }
    }

    @Test
    fun `the wide brush holds its 2mm thickness at any size`() {
        for (mm in listOf(20.0, 90.0, 300.0)) {
            val s = Stroke(brush = "wide", baseRadius = mm * MM * 0.5)
            assertTrue(
                abs(StrokeGeometry.halfThick(s, s.baseRadius) - 1.0 * MM) < 1e-12,
                "wide should stay 2mm thick at ${mm}mm, got ${StrokeGeometry.halfThick(s, s.baseRadius) / MM * 2}mm"
            )
        }
    }

    // ---- the nearest-surface query -------------------------------------

    /** a coarse grid surface: exactly the case that broke the vertex index */
    private fun coarsePlane(n: Int, span: Double): SurfaceMesh {
        val pos = FloatArray((n + 1) * (n + 1) * 3)
        for (r in 0..n) for (c in 0..n) {
            val i = (r * (n + 1) + c) * 3
            pos[i] = (c.toDouble() / n * span - span / 2).toFloat()
            pos[i + 1] = 0f
            pos[i + 2] = (r.toDouble() / n * span - span / 2).toFloat()
        }
        val idx = ArrayList<Int>()
        for (r in 0 until n) for (c in 0 until n) {
            val a = r * (n + 1) + c; val b = a + 1
            val d = (r + 1) * (n + 1) + c; val e = d + 1
            idx.addAll(listOf(a, b, d, b, e, d))
        }
        return SurfaceMesh(pos, idx.toIntArray())
    }

    private fun bruteForce(m: SurfaceMesh, p: Vec3): Double {
        var best = Double.MAX_VALUE
        val a = Vec3(); val b = Vec3(); val c = Vec3(); val out = Vec3()
        for (t in 0 until m.triangleCount) {
            val ia = m.indices[t * 3] * 3; val ib = m.indices[t * 3 + 1] * 3
            val ic = m.indices[t * 3 + 2] * 3
            a.set(m.positions[ia].toDouble(), m.positions[ia + 1].toDouble(), m.positions[ia + 2].toDouble())
            b.set(m.positions[ib].toDouble(), m.positions[ib + 1].toDouble(), m.positions[ib + 2].toDouble())
            c.set(m.positions[ic].toDouble(), m.positions[ic + 1].toDouble(), m.positions[ic + 2].toDouble())
            SurfaceMesh.closestOnTriangle(p, a, b, c, out)
            best = minOf(best, out.distanceTo(p))
        }
        return best
    }

    @Test
    fun `the snap query agrees with brute force over every triangle`() {
        // deliberately coarse: 8x8 cells over a 2-unit span means triangles a
        // quarter of a unit across, which is what defeated indexing vertices
        val mesh = coarsePlane(8, 2.0)
        val rnd = java.util.Random(12345)
        var worst = 0.0
        repeat(400) {
            val p = Vec3(
                rnd.nextDouble() * 3 - 1.5,
                rnd.nextDouble() * 1.2 - 0.6,
                rnd.nextDouble() * 3 - 1.5,
            )
            val mine = mesh.nearestPoint(p, Vec3()).distanceTo(p)
            worst = maxOf(worst, abs(mine - bruteForce(mesh, p)))
        }
        assertTrue(worst < 1e-9, "snap disagreed with brute force by $worst")
    }

    @Test
    fun `a point already on the surface is not moved`() {
        val mesh = coarsePlane(8, 2.0)
        // mid-triangle, far from any vertex — the case that used to slide 21mm
        val p = Vec3(0.06, 0.0, 0.09)
        val snapped = mesh.nearestPoint(p, Vec3())
        assertTrue(snapped.distanceTo(p) < 1e-9, "moved a correct point by ${snapped.distanceTo(p)}")
    }

    @Test
    fun `reprojection puts a smoothed stroke back on its surface`() {
        val mesh = coarsePlane(8, 2.0)
        val pts = (0 until 20).map { Vec3(-0.8 + it * 0.08, 0.0, 0.2) }
        val s = strokeAlong(pts, "wide", 45 * MM)
        // shove every point off the surface, the way Smooth and Liquify do
        for (pt in s.pts) pt.p.y += 0.05
        var before = 0.0
        for (pt in s.pts) before = maxOf(before, mesh.distanceTo(pt.p))
        assertTrue(before > 0.04, "test should start off-surface, got $before")

        Reproject.toSurface(s, mesh)
        var after = 0.0
        for (pt in s.pts) after = maxOf(after, mesh.distanceTo(pt.p))
        assertTrue(after < 1e-9, "paint should be back on the surface, still $after off")
    }
}
