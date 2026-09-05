package art.plume.core

import kotlin.math.max

/**
 * Where the symmetry folds.
 *
 * A stroke and its mirror meet in the middle, and that middle is a PLANE, not
 * a line: the midpoint of any point and its reflection lies on it, wherever on
 * the stroke you take it. Drawn edge-on — which is how you are looking at it
 * most of the time you are drawing — it collapses to exactly the faint line
 * down the middle it is meant to be, and when you orbit off-axis it stays
 * truthful instead of pretending the fold is somewhere it is not.
 *
 * Radial symmetry folds about a LINE rather than a plane, so that one is drawn
 * as a line: the upright axis every copy turns around.
 *
 * Both are bounded to what is actually on the page rather than running to the
 * horizon, so they read as part of this drawing and never compete with the
 * grid or the RGB axis.
 */
object Symmetry {

    /** So the fold is still there on an empty page. 160mm. */
    const val MIN_HALF = 0.16

    /** And always a little air around the work. */
    const val PAD_MIN = 0.05

    /** A fraction of the work, not a fixed distance. */
    const val PAD_FRACTION = 0.10

    /**
     * The fold, as triangles for the faint fill and line pairs for the edges.
     * [axisLine] is the radial turning axis, empty when radial is off.
     */
    class Fold(
        val fill: FloatArray,
        val edges: FloatArray,
        val axisLine: FloatArray,
    )

    /**
     * A minimum SIZE, centred on the work — not a minimum reach from the
     * origin. Forcing it to straddle the origin left the fold hanging below a
     * sketch that happened to sit above it, pointing at nothing.
     */
    private fun span(centre: Double, extent: Double, pad: Double): Pair<Double, Double> {
        val half = max(MIN_HALF, extent / 2 + pad)
        return (centre - half) to (centre + half)
    }

    /**
     * Build the indicator for [mirror] ("x", "z" or null) and [radial] copies.
     * Null when there is no symmetry to show.
     */
    fun fold(bounds: Bounds, mirror: String?, radial: Int): Fold? =
        fold(bounds, if (mirror == null) emptySet() else setOf(mirror), radial)

    /**
     * The indicator for any number of active planes.
     *
     * One plane per active axis, all three of them possible at once, because
     * that is what the mirror itself now allows — and a symmetry you cannot
     * see the planes of is one you find out about by drawing.
     */
    fun fold(bounds: Bounds, axes: Set<String>, radial: Int): Fold? {
        if (axes.isEmpty() && radial <= 1) return null

        val sx = if (bounds.empty) 0.0 else bounds.maxX - bounds.minX
        val sy = if (bounds.empty) 0.0 else bounds.maxY - bounds.minY
        val sz = if (bounds.empty) 0.0 else bounds.maxZ - bounds.minZ
        val mid = if (bounds.empty) Vec3() else bounds.centre()
        val pad = max(PAD_MIN, max(sx, max(sy, sz)) * PAD_FRACTION)

        /* both the fold plane and the turning axis want the height of the work */
        val (y0, y1) = span(mid.y, sy, pad)

        var fill = FloatArray(0)
        var edges = FloatArray(0)
        for (axis in Mirror.AXES) {
            if (axis !in axes) continue
            /*
             * A plane is spanned by the two axes it does NOT reflect: the X
             * plane stands in Y and Z, the Y plane lies flat in X and Z, and
             * the Z plane stands in X and Y.
             */
            val (u0, u1) = when (axis) {
                "x" -> span(mid.z, sz, pad)
                "y" -> span(mid.x, sx, pad)
                else -> span(mid.x, sx, pad)
            }
            val (v0, v1) = when (axis) {
                "x" -> y0 to y1
                "y" -> span(mid.z, sz, pad)
                else -> y0 to y1
            }

            fun corner(u: Double, v: Double): FloatArray = when (axis) {
                "x" -> floatArrayOf(0f, v.toFloat(), u.toFloat())
                "y" -> floatArrayOf(u.toFloat(), 0f, v.toFloat())
                else -> floatArrayOf(u.toFloat(), v.toFloat(), 0f)
            }

            val a = corner(u0, v0)
            val b = corner(u1, v0)
            val c = corner(u1, v1)
            val d = corner(u0, v1)
            fill += a + b + c + a + c + d
            edges += a + b + b + c + c + d + d + a
        }

        val axisLine = if (radial > 1) {
            floatArrayOf(0f, y0.toFloat(), 0f, 0f, y1.toFloat(), 0f)
        } else {
            FloatArray(0)
        }
        return Fold(fill, edges, axisLine)
    }
}

