package art.plume.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoryTest {

    private fun step(label: String, cost: Int = 0, log: MutableList<String>? = null) = Step(
        label, cost,
        onUndo = { log?.add("-$label") },
        onRedo = { log?.add("+$label") },
    )

    @Test
    fun `undo and redo walk the stack without losing anything`() {
        val log = ArrayList<String>()
        val h = History()
        h.run(step("a", log = log))
        h.run(step("b", log = log))
        h.run(step("c", log = log))
        assertEquals(listOf("+a", "+b", "+c"), log)

        assertTrue(h.undo()); assertTrue(h.undo())
        assertEquals("a", h.undoLabel())
        assertEquals("b", h.redoLabel())
        assertTrue(h.redo())
        assertEquals(listOf("+a", "+b", "+c", "-c", "-b", "+b"), log)

        assertTrue(h.undo()); assertTrue(h.undo())
        assertFalse(h.undo(), "undo past the bottom of the stack should do nothing")
        assertFalse(h.canUndo())
    }

    @Test
    fun `a new step drops the redo tail and refunds what it was holding`() {
        val h = History()
        h.run(step("a", cost = 100))
        h.run(step("b", cost = 500))
        h.run(step("c", cost = 900))
        assertEquals(1500, h.cost)

        h.undo(); h.undo()                       // b and c are now redo tail
        assertTrue(h.canRedo())
        h.run(step("d", cost = 7))

        assertFalse(h.canRedo(), "the redo tail should be gone")
        assertEquals(2, h.size)
        // 100 for a, 7 for d — b and c are no longer retained by anything
        assertEquals(107, h.cost, "dropping the redo tail must refund its cost")
    }

    @Test
    fun `the oldest steps fall off once the depth is passed`() {
        val h = History()
        for (i in 0 until Tune.UNDO_DEPTH + 25) h.run(step("s$i"))
        assertEquals(Tune.UNDO_DEPTH, h.size)
        // the survivors are the most recent ones, and the index still points
        // at the top rather than off the end of the shortened stack
        assertEquals(Tune.UNDO_DEPTH, h.index)
        assertEquals("s${Tune.UNDO_DEPTH + 24}", h.undoLabel())
    }

    @Test
    fun `a few huge steps are evicted before two hundred tiny ones would be`() {
        /*
         * The reason the budget is counted in POINTS and not in steps. Three
         * strokes of 200k points each is 30 MB of retained history at a stack
         * depth of three — a depth limit alone would never notice.
         */
        val h = History()
        h.run(step("big1", cost = 200_000))
        h.run(step("big2", cost = 200_000))
        assertEquals(2, h.size)
        h.run(step("big3", cost = 200_000))

        assertTrue(h.cost <= Tune.UNDO_POINT_BUDGET, "over budget at ${h.cost}")
        assertEquals(2, h.size, "the oldest big step should have been dropped")
        assertEquals("big3", h.undoLabel())
    }

    @Test
    fun `a single step larger than the whole budget is still undoable`() {
        /*
         * Evicting down to nothing would leave undo silently dead right after
         * the one action most likely to want it. One step always survives.
         */
        val h = History()
        h.run(step("enormous", cost = Tune.UNDO_POINT_BUDGET * 4))
        assertEquals(1, h.size)
        assertTrue(h.canUndo())
        assertTrue(h.undo())
    }

    @Test
    fun `listeners hear about every change, including a clear`() {
        val h = History()
        var beats = 0
        h.addListener { beats++ }
        h.run(step("a"))
        h.undo()
        h.redo()
        h.clear()
        assertEquals(4, beats)
        assertEquals(0, h.size)
        assertEquals(0, h.cost)
        assertFalse(h.canUndo())
    }
}
