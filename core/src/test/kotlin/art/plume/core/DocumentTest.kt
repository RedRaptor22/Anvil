package art.plume.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `.plume.json` document.
 *
 * The whole reason this format is transcribed field for field rather than
 * invented is that a sketch has to open in both builds. So the tests are about
 * what SURVIVES a round trip, and about reading files this build did not write
 * — including one from a version that predates half of it.
 */
class DocumentTest {

    private fun stroke(n: Int = 12, brush: String = "pen"): Stroke {
        val s = Stroke(brush = brush, color = Rgba(0.85, 0.22, 0.26), baseRadius = 9.0 * MM)
        for (i in 0 until n) {
            val t = i / (n - 1.0)
            s.pts.add(
                StrokePoint(
                    Vec3(t * 0.5 - 0.25, sin(t * 3) * 0.1, cos(t * 2) * 0.05),
                    pressure = 0.3 + t * 0.5,
                    roll = t * 0.4,
                ),
            )
        }
        return s
    }

    private fun sweptGuide(): Guide = assertNotNull(
        Guides.createFromStroke(
            (0 until 20).map {
                val a = -0.5 + it / 19.0
                Vec3(a * 0.4, sin(a * 2) * 0.12, 0.0)
            },
            Vec3(0.0, 0.0, -1.0), Vec3(1.0, 0.0, 0.0), 4.0,
        ),
    )

    private fun flatGuide(): Guide = assertNotNull(
        Guides.createFlatFromStroke(
            (0 until 24).map {
                val a = it / 23.0 * 2 * PI
                Vec3(cos(a) * 0.3, sin(a) * 0.3, 0.0)
            },
            Vec3(0.0, 0.0, -1.0), Vec3(1.0, 0.0, 0.0),
        ),
    )

    // ---- the round trip ----------------------------------------------------

    @Test
    fun `a sketch comes back the same after a save and a load`() {
        val sketch = Sketch()
        val guides = GuideScene()
        val cam = Camera().apply { resize(1000, 2000) }
        cam.theta = 1.2; cam.phi = 0.9; cam.radius = 3.3; cam.roll = 0.15
        cam.pivot.set(0.1, -0.2, 0.05)
        cam.focal = 85.0
        cam.apply()

        val a = stroke(14, "taper")
        val b = stroke(9, "flat")
        sketch.add(a); sketch.add(b)
        guides.setActive(sweptGuide())

        val text = Document.toJsonText(sketch, guides, cam)

        val outSketch = Sketch()
        val outGuides = GuideScene()
        val outCam = Camera().apply { resize(1000, 2000) }
        val r = Document.restore(text, outSketch, outGuides, outCam)
        assertTrue(r.ok, "restore failed: ${r.reason}")

        // the curves, to the six decimals the format stores
        assertEquals(2, outSketch.strokes.size)
        for (i in sketch.strokes.indices) {
            val src = sketch.strokes[i]
            val got = outSketch.strokes[i]
            assertEquals(src.brush, got.brush)
            assertEquals(src.baseRadius, got.baseRadius, 1e-6)
            assertEquals(src.color.r, got.color.r, 1.0 / 255)
            assertEquals(src.pts.size, got.pts.size)
            for (j in src.pts.indices) {
                assertTrue(
                    src.pts[j].p.distanceTo(got.pts[j].p) < 1e-6,
                    "point $j of stroke $i moved",
                )
                assertEquals(src.pts[j].pressure, got.pts[j].pressure, 1e-6)
                assertEquals(src.pts[j].roll, got.pts[j].roll, 1e-6)
            }
        }

        // the camera, so a reopened sketch is framed as it was left
        assertEquals(cam.theta, outCam.theta, 1e-6)
        assertEquals(cam.phi, outCam.phi, 1e-6)
        assertEquals(cam.radius, outCam.radius, 1e-6)
        assertEquals(cam.roll, outCam.roll, 1e-6)
        assertEquals(cam.focal, outCam.focal, 1e-6)
        assertEquals(0.0, cam.pivot.distanceTo(outCam.pivot), 1e-6)
    }

