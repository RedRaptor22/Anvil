package art.plume.anvil

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import art.plume.core.Guides
import art.plume.core.Vec3
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PHOTOGRAPH THE APP.
 *
 * Everything else this project can run stops at the edge of the GPU: the core
 * tests are pure maths, and Robolectric never draws a frame. So the things
 * that are only true on screen — a guide veiled by its own scaffolding, an
 * orange line that may or may not be orange — have only ever been checked by
 * someone installing the APK and looking.
 *
 * This runs on an emulator with a software GL driver and takes pictures. The
 * pictures go up as a build artifact, which is the part that matters: they can
 * be fetched and LOOKED AT afterwards, so a rendering claim can be answered
 * with the frame instead of with an argument about the code.
 *
 * State is driven directly rather than through gestures. Injected touches are
 * a second thing to debug when a screenshot comes out wrong, and the question
 * here is what the renderer does with a given scene, not whether the
 * emulator's finger landed where it was aimed.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /**
     * INTERNAL STORAGE, FETCHED WITH run-as AFTER THE RUN.
     *
     * Two runs were lost to a wrong diagnosis worth writing down. adb could
     * not find the pictures under /sdcard/Android/data, and could not find
     * them under Android/media either, and both failures look exactly like
     * Android 11's scoped storage — which is what they were blamed on.
     *
     * They were not. Gradle UNINSTALLS the app when connectedAndroidTest
     * finishes, and an uninstall takes every one of those directories with
     * it. The pictures were written, asserted, and then deleted before
     * anything went looking for them; "No such file or directory" was the
     * plain truth about a package that no longer existed. The giveaway was
     * run-as finally saying so in as many words: "unknown package".
     *
     * So the app's own files directory is fine — it just has to still be
     * there, which is what leaveApksInstalledAfterRun buys in the workflow.
     */
    private fun outDir(): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return File(ctx.filesDir, "shots").apply { mkdirs() }
    }

    private fun shoot(name: String) {
        Thread.sleep(1200)                 // let the GL thread land a frame
        val f = File(outDir(), "$name.png")
        val ok = device.takeScreenshot(f)
        check(ok && f.exists() && f.length() > 0) { "screenshot $name failed" }
        /* say where it went, so a run that comes back empty can be told from
           a run that never wrote anything */
        android.util.Log.i("ANVILSHOT", "wrote ${f.absolutePath} (${f.length()} bytes)")
    }

    /** Reach a private member, because the app exposes no test seam. */
    private fun <T> grab(target: Any, name: String): T {
        val f = generateSequence(target.javaClass) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField(name) }.getOrNull() }
            .first()
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return f.get(target) as T
    }

    private fun call(target: Any, name: String) {
        val m = generateSequence(target.javaClass) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredMethod(name) }.getOrNull() }
            .first()
        m.isAccessible = true
        m.invoke(target)
    }

    @Test
    fun photograph() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // 1. the start menu, which should have no canvas controls on it
            shoot("01-home")

            // 2. the canvas
            scenario.onActivity { act ->
                grab<Chrome>(act, "chrome").onOpenWork(null)
            }
            shoot("02-canvas")

            // 3. a swept guide, with the orange starting line on it
            scenario.onActivity { act ->
                val camera = grab<Any>(act, "camera")
                val fwd = Vec3(0.0, 0.0, -1.0)
                val right = Vec3(1.0, 0.0, 0.0)
                val profile = (0 until 24).map {
                    val a = -0.5 + it / 23.0
                    Vec3(a * 0.5, Math.sin(a * 4.0) * 0.18, 0.0)
                }
                val g = Guides.createFromStroke(profile, fwd, right, 4.0)
                if (g != null) {
                    val scene = grab<art.plume.core.GuideScene>(act, "guides")
                    scene.setActive(g)
                    call(act, "pushGuides")
                }
                camera.hashCode()
            }
            shoot("03-guide-with-orange-line")
        }
    }
}
