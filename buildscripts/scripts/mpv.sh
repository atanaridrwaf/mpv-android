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

# Keep AudioTrack's pull-AO presentation timeline continuous across normal
# pause/resume and within each post-reset stream epoch. For contiguous PCM,
# advance the previous end time by the duration of the samples actually read;
# use the measured AudioTrack clock only to seed a new/expired epoch.
python3 - <<'PY_AUDIO_CLOCK_CONTINUITY'
from pathlib import Path
import re


def function_span(text, signature, label):
    starts = [m.start() for m in re.finditer(re.escape(signature), text)]
    if len(starts) != 1:
        raise SystemExit(f"AudioTrack continuity fix failed at {label}: "
                         f"expected one function signature, found {len(starts)}")
    start = starts[0]
    brace = text.find("{", start + len(signature))
    if brace < 0:
        raise SystemExit(f"AudioTrack continuity fix failed at {label}: opening brace not found")
    depth = 0
    for i in range(brace, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return start, i + 1
    raise SystemExit(f"AudioTrack continuity fix failed at {label}: closing brace not found")


def regex_once(text, pattern, repl, label, flags=0):
    rx = re.compile(pattern, flags)
    matches = list(rx.finditer(text))
    if len(matches) != 1:
        raise SystemExit(f"AudioTrack continuity fix failed at {label}: "
                         f"expected one anchor, found {len(matches)}")
    return rx.sub(repl, text, count=1)


def regex_in_function(text, signature, pattern, repl, label, flags=0):
    start, end = function_span(text, signature, label)
    fn = text[start:end]
    fn = regex_once(fn, pattern, repl, label, flags)
    return text[:start] + fn + text[end:]


p = Path("audio/out/internal.h")
src = p.read_text()
if "ao_read_data_with_start_time" not in src:
    src = regex_once(
        src,
        r'(?m)^(?P<i>[ \t]*)int ao_read_data\(struct ao \*ao, void \*\*data, int samples, int64_t out_time_ns, bool \*eof, bool pad_silence, bool blocking\);[ \t]*$',
        lambda m: m.group(0) + '\n' + m.group('i') +
            'int ao_read_data_with_start_time(struct ao *ao, void **data, int samples,\n' +
            m.group('i') + '                                 int64_t out_start_time_ns, bool *eof,\n' +
            m.group('i') + '                                 bool pad_silence, bool blocking);',
        "internal pull-AO prototype",
    )
    p.write_text(src)


p = Path("audio/out/buffer.c")
src = p.read_text()
if "ao_read_data_with_start_time" not in src:
    src = regex_once(
        src,
        r'static int ao_read_data_locked\(struct ao \*ao, void \*\*data, int samples,\n(?P<i>[ \t]*)int64_t out_time_ns, bool \*eof, bool pad_silence\)',
        lambda m: 'static int ao_read_data_locked(struct ao *ao, void **data, int samples,\n' +
                  m.group('i') + 'int64_t out_time_ns, bool out_time_is_start,\n' +
                  m.group('i') + 'bool keep_contiguous, bool *eof, bool pad_silence)',
        "buffer locked signature",
    )

    endtime_block = r'''if (pos > 0) {
        int64_t old_end = p->end_time_ns;
        int64_t now = mp_time_ns();
        int64_t duration_ns = MP_TIME_S_TO_NS(pos / (double)ao->samplerate);
        int64_t measured_end = out_time_ns + (out_time_is_start ? duration_ns : 0);
        int64_t predicted_end = old_end > 0 ? old_end + duration_ns : measured_end;

        if (keep_contiguous && old_end > 0 && predicted_end >= now)
            p->end_time_ns = predicted_end;
        else
            p->end_time_ns = measured_end;
    }'''
    src = regex_in_function(
        src,
        "static int ao_read_data_locked(struct ao *ao, void **data, int samples,",
        r'(?m)^(?P<i>[ \t]*)if[ \t]*\(pos[ \t]*>[ \t]*0\)[ \t]*\n[ \t]+p->end_time_ns[ \t]*=[ \t]*out_time_ns;[ \t]*$',
        lambda m: m.group('i') + endtime_block,
        "buffer end-time assignment",
    )

    src = regex_in_function(
        src,
        "int ao_read_data(struct ao *ao, void **data, int samples,",
        r'ao_read_data_locked\(ao, data, samples, out_time_ns, eof, pad_silence\)',
        'ao_read_data_locked(ao, data, samples, out_time_ns, false, false, eof, pad_silence)',
        "existing ao_read_data call",
    )

    start, end = function_span(
        src,
        "int ao_read_data(struct ao *ao, void **data, int samples,",
        "ao_read_data function",
    )
    helper = r'''

// Same locking/underrun semantics as ao_read_data(), but out_start_time_ns is
// the expected output time of the first returned sample. The common buffer then
// derives the last-sample time from the number of samples actually returned.
int ao_read_data_with_start_time(struct ao *ao, void **data, int samples,
                                 int64_t out_start_time_ns, bool *eof,
                                 bool pad_silence, bool blocking)
{
    struct buffer_state *p = ao->buffer_state;
    if (blocking) {
        mp_mutex_lock(&p->lock);
    } else if (mp_mutex_trylock(&p->lock)) {
        return 0;
    }

    bool eof_buf;
    if (eof == NULL)
        eof = &eof_buf;

    int pos = ao_read_data_locked(ao, data, samples, out_start_time_ns,
                                  true, true, eof, pad_silence);

    mp_mutex_unlock(&p->lock);
    return pos;
}
'''
    src = src[:end] + helper + src[end:]
    p.write_text(src)


p = Path("audio/out/ao_audiotrack.c")
src = p.read_text()
if "ao_read_data_with_start_time(ao, &p->chunk" not in src:
    src = regex_in_function(
        src,
        "static MP_THREAD_VOID ao_thread(void *arg)",
        r'(?m)^(?P<i>[ \t]*)ts \+= MP_TIME_S_TO_NS\(read_samples / \(double\)\(ao->samplerate\)\);[ \t]*\n',
        '',
        "AudioTrack requested-duration removal",
    )
    src = regex_in_function(
        src,
        "static MP_THREAD_VOID ao_thread(void *arg)",
        r'ao_read_data\(ao, &p->chunk, read_samples, ts,',
        'ao_read_data_with_start_time(ao, &p->chunk, read_samples, ts,',
        "AudioTrack actual-sample timed read",
    )
    p.write_text(src)
PY_AUDIO_CLOCK_CONTINUITY

# The first pull after reset/seek may occur before AudioTimestamp is available.
# In that one state, do not seed the new epoch from Android's Java getLatency()
# fallback; it is a pipeline estimate rather than a reliable absolute anchor.
python3 - <<'PY_AUDIO_EPOCH_SEED_GUARD'
from pathlib import Path
import re


def function_span(text, signature, label):
    starts = [m.start() for m in re.finditer(re.escape(signature), text)]
    if len(starts) != 1:
        raise SystemExit(f"AudioTrack epoch seed guard failed at {label}: "
                         f"expected one function signature, found {len(starts)}")
    start = starts[0]
    brace = text.find("{", start + len(signature))
    if brace < 0:
        raise SystemExit(f"AudioTrack epoch seed guard failed at {label}: opening brace not found")
    depth = 0
    for i in range(brace, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return start, i + 1
    raise SystemExit(f"AudioTrack epoch seed guard failed at {label}: closing brace not found")


def regex_once(text, pattern, repl, label, flags=0):
    rx = re.compile(pattern, flags)
    matches = list(rx.finditer(text))
    if len(matches) != 1:
        raise SystemExit(f"AudioTrack epoch seed guard failed at {label}: "
                         f"expected one anchor, found {len(matches)}")
    return rx.sub(repl, text, count=1)


p = Path("audio/out/ao_audiotrack.c")
src = p.read_text()
if "AudioTrack_getTimelineLatency" not in src:
    if "ao_read_data_with_start_time" not in src:
        raise SystemExit("AudioTrack epoch seed guard requires the contiguous timeline patch")

    helper = r'''

static double AudioTrack_getTimelineLatency(struct ao *ao)
{
    struct priv *p = ao->priv;
    double measured = AudioTrack_getLatency(ao);

    if (p->written_frames == 0 && !p->timestamp_set &&
        p->format != AudioFormat.ENCODING_IEC61937)
        return 0;

    return measured;
}
'''
    src = regex_once(
        src,
        r'(?m)^static MP_THREAD_VOID ao_thread\(void \*arg\)\n\{$',
        lambda m: helper + '\n' + m.group(0),
        "seed helper insertion",
    )

    start, end = function_span(src, "static MP_THREAD_VOID ao_thread(void *arg)", "ao thread")
    fn = src[start:end]
    fn = regex_once(
        fn,
        r'MP_TIME_S_TO_NS\(AudioTrack_getLatency\(ao\)\)',
        'MP_TIME_S_TO_NS(AudioTrack_getTimelineLatency(ao))',
        "ao thread latency call",
    )
    src = src[:start] + fn + src[end:]
    p.write_text(src)
PY_AUDIO_EPOCH_SEED_GUARD

# Track only persistent long-term clock-rate drift. Fixed phase offsets are not
# correction targets, and a live contiguous timeline is never hard-reanchored.
# Three consecutive 30-second windows must agree on drift direction before a
# bounded rate-only correction is allowed.
python3 - <<'PY_AUDIO_LONG_TERM_DRIFT'
from pathlib import Path
import re


def function_span(text, signature, label):
    starts = [m.start() for m in re.finditer(re.escape(signature), text)]
    if len(starts) != 1:
        raise SystemExit(f"AudioTrack drift servo failed at {label}: "
                         f"expected one function signature, found {len(starts)}")
    start = starts[0]
    brace = text.find("{", start + len(signature))
    if brace < 0:
        raise SystemExit(f"AudioTrack drift servo failed at {label}: opening brace not found")
    depth = 0
    for i in range(brace, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return start, i + 1
    raise SystemExit(f"AudioTrack drift servo failed at {label}: closing brace not found")


def block_span(text, start, label):
    brace = text.find("{", start)
    if brace < 0:
        raise SystemExit(f"AudioTrack drift servo failed at {label}: opening brace not found")
    depth = 0
    for i in range(brace, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return start, i + 1
    raise SystemExit(f"AudioTrack drift servo failed at {label}: closing brace not found")


def regex_once(text, pattern, repl, label, flags=0):
    rx = re.compile(pattern, flags)
    matches = list(rx.finditer(text))
    if len(matches) != 1:
        raise SystemExit(f"AudioTrack drift servo failed at {label}: "
                         f"expected one anchor, found {len(matches)}")
    return rx.sub(repl, text, count=1)


def regex_in_function(text, signature, pattern, repl, label, flags=0):
    start, end = function_span(text, signature, label)
    fn = text[start:end]
    fn = regex_once(fn, pattern, repl, label, flags)
    return text[:start] + fn + text[end:]


p = Path("audio/out/buffer.c")
src = p.read_text()
if "contiguous_drift_rate_ppb" not in src:
    if "ao_read_data_with_start_time" not in src:
        raise SystemExit("AudioTrack drift servo requires the contiguous timeline patch")

    src = regex_once(
        src,
        r'(?m)^(?P<i>[ \t]*)int64_t[ \t]+end_time_ns;[ \t]*//[ \t]*absolute output time of last played sample[ \t]*$',
        lambda m: m.group(0) + '\n' +
            m.group('i') + 'bool contiguous_drift_tracking;\n' +
            m.group('i') + 'int contiguous_drift_good_samples;\n' +
            m.group('i') + 'int64_t contiguous_drift_anchor_now_ns;\n' +
            m.group('i') + 'int64_t contiguous_drift_anchor_phase_ns;\n' +
            m.group('i') + 'int64_t contiguous_drift_prev_now_ns;\n' +
            m.group('i') + 'int64_t contiguous_drift_prev_phase_ns;\n' +
            m.group('i') + 'int64_t contiguous_drift_rate_ppb;\n' +
            m.group('i') + 'int contiguous_drift_confirm_sign;\n' +
            m.group('i') + 'int contiguous_drift_confirm_windows;\n' +
            m.group('i') + 'int64_t contiguous_drift_confirm_sum_ppb;',
        "buffer drift state",
    )

    helper = r'''

static void reset_contiguous_clock_drift(struct buffer_state *p)
{
    p->contiguous_drift_tracking = false;
    p->contiguous_drift_good_samples = 0;
    p->contiguous_drift_anchor_now_ns = 0;
    p->contiguous_drift_anchor_phase_ns = 0;
    p->contiguous_drift_prev_now_ns = 0;
    p->contiguous_drift_prev_phase_ns = 0;
    p->contiguous_drift_rate_ppb = 0;
    p->contiguous_drift_confirm_sign = 0;
    p->contiguous_drift_confirm_windows = 0;
    p->contiguous_drift_confirm_sum_ppb = 0;
}
'''
    src = regex_once(
        src,
        r'(?m)^};\n\nstatic MP_THREAD_VOID ao_thread\(void \*arg\);$',
        lambda m: '};' + helper + '\nstatic MP_THREAD_VOID ao_thread(void *arg);',
        "drift reset helper",
    )

    src = regex_in_function(
        src,
        "void ao_set_paused(struct ao *ao, bool paused, bool eof)",
        r'(?m)^(?P<i>[ \t]*)p->paused = paused;[ \t]*$',
        lambda m: m.group('i') + 'if (p->paused != paused)\n' +
                  m.group('i') + '    reset_contiguous_clock_drift(p);\n' +
                  m.group('i') + 'p->paused = paused;',
        "pause/resume observer reset",
    )

    src = regex_in_function(
        src,
        "void ao_reset(struct ao *ao)",
        r'(?m)^(?P<i>[ \t]*)p->end_time_ns = 0;[ \t]*$',
        lambda m: m.group('i') + 'p->end_time_ns = 0;\n' +
                  m.group('i') + 'reset_contiguous_clock_drift(p);',
        "reset observer state",
    )

    fn_start, fn_end = function_span(
        src,
        "static int ao_read_data_locked(struct ao *ao, void **data, int samples,",
        "contiguous end-time function",
    )
    fn = src[fn_start:fn_end]
    if "bool keep_contiguous" not in fn:
        raise SystemExit("AudioTrack drift servo: contiguous end-time function shape not found")
    if_start = fn.find("if (pos > 0) {")
    if if_start < 0:
        raise SystemExit("AudioTrack drift servo: pos>0 block not found")
    old_start, old_end = block_span(fn, if_start, "contiguous end-time block")

    endtime_block = r'''if (pos > 0) {
        int64_t old_end = p->end_time_ns;
        int64_t now = mp_time_ns();
        int64_t duration_ns = MP_TIME_S_TO_NS(pos / (double)ao->samplerate);
        int64_t measured_end = out_time_ns + (out_time_is_start ? duration_ns : 0);
        int64_t predicted_end = old_end > 0 ? old_end + duration_ns : measured_end;
        int64_t new_end = measured_end;
        int64_t phase_error = measured_end - predicted_end;
        int64_t slew_ns = 0;

        if (keep_contiguous && old_end > 0 && predicted_end >= now) {
            const int64_t drift_jump_ns = 20 * 1000 * 1000LL;
            const int64_t drift_gap_ns = 1000 * 1000 * 1000LL;
            const int64_t drift_window_ns = 30LL * 1000 * 1000 * 1000;
            const int64_t drift_min_delta_ns = 200 * 1000LL;
            const int64_t drift_max_observed_ppb = 2LL * 1000 * 1000;
            const int64_t drift_max_rate_ppb = 500 * 1000LL;
            const int64_t drift_max_rate_step_ppb = 125 * 1000LL;
            const int64_t drift_deadband_ppb = 5 * 1000LL;
            const int drift_confirm_required = 3;

            bool reset_observer = false;
            if (p->contiguous_drift_tracking) {
                int64_t sample_dt = now - p->contiguous_drift_prev_now_ns;
                int64_t phase_step = phase_error - p->contiguous_drift_prev_phase_ns;
                int64_t abs_phase_step = phase_step < 0 ? -phase_step : phase_step;
                if (sample_dt <= 0 || sample_dt > drift_gap_ns ||
                    abs_phase_step > drift_jump_ns)
                    reset_observer = true;
            }

            if (!p->contiguous_drift_tracking || reset_observer) {
                reset_contiguous_clock_drift(p);
                p->contiguous_drift_tracking = true;
                p->contiguous_drift_anchor_now_ns = now;
                p->contiguous_drift_anchor_phase_ns = phase_error;
                p->contiguous_drift_prev_now_ns = now;
                p->contiguous_drift_prev_phase_ns = phase_error;
            } else {
                p->contiguous_drift_good_samples++;
                int64_t window_ns = now - p->contiguous_drift_anchor_now_ns;

                if (window_ns >= drift_window_ns &&
                    p->contiguous_drift_good_samples >= 96)
                {
                    int64_t phase_delta =
                        phase_error - p->contiguous_drift_anchor_phase_ns;
                    int64_t abs_phase_delta = phase_delta < 0 ? -phase_delta : phase_delta;
                    int64_t residual_ppb = phase_delta * 1000000000LL / window_ns;
                    int64_t abs_residual_ppb = residual_ppb < 0 ? -residual_ppb : residual_ppb;
                    int64_t old_rate_ppb = p->contiguous_drift_rate_ppb;

                    if (abs_residual_ppb > drift_max_observed_ppb) {
                        p->contiguous_drift_rate_ppb = 0;
                        p->contiguous_drift_confirm_sign = 0;
                        p->contiguous_drift_confirm_windows = 0;
                        p->contiguous_drift_confirm_sum_ppb = 0;
                    } else if (abs_phase_delta < drift_min_delta_ns ||
                               abs_residual_ppb < drift_deadband_ppb)
                    {
                        p->contiguous_drift_confirm_sign = 0;
                        p->contiguous_drift_confirm_windows = 0;
                        p->contiguous_drift_confirm_sum_ppb = 0;
                    } else {
                        int sign = residual_ppb > 0 ? 1 : -1;
                        if (p->contiguous_drift_confirm_sign != sign) {
                            p->contiguous_drift_confirm_sign = sign;
                            p->contiguous_drift_confirm_windows = 1;
                            p->contiguous_drift_confirm_sum_ppb = residual_ppb;
                        } else {
                            p->contiguous_drift_confirm_windows++;
                            p->contiguous_drift_confirm_sum_ppb += residual_ppb;
                        }

                        if (p->contiguous_drift_confirm_windows >=
                            drift_confirm_required)
                        {
                            int64_t qualified_ppb =
                                p->contiguous_drift_confirm_sum_ppb /
                                p->contiguous_drift_confirm_windows;
                            int64_t estimated_rate_ppb = old_rate_ppb + qualified_ppb;
                            if (estimated_rate_ppb > drift_max_rate_ppb)
                                estimated_rate_ppb = drift_max_rate_ppb;
                            if (estimated_rate_ppb < -drift_max_rate_ppb)
                                estimated_rate_ppb = -drift_max_rate_ppb;

                            int64_t rate_step_ppb =
                                (estimated_rate_ppb - old_rate_ppb) / 4;
                            if (rate_step_ppb > drift_max_rate_step_ppb)
                                rate_step_ppb = drift_max_rate_step_ppb;
                            if (rate_step_ppb < -drift_max_rate_step_ppb)
                                rate_step_ppb = -drift_max_rate_step_ppb;

                            if (rate_step_ppb != 0)
                                p->contiguous_drift_rate_ppb += rate_step_ppb;

                            p->contiguous_drift_confirm_sign = 0;
                            p->contiguous_drift_confirm_windows = 0;
                            p->contiguous_drift_confirm_sum_ppb = 0;
                        }
                    }

                    p->contiguous_drift_anchor_now_ns = now;
                    p->contiguous_drift_anchor_phase_ns = phase_error;
                    p->contiguous_drift_good_samples = 0;
                }

                p->contiguous_drift_prev_now_ns = now;
                p->contiguous_drift_prev_phase_ns = phase_error;
            }

            slew_ns = duration_ns * p->contiguous_drift_rate_ppb / 1000000000LL;
            int64_t max_slew_ns = 100 * 1000LL;
            if (slew_ns > max_slew_ns)
                slew_ns = max_slew_ns;
            if (slew_ns < -max_slew_ns)
                slew_ns = -max_slew_ns;
            if (slew_ns < 0 && predicted_end + slew_ns < now + 1000 * 1000LL)
                slew_ns = 0;

            new_end = predicted_end + slew_ns;
        } else if (keep_contiguous && old_end > 0) {
            reset_contiguous_clock_drift(p);
        } else if (keep_contiguous) {
            reset_contiguous_clock_drift(p);
        }

        p->end_time_ns = new_end;
    }'''

    fn = fn[:old_start] + endtime_block + fn[old_end:]
    src = src[:fn_start] + fn + src[fn_end:]
    p.write_text(src)
PY_AUDIO_LONG_TERM_DRIFT

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
