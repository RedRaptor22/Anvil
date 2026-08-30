package art.plume.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The incremental stroke buffer against the batch build.
 *
 * The whole value of [LiveStroke] rests on one claim: what you see while the
 * pen is down is what you get when you lift it. That is asserted here by
 * building the same stroke both ways and comparing every float, rather than by
 * looking at it.
 */
class LiveStrokeTest {

    /**
     * Deliberately UNEVENLY spaced. Even spacing hides the difference between a
     * bisector tangent and a central difference — the two agree exactly when
     * the chords either side are the same length — so an even test curve would
     * pass whichever of the two the live path used, which is no test at all.
     */
    private fun sample(i: Int): Vec3 {
        val t = i * 0.06 + 0.02 * sin(i * 1.7)
        return Vec3(cos(t) * 0.5, t * 0.12, sin(t) * 0.5)
    }

    private fun strokeOf(brush: String, n: Int): Stroke {
        val s = Stroke(brush = brush, baseRadius = 14.0 * MM * 0.5)
        for (i in 0 until n) s.pts.add(StrokePoint(sample(i)))
        return s
    }

    private fun drawIncrementally(brush: String, n: Int): Pair<Stroke, LiveStroke> {
        val s = Stroke(brush = brush, baseRadius = 14.0 * MM * 0.5)
        val live = LiveStroke()
        live.begin(s)
        for (i in 0 until n) {
            s.pts.add(StrokePoint(sample(i)))
            live.append(s)
        }
        return s to live
    }

    @Test
    fun `every brush's live buffer is exactly the geometry the commit builds`() {
        for (brush in Brushes.table.keys) {
            val (stroke, live) = drawIncrementally(brush, 40)
            val mesh = assertNotNull(StrokeGeometry.build(stroke), "no mesh for $brush")

            assertEquals(mesh.vertexCount, live.vertexCount, "$brush vertex count")
            assertEquals(mesh.indices.size, live.indexCount, "$brush index count")

            for (i in 0 until mesh.vertexCount * 3) {
                assertEquals(mesh.positions[i], live.positions[i], "$brush position[$i]")
                assertEquals(mesh.normals[i], live.normals[i], "$brush normal[$i]")
            }
            for (i in 0 until mesh.vertexCount * 4) {
                assertEquals(mesh.colors[i], live.colors[i], "$brush colour[$i]")
            }
            for (i in mesh.indices.indices) {
                assertEquals(mesh.indices[i], live.indices[i], "$brush index[$i]")
            }
        }
    }

    @Test
    fun `it still matches after the buffer has had to grow several times`() {
        // 64 is the opening capacity and it grows by 1.8x, so 400 crosses it
        // four times — the copy on each growth is where a stale tail would show
        val (stroke, live) = drawIncrementally("taper", 400)
        val mesh = assertNotNull(StrokeGeometry.build(stroke))
        assertEquals(mesh.vertexCount, live.vertexCount)
        assertEquals(mesh.indices.size, live.indexCount)
        for (i in 0 until mesh.vertexCount * 3) {
            assertEquals(mesh.positions[i], live.positions[i], "position[$i]")
        }
        for (i in mesh.indices.indices) assertEquals(mesh.indices[i], live.indices[i])
    }

    /**
     * The guard that stops the test above passing vacuously.
     *
     * If a bisector and a central difference gave the same answer on this
     * curve, matching the batch build would prove nothing about which one the
     * live path used. They do not: this measures the gap, so the exactness
     * asserted above is a real constraint.
     */
    @Test
    fun `a central difference tangent would visibly disagree, which is why this curve is uneven`() {
        val pts = (0 until 40).map { sample(it) }
        val bisector = Frames.computeTangents(pts, false)

        var worst = 0.0
        for (i in 1 until pts.size - 1) {
            val central = (pts[i + 1] - pts[i - 1]).normalize()
            val cosang = clamp(central dot bisector[i], -1.0, 1.0)
            worst = maxOf(worst, Math.toDegrees(kotlin.math.acos(cosang)))
        }
        assertTrue(
            worst > 0.05,
            "the two tangent rules differ by only $worst degrees here, so the " +
                "exact-match test would not detect the web build's rule",
        )
    }

