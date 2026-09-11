package art.plume.anvil

import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * ASK FOR A DEEP DEPTH BUFFER. THE DEFAULT IS SIXTEEN BITS.
 *
 * GLSurfaceView picks the surface format itself when nobody tells it what to
 * use, and what it picks is `SimpleEGLConfigChooser(true)` — eight bits of
 * each colour and SIXTEEN of depth. Sixteen bits is a 1996 number. It was
 * never a deliberate choice here; it is what you get for not choosing.
 *
 * FACT: the smallest depth difference the hardware can resolve is one part in
 * 2^bits of the clip range, so the jump from 16 to 24 is not a refinement, it
 * is two hundred and fifty six times finer. Everything this app does with
 * depth is measured in those units — the tie-break that decides which of two
 * curves painted on one guide shows on top counts in them, and so does the
 * gap between the near and the far wall of a tube. At sixteen bits the
 * tie-break was the bigger of the two, so the back of a shape came through
 * the front of it. At twenty-four the tube is orders of magnitude clear.
 *
 * Falling back matters as much as asking: eglChooseConfig hands back nothing
 * rather than something close, so a device with no 24-bit config would take
 * a hard IllegalArgumentException out of GLSurfaceView's own chooser. Every
 * depth is tried in turn and the first that exists wins, which lands on the
 * old sixteen only where nothing better is offered.
 */
internal class DepthFirstConfigChooser : GLSurfaceView.EGLConfigChooser {

    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        for (depth in DEPTHS) pick(egl, display, depth)?.let { return it }
        throw IllegalStateException("no 8/8/8 EGL config with a depth buffer")
    }

    private fun pick(egl: EGL10, display: EGLDisplay, depth: Int): EGLConfig? {
        val spec = intArrayOf(
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_ALPHA_SIZE, 0,
            EGL10.EGL_DEPTH_SIZE, depth,
            EGL10.EGL_STENCIL_SIZE, 0,
            /* the same bit GLSurfaceView sets for any client version above 1:
               ES3 contexts are created against ES2-renderable configs */
            EGL10.EGL_RENDERABLE_TYPE, ES2_BIT,
            EGL10.EGL_NONE,
        )
        val count = IntArray(1)
        if (!egl.eglChooseConfig(display, spec, null, 0, count) || count[0] <= 0) return null
        val found = arrayOfNulls<EGLConfig>(count[0])
        if (!egl.eglChooseConfig(display, spec, found, count[0], count)) return null

        /*
         * eglChooseConfig's sizes are MINIMA, and its sort order prefers the
         * deepest colour it has — which on some drivers means a 10-bit or
         * floating point surface for a request that only said "at least 8".
         * The canvas is 8-bit sRGB, so anything wider is memory bandwidth
         * spent on nothing. Exactly eight, and among those the one that also
         * skips the alpha channel it does not use.
         */
        var spare: EGLConfig? = null
        for (c in found) {
            if (c == null) continue
            if (attr(egl, display, c, EGL10.EGL_RED_SIZE) != 8) continue
            if (attr(egl, display, c, EGL10.EGL_GREEN_SIZE) != 8) continue
            if (attr(egl, display, c, EGL10.EGL_BLUE_SIZE) != 8) continue
            if (attr(egl, display, c, EGL10.EGL_DEPTH_SIZE) < depth) continue
            if (attr(egl, display, c, EGL10.EGL_ALPHA_SIZE) == 0) return c
            if (spare == null) spare = c
        }
        return spare
    }

    private fun attr(egl: EGL10, display: EGLDisplay, c: EGLConfig, which: Int): Int {
        val out = IntArray(1)
        return if (egl.eglGetConfigAttrib(display, c, which, out)) out[0] else 0
    }

    private companion object {
        /** Deepest first. 16 is the floor because it is what we had. */
        val DEPTHS = intArrayOf(24, 16)

        /** `EGL_OPENGL_ES2_BIT`, which EGL10 does not name. */
        const val ES2_BIT = 4
    }
}
