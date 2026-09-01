package art.plume.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Moving a whole guide, which is what a long press with Select hands to the
 * joystick.
 *
 * The web build parks the transform on the guide's scene object and lets the
 * renderer compose it. This build has no scene graph, so the transform goes
 * into the data the guide is rebuilt from — and the property that has to hold
 * is that everything which READS a guide sees it in the new place. Painting,
 * the edge trim, the nearest-point clamp and the occlusion mask all work off
 * `surface`, so it is `surface` these tests measure.
 */
class GuideTransformTest {

    private val viewDir = Vec3(0.0, 0.0, -1.0)
    private val camRight = Vec3(1.0, 0.0, 0.0)

    private fun circleStroke(r: Double = 0.3, n: Int = 32): List<Vec3> =
        (0 until n).map {
            val a = it.toDouble() / (n - 1) * 2 * PI
            Vec3(cos(a) * r, sin(a) * r, 0.0)
        }

    private fun sweptGuide(): Guide =
        assertNotNull(Guides.createFromStroke(circleStroke(), viewDir, camRight, 4.0))

    private fun flatGuide(): Guide =
        assertNotNull(Guides.createFlatFromStroke(circleStroke(), viewDir, camRight))

    /**
     * Positions are stored as floats, so a metre-scale coordinate carries
     * about a ten-millionth of a metre of representation error. Every
     * tolerance here is a MICRON — three orders of magnitude under anything
     * that could be seen, and still far above the noise.
     */
    private val micron = 1e-6

    private fun bounds(g: Guide): Bounds {
        val b = Bounds()
        val p = assertNotNull(g.surface).positions
        var i = 0
        while (i + 2 < p.size) {
            b.add(Vec3(p[i].toDouble(), p[i + 1].toDouble(), p[i + 2].toDouble()))
            i += 3
        }
        return b
    }

    @Test
    fun `moving a swept guide moves its surface`() {
        val g = sweptGuide()
        val was = bounds(g).centre()
        val d = Vec3(0.2, -0.1, 0.05)
        assertTrue(GuideTransform.apply(g, Mat4.translation(d.x, d.y, d.z, Mat4())))
        val now = bounds(g).centre()
        assertTrue(
            now.distanceTo(was + d) < micron,
            "the surface moved to $now, not ${was + d}",
        )
    }

    @Test
    fun `moving a flat guide moves its surface`() {
        val g = flatGuide()
        val was = bounds(g).centre()
        val d = Vec3(-0.3, 0.4, 0.1)
        assertTrue(GuideTransform.apply(g, Mat4.translation(d.x, d.y, d.z, Mat4())))
        assertTrue(bounds(g).centre().distanceTo(was + d) < micron)
    }

    @Test
    fun `a guide can be moved back exactly where it was`() {
        /* What undo needs. The drag is accumulated as one matrix and replayed
           inverted, so applying a transform and then its inverse has to leave
           the surface where it started — not merely near it, since a guide is
           what strokes are anchored to. */
        val g = sweptGuide()
        val before = assertNotNull(g.surface).positions.copyOf()

        val m = Mat4()
        Mat4.multiply(
            Mat4.translation(0.4, 0.1, -0.2, Mat4()),
            Mat4.rotationY(0.6, Mat4()),
            m,
        )
        val inv = Mat4()
        assertTrue(Mat4.invert(m, inv))

        assertTrue(GuideTransform.apply(g, m))
        assertTrue(GuideTransform.apply(g, inv))

        val after = assertNotNull(g.surface).positions
        var worst = 0.0
        for (i in before.indices) {
            val d = abs(before[i] - after[i]).toDouble()
            if (d > worst) worst = d
        }
        assertTrue(worst < micron, "the guide came back ${worst / MM}mm out")
    }

    @Test
    fun `a turned guide keeps its size`() {
        /* A rotation must not stretch anything: the surface's own extent is a
           property of the guide, and Fill lays rows at chosen distances across
           it. */
        val g = sweptGuide()
        val wasRadius = bounds(g).radius()
        val wasU = assertNotNull(g.surface).lu
        assertTrue(GuideTransform.apply(g, Mat4.rotationZ(PI / 2, Mat4())))
        assertTrue(
            abs(bounds(g).radius() - wasRadius) < micron,
            "a turn changed the guide's size",
        )
        assertTrue(
            abs(assertNotNull(g.surface).lu - wasU) < micron,
            "a turn changed how far paint can run across it",
        )
    }

    @Test
    fun `scaling a guide scales what can be painted on it`() {
        val g = sweptGuide()
        val srf = assertNotNull(g.surface)
        val wasU = srf.lu
        val wasDepth = assertNotNull(g.sweep).depth
        assertTrue(GuideTransform.apply(g, Mat4.scale(2.0, 2.0, 2.0, Mat4())))
        val now = assertNotNull(g.surface)
        assertTrue(abs(now.lu - wasU * 2) < micron, "the surface's own extent did not scale")
        assertTrue(abs(assertNotNull(g.sweep).depth - wasDepth * 2) < 1e-9)
    }

    @Test
    fun `a moved guide still takes paint, in the new place`() {
        /* The point of the whole thing. A ray aimed where the guide now IS has
           to hit it, and one aimed where it used to be must not.
           Aimed ACROSS the extrusion, not along it: a swept guide is the
           drawn outline carried along the view direction, so it is a tube
           wall, and a ray down its axis passes through the hollow middle. */
        val g = sweptGuide()
        val wasY = bounds(g).centre().y
        assertTrue(GuideTransform.apply(g, Mat4.translation(0.0, 1.5, 0.0, Mat4())))
        val c = bounds(g).centre()

        val hit = GuidePainting.project(
            g, Ray(Vec3(c.x + 5.0, c.y, c.z), Vec3(-1.0, 0.0, 0.0)), clampOffSurface = false,
        )
        assertNotNull(hit, "the moved guide did not take paint where it is")

        val miss = GuidePainting.project(
            g, Ray(Vec3(c.x + 5.0, wasY, c.z), Vec3(-1.0, 0.0, 0.0)), clampOffSurface = false,
        )
        assertTrue(miss == null, "the guide still took paint where it used to be")
    }

    @Test
    fun `the sweep frame stays square through a turn`() {
        /* `local` is written in the anchor frame and carried along the path by
           transported frames. If the two basis vectors stop being square the
           frames shear and the rebuilt surface is not the one that was moved. */
        val g = sweptGuide()
        assertTrue(GuideTransform.apply(g, Mat4.rotationY(0.9, Mat4())))
        val s = assertNotNull(g.sweep)
        assertTrue(abs(s.basisR dot s.basisT) < 1e-9, "the anchor frame sheared")
        assertTrue(abs(s.basisR.length() - 1.0) < 1e-9)
        assertTrue(abs(s.basisT.length() - 1.0) < 1e-9)
    }

    @Test
    fun `a guide with no source data says so rather than pretending`() {
        val g = Guide(id = 99, kind = GuideKind.MODEL)
        assertTrue(!GuideTransform.apply(g, Mat4.translation(1.0, 0.0, 0.0, Mat4())))
    }
}
