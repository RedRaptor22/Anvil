package art.plume.core

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * The spherical camera, ported from `P.VIEW` and `applyCamera()` in
 * `js/camera.js`.
 *
 * Position is an azimuth, a polar angle and a radius about a pivot, plus a
 * roll so a two-finger twist can rotate the canvas. A perspective and an
 * orthographic projection are both kept live and swapped on demand; the ortho
 * frustum is sized to match the perspective framing AT THE PIVOT DISTANCE, so
 * the toggle does not appear to jump.
 *
 * FOV is expressed the way Feather expresses it: as a lens focal length in
 * millimetres (FACT: 10-500 mm), converted against a 24 mm sensor height.
 * Storing a focal length rather than an angle is not decoration — it is what
 * makes "50 mm" mean the same framing on a phone as in the browser.
 *
 * **Pixel coordinates here are view-local**, unlike the web build, whose
 * `worldToScreen` adds the canvas rect's origin because pointer events report
 * client coordinates. Android's `MotionEvent` already reports coordinates
 * relative to the view, so adding an origin here would double-count it. That
 * is the only intentional difference; every world-space answer matches.
 */
class Camera {

    val pivot = Vec3()
    var theta = Math.PI * 0.25          // azimuth
    var phi = Math.PI * 0.42            // polar, clamped away from the poles
    var radius = Tune.RADIUS_DEFAULT
    var roll = 0.0
    var focal = Tune.FOCAL_DEFAULT      // mm
    var ortho = false

    /** True once a press-and-hold has pinned the pivot; changes what orbit does. */
    var pinned = false

    var width = 1
        private set
    var height = 1
        private set

    val eye = Vec3()

    val view = Mat4()
    val projection = Mat4()
    val viewProjection = Mat4()
    private val inverseViewProjection = Mat4()
    private var inverseOk = false

    private val rollMat = Mat4()
    private val scratch = Mat4()
    private val tmpA = Vec3()
    private val tmpB = Vec3()

    /** The plane a stroke lands on when no guide is active. */
    val drawPlane = Plane()

    private var spinTheta = 0.0
    private var spinPhi = 0.0

    init { apply() }

    fun resize(w: Int, h: Int) {
        width = max(1, w); height = max(1, h)
        apply()
    }

    val aspect: Double get() = width.toDouble() / height.toDouble()

    // ---- lens -----------------------------------------------------------

    /** Focal length in mm to a vertical field of view in degrees. */
    fun fovFromFocal(mm: Double): Double =
        2.0 * atan(Tune.SENSOR_HEIGHT_MM / (2.0 * mm)) * 180.0 / Math.PI

    /** World height covered by the viewport at the pivot's depth. */
    fun viewHeight(): Double =
        2.0 * tan(fovFromFocal(focal) * Math.PI / 360.0) * radius

    /** Pixels to world units at the pivot's depth, so brush size stays perceptual. */
    fun pxToWorld(px: Double): Double = px * (viewHeight() / height)

    fun worldToPx(w: Double): Double = w * (height / viewHeight())

    /**
     * Pixels to world units AT A GIVEN POINT rather than at the pivot.
     *
     * Under perspective a pixel covers more world the further away it is, so a
     * drag that must follow the pen — Liquify — needs the scale where the point
     * actually is. Orthographic has no such falloff and returns the pivot scale.
     */
    fun pxToWorldAt(p: Vec3): Double {
        if (ortho) return viewHeight() / height
        forward(tmpA)
        tmpB.set(p.x - eye.x, p.y - eye.y, p.z - eye.z)
        var d = abs(tmpA dot tmpB)                 // depth along the view axis
        if (!(d > 1e-6)) d = radius
        return 2.0 * tan(fovFromFocal(focal) * Math.PI / 360.0) * d / height
    }

    // ---- the matrices ---------------------------------------------------

    /**
     * Rebuild the matrices from the current pose. Cheap enough to call on every
     * change; nothing caches a stale view.
     */
    fun apply(): Camera {
        phi = clamp(phi, Tune.PHI_EPS, Math.PI - Tune.PHI_EPS)
        radius = clamp(radius, Tune.RADIUS_MIN, Tune.RADIUS_MAX)
        focal = clamp(focal, Tune.FOCAL_MIN, Tune.FOCAL_MAX)

        val sp = sin(phi)
        eye.set(
            pivot.x + radius * sp * sin(theta),
            pivot.y + radius * cos(phi),
            pivot.z + radius * sp * cos(theta),
        )

        Mat4.lookAt(eye, pivot, UP, view)
        if (roll != 0.0) {
            /*
             * three.js rolls the CAMERA OBJECT by `rotateZ(roll)`, and a view
             * matrix is that object's inverse — so the view picks up a rotation
             * by MINUS roll, not plus. Getting this backwards is invisible at
             * rest and spins the canvas the wrong way the moment two fingers
             * twist, which is exactly the kind of thing that only shows up on
             * hardware.
             */
            Mat4.rotationZ(-roll, rollMat)
            Mat4.multiply(rollMat, view, view)
        }

        if (ortho) {
            val h = viewHeight() / 2.0
            val w = h * aspect
            // the ortho near plane goes NEGATIVE so geometry behind the pivot
            // is not clipped away when the projection has no perspective to
            // push it off screen
            Mat4.orthographic(-w, w, -h, h, -4000.0, 8000.0, projection)
        } else {
            Mat4.perspective(fovFromFocal(focal), aspect, 0.02, 8000.0, projection)
        }

        Mat4.multiply(projection, view, viewProjection)
        inverseOk = Mat4.invert(viewProjection, inverseViewProjection)
        refreshDrawPlane(null)
        return this
    }

