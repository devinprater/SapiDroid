/* JNI bridge from the SapiDroid worker to the SAPI 5 engine (Microsoft Sam, Mike, Mary). */
#include <jni.h>
#include <android/log.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "sam_tts.h"

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
Java_org_sapidroid_tts_NativeSapi5_open(JNIEnv *env, jclass clazz,
                                        jstring dataDir, jstring voice) {
    (void)clazz;
    if (!dataDir || !voice) return 0;
    const char *d = (*env)->GetStringUTFChars(env, dataDir, NULL);
    const char *v = (*env)->GetStringUTFChars(env, voice, NULL);
    char err[256];
    err[0] = 0;
    sam_speech *t = sam_tts_open(d, v, err, sizeof err);
    if (!t) {
        __android_log_print(4, "SapiDroidSapi5", "open(%s) failed: %s", v, err);
    }
    (*env)->ReleaseStringUTFChars(env, dataDir, d);
    (*env)->ReleaseStringUTFChars(env, voice, v);
    return (jlong)(intptr_t)t;
}

JNIEXPORT void JNICALL
Java_org_sapidroid_tts_NativeSapi5_close(JNIEnv *env, jclass clazz, jlong h) {
    (void)env; (void)clazz;
    if (h) sam_tts_close((sam_speech *)(intptr_t)h);
}

JNIEXPORT void JNICALL
Java_org_sapidroid_tts_NativeSapi5_setRate(JNIEnv *env, jclass clazz, jlong h, jint rate) {
    (void)env; (void)clazz;
    if (h) sam_tts_set_rate((sam_speech *)(intptr_t)h, rate);
}

JNIEXPORT void JNICALL
Java_org_sapidroid_tts_NativeSapi5_setPitch(JNIEnv *env, jclass clazz, jlong h, jint pitch) {
    (void)env; (void)clazz;
    if (h) sam_tts_set_pitch((sam_speech *)(intptr_t)h, pitch);
}

JNIEXPORT void JNICALL
Java_org_sapidroid_tts_NativeSapi5_setVolume(JNIEnv *env, jclass clazz, jlong h, jint volume) {
    (void)env; (void)clazz;
    if (h) sam_tts_set_volume((sam_speech *)(intptr_t)h, volume);
}

JNIEXPORT jint JNICALL
Java_org_sapidroid_tts_NativeSapi5_sampleRate(JNIEnv *env, jclass clazz, jlong h) {
    (void)env; (void)clazz;
    return h ? sam_tts_sample_rate((sam_speech *)(intptr_t)h) : 0;
}

/* Blocking; sam_tts_cancel from another thread stops it. */
JNIEXPORT jshortArray JNICALL
Java_org_sapidroid_tts_NativeSapi5_speak(JNIEnv *env, jclass clazz, jlong h, jstring text) {
    (void)clazz;
    if (!h || !text) return NULL;
    const char *c_text = (*env)->GetStringUTFChars(env, text, NULL);
    if (!c_text) return NULL;

    pcm_buf buf;
    memset(&buf, 0, sizeof buf);
    sam_callbacks cb;
    memset(&cb, 0, sizeof cb);
    cb.audio = on_audio;
    cb.user = &buf;

    int rc = sam_tts_speak((sam_speech *)(intptr_t)h, c_text, 0, &cb);
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
        __android_log_print(4, "SapiDroidSapi5", "speak failed rc=%d", rc);
    }
    return out;
}

JNIEXPORT void JNICALL
Java_org_sapidroid_tts_NativeSapi5_cancel(JNIEnv *env, jclass clazz, jlong h) {
    (void)env; (void)clazz;
    if (h) sam_tts_cancel((sam_speech *)(intptr_t)h);
}
