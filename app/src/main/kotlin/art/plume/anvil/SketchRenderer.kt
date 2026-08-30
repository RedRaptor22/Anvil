package art.plume.anvil

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import art.plume.core.Camera
import art.plume.core.Grid
import art.plume.core.LiveStroke
import art.plume.core.MeshData
import art.plume.core.Rgba
import art.plume.core.Stroke
import art.plume.core.StrokeGeometry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The GL ES 3.0 renderer.
 *
 * This is the half that could not be shared with the web build: everything
 * above it — the frames, the geometry, the camera, the snap query — lives in
 * `:core` and is the same code the JVM tests exercise. What is here is buffer
 * management and two shader pairs.
 *
 * Two rules hold this together, and both were learned the hard way in the web
 * build:
 *
 *  - **The program is compiled once.** Rebuilding a material per frame cost a
 *    full shader link per pointermove there, which a desktop driver hides and a
 *    phone GPU turns into a multi-second stall.
 *  - **A committed stroke is uploaded once and never touched again.** The
 *    stroke being drawn goes into a separate DYNAMIC buffer that is re-uploaded
 *    only over the range [LiveStroke] says has changed — a couple of rings,
 *    not the whole tube. Without that, drawing is quadratic in stroke length.
 *
 * Threading: the camera and the live buffer are written on the UI thread and
 * read here on the GL thread, so both cross a lock. The committed stroke list
 * has always done so.
 */
class SketchRenderer : GLSurfaceView.Renderer {

    private val strokes = ArrayList<Stroke>()
    private val uploaded = HashMap<Stroke, Buffers>()

    private var live: LiveStroke? = null
    private var liveBuffers: LiveBuffers? = null

    /** Environment, in the same terms as the web build's `P.ENV`. */
    var background = Rgba(0.925, 0.918, 0.953)      // the web build's --bg
    var showGrid = true
    /** FACT: the Global Axis is off by default. */
    var showAxis = false

    private val matrixLock = Any()
    private val mvp = FloatArray(16)

    private var program = 0
    private var aPos = 0; private var aNor = 0; private var aCol = 0
    private var uMvp = 0
    private var uLightDir = 0; private var uLightCol = 0
    private var uAmbient = 0; private var uIntensity = 0

    private var lineProgram = 0
    private var lPos = 0; private var lCol = 0; private var lMvp = 0

    private var gridBuffers: LineBuffers? = null
    private var axisBuffers: LineBuffers? = null
    private var gridSignature = ""

    /**
     * Buffer names whose stroke has gone, waiting for a GL thread to delete
     * them. `glDeleteBuffers` is only legal with a current context, and every
     * caller that drops a stroke — undo, redo, clear — is the UI thread. Doing
     * it there deletes nothing and leaks the buffer on a good driver, and takes
     * out whatever else owns that name on a bad one.
     */
    private val pendingDelete = ArrayList<Int>()

    private class Buffers(val vbo: Int, val nbo: Int, val cbo: Int, val ibo: Int, val count: Int)
    private class LineBuffers(val vbo: Int, val cbo: Int, val count: Int)

    /** The dynamic buffers behind the stroke currently being drawn. */
    private class LiveBuffers(
        val vbo: Int, val nbo: Int, val cbo: Int, val ibo: Int,
    ) {
        var vertexCapacity = 0
        var indexCapacity = 0
    }

