package art.plume.core

/**
 * Which guides exist, which one is active, and which are on screen.
 *
 * Ported from the lifecycle half of `js/guides.js`. A guide is scaffolding:
 * you draw it, you draw on it, and then you put it away — FACT (A.5): closing
 * and sliding up saves it to the Resource tab for reuse, and "after saving,
 * you can create additional 3D Guides."
 *
 * The web build holds this in a three.js scene graph and asks whether an
 * object still has a parent. There is no scene graph here, so the same
 * question — is this guide on screen? — is answered by [shouldDraw], and the
 * renderer asks it directly.
 */
class GuideScene {

    /** The guide being drawn on. At most one, and it may be none. */
    var active: Guide? = null
        private set

    /** Saved guides, kept for reuse. FACT (A.5): the Resource tab. */
    private val saved = ArrayList<Guide>()

    /** The saved guides, in order, for the panel that lists them. */
    val resources: List<Guide> get() = saved

    private val listeners = ArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }

    /**
     * A guide is on screen if it is the active one, or if it is a saved
     * resource that has not been hidden. Anything else is only being held
     * because undo might want it back.
     */
    fun shouldDraw(g: Guide): Boolean = g === active || (saved.contains(g) && g.visible)

    /** Every guide the renderer should draw, active one last so it is on top. */
    fun drawList(): List<Guide> {
        val out = ArrayList<Guide>(saved.size + 1)
        for (g in saved) if (g.visible && g !== active) out.add(g)
        active?.let { out.add(it) }
        return out
    }

    fun setActive(g: Guide?) {
        active = g
        fire()
    }

    /** Put the active guide away without saving it. */
    fun close(): Guide? {
        val g = active ?: return null
        active = null
        fire()
        return g
    }

    /**
     * FACT (A.5): close and slide up saves the guide for reuse. A saved guide
     * stays on screen as reference rather than vanishing, and stops being the
     * active one — which is what makes room for the next guide.
     */
    fun save(guide: Guide? = null): Guide? {
        val g = guide ?: active ?: return null
        if (!saved.contains(g)) saved.add(g)
        g.visible = true
        if (g === active) active = null
        fire()
        return g
    }

    fun setResourceVisible(g: Guide, on: Boolean) {
        g.visible = on
        fire()
    }

    /**
     * Take a guide out of the scene.
     *
     * The object stays ALIVE. Deleting a reference has to be undoable, so this
     * only removes it from the resource list and the screen — throwing the
     * geometry away as well is right when the document goes away and wrong for
     * anything a person did on purpose.
     */
    fun remove(guide: Guide): Boolean {
        val wasSaved = saved.remove(guide)
        val wasActive = active === guide
        if (wasActive) active = null
        if (!wasSaved && !wasActive) return false
        fire()
        return true
    }

    /**
     * Put one back. [at] below zero means it was never a saved resource — only
     * ever the active guide — so restoring it must not quietly add it to the
     * list.
     */
    fun restore(guide: Guide, at: Int, makeActive: Boolean): Boolean {
        if (at >= 0 && !saved.contains(guide)) {
            saved.add(clamp(at, 0, saved.size), guide)
        }
        if (makeActive) active = guide
        fire()
        return true
    }

    /** Where a guide sits in the resource list, or -1 if it is not in it. */
    fun indexOf(guide: Guide): Int = saved.indexOf(guide)

    fun byId(id: Int): Guide? = saved.firstOrNull { it.id == id } ?: active?.takeIf { it.id == id }

    fun clear() {
        saved.clear()
        active = null
        fire()
    }

    private fun fire() { for (l in listeners) l() }
}
