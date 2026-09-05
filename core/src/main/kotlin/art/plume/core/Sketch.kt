package art.plume.core

/**
 * A named set of curves that hide and show together.
 *
 * FACT (C.8): groups can be created, renamed, hidden and deleted, and a curve
 * can be assigned to one.
 */
class StrokeGroup(val id: Int, var name: String, var visible: Boolean = true) {

    /**
     * How strongly the whole group draws, 0 to 1.
     *
     * Separate from visibility rather than a special case of it: hiding takes
     * a group out of the drawing — out of the render list, out of selection —
     * while fading LEAVES IT THERE to be worked against. That is what a
     * reference layer is, and it is the reason a slider is worth having next
     * to a switch that can already reach zero.
     */
    var opacity: Double = 1.0

    companion object {
        private var counter = 0
        fun freshId(): Int = ++counter
    }
}

/**
 * The document: every curve, the groups they belong to, and what is selected.
 *
 * The web build keeps this as `S.list` plus a scene graph. Here it is a plain
 * object so that every editing tool can be exercised on a JVM — which matters
 * more for this phase than any other, because erase and liquify are the tools
 * that can silently destroy someone's drawing.
 */
class Sketch {

    private val list = ArrayList<Stroke>()
    private val groupList = ArrayList<StrokeGroup>()

    /** Selection order is kept: duplicate and loft both care which came first. */
    private val selectionSet = LinkedHashSet<Stroke>()

    val strokes: List<Stroke> get() = list
    val groups: List<StrokeGroup> get() = groupList
    val selection: List<Stroke> get() = selectionSet.toList()

    // ---- curves ---------------------------------------------------------

    fun add(s: Stroke) { list.add(s) }

    fun addAt(index: Int, s: Stroke) { list.add(clamp(index, 0, list.size), s) }

    fun remove(s: Stroke): Int {
        val at = list.indexOf(s)
        if (at >= 0) list.removeAt(at)
        selectionSet.remove(s)
        return at
    }

    fun indexOf(s: Stroke): Int = list.indexOf(s)

    fun clear() { list.clear(); selectionSet.clear() }

    fun byId(id: Int): Stroke? = list.firstOrNull { it.id == id }

    // ---- groups ---------------------------------------------------------

    /**
     * Where a new group goes, when the caller does not say.
     *
     * FACT: "The new group will be created directly above the current active
     * group." Not at the top of the list — which is what this did, and which
     * puts the group you just made a long way from the one you were working
     * in as soon as there are more than two or three.
     */
    fun indexAboveActive(): Int {
        val at = groupList.indexOfFirst { it.id == activeGroup }
        return if (at < 0) 0 else at
    }

    /**
     * Make a group and put it at [at], the head of the list by default.
     *
     * The default is for the callers with nowhere in mind — the first group of
     * an empty sketch, a restore that places each one itself. Making a group
     * from the panel passes [indexAboveActive], which is where Feather puts
     * it; the two used to be the same thing and the documentation says they
     * are not.
     */
    fun newGroup(name: String, at: Int = 0): StrokeGroup =
        StrokeGroup(StrokeGroup.freshId(), name).also {
            groupList.add(clamp(at, 0, groupList.size), it)
        }

    fun groupById(id: Int?): StrokeGroup? =
        if (id == null) null else groupList.firstOrNull { it.id == id }

    /**
     * Delete a group, and hand back the curves that were in it.
     *
     * THE CURVES ARE NOT REMOVED HERE — the caller decides what happens to
     * them. Two readings are possible and this build had the wrong one: it
     * used to null out every member's group and leave the curves in the
     * drawing, on the argument that removing a folder should not remove the
     * work in it.
     *
     * The reference deletes them, as a layer does, and its own note gives the
     * reason the cautious reading is not needed: "undo puts both back — which
     * is the reason this is not behind a confirmation dialog". Freeing them
     * was also worse than it looked, because [ensureGroup] adopts anything
     * ungrouped on the next refresh: deleting a group quietly MOVED all of its
     * work into another one.
     */
    fun deleteGroup(g: StrokeGroup): List<Stroke> {
        val members = list.filter { it.group == g.id }
        groupList.remove(g)
        return members
    }

    fun assign(s: Stroke, g: StrokeGroup?) { s.group = g?.id }

    /**
     * Put a deleted group back where it was, for undo. Its curves are
     * re-pointed at it by the caller, which is the only party that still knows
     * which ones they were.
     */
    fun restoreGroup(g: StrokeGroup, at: Int = groupList.size) {
        if (groupList.any { it.id == g.id }) return
        groupList.add(clamp(at, 0, groupList.size), g)
    }

