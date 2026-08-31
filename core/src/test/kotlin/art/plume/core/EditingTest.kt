package art.plume.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Erase, vacuum and smooth.
 *
 * These are the tools that can quietly destroy a drawing, so the tests are
 * about what SURVIVES as much as what goes: a thin eraser slipping between two
 * samples, a curve split into two that keeps its brush, a guide that is
 * supposed to protect what it hides.
 */
class EditingTest {

    private fun camera() = Camera().apply { resize(1000, 1000) }

    /** A straight line across the middle of the screen, at the pivot's depth. */
    private fun lineStroke(cam: Camera, from: Double, to: Double, n: Int = 21): Stroke {
        val s = Stroke(brush = "pen", baseRadius = 7.0 * MM)
        val p = Vec3()
        for (i in 0 until n) {
            val px = from + (to - from) * i.toDouble() / (n - 1)
            assertNotNull(cam.planePoint(px, 500.0, p), "probe off the draw plane")
            s.pts.add(StrokePoint(p.copy()))
        }
        return s
    }

    // ---- erase ------------------------------------------------------------

    @Test
    fun `erasing the middle of a curve leaves two, and keeps the brush`() {
        val cam = camera()
        val sketch = Sketch()
        val original = lineStroke(cam, 100.0, 900.0)
        original.brush = "taper"
        original.color = Rgba(0.9, 0.1, 0.2)
        sketch.add(original)

        val r = Editing.eraseScreen(sketch, cam, 500.0, 500.0, 40.0)
        assertTrue(r.touched)
        assertEquals(listOf(original), r.removed)
        assertEquals(2, r.added.size, "one pass through the middle should give two curves")
        assertEquals(2, sketch.strokes.size)

        for (s in r.added) {
            assertEquals("taper", s.brush, "a split curve kept its brush")
            assertEquals(original.color, s.color)
            assertEquals(original.baseRadius, s.baseRadius, 0.0)
            assertTrue(s.pts.size >= 2)
            assertTrue(s.id != original.id, "a split curve is a new curve")
        }

        // and the gap is real: nothing survives within the disc
        val gone = Vec3()
        for (s in sketch.strokes) {
            for (pt in s.pts) {
                cam.worldToScreen(pt.p, gone)
                val d = kotlin.math.hypot(gone.x - 500.0, gone.y - 500.0)
                assertTrue(d >= 40.0 - 1e-6, "a point at ${d}px survived a 40px eraser")
            }
        }
    }

    @Test
    fun `a thin eraser cuts a segment it crosses between two samples`() {
        /*
         * The reason the disc is clipped against the centreline as a CONTINUOUS
         * polyline rather than against its sample points. Two points, 800px
         * apart, with a 10px eraser at the midpoint: no sample is inside the
         * disc at all, and a point-wise test would find nothing to do.
         */
        val cam = camera()
        val sketch = Sketch()
        val two = lineStroke(cam, 100.0, 900.0, n = 2)
        sketch.add(two)

        val s = Vec3()
        for (pt in two.pts) {
            cam.worldToScreen(pt.p, s)
            val d = kotlin.math.hypot(s.x - 500.0, s.y - 500.0)
            assertTrue(d > 10.0, "the test is void: a sample is already inside the disc")
        }

        val r = Editing.eraseScreen(sketch, cam, 500.0, 500.0, 10.0)
        assertTrue(r.touched, "a two-point curve could not be split at all")
        assertEquals(2, r.added.size)
    }

    @Test
    fun `erasing an end trims the curve rather than splitting it`() {
        val cam = camera()
        val sketch = Sketch()
        sketch.add(lineStroke(cam, 100.0, 900.0))

        val r = Editing.eraseScreen(sketch, cam, 100.0, 500.0, 60.0)
        assertTrue(r.touched)
        assertEquals(1, r.added.size, "trimming an end should leave one curve")
    }

    @Test
    fun `an eraser that misses changes nothing at all`() {
        val cam = camera()
        val sketch = Sketch()
        val s = lineStroke(cam, 100.0, 900.0)
        sketch.add(s)
        val before = s.pts.size

        val r = Editing.eraseScreen(sketch, cam, 500.0, 50.0, 20.0)
        assertFalse(r.touched)
        assertEquals(listOf(s), sketch.strokes)
        assertEquals(before, s.pts.size)
    }

    @Test
    fun `a guide protects what it hides, even from the eraser`() {
        // FACT (A.9): "prevented curves behind planes from being erased"
        val cam = camera()
        val sketch = Sketch()
        val s = lineStroke(cam, 100.0, 900.0)
        sketch.add(s)

        val r = Editing.eraseScreen(sketch, cam, 500.0, 500.0, 40.0, mask = { true })
        assertFalse(r.touched, "a masked curve was erased anyway")
        assertEquals(listOf(s), sketch.strokes)
    }

