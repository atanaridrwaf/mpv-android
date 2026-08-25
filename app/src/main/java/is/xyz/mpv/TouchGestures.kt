package `is`.xyz.mpv

import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import kotlin.math.*

enum class PropertyChange {
    Init,
    Seek,
    Volume,
    Bright,
    Finalize,

    /* Tap gestures */
    SeekFixed,
    PlayPause,
    Custom,
}

internal interface TouchGesturesObserver {
    fun onPropertyChange(p: PropertyChange, diff: Float)
}

internal class TouchGestures(private val observer: TouchGesturesObserver) {

    private enum class State {
        Up,
        Down,
        Ignored,
        SystemGestureReplay,
        ControlSeek,
        ControlVolume,
        ControlBright,
    }

    private var state = State.Up
    // relevant movement direction for the current state (0=H, 1=V)
    private var stateDirection = 0

    // timestamp of the last tap (ACTION_UP)
    private var lastTapTime = 0L
    // when the current gesture began
    private var lastDownTime = 0L

    // where user initially placed their finger (ACTION_DOWN)
    private var initialPos = PointF()
    // last non-throttled processed position
    private var lastPos = PointF()
    // largest displacement seen before the gesture direction is locked
    private var maxAbsDx = 0f
    private var maxAbsDy = 0f
    // latest point that still belonged clearly to the active movement axis
    private var directionAnchor = PointF()
    // When an OEM navigation handler cancels Back/Home/Recents, it replays old events before
    // handing the still-active finger back to the app. Events generated at or after this time are
    // live again and may start a new player gesture without requiring ACTION_UP first.
    private var systemGestureReplayCatchUpTime = 0L
    // Once a horizontal seek is active, a vertical turn pauses seek updates instead of
    // finalizing the gesture. The frozen horizontal delta lets a later horizontal turn
    // continue from the same seek target without counting sideways drift from the vertical leg.
    private var seekVerticalMovement = false
    private var frozenSeekDx = 0f

    private var width = 0f
    private var height = 0f
    // minimum movement which triggers a Control state
    private var trigger = 0f

    // which property change should be invoked where
    private var gestureHoriz = State.Down
    private var gestureVertLeft = State.Down
    private var gestureVertRight = State.Down
    private var tapGestureLeft : PropertyChange? = null
    private var tapGestureCenter : PropertyChange? = null
    private var tapGestureRight : PropertyChange? = null

    private inline fun checkFloat(vararg n: Float): Boolean {
        return !n.any { it.isInfinite() || it.isNaN() }
    }
    private inline fun assertFloat(vararg n: Float) {
        if (!checkFloat(*n))
            throw IllegalArgumentException()
    }

    fun setMetrics(width: Float, height: Float) {
        assertFloat(width, height)
        this.width = width
        this.height = height
        trigger = min(width, height) / TRIGGER_RATE
    }

    companion object {
        private const val TAG = "mpv"

        // ratio for trigger, 1/Xth of minimum dimension
        // for tap gestures this is the distance that must *not* be moved for it to trigger
        private const val TRIGGER_RATE = 30

        // maximum duration between taps (ms) for a double tap to count
        private const val TAP_DURATION = 225L

        // full sweep from left side to right side is 2:30
        private const val CONTROL_SEEK_MAX = 150f

        // same as below, we rescale it inside MPVActivity
        private const val CONTROL_VOLUME_MAX = 1.5f

        // brightness is scaled 0..1; max's not 1f so that user does not have to start from the bottom
        // if they want to go from none to full brightness
        private const val CONTROL_BRIGHT_MAX = 1.5f

        // do not trigger on X% of screen top/bottom
        // this is so that user can open android status bar
        private const val DEADZONE = 5

        // Legacy three-button swipe navigation (notably Samsung's NavbarGestureHandler) owns the
        // outer 300px of the 2220px landscape display in the supplied log. This percentage is
        // used only to recognize a delayed replayed ACTION_DOWN; it is not a touch deadzone.
        private const val NAVIGATION_GESTURE_CAPTURE_PERCENT = 15

        // A rejected OEM navigation gesture is replayed with its original event timestamps. A
        // normal ACTION_DOWN reaches the app almost immediately; a replayed one arrives only
        // after the system has spent time deciding that Home/Back/Recents was not completed.
        private const val NAVIGATION_GESTURE_REPLAY_DELAY_MS = 80L

        // Seeking should feel like 1s steps on slow drag (Samsung-like).
        // We achieve this by sending seek updates more frequently than volume/brightness.
        private const val SEEK_THROTTLE_DIV = 24

        // Require gestures to be clearly horizontal/vertical before locking to that axis.
        // This prevents accidental seeks when the user swipes mostly vertically.
        private const val DIRECTION_LOCK_RATIO = 1.25f
    }

