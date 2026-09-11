package art.plume.anvil

import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Does the app actually come up, and does it come up on Home? */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-xhdpi")
class LaunchTest {

    private fun launch(): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java).setup().get()

    @Test
    fun `the app opens without crashing`() {
        val act = launch()
        assertTrue("the activity finished on the way up", !act.isFinishing)
    }

    @Test
    fun `it opens on Home, with the canvas controls put away`() {
        val act = launch()
        val chrome = field<Any>(act, "chrome")
        val gallery = field<View>(chrome, "gallery")
        val canvas = field<View>(chrome, "canvasLayer")
        assertEquals("Home is not showing", View.VISIBLE, gallery.visibility)
        assertEquals("the canvas controls are still up", View.GONE, canvas.visibility)
    }

    @Test
    fun `nothing under the canvas layer is left showing on Home`() {
        val act = launch()
        val chrome = field<Any>(act, "chrome")
        val canvas = field<ViewGroup>(chrome, "canvasLayer")
        // a GONE parent takes the lot with it, whatever each child says
        assertEquals(View.GONE, canvas.visibility)
        assertTrue("the canvas layer is empty", canvas.childCount > 20)
    }

    /**
     * THE FIRST TAP ON PRIMITIVES PUTS THE STAGING BAR UP.
     *
     * It used to take two. setTool built the preview before `tool = t`, so
     * showStaging asked the tool being LEFT which bar to raise, fell through
     * to null, and hid the bar on the way in — taking the segment and taper
     * sliders and, with them, Done and Cancel. Tapping the same tool again
     * appeared to fix it, because by then `tool` matched.
     */
    @Test
    fun `one tap on Primitives raises the staging bar`() {
        val act = launch()
        val chrome = field<Chrome>(act, "chrome")
        chrome.onOpenWork(null)                 // leave Home for the canvas
        val stageBar = field<View>(chrome, "stageBar")
        assertEquals("nothing staged yet", View.GONE, stageBar.visibility)

        chrome.onTool(Tool.PRIM)                // ONE tap

        assertEquals(
            "the staging bar did not come up on the first tap",
            View.VISIBLE, stageBar.visibility,
        )
    }

    /** Loft needs two curves, so it refuses — and must not raise the bar. */
    @Test
    fun `leaving Primitives puts the staging bar away again`() {
        val act = launch()
        val chrome = field<Chrome>(act, "chrome")
        chrome.onOpenWork(null)
        val stageBar = field<View>(chrome, "stageBar")

        chrome.onTool(Tool.PRIM)
        assertEquals(View.VISIBLE, stageBar.visibility)
        chrome.onTool(Tool.DRAW)
        assertEquals("staging outlived the tool", View.GONE, stageBar.visibility)
    }

    /** Home puts the canvas away; leaving Home brings it back. */
    @Test
    fun `the canvas comes back when Home closes`() {
        val act = launch()
        val chrome = field<Chrome>(act, "chrome")
        val canvas = field<View>(chrome, "canvasLayer")
        val gallery = field<View>(chrome, "gallery")
        assertEquals(View.GONE, canvas.visibility)

        chrome.onOpenWork(null)

        assertEquals("the canvas did not come back", View.VISIBLE, canvas.visibility)
        assertEquals("Home did not close", View.GONE, gallery.visibility)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(target: Any, name: String): T {
        val f = generateSequence(target.javaClass) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField(name) }.getOrNull() }
            .first()
        f.isAccessible = true
        return f.get(target) as T
    }
}
