package art.plume.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Reshaping a guide after it exists: Bend, and Loft.
 *
 * Ported from sections 6 and 7 of `js/guides.js`. Both carry corrections that
 * were expensive to find in the web build, and the comments say which — they
 * are the parts most likely to be "simplified" back into being wrong.
 */
object GuideEditing {

    // ---- bend  (A.6) ----------------------------------------------------

    /**
     * Bend a SWEPT guide by replacing its path with the stroke you drew.
     *
     * The path is translated so it starts at the anchor — the orange line, the
     * documented starting point of the bend — and otherwise used EXACTLY as
     * drawn.
     *
     * An earlier version in the web build also rotated the path so its start
     * tangent matched the guide's original extrusion direction. That was an
     * inference and a wrong one: where the drawn direction ran roughly
     * opposite that axis the minimal rotation was ~180 degrees, so the surface
     * swept away from the stroke the user had just made. It is not needed for
     * the documented cylinder-to-doughnut case either — the transported frames
     * carry the profile perpendicular to the path whichever way it leaves the
     * anchor, and leaving the path alone keeps the result in the plane it was
     * drawn in instead of tilting it out.
     */
    fun bend(guide: Guide, worldPath: List<Vec3>): Boolean {
        val sw = guide.sweep ?: return false
        if (worldPath.size < 2) return false

        val path = Polyline.resample(worldPath, Tune.GUIDE_PATH_SEG + 1).toMutableList()
        val shift = sw.anchor - path[0]
        for (p in path) { p.x += shift.x; p.y += shift.y; p.z += shift.z }
        relaxTightTurns(path, reachOf(sw))

        val bent = Sweep(
            sw.local, sw.anchor, 0,          // bending starts from the orange line
            path, sw.basisR, sw.basisT, sw.depth,
        )
        guide.sweep = bent
        guide.bendPath = worldPath.map { it.copy() }
        return Guides.rebuildSweep(guide)
    }

    /**
     * How far the profile reaches out from the path it is carried on.
     *
     * Only the two axes ACROSS the path count: the third runs along it and
     * cannot fold the surface however far it goes.
     */
    private fun reachOf(sw: Sweep): Double {
        var most = 0.0
        for (l in sw.local) most = max(most, kotlin.math.hypot(l.x, l.y))
        return most
    }

    /**
     * OPEN OUT THE TURNS A PROFILE THIS WIDE CANNOT GET ROUND.
     *
     * A swept surface folds through itself wherever the path turns inside the
     * profile's own reach: the inner edge of the section crosses the centre of
     * the turn and comes out the far side, inside out. On screen that is the
     * spike and the fan — a wedge of surface converging to a point that
     * nothing in the drawing put there.
     *
     * A hand draws bends far tighter than it means to, especially at the ends
     * of a stroke where the pen slows and the samples bunch, so this is the
     * common case rather than an edge one. Each offending point is eased
     * towards the line between its neighbours until the turn clears the reach,
     * which opens the corner and leaves the rest of the path where it was
     * drawn. Bounded, because a path that will not relax — one drawn as a
     * hairpin, deliberately — should come out as a tight bend rather than as
     * a straight line.
     */
    private fun relaxTightTurns(path: MutableList<Vec3>, reach: Double) {
        if (reach <= Vec3.EPS || path.size < 3) return
        val need = reach * Tune.SWEEP_TURN_MARGIN
        val eased = Vec3()
        repeat(Tune.SWEEP_RELAX_PASSES) {
            var tight = 0
            for (i in 1 until path.size - 1) {
                if (circumradius(path[i - 1], path[i], path[i + 1]) >= need) continue
                tight++
                eased.set(
                    (path[i - 1].x + path[i + 1].x) * 0.5,
                    (path[i - 1].y + path[i + 1].y) * 0.5,
                    (path[i - 1].z + path[i + 1].z) * 0.5,
                )
                path[i].set(
                    path[i].x + (eased.x - path[i].x) * 0.5,
                    path[i].y + (eased.y - path[i].y) * 0.5,
                    path[i].z + (eased.z - path[i].z) * 0.5,
                )
            }
            if (tight == 0) return
        }
    }

