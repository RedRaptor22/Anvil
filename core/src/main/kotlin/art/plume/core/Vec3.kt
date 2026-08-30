package art.plume.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A mutable 3-vector.
 *
 * Mutable on purpose. Rebuilding a stroke touches every point on every
 * pointermove, and the JS original spent real effort keeping scratch vectors
 * out of the allocator for exactly that reason. Operations come in two forms:
 * the infix/operator ones allocate and read well in tests, and the `…Into`
 * ones write through a destination for the hot paths.
 */
data class Vec3(var x: Double = 0.0, var y: Double = 0.0, var z: Double = 0.0) {

    fun set(nx: Double, ny: Double, nz: Double): Vec3 { x = nx; y = ny; z = nz; return this }
    fun set(o: Vec3): Vec3 = set(o.x, o.y, o.z)
    fun copy(): Vec3 = Vec3(x, y, z)

    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)

    infix fun dot(o: Vec3): Double = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x
    )

    fun lengthSq(): Double = x * x + y * y + z * z
    fun length(): Double = sqrt(lengthSq())
    fun distanceTo(o: Vec3): Double = sqrt(distanceToSq(o))
    fun distanceToSq(o: Vec3): Double {
        val dx = x - o.x; val dy = y - o.y; val dz = z - o.z
        return dx * dx + dy * dy + dz * dz
    }

    fun normalize(): Vec3 {
        val l = length()
        if (l > EPS) { x /= l; y /= l; z /= l }
        return this
    }

    fun addScaled(o: Vec3, s: Double): Vec3 {
        x += o.x * s; y += o.y * s; z += o.z * s; return this
    }

    /** Rodrigues rotation about a unit axis, in place. */
    fun rotateAbout(axis: Vec3, angle: Double): Vec3 {
        val c = kotlin.math.cos(angle)
        val s = kotlin.math.sin(angle)
        val d = this dot axis
        val cx = axis.y * z - axis.z * y
        val cy = axis.z * x - axis.x * z
        val cz = axis.x * y - axis.y * x
        val nx = x * c + cx * s + axis.x * d * (1 - c)
        val ny = y * c + cy * s + axis.y * d * (1 - c)
        val nz = z * c + cz * s + axis.z * d * (1 - c)
        return set(nx, ny, nz)
    }

    companion object {
        const val EPS = 1e-12

        /** Any unit vector perpendicular to [t]. */
        fun perpTo(t: Vec3, out: Vec3 = Vec3()): Vec3 {
            val ax = if (abs(t.x) < 0.9) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
            out.set(t cross ax)
            if (out.lengthSq() < EPS) out.set(1.0, 0.0, 0.0) else out.normalize()
            return out
        }
    }
}

/** 1 world unit is 1000 mm, so a millimetre is this. Same constant as `P.MM`. */
const val MM = 0.001

fun clamp(v: Double, lo: Double, hi: Double): Double = if (v < lo) lo else if (v > hi) hi else v
fun clamp(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else if (v > hi) hi else v
