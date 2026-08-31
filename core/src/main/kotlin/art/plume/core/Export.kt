package art.plume.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One mesh, flattened to world-space triangle soup in the target's units. */
class MeshPart(
    val name: String,
    val positions: FloatArray,
    val normals: FloatArray,
    val triangles: IntArray,
    val color: Rgba,
    val opacity: Double,
)

class ObjExport(val obj: String, val mtl: String?, val name: String)

/**
 * Getting a sketch out into something else.
 *
 * **The units are the thing to get right, and the two paths differ on
 * purpose.** OBJ and STL are written in MILLIMETRES, because that is what a
 * printer and a CAD package expect and neither format declares its own unit.
 * glTF is written in METRES, because glTF *does* declare one and a Plume world
 * unit already is a metre (`MM = 0.001`, so one unit is 1000 mm). Exporting
 * glTF at the OBJ scale would hand Blender a sketch a thousand times too big.
 */
object Export {

    /** 1 world unit is 1000 mm. */
    const val MM_PER_UNIT = 1000.0

    /** Below this a triangle is a line, and no format wants it. */
    private const val AREA_EPS = 1e-18

    /**
     * Four decimals of a millimetre is 100 nanometres — far below the
     * quantisation the document format itself uses — and trimming the zeros
     * roughly halves an ASCII export.
     */
    fun fmt(n: Double, prec: Int = 4): String {
        if (!n.isFinite()) return "0"
        var s = String.format("%.${prec}f", n)
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        return if (s == "-0" || s.isEmpty()) "0" else s
    }

    /** Normals are unit vectors, so they get another decimal. */
    private fun fmtN(n: Double) = fmt(n, 5)

    fun safeName(s: String?, fallback: String): String {
        val cleaned = (s ?: "").trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
        return cleaned.ifEmpty { fallback }
    }

    fun hex(c: Rgba): String {
        fun ch(v: Double) = clamp(Math.round(v * 255).toInt(), 0, 255)
        return "%02x%02x%02x".format(ch(c.r), ch(c.g), ch(c.b))
    }

    /**
     * A material name that groups by what actually differs. Two strokes the
     * same colour and opacity share one material rather than producing a
     * thousand identical ones an importer then has to show in a list.
     */
    fun materialKey(part: MeshPart): String {
        val a = Math.round(clamp(part.opacity, 0.0, 1.0) * 100).toInt()
        return "plume_" + hex(part.color) + if (a < 100) "_a$a" else ""
    }

    /**
     * Every visible curve as triangle soup, scaled into the target's units.
     *
     * Degenerate triangles are dropped here rather than in each writer: a
     * sliver with no area is a line, and an STL full of them is a mesh a
     * printer will reject.
     */
    fun collect(sketch: Sketch, scale: Double = MM_PER_UNIT): List<MeshPart> {
        val parts = ArrayList<MeshPart>()
        var n = 0
        for (st in sketch.strokes) {
            if (!sketch.visible(st)) continue
            val mesh = StrokeGeometry.build(st) ?: continue
            val pos = FloatArray(mesh.positions.size)
            for (i in pos.indices) pos[i] = (mesh.positions[i] * scale).toFloat()

            val kept = ArrayList<Int>(mesh.indices.size)
            var t = 0
            while (t < mesh.indices.size) {
                val a = mesh.indices[t]; val b = mesh.indices[t + 1]; val c = mesh.indices[t + 2]
                if (area2(pos, a, b, c) > AREA_EPS) { kept.add(a); kept.add(b); kept.add(c) }
                t += 3
            }
            if (kept.isEmpty()) continue

            parts.add(
                MeshPart(
                    "stroke_${++n}", pos, mesh.normals, kept.toIntArray(),
                    st.color, st.opacity,
                ),
            )
        }
        return parts
    }

