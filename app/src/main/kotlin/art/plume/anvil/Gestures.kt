package art.plume.anvil

import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Turning Android touch into Feather's documented navigation set (B.1).
 *
 *     one finger swipe .............. orbit, with release momentum
 *     one finger double-tap ......... snap to the nearest of the six views
 *     two finger tap ................ undo
 *     pinch ......................... zoom
 *     two finger swipe .............. pan
 *     two finger twist .............. roll the canvas
 *     three finger double-tap ....... perspective <-> orthographic
 *     three finger swipe (vertical).. focal length, 10-500mm
 *     tap and hold on curve/grid .... pin the orbit point
 *     tap and hold on empty space ... unpin, or reset the view
 *
 * THE MAPPING SHIFTS BY ONE FINGER WHEN A FINGER IS DRAWING, because one
 * finger is then busy: two fingers orbit instead of panning, three pan instead
 * of setting the lens, and the lens moves to its slider. That shift is the web
 * build's, and [fingerDraws] is the switch. This class reports the finger
 * count and lets the listener do the mapping.
 *
 * Two fingers tapping to undo is the one addition. The web build's own note
 * calls a gesture undo "the documented rough edge worth fixing" — Feather has
 * none — and every tablet drawing app has taught the same two-finger tap.
 * Nothing else in the set uses a two-finger single tap, so there is nothing
 * for it to collide with.
 *
 * The one rule that matters most: **a stylus always draws, even mid-gesture**,
 * and a finger never draws while a second one is down. Feather's whole premise
 * is resting your hand on the glass, so a palm landing after the pen must not
 * turn the stroke into a camera move — and, when the palm finally lifts, must
 * not turn it into a tap either.
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
         * A single tap that finished as a tap, with the gesture's peak finger
         * count.
         *
         * Only reported for counts that mean something on their own — which is
         * two, for undo. A one-finger tap has to wait to see whether it is
         * half of a view snap, and a three-finger one half of a projection
         * toggle, so those arrive through [onDoubleTap] or not at all.
         */
        fun onTap(x: Float, y: Float, fingers: Int)

        /**
         * A real stylus touched the glass for the first time.
         *
         * Finger drawing is on by default because most Android tablets ship
         * without a usable stylus, and this is the moment that assumption is
         * proved wrong. The app hands itself back to the pen-first mapping,
         * once, and says so.
         */
        fun onPenDetected()

        /**
         * FACT (B.2/B.3): a press and hold on a curve or the grid pins the
         * orbit point; on empty space it unpins it, or resets the view when it
         * was not pinned.
         */
        fun onPressHold(x: Float, y: Float)

        /**
         * A press and hold by the pointer that is DRAWING, which only the tap
         * tools ask for.
         *
         * Select is a tap tool rather than a drag tool, so its press is free
         * to mean something on its own: holding it picks the guide underneath
         * and hands it to the joystick. Draw's press is a stroke and has no
         * hold to give — [holdWhileDrawing] is what says which is which.
         */
        fun onDrawHold(x: Float, y: Float)
    }

    /**
     * Whether a bare finger draws.
     *
     * On by default, because most Android tablets ship without a usable
     * stylus and the documented Finger-Pen "workaround" is the only way to
     * draw at all on one. [penSeen] takes it back the moment that turns out
     * not to be true here.
     */
    var fingerDraws = true

    /**
     * True once the user has changed [fingerDraws] themselves, after which a
     * pen landing does not get to change it back. Their setting outranks our
     * guess about their hardware.
     */
    var fingerDrawsIsOurs = true

    /** A real stylus has touched the glass at least once. */
    private var penSeen = false

    /**
     * Whether the drawing pointer should also run a hold clock.
     *
     * Set by the tool: Select and Loft are tap tools, so their press can carry
     * a hold; every drag tool's press is the drag itself. The web build makes
     * the same split inside `penBegin`, by reading the tool there.
     */
    var holdWhileDrawing = false

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
     * A PEN TOOK PART, SO THE GESTURE IS NOT A TAP.
     *
     * A palm resting through a whole pen stroke lifts like any other finger,
     * and without this its lift was a two-finger tap — so drawing with your
     * hand on the glass undid the stroke you had just drawn.
     */
    private var gesturePen = false

    /**
     * Where the gesture was, measured when it reached its peak finger count.
     *
     * Not the position of whichever finger happened to lift last: fingers come
     * off one at a time, so that point jumps by far more than TAP_SLOP between
     * two taps of the same three-finger gesture, and the double-tap test could
     * never match.
     */
    private var tapX = 0f
    private var tapY = 0f

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
                notePen(ev, i)
                peakFingers = 1
                gestureMoved = false
                gestureHeld = false
                gesturePen = isStylus(ev, i)
                gestureStart = ev.eventTime
                downX = ev.getX(i); downY = ev.getY(i)
                tapX = downX; tapY = downY
                /*
                 * The hold clock runs for a pointer that is NAVIGATING, not
                 * one that is drawing.
                 *
                 * A pointer resting still mid-stroke is hold-to-shape's
                 * business, and pinning the orbit point under it as well would
                 * be two different things happening to one gesture. The test
                 * used to be "not a stylus", which is only half of it: with
                 * finger drawing on, a finger holding still both drew and
                 * pinned the pivot out from under its own stroke.
                 */
                val willDraw = isStylus(ev, i) || fingerDraws
                if (!willDraw || holdWhileDrawing) armHold(ev.getX(i), ev.getY(i), willDraw)
                if (willDraw) {
                    drawingPointer = ev.getPointerId(i)
                    listener.onDrawBegin(
                        ev.getX(i), ev.getY(i), pressureOf(ev, i),
                        tiltAz(ev, i).toFloat(), tiltAlt(ev, i)
                    )
                } else {
                    /*
                     * ONE FINGER ORBITS. B.1's first line, and it was missing
                     * entirely: a camera gesture only ever started on the
                     * SECOND finger landing, so with drawing handed to the pen
                     * a single finger did nothing at all.
                     */
                    beginGesture(ev)
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val newIndex = ev.actionIndex
                notePen(ev, newIndex)
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
                    cancelHold()
                    gesturePen = true
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
                    if (ev.pointerCount > peakFingers) {
                        peakFingers = ev.pointerCount
                        // the gesture is where its fingers are, at their widest
                        var sx = 0f; var sy = 0f
                        for (k in 0 until ev.pointerCount) { sx += ev.getX(k); sy += ev.getY(k) }
                        tapX = sx / ev.pointerCount; tapY = sy / ev.pointerCount
                    }
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
                } else if (gesturing) {
                    stepGesture(ev)
                }
                /* a pointer that travels is a swipe, not a tap, and never a
                   hold — measured on whichever pointer is DRAWING when one is,
                   since a pen's hold has to die when the pen moves and the pen
                   is not always index 0 */
                val hi = if (drawingPointer >= 0) ev.findPointerIndex(drawingPointer) else 0
                if (hi >= 0) {
                    val moved = hypot(ev.getX(hi) - downX, ev.getY(hi) - downY)
                    if (moved > TAP_SLOP) gestureMoved = true
                    if (moved > HOLD_SLOP) cancelHold()
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val goneId = ev.getPointerId(ev.actionIndex)
                if (goneId == drawingPointer) { listener.onDrawEnd(); drawingPointer = -1 }
                /*
                 * Re-seed from whoever is left, INCLUDING a single finger:
                 * lifting one of two fingers hands the gesture back to a
                 * one-finger orbit rather than ending navigation with a finger
                 * still on the glass. Ending it here was what made a pinch
                 * that let go unevenly feel like it had died.
                 *
                 * A finger left over from a pen stroke is not navigation
                 * though — that is a palm, and it stays inert until it lifts.
                 */
                val left = ev.pointerCount - 1
                if (left <= 0 || (gesturePen && drawingPointer < 0)) endGesture()
                else beginGesture(ev, skipIndex = ev.actionIndex)
            }

            MotionEvent.ACTION_UP -> {
                if (drawingPointer >= 0) { listener.onDrawEnd(); drawingPointer = -1 }
                cancelHold()
                endGesture()
                /*
                 * A tap is a gesture that did not travel, did not become a
                 * hold, and did not linger.
                 *
                 * A ONE-finger tap only counts when a finger was not drawing
                 * with it, or every dot you draw would be half of a view snap.
                 * That test used to read `fingerDraws && peakFingers == 1`,
                 * which with finger drawing on — its default — threw away
                 * every one-finger tap whatever the peak count had been, and
                 * with it the view snap. It is the count that decides.
                 *
                 * A gesture the pen took part in is never a tap at all: the
                 * finger lifting is a palm that has been resting there since
                 * before the stroke began.
                 */
                val quick = ev.eventTime - gestureStart < TAP_MAX_MS
                val drew = fingerDraws && peakFingers == 1 && !gesturePen
                if (!gestureMoved && !gestureHeld && !gesturePen && quick && !drew) {
                    registerTap(tapX, tapY, peakFingers, ev.eventTime)
                }
                peakFingers = 0
                gesturePen = false
            }

            MotionEvent.ACTION_CANCEL -> {
                if (drawingPointer >= 0) { listener.onDrawCancel(); drawingPointer = -1 }
                cancelHold()
                endGesture()
                peakFingers = 0
                gesturePen = false
                // a cancelled gesture is not a tap, and not half of one either
                lastTapFingers = 0
            }
        }
        return true
    }

    private fun armHold(x: Float, y: Float, whileDrawing: Boolean = false) {
        cancelHold()
        holdX = x; holdY = y
        val r = Runnable {
            holdRunnable = null
            gestureHeld = true          // a hold is never also a tap
            if (whileDrawing) listener.onDrawHold(holdX, holdY)
            else listener.onPressHold(holdX, holdY)
        }
        holdRunnable = r
        main.postDelayed(r, HOLD_MS)
    }

    private fun cancelHold() {
        holdRunnable?.let { main.removeCallbacks(it) }
        holdRunnable = null
    }

    /**
     * FIRST REAL PEN CONTACT HANDS THE APP BACK TO THE PEN.
     *
     * Finger drawing is on by default on the assumption that there is no
     * stylus, and this is the moment that assumption is disproved. Doing it
     * once, and only while the setting is still ours rather than the user's,
     * is the web build's rule.
     */
    private fun notePen(ev: MotionEvent, index: Int) {
        if (penSeen || !isStylus(ev, index)) return
        penSeen = true
        if (fingerDraws && fingerDrawsIsOurs) {
            fingerDraws = false
            fingerDrawsIsOurs = false
            listener.onPenDetected()
        }
    }

    /**
     * A finished tap: either the second of a pair, or one that means something
     * on its own.
     */
    private fun registerTap(x: Float, y: Float, fingers: Int, now: Long) {
        val isDouble = lastTapFingers == fingers &&
            now - lastTapAt < TAP_MS &&
            hypot(x - lastTapX, y - lastTapY) < TAP_SLOP
        if (isDouble) {
            lastTapFingers = 0          // consumed, so a third tap is not a fourth
            listener.onDoubleTap(x, y, fingers)
            return
        }
        /*
         * Two fingers mean undo on their own, so it fires now rather than
         * waiting out the double-tap window — a gesture you have to wait 300ms
         * to find out did nothing is worse than no gesture. Nothing is bound
         * to a two-finger double tap, so there is nothing to pre-empt.
         */
        if (fingers == 2) {
            lastTapFingers = 0
            listener.onTap(x, y, fingers)
            return
        }
        lastTapAt = now; lastTapX = x; lastTapY = y; lastTapFingers = fingers
    }

    /**
     * Start (or re-seed) the camera gesture from whichever pointers are still
     * navigating.
     *
     * [skipIndex] is the pointer that is on its way up: ACTION_POINTER_UP
     * still reports it, and seeding from a finger that is leaving puts a jump
     * into the first frame after it goes.
     */
    private fun beginGesture(ev: MotionEvent, skipIndex: Int = -1) {
        val m = measure(ev, skipIndex)
        /* NOTHING TO NAVIGATE WITH IS NOT A GESTURE. A palm lifting from
           beside a pen stroke leaves only the pen, which is drawing, not
           navigating — starting a camera gesture on it would hand the stroke's
           own pointer to the camera. */
        if (m.n == 0) { endGesture(); return }
        gesturing = true
        lastDx = 0f; lastDy = 0f
        lastCx = m.cx; lastCy = m.cy; lastSpan = m.span; lastAngle = m.angle
    }

    private fun endGesture() {
        if (gesturing) listener.onCameraEnd(lastDx, lastDy)
        gesturing = false
        lastDx = 0f; lastDy = 0f
    }

    private fun stepGesture(ev: MotionEvent) {
        val m = measure(ev)
        if (m.n == 0) return

        /*
         * ONE FINGER HAS NO SPAN AND NO ANGLE, only a centroid — so zoom and
         * roll sit out and the whole gesture is the drag. Requiring a span
         * here (the old `lastSpan <= 0` guard) is what stopped a one-finger
         * gesture dead on its first move.
         */
        var dScale = 1f
        var dAngle = 0f
        if (m.n >= 2 && lastSpan > 0f && m.span > 0f) {
            dScale = m.span / lastSpan
            dAngle = m.angle - lastAngle
            // shortest way round, so crossing the +/-pi seam does not spin the view
            while (dAngle > Math.PI) dAngle -= (2 * Math.PI).toFloat()
            while (dAngle < -Math.PI) dAngle += (2 * Math.PI).toFloat()
        }

        lastDx = m.cx - lastCx; lastDy = m.cy - lastCy
        listener.onCamera(lastDx, lastDy, dScale, dAngle, m.n)
        lastCx = m.cx; lastCy = m.cy; lastSpan = m.span; lastAngle = m.angle
    }

    private class Measure(
        val cx: Float, val cy: Float, val span: Float, val angle: Float, val n: Int,
    )

    /**
     * The centroid, span and twist of the pointers that are navigating.
     *
     * The pen is never one of them, and neither is [skipIndex] — the finger
     * ACTION_POINTER_UP is reporting on its way off the glass.
     *
     * Span and angle come from the first TWO NAVIGATING pointers rather than
     * from raw indices 0 and 1: index 0 can be the pen, which would have made
     * a pinch measure the distance between the pen and a finger.
     */
    private fun measure(ev: MotionEvent, skipIndex: Int = -1): Measure {
        var sx = 0f; var sy = 0f; var n = 0
        var ax = 0f; var ay = 0f; var bx = 0f; var by = 0f
        for (i in 0 until ev.pointerCount) {
            if (i == skipIndex || ev.getPointerId(i) == drawingPointer) continue
            val x = ev.getX(i); val y = ev.getY(i)
            sx += x; sy += y
            if (n == 0) { ax = x; ay = y } else if (n == 1) { bx = x; by = y }
            n++
        }
        if (n == 0) return Measure(0f, 0f, 0f, 0f, 0)
        val cx = sx / n; val cy = sy / n
        if (n < 2) return Measure(cx, cy, 0f, 0f, n)
        return Measure(cx, cy, hypot(bx - ax, by - ay), atan2(by - ay, bx - ax), n)
    }

    private companion object {
        /** All four are the web build's, in the same units. */
        const val HOLD_MS = 480L
        const val HOLD_SLOP = 12f
        const val TAP_MS = 300L
        const val TAP_SLOP = 22f
        const val TAP_MAX_MS = 420L
    }
}
