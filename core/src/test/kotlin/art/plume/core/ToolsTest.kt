package art.plume.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Liquify, Fill and Draw Shape. */
class ToolsTest {

    private fun camera() = Camera().apply { resize(1000, 1000) }

    private fun line(cam: Camera, atY: Double, n: Int = 21): Stroke {
        val s = Stroke(brush = "pen", baseRadius = 7.0 * MM)
        val p = Vec3()
        for (i in 0 until n) {
            assertNotNull(cam.planePoint(100.0 + 800.0 * i / (n - 1.0), atY, p))
            s.pts.add(StrokePoint(p.copy()))
        }
        return s
    }

    // ---- liquify ----------------------------------------------------------

    @Test
    fun `the falloff is one at the centre, nothing at the rim, and monotonic`() {
        assertEquals(1.0, Liquify.falloff(0.0, 100.0, 50.0), 1e-12)
        assertEquals(0.0, Liquify.falloff(100.0, 100.0, 50.0), 0.0)
        assertEquals(0.0, Liquify.falloff(200.0, 100.0, 50.0), 0.0)
        var prev = 1.1
        for (d in 0..100 step 5) {
            val w = Liquify.falloff(d.toDouble(), 100.0, 50.0)
            assertTrue(w <= prev, "the falloff rose again at $d")
            prev = w
        }
    }

    @Test
    fun `range widens the shoulder rather than just scaling it`() {
        // at the halfway point a wide range still has most of its strength;
        // a sharp one has already fallen away
        val wide = Liquify.falloff(50.0, 100.0, 100.0)
        val sharp = Liquify.falloff(50.0, 100.0, 0.0)
        assertTrue(wide > sharp * 4, "range $wide vs $sharp does not change the shape")
        assertEquals(0.5, wide, 1e-9, "range 100 should be a straight ramp")
    }

    @Test
    fun `push moves points with the pen, most at the centre`() {
        val cam = camera()
        val s = line(cam, 500.0)
        val before = Editing.snapshot(listOf(s))
        val cfg = Liquify.Settings(mode = Liquify.Mode.PUSH, size = 300.0, strength = 100.0)

        val moved = Liquify.step(listOf(s), cam, cfg, 500.0, 500.0, 500.0, 460.0)
        assertEquals(listOf(s), moved)

        // the point under the pen moved furthest; the far end did not move
        val mid = s.pts[10].p.distanceTo(before[0][10])
        val end = s.pts[0].p.distanceTo(before[0][0])
        assertTrue(mid > 0.0, "nothing moved under the pen")
        assertTrue(mid > end, "the centre should move further than the rim")

        // and it moved the way the pen went: screen-up
        val a = Vec3(); val b = Vec3()
        cam.worldToScreen(before[0][10], a)
        cam.worldToScreen(s.pts[10].p, b)
        assertTrue(b.y < a.y, "push went the wrong way on screen")
    }

    @Test
    fun `pinch pulls toward the pen and cannot overshoot it`() {
        val cam = camera()
        val s = line(cam, 500.0)
        val cfg = Liquify.Settings(
            mode = Liquify.Mode.PINCH, size = 400.0, strength = 100.0, range = 100.0,
        )
        /*
         * Probe a point INSIDE the disc. The pen ends at x = 900 with a 400px
         * radius, so it reaches back to x = 500; the far end of the line at
         * x = 100 is outside it and would never move, which measures nothing.
         */
        val probe = 15                       // the sample at about x = 700
        val scr = Vec3()
        cam.worldToScreen(s.pts[probe].p, scr)
        val startX = scr.x
        assertTrue(kotlin.math.abs(startX - 900.0) < 400.0, "the probe is outside the disc")

        // a huge drag, which without the cap would fling points past the cursor
        repeat(20) { Liquify.step(listOf(s), cam, cfg, 500.0, 500.0, 900.0, 500.0) }

        cam.worldToScreen(s.pts[probe].p, scr)
        assertTrue(scr.x > startX, "pinch did not pull the point in")
        assertTrue(
            scr.x <= 900.0 + 1e-6,
            "a point overshot the cursor to ${scr.x}, which a squeeze cannot do",
        )
    }

