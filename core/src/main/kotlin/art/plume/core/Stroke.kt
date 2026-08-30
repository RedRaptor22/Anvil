package art.plume.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One brush's cross-section, ported from the `P.BRUSH` table in `js/strokes.kt`.
 *
 * Every brush is built from the same handful of parameters rather than being a
 * special case:
 *
 *  - [flat]   ellipse ratio — 1 round, towards 0 a blade
 *  - [square] 0 an ellipse, 1 a hard rectangular nib
 *  - [taper]  length of the taper at each end, in nib radii (0 = none)
 *  - [tip]    radius the taper narrows to, as a fraction of the nib
 *  - [wide]   width multiplier over the base radius
 *  - [rise]   the section stands ON the surface rather than straddling it
 *  - [glow]   additive material
 */
data class Brush(
    val name: String,
    val flat: Double,
    val square: Double,
    val taper: Double,
    val tip: Double,
    val caps: Boolean,
    val wide: Double,
    val glow: Boolean = false,
    val rise: Boolean = false,
    val paint: Boolean = false,
    val grit: Boolean = false,
    val halfWidthMM: Double? = null,
    val thickMM: Double? = null,
)

object Brushes {
    val table: Map<String, Brush> = listOf(
        Brush("pen", 1.00, 0.00, 0.0, 0.00, true, 1.00),
        Brush("sketch", 0.34, 0.00, 0.0, 0.00, true, 1.15, grit = true, paint = true),
        Brush("taper", 1.00, 0.00, 9.0, 0.04, true, 1.00),
        Brush("rectangle", 1.00, 1.00, 0.0, 0.00, true, 1.60, paint = true, halfWidthMM = 11.2),
        Brush("cube", 1.00, 1.00, 0.0, 0.00, true, 1.00, rise = true, paint = true),
        Brush("flat", 0.04, 1.00, 0.0, 0.00, true, 3.40, paint = true),
        Brush("wide", 0.04, 1.00, 0.0, 0.00, true, 3.40, paint = true, thickMM = 2.0),
        Brush("glow", 1.00, 0.00, 6.0, 0.10, true, 1.30, glow = true),
    ).associateBy { it.name }

    val aliases = mapOf(
        "square" to "rectangle", "marker" to "flat", "chisel" to "flat",
        "round" to "pen", "pencil" to "sketch", "ink" to "pen", "ribbon" to "wide",
    )

    fun resolve(name: String?): Brush =
        table[name] ?: table[aliases[name]] ?: table.getValue("pen")
}

/** A single sample along a stroke. */
class StrokePoint(
    val p: Vec3,
    var tan: Vec3? = null,
    var ref: Vec3? = null,
    var roll: Double = 0.0,
    var pressure: Double = 0.5,
    /** the guide surface normal here, when the stroke was painted onto one */
    var nrm: Vec3? = null,
)

/** Colour as linear-ish RGB in 0..1, plus alpha. */
data class Rgba(val r: Double, val g: Double, val b: Double, val a: Double = 1.0)

class Stroke(
    var brush: String = "pen",
    var color: Rgba = Rgba(0.1, 0.1, 0.13),
    var baseRadius: Double = 7.0 * MM,
    var opacity: Double = 1.0,
    /** the guide this was painted onto, so tools can put points back on it */
    var guideId: Int? = null,
) {
    val pts = ArrayList<StrokePoint>()
    val cfg: Brush get() = Brushes.resolve(brush)
}

/** Triangle soup ready for a GL buffer. */
class MeshData(
    val positions: FloatArray,
    val normals: FloatArray,
    val colors: FloatArray,
    val indices: IntArray,
) {
    val vertexCount get() = positions.size / 3
    val triangleCount get() = indices.size / 3
}

object StrokeGeometry {

    private const val SEG_MIN = 4
    private const val SEG_MAX = 48
    private const val SEG_ERR_MM = 0.35

