package art.plume.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FACT (A.6): "Every 3D Guide you create in Feather starts with a starting
 * point, marked by an orange line... the 3D Guide will bend along the drawn
 * line... bending starts from the orange line", and the worked example is
 * "drawing the side of a pot, then bending it into a cylinder".
 *
 * That example is a CLOSED path, which is why it is the one asserted hardest
 * here: the seam is where a bend shows its working.
 */
class BendTest {

    private val view = Vec3(0.0, 0.0, -1.0)
    private val right = Vec3(1.0, 0.0, 0.0)

    /** The side of a pot: a curve in the screen plane. */
    private fun potProfile(): List<Vec3> =
        (0 until 30).map { i ->
            val t = i / 29.0
            Vec3(0.05 + 0.03 * sin(t * Math.PI), -0.1 + 0.2 * t, 0.0)
        }

    /** A ring in the XZ plane, drawn as a hand would: ends near, not equal. */
    private fun ring(r: Double, n: Int = 48, gap: Double = 0.02): List<Vec3> =
        (0 until n).map { i ->
            val a = (2 * Math.PI - gap) * i / (n - 1)
            Vec3(r * sin(a), 0.0, r * (1 - cos(a)))
        }

    private fun rowsOf(g: Guide): List<List<Vec3>> = Guides.evalSweep(g.sweep!!)

    @Test
    fun `a hand-drawn ring reads as closed and a hook does not`() {
        assertTrue(Guides.pathIsClosed(ring(0.2)), "a ring the hand closed")
        val hook = (0 until 20).map { i -> Vec3(i * 0.01, 0.0, 0.0) } +
            (0 until 20).map { i -> Vec3(0.19 - i * 0.01, 0.02, 0.0) }
        assertFalse(Guides.pathIsClosed(hook), "a hairpin is not a ring")
    }

    @Test
    fun `bending a pot into a cylinder closes the seam`() {
        val g = Guides.createFromStroke(potProfile(), view, right, 1.0)!!
        assertTrue(GuideEditing.bend(g, ring(0.2)))

        val rows = rowsOf(g)
        val first = rows.first()
        val last = rows.last()
        assertEquals(first.size, last.size)

        /*
         * THE WHOLE POINT. Swept as an open path the two ends of the pot were
         * a visible step apart: the path's own ends are a pen's width from
         * each other, and transport round the loop had left the section
         * rotated on top of that.
         */
        var worst = 0.0
        for (i in first.indices) worst = maxOf(worst, first[i].distanceTo(last[i]))
        assertEquals(0.0, worst, 1e-9, "the seam of a closed bend is welded shut")
    }

    @Test
    fun `an open bend still runs from one end to the other`() {
        val g = Guides.createFromStroke(potProfile(), view, right, 1.0)!!
        val arc = (0 until 40).map { i ->
            val a = Math.PI * 0.5 * i / 39.0
            Vec3(0.3 * sin(a), 0.0, 0.3 * (1 - cos(a)))
        }
        assertTrue(GuideEditing.bend(g, arc))

        val rows = rowsOf(g)
        var worst = 0.0
        for (i in rows.first().indices) {
            worst = maxOf(worst, rows.first()[i].distanceTo(rows.last()[i]))
        }
        assertTrue(worst > 0.05, "an arc is not a ring and must not be welded into one")
    }

    @Test
    fun `the bend starts at the orange line, wherever the stroke was drawn`() {
        val g = Guides.createFromStroke(potProfile(), view, right, 1.0)!!
        /*
         * The orange line's own start — the profile's first point, the dot the
         * documentation marks "Starting point". NOT the profile's centroid,
         * which this used to measure: that sits half a profile out in the
         * middle of the surface, marks nothing, and is not what A.6 names.
         */
        val orange = g.anchorRow!!.first().copy()

        // a bend stroke drawn a long way from the guide
        val far = (0 until 30).map { i -> Vec3(5.0 + i * 0.01, 3.0, -2.0) }
        assertTrue(GuideEditing.bend(g, far))

        val path = g.sweep!!.path
        assertEquals(
            0.0, path[0].distanceTo(orange), 1e-9,
            "the path starts at the orange line",
        )
        assertEquals(0, g.sweep!!.anchorIndex, "and the orange line is that start")
    }

