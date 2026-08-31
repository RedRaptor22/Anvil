package art.plume.core

import kotlin.test.Test
import kotlin.test.assertEquals
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