    @Test
    fun `a hidden group is not erased either`() {
        val cam = camera()
        val sketch = Sketch()
        val s = lineStroke(cam, 100.0, 900.0)
        sketch.add(s)
        val g = sketch.newGroup("scaffold")
        sketch.assign(s, g)
        g.visible = false

        val r = Editing.eraseScreen(sketch, cam, 500.0, 500.0, 40.0)
        assertFalse(r.touched, "a curve in a hidden group was erased")
    }

    @Test
    fun `the sphere eraser drops the points inside it and keeps the rest`() {
        val cam = camera()
        val sketch = Sketch()
        val s = lineStroke(cam, 100.0, 900.0, n = 41)
        sketch.add(s)
        val mid = s.pts[20].p.copy()

        val r = Editing.eraseSphere(sketch, mid, 0.05)
        assertTrue(r.touched)
        assertEquals(2, r.added.size)
        for (n in r.added) {
            for (pt in n.pts) {
                assertTrue(pt.p.distanceTo(mid) > 0.05 - 1e-9, "a point inside the sphere survived")
            }
        }
    }

    @Test
    fun `erasing records where each curve sat, so undo can put it back in order`() {
        val cam = camera()
        val sketch = Sketch()
        val a = lineStroke(cam, 100.0, 900.0)
        val b = lineStroke(cam, 100.0, 900.0)
        val c = lineStroke(cam, 100.0, 900.0)
        sketch.add(a); sketch.add(b); sketch.add(c)

        val r = Editing.eraseScreen(sketch, cam, 500.0, 500.0, 40.0)
        assertEquals(3, r.removed.size)
        assertEquals(r.removed.size, r.removedAt.size)
        for (at in r.removedAt) assertTrue(at >= 0, "a removed curve had no recorded position")
    }

    @Test
    fun `the disc interval maths agrees with the geometry it stands for`() {
        // straight through the middle of a unit segment, radius 0.25
        val through = assertNotNull(Editing.discInterval(0.0, 0.0, 1.0, 0.0, 0.5, 0.0, 0.25))
        assertEquals(0.25, through.first, 1e-12)
        assertEquals(0.75, through.second, 1e-12)

        // tangent above the segment: no crossing
        assertNull(Editing.discInterval(0.0, 0.0, 1.0, 0.0, 0.5, 1.0, 0.25))

        // covering the whole segment
        val all = assertNotNull(Editing.discInterval(0.0, 0.0, 1.0, 0.0, 0.5, 0.0, 5.0))
        assertEquals(0.0, all.first, 0.0)
        assertEquals(1.0, all.second, 0.0)
    }

    // ---- vacuum  (C.6) ----------------------------------------------------

    @Test
    fun `vacuum takes the whole curve, not a piece of it`() {
        val cam = camera()
        val sketch = Sketch()
        val a = lineStroke(cam, 100.0, 900.0)
        val b = lineStroke(cam, 100.0, 900.0)
        // move b well out of the way
        for (pt in b.pts) pt.p.y += 1.0
        sketch.add(a); sketch.add(b)

        val killed = Editing.vacuumAt(sketch, cam, 500.0, 500.0)
        assertEquals(listOf(a), killed, "vacuum should take exactly the curve under the pen")
        assertEquals(listOf(b), sketch.strokes)
    }

    @Test
    fun `vacuum misses a curve the pen is not over`() {
        val cam = camera()
        val sketch = Sketch()
        sketch.add(lineStroke(cam, 100.0, 900.0))
        assertTrue(Editing.vacuumAt(sketch, cam, 500.0, 100.0).isEmpty())
        assertEquals(1, sketch.strokes.size)
    }

    @Test
    fun `vacuum honours the guide mask too`() {
        val cam = camera()
        val sketch = Sketch()
        sketch.add(lineStroke(cam, 100.0, 900.0))
        assertTrue(Editing.vacuumAt(sketch, cam, 500.0, 500.0, mask = { true }).isEmpty())
        assertEquals(1, sketch.strokes.size)
    }

    // ---- smooth -----------------------------------------------------------

    /** A zig-zag, so there is something for smoothing to take out. */
    private fun zigzag(cam: Camera, n: Int = 21): Stroke {
        val s = Stroke(brush = "pen", baseRadius = 7.0 * MM)
        val p = Vec3()
        for (i in 0 until n) {
            val px = 200.0 + 600.0 * i.toDouble() / (n - 1)
            val py = 500.0 + if (i % 2 == 0) -30.0 else 30.0
            assertNotNull(cam.planePoint(px, py, p))
            s.pts.add(StrokePoint(p.copy()))
        }
        return s
    }

    private fun roughness(s: Stroke): Double {
        var total = 0.0
        for (i in 1 until s.pts.size - 1) {
            val mid = (s.pts[i - 1].p + s.pts[i + 1].p) * 0.5
            total += s.pts[i].p.distanceTo(mid)
        }
        return total
    }

