package art.plume.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The frame maths, checked against the same properties `Plume/pt_test.js`
 * checks in the web build. If these drift apart the two apps will draw
 * differently, which is the whole reason this module is shared.
 */
class FramesTest {

    private fun helix(n: Int, turns: Double = 2.0, radius: Double = 1.0, rise: Double = 2.0) =
        (0 until n).map {
            val u = it.toDouble() / (n - 1)
            val a = u * turns * 2 * PI
            Vec3(cos(a) * radius, u * rise, sin(a) * radius)
        }

    private fun ring(n: Int, radius: Double = 1.0): List<Vec3> =
        (0..n).map {
            val a = it.toDouble() / n * 2 * PI
            Vec3(cos(a) * radius, 0.0, sin(a) * radius)
        }

    private fun degBetween(a: Vec3, b: Vec3): Double {
        // chord, not acos of the dot: near zero angle acos amplifies float
        // error so badly that identical vectors read as 1e-6 degrees apart
        val chord = a.distanceTo(b)
        return 2 * kotlin.math.asin(kotlin.math.min(1.0, chord / 2)) * 180 / PI
    }

    @Test
    fun `frames stay orthonormal along a helix`() {
        val pts = helix(200)
        val f = Frames.transportFrames(pts)
        var worstDot = 0.0
        var worstLen = 0.0
        for (i in pts.indices) {
            worstDot = maxOf(worstDot, abs(f.t[i] dot f.r[i]))
            worstLen = maxOf(worstLen, abs(f.t[i].length() - 1), abs(f.r[i].length() - 1))
        }
        assertTrue(worstDot < 1e-9, "t.r should stay 0, worst $worstDot")
        assertTrue(worstLen < 1e-9, "both should stay unit, worst $worstLen")
    }

    @Test
    fun `no NaN through an inflection, where a Frenet frame would flip`() {
        // an S-curve: curvature passes through zero in the middle
        val pts = (0 until 120).map {
            val u = it / 119.0 * 2 - 1
            Vec3(u * 2, u * u * u, 0.0)
        }
        val f = Frames.transportFrames(pts)
        for (i in pts.indices) {
            assertTrue(f.t[i].x.isFinite() && f.t[i].y.isFinite() && f.t[i].z.isFinite())
            assertTrue(f.r[i].x.isFinite() && f.r[i].y.isFinite() && f.r[i].z.isFinite())
        }
        // and the reference vector must not jump, which is the point of PT
        var worstStep = 0.0
        for (i in 1 until pts.size) worstStep = maxOf(worstStep, f.r[i].distanceTo(f.r[i - 1]))
        assertTrue(worstStep < 0.15, "reference should be continuous, worst step $worstStep")
    }

    @Test
    fun `a seed direction is honoured`() {
        val pts = helix(50)
        val seed = Vec3(0.0, 1.0, 0.0)
        val f = Frames.transportFrames(pts, seed)
        // r0 is the seed with the tangent component removed, so it stays in the
        // plane the seed and the tangent span
        assertTrue(abs(f.r[0] dot f.t[0]) < 1e-12)
        assertTrue((f.r[0] cross seed).length() < 0.5, "r0 should lean towards the seed")
    }

    @Test
    fun `a seed parallel to the tangent falls back instead of exploding`() {
        val pts = listOf(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(2.0, 0.0, 0.0))
        val f = Frames.transportFrames(pts, Vec3(1.0, 0.0, 0.0))
        assertTrue(f.r[0].length() > 0.9, "should still produce a unit reference")
        assertTrue(abs(f.r[0] dot f.t[0]) < 1e-12)
    }

    @Test
    fun `an open path is not mistaken for a ring`() {
        val pts = helix(40)
        assertTrue(!Frames.loopsClosed(pts))
    }

    @Test
    fun `a ring is recognised and its two ends agree exactly`() {
        val pts = ring(64)
        assertTrue(Frames.loopsClosed(pts), "last point is the first again")

        val f = Frames.transportFrames(pts, null, closed = true)
        val n = pts.size
        // Built open, these differ by exactly one angular step — 360/64 =
        // 5.625 degrees — and that wedge is the slit at a circle's seam.
        assertTrue(
            degBetween(f.t[0], f.t[n - 1]) < 1e-4,
            "tangents should meet, got ${degBetween(f.t[0], f.t[n - 1])} deg"
        )
        assertTrue(
            degBetween(f.r[0], f.r[n - 1]) < 1e-4,
            "cross-sections should meet, got ${degBetween(f.r[0], f.r[n - 1])} deg"
        )
    }

    @Test
    fun `built open, the same ring shows the 5-625 degree step this fixes`() {
        val pts = ring(64)
        val f = Frames.transportFrames(pts, null, closed = false)
        val gap = degBetween(f.t[0], f.t[pts.size - 1])
        assertTrue(
            abs(gap - 360.0 / 64) < 0.01,
            "open ends should differ by one angular step, got $gap deg"
        )
    }

    @Test
    fun `arc length is monotonic and matches the polyline`() {
        val pts = helix(80)
        val l = Frames.arcLengths(pts)
        for (i in 1 until l.size) assertTrue(l[i] >= l[i - 1])
        assertEquals(l[l.size - 1], Frames.polyLength(pts), 1e-12)
    }

    @Test
    fun `a doubled-back sample is removed before it can reverse a tangent`() {
        // The fold that used to make a wide nib stand a plate of paint off the
        // surface: one sample sits BEHIND its predecessor. computeTangents
        // deliberately does not rescue this — the honest fix is to drop the
        // spur — so the pipeline runs Dedupe first, and this checks the pair.
        // 0.35mm back, which is the size the original fold actually was. The
        // threshold is max(0.25mm, half x 0.01), so what counts as a spur scales
        // with the brush: under a 90mm nib the cut-off is 0.45mm and this goes,
        // while a 3.5mm excursion is a real feature at every brush size.
        val raw = listOf(
            Vec3(0.0, 0.0, 0.0),
            Vec3(1.0, 0.0, 0.0),
            Vec3(0.99965, 0.0, 0.0),
            Vec3(2.0, 0.0, 0.0),
        )
        val bare = Frames.computeTangents(raw)
        assertTrue(
            bare.any { it.x < 0 },
            "without cleaning, the fold should still reverse a tangent — otherwise this test proves nothing"
        )

        val cleaned = Dedupe.clean(raw, brushHalfWidth = 45 * MM)   // a 90mm nib
        assertTrue(cleaned.size == 3, "the spur should be gone, got ${cleaned.size} points")

        // and the same fold under a fine nib is kept, because there it is a
        // mark the brush can actually draw
        val fine = Dedupe.clean(raw, brushHalfWidth = 5 * MM)
        assertTrue(fine.size == 4, "a fine nib should keep it, got ${fine.size}")
        for (v in Frames.computeTangents(cleaned)) {
            assertTrue(v.x > 0, "every tangent should point forwards, got ${v.x}")
        }
    }

    @Test
    fun `a deliberate sharp corner survives cleaning`() {
        // the tip of a real V stands far off the line joining its ends, so it
        // is not a spur however hard the path reverses through it
        val v = listOf(
            Vec3(0.0, 0.0, 0.0),
            Vec3(0.5, 0.0, 0.0),
            Vec3(0.25, 0.4, 0.0),     // a real corner, 400mm off the chord
            Vec3(0.0, 0.0, 0.0) + Vec3(0.0, 0.8, 0.0),
        )
        val cleaned = Dedupe.clean(v, brushHalfWidth = 20 * MM)
        assertTrue(cleaned.size == v.size, "a corner is not a spur, lost ${v.size - cleaned.size}")
    }
}