    /** Right, up and backward, the three columns of the camera's world matrix. */
    fun basis(right: Vec3, up: Vec3, backward: Vec3) {
        right.set(view.m[0], view.m[4], view.m[8])
        up.set(view.m[1], view.m[5], view.m[9])
        backward.set(view.m[2], view.m[6], view.m[10])
    }

    /** The direction the camera looks, which is minus the third basis column. */
    fun forward(out: Vec3): Vec3 = out.set(-view.m[2], -view.m[6], -view.m[10])

    // ---- screen <-> world -----------------------------------------------

    /**
     * A world point to view-local pixels. `out.z` comes back as the NDC depth:
     * outside [-1, 1] means the point is off the frustum, which is the cheapest
     * way to reject something behind the camera before drawing UI at it.
     */
    fun worldToScreen(p: Vec3, out: Vec3): Vec3 {
        viewProjection.transformPoint(p, out)
        val ndcX = out.x; val ndcY = out.y; val ndcZ = out.z
        out.set(
            (ndcX * 0.5 + 0.5) * width,
            (-ndcY * 0.5 + 0.5) * height,
            ndcZ,
        )
        return out
    }

    /**
     * View-local pixels to a world ray.
     *
     * Built by unprojecting the near and far plane points rather than by
     * assembling a direction from the basis: that way it is correct for the
     * orthographic projection too, where every ray is parallel and none of them
     * passes through the eye.
     */
    fun rayFrom(px: Double, py: Double, out: Ray = Ray()): Ray {
        if (!inverseOk) return out
        val nx = 2.0 * px / width - 1.0
        val ny = 1.0 - 2.0 * py / height
        tmpA.set(nx, ny, -1.0)
        inverseViewProjection.transformPoint(tmpA, out.origin)
        tmpB.set(nx, ny, 1.0)
        inverseViewProjection.transformPoint(tmpB, tmpA)
        out.direction.set(tmpA.x - out.origin.x, tmpA.y - out.origin.y, tmpA.z - out.origin.z)
        out.direction.normalize()
        return out
    }

    // ---- the draw plane -------------------------------------------------

    /**
     * The sketching plane used when no guide is active: camera-facing, through
     * the pivot. The web build freezes it at stroke start, because the camera
     * cannot move mid-pen-stroke; the same rule applies here, and the caller
     * enforces it by simply not calling this while a stroke is live.
     */
    fun refreshDrawPlane(at: Vec3?): Plane {
        forward(tmpA)
        drawPlane.setFromNormalAndCoplanarPoint(tmpA, at ?: pivot)
        return drawPlane
    }

    /**
     * Where the pen is, on the draw plane. Null when the ray runs parallel to
     * the plane, or when the hit is behind the eye — a point the camera cannot
     * see is not a point the pen touched.
     */
    fun planePoint(px: Double, py: Double, out: Vec3, ray: Ray = Ray()): Vec3? {
        rayFrom(px, py, ray)
        val t = drawPlane.intersectRay(ray, out) ?: return null
        if (!ortho && t <= 0.0) return null
        return out
    }

    // ---- navigation -----------------------------------------------------

    /** One finger, dragging: swing the camera around the pivot. */
    fun orbitBy(dxPx: Double, dyPx: Double): Camera {
        theta -= dxPx * Tune.ORBIT_PER_PX
        phi -= dyPx * Tune.ORBIT_PER_PX
        return apply()
    }

    /**
     * Slide the pivot in the camera's own plane, so a drag moves the sketch by
     * exactly the pixels the fingers moved. Pinning is a side effect on purpose:
     * once you have panned, the thing you panned to is what you orbit around.
     */
    fun panBy(dxPx: Double, dyPx: Double): Camera {
        val right = Vec3(); val up = Vec3(); val back = Vec3()
        basis(right, up, back)
        val s = viewHeight() / height
        pivot.addScaled(right, -dxPx * s)
        pivot.addScaled(up, dyPx * s)
        pinned = true
        return apply()
    }

    /** [factor] > 1 moves the camera away; a pinch apart passes 1/span-ratio. */
    fun zoomBy(factor: Double): Camera {
        if (factor > 0.0) radius *= factor
        return apply()
    }

    /** A mouse wheel, in the web build's units, so both builds zoom alike. */
    fun zoomByWheel(deltaY: Double): Camera = zoomBy(exp(deltaY * 0.0012))

    fun rollBy(dRadians: Double): Camera { roll += dRadians; return apply() }

    // ---- release momentum -----------------------------------------------

    /** The last per-move deltas, held so a flick keeps turning after release. */
    fun addSpin(dTheta: Double, dPhi: Double) { spinTheta = dTheta; spinPhi = dPhi }

    fun killSpin() { spinTheta = 0.0; spinPhi = 0.0 }

    val spinning: Boolean
        get() = abs(spinTheta) >= Tune.SPIN_STOP || abs(spinPhi) >= Tune.SPIN_STOP

    /** Advance one frame of momentum; false once it has died out. */
    fun tickSpin(): Boolean {
        if (!spinning) return false
        theta += spinTheta
        phi += spinPhi
        spinTheta *= Tune.SPIN_DECAY
        spinPhi *= Tune.SPIN_DECAY
        apply()
        return true
    }

    private companion object {
        val UP = Vec3(0.0, 1.0, 0.0)
    }
}
