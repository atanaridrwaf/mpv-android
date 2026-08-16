package `is`.xyz.mpv

import `is`.xyz.mpv.databinding.PlayerBinding
import `is`.xyz.mpv.MPVLib.MpvEvent
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import androidx.appcompat.app.AlertDialog
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.util.Log
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.preference.PreferenceManager.getDefaultSharedPreferences
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Rational
import androidx.core.content.ContextCompat
import android.view.*
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Button
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.addCallback
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.annotation.RequiresApi
import java.security.MessageDigest
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import java.io.File
import java.lang.IllegalArgumentException
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

typealias ActivityResultCallback = (Int, Intent?) -> Unit
typealias StateRestoreCallback = () -> Unit

class MPVActivity : AppCompatActivity(), MPVLib.EventObserver, TouchGesturesObserver {
    // for calls to eventUi() and eventPropertyUi()
    private val eventUiHandler = Handler(Looper.getMainLooper())
    // for use with fadeRunnable1..3
    private val fadeHandler = Handler(Looper.getMainLooper())
    // for use with stopServiceRunnable
    private val stopServiceHandler = Handler(Looper.getMainLooper())
    private val orientationHandler = Handler(Looper.getMainLooper())
    // Delayed single-tap toggling (we wait a bit so a faster double-tap can be recognized
    // without flashing the control UI).
    private val tapToggleHandler = Handler(Looper.getMainLooper())
    private var pendingTapToggleRunnable: Runnable? = null

    // Restores immersive mode after temporary system UI interruptions such as Google Assistant
    // or the soft keyboard. A separate handler keeps these retries independent from player/event
    // callbacks, which may be cleared while the activity is paused.
    private val immersiveHandler = Handler(Looper.getMainLooper())
    private var playerWindowLostFocus = false
    private var manualSystemBarsRevealPending = false
    private val clearManualSystemBarsRevealRunnable = Runnable {
        manualSystemBarsRevealPending = false
    }
    private val restorePlayerImmersiveRunnable = Runnable {
        applyPlayerImmersiveModeIfPossible()
    }

    // We intentionally do *not* try to predict a double-tap here. Instead, we only cancel the
    // pending single-tap toggle if TouchGestures actually confirms and handles a double-tap
    // (PlayPause / SeekFixed / Custom). This avoids a "dead zone" where two quick taps that do
    // not qualify as a double-tap would otherwise cancel the single-tap toggle and do nothing.

    /**
     * DO NOT USE THIS
     */
    private var activityIsStopped = false

    private var activityIsForeground = true
    private var didResumeBackgroundPlayback = false
    private var userIsOperatingSeekbar = false


    // Scrub seeking (freeze frame while moving; seek only on idle/release).
    // Exact seeks are expensive on long-GOP video, so this controller deliberately keeps only
    // the newest target authoritative. Older native seeks may finish, but their callbacks can no
    // longer resume playback or complete a newer request.
    private val scrubSeekHandler = Handler(Looper.getMainLooper())

    private class ScrubSeekRequest(
        val userdata: Long,
        val generation: Long,
        val targetSec: Double,
        val exact: Boolean,
        val commandValueSec: Double,
        val directionalKeyframeDirection: Int,
        val issuedAtMs: Long,
        val frameFloor: Long
    ) {
        var superseded = false
        var commandReplyReceived = false
        var commandError = 0
        var seekEventSeen = false
        var playbackRestartSeen = false
        var targetPositionSeen = !exact
        var targetPositionNear = !exact
        var frameSeen = false
        var frameGraceScheduled = false
    }

    private var scrubSeekInFlight = false
    private var activeScrubSeek: ScrubSeekRequest? = null
    private var scrubSeekGeneration = 0L
    private var scrubAsyncCounter = 1L
    private var mpvSeeking = false
    private var latestPlaybackTimeSec = Double.NaN

    // The playback state requested by the user while scrub seeking temporarily pauses mpv.
    // Keeping it separate from mpv's real "pause" property makes play/pause controls symmetric:
    // the user can change their mind while a slow exact seek is still completing.
    private var scrubPlaybackPaused: Boolean? = null

    private val scrubFrameGraceRunnable = Runnable { finishScrubSeekAfterFrameGrace() }
    private val scrubHardTimeoutRunnable = Runnable { finishScrubSeekAfterHardTimeout() }

    private var gestureScrubActive = false
    // These retain the sub-second fraction of the drag target (e.g. 2.234, not just 2) so an
    // exact scrub seek lands precisely instead of snapping to the nearest whole second.
    private var pendingGestureSeekSec: Double? = null
    private var lastIssuedGestureSeekSec: Double? = null

    private var seekbarScrubActive = false
    private var initialSeekbarPosSec = 0
    private var pendingSeekbarSeekPos: Double? = null
    private var lastIssuedSeekbarSeekPos: Double? = null

    // A target is considered stationary only after its numeric seek value has remained
    // unchanged for a short interval. Repeated touch samples at the same value do not restart
    // the interval; only a real increase/decrease replaces the pending target.
    private var gestureStableSeekRunnable: Runnable? = null
    private var seekbarStableSeekRunnable: Runnable? = null

    private var toast: Toast? = null
    private val toastHandler = Handler(Looper.getMainLooper())
    private var toastCancelRunnable: Runnable? = null

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequestCompat? = null
    private var audioFocusRestore: () -> Unit = {}

