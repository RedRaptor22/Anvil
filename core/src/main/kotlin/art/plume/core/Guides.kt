package art.plume.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Making guide surfaces, and rebuilding them after they change.
 *
 * Ported from the creation half of `js/guides.js`. This is the Feather
 * premise: you draw a surface, then you draw on it. There are three ways to
 * get one, and they are genuinely different rather than variations —
 *
 *  - [createFromStroke] treats the stroke as a PROFILE and extrudes it away
 *    along the view, which is why drawing a circle gives you a tube;
 *  - [createFlatFromStroke] treats it as an OUTLINE and fills it in place,
 *    so what you drew is what you get, flat, in the plane you drew it on;
 *  - [Primitives] hands you a readymade.
 */
object Guides {

    // ---- a swept guide, from the first stroke  (A.1) --------------------

    /**
     * Extrude [worldPts] along the view direction into a surface.
     *
     * [orbitRadius] is the camera's distance to its pivot, and it is here for
     * one reason: the extrusion depth is scaled off the stroke but FLOORED
     * against the current view, so a small stroke in a big scene still gives a
     * guide you can orbit around and draw on rather than a sliver.
     */
    fun createFromStroke(
        worldPts: List<Vec3>,
        viewDir: Vec3,
        camRight: Vec3,
        orbitRadius: Double,
    ): Guide? {
        if (worldPts.size < 2) return null
        val profile = Polyline.resample(
            worldPts, min(Tune.GUIDE_PROFILE_SEG, max(8, worldPts.size * 2)),
        )
        if (profile.size < 2) return null

        val t0 = viewDir.copy().normalize()
        val r0 = camRight.copy().normalize()
        val s0 = (t0 cross r0).normalize()

        // the anchor is the profile's centroid; local coordinates hang off it
        val anchor = Polyline.centroid(profile)

        val local = ArrayList<Vec3>(profile.size)
        val d = Vec3()
        var extent = 0.0
        for (p in profile) {
            d.set(p - anchor)
            local.add(Vec3(d dot r0, d dot s0, d dot t0))
            extent = max(extent, d.length())
        }

        val depth = clamp(
            max(extent * 2 * Tune.GUIDE_DEPTH_FACTOR, orbitRadius * Tune.GUIDE_DEPTH_OF_VIEW),
            Tune.GUIDE_DEPTH_MIN, Tune.GUIDE_DEPTH_MAX,
        )
        val front = depth * Tune.GUIDE_DEPTH_FRONT

        val nSeg = Tune.GUIDE_PATH_SEG
        val anchorIndex = (nSeg * (front / (front + depth))).roundToInt()
        val path = ArrayList<Vec3>(nSeg + 1)
        for (j in 0..nSeg) {
            val tt = -front + (front + depth) * (j.toDouble() / nSeg)
            path.add(anchor.copy().addScaled(t0, tt))
        }
        // land the anchor row exactly on the stroke, so the orange line sits on it
        path[anchorIndex].set(anchor)

        return fromSweep(
            Sweep(local, anchor, anchorIndex, path, r0, t0, depth),
        )
    }

    /** Build a guide around an existing sweep, or rebuild one after a change. */
    fun fromSweep(sweep: Sweep): Guide? {
        val g = Guide(Guide.freshId(), GuideKind.DRAW)
        g.sweep = sweep
        return if (rebuildSweep(g)) g else null
    }

    /**
     * Lay the profile down along the path, row by row.
     *
     * Ported from `evalSweep`. The frames are rotation-minimising, which is the
     * whole point: a Frenet frame would flip the profile over at an inflection
     * and put a crease down the middle of the surface.
     */
    fun evalSweep(sweep: Sweep): List<List<Vec3>> {
        val path = sweep.path
        val t0 = Frames.computeTangents(path)[0]
        val frames = Frames.transportFrames(path, sweep.seedFor(t0), false)

        val rows = ArrayList<List<Vec3>>(path.size)
        val s = Vec3()
        for (j in path.indices) {
            val tv = frames.t[j]; val rv = frames.r[j]
            s.set(tv cross rv)
            val row = ArrayList<Vec3>(sweep.local.size)
            for (l in sweep.local) {
                row.add(
                    Vec3(
                        path[j].x + rv.x * l.x + s.x * l.y + tv.x * l.z,
                        path[j].y + rv.y * l.x + s.y * l.y + tv.y * l.z,
                        path[j].z + rv.z * l.x + s.z * l.y + tv.z * l.z,
                    ),
                )
            }
            rows.add(row)
        }
        return rows
    }

