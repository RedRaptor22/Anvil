package art.plume.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The light, the post-pass parameters and the ground shadow's geometry.
 *
 * All of this is the half of Phase 5 that can be checked without a GL context,
 * which is why it is in `core` at all: a shader can only be looked at, and
 * "the same sketch under the same light reads the same on both builds" is not
 * something looking at it can establish.
 */
class LightTest {

    // ---- the key light ---------------------------------------------------

    /**
     * The defaults are a compatibility claim, not a taste: the docs in
     * camera.js say they reproduce the direction that was hardcoded in the
     * stroke shader, (0.32, 0.62, 0.72). If these drift, every sketch made
     * before the panel existed is re-lit on load.
     */
    @Test
    fun `default direction is the light the shader used to hardcode`() {
        val d = Light().direction()
        assertEquals(0.32, d.x, 0.005)
        assertEquals(0.62, d.y, 0.005)
        assertEquals(0.72, d.z, 0.005)
        assertEquals(1.0, d.length(), 1e-12)
    }

    /** And that direction is the 38.2 degrees at azimuth 24 the note claims. */
    @Test
    fun `default altitude and azimuth are what the note says they are`() {
        val l = Light()
        assertEquals(38.2, l.alt * 180.0 / PI, 0.1)
        assertEquals(24.0, l.az * 180.0 / PI, 0.1)
    }

    /** az turns about +Y from +Z towards +X; alt lifts off the horizon. */
    @Test
    fun `azimuth and altitude round trip through the direction`() {
        for (az in listOf(-2.9, -1.0, 0.0, 0.7, 2.5)) {
            for (alt in listOf(0.05, 0.4, 1.2, 1.5)) {
                val d = Light().apply { this.az = az; this.alt = alt }.direction()
                assertEquals(alt, asin(d.y), 1e-9, "alt at az=$az alt=$alt")
                assertEquals(az, atan2(d.x, d.z), 1e-9, "az at az=$az alt=$alt")
            }
        }
    }

    @Test
    fun `a light straight overhead still gives a unit direction`() {
        val d = Light().apply { alt = PI / 2 }.direction()
        assertEquals(1.0, d.y, 1e-12)
        assertEquals(1.0, d.length(), 1e-12)
    }

    /**
     * `floor(hl*steps)/(steps-1)` divides by zero at one step, so the clamp is
     * load-bearing rather than defensive — a document is free to carry a 1.
     */
    @Test
    fun `toon steps never fall below two`() {
        assertEquals(2, Light().apply { toonSteps = 1 }.toonStepsClamped())
        assertEquals(2, Light().apply { toonSteps = 0 }.toonStepsClamped())
        assertEquals(7, Light().apply { toonSteps = 7 }.toonStepsClamped())
    }

    @Test
    fun `ambient is clamped to zero one`() {
        assertEquals(1.0, Light().apply { ambient = 4.0 }.ambientClamped(), 0.0)
        assertEquals(0.0, Light().apply { ambient = -1.0 }.ambientClamped(), 0.0)
    }

    // ---- the post pass ---------------------------------------------------

    /**
     * The aperture has to run the way a real one does or the control is
     * backwards under the hand: a LOW f-number is a wide aperture is a shallow
     * depth of field. The test states the direction rather than a number.
     */
    @Test
    fun `a lower f stop keeps less in focus`() {
        val fx = Fx()
        val focus = 4.0
        fx.fstop = 1.4
        val wide = fx.focusRange(focus)
        fx.fstop = 22.0
        val narrow = fx.focusRange(focus)
        assertTrue(wide < narrow, "f/1.4 range $wide should be tighter than f/22 $narrow")
        // f/22 is the "nearly everything sharp" end: the range reaches the subject
        assertEquals(focus, narrow, 1e-9)
    }

    /** Focus follows the pivot, which is the point the camera model calls the subject. */
    @Test
    fun `focus is the distance from the eye to the pivot`() {
        val cam = Camera()
        cam.resize(800, 600)
        cam.pivot.set(1.0, 2.0, -0.5)
        cam.radius = 3.0
        cam.apply()
        assertEquals(3.0, Fx().focusDistance(cam), 1e-9)
    }

