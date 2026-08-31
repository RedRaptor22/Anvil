package art.plume.anvil

import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Turning Android touch into the four things a 3D sketchbook needs.
 *
 * This is deliberately NOT a translation of the desktop's mouse handling. On a
 * desktop, orbit/pan/zoom hang off modifier keys and a wheel; on a phone there
 * are no modifiers, so the number of fingers IS the mode:
 *
 *  - one finger or a stylus  draws
 *  - two fingers             orbit, pan and zoom at once
 *
 * The one rule that matters most: **a stylus always draws, even mid-gesture**,
 * and a finger never draws while a second one is down. Feather's whole premise
 * is resting your hand on the glass, so a palm landing after the pen must not
 * turn the stroke into a camera move.
 */
class Gestures(private val listener: Listener) {

    interface Listener {
        fun onDrawBegin(x: Float, y: Float, pressure: Float, tiltAz: Float, tiltAlt: Float)
        fun onDrawMove(x: Float, y: Float, pressure: Float, tiltAz: Float, tiltAlt: Float)
        fun onDrawEnd()
        fun onDrawCancel()
        /**
         * dx/dy in pixels, dScale as a multiplier, dRotate in radians.
         *
         * [fingers] is how many are on the glass, because the number of fingers
         * IS the mode here: two orbit, three pan. There are no modifier keys to
         * hang that off, and putting pan on the same gesture as orbit would mean
         * guessing which one was meant.
         */
        fun onCamera(dx: Float, dy: Float, dScale: Float, dRotate: Float, fingers: Int)

        /** The fingers left the glass; [dx]/[dy] were the last per-move deltas. */
        fun onCameraEnd(dx: Float, dy: Float)
        fun onHover(x: Float, y: Float, pressure: Float)
        fun onHoverExit()

        /**
         * FACT (B.1): a one-finger double-tap snaps to the nearest of the six
         * standard views; a three-finger double-tap toggles the projection.
         *
         * [fingers] is the gesture's PEAK count, not the count at the moment
         * the last finger lifted — fingers come off one at a time, so reading
         * the live count would report every three-finger tap as a one-finger
         * one.
         */
        fun onDoubleTap(x: Float, y: Float, fingers: Int)

        /**
         * FACT (B.2/B.3): a press and hold on a curve or the grid pins the
         * orbit point; on empty space it unpins it, or resets the view when it
         * was not pinned.
         */
        fun onPressHold(x: Float, y: Float)
    }

    /** Finger drawing can be switched off, as on the desktop build. */
    var fingerDraws = true

    private var drawingPointer = -1
    private var gesturing = false

    // ---- taps and holds ---------------------------------------------------

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var holdRunnable: Runnable? = null
    private var holdX = 0f
    private var holdY = 0f

    /**
     * A gesture spans from the first finger landing to the last one lifting,
     * and its PEAK finger count is what identifies it.
     */
    private var peakFingers = 0
    private var gestureMoved = false
    private var gestureHeld = false
    private var gestureStart = 0L
    private var downX = 0f
    private var downY = 0f

    /**
     * The tap waiting to be doubled. `fingers == 0` means nothing is pending,
     * and it has to be an explicit sentinel rather than a zeroed timestamp:
     * uptimeMillis is not zero at boot, but a zeroed one would still be inside
     * the window forever after.
     */
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var lastTapFingers = 0

    private var lastCx = 0f
    private var lastCy = 0f
    private var lastSpan = 0f
    private var lastAngle = 0f

    /** The last per-move deltas, so a flick can hand on its momentum. */
    private var lastDx = 0f
    private var lastDy = 0f

    private fun isStylus(ev: MotionEvent, index: Int): Boolean =
        ev.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS ||
            ev.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER

    /**
     * Android reports tilt as one angle from vertical plus an orientation; the
     * core wants an azimuth and an altitude, the same pair the web build reads
     * out of a PointerEvent.
     */
    private fun tiltAz(ev: MotionEvent, i: Int) = ev.getOrientation(i)
    private fun tiltAlt(ev: MotionEvent, i: Int) =
        (Math.PI / 2 - ev.getAxisValue(MotionEvent.AXIS_TILT, i)).toFloat()

    private fun pressureOf(ev: MotionEvent, i: Int): Float {
        val p = ev.getPressure(i)
        // a finger reports 1.0 (or 0) on most panels; only trust a stylus
        return if (isStylus(ev, i)) p.coerceIn(0f, 1f) else 0.5f
    }

