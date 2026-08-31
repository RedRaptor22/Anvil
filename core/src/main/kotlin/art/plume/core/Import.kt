package art.plume.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reading a mesh someone else made, so it can be drawn on as a guide.
 *
 * FACT (C.1): models act as CURVED guides, which means the imported triangles
 * go straight into the same [SurfaceMesh] query the swept guides use. There is
 * no arc-length grid — an arbitrary mesh has no single parameterisation — so a
 * model guide can be painted on but not filled, exactly like a primitive.
 */
object Import {

    /**
     * Wavefront OBJ.
     *
     * Face references come in four shapes — `v`, `v/vt`, `v//vn`, `v/vt/vn` —
     * and any index may be NEGATIVE, meaning "counted back from the end". A
     * parser that assumes the positive form reads a large minority of real
     * files as an empty mesh.
     */
    fun parseOBJ(text: String): GuideSurface? {
        val v = ArrayList<Float>()
        val vn = ArrayList<Float>()
        val pos = ArrayList<Float>()
        val nor = ArrayList<Float>()

        fun emit(ref: String): Boolean {
            val parts = ref.split('/')
            var vi = parts[0].toIntOrNull() ?: return false
            vi = if (vi < 0) v.size / 3 + vi else vi - 1
            if (vi < 0 || vi * 3 + 2 >= v.size) return false
            pos.add(v[vi * 3]); pos.add(v[vi * 3 + 1]); pos.add(v[vi * 3 + 2])

            val ni = if (parts.size > 2) parts[2].toIntOrNull() else null
            if (ni != null) {
                val k = if (ni < 0) vn.size / 3 + ni else ni - 1
                if (k >= 0 && k * 3 + 2 < vn.size) {
                    nor.add(vn[k * 3]); nor.add(vn[k * 3 + 1]); nor.add(vn[k * 3 + 2])
                    return true
                }
            }
            nor.add(0f); nor.add(0f); nor.add(0f)     // filled in below
            return true
        }

        for (raw in text.lineSequence()) {
            val ln = raw.trim()
            if (ln.isEmpty() || ln[0] == '#') continue
            val t = ln.split(Regex("\\s+"))
            when (t[0]) {
                "v" -> if (t.size >= 4) {
                    v.add(t[1].toFloatOrNull() ?: 0f)
                    v.add(t[2].toFloatOrNull() ?: 0f)
                    v.add(t[3].toFloatOrNull() ?: 0f)
                }
                "vn" -> if (t.size >= 4) {
                    vn.add(t[1].toFloatOrNull() ?: 0f)
                    vn.add(t[2].toFloatOrNull() ?: 0f)
                    vn.add(t[3].toFloatOrNull() ?: 0f)
                }
                // a triangle fan over the polygon, so quads and n-gons load
                "f" -> for (k in 2 until t.size - 1) {
                    emit(t[1]); emit(t[k]); emit(t[k + 1])
                }
            }
        }
        if (pos.isEmpty()) return null
        return build(pos.toFloatArray(), nor.toFloatArray())
    }