    /**
     * How many facets a cross-section needs.
     *
     * Only the ROUND part needs them. The sagitta rule below is about
     * approximating a circle, and a section is only a circle at square 0; at
     * square 1 it is a rectangle, whose sides are exact at any size and whose
     * corners are corners. Feeding it the full radius asked for 92 segments on
     * a 300mm brush, of which 40 were extra vertices strung along four flat
     * sides, on every ring of every stroke.
     */
    fun segmentsFor(stroke: Stroke): Int {
        val cfg = stroke.cfg
        val rmm = max(0.25, stroke.baseRadius * cfg.wide / MM)
        val curved = rmm * (1.0 - clamp(cfg.square, 0.0, 1.0))
        val n = if (curved > SEG_ERR_MM) ceil(PI / sqrt(2 * SEG_ERR_MM / curved)).toInt() else 0
        val step = if (cfg.square > 0.5) 8 else 4
        return clamp(ceil(n / step.toDouble()).toInt() * step, SEG_MIN, SEG_MAX)
    }

    /**
     * A point on the unit cross-section at [ang], blended between a circle and
     * a Chebyshev square by [square].
     */
    fun sectionPoint(ang: Double, square: Double): Pair<Double, Double> {
        val c = cos(ang); val s = sin(ang)
        if (square <= 0) return c to s
        val m = max(abs(c), abs(s))
        if (m < Vec3.EPS) return c to s
        return (c * (1 - square) + (c / m) * square) to (s * (1 - square) + (s / m) * square)
    }

    fun halfWidth(stroke: Stroke, radius: Double): Double {
        val cfg = stroke.cfg
        cfg.halfWidthMM?.let { return it * MM }
        return radius * cfg.wide
    }

    fun halfThick(stroke: Stroke, radius: Double): Double {
        val cfg = stroke.cfg
        cfg.thickMM?.let { return it * MM * 0.5 }
        return radius * cfg.wide * cfg.flat
    }

    /** Taper factor at arc position [s] of [total], in nib radii from each end. */
    private fun taperAt(stroke: Stroke, s: Double, total: Double): Double {
        val cfg = stroke.cfg
        if (cfg.taper <= 0.0) return 1.0
        val len = cfg.taper * stroke.baseRadius
        if (len <= Vec3.EPS) return 1.0
        val d = kotlin.math.min(s, total - s)
        if (d >= len) return 1.0
        val u = clamp(d / len, 0.0, 1.0)
        return cfg.tip + (1.0 - cfg.tip) * u
    }