    /**
     * THE ORANGE LINE IS A RAIL, NOT A SECTION.
     *
     * The documentation draws it running the LENGTH of the surface from the
     * dot marked "Starting point" — so it is the path traced by the profile's
     * first point, one point per section, and it lies on an edge of the
     * surface. A section across the guide would be the profile's own shape,
     * which is a different curve entirely and is what `anchorRow` holds.
     */
    @Test
    fun `the orange line runs the length of the guide, along the profile's start`() {
        val g = Guides.createFromStroke(potProfile(), view, right, 1.0)!!
        val sw = g.sweep!!
        val rows = rowsOf(g)
        val line = g.startLine!!

        assertEquals(sw.path.size, line.size, "one point per section, down the sweep")
        for (j in rows.indices) {
            assertEquals(
                0.0, line[j].distanceTo(rows[j].first()), 1e-12,
                "the orange line is the rail through the profile's first point",
            )
        }
        /* and it starts where the pen went down, which is the row the guide
           was drawn on — the dot the documentation labels */
        assertEquals(
            0.0, line[sw.anchorIndex].distanceTo(g.anchorRow!!.first()), 1e-12,
            "the orange line passes through the guide's starting point",
        )
    }

    /**
     * THE GUIDE DOES NOT LEAP WHEN IT BENDS.
     *
     * FACT (A.6): "The bending starts from the orange line, which is the
     * starting point of the 3D Guide." So the surface's starting point is
     * still on the orange line when the bend is over — the guide hinges
     * there, it does not jump off it.
     *
     * It used to hinge on the profile's CENTROID instead, so the surface slid
     * out by the distance from the centroid to the edge: on a profile one
     * unit long that was three quarters of a unit, away from a stroke started
     * exactly on the line the documentation says to start from.
     */
    @Test
    fun `the guide's starting point stays on the orange line through a bend`() {
        val g = Guides.createFromStroke(potProfile(), view, right, 1.0)!!
        val orange = g.anchorRow!!.first().copy()

        val stroke = (0 until 24).map { i ->
            val t = i / 23.0
            Vec3(orange.x + t * 0.9, orange.y + sin(t * 2.0) * 0.2, orange.z)
        }
        assertTrue(GuideEditing.bend(g, stroke))

        /* row 0's first point IS the guide's starting point: the profile is
           hung off its own start, so it rides the drawn line */
        val start = rowsOf(g).first().first()
        assertEquals(
            0.0, start.distanceTo(orange), 1e-9,
            "the guide leapt ${start.distanceTo(orange)} off the orange line",
        )
    }

    @Test
    fun `bending twice is bending the second stroke, not both of them`() {
        val a = Guides.createFromStroke(potProfile(), view, right, 1.0)!!
        val b = Guides.createFromStroke(potProfile(), view, right, 1.0)!!

        val first = (0 until 30).map { i -> Vec3(i * 0.01, 0.0, 0.0) }
        val second = (0 until 30).map { i ->
            val t = i / 29.0
            Vec3(0.3 * sin(t * 2), 0.1 * t, 0.3 * (1 - cos(t * 2)))
        }
        assertTrue(GuideEditing.bend(a, first))
        assertTrue(GuideEditing.bend(a, second))
        assertTrue(GuideEditing.bend(b, second))

        val ra = rowsOf(a)
        val rb = rowsOf(b)
        var worst = 0.0
        for (j in ra.indices) for (i in ra[j].indices) {
            worst = maxOf(worst, ra[j][i].distanceTo(rb[j][i]))
        }
        assertEquals(0.0, worst, 1e-9, "a second bend replaces the first, it does not compound")
    }

    @Test
    fun `a welded ring has no zero-area quads at the seam`() {
        val g = Guides.createFromStroke(potProfile(), view, right, 1.0)!!
        assertTrue(GuideEditing.bend(g, ring(0.2)))
        val s = g.surface!!

        /* A weld that collapsed the last row onto the first would leave a band
           of degenerate triangles rather than a joint — the surface would look
           right and shade wrong. */
        var degenerate = 0
        for (j in 0 until s.nv - 1) for (i in 0 until s.nu - 1) {
            fun at(ii: Int, jj: Int): Vec3 {
                val k = (jj * s.nu + ii) * 3
                return Vec3(
                    s.positions[k].toDouble(),
                    s.positions[k + 1].toDouble(),
                    s.positions[k + 2].toDouble(),
                )
            }
            val n = (at(i + 1, j) - at(i, j)) cross (at(i, j + 1) - at(i, j))
            if (n.lengthSq() < 1e-16) degenerate++
        }
        assertTrue(
            degenerate <= s.nu,
            "a welded seam should not fill the mesh with slivers (got $degenerate)",
        )
    }
    // ---- the three faults the screenshots showed -------------------------

    /** Wall on the LEFT, floor running right — an asymmetric section. */
    private fun channel(): List<Vec3> {
        val out = ArrayList<Vec3>()
        for (i in 0 until 20) out.add(Vec3(-0.10, -0.05 + 0.10 * i / 19.0, 0.0))
        for (i in 1 until 20) out.add(Vec3(-0.10 + 0.20 * i / 19.0, 0.05, 0.0))
        return out
    }

