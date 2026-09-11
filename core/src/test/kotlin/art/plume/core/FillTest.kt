package art.plume.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Filling a guide.
 *
 * The rule under test is that a fill is the GUIDE'S shape in a colour, and
 * owes nothing to the brush that happened to be in the hand when it was
 * asked for. That had been the other way round: the fill took the held
 * brush, so the same guide filled as grain under the pencil, as light under
 * glow, and as a ragged outline under taper.
 */
class FillTest {

    private val viewDir = Vec3(0.0, 0.0, -1.0)
    private val camRight = Vec3(1.0, 0.0, 0.0)

    private fun flatGuide(): Guide {
        val loop = (0 until 32).map {
            val a = it.toDouble() / 31 * 2 * PI
            Vec3(cos(a) * 0.4, sin(a) * 0.4, 0.0)
        }
        return assertNotNull(Guides.createFlatFromStroke(loop, viewDir, camRight))
    }

    private fun proto(brush: String) = Stroke(
        brush = brush,
        color = Rgba(0.2, 0.4, 0.6),
        baseRadius = 6.0 * MM * 0.5,
        opacity = 0.7,
    )

    private fun filled(brush: String): List<Stroke> {
        val r = Fill.fillGuide(flatGuide(), proto(brush))
        assertTrue(r is Fill.Result.Filled, "expected a fill for brush $brush")
        return (r as Fill.Result.Filled).strokes
    }

    @Test
    fun `a fill is laid with the fill nib, not the brush in the hand`() {
        for (held in listOf("pen", "sketch", "glow", "taper", "cube", "wide")) {
            for (s in filled(held)) {
                assertEquals(
                    Fill.BRUSH, s.brush,
                    "filling with $held should still lay the fill nib",
                )
            }
        }
    }

    /**
     * The brush decided the row PITCH as well as the mark, so two brushes at
     * one size filled the same guide with different numbers of rows. That is
     * the half of the fault a check on the name alone would not have seen.
     */
    @Test
    fun `every brush fills one guide the same way`() {
        val rows = listOf("pen", "sketch", "glow", "taper", "cube", "wide", "flat")
            .map { filled(it).size }
        assertTrue(
            rows.toSet().size == 1,
            "one guide, one fill, whatever the brush — got $rows",
        )
    }

    /** What the caller DOES choose still arrives. */
    @Test
    fun `colour, size and opacity are still the caller's`() {
        val p = proto("sketch")
        for (s in filled("sketch")) {
            assertEquals(p.color, s.color)
            assertEquals(p.baseRadius, s.baseRadius)
            assertEquals(p.opacity, s.opacity)
        }
    }

    /**
     * A fill that came out glowing was a fill you could not read as paint.
     * The material follows from the nib when nothing has been chosen, so
     * substituting the nib is what settles this too.
     */
    @Test
    fun `a fill under the glow brush is paint, not light`() {
        for (s in filled("glow")) {
            assertEquals(Material.SHADED, s.materialOf)
            assertTrue(!s.cfg.glow)
        }
    }

    /** Rows are the guide's, so they land on it and carry its normals. */
    @Test
    fun `the rows lie on the guide that was filled`() {
        val g = flatGuide()
        val r = Fill.fillGuide(g, proto("sketch"))
        val out = (r as Fill.Result.Filled).strokes
        assertTrue(out.isNotEmpty())
        for (s in out) {
            assertEquals(g.id, s.guideId)
            assertTrue(s.pts.size >= 2)
            for (q in s.pts) assertNotNull(q.nrm)
        }
    }
}
