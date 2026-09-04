package art.plume.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CurvesTest {

    /** A straight run of [n] points along x, at height [y] and depth [z]. */
    private fun line(y: Double, z: Double = 0.0, n: Int = 40): List<Vec3> =
        (0 until n).map { i -> Vec3(i * 0.01, y, z) }      // 0.4 m long

    @Test
    fun `a curve on its own comes back exactly as it went in`() {
        val one = line(0.0)
        val out = Curves.merge(listOf(one))
        assertEquals(1, out.size)
        assertEquals(one.size, out[0].size)
        for (i in one.indices) assertEquals(0.0, one[i].distanceTo(out[0][i]), 1e-12)
    }

    @Test
    fun `curves drawn over one another become one, at their average`() {
        // three strokes within 6mm of each other on a 400mm line
        val bundle = listOf(line(0.0), line(0.003), line(0.006))
        val out = Curves.merge(bundle)

        assertEquals(1, out.size, "an overdrawn bundle is one curve")
        // the average of 0, 3 and 6mm is 3mm, everywhere along it
        for (p in out[0]) assertEquals(0.003, p.y, 1e-9)
    }

    @Test
    fun `a bundle and a far curve stay two sections, bundle first`() {
        // the bundle is drawn first, the far curve after
        val out = Curves.merge(listOf(line(0.0), line(0.004), line(0.25)))

        assertEquals(2, out.size)
        assertEquals(0.002, out[0][0].y, 1e-9, "the bundle averaged")
        assertEquals(0.25, out[1][0].y, 1e-9, "the far curve untouched")
    }

    @Test
    fun `the fault this exists for- a bundle no longer swamps the loft`() {
        /*
         * FOUR sections where three of them are the same line: the interpolated
         * surface spends two of its three spans inside a 4mm bundle. This is
         * the shape of the bug, asserted as the count the loft would receive.
         */
        val raw = listOf(line(0.0), line(0.002), line(0.004), line(0.30))
        assertEquals(4, raw.size)
        assertEquals(2, Curves.merge(raw).size)
    }

    @Test
    fun `a curve drawn the other way round is still the same curve`() {
        val out = Curves.merge(listOf(line(0.0), line(0.003).reversed()))
        assertEquals(1, out.size, "direction is not distance")
        // averaged, not folded: a fold would pull the middle away from 1.5mm
        for (p in out[0]) assertEquals(0.0015, p.y, 1e-9)
    }

    @Test
    fun `closeness is relative to the curves, not an absolute distance`() {
        /*
         * The same 20mm gap: wide apart on a 40mm line, overdrawn on a 4m one.
         * An absolute tolerance would have to be wrong about one of them.
         */
        val small = listOf(
            (0 until 40).map { Vec3(it * 0.001, 0.0, 0.0) },
            (0 until 40).map { Vec3(it * 0.001, 0.02, 0.0) },
        )
        val large = listOf(
            (0 until 40).map { Vec3(it * 0.1, 0.0, 0.0) },
            (0 until 40).map { Vec3(it * 0.1, 0.02, 0.0) },
        )
        assertEquals(2, Curves.merge(small).size, "20mm apart on a 40mm line is two")
        assertEquals(1, Curves.merge(large).size, "20mm apart on a 4m line is one")
    }

    @Test
    fun `separate curves are left alone, in the order they were given`() {
        val out = Curves.merge(listOf(line(0.0), line(0.2), line(0.4)))
        assertEquals(3, out.size)
        assertEquals(0.0, out[0][0].y, 1e-12)
        assertEquals(0.2, out[1][0].y, 1e-12)
        assertEquals(0.4, out[2][0].y, 1e-12)
    }

    @Test
    fun `a merged bundle can be lofted against the curve it was drawn towards`() {
        val strokes = listOf(0.0, 0.003, 0.006, 0.30).map { y ->
            Stroke(brush = "pen").also { st ->
                for (p in line(y)) st.pts.add(StrokePoint(p.copy()))
            }
        }
        val merged = Curves.mergeStrokes(strokes)
        assertEquals(2, merged.size)

        val g = GuideEditing.loftFromCurves(merged)
        assertTrue(g != null, "two sections make a surface")
        assertTrue((g!!.surface?.positions?.size ?: 0) > 0)
    }
}
