#!/bin/bash -e

. ../../include/path.sh

build=_build$ndk_suffix

if [ "$1" == "build" ]; then
	true
elif [ "$1" == "clean" ]; then
	rm -rf $build
	exit 0
else
	exit 255
fi

# AudioTrack.pause() preserves queued audio, while flush() deliberately discards
# it. mpv's AudioTrack backend only exposes reset (pause + flush), so a normal
# player pause loses the 75-150 ms already handed to Android. Teach the backend
# to use its hardware pause path and retain the unwritten tail of a blocking
# AudioTrack.write() if pause interrupts that call.
if ! grep -Eq '^[[:space:]]*\.set_pause[[:space:]]*=[[:space:]]*set_pause,' \
	audio/out/ao_audiotrack.c; then
	patch -p1 --forward --batch <<'PATCH'
diff --git a/audio/out/ao_audiotrack.c b/audio/out/ao_audiotrack.c
--- a/audio/out/ao_audiotrack.c
+++ b/audio/out/ao_audiotrack.c
@@ -21,6 +21,8 @@
  * License along with mpv.  If not, see <http://www.gnu.org/licenses/>.
  */
 
+#include <string.h>
+
 #include "ao.h"
 #include "internal.h"
 #include "common/msg.h"
@@ -52,6 +54,7 @@ struct priv {
 
     void *chunk;
     int chunksize;
+    int pending_bytes;
     jbyteArray bytearray;
     jshortArray shortarray;
     jfloatArray floatarray;
@@ -579,14 +582,25 @@ static MP_THREAD_VOID ao_thread(void *arg)
             state = MP_JNI_CALL_INT(p->audiotrack, AudioTrack.getPlayState);
         }
         if (state == AudioTrack.PLAYSTATE_PLAYING) {
-            int read_samples = p->chunksize / ao->sstride;
-            int64_t ts = mp_time_ns();
-            ts += MP_TIME_S_TO_NS(read_samples / (double)(ao->samplerate));
-            ts += MP_TIME_S_TO_NS(AudioTrack_getLatency(ao));
-            int samples = ao_read_data(ao, &p->chunk, read_samples, ts, NULL, false, false);
-            int ret = AudioTrack_write(ao, samples * ao->sstride);
+            int bytes = p->pending_bytes;
+            if (!bytes) {
+                int read_samples = p->chunksize / ao->sstride;
+                int64_t ts = mp_time_ns();
+                ts += MP_TIME_S_TO_NS(read_samples / (double)(ao->samplerate));
+                ts += MP_TIME_S_TO_NS(AudioTrack_getLatency(ao));
+                int samples = ao_read_data(ao, &p->chunk, read_samples, ts,
+                                           NULL, false, false);
+                bytes = samples * ao->sstride;
+            }
+
+            int ret = AudioTrack_write(ao, bytes);
             if (ret >= 0) {
+                mp_assert(ret <= bytes);
+                mp_assert(ret % ao->sstride == 0);
                 p->written_frames += ret / ao->sstride;
+                p->pending_bytes = bytes - ret;
+                if (ret > 0 && p->pending_bytes > 0)
+                    memmove(p->chunk, (char *)p->chunk + ret, p->pending_bytes);
             } else if (ret == AudioManager.ERROR_DEAD_OBJECT) {
                 MP_WARN(ao, "AudioTrack.write failed with ERROR_DEAD_OBJECT. Recreating AudioTrack...\n");
                 if (AudioTrack_Recreate(ao) < 0) {
@@ -808,30 +822,54 @@ static void stop(struct ao *ao)
 
     JNIEnv *env = MP_JNI_GET_ENV(ao);
     MP_JNI_CALL_VOID(p->audiotrack, AudioTrack.pause);
-    MP_JNI_EXCEPTION_LOG(ao);
+    if (MP_JNI_EXCEPTION_LOG(ao) < 0)
+        return;
+
+    // AudioTrack.pause() interrupts a blocking write. Wait for that write to
+    // return before flushing and discarding any unwritten tail retained by the
+    // audio thread.
+    mp_mutex_lock(&p->lock);
     MP_JNI_CALL_VOID(p->audiotrack, AudioTrack.flush);
     MP_JNI_EXCEPTION_LOG(ao);
 
+    p->pending_bytes = 0;
     p->playhead_offset = 0;
     p->reset_pending = true;
     p->written_frames = 0;
     p->timestamp_fetched = 0;
     p->timestamp_set = false;
+    mp_mutex_unlock(&p->lock);
 }
 
-static void start(struct ao *ao)
+static bool set_pause(struct ao *ao, bool paused)
 {
     struct priv *p = ao->priv;
     if (!p->audiotrack) {
-        MP_ERR(ao, "AudioTrack does not exist to start!\n");
-        return;
+        MP_ERR(ao, "AudioTrack does not exist to %s!\n",
+               paused ? "pause" : "resume");
+        return false;
     }
 
+    // Do not take p->lock here. The audio thread holds it while blocked in
+    // AudioTrack.write(), and pause() is what interrupts that write.
     JNIEnv *env = MP_JNI_GET_ENV(ao);
-    MP_JNI_CALL_VOID(p->audiotrack, AudioTrack.play);
-    MP_JNI_EXCEPTION_LOG(ao);
+    if (paused)
+        MP_JNI_CALL_VOID(p->audiotrack, AudioTrack.pause);
+    else
+        MP_JNI_CALL_VOID(p->audiotrack, AudioTrack.play);
 
-    mp_cond_signal(&p->wakeup);
+    if (MP_JNI_EXCEPTION_LOG(ao) < 0)
+        return false;
+
+    if (!paused)
+        mp_cond_signal(&p->wakeup);
+
+    return true;
+}
+
+static void start(struct ao *ao)
+{
+    set_pause(ao, false);
 }
 
 #define OPT_BASE_STRUCT struct priv
@@ -843,6 +881,7 @@ const struct ao_driver audio_out_audiotrack = {
     .uninit    = uninit,
     .reset     = stop,
     .start     = start,
+    .set_pause = set_pause,
     .priv_size = sizeof(struct priv),
     .priv_defaults = &(const OPT_BASE_STRUCT) {
         .cfg_pcm_float = 1,
PATCH
fi

# AudioTrack's AudioTimestamp may lag behind a hardware pause/resume transition.
# Do not let a pre-pause timestamp be extrapolated across the paused wall-clock
# interval: re-arm timestamp tracking after play() and use playback-head position
# until Android reports a post-resume timestamp whose frame position advances.
if ! grep -q 'timestamp_resume_rearming' audio/out/ao_audiotrack.c; then
python3 - <<'PY'
from pathlib import Path

path = Path("audio/out/ao_audiotrack.c")
s = path.read_text()

def replace_once(old, new, label):
    global s
    n = s.count(old)
    if n != 1:
        raise SystemExit(f"AudioTrack resume-clock patch: {label}: expected 1 match, got {n}")
    s = s.replace(old, new, 1)

replace_once(
    '#include <string.h>\n',
    '#include <string.h>\n#include <time.h>\n',
    'time.h include')

replace_once(
'''    int64_t timestamp_fetched;
    bool timestamp_set;
    int timestamp_stable;
''',
'''    int64_t timestamp_fetched;
    bool timestamp_set;
    int timestamp_stable;
    int64_t timestamp_resume_epoch_ns;
    uint32_t timestamp_resume_frame;
    int64_t timestamp_resume_time_ns;
    bool timestamp_resume_pending;
    bool timestamp_resume_rearming;
    bool timestamp_resume_sampled;
''',
    'timestamp resume state')

replace_once(
'''static int AudioTrack_Recreate(struct ao *ao)
{
''',
'''static int64_t AudioTrack_monotonic_ns(void)
{
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) < 0)
        return 0;
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static void AudioTrack_invalidate_timestamp(struct priv *p)
{
    p->timestamp_fetched = 0;
    p->timestamp_set = false;
    p->timestamp_stable = 0;
}

static void AudioTrack_clear_resume_timestamp_state(struct priv *p)
{
    p->timestamp_resume_epoch_ns = 0;
    p->timestamp_resume_frame = 0;
    p->timestamp_resume_time_ns = 0;
    p->timestamp_resume_rearming = false;
    p->timestamp_resume_sampled = false;
}

static int AudioTrack_Recreate(struct ao *ao)
{
''',
    'timestamp helper insertion')

# Keep this function-scoped instead of matching the entire Recreate body.
# Upstream may add harmless statements/spacing there; the repair must still
# inject the exact same resume-clock reset immediately before recreation.
func_start = s.find('static int AudioTrack_Recreate(struct ao *ao)\n{')
func_end = s.find('\nstatic uint32_t AudioTrack_getPlaybackHeadPosition', func_start)
if func_start < 0 or func_end < 0:
    raise SystemExit('AudioTrack resume-clock patch: recreate timestamp reset: function boundary not found')

func = s[func_start:func_end]
return_stmt = '    return AudioTrack_New(ao);'
if func.count(return_stmt) != 1:
    raise SystemExit(
        f'AudioTrack resume-clock patch: recreate timestamp reset: expected 1 recreate return, got {func.count(return_stmt)}')

reset = '''    AudioTrack_invalidate_timestamp(p);
    p->timestamp_resume_pending = false;
    AudioTrack_clear_resume_timestamp_state(p);
'''
if '    AudioTrack_clear_resume_timestamp_state(p);' not in func:
    func = func.replace(return_stmt, reset + return_stmt, 1)
    s = s[:func_start] + func + s[func_end:]

replace_once(
'''    int64_t now = mp_raw_time_ns();
''',
'''    // AudioTimestamp.nanoTime is in Android's MONOTONIC timebase. Keep the
    // extrapolation clock in the same timebase instead of CLOCK_MONOTONIC_RAW.
    int64_t now = AudioTrack_monotonic_ns();
    if (!now)
        now = mp_raw_time_ns();
''',
    'timestamp clock')

replace_once(
'''        int64_t time1 = MP_JNI_GET_LONG(p->timestamp, AudioTimestamp.nanoTime);
        if (MP_JNI_CALL_BOOL(p->audiotrack, AudioTrack.getTimestamp, p->timestamp)) {
            p->timestamp_set = true;
            p->timestamp_fetched = now;
            if (p->timestamp_stable < stable_count) {
                uint32_t fpos = 0xFFFFFFFFL & MP_JNI_GET_LONG(p->timestamp, AudioTimestamp.framePosition);
                int64_t time2 = MP_JNI_GET_LONG(p->timestamp, AudioTimestamp.nanoTime);
                //MP_VERBOSE(ao, "getTimestamp: fpos= %u / time= %"PRId64" / now= %"PRId64" / stable= %d\\n", fpos, time2, now, p->timestamp_stable);
                if (time1 != time2 && time2 != 0 && fpos != 0) {
                    p->timestamp_stable++;
                }
            }
        }
''',
'''        int64_t time1 = MP_JNI_GET_LONG(p->timestamp, AudioTimestamp.nanoTime);
        if (MP_JNI_CALL_BOOL(p->audiotrack, AudioTrack.getTimestamp, p->timestamp)) {
            uint32_t fpos = 0xFFFFFFFFL &
                MP_JNI_GET_LONG(p->timestamp, AudioTimestamp.framePosition);
            int64_t time2 = MP_JNI_GET_LONG(p->timestamp, AudioTimestamp.nanoTime);
            bool accept = true;

            if (p->timestamp_resume_rearming) {
                // A getTimestamp() immediately after play() is allowed to expose
                // a sample from the old paused clock epoch. Do not extrapolate it
                // over the pause interval. Require a timestamp produced after the
                // play() transition and then observable frame-position progress.
                if (!time2 || !fpos || !p->timestamp_resume_epoch_ns ||
                    time2 < p->timestamp_resume_epoch_ns)
                {
                    accept = false;
                } else if (!p->timestamp_resume_sampled) {
                    p->timestamp_resume_frame = fpos;
                    p->timestamp_resume_time_ns = time2;
                    p->timestamp_resume_sampled = true;
                    accept = false;
                } else {
                    uint32_t advanced = fpos - p->timestamp_resume_frame;
                    if (advanced > 0 && time2 > p->timestamp_resume_time_ns) {
                        p->timestamp_resume_rearming = false;
                        p->timestamp_resume_sampled = false;
                        MP_VERBOSE(ao, "resume: AudioTimestamp re-armed at frame=%u time=%"PRId64"\\n",
                                   fpos, time2);
                    } else {
                        p->timestamp_resume_frame = fpos;
                        p->timestamp_resume_time_ns = time2;
                        accept = false;
                    }
                }
            }

            p->timestamp_fetched = now;
            if (accept) {
                p->timestamp_set = true;
                if (p->timestamp_stable < stable_count &&
                    time1 != time2 && time2 != 0 && fpos != 0)
                {
                    p->timestamp_stable++;
                }
            } else {
                // getPlaybackHeadPosition() below is continuous across pause/play
                // and is therefore the safe clock while AudioTimestamp stabilizes.
                p->timestamp_set = false;
                p->timestamp_stable = 0;
            }
        } else if (p->timestamp_resume_rearming) {
            // Poll at the normal 50 ms unstable-timestamp cadence while falling
            // back to playbackHeadPosition; do not reuse a pre-pause timestamp.
            p->timestamp_fetched = now;
            p->timestamp_set = false;
            p->timestamp_stable = 0;
        }
''',
    'timestamp reacquisition')

replace_once(
'''    p->written_frames = 0;
    p->timestamp_fetched = 0;
    p->timestamp_set = false;
    mp_mutex_unlock(&p->lock);
}

static bool set_pause(struct ao *ao, bool paused)
''',
'''    p->written_frames = 0;
    AudioTrack_invalidate_timestamp(p);
    p->timestamp_resume_pending = false;
    AudioTrack_clear_resume_timestamp_state(p);
    mp_mutex_unlock(&p->lock);
}

static bool set_pause(struct ao *ao, bool paused)
''',
    'real reset timestamp state')

replace_once(
'''    // Do not take p->lock here. The audio thread holds it while blocked in
    // AudioTrack.write(), and pause() is what interrupts that write.
    JNIEnv *env = MP_JNI_GET_ENV(ao);
    if (paused)
        MP_JNI_CALL_VOID(p->audiotrack, AudioTrack.pause);
    else
        MP_JNI_CALL_VOID(p->audiotrack, AudioTrack.play);

    if (MP_JNI_EXCEPTION_LOG(ao) < 0)
        return false;

    if (!paused)
        mp_cond_signal(&p->wakeup);

    return true;
''',
'''    JNIEnv *env = MP_JNI_GET_ENV(ao);
    if (paused) {
        // Do not take p->lock before pause(): the audio thread may hold it while
        // blocked in AudioTrack.write(), and pause() is what releases that write.
        MP_JNI_CALL_VOID(p->audiotrack, AudioTrack.pause);
        if (MP_JNI_EXCEPTION_LOG(ao) < 0)
            return false;

        mp_mutex_lock(&p->lock);
        AudioTrack_invalidate_timestamp(p);
        p->timestamp_resume_pending = true;
        AudioTrack_clear_resume_timestamp_state(p);
        int pending = p->pending_bytes;
        mp_mutex_unlock(&p->lock);
        MP_VERBOSE(ao, "pause: AudioTrack queue preserved, pending=%d bytes\\n", pending);
    } else {
        // During resume the audio thread is no longer inside a blocking write.
        // Keep it behind p->lock until the post-play timestamp epoch is armed, so
        // no latency query can extrapolate a stale AudioTimestamp in between.
        mp_mutex_lock(&p->lock);
        AudioTrack_invalidate_timestamp(p);
        AudioTrack_clear_resume_timestamp_state(p);
        MP_JNI_CALL_VOID(p->audiotrack, AudioTrack.play);
        if (MP_JNI_EXCEPTION_LOG(ao) < 0) {
            mp_mutex_unlock(&p->lock);
            return false;
        }
        if (p->timestamp_resume_pending) {
            p->timestamp_resume_epoch_ns = AudioTrack_monotonic_ns();
            p->timestamp_resume_rearming = p->timestamp_resume_epoch_ns > 0;
            p->timestamp_resume_sampled = false;
        }
        p->timestamp_resume_pending = false;
        mp_mutex_unlock(&p->lock);
        mp_cond_signal(&p->wakeup);
        MP_VERBOSE(ao, "resume: AudioTrack queue preserved; timestamp epoch re-armed\\n");
    }

    return true;
''',
    'hardware pause/resume clock fencing')

path.write_text(s)
PY
fi

# Make subtitle seeking treat the primary and secondary tracks as one timeline.
# mpv exposes per-track seeking, so add a "both" mode which asks both tracks for
# their target and performs one seek to the closest result in the requested
# direction.
if ! grep -Eq '\{"both",[[:space:]]*2\}' player/command.c; then
	patch -p1 --forward --batch <<'PATCH'
diff --git a/player/command.c b/player/command.c
--- a/player/command.c
+++ b/player/command.c
@@ -6260,6 +6260,27 @@ static void cmd_playlist_play_index(void *p)
         mpctx->add_osd_seek_info |= OSD_SEEK_INFO_CURRENT_FILE;
 }
 
+static void queue_sub_seek(struct MPContext *mpctx, struct mp_cmd_ctx *cmd,
+                           double refpts, double target)
+{
+    // We can easily seek/step to the wrong subtitle line (because
+    // video frame PTS and sub PTS rarely match exactly).
+    // sub/sd_ass.c adds SUB_SEEK_OFFSET as a workaround, and we
+    // need an even bigger offset without a video.
+    if (!mpctx->current_track[0][STREAM_VIDEO] ||
+        mpctx->current_track[0][STREAM_VIDEO]->image) {
+        target += SUB_SEEK_WITHOUT_VIDEO_OFFSET - SUB_SEEK_OFFSET;
+    }
+    mark_seek(mpctx);
+    queue_seek(mpctx, MPSEEK_ABSOLUTE, target, MPSEEK_EXACT,
+               MPSEEK_FLAG_DELAY);
+    set_osd_function(mpctx, (target > refpts) ? OSD_FFW : OSD_REW);
+    if (cmd->seek_bar_osd)
+        mpctx->add_osd_seek_info |= OSD_SEEK_INFO_BAR;
+    if (cmd->seek_msg_osd)
+        mpctx->add_osd_seek_info |= OSD_SEEK_INFO_TEXT;
+}
+
 static void cmd_sub_step_seek(void *p)
 {
     struct mp_cmd_ctx *cmd = p;
@@ -6272,9 +6293,40 @@ static void cmd_sub_step_seek(void *p)
         return;
     }
 
+    double refpts = get_current_time(mpctx);
+    if (!step && track_ind == 2) {
+        if (refpts == MP_NOPTS_VALUE)
+            return;
+
+        int skip = cmd->args[0].v.i;
+        if (skip != -1 && skip != 1) {
+            cmd->success = false;
+            return;
+        }
+
+        double target = MP_NOPTS_VALUE;
+        for (int n = 0; n < 2; n++) {
+            struct track *track = mpctx->current_track[n][STREAM_SUB];
+            struct dec_sub *sub = track ? track->d_sub : NULL;
+            if (!sub)
+                continue;
+
+            double candidate[2] = {refpts, skip};
+            if (sub_control(sub, SD_CTRL_SUB_STEP, candidate) <= 0)
+                continue;
+
+            if (target == MP_NOPTS_VALUE ||
+                (skip > 0 ? candidate[0] < target : candidate[0] > target))
+                target = candidate[0];
+        }
+
+        if (target != MP_NOPTS_VALUE)
+            queue_sub_seek(mpctx, cmd, refpts, target);
+        return;
+    }
+
     struct track *track = mpctx->current_track[track_ind][STREAM_SUB];
     struct dec_sub *sub = track ? track->d_sub : NULL;
-    double refpts = get_current_time(mpctx);
     if (sub && refpts != MP_NOPTS_VALUE) {
         double a[2];
         a[0] = refpts;
@@ -6289,22 +6341,7 @@ static void cmd_sub_step_seek(void *p)
                     track_ind == 0 ? "sub-delay" : "secondary-sub-delay",
                     cmd->on_osd);
             } else {
-                // We can easily seek/step to the wrong subtitle line (because
-                // video frame PTS and sub PTS rarely match exactly).
-                // sub/sd_ass.c adds SUB_SEEK_OFFSET as a workaround, and we
-                // need an even bigger offset without a video.
-                if (!mpctx->current_track[0][STREAM_VIDEO] ||
-                    mpctx->current_track[0][STREAM_VIDEO]->image) {
-                    a[0] += SUB_SEEK_WITHOUT_VIDEO_OFFSET - SUB_SEEK_OFFSET;
-                }
-                mark_seek(mpctx);
-                queue_seek(mpctx, MPSEEK_ABSOLUTE, a[0], MPSEEK_EXACT,
-                           MPSEEK_FLAG_DELAY);
-                set_osd_function(mpctx, (a[0] > refpts) ? OSD_FFW : OSD_REW);
-                if (cmd->seek_bar_osd)
-                    mpctx->add_osd_seek_info |= OSD_SEEK_INFO_BAR;
-                if (cmd->seek_msg_osd)
-                    mpctx->add_osd_seek_info |= OSD_SEEK_INFO_TEXT;
+                queue_sub_seek(mpctx, cmd, refpts, a[0]);
             }
         }
     }