    fun cancel() {
        // If a control gesture was active, finalize it.
        if (state.isControl())
            sendPropertyChange(PropertyChange.Finalize, 0f)
        state = State.Up
        seekVerticalMovement = false
        frozenSeekDx = 0f
        lastTapTime = 0L
        systemGestureReplayCatchUpTime = 0L
    }

    private fun State.isControl(): Boolean {
        return this == State.ControlSeek ||
                this == State.ControlVolume ||
                this == State.ControlBright
    }

    private fun activationThreshold(gesture: State): Float {
        return if (gesture == State.ControlSeek) trigger / 4 else trigger
    }

    private fun isInNavigationGestureCaptureRegion(p: PointF): Boolean {
        val horizontalEdge = width * NAVIGATION_GESTURE_CAPTURE_PERCENT / 100f
        val verticalEdge = height * NAVIGATION_GESTURE_CAPTURE_PERCENT / 100f
        return p.x <= horizontalEdge || p.x >= width - horizontalEdge ||
                p.y >= height - verticalEdge
    }

    private fun isReplayedNavigationGestureDown(e: MotionEvent, p: PointF): Boolean {
        if (!isInNavigationGestureCaptureRegion(p))
            return false

        return SystemClock.uptimeMillis() - e.eventTime >= NAVIGATION_GESTURE_REPLAY_DELAY_MS
    }

    private fun resumeAfterSystemGesture(p: PointF) {
        // The distance accumulated while Back/Home/Recents owned the finger must not count toward
        // seek. Rebase at the first live point; the very next MOVE continues as a normal gesture.
        initialPos.set(p)
        lastPos.set(p)
        directionAnchor.set(p)
        maxAbsDx = 0f
        maxAbsDy = 0f
        seekVerticalMovement = false
        frozenSeekDx = 0f
        stateDirection = 0
        lastDownTime = 0L
        lastTapTime = 0L
        systemGestureReplayCatchUpTime = 0L
        state = State.Down
    }

    private fun activateGesture(
        gesture: State,
        direction: Int,
        p: PointF,
        rebaseOrigin: Boolean,
    ) {
        seekVerticalMovement = false
        frozenSeekDx = 0f
        stateDirection = direction
        state = if (gesture == State.Down) State.Ignored else gesture
        directionAnchor.set(p)

        if (state == State.Ignored)
            return

        if (!state.isControl())
            return

        // Give the observer a chance to cache values before modifying them.
        sendPropertyChange(PropertyChange.Init, 0f)

        // Seeking always starts at zero delta to avoid an activation jump. When changing
        // axes, every control starts from the turning point as though the new direction
        // had begun there.
        if (state == State.ControlSeek || rebaseOrigin) {
            initialPos.set(p)
            lastPos.set(p)
        }
    }

