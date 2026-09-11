package art.plume.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WHICH CURVES THE RENDERER MAY PULL TOWARDS THE EYE BY THE ANGLE THEY SIT AT.
 *
 * [Stroke.decal] decides whether a curve gets a SLOPE-scaled depth offset, and
 * a slope-scaled offset is the one that can move a curve a long way: it grows
 * with how fast depth changes across a pixel, so at a grazing angle it stops
 * being a tie-break and becomes a shove towards the camera.
 *
 * The rule used to be `paint`, which six of the eight brushes carry — a
 * pencil, a cube and a three-millimetre ribbon among them. So a tube drawn
 * with any of those had its whole rim pulled forward, and the far wall came
 * through the near one at exactly the angles where the rim is steepest. That
 * is the bleed-through this exists to keep out, which is why this is a test
 * about which brushes are EXCLUDED as much as which are let in.
 */
class DecalTest {

    private fun stroke(brush: String, onGuide: Boolean) =
        Stroke(brush = brush).also { if (onGuide) it.guideId = 7 }

    @Test
    fun `a ribbon painted onto a guide is a decal`() {
        assertTrue(stroke("flat", onGuide = true).decal)
    }

    @Test
    fun `the same ribbon in free space is not`() {
        assertFalse(
            stroke("flat", onGuide = false).decal,
            "with no surface under it there is nothing to be coplanar with",
        )
    }

    @Test
    fun `nothing with an inside is a decal, on a guide or off it`() {
        /* every brush that is not a blade: a round nib, a tapered one, a
           square section, a cube, and the pencil — all of which are tubes with
           a silhouette, and a silhouette is where the slope is steepest */
        for (brush in listOf("pen", "taper", "rectangle", "cube", "sketch", "glow")) {
            assertFalse(
                stroke(brush, onGuide = true).decal,
                "$brush has thickness and must not get the slope offset",
            )
        }
    }

    @Test
    fun `a ribbon that stands proud of the surface is not a decal either`() {
        /* `wide` is as thin as `flat` but rises 3mm off the guide, and 3mm is
           precisely the separation a decal does not have */
        assertFalse(
            stroke("wide", onGuide = true).decal,
            "a raised ribbon is not coplanar with what it was painted on",
        )
    }

    @Test
    fun `exactly one of the shipped brushes is a decal`() {
        val decals = Brushes.table.keys.filter { stroke(it, onGuide = true).decal }
        assertTrue(
            decals == listOf("flat"),
            "the decal set is $decals; widening it is what caused the bleed-through",
        )
    }

    @Test
    fun `a curve still under the pen answers the same way`() {
        /* the live preview has no stroke id yet, so it asks the brush directly
           — and it has to get the same answer or the curve jumps on pen-up */
        val flat = Brushes.resolve("flat")
        assertTrue(flat.isDecal(onSurface = true))
        assertFalse(flat.isDecal(onSurface = false))
        assertFalse(Brushes.resolve("wide").isDecal(onSurface = true))
    }
}