    /**
     * Build the tube for a stroke.
     *
     * A stroke whose last point is its first is welded shut: its final band
     * wraps onto ring 0 and it grows no caps. Built as an open tube instead it
     * gets two cap discs stacked on the one point, tilted apart by a whole
     * angular step, and the wedge between them is the slit that shows at the
     * top of every snapped circle.
     */
    fun build(stroke: Stroke): MeshData? {
        val n = stroke.pts.size
        if (n < 2) return null

        val closed = Frames.loopsClosed(stroke.pts.map { it.p })
        val frames = Frames.transportFrames(stroke.pts.map { it.p }, null, closed)
        val t = frames.t
        val r = frames.r

        val seg = segmentsFor(stroke)
        val cfg = stroke.cfg
        val rings = if (closed) n - 1 else n
        val caps = cfg.caps && !closed

        val arc = Frames.arcLengths(stroke.pts.map { it.p })
        val total = arc[n - 1]

        val vCount = 2 + rings * seg
        val pos = FloatArray(vCount * 3)
        val nor = FloatArray(vCount * 3)
        val col = FloatArray(vCount * 4)

        val u = Vec3(); val v = Vec3(); val b = Vec3()
        val world = Vec3(); val normal = Vec3()

        for (i in 0 until rings) {
            val pt = stroke.pts[i]
            val k = taperAt(stroke, arc[i], total)
            val rx = max(halfWidth(stroke, stroke.baseRadius * k), 1e-5)
            val ry = max(halfThick(stroke, stroke.baseRadius * k), 1e-5)

            // roll the reference frame, then build an orthonormal section basis
            val ca = cos(pt.roll); val sa = sin(pt.roll)
            b.set(t[i] cross r[i])
            u.set(r[i]) ; u.x = r[i].x * ca + b.x * sa
            u.y = r[i].y * ca + b.y * sa
            u.z = r[i].z * ca + b.z * sa
            v.set(t[i] cross u)

            /*
             * A RISEN SECTION STANDS ON THE SURFACE rather than straddling it:
             * shifting the centre by -ry along v puts the section's near face
             * on the stroke and its far face one full height out along the
             * normal, which is what makes the cube brush an extrusion FROM the
             * surface instead of a rod half sunk into it.
             */
            val riseShift = if (cfg.rise) -ry else 0.0

            for (j in 0 until seg) {
                val ang = j.toDouble() / seg * 2.0 * PI
                val (sx, sy) = sectionPoint(ang, cfg.square)
                world.set(pt.p)
                world.addScaled(u, sx * rx)
                world.addScaled(v, sy * ry + riseShift)

                normal.set(0.0, 0.0, 0.0)
                normal.addScaled(u, sx / rx)
                normal.addScaled(v, sy / ry)
                if (normal.lengthSq() < Vec3.EPS) normal.set(u) else normal.normalize()

                val o = (2 + i * seg + j)
                pos[o * 3] = world.x.toFloat(); pos[o * 3 + 1] = world.y.toFloat(); pos[o * 3 + 2] = world.z.toFloat()
                nor[o * 3] = normal.x.toFloat(); nor[o * 3 + 1] = normal.y.toFloat(); nor[o * 3 + 2] = normal.z.toFloat()
                col[o * 4] = stroke.color.r.toFloat(); col[o * 4 + 1] = stroke.color.g.toFloat()
                col[o * 4 + 2] = stroke.color.b.toFloat(); col[o * 4 + 3] = stroke.opacity.toFloat()
            }
        }

        if (caps) {
            writeCapCentre(stroke, 0, t[0], -1.0, pos, nor, col)
            writeCapCentre(stroke, rings - 1, t[rings - 1], 1.0, pos, nor, col)
        }

        val bands = if (closed) rings else rings - 1
        val idx = IntArray(bands * seg * 6 + if (caps) seg * 6 else 0)
        var at = 0
        if (caps) at = startFan(idx, at, seg)
        for (i in 0 until rings - 1) at = band(idx, at, i, i + 1, seg)
        if (closed) at = band(idx, at, rings - 1, 0, seg)
        if (caps) at = endFan(idx, at, rings - 1, seg)

        return MeshData(pos, nor, col, idx)
    }

    private fun writeCapCentre(
        stroke: Stroke, i: Int, t: Vec3, sign: Double,
        pos: FloatArray, nor: FloatArray, col: FloatArray,
    ) {
        val slot = if (sign < 0) 0 else 1
        val p = stroke.pts[i].p
        pos[slot * 3] = p.x.toFloat(); pos[slot * 3 + 1] = p.y.toFloat(); pos[slot * 3 + 2] = p.z.toFloat()
        nor[slot * 3] = (t.x * sign).toFloat()
        nor[slot * 3 + 1] = (t.y * sign).toFloat()
        nor[slot * 3 + 2] = (t.z * sign).toFloat()
        col[slot * 4] = stroke.color.r.toFloat(); col[slot * 4 + 1] = stroke.color.g.toFloat()
        col[slot * 4 + 2] = stroke.color.b.toFloat(); col[slot * 4 + 3] = stroke.opacity.toFloat()
    }

    /** One band of quads between two rings; a closed loop wraps its last onto 0. */
    private fun band(idx: IntArray, at0: Int, i: Int, j: Int, seg: Int): Int {
        var at = at0
        for (k in 0 until seg) {
            val a = 2 + i * seg + k
            val b = 2 + i * seg + (k + 1) % seg
            val c = 2 + j * seg + k
            val d = 2 + j * seg + (k + 1) % seg
            idx[at++] = a; idx[at++] = b; idx[at++] = c
            idx[at++] = b; idx[at++] = d; idx[at++] = c
        }
        return at
    }

    private fun startFan(idx: IntArray, at0: Int, seg: Int): Int {
        var at = at0
        for (k in 0 until seg) { idx[at++] = 0; idx[at++] = 2 + (k + 1) % seg; idx[at++] = 2 + k }
        return at
    }

    private fun endFan(idx: IntArray, at0: Int, lastRing: Int, seg: Int): Int {
        var at = at0
        val base = 2 + lastRing * seg
        for (k in 0 until seg) { idx[at++] = 1; idx[at++] = base + k; idx[at++] = base + (k + 1) % seg }
        return at
    }
}
