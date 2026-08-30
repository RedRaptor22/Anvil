package art.plume.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guide surfaces: the Feather premise, which is that you draw a surface and
 * then draw on it.
 *
 * The load-bearing property is that the surface actually passes through the
 * stroke that made it. If it does not, every mark afterwards lands somewhere
 * the person did not point at, and no amount of correct shading hides it.
 */
class GuideTest {

    /** A circle drawn on the plane facing a camera looking down -Z. */
    private fun circleStroke(r: Double = 0.3, n: Int = 32): List<Vec3> =
        (0 until n).map {
            val a = it.toDouble() / (n - 1) * 2 * PI
            Vec3(cos(a) * r, sin(a) * r, 0.0)
        }

    private val viewDir = Vec3(0.0, 0.0, -1.0)
    private val camRight = Vec3(1.0, 0.0, 0.0)

    // ---- the swept guide ------------------------------------------------

    @Test
    fun `the anchor row reproduces the stroke that was drawn, exactly`() {
        /*
         * This is the whole contract of guide creation. The profile is stored
         * in the anchor frame and the frames are transported from index 0 with
         * that frame carried onto the path, so the anchor row must come back
         * out as the resampled stroke itself — not near it.
         */
        val stroke = circleStroke()
        val g = assertNotNull(Guides.createFromStroke(stroke, viewDir, camRight, 4.0))
        val sweep = assertNotNull(g.sweep)
        val rows = Guides.evalSweep(sweep)
        val profile = Polyline.resample(stroke, minOf(96, maxOf(8, stroke.size * 2)))

        val anchorRow = rows[sweep.anchorIndex]
        assertEquals(profile.size, anchorRow.size)
        for (i in profile.indices) {
            assertTrue(
                profile[i].distanceTo(anchorRow[i]) < 1e-12,
                "anchor row point $i is ${profile[i].distanceTo(anchorRow[i])} off the stroke",
            )
        }
    }

    @Test
    fun `the guide extrudes along the view, mostly away from the camera`() {
        val g = assertNotNull(Guides.createFromStroke(circleStroke(), viewDir, camRight, 4.0))
        val sweep = assertNotNull(g.sweep)

        // depth is scaled off the stroke but floored against the orbit radius
        val extent = 0.3
        val expected = clamp(
            maxOf(extent * 2 * Tune.GUIDE_DEPTH_FACTOR, 4.0 * Tune.GUIDE_DEPTH_OF_VIEW),
            Tune.GUIDE_DEPTH_MIN, Tune.GUIDE_DEPTH_MAX,
        )
        assertEquals(expected, sweep.depth, 1e-9)

        // FACT (A.3): a little of it comes towards the camera, so the orange
        // starting edge sits at one side of the surface rather than on its rim
        val front = sweep.depth * Tune.GUIDE_DEPTH_FRONT
        assertTrue(sweep.anchorIndex > 0, "the anchor is on the very edge, with nothing in front")
        assertTrue(sweep.anchorIndex < sweep.path.size - 1)

        val zs = sweep.path.map { it.z }
        // the view runs down -Z, so the far end of the sweep is the most negative
        assertEquals(front, zs.max(), 1e-9)
        assertEquals(-sweep.depth, zs.min(), 1e-9)
    }

    @Test
    fun `a small stroke in a big scene still gets a guide worth orbiting`() {
        // 2mm across, with the camera 40m out: scaled off the stroke alone this
        // would be a 6mm sliver you could not see, let alone draw on
        val tiny = circleStroke(r = 0.001)
        val g = assertNotNull(Guides.createFromStroke(tiny, viewDir, camRight, 40.0))
        val sweep = assertNotNull(g.sweep)
        assertTrue(sweep.depth > 1.0, "depth ${sweep.depth} is not floored against the view")
        assertTrue(sweep.depth <= Tune.GUIDE_DEPTH_MAX)
    }

