package art.plume.core

import kotlin.math.abs
import kotlin.math.tan

/**
 * A 4x4 matrix, column-major, in doubles.
 *
 * Column-major is not a preference — it is the layout `glUniformMatrix4fv`
 * expects, so [into] can hand a frame's matrix straight to the driver with no
 * transpose. Element (row, col) lives at `m[col * 4 + row]`.
 *
 * The reason this exists rather than `android.opengl.Matrix`: the projection
 * maths is the part most likely to be subtly wrong, and anything that imports
 * `android.*` cannot be tested without a device. Everything here runs on a
 * plain JVM, so the round-trip a stroke depends on — screen pixel to world ray
 * and back — is under test rather than under inspection.
 *
 * Doubles throughout, converted to float only at the GL boundary. A camera
 * 400 units out with a 0.02 near plane is already asking a lot of a float
 * depth range; the maths that gets there should not add to it.
 */
class Mat4 {

    val m = DoubleArray(16)

    init { identity() }

    fun identity(): Mat4 {
        for (i in 0..15) m[i] = 0.0
        m[0] = 1.0; m[5] = 1.0; m[10] = 1.0; m[15] = 1.0
        return this
    }

    fun set(o: Mat4): Mat4 { o.m.copyInto(m); return this }

    /** Copy into a float array for GL, which is the only place floats appear. */
    fun into(out: FloatArray): FloatArray {
        for (i in 0..15) out[i] = m[i].toFloat()
        return out
    }

    operator fun get(row: Int, col: Int): Double = m[col * 4 + row]

