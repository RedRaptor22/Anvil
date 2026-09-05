package art.plume.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The transform gizmo's arithmetic.
 *
 * Ported from `deltaMatrix`, `axisDelta` and their helpers in js/ui.js. The
 * widget is a pad with three coloured arcs; this is what a drag on it means.
 *
 * It is in core because every one of these is a claim that can be checked. A
 * rotation must turn the selection about its own centre rather than about the
 * world origin — the difference is invisible for a sketch drawn near zero and
 * catastrophic for one drawn ten metres out. A move along an axis must move by
 * what the finger travelled ALONG that axis on screen, converted back by that
 * axis's own pixels-per-unit, or dragging the X handle on an axis pointing
 * nearly at the camera sends the selection to the horizon.
 */
object Transform {

    /** What a drag does. */
    enum class Mode { MOVE, ROTATE, SCALE }

    val AXES = listOf(
        Vec3(1.0, 0.0, 0.0),
        Vec3(0.0, 1.0, 0.0),
        Vec3(0.0, 0.0, 1.0),
    )

    /** GUESS: radians per pixel for a free rotation drag. */
    const val ROTATE_PER_PX = 0.011

    /** GUESS: the exponent for a scale drag, so it is geometric. */
    const val SCALE_PER_PX = 0.006

    /**
     * Conjugate [m] by a translation to [c], so it happens ABOUT that point.
     *
     * `T(c) · M · T(-c)`. Without it every rotation and scale is about the
     * world origin, which for a sketch drawn away from centre throws it across
     * the scene rather than turning it in place.
     */
    fun about(c: Vec3, m: Mat4, out: Mat4 = Mat4()): Mat4 {
        val t = Mat4.translation(c.x, c.y, c.z, Mat4())
        val back = Mat4.translation(-c.x, -c.y, -c.z, Mat4())
        return Mat4.multiply(t, Mat4.multiply(m, back, Mat4()), out)
    }

    /** A rotation of [angle] about an arbitrary unit [axis] (Rodrigues). */
    fun rotationAxis(axis: Vec3, angle: Double, out: Mat4 = Mat4()): Mat4 {
        val a = axis.copy().normalize()
        val c = cos(angle)
        val s = sin(angle)
        val t = 1 - c
        val m = out.m
        m[0] = t * a.x * a.x + c; m[4] = t * a.x * a.y - s * a.z; m[8] = t * a.x * a.z + s * a.y; m[12] = 0.0
        m[1] = t * a.x * a.y + s * a.z; m[5] = t * a.y * a.y + c; m[9] = t * a.y * a.z - s * a.x; m[13] = 0.0
        m[2] = t * a.x * a.z - s * a.y; m[6] = t * a.y * a.z + s * a.x; m[10] = t * a.z * a.z + c; m[14] = 0.0
        m[3] = 0.0; m[7] = 0.0; m[11] = 0.0; m[15] = 1.0
        return out
    }

    /**
     * Scale by [k] along [axis] only, leaving the two perpendicular directions
     * alone: `I + (k-1)·aaᵀ`.
     */
    fun axisScale(axis: Vec3, k: Double, out: Mat4 = Mat4()): Mat4 {
        val a = axis.copy().normalize()
        val f = k - 1
        val m = out.m
        m[0] = 1 + f * a.x * a.x; m[4] = f * a.x * a.y; m[8] = f * a.x * a.z; m[12] = 0.0
        m[1] = f * a.x * a.y; m[5] = 1 + f * a.y * a.y; m[9] = f * a.y * a.z; m[13] = 0.0
        m[2] = f * a.x * a.z; m[6] = f * a.y * a.z; m[10] = 1 + f * a.z * a.z; m[14] = 0.0
        m[3] = 0.0; m[7] = 0.0; m[11] = 0.0; m[15] = 1.0
        return out
    }

    /**
     * Where a world axis points ON SCREEN at [centre], and how many pixels one
     * world unit along it covers.
     *
     * Null when the axis is nearly end-on: its screen direction is then
     * meaningless, and a drag divided by a vanishing pixels-per-unit would
     * send the selection to the horizon. The widget dims that arc instead.
     */
    class ScreenAxis(val ux: Double, val uy: Double, val pxPerUnit: Double)

    fun axisOnScreen(camera: Camera, axis: Vec3, centre: Vec3): ScreenAxis? {
        val a = Vec3()
        val b = Vec3()
        camera.worldToScreen(centre, a)
        camera.worldToScreen(
            Vec3(centre.x + axis.x, centre.y + axis.y, centre.z + axis.z), b,
        )
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (!len.isFinite() || len < MIN_AXIS_PX) return null
        return ScreenAxis(dx / len, dy / len, len)
    }

    /** Below this an axis is end-on and has no usable screen direction. */
    const val MIN_AXIS_PX = 4.0

