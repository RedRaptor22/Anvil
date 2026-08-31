package art.plume.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bend and Loft.
 *
 * Both carry corrections the web build paid for the hard way, and both are the
 * sort of thing that looks plausible while being backwards — a bend that
 * sweeps away from the pen still produces a surface, just not the one you
 * asked for. The tests measure direction, not just that something happened.
 */
class GuideEditingTest {

    private val viewDir = Vec3(0.0, 0.0, -1.0)
    private val camRight = Vec3(1.0, 0.0, 0.0)

    private fun profileStroke(n: Int = 20): List<Vec3> =
        (0 until n).map {
            val a = -0.5 + it.toDouble() / (n - 1)
            Vec3(a * 0.5, sin(a * 2.0) * 0.1, 0.0)
        }

    private fun sweptGuide(): Guide =
        assertNotNull(Guides.createFromStroke(profileStroke(), viewDir, camRight, 4.0))

    private fun centroidOf(s: GuideSurface): Vec3 {
        val c = Vec3()
        for (i in 0 until s.vertexCount) {
            c.x += s.positions[i * 3]; c.y += s.positions[i * 3 + 1]; c.z += s.positions[i * 3 + 2]
        }
        val k = 1.0 / s.vertexCount
        c.x *= k; c.y *= k; c.z *= k
        return c
    }

    // ---- bending a swept guide -------------------------------------------

    @Test
    fun `a bent sweep starts at the orange line and follows the stroke`() {
        val g = sweptGuide()
        val anchor = assertNotNull(g.sweep).anchor.copy()

        // a stroke heading off to the right and up
        val path = (0 until 20).map {
            val t = it.toDouble() / 19
            Vec3(1.0 + t * 2.0, t * 1.0, 0.3)
        }
        assertTrue(GuideEditing.bend(g, path))

        val sw = assertNotNull(g.sweep)
        assertEquals(0, sw.anchorIndex, "bending starts from the orange line")
        assertTrue(
            sw.path[0].distanceTo(anchor) < 1e-9,
            "the path was not translated onto the anchor",
        )

        /*
         * And it goes the way the stroke went. An earlier web version rotated
         * the path onto the guide's original extrusion axis, which for a stroke
         * running roughly opposite that axis was a ~180 degree turn — the
         * surface swept away from the line just drawn.
         */
        val drawn = (path[path.size - 1] - path[0]).normalize()
        val swept = (sw.path[sw.path.size - 1] - sw.path[0]).normalize()
        val degrees = Math.toDegrees(kotlin.math.acos(clamp(drawn dot swept, -1.0, 1.0)))
        assertEquals(0.0, degrees, 1e-6, "the sweep ran $degrees degrees off the stroke")
    }

    @Test
    fun `a stroke drawn backwards bends the guide backwards, not forwards`() {
        // the case the rotation-onto-the-extrusion-axis version got wrong
        val forward = sweptGuide()
        val backward = sweptGuide()
        val anchorF = assertNotNull(forward.sweep).anchor.copy()

        val out = (0 until 16).map { Vec3(anchorF.x + it * 0.1, anchorF.y, anchorF.z) }
        val back = (0 until 16).map { Vec3(anchorF.x - it * 0.1, anchorF.y, anchorF.z) }

        assertTrue(GuideEditing.bend(forward, out))
        assertTrue(GuideEditing.bend(backward, back))

        val cf = centroidOf(assertNotNull(forward.surface))
        val cb = centroidOf(assertNotNull(backward.surface))
        assertTrue(cf.x > anchorF.x, "the forward bend should lie to the +X side")
        assertTrue(cb.x < anchorF.x, "the backward bend should lie to the -X side")
    }

