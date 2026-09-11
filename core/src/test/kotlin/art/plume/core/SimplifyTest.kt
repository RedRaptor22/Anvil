package art.plume.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FACT: "A lightened note is automatically optimized and decimated to reduce
 * file size, though it may differ slightly from the original."
 *
 * "Slightly" is the whole specification, and the tests are about what
 * slightly has to mean: corners survive, the ends never move, and a line
 * nobody would draw twice comes back as the two points it always was.
 */
class SimplifyTest {

    private fun stroke(vararg p: Vec3, radius: Double = 7.0 * MM): Stroke =
        Stroke(brush = "pen", baseRadius = radius).also { s ->
            for (q in p) s.pts.add(StrokePoint(q.copy()))
        }

    private fun line(n: Int, radius: Double = 7.0 * MM): Stroke =
        Stroke(brush = "pen", baseRadius = radius).also { s ->
            for (i in 0 until n) s.pts.add(StrokePoint(Vec3(i * 0.01, 0.0, 0.0)))
        }

    @Test
    fun `a straight line drawn slowly comes back as two points`() {
        val s = line(200)
        val gone = Simplify.stroke(s)
        assertEquals(198, gone)
        assertEquals(2, s.pts.size)
        assertEquals(0.0, s.pts[0].p.x, 1e-12)
        assertEquals(1.99, s.pts[1].p.x, 1e-9, "and the far end is exactly where it was")
    }

    @Test
    fun `a corner is not rounded off`() {
        /* the failure mode of every naive decimation — dropping every other
           point takes the corner with it, and a drawing is made of corners */
        val s = stroke(
            Vec3(0.0, 0.0, 0.0), Vec3(0.05, 0.0, 0.0), Vec3(0.1, 0.0, 0.0),
            Vec3(0.1, 0.05, 0.0), Vec3(0.1, 0.1, 0.0),
        )
        Simplify.stroke(s)
        assertEquals(3, s.pts.size)
        assertEquals(0.1, s.pts[1].p.x, 1e-12, "the corner itself")
        assertEquals(0.0, s.pts[1].p.y, 1e-12)
    }

    @Test
    fun `a wide brush forgives more than a hairline`() {
        val bump = { r: Double ->
            stroke(
                Vec3(0.0, 0.0, 0.0), Vec3(0.05, 0.0006, 0.0), Vec3(0.1, 0.0, 0.0),
                radius = r,
            ).also { Simplify.stroke(it) }.pts.size
        }
        /* 0.6mm off a straight line is nothing under a 30mm marker and is the
           shape of the stroke under a 1mm pen */
        assertEquals(2, bump(15.0 * MM), "the marker straightens it")
        assertEquals(3, bump(0.5 * MM), "the pen keeps it")
    }

    @Test
    fun `the ends never move, so a loop stays closed`() {
        /* a 50mm circle under a 7mm brush: big enough that the samples are
           closer together than the brush can show, which is the case worth
           decimating. Draw the same circle a metre across and the tolerance
           keeps nearly every point — that is the tolerance being measured
           against the brush rather than against the world, on purpose. */
        val ring = Stroke(brush = "pen", baseRadius = 7.0 * MM)
        for (i in 0..64) {
            val a = i / 64.0 * 2 * Math.PI
            ring.pts.add(StrokePoint(Vec3(Math.cos(a) * 0.05, Math.sin(a) * 0.05, 0.0)))
        }
        val first = ring.pts.first().p.copy()
        val last = ring.pts.last().p.copy()
        Simplify.stroke(ring)

        assertTrue(ring.pts.size < 40, "it did decimate the circle")
        assertTrue(ring.pts.size > 8, "and it is still a circle, not a triangle")
        assertEquals(first.x, ring.pts.first().p.x, 1e-12)
        assertEquals(last.x, ring.pts.last().p.x, 1e-12)
        assertEquals(last.y, ring.pts.last().p.y, 1e-12, "the seam is exactly where it was")
    }

    @Test
    fun `a stroke of two points is left alone`() {
        val s = stroke(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0))
        assertEquals(0, Simplify.stroke(s))
        assertEquals(2, s.pts.size)
    }

    @Test
    fun `what a point knows travels with it`() {
        /* points carry pressure, a frame and the trim the guide measured for
           them; rebuilding from positions alone would throw all of that away
           and the stroke would spring back over the edge it was painted on */
        val s = line(50)
        s.pts[49].pressure = 0.25
        s.pts[49].fitL = 3.5
        Simplify.stroke(s)
        assertEquals(0.25, s.pts.last().pressure, 1e-12)
        assertEquals(3.5, s.pts.last().fitL, 1e-12)
    }

    @Test
    fun `a whole sketch reports what it saved`() {
        val sk = Sketch()
        sk.add(line(100))
        sk.add(line(100))
        assertEquals(196, Simplify.sketch(sk))
        assertEquals(2, sk.strokes[0].pts.size)
    }
}
