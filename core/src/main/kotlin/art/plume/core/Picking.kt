package art.plume.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.tan

/**
 * Tapping a stroke.
 *
 * The web build raycasts the built tube meshes, which is exact but needs the
 * renderer's geometry to answer a question about the model. A stroke is a
 * polyline with a known radius, so the same answer comes out of the centreline
 * and costs nothing to test on a JVM: the ray hits if it passes within the
 * tube's radius of the centreline.
 *
 * The radius used is the WIDEST the section can be. A chisel brush is 3.4x
 * wide and 0.04 thick, and picking against the thin axis would make it
 * essentially untappable when it happens to be edge-on to the camera.
 */
object Picking {

    /**
     * How far along [dir] from [origin] the ray first comes within the
     * stroke's radius plus [slack] of it — or null if it never does.
     *
     * [dir] must be normalised. Only the forward half of the ray counts, so a
     * stroke behind the eye is never picked.
     */
    fun rayHit(stroke: Stroke, origin: Vec3, dir: Vec3, slack: Double = 0.0): Double? {
        if (stroke.pts.size < 2) return null
        val r = max(
            StrokeGeometry.halfWidth(stroke, stroke.baseRadius),
            StrokeGeometry.halfThick(stroke, stroke.baseRadius),
        ) + slack
        val r2 = r * r
        var best = Double.MAX_VALUE
        for (i in 0 until stroke.pts.size - 1) {
            val (d2, t) = raySegment(origin, dir, stroke.pts[i].p, stroke.pts[i + 1].p)
            if (t < 0.0 || d2 > r2 || t >= best) continue
            best = t
        }
        return if (best == Double.MAX_VALUE) null else best
    }

    /**
     * Squared distance between a ray and a segment, with how far along the ray
     * the closest approach lies. A negative distance means "behind the eye".
     *
     * The clamped closest-point-between-two-lines solve, with `u = dir` unit:
     *
     *     s = ((ab·dir)(ao·dir) - ao·ab) / (|ab|² - (ab·dir)²)
     *
     * The denominator vanishes when the segment is parallel to the ray, which
     * is not an exotic case — a stroke drawn straight away from the camera is
     * exactly that — so it falls back to the segment's start rather than
     * dividing by zero. Clamping s to the segment and re-projecting onto the
     * ray gives the right answer for the clamped case too.
     */
    fun raySegment(origin: Vec3, dir: Vec3, a: Vec3, b: Vec3): Pair<Double, Double> {
        val ab = b - a
        val ao = a - origin
        val abab = ab.lengthSq()
        val abd = ab dot dir
        val aod = ao dot dir
        val aoab = ao dot ab

        val denom = abab - abd * abd
        val s = if (abab < Vec3.EPS || abs(denom) < Vec3.EPS) 0.0
                else clamp((abd * aod - aoab) / denom, 0.0, 1.0)

        val ox = a.x + ab.x * s - origin.x
        val oy = a.y + ab.y * s - origin.y
        val oz = a.z + ab.z * s - origin.z
        val t = ox * dir.x + oy * dir.y + oz * dir.z
        if (t < 0.0) return (ox * ox + oy * oy + oz * oz) to -1.0

        val qx = ox - dir.x * t; val qy = oy - dir.y * t; val qz = oz - dir.z * t
        return (qx * qx + qy * qy + qz * qz) to t
    }

    /**
     * How much slack a finger-sized tap is worth, in world units, at
     * [distance] from the eye.
     *
     * A tap target measured in millimetres of world space is wrong on a phone:
     * zoomed out, a stroke is a hair and nothing is tappable; zoomed in, the
     * slack swallows everything nearby. The tolerance belongs in pixels and is
     * converted here.
     */
    fun slackFor(
        distance: Double,
        tapPixels: Double,
        viewportHeight: Double,
        fovYRadians: Double,
    ): Double {
        if (viewportHeight <= 0.0) return 0.0
        val worldPerPixel = 2.0 * distance * tan(fovYRadians * 0.5) / viewportHeight
        return max(0.0, tapPixels * worldPerPixel)
    }
}
