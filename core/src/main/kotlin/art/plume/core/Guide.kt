package art.plume.core

import kotlin.math.min

/** A point in a surface's own (u, v) coordinates, both in world units. */
data class UV(val u: Double, val v: Double)

enum class GuideKind { DRAW, FLAT, PRIMITIVE, LOFT, MODEL, IMAGE }

/**
 * A guide surface as a built mesh, plus the two things that make it drawable
 * rather than merely visible.
 *
 * **`uv` carries ARC LENGTH, not a 0..1 texture coordinate.** That is what makes
 * the surface addressable in millimetres: Fill lays rows of paint a nib apart,
 * which means putting them at chosen DISTANCES across the surface, and those
 * distances do not fall on grid lines. It also keeps the section lines a
 * constant physical spacing apart however the surface is stretched.
 *
 * A swept guide is an `nu x nv` grid and can be indexed directly. A flat guide
 * is triangulated to the outline it was drawn as — the edge is the curve you
 * drew, not a staircase of clipped cells — so it has no grid to index and
 * states its own extent in [lu]/[lv] and its real edge in [outline].
 */
class GuideSurface(
    val positions: FloatArray,
    val normals: FloatArray,
    /** two per vertex: arc length along u, then along v */
    val uv: FloatArray,
    val indices: IntArray,
    val nu: Int = 0,
    val nv: Int = 0,
    lu: Double = Double.NaN,
    lv: Double = Double.NaN,
    val outline: List<UV>? = null,
) {
    val vertexCount: Int get() = positions.size / 3

    /** How far the surface runs along each axis, in world units. */
    val lu: Double = if (lu.isNaN() && nu > 1) uv[(nu - 1) * 2].toDouble() else lu
    val lv: Double = if (lv.isNaN() && nv > 1 && nu > 0) uv[((nv - 1) * nu) * 2 + 1].toDouble() else lv

    /** True when this is a grid that [sampleSurface] can index by (u, v). */
    val hasGrid: Boolean get() = nu >= 2 && nv >= 2

    /** Built on demand — the nearest-point and ray queries share one index. */
    val mesh: SurfaceMesh by lazy { SurfaceMesh(positions, indices) }
}

/**
 * A guide as a sweep: `surface(u,v) = path[v] + local[u] · frame(v)`.
 *
 * This is the Feather premise in one line — a profile carried along a path by
 * rotation-minimising frames. [local] is written in the ANCHOR frame
 * (`basisR`, `basisT × basisR`, `basisT`), and the frames are transported from
 * index 0 with that anchor frame carried onto the path, so a row can be laid
 * down with no re-derivation.
 */
class Sweep(
    val local: MutableList<Vec3>,
    val anchor: Vec3,
    val anchorIndex: Int,
    val path: MutableList<Vec3>,
    val basisR: Vec3,
    val basisT: Vec3,
    var depth: Double,
) {
    /**
     * The reference vector to start transport from.
     *
     * THE PROFILE KEEPS THE SIDE IT WAS DRAWN ON, which is the whole of it.
     *
     * A frame perpendicular to the new tangent has one degree of freedom left,
     * and the question is which turn of it is the profile's own. Two earlier
     * answers each got half of it:
     *
     * Carrying [basisR] round by the shortest rotation from [basisT] is
     * continuous, and wrong at the far end — a path leaving back towards the
     * camera is close to the reverse of that axis, so the shortest rotation is
     * close to a half turn and rolls the section over.
     *
     * PROJECTING [basisR] onto the plane fixed that and broke a different
     * case. Where the path leaves along the profile's own right, basisR and
     * the tangent are nearly parallel and the projection is not a direction at
     * all — it is the numerical remainder of two vectors that cancelled, a few
     * thousandths long and pointing wherever the rounding went. Normalising it
     * yields a full-sized axis built out of noise. The guard for this was
     * `lengthSq > 1e-12`, which such a remainder passes by eight orders of
     * magnitude. On a pot bent into a ring the residue came out as (0, 0,
     * -0.011), the seed landed on -Z instead of +Z, and the second axis
     * inverted with it: the profile hung upwards from the rim, so the pot
     * built itself upside down with the orange line underneath.
     *
     * So neither axis is trusted alone. The frame taken is the one closest to
     * the WHOLE of the original — the turn that maximises
     * `r · basisR + s · s0` over every frame perpendicular to the tangent,
     * where s0 is the second axis the profile was drawn against. It has a
     * closed form: in any orthonormal basis (e1, e2) of the free plane the
     * best angle is atan2(B, A) for A and B below, and because it is an
     * optimum it does not care which basis it was computed in — including one
     * built from that same noisy projection.
     *
     * At creation, where the path runs along [basisT], it returns [basisR]
     * exactly. Where the path leaves along the profile's right it returns the
     * axis that keeps the section upright rather than the rounding error. Only
     * a sweep that leaves BACKWARDS is genuinely ambiguous — every frame is
     * then equally far from the original — and there the old projection is as
     * good an answer as any, so it is what is used.
     */
    fun seedFor(t0: Vec3, out: Vec3 = Vec3()): Vec3 {
        /* the axis the profile's own "up" was measured against */
        val s0 = basisT cross basisR

        /* any orthonormal basis of the plane the tangent leaves free; which
           one is immaterial, since what follows is an optimum over the plane */
        val e1 = Vec3().set(basisR).addScaled(t0, -(basisR dot t0))
        if (e1.lengthSq() < 1e-12) e1.set(s0).addScaled(t0, -(s0 dot t0))
        if (e1.lengthSq() < 1e-12) Vec3.perpTo(t0, e1) else e1.normalize()
        val e2 = t0 cross e1

        val a = (e1 dot basisR) + (e2 dot s0)
        val b = (e2 dot basisR) - (e1 dot s0)
        val n = kotlin.math.sqrt(a * a + b * b)
        if (n > 1e-9) {
            return out.set(
                (e1.x * a + e2.x * b) / n,
                (e1.y * a + e2.y * b) / n,
                (e1.z * a + e2.z * b) / n,
            )
        }
        /*
         * a * a + b * b is zero only for a path leaving BACKWARDS, where every
         * frame is equally far from the original and the rotation is a half
         * turn about an axis nothing picks. Turning about the section's own up
         * is the choice that keeps it: the surface comes back towards you the
         * same way up, and the two directions either side of the reversal
         * agree with it instead of jumping.
         */
        return out.set(s0 cross t0).normalize()
    }
}

