package art.plume.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Groups: the folders curves hide and show in.
 *
 * FACT (C.8): groups can be created, renamed, hidden and deleted, and a curve
 * can be assigned to one. Deleting a group frees its curves rather than taking
 * them with it.
 *
 * This file exists because there was no group test file, and six faults lived
 * in the gap — the loudest being that hiding a group changed nothing on
 * screen. The property that matters is not "the flag flipped" but "every
 * reader of the sketch agrees the curve is gone": the renderer, the selection,
 * the tools and the exporter each ask separately, so each is asked here.
 */
class GroupTest {

    private fun strokeAt(x: Double): Stroke =
        Stroke().also {
            it.pts.add(StrokePoint(Vec3(x, 0.0, 0.0)))
            it.pts.add(StrokePoint(Vec3(x, 0.1, 0.0)))
        }

    /** A sketch with two groups of two curves each. */
    private class Fixture {
        val sketch = Sketch()
        val a: StrokeGroup
        val b: StrokeGroup
        val inA: List<Stroke>
        val inB: List<Stroke>

        init {
            a = sketch.newGroup("A")
            b = sketch.newGroup("B")
            inA = (0..1).map { i ->
                Stroke().also {
                    it.pts.add(StrokePoint(Vec3(i * 0.1, 0.0, 0.0)))
                    it.pts.add(StrokePoint(Vec3(i * 0.1, 0.1, 0.0)))
                    it.group = a.id
                    sketch.add(it)
                }
            }
            inB = (0..1).map { i ->
                Stroke().also {
                    it.pts.add(StrokePoint(Vec3(1.0 + i * 0.1, 0.0, 0.0)))
                    it.pts.add(StrokePoint(Vec3(1.0 + i * 0.1, 0.1, 0.0)))
                    it.group = b.id
                    sketch.add(it)
                }
            }
        }
    }

    // ---- visibility ------------------------------------------------------

    @Test
    fun `hiding a group takes its curves out of what can be drawn`() {
        /* THE FAULT THIS FILE WAS WRITTEN FOR. `editable()` is what the
           renderer is handed, and it was never asked — the app passed every
           stroke, so a hidden group dimmed its row and stayed on screen. */
        val f = Fixture()
        assertEquals(4, f.sketch.editable().size)
        f.a.visible = false
        assertEquals(2, f.sketch.editable().size, "a hidden group was still drawable")
        assertTrue(f.sketch.editable().none { it.group == f.a.id })
    }

    @Test
    fun `hiding a group drops its curves from the selection`() {
        /* `S.applyVisibility`: a curve nobody can see is not selected. The
           joystick would otherwise go on driving it and the selection bar go
           on offering to delete it. */
        val f = Fixture()
        f.sketch.selectOnly(f.sketch.strokes)
        assertEquals(4, f.sketch.selection.size)

        f.a.visible = false
        assertEquals(2, f.sketch.dropHiddenFromSelection())
        assertEquals(2, f.sketch.selection.size)
        assertTrue(f.sketch.selection.none { it.group == f.a.id })
        // and the flag on the stroke itself, which is what the shader reads
        assertTrue(f.inA.none { it.selected }, "a hidden curve still says it is selected")
    }

    @Test
    fun `dropping hidden curves twice is not a second change`() {
        val f = Fixture()
        f.sketch.selectOnly(f.sketch.strokes)
        f.a.visible = false
        assertEquals(2, f.sketch.dropHiddenFromSelection())
        assertEquals(0, f.sketch.dropHiddenFromSelection(), "it reported a change twice")
    }

    @Test
    fun `showing a group again does not put the selection back`() {
        /* Deliberate: the selection is a thing you made, and reappearing
           curves silently rejoining it would be a selection nobody chose. */
        val f = Fixture()
        f.sketch.selectOnly(f.sketch.strokes)
        f.a.visible = false
        f.sketch.dropHiddenFromSelection()
        f.a.visible = true
        assertEquals(2, f.sketch.selection.size)
    }

