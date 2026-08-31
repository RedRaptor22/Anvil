package art.plume.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ColorTest {

    @Test
    fun `the primaries land where they should`() {
        assertEquals(Rgba(1.0, 0.0, 0.0), ColorSpace.hsvToRgb(0.0, 1.0, 1.0))
        assertEquals(Rgba(0.0, 1.0, 0.0), ColorSpace.hsvToRgb(120.0, 1.0, 1.0))
        assertEquals(Rgba(0.0, 0.0, 1.0), ColorSpace.hsvToRgb(240.0, 1.0, 1.0))
        assertEquals(Rgba(1.0, 1.0, 1.0), ColorSpace.hsvToRgb(0.0, 0.0, 1.0))
        assertEquals(Rgba(0.0, 0.0, 0.0), ColorSpace.hsvToRgb(0.0, 0.0, 0.0))
    }

    /** Hue wraps, so the wheel has no seam to catch a knob on. */
    @Test
    fun `hue wraps in both directions`() {
        val at0 = ColorSpace.hsvToRgb(0.0, 1.0, 1.0)
        assertEquals(at0, ColorSpace.hsvToRgb(360.0, 1.0, 1.0))
        assertEquals(at0, ColorSpace.hsvToRgb(720.0, 1.0, 1.0))
        assertEquals(at0, ColorSpace.hsvToRgb(-360.0, 1.0, 1.0))
    }

    @Test
    fun `hsv survives a round trip`() {
        for (h in 0 until 360 step 7) {
            for (s in listOf(0.15, 0.5, 1.0)) {
                for (v in listOf(0.2, 0.7, 1.0)) {
                    val rgb = ColorSpace.hsvToRgb(h.toDouble(), s, v)
                    val back = ColorSpace.rgbToHsv(rgb)
                    assertEquals(h.toDouble(), back.h, 1e-6, "hue at $h/$s/$v")
                    assertEquals(s, back.s, 1e-9, "sat at $h/$s/$v")
                    assertEquals(v, back.v, 1e-9, "val at $h/$s/$v")
                }
            }
        }
    }

    /**
     * A grey has no hue, and every formula returns 0 for it. Carrying the
     * previous hue is what stops the wheel's knob jumping to red the moment
     * the value slides to black — the colour would be right and the control
     * would be lying about where it is.
     */
    @Test
    fun `a grey keeps the hue it came from`() {
        assertEquals(0.0, ColorSpace.rgbToHsv(Rgba(0.5, 0.5, 0.5)).h, 1e-12)
        assertEquals(
            217.0, ColorSpace.rgbToHsv(Rgba(0.5, 0.5, 0.5), previousHue = 217.0).h, 1e-12,
        )
        assertEquals(
            217.0, ColorSpace.rgbToHsv(Rgba(0.0, 0.0, 0.0), previousHue = 217.0).h,
            1e-12, "black too",
        )
    }

    @Test
    fun `hex parses in both lengths, with or without the hash`() {
        val red = ColorSpace.parseHex("#FF0000")
        assertTrue(red != null && red.r == 1.0 && red.g == 0.0 && red.b == 0.0)
        val short = ColorSpace.parseHex("f00")
        assertTrue(short != null && short.r == 1.0 && short.g == 0.0)
        assertEquals(0x1B / 255.0, ColorSpace.parseHex("1B1C21")!!.r, 1e-12)
    }

    /**
     * A half-typed hex leaves the colour alone. Guessing at four characters
     * would move the swatch on every keystroke.
     */
    @Test
    fun `an incomplete hex is refused rather than guessed`() {
        assertNull(ColorSpace.parseHex("1B1C"))
        assertNull(ColorSpace.parseHex(""))
        assertNull(ColorSpace.parseHex(null))
        assertNull(ColorSpace.parseHex("zzzzzz"))
    }

    @Test
    fun `hex round trips`() {
        for (h in listOf("1B1C21", "FF8A3D", "5B9DFF", "000000", "FFFFFF")) {
            assertEquals(h, ColorSpace.toHex(ColorSpace.parseHex(h)!!))
        }
    }

    @Test
    fun `out of range components clamp rather than wrap`() {
        assertEquals("FFFFFF", ColorSpace.toHex(Rgba(2.0, 1.5, 1.0)))
        assertEquals("000000", ColorSpace.toHex(Rgba(-1.0, -0.5, 0.0)))
    }
}