/** A flat guide: the shape you drew, in the plane you drew it on. */
class PlaneData(
    val origin: Vec3,
    val right: Vec3,
    val up: Vec3,
    val normal: Vec3,
    val lu: Double,
    val lv: Double,
    val outline: List<UV>,
)

/**
 * One guide surface, in whichever of the three forms it was made.
 *
 * A guide is scaffolding: you draw it, you draw ON it, and then you put it
 * away. Everything needed to rebuild the mesh is kept — a sweep keeps its
 * profile and path, a flat guide keeps its plane and outline — so a saved and
 * reloaded guide is identical to a drawn one rather than a frozen copy of its
 * triangles.
 */
class Guide(
    val id: Int,
    val kind: GuideKind,
    var name: String = defaultName(kind),
) {
    /**
     * This guide's own colour, or null to take the one the page decides.
     *
     * Guide colour has always been derived from the background — a deep blue
     * on paper, a pale one on a dark page — which is right as a DEFAULT and
     * wrong as the only option: two guides crossing each other are two sheets
     * of the same blue, and telling them apart is exactly what you need when
     * one is the ground and the other is the wall you are drawing on. Per
     * guide rather than one setting for all of them, for the same reason.
     */
    var tint: Rgba? = null

    /**
     * FACT (A.2/A.10): opacity is adjustable down to 0% but never fully opaque
     * — EXCEPT for an imported image, which is reference art rather than
     * scaffolding and is allowed to be solid.
     *
     * The clamp lives on the property rather than in a setter method so there
     * is no route into it that skips the limit.
     */
    var opacity: Double = Tune.GUIDE_OPACITY_INIT
        set(value) { field = clamp(value, 0.0, maxOpacity) }

    /** How opaque this guide is allowed to get. */
    val maxOpacity: Double
        get() = if (kind == GuideKind.IMAGE) 1.0 else Tune.GUIDE_OPACITY_MAX

    var sweep: Sweep? = null
    var plane: PlaneData? = null
    var surface: GuideSurface? = null

    /** The row the orange line starts on; Bend hinges on its first point. */
    var anchorRow: List<Vec3>? = null

    /**
     * THE ORANGE LINE ITSELF — the rail the profile's FIRST point traces as
     * the guide is swept.
     *
     * FACT (A.6): "The bending starts from the orange line, which is the
     * starting point of the 3D Guide." The documentation draws it running the
     * whole length of the surface from the dot it labels "Starting point",
     * which is where the pen went down to draw the profile — so it is a rail
     * along the sweep, not a section across it, and it lies on one edge of
     * the surface rather than through the middle.
     *
     * Swept guides only. A loft or a primitive has no drawn profile to have a
     * first point, and [GuideEditing.bendMesh] already says as much: it has no
     * such mark to aim at, so it leaves the path where it was drawn.
     */
    var startLine: List<Vec3>? = null

    var selected = false
    var visible = true

    /**
     * FACT (A.4): an imported image refuses strokes past its edge rather than
     * clamping them back onto itself — the one place the clamp-to-nearest
     * fallback is explicitly wrong.
     */
    var noClamp = false

    /**
     * The mesh as it was before any bend, so repeated bends re-deform the
     * original rather than compounding into mush — which matches how a swept
     * bend replaces its path rather than bending the bent thing again.
     */
    var originalPositions: FloatArray? = null
    var originalNormals: FloatArray? = null

    /** The stroke this guide was last bent along, kept so it can be saved. */
    var bendPath: List<Vec3>? = null

    /** A loft keeps its input curves, so it can be re-lofted or reloaded. */
    var loftCurves: List<List<Vec3>>? = null
    var loftTension = 1.0

    var primitiveKind: String? = null
    var primitiveSegments = 24
    var primitiveTaper = 1.0

    companion object {
        private var nextId = 0
        fun freshId(): Int = ++nextId

        fun defaultName(kind: GuideKind): String = when (kind) {
            GuideKind.DRAW -> "Surface"
            GuideKind.LOFT -> "Loft"
            GuideKind.PRIMITIVE -> "Shape"
            GuideKind.FLAT -> "Shape"
            GuideKind.MODEL -> "Model"
            GuideKind.IMAGE -> "Image"
        }
    }
}