    @Test
    fun `every reader agrees a hidden curve is gone`() {
        val f = Fixture()
        f.a.visible = false
        for (s in f.inA) assertTrue(!f.sketch.visible(s), "visible() disagreed")
        // the framing bounds, which decide where Home takes you
        val b = Bounds.of(f.sketch)
        assertTrue(b.minX > 0.5, "a hidden group was still framed: minX ${b.minX}")
    }

    @Test
    fun `a curve in no group is visible`() {
        val s = strokeAt(0.0)
        val sketch = Sketch()
        sketch.add(s)
        assertTrue(sketch.visible(s))
        assertEquals(1, sketch.editable().size)
    }

    // ---- the active group ------------------------------------------------

    @Test
    fun `there is always a group and always an active one`() {
        val sketch = Sketch()
        sketch.add(strokeAt(0.0))
        val g = sketch.ensureGroup()
        assertEquals(1, sketch.groups.size)
        assertEquals(g.id, sketch.activeGroup)
        // and anything drawn before there were groups joins the first
        assertEquals(g.id, sketch.strokes[0].group)
    }

    @Test
    fun `setting an active group that does not exist falls back`() {
        /* the fallback is the group at the TOP of the list, which is the one
           most recently made — B here, since A was made first and B unshifted
           in front of it */
        val f = Fixture()
        f.sketch.setActiveGroup(9999)
        assertEquals(null, f.sketch.activeGroup)
        assertEquals(f.b.id, f.sketch.ensureGroup().id)
    }

    @Test
    fun `the active group survives a save and a reopen`() {
        /* It was written as the FIRST group and never read back, so reopening
           a sketch dropped you into Group 1 however long you had been working
           in another — and the next stroke went in there. */
        val f = Fixture()
        f.sketch.setActiveGroup(f.b.id)

        val text = Document.toJsonText(f.sketch, GuideScene(), Camera())
        val back = Sketch()
        Document.restore(text, back, GuideScene(), Camera())

        val active = assertNotNull(back.groupById(back.activeGroup))
        assertEquals("B", active.name, "the active group came back as ${active.name}")
    }

    @Test
    fun `a hidden group is still hidden when the file is reopened`() {
        val f = Fixture()
        f.a.visible = false
        val text = Document.toJsonText(f.sketch, GuideScene(), Camera())
        val back = Sketch()
        Document.restore(text, back, GuideScene(), Camera())

        assertEquals(2, back.groups.size)
        assertEquals(2, back.editable().size, "visibility did not survive the file")
    }

    // ---- creating, duplicating and deleting -------------------------------

    @Test
    fun `deleting a group hands back its curves for the caller to remove`() {
        /* The reference deletes them WITH the group, as a layer does, and its
           own note gives the reason no confirmation is needed: undo puts both
           back. deleteGroup itself only reports which they were — the caller
           owns the removal, because the caller is the one holding the history
           step that has to put them back. */
        val f = Fixture()
        val members = f.sketch.deleteGroup(f.a)
        assertEquals(2, members.size)
        assertEquals(f.inA, members)
        assertEquals(1, f.sketch.groups.size)
    }

    @Test
    fun `deleting a group and its curves is undoable to exactly what was there`() {
        val f = Fixture()
        val at = f.sketch.indexOfGroup(f.a)
        val order = f.inA.map { f.sketch.indexOf(it) }
        val was = f.sketch.strokes.toList()

        val members = f.sketch.deleteGroup(f.a)
        for (m in members) f.sketch.remove(m)
        assertEquals(2, f.sketch.strokes.size)

        f.sketch.restoreGroup(f.a, at)
        for (i in members.indices) f.sketch.addAt(order[i], members[i])
        assertEquals(was, f.sketch.strokes, "the curves came back in a different order")
        assertEquals(4, f.sketch.editable().size)
    }

    @Test
    fun `a deleted group goes back on the row it came from`() {
        /* What undo needs. restoreGroup takes an index and the caller was not
           passing one, so an undone delete sent the group to the bottom of the
           list — which does not read as an undo. */
        /* a new group goes on TOP, so B then A then C reads C, B, A */
        val f = Fixture()
        f.sketch.newGroup("C")
        assertEquals(listOf("C", "B", "A"), f.sketch.groups.map { it.name })

        val at = f.sketch.indexOfGroup(f.b)
        assertEquals(1, at)
        f.sketch.deleteGroup(f.b)
        f.sketch.restoreGroup(f.b, at)
        assertEquals(listOf("C", "B", "A"), f.sketch.groups.map { it.name })
    }

