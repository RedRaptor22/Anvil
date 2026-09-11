package art.plume.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The navigation globe: where the balls land, and what a tap on one means. */
class NavGizmoTest {

    private fun camAt(theta: Double, phi: Double): Triple<Vec3, Vec3, Vec3> {
        val cam = Camera()
        cam.resize(1000, 1000)
        cam.theta = theta
        cam.phi = phi
        cam.apply()
        val r = Vec3(); val u = Vec3(); val b = Vec3()
        cam.basis(r, u, b)
        return Triple(r, u, b)
    }

    private fun ball(balls: List<NavGizmo.Ball>, axis: Int, positive: Boolean) =
        assertNotNull(balls.firstOrNull { it.axis == axis && it.positive == positive })

    @Test
    fun `every ball is one of the camera's own views`() {
        val (r, u, b) = camAt(0.0, Math.PI / 2)
        val balls = NavGizmo.balls(r, u, b)
        assertEquals(6, balls.size)
        assertEquals(
            setOf("Right", "Left", "Top", "Bottom", "Front", "Back"),
            balls.map { it.view.name }.toSet(),
        )
    }

    /**
     * Looking down +Z (the Front view), +Z points at the eye and -Z away, so
     * their balls sit on the globe's centre and are told apart by depth alone.
     * X runs across the screen and Y up it.
     */
    @Test
    fun `from the front, the axes land where the screen puts them`() {
        val (r, u, b) = camAt(0.0, Math.PI / 2)
        val balls = NavGizmo.balls(r, u, b)

        val zp = ball(balls, NavGizmo.AXIS_Z, true)
        assertTrue(zp.depth > 0.99, "+Z should point at the eye, got ${zp.depth}")
        assertTrue(abs(zp.x) < 1e-9 && abs(zp.y) < 1e-9, "+Z should be dead centre")
        assertTrue(ball(balls, NavGizmo.AXIS_Z, false).depth < -0.99, "-Z away")

        /* +Y is up the screen, and screen y runs down, so its y is negative */
        assertTrue(ball(balls, NavGizmo.AXIS_Y, true).y < -0.99, "+Y up the screen")
        assertTrue(ball(balls, NavGizmo.AXIS_Y, false).y > 0.99, "-Y down it")
        /* the Right view sits on +X, so from the front +X is to the right */
        assertTrue(ball(balls, NavGizmo.AXIS_X, true).x > 0.99, "+X to the right")
        assertTrue(ball(balls, NavGizmo.AXIS_X, false).x < -0.99, "-X to the left")
    }

    /** Farthest first, so drawing down the list puts the near balls on top. */
    @Test
    fun `the balls come back back-to-front`() {
        val (r, u, b) = camAt(0.7, 1.1)
        val balls = NavGizmo.balls(r, u, b)
        for (i in 1 until balls.size) {
            assertTrue(
                balls[i].depth >= balls[i - 1].depth,
                "ball $i is nearer than the one before it",
            )
        }
        assertTrue(balls.last().inFront, "the last ball is the front-most")
    }

    /**
     * WHERE TWO BALLS OVERLAP, THE TAP GOES TO THE ONE YOU CAN SEE. An axis
     * aimed at the camera stacks its two balls on the same pixel, and the far
     * one is behind the globe.
     */
    @Test
    fun `a tap on stacked balls takes the near one`() {
        val (r, u, b) = camAt(0.0, Math.PI / 2)
        val balls = NavGizmo.balls(r, u, b)
        val hit = assertNotNull(NavGizmo.hit(balls, 0.0, 0.0, 0.3))
        assertEquals(NavGizmo.AXIS_Z, hit.axis)
        assertTrue(hit.positive, "the near ball is +Z, the one in front of the globe")
        assertEquals("Front", hit.view.name)
    }

    @Test
    fun `a tap on empty globe hits nothing`() {
        val (r, u, b) = camAt(0.0, Math.PI / 2)
        val balls = NavGizmo.balls(r, u, b)
        // between the axes, well clear of every ball
        assertTrue(NavGizmo.hit(balls, 0.6, 0.6, 0.2) == null, "nothing there to hit")
    }

    /** Tapping a ball aims the camera down that axis, to the last decimal. */
    @Test
    fun `aiming at a ball puts the eye on that axis`() {
        val (r, u, b) = camAt(0.9, 1.3)
        for (ball in NavGizmo.balls(r, u, b)) {
            val cam = Camera()
            cam.resize(1000, 1000)
            cam.theta = 0.9
            cam.phi = 1.3
            cam.applyOrthoView(ball.view)
            val eye = cam.eyeDirection(cam.theta, cam.phi)
            val want = Vec3(
                if (ball.axis == NavGizmo.AXIS_X) (if (ball.positive) 1.0 else -1.0) else 0.0,
                if (ball.axis == NavGizmo.AXIS_Y) (if (ball.positive) 1.0 else -1.0) else 0.0,
                if (ball.axis == NavGizmo.AXIS_Z) (if (ball.positive) 1.0 else -1.0) else 0.0,
            )
            /* Top and Bottom keep the azimuth on purpose, so they are within a
               hair of the pole rather than exactly on it */
            assertTrue(
                (eye dot want) > 0.999,
                "${ball.view.name} aimed ${eye} at an axis of $want",
            )
        }
    }
}
