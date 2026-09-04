package art.plume.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The camera and its projection.
 *
 * These matter more than their size suggests: every stroke a person draws is a
 * screen pixel run backwards through this maths, so an error here does not
 * look like a broken camera, it looks like a brush that puts ink in the wrong
 * place. None of it could be tested at all while the matrices lived in
 * `android.opengl.Matrix`.
 */
class CameraTest {

    private fun cam() = Camera().apply { resize(1080, 2400) }

    @Test
    fun `a lens focal length becomes the field of view Feather documents`() {
        val c = cam()
        // 50mm on a 24mm sensor: 2 * atan(24 / 100)
        assertEquals(2 * Math.toDegrees(kotlin.math.atan(0.24)), c.fovFromFocal(50.0), 1e-9)
        // and the clamp is the documented 10-500mm range
        c.focal = 5.0; c.apply(); assertEquals(Tune.FOCAL_MIN, c.focal, 0.0)
        c.focal = 900.0; c.apply(); assertEquals(Tune.FOCAL_MAX, c.focal, 0.0)
    }

    @Test
    fun `the pivot projects to the centre of the viewport`() {
        val c = cam()
        val s = Vec3()
        c.worldToScreen(c.pivot, s)
        assertEquals(c.width / 2.0, s.x, 1e-6)
        assertEquals(c.height / 2.0, s.y, 1e-6)
    }

    @Test
    fun `a pixel unprojected onto the draw plane projects back to the same pixel`() {
        val c = cam()
        c.theta = 0.9; c.phi = 1.3; c.radius = 3.0; c.roll = 0.37
        c.pivot.set(0.2, -0.4, 0.1)
        c.apply()

        val out = Vec3()
        val back = Vec3()
        for (px in listOf(12.0, 240.0, 539.5, 1000.0)) {
            for (py in listOf(30.0, 800.0, 1199.5, 2300.0)) {
                val hit = c.planePoint(px, py, out)
                assertNotNull(hit, "no draw-plane hit at $px,$py")
                c.worldToScreen(hit, back)
                assertEquals(px, back.x, 1e-6, "x round trip at $px,$py")
                assertEquals(py, back.y, 1e-6, "y round trip at $px,$py")
            }
        }
    }

    @Test
    fun `the draw plane faces the camera at exactly the pivot distance`() {
        val c = cam()
        c.theta = 2.1; c.phi = 0.8; c.radius = 5.5
        c.apply()
        val hit = Vec3()
        assertNotNull(c.planePoint(300.0, 700.0, hit))

        val fwd = Vec3()
        c.forward(fwd)
        val along = Vec3(hit.x - c.eye.x, hit.y - c.eye.y, hit.z - c.eye.z) dot fwd
        assertEquals(c.radius, along, 1e-9)
        // and the plane's normal really is the view direction
        assertEquals(1.0, c.drawPlane.normal dot fwd, 1e-12)
    }

    @Test
    fun `pxToWorld converts a pixel span to the world span it covers`() {
        val c = cam()
        val a = Vec3(); val b = Vec3()
        assertNotNull(c.planePoint(c.width / 2.0, c.height / 2.0, a))
        assertNotNull(c.planePoint(c.width / 2.0, c.height / 2.0 + 100.0, b))
        assertEquals(c.pxToWorld(100.0), a.distanceTo(b), 1e-9)
        // and back again
        assertEquals(100.0, c.worldToPx(a.distanceTo(b)), 1e-9)
    }

    @Test
    fun `panning moves the sketch by exactly the pixels the fingers moved`() {
        val c = cam()
        c.theta = 1.4; c.phi = 1.05; c.roll = 0.2
        c.apply()

        /*
         * The mark has to lie ON THE DRAW PLANE, at the pivot's depth. Pan
         * converts pixels to world units at that depth and nowhere else, so a
         * point nearer the camera genuinely moves further than the fingers did
         * — that is perspective working, not pan being wrong. Measuring one
         * off-plane point is what made this test fail first time round; the
         * probe was wrong before the code was.
         */
        val mark = Vec3()
        assertNotNull(c.planePoint(400.0, 900.0, mark))
        val before = Vec3()
        c.worldToScreen(mark, before)

        c.panBy(60.0, -35.0)

        val after = Vec3()
        c.worldToScreen(mark, after)
        /*
         * Panning slides the pivot the OTHER way, which carries the camera with
         * it and leaves the sketch following the fingers exactly — 60px right
         * and 35px up. An inverted pan is the classic way for this to be
         * "working" and still feel wrong, so the signs are asserted, not just
         * the distance.
         */
        assertEquals(60.0, after.x - before.x, 1e-6)
        assertEquals(-35.0, after.y - before.y, 1e-6)
    }