    @Test
    fun `smoothing takes the wobble out and pins the ends`() {
        val cam = camera()
        val sketch = Sketch()
        val s = zigzag(cam)
        sketch.add(s)
        val first = s.pts.first().p.copy()
        val last = s.pts.last().p.copy()
        val before = roughness(s)

        repeat(12) { Editing.smoothStep(sketch, cam, 500.0, 500.0, 400.0) }

        assertTrue(roughness(s) < before * 0.7, "smoothing did not smooth")
        /*
         * The endpoints are pinned on purpose: a smooth that let them creep
         * inwards would shorten the curve on every pass, and holding the tool
         * down would eat the stroke.
         */
        assertEquals(0.0, s.pts.first().p.distanceTo(first), 1e-12)
        assertEquals(0.0, s.pts.last().p.distanceTo(last), 1e-12)
    }

    @Test
    fun `smoothing only touches what is under the disc`() {
        val cam = camera()
        val sketch = Sketch()
        val near = zigzag(cam)
        val far = zigzag(cam)
        for (pt in far.pts) pt.p.y += 2.0
        sketch.add(near); sketch.add(far)
        val farBefore = Editing.snapshot(listOf(far))

        val touched = Editing.smoothStep(sketch, cam, 500.0, 500.0, 200.0)
        assertEquals(listOf(near), touched)
        for (j in far.pts.indices) {
            assertEquals(0.0, far.pts[j].p.distanceTo(farBefore[0][j]), 0.0, "the far curve moved")
        }
    }

    @Test
    fun `smoothing on a guide puts the paint back on it`() {
        /*
         * Averaging a point with its neighbours cuts the corner off a curved
         * surface, so paint drifts off the guide it was painted on — measured
         * in the web build at 6.41mm after one pass. Reprojection is what keeps
         * "a stroke stays on its guide under every tool" true.
         */
        val cam = camera()
        val guide = assertNotNull(
            Guides.createFromStroke(
                (0 until 24).map {
                    val a = -0.6 + it.toDouble() / 23 * 1.2
                    Vec3(a * 0.4, kotlin.math.sin(a * 2.2) * 0.22, 0.0)
                },
                Vec3(0.0, 0.0, -1.0), Vec3(1.0, 0.0, 0.0), 4.0,
            ),
        )
        val mesh = assertNotNull(guide.surface).mesh

        /*
         * The stroke has to run ACROSS the curved direction of the guide. A
         * swept surface is ruled along the sweep — dead straight that way — so
         * a stroke wobbling along it can be averaged all day without ever
         * leaving the sheet. Laid along the PROFILE, where the surface actually
         * bends, averaging cuts the corner and the paint comes off. The first
         * version of this test wobbled the wrong way and proved nothing.
         */
        val span = assertNotNull(GuidePainting.surfaceSpan(guide))
        val sketch = Sketch()
        val s = Stroke(brush = "pen", baseRadius = 7.0 * MM)
        for (i in 0 until 21) {
            val t = i.toDouble() / 20
            val su = span.lu * (0.1 + 0.8 * t)
            val sample = assertNotNull(GuidePainting.sampleSurface(guide, su, span.lv * 0.5))
            s.pts.add(StrokePoint(sample.point.copy()))
        }
        sketch.add(s)
        for (pt in s.pts) assertTrue(mesh.distanceTo(pt.p) < 1e-5)

        // without reprojection it drifts...
        val drifting = s.copyStroke()
        val other = Sketch().also { it.add(drifting) }
        repeat(6) { Editing.smoothStep(other, cam, 500.0, 500.0, 4000.0) }
        var drift = 0.0
        for (pt in drifting.pts) drift = maxOf(drift, mesh.distanceTo(pt.p))
        assertTrue(drift > 0.0005, "the test is void: smoothing did not pull it off the guide")

        // ...and with it, the paint stays put
        repeat(6) { Editing.smoothStep(sketch, cam, 500.0, 500.0, 4000.0, reprojectOnto = { mesh }) }
        for (pt in s.pts) {
            assertTrue(
                mesh.distanceTo(pt.p) < 1e-5,
                "paint came ${mesh.distanceTo(pt.p) / MM} mm off its guide",
            )
        }
    }

    @Test
    fun `a snapshot puts every point back exactly`() {
        val cam = camera()
        val sketch = Sketch()
        val s = zigzag(cam)
        sketch.add(s)
        val before = Editing.snapshot(listOf(s))

        repeat(5) { Editing.smoothStep(sketch, cam, 500.0, 500.0, 400.0) }
        assertTrue(roughness(s) > 0.0)

        Editing.restore(listOf(s), before)
        for (j in s.pts.indices) {
            assertEquals(0.0, s.pts[j].p.distanceTo(before[0][j]), 0.0, "point $j did not come back")
        }
    }
}