@@ -7554,7 +7591,8 @@ const struct mp_cmd_def mp_cmds[] = {
             {"skip", OPT_INT(v.i)},
             {"flags", OPT_CHOICE(v.i,
                 {"primary", 0},
-                {"secondary", 1}),
+                {"secondary", 1},
+                {"both", 2}),
                 OPTDEF_INT(0)},
         },
         .allow_auto_repeat = true,
PATCH
fi

# mpv-android: confine gpu-next OSD to the video crop without resizing the Android buffer.
#
# The compact-surface implementation achieves this by setting android-surface-size
# to the fitted video rectangle. On Android that also changes ANativeWindow buffer
# geometry and can lower render resolution. Keep the real surface untouched; only
# render gpu-next's OSD against the already-computed destination crop.
python3 - <<'PY_OSD_VIEWPORT'
from pathlib import Path

path = Path("video/out/vo_gpu_next.c")
src = path.read_text()
marker = "mpv-android OSD video-crop viewport"
if marker in src:
    raise SystemExit(0)

needle = "update_overlays(vo, p->osd_res,"
start = src.find(needle)
if start < 0:
    raise SystemExit("mpv gpu-next OSD viewport patch failed: main OSD call not found")

line_start = src.rfind("\n", 0, start) + 1
end_marker = "get_ref_luma(p));"
end = src.find(end_marker, start)
if end < 0:
    raise SystemExit("mpv gpu-next OSD viewport patch failed: main OSD call end not found")
