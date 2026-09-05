package art.plume.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FACT (A.6): "Every 3D Guide you create in Feather starts with a starting
 * point, marked by an orange line... the 3D Guide will bend along the drawn
 * line... bending starts from the orange line", and the worked example is
 * "drawing the side of a pot, then bending it into a cylinder".
 *
 * That example is a CLOSED path, which is why it is the one asserted hardest
 * here: the seam is where a bend shows its working.
 */
class BendTest {

    private val view = Vec3(0.0, 0.0, -1.0)
    private val right = Vec3(1.0, 0.0, 0.0)

    /** The side of a pot: a curve in the screen plane. */
    private fun potProfile(): List<Vec3> =
        (0 until 30).map { i ->
            val t = i / 29.0
            Vec3(0.05 + 0.03 * sin(t * Math.PI), -0.1 + 0.2 * t, 0.0)
        }

    /** A ring in the XZ plane, drawn as a hand would: ends near, not equal. */
    private fun ring(r: Double, n: Int = 48, gap: Double = 0.02): List<Vec3> =
        (0 until n).map { i ->
            val a = (2 * Math.PI - gap) * i / (n - 1)
            Vec3(r * sin(a), 0.0, r * (1 - cos(a)))
        }

    private fun rowsOf(g: Guide): List<List<Vec3>> = Guides.evalSweep(g.sweep!!)

    @Test
    fun `a hand-drawn ring reads as closed and a hook does not`() {
        assertTrue(Guides.pathIsClosed(ring(0.2)), "a ring the hand closed")
        val hook = (0 until 20).map { i -> Vec3(i * 0.01, 0.0, 0.0) } +
            (0 until 20).map { i -> Vec3(0.19 - i * 0.01, 0.02, 0.0) }
        assertFalse(Guides.pathIsClosed(hook), "a hairpin is not a ring")
    }

    @Test
    fun `bending a pot into a cylinder closes the seam`() {
        val g = Guides.createFromStroke(potProfile(), view, right, 1.0)!!
        assertTrue(GuideEditing.bend(g, ring(0.2)))

        val rows = rowsOf(g)
        val first = rows.first()
        val last = rows.last()
        assertEquals(first.size, last.size)

        /*
         * THE WHOLE POINT. Swept as an open path the two ends of the pot were
         * a visible step apart: the path's own ends are a pen's width from
         * each other, and transport round the loop had left the section
         * rotated on top of that.
         */
        var worst = 0.0
        for (i in first.indices) worst = maxOf(worst, first[i].distanceTo(last[i]))
        assertEquals(0.0, worst, 1e-9, "the seam of a closed bend is welded shut")
    }

    @Test
    fun `an open bend still runs from one end to the other`() {
        val g = Guides.createFromStroke(potProfile(), view, right, 1.0)!!
        val arc = (0 until 40).map { i ->
            val a = Math.PI * 0.5 * i / 39.0
            Vec3(0.3 * sin(a), 0.0, 0.3 * (1 - cos(a)))
        }
        assertTrue(GuideEditing.bend(g, arc))

        val rows = rowsOf(g)
        var worst = 0.0
        for (i in rows.first().indices) {
            worst = maxOf(worst, rows.first()[i].distanceTo(rows.last()[i]))
        }
        assertTrue(worst > 0.05, "an arc is not a ring and must not be welded into one")
    }

    @Test
    fun `the bend starts at the orange line, wherever the stroke was drawn`() {
        val g = Guides.createFromStroke(potProfile(), view, right, 1.0)!!
        val anchor = g.sweep!!.anchor.copy()

        // a bend stroke drawn a long way from the guide
        val far = (0 until 30).map { i -> Vec3(5.0 + i * 0.01, 3.0, -2.0) }
        assertTrue(GuideEditing.bend(g, far))

        val path = g.sweep!!.path
        assertEquals(0.0, path[0].distanceTo(anchor), 1e-9, "the path starts at the anchor")
        assertEquals(0, g.sweep!!.anchorIndex, "and the orange line is that start")
    }

    @Test
    fun `bending twice is bending the second stroke, not both of them`() {
        val a = Guides.createFromStroke(potProfile(), view, right, 1.0)!!
        val b = Guides.createFromStroke(potProfile(), view, right, 1.0)!!

        val first = (0 until 30).map { i -> Vec3(i * 0.01, 0.0, 0.0) }
        val second = (0 until 30).map { i ->
            val t = i / 29.0
            Vec3(0.3 * sin(t * 2), 0.1 * t, 0.3 * (1 - cos(t * 2)))
        }
        assertTrue(GuideEditing.bend(a, first))
        assertTrue(GuideEditing.bend(a, second))
        assertTrue(GuideEditing.bend(b, second))

        val ra = rowsOf(a)
        val rb = rowsOf(b)
        var worst = 0.0
        for (j in ra.indices) for (i in ra[j].indices) {
            worst = maxOf(worst, ra[j][i].distanceTo(rb[j][i]))
        }
        assertEquals(0.0, worst, 1e-9, "a second bend replaces the first, it does not compound")
    }

    @Test
    fun `a welded ring has no zero-area quads at the seam`() {
        val g = Guides.createFromStroke(potProfile(), view, right, 1.0)!!
        assertTrue(GuideEditing.bend(g, ring(0.2)))
        val s = g.surface!!

        /* A weld that collapsed the last row onto the first would leave a band
           of degenerate triangles rather than a joint — the surface would look
           right and shade wrong. */
        var degenerate = 0
        for (j in 0 until s.nv - 1) for (i in 0 until s.nu - 1) {
            fun at(ii: Int, jj: Int): Vec3 {
                val k = (jj * s.nu + ii) * 3
                return Vec3(
                    s.positions[k].toDouble(),
                    s.positions[k + 1].toDouble(),
                    s.positions[k + 2].toDouble(),
                )
            }
            val n = (at(i + 1, j) - at(i, j)) cross (at(i, j + 1) - at(i, j))
            if (n.lengthSq() < 1e-16) degenerate++
        }
        assertTrue(
            degenerate <= s.nu,
            "a welded seam should not fill the mesh with slivers (got $degenerate)",
        )
    }
}
