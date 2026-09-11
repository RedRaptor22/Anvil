package art.plume.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * PAINT SITS ON WHAT YOU PAINT ON.
 *
 * Reported from a device: "the curves made by any brush sits in between the
 * guide's surface". They did. The cross-section was CENTRED on the sample, so
 * half of every curve was inside the guide it was painted on — a pen stroke
 * sunk to its waist, showing half the thickness it was asked for, and a wide
 * ribbon with the surface cutting through the middle of it. Two of the eight
 * brushes opted out; the other six did not.
 *
 * These measure the built mesh against the surface normal, which is the only
 * way to check it: the rule lives in the geometry, not in a flag anyone can
 * read back. For each vertex, its height above the surface is its offset from
 * the sample dotted with the normal — so "stands on it" means every height in
 * [0, thickness] and "straddles it" means they run from -half to +half.
 */
class StandsOnTest {

    /** A straight stroke lying on a surface whose normal is [n]. */
    private fun onSurface(brush: String, n: Vec3?, radius: Double = 0.01): Stroke {
        val pts = (0 until 10).map { Vec3(it * 0.05, 0.0, 0.0) }
        val s = Stroke(brush = brush, baseRadius = radius)
        if (n != null) s.guideId = 3
        val f = Frames.transportFrames(pts, null, false)
        pts.forEachIndexed { i, p ->
            s.pts.add(
                StrokePoint(
                    p.copy(), pressure = 1.0, nrm = n?.copy(),
                    tan = f.t[i].copy(), ref = f.r[i].copy(),
                ),
            )
        }
        Nib.freezeFrames(s)
        return s
    }

    /** Every vertex's height above the surface through the middle sample. */
    private fun heights(s: Stroke, n: Vec3): Pair<Double, Double> {
        val m = StrokeGeometry.build(s)!!
        val mid = s.pts[s.pts.size / 2].p
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (i in 0 until m.vertexCount) {
            val v = Vec3(
                m.positions[i * 3].toDouble(),
                m.positions[i * 3 + 1].toDouble(),
                m.positions[i * 3 + 2].toDouble(),
            )
            /* only the ring near the middle: the ends taper and the caps sit
               at their own centres, and neither says anything about height */
            if (abs(v.x - mid.x) > 0.03) continue
            val h = (v - mid) dot n
            if (h < lo) lo = h
            if (h > hi) hi = h
        }
        return lo to hi
    }

    @Test
    fun `every brush on a guide stands on the surface rather than in it`() {
        val n = Vec3(0.0, 1.0, 0.0)
        for (brush in Brushes.table.keys) {
            val s = onSurface(brush, n)
            val thick = 2 * StrokeGeometry.halfThick(s, s.baseRadius)
            val (lo, hi) = heights(s, n)
            assertTrue(
                lo > -thick * 0.05,
                "$brush is sunk $lo into the guide; nothing should be below it",
            )
            assertTrue(
                hi > thick * 0.45,
                "$brush only reaches $hi above the guide, of a thickness of $thick",
            )
        }
    }

    @Test
    fun `the full thickness is above the surface, not half of it`() {
        /* the measurement that separates the fix from the fault: straddling
           puts half the thickness above and half below, so a test that only
           checked "something is above the surface" would have passed before */
        val n = Vec3(0.0, 1.0, 0.0)
        val s = onSurface("pen", n)
        val thick = 2 * StrokeGeometry.halfThick(s, s.baseRadius)
        val (lo, hi) = heights(s, n)
        assertTrue(abs(lo) < thick * 0.05, "the base is at $lo, not on the surface")
        assertTrue(
            hi > thick * 0.9,
            "the top is at $hi; a full thickness of $thick should be above the surface",
        )
    }

    @Test
    fun `a curve in free space is still centred on where it was drawn`() {
        /* no guide means no surface to sit on top of — only the place you
           aimed at, which is what every free-space curve in every saved note
           already is */
        val s = onSurface("pen", n = null)
        val thick = 2 * StrokeGeometry.halfThick(s, s.baseRadius)
        val (lo, hi) = heights(s, Vec3(0.0, 1.0, 0.0))
        assertTrue(lo < -thick * 0.4, "free space should straddle; the base is at $lo")
        assertTrue(hi > thick * 0.4, "free space should straddle; the top is at $hi")
    }

    /**
     * AND IT STANDS ON THE SIDE THE PEN WAS ON.
     *
     * Standing on the surface is only half an answer: a guide is a sheet you
     * orbit around, and the normal its winding hands out has no relation to
     * where you are standing. Extruded along that, half the ink stands BEHIND
     * the guide it was painted on — hidden by the very thing it is supposed to
     * be lying on, which is the fault it was meant to cure.
     *
     * Both sides are probed, of the same guide, at the same point. A guide
     * that answered from its winding would give the same normal twice; one
     * that answers from the pen gives opposite ones.
     */
    @Test
    fun `a guide reports the face the pen was on, from either side`() {
        val profile = (0 until 24).map {
            val a = -0.9 + it.toDouble() / 23 * 1.8
            Vec3(a * 0.35, kotlin.math.sin(a * 1.4) * 0.18, 0.0)
        }
        val g = Guides.createFromStroke(
            profile, Vec3(0.0, 0.0, -1.0), Vec3(1.0, 0.0, 0.0), 4.0,
        )!!

        var probed = 0
        for (t in 0 until g.surface!!.mesh.triangleCount step 7) {
            val a = Vec3(); val b = Vec3(); val c = Vec3()
            g.surface!!.mesh.triangle(t, a, b, c)
            val centre = Vec3((a.x + b.x + c.x) / 3, (a.y + b.y + c.y) / 3, (a.z + b.z + c.z) / 3)
            val face = ((b - a) cross (c - a))
            if (face.lengthSq() < Vec3.EPS) continue
            face.normalize()

            /* stand off the surface along its own normal, and again on the
               other side, and aim back at the same spot */
            for (side in listOf(1.0, -1.0)) {
                val eye = centre.copy().addScaled(face, side * 2.0)
                val dir = (centre - eye)
                if (dir.lengthSq() < Vec3.EPS) continue
                dir.normalize()
                val hit = GuidePainting.project(g, Ray(eye, dir), clampOffSurface = false)
                    ?: continue
                probed++
                assertTrue(
                    (hit.normal dot dir) <= 1e-9,
                    "the reported normal ${hit.normal} faces away from an eye at $eye " +
                        "looking along $dir — ink built along it would stand behind " +
                        "the guide",
                )
            }
        }
        assertTrue(probed > 8, "only $probed samples landed; the probe is not exercising it")
    }

    @Test
    fun `the two brushes that always rise are unaffected off a guide`() {
        for (brush in listOf("cube", "wide")) {
            val s = onSurface(brush, n = null)
            val thick = 2 * StrokeGeometry.halfThick(s, s.baseRadius)
            val (lo, _) = heights(s, Vec3(0.0, 1.0, 0.0))
            assertTrue(
                lo > -thick * 0.05,
                "$brush is documented as standing on whatever it is on, and is at $lo",
            )
        }
    }
}