    // Orientation is resolved before the player window is attached. The probe runs off the UI
    // thread, while android:windowDisablePreview keeps the calling screen visible instead of
    // showing a temporary black starting window.
    private val mediaOrientationExecutor = Executors.newFixedThreadPool(2)
    private var mediaSwitchProbeGeneration = 0
    private var entryConfigOrientation: Int = Configuration.ORIENTATION_UNDEFINED
    private var finishTransitionStarted = false
    private var orientationOwnedByPlayer = false
    private var mediaGeometryReadyForOrientation = false
    private val orientationProbesInFlight = mutableSetOf<String>()
    private val orientationProbeCache = object : LinkedHashMap<String, MediaOrientationResolver.Orientation>(
        ORIENTATION_CACHE_SIZE,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, MediaOrientationResolver.Orientation>?,
        ): Boolean = size > ORIENTATION_CACHE_SIZE
    }
    private var uiInitialized: Boolean = false

    @Volatile
    private var playerSurfaceFrameSerial = 0L

    // Normal mpv resume can release rendering and audio at the same instant. On this
    // project's TextureView path that still leaves Android free to consume a short
    // burst of frames before the first resumed frame is visibly composed. Prime
    // exactly one video frame while hardware audio remains paused, wait until the
    // TextureView confirms that frame, then release continuous playback.
    private val resumeFrameGateHandler = Handler(Looper.getMainLooper())
    private var resumeFrameGatePending = false
    private var resumeFrameGateGeneration = 0L
    private var resumeFrameGateFrameFloor = 0L
    private var resumeFrameGateStartedAtMs = 0L
    private var resumeFrameGateReleasePosted = false
    private val resumeFrameGateTimeoutRunnable = Runnable {
        if (!resumeFrameGatePending)
            return@Runnable
        val elapsed = SystemClock.uptimeMillis() - resumeFrameGateStartedAtMs
        Log.w(TAG, "resume gate: no TextureView frame after ${elapsed}ms; resuming directly")
        finishResumeFrameGate("timeout")
    }

    private var suppressAspectMenuGeometrySyncUntilMs = 0L

    private val psc = Utils.PlaybackStateCache()
    private var mediaSession: MediaSessionCompat? = null

    private lateinit var binding: PlayerBinding
    private lateinit var gestures: TouchGestures
    private lateinit var zoomGestures: VideoZoomGestures

    // convenience alias
    private val player get() = binding.player

    private val seekBarChangeListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (!fromUser) return

            // SeekBar progress is the authoritative whole-second target. The same value is
            // displayed to the user and sent to mpv as an absolute exact seek.
            val targetSec = progress.toDouble()
            val previousTarget = pendingSeekbarSeekPos
            val targetChanged = previousTarget == null || !sameSeekTarget(previousTarget, targetSec)
            pendingSeekbarSeekPos = targetSec
            if (targetChanged) {
                supersedeActiveScrubSeekIfTargetChanged(targetSec, exact = true)
                scheduleSeekbarStableTargetSeek()
            }

            val posText = Utils.prettyTime(targetSec.toInt())
            val diffText = Utils.prettyTime(targetSec.toInt() - initialSeekbarPosSec, true)
            if (binding.gestureTextView.visibility != View.VISIBLE)
                refreshPlayerOverlay()
            fadeHandler.removeCallbacks(fadeRunnable3)
            binding.gestureTextView.visibility = View.VISIBLE
            binding.gestureTextView.text =
                getString(R.string.ui_seek_distance, posText, diffText)

            // Repeated touch samples at the same numeric target do not reset stability. Only an
            // actual increase or decrease starts a new stability interval.
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {
            refreshPlayerOverlay()
            userIsOperatingSeekbar = true
            seekbarScrubActive = true
            invalidateSeekbarStableTargetCheck()
            initialSeekbarPosSec = seekBar.progress
            pendingSeekbarSeekPos = null
            lastIssuedSeekbarSeekPos = null

            beginScrubPlaybackHold()

            fadeHandler.removeCallbacks(fadeRunnable3)
            binding.gestureTextView.visibility = View.VISIBLE
            binding.gestureTextView.text = ""
        }

        override fun onStopTrackingTouch(seekBar: SeekBar) {
            userIsOperatingSeekbar = false
            seekbarScrubActive = false

            invalidateSeekbarStableTargetCheck()

            val target = pendingSeekbarSeekPos

            if (target != null && !seekbarTargetAlreadyResolved(target)) {
                if (sendScrubSeek(target, exact = true))
                    lastIssuedSeekbarSeekPos = target
            }

            finishScrubPlaybackHoldIfReady()

            binding.gestureTextView.visibility = View.GONE
            showControls() // re-trigger display timeout
        }
    }

    private var becomingNoisyReceiverRegistered = false
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS, "noisy")
            }
        }
    }

    // Fade out controls
    private val fadeRunnable = object : Runnable {
        var hasStarted = false
        private val listener = object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) { hasStarted = true }

            override fun onAnimationCancel(animation: Animator) { hasStarted = false }

            override fun onAnimationEnd(animation: Animator) {
                if (hasStarted)
                    hideControls()
                hasStarted = false
            }
        }

        override fun run() {
            binding.topControls.animate().alpha(0f).setDuration(CONTROLS_FADE_OUT_DURATION)
            binding.controls.animate().alpha(0f).setDuration(CONTROLS_FADE_OUT_DURATION).setListener(listener)
        }
    }

    // Fade out unlock button
    private val fadeRunnable2 = object : Runnable {
        private val listener = object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                binding.unlockBtn.visibility = View.GONE
            }
        }

        override fun run() {
            binding.unlockBtn.animate().alpha(0f).setDuration(CONTROLS_FADE_OUT_DURATION).setListener(listener)
        }
    }

    // Fade out gesture text
    private val fadeRunnable3 = object : Runnable {
        // okay this doesn't actually fade...
        override fun run() {
            binding.gestureTextView.visibility = View.GONE
        }
    }

    private val stopServiceRunnable = Runnable {
        val intent = Intent(this, BackgroundPlaybackService::class.java)
        applicationContext.stopService(intent)
    }

    /* Settings */
    private var statsFPS = false
    private var statsLuaMode = 0 // ==0 disabled, >0 page number

    private var backgroundPlayMode = ""
    private var noUIPauseMode = ""

    private var shouldSavePosition = false
    private var currentWatchLaterPath: String? = null
    private var completedWatchLaterPath: String? = null

    private var autoRotationMode = ""

    private var controlsAtBottom = true
    private var showMediaTitle = false
    private var useTimeRemaining = false

    private var rememberBrightness = false
    private var lastScreenBrightness = -1

    private var ignoreAudioFocus = false
    private var playlistExitWarning = true
    private var newIntentReplace = false

    private var smoothSeekGesture = false
    /* * */

    @SuppressLint("ClickableViewAccessibility")
    private fun initListeners() {
        with (binding) {
            prevBtn.setOnClickListener { playlistPrev() }
            nextBtn.setOnClickListener { playlistNext() }
            cycleAudioBtn.setOnClickListener { cycleAudio() }
            cycleSubsBtn.setOnClickListener { cycleSub() }
            playBtn.setOnClickListener { togglePlaybackPauseFromUi() }
            cycleDecoderBtn.setOnClickListener { player.cycleHwdec() }
            cycleSpeedBtn.setOnClickListener { cycleSpeed() }
            topLockBtn.setOnClickListener { lockUI() }
            topPiPBtn.setOnClickListener { goIntoPiP() }
            topMenuBtn.setOnClickListener { openTopMenu() }
            unlockBtn.setOnClickListener { unlockUI() }
            playbackDurationTxt.setOnClickListener {
                useTimeRemaining = !useTimeRemaining
                updatePlaybackPos(psc.positionSec)
                updatePlaybackDuration(psc.durationSec)
            }

            cycleAudioBtn.setOnLongClickListener { pickAudio(); true }
            cycleSpeedBtn.setOnLongClickListener { pickSpeed(); true }
            cycleSubsBtn.setOnLongClickListener { pickSub(); true }
            prevBtn.setOnLongClickListener { openPlaylistMenu(pauseForDialog()); true }
            nextBtn.setOnLongClickListener { openPlaylistMenu(pauseForDialog()); true }
            cycleDecoderBtn.setOnLongClickListener { pickDecoder(); true }

            playbackSeekbar.setOnSeekBarChangeListener(seekBarChangeListener)
        }

        // NOTE: touch events must come from an untransformed overlay view (gestureLayer).
        // The player view is transformed for zoom/pan, so attaching gestures directly to it
        // would inverse-transform MotionEvents and create feedback/jitter.
        binding.gestureLayer.setOnTouchListener { _, e ->
            if (lockedUI)
                return@setOnTouchListener false

            if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN)
                gestures.cancel()

            val blockDefault = zoomGestures.shouldBlockOtherGestures(e)
            val handledByZoom = zoomGestures.onTouchEvent(e)

            when {
                blockDefault -> handledByZoom
                else -> gestures.onTouchEvent(e)
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.outside) { v, windowInsets ->
            // guidance: https://medium.com/androiddevelopers/gesture-navigation-handling-visual-overlaps-4aed565c134c
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updateLayoutParams<MarginLayoutParams> {
                // avoid system bars and cutout
                leftMargin = insets.left
                topMargin = insets.top
                bottomMargin = insets.bottom
                rightMargin = insets.right
            }
            WindowInsetsCompat.CONSUMED
        }

        onBackPressedDispatcher.addCallback(this) {
            onBackPressedImpl()
        }

        addOnPictureInPictureModeChangedListener { info ->
            onPiPModeChangedImpl(info.isInPictureInPictureMode)
        }
    }

    private var playbackHasStarted = false
    private var onloadCommands = mutableListOf<Array<String>>()
    private var pendingSubtitleRestorePath: String? = null
    private val externalSubtitleLoadsInFlight = mutableSetOf<String>()

    // Activity lifetime

    override fun onCreate(icicle: Bundle?) {
        super.onCreate(icicle)

        // Capture the caller-facing orientation before this activity requests a media orientation.
        // The value is used only during the close transition; it never controls playback itself.
        entryConfigOrientation = icicle?.getInt(STATE_ENTRY_CONFIG_ORIENTATION)
            ?.takeIf { it != Configuration.ORIENTATION_UNDEFINED }
            ?: resources.configuration.orientation

        if (intent.action == Intent.ACTION_VIEW)
            parseIntentExtras(intent.extras)

        val filepath = parsePathFromIntent(intent)
        if (filepath == null) {
            Log.e(TAG, "No file given, exiting")
            showToast(getString(R.string.error_no_file))
            finishWithResult(RESULT_CANCELED)
            return
        }

        // Complete non-visual startup work while the caller is still unchanged. The potentially
        // expensive metadata read also happens before any orientation request, so there is no gap
        // in which the old interface rotates while the player is still being prepared.
        Utils.copyAssets(this)
        BackgroundPlaybackService.createNotificationChannel(this)

        binding = PlayerBinding.inflate(layoutInflater)
        gestures = TouchGestures(this)
        zoomGestures = VideoZoomGestures(binding.player)
        binding.player.onSurfaceTextureFrameAvailable = {
            playerSurfaceFrameSerial += 1L
            val serial = playerSurfaceFrameSerial
            onScrubSurfaceFrameAvailable(serial)
            zoomGestures.onSurfaceTextureFrameAvailable()
            onResumeGateSurfaceFrameAvailable(serial)
        }

        readAutoRotationModeForLaunch()
        val launchOrientation = resolveLaunchRequestedOrientation(filepath)
        setupUiAndStart(filepath, launchOrientation)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_ENTRY_CONFIG_ORIENTATION, entryConfigOrientation)
        super.onSaveInstanceState(outState)
    }

    private fun setupUiAndStart(filepath: String, launchOrientation: Int?) {
        if (uiInitialized)
            return

        // This request and the attachment of the real player are consecutive operations in the
        // same main-thread turn. Android 9 therefore receives one window transition rather than a
        // visible pre-rotation followed by a separate player launch.
        launchOrientation?.let(::requestOrientationIfNeeded)
        setContentView(binding.root)
        uiInitialized = true
        refreshPlayerOverlay()

        // Init controls to be hidden and view fullscreen.
        hideControls()

        // Initialize listeners for the player view.
        initListeners()
        installGestureMetricsUpdater()

        // Read full settings and update UI.
        readSettings()
        onConfigurationChanged(resources.configuration)

        // Edge-to-edge / immersive behavior.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        installImeDismissImmersiveRestore(window, binding.root)

        // Hide PiP / lock buttons on devices that don't support them.
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))
            binding.topPiPBtn.visibility = View.GONE
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN))
            binding.topLockBtn.visibility = View.GONE

        if (showMediaTitle)
            binding.controlsTitleGroup.visibility = View.VISIBLE

        updateOrientation(true)
        startPlayback(filepath)
    }

    private var playbackInitialized: Boolean = false

    private fun startPlayback(filepath: String) {
        if (playbackInitialized)
            return
        playbackInitialized = true

        player.addObserver(this)
        player.initialize(filesDir.path, cacheDir.path)
        player.playFile(filepath, persistedSubtitleLoadOptions(filepath))

        mediaSession = initMediaSession()
        updateMediaSession()
        with (BackgroundPlaybackService) {
            mediaToken = mediaSession?.sessionToken
            thumbnailChanged = {
                updateMediaSession()
            }
        }

        updateScreenBrightness()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val audioSessionId = audioManager!!.generateAudioSessionId()
        if (audioSessionId != AudioManager.ERROR)
            player.setAudioSessionId(audioSessionId)
        else
            Log.w(TAG, "AudioManager.generateAudioSessionId() returned error")

        volumeControlStream = STREAM_TYPE
    }

    private fun hasDisplayableVideoGeometry(): Boolean {
        val aspect = try { player.getEffectiveVideoAspect() ?: 0.0 } catch (_: Throwable) { 0.0 }
        val size = try { player.getVideoPixelSize() } catch (_: Throwable) { null }
        return aspect > 0.001 && size != null
    }

    private fun isAspectMenuGeometrySyncSuppressed(): Boolean {
        return SystemClock.uptimeMillis() < suppressAspectMenuGeometrySyncUntilMs
    }

    private fun syncZoomVideoGeometry(
        prepareNormalSurface: Boolean = false,
        immediate: Boolean = false,
    ) {
        if (!::zoomGestures.isInitialized || isAspectMenuGeometrySyncSuppressed())
            return

        val aspect = try { player.getEffectiveVideoAspect() } catch (_: Throwable) { null }
        val size = try { player.getVideoPixelSize() } catch (_: Throwable) { null }
        val pan = try { player.getPanscan() } catch (_: Throwable) { 0.0 }

        try {
            zoomGestures.setVideoGeometry(
                aspect = aspect,
                pixelSize = size,
                panscanValue = pan,
                prepareNormalSurface = prepareNormalSurface,
                immediate = immediate,
            )
        } catch (_: Throwable) {
            // A transient mpv geometry read must not affect playback.
        }
    }

    private fun prepareZoomSurfaceWhenReady() {
        if (!::zoomGestures.isInitialized || !hasDisplayableVideoGeometry())
            return

        syncZoomVideoGeometry(prepareNormalSurface = true, immediate = true)
        try {
            zoomGestures.prepareForVisibleMedia()
        } catch (_: Throwable) {
            // Zoom is optional; playback must continue.
        }
    }

    private fun resetZoomForNewFile() {
        if (!::zoomGestures.isInitialized)
            return
        try {
            zoomGestures.resetForNewFile()
        } catch (_: Throwable) {
            // Zoom is optional; playback must continue.
        }
    }

    private fun prepareZoomSurfaceForWindowExit() {
        if (!::zoomGestures.isInitialized || !::binding.isInitialized)
            return

        try {
            zoomGestures.prepareForWindowExit()
        } catch (_: Throwable) {
            // Finish must continue even if a vendor TextureView rejects a final transform update.
        }
    }

    private fun finishWithResult(code: Int, includeTimePos: Boolean = false) {
        // mpv may emit SHUTDOWN from its event thread. All window/orientation work must be queued
        // onto Android's main thread so the close request is committed as one UI transaction.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            orientationHandler.post { finishWithResult(code, includeTimePos) }
            return
        }

        // Refer to http://mpv-android.github.io/mpv-android/intent.html
        // FIXME: should track end-file events to accurately report OK vs CANCELED
        if (isFinishing || finishTransitionStarted)
            return
        finishTransitionStarted = true

        val result = Intent(RESULT_INTENT)
        result.data = if (intent.data?.scheme == "file") null else intent.data
        if (includeTimePos && playbackInitialized) {
            result.putExtra("position", psc.position.toInt())
            result.putExtra("duration", psc.duration.toInt())
        }
        setResult(code, result)

        // Return a zoomed TextureView to its normal transform without hiding or resizing it.
        prepareZoomSurfaceForWindowExit()

        // SCREEN_ORIENTATION_BEHIND is Android's native way to adopt the activity underneath while
        // this window is still participating in the close transition. A task-root player instead
        // uses its captured entry orientation only when it had imposed a media orientation lock.
        requestExitOrientationForTransition()

        // The orientation request and finish are issued during the same main-thread turn. There is
        // no timer, no wait-for-configuration callback and no hidden/black intermediate surface.
        finish()
    }

    override fun onDestroy() {
        Log.v(TAG, "Exiting.")

        mediaOrientationExecutor.shutdownNow()
        orientationHandler.removeCallbacksAndMessages(null)

        // Suppress any further callbacks
        activityIsForeground = false
        immersiveHandler.removeCallbacksAndMessages(null)
        scrubSeekHandler.removeCallbacksAndMessages(null)
        invalidateGestureStableTargetCheck()
        invalidateSeekbarStableTargetCheck()
        gestureScrubActive = false
        seekbarScrubActive = false
        activeScrubSeek = null
        scrubSeekInFlight = false

        if (becomingNoisyReceiverRegistered) {
            unregisterReceiver(becomingNoisyReceiver)
            becomingNoisyReceiverRegistered = false
        }

        with (BackgroundPlaybackService) {
            mediaToken = null
            thumbnailChanged = null
        }
        mediaSession?.let {
            it.isActive = false
            it.release()
        }
        mediaSession = null

        audioFocusRequest?.let {
            AudioManagerCompat.abandonAudioFocusRequest(audioManager!!, it)
        }
        audioFocusRequest = null

        // take the background service with us
        stopServiceRunnable.run()

        if (playbackInitialized && ::binding.isInitialized) {
            player.removeObserver(this)
            player.destroy()
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        Log.v(TAG, "onNewIntent($intent)")
        super.onNewIntent(intent)

        // A singleTask player can be brought forward again by a later external VIEW intent. When
        // it is the root of its task, refresh the fallback orientation from the display state that
        // is active at this new entry rather than retaining a value from the very first launch.
        if (!activityIsForeground && isTaskRoot)
            entryConfigOrientation = resources.configuration.orientation

        // Happens when mpv is still running (not necessarily playing) and the user selects a new
        // file to be played from another app
        val filepath = intent?.let { parsePathFromIntent(it) }
        if (filepath == null) {
            return
        }

        if (!activityIsForeground && didResumeBackgroundPlayback) {
            if (this.newIntentReplace) {
                loadFileWithPersistedSubtitles(filepath, "replace")
                showToast(getString(R.string.notice_file_play))
            } else {
                loadFileWithPersistedSubtitles(filepath, "append")
                showToast(getString(R.string.notice_file_appended))
            }
            moveTaskToBack(true)
        } else {
            // Keep the current file visible while local metadata is probed. The orientation
            // request and loadfile command are then issued in the same UI-thread turn.
            runWithMediaOrientation(filepath) {
                loadFileWithPersistedSubtitles(filepath)
            }
        }
    }

    private fun updateAudioPresence() {
        val haveAudio = MPVLib.getPropertyBoolean("current-tracks/audio/selected")
        if (haveAudio == null) {
            // If we *don't know* if there's an active audio track then don't update to avoid
            // spurious UI changes. The property will become available again later.
            return
        }
        isPlayingAudio = (haveAudio && MPVLib.getPropertyBoolean("mute") != true)
    }

    private fun isPlayingAudioOnly(): Boolean {
        if (!isPlayingAudio)
            return false
        val image = MPVLib.getPropertyString("current-tracks/video/image")
        return image.isNullOrEmpty() || image == "yes"
    }

    private fun shouldBackground(): Boolean {
        if (isFinishing) // about to exit?
            return false
        return when (backgroundPlayMode) {
            "always" -> true
            "audio-only" -> isPlayingAudioOnly()
            else -> false // "never"
        }
    }

    override fun onPause() {
        if (!playbackInitialized) {
            super.onPause()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (isInMultiWindowMode || isInPictureInPictureMode) {
                Log.v(TAG, "Going into multi-window mode")
                super.onPause()
                return
            }
        }

        onPauseImpl()
    }

    private fun tryStartForegroundService(intent: Intent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                ContextCompat.startForegroundService(this, intent)
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, e)
                return false
            }
        } else {
            ContextCompat.startForegroundService(this, intent)
        }
        return true
    }

    private fun onPauseImpl() {
        cancelResumeFrameGate("activity-pause", keepPaused = true)
        val shouldBackground = shouldBackground()
        if (shouldBackground)
            BackgroundPlaybackService.grabThumbnail()
        else
            BackgroundPlaybackService.thumbnail = null
        // media session uses the same thumbnail
        updateMediaSession()

        activityIsForeground = false
        eventUiHandler.removeCallbacksAndMessages(null)
        cancelPendingTapToggle()
        if (isFinishing) {
            savePosition()
            // tell mpv to shut down so that any other property changes or such are ignored,
            // preventing useless busywork
            MPVLib.command(arrayOf("stop"))
        } else if (!shouldBackground) {
            player.paused = true
            // Persist watch-later state even if the process is later killed (Home -> kill).
            savePosition()
        } else {
            // Background playback mode: still persist state once when leaving UI.
            savePosition()
        }
        writeSettings()
        super.onPause()

        didResumeBackgroundPlayback = shouldBackground
        if (shouldBackground) {
            Log.v(TAG, "Resuming playback in background")
            stopServiceHandler.removeCallbacks(stopServiceRunnable)
            val serviceIntent = Intent(this, BackgroundPlaybackService::class.java)
            if (!tryStartForegroundService(serviceIntent)) {
                didResumeBackgroundPlayback = false
                player.paused = true
            }
        }
    }

    private fun readSettings() {
        // FIXME: settings should be in their own class completely
        val prefs = getDefaultSharedPreferences(applicationContext)
        val getString: (String, Int) -> String = { key, defaultRes ->
            prefs.getString(key, resources.getString(defaultRes))!!
        }

        gestures.syncSettings(prefs, resources)

        val statsMode = prefs.getString("stats_mode", "") ?: ""
        this.statsFPS = statsMode == "native_fps"
        this.statsLuaMode = if (statsMode.startsWith("lua"))
            statsMode.removePrefix("lua").toInt()
        else
            0
        this.backgroundPlayMode = getString("background_play", R.string.pref_background_play_default)
        this.noUIPauseMode = getString("no_ui_pause", R.string.pref_no_ui_pause_default)
        this.shouldSavePosition = prefs.getBoolean("save_position", false)
        if (this.autoRotationMode != "manual") // don't reset
            this.autoRotationMode = getString("auto_rotation", R.string.pref_auto_rotation_default)
        this.controlsAtBottom = prefs.getBoolean("bottom_controls", true)
        this.showMediaTitle = prefs.getBoolean("display_media_title", false)
        this.useTimeRemaining = prefs.getBoolean("use_time_remaining", false)
        this.rememberBrightness = prefs.getBoolean("remember_brightness", false)
        this.lastScreenBrightness = if (this.rememberBrightness)
            prefs.getInt("last_screen_brightness", -1)
        else
            -1
        this.ignoreAudioFocus = prefs.getBoolean("ignore_audio_focus", false)
        this.playlistExitWarning = prefs.getBoolean("playlist_exit_warning", true)
        this.newIntentReplace = prefs.getBoolean("new_intent_replace", false)
        this.smoothSeekGesture = prefs.getBoolean("seek_gesture_smooth", false)
    }

    private fun writeSettings() {
        val prefs = getDefaultSharedPreferences(applicationContext)

        with (prefs.edit()) {
            putBoolean("use_time_remaining", useTimeRemaining)
            if (rememberBrightness)
                putInt("last_screen_brightness", lastScreenBrightness)
            else
                remove("last_screen_brightness")
            commit()
        }
    }

    override fun onStart() {
        super.onStart()
        activityIsStopped = false
    }

    override fun onStop() {
        super.onStop()
        activityIsStopped = true

        // Extra safety: persist state when the UI is gone, even if the process is killed.
        // Skip configuration changes (rotation) to avoid needless writes.
        if (playbackInitialized && !isFinishing && !isChangingConfigurations)
            try { savePosition() } catch (_: Throwable) {}
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Last chance before the system reclaims memory / kills background processes.
        if (playbackInitialized && level >= TRIM_MEMORY_UI_HIDDEN && !isFinishing)
            try { savePosition() } catch (_: Throwable) {}
    }

    override fun onResume() {
        if (!playbackInitialized) {
            super.onResume()
            return
        }

        // If we weren't actually in the background (e.g. multi window mode), don't reinitialize stuff
        if (activityIsForeground) {
            super.onResume()
            refreshPlayerOverlay()
            return
        }

        if (lockedUI) { // precaution
            Log.w(TAG, "resumed with locked UI, unlocking")
            unlockUI()
        }

        // Init controls to be hidden and view fullscreen
        hideControls()
        readSettings()

        if (playbackInitialized) {
            player.configureFileStatePersistence(shouldSavePosition)
            if (!shouldSavePosition) {
                MPVLib.getPropertyString("path")?.let {
                    try { discardPersistedFileState(it) } catch (_: Throwable) {}
                }
            }
        }

        activityIsForeground = true
        // stop background service with a delay
        stopServiceHandler.removeCallbacks(stopServiceRunnable)
        stopServiceHandler.postDelayed(stopServiceRunnable, 1000L)

        refreshUi()
        refreshPlayerOverlay()

        super.onResume()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!uiInitialized)
            return
        if (!hasFocus) {
            playerWindowLostFocus = true

            // A top-edge swipe may hand focus to the notification shade. Preserve that manual
            // reveal until focus returns so Android can keep its existing transient-bar behavior.
            if (manualSystemBarsRevealPending)
                immersiveHandler.removeCallbacks(clearManualSystemBarsRevealRunnable)
            return
        }

        if (hasFocus) {
            refreshPlayerOverlay()

            val preserveManualReveal = playerWindowLostFocus && manualSystemBarsRevealPending
            playerWindowLostFocus = false
            manualSystemBarsRevealPending = false
            immersiveHandler.removeCallbacks(clearManualSystemBarsRevealRunnable)

            // Assistant/dialog windows can make the status bar visible after onResume() has
            // already run. Re-apply immersive mode when player focus is restored, except when
            // the focus change came from the user's own top-edge system-bar swipe.
            if (!preserveManualReveal)
                requestPlayerImmersiveRestore()
        }
    }

    private fun savePosition() {
        val mediaPath = MPVLib.getPropertyString("path") ?: return
        if (!fileStatePersistenceEnabled()) {
            discardPersistedFileState(mediaPath)
            return
        }

        // END_FILE can reset eof-reached to false while leaving the old path briefly readable.
        // Never let a later lifecycle callback recreate a resume point for a completed file.
        if (mediaPath == completedWatchLaterPath) {
            player.persistCurrentFileStateWithoutPosition(mediaPath)
            return
        }

        // Take the final authoritative selection snapshot. Only external tracks active in one
        // of the three selectable slots will be restored the next time this file is opened.
        rememberActiveTrackSelectionsForCurrentFile()

        if (MPVLib.getPropertyBoolean("eof-reached") ?: true) {
            Log.d(TAG, "player indicates EOF, not saving watch-later config")
            return
        }
        player.persistCurrentFileState()
    }

    /**
     * Requests or abandons audio focus and noisy receiver depending on the playback state.
     * @warning Call from event thread, not UI thread
     */
    private fun handleAudioFocus() {
        if ((psc.pause && !psc.cachePause) || !isPlayingAudio) {
            if (becomingNoisyReceiverRegistered)
                unregisterReceiver(becomingNoisyReceiver)
            becomingNoisyReceiverRegistered = false
            // TODO: could abandon audio focus after a timeout
        } else {
            if (!becomingNoisyReceiverRegistered)
                registerReceiver(
                    becomingNoisyReceiver,
                    IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                )
            becomingNoisyReceiverRegistered = true
            // (re-)request audio focus
            // Note that this will actually request focus every time the user unpauses, refer to discussion in #1066
            if (requestAudioFocus()) {
                onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN, "request")
            } else {
                onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS, "request")
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val manager = audioManager ?: return false
        val req = audioFocusRequest ?:
            with(AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)) {
            setAudioAttributes(with(AudioAttributesCompat.Builder()) {
                // N.B.: libmpv may use different values in ao_audiotrack, but here we always pretend to be music.
                setUsage(AudioAttributesCompat.USAGE_MEDIA)
                setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
                build()
            })
            setOnAudioFocusChangeListener {
                onAudioFocusChange(it, "callback")
            }
            build()
        }
        val res = AudioManagerCompat.requestAudioFocus(manager, req)
        if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocusRequest = req
            return true
        }
        return false
    }

    // This handles both "real" audio focus changes by the callbacks, which aren't
    // really used anymore after Android 12 (except for AUDIOFOCUS_LOSS),
    // as well as actions equivalent to a focus change that we make up ourselves.
    private fun onAudioFocusChange(type: Int, source: String) {
        Log.v(TAG, "Audio focus changed: $type ($source)")
        if (ignoreAudioFocus || isFinishing)
            return
        when (type) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // loss can occur in addition to ducking, so remember the old callback
                val oldRestore = audioFocusRestore
                val wasPlayerPaused = player.paused ?: false
                player.paused = true
                audioFocusRestore = {
                    oldRestore()
                    if (!wasPlayerPaused) player.paused = false
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                MPVLib.command(arrayOf("multiply", "volume", AUDIO_FOCUS_DUCKING.toString()))
                audioFocusRestore = {
                    val inv = 1f / AUDIO_FOCUS_DUCKING
                    MPVLib.command(arrayOf("multiply", "volume", inv.toString()))
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                audioFocusRestore()
                audioFocusRestore = {}
            }
        }
    }

    // UI

    /** dpad navigation */
    private var btnSelected = -1

    private var mightWantToToggleControls = false
    private var tapDownX = 0f
    private var tapDownY = 0f

    // Prevent accidental single-tap UI toggle while user swipes from the very top to open
    // Android's notification shade / status bar.
    private var statusBarSwipeCandidate = false
    private var statusBarSwipeStartY = 0f
    private var statusBarSwipeCanceledToggle = false

    private fun isInTopSystemGestureDeadzone(y: Float): Boolean {
        // Use the gesture layer height if available (covers edge-to-edge/immersive scenarios).
        val h = when {
            ::binding.isInitialized && binding.gestureLayer.height > 0 -> binding.gestureLayer.height
            (window?.decorView?.height ?: 0) > 0 -> window.decorView.height
            else -> 0
        }
        if (h <= 0) return false
        return y <= h * STATUS_BAR_DEADZONE_PERCENT / 100f
    }

    private fun statusBarSwipeCancelPx(): Float {
        return STATUS_BAR_SWIPE_CANCEL_DP * resources.displayMetrics.density
    }

    /** true if we're actually outputting any audio (includes the mute state, but not pausing) */
    private var isPlayingAudio = false

    private var useAudioUI = false

    private var lockedUI = false

    private fun pauseForDialog(): StateRestoreCallback {
        val useKeepOpen = when (noUIPauseMode) {
            "always" -> true
            "audio-only" -> isPlayingAudioOnly()
            else -> false // "never"
        }
        if (useKeepOpen) {
            // don't pause but set keep-open so mpv doesn't exit while the user is doing stuff
            val oldValue = MPVLib.getPropertyString("keep-open")
            MPVLib.setPropertyBoolean("keep-open", true)
            return {
                oldValue?.also { MPVLib.setPropertyString("keep-open", it) }
            }
        }

        // Pause playback during UI dialogs
        val wasPlayerPaused = player.paused ?: true
        player.paused = true
        return {
            if (!wasPlayerPaused)
                player.paused = false
        }
    }

    private fun updateStats() {
        if (!statsFPS)
            return
        binding.statsTextView.text = getString(R.string.ui_fps, player.estimatedVfFps)
    }

    /**
     * Keeps the Android UI overlay above the video layer and forces a redraw.
     *
     * On some devices the TextureView can momentarily win composition/z-order during player
     * startup. Touch still reaches gestureLayer, so seeking works, but controls/gestureTextView
     * do not become visible until the window is redrawn by something external (for example
     * pulling the notification shade). Poking the overlay here makes that redraw deterministic.
     */
    private fun refreshPlayerOverlay() {
        if (!::binding.isInitialized)
            return

        // Keep the touch layer above the video and the actual UI above the touch layer.
        ViewCompat.setElevation(binding.gestureLayer, 1f)
        ViewCompat.setElevation(binding.outside, 2f)
        binding.gestureLayer.bringToFront()
        binding.outside.bringToFront()

        binding.outside.invalidate()
        binding.root.invalidate()

        binding.root.post {
            if (!::binding.isInitialized)
                return@post
            binding.gestureLayer.bringToFront()
            binding.outside.bringToFront()
            binding.outside.requestLayout()
            binding.outside.invalidate()
            binding.root.invalidate()
        }
    }

    private fun controlsShouldBeVisible(): Boolean {
        if (lockedUI)
            return false
        return btnSelected != -1 || userIsOperatingSeekbar
    }

    /** Make controls visible. */
    private fun showControls() {
        if (lockedUI) {
            Log.w(TAG, "cannot show UI in locked mode")
            return
        }

        refreshPlayerOverlay()

        // Cancel any pending fade-out.
        fadeHandler.removeCallbacks(fadeRunnable)
        binding.controls.animate().setListener(null).cancel()
        binding.topControls.animate().setListener(null).cancel()
        binding.statsTextView.animate().setListener(null).cancel()

        val wasHidden = binding.controls.visibility != View.VISIBLE
        val wasDimmed = binding.controls.alpha < 1f || binding.topControls.alpha < 1f

        if (wasHidden) {
            // Start from transparent so we can fade-in quickly (Samsung-like feel).
            binding.controls.alpha = 0f
            binding.topControls.alpha = 0f

            binding.controls.visibility = View.VISIBLE
            binding.topControls.visibility = View.VISIBLE

            if (this.statsFPS) {
                updateStats()
                binding.statsTextView.alpha = 0f
                binding.statsTextView.visibility = View.VISIBLE
            }

            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.show(WindowInsetsCompat.Type.navigationBars())
        }

        if (wasHidden || wasDimmed) {
            binding.controls.animate().alpha(1f).setDuration(CONTROLS_FADE_IN_DURATION)
            binding.topControls.animate().alpha(1f).setDuration(CONTROLS_FADE_IN_DURATION)
            if (this.statsFPS)
                binding.statsTextView.animate().alpha(1f).setDuration(CONTROLS_FADE_IN_DURATION)
        } else {
            // Ensure fully visible in case we were interrupted mid-animation.
            binding.controls.alpha = 1f
            binding.topControls.alpha = 1f
            if (this.statsFPS)
                binding.statsTextView.alpha = 1f
        }

        // Intentionally do NOT auto-hide controls.
        // The user must explicitly toggle them off (single tap), or they will be hidden when
        // opening any in-player menu/dialog.
    }

    /**
     * Force-hide controls regardless of the current UI state.
     *
     * This is used when opening in-player menus/dialogs: controls must disappear immediately,
     * and they must NOT reappear when the menu/dialog is dismissed.
     */
    private fun hideControlsForMenu() {
        // Cancel any pending fade-out so the runnable cannot interfere.
        fadeHandler.removeCallbacks(fadeRunnable)
        binding.controls.animate().setListener(null).cancel()
        binding.topControls.animate().setListener(null).cancel()
        binding.statsTextView.animate().setListener(null).cancel()

        // Hide instantly (use GONE because of SurfaceView bug, see hideControls()).
        binding.controls.visibility = View.GONE
        binding.topControls.visibility = View.GONE
        binding.statsTextView.visibility = View.GONE

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** Hide controls instantly */
    fun hideControls() {
        if (controlsShouldBeVisible())
            return
        // use GONE here instead of INVISIBLE (which makes more sense) because of Android bug with surface views
        // see http://stackoverflow.com/a/12655713/2606891
        binding.controls.visibility = View.GONE
        binding.topControls.visibility = View.GONE
        binding.statsTextView.visibility = View.GONE

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    /**
     * Some devices/OEMs temporarily show the status bar when a dialog/menu window gains focus.
     * We want the player to stay immersive, and only allow revealing system bars via swipe.
     *
     * IMPORTANT: to prevent even a brief "flash" of the status bar, we must apply immersive
     * flags BEFORE the dialog becomes focusable (Samsung/OEM behavior).
     */
    private fun applyImmersiveToWindow(w: Window) {
        WindowCompat.setDecorFitsSystemWindows(w, false)
        val controller = WindowCompat.getInsetsController(w, w.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** Apply immersive mode only to the active video player activity. */
    private fun applyPlayerImmersiveModeIfPossible() {
        if (!uiInitialized || !activityIsForeground || isFinishing || isDestroyed)
            return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode)
            return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode)
            return

        applyImmersiveToWindow(window)
    }

    /**
     * Restore immersive mode immediately and once more after the current window-focus/insets
     * transaction. The second pass handles OEMs that re-show the status bar just after focus is
     * returned from Assistant or an input window.
     */
    private fun requestPlayerImmersiveRestore() {
        immersiveHandler.removeCallbacks(restorePlayerImmersiveRunnable)
        applyPlayerImmersiveModeIfPossible()
        immersiveHandler.postDelayed(
            restorePlayerImmersiveRunnable,
            IMMERSIVE_RESTORE_RETRY_MS
        )
    }

    /**
     * Re-hide system bars when the soft keyboard changes from visible to hidden. This listener is
     * installed only on windows belonging to MPVActivity and its in-player dialogs.
     */
    private fun installImeDismissImmersiveRestore(w: Window, insetsHost: View) {
        var imeWasVisible =
            ViewCompat.getRootWindowInsets(insetsHost)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true

        ViewCompat.setOnApplyWindowInsetsListener(insetsHost) { view, insets ->
            val imeIsVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeWasVisible && !imeIsVisible) {
                view.post {
                    if (view.isAttachedToWindow)
                        applyImmersiveToWindow(w)
                    requestPlayerImmersiveRestore()
                }
            }
            imeWasVisible = imeIsVisible
            insets
        }
        ViewCompat.requestApplyInsets(insetsHost)
    }

    private fun markManualSystemBarsReveal() {
        manualSystemBarsRevealPending = true
        immersiveHandler.removeCallbacks(clearManualSystemBarsRevealRunnable)
        immersiveHandler.postDelayed(
            clearManualSystemBarsRevealRunnable,
            MANUAL_SYSTEM_BARS_GESTURE_WINDOW_MS
        )
    }

    /**
     * Show an AlertDialog in true immersive mode (prevents the status bar from appearing at all).
     * This uses FLAG_NOT_FOCUSABLE to keep the dialog from taking focus until after we apply
     * the same immersive flags as the activity window.
     */
    private fun showImmersiveDialog(dialog: AlertDialog) {
        // Requirement: when *any* in-player menu/dialog opens, controls must hide immediately,
        // and must not automatically reappear when the dialog closes.
        hideControlsForMenu()

        // Prevent the dialog window from taking focus first (avoids system bars flashing in).
        dialog.window?.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )

        dialog.show()

        val w = dialog.window ?: return
        // Copy the current immersive flags from the activity.
        @Suppress("DEPRECATION")
        w.decorView.systemUiVisibility = window.decorView.systemUiVisibility

        applyImmersiveToWindow(w)

        val insetsHost = w.findViewById<View>(android.R.id.content) ?: w.decorView
        installImeDismissImmersiveRestore(w, insetsHost)

        // Dismissing a keyboard-backed dialog can detach its window before a final IME-insets
        // callback arrives. Restore the player window as soon as that dialog view is detached.
        w.decorView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                requestPlayerImmersiveRestore()
            }
        })

        // Now allow focus/input again.
        w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

        // Re-apply once after focus is restored (extra safety on some ROMs).
        applyImmersiveToWindow(w)
    }

    // Backwards-compatible helper for any existing onShow listeners.
    private fun applyImmersiveToDialog(dialog: AlertDialog) {
        dialog.window?.let { applyImmersiveToWindow(it) }
    }



    /** Start fading out the controls */
    private fun hideControlsFade() {
        fadeHandler.removeCallbacks(fadeRunnable)
        fadeHandler.post(fadeRunnable)
    }

    /**
     * Toggle visibility of controls (if allowed)
     * @return future visibility state
     */
    private fun toggleControls(): Boolean {
        if (lockedUI)
            return false
        if (controlsShouldBeVisible())
            return true
        return if (binding.controls.visibility == View.VISIBLE && !fadeRunnable.hasStarted) {
            hideControlsFade()
            false
        } else {
            showControls()
            true
        }
    }

    private fun showUnlockControls() {
        fadeHandler.removeCallbacks(fadeRunnable2)
        binding.unlockBtn.animate().setListener(null).cancel()

        binding.unlockBtn.alpha = 1f
        binding.unlockBtn.visibility = View.VISIBLE

        fadeHandler.postDelayed(fadeRunnable2, CONTROLS_DISPLAY_TIMEOUT)
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (lockedUI) {
            showUnlockControls()
            return super.dispatchKeyEvent(ev)
        }

        // try built-in event handler first, forward all other events to libmpv
        val handled = interceptDpad(ev) ||
                (ev.action == KeyEvent.ACTION_DOWN && interceptKeyDown(ev)) ||
                player.onKey(ev)
        if (handled) {
            return true
        }
        return super.dispatchKeyEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent?): Boolean {
        if (lockedUI)
            return super.dispatchGenericMotionEvent(ev)

        if (ev != null && ev.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            if (player.onPointerEvent(ev))
                return true
            // keep controls visible when mouse moves
            if (ev.actionMasked == MotionEvent.ACTION_HOVER_MOVE)
                showControls()
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (lockedUI) {
            if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_DOWN)
                showUnlockControls()
            return super.dispatchTouchEvent(ev)
        }

        // For tap-to-toggle, we delay the single-tap action slightly.
        // We DO NOT cancel on the 2nd tap preemptively. Instead, we cancel only if TouchGestures
        // actually confirms and handles a double-tap (see onPropertyChange for PlayPause/SeekFixed/Custom).
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Read this before the event reaches VideoZoomGestures, because its ACTION_DOWN
                // handler immediately stops the fling. The touch that interrupts a coasting image
                // is consumed only for stopping that motion; it must not also toggle controls on UP.
                val stoppingZoomFling = ::zoomGestures.isInitialized &&
                    zoomGestures.isFlingInProgress()

                // Zoom mode uses double-tap to reset zoom (handled by VideoZoomGestures), not TouchGestures.
                // Cancel any pending single-tap toggle from the previous tap so the UI won't flash/appear.
                if (::zoomGestures.isInitialized && zoomGestures.shouldBlockOtherGestures(ev)) {
                    cancelPendingTapToggle()
                }
                mightWantToToggleControls = !stoppingZoomFling
                tapDownX = ev.x
                tapDownY = ev.y

                // If the gesture starts from the very top, treat it as a possible status-bar swipe.
                // We'll only cancel the tap-to-toggle if the finger moves down noticeably.
                statusBarSwipeCandidate = isInTopSystemGestureDeadzone(ev.y)
                statusBarSwipeStartY = ev.y
                statusBarSwipeCanceledToggle = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Multi-touch/pinch gestures are not taps; never toggle controls for them.
                cancelPendingTapToggle()
                mightWantToToggleControls = false
            }
        }

        if (ev.actionMasked == MotionEvent.ACTION_MOVE) {
            // Any meaningful movement turns the interaction into a drag/swipe, so controls should
            // only toggle for a real tap and never for horizontal or vertical swipes.
            if (mightWantToToggleControls) {
                val dx = ev.x - tapDownX
                val dy = ev.y - tapDownY
                val touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
                if (dx * dx + dy * dy > touchSlop * touchSlop) {
                    mightWantToToggleControls = false
                    cancelPendingTapToggle()
                }
            }

            if (statusBarSwipeCandidate && !statusBarSwipeCanceledToggle) {
                // User is likely pulling down the notification shade; don't show player controls.
                if (ev.y - statusBarSwipeStartY > statusBarSwipeCancelPx()) {
                    statusBarSwipeCanceledToggle = true
                    markManualSystemBarsReveal()
                    mightWantToToggleControls = false
                    cancelPendingTapToggle()
                }
            }
        }

        if (super.dispatchTouchEvent(ev)) {
            // reset delay if the event has been handled
            // ideally we'd want to know if the event was delivered to controls, but we can't
            if (binding.controls.visibility == View.VISIBLE && !fadeRunnable.hasStarted)
                showControls()
            // Always reset status-bar swipe tracking when a gesture ends, even if a child view
            // handled the event.
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                statusBarSwipeCandidate = false
                statusBarSwipeCanceledToggle = false
            }
            if (ev.action == MotionEvent.ACTION_UP)
                return true
        }

        if (ev.actionMasked == MotionEvent.ACTION_UP) {
            // Reset status-bar swipe tracking.
            statusBarSwipeCandidate = false
            statusBarSwipeCanceledToggle = false

            if (!mightWantToToggleControls)
                return true

            // Delay the single-tap toggle slightly so TouchGestures can recognize and handle
            // a possible double-tap. If a double-tap *is* handled, onPropertyChange will cancel.
            scheduleSingleTapToggle()
            mightWantToToggleControls = false
        }
        if (ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            cancelPendingTapToggle()
            mightWantToToggleControls = false
            statusBarSwipeCandidate = false
            statusBarSwipeCanceledToggle = false
        }
        return true
    }
    private fun cancelPendingTapToggle() {
        pendingTapToggleRunnable?.let { tapToggleHandler.removeCallbacks(it) }
        pendingTapToggleRunnable = null
    }

    private fun scheduleSingleTapToggle() {
        cancelPendingTapToggle()
        val r = Runnable {
            pendingTapToggleRunnable = null
            toggleControls()
        }
        pendingTapToggleRunnable = r
        tapToggleHandler.postDelayed(r, SINGLE_TAP_TOGGLE_DELAY_MS)
    }

    /**
     * Returns views eligible for dpad button navigation
     */
    private fun dpadButtons(): Sequence<View> {
        val groups = arrayOf(binding.controlsButtonGroup, binding.topControls)
        return sequence {
            for (g in groups) {
                for (i in 0 until g.childCount) {
                    val view = g.getChildAt(i)
                    if (view.isEnabled && view.isVisible && view.isFocusable)
                        yield(view)
                }
            }
        }
    }

    private fun interceptDpad(ev: KeyEvent): Boolean {
        if (btnSelected == -1) { // UP and DOWN are always grabbed and overridden
            when (ev.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (ev.action == KeyEvent.ACTION_DOWN) { // activate dpad navigation
                        btnSelected = 0
                        updateSelectedDpadButton()
                        showControls()
                    }
                    return true
                }
            }
            return false
        }

        // this runs when dpad navigation is active:
        when (ev.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (ev.action == KeyEvent.ACTION_DOWN) { // deactivate dpad navigation
                    btnSelected = -1
                    updateSelectedDpadButton()
                    hideControlsFade()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (ev.action == KeyEvent.ACTION_DOWN) {
                    btnSelected = (btnSelected + 1) % dpadButtons().count()
                    updateSelectedDpadButton()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (ev.action == KeyEvent.ACTION_DOWN) {
                    val count = dpadButtons().count()
                    btnSelected = (count + btnSelected - 1) % count
                    updateSelectedDpadButton()
                }
                return true
            }
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (ev.action == KeyEvent.ACTION_UP) {
                    val view = dpadButtons().elementAtOrNull(btnSelected)
                    // 500ms appears to be the standard
                    if (ev.eventTime - ev.downTime > 500L)
                        view?.performLongClick()
                    else
                        view?.performClick()
                }
                return true
            }
        }
        return false
    }

    private fun updateSelectedDpadButton() {
        val colorFocused = ContextCompat.getColor(this, R.color.tint_btn_bg_focused)
        val colorNoFocus = ContextCompat.getColor(this, R.color.tint_btn_bg_nofocus)

        dpadButtons().forEachIndexed { i, child ->
            if (i == btnSelected)
                child.setBackgroundColor(colorFocused)
            else
                child.setBackgroundColor(colorNoFocus)
        }
    }

    private fun interceptKeyDown(event: KeyEvent): Boolean {
        // intercept some keys to provide functionality "native" to
        // mpv-android even if libmpv already implements these
        var unhandled = 0

        when (event.unicodeChar.toChar()) {
            // (overrides default bindings)
            'j' -> cycleSub()
            '#' -> cycleAudio()
            '<' -> playlistPrev()
            '>' -> playlistNext()

            else -> unhandled++
        }
        // Note: dpad center is bound according to how Android TV apps should generally behave,
        // see <https://developer.android.com/docs/quality-guidelines/tv-app-quality>.
        // Due to implementation inconsistencies enter and numpad enter need to perform the same
        // function (issue #963).
        when (event.keyCode) {
            // (no default binding)
            KeyEvent.KEYCODE_CAPTIONS -> cycleSub()
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> cycleAudio()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> playlistPrev()
            KeyEvent.KEYCODE_MEDIA_NEXT -> playlistNext()
            KeyEvent.KEYCODE_INFO -> toggleControls()
            KeyEvent.KEYCODE_MENU -> openTopMenu()
            KeyEvent.KEYCODE_GUIDE -> openTopMenu()
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> togglePlaybackPauseFromUi()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> togglePlaybackPauseFromUi()
            KeyEvent.KEYCODE_MEDIA_PLAY -> resumePlaybackWithFrameGate("media-key")
            KeyEvent.KEYCODE_MEDIA_PAUSE -> pausePlaybackImmediately("media-key")

            // (overrides a default binding)
            KeyEvent.KEYCODE_ENTER -> togglePlaybackPauseFromUi()

            else -> unhandled++
        }

        return unhandled < 2
    }

    private fun onBackPressedImpl() {
        if (lockedUI)
            return showUnlockControls()

        val notYetPlayed = psc.playlistCount - psc.playlistPos - 1
        if (notYetPlayed <= 0 || !playlistExitWarning) {
            finishWithResult(RESULT_OK, true)
            return
        }

        val restore = pauseForDialog()
        with (AlertDialog.Builder(this)) {
            setMessage(getString(R.string.exit_warning_playlist, notYetPlayed))
            setPositiveButton(R.string.dialog_yes) { dialog, _ ->
                dialog.dismiss()
                finishWithResult(RESULT_OK, true)
            }
            setNegativeButton(R.string.dialog_no) { dialog, _ ->
                dialog.dismiss()
                restore()
            }
            val dialog = create()
            this@MPVActivity.showImmersiveDialog(dialog)
        }
    }

    private fun installGestureMetricsUpdater() {
        binding.gestureLayer.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.width > 1 && v.height > 1) {
                gestures.setMetrics(v.width.toFloat(), v.height.toFloat())
                zoomGestures.setMetrics(v.width.toFloat(), v.height.toFloat())
            }
        }
        binding.gestureLayer.post { updateGestureMetricsFromView() }
    }

    private fun updateGestureMetricsFromView() {
        if (!::binding.isInitialized || !::gestures.isInitialized || !::zoomGestures.isInitialized)
            return

        val w = when {
            binding.gestureLayer.width > 1 -> binding.gestureLayer.width
            binding.player.width > 1 -> binding.player.width
            else -> resources.displayMetrics.widthPixels
        }
        val h = when {
            binding.gestureLayer.height > 1 -> binding.gestureLayer.height
            binding.player.height > 1 -> binding.player.height
            else -> resources.displayMetrics.heightPixels
        }

        if (w > 1 && h > 1) {
            gestures.setMetrics(w.toFloat(), h.toFloat())
            zoomGestures.setMetrics(w.toFloat(), h.toFloat())
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE

        updateGestureMetricsFromView()

        // Adjust control margins (only after the player UI is attached)
        if (uiInitialized) {
            binding.controls.updateLayoutParams<MarginLayoutParams> {
                bottomMargin = if (!controlsAtBottom) {
                    Utils.convertDp(this@MPVActivity, 60f)
                } else {
                    0
                }
                leftMargin = if (!controlsAtBottom) {
                    Utils.convertDp(this@MPVActivity, if (isLandscape) 60f else 24f)
                } else {
                    0
                }
                rightMargin = leftMargin
            }
        }
    }

    private fun onPiPModeChangedImpl(state: Boolean) {
        Log.v(TAG, "onPiPModeChanged($state)")
        if (state) {
            lockedUI = true
            hideControls()
            return
        }

        unlockUI()
        // For whatever stupid reason Android provides no good detection for when PiP is exited
        // so we have to do this shit <https://stackoverflow.com/questions/43174507/#answer-56127742>
        // If we don't exit the activity here it will stick around and not be retrievable from the
        // recents screen, or react to onNewIntent().
        if (activityIsStopped) {
            // Note: On Android 12 or older there's another bug with this: the result will not
            // be delivered to the calling activity and is instead instantly returned the next
            // time, which makes it looks like the file picker is broken.
            finishWithResult(RESULT_OK, true)
        }
    }

    private fun persistBeforePlaylistJump() {
        val mediaPath = MPVLib.getPropertyString("path") ?: return
        if (!fileStatePersistenceEnabled()) {
            discardPersistedFileState(mediaPath)
            return
        }

        if (mediaPath == completedWatchLaterPath) {
            player.persistCurrentFileStateWithoutPosition(mediaPath)
            return
        }

        rememberActiveTrackSelectionsForCurrentFile()

        // Do not create a resume point at the physical end of a completed file. Per-file option
        // changes are persisted when they are made, so skipping this write does not lose them.
        if (MPVLib.getPropertyBoolean("eof-reached") == true)
            return
        player.persistCurrentFileState()
    }

    private fun playlistPrev() {
        persistBeforePlaylistJump()
        runPlaylistJumpWithOrientation(-1) {
            MPVLib.command(arrayOf("playlist-prev"))
        }
    }

    private fun playlistNext() {
        persistBeforePlaylistJump()
        runPlaylistJumpWithOrientation(1) {
            MPVLib.command(arrayOf("playlist-next"))
        }
    }

    private fun playPlaylistItem(index: Int) {
        if (MPVLib.getPropertyInt("playlist-pos") == index)
            return
        persistBeforePlaylistJump()
        val path = playlistPathAt(index)
        if (path == null) {
            MPVLib.setPropertyInt("playlist-pos", index)
        } else {
            runWithMediaOrientation(path) {
                MPVLib.setPropertyInt("playlist-pos", index)
            }
        }
    }

    private fun runPlaylistJumpWithOrientation(offset: Int, action: () -> Unit) {
        // Shuffle decides the target inside mpv, so there is no reliable filename to probe.
        if (MPVLib.getPropertyBoolean("shuffle") == true) {
            action()
            return
        }

        val position = MPVLib.getPropertyInt("playlist-pos") ?: run {
            action()
            return
        }
        val count = MPVLib.getPropertyInt("playlist-count") ?: run {
            action()
            return
        }
        if (count <= 0) {
            action()
            return
        }

        var target = position + offset
        if (target !in 0 until count) {
            val loops = MPVLib.getPropertyString("loop-playlist")
            if (loops == null || loops == "no" || loops == "0") {
                action()
                return
            }
            target = if (target < 0) count - 1 else 0
        }

        val path = playlistPathAt(target)
        if (path == null)
            action()
        else
            runWithMediaOrientation(path, action)
    }

    private fun playlistPathAt(index: Int): String? {
        if (index < 0)
            return null
        return MPVLib.getPropertyString("playlist/$index/filename")
            ?: MPVLib.getPropertyString("playlist/$index/current-filename")
    }

    private fun showToast(msg: String, cancel: Boolean = false, durationMs: Long? = null) {
        toastCancelRunnable?.let(toastHandler::removeCallbacks)
        toastCancelRunnable = null

        if (cancel)
            toast?.cancel()

        val shownToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).apply {
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 0)
            show()
        }
        toast = shownToast

        if (durationMs != null) {
            toastCancelRunnable = Runnable {
                if (toast === shownToast) {
                    shownToast.cancel()
                    toast = null
                }
                toastCancelRunnable = null
            }.also { toastHandler.postDelayed(it, durationMs) }
        }
    }

    // Intent/Uri parsing

    private fun parsePathFromIntent(intent: Intent): String? {
        fun safeResolveUri(u: Uri?): String? {
            return if (u != null && u.isHierarchical && !u.isRelative)
                resolveUri(u)
            else null
        }

        return when (intent.action) {
            Intent.ACTION_VIEW -> {
                // Normal file open or URL view
                intent.data?.let { resolveUri(it) }
            }

            Intent.ACTION_SEND -> {
                // Handle single shared file or text link
                var parsed = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (parsed == null) {
                    parsed = intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                        Uri.parse(it.trim())
                    }
                }

                safeResolveUri(parsed)
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                // Multiple shared files
                val uris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (!uris.isNullOrEmpty()) {
                    val paths = uris.mapNotNull { uri ->
                        safeResolveUri(uri)
                    }
                    if (paths.size == 1) {
                        return paths[0]
                    } else if (!paths.isEmpty()) {
                        // Use a memory playlist
                        val memoryUri = "memory://#EXTM3U\n${paths.joinToString("\n")}\n"
                        Log.v(TAG, "Created memory playlist URI (${paths.size})")
                        return memoryUri
                    }
                }
                return null
            }

            else -> {
                // Custom intent from MainScreenFragment
                intent.getStringExtra("filepath")
            }
        }
    }

    private fun resolveUri(data: Uri): String? {
        val filepath = when (data.scheme) {
            "file" -> data.path
            "content" -> translateContentUri(data)
            // mpv supports data URIs but needs data:// to pass it through correctly
            "data" -> "data://${data.schemeSpecificPart}"
            "http", "https", "rtmp", "rtmps", "rtp", "rtsp", "mms", "mmst", "mmsh",
            "tcp", "udp", "lavf", "ftp"
            -> data.toString()
            else -> null
        }

        if (filepath == null)
            Log.e(TAG, "unknown scheme: ${data.scheme}")
        return filepath
    }

    private fun translateContentUri(uri: Uri): String {
        val resolver = applicationContext.contentResolver
        Log.v(TAG, "Resolving content URI: $uri")
        try {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                // See if we can skip the indirection and read the real file directly
                val path = Utils.findRealPath(pfd.fd)
                if (path != null) {
                    Log.v(TAG, "Found real file path: $path")
                    return path
                }
            }
        } catch(e: Exception) {
            Log.e(TAG, "Failed to open content fd: $e")
        }

        // Otherwise, just let mpv open the content URI directly via ffmpeg
        return uri.toString()
    }

    // --- Per-file subtitle persistence (chosen subtitle track/file is restored on reopen) ---

    private fun sha1Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private fun perFileKey(suffix: String, path: String): String = "perfile_${suffix}_${sha1Hex(path)}"

    /**
     * Attach saved external subtitles to the playlist entry before mpv starts opening it. This
     * makes the tracks available during mpv's initial stream selection, rather than adding them
     * from FILE_LOADED after the first embedded-subtitle frame may already have been rendered.
     * The saved primary/secondary choices must be part of the same loadfile options as well:
     * setting secondary-sid from FILE_LOADED is asynchronous and can otherwise happen one frame
     * after an explicitly loaded external subtitle was auto-selected as the primary subtitle.
     */
    private fun persistedSubtitleLoadOptions(mediaPath: String): String? {
        if (!fileStatePersistenceEnabled())
            return null

        val prefs = getDefaultSharedPreferences(applicationContext)
        class SavedSubtitle(
            val kind: String?,
            val external: String?,
            val sid: Int?
        )

        fun readSavedSubtitle(kindKey: String, externalKey: String, sidKey: String): SavedSubtitle {
            val sidPreference = perFileKey(sidKey, mediaPath)
            return SavedSubtitle(
                kind = prefs.getString(perFileKey(kindKey, mediaPath), null),
                external = prefs.getString(perFileKey(externalKey, mediaPath), null)
                    ?.takeIf { it.isNotEmpty() },
                sid = if (prefs.contains(sidPreference))
                    prefs.getInt(sidPreference, -1)
                else
                    null
            )
        }

        val primary = readSavedSubtitle(PREF_SUB_KIND, PREF_SUB_EXTERNAL, PREF_SUB_SID)
        val secondary = readSavedSubtitle(PREF_SUB2_KIND, PREF_SUB2_EXTERNAL, PREF_SUB2_SID)
        val externalFiles = linkedSetOf<String>()
        for (selection in listOf(primary, secondary)) {
            if (selection.kind == PREF_SUB_KIND_EXTERNAL)
                selection.external?.let(externalFiles::add)
        }

        val options = mutableListOf<String>()

        // loadfile's fourth argument is a comma-separated key/value list. Fixed-length quoting
        // keeps commas, colons and non-ASCII characters in Android paths unambiguous to mpv.
        fun quoteOptionValue(value: String): String {
            val utf8Length = value.toByteArray(Charsets.UTF_8).size
            return "%${utf8Length}%$value"
        }

        if (externalFiles.size == 1) {
            // -append takes one literal path, so only loadfile's outer key/value parser needs
            // quoting. In particular, content:// and commas remain part of the filename.
            options += "sub-files-append=${quoteOptionValue(externalFiles.first())}"
        } else if (externalFiles.size > 1) {
            // -add parses a Unix ':'-separated path list after loadfile has parsed its own
            // comma-separated option list. Escape the inner list first, then quote that complete
            // value so the backslashes survive the outer parser.
            fun escapePathListItem(path: String): String = buildString(path.length) {
                for (char in path) {
                    if (char == '\\' || char == ':')
                        append('\\')
                    append(char)
                }
            }

            val pathList = externalFiles.joinToString(":") { escapePathListItem(it) }
            options += "sub-files-add=${quoteOptionValue(pathList)}"
        }

        fun initialTrackOption(selection: SavedSubtitle): String? {
            return when (selection.kind) {
                // Explicitly loaded subtitle files win mpv's automatic subtitle ranking. With
                // both slots set to auto, mpv picks them in the primary/secondary load order.
                PREF_SUB_KIND_EXTERNAL -> selection.external?.let { "auto" }
                PREF_SUB_KIND_SID -> selection.sid?.let { if (it < 0) "no" else it.toString() }
                else -> null
            }
        }

        var primaryOption = initialTrackOption(primary)
        val secondaryOption = initialTrackOption(secondary)
        if (primaryOption == null && secondary.kind == PREF_SUB_KIND_EXTERNAL) {
            // A legacy/incomplete snapshot may only contain the secondary external filename.
            // Prevent mpv from auto-selecting that file into the primary slot before it chooses
            // the same explicitly loaded track for secondary-sid=auto.
            primaryOption = "no"
        }
        primaryOption?.let { options += "sid=$it" }
        secondaryOption?.let { options += "secondary-sid=$it" }

        return options.takeIf { it.isNotEmpty() }?.joinToString(",")
    }

    private fun loadFileWithPersistedSubtitles(path: String, flags: String = "replace") {
        val options = persistedSubtitleLoadOptions(path)
        val command = if (options == null) {
            arrayOf("loadfile", path, flags)
        } else {
            // Since mpv 0.38, -1 occupies loadfile's insertion-index argument.
            arrayOf("loadfile", path, flags, "-1", options)
        }
        MPVLib.command(command)
    }

    private fun fileStatePersistenceEnabled(): Boolean {
        return getDefaultSharedPreferences(applicationContext)
            .getBoolean("save_position", false)
    }

    private fun clearPerFileSelections(path: String) {
        val prefs = getDefaultSharedPreferences(applicationContext)
        with (prefs.edit()) {
            for (suffix in PER_FILE_SELECTION_KEYS)
                remove(perFileKey(suffix, path))
            commit()
        }
    }

    private fun discardPersistedFileState(path: String) {
        MPVLib.command(arrayOf("delete-watch-later-config", path))
        player.clearPersistedPlaybackOptions(path)
        clearPerFileSelections(path)
    }

    /**
     * Reconcile persistence with the tracks that are active now. External tracks that were merely
     * added but are no longer primary, secondary or selected audio are therefore not reloaded.
     */
    private fun rememberActiveTrackSelectionsForCurrentFile() {
        if (!fileStatePersistenceEnabled())
            return

        // If a fallback sub-add is still in flight, keep the stored external filenames intact.
        // The track-list callback will take the authoritative snapshot after restoration finishes.
        val mediaPath = MPVLib.getPropertyString("path")
        val subtitleRestorePending = pendingSubtitleRestorePath?.let { it == mediaPath } == true
        if (!subtitleRestorePending) {
            rememberSubtitleSelectionForCurrentFile()
            rememberSubtitleSelectionForCurrentFile(secondary = true)
        }
        rememberAudioSelectionForCurrentFile()
    }

    private fun rememberSubtitleSelectionForCurrentFile(
        secondary: Boolean = false,
        selectedSid: Int? = null
    ) {
        if (!fileStatePersistenceEnabled())
            return
        val mediaPath = MPVLib.getPropertyString("path") ?: return
        val prefs = getDefaultSharedPreferences(applicationContext)

        val sidProp = if (secondary) "secondary-sid" else "sid"
        val kindKey = if (secondary) PREF_SUB2_KIND else PREF_SUB_KIND
        val extKey = if (secondary) PREF_SUB2_EXTERNAL else PREF_SUB_EXTERNAL
        val sidKey = if (secondary) PREF_SUB2_SID else PREF_SUB_SID

        // Property updates are asynchronous. For a direct UI selection, persist the ID that
        // was clicked instead of immediately reading sid/secondary-sid and saving its old value.
        val sid = selectedSid
            ?: MPVLib.getPropertyString(sidProp)?.toIntOrNull()
            ?: -1
        val ext = findExternalSubFilenameForSid(sid)

        with (prefs.edit()) {
            if (!ext.isNullOrEmpty()) {
                putString(perFileKey(kindKey, mediaPath), PREF_SUB_KIND_EXTERNAL)
                putString(perFileKey(extKey, mediaPath), ext)
                remove(perFileKey(sidKey, mediaPath))
            } else {
                putString(perFileKey(kindKey, mediaPath), PREF_SUB_KIND_SID)
                putInt(perFileKey(sidKey, mediaPath), sid)
                remove(perFileKey(extKey, mediaPath))
            }
            // A subtitle can be changed immediately before leaving the activity. Complete this
            // tiny write now so a previous primary/secondary choice cannot win during teardown.
            commit()
        }
    }

    private fun rememberExternalSubtitleForCurrentFile(subPath: String) {
        if (!fileStatePersistenceEnabled())
            return
        val mediaPath = MPVLib.getPropertyString("path") ?: return
        val prefs = getDefaultSharedPreferences(applicationContext)
        with (prefs.edit()) {
            // We treat adding an external subtitle as the user's chosen subtitle.
            putString(perFileKey(PREF_SUB_KIND, mediaPath), PREF_SUB_KIND_EXTERNAL)
            putString(perFileKey(PREF_SUB_EXTERNAL, mediaPath), subPath)
            remove(perFileKey(PREF_SUB_SID, mediaPath))
            commit()
        }
    }

    private fun clearPendingSubtitleRestore() {
        pendingSubtitleRestorePath = null
        externalSubtitleLoadsInFlight.clear()
    }

    private fun restoreSubtitleSelectionForCurrentFile(): Boolean {
        if (!fileStatePersistenceEnabled()) {
            clearPendingSubtitleRestore()
            return true
        }
        val mediaPath = MPVLib.getPropertyString("path") ?: return false
        val prefs = getDefaultSharedPreferences(applicationContext)
        var waitingForExternalTrack = false

        fun setSubProp(prop: String, id: Int) {
            player.setFileLocalTrack(prop, id)
        }

        fun resolveSelection(kind: String?, external: String?, sid: Int?): Int? {
            return when (kind) {
                PREF_SUB_KIND_EXTERNAL -> {
                    if (external.isNullOrEmpty()) {
                        null
                    } else {
                        val id = findExternalSubSidForFilename(external)
                        if (id == null) {
                            waitingForExternalTrack = true
                            // Never block mpv's event thread waiting for sub-add. A subsequent
                            // track-list update retries resolution once the new track has an ID.
                            if (externalSubtitleLoadsInFlight.add(external))
                                MPVLib.command(arrayOf("sub-add", external, "auto"))
                        }
                        id
                    }
                }
                PREF_SUB_KIND_SID -> sid
                else -> null
            }
        }

        val kind1 = prefs.getString(perFileKey(PREF_SUB_KIND, mediaPath), null)
        val ext1 = prefs.getString(perFileKey(PREF_SUB_EXTERNAL, mediaPath), null)
        val sid1 = if (prefs.contains(perFileKey(PREF_SUB_SID, mediaPath)))
            prefs.getInt(perFileKey(PREF_SUB_SID, mediaPath), -1)
        else
            null

        val kind2 = prefs.getString(perFileKey(PREF_SUB2_KIND, mediaPath), null)
        val ext2 = prefs.getString(perFileKey(PREF_SUB2_EXTERNAL, mediaPath), null)
        val sid2 = if (prefs.contains(perFileKey(PREF_SUB2_SID, mediaPath)))
            prefs.getInt(perFileKey(PREF_SUB2_SID, mediaPath), -1)
        else
            null

        // Adding an external track can influence mpv's automatic primary selection. Resolve every
        // stored file first, then set the two slots explicitly so their order cannot be swapped.
        val resolvedPrimary = resolveSelection(kind1, ext1, sid1)
        val resolvedSecondary = resolveSelection(kind2, ext2, sid2)
        if (waitingForExternalTrack) {
            pendingSubtitleRestorePath = mediaPath
            return false
        }

        clearPendingSubtitleRestore()
        resolvedPrimary?.let { setSubProp("sid", it) }
        resolvedSecondary?.let { setSubProp("secondary-sid", it) }
        return true
    }

    private fun retryPendingSubtitleRestore() {
        val expectedPath = pendingSubtitleRestorePath ?: return
        if (MPVLib.getPropertyString("path") != expectedPath) {
            clearPendingSubtitleRestore()
            return
        }

        val restored = try {
            restoreSubtitleSelectionForCurrentFile()
        } catch (_: Throwable) {
            false
        }
        if (restored) {
            try { rememberActiveTrackSelectionsForCurrentFile() } catch (_: Throwable) {}
        }
    }

    private fun findExternalSubSidForFilename(filename: String): Int? {
        val count = MPVLib.getPropertyInt("track-list/count") ?: return null
        for (i in 0 until count) {
            val type = MPVLib.getPropertyString("track-list/$i/type") ?: continue
            if (type != "sub") continue
            val isExternal = MPVLib.getPropertyBoolean("track-list/$i/external") == true
            if (!isExternal) continue
            val fn = MPVLib.getPropertyString("track-list/$i/external-filename") ?: continue
            if (fn != filename) continue
            return MPVLib.getPropertyInt("track-list/$i/id")
        }
        return null
    }

    private fun findExternalSubFilenameForSid(sid: Int): String? {
        if (sid < 0) return null
        val count = MPVLib.getPropertyInt("track-list/count") ?: return null
        for (i in 0 until count) {
            val type = MPVLib.getPropertyString("track-list/$i/type") ?: continue
            if (type != "sub") continue
            val id = MPVLib.getPropertyInt("track-list/$i/id") ?: continue
            if (id != sid) continue
            val isExternal = MPVLib.getPropertyBoolean("track-list/$i/external") == true
            if (!isExternal) return null
            return MPVLib.getPropertyString("track-list/$i/external-filename")
        }
        return null
    }

    // --- Per-file audio persistence (chosen audio track/file is restored on reopen) ---

    private fun rememberAudioSelectionForCurrentFile(selectedAid: Int? = null) {
        if (!fileStatePersistenceEnabled())
            return
        val mediaPath = MPVLib.getPropertyString("path") ?: return
        val prefs = getDefaultSharedPreferences(applicationContext)
        val aid = selectedAid
            ?: MPVLib.getPropertyString("aid")?.toIntOrNull()
            ?: -1
        val ext = findExternalAudioFilenameForAid(aid)
        with (prefs.edit()) {
            if (!ext.isNullOrEmpty()) {
                putString(perFileKey(PREF_AUD_KIND, mediaPath), PREF_AUD_KIND_EXTERNAL)
                putString(perFileKey(PREF_AUD_EXTERNAL, mediaPath), ext)
                remove(perFileKey(PREF_AUD_SID, mediaPath))
            } else {
                putString(perFileKey(PREF_AUD_KIND, mediaPath), PREF_AUD_KIND_SID)
                putInt(perFileKey(PREF_AUD_SID, mediaPath), aid)
                remove(perFileKey(PREF_AUD_EXTERNAL, mediaPath))
            }
            commit()
        }
    }

    private fun rememberExternalAudioForCurrentFile(audioPath: String) {
        if (!fileStatePersistenceEnabled())
            return
        val mediaPath = MPVLib.getPropertyString("path") ?: return
        val prefs = getDefaultSharedPreferences(applicationContext)
        with (prefs.edit()) {
            putString(perFileKey(PREF_AUD_KIND, mediaPath), PREF_AUD_KIND_EXTERNAL)
            putString(perFileKey(PREF_AUD_EXTERNAL, mediaPath), audioPath)
            remove(perFileKey(PREF_AUD_SID, mediaPath))
            commit()
        }
    }

    private fun restoreAudioSelectionForCurrentFile() {
        if (!fileStatePersistenceEnabled())
            return
        val mediaPath = MPVLib.getPropertyString("path") ?: return
        val prefs = getDefaultSharedPreferences(applicationContext)

        val kind = prefs.getString(perFileKey(PREF_AUD_KIND, mediaPath), null) ?: return
        val ext = prefs.getString(perFileKey(PREF_AUD_EXTERNAL, mediaPath), null)
        val hasAid = prefs.contains(perFileKey(PREF_AUD_SID, mediaPath))
        val aid = if (hasAid) prefs.getInt(perFileKey(PREF_AUD_SID, mediaPath), -1) else null

        when (kind) {
            PREF_AUD_KIND_EXTERNAL -> {
                if (!ext.isNullOrEmpty()) {
                    // "cached" → يضيف الملف ويختاره، ولو كان موجوداً مسبقاً يُعاد استخدامه
                    MPVLib.command(arrayOf("audio-add", ext, "cached"))
                }
            }
            PREF_AUD_KIND_SID -> {
                if (aid != null) {
                    player.setFileLocalTrack("aid", aid)
                }
            }
        }
    }

    private fun findExternalAudioFilenameForAid(aid: Int): String? {
        if (aid < 0) return null
        val count = MPVLib.getPropertyInt("track-list/count") ?: return null
        for (i in 0 until count) {
            val type = MPVLib.getPropertyString("track-list/$i/type") ?: continue
            if (type != "audio") continue
            val id = MPVLib.getPropertyInt("track-list/$i/id") ?: continue
            if (id != aid) continue
            val isExternal = MPVLib.getPropertyBoolean("track-list/$i/external") == true
            if (!isExternal) return null
            return MPVLib.getPropertyString("track-list/$i/external-filename")
        }
        return null
    }

    private fun parseIntentExtras(extras: Bundle?) {
        onloadCommands.clear()
        if (extras == null)
            return

        fun pushOption(key: String, value: String) {
            onloadCommands.add(arrayOf("set", "file-local-options/${key}", value))
        }

        // Refer to http://mpv-android.github.io/mpv-android/intent.html
        // Note: these only apply to the first file, it's not clear what the semantics for a
        // playlist should be.

        if (extras.getByte("decode_mode") == 2.toByte())
            pushOption("hwdec", "no")
        if (extras.containsKey("subs")) {
            val subList = Utils.getParcelableArray<Uri>(extras, "subs")
            val subsToEnable = Utils.getParcelableArray<Uri>(extras, "subs.enable")

            for (suburi in subList) {
                val subfile = resolveUri(suburi) ?: continue
                val flag = if (subsToEnable.any { it == suburi }) "select" else "auto"

                Log.v(TAG, "Adding subtitles from intent extras: $subfile")
                onloadCommands.add(arrayOf("sub-add", subfile, flag))
            }
        }
        extras.getInt("position", 0).let {
            if (it > 0)
                pushOption("start", "${it / 1000f}")
        }
        extras.getString("title", "").let {
            if (!it.isNullOrEmpty())
                pushOption("force-media-title", it)
        }
        // TODO: `headers` would be good, maybe `tls_verify`
    }

    // UI (Part 2)

    data class TrackData(val trackId: Int, val trackType: String)
    private fun trackSwitchNotification(f: () -> TrackData) {
        val (track_id, track_type) = f()
        val trackPrefix = when (track_type) {
            "audio" -> getString(R.string.track_audio)
            "sub"   -> getString(R.string.track_subs)
            "video" -> "Video"
            else    -> "???"
        }

        val msg = if (track_id == -1) {
            "$trackPrefix ${getString(R.string.track_off)}"
        } else {
            val trackName = player.tracks[track_type]?.firstOrNull{ it.mpvId == track_id }?.name ?: "???"
            "$trackPrefix $trackName"
        }
        val durationMs = if (track_type == "audio" || track_type == "sub")
            TRACK_SWITCH_TOAST_DURATION_MS
        else
            null
        showToast(msg, true, durationMs)
    }

    private fun cycleAudio() = trackSwitchNotification {
        player.cycleAudio()
        try { rememberAudioSelectionForCurrentFile() } catch (_: Throwable) {}
        player.persistCurrentFileState()
        TrackData(player.aid, "audio")
    }
    private fun cycleSub() = trackSwitchNotification {
        player.cycleSub()
        try { rememberSubtitleSelectionForCurrentFile() } catch (_: Throwable) {}
        player.persistCurrentFileState()
        TrackData(player.sid, "sub")
    }

    private fun selectTrack(type: String, get: () -> Int, set: (Int) -> Unit) {
    val tracks = player.tracks.getValue(type)
    val selectedMpvId = get()
    val selectedIndex = tracks.indexOfFirst { it.mpvId == selectedMpvId }
    val restore = pauseForDialog()

    var handled = false
    val dialog = with (AlertDialog.Builder(this)) {
        setSingleChoiceItems(tracks.map { it.name }.toTypedArray(), selectedIndex) { _, item ->
            val trackId = tracks[item].mpvId

            set(trackId)
            if (type == "sub") {
                try {
                    rememberSubtitleSelectionForCurrentFile(selectedSid = trackId)
                } catch (_: Throwable) {}
            } else if (type == "audio") {
                try { rememberAudioSelectionForCurrentFile(selectedAid = trackId) } catch (_: Throwable) {}
            }
            player.persistCurrentFileState()
            trackSwitchNotification { TrackData(trackId, type) }
            // Keep dialog open (apply-in-place).
        }
        setNegativeButton(R.string.dialog_cancel) { d, _ -> d.cancel() }
        setOnCancelListener {
            handled = true
            restore()
        }
        setOnDismissListener {
            if (!handled)
                restore()
        }
        create()
    }
    showImmersiveDialog(dialog)
}