end += len(end_marker)

old = src[line_start:end]
required = ("OSD_DRAW_OSD_ONLY", "PL_OVERLAY_COORDS_DST_FRAME",
            "&p->osd_state", "&target", "frame->current")
if not all(token in old for token in required):
    raise SystemExit("mpv gpu-next OSD viewport patch failed: unexpected main OSD call")

indent = old[:len(old) - len(old.lstrip())]
lines = [
    f"{indent}// {marker}",
    f"{indent}struct mp_osd_res osd_viewport = p->osd_res;",
    f"{indent}enum pl_overlay_coords osd_coords = PL_OVERLAY_COORDS_DST_FRAME;",
    f"{indent}int osd_w = mp_rect_w(p->dst);",
    f"{indent}int osd_h = mp_rect_h(p->dst);",
    f"{indent}if (osd_w > 0 && osd_h > 0) {{",
    f"{indent}    // Match the OSD canvas of a compact media-aspect surface, but keep",
    f"{indent}    // vo->dwidth/vo->dheight (and therefore Android's real buffer) intact.",
    f"{indent}    osd_viewport.w = osd_w;",
    f"{indent}    osd_viewport.h = osd_h;",
    f"{indent}    osd_viewport.ml = 0;",
    f"{indent}    osd_viewport.mr = 0;",
    f"{indent}    osd_viewport.mt = 0;",
    f"{indent}    osd_viewport.mb = 0;",
    f"{indent}    osd_coords = PL_OVERLAY_COORDS_DST_CROP;",
    f"{indent}}}",
    f"{indent}update_overlays(vo, osd_viewport,",
    f"{indent}                (frame->current && opts->blend_subs) ? OSD_DRAW_OSD_ONLY : 0,",
    f"{indent}                osd_coords, &p->osd_state, &target, frame->current,",
    f"{indent}                frame->current ? frame->current->params.stereo3d : 0, get_ref_luma(p));",
]
new = "\n".join(lines)
src = src[:line_start] + new + src[end:]
path.write_text(src)
PY_OSD_VIEWPORT

