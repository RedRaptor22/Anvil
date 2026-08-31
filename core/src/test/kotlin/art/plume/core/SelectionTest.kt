package art.plume.core

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelectionTest {

    private fun camera() = Camera().apply { resize(1000, 1000) }

    /** A horizontal line at screen height [atY]. */
    private fun line(cam: Camera, atY: Double, from: Double = 100.0, to: Double = 900.0): Stroke {
        val s = Stroke(brush = "pen", baseRadius = 7.0 * MM)
        val p = Vec3()
        for (i in 0 until 21) {
            val px = from + (to - from) * i / 20.0
            assertNotNull(cam.planePoint(px, atY, p))
            s.pts.add(StrokePoint(p.copy()))
        }
        return s
    }

    // ---- tapping ----------------------------------------------------------

    @Test
    fun `tapping adds, because a tablet has no shift key`() {
        /*
         * `additive` used to come from the shift key. Every tap threw away what
         * you had picked, and there was no way to select two curves by tapping
         * at all.
         */
        val cam = camera()
        val sketch = Sketch()
        val a = line(cam, 300.0); val b = line(cam, 700.0)
        sketch.add(a); sketch.add(b)

        assertEquals(a, Selection.tapSelect(sketch, cam, 500.0, 300.0))
        assertEquals(b, Selection.tapSelect(sketch, cam, 500.0, 700.0))
        assertEquals(listOf(a, b), sketch.selection)
    }

    @Test
    fun `tapping a selected curve takes it back out again`() {
        val cam = camera()
        val sketch = Sketch()
        val a = line(cam, 300.0)
        sketch.add(a)

        Selection.tapSelect(sketch, cam, 500.0, 300.0)
        assertTrue(sketch.isSelected(a))
        Selection.tapSelect(sketch, cam, 500.0, 300.0)
        assertFalse(sketch.isSelected(a))
    }

    @Test
    fun `tapping empty space is the gesture that means start again`() {
        val cam = camera()
        val sketch = Sketch()
        val a = line(cam, 300.0)
        sketch.add(a)
        Selection.tapSelect(sketch, cam, 500.0, 300.0)

        assertNull(Selection.tapSelect(sketch, cam, 500.0, 950.0))
        assertTrue(sketch.selection.isEmpty())
    }

    @Test
    fun `a guide hides a curve from selection too`() {
        // FACT (A.9): "curves hidden by the 3D guide cannot be selected"
        val cam = camera()
        val sketch = Sketch()
        sketch.add(line(cam, 300.0))
        assertNull(Selection.tapSelect(sketch, cam, 500.0, 300.0, mask = { true }))
        assertTrue(sketch.selection.isEmpty())
    }

    // ---- sweeping ---------------------------------------------------------

    @Test
    fun `a sweep picks up every curve it crosses, including the first`() {
        /*
         * Two things this pins. The sweep is sampled ALONG each segment, not
         * only where the pointer landed — testing points alone picked up one
         * stroke in four across thin curves. And it starts where the PRESS did,
         * so the curve sitting under the finger at the start is not the one
         * stroke a sweep across four reliably missed.
         */
        val cam = camera()
        val sketch = Sketch()
        val rows = listOf(200.0, 400.0, 600.0, 800.0).map { line(cam, it) }
        for (r in rows) sketch.add(r)

        val sweep = Selection.beginSweep(Px(500.0, 200.0))
        // one huge jump, as a fast pen reports
        sweep.step(sketch, cam, 500.0, 800.0)

        assertEquals(4, sketch.selection.size, "a fast sweep missed curves it crossed")
        for (r in rows) assertTrue(sketch.isSelected(r))
    }

    @Test
    fun `a sweep that starts on a curve still gets that curve`() {
        val cam = camera()
        val sketch = Sketch()
        val a = line(cam, 200.0)
        val b = line(cam, 400.0)
        sketch.add(a); sketch.add(b)

        val sweep = Selection.beginSweep(Px(500.0, 200.0))
        sweep.step(sketch, cam, 500.0, 400.0)
        assertTrue(sketch.isSelected(a), "the curve under the press was missed")
        assertTrue(sketch.isSelected(b))
    }

    // ---- lasso ------------------------------------------------------------

    @Test
    fun `a lasso takes what is mostly inside it and leaves what is clipped`() {
        val cam = camera()
        val sketch = Sketch()
        val inside = line(cam, 500.0, from = 300.0, to = 700.0)
        val clipped = line(cam, 500.0, from = 650.0, to = 1400.0)
        sketch.add(inside); sketch.add(clipped)

        // a box round the middle of the screen
        val poly = listOf(
            Px(250.0, 400.0), Px(750.0, 400.0), Px(750.0, 600.0), Px(250.0, 600.0),
        )
        val hits = Selection.lassoSelect(sketch, cam, poly)
        assertEquals(listOf(inside), hits, "the majority rule did not hold")
        assertFalse(sketch.isSelected(clipped))
    }

    @Test
    fun `a lasso with fewer than three points selects nothing`() {
        val cam = camera()
        val sketch = Sketch()
        sketch.add(line(cam, 500.0))
        assertTrue(Selection.lassoSelect(sketch, cam, listOf(Px(0.0, 0.0), Px(1.0, 1.0))).isEmpty())
    }

    @Test
    fun `the lasso only records a vertex once the pen has moved`() {
        val poly = ArrayList<Px>()
        assertTrue(Selection.appendLasso(poly, 10.0, 10.0))
        assertFalse(Selection.appendLasso(poly, 11.0, 10.0), "a 1px move should not add a vertex")
        assertTrue(Selection.appendLasso(poly, 20.0, 10.0))
        assertEquals(2, poly.size)
    }

    // ---- moving and copying ------------------------------------------------

    @Test
    fun `duplicating offsets the copy and leaves the copy selected`() {
        val cam = camera()
        val sketch = Sketch()
        val a = line(cam, 500.0)
        sketch.add(a)
        sketch.setSelected(a, true)
        val where = a.pts[0].p.copy()

        val copies = Selection.duplicate(sketch, cam)
        assertEquals(1, copies.size)
        assertEquals(2, sketch.strokes.size)
        assertEquals(copies, sketch.selection, "the copy should be what is selected now")
        assertFalse(sketch.isSelected(a))

        // moved by 24px across the screen, measured on the glass
        val s0 = Vec3(); val s1 = Vec3()
        cam.worldToScreen(where, s0)
        cam.worldToScreen(copies[0].pts[0].p, s1)
        assertEquals(24.0, s1.x - s0.x, 1e-6)
        assertEquals(0.0, s1.y - s0.y, 1e-6)
    }

    @Test
    fun `a mirrored duplicate lands in place, not offset`() {
        val cam = camera()
        val sketch = Sketch()
        val a = line(cam, 500.0)
        sketch.add(a)
        sketch.setSelected(a, true)
        val was = a.pts.map { it.p.copy() }

        val copies = Selection.mirroredDuplicate(sketch, "x")
        assertEquals(1, copies.size)
        assertEquals(copies, sketch.selection)
        for (j in was.indices) {
            // reflected across x, and otherwise exactly where it was
            assertEquals(-was[j].x, copies[0].pts[j].p.x, 1e-12)
            assertEquals(was[j].y, copies[0].pts[j].p.y, 1e-12)
            assertEquals(was[j].z, copies[0].pts[j].p.z, 1e-12)
        }
    }

    @Test
    fun `a uniform scale scales the brush, a stretch does not`() {
        /*
         * A non-uniform scale would need a cross-section the data model cannot
         * represent — an ellipse whose ratio varies along the curve — so the
         * radius is left alone rather than being quietly wrong.
         */
        val a = Stroke(baseRadius = 0.01)
        a.pts.add(StrokePoint(Vec3(1.0, 0.0, 0.0)))
        Selection.transform(listOf(a), Mat4.scale(2.0, 2.0, 2.0, Mat4()))
        assertEquals(0.02, a.baseRadius, 1e-12)
        assertEquals(2.0, a.pts[0].p.x, 1e-12)

        val b = Stroke(baseRadius = 0.01)
        b.pts.add(StrokePoint(Vec3(1.0, 0.0, 0.0)))
        Selection.transform(listOf(b), Mat4.scale(2.0, 1.0, 1.0, Mat4()))
        assertEquals(0.01, b.baseRadius, 1e-12, "a stretch must not scale the nib")
    }

    @Test
    fun `transforming carries the frame, so a rotated curve keeps its shape`() {
        val s = Stroke(baseRadius = 0.01)
        s.pts.add(
            StrokePoint(
                Vec3(1.0, 0.0, 0.0),
                tan = Vec3(0.0, 0.0, 1.0),
                ref = Vec3(1.0, 0.0, 0.0),
                nrm = Vec3(0.0, 1.0, 0.0),
            ),
        )
        Selection.transform(listOf(s), Mat4.rotationY(PI / 2, Mat4()))
        val pt = s.pts[0]
        // everything rotated together, and stayed unit and orthogonal
        assertEquals(1.0, assertNotNull(pt.tan).length(), 1e-9)
        assertEquals(1.0, assertNotNull(pt.ref).length(), 1e-9)
        assertEquals(1.0, assertNotNull(pt.nrm).length(), 1e-9)
        assertEquals(0.0, pt.tan!! dot pt.ref!!, 1e-9, "the frame stopped being orthogonal")
    }

    // ---- symmetry  (C.10) --------------------------------------------------

    @Test
    fun `symmetry never includes the stroke you actually drew`() {
        assertEquals(0, Selection.symmetryMatrices(null, 1).size)
        assertEquals(1, Selection.symmetryMatrices("x", 1).size, "mirror alone owes one copy")
        assertEquals(5, Selection.symmetryMatrices(null, 6).size, "radial 6 owes five")
        assertEquals(11, Selection.symmetryMatrices("x", 6).size, "both owe 2n-1")
    }

    @Test
    fun `mirror and radial compose into a rosette, not a pinwheel`() {
        /*
         * Each of the n sectors carries the stroke AND its reflection. The copy
         * is mirrored FIRST and then turned into its sector; doing it the other
         * way round gives a pinwheel, where every copy leans the same way.
         */
        val mats = Selection.symmetryMatrices("x", 4)
        val p = Vec3(1.0, 0.0, 0.0)
        val out = Vec3()

        // the reflection in sector 0 lands at -x, unrotated
        mats[0].transformPoint(p, out)
        assertEquals(-1.0, out.x, 1e-9)
        assertEquals(0.0, out.z, 1e-9)

        // every copy sits on the same circle as the original
        for (m in mats) {
            m.transformPoint(p, out)
            assertEquals(1.0, kotlin.math.hypot(out.x, out.z), 1e-9)
        }

        // and a rosette has mirrored pairs: for each copy there is another
        // that is its reflection about the vertical plane
        var pairs = 0
        val q = Vec3(0.7, 0.0, 0.3)
        val marks = ArrayList<Vec3>()
        marks.add(q.copy())
        for (m in mats) marks.add(Vec3().also { m.transformPoint(q, it) })
        for (a in marks) {
            if (marks.any { kotlin.math.abs(it.x + a.x) < 1e-9 && kotlin.math.abs(it.z - a.z) < 1e-9 }) {
                pairs++
            }
        }
        assertEquals(marks.size, pairs, "not every mark had a mirror twin")
    }

    @Test
    fun `mirroring on x and on z reflect the axes they name`() {
        val out = Vec3()
        Selection.mirrorMatrix("x").transformPoint(Vec3(2.0, 3.0, 4.0), out)
        assertEquals(-2.0, out.x, 1e-12); assertEquals(4.0, out.z, 1e-12)
        Selection.mirrorMatrix("z").transformPoint(Vec3(2.0, 3.0, 4.0), out)
        assertEquals(2.0, out.x, 1e-12); assertEquals(-4.0, out.z, 1e-12)
    }

    // ---- restyle -----------------------------------------------------------

    @Test
    fun `a scale drag measures from the base, so it cannot compound`() {
        /*
         * Every frame of a slider drag scales the ORIGINAL radius, not the one
         * the previous frame left behind. Without the base, dragging out and
         * back would leave the curve far from where it started.
         */
        val s = Stroke(baseRadius = 0.01)
        val base = Selection.radiiOf(listOf(s))
        for (k in listOf(1.5, 2.0, 1.5, 1.0)) {
            Selection.restyle(listOf(s), StyleChange(scale = k), base)
        }
        assertEquals(0.01, s.baseRadius, 1e-12)
    }

    @Test
    fun `restyling clamps to the brush panel's own range`() {
        // FACT: the panel runs 1mm - 300mm
        val s = Stroke(baseRadius = 0.01)
        Selection.restyle(listOf(s), StyleChange(scale = 1000.0), listOf(0.01))
        assertEquals(Tune.BRUSH_MAX_MM * MM * 0.5, s.baseRadius, 1e-12)
        Selection.restyle(listOf(s), StyleChange(scale = 0.0), listOf(0.01))
        assertEquals(Tune.BRUSH_MIN_MM * MM * 0.5, s.baseRadius, 1e-12)
    }

    @Test
    fun `the panel reads what the selection agrees on, and nulls the rest`() {
        val a = Stroke(brush = "pen", color = Rgba(1.0, 0.0, 0.0), baseRadius = 0.01)
        val b = Stroke(brush = "pen", color = Rgba(0.0, 1.0, 0.0), baseRadius = 0.03)
        val style = assertNotNull(Selection.styleOf(listOf(a, b)))
        assertEquals("pen", style.brush, "they agree on the brush")
        assertNull(style.color, "they do not agree on the colour")
        // size is averaged rather than nulled: a spread still has a middle
        assertEquals(0.02, style.averageRadius, 1e-12)
        assertNull(Selection.styleOf(emptyList()))
    }
}