private fun pickAudio() = selectTrack("audio", { player.aid }, { player.aid = it })

    private fun pickSub() {
    val restore = pauseForDialog()
    val impl = SubTrackDialog(player)
    lateinit var dialog: AlertDialog
    var handled = false

    impl.listener = { it, secondary ->
        if (secondary)
            player.secondarySid = it.mpvId
        else
            player.sid = it.mpvId

        try {
            rememberSubtitleSelectionForCurrentFile(
                secondary = secondary,
                selectedSid = it.mpvId
            )
        } catch (_: Throwable) {}
        player.persistCurrentFileState()
        trackSwitchNotification { TrackData(it.mpvId, SubTrackDialog.TRACK_TYPE) }
        // Keep dialog open (apply-in-place).
    }

    dialog = with(AlertDialog.Builder(this)) {
        val inflater = LayoutInflater.from(context)
        setView(impl.buildView(inflater))
        setOnCancelListener {
            handled = true
            restore()
        }
        setOnDismissListener {
            if (!handled)
                restore()
        }
        create()
    }
    showImmersiveDialog(dialog)
}

private fun openPlaylistMenu(restore: StateRestoreCallback, onBack: (() -> Unit)? = null) {
    val impl = PlaylistDialog(player)
    lateinit var dialog: AlertDialog

    val backAction: () -> Unit = onBack ?: restore
    var handled = false

    impl.listeners = object : PlaylistDialog.Listeners {
        private fun openFilePicker(skip: Int) {
            openFilePickerFor(RCODE_LOAD_FILE, "", skip) { result, data ->
                if (result == RESULT_OK) {
                    val path = data!!.getStringExtra("path")!!
                    loadFileWithPersistedSubtitles(path, "append")
                    impl.refresh()
                }
            }
        }
        override fun pickFile() = openFilePicker(FilePickerActivity.FILE_PICKER)

        override fun openUrl() {
            val helper = Utils.OpenUrlDialog(this@MPVActivity)
            // Apply without closing (stay in the URL dialog so the user can add multiple entries).
            val urlDialog = with(helper) {
                builder.setPositiveButton(R.string.dialog_ok, null)
                builder.setNegativeButton(R.string.dialog_cancel) { d, _ -> d.cancel() }
                create()
            }
            urlDialog.setOnShowListener {
                this@MPVActivity.applyImmersiveToDialog(urlDialog)
                urlDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val url = helper.text
                    if (url.isNotBlank()) {
                        loadFileWithPersistedSubtitles(url, "append")
                        impl.refresh()
                    }
                    // Keep dialog open.
                }
            }
            showImmersiveDialog(urlDialog)
        }

        override fun onItemPicked(item: MPVView.PlaylistItem) {
            playPlaylistItem(item.index)
            impl.refresh()
            // Keep dialog open (apply-in-place).
        }
    }

    dialog = with(AlertDialog.Builder(this)) {
        val inflater = LayoutInflater.from(context)
        setView(impl.buildView(inflater))

        // Tapping outside exits directly to video.
        setOnCancelListener {
            handled = true
            runIfActive(restore)
        }
        // Fallback for non-cancel dismissals.
        setOnDismissListener {
            if (!handled)
                runIfActive(restore)
        }
        create()
    }

    dialog.setCanceledOnTouchOutside(true)
    dialog.setOnKeyListener { _, keyCode, event ->
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                handled = true
                runIfActive(backAction)
                dialog.dismiss()
            }
            true
        } else {
            false
        }
    }

    showImmersiveDialog(dialog)
}