/**
 * Turning a grid of rows into a mesh.
 *
 * Ported from `buildSurfaceGeometry` in `js/guides.js`. The arc lengths are
 * measured down the MIDDLE row and the MIDDLE column rather than an edge: on a
 * surface that fans out, an edge is the least representative line on it, and
 * parameterising by one would put the section lines noticeably closer together
 * at the narrow end than the wide one.
 */
object SurfaceGrid {

    fun build(rows: List<List<Vec3>>): GuideSurface? {
        val nv = rows.size
        if (nv < 2) return null
        val nu = rows[0].size
        if (nu < 2) return null

        val count = nv * nu
        val pos = FloatArray(count * 3)
        val nor = FloatArray(count * 3)
        val uv = FloatArray(count * 2)

        val uLen = DoubleArray(nu)
        val vLen = DoubleArray(nv)
        val mid = rows[nv / 2]
        for (i in 1 until nu) uLen[i] = uLen[i - 1] + mid[i].distanceTo(mid[i - 1])
        val midU = nu / 2
        for (j in 1 until nv) vLen[j] = vLen[j - 1] + rows[j][midU].distanceTo(rows[j - 1][midU])

        val du = Vec3(); val dv = Vec3(); val nn = Vec3()
        for (j in 0 until nv) {
            for (i in 0 until nu) {
                val o = j * nu + i
                val p = rows[j][i]
                pos[o * 3] = p.x.toFloat(); pos[o * 3 + 1] = p.y.toFloat(); pos[o * 3 + 2] = p.z.toFloat()

                du.set(rows[j][min(nu - 1, i + 1)] - rows[j][if (i > 0) i - 1 else 0])
                dv.set(rows[min(nv - 1, j + 1)][i] - rows[if (j > 0) j - 1 else 0][i])
                nn.set(du cross dv)
                if (nn.lengthSq() < Vec3.EPS) nn.set(0.0, 0.0, 1.0) else nn.normalize()
                nor[o * 3] = nn.x.toFloat(); nor[o * 3 + 1] = nn.y.toFloat(); nor[o * 3 + 2] = nn.z.toFloat()

                uv[o * 2] = uLen[i].toFloat(); uv[o * 2 + 1] = vLen[j].toFloat()
            }
        }

        val idx = IntArray((nv - 1) * (nu - 1) * 6)
        var at = 0
        for (j in 0 until nv - 1) {
            for (i in 0 until nu - 1) {
                val a = j * nu + i; val b = a + 1
                val c = (j + 1) * nu + i; val d = c + 1
                idx[at++] = a; idx[at++] = c; idx[at++] = b
                idx[at++] = b; idx[at++] = c; idx[at++] = d
            }
        }
        return GuideSurface(pos, nor, uv, idx, nu, nv)
    }

    /**
     * Which cell an arc length falls in, and how far across it.
     *
     * A binary search rather than a scan because Fill asks this for every row
     * of paint across a surface, and the rows are not in order.
     */
    fun spanAt(get: (Int) -> Double, n: Int, s: Double): Pair<Int, Double> {
        if (!(s > get(0))) return 0 to 0.0
        if (s >= get(n - 1)) return (n - 2) to 1.0
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val m = (lo + hi) ushr 1
            if (get(m) <= s) lo = m else hi = m
        }
        val a = get(lo); val b = get(lo + 1)
        return lo to (if ((b - a) > Vec3.EPS) (s - a) / (b - a) else 0.0)
    }

    /** Even-odd, which is what a self-crossing freehand loop deserves. */
    fun insideOutline(poly: List<UV>, u: Double, v: Double): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.v > v) != (b.v > v) &&
                u < (b.u - a.u) * (v - a.v) / (b.v - a.v) + a.u
            ) inside = !inside
            j = i
        }
        return inside
    }
}
