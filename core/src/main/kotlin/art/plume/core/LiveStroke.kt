package art.plume.core

import kotlin.math.ceil
import kotlin.math.max

/**
 * The geometry of a stroke that is still being drawn, grown one sample at a
 * time. Ported from `S.Live` in `js/strokes.js`.
 *
 * Rebuilding the whole tube on every pointer sample is quadratic in the length
 * of the stroke, and a stroke is long. The saving here is that appending a
 * sample can only touch a BOUNDED TAIL of the tube:
 *
 *  - the previous point's tangent changes — it was the end of the curve, now it
 *    is an interior point — so its ring is rewritten,
 *  - the taper reaches back `taper * baseRadius` in arc length from the end, and
 *    the end just moved, so those rings are rewritten too,
 *  - the end cap fan moves.
 *
 * Everything before that window is already final and is never touched again.
 *
 * **This does not transliterate the web version's frame update, and must not.**
 * The web fixes up the previous point with a CENTRAL DIFFERENCE tangent and
 * merely re-orthogonalises its reference vector, which leaves the live geometry
 * slightly adrift from the batch build — the web build covers for that by
 * throwing the incremental buffer away and rebuilding exactly on commit. Anvil's
 * [Frames.computeTangents] uses the bisector of unit chords instead, and the two
 * disagree by more than rounding wherever samples are unevenly spaced. Doing it
 * the web's way here would show as a preview that visibly shifts the moment you
 * lift the pen. So the update below recomputes the last two transport steps in
 * full, from state that is provably unchanged, which costs the same and makes
 * the live buffer EXACTLY equal to [StrokeGeometry.build]. `LiveStrokeTest`
 * asserts that equality rather than trusting this paragraph.
 */
class LiveStroke {

    var seg = 0
        private set
    var caps = false
        private set

    /** Samples currently in the buffer. */
    var pointCount = 0
        private set

    var positions = FloatArray(0)
        private set
    var normals = FloatArray(0)
        private set
    var colors = FloatArray(0)
        private set
    var indices = IntArray(0)
        private set

    /** How many indices to draw this frame. */
    var indexCount = 0
        private set

    /** Vertices in use: two cap-centre slots plus one ring per sample. */
    val vertexCount: Int get() = if (pointCount == 0) 0 else 2 + pointCount * seg

    /**
     * The half-open RING vertex range touched since the last [clearDirty], so
     * the renderer can re-upload a tail instead of a whole stroke. Empty when
     * `dirtyFrom >= dirtyTo`.
     */
    var dirtyFrom = 0
        private set
    var dirtyTo = 0
        private set

    /**
     * The two cap centres, separately.
     *
     * They live at vertices 0 and 1 — the START of the buffer — and both move
     * on every append, because the end cap sits on the newest point. Folding
     * them into one min/max range with the rings, which are at the END, makes
     * that range span the entire stroke every time and quietly undoes the whole
     * point of the bounded window: the compute stays cheap and the upload goes
     * back to being the length of the stroke. Kept apart, an append uploads two
     * vertices plus a couple of rings.
     */
    var capsDirty = false
        private set

    /**
     * The half-open index range touched since the last [clearDirty]. An append
     * writes one new band and moves the end fan, so this is a handful of
     * triangles however long the stroke is — the index upload stays bounded for
     * the same reason the vertex upload does.
     */
    var indexDirtyFrom = 0
        private set
    var indexDirtyTo = 0
        private set

    private var capacity = 0
    private val t = ArrayList<Vec3>()
    private val r = ArrayList<Vec3>()
    private var arc = DoubleArray(0)
    private val scratch = StrokeGeometry.RingScratch()

    /**
     * False until point 0 has a tangent taken from a real chord. While the
     * opening samples are all coincident, [Frames.computeTangents] resolves
     * point 0 by looking AHEAD for the first distinct point — so a later append
     * can still change the head, and the "everything before the window is
     * final" assumption does not hold yet. Until it does, frames are recomputed
     * in full; n is tiny while this is true, so the cost is nothing.
     */
    private var headResolved = false