private fun pickDecoder() {
    val restore = pauseForDialog()

    val items = mutableListOf(
        Pair("HW (mediacodec-copy)", "mediacodec-copy"),
        Pair("SW", "no")
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        items.add(0, Pair("HW+ (mediacodec)", "mediacodec"))

    val hwdecActive = player.hwdecActive
    val selectedIndex = items.indexOfFirst { it.second == hwdecActive }

    var handled = false
    val dialog = with(AlertDialog.Builder(this)) {
        setSingleChoiceItems(items.map { it.first }.toTypedArray(), selectedIndex) { _, idx ->
            player.setFileLocalString("hwdec", items[idx].second)
            player.persistCurrentFileState()
            // Keep dialog open (apply-in-place).
        }
        setNegativeButton(R.string.dialog_cancel) { d, _ -> d.cancel() }
        setOnCancelListener {
            handled = true
            restore()
        }
        setOnDismissListener {
            if (!handled)
                restore()
        }
        create()
    }
    showImmersiveDialog(dialog)
}

private fun cycleSpeed() {
        player.cycleSpeed()
    }

    private fun pickSpeed() {
        // TODO: replace this with SliderPickerDialog
        val picker = SpeedPickerDialog()

        val restore = pauseForDialog()
        genericPickerDialog(picker, R.string.title_speed_dialog, "speed", onBack = { restore() }, onExit = { restore() })
    }

    private fun goIntoPiP() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
            return
        updatePiPParams(true)
        enterPictureInPictureMode()
    }

    private fun lockUI() {
        lockedUI = true
        hideControlsFade()
    }

    private fun unlockUI() {
        binding.unlockBtn.visibility = View.GONE
        lockedUI = false
    }

    data class MenuItem(
    @IdRes val idRes: Int,
    /** If true, the current menu dialog will be dismissed after running [handler]. */
    val dismiss: Boolean = false,
    /** If true, [onBack] will be invoked immediately before dismissing the dialog. */
    val restoreOnDismiss: Boolean = false,
    val handler: () -> Unit
)