    fun rebuildSweep(guide: Guide): Boolean {
        val sweep = guide.sweep ?: return false
        val rows = evalSweep(sweep)
        val surface = SurfaceGrid.build(rows) ?: return false
        guide.surface = surface
        /* FACT (A.3): the orange line marks the guide's starting point, and is
           the anchor that bending works from. */
        guide.anchorRow = rows.getOrNull(sweep.anchorIndex)
        return true
    }

    // ---- a flat guide: the shape you drew, facing you -------------------

    /**
     * The plane basis for a guide facing [normal], with [camRight] projected
     * into it so the surface's own u axis runs the way the screen's does —
     * otherwise "across" on the guide would have nothing to do with across on
     * the glass.
     */
    fun planeBasis(normal: Vec3, camRight: Vec3): Triple<Vec3, Vec3, Vec3> {
        val n = normal.copy().normalize()
        val r = camRight.copy().addScaled(n, -(camRight dot n))
        if (r.lengthSq() < Vec3.EPS) Vec3.perpTo(n, r) else r.normalize()
        val up = (n cross r).normalize()
        return Triple(n, r, up)
    }

    fun createFlatFromStroke(worldPts: List<Vec3>, viewDir: Vec3, camRight: Vec3): Guide? {
        if (worldPts.size < 3) return null
        val (normal, right, up) = planeBasis(viewDir, camRight)

        val mid = Polyline.centroid(worldPts)
        val d = Vec3()
        val raw = ArrayList<UV>(worldPts.size)
        var minU = Double.MAX_VALUE; var minV = Double.MAX_VALUE
        var maxU = -Double.MAX_VALUE; var maxV = -Double.MAX_VALUE
        for (p in worldPts) {
            d.set(p - mid)
            val u = d dot right
            val v = d dot up
            val last = raw.lastOrNull()
            if (last != null &&
                kotlin.math.abs(u - last.u) < 1e-9 && kotlin.math.abs(v - last.v) < 1e-9
            ) continue
            raw.add(UV(u, v))
            if (u < minU) minU = u
            if (u > maxU) maxU = u
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }
        if (raw.size < 3) return null
        val lu = maxU - minU
        val lv = maxV - minV
        if (!(lu > Vec3.EPS) || !(lv > Vec3.EPS)) return null

        /* The origin goes to the CORNER of the bounding box, so u and v run
           0..Lu and 0..Lv and read like the arc lengths every other guide
           hands out. Everything downstream — Fill, the nib trim, the outline
           test — then works in one coordinate convention. */
        val origin = mid.copy().addScaled(right, minU).addScaled(up, minV)
        val outline = raw.map { UV(it.u - minU, it.v - minV) }

        val g = Guide(Guide.freshId(), GuideKind.FLAT)
        g.plane = PlaneData(origin, right, up, normal, lu, lv, outline)
        return if (rebuildFlat(g)) g else null
    }

    fun rebuildFlat(guide: Guide): Boolean {
        val pl = guide.plane ?: return false
        val outline = Triangulate.dedupe(pl.outline)
        if (outline.size < 3) return false
        val tris = Triangulate.earClip(outline)
        if (tris.isEmpty()) return false

        val n = outline.size
        val pos = FloatArray(n * 3)
        val nor = FloatArray(n * 3)
        val uv = FloatArray(n * 2)
        val p = Vec3()
        for (i in 0 until n) {
            val q = outline[i]
            p.set(pl.origin).addScaled(pl.right, q.u).addScaled(pl.up, q.v)
            pos[i * 3] = p.x.toFloat(); pos[i * 3 + 1] = p.y.toFloat(); pos[i * 3 + 2] = p.z.toFloat()
            nor[i * 3] = pl.normal.x.toFloat()
            nor[i * 3 + 1] = pl.normal.y.toFloat()
            nor[i * 3 + 2] = pl.normal.z.toFloat()
            uv[i * 2] = q.u.toFloat(); uv[i * 2 + 1] = q.v.toFloat()
        }
        guide.surface = GuideSurface(
            pos, nor, uv, tris, nu = 0, nv = 0, lu = pl.lu, lv = pl.lv, outline = outline,
        )
        return true
    }
}