    // ---- lifecycle ------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(
            background.r.toFloat(), background.g.toFloat(), background.b.toFloat(), 1f,
        )
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)

        program = link(VERT, FRAG)
        aPos = GLES30.glGetAttribLocation(program, "aPos")
        aNor = GLES30.glGetAttribLocation(program, "aNor")
        aCol = GLES30.glGetAttribLocation(program, "aCol")
        uMvp = GLES30.glGetUniformLocation(program, "uMvp")
        uLightDir = GLES30.glGetUniformLocation(program, "uLightDir")
        uLightCol = GLES30.glGetUniformLocation(program, "uLightCol")
        uAmbient = GLES30.glGetUniformLocation(program, "uAmbient")
        uIntensity = GLES30.glGetUniformLocation(program, "uIntensity")

        lineProgram = link(LINE_VERT, LINE_FRAG)
        lPos = GLES30.glGetAttribLocation(lineProgram, "aPos")
        lCol = GLES30.glGetAttribLocation(lineProgram, "aCol")
        lMvp = GLES30.glGetUniformLocation(lineProgram, "uMvp")

        /*
         * Every buffer name belonged to the context that just went away. Drop
         * the bookkeeping rather than deleting: the names are already invalid,
         * and glDeleteBuffers on a fresh context would be deleting whatever now
         * holds those numbers.
         */
        uploaded.clear()
        liveBuffers = null
        gridBuffers = null; axisBuffers = null; gridSignature = ""
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES30.glViewport(0, 0, w, h)
    }

    /** Copy the camera's matrix across the thread boundary. */
    fun setCamera(camera: Camera) {
        synchronized(matrixLock) {
            camera.viewProjection.into(mvp)
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClearColor(
            background.r.toFloat(), background.g.toFloat(), background.b.toFloat(), 1f,
        )
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        drainDeletions()

        val m = FloatArray(16)
        synchronized(matrixLock) { mvp.copyInto(m) }

        drawEnvironment(m)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, m, 0)
        // one key light plus soft ambient, the same model as the web build
        GLES30.glUniform3f(uLightDir, 0.40f, 0.62f, 0.68f)
        GLES30.glUniform3f(uLightCol, 1f, 1f, 1f)
        GLES30.glUniform1f(uAmbient, 0.66f)
        GLES30.glUniform1f(uIntensity, 1.0f)

        synchronized(strokes) {
            for (s in strokes) draw(s)
        }
        drawLive()
    }

    // ---- environment ----------------------------------------------------

    private fun drawEnvironment(m: FloatArray) {
        if (!showGrid && !showAxis) return
        val signature = "${background.r},${background.g},${background.b}"
        if (gridBuffers == null || signature != gridSignature) {
            gridBuffers?.let { GLES30.glDeleteBuffers(2, intArrayOf(it.vbo, it.cbo), 0) }
            gridBuffers = uploadLines(Grid.build(background))
            gridSignature = signature
        }
        if (axisBuffers == null) axisBuffers = uploadLines(Grid.axis())

        GLES30.glUseProgram(lineProgram)
        GLES30.glUniformMatrix4fv(lMvp, 1, false, m, 0)
        /*
         * The grid is scaffolding, not ink: it blends and does NOT write depth,
         * so a stroke lying on the ground plane is never z-fought into stripes
         * by the line it is resting on.
         */
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        if (showGrid) gridBuffers?.let { drawLines(it) }
        if (showAxis) axisBuffers?.let { drawLines(it) }
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun uploadLines(lines: Grid.Lines): LineBuffers {
        val ids = IntArray(2)
        GLES30.glGenBuffers(2, ids, 0)
        arrayBuffer(ids[0], lines.positions, GLES30.GL_STATIC_DRAW)
        arrayBuffer(ids[1], lines.colors, GLES30.GL_STATIC_DRAW)
        return LineBuffers(ids[0], ids[1], lines.vertexCount)
    }

    private fun drawLines(b: LineBuffers) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b.vbo)
        GLES30.glEnableVertexAttribArray(lPos)
        GLES30.glVertexAttribPointer(lPos, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b.cbo)
        GLES30.glEnableVertexAttribArray(lCol)
        GLES30.glVertexAttribPointer(lCol, 4, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, b.count)
        /*
         * Leave the attribute arrays as we found them. An enabled array the
         * next program does not declare is harmless by the spec, but the two
         * programs here get their locations assigned by the driver and nothing
         * says they agree — turning them off is one call and removes a class of
         * bug that would only ever appear on someone else's phone.
         */
        GLES30.glDisableVertexAttribArray(lPos)
        GLES30.glDisableVertexAttribArray(lCol)
    }

    // ---- committed strokes ----------------------------------------------

    fun addStroke(s: Stroke): Unit = synchronized(strokes) { strokes.add(s); release(s) }

    fun removeStroke(s: Stroke): Unit = synchronized(strokes) { strokes.remove(s); release(s) }

    fun setStrokes(list: List<Stroke>): Unit = synchronized(strokes) {
        for (s in strokes) release(s)
        strokes.clear()
        strokes.addAll(list)
    }

    fun clear(): Unit = synchronized(strokes) {
        for (s in strokes) release(s)
        strokes.clear()
    }

    /** Drop the cached buffers so the next frame re-uploads. */
    fun invalidate(s: Stroke): Unit = synchronized(strokes) { release(s) }

    private fun release(s: Stroke) {
        uploaded.remove(s)?.let {
            pendingDelete.add(it.vbo); pendingDelete.add(it.nbo)
            pendingDelete.add(it.cbo); pendingDelete.add(it.ibo)
        }
    }

    /** Called on the GL thread, where deleting a buffer is actually legal. */
    private fun drainDeletions() {
        val ids = synchronized(strokes) {
            if (pendingDelete.isEmpty()) return
            pendingDelete.toIntArray().also { pendingDelete.clear() }
        }
        GLES30.glDeleteBuffers(ids.size, ids, 0)
    }

    private fun draw(s: Stroke) {
        val b = uploaded[s] ?: upload(s) ?: return
        bindAndDraw(b.vbo, b.nbo, b.cbo, b.ibo, b.count)
    }

    private fun bindAndDraw(vbo: Int, nbo: Int, cbo: Int, ibo: Int, count: Int) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(aPos)
        GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, nbo)
        GLES30.glEnableVertexAttribArray(aNor)
        GLES30.glVertexAttribPointer(aNor, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cbo)
        GLES30.glEnableVertexAttribArray(aCol)
        GLES30.glVertexAttribPointer(aCol, 4, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, count, GLES30.GL_UNSIGNED_INT, 0)
    }

    private fun upload(s: Stroke): Buffers? {
        val m: MeshData = StrokeGeometry.build(s) ?: return null
        val ids = IntArray(4)
        GLES30.glGenBuffers(4, ids, 0)
        arrayBuffer(ids[0], m.positions, GLES30.GL_STATIC_DRAW)
        arrayBuffer(ids[1], m.normals, GLES30.GL_STATIC_DRAW)
        arrayBuffer(ids[2], m.colors, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[3])
        GLES30.glBufferData(
            GLES30.GL_ELEMENT_ARRAY_BUFFER, m.indices.size * 4,
            intBuffer(m.indices, m.indices.size), GLES30.GL_STATIC_DRAW,
        )
        val b = Buffers(ids[0], ids[1], ids[2], ids[3], m.indices.size)
        uploaded[s] = b
        return b
    }

    // ---- the stroke being drawn -----------------------------------------

    /** Hand over the live buffer; null ends the preview. */
    fun setLive(buffer: LiveStroke?) {
        synchronized(strokes) { live = buffer }
    }

    private fun drawLive() {
        val buffer = synchronized(strokes) { live } ?: return
        // the UI thread appends to this while we read it
        synchronized(buffer) {
            if (buffer.pointCount < 2 || buffer.indexCount == 0) return
            val b = liveBuffers ?: newLiveBuffers().also { liveBuffers = it }
            syncLive(b, buffer)
            buffer.clearDirty()
            bindAndDraw(b.vbo, b.nbo, b.cbo, b.ibo, buffer.indexCount)
        }
    }

    private fun newLiveBuffers(): LiveBuffers {
        val ids = IntArray(4)
        GLES30.glGenBuffers(4, ids, 0)
        return LiveBuffers(ids[0], ids[1], ids[2], ids[3])
    }

    /**
     * Push only what changed.
     *
     * When the core arrays have grown, the whole thing is re-uploaded because
     * the GL buffer is the wrong size — that happens O(log n) times over a
     * stroke. Otherwise this is two ring's worth of vertices, two cap centres
     * and a band of indices, whatever the length of the stroke.
     */
    private fun syncLive(b: LiveBuffers, s: LiveStroke) {
        val verts = s.vertexCount
        if (verts > b.vertexCapacity) {
            arrayBuffer(b.vbo, s.positions, GLES30.GL_DYNAMIC_DRAW)
            arrayBuffer(b.nbo, s.normals, GLES30.GL_DYNAMIC_DRAW)
            arrayBuffer(b.cbo, s.colors, GLES30.GL_DYNAMIC_DRAW)
            b.vertexCapacity = s.positions.size / 3
        } else {
            if (s.capsDirty) {
                subVertices(b.vbo, s.positions, 0, 2, 3)
                subVertices(b.nbo, s.normals, 0, 2, 3)
                subVertices(b.cbo, s.colors, 0, 2, 4)
            }
            if (s.dirtyTo > s.dirtyFrom) {
                subVertices(b.vbo, s.positions, s.dirtyFrom, s.dirtyTo, 3)
                subVertices(b.nbo, s.normals, s.dirtyFrom, s.dirtyTo, 3)
                subVertices(b.cbo, s.colors, s.dirtyFrom, s.dirtyTo, 4)
            }
        }

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, b.ibo)
        if (s.indices.size > b.indexCapacity) {
            GLES30.glBufferData(
                GLES30.GL_ELEMENT_ARRAY_BUFFER, s.indices.size * 4,
                intBuffer(s.indices, s.indices.size), GLES30.GL_DYNAMIC_DRAW,
            )
            b.indexCapacity = s.indices.size
        } else if (s.indexDirtyTo > s.indexDirtyFrom) {
            val from = s.indexDirtyFrom
            val count = s.indexDirtyTo - from
            GLES30.glBufferSubData(
                GLES30.GL_ELEMENT_ARRAY_BUFFER, from * 4, count * 4,
                intBuffer(s.indices, count, from),
            )
        }
    }

    private fun subVertices(id: Int, data: FloatArray, from: Int, to: Int, stride: Int) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, id)
        val offset = from * stride
        val count = (to - from) * stride
        if (count <= 0 || offset + count > data.size) return
        GLES30.glBufferSubData(
            GLES30.GL_ARRAY_BUFFER, offset * 4, count * 4, floatBuffer(data, count, offset),
        )
    }

    // ---- buffer plumbing ------------------------------------------------

    private fun arrayBuffer(id: Int, data: FloatArray, usage: Int) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, id)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER, data.size * 4, floatBuffer(data, data.size), usage,
        )
    }

    private fun floatBuffer(data: FloatArray, count: Int, offset: Int = 0): FloatBuffer =
        ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(data, offset, count).also { it.position(0) }

    private fun intBuffer(data: IntArray, count: Int, offset: Int = 0): IntBuffer =
        ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
            .put(data, offset, count).also { it.position(0) }

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
            out vec3 vNor;
            out vec4 vCol;
            void main(){
              vNor = aNor;
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
            out vec4 fragColor;
            void main(){
              vec3 n = normalize(vNor);
              float d = dot(n, normalize(uLightDir)) * 0.5 + 0.5;
              float lit = uAmbient + (1.0 - uAmbient) * d * uIntensity;
              fragColor = vec4(vCol.rgb * uLightCol * lit, vCol.a);
            }"""

        const val LINE_VERT = """#version 300 es
            precision highp float;
            in vec3 aPos;
            in vec4 aCol;
            uniform mat4 uMvp;
            out vec4 vCol;
            void main(){
              vCol = aCol;
              gl_Position = uMvp * vec4(aPos, 1.0);
            }"""

        const val LINE_FRAG = """#version 300 es
            precision highp float;
            in vec4 vCol;
            out vec4 fragColor;
            void main(){ fragColor = vCol; }"""
    }
}