    @Test
    fun `bending a sweep round a circle closes it into a ring`() {
        // the documented worked example: a cylinder becomes a doughnut
        val g = sweptGuide()
        val anchor = assertNotNull(g.sweep).anchor.copy()
        val r = 0.8
        val circle = (0 until 48).map {
            val a = it.toDouble() / 47 * 2 * PI
            Vec3(anchor.x + cos(a) * r, anchor.y, anchor.z + sin(a) * r)
        }
        assertTrue(GuideEditing.bend(g, circle))

        val sw = assertNotNull(g.sweep)
        // the path came back round to where it started
        assertTrue(
            sw.path[0].distanceTo(sw.path[sw.path.size - 1]) < r * 0.05,
            "the bend path did not close",
        )
        /*
         * ...and the surface is a ring: everything sits about r from the
         * centre. Measured from the centroid of the BENT path, not from where
         * the circle was drawn — bending translates the path onto the anchor,
         * so the ring's centre moves with it. Measuring from the drawn centre
         * put the probe a full radius out and reported a ring with no hole.
         */
        val s = assertNotNull(g.surface)
        val centre = Polyline.centroid(sw.path)
        var minR = Double.MAX_VALUE
        for (i in 0 until s.vertexCount) {
            val d = kotlin.math.hypot(
                s.positions[i * 3] - centre.x, s.positions[i * 3 + 2] - centre.z,
            )
            minR = minOf(minR, d)
        }
        assertTrue(minR > r * 0.3, "the ring has no hole in the middle (min radius $minR)")
    }

    @Test
    fun `bending twice re-bends the original, rather than compounding`() {
        val once = sweptGuide()
        val twice = sweptGuide()
        val anchor = assertNotNull(once.sweep).anchor.copy()

        val a = (0 until 16).map { Vec3(anchor.x + it * 0.08, anchor.y + it * 0.03, anchor.z) }
        val b = (0 until 16).map { Vec3(anchor.x + it * 0.05, anchor.y - it * 0.06, anchor.z) }

        GuideEditing.bend(once, b)
        GuideEditing.bend(twice, a)
        GuideEditing.bend(twice, b)          // the second bend replaces the first

        val s1 = assertNotNull(once.surface)
        val s2 = assertNotNull(twice.surface)
        assertEquals(s1.vertexCount, s2.vertexCount)
        for (i in 0 until s1.positions.size) {
            assertEquals(s1.positions[i], s2.positions[i], 1e-6f, "vertex data $i differs")
        }
    }

    // ---- bending a mesh that has no sweep ---------------------------------

    /** The apex of a pyramid, which is the end that has to follow the pen. */
    private fun apexOf(s: GuideSurface): Vec3 {
        // the apex is the single vertex furthest from the centroid along the
        // axis it was built on; found here as the most isolated vertex
        val c = centroidOf(s)
        var best = Vec3()
        var bestD = -1.0
        for (i in 0 until s.vertexCount) {
            val p = Vec3(
                s.positions[i * 3].toDouble(),
                s.positions[i * 3 + 1].toDouble(),
                s.positions[i * 3 + 2].toDouble(),
            )
            val d = p.distanceToSq(c)
            if (d > bestD) { bestD = d; best = p }
        }
        return best
    }

    @Test
    fun `a deformed guide follows the stroke, whichever way it was drawn`() {
        /*
         * The measurement that caught this in the web build. Picking the
         * longest axis and always running it low-to-high meant a cube or a
         * sphere deformed along local +X whatever was drawn: measured against
         * the drawn direction, cube 91 degrees off, tube 86, pyramid 88,
         * sphere a full 180.
         */
        for (kind in listOf("cube", "pyramid", "sphere", "tube")) {
            for (dir in listOf(Vec3(1.0, 0.0, 0.0), Vec3(-1.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0))) {
                val g = Primitives.create(kind)
                val path = (0 until 24).map {
                    val t = it.toDouble() / 23 * 4.0
                    Vec3(dir.x * t, dir.y * t, dir.z * t)
                }
                assertTrue(GuideEditing.bendMesh(g, path), "$kind failed to bend")

                val s = assertNotNull(g.surface)
                // the mesh should now run along the path: its far end near the
                // path's far end, its near end near the path's start
                var nearStart = Double.MAX_VALUE
                var nearEnd = Double.MAX_VALUE
                for (i in 0 until s.vertexCount) {
                    val p = Vec3(
                        s.positions[i * 3].toDouble(),
                        s.positions[i * 3 + 1].toDouble(),
                        s.positions[i * 3 + 2].toDouble(),
                    )
                    nearStart = minOf(nearStart, p.distanceTo(path[0]))
                    nearEnd = minOf(nearEnd, p.distanceTo(path[path.size - 1]))
                }
                assertTrue(nearStart < 2.0, "$kind along $dir does not reach the path start")
                assertTrue(nearEnd < 2.0, "$kind along $dir does not reach the path end")

                // and the deformed centroid sits along the stroke, not against it
                val c = centroidOf(s)
                val along = c dot dir
                assertTrue(
                    along > 0.5,
                    "$kind bent along $dir put its centroid $along the wrong way",
                )
            }
        }
    }

