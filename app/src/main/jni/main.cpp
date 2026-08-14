#include <jni.h>
#include <stdlib.h>
#include <stdio.h>
#include <time.h>
#include <locale.h>
#include <atomic>
#include <stdint.h>

#include <mpv/client.h>

#include <pthread.h>

extern "C" {
    #include <libavcodec/jni.h>
}

#include "log.h"
#include "jni_utils.h"
#include "event.h"

#define ARRAYLEN(a) (sizeof(a)/sizeof(a[0]))

extern "C" {
    jni_func(void, create, jobject appctx);
    jni_func(void, init);
    jni_func(void, destroy);

    jni_func(void, command, jobjectArray jarray);
    jni_func(jint, commandAsync, jobjectArray jarray, jlong userdata);
    jni_func(void, abortAsyncCommand, jlong userdata);
    jni_func(jint, commandLoadFile, jstring path, jstring flags,
             jobjectArray option_names, jobjectArray option_values);
};

JavaVM *g_vm;
mpv_handle *g_mpv;
std::atomic<bool> g_event_thread_request_exit(false);

static pthread_t event_thread_id;
static jobject global_appctx;

static void prepare_environment(JNIEnv *env, jobject appctx) {
    setlocale(LC_NUMERIC, "C");

    g_vm = NULL;
    env->GetJavaVM(&g_vm);
    if (!g_vm)
        die("failed to get jvm");
    av_jni_set_java_vm(g_vm, NULL);

    if (global_appctx)
        env->DeleteGlobalRef(global_appctx);
    global_appctx = env->NewGlobalRef(appctx);
    if (global_appctx)
        av_jni_set_android_app_ctx(global_appctx, NULL);

    init_methods_cache(env);
}

jni_func(void, create, jobject appctx) {
    if (g_mpv)
        die("mpv is already initialized");

    prepare_environment(env, appctx);

    g_mpv = mpv_create();
    if (!g_mpv)
        die("context init failed");

    // use terminal log level but request verbose messages
    // this way --msg-level can be used to adjust later
    mpv_request_log_messages(g_mpv, "terminal-default");
    mpv_set_option_string(g_mpv, "msg-level", "all=v");
}

jni_func(void, init) {
    if (!g_mpv)
        die("mpv is not created");

    if (mpv_initialize(g_mpv) < 0)
        die("mpv init failed");

    g_event_thread_request_exit = false;
    if (pthread_create(&event_thread_id, NULL, event_thread, NULL) != 0)
        die("thread create failed");
    pthread_setname_np(event_thread_id, "event_thread");
}

jni_func(void, destroy) {
    if (!g_mpv) {
        ALOGV("mpv destroy called but it's already destroyed");
        return;
    }

    // poke event thread and wait for it to exit
    g_event_thread_request_exit = true;
    mpv_wakeup(g_mpv);
    pthread_join(event_thread_id, NULL);

    mpv_terminate_destroy(g_mpv);
    g_mpv = NULL;
}

jni_func(void, command, jobjectArray jarray) {
    CHECK_MPV_INIT();

    jstring strings[64] = {0};
    const char *arguments[64] = {0};
    jsize len = env->GetArrayLength(jarray);
    if (len >= ARRAYLEN(arguments)) // null-terminated
        die("too many command arguments");

    for (jsize i = 0; i < len; ++i) {
        strings[i] = (jstring)env->GetObjectArrayElement(jarray, i);
        arguments[i] = env->GetStringUTFChars(strings[i], NULL);
    }

    mpv_command(g_mpv, arguments);

    for (jsize i = 0; i < len; ++i) {
        env->ReleaseStringUTFChars(strings[i], arguments[i]);
        env->DeleteLocalRef(strings[i]);
    }
}