/**
 * MIRRORING AS A LIVING LINK, ACROSS THE THREE GLOBAL PLANES.
 *
 * FACT: Feather's Mirror reveals three axes below its icon — red for X, green
 * for Y, blue for Z — any number of which can be active at once, and it
 * mirrors about the GLOBAL axes rather than anything local to the selection.
 *
 * Two things follow that this build did not do. Symmetry was ONE axis at a
 * time and Y was not among them, so the reflection anyone wants for a chair
 * or a face — left/right and top/bottom together — could not be asked for.
 * And a reflection was a copy taken once at the moment of drawing, so every
 * later change to the original left its other half behind: the curve you
 * smoothed, moved or recoloured was symmetric right up until you touched it.
 *
 * A reflection therefore remembers what it reflects, and [resync] re-derives
 * it. That single pass is what makes symmetry a MODE rather than a stamp.
 */
object Mirror {

    /** The three planes, in the order Feather lists them. */
    val AXES = listOf("x", "y", "z")

    fun matrixFor(key: String, out: Mat4 = Mat4()): Mat4 = Mat4.scale(
        if (key.contains('x')) -1.0 else 1.0,
        if (key.contains('y')) -1.0 else 1.0,
        if (key.contains('z')) -1.0 else 1.0,
        out,
    )

    /**
     * Every reflection a set of active planes asks for, as sorted keys.
     *
     * Two planes give three reflections, not two: the pair of single
     * reflections and the one across both, which is what fills the fourth
     * quadrant. Three give seven, which is the eight octants less the one you
     * drew in. The identity is never in the list.
     */
    fun keysFor(axes: Set<String>): List<String> {
        val on = AXES.filter { it in axes }
        if (on.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (mask in 1 until (1 shl on.size)) {
            val sb = StringBuilder()
            for (i in on.indices) if ((mask shr i) and 1 == 1) sb.append(on[i])
            out.add(sb.toString())
        }
        return out
    }

    /** The reflections [source] owes, linked back to it. */
    fun copiesOf(source: Stroke, axes: Set<String>): List<Stroke> =
        keysFor(axes).map { key ->
            Selection.transformedCopy(source, matrixFor(key)).also {
                it.mirrorOf = source.id
                it.mirrorKey = key
            }
        }

    /**
     * Bring every reflection back into step with what it reflects.
     *
     * Returns the ones that actually moved, because the renderer holds a mesh
     * per curve and a curve whose points changed under it would otherwise go
     * on being drawn where it used to be.
     *
     * Comparing before writing is not an optimisation here, it IS the answer:
     * this runs on every change to the document, and rewriting every
     * reflection each time would rebuild meshes that nothing had touched.
     */
    fun resync(sketch: Sketch): List<Stroke> {
        val byId = HashMap<Int, Stroke>()
        for (s in sketch.strokes) byId[s.id] = s

        val moved = ArrayList<Stroke>()
        val m = Mat4()
        val p = Vec3()
        for (copy in sketch.strokes) {
            val srcId = copy.mirrorOf ?: continue
            val src = byId[srcId]
            if (src == null || src === copy) { copy.mirrorOf = null; continue }
            matrixFor(copy.mirrorKey, m)

            var changed = false
            if (copy.pts.size != src.pts.size) {
                changed = true
            } else {
                for (i in src.pts.indices) {
                    m.transformPoint(src.pts[i].p, p)
                    if (copy.pts[i].p.distanceToSq(p) > REDERIVE_EPS) { changed = true; break }
                }
            }
            if (!changed && sameStyle(copy, src)) continue

            val fresh = Selection.transformedCopy(src, m)
            copy.pts.clear()
            copy.pts.addAll(fresh.pts)
            copy.brush = src.brush
            copy.color = src.color
            copy.baseRadius = src.baseRadius
            copy.opacity = src.opacity
            copy.pressureTarget = src.pressureTarget
            copy.seedRef = fresh.seedRef
            moved.add(copy)
        }
        return moved
    }

    private fun sameStyle(a: Stroke, b: Stroke): Boolean =
        a.brush == b.brush &&
            a.color == b.color &&
            a.baseRadius == b.baseRadius &&
            a.opacity == b.opacity &&
            a.pressureTarget == b.pressureTarget

    /** Closer than this and a point has not moved. A micrometre, squared. */
    private const val REDERIVE_EPS = 1e-12
}
