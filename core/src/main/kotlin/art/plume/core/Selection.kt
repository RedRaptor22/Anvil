package art.plume.core

import kotlin.math.ceil
import kotlin.math.hypot

/** A point on the glass, in view-local pixels. */
data class Px(val x: Double, val y: Double)

/** What the brush panel should read while a selection is up. */
class Style(
    /** null where the selection does not agree. */
    val brush: String?,
    val color: Rgba?,
    val opacity: Double?,
    /** Averaged rather than nulled: a spread of widths still has a middle. */
    val averageRadius: Double,
)

/** What a restyle changes. Anything left null is left alone. */
class StyleChange(
    val brush: String? = null,
    val color: Rgba? = null,
    val opacity: Double? = null,
    /** Multiplies the radius, measured from a base snapshot. */
    val scale: Double? = null,
)

/**
 * Picking curves, moving them, and restyling them.
 *
 * FACT (A.9): the guide mask applies to selection as well as to the eraser —
 * "curves hidden by the 3D guide cannot be selected".
 */
object Selection {

    /** The curve under a pixel, nearest first, honouring the mask. */
    fun hitTest(
        sketch: Sketch,
        camera: Camera,
        x: Double,
        y: Double,
        mask: (Vec3) -> Boolean = Editing.NO_MASK,
    ): Stroke? {
        val ray = camera.rayFrom(x, y)
        val hit = Vec3()
        var best: Stroke? = null
        var bestT = Double.MAX_VALUE
        for (st in sketch.strokes) {
            if (!sketch.visible(st)) continue
            if (!Editing.rayHitsStroke(ray, st, hit)) continue
            if (mask(hit)) continue
            val t = (hit - ray.origin) dot ray.direction
            if (t in 0.0..bestT) { bestT = t; best = st }
        }
        return best
    }

    /**
     * A tap. **Tapping ADDS.**
     *
     * `additive` used to come from the shift key, which a tablet does not
     * have — so every tap threw away what you had picked and there was no way
     * to select two curves by tapping at all. A tap on a curve toggles it in or
     * out; a tap on empty space clears, which is the only gesture that needs to
     * mean "start again".
     *
     * Returns the curve tapped, or null for a tap on nothing.
     */
    fun tapSelect(
        sketch: Sketch,
        camera: Camera,
        x: Double,
        y: Double,
        additive: Boolean = true,
        mask: (Vec3) -> Boolean = Editing.NO_MASK,
    ): Stroke? {
        val st = hitTest(sketch, camera, x, y, mask)
        if (st == null) { sketch.clearSelection(); return null }
        val was = sketch.isSelected(st)
        if (!additive) sketch.clearSelection()
        // one tap, one curve — the whole group is a long press on its row
        sketch.setSelected(st, !was)
        return st
    }

    /**
     * A sweep picks up everything it crosses.
     *
     * Tapping curve after curve is fine for two and tedious for twenty, and a
     * selection is usually a run of neighbouring strokes — the ones you just
     * drew.
     *
     * **Sampled ALONG the segment, not just at the point.** A pen crossing a
     * thin curve covers it in a fraction of a frame: testing only where the
     * pointer landed picked up one stroke in four when sweeping across curves a
     * few pixels wide. Walking the gap between this sample and the last catches
     * what the hand actually passed over.
     */
    class Sweep(private val startedAt: Px?) {
        val added = ArrayList<Stroke>()
        private var last: Px? = startedAt
        private var testedOrigin = false

        fun step(
            sketch: Sketch,
            camera: Camera,
            x: Double,
            y: Double,
            mask: (Vec3) -> Boolean = Editing.NO_MASK,
        ): Int {
            val from = last ?: Px(x, y)

            /*
             * Test the press point ITSELF, once, before walking anywhere.
             *
             * Stepping from `from` at i = 1 starts one whole step past it, so a
             * curve lying exactly under the finger at the start is sampled 4px
             * away and missed whenever the tube is thinner than that — which at
             * a normal zoom a 14mm brush is, at about 3.6px. That is the very
             * stroke the sweep is meant to begin with, and the web build has
             * the same gap: its loop also starts at i = 1. It hides there
             * because a real hand moves slowly at the start and gets another
             * sample within a pixel or two.
             */
            if (!testedOrigin) { testedOrigin = true; pick(sketch, camera, from.x, from.y, mask) }

            val dx = x - from.x
            val dy = y - from.y
            val steps = maxOf(1, ceil(hypot(dx, dy) / STEP_PX).toInt())
            for (i in 1..steps) {
                val f = i.toDouble() / steps
                pick(sketch, camera, from.x + dx * f, from.y + dy * f, mask)
            }
            last = Px(x, y)
            return added.size
        }