    companion object {

        /** out = a * b. Aliasing-safe: `out` may be either operand. */
        fun multiply(a: Mat4, b: Mat4, out: Mat4): Mat4 {
            val r = DoubleArray(16)
            for (c in 0..3) for (row in 0..3) {
                var s = 0.0
                for (k in 0..3) s += a.m[k * 4 + row] * b.m[c * 4 + k]
                r[c * 4 + row] = s
            }
            r.copyInto(out.m)
            return out
        }

        /**
         * A symmetric perspective frustum, the same one `THREE.PerspectiveCamera`
         * builds: [fovYDeg] is the FULL vertical angle, not the half-angle.
         */
        fun perspective(fovYDeg: Double, aspect: Double, near: Double, far: Double, out: Mat4): Mat4 {
            val f = 1.0 / tan(fovYDeg * Math.PI / 360.0)
            for (i in 0..15) out.m[i] = 0.0
            out.m[0] = f / aspect
            out.m[5] = f
            out.m[10] = (far + near) / (near - far)
            out.m[11] = -1.0
            out.m[14] = 2.0 * far * near / (near - far)
            return out
        }

        fun orthographic(
            l: Double, r: Double, b: Double, t: Double, n: Double, f: Double, out: Mat4,
        ): Mat4 {
            for (i in 0..15) out.m[i] = 0.0
            out.m[0] = 2.0 / (r - l)
            out.m[5] = 2.0 / (t - b)
            out.m[10] = -2.0 / (f - n)
            out.m[12] = -(r + l) / (r - l)
            out.m[13] = -(t + b) / (t - b)
            out.m[14] = -(f + n) / (f - n)
            out.m[15] = 1.0
            return out
        }

        /**
         * A view matrix looking from [eye] at [at].
         *
         * Degenerate cases matter here: if the eye sits exactly on the target,
         * or the view direction is parallel to [up], the cross products
         * collapse and every later unprojection returns NaN. The camera clamps
         * phi off the poles precisely so this cannot happen, but a view matrix
         * that silently produces NaN is a bad thing to leave lying about.
         */
        fun lookAt(eye: Vec3, at: Vec3, up: Vec3, out: Mat4): Mat4 {
            val f = (at - eye)
            if (f.lengthSq() < Vec3.EPS) f.set(0.0, 0.0, -1.0) else f.normalize()
            val s = f cross up
            if (s.lengthSq() < Vec3.EPS) Vec3.perpTo(f, s) else s.normalize()
            val u = s cross f

            out.m[0] = s.x; out.m[4] = s.y; out.m[8] = s.z; out.m[12] = -(s dot eye)
            out.m[1] = u.x; out.m[5] = u.y; out.m[9] = u.z; out.m[13] = -(u dot eye)
            out.m[2] = -f.x; out.m[6] = -f.y; out.m[10] = -f.z; out.m[14] = (f dot eye)
            out.m[3] = 0.0; out.m[7] = 0.0; out.m[11] = 0.0; out.m[15] = 1.0
            return out
        }

        /** Rotation about the Z axis, for the canvas roll a two-finger twist gives. */
        fun rotationZ(angle: Double, out: Mat4): Mat4 {
            val c = kotlin.math.cos(angle); val s = kotlin.math.sin(angle)
            out.identity()
            out.m[0] = c; out.m[4] = -s
            out.m[1] = s; out.m[5] = c
            return out
        }

        /**
         * The general inverse. Returns false and leaves [out] untouched when
         * the matrix is singular, rather than filling it with infinities —
         * a caller that ignores the result gets the previous frame's ray, which
         * is wrong but bounded, instead of a NaN that poisons a whole stroke.
         */
        fun invert(a: Mat4, out: Mat4): Boolean {
            val m = a.m
            val inv = DoubleArray(16)

            inv[0] = m[5]*m[10]*m[15] - m[5]*m[11]*m[14] - m[9]*m[6]*m[15] +
                m[9]*m[7]*m[14] + m[13]*m[6]*m[11] - m[13]*m[7]*m[10]
            inv[4] = -m[4]*m[10]*m[15] + m[4]*m[11]*m[14] + m[8]*m[6]*m[15] -
                m[8]*m[7]*m[14] - m[12]*m[6]*m[11] + m[12]*m[7]*m[10]
            inv[8] = m[4]*m[9]*m[15] - m[4]*m[11]*m[13] - m[8]*m[5]*m[15] +
                m[8]*m[7]*m[13] + m[12]*m[5]*m[11] - m[12]*m[7]*m[9]
            inv[12] = -m[4]*m[9]*m[14] + m[4]*m[10]*m[13] + m[8]*m[5]*m[14] -
                m[8]*m[6]*m[13] - m[12]*m[5]*m[10] + m[12]*m[6]*m[9]
            inv[1] = -m[1]*m[10]*m[15] + m[1]*m[11]*m[14] + m[9]*m[2]*m[15] -
                m[9]*m[3]*m[14] - m[13]*m[2]*m[11] + m[13]*m[3]*m[10]
            inv[5] = m[0]*m[10]*m[15] - m[0]*m[11]*m[14] - m[8]*m[2]*m[15] +
                m[8]*m[3]*m[14] + m[12]*m[2]*m[11] - m[12]*m[3]*m[10]
            inv[9] = -m[0]*m[9]*m[15] + m[0]*m[11]*m[13] + m[8]*m[1]*m[15] -
                m[8]*m[3]*m[13] - m[12]*m[1]*m[11] + m[12]*m[3]*m[9]
            inv[13] = m[0]*m[9]*m[14] - m[0]*m[10]*m[13] - m[8]*m[1]*m[14] +
                m[8]*m[2]*m[13] + m[12]*m[1]*m[10] - m[12]*m[2]*m[9]
            inv[2] = m[1]*m[6]*m[15] - m[1]*m[7]*m[14] - m[5]*m[2]*m[15] +
                m[5]*m[3]*m[14] + m[13]*m[2]*m[7] - m[13]*m[3]*m[6]
            inv[6] = -m[0]*m[6]*m[15] + m[0]*m[7]*m[14] + m[4]*m[2]*m[15] -
                m[4]*m[3]*m[14] - m[12]*m[2]*m[7] + m[12]*m[3]*m[6]
            inv[10] = m[0]*m[5]*m[15] - m[0]*m[7]*m[13] - m[4]*m[1]*m[15] +
                m[4]*m[3]*m[13] + m[12]*m[1]*m[7] - m[12]*m[3]*m[5]
            inv[14] = -m[0]*m[5]*m[14] + m[0]*m[6]*m[13] + m[4]*m[1]*m[14] -
                m[4]*m[2]*m[13] - m[12]*m[1]*m[6] + m[12]*m[2]*m[5]
            inv[3] = -m[1]*m[6]*m[11] + m[1]*m[7]*m[10] + m[5]*m[2]*m[11] -
                m[5]*m[3]*m[10] - m[9]*m[2]*m[7] + m[9]*m[3]*m[6]
            inv[7] = m[0]*m[6]*m[11] - m[0]*m[7]*m[10] - m[4]*m[2]*m[11] +
                m[4]*m[3]*m[10] + m[8]*m[2]*m[7] - m[8]*m[3]*m[6]
            inv[11] = -m[0]*m[5]*m[11] + m[0]*m[7]*m[9] + m[4]*m[1]*m[11] -
                m[4]*m[3]*m[9] - m[8]*m[1]*m[7] + m[8]*m[3]*m[5]
            inv[15] = m[0]*m[5]*m[10] - m[0]*m[6]*m[9] - m[4]*m[1]*m[10] +
                m[4]*m[2]*m[9] + m[8]*m[1]*m[6] - m[8]*m[2]*m[5]

            val det = m[0]*inv[0] + m[1]*inv[4] + m[2]*inv[8] + m[3]*inv[12]
            if (abs(det) < 1e-18) return false
            val d = 1.0 / det
            for (i in 0..15) out.m[i] = inv[i] * d
            return true
        }
    }

