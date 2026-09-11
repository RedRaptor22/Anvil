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

/**
 * What a harder press actually does.
 *
 * FACT (C.3): the pressure toggle lives in the Brush Panel, and it chooses
 * between size, opacity, both and colour. Until now every one of those was
 * stored and none was read: pressure reached the point record and stopped
 * there, so a stylus changed nothing at all.
 */
class PressureTest {

    private fun strokeWith(target: String, pressures: List<Double>, brush: String = "pen"): Stroke {
        val s = Stroke(brush = brush, baseRadius = 0.01, opacity = 1.0)
        s.pressureTarget = target
        for ((i, p) in pressures.withIndex()) {
            s.pts.add(StrokePoint(Vec3(i * 0.01, 0.0, 0.0), pressure = p))
        }
        return s
    }

    private fun shade(s: Stroke, i: Int) = StrokeGeometry.shadeAt(s, i, 0.0, 0.0)

    @Test
    fun `size mode makes a light press narrower and leaves the ink alone`() {
        val s = strokeWith("size", listOf(1.0, 0.1))
        val hard = shade(s, 0)
        val soft = shade(s, 1)
        assertTrue(soft.radius < hard.radius, "${soft.radius} should be under ${hard.radius}")
        assertEquals(hard.alpha, soft.alpha, 1e-12, "opacity is untouched")
        assertEquals(0.0, soft.lift, 1e-12)
    }

    @Test
    fun `opacity mode makes a light press fainter and leaves the width alone`() {
        val s = strokeWith("opacity", listOf(1.0, 0.1))
        val hard = shade(s, 0)
        val soft = shade(s, 1)
        assertTrue(soft.alpha < hard.alpha)
        assertEquals(hard.radius, soft.radius, 1e-12, "width is untouched")
    }

    @Test
    fun `both moves width and opacity together`() {
        val s = strokeWith("both", listOf(1.0, 0.1))
        val hard = shade(s, 0)
        val soft = shade(s, 1)
        assertTrue(soft.alpha < hard.alpha && soft.radius < hard.radius)
    }

    /**
     * Colour mode is the only one that changes the COLOUR rather than the ink:
     * a light press is lifted towards the page, which is what a pencil does.
     */
    @Test
    fun `colour mode lifts a light press towards the page`() {
        val s = strokeWith("color", listOf(1.0, 0.1))
        assertEquals(0.0, shade(s, 0).lift, 1e-12, "a full press is the ink itself")
        assertTrue(shade(s, 1).lift > 0.4, "a light one is most of the way lifted")
        assertEquals(shade(s, 0).radius, shade(s, 1).radius, 1e-12, "width is untouched")
    }

    @Test
    fun `none leaves everything alone`() {
        val s = strokeWith("none", listOf(1.0, 0.02))
        assertEquals(shade(s, 0).radius, shade(s, 1).radius, 1e-12)
        assertEquals(shade(s, 0).alpha, shade(s, 1).alpha, 1e-12)
        assertEquals(0.0, shade(s, 1).lift, 1e-12)
    }

    /**
     * A sample with no pressure at all would be a ring of zero radius, which
     * is a pinch in the tube rather than a light touch. The floor is 0.02.
     */
    @Test
    fun `zero pressure still has a width`() {
        val s = strokeWith("size", listOf(0.0))
        assertTrue(shade(s, 0).radius > 0.0, "a zero-radius ring is a pinch, not a touch")
        assertEquals(shade(strokeWith("size", listOf(0.02)), 0).radius, shade(s, 0).radius, 1e-12)
    }

    /**
     * The sketch pencil is an opacity brush whatever the panel says: a pencil
     * that got WIDER under the hand would not read as a pencil.
     */
    @Test
    fun `a brush can insist on its own pressure target`() {
        val s = strokeWith("size", listOf(1.0, 0.1), brush = "sketch")
        assertEquals(shade(s, 0).radius, shade(s, 1).radius, 1e-12, "size was refused")
        assertTrue(shade(s, 1).alpha < shade(s, 0).alpha, "opacity was used instead")
    }

    /**
     * A brush that builds up cannot lay full strength in one pass, or a second
     * pass over the same ground would have nowhere to go.
     */
    @Test
    fun `a build-up brush deposits less than the chosen opacity`() {
        val plain = strokeWith("none", listOf(1.0))
        val pencil = strokeWith("none", listOf(1.0), brush = "sketch")
        assertEquals(1.0, shade(plain, 0).alpha, 1e-12)
        assertEquals(0.5, shade(pencil, 0).alpha, 1e-12)
    }

    /** And the geometry actually carries it, not just the shade function. */
    @Test
    fun `the built mesh is narrower where the press was lighter`() {
        val s = strokeWith("size", listOf(1.0, 1.0, 0.05, 0.05))
        val m = StrokeGeometry.build(s)
        assertTrue(m != null)
        // ring 0 is a full press, ring 3 a light one; compare their spread
        fun spread(ring: Int): Double {
            val seg = StrokeGeometry.segmentsFor(s)
            var lo = Double.MAX_VALUE
            var hi = -Double.MAX_VALUE
            for (j in 0 until seg) {
                val o = (2 + ring * seg + j) * 3 + 1      // the y of each vertex
                lo = minOf(lo, m.positions[o].toDouble())
                hi = maxOf(hi, m.positions[o].toDouble())
            }
            return hi - lo
        }
        assertTrue(spread(3) < spread(0) * 0.6, "${spread(3)} vs ${spread(0)}")
    }
}

/** The pressure target belongs to the stroke, so it has to survive a save. */
class PressureDocumentTest {

    @Test
    fun `a stroke keeps its pressure target through a round trip`() {
        val sketch = Sketch()
        for (target in listOf("size", "opacity", "both", "color", "none")) {
            val s = Stroke(brush = "pen")
            s.pressureTarget = target
            s.pts.add(StrokePoint(Vec3(0.0, 0.0, 0.0)))
            s.pts.add(StrokePoint(Vec3(0.1, 0.0, 0.0)))
            sketch.add(s)
        }
        val cam = Camera().apply { resize(800, 600) }
        val text = Document.toJsonText(sketch, GuideScene(), cam)

        val back = Sketch()
        assertTrue(Document.restore(text, back, GuideScene(), cam).ok)
        assertEquals(5, back.strokes.size)
        assertEquals(
            listOf("size", "opacity", "both", "color", "none"),
            back.strokes.map { it.pressureTarget },
        )
    }

    /** A file written before the field existed opens on the default. */
    @Test
    fun `a stroke with no pressure target reads as size`() {
        val old = """
        {"format":"plume","version":2,
         "strokes":[{"brush":"pen","color":"#000000","radius":0.007,
                     "pts":[0,0,0, 0.1,0,0]}]}
        """.trimIndent()
        val sk = Sketch()
        val r = Document.restore(old, sk, GuideScene(), Camera().apply { resize(800, 600) })
        assertTrue(r.ok)
        assertEquals("size", sk.strokes[0].pressureTarget)
    }
}