private inline fun runIfActive(block: () -> Unit) {
    if (!isFinishing && !isDestroyed) block()
}

private fun genericMenu(
    @LayoutRes layoutRes: Int,
    buttons: List<MenuItem>,
    hiddenButtons: Set<Int>,
    onBack: () -> Unit,
    onExit: () -> Unit
) {
    lateinit var dialog: AlertDialog

    val builder = AlertDialog.Builder(this)
    val dialogView = LayoutInflater.from(builder.context).inflate(layoutRes, null)

    for (button in buttons) {
        val buttonView = dialogView.findViewById<Button>(button.idRes)
        buttonView.setOnClickListener {
            button.handler()
            if (button.dismiss) {
                if (button.restoreOnDismiss) runIfActive(onBack)
                dialog.dismiss()
            }
        }
    }

    hiddenButtons.forEach { dialogView.findViewById<View>(it).isVisible = false }

    if (Utils.visibleChildren(dialogView) == 0) {
        Log.w(TAG, "Not showing menu because it would be empty")
        runIfActive(onBack)
        return
    }

    Utils.handleInsetsAsPadding(dialogView)

    with(builder) {
        setView(dialogView)
        // Tapping outside should exit directly to video; Back should navigate "up" in the menu stack.
        setOnCancelListener { runIfActive(onExit) }
        dialog = create()
    }

    dialog.setCanceledOnTouchOutside(true)
    dialog.setOnKeyListener { _, keyCode, event ->
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                runIfActive(onBack)
                dialog.dismiss()
            }
            true
        } else {
            false
        }
    }

    showImmersiveDialog(dialog)
}

private fun openTopMenu(existingRestoreState: StateRestoreCallback? = null) {
    val restoreState = existingRestoreState ?: pauseForDialog()

    fun addExternalThing(cmd: String, result: Int, data: Intent?) {
        if (result != RESULT_OK)
            return
        // file picker may return a content URI or a bare file path
        val path = data!!.getStringExtra("path")!!
        val path2 = if (path.startsWith("content://"))
            translateContentUri(Uri.parse(path))
        else
            path
        MPVLib.command(arrayOf(cmd, path2, "cached"))

        // Persist the chosen external track per video so it gets reloaded on reopen.
        if (cmd == "sub-add") {
            try { rememberExternalSubtitleForCurrentFile(path2) } catch (_: Throwable) {}
        } else if (cmd == "audio-add") {
            try { rememberExternalAudioForCurrentFile(path2) } catch (_: Throwable) {}
        }
    }

    fun openChapterListDialog() {
        val chapters = player.loadChapters()
        if (chapters.isEmpty()) {
            // Nothing to show; just stay in the top menu.
            openTopMenu(restoreState)
            return
        }
        val chapterArray = chapters.map {
            val timecode = Utils.prettyTime(it.time.roundToInt())
            if (!it.title.isNullOrEmpty())
                getString(R.string.ui_chapter, it.title, timecode)
            else
                getString(R.string.ui_chapter_fallback, it.index + 1, timecode)
        }.toTypedArray()

        val selectedIndex = MPVLib.getPropertyInt("chapter") ?: 0
        var handled = false
        val dialog = with(AlertDialog.Builder(this)) {
            setTitle(R.string.chapter_button)
            setSingleChoiceItems(chapterArray, selectedIndex) { _, item ->
                MPVLib.setPropertyInt("chapter", chapters[item].index)
            }
            // "Cancel" behaves like Back (up to the top menu).
            setNegativeButton(R.string.dialog_cancel) { _, _ ->
                handled = true
                openTopMenu(restoreState)
            }
            // Tapping outside exits directly to video.
            setOnCancelListener {
                handled = true
                restoreState()
            }
            setOnDismissListener {
                if (!handled)
                    restoreState()
            }
            create()
        }

        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    handled = true
                    openTopMenu(restoreState)
                    dialog.dismiss()
                }
                true
            } else {
                false
            }
        }

        showImmersiveDialog(dialog)
    }

    /******/
    val hiddenButtons = mutableSetOf<Int>()
    val buttons: MutableList<MenuItem> = mutableListOf(
        MenuItem(R.id.audioBtn, dismiss = true) {
            openFilePickerFor(RCODE_EXTERNAL_AUDIO, R.string.open_external_audio) { result, data ->
                addExternalThing("audio-add", result, data)
                restoreState()
            }
        },
        MenuItem(R.id.subBtn, dismiss = true) {
            openFilePickerFor(RCODE_EXTERNAL_SUB, R.string.open_external_sub) { result, data ->
                addExternalThing("sub-add", result, data)
                restoreState()
            }
        },
        MenuItem(R.id.playlistBtn, dismiss = true) {
            openPlaylistMenu(restoreState, onBack = { openTopMenu(restoreState) })
        },
        MenuItem(R.id.backgroundBtn, dismiss = true) {
            // Restoring state may (un)pause so do that first.
            restoreState()
            backgroundPlayMode = "always"
            player.paused = false
            moveTaskToBack(true)
        },
        MenuItem(R.id.chapterBtn, dismiss = true) {
            openChapterListDialog()
        },
        MenuItem(R.id.chapterPrev) {
            MPVLib.command(arrayOf("add", "chapter", "-1"))
        },
        MenuItem(R.id.chapterNext) {
            MPVLib.command(arrayOf("add", "chapter", "1"))
        },
        MenuItem(R.id.advancedBtn, dismiss = true) {
            openAdvancedMenu(restoreState)
        },
        MenuItem(R.id.orientationBtn) {
            autoRotationMode = "manual"
            cycleOrientation()
        }
    )

    if (!isPlayingAudio)
        hiddenButtons.add(R.id.backgroundBtn)
    if ((MPVLib.getPropertyInt("chapter-list/count") ?: 0) == 0)
        hiddenButtons.add(R.id.rowChapter)
    /******/

    genericMenu(R.layout.dialog_top_menu, buttons, hiddenButtons, onBack = { restoreState() }, onExit = { restoreState() })
}

    private fun genericPickerDialog(
    picker: PickerDialog,
    @StringRes titleRes: Int,
    property: String,
    onBack: () -> Unit,
    onExit: () -> Unit
) {
    lateinit var dialog: AlertDialog
    var handled = false

    dialog = with(AlertDialog.Builder(this)) {
        setTitle(titleRes)
        val inflater = LayoutInflater.from(context)
        setView(picker.buildView(inflater))

        // Apply without closing: we'll override the click listener after show().
        setPositiveButton(R.string.dialog_ok, null)
        // "Cancel" behaves like Back (up one menu level).
        setNegativeButton(R.string.dialog_cancel) { _, _ ->
            handled = true
            runIfActive(onBack)
        }

        // Tapping outside exits directly to video.
        setOnCancelListener {
            handled = true
            runIfActive(onExit)
        }
        // Fallback for non-cancel dismissals.
        setOnDismissListener {
            if (!handled)
                runIfActive(onExit)
        }
        create()
    }

    picker.number = MPVLib.getPropertyDouble(property)

    dialog.setCanceledOnTouchOutside(true)
    dialog.setOnKeyListener { _, keyCode, event ->
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                handled = true
                runIfActive(onBack)
                dialog.dismiss()
            }
            true
        } else {
            false
        }
    }

    dialog.setOnShowListener {

        applyImmersiveToDialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            picker.number?.let {
                if (picker.isInteger())
                    player.setFileLocalInt(property, it.toInt())
                else
                    player.setFileLocalDouble(property, it)
                player.persistCurrentFileState()
            }
            // Keep dialog open (apply-in-place).
        }
    }

    showImmersiveDialog(dialog)
}