    /**
     * Transform a point and divide through by w — the projective step, which is
     * what makes this a projection rather than an affine map. Returns the w
     * that was divided out: it is negative for anything behind the eye, and a
     * caller that forgets to check gets a point mirrored through the camera.
     */
    fun transformPoint(p: Vec3, out: Vec3): Double {
        val x = m[0]*p.x + m[4]*p.y + m[8]*p.z + m[12]
        val y = m[1]*p.x + m[5]*p.y + m[9]*p.z + m[13]
        val z = m[2]*p.x + m[6]*p.y + m[10]*p.z + m[14]
        val w = m[3]*p.x + m[7]*p.y + m[11]*p.z + m[15]
        if (abs(w) < Vec3.EPS) { out.set(x, y, z); return w }
        val iw = 1.0 / w
        out.set(x * iw, y * iw, z * iw)
        return w
    }

    /** Transform a direction: no translation, no divide. */
    fun transformDirection(v: Vec3, out: Vec3): Vec3 = out.set(
        m[0]*v.x + m[4]*v.y + m[8]*v.z,
        m[1]*v.x + m[5]*v.y + m[9]*v.z,
        m[2]*v.x + m[6]*v.y + m[10]*v.z,
    )

    /** The three basis columns of a camera's world matrix: right, up, forward. */
    fun extractBasis(right: Vec3, up: Vec3, forward: Vec3) {
        right.set(m[0], m[1], m[2])
        up.set(m[4], m[5], m[6])
        forward.set(m[8], m[9], m[10])
    }
}

/** A ray, in world units. */
class Ray(val origin: Vec3 = Vec3(), val direction: Vec3 = Vec3(0.0, 0.0, -1.0)) {
    fun at(t: Double, out: Vec3): Vec3 = out.set(origin).addScaled(direction, t)
}

/**
 * An infinite plane, stored as a unit normal and a signed distance from the
 * origin — the same form `THREE.Plane` uses, so `constant` carries the same
 * sign convention and the two builds' arithmetic can be compared directly.
 */
class Plane(val normal: Vec3 = Vec3(0.0, 1.0, 0.0), var constant: Double = 0.0) {

    fun setFromNormalAndCoplanarPoint(n: Vec3, p: Vec3): Plane {
        normal.set(n).normalize()
        constant = -(normal dot p)
        return this
    }

    fun distanceToPoint(p: Vec3): Double = (normal dot p) + constant

    /**
     * Where a ray meets the plane, or null when it runs parallel to it.
     *
     * A hit BEHIND the ray origin is still a hit and is returned as one: the
     * caller decides whether a negative t is meaningful. For drawing it is not
     * — a point behind the eye is not something the pen touched — and
     * [Camera.planePoint] rejects it there rather than here.
     */
    fun intersectRay(r: Ray, out: Vec3): Double? {
        val denom = normal dot r.direction
        if (abs(denom) < 1e-12) {
            // parallel: only a hit if the origin is already in the plane, and
            // then every t is a hit, which is no use to anyone
            return null
        }
        val t = -(( normal dot r.origin) + constant) / denom
        r.at(t, out)
        return t
    }
}
