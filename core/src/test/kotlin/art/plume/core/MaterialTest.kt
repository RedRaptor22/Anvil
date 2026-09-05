package art.plume.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FACT: four materials — Shadeless "does not respond to lighting or cast
 * shadows", Shaded "responds to lighting and casts shadows", Glow "does not
 * respond to lighting, does not cast shadows, and patterns cannot be applied",
 * Cutout "responds to the background". And five patterns, refused by Glow and
 * Cutout.
 */
class MaterialTest {

    private fun stroke(brush: String = "pen") = Stroke(brush = brush).also {
        it.pts.add(StrokePoint(Vec3(0.0, 0.0, 0.0)))
        it.pts.add(StrokePoint(Vec3(1.0, 0.0, 0.0)))
    }

    @Test
    fun `a curve nobody has given a material follows its brush`() {
        val plain = stroke()
        assertNull(plain.material, "nothing is stored until something is chosen")
        assertEquals(Material.SHADED, plain.materialOf)

        /* the whole identity of the glow brush is that it glows, so it brings
           its material with it and nobody has to know that */
        assertEquals(Material.GLOW, stroke("glow").materialOf)
    }

    @Test
    fun `choosing one overrides the brush, and only then is it stored`() {
        val s = stroke("glow")
        s.material = Material.SHADED
        assertEquals(Material.SHADED, s.materialOf, "a glow brush can draw solid curves")

        val back = stroke()
        back.material = Material.GLOW
        assertEquals(Material.GLOW, back.materialOf, "and a pen can draw glowing ones")
    }

    @Test
    fun `glow and cutout refuse a pattern, the other two take it`() {
        assertTrue(Material.takesPattern(Material.SHADED))
        assertTrue(Material.takesPattern(Material.SHADELESS))
        assertFalse(Material.takesPattern(Material.GLOW))
        assertFalse(Material.takesPattern(Material.CUTOUT))

        val s = stroke()
        s.pattern = Pattern.CROSS
        assertTrue(s.patterned)
        /* the pattern is REMEMBERED rather than cleared: switch back to a
           material that takes one and it is the pattern you chose, not none */
        s.material = Material.GLOW
        assertFalse(s.patterned)
        assertEquals(Pattern.CROSS, s.pattern)
        s.material = Material.SHADELESS
        assertTrue(s.patterned)
    }

    @Test
    fun `only shaded casts a shadow`() {
        assertTrue(Material.castsShadow(Material.SHADED))
        assertFalse(Material.castsShadow(Material.SHADELESS))
        assertFalse(Material.castsShadow(Material.GLOW))
        assertFalse(Material.castsShadow(Material.CUTOUT))
    }

    @Test
    fun `a restyle changes the whole selection, a mixture reports as none`() {
        val a = stroke(); val b = stroke()
        a.material = Material.SHADED
        b.material = Material.CUTOUT
        assertNull(Selection.styleOf(listOf(a, b))?.material, "they do not agree")

        Selection.restyle(listOf(a, b), StyleChange(material = Material.SHADELESS))
        assertEquals(Material.SHADELESS, Selection.styleOf(listOf(a, b))?.material)
    }

    @Test
    fun `two curves that have never been given one still agree`() {
        /* the panel asks what the selection is made of, and "both of these
           follow the pen brush" is an answer, not a disagreement */
        val style = Selection.styleOf(listOf(stroke(), stroke()))
        assertEquals(Material.SHADED, style?.material)
    }

    @Test
    fun `a copy is made of the same stuff`() {
        val s = stroke()
        s.material = Material.SHADELESS
        s.pattern = Pattern.STIPPLE
        s.patternIntensity = 0.9
        s.patternAngle = 1.2
        s.patternContrast = 0.3

        /* erasing splits a curve into the pieces that survived, and pieces
           that came back plain would be a fault every time you erased */
        val copy = s.copyStroke()
        assertEquals(Material.SHADELESS, copy.material)
        assertEquals(Pattern.STIPPLE, copy.pattern)
        assertEquals(0.9, copy.patternIntensity, 1e-12)
        assertEquals(1.2, copy.patternAngle, 1e-12)
        assertEquals(0.3, copy.patternContrast, 1e-12)
    }

    @Test
    fun `a pattern number from the future does not draw something random`() {
        assertEquals(Pattern.NONE, Pattern.sanitize(97))
        assertEquals(Pattern.NONE, Pattern.sanitize(-3))
        assertEquals(Pattern.TERRAZZO, Pattern.sanitize(Pattern.TERRAZZO))
    }
}
