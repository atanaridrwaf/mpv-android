package `is`.xyz.mpv

import android.content.Context
import android.os.Build
import android.os.Environment
import android.preference.PreferenceManager
import android.util.AttributeSet
import android.util.Log
import android.view.*
import androidx.core.content.ContextCompat
import `is`.xyz.mpv.MPVLib.MpvFormat.MPV_FORMAT_DOUBLE
import `is`.xyz.mpv.MPVLib.MpvFormat.MPV_FORMAT_FLAG
import `is`.xyz.mpv.MPVLib.MpvFormat.MPV_FORMAT_INT64
import `is`.xyz.mpv.MPVLib.MpvFormat.MPV_FORMAT_NONE
import `is`.xyz.mpv.MPVLib.MpvFormat.MPV_FORMAT_STRING
import java.security.MessageDigest
import kotlin.reflect.KProperty

internal class MPVView(context: Context, attrs: AttributeSet) : BaseMPVView(context, attrs) {
    private var watchLaterOptionsBeforeDisable: String? = null
    private var watchLaterOptionsSuppressed = false

    override fun initOptions() {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val persistFileState = sharedPreferences.getBoolean("save_position", false)

        // apply phone-optimized defaults
        MPVLib.setOptionString("profile", "fast")
        // When Save position on quit is disabled, old watch-later files must not restore
        // positions or any other per-file option before the activity can discard them.
        MPVLib.setOptionString("resume-playback", if (persistFileState) "yes" else "no")

        // vo
        setVo(if (sharedPreferences.getBoolean("gpu_next", false))
            "gpu-next"
        else
            "gpu")

        // hwdec
        val hwdec = if (sharedPreferences.getBoolean("hardware_decoding", true))
            HWDECS
        else
            "no"

        // vo: set display fps as reported by android
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val disp = ContextCompat.getDisplayOrDefault(context)
            val refreshRate = disp.mode.refreshRate

            Log.v(TAG, "Display ${disp.displayId} reports FPS of $refreshRate")
            MPVLib.setOptionString("display-fps-override", refreshRate.toString())
        } else {
            Log.v(TAG, "Android version too old, disabling refresh rate functionality " +
                       "(${Build.VERSION.SDK_INT} < ${Build.VERSION_CODES.M})")
        }

        // set non-complex options
        data class Property(val preferenceName: String, val mpvOption: String)
        val opts = arrayOf(
                Property("default_audio_language", "alang"),
                Property("default_subtitle_language", "slang"),

                // vo-related
                Property("video_scale", "scale"),
                Property("video_scale_param1", "scale-param1"),
                Property("video_scale_param2", "scale-param2"),

                Property("video_downscale", "dscale"),
                Property("video_downscale_param1", "dscale-param1"),
                Property("video_downscale_param2", "dscale-param2"),

                Property("video_tscale", "tscale"),
                Property("video_tscale_param1", "tscale-param1"),
                Property("video_tscale_param2", "tscale-param2")
        )

        for ((preferenceName, mpvOption) in opts) {
            val preference = sharedPreferences.getString(preferenceName, "")
            if (!preference.isNullOrBlank())
                MPVLib.setOptionString(mpvOption, preference)
        }

        val debandMode = sharedPreferences.getString("video_debanding", "")
        if (debandMode == "gradfun") {
            // lower the default radius (16) to improve performance
            MPVLib.setOptionString("vf", "gradfun=radius=12")
        } else if (debandMode == "gpu") {
            MPVLib.setOptionString("deband", "yes")
        }

        val vidsync = sharedPreferences.getString("video_sync", resources.getString(R.string.pref_video_interpolation_sync_default))
        MPVLib.setOptionString("video-sync", vidsync!!)

        if (sharedPreferences.getBoolean("video_interpolation", false))
            MPVLib.setOptionString("interpolation", "yes")

        if (sharedPreferences.getBoolean("gpudebug", false))
            MPVLib.setOptionString("gpu-debug", "yes")

        if (sharedPreferences.getBoolean("video_fastdecode", false)) {
            MPVLib.setOptionString("vd-lavc-fast", "yes")
            MPVLib.setOptionString("vd-lavc-skiploopfilter", "nonkey")
        }

