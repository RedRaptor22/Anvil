package art.plume.core

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The group model.
 *
 * Most of these are written against a specific fault the web build shipped, so
 * that the port starts from the fixed behaviour rather than inheriting it and
 * discovering it again on a phone. Where that is the case the test says which.
 */
class GroupsTest {

    private fun sketch(vararg strokes: Stroke): Sketch {
        val s = Sketch()
        for (st in strokes) s.addStroke(st)
        return s
    }

    /** A short straight stroke at x, running along Z so a camera ray can cross it. */
    private fun bar(x: Double, y: Double = 0.0, radius: Double = 0.02): Stroke {
        val s = Stroke(brush = "pen", baseRadius = radius)
        for (i in 0..4) s.pts.add(StrokePoint(Vec3(x, y, -0.2 + i * 0.1)))
        return s
    }

    private fun grouped(sk: Sketch, vararg of: Stroke): Group {
        sk.clearSelection()
        for (s in of) sk.setSelected(s, true)
        assertNotNull(sk.groupSelection(), "grouping ${of.size} strokes should produce an edit")
        return sk.groupOf(of[0])!!
    }

    // ---- visibility -----------------------------------------------------

    @Test
    fun `hiding a group hides its strokes and only its strokes`() {
        val a = bar(0.0); val b = bar(0.3); val loose = bar(0.6)
        val sk = sketch(a, b, loose)
        val g = grouped(sk, a, b)

        assertEquals(3, sk.visibleStrokes().size)
        sk.setVisible(g, false)

        assertFalse(sk.isVisible(a)); assertFalse(sk.isVisible(b))
        assertTrue(sk.isVisible(loose), "an ungrouped stroke has nothing that could hide it")
        assertEquals(listOf(loose), sk.visibleStrokes())
    }

    /**
     * The reported bug, in the shape it took in the web build: the flag flips
     * and what is on screen does not follow, because visibility is stored in
     * two places. Here [Sketch.visibleStrokes] is derived from the one flag, so
     * the two cannot disagree — this test is what pins that down.
     */
    @Test
    fun `the visible flag and what is drawn can never disagree`() {
        val a = bar(0.0); val b = bar(0.3)
        val sk = sketch(a, b)
        val g = grouped(sk, a, b)

        for (want in listOf(false, true, false, false, true)) {
            sk.setVisible(g, want)
            assertEquals(want, g.visible)
            assertEquals(
                if (want) setOf(a, b) else emptySet(),
                sk.visibleStrokes().toSet(),
                "eye says ${g.visible}, scene says otherwise",
            )
        }
    }

    @Test
    fun `hiding a group is undoable and takes the selection with it`() {
        val a = bar(0.0); val b = bar(0.3)
        val sk = sketch(a, b)
        val g = grouped(sk, a, b)
        assertEquals(2, sk.selection.size)

        val edit = sk.setVisible(g, false)
        assertTrue(sk.selection.isEmpty(), "a hidden group must not stay selected")

        edit.revert()
        assertTrue(g.visible)
        assertEquals(setOf(a, b), sk.selection, "undo puts the selection back")
    }

    @Test
    fun `a hidden stroke cannot be selected or picked`() {
        val a = bar(0.0)
        val behind = bar(0.0, y = 0.0, radius = 0.02)
        val sk = sketch(a, behind)
        val g = grouped(sk, a, behind)
        sk.setVisible(g, false)

        sk.setSelected(a, true)
        assertTrue(sk.selection.isEmpty(), "you cannot select what you cannot see")

        val hit = sk.pick(Vec3(-1.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0))
        assertNull(hit, "a hidden group must not catch a tap meant for what is behind it")

        sk.setVisible(g, true)
        assertNotNull(sk.pick(Vec3(-1.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0)))
    }

    // ---- selection ------------------------------------------------------

    @Test
    fun `selecting one member takes the whole group`() {
        val a = bar(0.0); val b = bar(0.3); val c = bar(0.6); val loose = bar(0.9)
        val sk = sketch(a, b, c, loose)
        grouped(sk, a, b, c)

        sk.clearSelection()
        sk.setSelected(b, true)
        assertEquals(setOf(a, b, c), sk.selection)

        sk.setSelected(a, false)
        assertTrue(sk.selection.isEmpty(), "deselecting a member drops the group, not half of it")
    }