    /**
     * A screen direction for [axis] THAT ALWAYS EXISTS.
     *
     * An axis pointing at the camera projects to a point, so there is no
     * direction on the glass that means "along it" — and the joystick's answer
     * was to grey that arc out. Which greys out whichever axis you are looking
     * down: turn to face the front of a model and the one direction you can no
     * longer move it is towards you, at exactly the moment you are looking
     * straight at where it should go.
     *
     * The arcs are not a projected gizmo, they are three labelled controls, so
     * an axis with no direction on screen needs a CONVENTION rather than a
     * refusal. The convention is the one the pad's own depth strip already
     * uses: a vertical drag, up for away. Rotation never needed this — a
     * spin about an axis pointing at you is the easiest one to read — and
     * scale follows the same up-is-more it has everywhere else.
     */
    fun screenAxis(camera: Camera, axis: Vec3, centre: Vec3): ScreenAxis {
        axisOnScreen(camera, axis, centre)?.let { return it }
        val r = Vec3(); val u = Vec3(); val back = Vec3()
        camera.basis(r, u, back)
        val towardEye = (axis dot back) >= 0.0
        val px = camera.height / camera.viewHeight()
        return ScreenAxis(0.0, if (towardEye) 1.0 else -1.0, if (px.isFinite()) px else 1.0)
    }

    // ---- a free drag on the pad -------------------------------------------

    /**
     * A drag with no axis grabbed: the pad's centre moves in the screen plane,
     * and the strip below it moves along the view direction.
     */
    fun free(
        camera: Camera, mode: Mode, dx: Double, dy: Double, centre: Vec3,
        strip: Boolean, out: Mat4 = Mat4(),
    ): Mat4 {
        val r = Vec3(); val u = Vec3(); val f = Vec3()
        camera.basis(r, u, f)
        // `backward` points away from the scene, so depth is its negation
        val world = camera.viewHeight() / camera.height

        return when (mode) {
            Mode.MOVE -> {
                val v = Vec3()
                if (strip) v.addScaled(f, dy * world)
                else { v.addScaled(r, dx * world); v.addScaled(u, -dy * world) }
                Mat4.translation(v.x, v.y, v.z, out)
            }
            Mode.ROTATE -> {
                val m = if (strip) {
                    rotationAxis(f, dy * ROTATE_PER_PX)
                } else {
                    Mat4.multiply(
                        rotationAxis(u, dx * ROTATE_PER_PX),
                        rotationAxis(r, dy * ROTATE_PER_PX),
                        Mat4(),
                    )
                }
                about(centre, m, out)
            }
            Mode.SCALE -> {
                val k = exp(-dy * SCALE_PER_PX)
                about(centre, Mat4.scale(k, k, k, Mat4()), out)
            }
        }
    }

    // ---- a drag with an axis grabbed ---------------------------------------

    /**
     * A drag on one of the three arcs.
     *
     * Move takes how far the drag went along that axis's SCREEN direction and
     * converts it back by that axis's own pixels-per-unit, so the selection
     * tracks the finger whichever way the axis happens to be pointing. Rotate
     * sweeps around the pad's centre. Scale is geometric along the axis alone.
     */
    fun alongAxis(
        mode: Mode, axis: Vec3, screen: ScreenAxis, centre: Vec3,
        dx: Double, dy: Double, sweep: Double, out: Mat4 = Mat4(),
    ): Mat4 = when (mode) {
        Mode.MOVE -> {
            val along = (dx * screen.ux + dy * screen.uy) / screen.pxPerUnit
            Mat4.translation(axis.x * along, axis.y * along, axis.z * along, out)
        }
        Mode.ROTATE -> about(centre, rotationAxis(axis, sweep), out)
        Mode.SCALE -> {
            val k = exp((dx * screen.ux + dy * screen.uy) * SCALE_PER_PX)
            about(centre, axisScale(axis, k), out)
        }
    }

    /**
     * Which arc a press landed on, or null for the free centre.
     *
     * [angles] are where the three arcs sit on the ring; an axis whose
     * [ScreenAxis] is null is skipped, because there is nothing to drag along.
     */
    fun pickAxis(
        localX: Double, localY: Double, centre: Double, inner: Double,
        angles: List<Double>, usable: List<Boolean>,
    ): Int? {
        val dx = localX - centre
        val dy = localY - centre
        if (hypot(dx, dy) < inner) return null
        val ang = kotlin.math.atan2(dy, dx)
        var best: Int? = null
        var bestD = PICK_ARC          // ~57 degrees either side
        for (i in angles.indices) {
            if (!usable.getOrElse(i) { false }) continue
            val d = abs(((ang - angles[i] + Math.PI * 3) % (Math.PI * 2)) - Math.PI)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** How far from an arc's centre a press still counts as grabbing it. */
    const val PICK_ARC = 1.0

    /**
     * SVG angles run clockwise from east, so -90 degrees is straight up. The
     * three arcs sit a third of the ring apart, at fixed points, so a handle
     * stays where you last reached for it.
     */
    val ARC_ANGLES = listOf(Math.PI / 6, -Math.PI / 2, Math.PI * 5 / 6)
}
