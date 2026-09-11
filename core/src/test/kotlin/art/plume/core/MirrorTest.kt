package art.plume.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FACT: Feather's Mirror shows three axes below its icon — red X, green Y,
 * blue Z — and "you can activate multiple axes at the same time"; it mirrors
 * about the GLOBAL axes.
 */
class MirrorTest {

    private fun stroke(vararg xs: Double): Stroke =
        Stroke(brush = "pen").also { st ->
            for (x in xs) st.pts.add(StrokePoint(Vec3(x, 1.0, 2.0)))
        }

    @Test
    fun `two planes give three reflections, three give seven`() {
        assertEquals(listOf("x"), Mirror.keysFor(setOf("x")))
        assertEquals(3, Mirror.keysFor(setOf("x", "y")).size, "and the corner one")
        assertEquals(7, Mirror.keysFor(setOf("x", "y", "z")).size, "eight octants, less yours")
        assertEquals(emptyList(), Mirror.keysFor(emptySet()))
        /* the diagonal is what fills the fourth quadrant: without it a chair
           mirrored left-right and top-bottom has three legs */
        assertTrue(Mirror.keysFor(setOf("x", "y")).contains("xy"))
    }

    @Test
    fun `a reflection lands across the plane and remembers what it reflects`() {
        val sketch = Sketch()
        val src = stroke(1.0, 2.0)
        sketch.add(src)
        val copies = Mirror.copiesOf(src, setOf("x"))
        for (c in copies) sketch.add(c)

        assertEquals(1, copies.size)
        assertEquals(src.id, copies[0].mirrorOf)
        assertEquals("x", copies[0].mirrorKey)
        assertEquals(-1.0, copies[0].pts[0].p.x, 1e-12)
        assertEquals(1.0, copies[0].pts[0].p.y, 1e-12, "only x is flipped")
    }

    @Test
    fun `moving the original moves its reflection`() {
        val sketch = Sketch()
        val src = stroke(1.0, 2.0)
        sketch.add(src)
        for (c in Mirror.copiesOf(src, setOf("x"))) sketch.add(c)

        // the fault this exists for: the original moves, by any tool at all
        Selection.transform(listOf(src), Mat4.translation(0.5, 0.0, 0.0, Mat4()))
        val moved = Mirror.resync(sketch)

        assertEquals(1, moved.size, "the reflection is out of date and says so")
        val copy = sketch.strokes[1]
        assertEquals(-1.5, copy.pts[0].p.x, 1e-9)
        assertEquals(-2.5, copy.pts[1].p.x, 1e-9)
    }

    @Test
    fun `restyling the original restyles its reflection`() {
        val sketch = Sketch()
        val src = stroke(1.0, 2.0)
        sketch.add(src)
        for (c in Mirror.copiesOf(src, setOf("z"))) sketch.add(c)

        src.color = Rgba(1.0, 0.0, 0.0)
        src.brush = "wide"
        val moved = Mirror.resync(sketch)

        assertEquals(1, moved.size)
        assertEquals("wide", sketch.strokes[1].brush)
        assertEquals(Rgba(1.0, 0.0, 0.0), sketch.strokes[1].color)
    }

    @Test
    fun `a reflection already in step is left alone`() {
        val sketch = Sketch()
        val src = stroke(1.0, 2.0)
        sketch.add(src)
        for (c in Mirror.copiesOf(src, setOf("x", "y"))) sketch.add(c)

        /* nothing has changed, so nothing may be rewritten: the renderer keeps
           a mesh per curve and rebuilding three of them on every refresh is
           the cost this whole comparison exists to avoid */
        assertEquals(emptyList(), Mirror.resync(sketch))
    }

    @Test
    fun `erasing the original leaves its reflection standing, unlinked`() {
        val sketch = Sketch()
        val src = stroke(1.0, 2.0)
        sketch.add(src)
        for (c in Mirror.copiesOf(src, setOf("x"))) sketch.add(c)
        val copy = sketch.strokes[1]

        sketch.remove(src)
        Mirror.resync(sketch)

        assertEquals(1, sketch.strokes.size, "the other half of the drawing is still there")
        assertNull(copy.mirrorOf, "but it is nobody's reflection now")
        assertEquals(-1.0, copy.pts[0].p.x, 1e-12, "and it did not move")
    }

    @Test
    fun `the link survives a save and a reload`() {
        val sketch = Sketch()
        val src = stroke(1.0, 2.0)
        sketch.add(src)
        for (c in Mirror.copiesOf(src, setOf("x"))) sketch.add(c)

        val text = Document.toJsonText(
            sketch, GuideScene(), Camera().apply { resize(800, 800) },
        )
        val back = Sketch()
        assertTrue(
            Document.restore(text, back, GuideScene(), Camera().apply { resize(800, 800) }).ok,
        )
        assertEquals(2, back.strokes.size)

        /* the ids in the file are the file's own, so this is really a test
           that they were REMAPPED rather than trusted */
        assertEquals(back.strokes[0].id, back.strokes[1].mirrorOf)
        assertEquals("x", back.strokes[1].mirrorKey)

        // and it is a live link, not just a remembered number
        Selection.transform(listOf(back.strokes[0]), Mat4.translation(0.5, 0.0, 0.0, Mat4()))
        assertEquals(1, Mirror.resync(back).size)
        assertEquals(-1.5, back.strokes[1].pts[0].p.x, 1e-9)
    }

    @Test
    fun `a point added to the original appears on the reflection`() {
        val sketch = Sketch()
        val src = stroke(1.0, 2.0)
        sketch.add(src)
        for (c in Mirror.copiesOf(src, setOf("x"))) sketch.add(c)

        src.pts.add(StrokePoint(Vec3(3.0, 1.0, 2.0)))
        assertEquals(1, Mirror.resync(sketch).size)
        assertEquals(3, sketch.strokes[1].pts.size)
        assertEquals(-3.0, sketch.strokes[1].pts[2].p.x, 1e-9)
    }
}