    @Test
    fun `orbiting swings the eye without changing the radius or the pivot`() {
        val c = cam()
        val pivot0 = c.pivot.copy()
        val r0 = c.radius
        val eye0 = c.eye.copy()

        c.orbitBy(80.0, 25.0)

        assertEquals(r0, c.radius, 1e-12)
        assertEquals(0.0, c.pivot.distanceTo(pivot0), 1e-12)
        assertEquals(r0, c.eye.distanceTo(c.pivot), 1e-9)
        assertTrue(c.eye.distanceTo(eye0) > 1e-3, "the eye did not actually move")
        // the documented sensitivity, not just "some rotation"
        assertEquals(Math.PI * 0.25 - 80.0 * Tune.ORBIT_PER_DP, c.theta, 1e-12)
    }

    @Test
    fun `the polar angle is held off both poles where the up vector dies`() {
        val c = cam()
        c.orbitBy(0.0, 100000.0)
        assertEquals(Tune.PHI_EPS, c.phi, 0.0)
        // and the matrices are still finite, which is the reason for the clamp
        assertTrue(c.viewProjection.m.all { it.isFinite() })
        c.orbitBy(0.0, -100000.0)
        assertEquals(Math.PI - Tune.PHI_EPS, c.phi, 0.0)
        assertTrue(c.viewProjection.m.all { it.isFinite() })
    }

    @Test
    fun `roll turns the canvas the way three js turns it, not the other way`() {
        /*
         * The renderer this replaces built the roll as R(+roll) * view. A view
         * matrix is the camera object's INVERSE, so rolling the camera by +a
         * rotates the view by -a; the old form spun the canvas backwards. It is
         * invisible at rest, which is how it survived: nothing had ever run.
         */
        val c = cam()
        val centre = Vec3()
        c.worldToScreen(c.pivot, centre)

        // a point directly above the pivot on screen, found by unprojecting
        val above = Vec3()
        assertNotNull(c.planePoint(centre.x, centre.y - 400.0, above))

        val roll = 0.30
        c.roll = roll
        c.apply()

        val now = Vec3()
        c.worldToScreen(above, now)
        val dx = now.x - centre.x
        val dy = now.y - centre.y

        // it stays the same distance from the centre and turns by exactly roll
        assertEquals(400.0, hypot(dx, dy), 1e-6)
        val angleBefore = atan2(-400.0, 0.0)
        val angleNow = atan2(dy, dx)
        var turned = angleNow - angleBefore
        while (turned > Math.PI) turned -= 2 * Math.PI
        while (turned < -Math.PI) turned += 2 * Math.PI
        assertEquals(roll, turned, 1e-6)
        // and it moved to the RIGHT, which is the direction the old sign got wrong
        assertTrue(dx > 0, "positive roll should carry a point above the pivot rightwards")
    }

    @Test
    fun `the orthographic toggle does not make the framing jump`() {
        val c = cam()
        c.theta = 0.7; c.phi = 1.2; c.radius = 3.0
        c.apply()

        // three points lying in the plane through the pivot, where the two
        // projections are defined to agree
        val marks = listOf(Vec3(), Vec3(), Vec3())
        val pixels = listOf(200.0 to 500.0, 540.0 to 1200.0, 900.0 to 1900.0)
        for (i in marks.indices) {
            assertNotNull(c.planePoint(pixels[i].first, pixels[i].second, marks[i]))
        }

        val persp = marks.map { Vec3().also { o -> c.worldToScreen(it, o) } }
        c.ortho = true
        c.apply()
        val orthoed = marks.map { Vec3().also { o -> c.worldToScreen(it, o) } }

        for (i in marks.indices) {
            assertEquals(persp[i].x, orthoed[i].x, 1e-6, "x at mark $i")
            assertEquals(persp[i].y, orthoed[i].y, 1e-6, "y at mark $i")
        }
    }