    @Test
    fun `a rod bends along its length, even when the stroke crosses it`() {
        // the axis choice is only free where the shape has no long axis; a tube
        // is 2.6 long and 2.4 across, so both qualify and the stroke decides
        val g = Primitives.create("tube")
        val path = (0 until 16).map { Vec3(0.0, it * 0.3, 0.0) }
        assertTrue(GuideEditing.bendMesh(g, path))
        val s = assertNotNull(g.surface)
        var hiY = -9.0
        for (i in 0 until s.vertexCount) hiY = maxOf(hiY, s.positions[i * 3 + 1].toDouble())
        assertTrue(hiY > 3.0, "the tube did not stretch along the stroke")
    }

    @Test
    fun `unbending puts the mesh back exactly`() {
        val g = Primitives.create("cube")
        val before = assertNotNull(g.surface).positions.copyOf()
        val path = (0 until 12).map { Vec3(it * 0.4, sin(it * 0.4) * 0.6, 0.0) }

        assertTrue(GuideEditing.bendMesh(g, path))
        val bent = assertNotNull(g.surface).positions
        var moved = 0.0
        for (i in before.indices) moved = maxOf(moved, kotlin.math.abs(bent[i] - before[i]).toDouble())
        assertTrue(moved > 0.5, "the bend did not actually move anything")

        assertTrue(GuideEditing.unbendMesh(g))
        val after = assertNotNull(g.surface).positions
        for (i in before.indices) assertEquals(before[i], after[i], 0.0f, "vertex data $i")
        assertNull(g.bendPath)
    }

    @Test
    fun `a bent mesh keeps unit normals, so it is not lit as a lump`() {
        val g = Primitives.create("sphere", segments = 20)
        val path = (0 until 20).map { Vec3(it * 0.3, sin(it * 0.3) * 0.8, 0.0) }
        assertTrue(GuideEditing.bendMesh(g, path))
        val s = assertNotNull(g.surface)
        for (i in 0 until s.vertexCount) {
            val n = Vec3(
                s.normals[i * 3].toDouble(),
                s.normals[i * 3 + 1].toDouble(),
                s.normals[i * 3 + 2].toDouble(),
            )
            assertEquals(1.0, n.length(), 1e-5, "normal $i has length ${n.length()}")
        }
    }

    // ---- loft  (A.7) ------------------------------------------------------

    private fun ring(y: Double, r: Double, n: Int = 24): List<Vec3> =
        (0 until n).map {
            val a = it.toDouble() / (n - 1) * PI          // an open arc, not a loop
            Vec3(cos(a) * r, y, sin(a) * r)
        }

    @Test
    fun `a loft passes through the curves it was built from`() {
        val a = ring(0.0, 0.5)
        val b = ring(1.0, 0.8)
        val g = assertNotNull(GuideEditing.loftFromCurves(listOf(a, b)))
        val s = assertNotNull(g.surface)

        // the first and last rows of the grid ARE the outer sections
        for (curve in listOf(a, b)) {
            for (p in curve) {
                assertTrue(
                    s.mesh.distanceTo(p) < 0.02,
                    "the loft misses its own input curve by ${s.mesh.distanceTo(p)}",
                )
            }
        }
        assertEquals(Tune.GUIDE_PROFILE_SEG, s.nu)
        assertEquals(25, s.nv, "two sections should give 24 interpolated rows plus one")
    }