private fun openAdvancedMenu(restoreState: StateRestoreCallback) {
    fun openAspectRatioDialog() {
        val ratios = resources.getStringArray(R.array.aspect_ratios)
        val names = resources.getStringArray(R.array.aspect_ratio_names)

        fun parseAspectRatio(value: String): Double? {
            val trimmed = value.trim()
            if (trimmed.isEmpty() || trimmed == "panscan" || trimmed == "-1" || trimmed.equals("no", true))
                return null

            val parts = trimmed.split(':', limit = 2)
            return if (parts.size == 2) {
                val width = parts[0].toDoubleOrNull()
                val height = parts[1].toDoubleOrNull()
                if (width != null && height != null && height != 0.0)
                    width / height
                else
                    null
            } else {
                trimmed.toDoubleOrNull()
            }
        }

        fun aspectRatioMatches(current: String, ratio: String): Boolean {
            if (current == ratio)
                return true
            val currentValue = parseAspectRatio(current) ?: return false
            val ratioValue = parseAspectRatio(ratio) ?: return false
            return abs(currentValue - ratioValue) < 0.001
        }

        val currentPanscan = MPVLib.getPropertyDouble("panscan") ?: 0.0
        val currentOverride = MPVLib.getPropertyString("video-aspect-override")?.trim() ?: ""
        val panscanIndex = ratios.indexOf("panscan")
        val originalIndex = ratios.indexOf("-1").takeIf { it >= 0 } ?: 0
        var selectedIndex = if (currentPanscan > 0.0 && panscanIndex >= 0) {
            panscanIndex
        } else {
            ratios.indexOfFirst { ratio ->
                ratio != "panscan" && aspectRatioMatches(currentOverride, ratio)
            }
        }
        if (selectedIndex < 0) selectedIndex = originalIndex

        var handled = false
        val dialog = with(AlertDialog.Builder(this)) {
            setSingleChoiceItems(names, selectedIndex) { _, item ->
                val ratio = ratios[item]
                val targetPanscan = if (ratio == "panscan") 1.0 else 0.0
                val targetAspect = if (ratio == "panscan") {
                    try { player.getVideoAspect() } catch (_: Throwable) { null }
                } else {
                    parseAspectRatio(ratio) ?: try { player.getVideoAspect() } catch (_: Throwable) { null }
                }
                val targetPixelSize = try { player.getVideoPixelSize() } catch (_: Throwable) { null }

                // Menu selections are the only transition where we already know the
                // requested geometry. Apply it before asking mpv to redraw so the
                // user never sees the temporary fullscreen/base layout.
                if (::zoomGestures.isInitialized) {
                    try {
                        zoomGestures.applyPredictedAspectMenuGeometry(
                            aspect = targetAspect,
                            pixelSize = targetPixelSize,
                            panscanValue = targetPanscan,
                        )
                    } catch (_: Throwable) {}
                }

                val suppressUntil = SystemClock.uptimeMillis() + ASPECT_MENU_PREDICTIVE_SYNC_GRACE_MS
                suppressAspectMenuGeometrySyncUntilMs = suppressUntil
                eventUiHandler.postDelayed({
                    if (SystemClock.uptimeMillis() >= suppressUntil)
                        syncZoomVideoGeometry(prepareNormalSurface = true, immediate = true)
                }, ASPECT_MENU_PREDICTIVE_SYNC_GRACE_MS + 20L)

                if (ratio == "panscan") {
                    player.setFileLocalString("video-aspect-override", "-1")
                    player.setFileLocalDouble("panscan", 1.0)
                } else {
                    player.setFileLocalString("video-aspect-override", ratio)
                    player.setFileLocalDouble("panscan", 0.0)
                }
                player.persistCurrentFileState()
                // Keep dialog open (apply-in-place).
            }
            // "Cancel" behaves like Back (up to the advanced menu).
            setNegativeButton(R.string.dialog_cancel) { _, _ ->
                handled = true
                openAdvancedMenu(restoreState)
            }
            // Tapping outside exits directly to video.
            setOnCancelListener {
                handled = true
                restoreState()
            }
            setOnDismissListener {
                if (!handled)
                    restoreState()
            }
            create()
        }

        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    handled = true
                    openAdvancedMenu(restoreState)
                    dialog.dismiss()
                }
                true
            } else {
                false
            }
        }

        showImmersiveDialog(dialog)
    }

    fun openSubDelayDialog() {
        val picker = SubDelayDialog(-600.0, 600.0)
        lateinit var dialog: AlertDialog
        var handled = false

        dialog = with(AlertDialog.Builder(this)) {
            setTitle(R.string.sub_delay)
            val inflater = LayoutInflater.from(context)
            setView(picker.buildView(inflater))

            setPositiveButton(R.string.dialog_ok, null)
            // "Cancel" behaves like Back (up to the advanced menu).
            setNegativeButton(R.string.dialog_cancel) { _, _ ->
                handled = true
                openAdvancedMenu(restoreState)
            }

            // Tapping outside exits directly to video.
            setOnCancelListener {
                handled = true
                restoreState()
            }
            setOnDismissListener {
                if (!handled)
                    restoreState()
            }
            create()
        }

        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    handled = true
                    openAdvancedMenu(restoreState)
                    dialog.dismiss()
                }
                true
            } else {
                false
            }
        }

        picker.delay1 = player.subDelay ?: 0.0
        picker.delay2 = if (player.sid != -1 || player.secondarySid != -1)
            (player.secondarySubDelay ?: 0.0)
        else
            null

        dialog.setOnShowListener {

            applyImmersiveToDialog(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                picker.delay1?.let { player.subDelay = it }
                picker.delay2?.let { player.secondarySubDelay = it }
                player.persistCurrentFileState()
                // Keep dialog open (apply-in-place).
            }
        }

        showImmersiveDialog(dialog)
    }

    /******/
    val hiddenButtons = mutableSetOf<Int>()
    val buttons: MutableList<MenuItem> = mutableListOf(
        MenuItem(R.id.subSeekPrev) {
            MPVLib.command(arrayOf("sub-seek", "-1", "both"))
        },
        MenuItem(R.id.subSeekNext) {
            MPVLib.command(arrayOf("sub-seek", "1", "both"))
        },
        MenuItem(R.id.statsBtn) {
            MPVLib.command(arrayOf("script-binding", "stats/display-stats-toggle"))
        },
        MenuItem(R.id.aspectBtn, dismiss = true) {
            openAspectRatioDialog()
        }
        )

    val statsButtons = arrayOf(R.id.statsBtn1, R.id.statsBtn2, R.id.statsBtn3)
    for (i in 1..3) {
        buttons.add(MenuItem(statsButtons[i - 1]) {
            MPVLib.command(arrayOf("script-binding", "stats/display-page-$i"))
        })
    }

    // contrast, brightness and others get a -100 to 100 slider
    val basicIds = arrayOf(R.id.contrastBtn, R.id.brightnessBtn, R.id.gammaBtn, R.id.saturationBtn)
    val basicProps = arrayOf("contrast", "brightness", "gamma", "saturation")
    val basicTitles = arrayOf(R.string.contrast, R.string.video_brightness, R.string.gamma, R.string.saturation)
    basicIds.forEachIndexed { index, id ->
        buttons.add(MenuItem(id, dismiss = true) {
            val slider = SliderPickerDialog(-100.0, 100.0, 1, R.string.format_fixed_number)
            genericPickerDialog(slider, basicTitles[index], basicProps[index], onBack = { openAdvancedMenu(restoreState) }, onExit = { restoreState() })
        })
    }

    // audio delay get a decimal picker
    buttons.add(MenuItem(R.id.audioDelayBtn, dismiss = true) {
        val picker = DecimalPickerDialog(-600.0, 600.0)
        genericPickerDialog(picker, R.string.audio_delay, "audio-delay", onBack = { openAdvancedMenu(restoreState) }, onExit = { restoreState() })
    })

    // sub delay (primary/secondary) dialog
    buttons.add(MenuItem(R.id.subDelayBtn, dismiss = true) {
        openSubDelayDialog()
    })

    if (player.vid == -1)
        hiddenButtons.addAll(arrayOf(R.id.rowVideo1, R.id.rowVideo2, R.id.aspectBtn))
    if (player.aid == -1 || player.vid == -1)
        hiddenButtons.add(R.id.audioDelayBtn)
    // Subtitle controls should be available whenever *any* subtitle track is active.
    // Previously this was gated only on the primary subtitle (sid), which made the
    // subtitle delay dialog disappear when only the secondary subtitle (secondary-sid)
    // was enabled.
    if (player.sid == -1 && player.secondarySid == -1)
        hiddenButtons.addAll(arrayOf(R.id.subDelayBtn, R.id.rowSubSeek))
    /******/

    genericMenu(R.layout.dialog_advanced_menu, buttons, hiddenButtons, onBack = { openTopMenu(restoreState) }, onExit = { restoreState() })
}

    private fun cycleOrientation() {
        val desired = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (setRequestedOrientationSafely(desired))
            orientationOwnedByPlayer = true
    }

    private var activityResultCallbacks: MutableMap<Int, ActivityResultCallback> = mutableMapOf()
    private fun openFilePickerFor(requestCode: Int, title: String, skip: Int?, callback: ActivityResultCallback) {
        val intent = Intent(this, FilePickerActivity::class.java)
        intent.putExtra("title", title)
        intent.putExtra("allow_document", true)
        skip?.let { intent.putExtra("skip", it) }
        // start file picker at directory of current file
        val path = MPVLib.getPropertyString("path") ?: ""
        if (path.startsWith('/'))
            intent.putExtra("default_path", File(path).parent)

        activityResultCallbacks[requestCode] = callback
        startActivityForResult(intent, requestCode)
    }
    private fun openFilePickerFor(requestCode: Int, @StringRes titleRes: Int, callback: ActivityResultCallback) {
        openFilePickerFor(requestCode, getString(titleRes), null, callback)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        activityResultCallbacks.remove(requestCode)?.invoke(resultCode, data)
    }

    private fun refreshUi() {
        // forces update of entire UI, used when resuming the activity
        updatePlaybackStatus(psc.pause)
        updatePlaybackPos(psc.positionSec)
        updatePlaybackDuration(psc.durationSec)
        updateAudioUI()
        updateOrientation()
        updateMetadataDisplay()
        updateDecoderButton()
        updateSpeedButton()
        updatePlaylistButtons()
        player.loadTracks()
    }

    private fun updateAudioUI() {
        val audioButtons = arrayOf(R.id.prevBtn, R.id.cycleAudioBtn, R.id.playBtn,
                R.id.cycleSpeedBtn, R.id.nextBtn)
        val videoButtons = arrayOf(R.id.cycleAudioBtn, R.id.cycleSubsBtn, R.id.playBtn,
                R.id.cycleDecoderBtn, R.id.cycleSpeedBtn)

        val shouldUseAudioUI = isPlayingAudioOnly()
        if (shouldUseAudioUI == useAudioUI)
            return
        useAudioUI = shouldUseAudioUI
        Log.v(TAG, "Audio UI: $useAudioUI")

        val seekbarGroup = binding.controlsSeekbarGroup
        val buttonGroup = binding.controlsButtonGroup

        if (useAudioUI) {
            // Move prev/next file from seekbar group to buttons group
            Utils.viewGroupMove(seekbarGroup, R.id.prevBtn, buttonGroup, 0)
            Utils.viewGroupMove(seekbarGroup, R.id.nextBtn, buttonGroup, -1)

            // Change button layout of buttons group
            Utils.viewGroupReorder(buttonGroup, audioButtons)

            // Show song title and more metadata
            binding.controlsTitleGroup.visibility = View.VISIBLE
            Utils.viewGroupReorder(binding.controlsTitleGroup, arrayOf(R.id.titleTextView, R.id.minorTitleTextView))
            updateMetadataDisplay()
        } else {
            Utils.viewGroupMove(buttonGroup, R.id.prevBtn, seekbarGroup, 0)
            Utils.viewGroupMove(buttonGroup, R.id.nextBtn, seekbarGroup, -1)

            Utils.viewGroupReorder(buttonGroup, videoButtons)

            // Show title only depending on settings
            if (showMediaTitle) {
                binding.controlsTitleGroup.visibility = View.VISIBLE
                Utils.viewGroupReorder(binding.controlsTitleGroup, arrayOf(R.id.fullTitleTextView))
                updateMetadataDisplay()
            } else {
                binding.controlsTitleGroup.visibility = View.GONE
            }

        }

        // Visibility might have changed, so update
        updatePlaylistButtons()
    }

    private fun updateScreenBrightness() {
        if (lastScreenBrightness == -1)
            return
        val lp = window.attributes
        lp.screenBrightness = lastScreenBrightness / 100f
        window.attributes = lp
    }

    private fun updateMetadataDisplay() {
        if (!useAudioUI) {
            if (showMediaTitle)
                binding.fullTitleTextView.text = psc.meta.formatTitle()
        } else {
            binding.titleTextView.text = psc.meta.formatTitle()
            binding.minorTitleTextView.text = psc.meta.formatArtistAlbum()
        }
    }

    private fun updatePlaybackPos(position: Int) {
        binding.playbackPositionTxt.text = Utils.prettyTime(position)
        if (useTimeRemaining) {
            val diff = psc.durationSec - position
            binding.playbackDurationTxt.text = if (diff <= 0)
                "-00:00"
            else
                Utils.prettyTime(-diff, true)
        }
        if (!userIsOperatingSeekbar)
            binding.playbackSeekbar.progress = position

        // Note: do NOT add other update functions here just because this is called every second.
        // Use property observation instead.
        updateStats()
    }

    private fun updatePlaybackDuration(duration: Int) {
        if (!useTimeRemaining)
            binding.playbackDurationTxt.text = Utils.prettyTime(duration)
        if (!userIsOperatingSeekbar)
            binding.playbackSeekbar.max = duration
    }

    private fun updatePlaybackStatus(paused: Boolean) {
        // mpv is deliberately paused while an exact scrub seek is decoding. During that hold,
        // show the state requested by the user rather than the temporary internal pause.
        val playbackPaused = scrubPlaybackPaused ?: paused
        val r = if (playbackPaused) R.drawable.ic_play_arrow_black_24dp else R.drawable.ic_pause_black_24dp
        binding.playBtn.setImageResource(r)

        updatePiPParams()
        if (playbackPaused) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun updateDecoderButton() {
        binding.cycleDecoderBtn.text = when (player.hwdecActive) {
            "mediacodec" -> "HW+"
            "no" -> "SW"
            else -> "HW"
        }
    }

    private fun updateSpeedButton() {
        binding.cycleSpeedBtn.text = getString(R.string.ui_speed, psc.speed)
    }

    private fun updatePlaylistButtons() {
        val plCount = psc.playlistCount
        val plPos = psc.playlistPos

        if (!useAudioUI && plCount == 1) {
            // use View.GONE so the buttons won't take up any space
            binding.prevBtn.visibility = View.GONE
            binding.nextBtn.visibility = View.GONE
            return
        }
        binding.prevBtn.visibility = View.VISIBLE
        binding.nextBtn.visibility = View.VISIBLE

        val g = ContextCompat.getColor(this, R.color.tint_disabled)
        val w = ContextCompat.getColor(this, R.color.tint_normal)
        binding.prevBtn.imageTintList = ColorStateList.valueOf(if (plPos == 0) g else w)
        binding.nextBtn.imageTintList = ColorStateList.valueOf(if (plPos == plCount-1) g else w)
    }

    private fun readAutoRotationModeForLaunch() {
        val prefs = getDefaultSharedPreferences(applicationContext)
        val defaultMode = resources.getString(R.string.pref_auto_rotation_default)
        autoRotationMode = prefs.getString("auto_rotation", defaultMode) ?: defaultMode
    }

    private fun resolveLaunchRequestedOrientation(path: String): Int? {
        if (!supportsRequestedOrientation())
            return null

        return when (autoRotationMode) {
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "auto" -> requestedOrientationForMedia(resolveOrientationWithinLaunchBudget(path))
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun supportsRequestedOrientation(): Boolean {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_SCREEN_PORTRAIT))
            return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode)
            return false
        return true
    }

    private fun resolveOrientationWithinLaunchBudget(path: String): MediaOrientationResolver.Orientation {
        cachedOrientation(path)?.let { return it }
        if (!MediaOrientationResolver.canResolve(path))
            return MediaOrientationResolver.Orientation.UNKNOWN

        val future = mediaOrientationExecutor.submit<MediaOrientationResolver.Orientation> {
            MediaOrientationResolver.resolve(applicationContext, path)
        }
        val result = try {
            future.get(ORIENTATION_PROBE_BUDGET_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            return MediaOrientationResolver.Orientation.UNKNOWN
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return MediaOrientationResolver.Orientation.UNKNOWN
        } catch (e: Throwable) {
            Log.d(TAG, "Media orientation probe failed for $path", e)
            MediaOrientationResolver.Orientation.UNKNOWN
        }

        cacheOrientation(path, result)
        return result
    }

    private fun cachedOrientation(path: String): MediaOrientationResolver.Orientation? {
        return synchronized(orientationProbeCache) { orientationProbeCache[path] }
    }

    private fun cacheOrientation(path: String, result: MediaOrientationResolver.Orientation) {
        // UNKNOWN can be transient for a document provider or an unfinished download. Caching it
        // would prevent a later retry when the same playlist item becomes locally readable.
        if (result == MediaOrientationResolver.Orientation.UNKNOWN)
            return
        synchronized(orientationProbeCache) { orientationProbeCache[path] = result }
    }

    private fun requestedOrientationForMedia(
        orientation: MediaOrientationResolver.Orientation,
    ): Int? {
        return when (orientation) {
            MediaOrientationResolver.Orientation.LANDSCAPE ->
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

            MediaOrientationResolver.Orientation.PORTRAIT ->
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT

            MediaOrientationResolver.Orientation.SQUARE ->
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

            MediaOrientationResolver.Orientation.UNKNOWN -> null
        }
    }

    private fun requestOrientationIfNeeded(desired: Int) {
        if (!supportsRequestedOrientation())
            return
        val ownsOrientation = desired == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE ||
            desired == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        if (requestedOrientation == desired) {
            orientationOwnedByPlayer = ownsOrientation
        } else if (setRequestedOrientationSafely(desired)) {
            orientationOwnedByPlayer = ownsOrientation
        }
    }

    private fun setRequestedOrientationSafely(desired: Int): Boolean {
        return try {
            requestedOrientation = desired
            true
        } catch (e: IllegalStateException) {
            // Some vendor Android 8/9 builds temporarily reject orientation requests while a
            // window is entering or leaving PiP/multi-window. mpv's normal layout still works.
            Log.w(TAG, "Orientation request rejected: $desired", e)
            false
        }
    }

    private fun requestExitOrientationForTransition() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_SCREEN_PORTRAIT))
            return

        val desired = if (!isTaskRoot) {
            // Adopt the exact orientation policy of the in-task activity beneath the player. This
            // is useful even in Device/square mode because the underlying activity may itself be
            // fixed to portrait or landscape.
            ActivityInfo.SCREEN_ORIENTATION_BEHIND
        } else {
            // External VIEW launches commonly make MPVActivity the root of its own task. With no
            // in-task activity to inherit from, restore the entry configuration only when the
            // player actually owned a portrait/landscape lock. Device/square mode must remain free
            // to follow the current physical orientation.
            if (!orientationOwnedByPlayer)
                return
            when (entryConfigOrientation) {
                Configuration.ORIENTATION_LANDSCAPE ->
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

                Configuration.ORIENTATION_PORTRAIT ->
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT

                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }

        // Do not gate this through supportsRequestedOrientation(): during a close transition on
        // Android 9 the activity may already be leaving PiP/multi-window, yet the handoff remains
        // valid and should occur before the underlying window becomes visible.
        if (requestedOrientation != desired)
            setRequestedOrientationSafely(desired)
    }

    private fun updateOrientation(initial: Boolean = false) {
        if (!supportsRequestedOrientation())
            return

        if (autoRotationMode != "auto") {
            if (!initial)
                return // Do not overwrite a fixed/manual choice while playback is running.

            val desired = when (autoRotationMode) {
                "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            requestOrientationIfNeeded(desired)
            return
        }

        if (initial || !playbackInitialized || !mediaGeometryReadyForOrientation || player.vid == -1)
            return

        // getVideoPixelSize() already applies video-params/rotate. Unlike the displayed aspect,
        // native dimensions are not changed by the user's aspect-ratio override.
        val size = player.getVideoPixelSize() ?: return
        val orientation = MediaOrientationResolver.classify(size.first, size.second)
        val desired = requestedOrientationForMedia(orientation) ?: return
        requestOrientationIfNeeded(desired)
    }

    private fun runWithMediaOrientation(path: String, action: () -> Unit) {
        if (autoRotationMode != "auto" ||
            !supportsRequestedOrientation() ||
            !MediaOrientationResolver.canResolve(path)
        ) {
            action()
            return
        }

        cachedOrientation(path)?.let { cached ->
            requestedOrientationForMedia(cached)?.let(::requestOrientationIfNeeded)
            action()
            return
        }

        val generation = ++mediaSwitchProbeGeneration
        val timeout = Runnable {
            if (generation != mediaSwitchProbeGeneration || isFinishing || isDestroyed)
                return@Runnable

            // Inaccessible/cloud-backed content must never make a file switch hang forever.
            // This is only a failure ceiling; local media normally completes far earlier.
            mediaSwitchProbeGeneration = generation + 1
            action()
        }
        orientationHandler.postDelayed(timeout, ORIENTATION_ASYNC_PROBE_TIMEOUT_MS)

        try {
            mediaOrientationExecutor.execute {
                val result = try {
                    MediaOrientationResolver.resolve(applicationContext, path)
                } catch (_: Throwable) {
                    MediaOrientationResolver.Orientation.UNKNOWN
                }

                orientationHandler.post {
                    if (generation != mediaSwitchProbeGeneration || isFinishing || isDestroyed)
                        return@post

                    orientationHandler.removeCallbacks(timeout)
                    mediaSwitchProbeGeneration = generation + 1
                    cacheOrientation(path, result)
                    if (autoRotationMode == "auto")
                        requestedOrientationForMedia(result)?.let(::requestOrientationIfNeeded)
                    action()
                }
            }
        } catch (_: Throwable) {
            orientationHandler.removeCallbacks(timeout)
            if (generation == mediaSwitchProbeGeneration && !isFinishing && !isDestroyed) {
                mediaSwitchProbeGeneration = generation + 1
                action()
            }
        }
    }

    private fun prefetchAdjacentPlaylistOrientations() {
        if (autoRotationMode != "auto" || !supportsRequestedOrientation() || isFinishing)
            return

        val position = MPVLib.getPropertyInt("playlist-pos") ?: return
        val count = MPVLib.getPropertyInt("playlist-count") ?: return
        if (count <= 1)
            return

        val candidates = linkedSetOf(position - 1, position + 1)
        for (index in candidates) {
            if (index !in 0 until count)
                continue
            val path = playlistPathAt(index) ?: continue
            if (!MediaOrientationResolver.canResolve(path) || cachedOrientation(path) != null)
                continue
            if (!markOrientationPrefetchStarted(path))
                continue

            try {
                mediaOrientationExecutor.execute {
                    try {
                        val result = try {
                            MediaOrientationResolver.resolve(applicationContext, path)
                        } catch (_: Throwable) {
                            MediaOrientationResolver.Orientation.UNKNOWN
                        }
                        cacheOrientation(path, result)
                    } finally {
                        markOrientationPrefetchFinished(path)
                    }
                }
            } catch (_: Throwable) {
                markOrientationPrefetchFinished(path)
                // The executor may already be shutting down while the activity exits.
            }
        }
    }

    private fun markOrientationPrefetchStarted(path: String): Boolean {
        return synchronized(orientationProbesInFlight) { orientationProbesInFlight.add(path) }
    }

    private fun markOrientationPrefetchFinished(path: String) {
        synchronized(orientationProbesInFlight) { orientationProbesInFlight.remove(path) }
    }

    @RequiresApi(26)
    private fun makeRemoteAction(@DrawableRes icon: Int, @StringRes title: Int, intentAction: String): RemoteAction {
        val intent = NotificationButtonReceiver.createIntent(this, intentAction)
        return RemoteAction(Icon.createWithResource(this, icon), getString(title), "", intent)
    }

    /**
     * Update Picture-in-picture parameters. Will only run if in PiP mode unless
     * `force` is set.
     */
    private fun updatePiPParams(force: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            return
        if (!isInPictureInPictureMode && !force)
            return

        val playPauseAction = if (psc.pause)
            makeRemoteAction(R.drawable.ic_play_arrow_black_24dp, R.string.btn_play, "PLAY_PAUSE")
        else
            makeRemoteAction(R.drawable.ic_pause_black_24dp, R.string.btn_pause, "PLAY_PAUSE")
        val actions = mutableListOf<RemoteAction>()
        if (psc.playlistCount > 1) {
            actions.add(makeRemoteAction(
                R.drawable.ic_skip_previous_black_24dp, R.string.dialog_prev, "ACTION_PREV"
            ))
            actions.add(playPauseAction)
            actions.add(makeRemoteAction(
                R.drawable.ic_skip_next_black_24dp, R.string.dialog_next, "ACTION_NEXT"
            ))
        } else {
            actions.add(playPauseAction)
        }

        val params = with(PictureInPictureParams.Builder()) {
            val aspect = player.getVideoAspect() ?: 0.0
            setAspectRatio(Rational(aspect.times(10000).toInt(), 10000))
            setActions(actions)
        }
        try {
            setPictureInPictureParams(params.build())
        } catch (e: IllegalArgumentException) {
            // Android has some limits of what the aspect ratio can be
            params.setAspectRatio(Rational(1, 1))
            setPictureInPictureParams(params.build())
        }
    }

    // Media Session handling

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPause() {
            pausePlaybackImmediately("media-session")
        }
        override fun onPlay() {
            if (activityIsForeground)
                resumePlaybackWithFrameGate("media-session")
            else
                player.paused = false
        }
        override fun onSeekTo(pos: Long) {
            player.timePos = (pos / 1000.0)
        }
        override fun onSkipToNext() = playlistNext()
        override fun onSkipToPrevious() = playlistPrev()
        override fun onSetRepeatMode(repeatMode: Int) {
            MPVLib.setPropertyString("loop-playlist",
                if (repeatMode == PlaybackStateCompat.REPEAT_MODE_ALL) "inf" else "no")
            MPVLib.setPropertyString("loop-file",
                if (repeatMode == PlaybackStateCompat.REPEAT_MODE_ONE) "inf" else "no")
        }
        override fun onSetShuffleMode(shuffleMode: Int) {
            player.changeShuffle(false, shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL)
        }
    }

    private fun initMediaSession(): MediaSessionCompat {
        /*
            https://developer.android.com/guide/topics/media-apps/working-with-a-media-session
            https://developer.android.com/guide/topics/media-apps/audio-app/mediasession-callbacks
            https://developer.android.com/reference/android/support/v4/media/session/MediaSessionCompat
         */
        val session = MediaSessionCompat(this, TAG)
        session.setFlags(0)
        session.setCallback(mediaSessionCallback)
        return session
    }

    private fun updateMediaSession() {
        synchronized (psc) {
            mediaSession?.let { psc.write(it) }
        }
    }

    // mpv events

    private fun eventPropertyUi(property: String, dummy: Any?, metaUpdated: Boolean) {
        if (!activityIsForeground) return
        when (property) {
            "track-list" -> player.loadTracks()
            "current-tracks/audio/selected", "current-tracks/video/image" -> updateAudioUI()
            "hwdec-current" -> updateDecoderButton()
        }
        if (metaUpdated)
            updateMetadataDisplay()
    }

    private fun eventPropertyUi(property: String, value: Boolean) {
        if (!activityIsForeground) return
        when (property) {
            // During scrub mpv is intentionally paused, but the controls represent the user's
            // desired state after the authoritative seek finishes.
            "pause" -> updatePlaybackStatus(
                if (resumeFrameGatePending) true else (scrubPlaybackPaused ?: value)
            )
            "mute" -> { // indirectly from updateAudioPresence()
                updateAudioUI()
            }
        }
    }

    private fun eventPropertyUi(property: String, value: Long) {
        if (!activityIsForeground) return
        when (property) {
            "playlist-pos", "playlist-count" -> {
                updatePlaylistButtons()
                prefetchAdjacentPlaylistOrientations()
            }
            "video-params/w", "video-params/h" -> {
                updateOrientation()
                syncZoomVideoGeometry()
                prepareZoomSurfaceWhenReady()
            }
        }
    }

    private fun eventPropertyUi(property: String, value: Double) {
        if (!activityIsForeground) return
        when (property) {
            "time-pos" -> updatePlaybackPos(psc.positionSec)
            "duration/full" -> updatePlaybackDuration(psc.durationSec)
            "video-params/aspect", "video-params/rotate" -> {
                updateOrientation()
                updatePiPParams()
                syncZoomVideoGeometry()
                prepareZoomSurfaceWhenReady()
            }
            "panscan" -> {
                syncZoomVideoGeometry()
                prepareZoomSurfaceWhenReady()
            }
        }
    }

    private fun eventPropertyUi(property: String, value: String, metaUpdated: Boolean) {
        if (!activityIsForeground) return
        when (property) {
            "speed" -> updateSpeedButton()
            "video-aspect-override" -> {
                syncZoomVideoGeometry()
                prepareZoomSurfaceWhenReady()
            }
        }
        if (metaUpdated)
            updateMetadataDisplay()
    }

    private fun eventUi(eventId: Int) {
        if (!activityIsForeground) return
        // empty
    }

    override fun eventProperty(property: String) {
        val metaUpdated = psc.update(property)
        if (metaUpdated)
            updateMediaSession()
        if (property == "track-list")
            retryPendingSubtitleRestore()
        if (property == "loop-file" || property == "loop-playlist") {
            mediaSession?.setRepeatMode(when (player.getRepeat()) {
                2 -> PlaybackStateCompat.REPEAT_MODE_ONE
                1 -> PlaybackStateCompat.REPEAT_MODE_ALL
                else -> PlaybackStateCompat.REPEAT_MODE_NONE
            })
        } else if (property == "current-tracks/audio/selected") {
            updateAudioPresence()
        }

        if (property == "pause" || property == "current-tracks/audio/selected")
            handleAudioFocus()

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, null, metaUpdated) }
    }

    override fun eventProperty(property: String, value: Boolean) {
        if (property == "seeking") {
            // Property callbacks arrive on mpv's event thread. Read the final high-resolution
            // position there so the Android main thread never blocks on mpv_get_property.
            val playbackTime = if (!value) readScrubPlaybackTimeFromMpv() else null
            eventUiHandler.post { handleScrubSeeking(value, playbackTime) }
        }

        val metaUpdated = psc.update(property, value)
        if (metaUpdated)
            updateMediaSession()
        if (property == "shuffle") {
            mediaSession?.setShuffleMode(if (value)
                PlaybackStateCompat.SHUFFLE_MODE_ALL
            else
                PlaybackStateCompat.SHUFFLE_MODE_NONE)
        } else if (property == "mute") {
            updateAudioPresence()
        } else if (property == "eof-reached" && value) {
            try {
                val mediaPath = MPVLib.getPropertyString("path")
                if (fileStatePersistenceEnabled()) {
                    val completedPath = currentWatchLaterPath ?: mediaPath
                    completedWatchLaterPath = completedPath
                    // Snapshot what is still available, then remove watch-later rather than
                    // rewriting it during mpv's asynchronous file-unload window.
                    rememberActiveTrackSelectionsForCurrentFile()
                    completedPath?.let {
                        player.persistCurrentFileStateWithoutPosition(it)
                    }
                } else if (mediaPath != null) {
                    discardPersistedFileState(mediaPath)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "failed to finalize completed file state", e)
            }
        }

        if (metaUpdated || property == "mute")
            handleAudioFocus()

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, value) }
    }

    override fun eventProperty(property: String, value: Long) {
        if (psc.update(property, value))
            updateMediaSession()

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, value) }
    }

    override fun eventProperty(property: String, value: Double) {
        // A double time-pos preserves the fraction that INT64 used to truncate. Dispatch UI and
        // media-session work only when its rounded whole-second value changes, retaining
        // once-per-second downstream updates while keeping displayed time consistent after seeking.
        val previousPositionSec = if (property == "time-pos") {
            if (psc.position < 0L) null else psc.positionSec
        } else {
            null
        }
        val cacheUpdated = psc.update(property, value)
        val roundedPositionChanged = property != "time-pos" ||
                (cacheUpdated && (previousPositionSec == null || previousPositionSec != psc.positionSec))

        if (cacheUpdated && roundedPositionChanged)
            updateMediaSession()

        if (!activityIsForeground || !roundedPositionChanged) return
        eventUiHandler.post { eventPropertyUi(property, value) }
    }

    override fun eventProperty(property: String, value: String) {
        val metaUpdated = psc.update(property, value)
        if (metaUpdated)
            updateMediaSession()

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, value, metaUpdated) }
    }

    override fun eventEndFile(reachedEof: Boolean) {
        val endedPath = currentWatchLaterPath
        currentWatchLaterPath = null

        if (reachedEof && endedPath != null) {
            completedWatchLaterPath = endedPath
            if (fileStatePersistenceEnabled()) {
                // Repeat the explicit-path deletion at END_FILE. This is intentionally
                // idempotent and covers cases where the eof-reached property callback arrived
                // after the file had already become unavailable.
                player.persistCurrentFileStateWithoutPosition(endedPath)
                Log.d(TAG, "reset completed file position and kept its active settings: $endedPath")
            } else {
                // Fallback in case the short-lived eof-reached property transition was missed.
                discardPersistedFileState(endedPath)
                Log.d(TAG, "discarded completed file state: $endedPath")
            }
        }

        event(MpvEvent.MPV_EVENT_END_FILE)
    }

    override fun eventCommandReply(userdata: Long, error: Int) {
        eventUiHandler.post { handleScrubCommandReply(userdata, error) }
    }

    override fun event(eventId: Int) {
        if (eventId == MpvEvent.MPV_EVENT_END_FILE) {
            psc.eof()
            updateMediaSession()
        }

        if (eventId == MpvEvent.MPV_EVENT_SHUTDOWN)
            finishWithResult(if (playbackHasStarted) RESULT_OK else RESULT_CANCELED)

        if (eventId == MpvEvent.MPV_EVENT_SEEK ||
            eventId == MpvEvent.MPV_EVENT_PLAYBACK_RESTART
        ) {
            // EventObserver callbacks run on mpv's event thread. Read playback-time here and
            // serialize only the lightweight state transition on Android's main thread.
            val playbackTime = if (eventId == MpvEvent.MPV_EVENT_PLAYBACK_RESTART)
                readScrubPlaybackTimeFromMpv()
            else
                null
            eventUiHandler.post { handleScrubMpvEvent(eventId, playbackTime) }
        }

        if (eventId == MpvEvent.MPV_EVENT_FILE_LOADED) {
            currentWatchLaterPath = MPVLib.getPropertyString("path")
            completedWatchLaterPath = null
            val persistFileState = fileStatePersistenceEnabled()
            player.configureFileStatePersistence(persistFileState)

            if (persistFileState) {
                // Restore the chosen audio before playback options and subtitle selection.
                try { restoreAudioSelectionForCurrentFile() } catch (_: Throwable) {}

                // These app snapshots survive deletion of watch-later at natural EOF, so only
                // `start` is reset while gamma, delays and the other controls are restored.
                try { player.restoreCurrentFilePlaybackOptions() } catch (_: Throwable) {}
                // Capture values that may have come from an older watch-later file. This also
                // migrates existing installs before that file is removed at natural EOF.
                try { player.persistCurrentPlaybackOptions() } catch (_: Throwable) {}

                // Track IDs and the external track list are authoritative only after FILE_LOADED.
                // Restore both subtitle slots now so a previous file or mpv's automatic selection
                // cannot swap primary and secondary while the new file is still being initialized.
                val subtitleRestoreComplete = try {
                    restoreSubtitleSelectionForCurrentFile()
                } catch (_: Throwable) {
                    false
                }
                if (subtitleRestoreComplete)
                    try { rememberActiveTrackSelectionsForCurrentFile() } catch (_: Throwable) {}
                else
                    try { rememberAudioSelectionForCurrentFile() } catch (_: Throwable) {}
            } else {
                // resume-playback was disabled before loading, so this removes any old state
                // without first applying it to the current session.
                currentWatchLaterPath?.let {
                    try { discardPersistedFileState(it) } catch (_: Throwable) {}
                }
            }

            eventUiHandler.post {
                mediaGeometryReadyForOrientation = true
                updateOrientation()
                prepareZoomSurfaceWhenReady()
                prefetchAdjacentPlaylistOrientations()
            }
        }

        if (eventId == MpvEvent.MPV_EVENT_VIDEO_RECONFIG) {
            eventUiHandler.post {
                updateOrientation()
                prepareZoomSurfaceWhenReady()
            }
        }

        if (eventId == MpvEvent.MPV_EVENT_START_FILE) {
            currentWatchLaterPath = null
            completedWatchLaterPath = null
            clearPendingSubtitleRestore()

            eventUiHandler.postAtFrontOfQueue {
                mediaGeometryReadyForOrientation = false
                resetScrubSeekControllerForFileChange()
                resetZoomForNewFile()

                // For auto-advanced or externally modified playlists, use a prefetched result
                // when available. Otherwise start a non-blocking probe while mpv's video-params
                // callbacks remain the final fallback.
                val path = MPVLib.getPropertyString("path")
                val cached = path?.let(::cachedOrientation)
                if (cached != null) {
                    requestedOrientationForMedia(cached)?.let(::requestOrientationIfNeeded)
                } else if (path != null) {
                    runWithMediaOrientation(path) { /* orientation only */ }
                }
            }
            try {
                MPVLib.setPropertyDouble("video-zoom", 0.0)
                MPVLib.setPropertyDouble("video-pan-x", 0.0)
                MPVLib.setPropertyDouble("video-pan-y", 0.0)
            } catch (_: Throwable) {
                // ignore
            }

            val cmds = onloadCommands.toTypedArray()
            onloadCommands.clear()
            for (c in cmds)
                MPVLib.command(c)

            if (this.statsLuaMode > 0 && !playbackHasStarted) {
                MPVLib.command(arrayOf("script-binding", "stats/display-page-${this.statsLuaMode}-toggle"))
            }

            playbackHasStarted = true
        }

        if (!activityIsForeground) return
        eventUiHandler.post { eventUi(eventId) }
    }


    // --- Scrub seek helpers ---
    private fun resetScrubSeekControllerForFileChange() {
        cancelResumeFrameGate("file-change", keepPaused = false)
        val desiredPlaybackPaused = scrubPlaybackPaused
        invalidateGestureStableTargetCheck()
        invalidateSeekbarStableTargetCheck()
        scrubSeekHandler.removeCallbacks(scrubFrameGraceRunnable)
        scrubSeekHandler.removeCallbacks(scrubHardTimeoutRunnable)
        activeScrubSeek = null
        scrubSeekInFlight = false
        scrubPlaybackPaused = null
        if (desiredPlaybackPaused != null) {
            player.paused = desiredPlaybackPaused
            updatePlaybackStatus(desiredPlaybackPaused)
        }
        mpvSeeking = false
        latestPlaybackTimeSec = Double.NaN
        gestureScrubActive = false
        pendingGestureSeekSec = null
        lastIssuedGestureSeekSec = null
        seekbarScrubActive = false
        userIsOperatingSeekbar = false
        pendingSeekbarSeekPos = null
        lastIssuedSeekbarSeekPos = null
    }

    // We keep the frame frozen while the finger is moving, then issue a throttled seek on
    // idle/release. Only the latest request is authoritative; stale native callbacks are ignored.
    private fun beginScrubPlaybackHold() {
        if (scrubPlaybackPaused != null)
            return

        cancelResumeFrameGate("scrub-begin", keepPaused = true)
        val wasPaused = psc.pause
        scrubPlaybackPaused = wasPaused
        updatePlaybackStatus(wasPaused)
        if (!wasPaused)
            player.paused = true
    }

    private fun finishScrubPlaybackHoldIfReady() {
        if (scrubSeekInFlight || gestureScrubActive || seekbarScrubActive)
            return

        val playbackPaused = scrubPlaybackPaused ?: return
        scrubPlaybackPaused = null
        player.paused = playbackPaused
        // Setting the same mpv value does not necessarily emit a property event.
        updatePlaybackStatus(playbackPaused)
    }

    private fun pausePlaybackImmediately(source: String) {
        cancelResumeFrameGate("pause:$source", keepPaused = true)
        player.paused = true
        Log.v(TAG, "pause gate: paused from $source at surfaceSerial=$playerSurfaceFrameSerial")
    }

    private fun hasSteppableVideoForResume(): Boolean {
        if (player.getVideoPixelSize() == null)
            return false
        val image = MPVLib.getPropertyString("current-tracks/video/image")
        return image != "yes"
    }

    private fun resumePlaybackWithFrameGate(source: String) {
        if (resumeFrameGatePending)
            return

        // Audio-only/static-image playback has no next TextureView video frame to wait for.
        // The successful AudioTrack hardware-pause path can resume it directly.
        if (!hasSteppableVideoForResume()) {
            Log.v(TAG, "resume gate: $source has no steppable video; resuming directly")
            player.paused = false
            return
        }

        // Keep the user's paused state established first. The mpv-android-only
        // frame-step video-only mode then lets the video core render exactly one
        // frame while AO/AudioTrack remains hardware-paused. Unlike frame-step
        // mute, no audio samples are consumed during this priming frame.
        player.paused = true
        val generation = ++resumeFrameGateGeneration
        resumeFrameGatePending = true
        resumeFrameGateReleasePosted = false
        resumeFrameGateFrameFloor = playerSurfaceFrameSerial
        resumeFrameGateStartedAtMs = SystemClock.uptimeMillis()
        Log.v(TAG,
            "resume gate: priming one video-only frame from $source " +
            "generation=$generation floor=$resumeFrameGateFrameFloor time=${player.timePos}")

        try {
            MPVLib.command(arrayOf("frame-step", "1", "video-only"))
        } catch (error: Throwable) {
            Log.e(TAG, "resume gate: video-only frame-step failed; resuming directly", error)
            finishResumeFrameGate("frame-step-error")
            return
        }
        resumeFrameGateHandler.removeCallbacks(resumeFrameGateTimeoutRunnable)
        resumeFrameGateHandler.postDelayed(
            resumeFrameGateTimeoutRunnable,
            RESUME_FRAME_GATE_TIMEOUT_MS
        )
    }

    private fun onResumeGateSurfaceFrameAvailable(serial: Long) {
        if (!resumeFrameGatePending || resumeFrameGateReleasePosted ||
            serial <= resumeFrameGateFrameFloor)
            return

        resumeFrameGateReleasePosted = true
        val generation = resumeFrameGateGeneration
        val elapsed = SystemClock.uptimeMillis() - resumeFrameGateStartedAtMs
        Log.v(TAG,
            "resume gate: primed frame reached TextureView serial=$serial " +
            "generation=$generation after ${elapsed}ms")

        // onSurfaceTextureUpdated means Android has latched the new texture into
        // this TextureView. Defer one UI animation turn before releasing continuous
        // playback, and guard the callback so a cancelled old gate cannot release a
        // newer one.
        player.postOnAnimation {
            if (resumeFrameGatePending && generation == resumeFrameGateGeneration)
                finishResumeFrameGate("texture-visible")
        }
    }

    private fun finishResumeFrameGate(reason: String) {
        if (!resumeFrameGatePending)
            return
        val generation = resumeFrameGateGeneration
        resumeFrameGatePending = false
        resumeFrameGateReleasePosted = false
        resumeFrameGateHandler.removeCallbacks(resumeFrameGateTimeoutRunnable)
        val elapsed = SystemClock.uptimeMillis() - resumeFrameGateStartedAtMs
        Log.v(TAG,
            "resume gate: releasing playback reason=$reason generation=$generation " +
            "elapsed=${elapsed}ms surfaceSerial=$playerSurfaceFrameSerial")

        // If the timeout fired while the one-frame step was still running, forcing
        // pause first cancels step_frames and clears mpv's audio-hold flag. If the
        // step already completed this is a harmless no-op. The following unpause is
        // therefore always a clean synchronized audio+video resume.
        player.paused = true
        player.paused = false
        resumeFrameGateGeneration += 1L
    }

    private fun cancelResumeFrameGate(reason: String, keepPaused: Boolean) {
        if (!resumeFrameGatePending)
            return
        val generation = resumeFrameGateGeneration
        resumeFrameGatePending = false
        resumeFrameGateReleasePosted = false
        resumeFrameGateHandler.removeCallbacks(resumeFrameGateTimeoutRunnable)
        resumeFrameGateGeneration += 1L
        Log.v(TAG, "resume gate: cancelled reason=$reason generation=$generation")

        // Also terminates an in-flight video-only frame-step and releases its
        // audio-hold state inside mpv. keepPaused=true is the normal cancellation
        // path (second toggle, activity pause, scrub).
        player.paused = true
        if (!keepPaused)
            player.paused = false
    }

    private fun togglePlaybackPauseFromUi() {
        val playbackPaused = scrubPlaybackPaused
        if (playbackPaused == null) {
            // If a second toggle arrives while the one-frame resume prime is in progress,
            // interpret it as Pause rather than allowing two overlapping frame-step commands.
            if (resumeFrameGatePending) {
                cancelResumeFrameGate("second-toggle", keepPaused = true)
                return
            }

            val isPaused = player.paused ?: psc.pause
            if (isPaused)
                resumePlaybackWithFrameGate("ui")
            else
                pausePlaybackImmediately("ui")
            return
        }

        // Record the user's desired post-seek state, but keep mpv physically paused until the
        // newest exact seek has produced its frame. Decoding while playback runs makes heavy
        // long-GOP HEVC seeks slower and can briefly expose an intermediate frame.
        cancelResumeFrameGate("scrub-toggle", keepPaused = true)
        val newPlaybackPaused = !playbackPaused
        scrubPlaybackPaused = newPlaybackPaused
        player.paused = true
        updatePlaybackStatus(newPlaybackPaused)
    }

    private fun invalidateGestureStableTargetCheck() {
        gestureStableSeekRunnable?.let(scrubSeekHandler::removeCallbacks)
        gestureStableSeekRunnable = null
    }

    private fun invalidateSeekbarStableTargetCheck() {
        seekbarStableSeekRunnable?.let(scrubSeekHandler::removeCallbacks)
        seekbarStableSeekRunnable = null
    }

    private fun scheduleGestureStableTargetSeek() {
        val target = pendingGestureSeekSec ?: return
        invalidateGestureStableTargetCheck()

        val runnable = Runnable {
            gestureStableSeekRunnable = null
            val currentTarget = pendingGestureSeekSec
            if (!gestureScrubActive || currentTarget == null || !sameSeekTarget(currentTarget, target))
                return@Runnable
            performGestureIdleSeek()
        }
        gestureStableSeekRunnable = runnable
        scrubSeekHandler.postDelayed(runnable, SCRUB_TARGET_STABLE_MS)
    }

    private fun scheduleSeekbarStableTargetSeek() {
        val target = pendingSeekbarSeekPos ?: return
        invalidateSeekbarStableTargetCheck()

        val runnable = Runnable {
            seekbarStableSeekRunnable = null
            if (!seekbarScrubActive)
                return@Runnable
            val currentTarget = pendingSeekbarSeekPos ?: return@Runnable
            if (!sameSeekTarget(currentTarget, target))
                return@Runnable
            performSeekbarIdleSeek()
        }
        seekbarStableSeekRunnable = runnable
        scrubSeekHandler.postDelayed(runnable, SCRUB_TARGET_STABLE_MS)
    }

    private fun sameSeekTarget(a: Double, b: Double): Boolean =
        abs(a - b) <= SCRUB_TARGET_COMPARE_EPSILON_SEC

    private fun hasAuthoritativeScrubSeek(targetSec: Double, exact: Boolean): Boolean {
        val request = activeScrubSeek ?: return false
        return !request.superseded && request.exact == exact &&
                sameSeekTarget(request.targetSec, targetSec)
    }

    private fun seekbarTargetAlreadyResolved(targetSec: Double): Boolean {
        if (activeScrubSeek != null)
            return hasAuthoritativeScrubSeek(targetSec, exact = true)
        return lastIssuedSeekbarSeekPos?.let { sameSeekTarget(it, targetSec) } == true
    }

    private fun gestureTargetAlreadyResolved(targetSec: Double, exact: Boolean): Boolean {
        if (activeScrubSeek != null)
            return hasAuthoritativeScrubSeek(targetSec, exact)
        return lastIssuedGestureSeekSec?.let { sameSeekTarget(it, targetSec) } == true
    }

    private fun clearLastIssuedTarget(request: ScrubSeekRequest) {
        val seekbarTarget = lastIssuedSeekbarSeekPos
        if (seekbarTarget != null && sameSeekTarget(seekbarTarget, request.targetSec))
            lastIssuedSeekbarSeekPos = null

        val gestureTarget = lastIssuedGestureSeekSec
        if (gestureTarget != null && sameSeekTarget(gestureTarget, request.targetSec))
            lastIssuedGestureSeekSec = null
    }

    private fun supersedeActiveScrubSeekIfTargetChanged(targetSec: Double, exact: Boolean) {
        val request = activeScrubSeek ?: return
        if (request.exact == exact && sameSeekTarget(request.targetSec, targetSec))
            return

        // mpv already coalesces queued seeks internally. Do not pretend mpv_abort_async_command
        // stopped decoder work; simply revoke the old request's authority over UI/playback state.
        request.superseded = true
        scrubSeekHandler.removeCallbacks(scrubFrameGraceRunnable)
        request.frameGraceScheduled = false
    }

    private fun nextScrubUserdata(): Long {
        var userdata = scrubAsyncCounter++
        if (userdata == 0L) {
            userdata = scrubAsyncCounter++
        }
        return userdata
    }

    private fun sendScrubSeek(targetSec: Double, exact: Boolean): Boolean {
        val mode = if (exact) "absolute+exact" else "absolute+keyframes"
        return queueScrubSeek(targetSec, exact, targetSec, mode)
    }

    private fun sendGestureScrubSeek(targetSec: Double): Boolean {
        if (smoothSeekGesture)
            return sendScrubSeek(targetSec, exact = true)

        val direction = targetSec.compareTo(initialSeek.toDouble())
        if (direction == 0)
            return sendScrubSeek(targetSec, exact = false)

        // An absolute keyframe seek always rounds backwards. First align playback with the
        // keyframe that owns the gesture's starting position; after that request settles,
        // complete the seek relatively so mpv can honor the requested direction. In particular,
        // clamping the second stage just past this anchor makes even +1/-1 select the adjacent
        // next/previous keyframe instead of resolving to the same GOP anchor.
        return queueScrubSeek(
            targetSec = targetSec,
            exact = false,
            commandValueSec = initialSeek.toDouble(),
            commandMode = "absolute+keyframes",
            directionalKeyframeDirection = direction
        )
    }

    private fun queueScrubSeek(
        targetSec: Double,
        exact: Boolean,
        commandValueSec: Double,
        commandMode: String,
        directionalKeyframeDirection: Int = 0
    ): Boolean {
        supersedeActiveScrubSeekIfTargetChanged(targetSec, exact)

        val request = ScrubSeekRequest(
            userdata = nextScrubUserdata(),
            generation = ++scrubSeekGeneration,
            targetSec = targetSec,
            exact = exact,
            commandValueSec = commandValueSec,
            directionalKeyframeDirection = directionalKeyframeDirection,
            issuedAtMs = SystemClock.uptimeMillis(),
            frameFloor = playerSurfaceFrameSerial
        )

        // Install the request before entering JNI. A very fast COMMAND_REPLY is posted back to the
        // same main looper and will therefore still see this request after commandAsync returns.
        activeScrubSeek = request
        latestPlaybackTimeSec = Double.NaN
        scrubSeekInFlight = true
        scrubSeekHandler.removeCallbacks(scrubFrameGraceRunnable)
        scrubSeekHandler.removeCallbacks(scrubHardTimeoutRunnable)
        scrubSeekHandler.postDelayed(scrubHardTimeoutRunnable, SCRUB_SEEK_HARD_TIMEOUT_MS)

        val result = try {
            MPVLib.commandAsync(
                arrayOf("seek", commandValueSec.toString(), commandMode),
                request.userdata
            )
        } catch (error: Throwable) {
            Log.e(TAG, "failed to queue scrub seek generation ${request.generation}", error)
            failActiveScrubSeek(request, Int.MIN_VALUE)
            return false
        }

        if (result < 0) {
            Log.w(TAG, "mpv rejected scrub seek generation ${request.generation}: $result")
            failActiveScrubSeek(request, result)
            return false
        }
        return true
    }

    private fun handleScrubCommandReply(userdata: Long, error: Int) {
        if (isDestroyed)
            return
        val request = activeScrubSeek ?: return
        if (request.userdata != userdata)
            return

        request.commandReplyReceived = true
        request.commandError = error
        if (error < 0) {
            Log.w(TAG, "scrub seek generation ${request.generation} failed: $error")
            failActiveScrubSeek(request, error)
            return
        }
        maybeFinishActiveScrubSeek(request)
    }

    private fun handleScrubMpvEvent(eventId: Int, playbackTime: Double?) {
        if (isDestroyed)
            return
        val request = activeScrubSeek ?: return
        when (eventId) {
            MpvEvent.MPV_EVENT_SEEK -> request.seekEventSeen = true
            MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                request.playbackRestartSeen = true
                applyScrubPlaybackTime(request, playbackTime)
            }
        }
        maybeFinishActiveScrubSeek(request)
    }

    private fun handleScrubSeeking(value: Boolean, playbackTime: Double?) {
        if (isDestroyed)
            return
        mpvSeeking = value
        val request = activeScrubSeek ?: return
        if (!value)
            applyScrubPlaybackTime(request, playbackTime)
        maybeFinishActiveScrubSeek(request)
    }

    private fun readScrubPlaybackTimeFromMpv(): Double? = try {
        MPVLib.getPropertyDouble("playback-time")
            ?: MPVLib.getPropertyDouble("time-pos")
    } catch (_: Throwable) {
        null
    }

    private fun applyScrubPlaybackTime(request: ScrubSeekRequest, value: Double?) {
        if (activeScrubSeek !== request || value == null)
            return

        latestPlaybackTimeSec = value
        if (request.exact) {
            val distance = abs(value - request.targetSec)
            if (distance <= SCRUB_TARGET_NEAR_TOLERANCE_SEC)
                request.targetPositionNear = true
            if (distance <= SCRUB_TARGET_REACHED_TOLERANCE_SEC)
                request.targetPositionSeen = true
        }
    }

    private fun refreshScrubPlaybackTimeFromMpv(request: ScrubSeekRequest) {
        applyScrubPlaybackTime(request, readScrubPlaybackTimeFromMpv())
    }

    private fun onScrubSurfaceFrameAvailable(serial: Long) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            eventUiHandler.post { onScrubSurfaceFrameAvailable(serial) }
            return
        }
        if (isDestroyed)
            return

        val request = activeScrubSeek ?: return
        if (serial > request.frameFloor)
            request.frameSeen = true
        maybeFinishActiveScrubSeek(request)
    }

    private fun maybeFinishActiveScrubSeek(request: ScrubSeekRequest) {
        if (activeScrubSeek !== request || request.superseded)
            return
        if (request.commandReplyReceived && request.commandError < 0) {
            failActiveScrubSeek(request, request.commandError)
            return
        }
        if (!request.commandReplyReceived || !request.seekEventSeen ||
            !request.playbackRestartSeen || mpvSeeking
        ) return

        // The anchor stage is internal: once mpv has settled on its keyframe, immediately queue
        // the visible directional stage instead of waiting for the anchor frame to be rendered.
        if (request.directionalKeyframeDirection != 0) {
            completeActiveScrubSeek(request, "keyframe-anchor")
            return
        }

        if (request.targetPositionSeen && request.frameSeen) {
            completeActiveScrubSeek(request, "frame")
            return
        }

        // Audio-only files, static images and low-frame-rate or timestamp-clamped video may not
        // satisfy both strict checks immediately. Re-read playback-time after a short grace. The
        // wider "near" window is accepted only with a real post-request frame and a settled seek.
        val canUseGrace = request.targetPositionSeen ||
                (request.targetPositionNear && request.frameSeen)
        if (canUseGrace && !request.frameGraceScheduled) {
            request.frameGraceScheduled = true
            scrubSeekHandler.postDelayed(scrubFrameGraceRunnable, SCRUB_FRAME_GRACE_MS)
        }
    }

    private fun finishScrubSeekAfterFrameGrace() {
        val request = activeScrubSeek ?: return
        request.frameGraceScheduled = false
        if (!request.targetPositionSeen)
            refreshScrubPlaybackTimeFromMpv(request)
        if (request.superseded || !request.commandReplyReceived || !request.seekEventSeen ||
            !request.playbackRestartSeen || mpvSeeking
        ) return

        if (request.targetPositionSeen || (request.targetPositionNear && request.frameSeen))
            completeActiveScrubSeek(request, "restart-target")
    }

    private fun finishScrubSeekAfterHardTimeout() {
        val request = activeScrubSeek ?: return
        Log.w(
            TAG,
            "scrub seek generation ${request.generation} timed out at target " +
                    "${request.targetSec}; last playback-time=$latestPlaybackTimeSec"
        )
        completeActiveScrubSeek(request, "timeout")
    }

    private fun completeActiveScrubSeek(request: ScrubSeekRequest, reason: String) {
        if (activeScrubSeek !== request)
            return

        val continueDirectionalKeyframeSeek = request.directionalKeyframeDirection != 0 &&
                !request.superseded && reason != "timeout"
        val keyframeAnchorSec = latestPlaybackTimeSec.takeIf { it.isFinite() }
            ?: readScrubPlaybackTimeFromMpv()
            ?: request.commandValueSec

        scrubSeekHandler.removeCallbacks(scrubFrameGraceRunnable)
        scrubSeekHandler.removeCallbacks(scrubHardTimeoutRunnable)
        activeScrubSeek = null
        scrubSeekInFlight = false

        if (request.superseded || reason == "timeout")
            clearLastIssuedTarget(request)

        if (request.exact && !request.superseded && reason != "timeout") {
            val elapsed = (SystemClock.uptimeMillis() - request.issuedAtMs).coerceAtLeast(1L)
            Log.v(
                TAG,
                "exact scrub seek generation ${request.generation} completed by $reason in ${elapsed}ms"
            )
        }

        if (continueDirectionalKeyframeSeek) {
            val relativeAmountSec = (request.targetSec - keyframeAnchorSec).let { amount ->
                if (request.directionalKeyframeDirection > 0)
                    amount.coerceAtLeast(KEYFRAME_DIRECTION_EPSILON_SEC)
                else
                    amount.coerceAtMost(-KEYFRAME_DIRECTION_EPSILON_SEC)
            }
            if (queueScrubSeek(
                    targetSec = request.targetSec,
                    exact = false,
                    commandValueSec = relativeAmountSec,
                    commandMode = "relative+keyframes"
                )
            ) return
        }

        finishScrubPlaybackHoldIfReady()
    }

    private fun failActiveScrubSeek(request: ScrubSeekRequest, error: Int) {
        if (activeScrubSeek !== request)
            return

        scrubSeekHandler.removeCallbacks(scrubFrameGraceRunnable)
        scrubSeekHandler.removeCallbacks(scrubHardTimeoutRunnable)
        activeScrubSeek = null
        scrubSeekInFlight = false

        clearLastIssuedTarget(request)

        Log.w(TAG, "scrub seek generation ${request.generation} ended with error $error")
        finishScrubPlaybackHoldIfReady()
    }

    private fun performGestureIdleSeek() {
        if (!gestureScrubActive) return
        val target = pendingGestureSeekSec ?: return
        val exact = smoothSeekGesture
        if (gestureTargetAlreadyResolved(target, exact)) return
        if (sendGestureScrubSeek(target))
            lastIssuedGestureSeekSec = target
    }

    private fun performSeekbarIdleSeek() {
        if (!seekbarScrubActive) return
        val target = pendingSeekbarSeekPos ?: return
        if (seekbarTargetAlreadyResolved(target)) return
        if (sendScrubSeek(target, exact = true))
            lastIssuedSeekbarSeekPos = target
    }

    // Gesture handler

    private var initialSeek = 0f
    private var initialBright = 0f
    private var initialVolume = 0
    private var maxVolume = 0

    // Keeps gesture seeking responsive after hitting either edge of the video.
    // Any drag distance beyond 0/duration is folded into this offset, so the
    // next 1-second movement in the opposite direction immediately leaves the edge.
    private var gestureSeekDeltaOffsetSec = 0

    private fun quantizeGestureSeekDelta(diff: Float): Int {
        val magnitude = abs(diff)

        // Preserve the original +/-00:00 range, then make every non-zero
        // second step 2.5 times as wide as in the original behavior.
        if (magnitude < GESTURE_SEEK_ZERO_HALF_STEP)
            return 0

        val seconds = 1 +
                ((magnitude - GESTURE_SEEK_ZERO_HALF_STEP) /
                        GESTURE_SEEK_NONZERO_STEP_WIDTH).toInt()
        return if (diff < 0f) -seconds else seconds
    }

    private fun fadeGestureText() {
        refreshPlayerOverlay()
        fadeHandler.removeCallbacks(fadeRunnable3)
        binding.gestureTextView.visibility = View.VISIBLE

        fadeHandler.postDelayed(fadeRunnable3, 500L)
    }

    override fun onPropertyChange(p: PropertyChange, diff: Float) {
        val gestureTextView = binding.gestureTextView
        when (p) {
            /* Drag gestures */
            PropertyChange.Init -> {
                mightWantToToggleControls = false
                cancelPendingTapToggle()
                refreshPlayerOverlay()

                initialSeek = (psc.position / 1000f)
                initialBright = Utils.getScreenBrightness(this) ?: 0.5f
                with (audioManager!!) {
                    initialVolume = getStreamVolume(STREAM_TYPE)
                    maxVolume = if (isVolumeFixed)
                        0
                    else
                        getStreamMaxVolume(STREAM_TYPE)
                }
                if (!isPlayingAudio)
                    maxVolume = 0 // disallow volume gesture if no audio
                gestureSeekDeltaOffsetSec = 0

                fadeHandler.removeCallbacks(fadeRunnable3)
                gestureTextView.visibility = View.VISIBLE
                gestureTextView.text = ""
            }
            PropertyChange.Seek -> {
                // disable seeking when duration is unknown
                val duration = (psc.duration / 1000f)
                if (duration == 0f || initialSeek < 0)
                    return

                // Hold playback while seeking, then restore the latest state chosen by the user.
                if (!gestureScrubActive) {
                    // Start of a scrub gesture.
                    gestureScrubActive = true
                    pendingGestureSeekSec = null
                    lastIssuedGestureSeekSec = null
                    invalidateGestureStableTargetCheck()
                    beginScrubPlaybackHold()
                }

                // Quantize to 1 second steps, same as before. Unlike before, the step is applied
                // on top of the gesture's exact starting position instead of a whole-second
                // rounding of it: starting at 1.234 and stepping +1 lands on 2.234, not 2.000.
                // The seek still only ever moves in fixed 1-second increments, it just no longer
                // throws away the sub-second fraction it started from.
                //
                // When the gesture reaches the start/end of the video, absorb any extra drag into
                // an offset. This prevents "overscroll debt": moving 1 second back from the edge
                // should require the same small reverse movement as it does anywhere else in the
                // video. Once a step would carry the target at or past either edge, the target
                // snaps exactly to that edge (0.000 or the full duration) instead of leaving a
                // sub-second remainder sitting just inside it.
                val basePos = initialSeek.toDouble()
                val fullDuration = duration.toDouble()
                val rawDeltaSec = quantizeGestureSeekDelta(diff)
                val minDeltaSec = floor(-basePos).toInt()
                val maxDeltaSec = ceil(fullDuration - basePos).toInt()
                var deltaSec = rawDeltaSec - gestureSeekDeltaOffsetSec

                if (deltaSec > maxDeltaSec) {
                    gestureSeekDeltaOffsetSec = rawDeltaSec - maxDeltaSec
                    deltaSec = maxDeltaSec
                } else if (deltaSec < minDeltaSec) {
                    gestureSeekDeltaOffsetSec = rawDeltaSec - minDeltaSec
                    deltaSec = minDeltaSec
                }

                val newPos = (basePos + deltaSec).coerceIn(0.0, fullDuration)
                val newDiff = deltaSec

                // Stability is defined by the seek value itself, not by whether touch events keep
                // arriving. Events that remain inside the same quantized second do not postpone
                // the seek; only an actual increase/decrease invalidates the current observation.
                val previousTarget = pendingGestureSeekSec
                pendingGestureSeekSec = newPos
                if (previousTarget == null || !sameSeekTarget(previousTarget, newPos)) {
                    supersedeActiveScrubSeekIfTargetChanged(
                        newPos,
                        exact = smoothSeekGesture
                    )
                    scheduleGestureStableTargetSeek()
                }

                // The displayed labels stay whole-second, same as before; only the seek target
                // sent to mpv keeps the sub-second fraction.
                val posText = Utils.prettyTime(newPos.roundToInt())
                val diffText = Utils.prettyTime(newDiff, true)
                gestureTextView.text = getString(R.string.ui_seek_distance, posText, diffText)
            }
            PropertyChange.Volume -> {
                if (maxVolume == 0)
                    return
                val newVolume = (initialVolume + (diff * maxVolume).toInt()).coerceIn(0, maxVolume)
                val newVolumePercent = 100 * newVolume / maxVolume
                audioManager!!.setStreamVolume(STREAM_TYPE, newVolume, 0)

                gestureTextView.text = getString(R.string.ui_volume, newVolumePercent)
            }
            PropertyChange.Bright -> {
                val newBrightPercent = ((initialBright + diff).coerceIn(0f, 1f) * 100).roundToInt()
                lastScreenBrightness = newBrightPercent
                updateScreenBrightness()

                gestureTextView.text = getString(R.string.ui_brightness, newBrightPercent)
            }
            PropertyChange.Finalize -> {
                // End of scrub gesture.
                gestureScrubActive = false
                invalidateGestureStableTargetCheck()

                val target = pendingGestureSeekSec
                if (target != null &&
                    !gestureTargetAlreadyResolved(target, exact = smoothSeekGesture)
                ) {
                    if (sendGestureScrubSeek(target))
                        lastIssuedGestureSeekSec = target
                }

                finishScrubPlaybackHoldIfReady()

                pendingGestureSeekSec = null
                gestureSeekDeltaOffsetSec = 0
                gestureTextView.visibility = View.GONE
            }

            /* Tap gestures */
            PropertyChange.SeekFixed -> {
                // Double-tap seek should not toggle the control UI.
                cancelPendingTapToggle()
                mightWantToToggleControls = false

                val seekTime = diff * 10f
                val newPos = psc.positionSec + seekTime.toInt() // only for display
                MPVLib.command(arrayOf("seek", seekTime.toString(), "relative"))

                val diffText = Utils.prettyTime(seekTime.toInt(), true)
                gestureTextView.text = getString(R.string.ui_seek_distance, Utils.prettyTime(newPos), diffText)
                fadeGestureText()
            }
            PropertyChange.PlayPause -> {
                // Double-tap play/pause should not trigger control UI.
                cancelPendingTapToggle()
                mightWantToToggleControls = false
                togglePlaybackPauseFromUi()
            }
            PropertyChange.Custom -> {
                // Double-tap custom action should not toggle the control UI.
                cancelPendingTapToggle()
                mightWantToToggleControls = false

                val keycode = 0x10002 + diff.toInt()
                MPVLib.command(arrayOf("keypress", "0x%x".format(keycode)))
            }
        }
    }

    companion object {
        private const val TAG = "mpv"
        // how long should controls be displayed on screen (ms)
        private const val CONTROLS_DISPLAY_TIMEOUT = 1500L
        // Android's short toast is roughly two seconds; track changes use one quarter of that.
        private const val TRACK_SWITCH_TOAST_DURATION_MS = 500L
        // Controls fade-in/out durations (ms). Keep them very fast but non-zero to avoid a harsh pop.
        private const val CONTROLS_FADE_IN_DURATION = 80L
        private const val CONTROLS_FADE_OUT_DURATION = 80L
        // Predictive aspect-menu geometry is held briefly so asynchronous mpv
        // property notifications cannot momentarily restore an intermediate state.
        private const val ASPECT_MENU_PREDICTIVE_SYNC_GRACE_MS = 120L

        // Tap timing (must match TouchGestures.TAP_DURATION).
        // - Double-tap gestures: fast window (ms)
        // - Single-tap control toggle: delayed slightly longer so double-tap can cancel it (ms)
        private const val DOUBLE_TAP_TIMEOUT_MS = 225L
        private const val SINGLE_TAP_TOGGLE_DELAY_MS = DOUBLE_TAP_TIMEOUT_MS + 20L
        private const val IMMERSIVE_RESTORE_RETRY_MS = 120L
        private const val MANUAL_SYSTEM_BARS_GESTURE_WINDOW_MS = 2000L

        // The launch probe has no fixed sleep: it returns as soon as local metadata is available.
        // The budget only prevents a cloud-backed/content provider from blocking Activity.onCreate.
        private const val ORIENTATION_PROBE_BUDGET_MS = 400L
        private const val ORIENTATION_ASYNC_PROBE_TIMEOUT_MS = 500L
        private const val ORIENTATION_CACHE_SIZE = 8
        private const val STATE_ENTRY_CONFIG_ORIENTATION = "entry_config_orientation"

        // Reserve the very top portion of the screen for Android system gestures (notification
        // shade/status bar). We only suppress the tap-to-toggle if the finger *moves down*
        // meaningfully from this region.
        private const val STATUS_BAR_DEADZONE_PERCENT = 5f
        private const val STATUS_BAR_SWIPE_CANCEL_DP = 16f
        // fraction to which audio volume is ducked on loss of audio focus
        private const val AUDIO_FOCUS_DUCKING = 0.5f
        // request codes for invoking other activities
        private const val RCODE_EXTERNAL_AUDIO = 1000
        private const val RCODE_EXTERNAL_SUB = 1001
        private const val RCODE_LOAD_FILE = 1002
        // action of result intent
        private const val RESULT_INTENT = "is.xyz.mpv.MPVActivity.result"
        // stream type used with AudioManager
        private const val STREAM_TYPE = AudioManager.STREAM_MUSIC
        // Use 100% of the original zero-step width and 250% of the original
        // non-zero whole-second step width.
        private const val GESTURE_SEEK_ZERO_HALF_STEP = 0.5f
        private const val GESTURE_SEEK_NONZERO_STEP_WIDTH = 2.5f

        // Start preview only after the numeric seek target itself has remained unchanged for this
        // interval. Touch events that still map to the same target do not postpone the seek.
        private const val SCRUB_TARGET_STABLE_MS = 100L
        private const val SCRUB_TARGET_COMPARE_EPSILON_SEC = 0.0005
        private const val SCRUB_TARGET_REACHED_TOLERANCE_SEC = 0.075
        private const val SCRUB_TARGET_NEAR_TOLERANCE_SEC = 0.75
        private const val SCRUB_FRAME_GRACE_MS = 350L
        private const val SCRUB_SEEK_HARD_TIMEOUT_MS = 45_000L
        // One frame should normally arrive in 16-42 ms. This only catches EOF,
        // decoder stalls, or a surface that disappeared during the resume prime.
        private const val RESUME_FRAME_GATE_TIMEOUT_MS = 500L
        private const val KEYFRAME_DIRECTION_EPSILON_SEC = 0.001

        // Per-file subtitle persistence keys
        private const val PREF_SUB_KIND = "sub_kind"
        private const val PREF_SUB_EXTERNAL = "sub_external"
        private const val PREF_SUB_SID = "sub_sid"
        private const val PREF_SUB2_KIND = "sub2_kind"
        private const val PREF_SUB2_EXTERNAL = "sub2_external"
        private const val PREF_SUB2_SID = "sub2_sid"
        private const val PREF_SUB_KIND_EXTERNAL = "external"
        private const val PREF_SUB_KIND_SID = "sid"

        // Per-file audio persistence keys
        private const val PREF_AUD_KIND = "aud_kind"
        private const val PREF_AUD_EXTERNAL = "aud_external"
        private const val PREF_AUD_SID = "aud_sid"
        private const val PREF_AUD_KIND_EXTERNAL = "external"
        private const val PREF_AUD_KIND_SID = "sid"

        private val PER_FILE_SELECTION_KEYS = arrayOf(
            PREF_SUB_KIND,
            PREF_SUB_EXTERNAL,
            PREF_SUB_SID,
            PREF_SUB2_KIND,
            PREF_SUB2_EXTERNAL,
            PREF_SUB2_SID,
            PREF_AUD_KIND,
            PREF_AUD_EXTERNAL,
            PREF_AUD_SID,
        )

    }
}