    /** The radius of the circle through three points; huge when they are straight. */
    fun circumradius(a: Vec3, b: Vec3, c: Vec3): Double {
        val ab = b.distanceTo(a)
        val bc = c.distanceTo(b)
        val ca = a.distanceTo(c)
        val area2 = ((b - a) cross (c - a)).length()
        if (area2 < 1e-15) return Double.MAX_VALUE
        return ab * bc * ca / (2.0 * area2)
    }

    /**
     * Bend a guide that has no sweep — a loft, a primitive, an imported model.
     *
     * A swept guide bends by replacing its path. A cube has no profile and
     * path to replace, so it bends as a curve DEFORM instead: one axis of the
     * mesh is re-parameterised onto the drawn stroke, and every vertex is
     * carried along in the transported frame at its own position. Same
     * gesture, same "follow the line I drew" result, on any geometry.
     *
     * **WHICH axis, and which way along it, is decided by the STROKE** — not by
     * the mesh alone. Picking the longest axis and always running it low-to-high
     * is what made this bend backwards in the web build: a sphere or a cube has
     * no meaningful longest axis, so the deform ran along local +X whatever was
     * drawn, and any stroke heading the other way produced a guide sweeping away
     * from the pen. Measured against the drawn direction, before: cube 91
     * degrees off, tube 86, pyramid 88, sphere a full 180. After: 0 for all four.
     *
     * So among the axes long enough to be worth routing, take the one the
     * stroke agrees with most, and orient it to point the same way. A rod still
     * bends along its length — its other axes are too short to qualify — while
     * a cube bends whichever way the pen went.
     */
    fun bendMesh(guide: Guide, worldPath: List<Vec3>): Boolean {
        val surface = guide.surface ?: return false
        if (worldPath.size < 2) return false

        val orig = guide.originalPositions ?: surface.positions.copyOf().also {
            guide.originalPositions = it
            guide.originalNormals = surface.normals.copyOf()
        }
        val origNor = guide.originalNormals ?: surface.normals.copyOf()

        val path = Polyline.resample(worldPath, Tune.GUIDE_PATH_SEG + 1)

        var loX = Double.MAX_VALUE; var loY = Double.MAX_VALUE; var loZ = Double.MAX_VALUE
        var hiX = -Double.MAX_VALUE; var hiY = -Double.MAX_VALUE; var hiZ = -Double.MAX_VALUE
        var i = 0
        while (i < orig.size) {
            loX = min(loX, orig[i].toDouble()); hiX = max(hiX, orig[i].toDouble())
            loY = min(loY, orig[i + 1].toDouble()); hiY = max(hiY, orig[i + 1].toDouble())
            loZ = min(loZ, orig[i + 2].toDouble()); hiZ = max(hiZ, orig[i + 2].toDouble())
            i += 3
        }
        val ext = doubleArrayOf(hiX - loX, hiY - loY, hiZ - loZ)
        val longest = maxOf(ext[0], ext[1], ext[2])
        if (longest < Vec3.EPS) return false

        /* The direction the stroke actually goes. A closed loop has no chord,
           so fall back to the sample furthest from the start — for a circle
           that is the diameter, which is the axis a doughnut turns on. */
        val drawn = path[path.size - 1] - path[0]
        if (drawn.lengthSq() < Vec3.EPS) {
            var far = 0
            var fd = -1.0
            for (k in 1 until path.size) {
                val d2 = path[k].distanceToSq(path[0])
                if (d2 > fd) { fd = d2; far = k }
            }
            drawn.set(path[far] - path[0])
        }
        if (drawn.lengthSq() < Vec3.EPS) drawn.set(1.0, 0.0, 0.0)
        drawn.normalize()

        val comp = doubleArrayOf(abs(drawn.x), abs(drawn.y), abs(drawn.z))
        var axis = -1
        var best = -1.0
        for (k in 0..2) {
            if (ext[k] < longest * 0.75) continue
            if (comp[k] > best) { best = comp[k]; axis = k }
        }
        if (axis < 0) {
            axis = if (ext[0] >= ext[1]) (if (ext[0] >= ext[2]) 0 else 2)
            else (if (ext[1] >= ext[2]) 1 else 2)
        }
        val span = ext[axis]
        if (span < Vec3.EPS) return false

        // ...and run it the way the stroke runs, so the far end follows the pen
        // instead of retreating from it
        val forward = drawn[axis] >= 0
        val pa = (axis + 1) % 3
        val pb = (axis + 2) % 3
        val loArr = doubleArrayOf(loX, loY, loZ)
        val hiArr = doubleArrayOf(hiX, hiY, hiZ)
        val mid = doubleArrayOf(
            (loArr[0] + hiArr[0]) / 2, (loArr[1] + hiArr[1]) / 2, (loArr[2] + hiArr[2]) / 2,
        )
        val startVal = if (forward) loArr[axis] else hiArr[axis]

        /*
         * THE STROKE IS WHERE THE GUIDE GOES. The path used to be translated
         * onto the mesh's own starting face, which is an invisible landmark on
         * the far side from the pen — so a cube bent by a stroke drawn to its
         * right jumped left and shrank onto the stroke's length, measured as a
         * 1.6-unit leap for a 0.55-unit stroke. A swept guide can translate to
         * its anchor because the orange line is visible and aimed at; a deform
         * has no such mark, so the honest answer is to leave the path where it
         * was drawn and lay the mesh along it.
         */

        // seed the frame with the first perpendicular axis, so an unbent
        // straight path reproduces the mesh exactly
        val seed = Vec3()
        when (pa) { 0 -> seed.x = 1.0; 1 -> seed.y = 1.0; else -> seed.z = 1.0 }
        val fr = Frames.transportFrames(path, seed, false)

        val arc = Frames.arcLengths(path)
        val total = arc[arc.size - 1]
        if (total < Vec3.EPS) return false

        val pos = FloatArray(orig.size)
        val nor = FloatArray(origNor.size)
        val out = Vec3(); val sVec = Vec3(); val tt = Vec3(); val sv = Vec3()

        i = 0
        while (i < orig.size) {
            val t = (orig[i + axis] - startVal) / (if (forward) span else -span)
            val target = t * total
            var j = 0
            while (j < arc.size - 2 && arc[j + 1] < target) j++
            val segLen = arc[j + 1] - arc[j]
            val f = if (segLen < Vec3.EPS) 0.0 else (target - arc[j]) / segLen

            lerpInto(path[j], path[j + 1], f, out)
            lerpInto(fr.r[j], fr.r[j + 1], f, sVec)
            lerpInto(fr.t[j], fr.t[j + 1], f, tt)
            if (tt.lengthSq() < Vec3.EPS) tt.set(fr.t[j])
            tt.normalize()
            sVec.addScaled(tt, -(sVec dot tt))
            if (sVec.lengthSq() < Vec3.EPS) Vec3.perpTo(tt, sVec) else sVec.normalize()
            sv.set(tt cross sVec)

            val u = orig[i + pa] - mid[pa]
            val w = orig[i + pb] - mid[pb]
            pos[i] = (out.x + sVec.x * u + sv.x * w).toFloat()
            pos[i + 1] = (out.y + sVec.y * u + sv.y * w).toFloat()
            pos[i + 2] = (out.z + sVec.z * u + sv.z * w).toFloat()

            /*
             * Carry the NORMAL through the same rotation rather than
             * recomputing it from the deformed triangles. The frame is
             * orthonormal, so a normal transforms exactly as a position does
             * minus the translation — and this keeps a sphere smooth. The web
             * build calls computeVertexNormals here and gets away with it
             * because its primitives share vertices between faces; ours do not,
             * so recomputing would leave a bent sphere visibly faceted.
             */
            val nAxis = origNor[i + axis].toDouble()
            val nPa = origNor[i + pa].toDouble()
            val nPb = origNor[i + pb].toDouble()
            nor[i] = (tt.x * nAxis + sVec.x * nPa + sv.x * nPb).toFloat()
            nor[i + 1] = (tt.y * nAxis + sVec.y * nPa + sv.y * nPb).toFloat()
            nor[i + 2] = (tt.z * nAxis + sVec.z * nPa + sv.z * nPb).toFloat()
            i += 3
        }

        guide.surface = GuideSurface(
            pos, nor, surface.uv, surface.indices,
            surface.nu, surface.nv, surface.lu, surface.lv, surface.outline,
        )
        guide.bendPath = worldPath.map { it.copy() }
        return true
    }

