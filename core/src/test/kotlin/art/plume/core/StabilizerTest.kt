package art.plume.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StabilizerTest {

    @Test
    fun `the first sample is always kept, and kept unsmoothed`() {
        val s = Stabilizer()
        assertTrue(s.next(120.0, 340.0))
        assertEquals(120.0, s.x, 0.0)
        assertEquals(340.0, s.y, 0.0)
    }

    @Test
    fun `a pen resting on the glass does not add points`() {
        val s = Stabilizer()
        s.begin(100.0, 100.0)
        var kept = 0
        // jitter of a pixel either way, the width of a resting hand's tremor
        for (i in 0 until 200) {
            val jx = if (i % 2 == 0) 0.6 else -0.6
            if (s.next(100.0 + jx, 100.0 - jx)) kept++
        }
        assertEquals(0, kept, "$kept jitter samples got through the resample gate")
    }

    @Test
    fun `a slow drift is not swallowed one sub-threshold step at a time`() {
        /*
         * This is the reason the gate measures against the last ACCEPTED
         * position rather than the last raw one. Against the raw stream, every
         * step here is under MIN_PX and the whole drift disappears — the stroke
         * silently stops following a slowly moving pen.
         */
        val s = Stabilizer()
        s.enabled = false                      // isolate the gate from the smoothing
        s.begin(0.0, 0.0)
        var kept = 0
        var x = 0.0
        for (i in 0 until 100) {
            x += Tune.MIN_PX * 0.4             // well under the threshold each time
            if (s.next(x, 0.0)) kept++
        }
        assertTrue(kept > 30, "only $kept of a 40px drift survived the gate")
    }

    @Test
    fun `smoothing lags the pen, and turning it off does not`() {
        val far = 500.0

        val off = Stabilizer()
        off.enabled = false
        off.begin(0.0, 0.0)
        off.next(far, 0.0)
        assertEquals(far, off.x, 1e-9, "with the stabiliser off the sample is the pen")

        val on = Stabilizer()
        on.amount = 0.45                        // the web build's default
        on.begin(0.0, 0.0)
        on.next(far, 0.0)
        assertEquals(far * (1.0 - 0.45), on.x, 1e-9)

        val heavy = Stabilizer()
        heavy.amount = 0.9
        heavy.begin(0.0, 0.0)
        heavy.next(far, 0.0)
        assertTrue(heavy.x < on.x, "a higher setting should lag further behind")
    }

    @Test
    fun `the amount is clamped, so the pen can never be ignored entirely`() {
        val s = Stabilizer()
        s.amount = 5.0                          // nonsense from a slider
        s.begin(0.0, 0.0)
        repeat(400) { s.next(1000.0, 0.0) }
        // at the 0.95 clamp it converges; at 1.0 it would never move at all
        assertTrue(s.x > 900.0, "the stabiliser never caught up: ${s.x}")
    }

    @Test
    fun `smoothing converges on the pen when it stops moving`() {
        val s = Stabilizer()
        s.begin(0.0, 0.0)
        repeat(200) { s.next(80.0, 60.0) }
        assertEquals(80.0, s.x, 1e-6)
        assertEquals(60.0, s.y, 1e-6)
    }

    @Test
    fun `reset makes the next sample a fresh start`() {
        val s = Stabilizer()
        s.begin(0.0, 0.0)
        assertFalse(s.next(0.5, 0.0))
        s.reset()
        assertTrue(s.next(0.5, 0.0), "after a reset the first sample must be kept")
        assertEquals(0.5, s.x, 0.0)
    }
}
