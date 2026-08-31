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
    /**
     * How much of the chosen opacity ONE pass deposits.
     *
     * A brush that builds up cannot lay full strength at once, or a second
     * pass over the same ground would have nowhere to go — the rest arrives by
     * going over it again, which is how shading with a pencil actually works.
     */
    val tone: Double = 1.0,
    /** A brush that insists on a pressure target regardless of the setting. */
    val pressure: String? = null,
)

object Brushes {
    val table: Map<String, Brush> = listOf(
        Brush("pen", 1.00, 0.00, 0.0, 0.00, true, 1.00),
        Brush(
            "sketch", 0.34, 0.00, 0.0, 0.00, true, 1.15, grit = true, paint = true,
            tone = 0.5, pressure = "opacity",
        ),
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
    /**
     * What pressure drives: "size", "opacity", "both", "color", or "none".
     *
     * FACT (C.3): the pressure toggle lives in the Brush Panel. It is stored
     * on the STROKE rather than read from the tool at draw time, because a
     * curve drawn with pressure on size has to keep looking like that after
     * the setting is changed for the next one.
     */
    var pressureTarget: String = "size",
    /** the guide this was painted onto, so tools can put points back on it */
    var guideId: Int? = null,
) {
    val pts = ArrayList<StrokePoint>()
    val cfg: Brush get() = Brushes.resolve(brush)

    /**
     * Stable identity, so a document can name a curve across a save, an undo
     * or an erase that split it in two. Object identity alone will not do:
     * erasing replaces a stroke with fresh ones built from its surviving runs.
     */
    val id: Int = nextId()

    /** Which group this belongs to, if any. Groups hide and show together. */
    var group: Int? = null

    var selected = false

    /** The seed reference direction, kept so a rebuild reproduces the frame. */
    var seedRef: Vec3? = null

    /** A copy carrying [points] instead of this stroke's own. */
    fun withPoints(points: List<StrokePoint>): Stroke {
        val out = Stroke(brush, color, baseRadius, opacity, pressureTarget, guideId)
        out.group = group
        out.seedRef = seedRef?.copy()
        for (q in points) {
            out.pts.add(
                StrokePoint(
                    q.p.copy(),
                    tan = q.tan?.copy(),
                    ref = q.ref?.copy(),
                    roll = q.roll,
                    pressure = q.pressure,
                    nrm = q.nrm?.copy(),
                ),
            )
        }
        return out
    }

    fun copyStroke(): Stroke = withPoints(pts)

    private companion object {
        var counter = 0
        fun nextId(): Int = ++counter
    }
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
    /**
     * What one sample of a stroke actually deposits: how wide, how opaque, and
     * how far the ink is lifted towards white.
     *
     * Ported from `shadeAt` in js/strokes.js. Pressure is clamped to 0.02 at
     * the bottom rather than 0: a sample with no pressure at all would be a
     * ring of zero radius, which is a pinch in the tube rather than a light
     * touch.
     *
     * The four targets are the four the brush panel offers, and each is a
     * different claim about what a harder press means. Size makes a wider
     * mark, opacity a darker one, both together, and colour a DARKER one by
     * lifting a light press towards the page — which is what a pencil does,
     * and the only one of the four that changes the colour rather than the
     * ink.
     */
    class Shade(val radius: Double, val alpha: Double, val lift: Double)

    fun shadeAt(stroke: Stroke, index: Int, arcS: Double, total: Double): Shade {
        val cfg = stroke.cfg
        val pr = clamp(stroke.pts[index].pressure, 0.02, 1.0)
        /* a brush may insist on a target: the sketch pencil is an opacity
           brush whatever the panel says, because a pencil that got WIDER
           under the hand would not read as a pencil */
        val mode = cfg.pressure ?: stroke.pressureTarget

        var rMul = 1.0
        var alpha = stroke.opacity
        var lift = 0.0
        if (mode == "size" || mode == "both") rMul *= 0.25 + 0.75 * pr
        if (mode == "opacity" || mode == "both") alpha = stroke.opacity * (0.18 + 0.82 * pr)
        if (mode == "color") lift = (1 - pr) * 0.55

        rMul *= taperAt(stroke, arcS, total)
        alpha *= cfg.tone
        return Shade(stroke.baseRadius * rMul, alpha, lift)
    }

    internal fun taperAt(stroke: Stroke, s: Double, total: Double): Double {
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

        val scratch = RingScratch()
        for (i in 0 until rings) {
            writeRing(stroke, i, i, t[i], r[i], arc[i], total, pos, nor, col, seg, scratch)
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

    /**
     * Scratch vectors for one ring write, owned by the caller.
     *
     * Held rather than allocated per ring because both callers write rings in a
     * loop — the batch build over a whole stroke, the live buffer over a tail —
     * and because the live path runs on every pointer sample. Caller-owned
     * rather than shared: the UI thread appends while the GL thread draws.
     */
    internal class RingScratch {
        val u = Vec3(); val v = Vec3(); val b = Vec3()
        val world = Vec3(); val normal = Vec3()
    }

    /**
     * Write one cross-section into the vertex arrays.
     *
     * Split out of [build] so that the incremental path in [LiveStroke] and the
     * batch path here cannot drift apart. They did not have to be one function
     * — the web build keeps two — but two copies of this arithmetic is exactly
     * the shape of bug that shows as "the preview looks right and the committed
     * stroke does not", and it is cheap to make impossible.
     *
     * [ptIndex] selects the sample; [ringSlot] selects where it lands in the
     * buffer. They are the same for every stroke we build today and are kept
     * separate because a closed loop's last ring is its first.
     */
    internal fun writeRing(
        stroke: Stroke, ptIndex: Int, ringSlot: Int,
        t: Vec3, r: Vec3, arcS: Double, total: Double,
        pos: FloatArray, nor: FloatArray, col: FloatArray, seg: Int,
        s: RingScratch,
    ) {
        val cfg = stroke.cfg
        val pt = stroke.pts[ptIndex]
        val sh = shadeAt(stroke, ptIndex, arcS, total)
        val rx = max(halfWidth(stroke, sh.radius), 1e-5)
        val ry = max(halfThick(stroke, sh.radius), 1e-5)
        /* colour mode lifts a light press towards the page rather than
           changing the ink, which is what a pencil does */
        val inkR = (stroke.color.r + (1.0 - stroke.color.r) * sh.lift).toFloat()
        val inkG = (stroke.color.g + (1.0 - stroke.color.g) * sh.lift).toFloat()
        val inkB = (stroke.color.b + (1.0 - stroke.color.b) * sh.lift).toFloat()
        val inkA = sh.alpha.toFloat()

        // roll the reference frame, then build an orthonormal section basis
        val ca = cos(pt.roll); val sa = sin(pt.roll)
        s.b.set(t cross r)
        s.u.set(
            r.x * ca + s.b.x * sa,
            r.y * ca + s.b.y * sa,
            r.z * ca + s.b.z * sa,
        )
        s.v.set(t cross s.u)

        /*
         * A RISEN SECTION STANDS ON THE SURFACE rather than straddling it:
         * shifting the centre by -ry along v puts the section's near face on the
         * stroke and its far face one full height out along the normal, which is
         * what makes the cube brush an extrusion FROM the surface instead of a
         * rod half sunk into it.
         */
        val riseShift = if (cfg.rise) -ry else 0.0

        for (j in 0 until seg) {
            val ang = j.toDouble() / seg * 2.0 * PI
            val (sx, sy) = sectionPoint(ang, cfg.square)
            s.world.set(pt.p)
            s.world.addScaled(s.u, sx * rx)
            s.world.addScaled(s.v, sy * ry + riseShift)

            s.normal.set(0.0, 0.0, 0.0)
            s.normal.addScaled(s.u, sx / rx)
            s.normal.addScaled(s.v, sy / ry)
            if (s.normal.lengthSq() < Vec3.EPS) s.normal.set(s.u) else s.normal.normalize()

            val o = (2 + ringSlot * seg + j)
            pos[o * 3] = s.world.x.toFloat(); pos[o * 3 + 1] = s.world.y.toFloat(); pos[o * 3 + 2] = s.world.z.toFloat()
            nor[o * 3] = s.normal.x.toFloat(); nor[o * 3 + 1] = s.normal.y.toFloat(); nor[o * 3 + 2] = s.normal.z.toFloat()
            col[o * 4] = inkR; col[o * 4 + 1] = inkG
            col[o * 4 + 2] = inkB; col[o * 4 + 3] = inkA
        }
    }

    internal fun writeCapCentre(
        stroke: Stroke, i: Int, t: Vec3, sign: Double,
        pos: FloatArray, nor: FloatArray, col: FloatArray,
        arcS: Double = 0.0, total: Double = 0.0,
    ) {
        val sh = shadeAt(stroke, i, arcS, total)
        val slot = if (sign < 0) 0 else 1
        val p = stroke.pts[i].p
        pos[slot * 3] = p.x.toFloat(); pos[slot * 3 + 1] = p.y.toFloat(); pos[slot * 3 + 2] = p.z.toFloat()
        nor[slot * 3] = (t.x * sign).toFloat()
        nor[slot * 3 + 1] = (t.y * sign).toFloat()
        nor[slot * 3 + 2] = (t.z * sign).toFloat()
        col[slot * 4] = (stroke.color.r + (1.0 - stroke.color.r) * sh.lift).toFloat()
        col[slot * 4 + 1] = (stroke.color.g + (1.0 - stroke.color.g) * sh.lift).toFloat()
        col[slot * 4 + 2] = (stroke.color.b + (1.0 - stroke.color.b) * sh.lift).toFloat()
        col[slot * 4 + 3] = sh.alpha.toFloat()
    }

    /** One band of quads between two rings; a closed loop wraps its last onto 0. */
    internal fun band(idx: IntArray, at0: Int, i: Int, j: Int, seg: Int): Int {
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

    internal fun startFan(idx: IntArray, at0: Int, seg: Int): Int {
        var at = at0
        for (k in 0 until seg) { idx[at++] = 0; idx[at++] = 2 + (k + 1) % seg; idx[at++] = 2 + k }
        return at
    }

    internal fun endFan(idx: IntArray, at0: Int, lastRing: Int, seg: Int): Int {
        var at = at0
        val base = 2 + lastRing * seg
        for (k in 0 until seg) { idx[at++] = 1; idx[at++] = base + k; idx[at++] = base + (k + 1) % seg }
        return at
    }
}
