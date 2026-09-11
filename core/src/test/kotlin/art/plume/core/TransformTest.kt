package art.plume.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The transform gizmo's arithmetic.
 *
 * The maths is here rather than beside the widget because every one of these
 * is a claim that can be checked, and two of them are the difference between
 * a usable gizmo and one that throws the drawing off screen.
 */
class TransformTest {

    private fun cam() = Camera().apply {
        resize(800, 600); radius = 4.0; theta = 0.6; phi = 1.1; apply()
    }

    /** transformPoint returns the w it divided by; the point lands in `out`. */
    private fun apply(m: Mat4, p: Vec3): Vec3 = Vec3().also { m.transformPoint(p, it) }

    // ---- about -------------------------------------------------------------

    /**
     * A rotation must turn the selection about ITS OWN centre. About the world
     * origin instead, a sketch drawn ten metres out is thrown across the scene
     * rather than turned in place — invisible for a sketch near zero, which is
     * exactly why it needs a test.
     */
    @Test
    fun `about leaves its own centre fixed`() {
        val c = Vec3(10.0, 2.0, -5.0)
        val m = Transform.about(c, Transform.rotationAxis(Vec3(0.0, 1.0, 0.0), 0.7))
        val moved = apply(m, c)
        assertEquals(c.x, moved.x, 1e-9)
        assertEquals(c.y, moved.y, 1e-9)
        assertEquals(c.z, moved.z, 1e-9)
    }

    @Test
    fun `without about the same rotation throws a distant point across the scene`() {
        val c = Vec3(10.0, 2.0, -5.0)
        val naive = Transform.rotationAxis(Vec3(0.0, 1.0, 0.0), 0.7)
        val moved = apply(naive, c)
        assertTrue((moved - c).length() > 5.0, "only moved ${(moved - c).length()}")
    }

    // ---- rotationAxis ------------------------------------------------------

    @Test
    fun `a quarter turn about Y sends +X to -Z`() {
        val m = Transform.rotationAxis(Vec3(0.0, 1.0, 0.0), PI / 2)
        val p = apply(m, Vec3(1.0, 0.0, 0.0))
        assertEquals(0.0, p.x, 1e-9)
        assertEquals(0.0, p.y, 1e-9)
        assertEquals(-1.0, p.z, 1e-9)
    }

    @Test
    fun `a rotation preserves length`() {
        val m = Transform.rotationAxis(Vec3(0.3, 0.9, -0.2), 1.3)
        val p = Vec3(1.0, -2.0, 3.0)
        assertEquals(p.length(), apply(m, p).length(), 1e-9)
    }

    @Test
    fun `an axis need not arrive normalised`() {
        val a = Transform.rotationAxis(Vec3(0.0, 5.0, 0.0), 0.4)
        val b = Transform.rotationAxis(Vec3(0.0, 1.0, 0.0), 0.4)
        for (i in 0..15) assertEquals(b.m[i], a.m[i], 1e-12)
    }

    // ---- axisScale ---------------------------------------------------------

    /**
     * Scaling along one axis must leave the other two ALONE. A uniform scale
     * where an axis scale was asked for is a squash of the whole selection.
     */
    @Test
    fun `axis scale stretches one direction and no other`() {
        val m = Transform.axisScale(Vec3(1.0, 0.0, 0.0), 2.0)
        assertEquals(2.0, apply(m, Vec3(1.0, 0.0, 0.0)).x, 1e-9)
        assertEquals(1.0, apply(m, Vec3(0.0, 1.0, 0.0)).y, 1e-9, "Y untouched")
        assertEquals(1.0, apply(m, Vec3(0.0, 0.0, 1.0)).z, 1e-9, "Z untouched")
    }

    @Test
    fun `axis scale works on a diagonal axis`() {
        val a = Vec3(1.0, 1.0, 0.0).normalize()
        val m = Transform.axisScale(a, 3.0)
        val along = apply(m, Vec3(a.x, a.y, a.z))
        assertEquals(3.0, along.length(), 1e-9, "three times along the axis")
        val across = apply(m, Vec3(-a.y, a.x, 0.0))
        assertEquals(1.0, across.length(), 1e-9, "and unchanged across it")
    }

    // ---- axisOnScreen ------------------------------------------------------

