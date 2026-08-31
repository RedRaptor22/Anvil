package art.plume.core

/**
 * A named set of curves that hide and show together.
 *
 * FACT (C.8): groups can be created, renamed, hidden and deleted, and a curve
 * can be assigned to one.
 */
class StrokeGroup(val id: Int, var name: String, var visible: Boolean = true) {
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

    fun newGroup(name: String): StrokeGroup =
        StrokeGroup(StrokeGroup.freshId(), name).also { groupList.add(it) }

    fun groupById(id: Int?): StrokeGroup? =
        if (id == null) null else groupList.firstOrNull { it.id == id }

    /**
     * Delete a group. The curves in it are NOT deleted — they come out of the
     * group and stay in the drawing. Removing a folder should not remove the
     * work in it, and there is no undo prompt that makes the other reading
     * safe.
     */
    fun deleteGroup(g: StrokeGroup): List<Stroke> {
        val freed = list.filter { it.group == g.id }
        for (s in freed) s.group = null
        groupList.remove(g)
        return freed
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
        val copy = newGroup(g.name + " copy")
        copy.visible = g.visible
        val members = membersOf(g.id)
        val copies = members.map { m -> m.copyStroke().also { c -> c.group = copy.id } }
        var at = members.lastOrNull()?.let { list.indexOf(it) + 1 } ?: list.size
        for (c in copies) { list.add(clamp(at, 0, list.size), c); at++ }
        return copy to copies
    }

    /** A curve is visible unless the group holding it is hidden. */
    fun visible(s: Stroke): Boolean = groupById(s.group)?.visible ?: true

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