jni_func(jint, commandLoadFile, jstring jpath, jstring jflags,
         jobjectArray joption_names, jobjectArray joption_values) {
    CHECK_MPV_INIT();

    constexpr int MAX_OPTIONS = 64;
    const jsize option_count = env->GetArrayLength(joption_names);
    if (option_count != env->GetArrayLength(joption_values) ||
        option_count > MAX_OPTIONS) {
        ALOGE("invalid loadfile option arrays");
        return MPV_ERROR_INVALID_PARAMETER;
    }

    const char *path = env->GetStringUTFChars(jpath, NULL);
    const char *flags = env->GetStringUTFChars(jflags, NULL);

    jstring option_names[MAX_OPTIONS] = {0};
    jstring option_values[MAX_OPTIONS] = {0};
    char *option_keys[MAX_OPTIONS] = {0};
    mpv_node option_nodes[MAX_OPTIONS] = {};

    for (jsize i = 0; i < option_count; ++i) {
        option_names[i] = (jstring)env->GetObjectArrayElement(joption_names, i);
        option_values[i] = (jstring)env->GetObjectArrayElement(joption_values, i);
        option_keys[i] = const_cast<char *>(
            env->GetStringUTFChars(option_names[i], NULL));
        option_nodes[i].format = MPV_FORMAT_STRING;
        option_nodes[i].u.string = const_cast<char *>(
            env->GetStringUTFChars(option_values[i], NULL));
    }

    mpv_node_list option_map = {};
    option_map.num = option_count;
    option_map.keys = option_keys;
    option_map.values = option_nodes;

    mpv_node arguments[5] = {};
    arguments[0].format = MPV_FORMAT_STRING;
    arguments[0].u.string = const_cast<char *>("loadfile");
    arguments[1].format = MPV_FORMAT_STRING;
    arguments[1].u.string = const_cast<char *>(path);
    arguments[2].format = MPV_FORMAT_STRING;
    arguments[2].u.string = const_cast<char *>(flags);
    arguments[3].format = MPV_FORMAT_INT64;
    arguments[3].u.int64 = -1;
    arguments[4].format = MPV_FORMAT_NODE_MAP;
    arguments[4].u.list = &option_map;

    mpv_node_list command_array = {};
    command_array.num = ARRAYLEN(arguments);
    command_array.values = arguments;

    mpv_node command = {};
    command.format = MPV_FORMAT_NODE_ARRAY;
    command.u.list = &command_array;

    mpv_node result = {};
    const int err = mpv_command_node(g_mpv, &command, &result);
    if (err < 0)
        ALOGE("loadfile command returned error %s", mpv_error_string(err));
    mpv_free_node_contents(&result);

    for (jsize i = 0; i < option_count; ++i) {
        env->ReleaseStringUTFChars(option_names[i], option_keys[i]);
        env->ReleaseStringUTFChars(option_values[i], option_nodes[i].u.string);
        env->DeleteLocalRef(option_names[i]);
        env->DeleteLocalRef(option_values[i]);
    }
    env->ReleaseStringUTFChars(jpath, path);
    env->ReleaseStringUTFChars(jflags, flags);

    return err;
}

jni_func(jint, commandAsync, jobjectArray jarray, jlong userdata) {
    CHECK_MPV_INIT();

    const char *arguments[128] = {0};
    jstring jstrings[128] = {0};
    int len = env->GetArrayLength(jarray);
    if (len >= ARRAYLEN(arguments))
        die("too many command arguments");

    for (int i = 0; i < len; ++i) {
        jstrings[i] = (jstring)env->GetObjectArrayElement(jarray, i);
        arguments[i] = env->GetStringUTFChars(jstrings[i], NULL);
    }

    int err = mpv_command_async(g_mpv, (uint64_t)userdata, arguments);

    for (int i = 0; i < len; ++i) {
        env->ReleaseStringUTFChars(jstrings[i], arguments[i]);
        env->DeleteLocalRef(jstrings[i]);
    }

    return err;
}

jni_func(void, abortAsyncCommand, jlong userdata) {
    CHECK_MPV_INIT();
    mpv_abort_async_command(g_mpv, (uint64_t)userdata);
}