    @Test
    fun `select all skips hidden groups`() {
        val a = bar(0.0); val b = bar(0.3); val loose = bar(0.6)
        val sk = sketch(a, b, loose)
        val g = grouped(sk, a, b)
        sk.setVisible(g, false)

        sk.selectAll()
        assertEquals(setOf(loose), sk.selection)
    }

    // ---- grouping -------------------------------------------------------

    @Test
    fun `grouping needs two curves`() {
        val a = bar(0.0)
        val sk = sketch(a)
        sk.setSelected(a, true)
        assertNull(sk.groupSelection(), "one curve is not a group")
        assertTrue(sk.groups.isEmpty())
    }

    /**
     * A lasso can hand Group or Ungroup half of an existing group. The web
     * build acted on the half, leaving the rest of the group tagged with an id
     * that now meant something else.
     */
    @Test
    fun `grouping part of a group folds the whole of it in`() {
        val a = bar(0.0); val b = bar(0.3); val c = bar(0.6)
        val sk = sketch(a, b, c)
        val first = grouped(sk, a, b)

        // setSelected widens on its own, so a torn set is not reachable through
        // it — check the widening directly, then that grouping uses it
        assertEquals(setOf(a, b), sk.wholeGroups(listOf(a)).toSet(),
                     "half a group widens to the whole of it")
        assertEquals(setOf(a, b, c), sk.wholeGroups(listOf(a, c)).toSet())

        sk.clearSelection()
        sk.setSelected(a, true)
        sk.setSelected(c, true)
        val edit = sk.groupSelection()
        assertNotNull(edit)

        val g = sk.groupOf(a)
        assertSame(g, sk.groupOf(b), "b was pulled in with a rather than left behind")
        assertSame(g, sk.groupOf(c))
        assertFalse(sk.groups.contains(first), "the emptied group is gone, not a dead row")
        assertEquals(setOf(a, b, c), sk.selection)
    }

    @Test
    fun `ungrouping one member ungroups the whole group`() {
        val a = bar(0.0); val b = bar(0.3); val c = bar(0.6)
        val sk = sketch(a, b, c)
        val g = grouped(sk, a, b, c)

        sk.clearSelection()
        sk.setSelected(a, true)
        val edit = sk.ungroupSelection()
        assertNotNull(edit)
        assertNull(sk.groupOf(a)); assertNull(sk.groupOf(b)); assertNull(sk.groupOf(c))
        assertTrue(sk.groups.isEmpty())

        edit.revert()
        assertSame(g, sk.groupOf(a))
        assertSame(g, sk.groupOf(c))
        assertTrue(sk.groups.contains(g), "undo brings the group row back too")
    }

    @Test
    fun `ungrouping loose curves does nothing`() {
        val a = bar(0.0); val b = bar(0.3)
        val sk = sketch(a, b)
        sk.setSelected(a, true); sk.setSelected(b, true)
        assertNull(sk.ungroupSelection())
    }

    @Test
    fun `a stroke belongs to at most one group`() {
        val a = bar(0.0); val b = bar(0.3); val c = bar(0.6)
        val sk = sketch(a, b, c)
        grouped(sk, a, b)
        val second = grouped(sk, a, c)          // widens to a, b and c
        assertSame(second, sk.groupOf(a))
        assertSame(second, sk.groupOf(b))
        assertSame(second, sk.groupOf(c))
        assertEquals(1, sk.groups.size)
    }

    // ---- duplication ----------------------------------------------------

    /**
     * The web build's copies inherit the source's group id, which silently
     * enrols them in that group: tap an original afterwards and the copies come
     * too, and the two can never be separated again.
     */
    @Test
    fun `copies get a group of their own`() {
        val a = bar(0.0); val b = bar(0.3)
        val sk = sketch(a, b)
        val g = grouped(sk, a, b)

        val edit = sk.duplicateSelection(Vec3(1.0, 0.0, 0.0))
        assertNotNull(edit)
        val copies = sk.strokes.filter { it !== a && it !== b }
        assertEquals(2, copies.size)

        val cg = sk.groupOf(copies[0])
        assertNotNull(cg, "a duplicated group is still a group")
        assertSame(cg, sk.groupOf(copies[1]), "and one group, not two")
        assertTrue(cg !== g, "but NOT the group it was copied from")

        sk.clearSelection()
        sk.setSelected(a, true)
        assertEquals(setOf(a, b), sk.selection, "picking an original leaves the copies alone")

        edit.revert()
        assertEquals(listOf(a, b), sk.strokes)
        assertEquals(listOf(g), sk.groups)
    }

