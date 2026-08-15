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

# Samsung-style pause/resume for Android AudioTrack + gpu-next video queue.
#
# Normal pause preserves queued audio/video and freezes their realtime deadlines.
# Full resets (seek/reconfigure/EOF) still flush exactly as upstream expects.
python3 - <<'PY_SAMSUNG_AUDIO_PAUSE'
from pathlib import Path

path = Path("audio/out/ao_audiotrack.c")
src = path.read_text()
marker = "mpv-android: Samsung-style AudioTrack pause/resume"
if marker not in src:
    def replace_once(old, new, what):
        global src
        count = src.count(old)
        if count != 1:
            raise SystemExit(f"mpv Samsung AudioTrack patch failed: expected one {what}, found {count}")
        src = src.replace(old, new, 1)

    if "#include <string.h>\n" not in src:
        replace_once('#include "ao.h"\n', '#include <string.h>\n\n#include "ao.h"\n', "ao.h include anchor")

    replace_once(
        "    void *chunk;\n    int chunksize;\n",
        "    void *chunk;\n"
        "    int chunksize;\n"
        "    int pending_bytes; // mpv-android: Samsung-style AudioTrack pause/resume\n",
        "AudioTrack chunk fields",
    )

    func_start = src.find("static MP_THREAD_VOID ao_thread(void *arg)")
    func_end = src.find("\nstatic void uninit(struct ao *ao)", func_start)
    if func_start < 0 or func_end < 0:
        raise SystemExit("mpv Samsung AudioTrack patch failed: ao_thread not found")
    chunk = src[func_start:func_end]
    branch_start = chunk.find("        if (state == AudioTrack.PLAYSTATE_PLAYING) {")
    wait_anchor = (
        "        } else {\n"
        "            mp_cond_timedwait(&p->wakeup, &p->lock, MP_TIME_MS_TO_NS(300));\n"
        "        }\n"
    )
    branch_end = chunk.find(wait_anchor, branch_start)
    if branch_start < 0 or branch_end < 0:
        raise SystemExit("mpv Samsung AudioTrack patch failed: ao_thread PLAYING branch not found")
    branch_end += len(wait_anchor)
    new_branch = r'''        if (state == AudioTrack.PLAYSTATE_PLAYING) {
            // pause() can interrupt a blocking write. Keep the unwritten tail
            // and submit it first after resume instead of dropping samples.
            int bytes = p->pending_bytes;
            if (!bytes) {
                int read_samples = p->chunksize / ao->sstride;
                int64_t ts = mp_time_ns();
                ts += MP_TIME_S_TO_NS(read_samples / (double)(ao->samplerate));
                ts += MP_TIME_S_TO_NS(AudioTrack_getLatency(ao));
                int samples = ao_read_data(ao, &p->chunk, read_samples, ts,
                                           NULL, false, false);
                bytes = samples * ao->sstride;
            }

            int ret = AudioTrack_write(ao, bytes);
            if (ret >= 0) {
                mp_assert(ret <= bytes);
                mp_assert(ret % ao->sstride == 0);
                p->written_frames += ret / ao->sstride;
                p->pending_bytes = bytes - ret;
                if (ret > 0 && p->pending_bytes > 0)
                    memmove(p->chunk, (char *)p->chunk + ret, p->pending_bytes);
            } else if (ret == AudioManager.ERROR_DEAD_OBJECT) {
                MP_WARN(ao, "AudioTrack.write failed with ERROR_DEAD_OBJECT. Recreating AudioTrack...\n");
                if (AudioTrack_Recreate(ao) < 0)
                    MP_ERR(ao, "AudioTrack_Recreate failed\n");
            } else {
                MP_ERR(ao, "AudioTrack.write failed with %d\n", ret);
            }
        } else {
            mp_cond_timedwait(&p->wakeup, &p->lock, MP_TIME_MS_TO_NS(300));
        }
'''
    chunk = chunk[:branch_start] + new_branch + chunk[branch_end:]
    src = src[:func_start] + chunk + src[func_end:]

    ctrl_start = src.find("static void stop(struct ao *ao)")
    ctrl_end = src.find("\n#define OPT_BASE_STRUCT struct priv", ctrl_start)
    if ctrl_start < 0 or ctrl_end < 0:
        raise SystemExit("mpv Samsung AudioTrack patch failed: AudioTrack control functions not found")

    new_ctrl = r'''static void invalidate_audio_timestamp(struct priv *p)
{
    // Never extrapolate a cached pre-pause AudioTimestamp over wall-clock time
    // spent paused. The first post-resume clock read must be fresh.
    p->timestamp_fetched = 0;
    p->timestamp_set = false;
    p->timestamp_stable = 0;
}

static void stop(struct ao *ao)
{
    struct priv *p = ao->priv;
    if (!p->audiotrack) {
        MP_ERR(ao, "AudioTrack does not exist to stop!\n");
        return;
    }

    // Real reset only: interrupt the writer, then flush queued audio.
    JNIEnv *env = MP_JNI_GET_ENV(ao);
    MP_JNI_CALL_VOID(p->audiotrack, AudioTrack.pause);
    if (MP_JNI_EXCEPTION_LOG(ao) < 0)
        return;

    mp_mutex_lock(&p->lock);
    MP_JNI_CALL_VOID(p->audiotrack, AudioTrack.flush);
    MP_JNI_EXCEPTION_LOG(ao);
    p->pending_bytes = 0;
    p->playhead_offset = 0;
    p->reset_pending = true;
    p->written_frames = 0;
    p->playhead_pos = 0;
    invalidate_audio_timestamp(p);
    mp_mutex_unlock(&p->lock);
}

static bool set_pause(struct ao *ao, bool paused)
{
    struct priv *p = ao->priv;
    if (!p->audiotrack) {
        MP_ERR(ao, "AudioTrack does not exist to %s!\n", paused ? "pause" : "resume");
        return false;
    }

    JNIEnv *env = MP_JNI_GET_ENV(ao);
    if (paused) {
        // pause() must happen before taking p->lock so it can interrupt a
        // blocking AudioTrack.write() owned by the AO thread.
        MP_JNI_CALL_VOID(p->audiotrack, AudioTrack.pause);
        if (MP_JNI_EXCEPTION_LOG(ao) < 0)
            return false;

        mp_mutex_lock(&p->lock);
        invalidate_audio_timestamp(p);
        int pending = p->pending_bytes;
        mp_mutex_unlock(&p->lock);
        MP_VERBOSE(ao, "pause: AudioTrack queue preserved, pending=%d bytes\n", pending);
    } else {
        mp_mutex_lock(&p->lock);
        invalidate_audio_timestamp(p);
        mp_mutex_unlock(&p->lock);

        MP_JNI_CALL_VOID(p->audiotrack, AudioTrack.play);
        if (MP_JNI_EXCEPTION_LOG(ao) < 0)
            return false;

        mp_cond_signal(&p->wakeup);
        MP_VERBOSE(ao, "resume: AudioTrack queue preserved; timestamp refreshed\n");
    }
    return true;
}

static void start(struct ao *ao)
{
    set_pause(ao, false);
}
'''
    src = src[:ctrl_start] + new_ctrl + src[ctrl_end:]

    driver_start = src.find("const struct ao_driver audio_out_audiotrack = {")
    driver_end = src.find("\n};", driver_start)
    if driver_start < 0 or driver_end < 0:
        raise SystemExit("mpv Samsung AudioTrack patch failed: driver table not found")
    driver = src[driver_start:driver_end]
    if ".set_pause" not in driver:
        old = "    .start     = start,\n"
        if old not in driver:
            raise SystemExit("mpv Samsung AudioTrack patch failed: driver start entry not found")
        driver = driver.replace(old, old + "    .set_pause = set_pause,\n", 1)
        src = src[:driver_start] + driver + src[driver_end:]

    path.write_text(src)