        private fun pick(
            sketch: Sketch, camera: Camera, x: Double, y: Double, mask: (Vec3) -> Boolean,
        ) {
            val st = hitTest(sketch, camera, x, y, mask) ?: return
            if (!sketch.isSelected(st)) {
                sketch.setSelected(st, true)
                added.add(st)
            }
        }

        private companion object { const val STEP_PX = 4.0 }
    }

    /**
     * The sweep starts where the PRESS did, not where the first move landed: a
     * curve sitting right under the finger at the start was otherwise the one
     * stroke a sweep across four reliably missed.
     */
    fun beginSweep(origin: Px?): Sweep = Sweep(origin)

    /**
     * Everything inside a drawn loop.
     *
     * A curve counts when the MAJORITY of its visible points are inside, so a
     * curve merely clipped by the edge of the loop is not grabbed.
     */
    fun lassoSelect(
        sketch: Sketch,
        camera: Camera,
        poly: List<Px>,
        mask: (Vec3) -> Boolean = Editing.NO_MASK,
    ): List<Stroke> {
        if (poly.size < 3) return emptyList()
        val hits = ArrayList<Stroke>()
        val s = Vec3()
        for (st in sketch.strokes) {
            if (!sketch.visible(st)) continue
            var inside = 0
            var seen = 0
            for (pt in st.pts) {
                camera.worldToScreen(pt.p, s)
                if (s.z < -1 || s.z > 1) continue
                if (mask(pt.p)) continue                 // A.9 applies to select too
                seen++
                if (pointInPoly(s.x, s.y, poly)) inside++
            }
            if (seen > 0 && inside.toDouble() / seen > 0.5) hits.add(st)
        }
        sketch.selectOnly(hits)
        return hits
    }