    /** Which way the wall points across the screen, at the orange row. */
    private fun wallSide(g: Guide): Double {
        val rows = Guides.evalSweep(g.sweep!!)
        val a = rows[g.sweep!!.anchorIndex]
        return (a[0] - a[a.size - 1]) dot right
    }

    /**
     * The section's own UP, at the start of the sweep — the axis a roll-over
     * inverts, and the one the wall measurement cannot see.
     */
    private fun sectionUp(g: Guide): Vec3 {
        val sw = g.sweep!!
        val closed = Guides.pathIsClosed(sw.path)
        val t = Frames.computeTangents(sw.path, closed)[0]
        val fr = Frames.transportFrames(sw.path, sw.seedFor(t), closed)
        return fr.t[0] cross fr.r[0]
    }

    /**
     * THE SECTION IS NEVER ROLLED OVER, BEND WHERE YOU LIKE.
     *
     * This used to measure which side of the SCREEN the wall came out on and
     * demand it stay left in all twenty-four directions. That is the wrong
     * axis, and demanding it hid the fault it was meant to catch.
     *
     * `s = t cross r`, so the side the wall lands on and the way up the
     * section sits are the SAME degree of freedom: fixing one fixes the other.
     * Forcing the wall to stay left for a path bending into the screen can
     * only be done by rolling the section a half turn — and that is exactly
     * what the old seed did, for every direction in that family, measured
     * here as `s · s0 = -1` throughout. The wall stayed put and the section
     * came out upside down, which is invisible to a channel drawn symmetric
     * about its floor and extremely visible on a pot: the rim ended up
     * underneath, with the orange line beneath the body instead of round the
     * top of it. That is what came back from the device.
     *
     * So the property asserted is the one that was actually meant — the
     * section does not turn over — stated on the axis that turns. The wall's
     * screen side is no longer pinned, because a surface bending away from
     * you SHOULD present its wall to the other side; that is the sweep
     * turning round, not the profile rolling.
     */
    @Test
    fun `the section is never rolled over, bend where you like`() {
        val drawn = Guides.createFromStroke(channel(), view, right, 1.0)!!
        assertTrue(wallSide(drawn) < 0.0, "the wall was drawn on the left")
        val up0 = sectionUp(drawn)

        for (deg in 0 until 360 step 15) {
            val a = Math.toRadians(deg.toDouble())
            for (dir in listOf(
                Vec3(cos(a), sin(a), 0.0),      // across the screen
                Vec3(cos(a), 0.0, sin(a)),      // and into or out of it
            )) {
                val path = (0 until 30).map { i ->
                    Vec3(dir.x * 0.02 * i, dir.y * 0.02 * i, dir.z * 0.02 * i)
                }
                val g = Guides.createFromStroke(channel(), view, right, 1.0)!!
                assertTrue(GuideEditing.bend(g, path))
                val dot = sectionUp(g) dot up0
                assertTrue(
                    dot >= -1e-9,
                    "bending $deg deg along $dir rolled the section over ($dot)",
                )
            }
        }
    }

    /**
     * A POT BENT INTO A RING STANDS UP THE WAY IT WAS DRAWN.
     *
     * The documented worked example, and the one that came back from the
     * device upside down: a profile drawn from the rim downwards, bent round
     * a circle, must still hang DOWNWARDS from the rim. The orange line is
     * the rim, so it belongs round the top of the pot and not underneath it.
     *
     * The circle's opening tangent runs along the profile's own right, which
     * is where projecting `basisR` onto the plane stops being a direction and
     * becomes the remainder of two vectors that cancelled — a few thousandths
     * long, pointing wherever the rounding went. The seed took it, the second
     * axis inverted with it, and the pot built itself the other way up.
     */
    @Test
    fun `a profile bent into a ring keeps the rim on top`() {
        val profile = (0 until 24).map { Vec3(0.0, 0.5 - it / 23.0, 0.0) }
        val g = Guides.createFromStroke(profile, view, right, 1.0)!!
        val rim = g.anchorRow!!.first().copy()

        val rad = 0.6
        val circle = (0 until 48).map {
            val th = it / 47.0 * 2 * PI
            Vec3(rim.x + sin(th) * rad, rim.y, rim.z + (cos(th) - 1.0) * rad)
        }
        assertTrue(GuideEditing.bend(g, circle))

        val row = rowsOf(g).first()
        assertEquals(0.0, row.first().distanceTo(rim), 1e-9, "the rim moved")
        assertTrue(
            row.last().y < row.first().y,
            "the pot is upside down: the body rose to ${row.last().y} above a rim at ${row.first().y}",
        )
        /* and the whole surface hangs below the rim, not above it */
        val top = rowsOf(g).flatten().maxOf { it.y }
        assertTrue(top <= rim.y + 1e-9, "part of the pot stands above its own rim")
    }

