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

/**
 * A point's position in the surface's OWN coordinates.
 *
 * [su] and [sv] are arc lengths in world units, not a normalised pair, so
 * "40 mm across the guide" means the same thing everywhere on it. [uDir] and
 * [vDir] are the world directions those two axes run in at this point, which
 * is what lets a nib be trimmed against the edge of the surface rather than
 * against the screen.
 */
class SurfaceFrame(
    val su: Double,
    val sv: Double,
    val lu: Double,
    val lv: Double,
    /** The real edge, where the guide has one; null for a rectangular sweep. */
    val outline: List<UV>?,
    val uDir: Vec3,
    val vDir: Vec3,
)

/**
 * Where a stroke sample landed. [onSurface] is false when the pen went off the
 * guide and the point was clamped back onto its nearest edge.
 */
class SurfaceSample(
    val point: Vec3,
    val normal: Vec3,
    val frame: SurfaceFrame?,
    val onSurface: Boolean,
)

/** How far a surface runs, and how it is addressed. */
class Span(
    val nu: Int,
    val nv: Int,
    val lu: Double,
    val lv: Double,
    val outline: List<UV>?,
)

/** How far the surface reaches either way along a direction, in world units. */
class Reach(val pos: Double, val neg: Double)

/**
 * Putting paint on a guide: the ray query, the surface's own coordinates, and
 * the edge behaviour.
 *
 * Ported from the query half of `js/guides.js`.
 */
object GuidePainting {

    // ---- the surface as a coordinate system -----------------------------

    /**
     * `grad = (d1 * (e2 x n) + d2 * (n x e1)) / |n|^2`, the gradient of a
     * linear field that rises by [d1] along [e1] and by [d2] along [e2].
     */
    private fun gradientOf(e1: Vec3, e2: Vec3, n: Vec3, d1: Double, d2: Double, out: Vec3): Vec3 {
        val nn = n.lengthSq()
        if (nn < Vec3.EPS) return out.set(0.0, 0.0, 0.0)
        val g1 = e2 cross n
        val g2 = n cross e1
        out.set(g1.x * (d1 / nn), g1.y * (d1 / nn), g1.z * (d1 / nn))
        return out.addScaled(g2, d2 / nn)
    }

    private fun vertexOf(s: GuideSurface, i: Int, out: Vec3): Vec3 = out.set(
        s.positions[i * 3].toDouble(),
        s.positions[i * 3 + 1].toDouble(),
        s.positions[i * 3 + 2].toDouble(),
    )

    /**
     * The surface coordinates of [point], read off one triangle.
     *
     * Null where the surface has no parameterisation at all — the primitives
     * carry no (u, v), because a box and a sphere have no single arc-length
     * grid. That is why Fill does not work on a primitive in either build.
     * Painting on one is unaffected: that goes through the triangle query.
     */
    fun frameOnTriangle(s: GuideSurface, ia: Int, ib: Int, ic: Int, point: Vec3): SurfaceFrame? {
        if (!(s.lu > 0.0) || !(s.lv > 0.0)) return null
        val a = vertexOf(s, ia, Vec3())
        val b = vertexOf(s, ib, Vec3())
        val c = vertexOf(s, ic, Vec3())
        val e1 = b - a
        val e2 = c - a
        val n = e1 cross e2
        if (n.lengthSq() < Vec3.EPS) return null

        val u0 = s.uv[ia * 2].toDouble()
        val v0 = s.uv[ia * 2 + 1].toDouble()
        val gu = gradientOf(e1, e2, n, s.uv[ib * 2] - u0, s.uv[ic * 2] - u0, Vec3())
        val gv = gradientOf(e1, e2, n, s.uv[ib * 2 + 1] - v0, s.uv[ic * 2 + 1] - v0, Vec3())
        if (gu.lengthSq() < Vec3.EPS || gv.lengthSq() < Vec3.EPS) return null

        val rel = point - a
        return SurfaceFrame(
            u0 + (gu dot rel), v0 + (gv dot rel),
            s.lu, s.lv, s.outline,
            gu.copy().normalize(), gv.copy().normalize(),
        )
    }

    /** A flat guide has no grid: it IS a plane, and says so. */
    fun surfaceSpan(guide: Guide): Span? {
        guide.plane?.let { return Span(0, 0, it.lu, it.lv, it.outline) }
        val s = guide.surface ?: return null
        if (!s.hasGrid) return null
        return Span(s.nu, s.nv, s.lu, s.lv, null)
    }