# Pause/resume frame-continuity instrumentation. Keep the production video
# behavior intact: no seek, queue reset, PTS rebase, acquisition-policy change,
# or resume gate is introduced here. The records join media PTS, VO frame IDs,
# libplacebo selection, AImage timestamps, EGL swaps, and pause epochs.
python3 - <<'PY_FCI_MPV'
from pathlib import Path


def replace_once(text, old, new, label):
    if text.count(old) != 1:
        raise SystemExit(f"mpv FCI instrumentation failed at {label}: "
                         f"expected one anchor, found {text.count(old)}")
    return text.replace(old, new, 1)


def load(path):
    p = Path(path)
    return p, p.read_text()


# Player/core: record filtered-decoder output and the exact image selected for VO.
p, src = load("player/video.c")
if "FCI_CORE_FRAME_READY" not in src:
    src = replace_once(
        src,
        '''    mp_assert(mpctx->num_next_frames < MP_ARRAY_SIZE(mpctx->next_frames));
    mp_assert(frame);
    mpctx->next_frames[mpctx->num_next_frames++] = frame;
''',
        '''    mp_assert(mpctx->num_next_frames < MP_ARRAY_SIZE(mpctx->next_frames));
    mp_assert(frame);
    MP_INFO(mpctx,
            "FCI_CORE_FRAME_READY image=%p media_pts=%.9f pkt_duration=%.9f mp_ns=%"PRId64"\\n",
            frame, frame->pts, frame->pkt_duration, mp_time_ns());
    mpctx->next_frames[mpctx->num_next_frames++] = frame;
''',
        "core decoded/filtered frame",
    )
    src = replace_once(
        src,
        '''    mpctx->last_frame_duration =
        mpctx->next_frames[0]->pkt_duration / mpctx->video_speed;

    shift_frames(mpctx);
''',
        '''    mpctx->last_frame_duration =
        mpctx->next_frames[0]->pkt_duration / mpctx->video_speed;

    MP_INFO(mpctx,
            "FCI_CORE_SELECT image=%p media_pts=%.9f target_ns=%"PRId64
            " paused=%d future=%d mp_ns=%"PRId64"\\n",
            frame->current, mpctx->video_pts, frame->pts, mpctx->paused,
            frame->num_frames, mp_time_ns());
    shift_frames(mpctx);
''',
        "core VO selection",
    )
    p.write_text(src)


# Player pause boundary: this is the authoritative effective mpv pause state,
# distinct from a UI command being accepted by the Android frontend.
p, src = load("player/playloop.c")
if "FCI_CORE_PAUSE" not in src:
    src = replace_once(
        src,
        '''#include "osdep/timer.h"
''',
        '''#include "osdep/timer.h"
#include <libavutil/time.h>
''',
        "shared monotonic clock include",
    )
    src = replace_once(
        src,
        '''void set_pause_state(struct MPContext *mpctx, bool user_pause)
{
    struct MPOpts *opts = mpctx->opts;

    opts->pause = user_pause;
''',
        '''void set_pause_state(struct MPContext *mpctx, bool user_pause)
{
    struct MPOpts *opts = mpctx->opts;
    static uint64_t fci_pause_transition;

    opts->pause = user_pause;
''',
        "pause transition counter",
    )
    src = replace_once(
        src,
        '''    if (internal_paused != mpctx->paused) {
        mpctx->paused = internal_paused;

        if (mpctx->ao) {
''',
        '''    if (internal_paused != mpctx->paused) {
        mpctx->paused = internal_paused;
        uint64_t fci_transition = ++fci_pause_transition;
        MP_INFO(mpctx,
                "FCI_CORE_PAUSE transition=%llu state=%s user=%d cache=%d video_pts=%.9f mp_ns=%lld mono_us=%lld\\n",
                (unsigned long long)fci_transition,
                internal_paused ? "PAUSE" : "RESUME", user_pause,
                mpctx->paused_for_cache, mpctx->video_pts,
                (long long)mp_time_ns(), (long long)av_gettime_relative());

        if (mpctx->ao) {
''',
        "effective pause marker",
    )
    p.write_text(src)