    @Test
    fun `comb straightens a wobble without moving the curve as a whole`() {
        val cam = camera()
        val s = Stroke(brush = "pen", baseRadius = 7.0 * MM)
        val p = Vec3()
        for (i in 0 until 21) {
            val py = 500.0 + if (i % 2 == 0) -20.0 else 20.0
            assertNotNull(cam.planePoint(100.0 + 800.0 * i / 20.0, py, p))
            s.pts.add(StrokePoint(p.copy()))
        }
        fun wobble(): Double {
            var t = 0.0
            for (i in 1 until s.pts.size - 1) {
                t += s.pts[i].p.distanceTo((s.pts[i - 1].p + s.pts[i + 1].p) * 0.5)
            }
            return t
        }
        val before = wobble()
        val ends = s.pts.first().p.copy() to s.pts.last().p.copy()
        val cfg = Liquify.Settings(
            mode = Liquify.Mode.COMB, size = 2000.0, strength = 100.0, range = 100.0,
        )
        repeat(20) { Liquify.step(listOf(s), cam, cfg, 500.0, 500.0, 500.0, 500.0) }

        assertTrue(wobble() < before * 0.5, "comb did not straighten anything")
        // the ends stay put: comb reshapes, it does not drag the curve about
        assertEquals(0.0, s.pts.first().p.distanceTo(ends.first), 1e-12)
        assertEquals(0.0, s.pts.last().p.distanceTo(ends.second), 1e-12)
    }

    @Test
    fun `liquify only touches what it was given, so a shove stays local`() {
        val cam = camera()
        val target = line(cam, 500.0)
        val other = line(cam, 500.0)
        val before = Editing.snapshot(listOf(other))
        val cfg = Liquify.Settings(size = 500.0, strength = 100.0)

        Liquify.step(listOf(target), cam, cfg, 500.0, 500.0, 500.0, 400.0)
        for (j in other.pts.indices) {
            assertEquals(0.0, other.pts[j].p.distanceTo(before[0][j]), 0.0, "an unselected curve moved")
        }
    }

    // ---- fill --------------------------------------------------------------

    private fun sweptGuide(): Guide = assertNotNull(
        Guides.createFromStroke(
            (0 until 24).map {
                val a = -0.6 + it.toDouble() / 23 * 1.2
                Vec3(a * 0.4, sin(a * 2.0) * 0.15, 0.0)
            },
            Vec3(0.0, 0.0, -1.0), Vec3(1.0, 0.0, 0.0), 4.0,
        ),
    )

    @Test
    fun `a fill covers the guide with rows that touch`() {
        val guide = sweptGuide()
        val span = assertNotNull(GuidePainting.surfaceSpan(guide))
        val proto = Stroke(brush = "pen", baseRadius = 20.0 * MM * 0.5)

        val result = Fill.fillGuide(guide, proto)
        val made = assertTrue(result is Fill.Result.Filled).let { (result as Fill.Result.Filled).strokes }
        assertTrue(made.isNotEmpty())

        // every stroke lies on the guide it filled
        val mesh = assertNotNull(guide.surface).mesh
        for (s in made) {
            assertEquals(guide.id, s.guideId)
            for (pt in s.pts) assertTrue(mesh.distanceTo(pt.p) < 1e-5)
        }

        /*
         * CEIL, not round. Rounding down leaves a step wider than the nib and
         * the rows stop touching — a 2mm groove down every seam. The row count
         * must be enough that the pitch never exceeds a nib width.
         */
        val half = StrokeGeometry.halfWidth(proto, proto.baseRadius)
        val across = kotlin.math.min(span.lu, span.lv)
        val rows = made.size
        assertTrue(
            across / rows <= half * 2 + 1e-9,
            "rows are ${across / rows} apart with a ${half * 2} nib: that is a seam",
        )
    }