    @Test
    fun `restoring a group that is already there changes nothing`() {
        val f = Fixture()
        f.sketch.restoreGroup(f.a, 0)
        assertEquals(2, f.sketch.groups.size)
    }

    @Test
    fun `a deleted and restored group is the same group`() {
        /* Ids have to survive, or the curves a redo re-points at it no longer
           find it. */
        val f = Fixture()
        val id = f.a.id
        val at = f.sketch.indexOfGroup(f.a)
        f.sketch.deleteGroup(f.a)
        f.sketch.restoreGroup(f.a, at)
        for (s in f.inA) s.group = id
        assertEquals(2, f.sketch.membersOf(id).size)
        assertEquals(4, f.sketch.editable().size)
    }

    @Test
    fun `duplicating a group copies its curves and its visibility`() {
        val f = Fixture()
        f.a.visible = false
        val (copy, copies) = f.sketch.duplicateGroup(f.a)
        assertEquals(2, copies.size)
        assertTrue(!copy.visible, "the copy came back visible")
        assertTrue(copies.all { it.group == copy.id })
        // the originals are untouched
        assertEquals(2, f.sketch.membersOf(f.a.id).size)
    }

    @Test
    fun `a duplicated group lands next to what it was copied from`() {
        /* Draw order decides who is on top, so a copy belongs beside its
           original rather than at the end of the document. */
        val f = Fixture()
        val (copy, _) = f.sketch.duplicateGroup(f.a)
        val order = f.sketch.strokes.map { it.group }
        assertEquals(
            listOf(f.a.id, f.a.id, copy.id, copy.id, f.b.id, f.b.id),
            order,
            "the copy did not land beside its original",
        )
    }

    @Test
    fun `an undone duplicate leaves no group behind`() {
        /* The app made the copy OUTSIDE its history step, so undo took the
           curves away and left an empty group on the panel. */
        val f = Fixture()
        val (copy, copies) = f.sketch.duplicateGroup(f.a)
        assertEquals(3, f.sketch.groups.size)

        for (c in copies) f.sketch.remove(c)
        f.sketch.deleteGroup(copy)
        assertEquals(2, f.sketch.groups.size, "an undone duplicate left a group")
        assertEquals(4, f.sketch.strokes.size)
    }

    @Test
    fun `an undone new group leaves no group behind`() {
        val f = Fixture()
        val g = f.sketch.newGroup("C")
        val at = f.sketch.indexOfGroup(g)
        f.sketch.deleteGroup(g)
        assertEquals(2, f.sketch.groups.size, "an undone new group stayed")
        // and redoing it puts the same group back where it was
        f.sketch.restoreGroup(g, at)
        assertEquals(listOf("C", "B", "A"), f.sketch.groups.map { it.name })
    }

    // ---- assignment -------------------------------------------------------

    @Test
    fun `assigning a curve moves it between groups`() {
        val f = Fixture()
        f.sketch.assign(f.inA[0], f.b)
        assertEquals(1, f.sketch.membersOf(f.a.id).size)
        assertEquals(3, f.sketch.membersOf(f.b.id).size)
    }

    @Test
    fun `assigning to null takes a curve out of every group`() {
        val f = Fixture()
        f.sketch.assign(f.inA[0], null)
        assertEquals(1, f.sketch.membersOf(f.a.id).size)
        assertTrue(f.sketch.visible(f.inA[0]), "an ungrouped curve became invisible")
    }

    @Test
    fun `moving a curve into a hidden group hides it`() {
        val f = Fixture()
        f.b.visible = false
        f.sketch.assign(f.inA[0], f.b)
        assertTrue(!f.sketch.visible(f.inA[0]))
        assertEquals(1, f.sketch.editable().size)
    }

    @Test
    fun `members come back in draw order`() {
        val f = Fixture()
        assertEquals(f.inA, f.sketch.membersOf(f.a.id))
    }