# VO scheduler: identify every queued and rendered frame, and expose whether a
# pause transition arrived while draw/flip was already in flight.
p, src = load("video/out/vo.c")
if "FCI_VO_QUEUE" not in src:
    src = replace_once(
        src,
        '''    uint64_t current_frame_id;

    double display_fps;
''',
        '''    uint64_t current_frame_id;
    uint64_t fci_epoch;
    uint64_t fci_render_seq;

    double display_fps;
''',
        "VO trace fields",
    )
    src = replace_once(
        src,
        '''    frame->frame_id = ++(in->current_frame_id);
    in->frame_queued = frame;
''',
        '''    frame->frame_id = ++(in->current_frame_id);
    MP_INFO(vo,
            "FCI_VO_QUEUE epoch=%llu id=%llu media_pts=%.9f target_ns=%lld duration_ns=%.0f paused=%d mp_ns=%lld\\n",
            (unsigned long long)in->fci_epoch,
            (unsigned long long)frame->frame_id,
            frame->current ? frame->current->pts : MP_NOPTS_VALUE,
            (long long)frame->pts, frame->duration, in->paused,
            (long long)mp_time_ns());
    in->frame_queued = frame;
''',
        "VO queue marker",
    )
    src = replace_once(
        src,
        '''    struct vo_frame *frame = NULL;
    bool more_frames = false;

    update_display_fps(vo);
''',
        '''    struct vo_frame *frame = NULL;
    bool more_frames = false;
    uint64_t fci_render_seq = 0;
    uint64_t fci_render_epoch = 0;
    uint64_t fci_frame_id = 0;
    double fci_media_pts = MP_NOPTS_VALUE;

    update_display_fps(vo);
''',
        "VO render trace locals",
    )
    src = replace_once(
        src,
        '''    frame = vo_frame_ref(in->current_frame);
    mp_assert(frame);

    if (frame->display_synced) {
''',
        '''    frame = vo_frame_ref(in->current_frame);
    mp_assert(frame);
    fci_render_seq = ++in->fci_render_seq;
    fci_render_epoch = in->fci_epoch;
    fci_frame_id = frame->frame_id;
    fci_media_pts = frame->current ? frame->current->pts : MP_NOPTS_VALUE;
    MP_INFO(vo,
            "FCI_VO_RENDER_SELECT render_seq=%llu epoch=%llu id=%llu media_pts=%.9f target_ns=%lld paused=%d mp_ns=%lld\\n",
            (unsigned long long)fci_render_seq,
            (unsigned long long)fci_render_epoch,
            (unsigned long long)fci_frame_id, fci_media_pts,
            (long long)frame->pts, in->paused, (long long)mp_time_ns());

    if (frame->display_synced) {
''',
        "VO render selection",
    )
    src = replace_once(
        src,
        '''    if (in->dropped_frame) {
        in->drop_count += 1;
        wakeup_core(vo);
    } else {
        in->rendering = true;
''',
        '''    if (in->dropped_frame) {
        MP_INFO(vo,
                "FCI_VO_DROP render_seq=%llu epoch=%llu id=%llu media_pts=%.9f mp_ns=%lld\\n",
                (unsigned long long)fci_render_seq,
                (unsigned long long)fci_render_epoch,
                (unsigned long long)fci_frame_id, fci_media_pts,
                (long long)mp_time_ns());
        in->drop_count += 1;
        wakeup_core(vo);
    } else {
        MP_INFO(vo,
                "FCI_VO_DRAW_BEGIN render_seq=%llu epoch=%llu id=%llu media_pts=%.9f mp_ns=%lld\\n",
                (unsigned long long)fci_render_seq,
                (unsigned long long)fci_render_epoch,
                (unsigned long long)fci_frame_id, fci_media_pts,
                (long long)mp_time_ns());
        in->rendering = true;
''',
        "VO draw/drop boundary",
    )
    src = replace_once(
        src,
        '''        in->visible = vo->driver->draw_frame(vo, frame);

        stats_time_end(in->stats, "video-draw");

        wait_until(vo, target);
''',
        '''        in->visible = vo->driver->draw_frame(vo, frame);

        stats_time_end(in->stats, "video-draw");
        MP_INFO(vo,
                "FCI_VO_DRAW_END render_seq=%llu epoch=%llu id=%llu visible=%d mp_ns=%lld\\n",
                (unsigned long long)fci_render_seq,
                (unsigned long long)fci_render_epoch,
                (unsigned long long)fci_frame_id, in->visible,
                (long long)mp_time_ns());

        wait_until(vo, target);
''',
        "VO draw completion",
    )
    src = replace_once(
        src,
        '''        stats_time_start(in->stats, "video-flip");

        vo->driver->flip_page(vo);

        struct vo_vsync_info vsync = {
''',
        '''        stats_time_start(in->stats, "video-flip");
        MP_INFO(vo,
                "FCI_VO_FLIP_BEGIN render_seq=%llu epoch=%llu id=%llu target_ns=%lld mp_ns=%lld\\n",
                (unsigned long long)fci_render_seq,
                (unsigned long long)fci_render_epoch,
                (unsigned long long)fci_frame_id, (long long)target,
                (long long)mp_time_ns());

        vo->driver->flip_page(vo);
        MP_INFO(vo,
                "FCI_VO_FLIP_END render_seq=%llu epoch=%llu id=%llu mp_ns=%lld\\n",
                (unsigned long long)fci_render_seq,
                (unsigned long long)fci_render_epoch,
                (unsigned long long)fci_frame_id, (long long)mp_time_ns());

        struct vo_vsync_info vsync = {
''',
        "VO flip boundary",
    )
    src = replace_once(
        src,
        '''        mp_mutex_lock(&in->lock);
        in->dropped_frame = prev_drop_count < vo->in->drop_count;
        in->rendering = false;

        update_vsync_timing_after_swap(vo, &vsync);
''',
        '''        mp_mutex_lock(&in->lock);
        MP_INFO(vo,
                "FCI_VO_RENDER_END render_seq=%llu start_epoch=%llu end_epoch=%llu id=%llu paused=%d mp_ns=%lld\\n",
                (unsigned long long)fci_render_seq,
                (unsigned long long)fci_render_epoch,
                (unsigned long long)in->fci_epoch,
                (unsigned long long)fci_frame_id, in->paused,
                (long long)mp_time_ns());
        in->dropped_frame = prev_drop_count < vo->in->drop_count;
        in->rendering = false;

        update_vsync_timing_after_swap(vo, &vsync);
''',
        "VO post-swap epoch check",
    )
    old_pause = '''void vo_set_paused(struct vo *vo, bool paused)
{
    struct vo_internal *in = vo->in;
    mp_mutex_lock(&in->lock);
    if (in->paused != paused) {
        in->paused = paused;
        if (in->paused && in->dropped_frame) {
            in->request_redraw = true;
            wakeup_core(vo);
        }
        reset_vsync_timings(vo);
        wakeup_locked(vo);
    }
    mp_mutex_unlock(&in->lock);
}
'''
    new_pause = '''void vo_set_paused(struct vo *vo, bool paused)
{
    struct vo_internal *in = vo->in;
    mp_mutex_lock(&in->lock);
    if (in->paused != paused) {
        in->paused = paused;
        in->fci_epoch++;
        MP_INFO(vo,
                "FCI_VO_PAUSE epoch=%llu state=%s rendering=%d current_id=%llu current_media_pts=%.9f queued_id=%llu queued_media_pts=%.9f mp_ns=%lld\\n",
                (unsigned long long)in->fci_epoch, paused ? "PAUSE" : "RESUME",
                in->rendering,
                (unsigned long long)(in->current_frame ? in->current_frame->frame_id : 0),
                in->current_frame && in->current_frame->current
                    ? in->current_frame->current->pts : MP_NOPTS_VALUE,
                (unsigned long long)(in->frame_queued ? in->frame_queued->frame_id : 0),
                in->frame_queued && in->frame_queued->current
                    ? in->frame_queued->current->pts : MP_NOPTS_VALUE,
                (long long)mp_time_ns());
        if (in->paused && in->dropped_frame) {
            in->request_redraw = true;
            wakeup_core(vo);
        }
        reset_vsync_timings(vo);
        wakeup_locked(vo);
    }
    mp_mutex_unlock(&in->lock);
}
'''
    src = replace_once(src, old_pause, new_pause, "VO pause state")
    src = replace_once(
        src,
        '''void vo_seek_reset(struct vo *vo)
{
    struct vo_internal *in = vo->in;
    mp_mutex_lock(&in->lock);
    forget_frames(vo);
''',
        '''void vo_seek_reset(struct vo *vo)
{
    struct vo_internal *in = vo->in;
    mp_mutex_lock(&in->lock);
    in->fci_epoch++;
    MP_INFO(vo, "FCI_VO_RESET epoch=%llu mp_ns=%lld\\n",
            (unsigned long long)in->fci_epoch, (long long)mp_time_ns());
    forget_frames(vo);
''',
        "VO reset generation",
    )
    p.write_text(src)


