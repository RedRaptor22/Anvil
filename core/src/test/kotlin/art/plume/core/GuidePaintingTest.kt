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
 * Painting on a guide: the ray query, the surface's own coordinates, and what
 * happens at the edge.
 *
 * The ray query gets checked against brute force for the same reason the
 * nearest-point query does — a spatial index that quietly skips triangles
 * looks exactly like a surface with holes in it, and it will only show up as
 * strokes that occasionally fall through.
 */
class GuidePaintingTest {

    private val viewDir = Vec3(0.0, 0.0, -1.0)
    private val camRight = Vec3(1.0, 0.0, 0.0)

    private fun arcStroke(n: Int = 24): List<Vec3> =
        (0 until n).map {
            val a = -0.9 + it.toDouble() / (n - 1) * 1.8
            Vec3(a * 0.35, sin(a * 1.4) * 0.18, 0.0)
        }

    private fun sweptGuide(): Guide =
        assertNotNull(Guides.createFromStroke(arcStroke(), viewDir, camRight, 4.0))

    private fun flatGuide(): Guide {
        val loop = (0 until 32).map {
            val a = it.toDouble() / 31 * 2 * PI
            Vec3(cos(a) * 0.4, sin(a) * 0.4, 0.0)
        }
        return assertNotNull(Guides.createFlatFromStroke(loop, viewDir, camRight))
    }

    /** The same query, done the slow obvious way. */
    private fun bruteRaycast(mesh: SurfaceMesh, ray: Ray): Double? {
        var best: Double? = null
        val a = Vec3(); val b = Vec3(); val c = Vec3()
        for (t in 0 until mesh.triangleCount) {
            mesh.triangle(t, a, b, c)
            val tt = SurfaceMesh.rayTriangle(ray, a, b, c) ?: continue
            if (tt >= 0 && (best == null || tt < best!!)) best = tt
        }
        return best
    }

    @Test
    fun `the ray query agrees with brute force over every triangle`() {
        val s = assertNotNull(sweptGuide().surface)
        val rnd = java.util.Random(20260830)
        var checked = 0
        var hits = 0
        for (i in 0 until 400) {
            /*
             * Fired ACROSS the sheet, from where you would be after orbiting.
             * Rays down the sweep axis run parallel to the surface and mostly
             * miss, which makes for a comparison that agrees on nothing.
             */
            val origin = Vec3(
                (rnd.nextDouble() - 0.5) * 1.2,
                2.0 + rnd.nextDouble(),
                -rnd.nextDouble() * 1.4,
            )
            val target = Vec3(
                (rnd.nextDouble() - 0.5) * 1.2,
                -2.0,
                -rnd.nextDouble() * 1.4,
            )
            val ray = Ray(origin, (target - origin).normalize())
            val fast = s.mesh.raycast(ray)
            val slow = bruteRaycast(s.mesh, ray)
            checked++
            if (slow == null) {
                assertNull(fast, "the grid found a hit brute force did not, at probe $i")
            } else {
                assertNotNull(fast, "the grid MISSED a hit brute force found, at probe $i")
                assertEquals(slow, fast.t, 1e-9, "probe $i disagreed on distance")
                hits++
            }
        }
        assertEquals(400, checked)
        // if nothing ever hit, the comparison above proved nothing at all
        assertTrue(hits > 100, "only $hits of 400 probes hit the guide; the test is too weak")
    }

    @Test
    fun `a fresh guide is edge-on to the view that made it, so you must orbit`() {
        /*
         * Not a quirk — the whole shape of the interaction. A swept guide is
         * the profile you drew EXTRUDED ALONG THE VIEW, so from where you drew
         * it the surface is a curtain seen edge-on and a ray down the view axis
         * runs parallel to it. That is why Feather has you orbit before
         * painting, and it is worth pinning: every probe in this file that
         * fired down the sweep axis found nothing, which looked like a broken
         * ray query and was not.
         */
        val s = assertNotNull(sweptGuide().surface)
        assertNull(
            s.mesh.raycast(Ray(Vec3(0.0, 0.0, 3.0), Vec3(0.0, 0.0, -1.0))),
            "a ray down the sweep axis should run parallel to the sheet",
        )
        assertNotNull(
            s.mesh.raycast(Ray(Vec3(0.0, 3.0, -0.5), Vec3(0.0, -1.0, 0.0))),
            "...and one fired across it, after orbiting, should hit",
        )
    }

    @Test
    fun `a ray that misses the guide entirely reports no hit`() {
        val s = assertNotNull(sweptGuide().surface)
        val away = Ray(Vec3(0.0, 5.0, 5.0), Vec3(0.0, 1.0, 0.0))
        assertNull(s.mesh.raycast(away))
        assertNull(bruteRaycast(s.mesh, away))
    }

    // ---- the surface's own coordinates -----------------------------------

