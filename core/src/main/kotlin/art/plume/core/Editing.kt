package art.plume.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What an erase did, in the form undo needs to put it back. */
class EraseResult(
    val removed: List<Stroke>,
    val added: List<Stroke>,
    /** Where each removed stroke sat, so undo restores draw order. */
    val removedAt: List<Int>,
) {
    val touched: Boolean get() = removed.isNotEmpty()
}

/**
 * Tools that change a curve after it has been drawn.
 *
 * Every one of these takes the [Camera] rather than reaching for a screen,
 * which is what makes them testable on a JVM. That matters more here than
 * anywhere else in the port: erase and liquify are the two tools that can
 * quietly destroy someone's drawing, and "it looked right when I tried it" is
 * not a way to find the case where a thin eraser slips between two samples.
 *
 * The [mask] argument is the guide acting as a filter. FACT (A.9): "prevented
 * curves behind planes from being erased or selected" — a point the active
 * guide hides from the current viewpoint is protected. It is passed in as a
 * predicate rather than reached for, so `core` need not know about guides to
 * honour them.
 */
object Editing {

    /** Nothing is masked. */
    val NO_MASK: (Vec3) -> Boolean = { false }

    // ---- erase  (C.6) ---------------------------------------------------

    /**
     * The screen-space eraser.
     *
     * FACT (C.6): "the Eraser removes points from the center of the curve, not
     * the surrounding geometry" — so the test is against the CENTRELINE, and a
     * broad brush can visibly overlap a curve without erasing it. That is
     * documented behaviour, not a bug worth fixing.
     *
     * The disc is clipped against the centreline as a CONTINUOUS POLYLINE, not
     * against its sample points. Where it crosses a segment, the segment is cut
     * and fresh endpoints are interpolated. Testing sample points alone lets a
     * thin eraser slip through the gap between two samples, and a two-point
     * curve could never be split at all.
     */
    fun eraseScreen(
        sketch: Sketch,
        camera: Camera,
        x: Double,
        y: Double,
        radiusPx: Double,
        mask: (Vec3) -> Boolean = NO_MASK,
    ): EraseResult {
        val screenX = ArrayList<Double>()
        val screenY = ArrayList<Double>()
        val visible = ArrayList<Boolean>()
        val s = Vec3()
        val midpoint = Vec3()

        return eraseRuns(sketch) { st ->
            val pts = st.pts
            val n = pts.size
            if (n == 0) return@eraseRuns null
            if (farFromDisc(camera, st, x, y, radiusPx)) return@eraseRuns null

            screenX.clear(); screenY.clear(); visible.clear()
            for (pt in pts) {
                camera.worldToScreen(pt.p, s)
                screenX.add(s.x); screenY.add(s.y)
                visible.add(s.z >= -1.0 && s.z <= 1.0)
            }

            if (n == 1) {
                val dx = screenX[0] - x
                val dy = screenY[0] - y
                val inside = visible[0] && dx * dx + dy * dy <= radiusPx * radiusPx
                return@eraseRuns if (inside && !mask(pts[0].p)) emptyList() else null
            }

            val runs = ArrayList<MutableList<StrokePoint>>()
            var cur = ArrayList<StrokePoint>()
            fun flush() { if (cur.isNotEmpty()) { runs.add(cur); cur = ArrayList() } }
            fun push(pt: StrokePoint) { if (cur.isEmpty() || cur.last() !== pt) cur.add(pt) }

            var touched = false
            for (i in 0 until n - 1) {
                val a = pts[i]
                val b = pts[i + 1]
                var iv = if (visible[i] && visible[i + 1]) {
                    discInterval(
                        screenX[i], screenY[i], screenX[i + 1], screenY[i + 1],
                        x, y, radiusPx,
                    )
                } else null

                // the guide protects what it hides, tested at the middle of the
                // span that would be removed
                if (iv != null) {
                    val t = (iv.first + iv.second) / 2
                    midpoint.set(
                        a.p.x + (b.p.x - a.p.x) * t,
                        a.p.y + (b.p.y - a.p.y) * t,
                        a.p.z + (b.p.z - a.p.z) * t,
                    )
                    if (mask(midpoint)) iv = null
                }

                if (iv == null) { push(a); push(b); continue }
                touched = true

                if (iv.first > 0) { push(a); push(lerpPoint(a, b, iv.first)) }
                flush()
                if (iv.second < 1) { push(lerpPoint(a, b, iv.second)); push(b) }
            }
            flush()
            if (touched) runs else null
        }
    }

