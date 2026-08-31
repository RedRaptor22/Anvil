package art.plume.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Colour conversions for the picker.
 *
 * Ported from the hsvToRgb / setHSVFromColor pair in js/ui.js. It lives in
 * core rather than beside the widget for the usual reason: a wheel can only be
 * looked at, and "the hex you typed is the colour you get back" is a claim a
 * test can settle. The round trip is also the one part that is easy to get
 * subtly wrong — hue is undefined for a grey, and a picker that snaps grey to
 * red every time it reopens is a picker nobody trusts.
 */
object ColorSpace {

    /** Hue in degrees 0..360, saturation and value 0..1. */
    class Hsv(var h: Double, var s: Double, var v: Double)

    fun hsvToRgb(h: Double, s: Double, v: Double): Rgba {
        val hh = ((h % 360.0) + 360.0) % 360.0
        val c = v * s
        val x = c * (1 - abs((hh / 60.0) % 2 - 1))
        val m = v - c
        val (r, g, b) = when {
            hh < 60 -> Triple(c, x, 0.0)
            hh < 120 -> Triple(x, c, 0.0)
            hh < 180 -> Triple(0.0, c, x)
            hh < 240 -> Triple(0.0, x, c)
            hh < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        return Rgba(r + m, g + m, b + m)
    }

    /**
     * The inverse.
     *
     * [previousHue] is kept for the case the maths cannot answer: a grey has no
     * hue at all, and every formula returns 0 for it. Without this the wheel's
     * knob jumps to red the moment the value slides to black or the saturation
     * to zero — the colour is right and the control lies about where it is.
     */
    fun rgbToHsv(c: Rgba, previousHue: Double = 0.0): Hsv {
        val r = c.r; val g = c.g; val b = c.b
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val h = when {
            d <= 1e-6 -> previousHue
            mx == r -> 60 * (((g - b) / d) % 6)
            mx == g -> 60 * ((b - r) / d + 2)
            else -> 60 * ((r - g) / d + 4)
        }
        return Hsv(((h % 360.0) + 360.0) % 360.0, if (mx < 1e-6) 0.0 else d / mx, mx)
    }

    /**
     * `#rgb`, `#rrggbb`, or the same without the hash. Anything else is null
     * rather than a guess: a half-typed hex should leave the colour alone, not
     * jump somewhere on every keystroke.
     */
    fun parseHex(text: String?): Rgba? {
        val v = (text ?: return null).filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        val six = when (v.length) {
            3 -> "${v[0]}${v[0]}${v[1]}${v[1]}${v[2]}${v[2]}"
            6 -> v
            else -> return null
        }
        val n = six.toLongOrNull(16) ?: return null
        return Rgba(
            ((n shr 16) and 0xFF) / 255.0,
            ((n shr 8) and 0xFF) / 255.0,
            (n and 0xFF) / 255.0,
        )
    }

    fun toHex(c: Rgba): String {
        fun ch(v: Double) = (clamp(v, 0.0, 1.0) * 255).toInt().coerceIn(0, 255)
        return "%02X%02X%02X".format(ch(c.r), ch(c.g), ch(c.b))
    }
}