    /** Where a group sits in the list, so an undo can put it back there. */
    fun indexOfGroup(g: StrokeGroup): Int = groupList.indexOf(g)

    /**
     * A CURVE NOBODY CAN SEE IS NOT SELECTED.
     *
     * `S.applyVisibility`, which deselects anything that has just become
     * invisible. Without it, hiding a group left its curves in the selection:
     * the joystick went on driving them, the selection bar went on offering to
     * duplicate and delete them, and the count in the corner claimed a number
     * of curves that were not on screen.
     *
     * @return how many were dropped, so a caller can tell whether anything
     *   changed without diffing the selection itself.
     */
    fun dropHiddenFromSelection(): Int {
        val gone = selectionSet.filter { !visible(it) }
        for (s in gone) { s.selected = false; selectionSet.remove(s) }
        return gone.size
    }

    /** Every curve in a group, in draw order. */
    fun membersOf(id: Int?): List<Stroke> = list.filter { it.group == id }

    /**
     * The group new curves are drawn into.
     *
     * Null means the default group, which [ensureGroup] guarantees exists —
     * the panel needs a row to show even for a sketch nobody has organised,
     * and "ungrouped" as a special case that behaves almost but not quite like
     * a group is worse than a group called Group 1.
     */
    var activeGroup: Int? = null
        private set

    fun setActiveGroup(id: Int?) {
        activeGroup = if (id != null && groupById(id) != null) id else null
    }

    /** There is always at least one group, and always an active one. */
    fun ensureGroup(): StrokeGroup {
        if (groupList.isEmpty()) {
            val g = newGroup("Group 1")
            /* everything drawn before there were groups belongs to the first */
            for (s in list) if (s.group == null) s.group = g.id
        }
        val active = groupById(activeGroup)
        if (active == null) activeGroup = groupList[0].id
        return groupById(activeGroup)!!
    }

    /**
     * A copy of the group and of every curve in it.
     *
     * The copies go in AFTER the originals rather than at the end of the
     * document, so a duplicated group stays next to what it was copied from in
     * draw order — which is what decides who is on top.
     */
    fun duplicateGroup(g: StrokeGroup): Pair<StrokeGroup, List<Stroke>> {
        /* beside the original in the PANEL as well as in draw order: a copy
           that jumps to the top of the list is a copy you have to hunt for */
        val copy = newGroup(g.name + " copy", indexOfGroup(g).coerceAtLeast(0))
        copy.visible = g.visible
        val members = membersOf(g.id)
        val copies = members.map { m -> m.copyStroke().also { c -> c.group = copy.id } }
        var at = members.lastOrNull()?.let { list.indexOf(it) + 1 } ?: list.size
        for (c in copies) { list.add(clamp(at, 0, list.size), c); at++ }
        return copy to copies
    }

    /**
     * The group being looked at alone, or null when all of them are.
     *
     * FACT: "Tap and hold the eyeball icon on the far right to isolate the
     * group. When a group is isolated, only the curves within that group are
     * visible. Tap another group to isolate and view only its curves. Tap and
     * hold the eyeball icon again to exit isolation."
     *
     * Separate from each group's own [StrokeGroup.visible] flag, and it has to
     * be: isolation is a way of LOOKING at the drawing that you back out of,
     * and doing it by hiding every other group would turn one held tap into a
     * dozen hidden groups you then have to unhide by hand.
     */
    var isolatedGroup: Int? = null

    /** A curve is visible unless its group is hidden, or another is isolated. */
    fun visible(s: Stroke): Boolean {
        isolatedGroup?.let { return s.group == it }
        return groupById(s.group)?.visible ?: true
    }

    /** Everything a tool is allowed to touch: visible curves, in draw order. */
    fun editable(): List<Stroke> = list.filter { visible(it) }

    // ---- selection -------------------------------------------------------

    fun setSelected(s: Stroke, on: Boolean) {
        s.selected = on
        if (on) selectionSet.add(s) else selectionSet.remove(s)
    }

    fun clearSelection() {
        for (s in selectionSet) s.selected = false
        selectionSet.clear()
    }

    fun isSelected(s: Stroke): Boolean = selectionSet.contains(s)

    fun selectOnly(items: Collection<Stroke>) {
        clearSelection()
        for (s in items) setSelected(s, true)
    }
}
