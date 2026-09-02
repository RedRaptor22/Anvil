package art.plume.core

/**
 * The scene: what has been drawn, how it is grouped, and what is selected.
 *
 * Anvil had no model layer — `MainActivity` kept an `ArrayList<Stroke>` that
 * doubled as the scene and the undo stack. Groups need somewhere to live that
 * is not a screen, so this is it, and it follows the module's rule: no Android
 * types, so the JVM tests can drive the whole thing.
 *
 * ## Visibility is DERIVED, never mirrored
 *
 * The single most important decision here. The web build keeps a `visible`
 * flag on a guide AND separately parents its object into the scene graph, and
 * every visibility bug it has is the two disagreeing: a toggle that flips the
 * flag but skips the removal, a reload that lists a guide without putting it
 * back on screen, an activation that shows something the flag still calls
 * hidden. There is one flag here, on the group, and everything on screen is a
 * question asked of it ([isVisible], [visibleStrokes]) rather than a second
 * copy kept in step by hand. A toggle cannot fail to take effect because
 * there is nothing else to update.
 *
 * ## A group is all-or-nothing
 *
 * Selecting one member selects the group; grouping or ungrouping part of a
 * group widens to the whole of it. Half a group is not a state any operation
 * here can leave behind, so no caller has to remember to widen first.
 */

/** A named, hideable set of strokes. Membership is held by [Sketch]. */
class Group(val id: Int, var name: String, var visible: Boolean = true)

class Sketch {

    private val _strokes = ArrayList<Stroke>()
    private val _groups = ArrayList<Group>()
    private val _selection = LinkedHashSet<Stroke>()

    /** Membership, one way round only, so a stroke can never be in two groups. */
    private val memberOf = HashMap<Stroke, Group>()

    private var nextGroupId = 1

    val strokes: List<Stroke> get() = _strokes
    val groups: List<Group> get() = _groups
    val selection: Set<Stroke> get() = _selection

    // ---- membership -----------------------------------------------------

    fun groupOf(stroke: Stroke): Group? = memberOf[stroke]

    fun membersOf(group: Group): List<Stroke> = _strokes.filter { memberOf[it] === group }

    /**
     * A stroke is visible unless the group holding it is hidden. An ungrouped
     * stroke has nothing that could hide it, so it always shows.
     */
    fun isVisible(stroke: Stroke): Boolean = memberOf[stroke]?.visible ?: true

    fun visibleStrokes(): List<Stroke> = _strokes.filter { isVisible(it) }

    /** Every stroke of every group the given strokes touch, plus the loose ones. */
    fun wholeGroups(of: Collection<Stroke>): List<Stroke> {
        val gs = of.mapNotNullTo(HashSet()) { memberOf[it] }
        if (gs.isEmpty()) return of.toList()
        val out = LinkedHashSet(of)
        for (s in _strokes) if (memberOf[s] in gs) out.add(s)
        return out.toList()
    }

    // ---- selection ------------------------------------------------------

    /**
     * Select or deselect, taking the whole group with it.
     *
     * A hidden stroke cannot be selected: you cannot see what you would be
     * acting on, and a selection you cannot see is how a transform lands on
     * geometry the user forgot was there.
     */
    fun setSelected(stroke: Stroke, on: Boolean) {
        if (on && !isVisible(stroke)) return
        for (s in wholeGroups(listOf(stroke))) {
            if (on) _selection.add(s) else _selection.remove(s)
        }
    }

    fun clearSelection() = _selection.clear()

    fun selectAll() {
        _selection.clear()
        _selection.addAll(visibleStrokes())
    }

    // ---- edits ----------------------------------------------------------

    /**
     * A reversible change. Every mutation the UI can make is one of these, so
     * undo is a property of the model rather than something each call site
     * remembers to record.
     */
    interface Edit {
        val label: String
        fun apply()
        fun revert()
    }

    private class Steps(
        override val label: String,
        val forward: () -> Unit,
        val back: () -> Unit,
    ) : Edit {
        override fun apply() = forward()
        override fun revert() = back()
    }

    /** Runs [edit] and returns it, ready for an [EditStack]. */
    fun run(edit: Edit): Edit { edit.apply(); return edit }

    fun addStroke(stroke: Stroke): Edit = run(Steps("draw",
        forward = { if (stroke !in _strokes) _strokes.add(stroke) },
        back = { detach(stroke); _strokes.remove(stroke); _selection.remove(stroke) },
    ))

