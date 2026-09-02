package art.plume.anvil

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import art.plume.core.MeshData
import art.plume.core.Stroke
import art.plume.core.StrokeGeometry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * The GL ES 3.0 renderer.
 *
 * This is the half that could not be shared with the web build: everything
 * above it — the frames, the geometry, the snap query — lives in `:core` and is
 * the same code the JVM tests exercise. What is here is buffer management and
 * one shader pair, ported from `js/strokes.js`'s VERT/FRAG.
 *
 * One VBO per stroke, uploaded once on commit and never touched again. The web
 * build learned this the hard way: rebuilding a material per frame cost a full
 * shader link per pointermove, which a desktop driver hides and a phone GPU
 * turns into a multi-second stall. Here the program is compiled once, in
 * [onSurfaceCreated], and the only per-frame work is uniforms and draw calls.
 */
class SketchRenderer : GLSurfaceView.Renderer {

    private val strokes = ArrayList<Stroke>()
    private val uploaded = HashMap<Stroke, Buffers>()
    private var live: Stroke? = null

    /*
     * What the model says to show, copied across under the same lock as the
     * stroke list. Two reasons it is a copy rather than a reference to the
     * Sketch: the GL thread must not read a model the UI thread is editing,
     * and a hidden stroke stays UPLOADED — a visibility toggle is a per-frame
     * decision, not a reason to churn four VBOs per stroke every time an eye
     * is pressed.
     */
    private val hidden = HashSet<Stroke>()
    private val selected = HashSet<Stroke>()

    /*
     * Buffers whose stroke has gone, waiting for a thread that can delete them.
     * glDeleteBuffers needs the GL context current, and every caller of
     * release() — invalidate, clear, setStrokes — runs on the UI thread, where
     * there is none. The calls were silently doing nothing and leaking the
     * names; now the ids queue here and the next frame drains them.
     */
    private val pendingDelete = ArrayList<Int>()

    /** Camera, in the same terms as the web build's `P.VIEW`. */
    var theta = 0.6f
    var phi = 1.1f
    var radius = 4.0f
    var roll = 0.0f
    val pivot = floatArrayOf(0f, 0f, 0f)

    private var program = 0
    private var aPos = 0; private var aNor = 0; private var aCol = 0
    private var uMvp = 0; private var uModel = 0
    private var uLightDir = 0; private var uLightCol = 0
    private var uAmbient = 0; private var uIntensity = 0
    private var uSelect = 0

    private val model = FloatArray(16)
    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val mvp = FloatArray(16)
    private val tmp = FloatArray(16)

    private var width = 1
    private var height = 1

    private class Buffers(val vbo: Int, val nbo: Int, val cbo: Int, val ibo: Int, val count: Int)