    private fun processVerticalMovementDuringSeek(p: PointF): Boolean {
        val dx = p.x - directionAnchor.x
        val dy = p.y - directionAnchor.y
        val absDx = abs(dx)
        val absDy = abs(dy)

        // Resume seek updates only after the new movement is clearly horizontal. Sideways
        // drift accumulated while moving vertically is excluded; only this horizontal leg is
        // added to the seek delta that was visible before the direction changed.
        val horizontalIntent =
            absDx > activationThreshold(State.ControlSeek) &&
            (absDy == 0f || absDx / absDy >= DIRECTION_LOCK_RATIO)
        if (horizontalIntent) {
            val resumedSeekDx = frozenSeekDx + dx
            initialPos.x = p.x - resumedSeekDx
            seekVerticalMovement = false
            frozenSeekDx = 0f
            stateDirection = 0
            directionAnchor.set(p)
            return false
        }

        // Follow the vertical leg with the direction anchor so a later horizontal turn is
        // recognized locally, regardless of how far up or down the finger has moved.
        val continuesVertically =
            absDy > 0f && (absDx == 0f || absDy / absDx >= DIRECTION_LOCK_RATIO)
        if (continuesVertically)
            directionAnchor.set(p)

        // Keep throttling relative to the latest ignored point. No observer event is sent, so
        // the pending seek target remains unchanged until horizontal movement resumes or the
        // finger is actually lifted.
        lastPos.set(p)
        return true
    }

    private fun processDirectionChange(p: PointF): Boolean {
        val dx = p.x - directionAnchor.x
        val dy = p.y - directionAnchor.y
        val currentDelta = if (stateDirection == 0) abs(dx) else abs(dy)
        val nextDelta = if (stateDirection == 0) abs(dy) else abs(dx)
        val nextDirection = 1 - stateDirection
        val nextGesture = if (nextDirection == 0) {
            gestureHoriz
        } else {
            if (p.x > width / 2) gestureVertRight else gestureVertLeft
        }

        // Use the target gesture's normal activation threshold. In particular, changing to
        // Seek must feel just as responsive as beginning with a horizontal Seek directly.
        val changedDirection =
            nextDelta > activationThreshold(nextGesture) &&
            (currentDelta == 0f || nextDelta / currentDelta >= DIRECTION_LOCK_RATIO)

        if (changedDirection) {
            // A vertical turn during an active horizontal seek is not the end of the touch.
            // Freeze the last seek delta and ignore the vertical leg instead of sending
            // Finalize (which would execute the seek as though ACTION_UP had occurred).
            if (state == State.ControlSeek && stateDirection == 0 && nextDirection == 1) {
                frozenSeekDx = lastPos.x - initialPos.x
                seekVerticalMovement = true
                directionAnchor.set(p)
                lastPos.set(p)
                return true
            }

            if (state.isControl())
                sendPropertyChange(PropertyChange.Finalize, 0f)
            activateGesture(nextGesture, nextDirection, p, rebaseOrigin = true)
            return true
        }

        // Keep following the current axis. Rebasing here prevents vertical up/down motion
        // and its small sideways drift from later looking like a horizontal direction change.
        val continuesCurrentDirection =
            currentDelta > 0f &&
            (nextDelta == 0f || currentDelta / nextDelta >= DIRECTION_LOCK_RATIO)
        if (continuesCurrentDirection)
            directionAnchor.set(p)

        return false
    }

    private fun processTap(p: PointF): Boolean {
        if (state == State.Up) {
            lastDownTime = SystemClock.uptimeMillis()
            // 3 is another arbitrary value here that seems good enough
            if (PointF(lastPos.x - p.x, lastPos.y - p.y).length() > trigger * 3)
                lastTapTime = 0 // last tap was too far away, invalidate
            return true
        }
        // discard if any movement gesture took place
        if (state != State.Down)
            return false

        val now = SystemClock.uptimeMillis()
        if (now - lastDownTime >= TAP_DURATION) {
            lastTapTime = 0 // finger was held too long, reset
            return false
        }
        if (now - lastTapTime < TAP_DURATION) {
            // [ Left 28% ] [    Center    ] [ Right 28% ]
            if (p.x <= width * 0.28f)
                tapGestureLeft?.let { sendPropertyChange(it, -1f); return true }
            else if (p.x >= width * 0.72f)
                tapGestureRight?.let { sendPropertyChange(it, 1f); return true }
            else
                tapGestureCenter?.let { sendPropertyChange(it, 0f); return true }
            lastTapTime = 0
        } else {
            lastTapTime = now
        }
        return false
    }

