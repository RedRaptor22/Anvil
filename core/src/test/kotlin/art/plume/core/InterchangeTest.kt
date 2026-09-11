package art.plume.core

import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Export and import.
 *
 * The load-bearing property is the UNITS, and the two paths differ on purpose:
 * OBJ and STL in millimetres because neither format declares a unit and that
 * is what a printer expects, glTF in metres because glTF does declare one.
 * Getting that backwards hands Blender a sketch a thousand times too big, and
 * nothing in the file would say so.
 */
class InterchangeTest {

    private fun sketchWith(vararg strokes: Stroke): Sketch =
        Sketch().also { s -> strokes.forEach { s.add(it) } }

    /** A stroke exactly 100 mm long, so the scale is easy to read off. */
    private fun ruler(): Stroke {
        val s = Stroke(brush = "pen", color = Rgba(1.0, 0.0, 0.0), baseRadius = 5.0 * MM)
        for (i in 0 until 11) s.pts.add(StrokePoint(Vec3(i * 0.01, 0.0, 0.0)))
        return s
    }

    private fun bounds(pos: FloatArray): Pair<Vec3, Vec3> {
        val lo = Vec3(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
        val hi = Vec3(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        var i = 0
        while (i < pos.size) {
            lo.x = minOf(lo.x, pos[i].toDouble()); hi.x = maxOf(hi.x, pos[i].toDouble())
            lo.y = minOf(lo.y, pos[i + 1].toDouble()); hi.y = maxOf(hi.y, pos[i + 1].toDouble())
            lo.z = minOf(lo.z, pos[i + 2].toDouble()); hi.z = maxOf(hi.z, pos[i + 2].toDouble())
            i += 3
        }
        return lo to hi
    }

    // ---- units ---------------------------------------------------------------

    @Test
    fun `OBJ and STL are millimetres, glTF is metres`() {
        val sketch = sketchWith(ruler())

        val mm = Export.collect(sketch)                          // the OBJ and STL path
        val m = Export.collect(sketch, scale = 1.0)              // the glTF path

        val (loMM, hiMM) = bounds(mm[0].positions)
        val (loM, hiM) = bounds(m[0].positions)

        // a 100mm line is 100 units long in the millimetre path...
        assertEquals(100.0, hiMM.x - loMM.x, 1.0)
        // ...and a tenth of a unit in the metre path
        assertEquals(0.1, hiM.x - loM.x, 0.001)
        assertEquals(1000.0, (hiMM.x - loMM.x) / (hiM.x - loM.x), 1.0)
    }

    @Test
    fun `the OBJ says what unit it is in, because the format cannot`() {
        val obj = Export.objSource(Export.collect(sketchWith(ruler())))
        assertTrue(
            obj.obj.contains("millimetres"),
            "an OBJ with no unit comment is a file nobody can scale correctly",
        )
    }

    // ---- OBJ ------------------------------------------------------------------

    @Test
    fun `an exported OBJ reads back as the same triangles`() {
        /*
         * The strongest check available without another program: write it,
         * read it with our own parser, and compare the geometry.
         */
        val sketch = sketchWith(ruler())
        val parts = Export.collect(sketch)
        val obj = Export.objSource(parts)

        val back = assertNotNull(Import.parseOBJ(obj.obj), "our own OBJ did not parse")
        assertEquals(Export.triangleCount(parts), back.indices.size / 3)

        val (loA, hiA) = bounds(parts[0].positions)
        val (loB, hiB) = bounds(back.positions)
        assertEquals(hiA.x - loA.x, hiB.x - loB.x, 0.01, "the width changed on the way out")
        assertEquals(hiA.y - loA.y, hiB.y - loB.y, 0.01)
    }

    @Test
    fun `the OBJ carries a material file, and one material per distinct look`() {
        val a = ruler()
        val b = ruler().also { it.color = Rgba(0.0, 0.0, 1.0) }
        val c = ruler()                                  // the same look as a
        val obj = Export.objSource(Export.collect(sketchWith(a, b, c)))

        val mtl = assertNotNull(obj.mtl)
        assertEquals(2, Regex("newmtl ").findAll(mtl).count(), "identical strokes need one material")
        assertTrue(obj.obj.contains("mtllib ${obj.name}.mtl"))
        assertTrue(mtl.contains("Kd "), "a material with no diffuse colour shows as white")
    }

    @Test
    fun `a translucent stroke is named and written as translucent`() {
        val s = ruler().also { it.opacity = 0.4 }
        val obj = Export.objSource(Export.collect(sketchWith(s)))
        assertTrue(obj.obj.contains("_a40"), "opacity is not in the material name")
        assertEquals("d 0.4", assertNotNull(obj.mtl).lines().first { it.startsWith("d ") })
    }

    @Test
    fun `OBJ face indices are one-based, which is the whole format's convention`() {
        val obj = Export.objSource(Export.collect(sketchWith(ruler())))
        val faces = obj.obj.lines().filter { it.startsWith("f ") }
        assertTrue(faces.isNotEmpty())
        val lowest = faces.flatMap { line ->
            line.removePrefix("f ").trim().split(" ").map { it.substringBefore("//").toInt() }
        }.min()
        assertEquals(1, lowest, "a zero index makes every reader drop the first vertex")
    }

    // ---- STL --------------------------------------------------------------------

    @Test
    fun `a binary STL is exactly the size the format says, and reads back`() {
        val parts = Export.collect(sketchWith(ruler()))
        val n = Export.triangleCount(parts)
        val bytes = Export.stlBinary(parts)

        assertEquals(84 + n * 50, bytes.size, "a binary STL is 84 + 50 per triangle, exactly")
        assertTrue(Import.looksBinarySTL(bytes))

        val back = assertNotNull(Import.parseSTL(bytes))
        assertEquals(n, back.indices.size / 3)
    }

    @Test
    fun `the binary header does not start with solid, or readers take it for ASCII`() {
        /*
         * Including this build's own importer. A binary file whose header
         * happens to begin "solid" is read as text and comes back empty.
         */
        val bytes = Export.stlBinary(Export.collect(sketchWith(ruler())))
        val head = String(bytes, 0, 5, Charsets.US_ASCII).lowercase()
        assertFalse(head == "solid", "the binary header begins with 'solid'")
    }

    @Test
    fun `an ASCII STL round-trips too, and is recognised as ASCII`() {
        val parts = Export.collect(sketchWith(ruler()))
        val text = Export.stlAscii(parts, "ruler")
        assertTrue(text.startsWith("solid ruler"))
        assertTrue(text.trimEnd().endsWith("endsolid ruler"))

        val bytes = text.toByteArray(Charsets.US_ASCII)
        assertFalse(Import.looksBinarySTL(bytes), "an ASCII file was taken for binary")
        val back = assertNotNull(Import.parseSTL(bytes))
        assertEquals(Export.triangleCount(parts), back.indices.size / 3)
    }

    @Test
    fun `both STL flavours describe the same solid`() {
        val parts = Export.collect(sketchWith(ruler()))
        val fromBinary = assertNotNull(Import.parseSTL(Export.stlBinary(parts)))
        val fromAscii = assertNotNull(
            Import.parseSTL(Export.stlAscii(parts).toByteArray(Charsets.US_ASCII)),
        )
        assertEquals(fromBinary.indices.size, fromAscii.indices.size)

        val (loA, hiA) = bounds(fromBinary.positions)
        val (loB, hiB) = bounds(fromAscii.positions)
        // ASCII is written to four decimals of a millimetre, so they agree to
        // about a micrometre rather than exactly
        assertEquals(hiA.x - loA.x, hiB.x - loB.x, 0.001)
        assertEquals(hiA.y - loA.y, hiB.y - loB.y, 0.001)
    }

    // ---- glTF ---------------------------------------------------------------------

    @Test
    fun `the glTF is one self-contained file with a valid embedded buffer`() {
        val parts = Export.collect(sketchWith(ruler()), scale = 1.0)
        val text = assertNotNull(Export.gltfSource(parts, "ruler"))
        val doc = assertNotNull(Json.parse(text).asObject())

        assertEquals("2.0", doc.obj("asset")?.str("version"))
        assertEquals(0, doc.int("scene", -1))
        for (k in listOf("scenes", "nodes", "meshes", "materials", "accessors", "bufferViews", "buffers")) {
            assertTrue(k in doc, "the glTF is missing '$k'")
        }

        // one buffer, embedded, and exactly as long as it claims
        val buffers = assertNotNull(doc.arr("buffers"))
        assertEquals(1, buffers.size)
        val buf = assertNotNull(buffers[0].asObject())
        val uri = assertNotNull(buf.str("uri"))
        assertTrue(uri.startsWith("data:application/octet-stream;base64,"), "the buffer is a sidecar")
        val bin = java.util.Base64.getDecoder().decode(uri.substringAfter("base64,"))
        assertEquals(buf.int("byteLength", -1), bin.size, "the buffer is not the length it declares")

        // every view lies inside it
        for (v in assertNotNull(doc.arr("bufferViews")).items) {
            val o = assertNotNull(v.asObject())
            assertTrue(o.int("byteOffset", 0) + o.int("byteLength", 0) <= bin.size)
        }
    }

    @Test
    fun `glTF accessors describe the data that is actually there`() {
        val parts = Export.collect(sketchWith(ruler()), scale = 1.0)
        val doc = assertNotNull(Json.parse(assertNotNull(Export.gltfSource(parts))).asObject())
        val accessors = assertNotNull(doc.arr("accessors"))
        val views = assertNotNull(doc.arr("bufferViews"))

        for (a in accessors.items) {
            val o = assertNotNull(a.asObject())
            val view = assertNotNull(views[o.int("bufferView", -1)].asObject())
            // 5126 float VEC3 is 12 bytes; 5125 uint SCALAR is 4
            val stride = if (o.str("type") == "VEC3") 12 else 4
            assertEquals(
                o.int("count", 0) * stride, view.int("byteLength", -1),
                "an accessor claims a count its buffer view cannot hold",
            )
        }

        // and POSITION carries min/max, which the spec requires
        val pos = assertNotNull(accessors[0].asObject())
        assertNotNull(pos.arr("min"), "POSITION without min/max is an invalid glTF")
        assertNotNull(pos.arr("max"))
    }

    @Test
    fun `glTF colour is linear, because baseColorFactor is`() {
        // the colour a person picked is sRGB; handing it over untouched makes
        // every export come out visibly too light
        val mid = Rgba(0.5, 0.5, 0.5)
        val s = ruler().also { it.color = mid }
        val doc = assertNotNull(
            Json.parse(
                assertNotNull(Export.gltfSource(Export.collect(sketchWith(s), 1.0))),
            ).asObject(),
        )
        val f = assertNotNull(
            doc.arr("materials")?.get(0)?.asObject()
                ?.obj("pbrMetallicRoughness")?.arr("baseColorFactor"),
        )
        // 0.5 sRGB is about 0.214 linear — and emphatically not 0.5
        assertEquals(Export.srgbToLinear(mid.r), f[0].asDouble()!!, 1e-9)
        assertTrue(f[0].asDouble()!! < 0.3, "the colour was not converted to linear")
        assertEquals(1.0, f[3].asDouble()!!, 1e-9)
    }

    @Test
    fun `an empty sketch exports nothing rather than an invalid file`() {
        val empty = Export.collect(Sketch())
        assertTrue(empty.isEmpty())
        assertNull(Export.gltfSource(empty), "an empty glTF has no meshes and is invalid")
        assertEquals(0, Export.triangleCount(empty))
        assertEquals(84, Export.stlBinary(empty).size, "an empty STL is still a valid header")
    }

    @Test
    fun `a hidden group is not exported`() {
        val sketch = Sketch()
        val shown = ruler()
        val hidden = ruler()
        sketch.add(shown); sketch.add(hidden)
        val g = sketch.newGroup("scaffold")
        sketch.assign(hidden, g)
        g.visible = false

        assertEquals(1, Export.collect(sketch).size, "a hidden curve was exported")
    }

    // ---- import --------------------------------------------------------------------

    @Test
    fun `an OBJ with negative and slash-form indices still loads`() {
        /*
         * Face refs come in four shapes and any index may be negative, meaning
         * counted back from the end. A parser that assumes the positive
         * `v//vn` form reads a large minority of real files as an empty mesh.
         */
        val text = """
            # a square, written four different ways
            v 0 0 0
            v 1 0 0
            v 1 1 0
            v 0 1 0
            vn 0 0 1
            f 1 2 3
            f 1//1 3//1 4//1
            f -4/1/-1 -3/1/-1 -2/1/-1
            f 1/1 2/1 3/1
        """.trimIndent()
        val s = assertNotNull(Import.parseOBJ(text))
        assertEquals(4, s.indices.size / 3, "not every face form was read")
        for (v in s.positions) assertTrue(v.isFinite())
    }

    @Test
    fun `an OBJ polygon is fanned into triangles`() {
        val text = """
            v 0 0 0
            v 1 0 0
            v 1 1 0
            v 0 1 0
            f 1 2 3 4
        """.trimIndent()
        val s = assertNotNull(Import.parseOBJ(text))
        assertEquals(2, s.indices.size / 3, "a quad should become two triangles")
    }

    @Test
    fun `an OBJ with no normals gets them computed, not left black`() {
        val text = "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3"
        val s = assertNotNull(Import.parseOBJ(text))
        for (i in 0 until s.vertexCount) {
            val n = Vec3(
                s.normals[i * 3].toDouble(),
                s.normals[i * 3 + 1].toDouble(),
                s.normals[i * 3 + 2].toDouble(),
            )
            assertEquals(1.0, n.length(), 1e-5, "vertex $i has no usable normal")
        }
    }

    @Test
    fun `rubbish does not parse into a mesh`() {
        assertNull(Import.parseOBJ(""))
        assertNull(Import.parseOBJ("# just a comment\nmtllib nothing.mtl"))
        assertNull(Import.parseSTL(ByteArray(0)))
        assertNull(Import.parseSTL("not an stl at all".toByteArray()))
    }

    @Test
    fun `an imported model is a guide you can draw on but not fill`() {
        // FACT (C.1): models act as curved guides. An arbitrary mesh has no
        // single arc-length parameterisation, which is why Fill refuses it.
        val parts = Export.collect(sketchWith(ruler()))
        val mesh = assertNotNull(Import.parseSTL(Export.stlBinary(parts)))
        val guide = Import.asGuide(mesh, "ruler")

        assertEquals(GuideKind.MODEL, guide.kind)
        assertNull(GuidePainting.surfaceSpan(guide))
        assertTrue(Fill.fillGuide(guide, Stroke()) is Fill.Result.Refused)

        // ...but the ray query works, which is what painting goes through
        val (lo, hi) = bounds(mesh.positions)
        val mid = Vec3((lo.x + hi.x) / 2, hi.y + 10, (lo.z + hi.z) / 2)
        val hit = mesh.mesh.raycast(Ray(mid, Vec3(0.0, -1.0, 0.0)))
        assertNotNull(hit, "a ray straight down at an imported mesh found nothing")
    }

    @Test
    fun `a sketch survives the whole way out and back through STL`() {
        val sketch = Sketch()
        for (k in 0 until 3) {
            val s = Stroke(brush = "pen", baseRadius = 6.0 * MM)
            for (i in 0 until 12) {
                s.pts.add(StrokePoint(Vec3(i * 0.02, sin(i * 0.4 + k) * 0.05, k * 0.03)))
            }
            sketch.add(s)
        }
        val parts = Export.collect(sketch)
        val back = assertNotNull(Import.parseSTL(Export.stlBinary(parts)))

        assertEquals(Export.triangleCount(parts), back.indices.size / 3)
        val (loA, hiA) = bounds(parts.flatMap { it.positions.toList() }.toFloatArray())
        val (loB, hiB) = bounds(back.positions)
        assertEquals(hiA.x - loA.x, hiB.x - loB.x, 0.01)
        assertEquals(hiA.y - loA.y, hiB.y - loB.y, 0.01)
        assertEquals(hiA.z - loA.z, hiB.z - loB.z, 0.01)
    }
}