    private fun area2(pos: FloatArray, a: Int, b: Int, c: Int): Double {
        val ax = pos[a * 3].toDouble(); val ay = pos[a * 3 + 1].toDouble()
        val az = pos[a * 3 + 2].toDouble()
        val ux = pos[b * 3] - ax; val uy = pos[b * 3 + 1] - ay; val uz = pos[b * 3 + 2] - az
        val wx = pos[c * 3] - ax; val wy = pos[c * 3 + 1] - ay; val wz = pos[c * 3 + 2] - az
        val nx = uy * wz - uz * wy
        val ny = uz * wx - ux * wz
        val nz = ux * wy - uy * wx
        return nx * nx + ny * ny + nz * nz
    }

    fun triangleCount(parts: List<MeshPart>): Int = parts.sumOf { it.triangles.size / 3 }

    // ---- OBJ + MTL ---------------------------------------------------------

    fun objSource(parts: List<MeshPart>, name: String? = null, withMtl: Boolean = true): ObjExport {
        val n = safeName(name, "plume")
        val out = StringBuilder()
        out.append("# Plume - 3D sketch export\n")
        out.append("# units: millimetres (1 Plume grid unit = 1000 mm)\n")
        out.append("# ${parts.size} object(s)\n")
        if (withMtl) out.append("mtllib $n.mtl\n")

        val mats = LinkedHashMap<String, MeshPart>()
        var base = 1
        for (part in parts) {
            val count = part.positions.size / 3
            val key = materialKey(part)
            mats[key] = part

            out.append("o ${part.name}\n")
            for (j in 0 until count) {
                out.append("v ")
                    .append(fmt(part.positions[j * 3].toDouble())).append(' ')
                    .append(fmt(part.positions[j * 3 + 1].toDouble())).append(' ')
                    .append(fmt(part.positions[j * 3 + 2].toDouble())).append('\n')
            }
            for (j in 0 until count) {
                out.append("vn ")
                    .append(fmtN(part.normals[j * 3].toDouble())).append(' ')
                    .append(fmtN(part.normals[j * 3 + 1].toDouble())).append(' ')
                    .append(fmtN(part.normals[j * 3 + 2].toDouble())).append('\n')
            }
            if (withMtl) out.append("usemtl $key\n")
            var j = 0
            while (j < part.triangles.size) {
                // v//vn - there are no texture coordinates to reference
                val a = base + part.triangles[j]
                val b = base + part.triangles[j + 1]
                val c = base + part.triangles[j + 2]
                out.append("f $a//$a $b//$b $c//$c\n")
                j += 3
            }
            base += count
        }

        var mtl: String? = null
        if (withMtl) {
            val ml = StringBuilder("# Plume - materials\n")
            for ((k, p) in mats) {
                val d = clamp(p.opacity, 0.0, 1.0)
                ml.append("\nnewmtl $k\n")
                ml.append("Kd ${fmtN(p.color.r)} ${fmtN(p.color.g)} ${fmtN(p.color.b)}\n")
                ml.append("Ka 0 0 0\nKs 0.04 0.04 0.04\nNs 24\n")
                ml.append("d ${fmtN(d)}\nillum 2\n")
            }
            mtl = ml.toString()
        }
        return ObjExport(out.toString(), mtl, n)
    }

    // ---- STL ---------------------------------------------------------------