    /** Where the rewrite window started on the previous append. */
    private var windowStart = 0

    /**
     * The brush the live stroke is being drawn with.
     *
     * Kept because the preview has to be shaded, blended and gritted the same
     * way the committed stroke will be: a glow that only starts glowing on
     * release is a preview that lies about what you are drawing.
     */
    var cfg: Brush = Brushes.resolve("pen")
        private set

    // ---- lifecycle ------------------------------------------------------

    fun begin(stroke: Stroke) {
        seg = StrokeGeometry.segmentsFor(stroke)
        cfg = stroke.cfg
        // a live stroke is open by definition — it has not been closed yet, and
        // commit re-runs the batch build, which detects a loop and welds it
        caps = stroke.cfg.caps
        pointCount = 0
        indexCount = 0
        capacity = 0
        headResolved = false
        t.clear(); r.clear()
        arc = DoubleArray(0)
        positions = FloatArray(0); normals = FloatArray(0); colors = FloatArray(0)
        indices = IntArray(0)
        dirtyFrom = 0; dirtyTo = 0; capsDirty = false
        indexDirtyFrom = 0; indexDirtyTo = 0
        windowStart = 0
        ensureCapacity(64)
    }

    /** Mark the uploaded range as clean; call after pushing to GL. */
    fun clearDirty() {
        dirtyFrom = Int.MAX_VALUE; dirtyTo = 0; capsDirty = false
        indexDirtyFrom = Int.MAX_VALUE; indexDirtyTo = 0
    }

    private fun soil(fromVertex: Int, toVertex: Int) {
        if (dirtyTo <= dirtyFrom) { dirtyFrom = fromVertex; dirtyTo = toVertex; return }
        if (fromVertex < dirtyFrom) dirtyFrom = fromVertex
        if (toVertex > dirtyTo) dirtyTo = toVertex
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= capacity) return
        val cap = max(needed, ceil(capacity * 1.8).toInt() + 16)
        val vCount = 2 + cap * seg

        positions = positions.copyOf(vCount * 3)
        normals = normals.copyOf(vCount * 3)
        colors = colors.copyOf(vCount * 4)

