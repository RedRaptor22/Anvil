package art.plume.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GridTest {

    @Test
    fun `the grid is one line per metre across forty metres, flat on the ground`() {
        val g = Grid.build(Rgba(0.925, 0.918, 0.953))
        // 41 lines each way, two vertices each
        assertEquals((Tune.GRID_DIVISIONS + 1) * 2 * 2, g.vertexCount)
        for (i in 0 until g.vertexCount) {
            assertEquals(0f, g.positions[i * 3 + 1], "grid vertex $i is off the ground plane")
            assertTrue(kotlin.math.abs(g.positions[i * 3]) <= Tune.GRID_EXTENT / 2 + 1e-6)
            assertTrue(kotlin.math.abs(g.positions[i * 3 + 2]) <= Tune.GRID_EXTENT / 2 + 1e-6)
        }
    }

    @Test
    fun `grid lines contrast against the page, whichever way the page goes`() {
        val light = Rgba(0.925, 0.918, 0.953)
        val dark = Rgba(0.09, 0.09, 0.11)

        val (lMajor, lMinor) = Grid.gridColors(light)
        assertTrue(Grid.luminance(lMajor) < Grid.luminance(light), "light page needs darker lines")
        assertTrue(Grid.luminance(lMinor) < Grid.luminance(light))
        // the centre line is the stronger of the two
        assertTrue(Grid.luminance(lMajor) < Grid.luminance(lMinor))

        val (dMajor, dMinor) = Grid.gridColors(dark)
        assertTrue(Grid.luminance(dMajor) > Grid.luminance(dark), "dark page needs lighter lines")
        assertTrue(Grid.luminance(dMajor) > Grid.luminance(dMinor))
    }

    @Test
    fun `the two centre lines are the ones picked out`() {
        val bg = Rgba(0.925, 0.918, 0.953)
        val (major, _) = Grid.gridColors(bg)
        val g = Grid.build(bg)
        var majorVertices = 0
        for (i in 0 until g.vertexCount) {
            if (kotlin.math.abs(g.colors[i * 4] - major.r.toFloat()) < 1e-6) majorVertices++
        }
        // one line through the origin in each direction, two vertices each
        assertEquals(4, majorVertices)
    }

    @Test
    fun `the axis is red green blue through the origin`() {
        val a = Grid.axis()
        assertEquals(6, a.vertexCount)
        // X is reddest, Y greenest, Z bluest
        assertTrue(a.colors[0] > a.colors[1] && a.colors[0] > a.colors[2])
        assertTrue(a.colors[9] > a.colors[8] && a.colors[9] > a.colors[10])
        assertTrue(a.colors[18] > a.colors[16] && a.colors[18] > a.colors[17])
        // and each runs symmetrically through zero
        for (axis in 0..2) {
            for (c in 0..2) {
                assertEquals(
                    -a.positions[axis * 6 + c], a.positions[axis * 6 + 3 + c], 1e-6f,
                    "axis $axis component $c is not symmetric about the origin",
                )
            }
        }
    }
}