    @Test
    fun `appending touches a bounded tail, not the whole stroke`() {
        val s = Stroke(brush = "pen", baseRadius = 14.0 * MM * 0.5)
        val live = LiveStroke()
        live.begin(s)
        var widest = 0
        for (i in 0 until 200) {
            s.pts.add(StrokePoint(sample(i)))
            live.clearDirty()
            live.append(s)
            if (i >= 4) widest = maxOf(widest, live.dirtyTo - live.dirtyFrom)
        }
        /*
         * An untapered brush can only have changed the last two rings. The
         * bound is what makes drawing linear rather than quadratic; if this
         * grows, a long stroke has started rewriting itself.
         *
         * The cap centres are counted separately on purpose — they sit at the
         * head of the buffer and move on every append, so folding them into
         * this range would make it span the whole stroke and this assertion
         * would be measuring nothing.
         */
        assertTrue(
            widest <= 2 * live.seg,
            "an append dirtied $widest ring vertices, more than the last two rings",
        )
        assertTrue(live.capsDirty, "the end cap moved and should be marked dirty")
        // and the index upload is bounded too: one new band plus the end fan
        assertTrue(
            live.indexDirtyTo - live.indexDirtyFrom <= live.seg * 9,
            "an append dirtied ${live.indexDirtyTo - live.indexDirtyFrom} indices",
        )
    }

    @Test
    fun `a taper reaches further back than the last two rings, because its end moved`() {
        val s = Stroke(brush = "taper", baseRadius = 60.0 * MM * 0.5)
        val live = LiveStroke()
        live.begin(s)
        var widest = 0
        for (i in 0 until 60) {
            s.pts.add(StrokePoint(sample(i)))
            live.clearDirty()
            live.append(s)
            widest = maxOf(widest, live.dirtyTo - live.dirtyFrom)
        }
        // the taper is measured from both ends, so growing the stroke moves the
        // tail taper and the rings inside its reach have to be rewritten
        assertTrue(
            widest > 2 * live.seg,
            "a tapered brush only dirtied $widest vertices — the taper reach is not being walked back",
        )
    }

    @Test
    fun `the live buffer stays open, and the commit is what welds a loop shut`() {
        /*
         * A stroke is not closed until it is finished, so the preview is always
         * an open tube with caps. The batch build detects the loop from the
         * geometry and welds it — which is why commit rebuilds rather than
         * keeping the live buffer, and why this is pinned rather than left to
         * chance.
         */
        val s = Stroke(brush = "pen", baseRadius = 14.0 * MM * 0.5)
        val live = LiveStroke()
        live.begin(s)
        val n = 24
        for (i in 0 until n) {
            val a = i.toDouble() / (n - 1) * 2 * Math.PI
            s.pts.add(StrokePoint(Vec3(cos(a) * 0.4, 0.0, sin(a) * 0.4)))
            live.append(s)
        }
        // last point is the first again, to within the loop tolerance
        s.pts[n - 1].p.set(s.pts[0].p)
        live.append(s)

        assertTrue(Frames.loopsClosed(s.pts.map { it.p }), "the test curve is not a loop")
        val mesh = assertNotNull(StrokeGeometry.build(s))
        assertTrue(live.caps, "the live preview should still be capped")
        assertTrue(
            mesh.vertexCount < live.vertexCount,
            "the welded ring should cost fewer vertices than the open preview",
        )
    }

    @Test
    fun `a stroke that opens with coincident samples still matches the batch build`() {
        /*
         * While every sample so far sits on the same spot, point 0's tangent is
         * resolved by looking AHEAD — so a later sample can still change it,
         * and the "everything behind the window is final" shortcut is not safe
         * yet. This is the case that shortcut would silently get wrong.
         */
        val s = Stroke(brush = "pen", baseRadius = 14.0 * MM * 0.5)
        val live = LiveStroke()
        live.begin(s)
        val pts = ArrayList<Vec3>()
        pts.add(Vec3(0.1, 0.0, 0.0))
        pts.add(Vec3(0.1, 0.0, 0.0))          // exactly coincident
        pts.add(Vec3(0.1, 0.0, 0.0))
        for (i in 3 until 20) pts.add(sample(i))
        for (p in pts) { s.pts.add(StrokePoint(p)); live.append(s) }

        val mesh = assertNotNull(StrokeGeometry.build(s))
        assertEquals(mesh.vertexCount, live.vertexCount)
        for (i in 0 until mesh.vertexCount * 3) {
            assertEquals(mesh.positions[i], live.positions[i], "position[$i]")
            assertEquals(mesh.normals[i], live.normals[i], "normal[$i]")
        }
    }
}
