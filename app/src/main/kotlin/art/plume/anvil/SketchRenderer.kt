package art.plume.anvil

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import art.plume.core.Bounds
import art.plume.core.Camera
import art.plume.core.DocumentEnv
import art.plume.core.Fx
import art.plume.core.Grid
import art.plume.core.Guide
import art.plume.core.GuideKind
import art.plume.core.GroundShadow
import art.plume.core.GuideSurface
import art.plume.core.Light
import art.plume.core.LiveStroke
import art.plume.core.Mat4
import art.plume.core.MeshData
import art.plume.core.Rgba
import art.plume.core.ShadowFit
import art.plume.core.Stroke
import art.plume.core.StrokeGeometry
import art.plume.core.Symmetry
import art.plume.core.Tune
import art.plume.core.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.min

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

    private var guides: List<Guide> = emptyList()
    private val guideUploaded = HashMap<GuideSurface, GuideBuffers>()

    /** Environment, in the same terms as the web build's `P.ENV`. */
    var background = Rgba(0.925, 0.918, 0.953)      // the web build's --bg
    var showGrid = true
    /** FACT: the Global Axis is off by default. */
    var showAxis = false

    /**
     * The rest of `P.ENV`, plus the light and the effects, which belong to the
     * SKETCH rather than to the app and arrive from the document.
     *
     * Read on the GL thread and written on the UI thread, so like the camera
     * they cross [matrixLock] — a light half-updated between two draw calls
     * would show as a one-frame flash of the wrong colour.
     */
    var shaded = true
    var fog = false
    var renderMode = false
    var groundShadow = true
    val light = Light()
    val fx = Fx()

    fun setEnvironment(env: DocumentEnv) = synchronized(matrixLock) {
        background = env.background
        showGrid = env.grid
        showAxis = env.axis
        shaded = env.shaded
        fog = env.fog
        renderMode = env.render
        groundShadow = env.groundShadow
        light.copyFrom(env.light)
        fx.copyFrom(env.fx)
        shadowDirty = true
    }

    private val matrixLock = Any()
    private val mvp = FloatArray(16)
    private val eye = FloatArray(3)

    private var program = 0
    private var aPos = 0; private var aNor = 0; private var aCol = 0
    private var uMvp = 0
    private var uLightDir = 0; private var uLightCol = 0
    private var uAmbient = 0; private var uIntensity = 0
    private var uToon = 0; private var uToonStep = 0
    private var uEye = 0; private var uFogCol = 0
    private var uFogNear = 0; private var uFogFar = 0
    private var uShade = 0; private var uGlow = 0
    private var uGrit = 0; private var uSelect = 0; private var uFade = 0

    private var lineProgram = 0
    private var lPos = 0; private var lCol = 0; private var lMvp = 0

    private var guideProgram = 0
    private var gPos = 0; private var gNor = 0; private var gUvw = 0
    private var gMvp = 0; private var gEye = 0
    private var gFill = 0; private var gLine = 0
    private var gOpacity = 0; private var gStep = 0; private var gMode = 0; private var gSelect = 0

    private var gridBuffers: LineBuffers? = null
    private var axisBuffers: LineBuffers? = null
    private var gridSignature = ""

    /**
     * Where the symmetry folds, when there is any. Rebuilt on the UI thread
     * whenever the mirror, the radial count or the sketch's extent changes.
     */
    private var fold: Symmetry.Fold? = null
    private var foldFill: LineBuffers? = null
    private var foldEdge: LineBuffers? = null
    private var foldAxis: LineBuffers? = null
    private var foldDirty = false

    fun setFold(f: Symmetry.Fold?): Unit = synchronized(strokes) {
        fold = f
        foldDirty = true
    }

    /** A screen-space polyline drawn over everything: the lasso boundary. */
    private var overlay: FloatArray? = null
    private var overlayBuffers: LineBuffers? = null
    private var overlayCapacity = 0
    private var viewW = 1
    private var viewH = 1

    /**
     * Buffer names whose stroke has gone, waiting for a GL thread to delete
     * them. `glDeleteBuffers` is only legal with a current context, and every
     * caller that drops a stroke — undo, redo, clear — is the UI thread. Doing
     * it there deletes nothing and leaks the buffer on a good driver, and takes
     * out whatever else owns that name on a bad one.
     */
    private val pendingDelete = ArrayList<Int>()

    // ---- the ground shadow ----------------------------------------------

    private var shadowProgram = 0
    private var sPos = 0; private var sMvp = 0
    private var groundProgram = 0
    private var qPos = 0; private var qMvp = 0; private var qLightVp = 0
    private var qMask = 0; private var qColor = 0
    private var qStrength = 0; private var qSoft = 0

    private var shadowFbo = 0
    private var shadowTex = 0
    private var shadowDepthRb = 0
    private var groundVbo = 0
    private val shadowFit = ShadowFit()
    private val shadowVp = FloatArray(16)
    private var shadowKey = ""
    private var shadowDirty = true

    /** Reused so the frame loop allocates nothing to read the stroke list. */
    private val shadowList = ArrayList<Stroke>()

    private var shadowBuiltAt = 0L

    /**
     * Ask for another frame.
     *
     * The renderer has no handle on the view it draws into — deliberately, it
     * is driven rather than driving — so anything it defers has to be able to
     * say so. Wired to the surface by the activity.
     */
    var needsFrame: () -> Unit = {}
    private var shadowVisible = false
    /** The sketch's extent, recomputed on the UI thread when the ink changes. */
    private var strokeBounds = Bounds()

    // ---- the post pass ---------------------------------------------------

    private var postProgram = 0
    private var pPos = 0; private var pColor = 0; private var pDepth = 0
    private var pTexel = 0; private var pNear = 0; private var pFar = 0
    private var pOrtho = 0; private var pFocus = 0; private var pRange = 0
    private var pDof = 0; private var pGrain = 0; private var pPixel = 0
    private var pGrid = 0

    private var postFbo = 0
    private var postColorTex = 0
    private var postDepthTex = 0
    private var postVbo = 0
    private var postW = 0
    private var postH = 0

    /** A PNG asked for, taken at the end of the next frame. See [requestSnapshot]. */
    private var snapshotWanted: ((android.graphics.Bitmap?) -> Unit)? = null

    /** Camera figures the post pass needs, snapshotted with the matrices. */
    private var camNear = 0.02
    private var camFar = 8000.0
    private var camOrtho = false
    private var camFocus = 1.0
    private var density = 1.0

    private class Buffers(val vbo: Int, val nbo: Int, val cbo: Int, val ibo: Int, val count: Int)
    private class LineBuffers(val vbo: Int, val cbo: Int, val count: Int)
    private class GuideBuffers(
        val vbo: Int, val nbo: Int, val ubo: Int, val ibo: Int, val count: Int,
    )

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
        /*
         * LESS-OR-EQUAL, WHICH IS THREE.JS'S DEFAULT AND NOT GL'S.
         *
         * The web build never says so because it never had to: three.js sets
         * LessEqualDepth on every material, and this port took GL's own
         * default of LESS instead. On a flat guide that is the difference
         * between "the curve you just drew wins" and "whichever curve the
         * rasteriser happened to favour wins, pixel by pixel" — which is where
         * the black diagonals came from, the fill and the strokes over it
         * splitting each quad along its triangulation.
         */
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
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
        uToon = GLES30.glGetUniformLocation(program, "uToon")
        uToonStep = GLES30.glGetUniformLocation(program, "uToonStep")
        uEye = GLES30.glGetUniformLocation(program, "uEye")
        uFogCol = GLES30.glGetUniformLocation(program, "uFogCol")
        uFogNear = GLES30.glGetUniformLocation(program, "uFogNear")
        uFogFar = GLES30.glGetUniformLocation(program, "uFogFar")
        uShade = GLES30.glGetUniformLocation(program, "uShade")
        uGlow = GLES30.glGetUniformLocation(program, "uGlow")
        uGrit = GLES30.glGetUniformLocation(program, "uGrit")
        uSelect = GLES30.glGetUniformLocation(program, "uSelect")
        uFade = GLES30.glGetUniformLocation(program, "uFade")

        shadowProgram = link(SHADOW_VERT, SHADOW_FRAG)
        sPos = GLES30.glGetAttribLocation(shadowProgram, "aPos")
        sMvp = GLES30.glGetUniformLocation(shadowProgram, "uMvp")

        groundProgram = link(GROUND_VERT, GROUND_FRAG)
        qPos = GLES30.glGetAttribLocation(groundProgram, "aPos")
        qMvp = GLES30.glGetUniformLocation(groundProgram, "uMvp")
        qLightVp = GLES30.glGetUniformLocation(groundProgram, "uLightVp")
        qMask = GLES30.glGetUniformLocation(groundProgram, "uMask")
        qColor = GLES30.glGetUniformLocation(groundProgram, "uColor")
        qStrength = GLES30.glGetUniformLocation(groundProgram, "uStrength")
        qSoft = GLES30.glGetUniformLocation(groundProgram, "uSoft")

        postProgram = link(POST_VERT, POST_FRAG)
        pPos = GLES30.glGetAttribLocation(postProgram, "aPos")
        pColor = GLES30.glGetUniformLocation(postProgram, "uColor")
        pDepth = GLES30.glGetUniformLocation(postProgram, "uDepth")
        pTexel = GLES30.glGetUniformLocation(postProgram, "uTexel")
        pNear = GLES30.glGetUniformLocation(postProgram, "uNear")
        pFar = GLES30.glGetUniformLocation(postProgram, "uFar")
        pOrtho = GLES30.glGetUniformLocation(postProgram, "uOrtho")
        pFocus = GLES30.glGetUniformLocation(postProgram, "uFocus")
        pRange = GLES30.glGetUniformLocation(postProgram, "uRange")
        pDof = GLES30.glGetUniformLocation(postProgram, "uDof")
        pGrain = GLES30.glGetUniformLocation(postProgram, "uGrain")
        pPixel = GLES30.glGetUniformLocation(postProgram, "uPixel")
        pGrid = GLES30.glGetUniformLocation(postProgram, "uGrid")

        /* a new context has no framebuffers; rebuild on the next frame */
        shadowFbo = 0
        shadowDirty = true
        postFbo = 0
        postVbo = 0

        lineProgram = link(LINE_VERT, LINE_FRAG)
        lPos = GLES30.glGetAttribLocation(lineProgram, "aPos")
        lCol = GLES30.glGetAttribLocation(lineProgram, "aCol")
        lMvp = GLES30.glGetUniformLocation(lineProgram, "uMvp")

        guideProgram = link(GUIDE_VERT, GUIDE_FRAG)
        gPos = GLES30.glGetAttribLocation(guideProgram, "aPos")
        gNor = GLES30.glGetAttribLocation(guideProgram, "aNor")
        gUvw = GLES30.glGetAttribLocation(guideProgram, "aUvw")
        gMvp = GLES30.glGetUniformLocation(guideProgram, "uMvp")
        gEye = GLES30.glGetUniformLocation(guideProgram, "uEye")
        gFill = GLES30.glGetUniformLocation(guideProgram, "uFill")
        gLine = GLES30.glGetUniformLocation(guideProgram, "uLine")
        gOpacity = GLES30.glGetUniformLocation(guideProgram, "uOpacity")
        gStep = GLES30.glGetUniformLocation(guideProgram, "uStep")
        gMode = GLES30.glGetUniformLocation(guideProgram, "uMode")
        gSelect = GLES30.glGetUniformLocation(guideProgram, "uSelect")

        /*
         * Every buffer name belonged to the context that just went away. Drop
         * the bookkeeping rather than deleting: the names are already invalid,
         * and glDeleteBuffers on a fresh context would be deleting whatever now
         * holds those numbers.
         */
        uploaded.clear()
        liveBuffers = null
        overlayBuffers = null
        overlayCapacity = 0
        guideUploaded.clear()
        gridBuffers = null; axisBuffers = null; gridSignature = ""
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES30.glViewport(0, 0, w, h)
        viewW = w; viewH = h
    }

    /** Copy the camera's matrix across the thread boundary. */
    fun setCamera(camera: Camera) {
        synchronized(matrixLock) {
            camera.viewProjection.into(mvp)
            eye[0] = camera.eye.x.toFloat()
            eye[1] = camera.eye.y.toFloat()
            eye[2] = camera.eye.z.toFloat()
            /*
             * The post pass has to undo the projection to compare distances, so
             * it needs the same near and far the matrix was built with. Taken
             * here rather than read from the Camera on the GL thread, so the
             * planes and the matrix can never be from different frames.
             */
            camNear = camera.near
            camFar = camera.far
            camOrtho = camera.ortho
            camFocus = fx.focusDistance(camera)
        }
    }

    /** Screen density, so a pixelation block is the size it says it is. */
    fun setDensity(d: Float) = synchronized(matrixLock) { density = d.toDouble() }

    override fun onDrawFrame(gl: GL10?) {
        drainDeletions()

        val m = FloatArray(16)
        val e = FloatArray(3)
        synchronized(matrixLock) { mvp.copyInto(m); eye.copyInto(e) }

        /*
         * FACT: effects show accurately only in rendering mode. Drawing stays
         * cheap; you ask for the picture. So the whole scene goes to an
         * offscreen target only when something is actually going to read it
         * back — otherwise this is the same straight-to-screen path it always
         * was, and a full-screen pass is not being charged for nothing.
         */
        val post = postWanted()
        if (post) beginPost() else GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        GLES30.glClearColor(
            background.r.toFloat(), background.g.toFloat(), background.b.toFloat(), 1f,
        )
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        drawEnvironment(m)

        /* over the grid, under the ink — the same render order the web build
           gives the shadow plane */
        drawGroundShadow(m)

        /* the fold is BEHIND the sketch and never over it: it is a thing you
           glance at to place a stroke, not a thing you look at */
        drawFold(m)

        drawStrokePass(m, e)

        // guides last: they are translucent scaffolding and belong over the ink
        drawGuides(m, e)

        if (post) endPost()

        /*
         * The lasso is CHROME, so it is drawn after the post pass rather than
         * through it. A defocused, pixelated, grainy cursor is a cursor you
         * cannot aim, and the web build has the same split — the loop lives in
         * an SVG overlay outside the canvas entirely.
         */
        drawOverlay()

        takeSnapshotIfAsked()
    }

    // ---- the picture ------------------------------------------------------

    /**
     * `renderer.domElement.toDataURL('image/png')`, which on this side means
     * reading the framebuffer back.
     *
     * Deferred to the END of a frame rather than taken on demand, because
     * glReadPixels is only legal on the GL thread with a current context and
     * only meaningful once the frame is finished. The caller asks, the next
     * frame answers, and [callback] arrives on the GL thread — whatever it
     * does with the bitmap must be safe there or must hop threads itself.
     *
     * It reads the DEFAULT framebuffer, so it captures the post pass: a PNG of
     * a render-mode scene comes out with its defocus and grain, which is the
     * picture that was asked for. The lasso is in it too if one is being drawn,
     * and that is the same as the web build, where the overlay is a separate
     * SVG the canvas capture cannot see — a difference worth knowing about
     * rather than one worth pretending away.
     */
    fun requestSnapshot(callback: (android.graphics.Bitmap?) -> Unit) {
        synchronized(matrixLock) { snapshotWanted = callback }
    }

    private fun takeSnapshotIfAsked() {
        val cb = synchronized(matrixLock) { snapshotWanted.also { snapshotWanted = null } } ?: return
        val w = viewW
        val h = viewH
        if (w <= 0 || h <= 0) { cb(null); return }

        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        buf.rewind()

        /*
         * GL reads bottom-up and a Bitmap is top-down, so the rows are copied
         * back to front. Reading straight into the bitmap gives a picture that
         * is upside down, which is the classic way to get this wrong.
         */
        val row = IntArray(w)
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = (y * w + x) * 4
                val r = buf.get(i).toInt() and 0xFF
                val g = buf.get(i + 1).toInt() and 0xFF
                val b = buf.get(i + 2).toInt() and 0xFF
                row[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            System.arraycopy(row, 0, pixels, (h - 1 - y) * w, w)
        }
        cb(
            android.graphics.Bitmap.createBitmap(
                pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888,
            ),
        )
    }

    // ---- the post pass ---------------------------------------------------

    private fun postWanted(): Boolean = synchronized(matrixLock) {
        renderMode && (fx.dofOn || fx.grainOn || fx.pixelOn)
    }

    /** Point the scene at the offscreen target instead of the screen. */
    private fun beginPost() {
        ensurePostTarget()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, postFbo)
        GLES30.glViewport(0, 0, postW, postH)
    }

    /**
     * Read the scene back once and write it once.
     *
     * ONE PASS, NOT A CHAIN. Each effect is a few lines of the same fragment
     * shader, so a full-screen buffer is not ping-ponged per effect for no gain
     * at these resolutions. That is the web build's reasoning and it holds
     * here.
     *
     * A REAL DEPTH TEXTURE, which is the one place the roadmap says this phase
     * gets EASIER than the original. fx.js has a long note on why it packs
     * depth across four 8-bit channels: a DepthTexture came back reading 1.0
     * everywhere on WebGL, and a 16-bit non-linear buffer over a near of 0.02
     * to a far of 8000 — 400,000 to 1 — spends almost all its resolution in the
     * first few centimetres. It costs the web build a second pass over the
     * whole scene. GL ES 3.0 has DEPTH_COMPONENT24 as a sampleable texture
     * attachment, so the depth the scene already wrote IS the depth buffer:
     * no packing, no unpack constants, and no second geometry pass at all.
     */
    private fun endPost() {
        val f = synchronized(matrixLock) {
            PostSnapshot(
                fx.dofOn, fx.grainAmount(), fx.pixelOn,
                fx.pixelGridX(postW, density), fx.pixelGridY(postH, density),
                camFocus, fx.focusRange(camFocus), camNear, camFar, camOrtho,
            )
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewW, viewH)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)

        GLES30.glUseProgram(postProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, postColorTex)
        GLES30.glUniform1i(pColor, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, postDepthTex)
        GLES30.glUniform1i(pDepth, 1)

        GLES30.glUniform2f(pTexel, 1f / postW, 1f / postH)
        GLES30.glUniform1f(pNear, f.near.toFloat())
        GLES30.glUniform1f(pFar, f.far.toFloat())
        GLES30.glUniform1f(pOrtho, if (f.ortho) 1f else 0f)
        GLES30.glUniform1f(pFocus, f.focus.toFloat())
        GLES30.glUniform1f(pRange, f.range.toFloat())
        GLES30.glUniform1f(pDof, if (f.dof) 1f else 0f)
        GLES30.glUniform1f(pGrain, f.grain.toFloat())
        GLES30.glUniform1f(pPixel, if (f.pixel) 1f else 0f)
        GLES30.glUniform2f(pGrid, f.gridX.toFloat(), f.gridY.toFloat())

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, postVbo)
        GLES30.glEnableVertexAttribArray(pPos)
        GLES30.glVertexAttribPointer(pPos, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        GLES30.glDisableVertexAttribArray(pPos)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private class PostSnapshot(
        val dof: Boolean, val grain: Double, val pixel: Boolean,
        val gridX: Double, val gridY: Double,
        val focus: Double, val range: Double,
        val near: Double, val far: Double, val ortho: Boolean,
    )

    private fun ensurePostTarget() {
        if (postFbo != 0 && postW == viewW && postH == viewH) return
        if (postFbo != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(postFbo), 0)
            GLES30.glDeleteTextures(2, intArrayOf(postColorTex, postDepthTex), 0)
            postFbo = 0
        }
        postW = maxOf(1, viewW)
        postH = maxOf(1, viewH)

        val tex = IntArray(2)
        GLES30.glGenTextures(2, tex, 0)
        postColorTex = tex[0]
        postDepthTex = tex[1]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, postColorTex)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, postW, postH, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )
        texClampLinear()

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, postDepthTex)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24, postW, postH, 0,
            GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, null,
        )
        /*
         * NEAREST on depth, deliberately. A linear filter over a non-linear
         * depth buffer averages values that mean nothing between them: at a
         * silhouette it invents a surface halfway between the near object and
         * the far one, and the defocus then blurs towards a distance where
         * there is nothing at all.
         */
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST,
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST,
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE,
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE,
        )

        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        postFbo = fbo[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, postFbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, postColorTex, 0,
        )
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT,
            GLES30.GL_TEXTURE_2D, postDepthTex, 0,
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        if (postVbo == 0) {
            val ids = IntArray(1)
            GLES30.glGenBuffers(1, ids, 0)
            postVbo = ids[0]
            /* two triangles in clip space; the shader derives uv from them */
            arrayBuffer(
                postVbo,
                floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f),
                GLES30.GL_STATIC_DRAW,
            )
        }
    }

    private fun texClampLinear() {
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR,
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR,
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE,
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE,
        )
    }

    // ---- the ground shadow ----------------------------------------------

    /**
     * The sketch's silhouette, thrown down the light onto the ground.
     *
     * NOT A SHADOW MAP, and the reason is in [GroundShadow]: a depth-map
     * comparison wants surfaces thick and flat enough to bias against, and
     * almost everything here is a thin tube, so the bias that stops the acne is
     * the bias that lifts the shadow off the object it belongs to. The ground
     * only needs to know whether anything is between it and the light, and the
     * answer to that is the silhouette. No depth compare, no bias, nothing to
     * tune.
     *
     * STROKES ONLY, and said as a whitelist. The web build's note records what
     * happened when it was said as a list of things to hide: an override
     * material makes everything opaque, so the pivot marker — a one-metre
     * sphere kept at zero opacity — became a solid black ball filling the
     * light's whole view. Here that is structural rather than a rule to
     * remember: this pass has its own program and only stroke buffers are ever
     * bound to it, so a guide or an overlay cannot cast by accident.
     */
    private fun drawGroundShadow(m: FloatArray) {
        var on: Boolean
        val sun = Light()
        var bg: Rgba
        synchronized(matrixLock) {
            on = renderMode && groundShadow
            sun.copyFrom(light)
            bg = background
        }
        if (!on) { shadowVisible = false; return }

        /*
         * ONE LIST, REUSED. This copied every stroke into a fresh ArrayList on
         * EVERY FRAME — sixty allocations a second of a four-hundred-element
         * list, which is not slow so much as a steady drip into the collector,
         * and the collector is what stops the world.
         */
        synchronized(strokes) { shadowList.clear(); shadowList.addAll(strokes) }
        val list = shadowList

        if (shadowDirty) {
            strokeBounds = Bounds()
            for (st in list) for (sp in st.pts) strokeBounds.add(sp.p)
            strokeBounds.expand(0.02)
        }
        val bounds = strokeBounds
        if (bounds.empty || sun.alt < GroundShadow.MIN_ALT) {
            shadowVisible = false
            shadowDirty = false
            return
        }

        val key = GroundShadow.signature(bounds, sun)
        if (shadowDirty || key != shadowKey) {
            /*
             * THE SILHOUETTE IS REBUILT AT MOST SO OFTEN.
             *
             * Rebuilding it re-draws every stroke in the sketch into an
             * offscreen target — a whole second scene. That was happening on
             * every change to the stroke list, and the stroke list changes on
             * every sample of an erase drag: four hundred curves re-rendered
             * per pointer event, which is the freeze.
             *
             * A shadow that trails a tenth of a second behind the pen is not
             * something anyone can see. A drag that stops responding is. So
             * the rebuild is rate-limited, and the dirty flag stays up until
             * one actually happens, which means the last state always lands.
             */
            val now = System.nanoTime()
            if (now - shadowBuiltAt >= SHADOW_MIN_INTERVAL_NS) {
                shadowBuiltAt = now
                shadowKey = key
                shadowDirty = false
                GroundShadow.fit(bounds, sun, shadowFit)
                shadowFit.viewProj.into(shadowVp)
                renderSilhouette(list)
            } else {
                /*
                 * SOMETHING HAS TO COME BACK FOR THE ONE WE SKIPPED.
                 *
                 * The surface renders WHEN DIRTY, so a frame only happens
                 * because something asked for one. Dropping this rebuild on
                 * the last frame of a drag would leave the flag up with
                 * nothing left to act on it, and the shadow would stay a drag
                 * behind until some unrelated touch happened to draw again.
                 * The throttle has to schedule its own catch-up.
                 */
                needsFrame()
            }
        }
        shadowVisible = true
        paintGround(m, bg)
    }

    /** Draw every stroke flat into the offscreen target, seen from the light. */
    private fun renderSilhouette(list: List<Stroke>) {
        ensureShadowTarget()
        val prevFbo = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo)
        GLES30.glViewport(0, 0, Tune.SHADOW_SIZE, Tune.SHADOW_SIZE)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        /*
         * Both faces, and no culling. The silhouette wants the union of
         * everything the light cannot see past; culling the back of a tube
         * would punch the far wall out of the mask and leave a hole down the
         * middle of the shadow of every closed loop.
         */
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true)

        GLES30.glUseProgram(shadowProgram)
        GLES30.glUniformMatrix4fv(sMvp, 1, false, shadowVp, 0)
        for (st in list) {
            val b = uploaded[st] ?: upload(st) ?: continue
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b.vbo)
            GLES30.glEnableVertexAttribArray(sPos)
            GLES30.glVertexAttribPointer(sPos, 3, GLES30.GL_FLOAT, false, 0, 0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, b.ibo)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, b.count, GLES30.GL_UNSIGNED_INT, 0)
        }
        GLES30.glDisableVertexAttribArray(sPos)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
        GLES30.glViewport(0, 0, viewW, viewH)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    /** Sample the mask on a quad lying at y = 0 under the sketch. */
    private fun paintGround(m: FloatArray, bg: Rgba) {
        ensureShadowTarget()
        val half = (shadowFit.size * 0.5).toFloat()
        val cx = shadowFit.planeX.toFloat()
        val cz = shadowFit.planeZ.toFloat()
        val quad = floatArrayOf(
            cx - half, 0f, cz - half,
            cx + half, 0f, cz - half,
            cx + half, 0f, cz + half,
            cx - half, 0f, cz - half,
            cx + half, 0f, cz + half,
            cx - half, 0f, cz + half,
        )
        arrayBuffer(groundVbo, quad, GLES30.GL_DYNAMIC_DRAW)

        GLES30.glUseProgram(groundProgram)
        GLES30.glUniformMatrix4fv(qMvp, 1, false, m, 0)
        GLES30.glUniformMatrix4fv(qLightVp, 1, false, shadowVp, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTex)
        GLES30.glUniform1i(qMask, 0)
        // the page colour pulled most of the way to black, as applyEnv does
        val k = 1.0 - Tune.SHADOW_MIX
        GLES30.glUniform3f(
            qColor, (bg.r * k).toFloat(), (bg.g * k).toFloat(), (bg.b * k).toFloat(),
        )
        GLES30.glUniform1f(qStrength, Tune.SHADOW_STRENGTH.toFloat())
        GLES30.glUniform1f(qSoft, (Tune.SHADOW_SOFT_TEXELS / Tune.SHADOW_SIZE).toFloat())

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        /* the ground is a single plane seen from either side */
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, groundVbo)
        GLES30.glEnableVertexAttribArray(qPos)
        GLES30.glVertexAttribPointer(qPos, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        GLES30.glDisableVertexAttribArray(qPos)

        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun ensureShadowTarget() {
        if (shadowFbo != 0) return
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        shadowTex = tex[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTex)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
            Tune.SHADOW_SIZE, Tune.SHADOW_SIZE, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR,
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR,
        )
        /*
         * CLAMP TO EDGE, and it matters. The ground shader discards outside
         * 0..1 anyway, but a wrapped tap from the soft-edge offsets would pull
         * the silhouette in from the opposite side and print a ghost of the
         * sketch along the far edge of the shadow.
         */
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE,
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE,
        )

        val rb = IntArray(1)
        GLES30.glGenRenderbuffers(1, rb, 0)
        shadowDepthRb = rb[0]
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, shadowDepthRb)
        GLES30.glRenderbufferStorage(
            GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT16,
            Tune.SHADOW_SIZE, Tune.SHADOW_SIZE,
        )

        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        shadowFbo = fbo[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, shadowTex, 0,
        )
        GLES30.glFramebufferRenderbuffer(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT,
            GLES30.GL_RENDERBUFFER, shadowDepthRb,
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        groundVbo = ids[0]
    }

    // ---- the symmetry fold ------------------------------------------------

    /**
     * Faint means faint. The fill is barely a tint and the rim only just holds
     * its edge; seen face-on the two stack into the one legible line down the
     * middle they are meant to be.
     */
    private fun drawFold(m: FloatArray) {
        val f = synchronized(strokes) {
            if (foldDirty) { releaseFold(); foldDirty = false }
            fold
        } ?: return

        val bg = synchronized(matrixLock) { background }
        /* the fold takes the ink's own contrast against the page, so it stays
           legible whichever way round the theme is */
        val lum = Grid.luminance(bg)
        val c = if (lum > 0.5) 0f else 1f

        /*
         * EACH PLANE IN ITS OWN COLOUR. FACT: switching a mirror axis on shows
         * "the global axis in the respective color". With three planes
         * possible at once, one grey for all of them tells you how many are on
         * and not which — and which is the only thing worth reading off it.
         * The fill stays nearly transparent either way: a plane you can see
         * THROUGH is the point of it.
         */
        if (f.fill.isNotEmpty()) {
            val b = foldFill
                ?: uploadFlat(f.fill, c, 0.05f, f.fillColors).also { foldFill = it }
            drawFlat(m, b, GLES30.GL_TRIANGLES)
        }
        if (f.edges.isNotEmpty()) {
            val b = foldEdge
                ?: uploadFlat(f.edges, c, 0.30f, f.edgeColors).also { foldEdge = it }
            drawFlat(m, b, GLES30.GL_LINES)
        }
        if (f.axisLine.isNotEmpty()) {
            val b = foldAxis ?: uploadFlat(f.axisLine, c, 0.38f).also { foldAxis = it }
            drawFlat(m, b, GLES30.GL_LINES)
        }
    }

    /** [rgb] is three floats per vertex, or empty to paint the lot [grey]. */
    private fun uploadFlat(
        pos: FloatArray,
        grey: Float,
        alpha: Float,
        rgb: FloatArray = FloatArray(0),
    ): LineBuffers {
        val n = pos.size / 3
        val col = FloatArray(n * 4)
        val tinted = rgb.size >= n * 3
        for (i in 0 until n) {
            if (tinted) {
                col[i * 4] = rgb[i * 3]
                col[i * 4 + 1] = rgb[i * 3 + 1]
                col[i * 4 + 2] = rgb[i * 3 + 2]
            } else {
                col[i * 4] = grey; col[i * 4 + 1] = grey; col[i * 4 + 2] = grey
            }
            col[i * 4 + 3] = alpha
        }
        val ids = IntArray(2)
        GLES30.glGenBuffers(2, ids, 0)
        arrayBuffer(ids[0], pos, GLES30.GL_STATIC_DRAW)
        arrayBuffer(ids[1], col, GLES30.GL_STATIC_DRAW)
        return LineBuffers(ids[0], ids[1], n)
    }

    private fun drawFlat(m: FloatArray, b: LineBuffers, primitive: Int) {
        GLES30.glUseProgram(lineProgram)
        GLES30.glUniformMatrix4fv(lMvp, 1, false, m, 0)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b.vbo)
        GLES30.glEnableVertexAttribArray(lPos)
        GLES30.glVertexAttribPointer(lPos, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b.cbo)
        GLES30.glEnableVertexAttribArray(lCol)
        GLES30.glVertexAttribPointer(lCol, 4, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(primitive, 0, b.count)
        GLES30.glDisableVertexAttribArray(lPos)
        GLES30.glDisableVertexAttribArray(lCol)

        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun releaseFold() {
        for (b in listOfNotNull(foldFill, foldEdge, foldAxis)) {
            pendingDelete.add(b.vbo); pendingDelete.add(b.cbo)
        }
        foldFill = null; foldEdge = null; foldAxis = null
    }

    // ---- the ink --------------------------------------------------------

    /**
     * Every committed stroke, then the one being drawn.
     *
     * ORDER IS THE WHOLE POINT. Opaque strokes go down first with depth writes
     * on, so they occlude properly; then the translucent ones, which cannot
     * write depth without hiding each other; then the glow, which is additive
     * and has to see everything already laid down. Drawing them in list order
     * would let a translucent stroke drawn early z-reject an opaque one behind
     * it that had not been drawn yet.
     *
     * The web build gets this from three.js's own sort. Doing it by hand costs
     * two extra walks over a list and no extra state changes: the uniforms
     * that vary per stroke are four floats.
     */
    private fun drawStrokePass(m: FloatArray, e: FloatArray) {
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, m, 0)
        GLES30.glUniform3f(uEye, e[0], e[1], e[2])

        val l = synchronized(matrixLock) {
            LightSnapshot(
                light.direction(), light.color, light.intensity, light.ambientClamped(),
                light.toon, light.toonStepsClamped(), shaded, fog, background,
            )
        }
        GLES30.glUniform3f(uLightDir, l.dir.x.toFloat(), l.dir.y.toFloat(), l.dir.z.toFloat())
        GLES30.glUniform3f(
            uLightCol, l.color.r.toFloat(), l.color.g.toFloat(), l.color.b.toFloat(),
        )
        GLES30.glUniform1f(uAmbient, l.ambient.toFloat())
        GLES30.glUniform1f(uIntensity, l.intensity.toFloat())
        GLES30.glUniform1f(uToon, if (l.toon) 1f else 0f)
        GLES30.glUniform1f(uToonStep, l.toonSteps.toFloat())
        GLES30.glUniform3f(
            uFogCol, l.background.r.toFloat(), l.background.g.toFloat(),
            l.background.b.toFloat(),
        )
        /*
         * Fog off is expressed as a range nothing can reach rather than as a
         * branch: one float against a conditional in the hottest shader in the
         * app, and the far plane is 8000.
         */
        GLES30.glUniform1f(uFogNear, if (l.fog) Tune.FOG_NEAR.toFloat() else 1e9f)
        GLES30.glUniform1f(uFogFar, if (l.fog) Tune.FOG_FAR.toFloat() else 2e9f)

        val list = synchronized(strokes) { ArrayList(strokes) }

        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        /* the age bias is the stroke's place in the DRAWING, not in this pass:
           a blended curve drawn after an opaque one is still the newer one */
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        for ((i, s) in list.withIndex()) if (pass(s) == OPAQUE) drawStroke(s, l.shaded, i)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        for ((i, s) in list.withIndex()) if (pass(s) == BLENDED) drawStroke(s, l.shaded, i)

        /*
         * FACT (C.5): "a Glow material enables glowing lines" — additive
         * blending is what makes overlapping strokes bloom instead of just
         * stacking, and it is why glow is never shaded.
         */
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
        for ((i, s) in list.withIndex()) if (pass(s) == GLOWING) drawStroke(s, l.shaded, i)

        /* the live stroke last, and newer than everything: it is the one you
           are drawing right now */
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glPolygonOffset(
            if (liveIsPaint()) -1f else 0f,
            -(1f + min(list.size, DEPTH_ORDER_CAP)),
        )
        drawLive(l.shaded)

        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(0f, 0f)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private class LightSnapshot(
        val dir: Vec3, val color: Rgba, val intensity: Double, val ambient: Double,
        val toon: Boolean, val toonSteps: Int, val shaded: Boolean, val fog: Boolean,
        val background: Rgba,
    )

    private fun pass(s: Stroke): Int {
        val c = s.cfg
        return when {
            c.glow -> GLOWING
            c.grit || s.opacity < 1.0 || fadeOf(s) < 1f -> BLENDED
            else -> OPAQUE
        }
    }

    /**
     * One stroke, nudged towards the eye by where it comes in the drawing.
     *
     * LEQUAL settles an exact tie, and two flat curves on one guide are rarely
     * an exact tie: they are triangulated differently, so their interpolated
     * depths differ by an ULP or two and which one shows varies ACROSS THE
     * SURFACE. That is the diagonal — it follows the diagonal of the quad.
     *
     * The offset is in units of the smallest resolvable depth difference, so
     * [order] steps is [order] ULPs: enough to settle a tie by age, far too
     * little to lift a curve off the surface it was painted onto. A stroke
     * drawn later is a stroke drawn ON TOP, which is what a pen does.
     *
     * The slope term is FOR THE PAINT BRUSHES ONLY, and that restriction is
     * the point. A ribbon lying on a guide is a decal, and a decal seen
     * edge-on needs one: depth changes fast enough across a single pixel that
     * a constant nudge stops being a nudge. A round brush is not a decal but a
     * tube, and its silhouette has the steepest slope in the drawing — giving
     * that a slope term would pull the edge of every tube towards the eye and
     * let strokes bleed through the guides they sit behind. So the tie-breaker
     * they share is the constant one, and only the curves that lie flat on a
     * surface get help with the surface's angle.
     */
    private fun drawStroke(s: Stroke, shadedNow: Boolean, order: Int) {
        val c = s.cfg
        GLES30.glPolygonOffset(
            if (c.paint) -1f else 0f,
            -(1f + min(order, DEPTH_ORDER_CAP)),
        )
        GLES30.glUniform1f(uShade, if (shadedNow && !c.glow) 1f else 0f)
        GLES30.glUniform1f(uGlow, if (c.glow) 1f else 0f)
        GLES30.glUniform1f(uGrit, if (c.grit) 1f else 0f)
        GLES30.glUniform1f(uSelect, if (s.selected) 1f else 0f)
        GLES30.glUniform1f(uFade, fadeOf(s))
        draw(s)
    }

    // ---- screen-space overlay -------------------------------------------

    /**
     * A polyline in VIEW PIXELS, drawn flat over the scene.
     *
     * The lasso needs this: a loop you cannot see is a loop you cannot aim.
     * Points arrive as x, y pairs and are drawn through an orthographic matrix
     * built from the viewport, so they land exactly where the finger was rather
     * than being unprojected into the world and back.
     */
    fun setOverlay(points: FloatArray?): Unit = synchronized(strokes) { overlay = points }

    private fun drawOverlay() {
        val pts = synchronized(strokes) { overlay } ?: return
        if (pts.size < 4) return
        val n = pts.size / 2

        val b = overlayBuffers ?: newOverlayBuffers().also { overlayBuffers = it }
        val pos = FloatArray(n * 3)
        val col = FloatArray(n * 4)
        for (i in 0 until n) {
            pos[i * 3] = pts[i * 2]
            pos[i * 3 + 1] = pts[i * 2 + 1]
            pos[i * 3 + 2] = 0f
            col[i * 4] = 0.12f; col[i * 4 + 1] = 0.45f; col[i * 4 + 2] = 0.95f
            col[i * 4 + 3] = 0.9f
        }
        if (n > overlayCapacity) {
            arrayBuffer(b.vbo, pos, GLES30.GL_DYNAMIC_DRAW)
            arrayBuffer(b.cbo, col, GLES30.GL_DYNAMIC_DRAW)
            overlayCapacity = n
        } else {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b.vbo)
            GLES30.glBufferSubData(
                GLES30.GL_ARRAY_BUFFER, 0, pos.size * 4, floatBuffer(pos, pos.size),
            )
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b.cbo)
            GLES30.glBufferSubData(
                GLES30.GL_ARRAY_BUFFER, 0, col.size * 4, floatBuffer(col, col.size),
            )
        }

        // pixels straight to clip space, y down as the touch events report it
        val o = FloatArray(16)
        Mat4.orthographic(0.0, viewW.toDouble(), viewH.toDouble(), 0.0, -1.0, 1.0, orthoM)
        orthoM.into(o)

        GLES30.glUseProgram(lineProgram)
        GLES30.glUniformMatrix4fv(lMvp, 1, false, o, 0)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b.vbo)
        GLES30.glEnableVertexAttribArray(lPos)
        GLES30.glVertexAttribPointer(lPos, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b.cbo)
        GLES30.glEnableVertexAttribArray(lCol)
        GLES30.glVertexAttribPointer(lCol, 4, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES30.GL_LINE_STRIP, 0, n)
        GLES30.glDisableVertexAttribArray(lPos)
        GLES30.glDisableVertexAttribArray(lCol)

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private val orthoM = Mat4()

    private fun newOverlayBuffers(): LineBuffers {
        val ids = IntArray(2)
        GLES30.glGenBuffers(2, ids, 0)
        return LineBuffers(ids[0], ids[1], 0)
    }

    // ---- guides ---------------------------------------------------------

    fun setGuides(list: List<Guide>): Unit = synchronized(strokes) { guides = list }

    /**
     * How strongly each group draws, by group id, and which group the live
     * stroke will join.
     *
     * A UNIFORM rather than a rebuild. Opacity is baked into the vertex
     * colours when a mesh is built, so fading a group that way would mean
     * rebuilding every curve in it on every step of a slider drag — hundreds
     * of meshes for a control that is still moving. One float per draw call
     * costs nothing and the slider stays live.
     */
    fun setGroupFade(m: Map<Int, Float>, live: Int): Unit = synchronized(strokes) {
        groupFade = m
        liveGroup = live
    }

    private var groupFade: Map<Int, Float> = emptyMap()
    private var liveGroup = 0

    private fun fadeOf(s: Stroke): Float = groupFade[s.group] ?: 1f

    private fun drawGuides(m: FloatArray, e: FloatArray) {
        val list = synchronized(strokes) { guides }
        if (list.isEmpty()) {
            if (guideUploaded.isNotEmpty()) releaseGuides(emptySet())
            return
        }
        releaseGuides(list.mapNotNull { it.surface }.toSet())

        val (fill, line) = Grid.guideColors(background)
        GLES30.glUseProgram(guideProgram)
        GLES30.glUniformMatrix4fv(gMvp, 1, false, m, 0)
        GLES30.glUniform3f(gEye, e[0], e[1], e[2])
        GLES30.glUniform3f(gFill, fill.r.toFloat(), fill.g.toFloat(), fill.b.toFloat())
        GLES30.glUniform3f(gLine, line.r.toFloat(), line.g.toFloat(), line.b.toFloat())
        GLES30.glUniform1f(gStep, Tune.GUIDE_GRID_STEP.toFloat())

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        /*
         * Double-sided and no depth write. A guide is a sheet you orbit around
         * and see from both faces, and it must not occlude the ink drawn on it
         * — writing depth would make paint on the far side vanish behind the
         * scaffolding it was painted onto.
         */
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDepthMask(false)

        for (g in list) {
            val surface = g.surface ?: continue
            val b = guideUploaded[surface] ?: uploadGuide(surface) ?: continue
            GLES30.glUniform1f(gOpacity, g.opacity.toFloat())
            /*
             * A guide's own colour when it has one, the page's when it does
             * not. The uniform is set per guide rather than once for the pass,
             * which costs two calls and is what lets two guides in one scene
             * be told apart.
             */
            val tint = g.tint
            if (tint == null) {
                GLES30.glUniform3f(gFill, fill.r.toFloat(), fill.g.toFloat(), fill.b.toFloat())
                GLES30.glUniform3f(gLine, line.r.toFloat(), line.g.toFloat(), line.b.toFloat())
            } else {
                GLES30.glUniform3f(
                    gFill, tint.r.toFloat(), tint.g.toFloat(), tint.b.toFloat(),
                )
                /* the section lines are the same hue carried towards the page,
                   so a tinted guide keeps the fill/line relationship the
                   derived colours have */
                val k = if (Grid.luminance(background) > 0.5) 0.55 else 1.35
                GLES30.glUniform3f(
                    gLine,
                    (tint.r * k).toFloat().coerceIn(0f, 1f),
                    (tint.g * k).toFloat().coerceIn(0f, 1f),
                    (tint.b * k).toFloat().coerceIn(0f, 1f),
                )
            }
            // a primitive has no arc-length grid, so its lines come from a
            // triplanar projection of world space instead
            val triplanar = g.kind == GuideKind.PRIMITIVE || g.kind == GuideKind.MODEL
            GLES30.glUniform1f(gMode, if (triplanar) 1f else 0f)
            GLES30.glUniform1f(gSelect, if (g.selected) 1f else 0f)

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b.vbo)
            GLES30.glEnableVertexAttribArray(gPos)
            GLES30.glVertexAttribPointer(gPos, 3, GLES30.GL_FLOAT, false, 0, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b.nbo)
            GLES30.glEnableVertexAttribArray(gNor)
            GLES30.glVertexAttribPointer(gNor, 3, GLES30.GL_FLOAT, false, 0, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b.ubo)
            GLES30.glEnableVertexAttribArray(gUvw)
            GLES30.glVertexAttribPointer(gUvw, 2, GLES30.GL_FLOAT, false, 0, 0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, b.ibo)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, b.count, GLES30.GL_UNSIGNED_INT, 0)
        }

        GLES30.glDisableVertexAttribArray(gPos)
        GLES30.glDisableVertexAttribArray(gNor)
        GLES30.glDisableVertexAttribArray(gUvw)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun uploadGuide(s: GuideSurface): GuideBuffers? {
        if (s.indices.isEmpty()) return null
        val ids = IntArray(4)
        GLES30.glGenBuffers(4, ids, 0)
        arrayBuffer(ids[0], s.positions, GLES30.GL_STATIC_DRAW)
        arrayBuffer(ids[1], s.normals, GLES30.GL_STATIC_DRAW)
        arrayBuffer(ids[2], s.uv, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[3])
        GLES30.glBufferData(
            GLES30.GL_ELEMENT_ARRAY_BUFFER, s.indices.size * 4,
            intBuffer(s.indices, s.indices.size), GLES30.GL_STATIC_DRAW,
        )
        val b = GuideBuffers(ids[0], ids[1], ids[2], ids[3], s.indices.size)
        guideUploaded[s] = b
        return b
    }

    /** Drop the buffers of any surface no longer on screen — a rebuilt guide
     *  hands over a NEW surface, so its old one lands here. */
    private fun releaseGuides(keep: Set<GuideSurface>) {
        val gone = guideUploaded.keys.filter { it !in keep }
        for (k in gone) {
            guideUploaded.remove(k)?.let {
                GLES30.glDeleteBuffers(4, intArrayOf(it.vbo, it.nbo, it.ubo, it.ibo), 0)
            }
        }
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

    fun addStroke(s: Stroke): Unit = synchronized(strokes) {
        strokes.add(s); release(s); shadowDirty = true
    }

    fun removeStroke(s: Stroke): Unit = synchronized(strokes) {
        strokes.remove(s); release(s); shadowDirty = true
    }

    /**
     * Replace the stroke list, keeping the buffers of everything that stayed.
     *
     * Releasing all of them and re-uploading was fine when this was only called
     * by undo. The eraser calls it on every pointer sample, and a drag across a
     * drawing of two hundred curves was re-uploading all two hundred at 120Hz —
     * the exact shape of the shader-relink bug the web build hit, in a
     * different place. Only what actually left is released.
     */
    fun setStrokes(list: List<Stroke>): Unit = synchronized(strokes) {
        shadowDirty = true
        val keep = java.util.IdentityHashMap<Stroke, Boolean>(list.size)
        for (s in list) keep[s] = true
        for (s in strokes) if (!keep.containsKey(s)) release(s)
        strokes.clear()
        strokes.addAll(list)
    }

    fun clear(): Unit = synchronized(strokes) {
        for (s in strokes) release(s)
        strokes.clear()
    }

    /** Drop the cached buffers so the next frame re-uploads. */
    fun invalidate(s: Stroke): Unit = synchronized(strokes) { release(s); shadowDirty = true }

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

    private fun liveIsPaint(): Boolean =
        synchronized(strokes) { live }?.cfg?.paint ?: false

    private fun drawLive(shadedNow: Boolean = shaded) {
        val buffer = synchronized(strokes) { live } ?: return
        // the UI thread appends to this while we read it
        synchronized(buffer) {
            if (buffer.pointCount < 2 || buffer.indexCount == 0) return
            val b = liveBuffers ?: newLiveBuffers().also { liveBuffers = it }
            syncLive(b, buffer)
            buffer.clearDirty()
            val c = buffer.cfg
            GLES30.glUniform1f(uShade, if (shadedNow && !c.glow) 1f else 0f)
            GLES30.glUniform1f(uGlow, if (c.glow) 1f else 0f)
            GLES30.glUniform1f(uGrit, if (c.grit) 1f else 0f)
            GLES30.glUniform1f(uSelect, 0f)
            /* at the strength of the group it is about to join, or it would
               jump the moment the pen came up */
            GLES30.glUniform1f(uFade, groupFade[liveGroup] ?: 1f)
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
        /**
         * How often the ground shadow's silhouette may be rebuilt.
         *
         * A tenth of a second: far below what the eye reads as lag on a soft
         * shadow, and far above the rate an erase drag was asking for.
         */
        const val SHADOW_MIN_INTERVAL_NS = 100_000_000L

        /**
         * The most depth-buffer steps the age bias may add up to.
         *
         * Each stroke asks for one more step than the one before it, and a
         * step is the smallest difference the depth buffer can hold — so a
         * thousand curves is a thousandth of nothing. The cap is only so that
         * a drawing of a hundred thousand curves cannot turn a tie-breaker
         * into a visible lift off the guide.
         */
        const val DEPTH_ORDER_CAP = 4096

        /** Which of the three ordered passes a stroke belongs to. */
        const val OPAQUE = 0
        const val BLENDED = 1
        const val GLOWING = 2

        const val VERT = """#version 300 es
            precision highp float;
            in vec3 aPos;
            in vec3 aNor;
            in vec4 aCol;
            uniform mat4 uMvp;
            out vec3 vNor;
            out vec4 vCol;
            out vec3 vPos;
            void main(){
              vNor = aNor;
              vCol = aCol;
              /* stroke geometry is written in WORLD coordinates, so object
                 space is world space and the paper grain can be anchored to
                 the sketch rather than to the nib */
              vPos = aPos;
              gl_Position = uMvp * vec4(aPos, 1.0);
            }"""

        /*
         * The ink.
         *
         * Ported from FRAG in js/strokes.js. Four things in here are decisions
         * rather than arithmetic, and each is the reason the sketch reads the
         * way it does:
         *
         * HALF-LAMBERT. `dot*0.5+0.5` rather than `max(dot,0)`, so a curved
         * stroke keeps some shape on its dark side instead of falling off a
         * cliff at the terminator. That is the sketchbook read, and it is what
         * the hardcoded term did before there was a light to aim.
         *
         * TOON BANDS THE SAME TERM instead of replacing it, so toggling it
         * changes how the light falls off and not where the light is.
         *
         * GRIT EATS INTO THE TONE, never into the outline. The silhouette is
         * left exactly as the sweep built it — the retired `grain` parameter
         * jittered the radius instead, and was the only source of visibly
         * jagged geometry in the brush set. The noise is sampled in world
         * MILLIMETRES, which is what makes the tooth a property of the paper:
         * it stays the same size whatever the brush size, and two strokes
         * crossing agree about where it is, so overlapping pencil reads as one
         * surface being shaded rather than as two marks. That is also why this
         * shader is highp — a sketch runs to a few thousand millimetres across
         * and mediump would alias the hash into bands.
         *
         * FOG LAST, over everything including the glow, because it is the air
         * between the eye and the mark rather than a property of the mark.
         */
        const val FRAG = """#version 300 es
            precision highp float;
            in vec3 vNor;
            in vec4 vCol;
            in vec3 vPos;
            uniform vec3 uLightDir;
            uniform vec3 uLightCol;
            uniform float uAmbient;
            uniform float uIntensity;
            uniform float uToon;
            uniform float uToonStep;
            uniform float uShade;
            uniform float uGlow;
            uniform float uGrit;
            uniform float uSelect;
            uniform float uFade;
            uniform vec3 uEye;
            uniform vec3 uFogCol;
            uniform float uFogNear;
            uniform float uFogFar;
            out vec4 fragColor;

            float gHash(vec3 p){
              return fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453);
            }
            float gNoise(vec3 p){
              vec3 i = floor(p), f = fract(p);
              f = f*f*(3.0 - 2.0*f);
              return mix(mix(mix(gHash(i), gHash(i+vec3(1,0,0)), f.x),
                             mix(gHash(i+vec3(0,1,0)), gHash(i+vec3(1,1,0)), f.x), f.y),
                         mix(mix(gHash(i+vec3(0,0,1)), gHash(i+vec3(1,0,1)), f.x),
                             mix(gHash(i+vec3(0,1,1)), gHash(i+vec3(1,1,1)), f.x), f.y), f.z);
            }

            void main(){
              vec3 n = normalize(vNor);
              if(!gl_FrontFacing) n = -n;
              float hl = dot(n, uLightDir) * 0.5 + 0.5;
              if(uToon > 0.5){
                float steps = max(2.0, uToonStep);
                hl = clamp(floor(hl * steps) / (steps - 1.0), 0.0, 1.0);
              }
              float lit = uAmbient + (1.0 - uAmbient) * hl * uIntensity;
              float shade = mix(1.0, lit, uShade);
              if(vCol.a < 0.004) discard;
              vec3 rgb = vCol.rgb * shade * mix(vec3(1.0), uLightCol, uShade);

              /* glow: an emissive core that falls off at grazing angles, so the
                 tube reads as a light source rather than a flat additive smear.

                 One deliberate divergence. The web build writes this rim as
                 dot(n, vec3(0,0,1)), which works there because three.js gives
                 the fragment a VIEW-space normal, so +Z is the way the camera
                 faces. Anvil's normals are in world space — the geometry is
                 built in world coordinates so the grain can be anchored to the
                 paper — and world +Z is a fixed compass direction, which would
                 make the glow brightest towards north whatever you were
                 looking at. The eye vector is what that expression MEANT. */
              if(uGlow > 0.5){
                vec3 v = normalize(uEye - vPos);
                float rim = pow(abs(dot(n, v)), 0.5);
                rgb = vCol.rgb * (0.55 + 1.15*rim);
              }

              /* FACT: "Selected curves are highlighted in green", and
                 selected resources are too — so a curve and the guide it sits
                 on answer a selection in the same colour, which is the green
                 the guide pass has always used. It was blue here, which read
                 as a different kind of state. */
              rgb = mix(rgb, vec3(0.36, 0.85, 0.55), uSelect * 0.55);
              float a = vCol.a * uFade;

              if(uGrit > 0.5){
                vec3 mm = vPos * 1000.0;
                float tooth = gNoise(mm * 0.75) * 0.65 + gNoise(mm * 2.4) * 0.35;
                a *= clamp(0.32 + 1.15 * tooth, 0.0, 1.0);
                if(a < 0.012) discard;
              }

              float d = length(vPos - uEye);
              float f = clamp((d - uFogNear) / (uFogFar - uFogNear), 0.0, 1.0);
              fragColor = vec4(mix(rgb, uFogCol, f), a);
            }"""

        /*
         * The guide surface: translucent, grid-lined, background-aware.
         *
         * Ported from the VERT/FRAG pair in `js/guides.js`. Two things carry
         * over exactly because they are what make a guide read as a SURFACE
         * rather than a haze: the grid lines are drawn in the surface's own
         * arc-length coordinates, so they keep a constant physical spacing
         * however the sheet is stretched; and grazing angles get a lift, so the
         * silhouette firms up and the form reads as a volume.
         *
         * `fwidth` needs a derivatives extension in WebGL 1 and is core in
         * GLSL ES 3.0, which is the one place this side has it easier.
         */
        const val GUIDE_VERT = """#version 300 es
            precision highp float;
            in vec3 aPos;
            in vec3 aNor;
            in vec2 aUvw;
            uniform mat4 uMvp;
            out vec2 vUv2;
            out vec3 vN;
            out vec3 vW;
            void main(){
              vUv2 = aUvw;
              vN = aNor;
              vW = aPos;
              gl_Position = uMvp * vec4(aPos, 1.0);
            }"""

        const val GUIDE_FRAG = """#version 300 es
            precision highp float;
            uniform vec3  uFill;
            uniform vec3  uLine;
            uniform vec3  uEye;
            uniform float uOpacity;
            uniform float uStep;
            uniform float uMode;
            uniform float uSelect;
            in vec2 vUv2;
            in vec3 vN;
            in vec3 vW;
            out vec4 fragColor;

            float gridFactor(vec2 c, float step){
              vec2 g = c / step;
              vec2 d = fwidth(g);
              vec2 f = abs(fract(g - 0.5) - 0.5) / max(d, 1e-5);
              return 1.0 - min(min(f.x, f.y), 1.0);
            }

            void main(){
              vec3 n = normalize(vN);
              if(!gl_FrontFacing) n = -n;
              float line;
              if(uMode < 0.5){
                line = gridFactor(vUv2, uStep);
              } else {
                /* a primitive has no arc-length grid, so the lines come from a
                   triplanar projection of world space instead */
                vec3 an = abs(normalize(vN));
                float w = max(an.x + an.y + an.z, 1e-4);
                line = ( gridFactor(vW.yz, uStep)*an.x
                       + gridFactor(vW.xz, uStep)*an.y
                       + gridFactor(vW.xy, uStep)*an.z ) / w;
              }
              vec3 view = normalize(uEye - vW);
              float facing = abs(dot(n, view));
              float rim = pow(1.0 - facing, 2.0);
              float a = uOpacity * (0.55 + 0.45*rim);
              vec3 col = mix(uFill, uLine, line);
              a = mix(a, min(uOpacity*1.9, 0.95), line*0.85);
              col = mix(col, vec3(0.36,0.85,0.55), uSelect*0.6);
              if(a < 0.002) discard;
              fragColor = vec4(col, a);
            }"""

        /**
         * The post pass.
         *
         * Ported from fx.js, with one substitution that the roadmap called for:
         * the depth comes from a real DEPTH_COMPONENT24 texture rather than
         * from depth packed across four 8-bit channels. The web build packs
         * because a WebGL DepthTexture came back reading 1.0 everywhere and
         * because 16 bits over a 400,000:1 range is almost all spent in the
         * first few centimetres. GL ES 3.0 gives 24 bits sampleable directly
         * off the attachment the scene already wrote, so the unpack constants,
         * the extra render target and the second geometry pass all go.
         *
         * PIXELATION FIRST, so everything after it is sampled on the block grid
         * and the picture reads as one resolution rather than as a sharp image
         * with blocky edges pasted over it.
         *
         * The twelve defocus taps are a golden-angle spiral: enough to read as
         * defocus rather than as a box, cheap enough to stay one pass. Each tap
         * is weighted by how out of focus IT is, so a sample nearer the camera
         * than the focus cannot bleed onto a sharp background.
         *
         * The grain is keyed to gl_FragCoord and a FIXED seed. Grain belongs to
         * the image rather than to the scene, so it must not swim when the
         * camera moves; and reseeding per frame is television static, where
         * film grain belongs to the print and does not crawl when you look at
         * it.
         */
        const val POST_VERT = """#version 300 es
            precision highp float;
            in vec2 aPos;
            out vec2 vUv;
            void main(){
              vUv = aPos * 0.5 + 0.5;
              gl_Position = vec4(aPos, 0.0, 1.0);
            }"""

        const val POST_FRAG = """#version 300 es
            precision highp float;
            in vec2 vUv;
            uniform sampler2D uColor;
            uniform sampler2D uDepth;
            uniform vec2  uTexel;
            uniform float uNear;
            uniform float uFar;
            uniform float uOrtho;
            uniform float uFocus;
            uniform float uRange;
            uniform float uDof;
            uniform float uGrain;
            uniform float uPixel;
            uniform vec2  uGrid;
            out vec4 fragColor;

            /* the depth buffer is non-linear under a perspective camera, so a
               distance comparison has to undo the projection first */
            float viewDepth(vec2 uv){
              float d = texture(uDepth, uv).r;
              if(uOrtho > 0.5) return mix(uNear, uFar, d);
              float ndc = d * 2.0 - 1.0;
              return (2.0 * uNear * uFar) / (uFar + uNear - ndc * (uFar - uNear));
            }

            float hash(vec2 p){
              return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
            }

            const int TAPS = 12;

            void main(){
              vec2 uv = vUv;
              if(uPixel > 0.5) uv = (floor(uv * uGrid) + 0.5) / uGrid;

              vec3 col = texture(uColor, uv).rgb;

              if(uDof > 0.5){
                float dist = viewDepth(uv);
                float coc = clamp(abs(dist - uFocus) / max(uRange, 1e-4), 0.0, 1.0);
                float r = coc * 9.0;
                if(r > 0.6){
                  vec3 sum = col; float wsum = 1.0;
                  for(int i = 0; i < TAPS; i++){
                    float a = float(i) * 2.39996;
                    float t = sqrt((float(i) + 0.5) / float(TAPS));
                    vec2 off = vec2(cos(a), sin(a)) * t * r * uTexel;
                    float dd = viewDepth(uv + off);
                    float w = clamp(abs(dd - uFocus) / max(uRange, 1e-4), 0.0, 1.0);
                    w = max(w, 0.05);
                    sum += texture(uColor, uv + off).rgb * w;
                    wsum += w;
                  }
                  col = sum / wsum;
                }
              }

              if(uGrain > 0.0){
                float n = hash(gl_FragCoord.xy) - 0.5;
                col += n * uGrain;
              }

              fragColor = vec4(col, 1.0);
            }"""

        /**
         * The silhouette pass: position only, one flat colour, no lighting.
         * The alpha is what the ground samples, so it is 1 wherever a stroke
         * covered the texel and 0 where nothing did.
         */
        const val SHADOW_VERT = """#version 300 es
            precision highp float;
            in vec3 aPos;
            uniform mat4 uMvp;
            void main(){ gl_Position = uMvp * vec4(aPos, 1.0); }"""

        const val SHADOW_FRAG = """#version 300 es
            precision mediump float;
            out vec4 fragColor;
            void main(){ fragColor = vec4(0.0, 0.0, 0.0, 1.0); }"""

        /**
         * The ground: a quad at y = 0 that projects each of its own fragments
         * into the light's clip space and reads the silhouette there.
         *
         * Ported from GROUND_VERT/GROUND_FRAG in js/camera.js, including the
         * five-tap blur: a single tap gives a stencil cut, and the point of a
         * ground shadow is that it is soft.
         */
        const val GROUND_VERT = """#version 300 es
            precision highp float;
            in vec3 aPos;
            uniform mat4 uMvp;
            uniform mat4 uLightVp;
            out vec4 vLightPos;
            void main(){
              vLightPos = uLightVp * vec4(aPos, 1.0);
              gl_Position = uMvp * vec4(aPos, 1.0);
            }"""

        const val GROUND_FRAG = """#version 300 es
            precision highp float;
            in vec4 vLightPos;
            uniform sampler2D uMask;
            uniform vec3 uColor;
            uniform float uStrength;
            uniform float uSoft;
            out vec4 fragColor;
            void main(){
              vec3 lp = vLightPos.xyz / vLightPos.w;
              vec2 uv = lp.xy * 0.5 + 0.5;
              if(uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) discard;
              float o = uSoft;
              float a  = texture(uMask, uv).a * 0.4;
              a += texture(uMask, uv + vec2( o, 0.0)).a * 0.15;
              a += texture(uMask, uv + vec2(-o, 0.0)).a * 0.15;
              a += texture(uMask, uv + vec2(0.0,  o)).a * 0.15;
              a += texture(uMask, uv + vec2(0.0, -o)).a * 0.15;
              if(a < 0.004) discard;
              fragColor = vec4(uColor, a * uStrength);
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