    @Test
    fun `a curve drawn the other way round does not twist the loft`() {
        /*
         * Without the flip, the surface pinches to a waist in the middle and
         * turns itself inside out — every column runs to the opposite end of
         * the next section.
         */
        val a = ring(0.0, 0.5)
        val b = ring(1.0, 0.5)
        val straight = assertNotNull(GuideEditing.loftFromCurves(listOf(a, b)))
        val flipped = assertNotNull(GuideEditing.loftFromCurves(listOf(a, b.reversed())))

        val s1 = assertNotNull(straight.surface)
        val s2 = assertNotNull(flipped.surface)
        assertEquals(s1.vertexCount, s2.vertexCount)
        for (i in s1.positions.indices) {
            assertEquals(s1.positions[i], s2.positions[i], 1e-5f, "vertex data $i differs")
        }

        // and the guard: an untwisted loft has no waist. The middle row should
        // be as wide as the sections it sits between.
        val mid = s1.nv / 2
        val first = Vec3(s1.positions[0].toDouble(), s1.positions[1].toDouble(), s1.positions[2].toDouble())
        val last = Vec3(
            s1.positions[(s1.nu - 1) * 3].toDouble(),
            s1.positions[(s1.nu - 1) * 3 + 1].toDouble(),
            s1.positions[(s1.nu - 1) * 3 + 2].toDouble(),
        )
        val m0 = mid * s1.nu
        val m1 = m0 + s1.nu - 1
        val midFirst = Vec3(s1.positions[m0 * 3].toDouble(), s1.positions[m0 * 3 + 1].toDouble(), s1.positions[m0 * 3 + 2].toDouble())
        val midLast = Vec3(s1.positions[m1 * 3].toDouble(), s1.positions[m1 * 3 + 1].toDouble(), s1.positions[m1 * 3 + 2].toDouble())
        assertEquals(
            first.distanceTo(last), midFirst.distanceTo(midLast), 0.05,
            "the middle of the loft is pinched, which is what a twist looks like",
        )
    }

    @Test
    fun `tension zero interpolates straight between sections`() {
        // FACT: the slider runs sharp to smooth; at zero it is piecewise linear,
        // so the row halfway between two sections is their average
        val a = ring(0.0, 0.5)
        val b = ring(2.0, 0.5)
        val g = assertNotNull(GuideEditing.loftFromCurves(listOf(a, b), tension = 0.0))
        val s = assertNotNull(g.surface)
        val mid = s.nv / 2
        val o = (mid * s.nu) * 3
        assertEquals(1.0, s.positions[o + 1].toDouble(), 0.05, "the middle row should be halfway up")
    }

    @Test
    fun `a loft needs at least two curves`() {
        assertNull(GuideEditing.loftFromCurves(listOf(ring(0.0, 0.5))))
        assertNull(GuideEditing.loftFromCurves(emptyList()))
    }

    @Test
    fun `a loft keeps its curves, so it can be rebuilt without them`() {
        val g = assertNotNull(GuideEditing.loftFromCurves(listOf(ring(0.0, 0.5), ring(1.0, 0.8)), 0.6))
        val kept = assertNotNull(g.loftCurves)
        assertEquals(2, kept.size)
        assertEquals(0.6, g.loftTension, 0.0)

        val s1 = assertNotNull(g.surface).positions.copyOf()
        assertTrue(GuideEditing.rebuildLoft(g))
        val s2 = assertNotNull(g.surface).positions
        for (i in s1.indices) assertEquals(s1[i], s2[i], 0.0f, "rebuild differs at $i")

        /*
         * ...and this is why rebuildLoft exists rather than just calling
         * loftFromCurves again. The stored curves are already resampled, so
         * putting them back through the resampler shaves every corner a second
         * time — measured at 0.05mm on a half-metre section. Invisible on its
         * own, but it would happen on every save and reload, and a document
         * should come back as what was saved rather than a smoothed copy.
         */
        val viaResample = assertNotNull(GuideEditing.loftFromCurves(kept, g.loftTension))
        var drift = 0.0
        val s3 = assertNotNull(viaResample.surface).positions
        for (i in s1.indices) drift = maxOf(drift, kotlin.math.abs(s1[i] - s3[i]).toDouble())
        assertTrue(drift > 0.0, "if these agreed, rebuildLoft would be pointless")
        assertTrue(drift < 0.2 * MM, "the resample route drifted by ${drift / MM} mm")
    }
}