    @Test
    fun `duplicated loose curves stay loose`() {
        val a = bar(0.0)
        val sk = sketch(a)
        sk.setSelected(a, true)
        assertNotNull(sk.duplicateSelection(Vec3(1.0, 0.0, 0.0)))
        val copy = sk.strokes.single { it !== a }
        assertNull(sk.groupOf(copy))
        assertTrue(sk.groups.isEmpty())
    }

    @Test
    fun `a copy is offset and shares no points with its source`() {
        val a = bar(0.0)
        val sk = sketch(a)
        sk.setSelected(a, true)
        sk.duplicateSelection(Vec3(1.0, 0.0, 0.0))
        val copy = sk.strokes.single { it !== a }
        assertEquals(a.pts.size, copy.pts.size)
        assertEquals(a.pts[0].p.x + 1.0, copy.pts[0].p.x, 1e-12)
        copy.pts[0].p.x = 99.0
        assertTrue(a.pts[0].p.x != 99.0, "the copy must not alias the source's points")
    }

    // ---- assigning ------------------------------------------------------

    @Test
    fun `assign moves the selection into an existing group`() {
        val a = bar(0.0); val b = bar(0.3); val loose = bar(0.6)
        val sk = sketch(a, b, loose)
        val g = grouped(sk, a, b)

        sk.clearSelection()
        sk.setSelected(loose, true)
        val edit = sk.assignSelectionTo(g)
        assertNotNull(edit)
        assertSame(g, sk.groupOf(loose))
        assertEquals(3, sk.membersOf(g).size)

        edit.revert()
        assertNull(sk.groupOf(loose))
        assertEquals(2, sk.membersOf(g).size)
    }

    @Test
    fun `assigning a member of another group moves that group whole`() {
        val a = bar(0.0); val b = bar(0.3); val c = bar(0.6); val d = bar(0.9)
        val sk = sketch(a, b, c, d)
        val one = grouped(sk, a, b)
        val two = grouped(sk, c, d)

        sk.clearSelection()
        sk.setSelected(c, true)
        assertNotNull(sk.assignSelectionTo(one))
        assertEquals(setOf(a, b, c, d), sk.membersOf(one).toSet())
        assertFalse(sk.groups.contains(two), "the emptied group is pruned")
    }

    @Test
    fun `assigning into a hidden group shows it rather than vanishing the strokes`() {
        val a = bar(0.0); val b = bar(0.3); val loose = bar(0.6)
        val sk = sketch(a, b, loose)
        val g = grouped(sk, a, b)
        sk.setVisible(g, false)

        sk.clearSelection()
        sk.setSelected(loose, true)
        val edit = sk.assignSelectionTo(g)
        assertNotNull(edit)
        assertTrue(g.visible)
        assertTrue(sk.isVisible(loose))

        edit.revert()
        assertFalse(g.visible, "undo puts the group back the way it was")
    }

    @Test
    fun `assigning into the group you are already in is not an edit`() {
        val a = bar(0.0); val b = bar(0.3)
        val sk = sketch(a, b)
        val g = grouped(sk, a, b)
        assertNull(sk.assignSelectionTo(g), "nothing moves, so there is nothing to undo")
    }

    @Test
    fun `renaming is undoable`() {
        val a = bar(0.0); val b = bar(0.3)
        val sk = sketch(a, b)
        val g = grouped(sk, a, b)
        val was = g.name
        val edit = sk.renameGroup(g, "Left arm")
        assertEquals("Left arm", g.name)
        edit.revert()
        assertEquals(was, g.name)
    }

    // ---- deletion -------------------------------------------------------

    @Test
    fun `deleting a member deletes the group and undo restores order`() {
        val a = bar(0.0); val b = bar(0.3); val c = bar(0.6)
        val sk = sketch(a, b, c)
        val g = grouped(sk, a, c)              // a and c, straddling b

        sk.clearSelection()
        sk.setSelected(a, true)
        val edit = sk.deleteSelection()
        assertNotNull(edit)
        assertEquals(listOf(b), sk.strokes)
        assertTrue(sk.groups.isEmpty())

        edit.revert()
        assertEquals(listOf(a, b, c), sk.strokes, "undo puts them back where they were")
        assertSame(g, sk.groupOf(a))
        assertSame(g, sk.groupOf(c))
    }