    @Test
    fun `a fill runs the long way, so it is a few long curves not hundreds of stubs`() {
        val guide = sweptGuide()
        val span = assertNotNull(GuidePainting.surfaceSpan(guide))
        val proto = Stroke(brush = "pen", baseRadius = 20.0 * MM * 0.5)
        val made = (Fill.fillGuide(guide, proto) as Fill.Result.Filled).strokes

        val longSide = maxOf(span.lu, span.lv)
        for (s in made) {
            val len = Frames.polyLength(s.pts.map { it.p })
            assertTrue(
                len > longSide * 0.5,
                "a fill row is only $len long against a $longSide side: it ran the short way",
            )
        }
    }

    @Test
    fun `a brush too fine to fill is refused rather than hanging`() {
        val guide = sweptGuide()
        val proto = Stroke(brush = "pen", baseRadius = 0.002 * MM)
        val r = Fill.fillGuide(guide, proto)
        assertTrue(r is Fill.Result.Refused, "a runaway fill should be refused")
    }

    @Test
    fun `a primitive cannot be filled, and says so instead of failing quietly`() {
        // the known gap in both builds: a box has no arc-length grid to fill along
        val r = Fill.fillGuide(Primitives.create("cube"), Stroke(baseRadius = 7.0 * MM))
        assertTrue(r is Fill.Result.Refused)
        assertEquals("This guide cannot be filled", (r as Fill.Result.Refused).reason)
    }

    @Test
    fun `a row that leaves the shape and comes back becomes two strokes`() {
        /*
         * A horseshoe. Skipping the missing samples would join the two arms
         * with a stroke straight across the hole, which is paint the person
         * never asked for in a place they can see.
         */
        val outer = 0.5
        val inner = 0.25
        val pts = ArrayList<Vec3>()
        for (i in 0..24) {
            val a = PI * i / 24.0
            pts.add(Vec3(cos(a) * outer, sin(a) * outer, 0.0))
        }
        for (i in 24 downTo 0) {
            val a = PI * i / 24.0
            pts.add(Vec3(cos(a) * inner, sin(a) * inner, 0.0))
        }
        val guide = assertNotNull(
            Guides.createFlatFromStroke(pts, Vec3(0.0, 0.0, -1.0), Vec3(1.0, 0.0, 0.0)),
        )
        val proto = Stroke(brush = "pen", baseRadius = 12.0 * MM * 0.5)
        val made = (Fill.fillGuide(guide, proto) as Fill.Result.Filled).strokes

        // rows crossing the gap under the arch must have split
        val span = assertNotNull(GuidePainting.surfaceSpan(guide))
        val rowsAcross = kotlin.math.ceil(
            kotlin.math.min(span.lu, span.lv) /
                (StrokeGeometry.halfWidth(proto, proto.baseRadius) * 2 * Fill.OVERLAP),
        ).toInt()
        assertTrue(
            made.size > rowsAcross,
            "${made.size} strokes for $rowsAcross rows: no row was split at the gap",
        )

        // and nothing crosses the hole: every point is inside the outline
        val outline = assertNotNull(assertNotNull(guide.surface).outline)
        val pl = assertNotNull(guide.plane)
        for (s in made) {
            for (pt in s.pts) {
                val d = pt.p - pl.origin
                assertTrue(
                    SurfaceGrid.insideOutline(outline, d dot pl.right, d dot pl.up),
                    "a fill point landed outside the shape",
                )
            }
        }
    }

    // ---- draw shape  (C.9) --------------------------------------------------

    private fun jitter(seed: Int): java.util.Random = java.util.Random(seed.toLong())

    @Test
    fun `a roughly straight gesture becomes a line`() {
        val r = jitter(1)
        val pts = (0 until 40).map {
            Px(100.0 + it * 15.0, 400.0 + (r.nextDouble() - 0.5) * 6.0)
        }
        val shape = assertNotNull(Shapes.fitShape(pts))
        assertTrue(shape is Shapes.Shape.Line, "a straight gesture gave ${shape::class.simpleName}")
        assertEquals(33, shape.points.size)
    }