    @Test
    fun `the surface passes through the stroke it was made from`() {
        val stroke = circleStroke()
        val g = assertNotNull(Guides.createFromStroke(stroke, viewDir, camRight, 4.0))
        val surface = assertNotNull(g.surface)

        /*
         * Exactly, for the RESAMPLED profile — that is what the surface is
         * built from and there is no excuse for drift there.
         */
        val profile = Polyline.resample(stroke, minOf(96, maxOf(8, stroke.size * 2)))
        for (p in profile) {
            val d = surface.mesh.distanceTo(p)
            assertTrue(d < 1e-5, "a profile point sits ${d * 1000} mm off its own guide")
        }

        /*
         * ...and close, but NOT exactly, for the raw samples. The gap is real
         * and is not a bug: resampling walks along the stroke's own chords, so
         * between two resampled points that straddle an original vertex the
         * surface cuts that corner off. Measured here at 1.5mm on a 300mm
         * circle sampled 32 times — the web build resamples identically and
         * has the same gap.
         *
         * The bound is stated against the stroke's OWN sample spacing rather
         * than as a millimetre figure, so it stays meaningful at any scale: a
         * corner cut can only ever be a small fraction of the distance between
         * the samples that formed it. A magic number here would silently stop
         * testing anything the first time the test curve changed.
         */
        var maxSegment = 0.0
        for (i in 1 until stroke.size) {
            maxSegment = maxOf(maxSegment, stroke[i].distanceTo(stroke[i - 1]))
        }
        var worst = 0.0
        for (p in stroke) worst = maxOf(worst, surface.mesh.distanceTo(p))
        assertTrue(
            worst < maxSegment * 0.05,
            "raw samples sit ${worst / MM} mm off the guide, more than 5% of the " +
                "${maxSegment / MM} mm between them",
        )
        assertTrue(worst > 0.0, "a resampled profile cannot pass through every raw sample")
    }

    @Test
    fun `uv carries arc length in world units, not a zero-to-one coordinate`() {
        val g = assertNotNull(Guides.createFromStroke(circleStroke(), viewDir, camRight, 4.0))
        val s = assertNotNull(g.surface)
        assertTrue(s.hasGrid)

        // u along the middle row is the length of the profile, which for a
        // circle of radius 0.3 is 2*pi*0.3 — and emphatically not 1.0
        assertEquals(2 * PI * 0.3, s.lu, 0.01)
        assertTrue(s.lu > 1.5, "Lu is ${s.lu}, which looks like a normalised coordinate")

        // v spans the whole sweep: front plus depth
        val sweep = assertNotNull(g.sweep)
        // read back out of a float32 buffer, so compare at float precision
        assertEquals(sweep.depth * (1 + Tune.GUIDE_DEPTH_FRONT), s.lv, 1e-6)

        // and it is monotonic across the grid, or a binary search on it is void
        for (i in 1 until s.nu) {
            assertTrue(s.uv[i * 2] >= s.uv[(i - 1) * 2], "u went backwards at column $i")
        }
        for (j in 1 until s.nv) {
            assertTrue(
                s.uv[(j * s.nu) * 2 + 1] >= s.uv[((j - 1) * s.nu) * 2 + 1],
                "v went backwards at row $j",
            )
        }
    }