    /** Put a deformed guide back the way it was. */
    fun unbendMesh(guide: Guide): Boolean {
        val orig = guide.originalPositions ?: return false
        val origNor = guide.originalNormals ?: return false
        val s = guide.surface ?: return false
        guide.surface = GuideSurface(
            orig.copyOf(), origNor.copyOf(), s.uv, s.indices,
            s.nu, s.nv, s.lu, s.lv, s.outline,
        )
        guide.bendPath = null
        return true
    }

    private fun lerpInto(a: Vec3, b: Vec3, t: Double, out: Vec3): Vec3 =
        out.set(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)

    private operator fun Vec3.get(i: Int): Double = when (i) { 0 -> x; 1 -> y; else -> z }

    // ---- loft  (A.7) ----------------------------------------------------

    /**
     * Connect two or more already-drawn curves in sequence.
     *
     * FACT: a tension slider controls the interpolation — up is smoother, down
     * is sharper — which is the cardinal spline's tension parameter.
     *
     * Works from raw point arrays rather than strokes, so a saved document can
     * rebuild a loft without needing its source curves to still exist.
     */
    fun loftFromCurves(rawCurves: List<List<Vec3>>, tension: Double = 1.0): Guide? {
        if (rawCurves.size < 2) return null
        val nu = Tune.GUIDE_PROFILE_SEG
        val curves = ArrayList<List<Vec3>>(rawCurves.size)

        for (raw in rawCurves) {
            var c = Polyline.resample(raw, nu)
            if (c.size < nu) return null
            /* Flip a curve whose direction opposes its predecessor, or the loft
               twists through 180 degrees between sections — the surface pinches
               to a waist in the middle and turns itself inside out. */
            val prev = curves.lastOrNull()
            if (prev != null) {
                val straight = prev[0].distanceTo(c[0]) + prev[nu - 1].distanceTo(c[nu - 1])
                val flipped = prev[0].distanceTo(c[nu - 1]) + prev[nu - 1].distanceTo(c[0])
                if (flipped < straight) c = c.reversed()
            }
            curves.add(c)
        }

        return buildLoft(curves, tension)
    }