# AImageReader mapper: preserve acquireLatestImage exactly, but expose callback
# count, source PTS, acquired AImage timestamp, and hardware-buffer identity.
p, src = load("video/out/hwdec/hwdec_aimagereader.c")
if "FCI_AIMAGE_CALLBACK" not in src:
    src = replace_once(
        src,
        '''    media_status_t (*AImage_getHardwareBuffer)(const AImage *, AHardwareBuffer **);
    void (*AImage_delete)(AImage *);
''',
        '''    media_status_t (*AImage_getHardwareBuffer)(const AImage *, AHardwareBuffer **);
    media_status_t (*AImage_getTimestamp)(const AImage *, int64_t *);
    void (*AImage_delete)(AImage *);
''',
        "AImage timestamp function pointer",
    )
    src = replace_once(
        src,
        '''    bool image_available;

    EGLImageKHR (EGLAPIENTRY *CreateImageKHR)(
''',
        '''    bool image_available;
    uint64_t image_callback_seq;
    uint64_t map_seq;
    uint64_t current_map_seq;
    int64_t current_image_ts_ns;
    AHardwareBuffer *current_hwbuf;

    EGLImageKHR (EGLAPIENTRY *CreateImageKHR)(
''',
        "AImage trace fields",
    )
    src = replace_once(
        src,
        '''    { "AImage_getHardwareBuffer", offsetof(struct priv_owner, AImage_getHardwareBuffer) },
    { "AImage_delete", offsetof(struct priv_owner, AImage_delete) },
''',
        '''    { "AImage_getHardwareBuffer", offsetof(struct priv_owner, AImage_getHardwareBuffer) },
    { "AImage_getTimestamp", offsetof(struct priv_owner, AImage_getTimestamp) },
    { "AImage_delete", offsetof(struct priv_owner, AImage_delete) },
''',
        "AImage timestamp symbol",
    )
    old_cb = '''    mp_mutex_lock(&p->lock);
    p->image_available = true;
    mp_cond_signal(&p->cond);
    mp_mutex_unlock(&p->lock);
'''
    new_cb = '''    mp_mutex_lock(&p->lock);
    p->image_available = true;
    uint64_t callback_seq = ++p->image_callback_seq;
    mp_cond_signal(&p->cond);
    mp_mutex_unlock(&p->lock);
    mp_info(p->log, "FCI_AIMAGE_CALLBACK callback_seq=%llu mp_ns=%lld\\n",
            (unsigned long long)callback_seq, (long long)mp_time_ns());
'''
    src = replace_once(src, old_cb, new_cb, "AImage callback")
    src = replace_once(
        src,
        '''    if (p->image) {
        o->AImage_delete(p->image);
        p->image = NULL;
    }
''',
        '''    if (p->image) {
        MP_INFO(mapper,
                "FCI_AIMAGE_UNMAP map_seq=%llu image=%p image_ts_ns=%lld hwbuf=%p mp_ns=%lld\\n",
                (unsigned long long)p->current_map_seq, p->image,
                (long long)p->current_image_ts_ns, p->current_hwbuf,
                (long long)mp_time_ns());
        o->AImage_delete(p->image);
        p->image = NULL;
        p->current_hwbuf = NULL;
    }
''',
        "AImage unmap",
    )
    old_release = '''    {
        if (mapper->src->imgfmt != IMGFMT_MEDIACODEC)
            return -1;
        AVMediaCodecBuffer *buffer = (AVMediaCodecBuffer *)mapper->src->planes[3];
        av_mediacodec_release_buffer(buffer, 1);
    }
'''
    new_release = '''    uint64_t map_seq = ++p->map_seq;
    {
        if (mapper->src->imgfmt != IMGFMT_MEDIACODEC)
            return -1;
        AVMediaCodecBuffer *buffer = (AVMediaCodecBuffer *)mapper->src->planes[3];
        MP_INFO(mapper,
                "FCI_AIMAGE_CODEC_RELEASE_BEGIN map_seq=%llu codec_buffer=%p source_media_pts=%.9f mp_ns=%lld\\n",
                (unsigned long long)map_seq, buffer, mapper->src->pts,
                (long long)mp_time_ns());
        int release_ret = av_mediacodec_release_buffer(buffer, 1);
        MP_INFO(mapper,
                "FCI_AIMAGE_CODEC_RELEASE_END map_seq=%llu codec_buffer=%p result=%d mp_ns=%lld\\n",
                (unsigned long long)map_seq, buffer, release_ret,
                (long long)mp_time_ns());
    }
'''
    src = replace_once(src, old_release, new_release, "MediaCodec-to-AImage release")
    src = replace_once(
        src,
        '''    image_available = p->image_available;
    p->image_available = false;
    mp_mutex_unlock(&p->lock);

    media_status_t ret = o->AImageReader_acquireLatestImage(o->reader, &p->image);
''',
        '''    image_available = p->image_available;
    uint64_t callback_seq = p->image_callback_seq;
    p->image_available = false;
    mp_mutex_unlock(&p->lock);

    media_status_t ret = o->AImageReader_acquireLatestImage(o->reader, &p->image);
''',
        "AImage callback snapshot",
    )
    src = replace_once(
        src,
        '''    mp_assert(p->image);

    AHardwareBuffer *hwbuf = NULL;
''',
        '''    mp_assert(p->image);

    int64_t image_ts_ns = -1;
    media_status_t ts_ret = o->AImage_getTimestamp(p->image, &image_ts_ns);

    AHardwareBuffer *hwbuf = NULL;
''',
        "AImage timestamp read",
    )
    src = replace_once(
        src,
        '''    AHardwareBuffer_Desc d;
    o->AHardwareBuffer_describe(hwbuf, &d);
    if (mapper->tex[0]->params.w != d.width || mapper->tex[0]->params.h != d.height) {
''',
        '''    AHardwareBuffer_Desc d;
    o->AHardwareBuffer_describe(hwbuf, &d);
    p->current_map_seq = map_seq;
    p->current_image_ts_ns = image_ts_ns;
    p->current_hwbuf = hwbuf;
    MP_INFO(mapper,
            "FCI_AIMAGE_ACQUIRE map_seq=%llu callback_seq=%llu source_media_pts=%.9f image=%p image_ts_status=%d image_ts_ns=%lld hwbuf=%p width=%u height=%u layers=%u format=%u stride=%u mp_ns=%lld\\n",
            (unsigned long long)map_seq, (unsigned long long)callback_seq,
            mapper->src->pts, p->image, ts_ret, (long long)image_ts_ns,
            hwbuf, d.width, d.height, d.layers, d.format, d.stride,
            (long long)mp_time_ns());
    if (mapper->tex[0]->params.w != d.width || mapper->tex[0]->params.h != d.height) {
''',
        "AImage acquisition identity",
    )
    p.write_text(src)


