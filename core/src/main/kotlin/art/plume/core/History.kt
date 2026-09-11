package art.plume.core

/**
 * One undoable step.
 *
 * [cost] is what this step RETAINS, counted in stroke points. It is declared
 * rather than measured because only the command knows what it is holding onto:
 * an erase keeps the whole stroke it removed alive, a colour change keeps
 * nothing but two small values.
 */
interface Command {
    val label: String
    val cost: Int get() = 0
    fun undo()
    fun redo()
}

/** A command built from two lambdas, which is what most call sites want. */
class Step(
    override val label: String,
    override val cost: Int = 0,
    private val onUndo: () -> Unit,
    private val onRedo: () -> Unit,
) : Command {
    override fun undo() = onUndo()
    override fun redo() = onRedo()
}

/**
 * Undo and redo, ported from `P.History` in `js/core.js`.
 *
 * One flat stack. Feather's documented coverage is draw, erase, attribute
 * change, group add/delete, copy, transform, selection, guide close and
 * joystick transforms — so every mutating operation pushes.
 *
 * The bound is the interesting part. An undo step keeps whole stroke records
 * alive, so DEPTH ALONE IS A POOR BOUND: 200 steps of one dot cost nothing,
 * 200 steps holding thousand-point strokes are tens of megabytes. Commands
 * declare what they retain and the oldest are dropped once the budget is
 * passed. On a phone this matters more than it does in a browser tab — the
 * process is killed rather than swapped — which is why it is ported now
 * instead of being left as an ArrayList.
 */
class History {

    private val stack = ArrayList<Command>()

    /** Number of commands currently applied; everything above it is redo tail. */
    var index = 0
        private set

    /** Retained points across the whole stack. */
    var cost = 0
        private set

    private val listeners = ArrayList<(History) -> Unit>()

    val size: Int get() = stack.size

    fun addListener(l: (History) -> Unit) { listeners.add(l) }

    fun canUndo(): Boolean = index > 0
    fun canRedo(): Boolean = index < stack.size

    /** The label of the step undo would take back, for a UI to name a button. */
    fun undoLabel(): String? = if (canUndo()) stack[index - 1].label else null
    fun redoLabel(): String? = if (canRedo()) stack[index].label else null

    fun push(cmd: Command) {
        // drop the redo tail, refunding what it was holding
        for (i in index until stack.size) cost -= stack[i].cost
        while (stack.size > index) stack.removeAt(stack.size - 1)

        stack.add(cmd)
        cost += cmd.cost

        /*
         * Never evict the only step: a stack of one is what "undo the thing I
         * just did" needs, and a single stroke big enough to blow the budget on
         * its own would otherwise be unswallowable — pushed and immediately
         * dropped, leaving undo silently dead.
         */
        while (stack.size > 1 &&
            (stack.size > Tune.UNDO_DEPTH || cost > Tune.UNDO_POINT_BUDGET)
        ) {
            cost -= stack.removeAt(0).cost
        }

        index = stack.size
        fire()
    }

    /** Do it, then record it — the order every call site wants. */
    fun run(cmd: Command) { cmd.redo(); push(cmd) }

    fun undo(): Boolean {
        if (!canUndo()) return false
        stack[--index].undo()
        fire()
        return true
    }

    fun redo(): Boolean {
        if (!canRedo()) return false
        stack[index++].redo()
        fire()
        return true
    }

    fun clear() {
        stack.clear(); index = 0; cost = 0
        fire()
    }

    private fun fire() { for (l in listeners) l(this) }
}