    /** The 3D eraser: everything inside a sphere goes. */
    fun eraseSphere(
        sketch: Sketch,
        centre: Vec3,
        radius: Double,
        mask: (Vec3) -> Boolean = NO_MASK,
    ): EraseResult {
        val r2 = radius * radius
        return eraseBy(sketch) { p -> p.distanceToSq(centre) <= r2 && !mask(p) }
    }

    /**
     * `split(stroke)` returns the surviving runs, or null to leave it alone.
     * Every run of two or more points becomes a new curve, so one pass through
     * the middle of a stroke yields two.
     */
    private fun eraseRuns(
        sketch: Sketch,
        split: (Stroke) -> List<List<StrokePoint>>?,
    ): EraseResult {
        val removed = ArrayList<Stroke>()
        val removedAt = ArrayList<Int>()
        val added = ArrayList<Stroke>()

        // backwards, so removing does not disturb the indices still to come
        for (i in sketch.strokes.indices.reversed()) {
            val st = sketch.strokes[i]
            if (!sketch.visible(st)) continue            // a hidden group is protected
            val runs = split(st) ?: continue
            removed.add(st)
            for (run in runs) if (run.size >= 2) added.add(st.withPoints(run))
        }
        for (st in removed) removedAt.add(sketch.remove(st))
        for (st in added) sketch.add(st)
        return EraseResult(removed, added, removedAt)
    }

    private fun eraseBy(sketch: Sketch, hit: (Vec3) -> Boolean): EraseResult =
        eraseRuns(sketch) { st ->
            val pts = st.pts
            val kill = BooleanArray(pts.size)
            var any = false
            for (j in pts.indices) {
                kill[j] = hit(pts[j].p)
                if (kill[j]) any = true
            }
            if (!any) return@eraseRuns null
            val runs = ArrayList<List<StrokePoint>>()
            var run = ArrayList<StrokePoint>()
            for (j in 0..pts.size) {
                if (j < pts.size && !kill[j]) { run.add(pts[j]); continue }
                if (run.isNotEmpty()) runs.add(run)
                run = ArrayList()
            }
            runs
        }

    /**
     * The sub-interval of A->B inside the eraser disc, as (enter, exit) in
     * 0..1, or null. A straight circle-segment quadratic.
     */
    internal fun discInterval(
        ax: Double, ay: Double, bx: Double, by: Double,
        cx: Double, cy: Double, r: Double,
    ): Pair<Double, Double>? {
        val dx = bx - ax; val dy = by - ay
        val fx = ax - cx; val fy = ay - cy
        val a = dx * dx + dy * dy
        if (a < 1e-12) return if (fx * fx + fy * fy <= r * r) 0.0 to 1.0 else null
        val b = 2 * (fx * dx + fy * dy)
        val c = fx * fx + fy * fy - r * r
        val disc = b * b - 4 * a * c
        if (disc < 0) return null
        val sq = sqrt(disc)
        val t1 = (-b - sq) / (2 * a)
        val t2 = (-b + sq) / (2 * a)
        if (t2 < 0 || t1 > 1) return null
        val lo = max(t1, 0.0)
        val hi = min(t2, 1.0)
        return if (lo <= hi) lo to hi else null
    }