    private fun facetNormal(pos: FloatArray, a: Int, b: Int, c: Int, o: DoubleArray) {
        val ax = pos[a * 3].toDouble(); val ay = pos[a * 3 + 1].toDouble()
        val az = pos[a * 3 + 2].toDouble()
        val ux = pos[b * 3] - ax; val uy = pos[b * 3 + 1] - ay; val uz = pos[b * 3 + 2] - az
        val wx = pos[c * 3] - ax; val wy = pos[c * 3 + 1] - ay; val wz = pos[c * 3 + 2] - az
        val nx = uy * wz - uz * wy
        val ny = uz * wx - ux * wz
        val nz = ux * wy - uy * wx
        val l = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).let { if (it == 0.0) 1.0 else it }
        o[0] = nx / l; o[1] = ny / l; o[2] = nz / l
    }

    fun stlBinary(parts: List<MeshPart>): ByteArray {
        val n = triangleCount(parts)
        val buf = ByteBuffer.allocate(84 + n * 50).order(ByteOrder.LITTLE_ENDIAN)

        /*
         * The 80-byte header must NOT begin with "solid", or a sniffing reader
         * takes the file for ASCII - including this build's own importer.
         */
        val head = "Plume sketch export - millimetres - $n triangles"
        for (h in 0 until 80) {
            val code = if (h < head.length) head[h].code else 32
            buf.put(if (code < 32 || code > 126) 32 else code.toByte())
        }
        buf.putInt(n)

        val nrm = DoubleArray(3)
        for (part in parts) {
            var j = 0
            while (j < part.triangles.size) {
                val a = part.triangles[j]; val b = part.triangles[j + 1]
                val c = part.triangles[j + 2]
                facetNormal(part.positions, a, b, c, nrm)
                buf.putFloat(nrm[0].toFloat())
                buf.putFloat(nrm[1].toFloat())
                buf.putFloat(nrm[2].toFloat())
                for (v in intArrayOf(a, b, c)) {
                    buf.putFloat(part.positions[v * 3])
                    buf.putFloat(part.positions[v * 3 + 1])
                    buf.putFloat(part.positions[v * 3 + 2])
                }
                buf.putShort(0)                     // attribute byte count
                j += 3
            }
        }
        return buf.array()
    }

    fun stlAscii(parts: List<MeshPart>, name: String? = null): String {
        val n = safeName(name, "plume")
        val out = StringBuilder("solid $n\n")
        val nrm = DoubleArray(3)
        for (part in parts) {
            var j = 0
            while (j < part.triangles.size) {
                val a = part.triangles[j]; val b = part.triangles[j + 1]
                val c = part.triangles[j + 2]
                facetNormal(part.positions, a, b, c, nrm)
                out.append("facet normal ${fmtN(nrm[0])} ${fmtN(nrm[1])} ${fmtN(nrm[2])}\n")
                out.append("  outer loop\n")
                for (v in intArrayOf(a, b, c)) {
                    out.append("    vertex ")
                        .append(fmt(part.positions[v * 3].toDouble())).append(' ')
                        .append(fmt(part.positions[v * 3 + 1].toDouble())).append(' ')
                        .append(fmt(part.positions[v * 3 + 2].toDouble())).append('\n')
                }
                out.append("  endloop\nendfacet\n")
                j += 3
            }
        }
        out.append("endsolid $n\n")
        return out.toString()
    }

    // ---- glTF 2.0 ------------------------------------------------------------

    /** glTF's baseColorFactor is linear; the colour a person picked is sRGB. */
    fun srgbToLinear(c: Double): Double =
        if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)

    /**
     * A single self-contained `.gltf`: the JSON carries one embedded buffer as
     * a data URI, so there is no sidecar to lose the way an OBJ loses its
     * `.mtl`.
     *
     * Colour rides on the MATERIAL rather than on COLOR_0 — a stroke is one
     * colour throughout, and a baseColorFactor is what an importer will
     * actually show.
     */
    fun gltfSource(parts: List<MeshPart>, name: String? = null): String? {
        val n = safeName(name, "plume")
        val views = JsonArray()
        val accessors = JsonArray()
        val meshes = JsonArray()
        val nodes = JsonArray()
        val materials = JsonArray()
        val matIndex = HashMap<String, Int>()
        val blob = java.io.ByteArrayOutputStream()

        fun pushView(bytes: ByteArray, target: Int): Int {
            val v = JsonObject()
                .put("buffer", 0)
                .put("byteOffset", blob.size())
                .put("byteLength", bytes.size)
            if (target != 0) v.put("target", target)
            views.add(v)
            blob.write(bytes)
            // every accessor here is 4 bytes wide, so offsets stay aligned on
            // their own - but pad anyway rather than depend on that staying true
            while (blob.size() % 4 != 0) blob.write(0)
            return views.size - 1
        }

        fun floats(a: FloatArray): ByteArray {
            val b = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (f in a) b.putFloat(f)
            return b.array()
        }

        fun ints(a: IntArray): ByteArray {
            val b = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in a) b.putInt(i)
            return b.array()
        }

        for (part in parts) {
            val count = part.positions.size / 3
            if (count == 0 || part.triangles.isEmpty()) continue

            val lo = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
            val hi = doubleArrayOf(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
            var i = 0
            while (i < part.positions.size) {
                for (k in 0..2) {
                    val v = part.positions[i + k].toDouble()
                    if (v < lo[k]) lo[k] = v
                    if (v > hi[k]) hi[k] = v
                }
                i += 3
            }

            val vPos = pushView(floats(part.positions), 34962)
            val vNor = pushView(floats(part.normals), 34962)
            val vIdx = pushView(ints(part.triangles), 34963)

            accessors.add(
                JsonObject().put("bufferView", vPos).put("componentType", 5126)
                    .put("count", count).put("type", "VEC3")
                    .put("min", JsonArray.of(lo)).put("max", JsonArray.of(hi)),
            )
            accessors.add(
                JsonObject().put("bufferView", vNor).put("componentType", 5126)
                    .put("count", count).put("type", "VEC3"),
            )
            accessors.add(
                JsonObject().put("bufferView", vIdx).put("componentType", 5125)
                    .put("count", part.triangles.size).put("type", "SCALAR"),
            )
            val aPos = accessors.size - 3
            val aNor = accessors.size - 2
            val aIdx = accessors.size - 1

            val key = materialKey(part)
            val mat = matIndex.getOrPut(key) {
                val alpha = clamp(part.opacity, 0.0, 1.0)
                materials.add(
                    JsonObject().put("name", key).put(
                        "pbrMetallicRoughness",
                        JsonObject()
                            .put(
                                "baseColorFactor",
                                JsonArray.of(
                                    doubleArrayOf(
                                        srgbToLinear(part.color.r),
                                        srgbToLinear(part.color.g),
                                        srgbToLinear(part.color.b),
                                        alpha,
                                    ),
                                ),
                            )
                            .put("metallicFactor", 0)
                            .put("roughnessFactor", 0.85),
                    )
                        .put("doubleSided", true)
                        .put("alphaMode", if (alpha < 1) "BLEND" else "OPAQUE"),
                )
                materials.size - 1
            }

            meshes.add(
                JsonObject().put("name", part.name).put(
                    "primitives",
                    JsonArray().add(
                        JsonObject()
                            .put(
                                "attributes",
                                JsonObject().put("POSITION", aPos).put("NORMAL", aNor),
                            )
                            .put("indices", aIdx).put("material", mat).put("mode", 4),
                    ),
                ),
            )
            nodes.add(JsonObject().put("mesh", meshes.size - 1).put("name", part.name))
        }

        if (meshes.size == 0) return null

        val bin = blob.toByteArray()
        val sceneNodes = JsonArray()
        for (i in 0 until nodes.size) sceneNodes.add(JsonNumber(i.toDouble()))

        val doc = JsonObject()
            .put(
                "asset",
                JsonObject().put("version", "2.0").put("generator", "Plume - 3D sketchbook"),
            )
            .put("scene", 0)
            .put("scenes", JsonArray().add(JsonObject().put("name", n).put("nodes", sceneNodes)))
            .put("nodes", nodes)
            .put("meshes", meshes)
            .put("materials", materials)
            .put("accessors", accessors)
            .put("bufferViews", views)
            .put(
                "buffers",
                JsonArray().add(
                    JsonObject().put("byteLength", bin.size).put(
                        "uri",
                        "data:application/octet-stream;base64," +
                            java.util.Base64.getEncoder().encodeToString(bin),
                    ),
                ),
            )
        return doc.write()
    }
}