    private fun processMovement(p: PointF): Boolean {
        if (state == State.SystemGestureReplay)
            return true
        if (state == State.ControlSeek && seekVerticalMovement &&
            processVerticalMovementDuringSeek(p)
        ) return true
        if (state != State.Up && state != State.Down && processDirectionChange(p))
            return true
        if (state == State.Ignored)
            return true

        assertFloat(initialPos.x, initialPos.y)
        val dx = p.x - initialPos.x
        val dy = p.y - initialPos.y
        if (state == State.Down) {
            maxAbsDx = max(maxAbsDx, abs(dx))
            maxAbsDy = max(maxAbsDy, abs(dy))
        }

        // throttle events: only send updates when there's some movement compared to last update
        // 3 here is arbitrary.
        // For seeking we want finer updates so the step size becomes ~1s on slow drag.
        val throttle = if (state == State.ControlSeek) trigger / SEEK_THROTTLE_DIV else trigger / 3
        if (PointF(lastPos.x - p.x, lastPos.y - p.y).length() < throttle)
            return false
        lastPos.set(p)

        val dr = if (stateDirection == 0) (dx / width) else (-dy / height)

        when (state) {
            State.Up -> {}
            State.Ignored -> {}
            State.SystemGestureReplay -> {}
            State.Down -> {
                // We might enter one of the Control states if the user moves enough.
                // For seeking we want a shorter activation distance (Samsung-like), so the
                // first visible step can be ~1s instead of jumping multiple seconds.
                //
                // Direction is decided from the largest displacement seen during this touch,
                // not only from the latest point. This makes a fast vertical up/down swipe stay
                // vertical after returning near its starting Y coordinate.
                val vertGesture = if (initialPos.x > width / 2) gestureVertRight else gestureVertLeft

                // Horizontal activation: require the gesture to be clearly horizontal.
                val horizontalIntent =
                    maxAbsDx > activationThreshold(gestureHoriz) &&
                    (maxAbsDy == 0f || maxAbsDx / maxAbsDy >= DIRECTION_LOCK_RATIO)

                // Vertical activation: require the gesture to be clearly vertical.
                val verticalIntent =
                    maxAbsDy > activationThreshold(vertGesture) &&
                    (maxAbsDx == 0f || maxAbsDy / maxAbsDx >= DIRECTION_LOCK_RATIO)

                if (horizontalIntent) {
                    activateGesture(gestureHoriz, 0, p, rebaseOrigin = false)
                } else if (verticalIntent) {
                    activateGesture(vertGesture, 1, p, rebaseOrigin = false)
                }
            }
            State.ControlSeek ->
                sendPropertyChange(PropertyChange.Seek, CONTROL_SEEK_MAX * dr)
            State.ControlVolume ->
                sendPropertyChange(PropertyChange.Volume, CONTROL_VOLUME_MAX * dr)
            State.ControlBright ->
                sendPropertyChange(PropertyChange.Bright, CONTROL_BRIGHT_MAX * dr)
        }
        return state != State.Up && state != State.Down
    }

    private fun processMovement(e: MotionEvent): Boolean {
        var handled = false

        // Fast direction changes can be delivered as batched historical points. Process them
        // in order so a vertical excursion cannot disappear when the current point has already
        // returned close to its starting position.
        for (i in 0 until e.historySize) {
            if (processMovement(PointF(e.getHistoricalX(i), e.getHistoricalY(i))))
                handled = true
        }
        if (processMovement(PointF(e.x, e.y)))
            handled = true

        return handled
    }

