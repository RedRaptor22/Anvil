package art.plume.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GuideSceneTest {

    private fun guide() = Primitives.create("cube")

    @Test
    fun `the active guide is drawn, and drawn last so it is on top`() {
        val scene = GuideScene()
        val a = guide(); val b = guide()
        scene.save(a)
        scene.setActive(b)

        assertTrue(scene.shouldDraw(a))
        assertTrue(scene.shouldDraw(b))
        assertEquals(listOf(a, b), scene.drawList())
    }

    @Test
    fun `closing puts the guide away, saving keeps it on screen`() {
        val scene = GuideScene()
        val g = guide()

        scene.setActive(g)
        assertSame(g, scene.close())
        assertNull(scene.active)
        assertFalse(scene.shouldDraw(g), "a closed, unsaved guide should leave the screen")

        // FACT (A.5): saving keeps it visible as reference and frees the slot
        scene.setActive(g)
        assertSame(g, scene.save())
        assertNull(scene.active, "saving should make room for the next guide")
        assertTrue(scene.shouldDraw(g), "a saved guide stays on screen as reference")
        assertEquals(listOf(g), scene.resources)
    }

    @Test
    fun `a hidden resource stays in the list but leaves the screen`() {
        val scene = GuideScene()
        val g = guide()
        scene.save(g)
        scene.setResourceVisible(g, false)
        assertFalse(scene.shouldDraw(g))
        assertEquals(listOf(g), scene.resources, "hiding is not deleting")
        assertTrue(scene.drawList().isEmpty())
    }

    @Test
    fun `removing a guide keeps the object alive so the delete can be undone`() {
        val scene = GuideScene()
        val g = guide()
        scene.save(g)
        val at = scene.indexOf(g)

        assertTrue(scene.remove(g))
        assertTrue(scene.resources.isEmpty())
        assertFalse(scene.shouldDraw(g))
        // the geometry is untouched — that is what makes undo possible
        assertTrue(g.surface != null, "remove must not destroy the guide")

        assertTrue(scene.restore(g, at, makeActive = false))
        assertEquals(listOf(g), scene.resources)
    }

    @Test
    fun `restoring something that was never saved does not add it to the list`() {
        /*
         * `at` below zero means it was only ever the active guide. Putting it
         * back has to make it active again without quietly promoting it into
         * the resource list, which the person never asked for.
         */
        val scene = GuideScene()
        val g = guide()
        scene.setActive(g)
        assertTrue(scene.remove(g))

        assertTrue(scene.restore(g, -1, makeActive = true))
        assertSame(g, scene.active)
        assertTrue(scene.resources.isEmpty(), "it was never a resource")
    }

    @Test
    fun `removing something the scene does not hold changes nothing`() {
        val scene = GuideScene()
        assertFalse(scene.remove(guide()))
    }

    @Test
    fun `a scaffold cannot go fully opaque, but reference art can`() {
        // FACT (A.2/A.10): the exception is an imported image, which is
        // reference art rather than scaffolding
        val scaffold = Primitives.create("cube")
        scaffold.opacity = 1.0
        assertEquals(Tune.GUIDE_OPACITY_MAX, scaffold.opacity, 0.0)

        val image = Guide(Guide.freshId(), GuideKind.IMAGE)
        image.opacity = 1.0
        assertEquals(1.0, image.opacity, 0.0, "an image guide is allowed to be solid")
    }

    @Test
    fun `listeners hear about every change`() {
        val scene = GuideScene()
        var beats = 0
        scene.addListener { beats++ }
        val g = guide()
        scene.setActive(g)      // 1
        scene.save()            // 2
        scene.setResourceVisible(g, false)  // 3
        scene.remove(g)         // 4
        scene.clear()           // 5
        assertEquals(5, beats)
    }

    @Test
    fun `the last closed guide can be had back`() {
        val scene = GuideScene()
        val g = guide()
        scene.setActive(g)
        assertNull(scene.recent, "nothing has been closed yet")

        scene.close()
        assertSame(g, scene.recent)
        assertNull(scene.active)

        assertSame(g, scene.recall())
        assertSame(g, scene.active, "and it is the one being drawn on again")
        assertNull(scene.recent, "spent: recalling it twice would be two guides")
        assertNull(scene.recall())
    }

    @Test
    fun `only the last one, and only a closed one`() {
        val scene = GuideScene()
        val first = guide(); val second = guide()
        scene.setActive(first); scene.close()
        scene.setActive(second); scene.close()
        assertSame(second, scene.recent, "one step back, not a stack")

        /* saving is filing, not closing — it is in the Resource tab, and
           offering it here as well would recall a guide already on screen */
        val third = guide()
        scene.setActive(third)
        scene.save()
        assertSame(second, scene.recent)
    }

    @Test
    fun `a guide you deleted is not one you can recall`() {
        val scene = GuideScene()
        val g = guide()
        scene.setActive(g)
        scene.close()
        scene.remove(g)
        assertNull(scene.recent, "it was thrown away, not put down")
        assertNull(scene.recall())
    }
}