    /**
     * Group the selection.
     *
     * Widens to whole groups first, so a partial selection folds the groups it
     * touches into the new one entirely instead of tearing strays out of them,
     * and the members it pulls in join the selection so the screen agrees with
     * what just happened.
     */
    fun groupSelection(name: String? = null): Edit? {
        val members = wholeGroups(_selection)
        if (members.size < 2) return null
        val before = members.associateWith { memberOf[it] }
        val hadSelected = _selection.toList()
        val group = Group(nextGroupId++, name ?: "Group ${_groups.size + 1}")
        return run(Steps("group",
            forward = {
                _groups.add(group)
                for (s in members) attach(s, group)
                _selection.clear(); _selection.addAll(members)
                pruneEmpty()
            },
            back = {
                for (s in members) before[s]?.let { attach(s, it) } ?: detach(s)
                _groups.remove(group)
                restoreGroupsOf(before.values)
                _selection.clear(); _selection.addAll(hadSelected)
            },
        ))
    }

    /** Ungroup every group the selection touches, whole. */
    fun ungroupSelection(): Edit? {
        val members = wholeGroups(_selection).filter { memberOf[it] != null }
        if (members.isEmpty()) return null
        val before = members.associateWith { memberOf.getValue(it) }
        val hadSelected = _selection.toList()
        val emptied = before.values.toSet().toList()
        return run(Steps("ungroup",
            forward = {
                for (s in members) detach(s)
                pruneEmpty()
            },
            back = {
                restoreGroupsOf(emptied)
                for (s in members) attach(s, before.getValue(s))
                _selection.clear(); _selection.addAll(hadSelected)
            },
        ))
    }

    /**
     * Show or hide a group.
     *
     * Hiding drops its members from the selection. Leaving them selected is
     * how the web build ends up with a joystick pointed at geometry nobody can
     * see; and since [setSelected] refuses to select a hidden stroke, keeping
     * them would also be a state the user could not reach any other way.
     */
    fun setVisible(group: Group, on: Boolean): Edit {
        val was = group.visible
        val hadSelected = _selection.toList()
        return run(Steps(if (on) "show group" else "hide group",
            forward = {
                group.visible = on
                if (!on) _selection.removeAll(membersOf(group).toSet())
            },
            back = {
                group.visible = was
                _selection.clear(); _selection.addAll(hadSelected)
            },
        ))
    }

    /**
     * Move the selection into an existing group.
     *
     * Widens to whole groups like everything else, so assigning one member of
     * another group moves that whole group across rather than splitting it.
     * Assigning into the group the selection already sits in does nothing —
     * and returns null rather than an edit that undoes to the same state.
     */
    fun assignSelectionTo(group: Group): Edit? {
        if (group !in _groups) return null
        val moving = wholeGroups(_selection).filter { memberOf[it] !== group }
        if (moving.isEmpty()) return null
        val before = moving.associateWith { memberOf[it] }
        val emptied = before.values.filterNotNull().toSet().toList()
        val wasVisible = group.visible
        return run(Steps("assign to group",
            forward = {
                for (s in moving) attach(s, group)
                /* Moving strokes into a hidden group would make them vanish on
                   an action whose name says nothing about visibility. Show it
                   instead — the eye is how you hide things. */
                group.visible = true
                pruneEmpty()
            },
            back = {
                restoreGroupsOf(emptied)
                for (s in moving) before[s]?.let { attach(s, it) } ?: detach(s)
                group.visible = wasVisible
                pruneEmpty()
            },
        ))
    }

    fun renameGroup(group: Group, name: String): Edit {
        val was = group.name
        return run(Steps("rename group",
            forward = { group.name = name },
            back = { group.name = was },
        ))
    }

    /**
     * Copy the selection.
     *
     * The copies get ONE group of their own rather than inheriting the
     * source's id. Inheriting it — which is what the web build does — quietly
     * enrols the copies in the original group, so the next tap on an original
     * drags the copies along and the two can never be moved apart again. A
     * duplicated group stays a group; duplicated loose strokes stay loose.
     */
    fun duplicateSelection(offset: Vec3): Edit? {
        val sources = wholeGroups(_selection)
        if (sources.isEmpty()) return null
        val copies = sources.map { copyOf(it, offset) }
        val hadSelected = _selection.toList()
        val fresh = HashMap<Group, Group>()
        for (src in sources) {
            val g = memberOf[src] ?: continue
            fresh.getOrPut(g) { Group(nextGroupId++, "${g.name} copy", g.visible) }
        }
        return run(Steps("duplicate",
            forward = {
                for (g in fresh.values) if (g !in _groups) _groups.add(g)
                _selection.clear()
                for ((i, c) in copies.withIndex()) {
                    if (c !in _strokes) _strokes.add(c)
                    memberOf[sources[i]]?.let { attach(c, fresh.getValue(it)) }
                    _selection.add(c)
                }
            },
            back = {
                for (c in copies) { detach(c); _strokes.remove(c); _selection.remove(c) }
                _groups.removeAll(fresh.values.toSet())
                _selection.clear(); _selection.addAll(hadSelected)
            },
        ))
    }