    /**
     * An axis pointing nearly at the camera has no usable screen direction,
     * and dividing a drag by its vanishing pixels-per-unit would send the
     * selection to the horizon. The raw measurement says so honestly; what to
     * DO about it is [Transform.screenAxis]'s business, below.
     */
    @Test
    fun `an end-on axis reports null rather than a huge step`() {
        val c = Camera().apply {
            resize(800, 600); radius = 4.0
            // look straight down -Z, so the Z axis points at the eye
            theta = 0.0; phi = PI / 2; apply()
        }
        val centre = Vec3(0.0, 0.0, 0.0)
        assertNull(
            Transform.axisOnScreen(c, Vec3(0.0, 0.0, 1.0), centre),
            "the axis along the view direction should be unusable",
        )
        assertNotNull(
            Transform.axisOnScreen(c, Vec3(1.0, 0.0, 0.0), centre),
            "but the one across it is fine",
        )
    }

    /** The camera of the test above: +Z points at the eye. */
    private fun endOnCam() = Camera().apply {
        resize(800, 600); radius = 4.0
        theta = 0.0; phi = PI / 2; apply()
    }

    @Test
    fun `an end-on axis still gets a direction to be dragged by`() {
        val c = endOnCam()
        val s = Transform.screenAxis(c, Vec3(0.0, 0.0, 1.0), Vec3())
        assertEquals(1.0, kotlin.math.hypot(s.ux, s.uy), 1e-9, "a unit direction")
        assertTrue(s.pxPerUnit > 0 && s.pxPerUnit.isFinite(), "and a sane scale")
        assertEquals(0.0, s.ux, 1e-12, "vertical, like the depth strip")
    }

    @Test
    fun `dragging up on the axis you are looking down pushes it away`() {
        val c = endOnCam()
        val axis = Vec3(0.0, 0.0, 1.0)          // pointing at the eye
        val m = Transform.alongAxis(
            Transform.Mode.MOVE, axis, Transform.screenAxis(c, axis, Vec3()),
            Vec3(), 0.0, -40.0, 0.0,
        )
        val p = Vec3()
        m.transformPoint(Vec3(0.0, 0.0, 0.0), p)
        assertTrue(p.z < -1e-6, "up moved it away from the eye, as the strip does")

        /* and the same drag on an axis pointing AWAY from the eye moves it
           away too — the convention is about the drag, not about the axis */
        val other = Vec3(0.0, 0.0, -1.0)
        val q = Vec3()
        Transform.alongAxis(
            Transform.Mode.MOVE, other, Transform.screenAxis(c, other, Vec3()),
            Vec3(), 0.0, -40.0, 0.0,
        ).transformPoint(Vec3(0.0, 0.0, 0.0), q)
        assertTrue(q.z < -1e-6, "still away")
    }

    @Test
    fun `an axis across the view is unaffected by the fallback`() {
        val c = cam()
        val direct = assertNotNull(Transform.axisOnScreen(c, Vec3(1.0, 0.0, 0.0), Vec3()))
        val viaFallback = Transform.screenAxis(c, Vec3(1.0, 0.0, 0.0), Vec3())
        assertEquals(direct.ux, viaFallback.ux, 1e-12)
        assertEquals(direct.uy, viaFallback.uy, 1e-12)
        assertEquals(direct.pxPerUnit, viaFallback.pxPerUnit, 1e-12)
    }

    @Test
    fun `a usable axis reports a unit screen direction`() {
        val s = assertNotNull(Transform.axisOnScreen(cam(), Vec3(1.0, 0.0, 0.0), Vec3()))
        assertEquals(1.0, kotlin.math.hypot(s.ux, s.uy), 1e-9)
        assertTrue(s.pxPerUnit > 0)
    }

    /**
     * Moving along an axis tracks the finger: a drag of N pixels along the
     * axis's screen direction moves by N / pxPerUnit world units.
     */
    @Test
    fun `an axis move converts pixels back by that axis's own scale`() {
        val c = cam()
        val axis = Vec3(1.0, 0.0, 0.0)
        val s = assertNotNull(Transform.axisOnScreen(c, axis, Vec3()))
        val drag = 40.0
        val m = Transform.alongAxis(
            Transform.Mode.MOVE, axis, s, Vec3(), s.ux * drag, s.uy * drag, 0.0,
        )
        val moved = apply(m, Vec3())
        assertEquals(drag / s.pxPerUnit, moved.x, 1e-9)
        assertEquals(0.0, moved.y, 1e-12, "and nowhere else")
        assertEquals(0.0, moved.z, 1e-12)
    }