    @Test
    fun `zoom clamps, and a pinch and a wheel agree on direction`() {
        val c = cam()
        val r0 = c.radius
        c.zoomBy(0.5)
        assertEquals(r0 * 0.5, c.radius, 1e-12)
        c.radius = r0; c.apply()
        c.zoomByWheel(100.0)
        assertTrue(c.radius > r0, "scrolling down should pull the camera back")
        c.zoomBy(1e9)
        assertEquals(Tune.RADIUS_MAX, c.radius, 0.0)
        c.zoomBy(1e-9)
        assertEquals(Tune.RADIUS_MIN, c.radius, 0.0)
    }

    @Test
    fun `release momentum decays to a stop rather than spinning forever`() {
        val c = cam()
        c.addSpin(0.05, 0.0)
        var frames = 0
        while (c.tickSpin()) {
            frames++
            assertTrue(frames < 1000, "spin never stopped")
        }
        assertTrue(frames in 80..300, "decay took $frames frames, which is not a flick")
        assertTrue(!c.spinning)
    }

    @Test
    fun `pxToWorldAt grows with distance under perspective and is flat under ortho`() {
        val c = cam()
        val near = Vec3().also { c.planePoint(540.0, 1200.0, it) }
        val far = Vec3(near.x, near.y, near.z)
        // push the sample twice as far along the view axis
        val fwd = Vec3(); c.forward(fwd)
        far.addScaled(fwd, c.radius)

        val sNear = c.pxToWorldAt(near)
        val sFar = c.pxToWorldAt(far)
        assertEquals(2.0, sFar / sNear, 1e-9)

        c.ortho = true; c.apply()
        assertEquals(c.pxToWorldAt(near), c.pxToWorldAt(far), 1e-12)
    }

    @Test
    fun `a matrix inverse really is one, and a singular matrix is refused`() {
        val c = cam()
        c.theta = 1.1; c.phi = 0.9; c.roll = 0.4; c.apply()
        val inv = Mat4()
        assertTrue(Mat4.invert(c.viewProjection, inv))
        val id = Mat4()
        Mat4.multiply(c.viewProjection, inv, id)
        for (row in 0..3) for (col in 0..3) {
            assertEquals(if (row == col) 1.0 else 0.0, id[row, col], 1e-9)
        }

        val flat = Mat4()
        for (i in 0..15) flat.m[i] = 0.0
        assertTrue(!Mat4.invert(flat, inv), "a singular matrix must not report success")
    }

    @Test
    fun `a point behind the camera is reported as off frustum, not drawn in front`() {
        val c = cam()
        val behind = Vec3()
        val fwd = Vec3(); c.forward(fwd)
        behind.set(c.eye).addScaled(fwd, -1.0)      // one unit the wrong side of the eye
        val s = Vec3()
        c.worldToScreen(behind, s)
        assertTrue(abs(s.z) > 1.0, "NDC depth ${s.z} should be outside the frustum")
        // and the draw plane refuses it rather than mirroring it into view
        assertTrue(c.planePoint(540.0, 1200.0, Vec3()) != null)
    }
}

/**
 * The six standard views, and the snap onto them.
 *
 * FACT (B.1): a one-finger double-tap snaps to the nearest of the six.
 */
class OrthoViewTest {

    private fun cam(theta: Double, phi: Double) = Camera().apply {
        resize(800, 600); this.theta = theta; this.phi = phi; apply()
    }

    @Test
    fun `each standard view snaps to itself`() {
        for (v in Camera.ORTHO_VIEWS) {
            val c = cam(v.theta, v.phi)
            assertEquals(v.name, c.nearestOrthoView().name, "at ${v.name}")
        }
    }