    @Test
    fun `a projected sample lands on the guide, and knows where it is on it`() {
        val g = sweptGuide()
        // from the side, which is where you are once you have orbited
        val ray = Ray(Vec3(0.0, 3.0, -0.5), Vec3(0.0, -1.0, 0.0))
        val hit = assertNotNull(GuidePainting.project(g, ray))
        assertTrue(hit.onSurface)
        assertTrue(
            g.surface!!.mesh.distanceTo(hit.point) < 1e-9,
            "a hit is not on the surface it hit",
        )

        val f = assertNotNull(hit.frame, "a swept guide's hit should carry a frame")
        // the coordinates are arc lengths in world units, inside the extent
        assertTrue(f.su in 0.0..f.lu, "su ${f.su} is outside 0..${f.lu}")
        assertTrue(f.sv in 0.0..f.lv, "sv ${f.sv} is outside 0..${f.lv}")
        // and the two axes are real directions, not zero vectors
        assertEquals(1.0, f.uDir.length(), 1e-9)
        assertEquals(1.0, f.vDir.length(), 1e-9)
    }

    @Test
    fun `sampling by arc length and projecting a ray agree with each other`() {
        /*
         * These are two different routes onto the same surface — Fill uses the
         * first, the pen uses the second — and if they disagree then a filled
         * row lands somewhere a hand-drawn one would not.
         */
        val g = sweptGuide()
        val s = assertNotNull(g.surface)
        val span = assertNotNull(GuidePainting.surfaceSpan(g))

        for (fu in listOf(0.15, 0.5, 0.8)) {
            for (fv in listOf(0.2, 0.55, 0.9)) {
                val sample = assertNotNull(
                    GuidePainting.sampleSurface(g, span.lu * fu, span.lv * fv),
                )
                assertTrue(
                    s.mesh.distanceTo(sample.point) < 1e-6,
                    "a sampled point is off its own surface at ($fu, $fv)",
                )
                val f = assertNotNull(sample.frame)
                // the frame read back at that point must agree with where we asked
                assertEquals(span.lu * fu, f.su, span.lu * 0.02, "su round trip at ($fu,$fv)")
                assertEquals(span.lv * fv, f.sv, span.lv * 0.02, "sv round trip at ($fu,$fv)")
            }
        }
    }

    @Test
    fun `arc length means millimetres, so a step across the guide is a real distance`() {
        val g = sweptGuide()
        val span = assertNotNull(GuidePainting.surfaceSpan(g))
        val a = assertNotNull(GuidePainting.sampleSurface(g, span.lu * 0.5, span.lv * 0.5))
        val b = assertNotNull(GuidePainting.sampleSurface(g, span.lu * 0.5, span.lv * 0.5 + 0.05))
        // 50mm along v should be 50mm of world, which is the whole point of
        // parameterising by arc length rather than by cell index
        assertEquals(0.05, a.point.distanceTo(b.point), 0.05 * 0.05)
    }

    @Test
    fun `a primitive has no arc-length grid, which is why Fill cannot use one`() {
        // stated as a test rather than left as folklore: this is the known gap
        // that makes Fill fail on a primitive in both builds
        val g = Primitives.create("sphere")
        assertNull(GuidePainting.surfaceSpan(g))
        assertNull(GuidePainting.sampleSurface(g, 0.1, 0.1))

        // ...but painting on one works, because that goes through the ray query
        val ray = Ray(Vec3(0.0, 0.0, 5.0), Vec3(0.0, 0.0, -1.0))
        val hit = assertNotNull(GuidePainting.project(g, ray))
        assertTrue(hit.onSurface)
        assertEquals(1.4, hit.point.z, 1e-5, "the ray should meet the sphere at its radius")
        assertNull(hit.frame, "a primitive cannot produce a surface frame")
    }

    // ---- the edge --------------------------------------------------------

    @Test
    fun `reach measures to the edge of a swept guide, and is zero off the end`() {
        val g = sweptGuide()
        val span = assertNotNull(GuidePainting.surfaceSpan(g))
        val mid = assertNotNull(GuidePainting.sampleSurface(g, span.lu * 0.5, span.lv * 0.5))
        val f = assertNotNull(mid.frame)

        val along = GuidePainting.reachAlong(f, f.uDir)
        assertEquals(f.lu - f.su, along.pos, f.lu * 0.02)
        assertEquals(f.su, along.neg, f.lu * 0.02)

        // sitting exactly on the u edge, there is nothing further that way
        val edge = assertNotNull(GuidePainting.sampleSurface(g, span.lu, span.lv * 0.5))
        val ef = assertNotNull(edge.frame)
        assertEquals(0.0, GuidePainting.reachAlong(ef, ef.uDir).pos, span.lu * 0.02)
    }