    /** A drag ACROSS the axis moves nothing: the handle is constrained. */
    @Test
    fun `a drag across an axis handle does not move along it`() {
        val c = cam()
        val axis = Vec3(1.0, 0.0, 0.0)
        val s = assertNotNull(Transform.axisOnScreen(c, axis, Vec3()))
        val m = Transform.alongAxis(
            Transform.Mode.MOVE, axis, s, Vec3(), -s.uy * 50.0, s.ux * 50.0, 0.0,
        )
        assertEquals(0.0, apply(m, Vec3()).length(), 1e-9)
    }

    // ---- free drags --------------------------------------------------------

    @Test
    fun `a free move goes across the screen and the strip goes into it`() {
        val c = cam()
        val flat = Transform.free(c, Transform.Mode.MOVE, 30.0, 0.0, Vec3(), strip = false)
        val deep = Transform.free(c, Transform.Mode.MOVE, 0.0, 30.0, Vec3(), strip = true)
        val r = Vec3(); val u = Vec3(); val f = Vec3()
        c.basis(r, u, f)
        val a = apply(flat, Vec3())
        val b = apply(deep, Vec3())
        assertTrue(abs(a dot f) < 1e-9, "a flat move has no depth in it")
        assertTrue(abs(b dot r) < 1e-9 && abs(b dot u) < 1e-9, "the strip is depth only")
    }

    @Test
    fun `a free scale is geometric and centred`() {
        val c = cam()
        val centre = Vec3(3.0, 1.0, 0.0)
        val up = Transform.free(c, Transform.Mode.SCALE, 0.0, -50.0, centre, strip = false)
        val down = Transform.free(c, Transform.Mode.SCALE, 0.0, 50.0, centre, strip = false)
        assertEquals(centre.x, apply(up, centre).x, 1e-9, "the centre holds still")
        val outAt = apply(up, Vec3(centre.x + 1, centre.y, centre.z))
        val inAt = apply(down, Vec3(centre.x + 1, centre.y, centre.z))
        assertTrue(outAt.x - centre.x > 1.0, "dragging up grows it")
        assertTrue(inAt.x - centre.x < 1.0, "and down shrinks it")
    }

    // ---- picking -----------------------------------------------------------

    @Test
    fun `the middle of the pad is the free centre`() {
        assertNull(
            Transform.pickAxis(
                54.0, 54.0, 54.0, 19.0, Transform.ARC_ANGLES, listOf(true, true, true),
            ),
        )
    }

    @Test
    fun `a press on the ring finds the arc nearest it`() {
        for ((i, ang) in Transform.ARC_ANGLES.withIndex()) {
            val x = 54 + kotlin.math.cos(ang) * 43
            val y = 54 + kotlin.math.sin(ang) * 43
            assertEquals(
                i,
                Transform.pickAxis(x, y, 54.0, 19.0, Transform.ARC_ANGLES, listOf(true, true, true)),
                "arc $i",
            )
        }
    }

    /** An axis that is end-on cannot be grabbed, because there is nothing to drag along. */
    @Test
    fun `an unusable axis is not pickable`() {
        val ang = Transform.ARC_ANGLES[1]
        val x = 54 + kotlin.math.cos(ang) * 43
        val y = 54 + kotlin.math.sin(ang) * 43
        assertEquals(
            null,
            Transform.pickAxis(x, y, 54.0, 19.0, Transform.ARC_ANGLES, listOf(false, false, false)),
        )
    }

    // ---- the 2D joystick ---------------------------------------------------

    /**
     * FACT: "The 2D Joystick moves, rotates, and scales objects based on the
     * view direction. It's very intuitive because it transforms as it
     * appears."
     */
    @Test
    fun `dragging the stick right moves the selection right on the glass`() {
        val c = cam()
        /* measured AT THE PIVOT, because the pad's pixels-per-unit is the
           scale there: under perspective a curve nearer the eye covers more
           screen for the same world step, which is the projection being
           honest rather than the stick being wrong */
        val p = c.pivot.copy()
        val before = Vec3().also { c.worldToScreen(p, it) }

        val m = Transform.view(c, Transform.View2D.MOVE, 40.0, 0.0, 0.0, c.pivot)
        val after = Vec3().also { c.worldToScreen(apply(m, p), it) }

        assertEquals(40.0, after.x - before.x, 0.4, "right on screen, whatever the angle")
        assertEquals(0.0, after.y - before.y, 0.4, "and not up or down")

        // and a curve off the pivot still goes right, and still not up
        val off = Vec3(0.3, -0.2, 0.1)
        val wasOff = Vec3().also { c.worldToScreen(off, it) }
        val nowOff = Vec3().also { c.worldToScreen(apply(m, off), it) }
        assertTrue(nowOff.x - wasOff.x > 20.0)
        assertEquals(0.0, nowOff.y - wasOff.y, 0.6)
    }

