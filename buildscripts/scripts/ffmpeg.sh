#!/bin/bash -e

. ../../include/path.sh

if [ "$1" == "build" ]; then
	true
elif [ "$1" == "clean" ]; then
	rm -rf _build$ndk_suffix
	exit 0
else
	exit 255
fi

# Pause/resume frame-continuity instrumentation. This deliberately changes no
# MediaCodec policy: it only promotes the existing per-buffer lifecycle data to
# stable FCI_* log records and gives every hardware output buffer a trace ID.
python3 - <<'PY_FCI_FFMPEG'
from pathlib import Path


def replace_once(text, old, new, label):
    if text.count(old) != 1:
        raise SystemExit(f"FFmpeg FCI instrumentation failed at {label}: "
                         f"expected one anchor, found {text.count(old)}")
    return text.replace(old, new, 1)


header = Path("libavcodec/mediacodecdec_common.h")
common = Path("libavcodec/mediacodecdec_common.c")
public = Path("libavcodec/mediacodec.c")

if "FCI_CODEC_DEQUEUE" not in common.read_text():
    src = header.read_text()
    src = replace_once(
        src,
        "    int64_t pts;\n    atomic_int released;\n",
        "    int64_t pts;\n"
        "    uint64_t fci_seq; // mpv-android frame-continuity trace ID\n"
        "    atomic_int released;\n",
        "MediaCodecBuffer trace field",
    )
    header.write_text(src)

    src = common.read_text()
    src = replace_once(
        src,
        '''        av_log(ctx->avctx, AV_LOG_DEBUG,
               "Releasing output buffer %zd (%p) ts=%"PRId64" on free() [%d pending]\\n",
               buffer->index, buffer, buffer->pts, atomic_load(&ctx->hw_buffer_count));
''',
        '''        av_log(ctx->avctx, AV_LOG_INFO,
               "FCI_CODEC_RELEASE seq=%"PRIu64" index=%zd buffer=%p pts_us=%"PRId64
               " render=0 reason=free pending=%d mono_us=%"PRId64"\\n",
               buffer->fci_seq, buffer->index, buffer, buffer->pts,
               atomic_load(&ctx->hw_buffer_count), av_gettime_relative());
''',
        "hardware buffer free log",
    )
    src = replace_once(
        src,
        "    buffer->index = index;\n    buffer->pts = info->presentationTimeUs;\n",
        "    buffer->index = index;\n"
        "    buffer->pts = info->presentationTimeUs;\n"
        "    buffer->fci_seq = s->output_buffer_count + 1;\n",
        "hardware buffer trace ID assignment",
    )
    src = replace_once(
        src,
        '''    av_log(avctx, AV_LOG_DEBUG,
            "Wrapping output buffer %zd (%p) ts=%"PRId64" [%d pending]\\n",
            buffer->index, buffer, buffer->pts, atomic_load(&s->hw_buffer_count));
''',
        '''    av_log(avctx, AV_LOG_INFO,
            "FCI_CODEC_WRAP seq=%"PRIu64" index=%zd buffer=%p pts_us=%"PRId64
            " pending=%d mono_us=%"PRId64"\\n",
            buffer->fci_seq, buffer->index, buffer, buffer->pts,
            atomic_load(&s->hw_buffer_count), av_gettime_relative());
''',
        "hardware buffer wrap log",
    )
    src = replace_once(
        src,
        '''        av_log(avctx, AV_LOG_TRACE, "Got output buffer %zd"
                " offset=%" PRIi32 " size=%" PRIi32 " ts=%" PRIi64
                " flags=%" PRIu32 "\\n", index, info.offset, info.size,
                info.presentationTimeUs, info.flags);
''',
        '''        av_log(avctx, AV_LOG_INFO,
               "FCI_CODEC_DEQUEUE seq=%"PRIu64" index=%zd pts_us=%"PRId64
               " offset=%"PRId32" size=%"PRId32" flags=%"PRIu32
               " pending=%d mono_us=%"PRId64"\\n",
               s->output_buffer_count + 1, index, info.presentationTimeUs,
               info.offset, info.size, info.flags,
               atomic_load(&s->hw_buffer_count), av_gettime_relative());
''',
        "codec dequeue log",
    )

    start = src.find("static int mediacodec_wrap_sw_video_buffer(")
    end = src.find("static int mediacodec_wrap_sw_buffer(", start)
    if start < 0 or end < 0:
        raise SystemExit("FFmpeg FCI instrumentation failed: software video wrapper not found")
    segment = src[start:end]
    segment = replace_once(
        segment,
        "done:\n    status = ff_AMediaCodec_releaseOutputBuffer(s->codec, index, 0);\n",
        "done:\n"
        "    av_log(avctx, AV_LOG_INFO,\n"
        "           \"FCI_CODEC_COPY_RELEASE seq=%\"PRIu64\" index=%zd pts_us=%\"PRId64\n"
        "           \" render=0 mono_us=%\"PRId64\"\\n\",\n"
        "           s->output_buffer_count + 1, index, info->presentationTimeUs,\n"
        "           av_gettime_relative());\n"
        "    status = ff_AMediaCodec_releaseOutputBuffer(s->codec, index, 0);\n",
        "software video release log",
    )
    src = src[:start] + segment + src[end:]
    common.write_text(src)

    src = public.read_text()
    src = replace_once(
        src,
        '#include "libavutil/mem.h"\n',
        '#include "libavutil/mem.h"\n#include "libavutil/time.h"\n',
        "relative clock include",
    )
    src = replace_once(
        src,
        '''        av_log(ctx->avctx, AV_LOG_DEBUG,
               "Releasing output buffer %zd (%p) ts=%"PRId64" with render=%d [%d pending]\\n",
               buffer->index, buffer, buffer->pts, render, atomic_load(&ctx->hw_buffer_count));
''',
        '''        av_log(ctx->avctx, AV_LOG_INFO,
               "FCI_CODEC_RELEASE seq=%"PRIu64" index=%zd buffer=%p pts_us=%"PRId64
               " render=%d reason=explicit pending=%d mono_us=%"PRId64"\\n",
               buffer->fci_seq, buffer->index, buffer, buffer->pts, render,
               atomic_load(&ctx->hw_buffer_count), av_gettime_relative());
''',
        "explicit release log",
    )
    src = replace_once(
        src,
        '''        av_log(ctx->avctx, AV_LOG_DEBUG,
               "Rendering output buffer %zd (%p) ts=%"PRId64" with time=%"PRId64" [%d pending]\\n",
               buffer->index, buffer, buffer->pts, time, atomic_load(&ctx->hw_buffer_count));
''',
        '''        av_log(ctx->avctx, AV_LOG_INFO,
               "FCI_CODEC_RELEASE_AT seq=%"PRIu64" index=%zd buffer=%p pts_us=%"PRId64
               " target_ns=%"PRId64" pending=%d mono_us=%"PRId64"\\n",
               buffer->fci_seq, buffer->index, buffer, buffer->pts, time,
               atomic_load(&ctx->hw_buffer_count), av_gettime_relative());
''',
        "timed release log",
    )
    public.write_text(src)