    private fun sendPropertyChange(p: PropertyChange, diff: Float) {
        observer.onPropertyChange(p, diff)
    }

    fun syncSettings(prefs: SharedPreferences, resources: Resources) {
        val get: (String, Int) -> String = { key, defaultRes ->
            val v = prefs.getString(key, "")
            if (v.isNullOrEmpty()) resources.getString(defaultRes) else v
        }
        val map = mapOf(
            "bright" to State.ControlBright,
            "seek" to State.ControlSeek,
            "volume" to State.ControlVolume
        )
        val map2 = mapOf(
            "seek" to PropertyChange.SeekFixed,
            "playpause" to PropertyChange.PlayPause,
            "custom" to PropertyChange.Custom
        )

        gestureHoriz = map[get("gesture_horiz", R.string.pref_gesture_horiz_default)] ?: State.Down
        gestureVertLeft = map[get("gesture_vert_left", R.string.pref_gesture_vert_left_default)] ?: State.Down
        gestureVertRight = map[get("gesture_vert_right", R.string.pref_gesture_vert_right_default)] ?: State.Down
        tapGestureLeft = map2[get("gesture_tap_left", R.string.pref_gesture_tap_left_default)]
        tapGestureCenter = map2[get("gesture_tap_center", R.string.pref_gesture_tap_center_default)]
        tapGestureRight = map2[get("gesture_tap_right", R.string.pref_gesture_tap_right_default)]
    }

    fun onTouchEvent(e: MotionEvent): Boolean {
        if (width < 1 || height < 1) {
            Log.w(TAG, "TouchGestures: width or height not set!")
            return false
        }
        if (!checkFloat(e.x, e.y)) {
            Log.w(TAG, "TouchGestures: ignoring invalid point ${e.x} ${e.y}")
            return false
        }
        var gestureHandled = false
        val point = PointF(e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_UP -> {
                if (state == State.SystemGestureReplay) {
                    // The finger was lifted before a live event arrived, so the entire stream
                    // belonged to the canceled system-button attempt.
                    gestureHandled = true
                } else {
                    gestureHandled = processMovement(e) or processTap(point)
                    if (state.isControl())
                        sendPropertyChange(PropertyChange.Finalize, 0f)
                }
                state = State.Up
                seekVerticalMovement = false
                frozenSeekDx = 0f
                systemGestureReplayCatchUpTime = 0L
            }
            MotionEvent.ACTION_DOWN -> {
                // Samsung's navigation layer restores a canceled button swipe by injecting the
                // original ACTION_DOWN much later with its old eventTime. Consume only that
                // replayed phase. Fresh edge touches continue through the normal path below.
                if (isReplayedNavigationGestureDown(e, point)) {
                    state = State.SystemGestureReplay
                    systemGestureReplayCatchUpTime = SystemClock.uptimeMillis()
                    lastTapTime = 0L
                    return true
                }

                // deadzone on top/bottom
                if (e.y < height * DEADZONE / 100 || e.y > height * (100 - DEADZONE) / 100)
                    return false
                initialPos.set(point)
                processTap(point)
                lastPos.set(point)
                maxAbsDx = 0f
                maxAbsDy = 0f
                seekVerticalMovement = false
                frozenSeekDx = 0f
                state = State.Down
                // always return true on ACTION_DOWN to continue receiving events
                gestureHandled = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (state == State.SystemGestureReplay) {
                    // Replayed points were generated before the handler returned control. The
                    // first point generated after that hand-off becomes the new drag origin, so
                    // mpv resumes on the same finger without inheriting the system swipe distance.
                    if (e.eventTime >= systemGestureReplayCatchUpTime)
                        resumeAfterSystemGesture(point)
                    gestureHandled = true
                } else {
                    gestureHandled = processMovement(e)
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                cancel()
                gestureHandled = false
            }
        }
        return gestureHandled
    }
}