# gpu-next/libplacebo: record queue insertion, lazy mapping, selected mix, and
# the frame associated with each output swap. No queue state is changed.
p, src = load("video/out/vo_gpu_next.c")
if "FCI_GPU_DRAW_BEGIN" not in src:
    src = replace_once(
        src,
        '''    bool frame_pending;
    bool paused;

    pl_options pars;
''',
        '''    bool frame_pending;
    bool paused;
    uint64_t fci_draw_seq;
    uint64_t fci_pending_draw_seq;
    uint64_t fci_pending_frame_id;
    double fci_pending_media_pts;
    uint64_t fci_swap_seq;

    pl_options pars;
''',
        "gpu-next trace fields",
    )
    src = replace_once(
        src,
        '''struct frame_priv {
    struct vo *vo;
    struct osd_state subs;
''',
        '''struct frame_priv {
    struct vo *vo;
    uint64_t frame_id;
    struct osd_state subs;
''',
        "libplacebo source frame ID",
    )
    src = replace_once(
        src,
        '''    struct frame_priv *fp = mpi->priv;
    struct priv *p = fp->vo->priv;
    if (!hwdec_reconfig(p, &p->hwdec_mapper, &p->hwdec_timer, fp->hwdec,
''',
        '''    struct frame_priv *fp = mpi->priv;
    struct priv *p = fp->vo->priv;
    MP_INFO(fp->vo,
            "FCI_GPU_HWACQUIRE_BEGIN id=%llu media_pts=%.9f mp_ns=%lld\\n",
            (unsigned long long)fp->frame_id, mpi->pts,
            (long long)mp_time_ns());
    if (!hwdec_reconfig(p, &p->hwdec_mapper, &p->hwdec_timer, fp->hwdec,
''',
        "gpu hardware acquire begin",
    )
    src = replace_once(
        src,
        '''    p->hwdec_perf = timer_pool_measure(p->hwdec_timer);
    stats_time_end(p->stats, "hwdec-map");

    return true;
''',
        '''    p->hwdec_perf = timer_pool_measure(p->hwdec_timer);
    stats_time_end(p->stats, "hwdec-map");
    MP_INFO(fp->vo,
            "FCI_GPU_HWACQUIRE_END id=%llu media_pts=%.9f mp_ns=%lld\\n",
            (unsigned long long)fp->frame_id, mpi->pts,
            (long long)mp_time_ns());

    return true;
''',
        "gpu hardware acquire end",
    )
    src = replace_once(
        src,
        '''    struct vo *vo = fp->vo;
    struct priv *p = vo->priv;

    fp->hwdec = ra_hwdec_get(&p->hwdec_ctx, mpi->imgfmt);
''',
        '''    struct vo *vo = fp->vo;
    struct priv *p = vo->priv;
    MP_INFO(vo,
            "FCI_PL_MAP id=%llu media_pts=%.9f image=%p mp_ns=%lld\\n",
            (unsigned long long)fp->frame_id, mpi->pts, mpi,
            (long long)mp_time_ns());

    fp->hwdec = ra_hwdec_get(&p->hwdec_ctx, mpi->imgfmt);
''',
        "libplacebo lazy map",
    )
    src = replace_once(
        src,
        '''    pl_gpu gpu = p->gpu;
    update_options(vo);

    struct pl_render_params params = pars->params;
''',
        '''    pl_gpu gpu = p->gpu;
    update_options(vo);
    uint64_t fci_draw_seq = ++p->fci_draw_seq;
    p->fci_pending_draw_seq = fci_draw_seq;
    p->fci_pending_frame_id = frame->current ? frame->frame_id : 0;
    p->fci_pending_media_pts = frame->current
        ? frame->current->pts : MP_NOPTS_VALUE;
    MP_INFO(vo,
            "FCI_GPU_DRAW_BEGIN draw_seq=%llu id=%llu media_pts=%.9f num_frames=%d paused_driver=%d mp_ns=%lld\\n",
            (unsigned long long)fci_draw_seq,
            (unsigned long long)p->fci_pending_frame_id,
            p->fci_pending_media_pts, frame->num_frames, p->paused,
            (long long)mp_time_ns());

    struct pl_render_params params = pars->params;
''',
        "gpu draw begin",
    )
    src = replace_once(
        src,
        '''        if (p->want_reset) {
            pl_queue_reset(p->queue);
            p->last_pts = 0.0;
''',
        '''        if (p->want_reset) {
            MP_INFO(vo,
                    "FCI_PL_QUEUE_RESET draw_seq=%llu incoming_id=%d incoming_pts=%.9f mp_ns=%lld\\n",
                    (unsigned long long)fci_draw_seq, id, frame->frames[n]->pts,
                    (long long)mp_time_ns());
            pl_queue_reset(p->queue);
            p->last_pts = 0.0;
''',
        "libplacebo queue reset",
    )
    src = replace_once(
        src,
        '''        struct frame_priv *fp = talloc_zero(mpi, struct frame_priv);
        mpi->priv = fp;
        fp->vo = vo;

        pl_queue_push(p->queue, &(struct pl_source_frame) {
''',
        '''        struct frame_priv *fp = talloc_zero(mpi, struct frame_priv);
        mpi->priv = fp;
        fp->vo = vo;
        fp->frame_id = id;
        MP_INFO(vo,
                "FCI_PL_PUSH draw_seq=%llu id=%d media_pts=%.9f image=%p mp_ns=%lld\\n",
                (unsigned long long)fci_draw_seq, id, mpi->pts, mpi,
                (long long)mp_time_ns());

        pl_queue_push(p->queue, &(struct pl_source_frame) {
''',
        "libplacebo queue push",
    )
    src = replace_once(
        src,
        '''        case PL_QUEUE_OK:
            break;
        }

        // Update source crop and overlays on all existing frames. We
''',
        '''        case PL_QUEUE_OK:
            break;
        }

        for (int i = 0; i < mix.num_frames; i++) {
            const struct pl_frame *selected = mix.frames[i];
            struct mp_image *selected_mpi = selected->user_data;
            struct frame_priv *selected_fp = selected_mpi->priv;
            MP_INFO(vo,
                    "FCI_PL_SELECT draw_seq=%llu slot=%d count=%d id=%llu media_pts=%.9f rel_time=%.6f signature=%llu mp_ns=%lld\\n",
                    (unsigned long long)fci_draw_seq, i, mix.num_frames,
                    (unsigned long long)selected_fp->frame_id,
                    selected_mpi->pts, mix.timestamps ? mix.timestamps[i] : 0.0f,
                    (unsigned long long)(mix.signatures ? mix.signatures[i] : 0),
                    (long long)mp_time_ns());
        }

        // Update source crop and overlays on all existing frames. We
''',
        "libplacebo selected mix",
    )
    src = replace_once(
        src,
        '''done:
    if (!valid) // clear with purple to indicate error
        pl_tex_clear(gpu, swframe.fbo, (float[4]){ 0.5, 0.0, 1.0, 1.0 });

    pl_gpu_flush(gpu);
''',
        '''done:
    if (!valid) // clear with purple to indicate error
        pl_tex_clear(gpu, swframe.fbo, (float[4]){ 0.5, 0.0, 1.0, 1.0 });

    MP_INFO(vo,
            "FCI_GPU_DRAW_END draw_seq=%llu id=%llu media_pts=%.9f valid=%d mp_ns=%lld\\n",
            (unsigned long long)fci_draw_seq,
            (unsigned long long)p->fci_pending_frame_id,
            p->fci_pending_media_pts, valid, (long long)mp_time_ns());
    pl_gpu_flush(gpu);
''',
        "gpu draw end",
    )
    old_flip = '''static void flip_page(struct vo *vo)
{
    struct priv *p = vo->priv;
    struct ra_swapchain *sw = p->ra_ctx->swapchain;

    if (p->frame_pending) {
        if (!pl_swapchain_submit_frame(p->sw))
            MP_ERR(vo, "Failed presenting frame!\\n");
        p->frame_pending = false;
    }

    sw->fns->swap_buffers(sw);
}
'''
    new_flip = '''static void flip_page(struct vo *vo)
{
    struct priv *p = vo->priv;
    struct ra_swapchain *sw = p->ra_ctx->swapchain;
    uint64_t swap_seq = ++p->fci_swap_seq;
    bool submitted = true;

    MP_INFO(vo,
            "FCI_GPU_SWAP_BEGIN swap_seq=%llu draw_seq=%llu id=%llu media_pts=%.9f pending=%d mp_ns=%lld\\n",
            (unsigned long long)swap_seq,
            (unsigned long long)p->fci_pending_draw_seq,
            (unsigned long long)p->fci_pending_frame_id,
            p->fci_pending_media_pts, p->frame_pending,
            (long long)mp_time_ns());
    if (p->frame_pending) {
        submitted = pl_swapchain_submit_frame(p->sw);
        if (!submitted)
            MP_ERR(vo, "Failed presenting frame!\\n");
        p->frame_pending = false;
    }

    sw->fns->swap_buffers(sw);
    MP_INFO(vo,
            "FCI_GPU_SWAP_END swap_seq=%llu draw_seq=%llu id=%llu media_pts=%.9f submitted=%d mp_ns=%lld\\n",
            (unsigned long long)swap_seq,
            (unsigned long long)p->fci_pending_draw_seq,
            (unsigned long long)p->fci_pending_frame_id,
            p->fci_pending_media_pts, submitted, (long long)mp_time_ns());
}
'''
    src = replace_once(src, old_flip, new_flip, "gpu swap")
    src = replace_once(
        src,
        '''    case VOCTRL_PAUSE:
        if (p->is_interpolated)
            vo->want_redraw = true;
        p->paused = true;
        return VO_TRUE;
    case VOCTRL_RESUME:
        p->paused = false;
        return VO_TRUE;
''',
        '''    case VOCTRL_PAUSE:
        MP_INFO(vo,
                "FCI_GPU_CONTROL state=PAUSE pending_draw_seq=%llu pending_id=%llu pending_pts=%.9f mp_ns=%lld\\n",
                (unsigned long long)p->fci_pending_draw_seq,
                (unsigned long long)p->fci_pending_frame_id,
                p->fci_pending_media_pts, (long long)mp_time_ns());
        if (p->is_interpolated)
            vo->want_redraw = true;
        p->paused = true;
        return VO_TRUE;
    case VOCTRL_RESUME:
        MP_INFO(vo,
                "FCI_GPU_CONTROL state=RESUME pending_draw_seq=%llu pending_id=%llu pending_pts=%.9f mp_ns=%lld\\n",
                (unsigned long long)p->fci_pending_draw_seq,
                (unsigned long long)p->fci_pending_frame_id,
                p->fci_pending_media_pts, (long long)mp_time_ns());
        p->paused = false;
        return VO_TRUE;
''',
        "gpu pause control arrival",
    )
    src = replace_once(
        src,
        '''    case VOCTRL_RESET:
        // Defer until the first new frame (unique ID) actually arrives
        p->want_reset = true;
        return VO_TRUE;
''',
        '''    case VOCTRL_RESET:
        MP_INFO(vo, "FCI_GPU_RESET_REQUEST mp_ns=%lld\\n",
                (long long)mp_time_ns());
        // Defer until the first new frame (unique ID) actually arrives
        p->want_reset = true;
        return VO_TRUE;
''',
        "gpu reset request",
    )
    p.write_text(src)