    @Test
    fun `a swept guide comes back as the same surface, not a copy of its triangles`() {
        /*
         * The file carries {local, path, anchorIndex, basis} and the surface is
         * swept again on load. That is what keeps it editable — a reloaded
         * guide can still be bent — and it is why the test compares the built
         * geometry rather than the stored numbers.
         */
        val guides = GuideScene()
        val original = sweptGuide()
        guides.setActive(original)
        val text = Document.toJsonText(Sketch(), guides, Camera().apply { resize(800, 800) })

        val out = GuideScene()
        assertTrue(
            Document.restore(text, Sketch(), out, Camera().apply { resize(800, 800) }).ok,
        )
        val back = assertNotNull(out.active)
        assertNotNull(back.sweep, "a reloaded sweep must still be a sweep, so it can be bent")

        val src = assertNotNull(original.surface)
        val got = assertNotNull(back.surface)
        assertEquals(src.vertexCount, got.vertexCount)
        for (i in 0 until src.vertexCount * 3) {
            assertEquals(src.positions[i], got.positions[i], 1e-5f, "vertex data $i")
        }
    }

    @Test
    fun `a flat guide comes back as its outline, retriangulated`() {
        val guides = GuideScene()
        val original = flatGuide()
        guides.save(original)
        val text = Document.toJsonText(Sketch(), guides, Camera().apply { resize(800, 800) })

        val out = GuideScene()
        assertTrue(Document.restore(text, Sketch(), out, Camera().apply { resize(800, 800) }).ok)
        assertEquals(1, out.resources.size)
        val back = out.resources[0]
        assertNotNull(back.plane, "a flat guide must come back with its plane")

        val src = assertNotNull(original.surface)
        val got = assertNotNull(back.surface)
        assertEquals(src.indices.size, got.indices.size, "the retriangulation differs")
        assertEquals(src.lu, got.lu, 1e-6)
    }

    @Test
    fun `a primitive and a loft come back from their parameters`() {
        val guides = GuideScene()
        guides.save(Primitives.create("torus", 20, 0.5))
        guides.save(
            assertNotNull(
                GuideEditing.loftFromCurves(
                    listOf(
                        (0 until 12).map { Vec3(it * 0.05, 0.0, 0.0) },
                        (0 until 12).map { Vec3(it * 0.05, 0.4, 0.1) },
                    ),
                    0.7,
                ),
            ),
        )
        val text = Document.toJsonText(Sketch(), guides, Camera().apply { resize(800, 800) })

        val out = GuideScene()
        assertTrue(Document.restore(text, Sketch(), out, Camera().apply { resize(800, 800) }).ok)
        assertEquals(2, out.resources.size)

        val torus = out.resources[0]
        assertEquals("torus", torus.primitiveKind)
        assertEquals(20, torus.primitiveSegments)
        assertEquals(0.5, torus.primitiveTaper, 1e-6)

        val loft = out.resources[1]
        assertEquals(0.7, loft.loftTension, 1e-6)
        assertEquals(2, assertNotNull(loft.loftCurves).size)
    }

    @Test
    fun `groups and their visibility survive`() {
        val sketch = Sketch()
        val hidden = sketch.newGroup("scaffold")
        hidden.visible = false
        val shown = sketch.newGroup("ink")
        val a = stroke(); val b = stroke(); val c = stroke()
        sketch.add(a); sketch.add(b); sketch.add(c)
        sketch.assign(a, hidden)
        sketch.assign(b, shown)

        val text = Document.toJsonText(sketch, GuideScene(), Camera().apply { resize(800, 800) })
        val out = Sketch()
        assertTrue(Document.restore(text, out, GuideScene(), Camera().apply { resize(800, 800) }).ok)

        assertEquals(2, out.groups.size)
        assertEquals("scaffold", out.groups[0].name)
        assertFalse(out.groups[0].visible, "a hidden group came back visible")
        assertTrue(out.groups[1].visible)

        // the curves point at the right groups, by the NEW ids
        assertEquals(out.groups[0].id, out.strokes[0].group)
        assertEquals(out.groups[1].id, out.strokes[1].group)
        assertNull(out.strokes[2].group, "an ungrouped curve should stay ungrouped")
        assertFalse(out.visible(out.strokes[0]), "the hidden group is not hiding anything")
    }

    // ---- reading what this build did not write --------------------------------