    /**
     * A point on the guide at arc lengths ([su], [sv]), with the normal and the
     * same kind of frame a drawn sample gets — built by the very code painting
     * uses, so a filled row trims at a boundary exactly like a hand-drawn one.
     */
    fun sampleSurface(guide: Guide, su: Double, sv: Double): SurfaceSample? {
        guide.plane?.let { pl ->
            // outside the drawn shape there is no surface, so Fill skips it
            if (!SurfaceGrid.insideOutline(pl.outline, su, sv)) return null
            val pt = pl.origin.copy().addScaled(pl.right, su).addScaled(pl.up, sv)
            return SurfaceSample(
                pt, pl.normal.copy(),
                SurfaceFrame(su, sv, pl.lu, pl.lv, pl.outline, pl.right.copy(), pl.up.copy()),
                onSurface = true,
            )
        }

        val s = guide.surface ?: return null
        if (!s.hasGrid) return null
        val nu = s.nu

        val (cui, cuf) = SurfaceGrid.spanAt({ i -> s.uv[i * 2].toDouble() }, nu, su)
        val (cvi, cvf) = SurfaceGrid.spanAt({ j -> s.uv[(j * nu) * 2 + 1].toDouble() }, s.nv, sv)
        val a = cvi * nu + cui
        val b = a + 1
        val c = a + nu
        val d = c + 1

        val point = blend(s.positions, a, b, c, d, cuf, cvf, Vec3())
        val normal = blend(s.normals, a, b, c, d, cuf, cvf, Vec3())
        if (normal.lengthSq() < Vec3.EPS) normal.set(0.0, 0.0, 1.0) else normal.normalize()

        return SurfaceSample(point, normal, frameOnTriangle(s, a, b, c, point), onSurface = true)
    }

    private fun blend(
        attr: FloatArray, a: Int, b: Int, c: Int, d: Int, fu: Double, fv: Double, out: Vec3,
    ): Vec3 {
        val p00 = Vec3(attr[a * 3].toDouble(), attr[a * 3 + 1].toDouble(), attr[a * 3 + 2].toDouble())
        val p10 = Vec3(attr[b * 3].toDouble(), attr[b * 3 + 1].toDouble(), attr[b * 3 + 2].toDouble())
        val p01 = Vec3(attr[c * 3].toDouble(), attr[c * 3 + 1].toDouble(), attr[c * 3 + 2].toDouble())
        val p11 = Vec3(attr[d * 3].toDouble(), attr[d * 3 + 1].toDouble(), attr[d * 3 + 2].toDouble())
        lerp(p00, p10, fu); lerp(p01, p11, fu)
        return out.set(lerp(p00, p01, fv))
    }

    private fun lerp(a: Vec3, b: Vec3, t: Double): Vec3 {
        a.x += (b.x - a.x) * t; a.y += (b.y - a.y) * t; a.z += (b.z - a.z) * t
        return a
    }

    // ---- putting a pen sample on the guide ------------------------------

    /**
     * Where the pen is on the guide.
     *
     * A hit is a hit. When the ray misses, the sample is clamped back to the
     * guide's nearest point instead — which is what the Clamp setting already
     * promises — unless the guide refuses that. FACT (A.4): an imported image
     * refuses strokes past its edge rather than clamping them, which is the one
     * place clamping is explicitly wrong.
     */
    fun project(guide: Guide, ray: Ray, clampOffSurface: Boolean = true): SurfaceSample? {
        val s = guide.surface ?: return null
        val hit = s.mesh.raycast(ray)
        if (hit != null) {
            val (ia, ib, ic) = s.mesh.triangleIndices(hit.triangle)
            return SurfaceSample(
                hit.point, faceNormal(s, hit.triangle),
                frameOnTriangle(s, ia, ib, ic, hit.point),
                onSurface = true,
            )
        }
        if (guide.noClamp || !clampOffSurface) return null
        return nearestOnGuide(guide, ray)
    }

    /**
     * The closest point on the guide to the ray, for a stroke that ran off it.
     *
     * Found by alternating — nearest surface point to a point on the ray, then
     * nearest ray point to that — which converges in a couple of passes. The
     * web build seeds this from the nearest VERTEX; here it goes straight
     * through the exact triangle query, which is the same correction that had
     * to be made to the snap: on a coarse guide the nearest vertex can belong
     * to a completely different triangle from the one the point is over.
     */
    fun nearestOnGuide(guide: Guide, ray: Ray): SurfaceSample? {
        val s = guide.surface ?: return null
        if (s.mesh.triangleCount == 0) return null

        val target = Vec3()
        val result = Vec3()
        // seed on the ray, level with the middle of the surface
        vertexOf(s, 0, target)
        ray.closestPointTo(target, target)
        var tri = -1
        for (pass in 0 until 4) {
            s.mesh.nearestPoint(target, result)
            tri = s.mesh.lastTriangle
            ray.closestPointTo(result, target)
        }
        if (tri < 0) return null

        /*
         * THE SAME NORMAL A RAY HIT WOULD GIVE. project() reports the FACE
         * normal, built from the winding. The web build used to report the
         * stored VERTEX normal here, and on a swept surface the two point
         * opposite ways — so every clamped sample was lit as if it faced away
         * from the light, painting a visibly darker band (measured at 136
         * against 181) everywhere a stroke ran off the edge.
         */
        val (ia, ib, ic) = s.mesh.triangleIndices(tri)
        return SurfaceSample(
            result.copy(), faceNormal(s, tri),
            frameOnTriangle(s, ia, ib, ic, result),
            onSurface = false,
        )
    }

