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
        /** dx/dy in pixels, dScale as a multiplier, dRotate in radians */
        fun onCamera(dx: Float, dy: Float, dScale: Float, dRotate: Float)
        fun onHover(x: Float, y: Float, pressure: Float)
        fun onHoverExit()

        /**
         * Held still on one pointer for [LONG_PRESS_MS].
         *
         * Selection hangs off a long press rather than a tap because a tap is
         * already a stroke — a one-point one that gets thrown away, but the
         * pen is committed to drawing from the moment it lands. A hold is the
         * only single-pointer gesture left that does not fight the pen.
         *
         * The stroke in progress is cancelled first, so a hold never leaves a
         * dot behind where the user was pointing.
         */
        fun onLongPress(x: Float, y: Float)
    }

    /** Finger drawing can be switched off, as on the desktop build. */
    var fingerDraws = true

    private var drawingPointer = -1
    private var gesturing = false

    /** Long press: where the pointer landed, when, and whether it has fired. */
    private var pressX = 0f
    private var pressY = 0f
    private var pressAt = 0L
    private var pressLive = false

    private var lastCx = 0f
    private var lastCy = 0f
    private var lastSpan = 0f
    private var lastAngle = 0f

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
                if (isStylus(ev, i) || fingerDraws) {
                    drawingPointer = ev.getPointerId(i)
                    beginPress(ev.getX(i), ev.getY(i), ev.eventTime)
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

                pressLive = false            // a second pointer is never a hold
                if (isStylus(ev, newIndex)) {
                    // the pen arrived after a finger: hand the stroke to the pen
                    if (drawingPointer >= 0) listener.onDrawCancel()
                    endGesture()
                    drawingPointer = ev.getPointerId(newIndex)
                    beginPress(ev.getX(newIndex), ev.getY(newIndex), ev.eventTime)
                    listener.onDrawBegin(
                        ev.getX(newIndex), ev.getY(newIndex), pressureOf(ev, newIndex),
                        tiltAz(ev, newIndex).toFloat(), tiltAlt(ev, newIndex)
                    )
                    return true
                }

                // a second finger: the first one was never a stroke, it was a camera move
                if (ev.pointerCount >= 2) {
                    if (drawingPointer >= 0) { listener.onDrawCancel(); drawingPointer = -1 }
                    beginGesture(ev)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (drawingPointer >= 0) {
                    val i = ev.findPointerIndex(drawingPointer)
                    if (i >= 0 && stepPress(ev.getX(i), ev.getY(i), ev.eventTime)) {
                        // the hold fired: the stroke becomes a selection instead
                        listener.onDrawCancel()
                        drawingPointer = -1
                        listener.onLongPress(pressX, pressY)
                        return true
                    }
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
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val goneId = ev.getPointerId(ev.actionIndex)
                if (goneId == drawingPointer) { listener.onDrawEnd(); drawingPointer = -1 }
                if (ev.pointerCount - 1 < 2) endGesture()
                else beginGesture(ev)          // re-seed from whoever is left
            }

            MotionEvent.ACTION_UP -> {
                /* A hold that never moved gets no further ACTION_MOVE to fire
                   it, so the release has to check the clock as well — holding
                   perfectly still is the ONE case the move path cannot see. */
                if (drawingPointer >= 0 && stepPress(pressX, pressY, ev.eventTime)) {
                    listener.onDrawCancel()
                    drawingPointer = -1
                    listener.onLongPress(pressX, pressY)
                } else if (drawingPointer >= 0) {
                    listener.onDrawEnd(); drawingPointer = -1
                }
                pressLive = false
                endGesture()
            }

            MotionEvent.ACTION_CANCEL -> {
                if (drawingPointer >= 0) { listener.onDrawCancel(); drawingPointer = -1 }
                pressLive = false
                endGesture()
            }
        }
        return true
    }

    // ---- long press -----------------------------------------------------

    private fun beginPress(x: Float, y: Float, at: Long) {
        pressX = x; pressY = y; pressAt = at; pressLive = true
    }

    /**
     * True exactly once, on the event that takes the hold over the line.
     *
     * Moving past [LONG_PRESS_SLOP_PX] cancels it — that is a stroke, not a
     * hold — and it stays cancelled for the rest of the pointer's life so a
     * long drag that happens to pause cannot turn into a selection.
     */
    private fun stepPress(x: Float, y: Float, at: Long): Boolean {
        if (!pressLive) return false
        if (hypot(x - pressX, y - pressY) > LONG_PRESS_SLOP_PX) { pressLive = false; return false }
        if (at - pressAt < LONG_PRESS_MS) return false
        pressLive = false
        return true
    }

    private fun beginGesture(ev: MotionEvent) {
        gesturing = true
        val (cx, cy, span, angle) = measure(ev)
        lastCx = cx; lastCy = cy; lastSpan = span; lastAngle = angle
    }

    private fun endGesture() { gesturing = false }

    private fun stepGesture(ev: MotionEvent) {
        val (cx, cy, span, angle) = measure(ev)
        if (lastSpan <= 0f) { lastCx = cx; lastCy = cy; lastSpan = span; lastAngle = angle; return }

        var dAngle = angle - lastAngle
        // shortest way round, so crossing the +/-pi seam does not spin the view
        while (dAngle > Math.PI) dAngle -= (2 * Math.PI).toFloat()
        while (dAngle < -Math.PI) dAngle += (2 * Math.PI).toFloat()

        listener.onCamera(cx - lastCx, cy - lastCy, span / lastSpan, dAngle)
        lastCx = cx; lastCy = cy; lastSpan = span; lastAngle = angle
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

    companion object {
        /** Android's own long-press default; a sketch should not feel different. */
        const val LONG_PRESS_MS = 500L
        /** Generous: a hand resting on glass drifts, and this must not need a vice. */
        const val LONG_PRESS_SLOP_PX = 24f
    }
}