    @Test
    fun `a new group goes on top of the list`() {
        /* `S.groups.unshift(g)` — the newest layer is the one you are working
           in, and ensureGroup's fallback picks index 0 for the same reason.
           This build appended, which put every new group at the bottom of a
           panel you then had to scroll. */
        val f = Fixture()
        assertEquals(listOf("B", "A"), f.sketch.groups.map { it.name })
        f.sketch.newGroup("C")
        assertEquals(listOf("C", "B", "A"), f.sketch.groups.map { it.name })
    }

    @Test
    fun `a duplicate sits beside its original in the panel too`() {
        val f = Fixture()
        val (copy, _) = f.sketch.duplicateGroup(f.a)
        val names = f.sketch.groups.map { it.name }
        assertEquals(
            names.indexOf("A"), names.indexOf(copy.name) + 1,
            "the copy did not land beside its original: $names",
        )
    }

    @Test
    fun `the group list comes back in the order it was saved`() {
        /* A new group unshifts, but RESTORING a file is not creating groups —
           it is reproducing a list that already has an order. Unshifting each
           one in turn reversed the whole panel on every save-and-open cycle. */
        val f = Fixture()
        f.sketch.newGroup("C")
        val order = f.sketch.groups.map { it.name }

        val text = Document.toJsonText(f.sketch, GuideScene(), Camera())
        val back = Sketch()
        Document.restore(text, back, GuideScene(), Camera())
        assertEquals(order, back.groups.map { it.name }, "the panel came back reversed")
    }

    @Test
    fun `ensureGroup only adopts loose curves when there are no groups at all`() {
        /* Worth stating outright, because it is easy to read the adoption as a
           general tidy-up that catches anything ungrouped on every refresh. It
           is not: it runs ONCE, when the first group is made. A curve that
           goes ungrouped after that stays ungrouped — outside every count,
           unselectable by a group, and unhideable by any row in the panel.
           That is why nothing is allowed to make one. */
        val f = Fixture()
        val loose = strokeAt(5.0)
        f.sketch.add(loose)
        f.sketch.ensureGroup()
        assertEquals(null, loose.group, "a loose curve was adopted after the fact")
        assertTrue(f.sketch.visible(loose), "and it is visible, so it just floats")

        // whereas the first group ever made does adopt what came before it
        val fresh = Sketch()
        val early = strokeAt(0.0)
        fresh.add(early)
        val g = fresh.ensureGroup()
        assertEquals(g.id, early.group)
    }

    @Test
    fun `a group can be opened and undone without taking the work in it`() {
        /* What importing a reference needs. The import opens a group named
           after the file so the tracing has somewhere to live, and undoing the
           IMPORT must not delete the tracing — those are two different
           actions, and only one of them was asked for. */
        val f = Fixture()
        val g = f.sketch.newGroup("model.obj")
        val at = f.sketch.indexOfGroup(g)
        val previous = f.sketch.activeGroup
        f.sketch.setActiveGroup(g.id)

        // some tracing goes in
        val traced = strokeAt(9.0).also { it.group = g.id; f.sketch.add(it) }
        assertEquals(1, f.sketch.membersOf(g.id).size)

        // undo of the import: the group goes, the curves stay
        for (st in f.sketch.membersOf(g.id)) st.group = previous
        f.sketch.deleteGroup(g)
        f.sketch.setActiveGroup(previous)

        assertEquals(2, f.sketch.groups.size, "the imported group stayed")
        assertTrue(f.sketch.strokes.contains(traced), "undoing an import ate the tracing")
        assertTrue(f.sketch.visible(traced), "the tracing came back invisible")

        // and a redo puts the same group back on the row it had
        f.sketch.restoreGroup(g, at)
        assertEquals("model.obj", f.sketch.groups[at].name)
    }

    @Test
    fun `removing a curve takes it out of the selection too`() {
        val f = Fixture()
        f.sketch.selectOnly(f.inA)
        f.sketch.remove(f.inA[0])
        assertEquals(1, f.sketch.selection.size)
    }

    // ======================================================================
    // Membership, the active group and duplication.
    //
    // These were written first and lived in ColorTest.kt, which is most of the
    // reason the faults above went unnoticed: a group suite nobody can find by
    // its filename is a group suite nobody extends.
    // ======================================================================


