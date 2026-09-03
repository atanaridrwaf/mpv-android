package `is`.xyz.mpv

import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pinch-to-zoom + pan for mpv output.
 *
 * Important quality detail:
 *  - Unzoomed view uses a display-sized mpv-rendered compact surface, so mpv,
 *    not Android's TextureView compositor, performs the huge downscale. This
 *    avoids moire / false-color artifacts on high-frequency scans at 720p.
 *  - After the first mpv frame is ready, the unzoomed view is prepared with the
 *    same media-aspect fit that will be used while zoomed. At normal size it
 *    uses only a display-sized compact buffer; when the user starts zooming it
 *    upgrades the same geometry to an original-detail buffer.
 *  - New-file and window-exit transitions are forced back to the plain mpv/base
 *    surface so Android never animates a transformed TextureView while entering
 *    or leaving the player.
 *  - Because the geometry does not switch at zoom start/end, Android never shows
 *    the one-frame shrink/stretch tear. Because the zoom buffer has no oversized
 *    black bars, it keeps full source detail in both matching and opposite
 *    phone/media orientations.
 *
 * We do not use mpv video-pan/video-zoom for finger movement.
 */
internal class VideoZoomGestures(
    private val target: View,
) {
    private val renderTarget = target as? BaseMPVView

    private var viewWidth = 0f
    private var viewHeight = 0f

    /** currently displayed aspect ratio, including video-aspect-override. 0 => unknown */
    private var videoAspect = 0.0
    private var videoPixelWidth = 0
    private var videoPixelHeight = 0
    private var panscan = 0.0

    private val viewConfiguration = ViewConfiguration.get(target.context)
    private val touchSlop = viewConfiguration.scaledTouchSlop.toFloat()
    private val panStartSlop = max(1f, min(2.5f, touchSlop * 0.22f))
    private val minimumFlingVelocity = viewConfiguration.scaledMinimumFlingVelocity.toFloat()
    private val maximumFlingVelocity = viewConfiguration.scaledMaximumFlingVelocity.toFloat()

    // Android's own spline-based fling implementation. Translation is applied
    // from its absolute scroll positions on each vsync, bounded to the visible
    // video content so the image cannot coast beyond its legal pan range.
    private val panScroller = OverScroller(target.context)
    private var flingFramePosted = false
    private val flingFrameCallback = Choreographer.FrameCallback {
        flingFramePosted = false
        if (panScroller.computeScrollOffset()) {
            tx = panScroller.currX.toDouble()
            ty = panScroller.currY.toDouble()
            clampTranslationToVideoContent()
            applyToView()

            if (!panScroller.isFinished)
                postFlingFrame()
        }
    }

    // A synthetic one-pointer stream is supplied to VelocityTracker for
    // one-finger dragging. Multi-touch is reserved exclusively for scaling and
    // never contributes pan velocity.
    private var panVelocityTracker: VelocityTracker? = null
    private var velocityGestureDownTimeMs = 0L

    // Linear scale factor (1.0 = normal). Translation is stored as Double so large
    // 20x offsets do not lose sub-pixel precision before being sent to the View.
    private var scale = 1f
    private var tx = 0.0
    private var ty = 0.0

    private var downX = 0f
    private var downY = 0f
    private var lastPointerX = 0f
    private var lastPointerY = 0f
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var downTime = 0L

    private var panFingerDown = false
    private var panActive = false
    private var panMovedDuringTouch = false
    private var canBeTap = false

    private var tapStartTx = 0.0
    private var tapStartTy = 0.0

    // The zoom anchor is captured when the second finger touches the screen and
    // remains fixed until every finger has been lifted. ScaleGestureDetector's
    // focus normally follows the moving midpoint, which unintentionally changes
    // the part of the image being zoomed during the same gesture.
    private var pinchTouchSessionActive = false
    private var lockedPinchFocusX = 0f
    private var lockedPinchFocusY = 0f

    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private val panFilterX = OneEuroFilter()
    private val panFilterY = OneEuroFilter()

    private var requestedRenderSurfaceMode = RenderSurfaceMode.BASE
    private var displayedRenderSurfaceMode = RenderSurfaceMode.BASE
    private var surfaceModeTransitionInFlight: RenderSurfaceMode? = null
    private var queuedRenderSurfaceUpdate = false

    private var previousSurfaceFrameUptimeMs = Long.MIN_VALUE
    private var lastSurfaceFrameUptimeMs = Long.MIN_VALUE
    private var zoomRenderSurfaceMode: RenderSurfaceMode? = null
    private var zoomHighQualityRequested = false

    private var lastZoomMotionUptimeMs = 0L
    private var smoothedZoomVelocity = Float.POSITIVE_INFINITY
    private var slowZoomMotionSinceMs = 0L
    private var zoomQualityMonitorPosted = false
    private val zoomQualityMonitor = object : Runnable {
        override fun run() {
            zoomQualityMonitorPosted = false
            if (!scaleDetector.isInProgress || zoomHighQualityRequested || !isZoomed())
                return

            val now = SystemClock.uptimeMillis()
            val motionAge = now - lastZoomMotionUptimeMs
            val quietEnough = motionAge >= ZOOM_QUIET_GAP_MS
            val slowEnough = smoothedZoomVelocity <= ZOOM_SLOW_VELOCITY_PER_SECOND

            if (quietEnough && motionAge >= ZOOM_QUIET_UPGRADE_DELAY_MS) {
                requestZoomHighQuality()
                return
            }

            if (slowEnough) {
                if (slowZoomMotionSinceMs == 0L)
                    slowZoomMotionSinceMs = now
                if (now - slowZoomMotionSinceMs >= ZOOM_SLOW_DWELL_MS) {
                    requestZoomHighQuality()
                    return
                }
            } else if (!quietEnough) {
                slowZoomMotionSinceMs = 0L
            }

            postZoomQualityMonitor()
        }
    }

    // At 1x we deliberately keep the view-sized BASE surface. The old compact media-aspect
    // normal surface caused an extra TextureView composition/resample step and is the source of
    // the visible pre-zoom softness confirmed by the ADB surface-size trace.

    // When a pinch returns close enough to normal size, finish it through the
    // same delayed reset path as double-tap. Calling reset() directly from
    // onScaleEnd still sees ScaleGestureDetector as in-progress on some devices,
    // which keeps the original-detail Android surface selected for that frame.
    private var pendingPinchDoubleTapReset = false

    // Coalesce view property updates to vsync. We do not animate here; we only avoid
    // writing View properties multiple times in one display frame.
    private val choreographer: Choreographer = Choreographer.getInstance()
    private var applyScheduled = false
    private val frameCallback = Choreographer.FrameCallback {
        applyScheduled = false
        clampTranslationToVideoContent()
        applyToView()
    }

    private val scaleDetector = ScaleGestureDetector(
        target.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                stopFling()
                lastTapTime = 0L
                pendingPinchDoubleTapReset = false
                panActive = false
                canBeTap = false

                // Normally this was already captured on ACTION_POINTER_DOWN. Keep
                // this fallback for unusual event streams that start the detector
                // without delivering that pointer transition to this view.
                if (!pinchTouchSessionActive) {
                    pinchTouchSessionActive = true
                    lockedPinchFocusX = detector.focusX
                    lockedPinchFocusY = detector.focusY
                }

                // Keep the view-sized BASE buffer while the pinch is moving; the quality monitor
                // upgrades to original detail once the zoom motion slows or settles.
                val now = SystemClock.uptimeMillis()
                if (!isZoomed()) {
                    zoomHighQualityRequested = false
                    zoomRenderSurfaceMode = null
                }
                lastZoomMotionUptimeMs = now
                smoothedZoomVelocity = Float.POSITIVE_INFINITY
                slowZoomMotionSinceMs = 0L
                updateRenderSurfaceForCurrentState(force = false)
                applyToView()
                postZoomQualityMonitor()

                resetPanFilters(detector.focusX, detector.focusY, now)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                refreshMetricsFromTarget()
                if (viewWidth <= 1f || viewHeight <= 1f)
                    return true

                val oldScale = scale
                val requested = oldScale * detector.scaleFactor
                val newScale = requested.coerceIn(MIN_SCALE, MAX_SCALE)

                if (newScale <= PINCH_DOUBLE_TAP_RESET_SCALE) {
                    scale = 1f
                    tx = 0.0
                    ty = 0.0
                    pendingPinchDoubleTapReset = true
                    resetPanFilters(detector.focusX, detector.focusY, SystemClock.uptimeMillis())
                    scheduleApply()
                    return true
                }

                pendingPinchDoubleTapReset = false
                if (newScale == oldScale)
                    return true

                // Keep the zoom focus fixed at the midpoint captured when the
                // two-finger touch session started. Moving both fingers does not
                // pan the image; multi-touch is reserved exclusively for zoom.
                // transform: screen = scale * content + translation
                val fx = lockedPinchFocusX.toDouble()
                val fy = lockedPinchFocusY.toDouble()
                val k = (newScale / oldScale).toDouble()
                tx = (k * tx) + ((1.0 - k) * fx)
                ty = (k * ty) + ((1.0 - k) * fy)
                scale = newScale

                val now = SystemClock.uptimeMillis()
                updateZoomMotionVelocity(oldScale, newScale, now)
                clampTranslationToVideoContent()
                resetPanFilters(detector.focusX, detector.focusY, now)
                scheduleApply()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (pendingPinchDoubleTapReset || scale <= PINCH_DOUBLE_TAP_RESET_SCALE) {
                    pendingPinchDoubleTapReset = true
                    resetLikeDoubleTapAfterPinch()
                } else {
                    stopZoomQualityMonitor()
                    resetPanFilters(detector.focusX, detector.focusY, SystemClock.uptimeMillis())
                    requestZoomHighQuality()
                }
            }
        }
    ).apply {
        // ScaleGestureDetector enables quick-scale by default: a double-tap followed by
        // dragging the second tap can zoom with one finger. That conflicts with the
        // player's double-tap/tap gestures, so zoom must be strictly two-pointer only.
        isQuickScaleEnabled = false
        isStylusScaleEnabled = false
    }

    fun setMetrics(width: Float, height: Float) {
        stopFling()
        viewWidth = width
        viewHeight = height
        refreshMetricsFromTarget()
        if (isZoomed() || scaleDetector.isInProgress) {
            clampTranslationToVideoContent()
            updateRenderSurfaceForCurrentState(force = true)
            scheduleApply()
        } else {
            updateRenderSurfaceForCurrentState(force = true)
            scheduleApply()
        }
    }

    fun setVideoAspect(aspect: Double?) {
        setVideoGeometry(
            aspect = aspect,
            pixelSize = videoPixelSizeOrNull(),
            panscanValue = panscan,
            prepareNormalSurface = false,
            immediate = false,
        )
    }

    fun setVideoPixelSize(size: Pair<Int, Int>?) {
        setVideoGeometry(
            aspect = videoAspect.takeIf { it > 0.001 },
            pixelSize = size,
            panscanValue = panscan,
            prepareNormalSurface = false,
            immediate = false,
        )
    }

    fun setPanscan(value: Double?) {
        setVideoGeometry(
            aspect = videoAspect.takeIf { it > 0.001 },
            pixelSize = videoPixelSizeOrNull(),
            panscanValue = value,
            prepareNormalSurface = false,
            immediate = false,
        )
    }

    fun setVideoGeometry(
        aspect: Double?,
        pixelSize: Pair<Int, Int>?,
        panscanValue: Double?,
        prepareNormalSurface: Boolean = false,
        immediate: Boolean = false,
    ) {
        stopFling()
        videoAspect = aspect ?: 0.0
        videoPixelWidth = pixelSize?.first ?: 0
        videoPixelHeight = pixelSize?.second ?: 0
        panscan = panscanValue ?: 0.0
        zoomRenderSurfaceMode = null

        // prepareNormalSurface is retained for call-site compatibility. Reliable media geometry
        // is still captured here, but normal playback intentionally remains on BASE.
        if (prepareNormalSurface && !isZoomed() && !scaleDetector.isInProgress)
            zoomHighQualityRequested = false

        if (isZoomed() || scaleDetector.isInProgress)
            clampTranslationToVideoContent()

        updateRenderSurfaceForCurrentState(force = true)
        if (immediate)
            applyToView()
        else
            scheduleApply()
    }

    fun applyPredictedAspectMenuGeometry(
        aspect: Double?,
        pixelSize: Pair<Int, Int>?,
        panscanValue: Double?,
    ) {
        setVideoGeometry(
            aspect = aspect,
            pixelSize = pixelSize,
            panscanValue = panscanValue,
            prepareNormalSurface = true,
            immediate = true,
        )
    }

    private fun videoPixelSizeOrNull(): Pair<Int, Int>? {
        if (videoPixelWidth <= 0 || videoPixelHeight <= 0)
            return null
        return videoPixelWidth to videoPixelHeight
    }

    fun isZoomed(): Boolean = scale > 1f + EPS

    /**
     * True while the image is still coasting after a one-finger pan release.
     * A tap that begins in this state is used only to stop the fling and must
     * not also be interpreted by the Activity as a controls-toggle tap.
     */
    fun isFlingInProgress(): Boolean = !panScroller.isFinished || flingFramePosted

    fun onSurfaceTextureFrameAvailable() {
        val now = SystemClock.uptimeMillis()
        previousSurfaceFrameUptimeMs = lastSurfaceFrameUptimeMs
        lastSurfaceFrameUptimeMs = now

        val completedMode = surfaceModeTransitionInFlight ?: return
        displayedRenderSurfaceMode = completedMode
        surfaceModeTransitionInFlight = null
        clampTranslationToVideoContent()
        applyToView()

        if (queuedRenderSurfaceUpdate) {
            queuedRenderSurfaceUpdate = false
            updateRenderSurfaceForCurrentState(force = true)
        }
    }

    fun shouldBlockOtherGestures(e: MotionEvent): Boolean {
        return isZoomed() || pendingPinchDoubleTapReset || scaleDetector.isInProgress || e.pointerCount > 1
    }

    fun reset() {
        resetTransformState()

        // At 1x, return to the full view-sized BASE buffer. mpv performs the source-to-display
        // downscale directly, so Android does not resample a compact intermediate texture.
        updateRenderSurfaceForCurrentState(force = true)
        applyToView()
    }

    fun resetForNewFile() {
        resetTransformState()
        videoAspect = 0.0
        videoPixelWidth = 0
        videoPixelHeight = 0
        panscan = 0.0
        previousSurfaceFrameUptimeMs = Long.MIN_VALUE
        lastSurfaceFrameUptimeMs = Long.MIN_VALUE
        zoomRenderSurfaceMode = null
        zoomHighQualityRequested = false
        commitHiddenBaseRenderSurfaceMode()
        requestBaseRenderSurfaceSize(force = true)
        applyToView()
    }

    fun prepareForVisibleMedia() {
        // Geometry is ready, but do not compact the 1x SurfaceTexture. Keeping BASE here removes
        // the extra resampling stage while the zoom-time quality policy remains unchanged.
        updateRenderSurfaceForCurrentState(force = true)
        applyToView()
    }

    fun prepareForWindowExit() {
        // Reset only the Android view transform. Resizing or hiding the TextureView here would
        // expose a black frame or a geometry jump while the activity close transition is visible.
        resetTransformState()
        applyToView()
    }

    private fun resetTransformState() {
        stopFling()
        recyclePanVelocityTracker()
        if (applyScheduled) {
            choreographer.removeFrameCallback(frameCallback)
            applyScheduled = false
        }

        scale = 1f
        tx = 0.0
        ty = 0.0
        panFingerDown = false
        panActive = false
        panMovedDuringTouch = false
        canBeTap = false
        lastTapTime = 0L
        pendingPinchDoubleTapReset = false
        stopZoomQualityMonitor()
        zoomRenderSurfaceMode = null
        zoomHighQualityRequested = false
        lastZoomMotionUptimeMs = 0L
        smoothedZoomVelocity = Float.POSITIVE_INFINITY
        slowZoomMotionSinceMs = 0L
        resetPanFilters(0f, 0f, SystemClock.uptimeMillis())
        target.alpha = 1f
    }

    private fun resetLikeDoubleTapAfterPinch() {
        target.post {
            if (scaleDetector.isInProgress) {
                resetLikeDoubleTapAfterPinch()
                return@post
            }

            if (!pendingPinchDoubleTapReset && scale > PINCH_DOUBLE_TAP_RESET_SCALE)
                return@post

            // This is intentionally the same reset action used by double-tap,
            // but deferred until the pinch detector has fully ended so surface
            // selection follows the smooth double-tap path.
            reset()
        }
    }

    /**
     * @return true if the event should be consumed.
     *         While zoomed: pinch/pan/double-tap are consumed.
     *         Single tap returns false so the Activity can toggle controls.
     */
    fun onTouchEvent(e: MotionEvent): Boolean {
        refreshMetricsFromTarget()

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopFling()
                // ACTION_DOWN means the previous touch session is fully over.
                endPinchTouchSession()
                panMovedDuringTouch = false
                beginPanVelocityTracking(e.x, e.y, e.eventTime)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                stopFling()

                // Entering multi-touch must completely end the current one-finger
                // fling candidate. Pinch motion never contributes velocity, and a
                // drag performed before the second finger arrived must not launch
                // a fling when the touch session eventually ends.
                recyclePanVelocityTracker()
                panMovedDuringTouch = false
                panFingerDown = false
                panActive = false
                canBeTap = false
                beginPinchTouchSession(e)
            }
        }

        // Always feed the scale detector first.
        scaleDetector.onTouchEvent(e)

        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL)
            endPinchTouchSession()

        if (e.actionMasked == MotionEvent.ACTION_CANCEL) {
            stopFling()
            lastTapTime = 0L
            panFingerDown = false
            panActive = false
            canBeTap = false
            resetPanFilters(lastPointerX, lastPointerY, SystemClock.uptimeMillis())
            recyclePanVelocityTracker()
            return isZoomed() || pendingPinchDoubleTapReset
        }

        // Pointer transitions during pinch:
        // If one finger lifts and another remains down, rebase pan input so there is no jump.
        if (e.actionMasked == MotionEvent.ACTION_POINTER_UP && isZoomed()) {
            lastTapTime = 0L
            panFingerDown = false
            panActive = false
            canBeTap = false
            val remainingPointerCount = e.pointerCount - 1
            if (remainingPointerCount == 1) {
                val upIdx = e.actionIndex
                val remainIdx = firstPointerIndexExcept(e, upIdx)
                if (remainIdx < 0)
                    return true
                val x = e.getX(remainIdx)
                val y = e.getY(remainIdx)
                downX = x
                downY = y
                lastPointerX = x
                lastPointerY = y
                lastPanX = x
                lastPanY = y
                downTime = SystemClock.uptimeMillis()
                resetPanFilters(x, y, downTime)

                // The remaining finger begins a brand-new one-finger drag segment
                // immediately. Its velocity is measured from this pointer-up
                // transition, so moving it can fling normally without first lifting
                // every finger, while all preceding pinch motion remains excluded.
                panMovedDuringTouch = false
                rebasePanVelocityTracking(x, y, e.eventTime)

                // Keep the remaining finger as an active one-finger pan.
                // Previously this stayed false, so the following MOVE events were
                // consumed while zoomed but ignored until every finger was lifted
                // and a fresh ACTION_DOWN was received.
                panFingerDown = true
            }
            return true
        }

        if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            return true
        }

        // Multi-touch is consumed here and handled only by ScaleGestureDetector.
        // It must never translate the image or retain any fling velocity. A new
        // one-finger velocity stream is created above only after one pointer lifts
        // and exactly one remains on the screen.
        if (e.pointerCount > 1 || scaleDetector.isInProgress) {
            lastTapTime = 0L
            panFingerDown = false
            panActive = false
            panMovedDuringTouch = false
            canBeTap = false
            recyclePanVelocityTracker()
            return true
        }

        if (!isZoomed()) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL)
                recyclePanVelocityTracker()
            return pendingPinchDoubleTapReset
        }

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastPointerX = e.x
                lastPointerY = e.y
                lastPanX = e.x
                lastPanY = e.y
                downTime = SystemClock.uptimeMillis()

                tapStartTx = tx
                tapStartTy = ty

                panFingerDown = true
                panActive = false
                canBeTap = true
                resetPanFilters(e.x, e.y, e.eventTime)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!panFingerDown)
                    return true

                // Android may batch several touch points into one MOVE. Processing them in order
                // prevents input bursts from becoming uneven pan steps.
                for (i in 0 until e.historySize) {
                    processPanSample(
                        e.getHistoricalX(0, i),
                        e.getHistoricalY(0, i),
                        e.getHistoricalEventTime(i),
                    )
                }
                processPanSample(e.x, e.y, e.eventTime)
                return true
            }

            MotionEvent.ACTION_UP -> {
                val now = SystemClock.uptimeMillis()
                val moveDist = hypot(e.x - downX, e.y - downY)
                val wasTap = canBeTap && moveDist < touchSlop && (now - downTime) < DOUBLE_TAP_TIMEOUT

                addPanVelocitySample(e.x, e.y, e.eventTime, MotionEvent.ACTION_UP)
                val releaseVelocity = releasePanVelocity()

                panFingerDown = false
                panActive = false
                canBeTap = false

                if (!wasTap) {
                    lastTapTime = 0L
                    resetPanFilters(lastPointerX, lastPointerY, now)
                    if (panMovedDuringTouch)
                        startFling(releaseVelocity.x, releaseVelocity.y)
                    recyclePanVelocityTracker()
                    return true
                }

                // Double-tap anywhere while zoomed => reset.
                val dt = now - lastTapTime
                val dist = hypot(e.x - lastTapX, e.y - lastTapY)
                if (lastTapTime != 0L && dt < DOUBLE_TAP_TIMEOUT && dist < touchSlop * 3f) {
                    reset()
                    lastTapTime = 0L
                    recyclePanVelocityTracker()
                    return true
                }

                // Single tap: undo any tiny pan admitted below touch slop and let Activity
                // handle tap-to-toggle controls.
                tx = tapStartTx
                ty = tapStartTy
                clampTranslationToVideoContent()
                applyToView()

                lastTapTime = now
                lastTapX = e.x
                lastTapY = e.y
                resetPanFilters(e.x, e.y, now)
                recyclePanVelocityTracker()
                return false
            }

        }

        return true
    }

    private fun beginPinchTouchSession(e: MotionEvent) {
        val focus = pointerCentroid(e) ?: return

        if (!pinchTouchSessionActive) {
            pinchTouchSessionActive = true
            lockedPinchFocusX = focus.x
            lockedPinchFocusY = focus.y
        }
    }

    private fun endPinchTouchSession() {
        pinchTouchSessionActive = false
        lockedPinchFocusX = 0f
        lockedPinchFocusY = 0f
    }

    private fun pointerCentroid(e: MotionEvent): PointerCentroid? {
        var sumX = 0f
        var sumY = 0f
        var count = 0
        for (i in 0 until e.pointerCount) {
            sumX += e.getX(i)
            sumY += e.getY(i)
            count++
        }

        if (count == 0)
            return null
        return PointerCentroid(sumX / count, sumY / count)
    }

    private fun firstPointerIndexExcept(e: MotionEvent, excludedPointerIndex: Int): Int {
        for (i in 0 until e.pointerCount) {
            if (i != excludedPointerIndex)
                return i
        }
        return -1
    }

    private fun processPanSample(x: Float, y: Float, timeMs: Long) {
        addPanVelocitySample(x, y, timeMs)
        lastPointerX = x
        lastPointerY = y

        val distFromDown = hypot(x - downX, y - downY)
        val gestureAge = SystemClock.uptimeMillis() - downTime

        // Keep double-tap reliable: a gesture remains a tap until normal Android tap slop
        // is crossed or the press is held long enough.
        if (canBeTap && (distFromDown >= touchSlop || gestureAge >= DOUBLE_TAP_TIMEOUT)) {
            canBeTap = false
            lastTapTime = 0L
        }

        if (!panActive) {
            if (distFromDown < panStartSlop)
                return

            panActive = true
            // Avoid the first slop-crossing jump.
            lastPanX = x
            lastPanY = y
            resetPanFilters(x, y, timeMs)
            return
        }

        val params = filterParamsForCurrentScale()
        val panX: Float
        val panY: Float
        if (params.enabled) {
            panX = panFilterX.filter(x, timeMs, params)
            panY = panFilterY.filter(y, timeMs, params)
        } else {
            panX = x
            panY = y
        }

        val dx = panX - lastPanX
        val dy = panY - lastPanY
        lastPanX = panX
        lastPanY = panY

        if (dx == 0f && dy == 0f)
            return

        tx += dx.toDouble()
        ty += dy.toDouble()
        panMovedDuringTouch = true
        clampTranslationToVideoContent()
        scheduleApply()
    }

    private fun beginPanVelocityTracking(x: Float, y: Float, timeMs: Long) {
        recyclePanVelocityTracker()
        panVelocityTracker = VelocityTracker.obtain()
        velocityGestureDownTimeMs = timeMs
        addPanVelocitySample(x, y, timeMs, MotionEvent.ACTION_DOWN)
    }

    private fun rebasePanVelocityTracking(x: Float, y: Float, timeMs: Long) {
        val tracker = panVelocityTracker ?: VelocityTracker.obtain().also {
            panVelocityTracker = it
        }
        tracker.clear()
        velocityGestureDownTimeMs = timeMs
        addPanVelocitySample(x, y, timeMs, MotionEvent.ACTION_DOWN)
    }

    private fun addPanVelocitySample(
        x: Float,
        y: Float,
        timeMs: Long,
        action: Int = MotionEvent.ACTION_MOVE,
    ) {
        val tracker = panVelocityTracker ?: return
        val safeEventTime = max(timeMs, velocityGestureDownTimeMs)
        val event = MotionEvent.obtain(
            velocityGestureDownTimeMs,
            safeEventTime,
            action,
            x,
            y,
            0,
        )
        tracker.addMovement(event)
        event.recycle()
    }

    private fun currentPanVelocity(): PanVelocity {
        val tracker = panVelocityTracker ?: return PanVelocity.ZERO
        tracker.computeCurrentVelocity(1000, maximumFlingVelocity)
        return PanVelocity(
            x = tracker.getXVelocity(VELOCITY_POINTER_ID),
            y = tracker.getYVelocity(VELOCITY_POINTER_ID),
        )
    }

    private fun releasePanVelocity(): PanVelocity = currentPanVelocity()

    private fun recyclePanVelocityTracker() {
        panVelocityTracker?.recycle()
        panVelocityTracker = null
        velocityGestureDownTimeMs = 0L
    }

    private fun startFling(rawVelocityX: Float, rawVelocityY: Float) {
        if (!isZoomed() || scaleDetector.isInProgress)
            return

        refreshMetricsFromTarget()
        clampTranslationToVideoContent()
        val bounds = translationBounds()

        val velocityX = rawVelocityX
            .takeIf { abs(it) >= minimumFlingVelocity }
            ?.coerceIn(-maximumFlingVelocity, maximumFlingVelocity)
            ?: 0f
        val velocityY = rawVelocityY
            .takeIf { abs(it) >= minimumFlingVelocity }
            ?.coerceIn(-maximumFlingVelocity, maximumFlingVelocity)
            ?: 0f

        if (velocityX == 0f && velocityY == 0f)
            return

        panScroller.fling(
            tx.roundToInt(),
            ty.roundToInt(),
            velocityX.roundToInt(),
            velocityY.roundToInt(),
            bounds.minX.roundToInt(),
            bounds.maxX.roundToInt(),
            bounds.minY.roundToInt(),
            bounds.maxY.roundToInt(),
        )
        postFlingFrame()
    }

    private fun postFlingFrame() {
        if (flingFramePosted)
            return
        flingFramePosted = true
        choreographer.postFrameCallback(flingFrameCallback)
    }

    private fun stopFling() {
        if (!panScroller.isFinished)
            panScroller.forceFinished(true)
        if (flingFramePosted) {
            choreographer.removeFrameCallback(flingFrameCallback)
            flingFramePosted = false
        }
    }

    private fun scheduleApply() {
        if (applyScheduled) return
        applyScheduled = true
        choreographer.postFrameCallback(frameCallback)
    }

    private fun resetPanFilters(x: Float, y: Float, timeMs: Long) {
        panFilterX.reset(x, timeMs)
        panFilterY.reset(y, timeMs)
        lastPanX = x
        lastPanY = y
    }

    private fun refreshMetricsFromTarget() {
        val w = target.width
        val h = target.height
        if (w > 1 && h > 1) {
            viewWidth = w.toFloat()
            viewHeight = h.toFloat()
        }
    }

    /** Compute the content/video rect within the view at base scale. */
    private fun contentRect(): ContentRect {
        val w = viewWidth
        val h = viewHeight
        if (w <= 1f || h <= 1f)
            return ContentRect(0f, 0f, w, h)

        if (isPanscanActive())
            return ContentRect(0f, 0f, w, h)

        val ar = if (videoAspect > 0.001) videoAspect.toFloat() else (w / h)
        val viewAr = w / h
        val cw: Float
        val ch: Float
        if (ar > viewAr) {
            cw = w
            ch = w / ar
        } else {
            ch = h
            cw = h * ar
        }
        val ox = (w - cw) * 0.5f
        val oy = (h - ch) * 0.5f
        return ContentRect(ox, oy, cw, ch)
    }

    private fun clampTranslationToVideoContent() {
        if (viewWidth <= 1f || viewHeight <= 1f)
            return

        if (scale <= 1f + EPS) {
            tx = 0.0
            ty = 0.0
            return
        }

        val bounds = translationBounds()
        tx = tx.coerceIn(bounds.minX, bounds.maxX)
        ty = ty.coerceIn(bounds.minY, bounds.maxY)
    }

    private fun translationBounds(): TranslationBounds {
        if (viewWidth <= 1f || viewHeight <= 1f || scale <= 1f + EPS)
            return TranslationBounds(0.0, 0.0, 0.0, 0.0)

        val c = contentRect()
        val contentWScaled = scale * c.w
        val contentHScaled = scale * c.h

        val minX: Double
        val maxX: Double
        if (contentWScaled <= viewWidth + EPS) {
            val centeredX = (((viewWidth - contentWScaled) * 0.5f) - scale * c.ox).toDouble()
            minX = centeredX
            maxX = centeredX
        } else {
            minX = (viewWidth - scale * (c.ox + c.w)).toDouble()
            maxX = (-scale * c.ox).toDouble()
        }

        val minY: Double
        val maxY: Double
        if (contentHScaled <= viewHeight + EPS) {
            val centeredY = (((viewHeight - contentHScaled) * 0.5f) - scale * c.oy).toDouble()
            minY = centeredY
            maxY = centeredY
        } else {
            minY = (viewHeight - scale * (c.oy + c.h)).toDouble()
            maxY = (-scale * c.oy).toDouble()
        }

        return TranslationBounds(minX, maxX, minY, maxY)
    }

    private fun applyToView() {
        val fit = renderSurfaceFitTransform()

        target.pivotX = 0f
        target.pivotY = 0f
        target.scaleX = scale * fit.scaleX
        target.scaleY = scale * fit.scaleY
        target.translationX = (tx + scale * fit.translationX).toFloat()
        target.translationY = (ty + scale * fit.translationY).toFloat()
    }

    private fun renderSurfaceFitTransform(): SurfaceFitTransform {
        if (!displayedRenderSurfaceMode.usesMediaAspectFit || viewWidth <= 1f || viewHeight <= 1f)
            return SurfaceFitTransform.IDENTITY

        val c = contentRect()
        if (c.w <= 1f || c.h <= 1f)
            return SurfaceFitTransform.IDENTITY

        return SurfaceFitTransform(
            scaleX = c.w / viewWidth,
            scaleY = c.h / viewHeight,
            translationX = c.ox.toDouble(),
            translationY = c.oy.toDouble(),
        )
    }

    private fun updateRenderSurfaceForCurrentState(force: Boolean) {
        val zooming = isZoomed() || scaleDetector.isInProgress
        val desiredMode = if (!zooming || !zoomHighQualityRequested) {
            RenderSurfaceMode.BASE
        } else {
            zoomRenderSurfaceMode ?: selectZoomRenderSurfaceMode().also {
                zoomRenderSurfaceMode = it
            }
        }

        val transition = surfaceModeTransitionInFlight
        if (transition != null) {
            if (force || desiredMode != requestedRenderSurfaceMode)
                queuedRenderSurfaceUpdate = true
            return
        }

        when (desiredMode) {
            RenderSurfaceMode.BASE -> requestBaseRenderSurfaceSize(force)
            RenderSurfaceMode.VIEW_ASPECT_ORIGINAL -> requestViewAspectOriginalRenderSurfaceSize(force)
            RenderSurfaceMode.MEDIA_ASPECT_ORIGINAL -> requestMediaAspectOriginalRenderSurfaceSize(force)
        }
    }

    private fun selectZoomRenderSurfaceMode(): RenderSurfaceMode {
        if (isPanscanActive())
            return RenderSurfaceMode.VIEW_ASPECT_ORIGINAL

        return if (shouldKeepViewAspectWhileZooming())
            RenderSurfaceMode.VIEW_ASPECT_ORIGINAL
        else
            RenderSurfaceMode.MEDIA_ASPECT_ORIGINAL
    }

    private fun shouldKeepViewAspectWhileZooming(): Boolean {
        val currentTrackIsStillImage = try {
            MPVLib.getPropertyString("current-tracks/video/image")
                ?.equals("yes", ignoreCase = true) == true
        } catch (_: Throwable) {
            false
        }

        if (!currentTrackIsStillImage)
            return true

        val previous = previousSurfaceFrameUptimeMs
        val latest = lastSurfaceFrameUptimeMs
        if (previous == Long.MIN_VALUE || latest == Long.MIN_VALUE)
            return false

        val frameInterval = latest - previous
        val frameAge = SystemClock.uptimeMillis() - latest
        return frameInterval in 1..CONTINUOUS_SURFACE_FRAME_MAX_INTERVAL_MS &&
            frameAge in 0..CONTINUOUS_SURFACE_FRAME_MAX_AGE_MS
    }

    private fun requestBaseRenderSurfaceSize(force: Boolean) {
        val player = renderTarget ?: return
        if (!force && requestedRenderSurfaceMode == RenderSurfaceMode.BASE)
            return

        player.resetRenderSurfaceSize()
        markRenderSurfaceModeRequested(RenderSurfaceMode.BASE)
    }

    private fun requestViewAspectOriginalRenderSurfaceSize(force: Boolean) {
        val player = renderTarget ?: return
        refreshMetricsFromTarget()

        if (!force && requestedRenderSurfaceMode == RenderSurfaceMode.VIEW_ASPECT_ORIGINAL)
            return

        if (viewWidth <= 1f || viewHeight <= 1f || videoPixelWidth <= 1 || videoPixelHeight <= 1) {
            requestBaseRenderSurfaceSize(force = true)
            return
        }

        val c = contentRect()
        if (c.w <= 1f || c.h <= 1f) {
            requestBaseRenderSurfaceSize(force = true)
            return
        }

        val bufferScale = limitedOriginalDetailBufferScale(
            baseWidth = viewWidth.toDouble(),
            baseHeight = viewHeight.toDouble(),
            content = c,
        )

        val bufferWidth = ceilToIntAtLeastOne(viewWidth.toDouble() * bufferScale)
        val bufferHeight = ceilToIntAtLeastOne(viewHeight.toDouble() * bufferScale)
        player.setRenderSurfaceSize(bufferWidth, bufferHeight)
        markRenderSurfaceModeRequested(RenderSurfaceMode.VIEW_ASPECT_ORIGINAL)
    }

    private fun requestMediaAspectOriginalRenderSurfaceSize(force: Boolean) {
        val player = renderTarget ?: return
        refreshMetricsFromTarget()

        if (!force && requestedRenderSurfaceMode == RenderSurfaceMode.MEDIA_ASPECT_ORIGINAL)
            return

        if (viewWidth <= 1f || viewHeight <= 1f || videoPixelWidth <= 1 || videoPixelHeight <= 1) {
            requestBaseRenderSurfaceSize(force = true)
            return
        }

        val c = contentRect()
        if (c.w <= 1f || c.h <= 1f) {
            requestBaseRenderSurfaceSize(force = true)
            return
        }

        val bufferScale = limitedOriginalDetailBufferScale(
            baseWidth = c.w.toDouble(),
            baseHeight = c.h.toDouble(),
            content = c,
        )

        val bufferWidth = ceilToIntAtLeastOne(c.w.toDouble() * bufferScale)
        val bufferHeight = ceilToIntAtLeastOne(c.h.toDouble() * bufferScale)
        player.setRenderSurfaceSize(bufferWidth, bufferHeight)
        markRenderSurfaceModeRequested(RenderSurfaceMode.MEDIA_ASPECT_ORIGINAL)
    }

    private fun markRenderSurfaceModeRequested(mode: RenderSurfaceMode) {
        requestedRenderSurfaceMode = mode
        if (mode.usesMediaAspectFit == displayedRenderSurfaceMode.usesMediaAspectFit) {
            displayedRenderSurfaceMode = mode
            surfaceModeTransitionInFlight = null
        } else {
            surfaceModeTransitionInFlight = mode
        }
    }

    private fun commitHiddenBaseRenderSurfaceMode() {
        requestedRenderSurfaceMode = RenderSurfaceMode.BASE
        displayedRenderSurfaceMode = RenderSurfaceMode.BASE
        surfaceModeTransitionInFlight = null
        queuedRenderSurfaceUpdate = false
    }

    private fun updateZoomMotionVelocity(oldScale: Float, newScale: Float, now: Long) {
        val previousTime = lastZoomMotionUptimeMs
        lastZoomMotionUptimeMs = now
        if (previousTime <= 0L || now <= previousTime) {
            smoothedZoomVelocity = Float.POSITIVE_INFINITY
            slowZoomMotionSinceMs = 0L
            postZoomQualityMonitor()
            return
        }

        val dtSeconds = ((now - previousTime).toFloat() / 1000f)
            .coerceIn(MIN_ZOOM_VELOCITY_DT_SECONDS, MAX_ZOOM_VELOCITY_DT_SECONDS)
        val relativeDelta = abs(newScale - oldScale) / oldScale.coerceAtLeast(1f)
        val instantaneousVelocity = relativeDelta / dtSeconds
        smoothedZoomVelocity = if (smoothedZoomVelocity.isFinite()) {
            ZOOM_VELOCITY_SMOOTHING * smoothedZoomVelocity +
                (1f - ZOOM_VELOCITY_SMOOTHING) * instantaneousVelocity
        } else {
            instantaneousVelocity
        }

        if (smoothedZoomVelocity > ZOOM_SLOW_VELOCITY_PER_SECOND)
            slowZoomMotionSinceMs = 0L
        postZoomQualityMonitor()
    }

    private fun requestZoomHighQuality() {
        if (zoomHighQualityRequested || !isZoomed())
            return

        zoomHighQualityRequested = true
        zoomRenderSurfaceMode = null
        stopZoomQualityMonitor()
        updateRenderSurfaceForCurrentState(force = false)
    }

    private fun postZoomQualityMonitor() {
        if (zoomQualityMonitorPosted || zoomHighQualityRequested || !scaleDetector.isInProgress)
            return
        zoomQualityMonitorPosted = true
        target.postDelayed(zoomQualityMonitor, ZOOM_QUALITY_MONITOR_INTERVAL_MS)
    }

    private fun stopZoomQualityMonitor() {
        if (zoomQualityMonitorPosted) {
            target.removeCallbacks(zoomQualityMonitor)
            zoomQualityMonitorPosted = false
        }
    }

    private fun usesOppositeOrientationMediaAspectRenderSurface(): Boolean {
        if (viewWidth <= 1f || viewHeight <= 1f || videoAspect <= 0.001)
            return false

        val mediaIsLandscape = videoAspect > MEDIA_ORIENTATION_THRESHOLD
        val mediaIsPortrait = videoAspect < (1.0 / MEDIA_ORIENTATION_THRESHOLD)
        if (!mediaIsLandscape && !mediaIsPortrait)
            return false

        val viewAspect = viewWidth / viewHeight
        val viewIsLandscape = viewAspect > VIEW_ORIENTATION_THRESHOLD
        val viewIsPortrait = viewAspect < (1f / VIEW_ORIENTATION_THRESHOLD)
        if (!viewIsLandscape && !viewIsPortrait)
            return false

        return (mediaIsLandscape && viewIsPortrait) || (mediaIsPortrait && viewIsLandscape)
    }

    private fun shouldAvoidViewAspectOriginalRenderSurface(): Boolean {
        if (viewWidth <= 1f || viewHeight <= 1f || videoPixelWidth <= 1 || videoPixelHeight <= 1)
            return false

        val c = contentRect()
        if (c.w <= 1f || c.h <= 1f)
            return false

        val bufferScale = originalDetailBufferScale(c)
        val viewAspectWidth = viewWidth.toDouble() * bufferScale
        val viewAspectHeight = viewHeight.toDouble() * bufferScale
        val mediaAspectWidth = c.w.toDouble() * bufferScale
        val mediaAspectHeight = c.h.toDouble() * bufferScale

        val viewAspectPixels = viewAspectWidth * viewAspectHeight
        val mediaAspectPixels = (mediaAspectWidth * mediaAspectHeight).coerceAtLeast(1.0)
        val wastedPixelRatio = viewAspectPixels / mediaAspectPixels
        val longestViewAspectEdge = max(viewAspectWidth, viewAspectHeight)

        return wastedPixelRatio >= MEDIA_ASPECT_FALLBACK_WASTE_RATIO ||
            longestViewAspectEdge >= MEDIA_ASPECT_FALLBACK_MAX_EDGE
    }

    private fun isPanscanActive(): Boolean = panscan > EPS.toDouble()

    private fun limitedOriginalDetailBufferScale(
        baseWidth: Double,
        baseHeight: Double,
        content: ContentRect,
    ): Double {
        val desired = originalDetailBufferScale(content)
        val maxEdge = max(baseWidth, baseHeight).coerceAtLeast(1.0)
        val maxByEdge = MAX_RENDER_SURFACE_EDGE / maxEdge
        val maxByPixels = sqrt(
            MAX_RENDER_SURFACE_PIXELS / (baseWidth * baseHeight).coerceAtLeast(1.0),
        )

        // Avoid requesting oversized SurfaceTexture buffers. Very wide overridden
        // ratios such as 2.35:1 on huge images can otherwise exceed the device
        // texture limit and leave the TextureView black even after resetting zoom.
        return desired
            .coerceAtMost(maxByEdge)
            .coerceAtMost(maxByPixels)
            .coerceAtLeast(1.0)
    }

    private fun originalDetailBufferScale(c: ContentRect): Double {
        val scaleX = videoPixelWidth.toDouble() / c.w.toDouble()
        val scaleY = videoPixelHeight.toDouble() / c.h.toDouble()
        return max(scaleX, scaleY).coerceAtLeast(1.0)
    }

    private fun ceilToIntAtLeastOne(value: Double): Int {
        return ceil(value)
            .coerceAtLeast(1.0)
            .coerceAtMost(Int.MAX_VALUE.toDouble())
            .toInt()
    }

    private fun filterParamsForCurrentScale(): FilterParams {
        if (scale < FILTER_START_SCALE)
            return FilterParams(enabled = false, minCutoff = 0f, beta = 0f, derivativeCutoff = 0f)

        val t = ((scale - FILTER_START_SCALE) / (MAX_SCALE - FILTER_START_SCALE)).coerceIn(0f, 1f)
        val smoothT = t * t * (3f - 2f * t)
        return FilterParams(
            enabled = true,
            minCutoff = lerp(FILTER_MIN_CUTOFF_AT_START, FILTER_MIN_CUTOFF_AT_MAX, smoothT),
            beta = lerp(FILTER_BETA_AT_START, FILTER_BETA_AT_MAX, smoothT),
            derivativeCutoff = FILTER_D_CUTOFF,
        )
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private data class ContentRect(val ox: Float, val oy: Float, val w: Float, val h: Float)
    private data class PointerCentroid(val x: Float, val y: Float)
    private data class PanVelocity(val x: Float, val y: Float) {
        companion object {
            val ZERO = PanVelocity(0f, 0f)
        }
    }
    private data class TranslationBounds(
        val minX: Double,
        val maxX: Double,
        val minY: Double,
        val maxY: Double,
    )
    private data class SurfaceFitTransform(
        val scaleX: Float,
        val scaleY: Float,
        val translationX: Double,
        val translationY: Double,
    ) {
        companion object {
            val IDENTITY = SurfaceFitTransform(1f, 1f, 0.0, 0.0)
        }
    }

    private enum class RenderSurfaceMode(val usesMediaAspectFit: Boolean) {
        BASE(false),
        VIEW_ASPECT_ORIGINAL(false),
        MEDIA_ASPECT_ORIGINAL(true),
    }

    private data class FilterParams(
        val enabled: Boolean,
        val minCutoff: Float,
        val beta: Float,
        val derivativeCutoff: Float,
    )

    private class LowPassFilter {
        private var initialized = false
        private var previous = 0f

        fun reset(value: Float) {
            initialized = true
            previous = value
        }

        fun filter(value: Float, alpha: Float): Float {
            if (!initialized) {
                reset(value)
                return value
            }
            val filtered = alpha * value + (1f - alpha) * previous
            previous = filtered
            return filtered
        }
    }

    private class OneEuroFilter {
        private val valueFilter = LowPassFilter()
        private val derivativeFilter = LowPassFilter()
        private var initialized = false
        private var previousRaw = 0f
        private var previousTimeMs = 0L

        fun reset(value: Float, timeMs: Long) {
            initialized = true
            previousRaw = value
            previousTimeMs = timeMs
            valueFilter.reset(value)
            derivativeFilter.reset(0f)
        }

        fun filter(value: Float, timeMs: Long, params: FilterParams): Float {
            if (!initialized) {
                reset(value, timeMs)
                return value
            }

            val dt = if (previousTimeMs > 0L && timeMs > previousTimeMs)
                ((timeMs - previousTimeMs).toFloat() / 1000f)
            else
                DEFAULT_FRAME_DT

            val safeDt = dt.coerceIn(MIN_FILTER_DT, MAX_FILTER_DT)
            val derivative = (value - previousRaw) / safeDt
            val filteredDerivative = derivativeFilter.filter(
                derivative,
                alpha(params.derivativeCutoff, safeDt),
            )
            val cutoff = params.minCutoff + params.beta * abs(filteredDerivative)
            val filtered = valueFilter.filter(value, alpha(cutoff, safeDt))

            previousRaw = value
            previousTimeMs = timeMs
            return filtered
        }

        private fun alpha(cutoff: Float, dt: Float): Float {
            val tau = 1.0f / (2.0f * PI.toFloat() * cutoff.coerceAtLeast(0.001f))
            return 1.0f / (1.0f + tau / dt)
        }
    }

    companion object {
        private const val EPS = 0.001f
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 20f
        private const val PINCH_DOUBLE_TAP_RESET_SCALE = 1.001f
        private const val DOUBLE_TAP_TIMEOUT = 300L
        private const val VELOCITY_POINTER_ID = 0
        private const val MEDIA_ORIENTATION_THRESHOLD = 1.08
        private const val VIEW_ORIENTATION_THRESHOLD = 1.08f
        private const val MEDIA_ASPECT_FALLBACK_WASTE_RATIO = 2.0
        private const val MEDIA_ASPECT_FALLBACK_MAX_EDGE = 8192.0
        private const val CONTINUOUS_SURFACE_FRAME_MAX_INTERVAL_MS = 250L
        private const val CONTINUOUS_SURFACE_FRAME_MAX_AGE_MS = 250L

        private const val ZOOM_QUALITY_MONITOR_INTERVAL_MS = 32L
        private const val ZOOM_QUIET_GAP_MS = 48L
        private const val ZOOM_QUIET_UPGRADE_DELAY_MS = 135L
        private const val ZOOM_SLOW_DWELL_MS = 145L
        private const val ZOOM_SLOW_VELOCITY_PER_SECOND = 0.32f
        private const val ZOOM_VELOCITY_SMOOTHING = 0.58f
        private const val MIN_ZOOM_VELOCITY_DT_SECONDS = 1f / 240f
        private const val MAX_ZOOM_VELOCITY_DT_SECONDS = 1f / 8f

        private const val MAX_RENDER_SURFACE_EDGE = 8192.0
        private const val MAX_RENDER_SURFACE_PIXELS = MAX_RENDER_SURFACE_EDGE * MAX_RENDER_SURFACE_EDGE

        private const val DEFAULT_FRAME_DT = 1f / 60f
        private const val MIN_FILTER_DT = 1f / 240f
        private const val MAX_FILTER_DT = 1f / 30f

        // Filtering is deliberately disabled at normal zoom. It only appears when
        // finger sensor noise becomes visible because the image is deeply magnified.
        private const val FILTER_START_SCALE = 10f
        private const val FILTER_MIN_CUTOFF_AT_START = 12f
        private const val FILTER_MIN_CUTOFF_AT_MAX = 6f
        private const val FILTER_BETA_AT_START = 0.020f
        private const val FILTER_BETA_AT_MAX = 0.050f
        private const val FILTER_D_CUTOFF = 1.0f
    }
}
