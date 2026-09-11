package art.plume.core

/**
 * THE HOME SCREEN'S MODEL: folders, notes, and the order they come in.
 *
 * FACT: Home "is an important menu where you can create notes, create
 * folders, and manage them." A folder is "a folder where you can store
 * multiple notes. Thumbnails of the four most recently modified notes are
 * displayed"; a note is "the format where sketches drawn in Feather and
 * various metadata are saved."
 *
 * All of it is here rather than beside the views for the usual reason — every
 * rule below is a claim that can be checked without a screen. Which folder a
 * note is in, what happens to the notes inside a folder you delete, and what
 * "sorted by name" means when two notes share one are exactly the questions a
 * file browser gets wrong, and they are all arithmetic.
 */
object Library {

    /** FACT: "Displays folders and notes together." */
    enum class Kind { FOLDER, NOTE }

    /**
     * One row of the home screen.
     *
     * [id] is the file stem for a note and a generated key for a folder;
     * [parent] is the folder it sits in, or null at the top. [modified] and
     * [created] are millis, and [curves] is how much is in it — zero for a
     * folder, which carries its children's count instead.
     */
    class Item(
        val id: String,
        val name: String,
        val kind: Kind,
        val parent: String? = null,
        val created: Long = 0L,
        val modified: Long = 0L,
        val curves: Int = 0,
    )

    /** FACT: "The currently supported options are last modified, last created, and Name." */
    enum class Sort { MODIFIED, CREATED, NAME }

    /**
     * FACT: "Displays folders and notes together" — and folders first, which
     * is not in the text but is in every screenshot of it, and is the only
     * arrangement where a folder can be found without reading.
     *
     * Name sorting is case-insensitive and falls back to the modified time,
     * so two notes both called "Untitled" still have a stable order rather
     * than swapping places every time the screen is drawn.
     */
    fun sorted(items: List<Item>, by: Sort): List<Item> {
        val cmp = when (by) {
            Sort.MODIFIED -> compareByDescending<Item> { it.modified }
            Sort.CREATED -> compareByDescending<Item> { it.created }
            Sort.NAME -> compareBy<Item> { it.name.lowercase() }
                .thenByDescending { it.modified }
        }
        return items.sortedWith(compareBy<Item> { if (it.kind == Kind.FOLDER) 0 else 1 }.then(cmp))
    }

    /** What is directly inside [parent] — the top level when it is null. */
    fun children(items: List<Item>, parent: String?): List<Item> =
        items.filter { it.parent == parent }

    /**
     * FACT: "6. Current Path — Displays the current folder path. When inside a
     * folder, you can go back to the parent folder."
     *
     * Outermost first, so it reads left to right. A folder whose parent has
     * gone missing stops rather than looping: a cycle in this list would hang
     * the screen that draws it, and a broken index is not worth hanging for.
     */
    fun path(items: List<Item>, folder: String?): List<Item> {
        val out = ArrayList<Item>()
        var at = folder
        val seen = HashSet<String>()
        while (at != null && seen.add(at)) {
            val here = items.firstOrNull { it.id == at && it.kind == Kind.FOLDER } ?: break
            out.add(0, here)
            at = here.parent
        }
        return out
    }

    /**
     * FACT: "Thumbnails of the four most recently modified notes are
     * displayed."
     */
    fun cover(items: List<Item>, folder: String, limit: Int = 4): List<Item> =
        children(items, folder)
            .filter { it.kind == Kind.NOTE }
            .sortedByDescending { it.modified }
            .take(limit)

    /**
     * Everything that would go with [folder] if it were deleted — itself, the
     * folders inside it, and every note in any of them.
     *
     * Deleting a folder in Feather deletes what is in it; the caller is what
     * decides whether to ask first. Returned rather than performed because
     * removing files is the app's business and knowing WHICH is this one's.
     */
    fun descendants(items: List<Item>, folder: String): List<Item> {
        val out = ArrayList<Item>()
        val queue = ArrayDeque<String>()
        queue.add(folder)
        val seen = HashSet<String>()
        while (queue.isNotEmpty()) {
            val at = queue.removeFirst()
            if (!seen.add(at)) continue
            for (child in items.filter { it.parent == at }) {
                out.add(child)
                if (child.kind == Kind.FOLDER) queue.add(child.id)
            }
        }
        return out
    }

    /**
     * Whether [folder] may be moved into [into].
     *
     * A folder cannot go inside itself, or inside anything it contains — that
     * is how a file tree becomes a ring that nothing can list and nothing can
     * delete. The check is the same walk [path] does, from the other end.
     */
    fun canMove(items: List<Item>, folder: String, into: String?): Boolean {
        if (into == null) return true
        if (folder == into) return false
        var at: String? = into
        val seen = HashSet<String>()
        while (at != null && seen.add(at)) {
            if (at == folder) return false
            at = items.firstOrNull { it.id == at }?.parent
        }
        return true
    }

    /**
     * A name nothing else beside it is using.
     *
     * "Sketch", then "Sketch 2", then "Sketch 3" — the shape every duplicate
     * in every file browser takes, because the alternative is refusing the
     * duplicate over something nobody was asked about.
     */
    fun freeName(taken: Collection<String>, want: String): String {
        val used = taken.map { it.lowercase() }.toHashSet()
        if (want.lowercase() !in used) return want
        var n = 2
        while ("${want.lowercase()} $n" in used) n++
        return "$want $n"
    }
}