    @Test
    fun `on a flat guide the outline is the edge, not its bounding box`() {
        /*
         * The distinction that makes a flat guide worth having. A circle's
         * bounding box reaches to the corner; the circle does not, and the nib
         * has to be trimmed against the shape that was actually drawn.
         */
        val g = flatGuide()
        val span = assertNotNull(GuidePainting.surfaceSpan(g))
        assertNotNull(span.outline, "a flat guide must carry its outline")

        // stand at the centre and reach towards a corner of the bounding box
        val centre = assertNotNull(GuidePainting.sampleSurface(g, span.lu / 2, span.lv / 2))
        val f = assertNotNull(centre.frame)
        val diagonal = (f.uDir.copy() + f.vDir.copy()).normalize()
        val reach = GuidePainting.reachAlong(f, diagonal)

        val radius = span.lu / 2
        val toCorner = kotlin.math.hypot(span.lu / 2, span.lv / 2)
        assertTrue(
            reach.pos < toCorner * 0.95,
            "reach ${reach.pos} went as far as the bounding box corner $toCorner",
        )
        assertEquals(radius, reach.pos, radius * 0.05, "reach should stop at the circle")
    }

    @Test
    fun `the area outside a flat guide's outline is not surface at all`() {
        val g = flatGuide()
        val span = assertNotNull(GuidePainting.surfaceSpan(g))
        // the corner of the bounding box is outside a circle inscribed in it
        assertNull(GuidePainting.sampleSurface(g, 0.001, 0.001))
        assertNotNull(GuidePainting.sampleSurface(g, span.lu / 2, span.lv / 2))
    }

    // ---- running off the guide -------------------------------------------

    @Test
    fun `a stroke that runs off the guide clamps back to its nearest point`() {
        val g = sweptGuide()
        val s = assertNotNull(g.surface)
        // aimed well past the side of the surface
        val ray = Ray(Vec3(3.0, 0.0, 0.5), Vec3(0.0, 0.0, -1.0))
        assertNull(s.mesh.raycast(ray), "the probe was supposed to miss")

        val clamped = assertNotNull(GuidePainting.project(g, ray, clampOffSurface = true))
        assertTrue(!clamped.onSurface, "a clamped sample must say it was clamped")
        assertTrue(
            s.mesh.distanceTo(clamped.point) < 1e-6,
            "the clamped point is not actually on the guide",
        )
    }

    @Test
    fun `a clamped sample is lit the same way a hit would be, not the reverse`() {
        /*
         * The web build reported the stored VERTEX normal here while a hit
         * reported the FACE normal, and on a swept surface the two point
         * opposite ways — so every clamped sample was shaded as if it faced
         * away from the light, leaving a visibly darker band wherever a stroke
         * ran off the edge. Both routes must agree.
         */
        val g = sweptGuide()
        val hit = assertNotNull(
            GuidePainting.project(g, Ray(Vec3(0.0, 3.0, -0.5), Vec3(0.0, -1.0, 0.0))),
        )
        val clamped = assertNotNull(
            GuidePainting.project(g, Ray(Vec3(3.0, 3.0, -0.5), Vec3(0.0, -1.0, 0.0))),
        )
        assertEquals(1.0, clamped.normal.length(), 1e-9)
        assertTrue(
            (hit.normal dot clamped.normal) > 0,
            "the clamped normal faces the opposite way to the hit normal",
        )
    }

    @Test
    fun `a guide that refuses to clamp drops the sample instead`() {
        // FACT (A.4): an imported image refuses strokes past its edge
        val g = sweptGuide()
        g.noClamp = true
        val ray = Ray(Vec3(3.0, 0.0, 0.5), Vec3(0.0, 0.0, -1.0))
        assertNull(GuidePainting.project(g, ray, clampOffSurface = true))

        // and the Clamp setting turns it off for everything else
        g.noClamp = false
        assertNull(GuidePainting.project(g, ray, clampOffSurface = false))
        assertNotNull(GuidePainting.project(g, ray, clampOffSurface = true))
    }

    // ---- isolation masking  (A.9) ----------------------------------------

    @Test
    fun `a guide hides what is behind it, from the side it is being seen from`() {
        val g = sweptGuide()
        // looking across the sheet, so there is actually something in the way
        val eye = Vec3(0.0, 3.0, -0.5)

        // a point on the far side of the surface
        val behind = Vec3(0.0, -3.0, -0.5)
        assertTrue(GuidePainting.isMasked(g, eye, behind), "the guide should hide this")

        // the same point, seen from its own side, has nothing in the way
        assertTrue(
            !GuidePainting.isMasked(g, Vec3(0.0, -6.0, -0.5), behind),
            "masking is a property of the viewpoint, and this one has a clear line",
        )

        // a point between the eye and the guide is never hidden
        assertTrue(!GuidePainting.isMasked(g, eye, Vec3(0.0, 2.0, -0.5)))
        // and a point out past the end of the sweep has nothing in the way
        assertTrue(!GuidePainting.isMasked(g, eye, Vec3(0.0, -3.0, -5.0)))
    }

    @Test
    fun `a point on the guide is not masked by the guide it sits on`() {
        val g = sweptGuide()
        val eye = Vec3(0.0, 3.0, -0.5)
        val hit = assertNotNull(
            GuidePainting.project(g, Ray(eye.copy(), Vec3(0.0, -1.0, 0.0))),
        )
        assertTrue(
            !GuidePainting.isMasked(g, eye, hit.point),
            "paint on the guide would be filtered out by its own surface",
        )
    }
}