    /** Delete the selection, whole groups included. */
    fun deleteSelection(): Edit? {
        val doomed = wholeGroups(_selection)
        if (doomed.isEmpty()) return null
        val at = doomed.associateWith { _strokes.indexOf(it) }
        val before = doomed.associateWith { memberOf[it] }
        val hadGroups = _groups.toList()
        return run(Steps("delete",
            forward = {
                for (s in doomed) { detach(s); _strokes.remove(s); _selection.remove(s) }
                pruneEmpty()
            },
            back = {
                _groups.clear(); _groups.addAll(hadGroups)
                for (s in doomed.sortedBy { at.getValue(it) }) {
                    val i = at.getValue(s).coerceIn(0, _strokes.size)
                    _strokes.add(i, s)
                    before[s]?.let { attach(s, it) }
                }
                _selection.clear(); _selection.addAll(doomed)
            },
        ))
    }

    fun clear() {
        _strokes.clear(); _groups.clear(); _selection.clear(); memberOf.clear()
    }

    // ---- picking --------------------------------------------------------

    /**
     * The nearest stroke along a ray, or null.
     *
     * Only visible strokes are candidates: a hidden group must not catch a tap
     * meant for what is behind it, which is the same rule the web build states
     * for its guide mask and then only applies to erasing.
     */
    fun pick(origin: Vec3, dir: Vec3, slack: Double = 0.0): Stroke? {
        var best: Stroke? = null
        var bestT = Double.MAX_VALUE
        for (s in _strokes) {
            if (!isVisible(s)) continue
            val hit = Picking.rayHit(s, origin, dir, slack) ?: continue
            if (hit < bestT) { bestT = hit; best = s }
        }
        return best
    }

    // ---- internals ------------------------------------------------------

    private fun attach(stroke: Stroke, group: Group) {
        if (group !in _groups) _groups.add(group)
        memberOf[stroke] = group
    }

    private fun detach(stroke: Stroke) { memberOf.remove(stroke) }

    /** Put back groups an undo needs, in their original places. */
    private fun restoreGroupsOf(groups: Collection<Group?>) {
        for (g in groups) if (g != null && g !in _groups) _groups.add(g)
    }

    /**
     * A group with no members left is not a group. Left in place it shows up
     * in the panel as a row that hides nothing and cannot be selected.
     */
    private fun pruneEmpty() {
        val live = memberOf.values.toSet()
        _groups.removeAll { it !in live }
    }

    private fun copyOf(src: Stroke, offset: Vec3): Stroke {
        val c = Stroke(src.brush, src.color, src.baseRadius, src.opacity, src.guideId)
        for (p in src.pts) {
            c.pts.add(
                StrokePoint(
                    Vec3(p.p.x + offset.x, p.p.y + offset.y, p.p.z + offset.z),
                    tan = p.tan?.copy(), ref = p.ref?.copy(),
                    roll = p.roll, pressure = p.pressure, nrm = p.nrm?.copy(),
                )
            )
        }
        return c
    }
}

/**
 * Undo/redo over [Sketch.Edit]s.
 *
 * Replaces the stroke-only stack in `MainActivity`, which could only take back
 * a stroke — a group, a hide or a delete had nothing to record itself in, and
 * its `redo` list was filled but never read.
 */
class EditStack(private val depth: Int = 200) {

    private val done = ArrayList<Sketch.Edit>()
    private val undone = ArrayList<Sketch.Edit>()

    val canUndo: Boolean get() = done.isNotEmpty()
    val canRedo: Boolean get() = undone.isNotEmpty()

    /** Record an already-applied edit. Null is ignored, so `push(x() ?: return)` is not needed. */
    fun push(edit: Sketch.Edit?) {
        if (edit == null) return
        done.add(edit)
        undone.clear()
        while (done.size > depth) done.removeAt(0)
    }

    fun undo(): Sketch.Edit? {
        val e = done.removeLastOrNull() ?: return null
        e.revert(); undone.add(e); return e
    }

    fun redo(): Sketch.Edit? {
        val e = undone.removeLastOrNull() ?: return null
        e.apply(); done.add(e); return e
    }

    fun clear() { done.clear(); undone.clear() }
}
