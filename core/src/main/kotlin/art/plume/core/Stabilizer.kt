package art.plume.core

/**
 * Stable Stroke — FACT (C.2): a stabiliser on the input, adjustable.
 *
 * An exponential moving average on the SCREEN position, applied before the
 * sample is projected into the world. Doing it in screen space rather than
 * world space is what makes the amount mean the same thing at every zoom
 * level: a person judges wobble by how it looks, not by how many millimetres
 * it covers.
 *
 * [amount] runs 0 to 0.95. It is the weight kept on the previous position, so
 * higher is smoother and laggier; the web build's default is 0.45.
 *
 * The second job here is the resample gate. A pen resting on glass wanders a
 * pixel or two, and the tube cannot show two points a pixel apart as anything
 * but a longer tube — so samples closer than [Tune.MIN_PX] to the last
 * ACCEPTED one are dropped. Measuring against the last accepted point rather
 * than the last raw one is the whole point: measuring against the raw stream
 * lets a slow drift accumulate one sub-threshold step at a time and sneak
 * every sample through.
 */
class Stabilizer {

    var enabled = true
    var amount = Tune.STABLE_DEFAULT

    private var smX = 0.0
    private var smY = 0.0
    private var lastX = 0.0
    private var lastY = 0.0
    private var started = false

    /** Where the smoothed pen is now, whether or not the last sample was kept. */
    val x: Double get() = smX
    val y: Double get() = smY

    /**
     * Start a stroke. The first sample is never smoothed and never dropped —
     * there is nothing to smooth towards, and a stroke that swallowed its own
     * first point would start late.
     */
    fun begin(px: Double, py: Double) {
        smX = px; smY = py
        lastX = px; lastY = py
        started = true
    }

    /**
     * Feed a sample. Returns true when it should be kept, in which case [x] and
     * [y] hold the smoothed position to project.
     */
    fun next(px: Double, py: Double): Boolean {
        if (!started) { begin(px, py); return true }

        if (enabled) {
            val k = 1.0 - clamp(amount, 0.0, Tune.STABLE_MAX)
            smX += (px - smX) * k
            smY += (py - smY) * k
        } else {
            smX = px; smY = py
        }

        val dx = smX - lastX
        val dy = smY - lastY
        if (dx * dx + dy * dy < Tune.MIN_PX * Tune.MIN_PX) return false

        lastX = smX; lastY = smY
        return true
    }

    fun reset() { started = false }
}