    /**
     * Rebuild a loft from the curves it already stores.
     *
     * Separate from [loftFromCurves] because the stored curves are CANONICAL —
     * already resampled, already flip-resolved — and putting them back through
     * the resampler shaves every corner a second time. Measured at 0.05mm on a
     * half-metre section: invisible, but it happens on every rebuild, and a
     * document that is saved and reloaded should come back as the thing that
     * was saved rather than a slightly smoothed copy of it.
     */
    fun rebuildLoft(guide: Guide): Boolean {
        val curves = guide.loftCurves ?: return false
        if (curves.size < 2) return false
        val rebuilt = buildLoft(curves, guide.loftTension) ?: return false
        guide.surface = rebuilt.surface
        guide.anchorRow = rebuilt.anchorRow
        return true
    }

    private fun buildLoft(curves: List<List<Vec3>>, tension: Double): Guide? {
        val nu = curves[0].size
        if (nu < 2) return null
        for (c in curves) if (c.size != nu) return null

        // GUESS: 24 interpolated rows between the outer sections reads smooth
        // without making the mesh heavy
        val nv = max(2, (curves.size - 1) * 24 + 1)
        val cols = ArrayList<List<Vec3>>(nu)
        for (i in 0 until nu) {
            val ctrl = curves.map { it[i] }
            cols.add(Polyline.sampleChain(ctrl, nv, tension))
        }
        val rows = ArrayList<List<Vec3>>(nv)
        for (j in 0 until nv) rows.add((0 until nu).map { cols[it][j] })

        val surface = SurfaceGrid.build(rows) ?: return null
        val g = Guide(Guide.freshId(), GuideKind.LOFT)
        g.surface = surface
        g.anchorRow = rows[0]                  // the first selected curve is the start
        g.loftCurves = curves.map { c -> c.map { it.copy() } }
        g.loftTension = tension
        return g
    }