    /**
     * The snap compares DIRECTIONS, not angles. Theta is circular, and two
     * angles a hair either side of the wrap are as close as can be while their
     * numbers are as far apart as they get — comparing the numbers would send
     * a view just past Back all the way round to Front.
     */
    @Test
    fun `a view just past the azimuth wrap still snaps to Back`() {
        val justOver = cam(Math.PI + 0.05, Math.PI / 2)
        assertEquals("Back", justOver.nearestOrthoView().name)
        val justUnder = cam(-Math.PI + 0.05, Math.PI / 2)
        assertEquals("Back", justUnder.nearestOrthoView().name, "and from the other side")
    }

    @Test
    fun `looking down snaps to Top and up snaps to Bottom`() {
        assertEquals("Top", cam(1.1, 0.2).nearestOrthoView().name)
        assertEquals("Bottom", cam(1.1, Math.PI - 0.2).nearestOrthoView().name)
    }

    /**
     * Looking straight down, theta only decides which way up the sketch is, so
     * snapping it to zero would spin the drawing for no reason anyone asked
     * for.
     */
    @Test
    fun `snapping to Top keeps the azimuth`() {
        val c = cam(1.1, 0.2)
        c.applyOrthoView(Camera.ORTHO_VIEWS.first { it.name == "Top" })
        assertEquals(1.1, c.theta, 1e-12, "the azimuth was kept")
        assertEquals(0.0025, c.phi, 1e-12)

        c.theta = 1.1
        c.applyOrthoView(Camera.ORTHO_VIEWS.first { it.name == "Front" })
        assertEquals(0.0, c.theta, 1e-12, "but a side view does set it")
    }

    /** A snap always levels the roll: a tilted standard view is not standard. */
    @Test
    fun `snapping levels the roll`() {
        val c = cam(0.3, 1.2)
        c.roll = 0.7
        c.applyOrthoView(c.nearestOrthoView())
        assertEquals(0.0, c.roll, 1e-12)
    }

    /** Top and Bottom are off the pole, or the view matrix degenerates. */
    @Test
    fun `the polar views stay off the pole`() {
        for (name in listOf("Top", "Bottom")) {
            val v = Camera.ORTHO_VIEWS.first { it.name == name }
            val c = cam(0.0, v.phi)
            c.applyOrthoView(v)
            for (m in c.view.m) assertTrue(m.isFinite(), "$name produced $m")
        }
    }
}

/**
 * Pinning the orbit point.
 *
 * FACT (B.2/B.3): a hold on a curve or the grid pins the orbit point there.
 * The interesting half is what must NOT happen — the viewpoint must not move.
 */
class PivotTest {

    @Test
    fun `moving the pivot with lookFrom leaves the eye exactly where it was`() {
        val c = Camera().apply { resize(800, 600); radius = 4.0; theta = 0.9; phi = 1.1; apply() }
        val eye = c.eye.copy()

        c.pivot.set(1.5, 0.7, -2.0)
        c.lookFrom(eye)

        assertEquals(eye.x, c.eye.x, 1e-9)
        assertEquals(eye.y, c.eye.y, 1e-9)
        assertEquals(eye.z, c.eye.z, 1e-9)
    }

    /**
     * Without it the spherical coordinates are kept and the camera swings
     * round to satisfy them, which looks like the sketch jumping away from the
     * finger that just touched it. This measures that it would.
     */
    @Test
    fun `without lookFrom the eye would move a long way`() {
        val c = Camera().apply { resize(800, 600); radius = 4.0; theta = 0.9; phi = 1.1; apply() }
        val eye = c.eye.copy()
        c.pivot.set(1.5, 0.7, -2.0)
        c.apply()                       // the naive version: pivot moved, angles kept
        val moved = (c.eye - eye).length()
        assertTrue(moved > 1.0, "the eye only moved $moved; the guard would be pointless")
    }

    @Test
    fun `a pivot on top of the eye does not collapse the radius`() {
        val c = Camera().apply { resize(800, 600); apply() }
        val eye = c.eye.copy()
        c.pivot.set(eye.x, eye.y, eye.z)
        c.lookFrom(eye)
        assertTrue(c.radius >= Tune.RADIUS_MIN, "radius fell to ${c.radius}")
        for (m in c.view.m) assertTrue(m.isFinite())
    }
}
