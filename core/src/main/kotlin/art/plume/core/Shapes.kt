package art.plume.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Draw Shape  (C.9): recognising what you meant to draw.
 *
 * FACT: "Hold after drawing to adjust length/endpoint (lines) or curvature
 * (curves); press-hold-drag to size a circle." So the pen keeps its samples
 * until it stops moving, and then the stroke snaps to its fitted shape.
 *
 * All three fits work in the SCREEN plane, because that is where the judgement
 * is made: a circle drawn on a guide seen at an angle is an ellipse in world
 * space and a circle to the person drawing it. The caller converts the result
 * back to world.
 */
object Shapes {

    /** GUESS: the docs give no figure for the hold. */
    const val HOLD_MS = 420L

    /** GUESS: straightness gate — how far off a line before it is a curve. */
    const val LINE_GATE = 0.035

    /** GUESS: roundness gate. */
    const val CIRCLE_GATE = 0.14

    /** Below this the gesture is a dot, not a shape. */
    const val MIN_SPAN_PX = 12.0

    sealed class Shape {
        abstract val points: List<Px>

        class Line(var a: Px, var b: Px) : Shape() {
            override val points: List<Px>
                get() = (0..32).map {
                    val t = it / 32.0
                    Px(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
                }
        }

        class Circle(var cx: Double, var cy: Double, var r: Double) : Shape() {
            override val points: List<Px>
                get() = (0..64).map {
                    val a = it / 64.0 * PI * 2
                    Px(cx + cos(a) * r, cy + sin(a) * r)
                }
        }

        /**
         * A smooth curve through the input, bowed by an even parabola along
         * its length — so holding still leaves it untouched and dragging bends
         * it smoothly rather than dragging one point out of it.
         */
        class Curve(val base: List<Px>, var bow: Double = 0.0) : Shape() {
            override val points: List<Px>
                get() {
                    val n = base.size
                    if (n < 2) return base
                    val dx = base[n - 1].x - base[0].x
                    val dy = base[n - 1].y - base[0].y
                    val l = hypot(dx, dy).let { if (it < 1e-9) 1.0 else it }
                    val px = -dy / l
                    val py = dx / l
                    return base.mapIndexed { i, p ->
                        val u = i.toDouble() / (n - 1)
                        val w = 4 * u * (1 - u) * bow
                        Px(p.x + px * w, p.y + py * w)
                    }
                }
        }
    }

    class LineFit(val a: Px, val b: Px, val length: Double, val deviation: Double)
    class CircleFit(val cx: Double, val cy: Double, val r: Double, val deviation: Double)

    /**
     * How straight is this? [LineFit.deviation] is the worst offset from the
     * chord, as a fraction of its length, so the gate means the same thing at
     * any size.
     */
    fun fitLine(pts: List<Px>): LineFit? {
        if (pts.size < 2) return null
        val a = pts.first()
        val b = pts.last()
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (len < Vec3.EPS) return null
        var worst = 0.0
        for (p in pts) {
            val d = abs((p.x - a.x) * dy - (p.y - a.y) * dx) / len
            if (d > worst) worst = d
        }
        return LineFit(a, b, len, worst / len)
    }

    /** Algebraic (Kasa) circle fit — closed form, no iteration. */
    fun fitCircle(pts: List<Px>): CircleFit? {
        val n = pts.size
        if (n < 8) return null
        var sx = 0.0; var sy = 0.0
        for (p in pts) { sx += p.x; sy += p.y }
        val mx = sx / n; val my = sy / n

        var suu = 0.0; var suv = 0.0; var svv = 0.0
        var suuu = 0.0; var svvv = 0.0; var suvv = 0.0; var svuu = 0.0
        for (p in pts) {
            val u = p.x - mx; val v = p.y - my
            suu += u * u; svv += v * v; suv += u * v
            suuu += u * u * u; svvv += v * v * v
            suvv += u * v * v; svuu += v * u * u
        }
        val det = 2 * (suu * svv - suv * suv)
        if (abs(det) < Vec3.EPS) return null
        val uc = (svv * (suuu + suvv) - suv * (svvv + svuu)) / det
        val vc = (suu * (svvv + svuu) - suv * (suuu + suvv)) / det
        val cx = uc + mx
        val cy = vc + my

        var r = 0.0
        for (p in pts) r += hypot(p.x - cx, p.y - cy)
        r /= n
        if (r < Vec3.EPS) return null
        var dev = 0.0
        for (p in pts) dev = max(dev, abs(hypot(p.x - cx, p.y - cy) - r))
        return CircleFit(cx, cy, r, dev / r)
    }

    /**
     * What the gesture was: a line, a circle, or a tidied version of itself.
     *
     * A circle is only considered for a CLOSED gesture. Fitting one to an arc
     * finds a perfectly good circle that the person did not draw — the fit
     * itself cannot tell you they stopped a third of the way round.
     */
    fun fitShape(screen: List<Px>): Shape? {
        if (screen.size < 2) return null
        var span = 0.0
        for (i in 1 until screen.size) {
            span += hypot(screen[i].x - screen[i - 1].x, screen[i].y - screen[i - 1].y)
        }
        if (span < MIN_SPAN_PX) return null

        val closed = hypot(
            screen[0].x - screen.last().x, screen[0].y - screen.last().y,
        ) < span * 0.22

        fitLine(screen)?.let { if (it.deviation < LINE_GATE) return Shape.Line(it.a, it.b) }

        if (closed) {
            fitCircle(screen)?.let {
                if (it.deviation < CIRCLE_GATE) return Shape.Circle(it.cx, it.cy, it.r)
            }
        }

        // otherwise: a smooth curve through the input
        val v3 = screen.map { Vec3(it.x, it.y, 0.0) }
        val sm = Polyline.smooth(v3, 6, 0.6)
        val re = Polyline.resample(sm, kotlin.math.min(64, max(8, sm.size)))
        return Shape.Curve(re.map { Px(it.x, it.y) })
    }

    /**
     * Hold-to-adjust: the pen stops adding samples and drives ONE parameter of
     * the fitted shape instead.
     *
     * [anchor] is where the hold began, so a drag is measured from the moment
     * the shape froze rather than from wherever the stroke started.
     */
    fun adjust(shape: Shape, anchor: Px, x: Double, y: Double) {
        when (shape) {
            // a line follows its far endpoint
            is Shape.Line -> shape.b = Px(x, y)
            // press-hold-drag sizes a circle, measured from its centre
            is Shape.Circle -> shape.r = max(1.0, hypot(x - shape.cx, y - shape.cy))
            /* a curve bows: the drag's component ACROSS the chord, so pulling
               along it does nothing and pulling sideways bends it */
            is Shape.Curve -> {
                val n = shape.base.size
                if (n < 2) return
                val dx = shape.base[n - 1].x - shape.base[0].x
                val dy = shape.base[n - 1].y - shape.base[0].y
                val l = hypot(dx, dy).let { if (it < 1e-9) 1.0 else it }
                shape.bow = ((x - anchor.x) * (-dy / l) + (y - anchor.y) * (dx / l))
            }
        }
    }
}