    @Test
    fun `a roughly round closed gesture becomes a circle`() {
        val r = jitter(2)
        val pts = (0 until 48).map {
            val a = it / 47.0 * 2 * PI
            Px(500 + cos(a) * 200 + (r.nextDouble() - 0.5) * 12, 500 + sin(a) * 200 + (r.nextDouble() - 0.5) * 12)
        }
        val shape = assertNotNull(Shapes.fitShape(pts))
        assertTrue(shape is Shapes.Shape.Circle, "a round gesture gave ${shape::class.simpleName}")
        val c = shape as Shapes.Shape.Circle
        assertEquals(500.0, c.cx, 10.0)
        assertEquals(200.0, c.r, 10.0)
    }

    @Test
    fun `an arc is not turned into the circle it is part of`() {
        /*
         * A circle is only considered for a CLOSED gesture. The fit itself
         * finds a perfectly good circle through an arc; what it cannot tell you
         * is that the person stopped a third of the way round and did not want
         * the rest of it.
         */
        val pts = (0 until 40).map {
            val a = it / 39.0 * (PI * 0.6)
            Px(500 + cos(a) * 200, 500 + sin(a) * 200)
        }
        assertNotNull(Shapes.fitCircle(pts), "the test is void: no circle fits this arc")
        val shape = assertNotNull(Shapes.fitShape(pts))
        assertTrue(shape !is Shapes.Shape.Circle, "an arc was closed into a full circle")
        assertTrue(shape is Shapes.Shape.Curve)
    }

    @Test
    fun `a wandering gesture is tidied rather than forced into a shape`() {
        val pts = (0 until 40).map {
            val t = it / 39.0
            Px(100 + t * 700, 500 + sin(t * 6) * 180)
        }
        val shape = assertNotNull(Shapes.fitShape(pts))
        assertTrue(shape is Shapes.Shape.Curve)
    }

    @Test
    fun `a dot is not a shape at all`() {
        val pts = (0 until 10).map { Px(500.0 + it * 0.2, 500.0) }
        assertNull(Shapes.fitShape(pts))
    }

    @Test
    fun `holding drives one parameter, and holding still changes nothing`() {
        // FACT (C.9): hold to adjust length (lines), curvature (curves), or
        // press-hold-drag to size a circle
        val line = Shapes.Shape.Line(Px(0.0, 0.0), Px(100.0, 0.0))
        Shapes.adjust(line, Px(100.0, 0.0), 250.0, 0.0)
        assertEquals(250.0, line.b.x, 1e-12, "a line should follow its far endpoint")

        val circle = Shapes.Shape.Circle(500.0, 500.0, 100.0)
        Shapes.adjust(circle, Px(600.0, 500.0), 700.0, 500.0)
        assertEquals(200.0, circle.r, 1e-12, "a circle should size from its centre")

        val base = (0..10).map { Px(it * 10.0, 0.0) }
        val curve = Shapes.Shape.Curve(base)
        val anchor = Px(100.0, 0.0)
        Shapes.adjust(curve, anchor, 100.0, 0.0)
        assertEquals(0.0, curve.bow, 1e-12, "holding still must leave the curve alone")

        Shapes.adjust(curve, anchor, 100.0, 50.0)
        assertTrue(kotlin.math.abs(curve.bow) > 0.0, "dragging across should bow it")
        // ...and the bow is an even parabola: the ends stay put
        val pts = curve.points
        assertEquals(base.first().y, pts.first().y, 1e-12)
        assertEquals(base.last().y, pts.last().y, 1e-12)
        assertTrue(kotlin.math.abs(pts[pts.size / 2].y) > 10.0, "the middle should have moved")
    }

    @Test
    fun `the line fit measures deviation as a fraction, so the gate scales`() {
        // the same shape at two sizes must be judged the same way
        fun bent(scale: Double) = (0 until 20).map {
            val t = it / 19.0
            Px(t * 100 * scale, sin(t * PI) * 5 * scale)
        }
        val small = assertNotNull(Shapes.fitLine(bent(1.0)))
        val large = assertNotNull(Shapes.fitLine(bent(10.0)))
        assertEquals(small.deviation, large.deviation, 1e-12)
        assertEquals(10.0, large.length / small.length, 1e-9)
    }
}
