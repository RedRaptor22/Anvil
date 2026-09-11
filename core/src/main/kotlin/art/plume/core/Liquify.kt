package art.plume.core

import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow

/**
 * Liquify: push, pinch and comb.
 *
 * FACT: the panel carries size, range and strength, each adjusted by sliding,
 * and a mode you tap or drag to change.
 *
 * All three work in SCREEN space and convert back to world at each point's own
 * depth, which is what makes the deformation track the pen rather than drifting
 * away from it on the far side of a sketch.
 */
object Liquify {

    enum class Mode { PUSH, PINCH, COMB }

    class Settings(
        var mode: Mode = Mode.PUSH,
        /** Disc radius in pixels. */
        var size: Double = 120.0,
        /** 0..100. How much of the disc is at full strength. */
        var range: Double = 60.0,
        /** 0..100. */
        var strength: Double = 55.0,
    )

    /**
     * Screen-space falloff: 1 at the centre, 0 at the rim.
     *
     * [range] 100 gives a wide, gentle shoulder; 0 gives a sharp spike. The
     * exponent is what the range slider actually controls — a linear ramp
     * would make every setting feel much the same.
     */
    fun falloff(d: Double, r: Double, range: Double): Double {
        if (d >= r) return 0.0
        val t = 1 - d / r
        val soft = clamp(range / 100, 0.0, 1.0)
        val k = 1 + (1 - soft) * 6
        return t.pow(k)
    }

    /**
     * One drag step. [targets] is what the tool applies to — Liquify works on a
     * SELECTION rather than on everything, so a shove cannot quietly reshape a
     * curve on the other side of the drawing.
     *
     * Returns the curves it moved.
     */
    fun step(
        targets: List<Stroke>,
        camera: Camera,
        cfg: Settings,
        fromX: Double,
        fromY: Double,
        x: Double,
        y: Double,
        mask: (Vec3) -> Boolean = Editing.NO_MASK,
    ): List<Stroke> {
        val dxPx = x - fromX
        val dyPx = y - fromY
        val dragPx = hypot(dxPx, dyPx)
        // comb straightens where it sits, so it still works when the pen rests
        if (cfg.mode != Mode.COMB && dragPx < 0.01) return emptyList()

        val rPx = maxOf(8.0, cfg.size)
        val strength = clamp(cfg.strength / 100, 0.0, 1.0)
        val right = Vec3(); val up = Vec3(); val back = Vec3()
        camera.basis(right, up, back)

        val touched = ArrayList<Stroke>()
        val scr = Vec3()
        val mid = Vec3()

        for (st in targets) {
            if (Editing.farFromDisc(camera, st, x, y, rPx)) continue
            val pts = st.pts
            var moved = false
            for (j in pts.indices) {
                camera.worldToScreen(pts[j].p, scr)
                if (scr.z < -1 || scr.z > 1) continue
                val d = hypot(scr.x - x, scr.y - y)
                val w = falloff(d, rPx, cfg.range)
                if (w <= 0) continue
                if (mask(pts[j].p)) continue

                var mx = 0.0
                var my = 0.0
                when (cfg.mode) {
                    Mode.PUSH -> { mx = dxPx * w * strength; my = dyPx * w * strength }

                    Mode.PINCH -> {
                        /* Toward the cursor, by how far the pen MOVED — a
                           squeeze rather than a shove, so a curve can be drawn
                           in or stretched out. Capped at the distance to the
                           cursor so a point cannot overshoot through it. */
                        val toX = x - scr.x
                        val toY = y - scr.y
                        val len = hypot(toX, toY).let { if (it < 1e-9) 1.0 else it }
                        val pull = min(len, dragPx * w * strength)
                        mx = toX / len * pull
                        my = toY / len * pull
                    }

                    Mode.COMB -> {
                        /* Pull each point toward the line its neighbours make,
                           which straightens a wobble without moving the curve
                           as a whole. The ends have no neighbours to average. */
                        if (j == 0 || j == pts.size - 1) continue
                        val a = pts[j - 1].p
                        val b = pts[j + 1].p
                        mid.set(
                            (a.x + b.x) * 0.5 - pts[j].p.x,
                            (a.y + b.y) * 0.5 - pts[j].p.y,
                            (a.z + b.z) * 0.5 - pts[j].p.z,
                        )
                        pts[j].p.addScaled(mid, w * strength * 0.5)
                        moved = true
                        continue
                    }
                }
                if (mx == 0.0 && my == 0.0) continue

                // pixels to world AT THIS POINT'S DEPTH, so it tracks the pen
                val scale = camera.pxToWorldAt(pts[j].p)
                pts[j].p.addScaled(right, mx * scale).addScaled(up, -my * scale)
                moved = true
            }
            if (moved) touched.add(st)
        }
        return touched
    }
}