    @Test
    fun `every surface normal is a unit vector`() {
        val g = assertNotNull(Guides.createFromStroke(circleStroke(), viewDir, camRight, 4.0))
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

    @Test
    fun `a profile carried round a bend does not spin`() {
        /*
         * The reason the sweep uses rotation-minimising frames. Bending the
         * path through an inflection with a Frenet frame flips the profile
         * over at the moment the curvature changes sign, and the surface gets
         * a crease down the middle of it.
         */
        val stroke = circleStroke()
        val g = assertNotNull(Guides.createFromStroke(stroke, viewDir, camRight, 4.0))
        val sweep = assertNotNull(g.sweep)

        // an S-bend: curvature changes sign halfway along
        val n = sweep.path.size
        for (j in 0 until n) {
            val t = j.toDouble() / (n - 1)
            sweep.path[j].set(sin(t * 2 * PI) * 0.5, 0.0, -t * 2.0)
        }
        val rows = Guides.evalSweep(sweep)

        // consecutive rows must stay pointing the same way round
        var worst = 0.0
        for (j in 1 until rows.size) {
            val a = rows[j - 1][0] - rows[j - 1][rows[0].size / 2]
            val b = rows[j][0] - rows[j][rows[0].size / 2]
            if (a.lengthSq() < 1e-18 || b.lengthSq() < 1e-18) continue
            val turn = Math.toDegrees(
                kotlin.math.acos(clamp(a.normalize() dot b.normalize(), -1.0, 1.0)),
            )
            worst = maxOf(worst, turn)
        }
        assertTrue(worst < 20.0, "the profile spun $worst degrees between adjacent rows")
    }

    // ---- the flat guide -------------------------------------------------

    @Test
    fun `a flat guide is the shape you drew, in the plane you drew it on`() {
        val stroke = circleStroke(r = 0.4)
        val g = assertNotNull(Guides.createFlatFromStroke(stroke, viewDir, camRight))
        val pl = assertNotNull(g.plane)

        // it faces the camera
        assertEquals(1.0, abs(pl.normal dot viewDir.copy().normalize()), 1e-12)
        // its extent is the stroke's own bounding box, not some padded grid.
        // Measured off the samples, not off the ideal circle: 32 points around
        // a circle do not land on its extremes, so the box is a hair inside.
        val bx = stroke.maxOf { it.x } - stroke.minOf { it.x }
        val by = stroke.maxOf { it.y } - stroke.minOf { it.y }
        assertEquals(bx, pl.lu, 1e-12)
        assertEquals(by, pl.lv, 1e-12)
        assertTrue(bx < 0.8 && bx > 0.79, "the sampled box should be just inside the true circle")

        val s = assertNotNull(g.surface)
        assertNotNull(s.outline)
        assertTrue(!s.hasGrid, "a flat guide has an outline, not a grid")

        // every vertex lies in the plane, and every stroke point is on the sheet
        for (i in 0 until s.vertexCount) {
            val p = Vec3(
                s.positions[i * 3].toDouble(),
                s.positions[i * 3 + 1].toDouble(),
                s.positions[i * 3 + 2].toDouble(),
            )
            assertEquals(0.0, (p - pl.origin) dot pl.normal, 1e-6, "vertex $i is off the plane")
        }
        for (p in stroke) {
            assertTrue(s.mesh.distanceTo(p) < 1e-5, "a stroke point is off its own flat guide")
        }
    }

    @Test
    fun `the triangulation covers the shape, and does not spill outside it`() {
        val stroke = circleStroke(r = 0.4, n = 40)
        val g = assertNotNull(Guides.createFlatFromStroke(stroke, viewDir, camRight))
        val s = assertNotNull(g.surface)
        val outline = assertNotNull(s.outline)

        // the triangles' total area must equal the polygon's own area: too
        // little means a hole, too much means overlapping ears
        var tri = 0.0
        var i = 0
        while (i < s.indices.size) {
            val a = outline[s.indices[i]]
            val b = outline[s.indices[i + 1]]
            val c = outline[s.indices[i + 2]]
            tri += abs((b.u - a.u) * (c.v - a.v) - (c.u - a.u) * (b.v - a.v)) / 2
            i += 3
        }
        val poly = abs(Triangulate.signedArea2(outline)) / 2
        assertEquals(poly, tri, poly * 1e-6, "triangulated area does not match the outline's")
        assertEquals((outline.size - 2) * 3, s.indices.size, "not a minimal fan of ears")
    }

    @Test
    fun `a loop drawn either way round still faces the same way`() {
        // people draw clockwise as often as not, and a surface whose triangles
        // face backwards is invisible from the side it was drawn on
        val cw = circleStroke(r = 0.3, n = 24)
        val ccw = cw.reversed()
        val a = assertNotNull(Guides.createFlatFromStroke(cw, viewDir, camRight)).surface!!
        val b = assertNotNull(Guides.createFlatFromStroke(ccw, viewDir, camRight)).surface!!

        // the two outlines wind opposite ways, which is the thing being handled
        assertTrue(
            Triangulate.signedArea2(a.outline!!) * Triangulate.signedArea2(b.outline!!) < 0,
            "the test did not actually produce two opposite windings",
        )

        // yet both triangulate to the same count, and every triangle of each
        // winds the same way as its neighbours
        assertEquals(a.indices.size, b.indices.size)
        for (s2 in listOf(a, b)) {
            val poly = s2.outline!!
            var i = 0
            while (i < s2.indices.size) {
                val p0 = poly[s2.indices[i]]; val p1 = poly[s2.indices[i + 1]]
                val p2 = poly[s2.indices[i + 2]]
                val cross = (p1.u - p0.u) * (p2.v - p0.v) - (p2.u - p0.u) * (p1.v - p0.v)
                assertTrue(cross > 0, "a triangle faces the other way at index $i")
                i += 3
            }
        }
    }

    @Test
    fun `a stroke too small or too straight makes no flat guide`() {
        assertNull(Guides.createFlatFromStroke(listOf(Vec3(), Vec3(1.0, 0.0, 0.0)), viewDir, camRight))
        // a straight line encloses nothing, so there is no sheet to make
        val line = (0 until 10).map { Vec3(it * 0.1, 0.0, 0.0) }
        assertNull(Guides.createFlatFromStroke(line, viewDir, camRight))
    }

    @Test
    fun `inside-outline is even-odd, which is what a self-crossing loop deserves`() {
        /*
         * A bowtie, which crosses itself at (1,1). Both lobes count as inside;
         * the pinch point itself is the one place the answer is meaningless, so
         * the probes deliberately sit above and below it rather than on it —
         * v = 1.0 is exactly the crossing, and testing there measures nothing
         * but the tie-break.
         */
        val fig = listOf(
            UV(0.0, 0.0), UV(2.0, 2.0), UV(0.0, 2.0), UV(2.0, 0.0),
        )
        assertTrue(SurfaceGrid.insideOutline(fig, 1.0, 0.5), "the lower lobe is inside")
        assertTrue(SurfaceGrid.insideOutline(fig, 1.0, 1.5), "the upper lobe is inside")
        assertTrue(!SurfaceGrid.insideOutline(fig, 0.2, 0.5), "the left wing is outside")
        assertTrue(!SurfaceGrid.insideOutline(fig, 1.8, 0.5), "the right wing is outside")
        assertTrue(!SurfaceGrid.insideOutline(fig, -1.0, 1.0), "outside is not inside")
    }

    // ---- primitives ------------------------------------------------------

    @Test
    fun `every primitive builds finite geometry with in-range indices`() {
        for (kind in Primitives.KINDS) {
            val g = Primitives.create(kind)
            val s = assertNotNull(g.surface, "no surface for $kind")
            assertTrue(s.vertexCount > 0, "$kind has no vertices")
            assertTrue(s.indices.isNotEmpty(), "$kind has no triangles")
            for (v in s.positions) assertTrue(v.isFinite(), "$kind has a non-finite position")
            for (v in s.normals) assertTrue(v.isFinite(), "$kind has a non-finite normal")
            for (i in s.indices) {
                assertTrue(i in 0 until s.vertexCount, "$kind index $i is out of range")
            }
            assertEquals(0, s.indices.size % 3, "$kind has a partial triangle")
        }
    }

    @Test
    fun `primitives keep the dimensions the web build gives them`() {
        // a sketch drawn on a primitive has to measure the same in both builds
        fun bounds(kind: String): Pair<Vec3, Vec3> {
            val s = Primitives.create(kind).surface!!
            val lo = Vec3(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
            val hi = Vec3(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
            for (i in 0 until s.vertexCount) {
                lo.x = minOf(lo.x, s.positions[i * 3].toDouble())
                lo.y = minOf(lo.y, s.positions[i * 3 + 1].toDouble())
                lo.z = minOf(lo.z, s.positions[i * 3 + 2].toDouble())
                hi.x = maxOf(hi.x, s.positions[i * 3].toDouble())
                hi.y = maxOf(hi.y, s.positions[i * 3 + 1].toDouble())
                hi.z = maxOf(hi.z, s.positions[i * 3 + 2].toDouble())
            }
            return lo to hi
        }

        // all read out of float32 buffers, so compared at float precision
        val (cLo, cHi) = bounds("cube")
        assertEquals(-1.0, cLo.x, 1e-6); assertEquals(1.0, cHi.y, 1e-6)

        val (sLo, sHi) = bounds("sphere")
        assertEquals(1.4, sHi.y, 1e-6); assertEquals(-1.4, sLo.y, 1e-6)

        val (pLo, pHi) = bounds("pyramid")
        assertEquals(2.4, pHi.y - pLo.y, 1e-6)

        // the torus lies flat like the grid: wide in X and Z, thin in Y
        val (tLo, tHi) = bounds("torus")
        assertEquals(2 * (1.4 + 0.42), tHi.x - tLo.x, 1e-6)
        assertEquals(2 * 0.42, tHi.y - tLo.y, 1e-6)
    }

    @Test
    fun `a sphere's points all sit at its radius and its normals point outwards`() {
        val s = Primitives.create("sphere", segments = 32).surface!!
        for (i in 0 until s.vertexCount) {
            val p = Vec3(
                s.positions[i * 3].toDouble(),
                s.positions[i * 3 + 1].toDouble(),
                s.positions[i * 3 + 2].toDouble(),
            )
            assertEquals(1.4, p.length(), 1e-6, "vertex $i is off the sphere")
            val n = Vec3(
                s.normals[i * 3].toDouble(),
                s.normals[i * 3 + 1].toDouble(),
                s.normals[i * 3 + 2].toDouble(),
            )
            assertTrue((n dot p) > 0, "normal $i points into the sphere")
        }
    }

    @Test
    fun `taper thins the ring of a torus and the top of a tube`() {
        fun spanY(kind: String, taper: Double): Double {
            val s = Primitives.create(kind, 24, taper).surface!!
            var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
            for (i in 0 until s.vertexCount) {
                lo = minOf(lo, s.positions[i * 3 + 1].toDouble())
                hi = maxOf(hi, s.positions[i * 3 + 1].toDouble())
            }
            return hi - lo
        }
        // a torus has no ends, so taper drives thickness instead of a cone
        assertTrue(spanY("torus", 0.3) < spanY("torus", 1.0))

        // a tube at taper 0 is a cone: its top radius collapses to a point
        val cone = Primitives.create("tube", 24, 0.0).surface!!
        var topR = 0.0
        for (i in 0 until cone.vertexCount) {
            if (cone.positions[i * 3 + 1] > 1.29f) {
                topR = maxOf(
                    topR,
                    kotlin.math.hypot(
                        cone.positions[i * 3].toDouble(), cone.positions[i * 3 + 2].toDouble(),
                    ),
                )
            }
        }
        assertEquals(0.0, topR, 1e-9, "a fully tapered tube should come to a point")
    }

    @Test
    fun `a guide cannot be made completely opaque`() {
        // FACT: this is documented Feather behaviour, and the clamp is on the
        // property so there is no route into it that skips the limit
        val g = Primitives.create("cube")
        g.opacity = 1.0
        assertEquals(Tune.GUIDE_OPACITY_MAX, g.opacity, 0.0)
        g.opacity = -5.0
        assertEquals(0.0, g.opacity, 0.0)
    }
}