    /**
     * A binary STL is exactly `84 + 50 * triangles` bytes. Where that does not
     * hold, fall back to sniffing for "solid" — which is why this build's own
     * exporter is careful NOT to begin its 80-byte header with that word.
     */
    fun looksBinarySTL(buf: ByteArray): Boolean {
        if (buf.size < 84) return false
        val tris = ByteBuffer.wrap(buf, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (84 + tris.toLong() * 50 == buf.size.toLong()) return true
        val head = String(buf, 0, minOf(5, buf.size), Charsets.US_ASCII)
        return head.lowercase() != "solid"
    }

    fun parseSTL(buf: ByteArray): GuideSurface? =
        if (looksBinarySTL(buf)) parseBinarySTL(buf)
        else parseAsciiSTL(String(buf, Charsets.US_ASCII))

    private fun parseBinarySTL(buf: ByteArray): GuideSurface? {
        val b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        b.position(80)
        val tris = b.int
        if (tris <= 0 || 84 + tris.toLong() * 50 != buf.size.toLong()) return null

        val pos = FloatArray(tris * 9)
        val nor = FloatArray(tris * 9)
        for (t in 0 until tris) {
            val nx = b.float; val ny = b.float; val nz = b.float
            for (c in 0..2) {
                val o = t * 9 + c * 3
                pos[o] = b.float; pos[o + 1] = b.float; pos[o + 2] = b.float
                nor[o] = nx; nor[o + 1] = ny; nor[o + 2] = nz
            }
            b.short                                  // attribute byte count
        }
        return build(pos, nor)
    }

    private fun parseAsciiSTL(text: String): GuideSurface? {
        val facet = Regex("facet\\s+normal\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)([\\s\\S]*?)endfacet")
        val vertex = Regex("vertex\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)")
        val pos = ArrayList<Float>()
        val nor = ArrayList<Float>()

        for (m in facet.findAll(text)) {
            val nx = m.groupValues[1].toFloatOrNull() ?: 0f
            val ny = m.groupValues[2].toFloatOrNull() ?: 0f
            val nz = m.groupValues[3].toFloatOrNull() ?: 0f
            var count = 0
            for (vm in vertex.findAll(m.groupValues[4])) {
                if (count >= 3) break
                pos.add(vm.groupValues[1].toFloatOrNull() ?: 0f)
                pos.add(vm.groupValues[2].toFloatOrNull() ?: 0f)
                pos.add(vm.groupValues[3].toFloatOrNull() ?: 0f)
                nor.add(nx); nor.add(ny); nor.add(nz)
                count++
            }
            // a facet with fewer than three vertices is not a triangle
            while (count in 1..2) {
                repeat(3) { pos.removeAt(pos.size - 1); nor.removeAt(nor.size - 1) }
                count--
            }
        }
        if (pos.isEmpty()) return null
        return build(pos.toFloatArray(), nor.toFloatArray())
    }

    /**
     * Turn loose triangles into a guide surface, filling in any normal the
     * file did not give — an OBJ without `vn` is common and would otherwise
     * light as a flat black shape.
     */
    private fun build(pos: FloatArray, nor: FloatArray): GuideSurface? {
        val count = pos.size / 3
        if (count < 3) return null
        val indices = IntArray(count) { it }
        val normals = nor.copyOf(pos.size)

        var t = 0
        while (t + 2 < count) {
            val a = t * 3; val b = (t + 1) * 3; val c = (t + 2) * 3
            if (isZero(normals, a) || isZero(normals, b) || isZero(normals, c)) {
                val ux = pos[b] - pos[a]; val uy = pos[b + 1] - pos[a + 1]; val uz = pos[b + 2] - pos[a + 2]
                val wx = pos[c] - pos[a]; val wy = pos[c + 1] - pos[a + 1]; val wz = pos[c + 2] - pos[a + 2]
                var nx = uy * wz - uz * wy
                var ny = uz * wx - ux * wz
                var nz = ux * wy - uy * wx
                val l = kotlin.math.sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat()
                if (l > 0f) { nx /= l; ny /= l; nz /= l } else { nx = 0f; ny = 0f; nz = 1f }
                for (o in intArrayOf(a, b, c)) {
                    if (isZero(normals, o)) {
                        normals[o] = nx; normals[o + 1] = ny; normals[o + 2] = nz
                    }
                }
            }
            t += 3
        }

        // no arc-length grid: an arbitrary mesh has no single parameterisation,
        // which is why a model guide can be painted on but not filled
        return GuideSurface(
            pos, normals, FloatArray(count * 2), indices,
            nu = 0, nv = 0, lu = 0.0, lv = 0.0,
        )
    }

    private fun isZero(a: FloatArray, o: Int): Boolean =
        a[o] == 0f && a[o + 1] == 0f && a[o + 2] == 0f

    /** Wrap an imported mesh as a guide you can draw on. */
    fun asGuide(surface: GuideSurface, name: String = "Model"): Guide {
        val g = Guide(Guide.freshId(), GuideKind.MODEL, name)
        g.surface = surface
        return g
    }
}
