package art.plume.anvil

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.uiautomator.UiDevice
import art.plume.core.GuideEditing
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

    /**
     * THE VIEW LAYER, DRAWN SEPARATELY.
     *
     * UiAutomator's screenshot came back with the GL surface and nothing else
     * on it — no rail, no pill, and Home itself missing — which could mean the
     * chrome was not there or could mean the capture does not composite the
     * app window over a SurfaceView. Those are opposite bugs and the picture
     * cannot tell them apart, so the window draws itself into a bitmap here
     * and is saved beside the other one. Between the pair, whichever layer is
     * empty is the one at fault.
     */
    private fun drawViews(scenario: ActivityScenario<MainActivity>, name: String) {
        scenario.onActivity { act ->
            val v = act.window.decorView
            if (v.width <= 0 || v.height <= 0) return@onActivity
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            File(outDir(), "$name-views.png").outputStream().use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            val chrome = grab<Chrome>(act, "chrome")
            android.util.Log.i(
                "ANVILSHOT",
                "$name decor=${v.width}x${v.height}" +
                    " root=${grab<android.view.View>(chrome, "root").visibility}" +
                    " gallery=${grab<android.view.View>(chrome, "gallery").visibility}" +
                    " canvasLayer=${grab<android.view.View>(chrome, "canvasLayer").visibility}",
            )
        }
    }

    private fun shoot(name: String): File {
        Thread.sleep(1200)                 // let the GL thread land a frame
        val f = File(outDir(), "$name.png")
        val ok = device.takeScreenshot(f)
        check(ok && f.exists() && f.length() > 0) { "screenshot $name failed" }
        /* say where it went, so a run that comes back empty can be told from
           a run that never wrote anything */
        android.util.Log.i("ANVILSHOT", "wrote ${f.absolutePath} (${f.length()} bytes)")
        return f
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

    private fun setField(target: Any, name: String, value: Any) {
        val f = generateSequence(target.javaClass) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField(name) }.getOrNull() }
            .first()
        f.isAccessible = true
        f.set(target, value)
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
            drawViews(scenario, "01-home")

            // 2. the canvas
            scenario.onActivity { act ->
                grab<Chrome>(act, "chrome").onOpenWork(null)
            }
            shoot("02-canvas")
            drawViews(scenario, "02-canvas")

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
            drawViews(scenario, "03-guide-with-orange-line")

            /*
             * 4-6: the two faults that came back from the device. A tube bent
             * into a doughnut and then FILLED, which came back patterned
             * rather than covered; and a pot profile bent round a ring, which
             * came back upside down with its rim underneath.
             */
            scenario.onActivity { act ->
                val scene = grab<art.plume.core.GuideScene>(act, "guides")
                for (g in scene.resources.toList()) scene.remove(g)
                scene.setActive(null)
                val prof = (0 until 40).map {
                    val a = it / 39.0 * 2 * Math.PI
                    Vec3(Math.cos(a) * 0.18, Math.sin(a) * 0.18, 0.0)
                }
                val g = Guides.createFromStroke(prof, Vec3(0.0, 0.0, -1.0), Vec3(1.0, 0.0, 0.0), 4.0)
                if (g != null) {
                    val rim = g.anchorRow!!.first()
                    val circle = (0 until 64).map {
                        val th = it / 63.0 * 2 * Math.PI
                        Vec3(
                            rim.x + Math.sin(th) * 0.9, rim.y,
                            rim.z + (Math.cos(th) - 1.0) * 0.9,
                        )
                    }
                    GuideEditing.bend(g, circle)
                    scene.setActive(g)
                    call(act, "pushGuides")
                }
            }
            shoot("04-donut")

            /*
             * FILL IN A LIGHT COLOUR, ON A FRAMED DONUT.
             *
             * The first pass of this filled in the app's default ink, which is
             * very nearly black, against a dark page — so a fill full of holes
             * and a fill that covered everything photograph identically. The
             * reported fault is gaps, and gaps are only visible in a colour
             * that is not the background.
             */
            scenario.onActivity { act ->
                setField(act, "color", art.plume.core.Rgba(0.66, 0.69, 0.96))
                call(act, "resetView")
                call(act, "pushCamera")
                val scene = grab<art.plume.core.GuideScene>(act, "guides")
                val g = scene.active
                if (g != null) {
                    val sp = art.plume.core.GuidePainting.surfaceSpan(g)
                    val nib = art.plume.core.Stroke(brush = art.plume.core.Fill.BRUSH, baseRadius = 14.0 * art.plume.core.MM * 0.5)
                    val half = art.plume.core.StrokeGeometry.halfWidth(nib, nib.baseRadius)
                    android.util.Log.i(
                        "ANVILSHOT",
                        "fill span lu=${sp?.lu} lv=${sp?.lv} nu=${sp?.nu} nv=${sp?.nv}" +
                            " half=$half pitch=${half * 2 * art.plume.core.Fill.OVERLAP}",
                    )
                }
                call(act, "fillActiveGuide")
                val n = grab<art.plume.core.Sketch>(act, "sketch").strokes.size
                android.util.Log.i("ANVILSHOT", "fill produced $n strokes")
            }
            shoot("05-donut-filled")

            scenario.onActivity { act ->
                val scene = grab<art.plume.core.GuideScene>(act, "guides")
                scene.setActive(null)
                /* and clear the fill, or the pot is photographed through it */
                grab<art.plume.core.Sketch>(act, "sketch").clear()
                /*
                 * The path the app really makes when you hold to a circle:
                 * Shapes' circle in screen pixels, unprojected onto the
                 * camera-facing draw plane. A circle written by hand does not
                 * exercise the fault; this does.
                 */
                val cam = grab<art.plume.core.Camera>(act, "camera")
                val tmp = Vec3()
                cam.refreshDrawPlane(null)
                val prof = ArrayList<Vec3>()
                for (i in 0 until 24) {
                    val py = 500.0 + i / 23.0 * 700.0
                    cam.planePoint(1200.0, py, tmp)?.let { prof.add(it.copy()) }
                }
                val fwd = Vec3(); cam.forward(fwd)
                val rt = Vec3(); val u = Vec3(); val bk = Vec3(); cam.basis(rt, u, bk)
                val g = Guides.createFromStroke(prof, fwd, rt, cam.radius)
                if (g != null) {
                    cam.refreshDrawPlane(g.sweep!!.anchor)
                    val circle = ArrayList<Vec3>()
                    for (q in art.plume.core.Shapes.Shape.Circle(1500.0, 900.0, 260.0).points) {
                        val w = cam.planePoint(q.x, q.y, tmp) ?: continue
                        if (circle.isEmpty() || circle.last().distanceTo(w) > 0.0005) {
                            circle.add(w.copy())
                        }
                    }
                    GuideEditing.bend(g, circle)
                    scene.setActive(g)
                    call(act, "pushGuides")
                }
            }
            shoot("06-pot-rim-on-top")
        }
    }

    /**
     * THE BACK OF A SHAPE MUST NOT COME THROUGH THE FRONT OF IT.
     *
     * Reported from a device: "parts of their stroke bleed through the back
     * and appear in front of the front viewing strokes" — a tube whose far
     * wall showed through its near wall at some angles and not others, and a
     * scene where curves plainly behind an object were drawn over it.
     *
     * Three things were wrong and all three are exercised here.
     *
     *  - THE DEPTH BUFFER WAS SIXTEEN BITS, because that is what
     *    GLSurfaceView picks when nobody asks. See [DepthFirstConfigChooser].
     *  - THE AGE TIE-BREAK GREW WITH THE DRAWING. Each curve was pulled one
     *    depth step further towards the eye than the one before it, counting
     *    from the first, so the pull on recent curves was the length of the
     *    whole drawing rather than a tie-break.
     *  - THE SLOPE OFFSET WAS GIVEN TO SOLIDS. It scales with how fast depth
     *    changes across a pixel, so on anything steep it is a shove. `cube`
     *    used to get one; the red bars driven into the screen below are drawn
     *    with it for exactly that reason.
     *
     * The name is an identifier rather than a sentence in backticks, which the
     * rest of this project's tests use: these run on the DEVICE, so they are
     * dexed, and D8 refuses a method name with a space in it below DEX 040.
     * The JVM suites are not dexed and can go on reading like sentences.
     *
     * THIS IS AN ASSERTION, NOT A PHOTOGRAPH. The picture is kept too, but a
     * picture that has to be looked at is a test nobody runs. The scene is a
     * green wall with red bars behind it, and the question — is any red
     * visible where the wall is — is one a few thousand pixel reads can
     * answer. The wall is checked for first: if the green is not there the
     * capture failed and the absence of red proves nothing.
     */
    @Test
    fun nothingBehindTheWallShowsThroughIt() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { act ->
                grab<Chrome>(act, "chrome").onOpenWork(null)
                call(act, "resetView")
                call(act, "pushCamera")
            }
            scenario.onActivity { act ->
                val cam = grab<art.plume.core.Camera>(act, "camera")
                val sketch = grab<art.plume.core.Sketch>(act, "sketch")
                sketch.clear()
                val group = sketch.ensureGroup().id
                val r = cam.radius
                val fwd = Vec3(); cam.forward(fwd)
                val right = Vec3(); val up = Vec3(); val back = Vec3()
                cam.basis(right, up, back)
                val mid = cam.pivot.copy()

                fun at(across: Double, high: Double, deep: Double) = Vec3(
                    mid.x + right.x * across + up.x * high + fwd.x * deep,
                    mid.y + right.y * across + up.y * high + fwd.y * deep,
                    mid.z + right.z * across + up.z * high + fwd.z * deep,
                )

                fun bar(a: Vec3, b: Vec3, brush: String, colour: art.plume.core.Rgba, rad: Double) {
                    val st = art.plume.core.Stroke(
                        brush = brush, color = colour, baseRadius = rad,
                    )
                    st.pressureTarget = "none"
                    st.material = art.plume.core.Material.SHADELESS
                    st.group = group
                    for (i in 0..16) {
                        val t = i / 16.0
                        st.pts.add(
                            art.plume.core.StrokePoint(
                                Vec3(
                                    a.x + (b.x - a.x) * t,
                                    a.y + (b.y - a.y) * t,
                                    a.z + (b.z - a.z) * t,
                                ),
                                pressure = 1.0,
                            ),
                        )
                    }
                    art.plume.core.Nib.freezeFrames(st)
                    sketch.add(st)
                }

                /* the wall: overlapping bars across the view, at the pivot */
                val green = art.plume.core.Rgba(0.15, 0.85, 0.25)
                var h = -0.5 * r
                while (h <= 0.5 * r + 1e-9) {
                    bar(at(-0.7 * r, h, 0.0), at(0.7 * r, h, 0.0), "pen", green, 0.05 * r)
                    h += 0.06 * r
                }

                /*
                 * BEHIND IT BY A HAIR. The gap is deliberately small — a
                 * hundred and twenty-fifth of the view — because that is the
                 * scale the fault lived at: the two walls of a tube are this
                 * far apart near its rim, which is where the bleed-through
                 * showed and why turning the object made it come and go.
                 */
                val red = art.plume.core.Rgba(0.92, 0.12, 0.12)
                val gap = 0.008 * r
                var a = -0.25 * r
                while (a <= 0.25 * r + 1e-9) {
                    bar(at(a, -0.3 * r, gap), at(a, 0.3 * r, gap), "pen", red, 0.04 * r)
                    a += 0.1 * r
                }
                /* and driven away from the eye, so their silhouette is as
                   steep as depth gets: this is the slope offset's scene */
                var k = -0.2 * r
                while (k <= 0.2 * r + 1e-9) {
                    bar(at(k, 0.0, gap), at(k, 0.0, 0.45 * r), "cube", red, 0.04 * r)
                    k += 0.1 * r
                }
                call(act, "pushStrokes")
                android.util.Log.i("ANVILSHOT", "bleed scene: ${sketch.strokes.size} curves")
            }

            val shot = shoot("08-bleed-through")
            val bmp = android.graphics.BitmapFactory.decodeFile(shot.absolutePath)
            checkNotNull(bmp) { "the capture could not be decoded" }

            var greens = 0
            var reds = 0
            var seen = 0
            val x0 = bmp.width * 2 / 5; val x1 = bmp.width * 3 / 5
            val y0 = bmp.height * 2 / 5; val y1 = bmp.height * 3 / 5
            for (y in y0 until y1) for (x in x0 until x1) {
                val c = bmp.getPixel(x, y)
                val cr = android.graphics.Color.red(c)
                val cg = android.graphics.Color.green(c)
                val cb = android.graphics.Color.blue(c)
                seen++
                if (cg > cr + 40 && cg > cb + 40) greens++
                if (cr > cg + 40 && cr > cb + 40) reds++
            }
            android.util.Log.i("ANVILSHOT", "bleed: $greens green, $reds red of $seen")
            check(greens > seen / 5) {
                "the wall is not in the capture ($greens green of $seen) — this run " +
                    "proves nothing about what is behind it"
            }
            check(reds * 200 < seen) {
                "$reds of $seen pixels behind the wall came through it"
            }
        }
    }
}