    /** Interpolate a whole point record along a segment. */
    private fun lerpPoint(a: StrokePoint, b: StrokePoint, t: Double): StrokePoint {
        val near = if (t < 0.5) a else b
        return StrokePoint(
            Vec3(
                a.p.x + (b.p.x - a.p.x) * t,
                a.p.y + (b.p.y - a.p.y) * t,
                a.p.z + (b.p.z - a.p.z) * t,
            ),
            tan = near.tan?.copy(),
            ref = near.ref?.copy(),
            roll = near.roll,
            pressure = a.pressure + (b.pressure - a.pressure) * t,
            nrm = near.nrm?.copy(),
        )
    }

    /**
     * Cheap rejection: project the stroke's bounds and skip it entirely when
     * the disc cannot possibly reach.
     *
     * Without this, every drag sample projects every point of every curve in
     * the scene. Smooth and Liquify sweep a disc exactly as the eraser does and
     * pay the same price, so they share it.
     */
    fun farFromDisc(camera: Camera, st: Stroke, x: Double, y: Double, radiusPx: Double): Boolean {
        if (st.pts.isEmpty()) return true
        val centre = Vec3()
        var radius = 0.0
        for (pt in st.pts) { centre.x += pt.p.x; centre.y += pt.p.y; centre.z += pt.p.z }
        val k = 1.0 / st.pts.size
        centre.x *= k; centre.y *= k; centre.z *= k
        for (pt in st.pts) radius = max(radius, pt.p.distanceTo(centre))
        radius += StrokeGeometry.halfWidth(st, st.baseRadius)

        val s = Vec3()
        camera.worldToScreen(centre, s)
        if (s.z < -1.5 || s.z > 1.5) return false        // near or behind: do not risk it
        // 1.25 is perspective slack: the far side of a sphere subtends more
        val rPx = camera.worldToPx(radius) * 1.25 + radiusPx
        val dx = s.x - x
        val dy = s.y - y
        return (dx * dx + dy * dy) > rPx * rPx
    }

    // ---- vacuum  (C.6) ---------------------------------------------------

    /**
     * FACT (C.6): Vacuum erases entire curves it touches.
     *
     * The web build raycasts the rendered tube. This tests the ANALYTIC tube —
     * distance from the ray to each centreline segment, against the local
     * radius — which agrees with the tessellated one to within a facet and is
     * both cheaper and exact at the ends. The visible difference is that a
     * 12-sided tube's flats no longer let a ray graze past a curve it visibly
     * crosses.
     */
    fun vacuumAt(
        sketch: Sketch,
        camera: Camera,
        x: Double,
        y: Double,
        mask: (Vec3) -> Boolean = NO_MASK,
    ): List<Stroke> {
        val ray = camera.rayFrom(x, y)
        val killed = ArrayList<Stroke>()
        val hit = Vec3()
        for (st in sketch.strokes) {
            if (!sketch.visible(st)) continue
            if (rayHitsStroke(ray, st, hit) && !mask(hit)) killed.add(st)
        }
        for (st in killed) sketch.remove(st)
        return killed
    }

    /** Does [ray] pass within the tube of [st]? Fills [out] with where. */
    fun rayHitsStroke(ray: Ray, st: Stroke, out: Vec3): Boolean {
        val pts = st.pts
        if (pts.isEmpty()) return false
        val radius = max(
            StrokeGeometry.halfWidth(st, st.baseRadius),
            StrokeGeometry.halfThick(st, st.baseRadius),
        )
        if (pts.size == 1) {
            val d = ray.distanceSqToPoint(pts[0].p)
            if (d <= radius * radius) { out.set(pts[0].p); return true }
            return false
        }
        var bestT = Double.MAX_VALUE
        var found = false
        val q = Vec3()
        for (i in 0 until pts.size - 1) {
            val d = rayToSegment(ray, pts[i].p, pts[i + 1].p, q)
            if (d <= radius) {
                val t = (q - ray.origin) dot ray.direction
                if (t >= 0 && t < bestT) { bestT = t; out.set(q); found = true }
            }
        }
        return found
    }

