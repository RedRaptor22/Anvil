package art.plume.anvil

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SEQUENCES, NOT SINGLE ACTIONS.
 *
 * Every one of this app's shipped faults worked correctly on its own and
 * failed in combination: a staging bar that appeared on the second tap, canvas
 * controls that only showed on a device with a cutout, a pot that only
 * inverted when the circle was snapped. So these drive runs of actions the way
 * a person does, and assert the state after each one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-xhdpi")
class FlowTest {

    private fun launch(): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java).setup().get()

    @Suppress("UNCHECKED_CAST")
    private fun <T> f(target: Any, name: String): T {
        val fl = generateSequence(target.javaClass) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField(name) }.getOrNull() }.first()
        fl.isAccessible = true
        return fl.get(target) as T
    }

    private fun vis(chrome: Any, name: String) = f<View>(chrome, name).visibility

    /** Home -> canvas -> Home -> canvas, twice round. */
    @Test
    fun `going back and forth between Home and the canvas keeps the layers straight`() {
        val act = launch()
        val chrome = f<Chrome>(act, "chrome")
        repeat(3) { i ->
            assertEquals("round $i: Home not showing", View.VISIBLE, vis(chrome, "gallery"))
            assertEquals("round $i: canvas not put away", View.GONE, vis(chrome, "canvasLayer"))
            chrome.onOpenWork(null)
            assertEquals("round $i: canvas did not come back", View.VISIBLE, vis(chrome, "canvasLayer"))
            assertEquals("round $i: Home did not close", View.GONE, vis(chrome, "gallery"))
            chrome.setGallery(true)
        }
    }

    /** Staging must not survive a trip to Home and back. */
    @Test
    fun `staging does not leak across a trip to Home`() {
        val act = launch()
        val chrome = f<Chrome>(act, "chrome")
        chrome.onOpenWork(null)
        chrome.onTool(Tool.PRIM)
        assertEquals(View.VISIBLE, vis(chrome, "stageBar"))
        chrome.setGallery(true)
        chrome.onOpenWork(null)
        assertEquals(
            "a staged primitive survived a trip to Home",
            View.GONE, vis(chrome, "stageBar"),
        )
    }

    /** Tapping the same tool repeatedly must be idempotent. */
    @Test
    fun `hammering a tool button does not stack staged guides`() {
        val act = launch()
        val chrome = f<Chrome>(act, "chrome")
        chrome.onOpenWork(null)
        repeat(5) { chrome.onTool(Tool.PRIM) }
        assertEquals(View.VISIBLE, vis(chrome, "stageBar"))
        val guides = f<art.plume.core.GuideScene>(act, "guides")
        assertEquals("repeated taps saved guides", 0, guides.resources.size)
        chrome.onTool(Tool.DRAW)
        assertEquals(View.GONE, vis(chrome, "stageBar"))
        assertEquals("cancelling staging left a guide behind", 0, guides.resources.size)
    }

    /** Hiding the UI and coming back must restore what was there. */
    @Test
    fun `hide UI then show it again restores the canvas controls`() {
        val act = launch()
        val chrome = f<Chrome>(act, "chrome")
        chrome.onOpenWork(null)
        val before = vis(chrome, "canvasLayer")
        val flip = generateSequence(act.javaClass as Class<*>) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredMethod("flipInput", InputToggle::class.java) }.getOrNull() }
            .first()
        flip.isAccessible = true
        flip.invoke(act, InputToggle.HIDE_UI)
        assertEquals("hide UI did not hide the chrome", View.GONE, f<View>(chrome, "root").visibility)
        flip.invoke(act, InputToggle.HIDE_UI)
        assertEquals("showing again did not restore the chrome", View.VISIBLE, f<View>(chrome, "root").visibility)
        assertEquals("the canvas layer changed behind hide-UI", before, vis(chrome, "canvasLayer"))
    }

    /** The globe must follow the camera and aim it. */
    @Test
    fun `the navigation globe aims the camera and follows it`() {
        val act = launch()
        val chrome = f<Chrome>(act, "chrome")
        chrome.onOpenWork(null)
        val cam = f<art.plume.core.Camera>(act, "camera")

        chrome.onNavAim(art.plume.core.Camera.ORTHO_VIEWS.first { it.name == "Top" })
        val eye = cam.eyeDirection(cam.theta, cam.phi)
        assertTrue("Top did not put the eye overhead: $eye", eye.y > 0.999)

        val before = cam.theta
        chrome.onNavOrbit(40.0, 0.0)
        assertTrue("the globe's drag did not orbit", cam.theta != before)
    }

    /**
     * A CLOSED GUIDE BELONGS TO THE NOTE IT WAS DRAWN IN.
     *
     * Recall Recent Guide brings back the last guide you closed. Opening
     * another note emptied the saved resources and the active guide but not
     * the recallable one, so the quick menu would offer — and inject — a
     * guide out of a drawing you had left.
     */
    @Test
    fun `the recallable guide does not follow you into another note`() {
        val act = launch()
        val chrome = f<Chrome>(act, "chrome")
        chrome.onOpenWork(null)
        val guides = f<art.plume.core.GuideScene>(act, "guides")

        val g = art.plume.core.Guides.createFromStroke(
            (0 until 12).map { art.plume.core.Vec3(it * 0.05, it * 0.02, 0.0) },
            art.plume.core.Vec3(0.0, 0.0, -1.0), art.plume.core.Vec3(1.0, 0.0, 0.0), 4.0,
        )!!
        guides.setActive(g)
        guides.close()
        assertTrue("nothing to recall — the probe is wrong", guides.recent != null)

        chrome.onOpenWork(null)
        assertTrue(
            "a guide from the last note is still waiting in Recall",
            guides.recent == null,
        )
    }

    /**
     * FACT: "Brush presets are saved per note." A NEW note has none, and was
     * inheriting whatever the last one had — restoreAutosave reloads them, and
     * a new note never calls it.
     */
    @Test
    fun `brush presets do not follow you into a new note`() {
        val act = launch()
        val chrome = f<Chrome>(act, "chrome")
        chrome.onOpenWork(null)
        val presets = f<MutableList<art.plume.core.BrushPreset>>(act, "presets")
        presets.add(
            art.plume.core.BrushPreset("flat", art.plume.core.Rgba(1.0, 0.0, 0.0), 9.0, 0.5),
        )
        chrome.onOpenWork(null)
        assertEquals(
            "the last note's brush presets came along", 0, presets.size,
        )
    }

    /**
     * THE CURVE UNDER THE PEN IS A CONSTRUCTION LINE, NOT INK.
     *
     * Guide, Flat Guide and Bend all draw a curve that is handed to the
     * geometry and thrown away, and all three used to draw it with whatever
     * brush was loaded — so making a guide with the wide marker selected
     * painted a shaded ribbon across the screen and called it a profile.
     *
     * Both halves are asserted together on purpose: the drawing tools must
     * NOT have been dragged into the same rule, and a check that only looks at
     * the guide tools would pass with every stroke in the app turned orange.
     */
    @Test
    fun `the guide and bend tools draw an orange line, and Draw still draws ink`() {
        val act = launch()
        val chrome = f<Chrome>(act, "chrome")
        chrome.onOpenWork(null)

        // a brush nothing would mistake for a construction line
        chrome.onBrush("wide")
        chrome.onSizeMm(40.0)
        chrome.onColor(android.graphics.Color.rgb(20, 30, 200))

        for (t in listOf(Tool.GUIDE, Tool.FLATGUIDE, Tool.BEND)) {
            chrome.onTool(t)
            penDown(act, 400f, 400f)
            val live = f<art.plume.core.Stroke?>(act, "live")
            assertTrue("$t put nothing under the pen", live != null)
            assertEquals("$t did not use the construction nib", "pen", live!!.brush)
            assertEquals(
                "$t did not draw in the guide orange",
                art.plume.core.Tune.GUIDE_LINE, live.color,
            )
            assertEquals(
                "$t drew at the brush's size", art.plume.core.Tune.GUIDE_LINE_MM * 0.001 * 0.5,
                live.baseRadius, 1e-12,
            )
            assertEquals("a construction line must not taper", "none", live.pressureTarget)
            call0(act, "endTransientEditing")
        }

        chrome.onTool(Tool.DRAW)
        penDown(act, 400f, 400f)
        val ink = f<art.plume.core.Stroke?>(act, "live")
        assertEquals("Draw stopped using the chosen brush", "wide", ink!!.brush)
        assertEquals("Draw stopped using the chosen size", 0.02, ink.baseRadius, 1e-12)
    }

    /**
     * And nothing orange is left in the drawing afterwards: the construction
     * line is consumed by the guide it makes.
     */
    @Test
    fun `a finished guide leaves no construction line in the sketch`() {
        val act = launch()
        val chrome = f<Chrome>(act, "chrome")
        chrome.onOpenWork(null)
        chrome.onTool(Tool.GUIDE)
        penDown(act, 300f, 300f)
        for (i in 1..20) penMove(act, 300f + i * 12f, 300f + i * 4f)
        call0(act, "endStroke")
        val sketch = f<art.plume.core.Sketch>(act, "sketch")
        assertTrue(
            "the guide's own profile was left behind as a curve",
            sketch.strokes.none { it.color == art.plume.core.Tune.GUIDE_LINE },
        )
    }

    /**
     * THE ERASER'S PANEL, AND THE WIDTH IT IS FOR.
     *
     * The panel is only useful because the eraser has a size of its own; while
     * it read the brush's, a size control on it would have been a control that
     * quietly resized the pen. So the two are asserted together.
     */
    @Test
    fun `the eraser panel shows for both erasers and sets a width of its own`() {
        val act = launch()
        val chrome = f<Chrome>(act, "chrome")
        chrome.onOpenWork(null)

        assertEquals("the eraser panel is up with no eraser selected",
            View.GONE, vis(chrome, "erasePanel"))
        chrome.onTool(Tool.ERASE)
        assertEquals(View.VISIBLE, vis(chrome, "erasePanel"))
        chrome.onTool(Tool.VACUUM)
        assertEquals("vacuum is the other eraser and keeps the panel",
            View.VISIBLE, vis(chrome, "erasePanel"))
        chrome.onTool(Tool.DRAW)
        assertEquals(View.GONE, vis(chrome, "erasePanel"))

        chrome.onTool(Tool.ERASE)
        chrome.onSizeMm(33.0)
        chrome.onEraseSize(4.0)
        assertEquals("the eraser's width followed the brush's",
            4.0, f<Double>(act, "eraseMM"), 1e-9)
        assertEquals("setting the eraser resized the brush",
            33.0, f<Double>(act, "sizeMM"), 1e-9)
    }

    private fun penDown(act: MainActivity, x: Float, y: Float) {
        val m = generateSequence(act.javaClass as Class<*>) { it.superclass }
            .mapNotNull {
                runCatching {
                    it.getDeclaredMethod(
                        "beginStroke",
                        Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType,
                    )
                }.getOrNull()
            }.first()
        m.isAccessible = true
        m.invoke(act, x, y, 0.5f)
    }

    private fun penMove(act: MainActivity, x: Float, y: Float) {
        val m = generateSequence(act.javaClass as Class<*>) { it.superclass }
            .mapNotNull {
                runCatching {
                    it.getDeclaredMethod(
                        "moveStroke",
                        Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType,
                    )
                }.getOrNull()
            }.first()
        m.isAccessible = true
        m.invoke(act, x, y, 0.5f)
    }

    private fun call1(target: Any, name: String, argType: Class<*>, arg: Any) {
        val m = generateSequence(target.javaClass as Class<*>) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredMethod(name, argType) }.getOrNull() }.first()
        m.isAccessible = true
        m.invoke(target, arg)
    }

    private fun setF(target: Any, name: String, value: Any?) {
        val fl = generateSequence(target.javaClass as Class<*>) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField(name) }.getOrNull() }.first()
        fl.isAccessible = true
        fl.set(target, value)
    }

    private fun call0(target: Any, name: String) {
        val m = generateSequence(target.javaClass as Class<*>) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredMethod(name) }.getOrNull() }.first()
        m.isAccessible = true
        m.invoke(target)
    }

    private fun workId(act: MainActivity): String {
        val m = generateSequence(act.javaClass as Class<*>) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredMethod("currentWorkId") }.getOrNull() }.first()
        m.isAccessible = true
        return m.invoke(act) as String
    }

    /**
     * ERASING EVERYTHING HAS TO STICK.
     *
     * Both save paths skip the write when the sketch is empty. That is right
     * for a note nobody has drawn in and wrong the moment a file exists: the
     * old drawing stays on disk, and the next open brings back work the user
     * deliberately deleted.
     */
    @Test
    fun `clearing a drawing survives a reload`() {
        val act = launch()
        val chrome = f<Chrome>(act, "chrome")
        chrome.onOpenWork(null)
        val sketch = f<art.plume.core.Sketch>(act, "sketch")

        val st = art.plume.core.Stroke()
        st.pts.add(art.plume.core.StrokePoint(art.plume.core.Vec3(0.0, 0.0, 0.0)))
        st.pts.add(art.plume.core.StrokePoint(art.plume.core.Vec3(0.1, 0.1, 0.0)))
        sketch.add(st)
        call1(act, "saveWorkNow", String::class.java, workId(act))

        sketch.clear()
        call1(act, "saveWorkNow", String::class.java, workId(act))

        sketch.clear()
        call0(act, "restoreAutosave")
        assertEquals(
            "the drawing came back after being deleted",
            0, sketch.strokes.size,
        )
    }

    /**
     * ROTATION MUST NOT DISTURB WHICH LAYER IS UP.
     *
     * The activity handles configuration changes itself rather than being
     * recreated for them, so applyMode runs over a live view tree. It sets
     * visibilities on things that are now children of the canvas layer, and a
     * rotation on Home must not bring any of them back.
     */
    @Test
    fun `rotating on Home leaves the canvas put away`() {
        val act = launch()
        val chrome = f<Chrome>(act, "chrome")
        assertEquals(View.VISIBLE, vis(chrome, "gallery"))

        org.robolectric.RuntimeEnvironment.setQualifiers("+land")
        act.onConfigurationChanged(act.resources.configuration)

        assertEquals("rotating on Home hid Home", View.VISIBLE, vis(chrome, "gallery"))
        assertEquals(
            "rotating on Home brought the canvas controls back",
            View.GONE, vis(chrome, "canvasLayer"),
        )
    }

    /** And on the canvas, rotation must not throw away what is staged. */
    @Test
    fun `rotating mid-staging keeps the staged shape and its bar`() {
        val act = launch()
        val chrome = f<Chrome>(act, "chrome")
        chrome.onOpenWork(null)
        chrome.onTool(Tool.PRIM)
        assertEquals(View.VISIBLE, vis(chrome, "stageBar"))

        org.robolectric.RuntimeEnvironment.setQualifiers("+land")
        act.onConfigurationChanged(act.resources.configuration)

        assertEquals("a rotation threw the staging bar away", View.VISIBLE, vis(chrome, "stageBar"))
        assertEquals(View.VISIBLE, vis(chrome, "canvasLayer"))
    }

    /** Pause writes the autosave; resume must not lose the drawing. */
    @Test
    fun `a pause and resume keeps the drawing`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val act = controller.get()
        val chrome = f<Chrome>(act, "chrome")
        chrome.onOpenWork(null)
        val sketch = f<art.plume.core.Sketch>(act, "sketch")
        val st = art.plume.core.Stroke()
        st.pts.add(art.plume.core.StrokePoint(art.plume.core.Vec3(0.0, 0.0, 0.0)))
        st.pts.add(art.plume.core.StrokePoint(art.plume.core.Vec3(0.2, 0.1, 0.0)))
        sketch.add(st)

        controller.pause().resume()

        assertEquals("the drawing did not survive a pause", 1, sketch.strokes.size)
    }

    /**
     * A FILL IS MADE OF WHAT EVERY OTHER MARK IS MADE OF.
     *
     * beginStroke stamps the chosen material and pattern onto a hand-drawn
     * curve. A fill's curves are made by a different path, which copied
     * neither — so Glow filled a guide without glowing.
     */
    @Test
    fun `a fill carries the chosen material and pattern`() {
        val act = launch()
        val chrome = f<Chrome>(act, "chrome")
        chrome.onOpenWork(null)

        val guides = f<art.plume.core.GuideScene>(act, "guides")
        val g = art.plume.core.Guides.createFromStroke(
            (0 until 20).map {
                val a = -0.4 + it / 19.0 * 0.8
                art.plume.core.Vec3(a, kotlin.math.sin(a * 3) * 0.2, 0.0)
            },
            art.plume.core.Vec3(0.0, 0.0, -1.0), art.plume.core.Vec3(1.0, 0.0, 0.0), 4.0,
        )!!
        guides.setActive(g)

        setF(act, "material", art.plume.core.Material.GLOW)
        setF(act, "pattern", art.plume.core.Pattern.CROSS)

        call0(act, "fillActiveGuide")
        val sketch = f<art.plume.core.Sketch>(act, "sketch")
        assertTrue("the fill produced nothing", sketch.strokes.isNotEmpty())
        for (st in sketch.strokes) {
            assertEquals("a fill row lost the material", art.plume.core.Material.GLOW, st.material)
            assertEquals("a fill row lost the pattern", art.plume.core.Pattern.CROSS, st.pattern)
        }
    }

    /** A phone-width layout must still reach Home and the canvas. */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun `a phone layout still opens a note and shows its controls`() {
        val act = launch()
        val chrome = f<Chrome>(act, "chrome")
        assertEquals(View.VISIBLE, vis(chrome, "gallery"))
        chrome.onOpenWork(null)
        assertEquals(View.VISIBLE, vis(chrome, "canvasLayer"))
        assertEquals("the dock should carry the tools on a phone", View.VISIBLE, vis(chrome, "dock"))
    }
}