    private fun sketchOf(n: Int): Sketch {
        val sk = Sketch()
        repeat(n) {
            val s = Stroke()
            s.pts.add(StrokePoint(Vec3(it.toDouble(), 0.0, 0.0)))
            s.pts.add(StrokePoint(Vec3(it.toDouble(), 1.0, 0.0)))
            sk.add(s)
        }
        return sk
    }

    /**
     * A sketch nobody has organised still has a row to show. "Ungrouped" as a
     * special case that behaves almost but not quite like a group is worse
     * than a group called Group 1.
     */
    @Test
    fun `ensureGroup adopts everything drawn before there were groups`() {
        val sk = sketchOf(3)
        assertTrue(sk.groups.isEmpty())
        val g = sk.ensureGroup()
        assertEquals(1, sk.groups.size)
        assertEquals(g.id, sk.activeGroup)
        assertEquals(3, sk.membersOf(g.id).size, "the existing curves joined it")
    }

    @Test
    fun `ensureGroup is idempotent and repairs a dangling active id`() {
        val sk = sketchOf(1)
        val a = sk.ensureGroup()
        assertEquals(a.id, sk.ensureGroup().id)

        val b = sk.newGroup("B")
        sk.setActiveGroup(b.id)
        sk.deleteGroup(b)
        // active now points at a group that is gone; ensureGroup must not
        // leave it there, or a new curve joins nothing
        assertEquals(a.id, sk.ensureGroup().id, "the only group left is the fallback")
    }

    @Test
    fun `setActiveGroup refuses an id that is not a group`() {
        val sk = sketchOf(1)
        val g = sk.ensureGroup()
        sk.setActiveGroup(9999)
        assertEquals(null, sk.activeGroup, "a bad id clears rather than sticks")
        sk.setActiveGroup(g.id)
        assertEquals(g.id, sk.activeGroup)
    }

    /**
     * The copies land next to the originals rather than at the end of the
     * document, because draw order is what decides who is on top: a duplicate
     * that jumps to the front is a duplicate that looks different.
     */
    @Test
    fun `a duplicated group keeps its place in draw order`() {
        val sk = sketchOf(4)
        val g = sk.ensureGroup()
        val other = sk.newGroup("Other")
        sk.assign(sk.strokes[3], other)

        val (copy, copies) = sk.duplicateGroup(g)
        assertEquals(3, copies.size)
        assertEquals("Group 1 copy", copy.name)
        assertEquals(3, sk.membersOf(copy.id).size)
        assertEquals(3, sk.membersOf(g.id).size, "the originals are untouched")

        // the copies sit immediately after the last original, before `other`
        val lastOriginal = sk.strokes.indexOf(sk.membersOf(g.id).last())
        assertEquals(lastOriginal + 1, sk.strokes.indexOf(copies[0]))
        assertTrue(
            sk.strokes.indexOf(copies.last()) < sk.strokes.indexOf(sk.membersOf(other.id)[0]),
            "the copies went in ahead of the group that followed",
        )
    }

    @Test
    fun `a duplicated group carries its hidden state`() {
        val sk = sketchOf(2)
        val g = sk.ensureGroup()
        g.visible = false
        val (copy, copies) = sk.duplicateGroup(g)
        assertFalse(copy.visible)
        for (c in copies) assertFalse(sk.visible(c), "a copy of hidden work is hidden")
    }

    /**
     * Deleting a group frees its curves rather than taking them with it.
     * Removing a folder should not remove the work in it, and there is no undo
     * prompt that makes the other reading safe.
     */
    @Test
    fun `deleting a group reports the curves it held`() {
        /* Was `deleting a group keeps the curves`, and it asserted the reading
           this build had wrong: that delete frees its members and leaves them
           in the drawing. It does not — it names them, and the caller removes
           them inside the same history step. */
        val sk = sketchOf(3)
        val g = sk.ensureGroup()
        val members = sk.deleteGroup(g)
        assertEquals(3, members.size)
        assertEquals(0, sk.groups.size)
        for (s in members) sk.remove(s)
        assertEquals(0, sk.strokes.size)
    }
}