    @Test
    fun `a version 1 file invents the groups it never had`() {
        /*
         * v1 stored `group` as a bare number with no list and no names. Every
         * distinct number becomes a group, because a nameless group is not
         * something a panel can show — and a v1 sketch has to open at all.
         */
        val v1 = """
        {"format":"plume","version":1,"strokes":[
          {"n":2,"p":[0,0,0, 1,0,0],"tan":[null,null,null,null,null,null],
           "ref":[null,null,null,null,null,null],"roll":[0,0],"pressure":[1,1],
           "tiltAz":[null,null],"tiltAlt":[1,1],
           "brush":"pen","color":"#112233","radius":0.007,"opacity":1,"group":7},
          {"n":2,"p":[0,1,0, 1,1,0],"tan":[null,null,null,null,null,null],
           "ref":[null,null,null,null,null,null],"roll":[0,0],"pressure":[1,1],
           "tiltAz":[null,null],"tiltAlt":[1,1],
           "brush":"pen","color":"#112233","radius":0.007,"opacity":1,"group":9}
        ]}
        """.trimIndent()

        val sketch = Sketch()
        val r = Document.restore(v1, sketch, GuideScene(), Camera().apply { resize(800, 800) })
        assertTrue(r.ok, "a v1 file should still open: ${r.reason}")
        assertEquals(2, sketch.strokes.size)
        assertEquals(2, sketch.groups.size, "one group per distinct id")
        assertTrue(sketch.groups.all { it.name.isNotEmpty() }, "an invented group needs a name")
        assertEquals(sketch.groups[0].id, sketch.strokes[0].group)
        assertEquals(sketch.groups[1].id, sketch.strokes[1].group)
    }

    @Test
    fun `a file from a newer build is refused rather than half read`() {
        val newer = """{"format":"plume","version":99,"strokes":[]}"""
        val r = Document.restore(newer, Sketch(), GuideScene(), Camera().apply { resize(8, 8) })
        assertFalse(r.ok)
        assertEquals("written by a newer build", r.reason)
    }

    @Test
    fun `something that is not a sketch is refused, and does not throw`() {
        val cam = Camera().apply { resize(8, 8) }
        for (junk in listOf("", "{", "[1,2,3]", """{"format":"notplume"}""", "hello")) {
            val r = Document.restore(junk, Sketch(), GuideScene(), cam)
            assertFalse(r.ok, "'$junk' should not have been accepted")
            assertNotNull(r.reason)
        }
    }

    @Test
    fun `a browser sketch keeps its lighting through a round trip on the phone`() {
        /*
         * The light and the post effects belong to the SKETCH, so dropping them
         * would mean a file that went through the phone came back re-lit, which
         * is a change nobody asked for.
         *
         * This test predates Phase 5, when they were carried through as opaque
         * JSON because there was nothing here to apply them to. They are read
         * and written properly now, and the test is deliberately unchanged in
         * what it demands: the same file, the same values out. What it gained
         * is the assertions below, which check the values actually reached the
         * model rather than merely surviving as text.
         */
        val fromWeb = """
        {"format":"plume","version":2,
         "env":{"bg":"#eceaf3","grid":true,"axis":false,
                "light":{"az":1.1,"alt":0.6,"color":"#ffeedd","intensity":1.4,"ambient":0.2},
                "fx":{"dof":true,"fstop":2.8,"grain":true,"grainLevel":0.3}},
         "tool":{"brush":"sketch","sizeMM":22,"pressureTarget":"opacity"},
         "strokes":[]}
        """.trimIndent()

        val sketch = Sketch()
        val guides = GuideScene()
        val cam = Camera().apply { resize(800, 800) }
        val r = Document.restore(fromWeb, sketch, guides, cam)
        assertTrue(r.ok)

        val again = Document.toJsonText(
            sketch, guides, cam, r.env, r.tool, r.carried,
        )
        val back = assertNotNull(Json.parse(again).asObject())
        val light = assertNotNull(
            back.obj("env")?.obj("light"), "the light was dropped on the way through",
        )
        assertEquals(1.4, light.num("intensity", 0.0), 1e-9)
        assertEquals("#ffeedd", light.str("color"))
        val fx = assertNotNull(back.obj("env")?.obj("fx"), "the post effects were dropped")
        assertEquals(2.8, fx.num("fstop", 0.0), 1e-9)

        // and they were UNDERSTOOD, not just copied
        assertEquals(1.1, r.env.light.az, 1e-9)
        assertEquals(0.6, r.env.light.alt, 1e-9)
        assertEquals(1.4, r.env.light.intensity, 1e-9)
        assertEquals(0.2, r.env.light.ambient, 1e-9)
        assertEquals(1.0, r.env.light.color.r, 1e-3)
        assertEquals(0xdd / 255.0, r.env.light.color.b, 1e-3)
        assertTrue(r.env.fx.dofOn && r.env.fx.grainOn)
        assertFalse(r.env.fx.pixelOn, "absent means off, not on")

        // and the parts this build DOES model still round-trip
        assertEquals("sketch", back.obj("tool")?.str("brush"))
        assertEquals(22.0, back.obj("tool")?.num("sizeMM", 0.0) ?: 0.0, 1e-9)
        assertEquals("opacity", back.obj("tool")?.str("pressureTarget"))
    }

