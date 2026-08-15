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
# AudioTrack.write() if pause interrupts that call. Also invalidate/rebase the
# cached AudioTimestamp around hardware pause: otherwise a pre-pause timestamp
# can be extrapolated across the paused wall-clock interval on resume, making
# the AO briefly under-report its buffered latency and pushing video sync ahead.
if ! grep -Eq '^[[:space:]]*\.set_pause[[:space:]]*=[[:space:]]*set_pause,' \
	audio/out/ao_audiotrack.c; then
	patch -p1 --forward --batch <<'PATCH'
diff --git a/audio/out/ao_audiotrack.c b/audio/out/ao_audiotrack.c
--- a/audio/out/ao_audiotrack.c
+++ b/audio/out/ao_audiotrack.c
@@ -19,5 +19,7 @@
  * License along with mpv.  If not, see <http://www.gnu.org/licenses/>.
  */
+#include <string.h>
+
 #include "ao.h"
 #include "internal.h"
 #include "common/msg.h"
@@ -41,6 +41,7 @@
     int64_t timestamp_fetched;
     bool timestamp_set;
     int timestamp_stable;
+    int64_t timestamp_resume_time;
 
     uint32_t written_frames; /* requires uint32_t rollover semantics */
     uint32_t playhead_pos;
@@ -49,6 +50,7 @@
 
     void *chunk;
     int chunksize;
+    int pending_bytes;
     jbyteArray bytearray;
     jshortArray shortarray;
     jfloatArray floatarray;
@@ -376,14 +376,23 @@
             p->timestamp_stable = 0;
         int64_t time1 = MP_JNI_GET_LONG(p->timestamp, AudioTimestamp.nanoTime);
         if (MP_JNI_CALL_BOOL(p->audiotrack, AudioTrack.getTimestamp, p->timestamp)) {
-            p->timestamp_set = true;
+            uint32_t fpos = 0xFFFFFFFFL &
+                MP_JNI_GET_LONG(p->timestamp, AudioTimestamp.framePosition);
+            int64_t time2 = MP_JNI_GET_LONG(p->timestamp, AudioTimestamp.nanoTime);
             p->timestamp_fetched = now;
-            if (p->timestamp_stable < stable_count) {
-                uint32_t fpos = 0xFFFFFFFFL & MP_JNI_GET_LONG(p->timestamp, AudioTimestamp.framePosition);
-                int64_t time2 = MP_JNI_GET_LONG(p->timestamp, AudioTimestamp.nanoTime);
-                //MP_VERBOSE(ao, "getTimestamp: fpos= %u / time= %"PRId64" / now= %"PRId64" / stable= %d\n", fpos, time2, now, p->timestamp_stable);
-                if (time1 != time2 && time2 != 0 && fpos != 0) {
-                    p->timestamp_stable++;
+
+            // play() can briefly expose the timestamp cached before pause().
+            // Do not extrapolate it across the paused wall-clock interval.
+            if (p->timestamp_resume_time && time2 < p->timestamp_resume_time) {
+                p->timestamp_set = false;
+                p->timestamp_stable = 0;
+            } else {
+                p->timestamp_set = true;
+                p->timestamp_resume_time = 0;
+                if (p->timestamp_stable < stable_count) {
+                    //MP_VERBOSE(ao, "getTimestamp: fpos= %u / time= %"PRId64" / now= %"PRId64" / stable= %d\n", fpos, time2, now, p->timestamp_stable);
+                    if (time1 != time2 && time2 != 0 && fpos != 0)
+                        p->timestamp_stable++;
                 }
             }
         }
@@ -543,12 +543,23 @@
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
@@ -758,27 +758,69 @@
 
     JNIEnv *env = MP_JNI_GET_ENV(ao);
     MP_JNI_CALL_VOID(p->audiotrack, AudioTrack.pause);
-    MP_JNI_EXCEPTION_LOG(ao);
+    if (MP_JNI_EXCEPTION_LOG(ao) < 0)
+        return;
+
+    // pause() interrupts a blocking write. Wait until the audio thread has
+    // accounted any short write before flushing the device.
+    mp_mutex_lock(&p->lock);
     MP_JNI_CALL_VOID(p->audiotrack, AudioTrack.flush);
     MP_JNI_EXCEPTION_LOG(ao);
+
+    p->pending_bytes = 0;
     p->playhead_offset = 0;
     p->reset_pending = true;
     p->written_frames = 0;
     p->timestamp_fetched = 0;
     p->timestamp_set = false;
+    p->timestamp_stable = 0;
+    p->timestamp_resume_time = 0;
+    mp_mutex_unlock(&p->lock);
+}
+
+static bool set_pause(struct ao *ao, bool paused)
+{
+    struct priv *p = ao->priv;
+    if (!p->audiotrack) {
+        MP_ERR(ao, "AudioTrack does not exist to %s!\n",
+               paused ? "pause" : "resume");
+        return false;
+    }
+
+    JNIEnv *env = MP_JNI_GET_ENV(ao);
+    if (paused) {
+        // Do not take p->lock before pause(): the audio thread may hold it in a
+        // blocking write, and pause() is what interrupts that write.
+        MP_JNI_CALL_VOID(p->audiotrack, AudioTrack.pause);
+        if (MP_JNI_EXCEPTION_LOG(ao) < 0)
+            return false;
+
+        mp_mutex_lock(&p->lock);
+        p->timestamp_fetched = 0;
+        p->timestamp_set = false;
+        p->timestamp_stable = 0;
+        p->timestamp_resume_time = 0;
+        mp_mutex_unlock(&p->lock);
+        return true;
+    }
+
+    // Serialize play() with the audio thread. It must not observe PLAYING until
+    // the timestamp cache has been invalidated and the resume boundary saved.
+    mp_mutex_lock(&p->lock);
+    p->timestamp_fetched = 0;
+    p->timestamp_set = false;
+    p->timestamp_stable = 0;
+    p->timestamp_resume_time = mp_raw_time_ns();
+    MP_JNI_CALL_VOID(p->audiotrack, AudioTrack.play);
+    int err = MP_JNI_EXCEPTION_LOG(ao);
+    if (err >= 0)
+        mp_cond_signal(&p->wakeup);
+    mp_mutex_unlock(&p->lock);
+
+    return err >= 0;
 }
 
 static void start(struct ao *ao)
 {
-    struct priv *p = ao->priv;
-    if (!p->audiotrack) {
-        MP_ERR(ao, "AudioTrack does not exist to start!\n");
-        return;
-    }
-
-    JNIEnv *env = MP_JNI_GET_ENV(ao);
-    MP_JNI_CALL_VOID(p->audiotrack, AudioTrack.play);
-    MP_JNI_EXCEPTION_LOG(ao);
-
-    mp_cond_signal(&p->wakeup);
+    set_pause(ao, false);
 }
@@ -789,6 +789,7 @@
     .uninit    = uninit,
     .reset     = stop,
     .start     = start,
+    .set_pause = set_pause,
     .priv_size = sizeof(struct priv),
     .priv_defaults = &(const OPT_BASE_STRUCT) {
         .cfg_pcm_float = 1,
PATCH
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
