package art.plume.anvil

import android.graphics.Rect
import android.view.View
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * NOTHING IN THE TOP-RIGHT COLUMN MAY SIT ON THE NAVIGATION GLOBE.
 *
 * The globe was dropped into a corner that already had tenants: the tool pill
 * at the top, and the mirror bar under it. It cleared the pill and clipped the
 * mirror bar by five pixels — the kind of thing that is invisible in a review
 * and obvious the moment somebody turns the mirror on.
 *
 * `diag` and `stagePanel` are deliberately left out. They are the same slot as
 * each other and have always overlapped it; that is the existing design, not
 * something the globe introduced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-xhdpi")
class LayoutTest {

    @Suppress("UNCHECKED_CAST")
    private fun <T> f(target: Any, name: String): T {
        val fl = generateSequence(target.javaClass as Class<*>) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField(name) }.getOrNull() }.first()
        fl.isAccessible = true
        return fl.get(target) as T
    }

    private fun rectOf(v: View): Rect {
        val p = IntArray(2); v.getLocationInWindow(p)
        return Rect(p[0], p[1], p[0] + v.width, p[1] + v.height)
    }

    private fun check(q: String) {
        org.robolectric.RuntimeEnvironment.setQualifiers(q)
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val chrome = f<Chrome>(act, "chrome")
        chrome.onOpenWork(null)
        /* the mirror bar only appears when the mirror is on, so it is turned
           on here rather than waited for */
        f<View>(chrome, "mirrorBar").visibility = View.VISIBLE

        val root = f<View>(chrome, "root")
        root.measure(
            View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, root.width, root.height)

        val globe = rectOf(f<View>(chrome, "navGlobe"))
        assertTrue("$q: the globe was not laid out", globe.width() > 0 && globe.height() > 0)
        assertTrue(
            "$q: the globe runs off the bottom of the screen ($globe in ${root.height})",
            globe.bottom <= root.height,
        )
        for (name in listOf("toolPill", "mirrorBar")) {
            val other = rectOf(f<View>(chrome, name))
            if (other.isEmpty) continue
            assertTrue(
                "$q: $name $other sits on the navigation globe $globe",
                !Rect.intersects(other, globe),
            )
        }
    }

    @Test fun `the globe clears its neighbours on a tablet`() = check("w1280dp-h800dp-xhdpi")

    @Test fun `the globe clears its neighbours on a phone`() = check("w411dp-h891dp-xhdpi")

    @Test fun `the globe clears its neighbours in landscape on a phone`() =
        check("w891dp-h411dp-xhdpi")
}