/**
 * Groups as the panel needs them: an active one, membership, and duplication.
 *
 * FACT (C.8): groups can be created, renamed, hidden and deleted, and a curve
 * can be assigned to one.
 */
class GroupTest {

    private fun sketchOf(n: Int): Sketch {
        val sk = Sketch()
        repeat(n) {
            val s = Stroke()
            s.pts.add(StrokePoint(Vec3(it.toDouble(), 0.0, 0.0)))
            s.pts.add(StrokePoint(Vec3(it.toDouble(), 1.0, 0.0)))
            sk.add(s)
        }
        return sk
    }

    /**
     * A sketch nobody has organised still has a row to show. "Ungrouped" as a
     * special case that behaves almost but not quite like a group is worse
     * than a group called Group 1.
     */
    @Test
    fun `ensureGroup adopts everything drawn before there were groups`() {
        val sk = sketchOf(3)
        assertTrue(sk.groups.isEmpty())
        val g = sk.ensureGroup()
        assertEquals(1, sk.groups.size)
        assertEquals(g.id, sk.activeGroup)
        assertEquals(3, sk.membersOf(g.id).size, "the existing curves joined it")
    }

    @Test
    fun `ensureGroup is idempotent and repairs a dangling active id`() {
        val sk = sketchOf(1)
        val a = sk.ensureGroup()
        assertEquals(a.id, sk.ensureGroup().id)

        val b = sk.newGroup("B")
        sk.setActiveGroup(b.id)
        sk.deleteGroup(b)
        // active now points at a group that is gone; ensureGroup must not
        // leave it there, or a new curve joins nothing
        assertEquals(a.id, sk.ensureGroup().id)
    }

    @Test
    fun `setActiveGroup refuses an id that is not a group`() {
        val sk = sketchOf(1)
        val g = sk.ensureGroup()
        sk.setActiveGroup(9999)
        assertEquals(null, sk.activeGroup, "a bad id clears rather than sticks")
        sk.setActiveGroup(g.id)
        assertEquals(g.id, sk.activeGroup)
    }

    /**
     * The copies land next to the originals rather than at the end of the
     * document, because draw order is what decides who is on top: a duplicate
     * that jumps to the front is a duplicate that looks different.
     */
    @Test
    fun `a duplicated group keeps its place in draw order`() {
        val sk = sketchOf(4)
        val g = sk.ensureGroup()
        val other = sk.newGroup("Other")
        sk.assign(sk.strokes[3], other)

        val (copy, copies) = sk.duplicateGroup(g)
        assertEquals(3, copies.size)
        assertEquals("Group 1 copy", copy.name)
        assertEquals(3, sk.membersOf(copy.id).size)
        assertEquals(3, sk.membersOf(g.id).size, "the originals are untouched")

        // the copies sit immediately after the last original, before `other`
        val lastOriginal = sk.strokes.indexOf(sk.membersOf(g.id).last())
        assertEquals(lastOriginal + 1, sk.strokes.indexOf(copies[0]))
        assertTrue(
            sk.strokes.indexOf(copies.last()) < sk.strokes.indexOf(sk.membersOf(other.id)[0]),
            "the copies went in ahead of the group that followed",
        )
    }

    @Test
    fun `a duplicated group carries its hidden state`() {
        val sk = sketchOf(2)
        val g = sk.ensureGroup()
        g.visible = false
        val (copy, copies) = sk.duplicateGroup(g)
        assertFalse(copy.visible)
        for (c in copies) assertFalse(sk.visible(c), "a copy of hidden work is hidden")
    }

    /**
     * Deleting a group frees its curves rather than taking them with it.
     * Removing a folder should not remove the work in it, and there is no undo
     * prompt that makes the other reading safe.
     */
    @Test
    fun `deleting a group keeps the curves`() {
        val sk = sketchOf(3)
        val g = sk.ensureGroup()
        val freed = sk.deleteGroup(g)
        assertEquals(3, freed.size)
        assertEquals(3, sk.strokes.size)
        for (s in sk.strokes) assertEquals(null, s.group)
    }
}