    @Test
    fun `dragging the stick down moves it down, not up`() {
        val c = cam()
        val p = Vec3(0.0, 0.0, 0.0)
        val before = Vec3().also { c.worldToScreen(p, it) }
        /* the sign of y is the whole test: screen y grows downward and the
           world's up does not, and getting it wrong makes the stick fight the
           hand rather than follow it */
        val m = Transform.view(c, Transform.View2D.MOVE, 0.0, 30.0, 0.0, c.pivot)
        val after = Vec3().also { c.worldToScreen(apply(m, p), it) }
        assertEquals(30.0, after.y - before.y, 0.6)
    }

    @Test
    fun `the rotate handle turns the picture, and nothing comes towards you`() {
        val c = cam()
        val p = Vec3(0.5, 0.0, 0.0)
        val m = Transform.view(c, Transform.View2D.ROTATE, 0.0, 0.0, PI / 2, c.pivot)
        val turned = apply(m, p)

        /* a turn in the plane of the picture leaves depth alone: the distance
           from the eye is what says whether something swung towards you */
        val r = Vec3(); val u = Vec3(); val back = Vec3()
        c.basis(r, u, back)
        assertEquals(p dot back, turned dot back, 1e-9, "same depth")
        assertEquals(0.5, hypotOf(p, c), 1e-9)
        assertEquals(0.5, hypotOf(turned, c), 1e-9, "and the same distance out")
    }

    private fun hypotOf(p: Vec3, c: Camera): Double {
        val d = Vec3(p.x - c.pivot.x, p.y - c.pivot.y, p.z - c.pivot.z)
        return kotlin.math.sqrt(d.lengthSq())
    }

    @Test
    fun `width scales across the glass only, height only up it`() {
        val c = cam()
        val r = Vec3(); val u = Vec3(); val back = Vec3()
        c.basis(r, u, back)
        val p = Vec3(r.x + u.x, r.y + u.y, r.z + u.z)   // one unit right, one up

        val wide = apply(Transform.view(c, Transform.View2D.SCALE_WIDTH, 60.0, 0.0, 0.0, c.pivot), p)
        assertTrue((wide dot r) > 1.2, "wider across")
        assertEquals(1.0, wide dot u, 1e-9, "and exactly as tall as it was")

        val tall = apply(Transform.view(c, Transform.View2D.SCALE_HEIGHT, 0.0, -60.0, 0.0, c.pivot), p)
        assertTrue((tall dot u) > 1.2, "taller up")
        assertEquals(1.0, tall dot r, 1e-9, "and exactly as wide")
    }

    @Test
    fun `scaling is about the crosshair, so the centre of the screen holds still`() {
        /* FACT: "The scaling reference point is the center of the screen,
           marked with a crosshair." */
        val c = cam()
        val at = c.pivot.copy()
        val m = Transform.view(c, Transform.View2D.SCALE_FREE, 0.0, -50.0, 0.0, c.pivot)
        val moved = apply(m, at)
        assertEquals(at.x, moved.x, 1e-9)
        assertEquals(at.y, moved.y, 1e-9)
        assertEquals(at.z, moved.z, 1e-9)
    }

    @Test
    fun `locked, the stick only goes up, down, left and right`() {
        /* FACT: "When the 2D Joystick is locked, tapping and dragging the
           stick only allows movement up, down, left, and right." */
        val mostlyAcross = Transform.lockedDelta(30.0, 8.0)
        assertEquals(30.0, mostlyAcross[0], 1e-12)
        assertEquals(0.0, mostlyAcross[1], 1e-12)

        val mostlyDown = Transform.lockedDelta(-4.0, 22.0)
        assertEquals(0.0, mostlyDown[0], 1e-12)
        assertEquals(22.0, mostlyDown[1], 1e-12)
    }