    @Test
    fun `grain is off when the toggle is off, whatever the level`() {
        val fx = Fx().apply { grain = 100.0; grainOn = false }
        assertEquals(0.0, fx.grainAmount(), 0.0)
        fx.grainOn = true
        assertEquals(0.22, fx.grainAmount(), 1e-12)
    }

    /** A block size in screen pixels becomes a count of blocks across a buffer. */
    @Test
    fun `pixel grid counts blocks, and never fewer than one`() {
        val fx = Fx().apply { pixel = 4.0 }
        assertEquals(250.0, fx.pixelGridX(1000, 1.0), 1e-9)
        assertEquals(125.0, fx.pixelGridX(1000, 2.0), 1e-9)   // denser screen, same block
        fx.pixel = 100000.0
        assertEquals(1.0, fx.pixelGridX(1000, 1.0), 1e-9)
    }

    // ---- bounds ----------------------------------------------------------

    @Test
    fun `bounds of an empty sketch stay empty`() {
        assertTrue(Bounds.of(Sketch()).empty)
    }

    /** A hidden group is not part of the sketch's extent, so it casts nothing. */
    @Test
    fun `bounds skip a hidden group`() {
        val sk = Sketch()
        val far = strokeAt(Vec3(50.0, 0.0, 0.0))
        val near = strokeAt(Vec3(0.0, 0.0, 0.0))
        sk.add(near)
        sk.add(far)
        val g = sk.newGroup("hidden")
        far.group = g.id

        assertEquals(50.0, Bounds.of(sk).maxX, 1e-9, "visible: the far stroke counts")
        g.visible = false
        assertEquals(0.0, Bounds.of(sk).maxX, 1e-9, "hidden: it does not")
    }

    // ---- the ground shadow ----------------------------------------------

    @Test
    fun `no shadow below the horizon, or off render mode, or with nothing drawn`() {
        val env = DocumentEnv().apply { render = true; groundShadow = true }
        val light = Light()
        val b = Bounds().add(Vec3(0.0, 0.0, 0.0)).add(Vec3(1.0, 1.0, 1.0))

        assertTrue(GroundShadow.active(env, light, b))
        assertFalse(GroundShadow.active(env, light, Bounds()), "nothing to cast")
        assertFalse(
            GroundShadow.active(env, Light().apply { alt = 0.01 }, b), "sun on the horizon",
        )
        assertFalse(
            GroundShadow.active(DocumentEnv().apply { render = false }, light, b),
            "FACT: effects show only in rendering mode",
        )
    }

    /** The light's camera looks at the sketch from the direction the light comes from. */
    @Test
    fun `the shadow camera stands up the light and looks back at the sketch`() {
        val b = Bounds().add(Vec3(-1.0, 0.0, -1.0)).add(Vec3(1.0, 2.0, 1.0))
        val light = Light()
        val fit = GroundShadow.fit(b, light)

        val toCentre = (fit.centre - fit.eye).normalize()
        val fromLight = light.direction()
        // the eye is up the light, so looking back at the sketch is -direction
        assertEquals(-fromLight.x, toCentre.x, 1e-9)
        assertEquals(-fromLight.y, toCentre.y, 1e-9)
        assertEquals(-fromLight.z, toCentre.z, 1e-9)
        assertEquals(0.0, fit.centre.x, 1e-9)
        assertEquals(1.0, fit.centre.y, 1e-9)
    }