    // ---- lifecycle ------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.925f, 0.918f, 0.953f, 1f)   // the web build's --bg
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)

        program = link(VERT, FRAG)
        aPos = GLES30.glGetAttribLocation(program, "aPos")
        aNor = GLES30.glGetAttribLocation(program, "aNor")
        aCol = GLES30.glGetAttribLocation(program, "aCol")
        uMvp = GLES30.glGetUniformLocation(program, "uMvp")
        uModel = GLES30.glGetUniformLocation(program, "uModel")
        uLightDir = GLES30.glGetUniformLocation(program, "uLightDir")
        uLightCol = GLES30.glGetUniformLocation(program, "uLightCol")
        uAmbient = GLES30.glGetUniformLocation(program, "uAmbient")
        uIntensity = GLES30.glGetUniformLocation(program, "uIntensity")
        uSelect = GLES30.glGetUniformLocation(program, "uSelect")

        // anything already drawn has to be re-uploaded onto the new context,
        // and its old buffer names died with the old context
        synchronized(strokes) { uploaded.clear(); pendingDelete.clear() }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        GLES30.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        drainDeletes()
        buildCamera()

        GLES30.glUseProgram(program)
        Matrix.setIdentityM(model, 0)
        GLES30.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        // one key light plus soft ambient, the same model as the web build
        GLES30.glUniform3f(uLightDir, 0.40f, 0.62f, 0.68f)
        GLES30.glUniform3f(uLightCol, 1f, 1f, 1f)
        GLES30.glUniform1f(uAmbient, 0.66f)
        GLES30.glUniform1f(uIntensity, 1.0f)

        synchronized(strokes) {
            for (s in strokes) {
                if (s in hidden) continue
                GLES30.glUniform1f(uSelect, if (s in selected) 1f else 0f)
                draw(s)
            }
            live?.let { GLES30.glUniform1f(uSelect, 0f); draw(it) }
        }
    }

    private fun draw(s: Stroke) {
        val b = uploaded[s] ?: upload(s) ?: return
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b.vbo)
        GLES30.glEnableVertexAttribArray(aPos)
        GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b.nbo)
        GLES30.glEnableVertexAttribArray(aNor)
        GLES30.glVertexAttribPointer(aNor, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b.cbo)
        GLES30.glEnableVertexAttribArray(aCol)
        GLES30.glVertexAttribPointer(aCol, 4, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, b.ibo)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, b.count, GLES30.GL_UNSIGNED_INT, 0)
    }

    // ---- geometry in and out -------------------------------------------

    fun addStroke(s: Stroke) = synchronized(strokes) { strokes.add(s); invalidate(s) }

    /**
     * Tell the renderer what the model currently hides and selects.
     *
     * This is the whole of the visibility path. There is no second copy of the
     * flag anywhere else to fall out of step with it: [Sketch] owns the answer,
     * this hands over today's, and the next frame draws it.
     */
    fun setDisplay(hiddenNow: Collection<Stroke>, selectedNow: Collection<Stroke>) =
        synchronized(strokes) {
            hidden.clear(); hidden.addAll(hiddenNow)
            selected.clear(); selected.addAll(selectedNow)
        }

    /** Replace the drawn set wholesale, keeping nothing uploaded that has gone. */
    fun setStrokes(all: List<Stroke>) = synchronized(strokes) {
        for (s in strokes) if (s !in all) release(s)
        strokes.clear(); strokes.addAll(all)
    }
    fun setLive(s: Stroke?) = synchronized(strokes) { live?.let { release(it) }; live = s }
    fun clear() = synchronized(strokes) {
        for (s in strokes) release(s)
        strokes.clear(); live?.let { release(it) }; live = null
        hidden.clear(); selected.clear()
    }

    /** Drop the cached buffers so the next frame re-uploads. */
    fun invalidate(s: Stroke) { release(s) }

    private fun release(s: Stroke) = synchronized(strokes) {
        uploaded.remove(s)?.let {
            pendingDelete.add(it.vbo); pendingDelete.add(it.nbo)
            pendingDelete.add(it.cbo); pendingDelete.add(it.ibo)
        }
        Unit
    }

    /** GL thread only. */
    private fun drainDeletes() {
        val ids = synchronized(strokes) {
            if (pendingDelete.isEmpty()) return
            pendingDelete.toIntArray().also { pendingDelete.clear() }
        }
        GLES30.glDeleteBuffers(ids.size, ids, 0)
    }

    private fun upload(s: Stroke): Buffers? {
        val m: MeshData = StrokeGeometry.build(s) ?: return null
        val ids = IntArray(4)
        GLES30.glGenBuffers(4, ids, 0)
        arrayBuffer(ids[0], m.positions)
        arrayBuffer(ids[1], m.normals)
        arrayBuffer(ids[2], m.colors)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[3])
        val ib: IntBuffer = ByteBuffer.allocateDirect(m.indices.size * 4)
            .order(ByteOrder.nativeOrder()).asIntBuffer().put(m.indices).also { it.position(0) }
        GLES30.glBufferData(
            GLES30.GL_ELEMENT_ARRAY_BUFFER, m.indices.size * 4, ib, GLES30.GL_STATIC_DRAW
        )
        val b = Buffers(ids[0], ids[1], ids[2], ids[3], m.indices.size)
        uploaded[s] = b
        return b
    }

    private fun arrayBuffer(id: Int, data: FloatArray) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, id)
        val fb: FloatBuffer = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(data).also { it.position(0) }
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, fb, GLES30.GL_STATIC_DRAW)
    }

    // ---- camera ---------------------------------------------------------

    private fun buildCamera() {
        val ex = pivot[0] + radius * sin(phi) * sin(theta)
        val ey = pivot[1] + radius * cos(phi)
        val ez = pivot[2] + radius * sin(phi) * cos(theta)
        Matrix.setLookAtM(
            view, 0, ex, ey, ez, pivot[0], pivot[1], pivot[2],
            0f, 1f, 0f
        )
        if (roll != 0f) {
            Matrix.setRotateM(tmp, 0, Math.toDegrees(roll.toDouble()).toFloat(), 0f, 0f, 1f)
            Matrix.multiplyMM(view, 0, tmp.copyOf(), 0, view.copyOf(), 0)
        }
        val aspect = width.toFloat() / height.toFloat().coerceAtLeast(1f)
        // near/far chosen for a sketch measured in metres; the web build runs
        // 0.02 to 8000 and never needed more depth precision than that
        Matrix.perspectiveM(proj, 0, 50f, aspect, 0.02f, 8000f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
    }

    /** Screen pixels to a world ray, for projecting a stroke onto a guide. */
    fun screenToRay(x: Float, y: Float, origin: FloatArray, dir: FloatArray) {
        val nx = 2f * x / width - 1f
        val ny = 1f - 2f * y / height
        val inv = FloatArray(16)
        Matrix.invertM(inv, 0, mvp, 0)
        val near = floatArrayOf(nx, ny, -1f, 1f)
        val far = floatArrayOf(nx, ny, 1f, 1f)
        val a = FloatArray(4); val b = FloatArray(4)
        Matrix.multiplyMV(a, 0, inv, 0, near, 0)
        Matrix.multiplyMV(b, 0, inv, 0, far, 0)
        for (i in 0..2) { a[i] /= a[3]; b[i] /= b[3] }
        origin[0] = a[0]; origin[1] = a[1]; origin[2] = a[2]
        var lx = b[0] - a[0]; var ly = b[1] - a[1]; var lz = b[2] - a[2]
        val len = kotlin.math.sqrt(lx * lx + ly * ly + lz * lz)
        if (len > 0) { lx /= len; ly /= len; lz /= len }
        dir[0] = lx; dir[1] = ly; dir[2] = lz
    }

    // ---- shaders --------------------------------------------------------

    private fun link(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program link failed: " + GLES30.glGetProgramInfoLog(p) }
        GLES30.glDeleteShader(v); GLES30.glDeleteShader(f)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "shader compile failed: " + GLES30.glGetShaderInfoLog(s) }
        return s
    }

    private companion object {
        const val VERT = """#version 300 es
            precision highp float;
            in vec3 aPos;
            in vec3 aNor;
            in vec4 aCol;
            uniform mat4 uMvp;
            uniform mat4 uModel;
            out vec3 vNor;
            out vec4 vCol;
            void main(){
              vNor = mat3(uModel) * aNor;
              vCol = aCol;
              gl_Position = uMvp * vec4(aPos, 1.0);
            }"""

        /* Half-lambert, the same wrap the web build uses: a sketch lit with a
           hard terminator reads as a lump, and wrapping the falloff keeps the
           unlit side legible without washing the form out. */
        const val FRAG = """#version 300 es
            precision highp float;
            in vec3 vNor;
            in vec4 vCol;
            uniform vec3 uLightDir;
            uniform vec3 uLightCol;
            uniform float uAmbient;
            uniform float uIntensity;
            uniform float uSelect;
            out vec4 fragColor;
            void main(){
              vec3 n = normalize(vNor);
              float d = dot(n, normalize(uLightDir)) * 0.5 + 0.5;
              float lit = uAmbient + (1.0 - uAmbient) * d * uIntensity;
              vec3 c = vCol.rgb * uLightCol * lit;
              /* Selection is a tint, not a replacement: a solid highlight
                 colour hides which curve you picked out of several. */
              c = mix(c, vec3(0.35, 0.78, 0.60), uSelect * 0.55);
              fragColor = vec4(c, vCol.a);
            }"""
    }
}