    @Test
    fun `a ring the hand left open still welds`() {
        /* The screenshot's notch: a hand lifts the pen before it quite gets
           back, and a tolerance of a twentieth of the path called that open. */
        val short = (0 until 50).map { i ->
            val a = (2 * Math.PI * 0.88) * i / 49.0
            Vec3(0.25 * sin(a), 0.0, 0.25 * (1 - cos(a)))
        }
        assertTrue(Guides.pathIsClosed(short), "a ring 12% short is still a ring")

        val g = Guides.createFromStroke(potProfile(), view, right, 1.0)!!
        assertTrue(GuideEditing.bend(g, short))
        val rows = Guides.evalSweep(g.sweep!!)
        var worst = 0.0
        for (i in rows.first().indices) {
            worst = maxOf(worst, rows.first()[i].distanceTo(rows.last()[i]))
        }
        assertEquals(0.0, worst, 1e-9, "and it is welded, not left with a step")
    }

    @Test
    fun `a turn the profile cannot get round is opened out`() {
        val g = Guides.createFromStroke(channel(), view, right, 1.0)!!
        val reach = 0.1     // the channel reaches 100mm across from its centre

        /* a hairpin far tighter than the section is wide: swept as drawn, the
           inner edge crosses the centre of the turn and comes out inside out,
           which is the spike in the screenshots */
        val hairpin = (0 until 40).map { i ->
            val t = i / 39.0
            val a = Math.PI * t
            Vec3(0.02 * sin(a), 0.0, 0.02 * (1 - cos(a)) - 0.2 * t)
        }
        assertTrue(GuideEditing.bend(g, hairpin))

        val path = g.sweep!!.path
        var tightest = Double.MAX_VALUE
        for (i in 1 until path.size - 1) {
            tightest = minOf(tightest, GuideEditing.circumradius(path[i - 1], path[i], path[i + 1]))
        }
        assertTrue(
            tightest > reach * 0.9,
            "the path still turns inside the profile's reach ($tightest)",
        )
    }

    @Test
    fun `a bend the profile fits round is left where it was drawn`() {
        val g = Guides.createFromStroke(channel(), view, right, 1.0)!!
        val gentle = (0 until 40).map { i ->
            val a = Math.PI * 0.5 * i / 39.0
            Vec3(0.8 * sin(a), 0.0, 0.8 * (1 - cos(a)))
        }
        assertTrue(GuideEditing.bend(g, gentle))

        /* a metre-scale curve is nowhere near the 100mm the section reaches,
           so nothing may be smoothed: the guide has to go where the pen went */
        val want = Polyline.resample(gentle, Tune.GUIDE_PATH_SEG + 1)
        val path = g.sweep!!.path
        val shift = g.sweep!!.anchor - want[0]
        var worst = 0.0
        for (i in path.indices) {
            worst = maxOf(
                worst,
                path[i].distanceTo(
                    Vec3(want[i].x + shift.x, want[i].y + shift.y, want[i].z + shift.z),
                ),
            )
        }
        assertEquals(0.0, worst, 1e-12, "a gentle bend must not be relaxed at all")
    }

    @Test
    fun `a revolve tighter than its profile keeps its size and its weld`() {
        /*
         * The two new behaviours could have fought each other: easing tight
         * turns open is a smoothing pass, and smoothing a loop shrinks it —
         * far enough and a small revolve would come out smaller than it was
         * drawn, or open at the seam. It is bounded on purpose, so this pins
         * what bounded means.
         */
        val profile = (0 until 20).map { i -> Vec3(-0.1 + 0.2 * i / 19.0, 0.0, 0.0) }
        val g = Guides.createFromStroke(profile, view, right, 1.0)!!
        assertTrue(GuideEditing.bend(g, ring(0.06, n = 50, gap = 0.15)))

        val path = g.sweep!!.path
        val c = Polyline.centroid(path)
        var worstR = 0.0
        for (q in path) worstR = maxOf(worstR, kotlin.math.abs(q.distanceTo(c) - 0.06))
        assertTrue(worstR < 0.06 * 0.15, "the ring shrank by more than a seventh ($worstR)")

        val rows = rowsOf(g)
        var seam = 0.0
        for (i in rows.first().indices) {
            seam = maxOf(seam, rows.first()[i].distanceTo(rows.last()[i]))
        }
        assertEquals(0.0, seam, 1e-9, "and it is still welded")
    }

}