PY_SAMSUNG_AUDIO_PAUSE

# Reset gpu-next/libplacebo's private frame queue on user pause.
#
# The generic VO queue can contain only the retained current frame while gpu-next
# still owns a separate pl_queue of interpolation/reference frames. Keeping that
# private queue across pause/resume lets its virtual presentation timeline remain
# ahead of the frame visibly retained during pause. Reset it on the paused redraw
# so the retained frame becomes the new queue anchor, while leaving interpolation
# and normal framedrop policy untouched.
python3 - <<'PY_GPU_NEXT_PAUSE_QUEUE'
from pathlib import Path

path = Path("video/out/vo_gpu_next.c")
src = path.read_text()
marker = "mpv-android: reset libplacebo queue on pause"
if marker not in src:
    old = '''    case VOCTRL_PAUSE:
        if (p->is_interpolated)
            vo->want_redraw = true;
        p->paused = true;
        return VO_TRUE;
    case VOCTRL_RESUME:
        p->paused = false;
        return VO_TRUE;
'''
    new = '''    case VOCTRL_PAUSE:
        // mpv-android: reset libplacebo queue on pause.
        //
        // gpu-next has its own pl_queue in addition to mpv's generic VO queue.
        // In particular, interpolation can leave future/reference frames in
        // that queue even when the generic VO reports only one retained frame.
        // Reset on the redraw performed while paused: draw_frame() then runs
        // with the mixer disabled, clears pl_queue/renderer cache through the
        // existing want_reset path, and re-anchors it to the retained frame.
        p->paused = true;
        p->want_reset = true;
        vo->want_redraw = true;
        MP_VERBOSE(vo, "pause: scheduling libplacebo queue reset at retained frame\\n");
        return VO_TRUE;
    case VOCTRL_RESUME:
        p->paused = false;
        MP_VERBOSE(vo, "resume: libplacebo queue anchored to paused frame\\n");
        return VO_TRUE;
'''
    count = src.count(old)
    if count != 1:
        raise SystemExit(
            f"mpv gpu-next pause-queue patch failed: expected one VOCTRL_PAUSE/RESUME block, found {count}"
        )
    src = src.replace(old, new, 1)
    path.write_text(src)
PY_GPU_NEXT_PAUSE_QUEUE

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