PY_FCI_FFMPEG

mkdir -p _build$ndk_suffix
cd _build$ndk_suffix

cpu=armv7-a
[[ "$ndk_triple" == "aarch64"* ]] && cpu=armv8-a
[[ "$ndk_triple" == "x86_64"* ]] && cpu=generic
[[ "$ndk_triple" == "i686"* ]] && cpu="i686 --disable-asm"

cpuflags=
[[ "$ndk_triple" == "arm"* ]] && cpuflags="$cpuflags -mfpu=neon -mcpu=cortex-a8"

args=(
	--target-os=android --enable-cross-compile
	--cross-prefix=$ndk_triple- --cc=$CC --pkg-config=pkg-config --nm=llvm-nm
	--arch=${ndk_triple%%-*} --cpu=$cpu
	--extra-cflags="-I$prefix_dir/include $cpuflags" --extra-ldflags="-L$prefix_dir/lib"

	--enable-{jni,mediacodec,mbedtls,libdav1d,libxml2} --disable-vulkan
	--disable-static --enable-shared --enable-{gpl,version3}

	# disable unneeded parts
	--disable-{stripping,doc,programs}
	# to keep the build lean we disable some feature quite aggressively:
	# - muxers, encoders: mpv-android does not have any way to use these
	# - devices: no practical use on Android
	--disable-{muxers,encoders,devices}
	# useful to taking screenshots
	--enable-encoder=mjpeg,png
	# useful for the `dump-cache` command
	--enable-muxer=mov,matroska,mpegts
)
../configure "${args[@]}"

make -j$cores
make DESTDIR="$prefix_dir" install