    /** Even-odd ray crossing, which is what a self-crossing loop deserves. */
    fun pointInPoly(px: Double, py: Double, poly: List<Px>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.y > py) != (b.y > py)) {
                val den = if (b.y - a.y != 0.0) b.y - a.y else 1e-12
                if (px < (b.x - a.x) * (py - a.y) / den + a.x) inside = !inside
            }
            j = i
        }
        return inside
    }

    /** Only add a lasso vertex once the pen has actually moved. */
    fun appendLasso(poly: MutableList<Px>, x: Double, y: Double, minPx: Double = 3.0): Boolean {
        val last = poly.lastOrNull()
        if (last != null && hypot(x - last.x, y - last.y) < minPx) return false
        poly.add(Px(x, y))
        return true
    }

    // ---- moving and copying ----------------------------------------------

    /**
     * Apply a matrix to positions AND to the frozen cross-section frame, which
     * is what keeps a rotated curve's shape identical rather than letting the
     * nib re-derive itself into a different section.
     *
     * A UNIFORM scale also scales the radius. A non-uniform one leaves the
     * radius alone rather than producing a cross-section the data model cannot
     * represent.
     */
    fun transform(strokes: List<Stroke>, m: Mat4) {
        val uniform = m.uniformScale()
        val flips = m.flipsHandedness()
        val tmp = Vec3()
        for (st in strokes) {
            for (pt in st.pts) {
                m.transformPoint(pt.p, tmp)
                pt.p.set(tmp)
                pt.tan?.let {
                    m.transformDirection(it, tmp)
                    if (tmp.lengthSq() > Vec3.EPS) it.set(tmp).normalize()
                }
                pt.ref?.let {
                    m.transformDirection(it, tmp)
                    it.set(tmp)
                    pt.tan?.let { t -> it.addScaled(t, -(it dot t)) }
                    if (it.lengthSq() > Vec3.EPS) it.normalize()
                    else Vec3.perpTo(pt.tan ?: Vec3(0.0, 0.0, 1.0), it)
                }
                // the surface normal is what the nib is squared to, so it turns too
                pt.nrm?.let {
                    m.transformDirection(it, tmp)
                    if (tmp.lengthSq() > Vec3.EPS) it.set(tmp).normalize()
                }
                /*
                 * A MIRROR REVERSES THE FRAME THE ROLL IS MEASURED IN.
                 *
                 * The section angle is measured from `ref` towards `tan x ref`,
                 * and a reflection sends `tan x ref` to MINUS the transform of
                 * it — so carrying the angle across unchanged aims a mirrored
                 * blade on the wrong side of its own stroke. Re-deriving it
                 * from the surface, which has just been transformed too, is
                 * exact for a reflection and for everything else; a point with
                 * no surface has only the angle, and negating it is what a
                 * handedness flip does to it.
                 */
                val t = pt.tan; val r = pt.ref
                if (pt.nrm != null && t != null && r != null) {
                    pt.roll = Nib.rollOf(pt, t, r)
                } else if (flips) {
                    pt.roll = -pt.roll
                }
            }
            st.baseRadius *= uniform
        }
    }

    fun transformedCopy(st: Stroke, m: Mat4): Stroke =
        st.copyStroke().also { transform(listOf(it), m) }

    /**
     * Duplicate the selection, IN PLACE.
     *
     * FACT: "The duplicated curves are in the same position as the original,
     * so be careful not to confuse them." This used to nudge the copy a
     * couple of dozen pixels across the screen so it read as a copy — kinder
     * on the eye and wrong: a duplicate you then move yourself has a known
     * starting point, and one that arrived somewhere of its own choosing has
     * to be put back before it can be placed. The count is reported instead,
     * which is what Feather does with it.
     */
    fun duplicate(sketch: Sketch, camera: Camera, offsetPx: Double = 0.0): List<Stroke> {
        val sel = sketch.selection
        if (sel.isEmpty()) return emptyList()
        val right = Vec3(); val up = Vec3(); val back = Vec3()
        camera.basis(right, up, back)
        val d = camera.pxToWorld(offsetPx)
        val m = Mat4()
        Mat4.translation(right.x * d, right.y * d, right.z * d, m)

        val copies = sel.map { transformedCopy(it, m) }
        sketch.clearSelection()
        for (c in copies) { sketch.add(c); sketch.setSelected(c, true) }
        return copies
    }

    /**
     * Duplicate the selection reflected across a world plane.
     *
     * Not the same as duplicate-then-mirror by hand: the copy lands in place
     * rather than offset, because a mirrored copy has somewhere it belongs and
     * nudging it 24px sideways would be wrong.
     */
    fun mirroredDuplicate(sketch: Sketch, axis: String): List<Stroke> =
        mirroredDuplicate(sketch, listOf(mirrorMatrix(axis)))

    /**
     * SYMMETRICALLY BY VIEW: reflect across the plane you are looking through.
     *
     * FACT: "duplicate symmetrically based on the view direction. If the
     * sketch is skewed to the right, it will be duplicated to the left, and
     * vice versa." The plane is the one containing the camera's up and
     * forward axes, through the origin — so "left" and "right" mean what they
     * mean on the glass, whichever way the model is turned underneath.
     */
    fun viewMirroredDuplicate(sketch: Sketch, camera: Camera): List<Stroke> {
        val right = Vec3(); val up = Vec3(); val back = Vec3()
        camera.basis(right, up, back)
        return mirroredDuplicate(sketch, listOf(reflectionAcross(right)))
    }

    /**
     * SYMMETRICALLY BY MIRROR: every plane the mirror currently has on.
     *
     * FACT: "can only be used when the mirror is on. It duplicates
     * symmetrically based on the currently active mirror axis. If multiple
     * axes are active, multiple curves will be duplicated at once."
     */
    fun mirrorAxesDuplicate(sketch: Sketch, axes: Set<String>): List<Stroke> =
        mirroredDuplicate(sketch, Mirror.keysFor(axes).map { Mirror.matrixFor(it) })

    private fun mirroredDuplicate(sketch: Sketch, mats: List<Mat4>): List<Stroke> {
        val sel = sketch.selection
        if (sel.isEmpty() || mats.isEmpty()) return emptyList()
        val copies = ArrayList<Stroke>(sel.size * mats.size)
        for (m in mats) for (st in sel) copies.add(transformedCopy(st, m))
        sketch.clearSelection()
        for (c in copies) { sketch.add(c); sketch.setSelected(c, true) }
        return copies
    }

    /** The reflection across the plane through the origin with this normal. */
    fun reflectionAcross(normal: Vec3, out: Mat4 = Mat4()): Mat4 {
        val n = normal.copy().normalize()
        out.identity()
        val m = out.m
        m[0] = 1 - 2 * n.x * n.x; m[4] = -2 * n.x * n.y; m[8] = -2 * n.x * n.z
        m[1] = -2 * n.y * n.x; m[5] = 1 - 2 * n.y * n.y; m[9] = -2 * n.y * n.z
        m[2] = -2 * n.z * n.x; m[6] = -2 * n.z * n.y; m[10] = 1 - 2 * n.z * n.z
        return out
    }

    /** FACT (C.10): live symmetry on X, and from v1.5 on Z. */
    fun mirrorMatrix(axis: String, out: Mat4 = Mat4()): Mat4 =
        if (axis == "x") Mat4.scale(-1.0, 1.0, 1.0, out)
        else Mat4.scale(1.0, 1.0, -1.0, out)

    /**
     * Every copy the current symmetry owes a stroke.
     *
     * Mirror reflects across a world plane; radial turns about the vertical
     * axis through the origin — the same axis the grid is drawn around, so the
     * centre is somewhere you can see rather than somewhere you have to
     * remember.
     *
     * **They compose.** With both on, each of the n sectors carries the stroke
     * AND its reflection, which is what makes a ROSETTE rather than a pinwheel:
     * the copy is mirrored first, then turned into its sector.
     *
     * The identity is never in the list — that is the stroke you actually drew.
     * So mirror alone returns 1, radial n alone returns n-1, and the two
     * together return 2n-1, for 2n marks on the page.
     */
    fun symmetryMatrices(mirror: String?, radial: Int): List<Mat4> {
        val n = maxOf(1, radial)
        val mm = mirror?.let { mirrorMatrix(it) }
        val out = ArrayList<Mat4>()
        for (i in 0 until n) {
            val rot = Mat4.rotationY(i * Math.PI * 2 / n, Mat4())
            if (i > 0) out.add(rot)
            if (mm != null) out.add(Mat4.multiply(rot, mm, Mat4()))
        }
        return out
    }

    // ---- restyling --------------------------------------------------------

    /**
     * [base] is the snapshot a scale is measured from, so dragging a slider
     * back and forth cannot compound: every frame of the drag scales the
     * ORIGINAL radius, not the one the previous frame left behind.
     */
    fun restyle(strokes: List<Stroke>, changes: StyleChange, base: List<Double>? = null) {
        val minR = Tune.BRUSH_MIN_MM * MM * 0.5
        val maxR = Tune.BRUSH_MAX_MM * MM * 0.5
        for (i in strokes.indices) {
            val st = strokes[i]
            changes.brush?.let { st.brush = it }
            changes.color?.let { st.color = it }
            changes.opacity?.let { st.opacity = it }
            changes.scale?.let { k ->
                val from = base?.getOrNull(i) ?: st.baseRadius
                st.baseRadius = clamp(from * k, minR, maxR)
            }
        }
    }

    /** The radii a scale drag should measure from. */
    fun radiiOf(strokes: List<Stroke>): List<Double> = strokes.map { it.baseRadius }

    /** A property the whole selection agrees on, or null where it does not. */
    fun styleOf(strokes: List<Stroke>): Style? {
        if (strokes.isEmpty()) return null
        var brush: String? = strokes[0].brush
        var color: Rgba? = strokes[0].color
        var opacity: Double? = strokes[0].opacity
        var sum = 0.0
        for (st in strokes) {
            if (st.brush != brush) brush = null
            if (st.color != color) color = null
            if (st.opacity != opacity) opacity = null
            sum += st.baseRadius
        }
        return Style(brush, color, opacity, sum / strokes.size)
    }
}
