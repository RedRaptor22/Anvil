package art.plume.core

import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * WHICH WAY THE NIB POINTS.
 *
 * A flat nib has a direction, and something has to decide it.
 *
 * This port originally handed the job to the pen: the cross-section was rolled
 * about the stroke's tangent by the stylus tilt azimuth, on the reading that
 * C.3's "tilt turns the nib" should survive. It cannot, and the web build
 * carries the same finding after trying it. ANY rotation about the tangent
 * lifts a blade OFF the surface, and a pen held at a natural angle reports
 * azimuths right across the range — so a ribbon painted on a wall stood at
 * whatever angle the hand happened to hold, which is exactly the "the brushes
 * protrude outwards instead of sticking to the guide" that came back from the
 * device. It was worst on `wide` because `wide` is the widest blade of the
 * eight; a round pen has no direction to get wrong.
 *
 * The SURFACE is what decides it. Every sample already carries the normal of
 * whatever it landed on — guide, imported mesh, or the pivot plane when
 * nothing is active — so the wide axis is `t x n`: perpendicular to the
 * stroke, lying IN the surface. The thin axis then comes out along the normal,
 * and a blade reads as a blade held flat against the guide, which is what it
 * is.
 *
 * Tilt is still recorded per point. It no longer turns the section.
 *
 * With no guide the pivot plane faces the camera, so its normal IS the view
 * direction and free-space strokes look exactly as they did.
 */
object Nib {

    /** never let a section collapse to zero area at an edge */
    const val FIT_MIN = 0.02

    /**
     * The wide axis at [pt] for a stroke running along [t]: in the surface,
     * across the stroke.
     */
    fun axisAt(pt: StrokePoint, t: Vec3, out: Vec3 = Vec3()): Vec3 {
        val n = pt.nrm
        if (n != null && n.lengthSq() > Vec3.EPS) {
            out.set(t cross n)
            if (out.lengthSq() > Vec3.EPS) return out.normalize()
        }
        // no surface to lie in: the camera-plane axis the sample was taken with
        return out.set(pt.axis ?: pt.ref ?: t)
    }

    /**
     * The cross-section angle that puts [axisAt] where it belongs, measured in
     * the transported frame ([t], [r]).
     *
     * Stored as an angle rather than a vector because that is what survives
     * every later edit: smooth, liquify, bend and the joystick all move points
     * without knowing anything about surfaces, and a stroke has to keep
     * looking like itself afterwards.
     */
    fun rollOf(pt: StrokePoint, t: Vec3, r: Vec3): Double {
        val s = t cross r
        val axis = axisAt(pt, t)
        // the part of the axis that lies in the section plane
        val proj = axis.copy().addScaled(t, -(axis dot t))
        if (proj.lengthSq() < Vec3.EPS) return 0.0
        return atan2(proj dot s, proj dot r)
    }

    /**
     * HOW MUCH OF THE NIB FITS.
     *
     * The nib is wide across the stroke, so near a guide's edge part of it
     * would land off the surface. Points with no surface frame — free space, a
     * closed guide, an off-surface clamp — keep the full nib.
     */
    fun fitAt(pt: StrokePoint, t: Vec3, halfWidth: Double) {
        val f = pt.surf
        /* KEEP A TRIM WE CANNOT RE-MEASURE. `surf` is spent by the first
           freeze, so every later one arrives without it. A tool that nudges a
           point by a fraction of a millimetre has not changed how much room
           that section has, so the measured value stands until something can
           measure it again. */
        if (f == null || halfWidth <= Vec3.EPS) return
        val reach = GuidePainting.reachAlong(f, axisAt(pt, t))
        pt.fitR = clamp(reach.pos / halfWidth, FIT_MIN, 1.0)
        pt.fitL = clamp(reach.neg / halfWidth, FIT_MIN, 1.0)
    }

    /**
     * The trim is measured per point, and per-point measurements of anything
     * jitter: the arc position is read from whichever cell of the surface grid
     * the sample landed in, and the nib's direction wanders by a fraction of a
     * degree between samples. Left alone that is a ragged edge where the paint
     * meets the boundary. Two passes of a three-tap average take it out
     * without moving where the edge actually is.
     */
    fun smoothFit(pts: List<StrokePoint>) {
        val n = pts.size
        if (n < 3) return
        /* the measured limit, kept aside: averaging may pull a section IN but
           never push one back out past what was measured for it, or a column
           painted along a boundary creeps over the edge again wherever its
           neighbours happen to have more room */
        val capL = DoubleArray(n) { pts[it].fitL }
        val capR = DoubleArray(n) { pts[it].fitR }
        repeat(2) {
            val l = DoubleArray(n); val r = DoubleArray(n)
            for (i in 0 until n) {
                val a = pts[max(0, i - 1)]; val b = pts[i]; val c = pts[min(n - 1, i + 1)]
                l[i] = (a.fitL + 2 * b.fitL + c.fitL) / 4
                r[i] = (a.fitR + 2 * b.fitR + c.fitR) / 4
            }
            for (i in 0 until n) {
                pts[i].fitL = min(l[i], capL[i])
                pts[i].fitR = min(r[i], capR[i])
            }
        }
    }

    /**
     * Freeze the frames into the points.
     *
     * This is the step that makes orientation PERSISTENT rather than
     * re-derived, and it is what erase, bend and the joystick transform all
     * read back. Run it once, when a stroke is committed.
     */
    fun freezeFrames(stroke: Stroke) {
        val pts = stroke.pts
        if (pts.isEmpty()) return
        val world = pts.map { it.p }
        val fr = Frames.transportFrames(world, stroke.seedRef, Frames.loopsClosed(world))
        val arc = Frames.arcLengths(world)
        val total = arc[pts.size - 1]
        for (i in pts.indices) {
            val t = fr.t[i]; val r = fr.r[i]; val pt = pts[i]
            pt.roll = rollOf(pt, t, r)
            val sh = StrokeGeometry.shadeAt(stroke, i, arc[i], total)
            fitAt(pt, t, StrokeGeometry.halfWidth(stroke, sh.radius))
            pt.tan = t.copy()
            pt.ref = r.copy()
            pt.axis = null
            pt.surf = null      // transient: the frame is spent here
        }
        smoothFit(pts)
    }
}