    /** Loft from strokes, which is what the toolbar hands over. */
    fun loft(strokes: List<Stroke>, tension: Double = 1.0): Guide? {
        if (strokes.size < 2) return null
        return loftFromCurves(strokes.map { st -> st.pts.map { it.p.copy() } }, tension)
    }
}

/**
 * MOVING A WHOLE GUIDE.
 *
 * The web build transforms the guide's scene object — position, quaternion,
 * scale — and lets the renderer compose that with the mesh. This build has no
 * scene graph: a guide keeps the data it was made from and rebuilds its mesh
 * from it, so a guide is moved by moving THAT and rebuilding.
 *
 * Which is the better place for it anyway. Everything that reads a guide —
 * painting onto it, the edge trim, the nearest-point clamp, Fill's rows, the
 * occlusion mask — works in world coordinates off `surface`, and a transform
 * parked on a separate object would have to be threaded through every one of
 * them.
 */
object GuideTransform {

    /**
     * Apply [m] to [guide] and rebuild its surface.
     *
     * Rigid motion and uniform scale only, which is what the joystick offers.
     * A non-uniform scale would need a profile the sweep model cannot hold —
     * `local` is written in the anchor frame and carried along the path, so
     * squashing one world axis is not a thing the data can say.
     *
     * @return false when the guide is a kind that has no source data to move
     *   (an imported model or image, which keep only their built mesh).
     */
    fun apply(guide: Guide, m: Mat4): Boolean {
        val k = m.uniformScale()
        val tmp = Vec3()

        guide.sweep?.let { s ->
            m.transformPoint(s.anchor, tmp); s.anchor.set(tmp)
            for (p in s.path) { m.transformPoint(p, tmp); p.set(tmp) }
            /* the profile is in the anchor frame, so it does not rotate with
               the guide — it only grows or shrinks with it */
            if (k != 1.0) for (p in s.local) p.set(p.x * k, p.y * k, p.z * k)
            m.transformDirection(s.basisR, tmp)
            if (tmp.lengthSq() > Vec3.EPS) s.basisR.set(tmp).normalize()
            m.transformDirection(s.basisT, tmp)
            if (tmp.lengthSq() > Vec3.EPS) s.basisT.set(tmp).normalize()
            /* basisR has to stay square to basisT or the transported frames
               shear; a rotation keeps them square but a scale need not */
            s.basisR.addScaled(s.basisT, -(s.basisR dot s.basisT))
            if (s.basisR.lengthSq() > Vec3.EPS) s.basisR.normalize()
            else Vec3.perpTo(s.basisT, s.basisR)
            s.depth *= k
            return when (guide.kind) {
                GuideKind.LOFT -> GuideEditing.rebuildLoft(guide)
                else -> Guides.rebuildSweep(guide)
            }
        }

        guide.plane?.let { pl ->
            m.transformPoint(pl.origin, tmp); pl.origin.set(tmp)
            for (v in listOf(pl.right, pl.up, pl.normal)) {
                m.transformDirection(v, tmp)
                if (tmp.lengthSq() > Vec3.EPS) v.set(tmp).normalize()
            }
            guide.plane = PlaneData(
                pl.origin, pl.right, pl.up, pl.normal,
                pl.lu * k, pl.lv * k,
                if (k == 1.0) pl.outline else pl.outline.map { UV(it.u * k, it.v * k) },
            )
            return Guides.rebuildFlat(guide)
        }

        return false
    }
}
