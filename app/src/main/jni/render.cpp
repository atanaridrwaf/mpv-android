#include <jni.h>

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <mpv/client.h>

#include "jni_utils.h"
#include "log.h"
#include "globals.h"

extern "C" {
    jni_func(void, attachSurface, jobject surface_);
    jni_func(void, detachSurface);
};

static jobject surface;
static ANativeWindow *native_window;

jni_func(void, attachSurface, jobject surface_) {
    CHECK_MPV_INIT();

    surface = env->NewGlobalRef(surface_);
    if (!surface)
        die("invalid surface provided");

    /*
     * Keep the Java Surface and the native window on an explicit 32-bit
     * opaque RGB format. Leaving the native window format unspecified lets
     * some Android EGL/SurfaceFlinger paths select a low-precision RGB_565
     * buffer for SurfaceView. That path is visually catastrophic on scanned
     * printed images because RGB565 quantization/dithering aliases with the
     * print-dot pattern, producing the green/magenta ghosting seen in the
     * bug report. Forcing RGBX_8888 fixes the issue below mpv's scaler layer,
     * so changing dscale/cscale is no longer relevant.
     */
    native_window = ANativeWindow_fromSurface(env, surface_);
    if (native_window) {
        int before_format = ANativeWindow_getFormat(native_window);
        int ret = ANativeWindow_setBuffersGeometry(native_window, 0, 0, WINDOW_FORMAT_RGBX_8888);
        int after_format = ANativeWindow_getFormat(native_window);
        ALOGV("ANativeWindow format %d -> %d", before_format, after_format);
        if (ret < 0)
            ALOGE("ANativeWindow_setBuffersGeometry(RGBX_8888) failed: %d", ret);
    } else {
        ALOGE("ANativeWindow_fromSurface failed; continuing with Java Surface only");
    }

    int64_t wid = reinterpret_cast<intptr_t>(surface);
    int result = mpv_set_option(g_mpv, "wid", MPV_FORMAT_INT64, &wid);
    if (result < 0)
         ALOGE("mpv_set_option(wid) returned error %s", mpv_error_string(result));
}

jni_func(void, detachSurface) {
    CHECK_MPV_INIT();

    int64_t wid = 0;
    int result = mpv_set_option(g_mpv, "wid", MPV_FORMAT_INT64, &wid);
    if (result < 0)
         ALOGE("mpv_set_option(wid) returned error %s", mpv_error_string(result));

    if (native_window) {
        ANativeWindow_release(native_window);
        native_window = NULL;
    }
    env->DeleteGlobalRef(surface);
    surface = NULL;
}