    @Test
    fun `locked, the rotate handle jumps fifteen degrees at a time`() {
        /* FACT: "rotate… in 15-degree increments. It's useful for rotating to
           specific angles like 90 or 180 degrees" — which is only true if the
           angles in between are never visited at all. */
        val step = Transform.LOCK_TURN
        assertEquals(PI / 12, step, 1e-12, "fifteen degrees")

        // barely turned: nothing has happened yet
        assertEquals(0.0, Transform.snapStep(0.05, 0.0), 1e-12)
        // past halfway to the first step: the whole step lands at once
        assertEquals(step, Transform.snapStep(step * 0.6, 0.0), 1e-12)
        // and having applied it, more of the same spin owes nothing
        assertEquals(0.0, Transform.snapStep(step * 0.9, step), 1e-12)
        // six steps is a right angle, exactly
        assertEquals(PI / 2, Transform.snapStep(PI / 2 + 0.01, 0.0), 1e-12)
        // and it goes back the other way
        assertEquals(-step, Transform.snapStep(-step * 0.7, 0.0), 1e-12)
    }
}

/** Where the symmetry folds, and why it is a plane rather than a line. */
class SymmetryFoldTest {

    private fun boundsOf(vararg p: Vec3) = Bounds().also { for (v in p) it.add(v) }

    @Test
    fun `no symmetry means nothing to draw`() {
        assertNull(Symmetry.fold(boundsOf(Vec3()), null, 1))
    }

    @Test
    fun `a mirror gives a quad and its edges`() {
        val f = assertNotNull(Symmetry.fold(boundsOf(Vec3(-1.0, 0.0, -1.0), Vec3(1.0, 2.0, 1.0)), "x", 1))
        assertEquals(18, f.fill.size, "two triangles")
        assertEquals(24, f.edges.size, "four segments")
        assertEquals(0, f.axisLine.size, "radial is off")
    }

    /**
     * Mirroring across X leaves the Z axis lying IN the plane, so every corner
     * of the quad has x = 0. Getting this the other way round draws the fold
     * at ninety degrees to where the strokes actually meet.
     */
    @Test
    fun `the plane lies where the reflection meets its original`() {
        val b = boundsOf(Vec3(-1.0, 0.0, -3.0), Vec3(1.0, 2.0, 3.0))
        val x = assertNotNull(Symmetry.fold(b, "x", 1))
        for (i in x.fill.indices step 3) assertEquals(0f, x.fill[i], 1e-6f, "x at $i")
        val z = assertNotNull(Symmetry.fold(b, "z", 1))
        for (i in 2 until z.fill.size step 3) assertEquals(0f, z.fill[i], 1e-6f, "z at $i")
    }

    /** Radial folds about a LINE, so that one is a line. */
    @Test
    fun `radial alone gives the upright axis and no plane`() {
        val f = assertNotNull(Symmetry.fold(boundsOf(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 4.0, 1.0)), null, 6))
        assertEquals(0, f.fill.size)
        assertEquals(6, f.axisLine.size)
        assertEquals(0f, f.axisLine[0], 1e-6f)
        assertEquals(0f, f.axisLine[2], 1e-6f)
        assertTrue(f.axisLine[4] > f.axisLine[1], "it runs upwards")
    }

    /**
     * A minimum SIZE centred on the work, not a minimum reach from the origin.
     * Forcing it to straddle zero left the fold hanging below a sketch that
     * happened to sit above it, pointing at nothing.
     */
    @Test
    fun `the fold is centred on the work, not on the origin`() {
        val high = boundsOf(Vec3(-0.1, 8.0, -0.1), Vec3(0.1, 8.4, 0.1))
        val f = assertNotNull(Symmetry.fold(high, "x", 1))
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (i in 1 until f.fill.size step 3) { lo = minOf(lo, f.fill[i]); hi = maxOf(hi, f.fill[i]) }
        assertTrue(lo > 7.0f, "the fold started at $lo, far below the work")
        assertTrue(hi > 8.0f)
    }

    /** And it is still there when there is nothing on the page. */
    @Test
    fun `an empty sketch still shows a fold`() {
        val f = assertNotNull(Symmetry.fold(Bounds(), "x", 1))
        assertEquals(18, f.fill.size)
        var hi = -Float.MAX_VALUE
        for (i in 1 until f.fill.size step 3) hi = maxOf(hi, f.fill[i])
        assertTrue(hi >= Symmetry.MIN_HALF.toFloat() - 1e-6f)
    }
}