# Android EGL producer: record the actual eglSwapBuffers return. mpv does not
# use EGL_ANDROID_presentation_time here, and this build intentionally does not
# start using it because that would change scheduling behavior under test.
p, src = load("video/out/opengl/context_android.c")
if "FCI_EGL_SWAP" not in src:
    src = replace_once(
        src,
        '''#include "common/common.h"
#include "context.h"
''',
        '''#include "common/common.h"
#include "osdep/timer.h"
#include "context.h"
''',
        "Android EGL monotonic clock include",
    )
    src = replace_once(
        src,
        '''    EGLContext egl_context;
    EGLSurface egl_surface;
};
''',
        '''    EGLContext egl_context;
    EGLSurface egl_surface;
    uint64_t fci_swap_seq;
};
''',
        "Android EGL swap counter",
    )
    src = replace_once(
        src,
        '''static void android_swap_buffers(struct ra_ctx *ctx)
{
    struct priv *p = ctx->priv;
    eglSwapBuffers(p->egl_display, p->egl_surface);
}
''',
        '''static void android_swap_buffers(struct ra_ctx *ctx)
{
    struct priv *p = ctx->priv;
    uint64_t swap_seq = ++p->fci_swap_seq;
    int64_t begin = mp_time_ns();
    EGLBoolean ok = eglSwapBuffers(p->egl_display, p->egl_surface);
    EGLint error = ok ? EGL_SUCCESS : eglGetError();
    MP_INFO(ctx,
            "FCI_EGL_SWAP swap_seq=%llu result=%d egl_error=0x%x presentation_ts=unset begin_mp_ns=%lld end_mp_ns=%lld\\n",
            (unsigned long long)swap_seq, ok, error,
            (long long)begin, (long long)mp_time_ns());
}
''',
        "Android EGL swap",
    )
    p.write_text(src)
PY_FCI_MPV

unset CC CXX # meson wants these unset

meson setup $build --cross-file "$prefix_dir"/crossfile.txt \
	--default-library shared \
	-Diconv=disabled -D{lua,libcurl}=enabled \
	-Dlibmpv=true -Dcplayer=false \
	-Dmanpage-build=disabled

ninja -C $build -j$cores
if [ -f $build/libmpv.a ]; then
	echo >&2 "Meson fucked up, forcing rebuild."
	$0 clean
	exec $0 build
fi
DESTDIR="$prefix_dir" ninja -C $build install