    @Test
    fun `the file says what it is, so the web build recognises it`() {
        val text = Document.toJsonText(
            Sketch(), GuideScene(), Camera().apply { resize(8, 8) },
        )
        val o = assertNotNull(Json.parse(text).asObject())
        assertEquals("plume", o.str("format"))
        assertEquals(2, o.int("version", 0))
        // the sections the web build reads by name
        for (k in listOf("view", "env", "tool", "groups", "strokes", "guides")) {
            assertTrue(k in o, "the document is missing its '$k' section")
        }
    }

    @Test
    fun `numbers are quantised to six decimals, which is what halves the file`() {
        assertEquals(0.123457, Document.q(0.1234567890), 0.0)
        assertEquals(-0.000001, Document.q(-0.00000051), 0.0)
        assertEquals(0.0, Document.q(0.0000004), 0.0)

        // and a real sketch actually gets smaller for it
        val sketch = Sketch()
        val s = Stroke()
        for (i in 0 until 200) {
            s.pts.add(StrokePoint(Vec3(i * 0.0011234567891, 0.9876543210987, 0.5)))
        }
        sketch.add(s)
        val text = Document.toJsonText(sketch, GuideScene(), Camera().apply { resize(8, 8) })
        assertFalse(
            text.contains("0.9876543210987"),
            "a full-precision number reached the file",
        )
        assertTrue(text.contains("0.987654"))
    }

    // ---- the JSON layer itself -------------------------------------------------

    @Test
    fun `json survives a round trip, including the awkward parts`() {
        val o = JsonObject()
            .put("text", "quote \" backslash \\ newline \n tab \t")
            .put("neg", -12.5)
            .put("exp", 1.0e-7)
            .put("int", 42)
            .put("yes", true)
            .putNull("nothing")
            .put("list", JsonArray().add(1.0).add("two").add(JsonNull))
        val back = assertNotNull(Json.parse(o.write()).asObject())

        assertEquals("quote \" backslash \\ newline \n tab \t", back.str("text"))
        assertEquals(-12.5, back.num("neg", 0.0), 0.0)
        assertEquals(1.0e-7, back.num("exp", 0.0), 1e-20)
        assertEquals(42, back.int("int", 0))
        assertTrue(back.bool("yes", false))
        assertTrue(back["nothing"] is JsonNull)
        assertEquals(3, assertNotNull(back.arr("list")).size)
    }

    @Test
    fun `an integral number writes without a trailing point, as JavaScript does`() {
        // a file full of "1.0" where the web writes "1" is still valid JSON,
        // but it is needless bulk in a format built to be small
        assertEquals("1", JsonNumber(1.0).write())
        assertEquals("-7", JsonNumber(-7.0).write())
        assertEquals("0.5", JsonNumber(0.5).write())
    }

    @Test
    fun `a number that is not a number cannot corrupt the file`() {
        // one NaN would otherwise write a document neither build can open
        assertEquals("0", JsonNumber(Double.NaN).write())
        assertEquals("0", JsonNumber(Double.POSITIVE_INFINITY).write())
        assertNotNull(Json.parse(JsonArray().add(Double.NaN).write()))
    }
}
