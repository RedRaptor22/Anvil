package art.plume.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The five readymade guides.
 *
 * The web build gets these from `THREE.BoxGeometry` and friends; there is no
 * equivalent here, so they are built by hand. **The dimensions are matched
 * exactly** — a sphere is radius 1.4, a cube spans -1..1, the cone is radius
 * 1.5 by height 2.4 — because a sketch drawn on a primitive in one build has
 * to measure the same in the other. Vertex ORDER is not matched and does not
 * need to be: nothing downstream indexes these by vertex, and the nearest-point
 * query works off triangles.
 *
 * These carry no (u, v) grid. That is not an oversight — a box and a sphere
 * have no single arc-length parameterisation — and it is why Fill does not
 * work on a primitive in either build. Painting on one works fine, because
 * that goes through the triangle query rather than the grid.
 */
object Primitives {

    val KINDS = listOf("cube", "pyramid", "sphere", "torus", "tube")

    /**
     * [taper] is 1 for a cylinder and 0 for a cone; on a torus it drives the
     * thickness of the ring instead, since a torus has no ends to taper.
     */
    fun create(kind: String, segments: Int = 24, taper: Double = 1.0): Guide {
        val seg = max(3, segments)
        val tp = taper
        val b = MeshBuilder()
        when (kind) {
            "cube" -> box(b, 2.0, 2.0, 2.0)
            // radius 1.5 at the base, coming to a POINT: a four-sided cone
            "pyramid" -> cone(b, 0.0, 1.5, 2.4, 4)
            "sphere" -> sphere(b, 1.4, seg, max(3, seg shr 1))
            /* Feather offers a torus among its readymades, and it is the one
               shape here you cannot get by bending a swept guide into a ring:
               the tube of a torus closes on itself in BOTH directions. */
            "torus" -> torus(b, 1.4, 0.42 * clamp(tp, 0.15, 1.0), max(6, seg shr 1), seg)
            else -> cone(b, 1.2 * tp, 1.2, 2.6, seg)
        }
        val g = Guide(Guide.freshId(), GuideKind.PRIMITIVE)
        g.primitiveKind = kind
        g.primitiveSegments = seg
        g.primitiveTaper = tp
        g.surface = b.build()
        return g
    }

    // ---- the shapes ------------------------------------------------------

    private fun box(b: MeshBuilder, w: Double, h: Double, d: Double) {
        val x = w / 2; val y = h / 2; val z = d / 2
        // one quad per face, with its own normal — a shared-vertex cube would
        // average the normals at the corners and light it like a sphere
        face(b, Vec3(-x, -y, z), Vec3(x, -y, z), Vec3(x, y, z), Vec3(-x, y, z), Vec3(0.0, 0.0, 1.0))
        face(b, Vec3(x, -y, -z), Vec3(-x, -y, -z), Vec3(-x, y, -z), Vec3(x, y, -z), Vec3(0.0, 0.0, -1.0))
        face(b, Vec3(x, -y, z), Vec3(x, -y, -z), Vec3(x, y, -z), Vec3(x, y, z), Vec3(1.0, 0.0, 0.0))
        face(b, Vec3(-x, -y, -z), Vec3(-x, -y, z), Vec3(-x, y, z), Vec3(-x, y, -z), Vec3(-1.0, 0.0, 0.0))
        face(b, Vec3(-x, y, z), Vec3(x, y, z), Vec3(x, y, -z), Vec3(-x, y, -z), Vec3(0.0, 1.0, 0.0))
        face(b, Vec3(-x, -y, -z), Vec3(x, -y, -z), Vec3(x, -y, z), Vec3(-x, -y, z), Vec3(0.0, -1.0, 0.0))
    }

    /** A cone, a cylinder or a pyramid, depending on the radii and the sides. */
    private fun cone(b: MeshBuilder, rTop: Double, rBottom: Double, h: Double, sides: Int) {
        val y = h / 2
        for (i in 0 until sides) {
            val a0 = i.toDouble() / sides * 2 * PI
            val a1 = (i + 1).toDouble() / sides * 2 * PI
            val t0 = Vec3(cos(a0) * rTop, y, sin(a0) * rTop)
            val t1 = Vec3(cos(a1) * rTop, y, sin(a1) * rTop)
            val b0 = Vec3(cos(a0) * rBottom, -y, sin(a0) * rBottom)
            val b1 = Vec3(cos(a1) * rBottom, -y, sin(a1) * rBottom)
            // the side normal leans by the slope, so a cone is not lit as a tube
            val n0 = sideNormal(a0, rTop, rBottom, h)
            val n1 = sideNormal(a1, rTop, rBottom, h)
            if (rTop > Vec3.EPS) {
                b.tri(b0, b1, t1, n0, n1, n1)
                b.tri(b0, t1, t0, n0, n1, n0)
            } else {
                // a true point at the top: one triangle, no degenerate quad
                b.tri(b0, b1, Vec3(0.0, y, 0.0), n0, n1, n0)
            }
        }
        if (rTop > Vec3.EPS) fan(b, rTop, y, sides, Vec3(0.0, 1.0, 0.0), true)
        fan(b, rBottom, -y, sides, Vec3(0.0, -1.0, 0.0), false)
    }

    private fun sideNormal(a: Double, rTop: Double, rBottom: Double, h: Double): Vec3 {
        val slope = (rBottom - rTop) / h
        return Vec3(cos(a), slope, sin(a)).normalize()
    }