    // ---- undo stack -----------------------------------------------------

    @Test
    fun `the edit stack covers groups, visibility and drawing alike`() {
        val a = bar(0.0); val b = bar(0.3)
        val sk = Sketch()
        val stack = EditStack()
        stack.push(sk.addStroke(a))
        stack.push(sk.addStroke(b))
        sk.setSelected(a, true); sk.setSelected(b, true)
        stack.push(sk.groupSelection())
        val g = sk.groupOf(a)!!
        stack.push(sk.setVisible(g, false))

        assertFalse(g.visible)
        stack.undo(); assertTrue(g.visible, "undo the hide")
        stack.undo(); assertNull(sk.groupOf(a), "undo the group")
        stack.undo(); assertEquals(listOf(a), sk.strokes, "undo the second stroke")

        stack.redo(); assertEquals(listOf(a, b), sk.strokes)
        stack.redo(); assertNotNull(sk.groupOf(a))
        stack.redo(); assertFalse(sk.groupOf(a)!!.visible)
        assertFalse(stack.canRedo)
    }

    @Test
    fun `a new edit drops the redo tail`() {
        val a = bar(0.0); val b = bar(0.3)
        val sk = Sketch()
        val stack = EditStack()
        stack.push(sk.addStroke(a))
        stack.undo()
        assertTrue(stack.canRedo)
        stack.push(sk.addStroke(b))
        assertFalse(stack.canRedo, "drawing after an undo forks the history")
    }

    // ---- picking --------------------------------------------------------

    @Test
    fun `pick returns the nearest stroke along the ray`() {
        val near = bar(0.0); val far = bar(1.0)
        val sk = sketch(far, near)
        val hit = sk.pick(Vec3(-2.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0))
        assertSame(near, hit, "the ray crosses both; the near one wins")
    }

    @Test
    fun `a ray that misses picks nothing, and slack decides by how much`() {
        val a = bar(0.0, y = 0.0, radius = 0.02)
        val sk = sketch(a)
        val origin = Vec3(-2.0, 0.5, 0.0)
        assertNull(sk.pick(origin, Vec3(1.0, 0.0, 0.0)), "0.5 away from a 0.02 tube")
        assertNotNull(sk.pick(origin, Vec3(1.0, 0.0, 0.0), slack = 0.6))
    }

    @Test
    fun `nothing behind the eye is picked`() {
        val a = bar(0.0)
        val sk = sketch(a)
        assertNull(sk.pick(Vec3(2.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0)), "ray points away from it")
    }

    @Test
    fun `a stroke drawn straight at the camera still picks`() {
        // parallel to the ray: the closest-approach solve divides by zero here
        val s = Stroke(brush = "pen", baseRadius = 0.02)
        for (i in 0..4) s.pts.add(StrokePoint(Vec3(-1.0 + i * 0.2, 0.0, 0.0)))
        val sk = sketch(s)
        assertNotNull(sk.pick(Vec3(-3.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0)))
    }

    @Test
    fun `a chisel is pickable edge on`() {
        // flat: wide 3.4, flat 0.04 — picking against the thin axis misses it
        val s = Stroke(brush = "flat", baseRadius = 0.05)
        for (i in 0..4) s.pts.add(StrokePoint(Vec3(0.0, 0.0, -0.2 + i * 0.1)))
        val sk = sketch(s)
        val off = StrokeGeometry.halfWidth(s, s.baseRadius) * 0.9
        assertNotNull(
            sk.pick(Vec3(-2.0, off, 0.0), Vec3(1.0, 0.0, 0.0)),
            "offset by 0.9 of its half-width, so inside the widest section",
        )
    }

    @Test
    fun `tap slack grows with distance`() {
        val near = Picking.slackFor(1.0, 24.0, 1920.0, 50.0 * PI / 180.0)
        val far = Picking.slackFor(10.0, 24.0, 1920.0, 50.0 * PI / 180.0)
        assertTrue(far > near * 9.0, "a fixed pixel target is a growing world target")
        assertEquals(0.0, Picking.slackFor(1.0, 24.0, 0.0, 1.0), "no viewport, no slack")
    }
}
