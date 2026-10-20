/* JNI bridge from the SapiDroid worker to the Microsoft Anna engine. */
#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include "anna_tts.h"

typedef struct {
    int16_t *data;
    size_t count;
    size_t cap;
    int failed;
} pcm_buf;

static int on_audio(const int16_t *pcm, size_t n, void *user) {
    pcm_buf *b = (pcm_buf *)user;
    if (b->count + n > b->cap) {
        size_t cap = b->cap ? b->cap : 32768;
        while (cap < b->count + n) cap *= 2;
        int16_t *grown = (int16_t *)realloc(b->data, cap * sizeof(int16_t));
        if (!grown) {
            b->failed = 1;
            return 1;
        }
        b->data = grown;
        b->cap = cap;
    }
    memcpy(b->data + b->count, pcm, n * sizeof(int16_t));
    b->count += n;
    return 0;
}

JNIEXPORT jlong JNICALL
Java_org_sapidroid_tts_NativeAnna_open(JNIEnv *env, jclass clazz, jstring dir) {
    (void)clazz;
    if (!dir) return 0;
    const char *c_dir = (*env)->GetStringUTFChars(env, dir, NULL);
    char err[256];
    err[0] = 0;
    anna_tts *t = anna_tts_open(c_dir, err, sizeof err);
    if (!t) {
        __android_log_print(4, "SapiDroidNative", "anna open failed: %s", err);
    }
    (*env)->ReleaseStringUTFChars(env, dir, c_dir);
    return (jlong)(intptr_t)t;
}

JNIEXPORT void JNICALL
Java_org_sapidroid_tts_NativeAnna_close(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    if (handle) anna_tts_close((anna_tts *)(intptr_t)handle);
}

JNIEXPORT void JNICALL
Java_org_sapidroid_tts_NativeAnna_setRate(JNIEnv *env, jclass clazz, jlong h, jint rate) {
    (void)env; (void)clazz;
    if (h) anna_tts_set_rate((anna_tts *)(intptr_t)h, rate);
}

JNIEXPORT void JNICALL
Java_org_sapidroid_tts_NativeAnna_setPitch(JNIEnv *env, jclass clazz, jlong h, jint pitch) {
    (void)env; (void)clazz;
    if (h) anna_tts_set_pitch((anna_tts *)(intptr_t)h, pitch);
}

JNIEXPORT void JNICALL
Java_org_sapidroid_tts_NativeAnna_setVolume(JNIEnv *env, jclass clazz, jlong h, jint volume) {
    (void)env; (void)clazz;
    if (h) anna_tts_set_volume((anna_tts *)(intptr_t)h, volume);
}

/* Blocking: returns the samples produced, or NULL on failure.
 * anna_tts_cancel may be called from another thread and stops it within a chunk. */
JNIEXPORT jshortArray JNICALL
Java_org_sapidroid_tts_NativeAnna_speak(JNIEnv *env, jclass clazz, jlong h, jstring text) {
    (void)clazz;
    if (!h || !text) return NULL;
    const char *c_text = (*env)->GetStringUTFChars(env, text, NULL);
    if (!c_text) return NULL;

    pcm_buf buf;
    memset(&buf, 0, sizeof buf);
    anna_callbacks cb;
    memset(&cb, 0, sizeof cb);
    cb.audio = on_audio;
    cb.user = &buf;

    int rc = anna_tts_speak((anna_tts *)(intptr_t)h, c_text, 0, &cb);
    (*env)->ReleaseStringUTFChars(env, text, c_text);

    if (buf.failed) {
        free(buf.data);
        return NULL;
    }
    jshortArray out = (*env)->NewShortArray(env, (jsize)buf.count);
    if (out && buf.count) {
        (*env)->SetShortArrayRegion(env, out, 0, (jsize)buf.count, buf.data);
    }
    free(buf.data);
    if (rc < 0) {
        __android_log_print(4, "SapiDroidNative", "anna speak failed rc=%d", rc);
    }
    return out;
}

JNIEXPORT void JNICALL
Java_org_sapidroid_tts_NativeAnna_cancel(JNIEnv *env, jclass clazz, jlong h) {
    (void)env; (void)clazz;
    if (h) anna_tts_cancel((anna_tts *)(intptr_t)h);
}