        MPVLib.setOptionString("gpu-context", "android")
        MPVLib.setOptionString("opengl-es", "yes")
        MPVLib.setOptionString("hwdec", hwdec)
        MPVLib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        MPVLib.setOptionString("ao", "audiotrack,opensles")
        MPVLib.setOptionString("audio-set-media-role", "yes")
        MPVLib.setOptionString("tls-verify", "yes")
        MPVLib.setOptionString("tls-ca-file", "${this.context.filesDir.path}/cacert.pem")
        MPVLib.setOptionString("input-default-bindings", "yes")
        // Keep still images open indefinitely instead of letting mpv advance/end them
        // after its default image display timeout.
        MPVLib.setOptionString("image-display-duration", "inf")
        // Limit demuxer cache since the defaults are too high for mobile devices
        val cacheMegs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) 64 else 32
        MPVLib.setOptionString("demuxer-max-bytes", "${cacheMegs * 1024 * 1024}")
        MPVLib.setOptionString("demuxer-max-back-bytes", "${cacheMegs * 1024 * 1024}")
        //
        val screenshotDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        screenshotDir.mkdirs()
        MPVLib.setOptionString("screenshot-directory", screenshotDir.path)
    }

    override fun postInitOptions() {
        // We call write-watch-later manually, including before every explicit playlist jump.
        MPVLib.setOptionString("save-position-on-quit", "no")

        // Runtime option changes normally leak into the next playlist item. Reset every option
        // exposed as a per-video control by this app, including changes made through input.conf.
        mergeStringListProperty("reset-on-next-file", PER_FILE_PLAYBACK_OPTIONS + "start")

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        configureFileStatePersistence(sharedPreferences.getBoolean("save_position", false))
    }

    /**
     * Apply the Save position on quit preference to both reading and writing watch-later data.
     * This is public so a preference change can take effect without recreating libmpv.
     */
    fun configureFileStatePersistence(enabled: Boolean) {
        MPVLib.setPropertyString("resume-playback", if (enabled) "yes" else "no")
        if (enabled) {
            // Restore the complete pre-disable list first. This preserves mpv/user options that
            // are unrelated to the controls managed by this app when the switch is toggled live.
            if (watchLaterOptionsSuppressed) {
                watchLaterOptionsBeforeDisable?.let {
                    MPVLib.setPropertyString("watch-later-options", it)
                }
                watchLaterOptionsBeforeDisable = null
                watchLaterOptionsSuppressed = false
            }

            // Saving is all-or-nothing for this app: position and every app-controlled per-file
            // option are stored together while the preference is enabled.
            //
            // aid/sid/secondary-sid are deliberately excluded here (unlike the full
            // PER_FILE_PLAYBACK_OPTIONS list used for reset-on-next-file above). Those three are
            // restored by MPVActivity instead, because external tracks need their filename
            // resolved, not just a raw numeric id. If mpv's own watch-later mechanism also
            // restores them natively, it does so earlier and blind to filenames: at that point
            // an external track from a previous session doesn't exist yet, so mpv falls back to
            // selecting the track list's default (typically an embedded track) and commits to it.
            // MPVActivity's own restore then re-adds the external file and sets sid/secondary-sid
            // to the same numeric id mpv's option already silently holds from that native
            // restore, so mpv sees no value change and never re-applies the selection - leaving
            // the embedded default active even though the external track is still listed. Letting
            // MPVActivity own this exclusively (as it already does for SharedPreferences-based
            // persistence via APP_PERSISTED_PLAYBACK_OPTIONS) avoids the conflict entirely.
            mergeStringListProperty(
                "watch-later-options",
                APP_PERSISTED_PLAYBACK_OPTIONS + "start",
                remove = setOf("aid", "sid", "secondary-sid")
            )
        } else {
            if (!watchLaterOptionsSuppressed) {
                watchLaterOptionsBeforeDisable =
                    MPVLib.getPropertyString("watch-later-options")
                watchLaterOptionsSuppressed = true
            }

            // An empty list also prevents an input.conf write-watch-later-config command from
            // serializing gamma, delays, tracks or any other app-controlled file state.
            MPVLib.setPropertyString("watch-later-options", "")
        }
    }

    private fun mergeStringListProperty(
        property: String,
        required: Collection<String>,
        remove: Collection<String> = emptyList()
    ) {
        val current = MPVLib.getPropertyString(property)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toMutableList()
            ?: mutableListOf()

        current.removeAll(remove.toSet())
        if (!current.contains("all")) {
            for (option in required) {
                if (!current.contains(option))
                    current.add(option)
            }
        }
        MPVLib.setPropertyString(property, current.joinToString(","))
    }

    /**
     * Set an option only for the currently playing file. `file-local-options` guarantees that the
     * value is restored to the pre-file value when this file unloads.
     */
    fun setFileLocalString(name: String, value: String) {
        MPVLib.setPropertyString("file-local-options/$name", value)
    }

    fun setFileLocalInt(name: String, value: Int) {
        MPVLib.setPropertyInt("file-local-options/$name", value)
    }

    fun setFileLocalDouble(name: String, value: Double) {
        MPVLib.setPropertyDouble("file-local-options/$name", value)
    }

    fun persistCurrentFileState() {
        if (!fileStatePersistenceEnabled())
            return
        persistCurrentPlaybackOptions()
        // During teardown/pathological load failures there may be no writable current file.
        if (MPVLib.getPropertyString("path") != null)
            MPVLib.command(arrayOf("write-watch-later-config"))
    }

    /**
     * Keep this file's playback options while removing its resume position.
     *
     * The Java property callback can run after mpv has started unloading the completed file.
     * Rewriting watch-later at that point is racy: properties such as gamma may already be
     * unavailable, while `start` can survive in an older file. App-controlled options are
     * therefore stored separately as they change, and the watch-later file is deleted outright.
     */
    fun persistCurrentFileStateWithoutPosition(path: String) {
        if (!fileStatePersistenceEnabled())
            return

        // Capture one last snapshot only if the completed file is still current. If it has
        // already unloaded, the snapshots taken when settings changed remain authoritative.
        if (MPVLib.getPropertyString("path") == path)
            persistCurrentPlaybackOptions()

        MPVLib.command(arrayOf("delete-watch-later-config", path))
    }

    /**
     * Save options that must survive a completed file independently of watch-later `start`.
     * Track selections are handled by MPVActivity because external tracks also need filenames.
     */
    fun persistCurrentPlaybackOptions() {
        if (!fileStatePersistenceEnabled())
            return
        val path = MPVLib.getPropertyString("path") ?: return
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)

        with (preferences.edit()) {
            for (option in APP_PERSISTED_PLAYBACK_OPTIONS) {
                val key = perFilePlaybackOptionKey(path, option)
                val value = MPVLib.getPropertyString(option)
                if (value == null)
                    remove(key)
                else
                    putString(key, value)
            }
            commit()
        }
    }

    fun restoreCurrentFilePlaybackOptions() {
        if (!fileStatePersistenceEnabled())
            return
        val path = MPVLib.getPropertyString("path") ?: return
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)

        for (option in APP_PERSISTED_PLAYBACK_OPTIONS) {
            val value = preferences.getString(perFilePlaybackOptionKey(path, option), null)
                ?: continue
            setFileLocalString(option, value)
        }
    }

    fun clearPersistedPlaybackOptions(path: String) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        with (preferences.edit()) {
            for (option in APP_PERSISTED_PLAYBACK_OPTIONS)
                remove(perFilePlaybackOptionKey(path, option))
            commit()
        }
    }

    private fun perFilePlaybackOptionKey(path: String, option: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(path.toByteArray(Charsets.UTF_8))
        val pathHash = buildString(digest.size * 2) {
            for (byte in digest)
                append(String.format("%02x", byte))
        }
        return "perfile_playback_${pathHash}_$option"
    }

    private fun fileStatePersistenceEnabled(): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean("save_position", false)
    }

    fun onPointerEvent(event: MotionEvent): Boolean {
        assert (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER))
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (h > 0)
                MPVLib.command(arrayOf("keypress", "WHEEL_RIGHT", "$h"))
            else if (h < 0)
                MPVLib.command(arrayOf("keypress", "WHEEL_LEFT", "${-h}"))
            if (v > 0)
                MPVLib.command(arrayOf("keypress", "WHEEL_UP", "$v"))
            else if (v < 0)
                MPVLib.command(arrayOf("keypress", "WHEEL_DOWN", "${-v}"))
            return true
        }
        return false
    }

    fun onKey(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_MULTIPLE)
            return false
        if (KeyEvent.isModifierKey(event.keyCode))
            return false

        var mapped = keyMapping[event.keyCode]
        if (mapped == null) {
            // Fallback to produced glyph
            if (!event.isPrintingKey) {
                if (event.repeatCount == 0)
                    Log.d(TAG, "Unmapped non-printable key ${event.keyCode}")
                return false
            }

            val ch = event.unicodeChar
            if (ch.and(KeyCharacterMap.COMBINING_ACCENT) != 0)
                return false // dead key
            mapped = ch.toChar().toString()
        }

        if (event.repeatCount > 0)
            return true // eat event but ignore it, mpv has its own key repeat

        val mod: MutableList<String> = mutableListOf()
        event.isShiftPressed && mod.add("shift")
        event.isCtrlPressed && mod.add("ctrl")
        event.isAltPressed && mod.add("alt")
        event.isMetaPressed && mod.add("meta")

        val action = if (event.action == KeyEvent.ACTION_DOWN) "keydown" else "keyup"
        mod.add(mapped)
        MPVLib.command(arrayOf(action, mod.joinToString("+")))

        return true
    }

    override fun observeProperties() {
        // This observes all properties needed by MPVView, MPVActivity or other classes
        data class Property(val name: String, val format: Int = MPV_FORMAT_NONE)
        val p = arrayOf(
            // Preserve sub-second precision so the elapsed-time label and seekbar can use
            // the same whole-second rounding as scrub targets.
            Property("time-pos", MPV_FORMAT_DOUBLE),
            // Seek state changes rarely and is used by the exact-scrub controller.
            Property("seeking", MPV_FORMAT_FLAG),
            Property("duration/full", MPV_FORMAT_DOUBLE),
            Property("pause", MPV_FORMAT_FLAG),
            Property("paused-for-cache", MPV_FORMAT_FLAG),
            Property("speed", MPV_FORMAT_STRING),
            Property("track-list"),
            Property("video-params/aspect", MPV_FORMAT_DOUBLE),
            Property("video-params/rotate", MPV_FORMAT_DOUBLE),
            Property("video-params/w", MPV_FORMAT_INT64),
            Property("video-params/h", MPV_FORMAT_INT64),
            Property("video-aspect-override", MPV_FORMAT_STRING),
            Property("panscan", MPV_FORMAT_DOUBLE),
            Property("playlist-pos", MPV_FORMAT_INT64),
            Property("playlist-count", MPV_FORMAT_INT64),
            Property("current-tracks/video/image"),
            Property("media-title", MPV_FORMAT_STRING),
            Property("metadata"),
            Property("loop-playlist"),
            Property("loop-file"),
            Property("shuffle", MPV_FORMAT_FLAG),
            Property("eof-reached", MPV_FORMAT_FLAG),
            Property("hwdec-current"),
            Property("mute", MPV_FORMAT_FLAG),
            Property("current-tracks/audio/selected")
        )

        for ((name, format) in p)
            MPVLib.observeProperty(name, format)
    }

    fun addObserver(o: MPVLib.EventObserver) {
        MPVLib.addObserver(o)
    }
    fun removeObserver(o: MPVLib.EventObserver) {
        MPVLib.removeObserver(o)
    }

    data class Track(val mpvId: Int, val name: String)
    var tracks = mapOf<String, MutableList<Track>>(
            "audio" to arrayListOf(),
            "video" to arrayListOf(),
            "sub" to arrayListOf())

    fun loadTracks() {
        for (list in tracks.values) {
            list.clear()
            // pseudo-track to allow disabling audio/subs
            list.add(Track(-1, context.getString(R.string.track_off)))
        }
        val count = MPVLib.getPropertyInt("track-list/count")!!
        // Note that because events are async, properties might disappear at any moment
        // so use ?: continue instead of !!
        for (i in 0 until count) {
            val type = MPVLib.getPropertyString("track-list/$i/type") ?: continue
            if (!tracks.containsKey(type)) {
                Log.w(TAG, "Got unknown track type: $type")
                continue
            }
            val mpvId = MPVLib.getPropertyInt("track-list/$i/id") ?: continue
            val lang = MPVLib.getPropertyString("track-list/$i/lang")
            val title = MPVLib.getPropertyString("track-list/$i/title")

            val trackName = if (!lang.isNullOrEmpty() && !title.isNullOrEmpty())
                context.getString(R.string.ui_track_title_lang, mpvId, title, lang)
            else if (!lang.isNullOrEmpty() || !title.isNullOrEmpty())
                context.getString(R.string.ui_track_text, mpvId, (lang ?: "") + (title ?: ""))
            else
                context.getString(R.string.ui_track, mpvId)
            tracks.getValue(type).add(Track(
                    mpvId=mpvId,
                    name=trackName
            ))
        }
    }

    data class PlaylistItem(val index: Int, val filename: String, val title: String?)

    fun loadPlaylist(): MutableList<PlaylistItem> {
        val playlist = mutableListOf<PlaylistItem>()
        val count = MPVLib.getPropertyInt("playlist-count")!!
        for (i in 0 until count) {
            val filename = MPVLib.getPropertyString("playlist/$i/filename")!!
            val title = MPVLib.getPropertyString("playlist/$i/title")
            playlist.add(PlaylistItem(index=i, filename=filename, title=title))
        }
        return playlist
    }

    data class Chapter(val index: Int, val title: String?, val time: Double)

    fun loadChapters(): MutableList<Chapter> {
        val chapters = mutableListOf<Chapter>()
        val count = MPVLib.getPropertyInt("chapter-list/count")!!
        for (i in 0 until count) {
            val title = MPVLib.getPropertyString("chapter-list/$i/title")
            val time = MPVLib.getPropertyDouble("chapter-list/$i/time")!!
            chapters.add(Chapter(
                    index=i,
                    title=title,
                    time=time
            ))
        }
        return chapters
    }

    // Property getters/setters

    var paused: Boolean?
        get() = MPVLib.getPropertyBoolean("pause")
        set(paused) = MPVLib.setPropertyBoolean("pause", paused!!)

    var timePos: Double?
        get() = MPVLib.getPropertyDouble("time-pos/full")
        set(progress) = MPVLib.setPropertyDouble("time-pos", progress!!)

    /** name of currently active hardware decoder or "no" */
    val hwdecActive: String
        get() = MPVLib.getPropertyString("hwdec-current") ?: "no"

    var playbackSpeed: Double?
        get() = MPVLib.getPropertyDouble("speed")
        set(speed) = setFileLocalDouble("speed", speed!!)

    var subDelay: Double?
        get() = MPVLib.getPropertyDouble("sub-delay")
        set(delay) = setFileLocalDouble("sub-delay", delay!!)

    var secondarySubDelay: Double?
        get() = MPVLib.getPropertyDouble("secondary-sub-delay")
        set(delay) = setFileLocalDouble("secondary-sub-delay", delay!!)

    val estimatedVfFps: Double?
        get() = MPVLib.getPropertyDouble("estimated-vf-fps")

    /**
     * Returns the video's native aspect ratio. Rotation is taken into account.
     */
    fun getVideoAspect(): Double? {
        return MPVLib.getPropertyDouble("video-params/aspect")?.let {
            if (it < 0.001)
                return 0.0
            val rot = MPVLib.getPropertyInt("video-params/rotate") ?: 0
            if (rot % 180 == 90)
                1.0 / it
            else
                it
        }
    }

    /**
     * Returns the aspect ratio that mpv is currently displaying. This includes
     * video-aspect-override values coming either from the in-app aspect menu or
     * from mpv.conf, so the zoom render surface can keep the same geometry as mpv.
     */
    fun getEffectiveVideoAspect(): Double? {
        parseAspectRatio(MPVLib.getPropertyString("video-aspect-override"))?.let {
            return it
        }
        return getVideoAspect()
    }

    fun getPanscan(): Double {
        return MPVLib.getPropertyDouble("panscan") ?: 0.0
    }

    private fun parseAspectRatio(value: String?): Double? {
        val trimmed = value?.trim() ?: return null
        if (trimmed.isEmpty() || trimmed == "-1" || trimmed.equals("no", true))
            return null

        val parts = trimmed.split(':', limit = 2)
        val parsed = if (parts.size == 2) {
            val width = parts[0].toDoubleOrNull()
            val height = parts[1].toDoubleOrNull()
            if (width != null && height != null && height != 0.0)
                width / height
            else
                null
        } else {
            trimmed.toDoubleOrNull()
        }
        return parsed?.takeIf { it > 0.001 }
    }

    fun getVideoPixelSize(): Pair<Int, Int>? {
        val w = MPVLib.getPropertyInt("video-params/w") ?: return null
        val h = MPVLib.getPropertyInt("video-params/h") ?: return null
        if (w <= 0 || h <= 0)
            return null
        val rot = MPVLib.getPropertyInt("video-params/rotate") ?: 0
        return if (rot % 180 == 90) h to w else w to h
    }

    fun setAudioSessionId(id: Int) {
        MPVLib.setPropertyInt("audiotrack-session-id", id)
        MPVLib.setPropertyInt("aaudio-session-id", id)
    }

    inner class TrackDelegate(private val name: String) {
        operator fun getValue(thisRef: Any?, property: KProperty<*>): Int {
            val v = MPVLib.getPropertyString(name)
            // we can get null here for "no" or other invalid value
            return v?.toIntOrNull() ?: -1
        }
        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            if (value == -1)
                setFileLocalString(name, "no")
            else
                setFileLocalInt(name, value)
        }
    }

    var vid: Int by TrackDelegate("vid")
    var sid: Int by TrackDelegate("sid")
    var secondarySid: Int by TrackDelegate("secondary-sid")
    var aid: Int by TrackDelegate("aid")

    // Commands

    fun cyclePause() = MPVLib.command(arrayOf("cycle", "pause"))

    private fun makeCurrentOptionFileLocal(name: String): Boolean {
        val current = MPVLib.getPropertyString(name) ?: return false
        setFileLocalString(name, current)
        return true
    }

    fun cycleAudio() {
        if (makeCurrentOptionFileLocal("aid"))
            MPVLib.command(arrayOf("cycle", "audio"))
    }

    fun cycleSub() {
        if (makeCurrentOptionFileLocal("sid"))
            MPVLib.command(arrayOf("cycle", "sub"))
    }

    fun cycleHwdec() {
        MPVLib.command(arrayOf("cycle-values", "hwdec", HWDECS, "no"))
        persistCurrentFileState()
    }

    fun cycleSpeed() {
        val speeds = arrayOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)
        val currentSpeed = playbackSpeed ?: 1.0
        val index = speeds.indexOfFirst { it > currentSpeed }
        playbackSpeed = speeds[if (index == -1) 0 else index]
        persistCurrentFileState()
    }

    fun getRepeat(): Int {
        return when (MPVLib.getPropertyString("loop-playlist") +
                MPVLib.getPropertyString("loop-file")) {
            "noinf" -> 2
            "infno" -> 1
            else -> 0
        }
    }

    fun cycleRepeat() {
        when (val state = getRepeat()) {
            0, 1 -> {
                MPVLib.setPropertyString("loop-playlist", if (state == 1) "no" else "inf")
                MPVLib.setPropertyString("loop-file", if (state == 1) "inf" else "no")
            }
            2 -> MPVLib.setPropertyString("loop-file", "no")
        }
    }

    fun getShuffle(): Boolean {
        return MPVLib.getPropertyBoolean("shuffle") == true
    }

    fun changeShuffle(cycle: Boolean, value: Boolean = true) {
        // Use the 'shuffle' property to store the shuffled state, since changing
        // it at runtime doesn't do anything.
        val state = getShuffle()
        val newState = if (cycle) state.xor(value) else value
        if (state == newState)
            return
        MPVLib.command(arrayOf(if (newState) "playlist-shuffle" else "playlist-unshuffle"))
        MPVLib.setPropertyBoolean("shuffle", newState)
    }

    companion object {
        private const val TAG = "mpv"

        // Options controlled per media item by the Android UI. These are both reset between
        // playlist entries and included in watch-later persistence.
        private val PER_FILE_PLAYBACK_OPTIONS = linkedSetOf(
            "speed",
            "audio-delay",
            "sub-delay",
            "secondary-sub-delay",
            "video-aspect-override",
            "panscan",
            "contrast",
            "brightness",
            "gamma",
            "saturation",
            "hwdec",
            "aid",
            "sid",
            "secondary-sid",
            "vid",
        )

        // Audio/subtitle IDs are restored together with external filenames by MPVActivity.
        private val APP_PERSISTED_PLAYBACK_OPTIONS =
            PER_FILE_PLAYBACK_OPTIONS - setOf("aid", "sid", "secondary-sid")

        // mpv option `hwdec` is set to this
        private const val HWDECS = "mediacodec,mediacodec-copy"
    }
}