    /**
     * Everything the sketch contains has to land inside the target, or the
     * silhouette is clipped and the shadow has a straight edge across it that
     * belongs to nothing in the drawing.
     */
    @Test
    fun `every corner of the sketch projects inside the shadow target`() {
        val b = Bounds().add(Vec3(-0.7, 0.1, -1.3)).add(Vec3(2.0, 1.6, 0.4))
        for (alt in listOf(0.06, 0.3, 0.9, 1.5)) {
            for (az in listOf(-2.0, 0.0, 1.1)) {
                val fit = GroundShadow.fit(b, Light().apply { this.alt = alt; this.az = az })
                val out = Vec3()
                for (x in listOf(b.minX, b.maxX)) {
                    for (y in listOf(b.minY, b.maxY)) {
                        for (z in listOf(b.minZ, b.maxZ)) {
                            fit.viewProj.transformPoint(Vec3(x, y, z), out)
                            assertTrue(
                                abs(out.x) <= 1.0 && abs(out.y) <= 1.0 && abs(out.z) <= 1.0,
                                "corner ($x,$y,$z) fell outside at alt=$alt az=$az: $out",
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * A low sun throws a long shadow, so the ground the target has to cover
     * grows as the light drops. Without this the shadow of a sketch floating
     * above the ground is cut off exactly where the target ends.
     */
    @Test
    fun `a lower sun asks for more ground`() {
        val b = Bounds().add(Vec3(-0.5, 1.0, -0.5)).add(Vec3(0.5, 2.0, 0.5))
        val high = GroundShadow.fit(b, Light().apply { alt = 1.4 }).half
        val low = GroundShadow.fit(b, Light().apply { alt = 0.15 }).half
        assertTrue(low > high * 2, "low sun half=$low should far exceed high sun half=$high")
    }

    /** ...but not without limit, or a sun near the horizon asks for the world. */
    @Test
    fun `the reach is capped`() {
        val b = Bounds().add(Vec3(-0.5, 8.0, -0.5)).add(Vec3(0.5, 9.0, 0.5))
        val fit = GroundShadow.fit(b, Light().apply { alt = GroundShadow.MIN_ALT })
        val radius = b.radius()
        assertTrue(fit.half <= radius * 40.0 + 1.0, "half ${fit.half} exceeded the cap")
    }

    /**
     * A light straight overhead makes the view direction parallel to +Y, where
     * lookAt's cross products collapse. The matrix has to stay finite.
     */
    @Test
    fun `a noon sun does not produce NaN`() {
        val b = Bounds().add(Vec3(-1.0, 0.0, -1.0)).add(Vec3(1.0, 1.0, 1.0))
        val fit = GroundShadow.fit(b, Light().apply { alt = PI / 2 })
        for (v in fit.viewProj.m) assertTrue(v.isFinite(), "matrix held $v")
        val out = Vec3()
        fit.viewProj.transformPoint(Vec3(0.0, 0.5, 0.0), out)
        assertTrue(out.x.isFinite() && out.y.isFinite() && out.z.isFinite())
    }

    /**
     * Orbiting must not dirty the shadow — that is the whole reason the pass is
     * keyed rather than run every frame. The signature is what the renderer
     * compares, so it has to answer to the light and the sketch and to nothing
     * else.
     */
    @Test
    fun `the signature answers to the light and the sketch, not the camera`() {
        val b = Bounds().add(Vec3(0.0, 0.0, 0.0)).add(Vec3(1.0, 1.0, 1.0))
        val light = Light()
        val base = GroundShadow.signature(b, light)

        assertEquals(base, GroundShadow.signature(b, light), "stable when nothing moved")

        light.az += 0.5
        assertTrue(GroundShadow.signature(b, light) != base, "turning the light")
        light.az -= 0.5

        // a colour change repaints but does not move the silhouette
        light.color = Rgba(1.0, 0.4, 0.2)
        assertEquals(base, GroundShadow.signature(b, light), "recolouring the light")

        b.add(Vec3(3.0, 0.0, 0.0))
        assertTrue(GroundShadow.signature(b, light) != base, "the sketch grew")
    }

    private fun strokeAt(p: Vec3): Stroke {
        val s = Stroke()
        s.pts.add(StrokePoint(p.copy()))
        s.pts.add(StrokePoint(p.copy()))
        return s
    }
}

/**
 * The light and the effects as DOCUMENT fields.
 *
 * Separate from LightTest because these are about the file, not the maths, and
 * the phase's "done when" is about a file: the same sketch under the same light
 * reading the same on both builds.
 */
class LightDocumentTest {

    private fun roundTrip(env: DocumentEnv): DocumentEnv {
        val sketch = Sketch()
        val guides = GuideScene()
        val cam = Camera().apply { resize(800, 800) }
        val text = Document.toJsonText(sketch, guides, cam, env)
        val r = Document.restore(text, Sketch(), GuideScene(), Camera().apply { resize(800, 800) })
        assertTrue(r.ok, r.reason ?: "restore failed")
        return r.env
    }

    @Test
    fun `every light and fx field survives a round trip`() {
        val env = DocumentEnv()
        env.light.az = -1.234
        env.light.alt = 0.789
        env.light.color = Rgba(0.2, 0.6, 1.0)
        env.light.intensity = 1.75
        env.light.ambient = 0.11
        env.light.toon = true
        env.light.toonSteps = 6
        env.fx.dofOn = true
        env.fx.fstop = 1.8
        env.fx.grainOn = true
        env.fx.grain = 72.0
        env.fx.pixelOn = true
        env.fx.pixel = 9.0

        val back = roundTrip(env)
        assertEquals(-1.234, back.light.az, 1e-6)
        assertEquals(0.789, back.light.alt, 1e-6)
        assertEquals(1.75, back.light.intensity, 1e-6)
        assertEquals(0.11, back.light.ambient, 1e-6)
        assertTrue(back.light.toon)
        assertEquals(6, back.light.toonSteps)
        // colour goes through a hex triple, so a byte is the honest tolerance
        assertEquals(0.2, back.light.color.r, 1.0 / 255)
        assertEquals(0.6, back.light.color.g, 1.0 / 255)
        assertEquals(1.0, back.light.color.b, 1.0 / 255)

        assertTrue(back.fx.dofOn && back.fx.grainOn && back.fx.pixelOn)
        assertEquals(1.8, back.fx.fstop, 1e-6)
        assertEquals(72.0, back.fx.grain, 1e-6)
        assertEquals(9.0, back.fx.pixel, 1e-6)
    }

    /**
     * A v1 file has no light block at all. Reading a missing field as zero
     * would give intensity 0 at ambient 0 — a black drawing, which looks like
     * a corrupt file rather than a missing field.
     */
    @Test
    fun `a file with no light block opens under the default sun`() {
        val old = """{"format":"plume","version":1,"strokes":[]}"""
        val r = Document.restore(old, Sketch(), GuideScene(), Camera().apply { resize(800, 800) })
        assertTrue(r.ok)
        val d = Light()
        assertEquals(d.az, r.env.light.az, 1e-12)
        assertEquals(d.alt, r.env.light.alt, 1e-12)
        assertEquals(d.intensity, r.env.light.intensity, 1e-12)
        assertEquals(d.ambient, r.env.light.ambient, 1e-12)
        assertFalse(r.env.light.toon)
        assertEquals(4, r.env.light.toonSteps)
    }

    /**
     * A partial block keeps the defaults for what it does not mention, rather
     * than zeroing them. The web build reads its own fields the same way
     * (`if(X.fstop !== undefined)`).
     */
    @Test
    fun `a partial light block fills the gaps with defaults`() {
        val partial = """
        {"format":"plume","version":2,
         "env":{"light":{"intensity":2.0},"fx":{"grain":true}},
         "strokes":[]}
        """.trimIndent()
        val r = Document.restore(partial, Sketch(), GuideScene(), Camera().apply { resize(800, 800) })
        assertTrue(r.ok)
        assertEquals(2.0, r.env.light.intensity, 1e-12)
        assertEquals(Light().ambient, r.env.light.ambient, 1e-12, "ambient was not mentioned")
        assertEquals(Light().az, r.env.light.az, 1e-12)
        assertTrue(r.env.fx.grainOn)
        assertEquals(Fx().grain, r.env.fx.grain, 1e-12, "the level was not mentioned")
        assertEquals(Fx().fstop, r.env.fx.fstop, 1e-12)
    }

    /**
     * Whatever a future web version puts in `env` beside the modelled keys has
     * to come back out — that is what `Carried` is still for now that the light
     * and the effects are modelled.
     */
    @Test
    fun `an unknown env key is still carried through`() {
        val withExtra = """
        {"format":"plume","version":2,
         "env":{"bg":"#101010","someFutureThing":{"a":1,"b":"two"},
                "light":{"intensity":1.5}},
         "strokes":[]}
        """.trimIndent()
        val sketch = Sketch(); val guides = GuideScene()
        val cam = Camera().apply { resize(800, 800) }
        val r = Document.restore(withExtra, sketch, guides, cam)
        val back = Json.parse(Document.toJsonText(sketch, guides, cam, r.env, r.tool, r.carried))
            .asObject()!!
        val future = back.obj("env")?.obj("someFutureThing")
        assertTrue(future != null, "an unmodelled env key was dropped")
        assertEquals(1.0, future.num("a", 0.0), 1e-12)
        assertEquals("two", future.str("b"))
        // and the modelled key was overwritten from the model, not the carry
        assertEquals(1.5, back.obj("env")?.obj("light")?.num("intensity", 0.0) ?: 0.0, 1e-9)
    }
}