    fun onHoverEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER ->
                listener.onHover(ev.x, ev.y, pressureOf(ev, 0))
            MotionEvent.ACTION_HOVER_EXIT -> listener.onHoverExit()
        }
        return true
    }

    fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                val i = 0
                peakFingers = 1
                gestureMoved = false
                gestureHeld = false
                gestureStart = ev.eventTime
                downX = ev.getX(i); downY = ev.getY(i)
                /*
                 * The hold clock runs for a finger, not for the pen. A pen
                 * resting still mid-stroke is hold-to-shape's business, and
                 * pinning the orbit point under it as well would be two
                 * different things happening to one gesture.
                 */
                if (!isStylus(ev, i)) armHold(ev.getX(i), ev.getY(i))
                if (isStylus(ev, i) || fingerDraws) {
                    drawingPointer = ev.getPointerId(i)
                    listener.onDrawBegin(
                        ev.getX(i), ev.getY(i), pressureOf(ev, i),
                        tiltAz(ev, i).toFloat(), tiltAlt(ev, i)
                    )
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val newIndex = ev.actionIndex
                /*
                 * A stylus outranks everything. If the pen is drawing, a second
                 * touch is a palm or a steadying hand and must be ignored
                 * entirely — not promoted into a camera gesture.
                 */
                val penDrawing = drawingPointer >= 0 &&
                    isStylus(ev, ev.findPointerIndex(drawingPointer).coerceAtLeast(0))
                if (penDrawing) return true

                if (isStylus(ev, newIndex)) {
                    // the pen arrived after a finger: hand the stroke to the pen
                    if (drawingPointer >= 0) listener.onDrawCancel()
                    endGesture()
                    drawingPointer = ev.getPointerId(newIndex)
                    listener.onDrawBegin(
                        ev.getX(newIndex), ev.getY(newIndex), pressureOf(ev, newIndex),
                        tiltAz(ev, newIndex).toFloat(), tiltAlt(ev, newIndex)
                    )
                    return true
                }

                // a second finger: the first one was never a stroke, it was a camera move
                if (ev.pointerCount >= 2) {
                    if (drawingPointer >= 0) { listener.onDrawCancel(); drawingPointer = -1 }
                    if (ev.pointerCount > peakFingers) peakFingers = ev.pointerCount
                    cancelHold()
                    beginGesture(ev)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (drawingPointer >= 0) {
                    val i = ev.findPointerIndex(drawingPointer)
                    if (i >= 0) {
                        // every batched sample, not just the latest: at 240Hz the
                        // pen reports far faster than the display refreshes, and
                        // dropping the history visibly corners a fast curve
                        for (h in 0 until ev.historySize) {
                            listener.onDrawMove(
                                ev.getHistoricalX(i, h), ev.getHistoricalY(i, h),
                                ev.getHistoricalPressure(i, h).coerceIn(0f, 1f),
                                tiltAz(ev, i).toFloat(), tiltAlt(ev, i)
                            )
                        }
                        listener.onDrawMove(
                            ev.getX(i), ev.getY(i), pressureOf(ev, i),
                            tiltAz(ev, i).toFloat(), tiltAlt(ev, i)
                        )
                    }
                } else if (gesturing && ev.pointerCount >= 2) {
                    stepGesture(ev)
                }
                /* a finger that travels is a swipe, not a tap, and never a hold */
                val moved = hypot(ev.getX(0) - downX, ev.getY(0) - downY)
                if (moved > TAP_SLOP) gestureMoved = true
                if (moved > HOLD_SLOP) cancelHold()
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val goneId = ev.getPointerId(ev.actionIndex)
                if (goneId == drawingPointer) { listener.onDrawEnd(); drawingPointer = -1 }
                if (ev.pointerCount - 1 < 2) endGesture()
                else beginGesture(ev)          // re-seed from whoever is left
            }

            MotionEvent.ACTION_UP -> {
                if (drawingPointer >= 0) { listener.onDrawEnd(); drawingPointer = -1 }
                cancelHold()
                endGesture()
                /*
                 * A tap is a gesture that did not travel, did not become a
                 * hold, and did not linger. A one-finger tap only counts when
                 * a finger was not drawing with it — otherwise every dot you
                 * draw would be half of a view snap.
                 */
                val quick = ev.eventTime - gestureStart < TAP_MAX_MS
                val drew = fingerDraws && peakFingers == 1
                if (!gestureMoved && !gestureHeld && quick && !drew) {
                    registerTap(ev.getX(0), ev.getY(0), peakFingers, ev.eventTime)
                }
                peakFingers = 0
            }

            MotionEvent.ACTION_CANCEL -> {
                if (drawingPointer >= 0) { listener.onDrawCancel(); drawingPointer = -1 }
                cancelHold()
                endGesture()
                peakFingers = 0
            }
        }
        return true
    }

    private fun armHold(x: Float, y: Float) {
        cancelHold()
        holdX = x; holdY = y
        val r = Runnable {
            holdRunnable = null
            gestureHeld = true          // a hold is never also a tap
            listener.onPressHold(holdX, holdY)
        }
        holdRunnable = r
        main.postDelayed(r, HOLD_MS)
    }

    private fun cancelHold() {
        holdRunnable?.let { main.removeCallbacks(it) }
        holdRunnable = null
    }

    /** Two taps of the same finger count, close together in time and space. */
    private fun registerTap(x: Float, y: Float, fingers: Int, now: Long) {
        val isDouble = lastTapFingers == fingers &&
            now - lastTapAt < TAP_MS &&
            hypot(x - lastTapX, y - lastTapY) < TAP_SLOP
        if (isDouble) {
            lastTapFingers = 0          // consumed, so a third tap is not a fourth
            listener.onDoubleTap(x, y, fingers)
            return
        }
        lastTapAt = now; lastTapX = x; lastTapY = y; lastTapFingers = fingers
    }

    private fun beginGesture(ev: MotionEvent) {
        gesturing = true
        lastDx = 0f; lastDy = 0f
        val (cx, cy, span, angle) = measure(ev)
        lastCx = cx; lastCy = cy; lastSpan = span; lastAngle = angle
    }

    private fun endGesture() {
        if (gesturing) listener.onCameraEnd(lastDx, lastDy)
        gesturing = false
        lastDx = 0f; lastDy = 0f
    }

    private fun stepGesture(ev: MotionEvent) {
        val (cx, cy, span, angle) = measure(ev)
        if (lastSpan <= 0f) { lastCx = cx; lastCy = cy; lastSpan = span; lastAngle = angle; return }

        var dAngle = angle - lastAngle
        // shortest way round, so crossing the +/-pi seam does not spin the view
        while (dAngle > Math.PI) dAngle -= (2 * Math.PI).toFloat()
        while (dAngle < -Math.PI) dAngle += (2 * Math.PI).toFloat()

        lastDx = cx - lastCx; lastDy = cy - lastCy
        listener.onCamera(lastDx, lastDy, span / lastSpan, dAngle, fingerCount(ev))
        lastCx = cx; lastCy = cy; lastSpan = span; lastAngle = angle
    }

    /** Pointers taking part in the camera gesture — the pen is not one of them. */
    private fun fingerCount(ev: MotionEvent): Int {
        var n = 0
        for (i in 0 until ev.pointerCount) if (ev.getPointerId(i) != drawingPointer) n++
        return n
    }

    private data class Measure(val cx: Float, val cy: Float, val span: Float, val angle: Float)

    private fun measure(ev: MotionEvent): Measure {
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until ev.pointerCount) {
            if (ev.getPointerId(i) == drawingPointer) continue
            sx += ev.getX(i); sy += ev.getY(i); n++
        }
        if (n == 0) return Measure(0f, 0f, 0f, 0f)
        val cx = sx / n; val cy = sy / n
        if (n < 2) return Measure(cx, cy, lastSpan, lastAngle)
        val dx = ev.getX(1) - ev.getX(0)
        val dy = ev.getY(1) - ev.getY(0)
        return Measure(cx, cy, hypot(dx, dy), atan2(dy, dx))
    }

    private operator fun Measure.component1() = cx
    private operator fun Measure.component2() = cy
    private operator fun Measure.component3() = span
    private operator fun Measure.component4() = angle

    private companion object {
        /** All four are the web build's, in the same units. */
        const val HOLD_MS = 480L
        const val HOLD_SLOP = 12f
        const val TAP_MS = 300L
        const val TAP_SLOP = 22f
        const val TAP_MAX_MS = 420L
    }
}