    private fun faceNormal(s: GuideSurface, tri: Int): Vec3 {
        val a = Vec3(); val b = Vec3(); val c = Vec3()
        s.mesh.triangle(tri, a, b, c)
        val n = (b - a) cross (c - a)
        if (n.lengthSq() < Vec3.EPS) {
            val (ia, _, _) = s.mesh.triangleIndices(tri)
            return Vec3(
                s.normals[ia * 3].toDouble(),
                s.normals[ia * 3 + 1].toDouble(),
                s.normals[ia * 3 + 2].toDouble(),
            ).normalize()
        }
        return n.normalize()
    }

    // ---- the edge -------------------------------------------------------

    /**
     * How far the surface reaches from [frame] along +[dir] and -[dir], in
     * world units. Zero where it runs straight off the edge.
     *
     * AN OUTLINE IS THE REAL EDGE. A swept guide is a rectangle in (u, v) and
     * the box test is exact for it. A flat guide is whatever shape was drawn,
     * so the distance to its edge is a ray-polygon crossing in the surface's
     * own coordinates — the same sum the box test does, over the shape's actual
     * sides rather than four implied ones.
     */
    fun reachAlong(frame: SurfaceFrame, dir: Vec3): Reach {
        val a = dir dot frame.uDir
        val b = dir dot frame.vDir

        val outline = frame.outline
        if (outline != null && outline.size > 2) {
            return Reach(outlineReach(frame, a, b, 1.0), outlineReach(frame, a, b, -1.0))
        }

        fun side(sign: Double): Double {
            var t = Double.MAX_VALUE
            val aa = a * sign
            val bb = b * sign
            if (kotlin.math.abs(aa) > 1e-6) {
                t = kotlin.math.min(t, if (aa > 0) (frame.lu - frame.su) / aa else -frame.su / aa)
            }
            if (kotlin.math.abs(bb) > 1e-6) {
                t = kotlin.math.min(t, if (bb > 0) (frame.lv - frame.sv) / bb else -frame.sv / bb)
            }
            return kotlin.math.max(0.0, t)
        }
        return Reach(side(1.0), side(-1.0))
    }

    /** The nearest crossing of the outline along (a, b) from (su, sv). */
    private fun outlineReach(frame: SurfaceFrame, a: Double, b: Double, sign: Double): Double {
        val du = a * sign
        val dv = b * sign
        if (kotlin.math.abs(du) < 1e-12 && kotlin.math.abs(dv) < 1e-12) return 0.0
        val poly = frame.outline ?: return 0.0
        var best = Double.MAX_VALUE
        for (i in poly.indices) {
            val p = poly[i]
            val q = poly[(i + 1) % poly.size]
            val ex = q.u - p.u
            val ey = q.v - p.v
            val den = du * ey - dv * ex
            if (kotlin.math.abs(den) < 1e-12) continue          // parallel to this side
            val rx = p.u - frame.su
            val ry = p.v - frame.sv
            val t = (rx * ey - ry * ex) / den                   // along the ray
            val s2 = (rx * dv - ry * du) / den                  // along the side
            if (t >= 0 && s2 >= 0 && s2 <= 1) best = kotlin.math.min(best, t)
        }
        return if (best == Double.MAX_VALUE) 0.0 else best
    }

    // ---- the guide as an isolation mask  (A.9) --------------------------

    /**
     * FACT: "Prevented curves behind planes from being erased or selected";
     * "3D guide can now be used as a filter."
     *
     * So a point the guide HIDES from the current viewpoint is protected. This
     * is a property of the viewpoint as much as the geometry, which is why the
     * web build throws the cache away on every camera change — the same point
     * is masked from one side and not from the other.
     */
    fun isMasked(guide: Guide, eye: Vec3, worldPoint: Vec3): Boolean {
        val s = guide.surface ?: return false
        val dir = worldPoint - eye
        val dist = dir.length()
        if (dist < Vec3.EPS) return false
        dir.normalize()
        val hit = s.mesh.raycast(Ray(eye.copy(), dir), dist) ?: return false
        // a hit at the point itself is the point's own surface, not a mask
        return hit.t < dist - 1e-6
    }
}