    /**
     * Closest distance between a ray and a segment; [out] gets the point on the
     * SEGMENT. Standard clamped closest-point-between-two-lines.
     */
    private fun rayToSegment(ray: Ray, a: Vec3, b: Vec3, out: Vec3): Double {
        val u = ray.direction
        val v = b - a
        val w = a - ray.origin
        val aa = u dot u
        val bb = u dot v
        val cc = v dot v
        val dd = u dot w
        val ee = v dot w
        val den = aa * cc - bb * bb

        var tSeg = if (kotlin.math.abs(den) < 1e-12) 0.0 else (aa * ee - bb * dd) / den
        tSeg = clamp(tSeg, 0.0, 1.0)
        out.set(a).addScaled(v, tSeg)
        var tRay = (out - ray.origin) dot u
        if (tRay < 0) {
            // behind the eye: the nearest visible point is the ray origin
            tRay = 0.0
        }
        val onRay = Vec3().set(ray.origin).addScaled(u, tRay)
        return out.distanceTo(onRay)
    }

    // ---- smooth ----------------------------------------------------------

    /**
     * One pass of smoothing under the disc, ENDPOINTS PINNED so a curve keeps
     * its extent — a smooth that shortened the line every pass would eat a
     * stroke you held the tool on.
     *
     * [reprojectOnto] puts the result back on the guide the stroke was painted
     * on. Averaging a point with its neighbours cuts the corner off a curved
     * surface, so without it paint drifts off the guide: measured in the web
     * build at 6.41mm after one pass on a swept guide.
     */
    fun smoothStep(
        sketch: Sketch,
        camera: Camera,
        x: Double,
        y: Double,
        radiusPx: Double,
        mask: (Vec3) -> Boolean = NO_MASK,
        reprojectOnto: (Stroke) -> SurfaceMesh? = { null },
    ): List<Stroke> {
        val r2 = radiusPx * radiusPx
        val touched = ArrayList<Stroke>()
        val s = Vec3()
        val avg = Vec3()

        for (st in sketch.strokes) {
            val pts = st.pts
            if (pts.size < 3 || !sketch.visible(st)) continue
            if (farFromDisc(camera, st, x, y, radiusPx)) continue

            var moved = false
            for (j in 1 until pts.size - 1) {
                camera.worldToScreen(pts[j].p, s)
                if (s.z < -1 || s.z > 1) continue
                val dx = s.x - x
                val dy = s.y - y
                val d2 = dx * dx + dy * dy
                if (d2 > r2) continue
                if (mask(pts[j].p)) continue

                // GUESS: 0.45 at the centre, falling to nothing at the rim
                val w = (1 - d2 / r2) * 0.45
                avg.set(
                    (pts[j - 1].p.x + pts[j + 1].p.x) * 0.5,
                    (pts[j - 1].p.y + pts[j + 1].p.y) * 0.5,
                    (pts[j - 1].p.z + pts[j + 1].p.z) * 0.5,
                )
                pts[j].p.set(
                    pts[j].p.x + (avg.x - pts[j].p.x) * w,
                    pts[j].p.y + (avg.y - pts[j].p.y) * w,
                    pts[j].p.z + (avg.z - pts[j].p.z) * w,
                )
                moved = true
            }
            if (moved) {
                reprojectOnto(st)?.let { Reproject.toSurface(st, it) }
                touched.add(st)
            }
        }
        return touched
    }

    /** A snapshot of positions, for the undo of a tool that nudges points. */
    fun snapshot(strokes: List<Stroke>): List<List<Vec3>> =
        strokes.map { st -> st.pts.map { it.p.copy() } }

    fun restore(strokes: List<Stroke>, snap: List<List<Vec3>>) {
        for (i in strokes.indices) {
            if (i >= snap.size) break
            val pts = strokes[i].pts
            val row = snap[i]
            for (j in 0 until minOf(pts.size, row.size)) pts[j].p.set(row[j])
        }
    }
}