        /*
         * The web build has to widen its index array from 16- to 32-bit here
         * once the vertex count passes 65536. Kotlin's IntArray is 32-bit
         * already and the renderer draws with GL_UNSIGNED_INT, so that whole
         * class of overflow simply does not exist on this side.
         */
        indices = indices.copyOf((cap - 1) * seg * 6 + if (caps) seg * 6 else 0)
        arc = arc.copyOf(cap)
        capacity = cap
    }

    // ---- appending ------------------------------------------------------

    /**
     * Take up the sample most recently added to [stroke]. Call once per point
     * appended, in order; this reads `stroke.pts` and does not append to it.
     */
    fun append(stroke: Stroke) {
        val n = stroke.pts.size
        if (n == 0 || n == pointCount) return
        ensureCapacity(n)

        val pts = stroke.pts
        arc[0] = 0.0
        for (i in max(1, pointCount) until n) {
            arc[i] = arc[i - 1] + pts[i].p.distanceTo(pts[i - 1].p)
        }

        updateFrames(pts, n)
        pointCount = n

        val total = arc[n - 1]

        /*
         * The window. Ring n-2's tangent changed when n-1 arrived, so that is
         * the default start; a tapered brush measures from BOTH ends, and the
         * far end just moved, so the taper's reach has to be walked back too.
         */
        var first = max(0, n - 2)
        val cfg = stroke.cfg
        var writeFrom = first

        /*
         * A tapered brush rewrites from wherever the window started LAST time,
         * not just from where it starts now.
         *
         * Samples are not evenly spaced — clamping onto a guide bunches them —
         * so one append can carry the window's start forward by more than one
         * ring. A ring the window steps clean over never gets a final write,
         * and keeps whatever taper factor it had while it was still inside the
         * taper's reach: measured on an uneven 40-point curve, ring 5 froze at
         * 0.758 of its radius when the correct value had become 1.0. Starting
         * from the previous window guarantees every ring is written at least
         * once after it leaves, and that write is final — outside the reach the
         * factor no longer depends on the total length.
         *
         * The web build has the same gap. It gets away with it because it
         * throws the live buffer away and rebuilds exactly on commit, so the
         * error only ever shows in the preview.
         *
         * Only a tapered brush needs this. With no taper a ring's radius does
         * not depend on the stroke's total length, so nothing behind the last
         * two rings can go stale and the extra ring write would be waste on
         * every sample of every stroke.
         */
        if (cfg.taper > 0.0) {
            val reach = cfg.taper * stroke.baseRadius
            while (first > 0 && (total - arc[first]) < reach) first--
            writeFrom = kotlin.math.min(first, windowStart)
            windowStart = first
        }
        // while the head is unresolved every frame can still move
        if (!headResolved) { writeFrom = 0; windowStart = 0 }

        for (i in writeFrom until n) {
            /* THE PREVIEW ORIENTS ITSELF THE SAME WAY THE COMMITTED STROKE
               WILL. The roll is only frozen onto the point when the stroke
               ends, so until then it is measured here, against the frame this
               ring is about to be written in. Skipping it left the live
               preview rolling with whatever transport chose while the
               committed stroke lay flat on the guide — the same mark changing
               shape the instant the pen lifted. */
            StrokeGeometry.writeRing(
                stroke, i, i, t[i], r[i], arc[i], total,
                positions, normals, colors, seg, scratch,
                roll = Nib.rollOf(pts[i], t[i], r[i]),
            )
        }
        soil(2 + writeFrom * seg, 2 + n * seg)

        if (caps && n >= 1) {
            StrokeGeometry.writeCapCentre(
                stroke, 0, t[0], -1.0, positions, normals, colors,
                arc[0], total, r[0],
            )
            StrokeGeometry.writeCapCentre(
                stroke, n - 1, t[n - 1], 1.0, positions, normals, colors,
                arc[n - 1], total, r[n - 1],
            )
            capsDirty = true
        }

        writeIndices(n)
    }

    /**
     * Rebuild only what the newest sample can have changed.
     *
     * Point n-2 stops being the end of the curve and becomes an interior point,
     * so its tangent changes; that changes the transport step that produced its
     * reference vector, so the step from n-3 is re-run in full rather than
     * patched. Point n-3 and everything before it is untouched — its tangent
     * depends on its two neighbours, both of which are already fixed.
     */
    private fun updateFrames(pts: List<StrokePoint>, n: Int) {
        if (n == 1) {
            setFrame(0, Vec3(0.0, 0.0, 1.0), null, pts)
            headResolved = false
            return
        }

        if (!headResolved) {
            // still degenerate at the head, or the very first real chord: the
            // cheap thing and the correct thing are the same at this length
            rebuildAllFrames(pts, n)
            return
        }

        val j = n - 1
        if (j - 1 >= 1) recomputeTangent(pts, j - 1, n)
        setTangent(j, endTangent(pts, j))
        // r[j-1] depends on the tangent that just changed; r[j] on r[j-1]
        if (j - 1 >= 1) transportInto(pts, j - 1)
        transportInto(pts, j)
    }

    private fun rebuildAllFrames(pts: List<StrokePoint>, n: Int) {
        val world = ArrayList<Vec3>(n)
        for (i in 0 until n) world.add(pts[i].p)
        val fs = Frames.transportFrames(world, null, false)
        while (t.size < n) { t.add(Vec3()); r.add(Vec3()) }
        for (i in 0 until n) { t[i].set(fs.t[i]); r[i].set(fs.r[i]) }
        // the head is settled once point 0's own chord is real
        headResolved = n >= 2 && pts[1].p.distanceToSq(pts[0].p) > Vec3.EPS
    }

    private fun setFrame(i: Int, tan: Vec3, ref: Vec3?, pts: List<StrokePoint>) {
        while (t.size <= i) { t.add(Vec3()); r.add(Vec3()) }
        t[i].set(tan)
        if (ref != null) r[i].set(ref) else Vec3.perpTo(t[i], r[i])
    }

    private fun setTangent(i: Int, tan: Vec3) {
        while (t.size <= i) { t.add(Vec3()); r.add(Vec3()) }
        t[i].set(tan)
    }

    /** The one-sided tangent of the final point, with the coincident fallback. */
    private fun endTangent(pts: List<StrokePoint>, j: Int): Vec3 {
        val v = pts[j].p - pts[j - 1].p
        if (v.lengthSq() > Vec3.EPS) return v.normalize()
        return t[j - 1].copy()
    }

    /** An interior point's tangent: the bisector of its two UNIT chords. */
    private fun recomputeTangent(pts: List<StrokePoint>, i: Int, n: Int) {
        val back = pts[i].p - pts[i - 1].p
        val fwd = pts[i + 1].p - pts[i].p
        val lb = back.lengthSq(); val lf = fwd.lengthSq()
        val v = Vec3()
        if (lb > Vec3.EPS && lf > Vec3.EPS) {
            back.normalize(); fwd.normalize()
            v.set(back + fwd)
            if (v.lengthSq() <= Vec3.EPS) v.set(fwd)
        } else if (lf > Vec3.EPS) v.set(fwd)
        else if (lb > Vec3.EPS) v.set(back)

        if (v.lengthSq() > Vec3.EPS) setTangent(i, v.normalize())
        else setTangent(i, t[i - 1].copy())     // wholly coincident: carry on
    }

    /** One double-reflection transport step, from i-1 into i. */
    private fun transportInto(pts: List<StrokePoint>, i: Int) {
        while (r.size <= i) { t.add(Vec3()); r.add(Vec3()) }
        val a = pts[i - 1].p
        val b = pts[i].p
        val tA = t[i - 1]; val tB = t[i]; val rA = r[i - 1]
        val v1 = b - a
        val c1 = v1.lengthSq()
        val rN = Vec3()
        if (c1 < Vec3.EPS) {
            rN.set(rA)
        } else {
            val rL = rA.copy().addScaled(v1, -2.0 * (v1 dot rA) / c1)
            val tL = tA.copy().addScaled(v1, -2.0 * (v1 dot tA) / c1)
            val v2 = tB - tL
            val c2 = v2.lengthSq()
            rN.set(rL)
            if (c2 >= Vec3.EPS) rN.addScaled(v2, -2.0 * (v2 dot rL) / c2)
        }
        rN.addScaled(tB, -(rN dot tB))
        if (rN.lengthSq() < Vec3.EPS) Vec3.perpTo(tB, rN) else rN.normalize()
        r[i].set(rN)
    }

    // ---- indices --------------------------------------------------------

    /**
     * Only the newest band is new, and the end fan sits after it — so appending
     * a sample writes `seg` quads and moves `seg` triangles, not the whole
     * index array. The web build rewrites every band on every sample; it does
     * not have to, and at a few thousand samples the difference is the whole
     * frame budget.
     */
    private fun writeIndices(n: Int) {
        val capsOffset = if (caps) seg * 3 else 0
        if (n < 2) {
            indexCount = 0
            return
        }
        if (caps) StrokeGeometry.startFan(indices, 0, seg)
        val bandAt = capsOffset + (n - 2) * seg * 6
        var at = StrokeGeometry.band(indices, bandAt, n - 2, n - 1, seg)
        if (caps) at = StrokeGeometry.endFan(indices, at, n - 1, seg)
        indexCount = at

        // the start fan is written every time but never changes after the
        // first, so the dirty range begins at the new band
        val from = if (n == 2) 0 else bandAt
        if (indexDirtyTo <= indexDirtyFrom) {
            indexDirtyFrom = from; indexDirtyTo = at
        } else {
            if (from < indexDirtyFrom) indexDirtyFrom = from
            if (at > indexDirtyTo) indexDirtyTo = at
        }
    }
}