    private fun fan(b: MeshBuilder, r: Double, y: Double, sides: Int, n: Vec3, up: Boolean) {
        if (r <= Vec3.EPS) return
        val c = Vec3(0.0, y, 0.0)
        for (i in 0 until sides) {
            val a0 = i.toDouble() / sides * 2 * PI
            val a1 = (i + 1).toDouble() / sides * 2 * PI
            val p0 = Vec3(cos(a0) * r, y, sin(a0) * r)
            val p1 = Vec3(cos(a1) * r, y, sin(a1) * r)
            if (up) b.tri(c, p0, p1, n, n, n) else b.tri(c, p1, p0, n, n, n)
        }
    }

    private fun sphere(b: MeshBuilder, r: Double, widthSeg: Int, heightSeg: Int) {
        for (j in 0 until heightSeg) {
            val v0 = j.toDouble() / heightSeg
            val v1 = (j + 1).toDouble() / heightSeg
            for (i in 0 until widthSeg) {
                val u0 = i.toDouble() / widthSeg
                val u1 = (i + 1).toDouble() / widthSeg
                val a = spherePoint(r, u0, v0); val d = spherePoint(r, u1, v0)
                val c = spherePoint(r, u0, v1); val e = spherePoint(r, u1, v1)
                // the normal of a sphere at a point IS that point, normalised
                if (j != 0) b.tri(a, c, d, unit(a), unit(c), unit(d))
                if (j != heightSeg - 1) b.tri(d, c, e, unit(d), unit(c), unit(e))
            }
        }
    }

    private fun spherePoint(r: Double, u: Double, v: Double): Vec3 {
        val theta = u * 2 * PI
        val phi = v * PI
        return Vec3(-r * cos(theta) * sin(phi), r * cos(phi), r * sin(theta) * sin(phi))
    }

    /** Lying flat, like the grid, rather than standing on edge. */
    private fun torus(b: MeshBuilder, radius: Double, tube: Double, radialSeg: Int, tubularSeg: Int) {
        for (j in 0 until radialSeg) {
            for (i in 0 until tubularSeg) {
                val a = torusPoint(radius, tube, i, j, tubularSeg, radialSeg)
                val d = torusPoint(radius, tube, i + 1, j, tubularSeg, radialSeg)
                val c = torusPoint(radius, tube, i, j + 1, tubularSeg, radialSeg)
                val e = torusPoint(radius, tube, i + 1, j + 1, tubularSeg, radialSeg)
                val na = torusNormal(radius, a); val nd = torusNormal(radius, d)
                val nc = torusNormal(radius, c); val ne = torusNormal(radius, e)
                b.tri(a, c, d, na, nc, nd)
                b.tri(d, c, e, nd, nc, ne)
            }
        }
    }

    private fun torusPoint(
        radius: Double, tube: Double, i: Int, j: Int, tubularSeg: Int, radialSeg: Int,
    ): Vec3 {
        val u = i.toDouble() / tubularSeg * 2 * PI
        val v = j.toDouble() / radialSeg * 2 * PI
        // built in XY then laid flat, which is the rotateX(PI/2) the web build does
        val x = (radius + tube * cos(v)) * cos(u)
        val z = (radius + tube * cos(v)) * sin(u)
        val y = tube * sin(v)
        return Vec3(x, y, z)
    }

    private fun torusNormal(radius: Double, p: Vec3): Vec3 {
        // the centre of the tube ring nearest p, then outwards from it
        val len = kotlin.math.sqrt(p.x * p.x + p.z * p.z)
        if (len < Vec3.EPS) return Vec3(0.0, 1.0, 0.0)
        val cx = p.x / len * radius
        val cz = p.z / len * radius
        return Vec3(p.x - cx, p.y, p.z - cz).normalize()
    }

    private fun unit(p: Vec3): Vec3 = p.copy().normalize()

    private fun face(b: MeshBuilder, a: Vec3, c: Vec3, d: Vec3, e: Vec3, n: Vec3) {
        b.tri(a, c, d, n, n, n)
        b.tri(a, d, e, n, n, n)
    }

    /**
     * Collects loose triangles into the flat arrays a [GuideSurface] wants.
     *
     * No vertex sharing. These meshes are a few thousand triangles at most and
     * are never edited, so welding them would buy nothing and would blur the
     * hard edges a cube is supposed to have.
     */
    private class MeshBuilder {
        private val pos = ArrayList<Float>()
        private val nor = ArrayList<Float>()

        fun tri(a: Vec3, b: Vec3, c: Vec3, na: Vec3, nb: Vec3, nc: Vec3) {
            push(a, na); push(b, nb); push(c, nc)
        }

        private fun push(p: Vec3, n: Vec3) {
            pos.add(p.x.toFloat()); pos.add(p.y.toFloat()); pos.add(p.z.toFloat())
            nor.add(n.x.toFloat()); nor.add(n.y.toFloat()); nor.add(n.z.toFloat())
        }

        fun build(): GuideSurface {
            val count = pos.size / 3
            val idx = IntArray(count) { it }
            return GuideSurface(
                pos.toFloatArray(), nor.toFloatArray(),
                FloatArray(count * 2), idx, nu = 0, nv = 0,
                lu = 0.0, lv = 0.0,
            )
        }
    }

    /** Rounded to an int the same way the web build rounds its segment count. */
    fun segmentsOf(v: Double): Int = max(3, v.roundToInt())
}
