/* JNI bridge from the SapiDroid worker to the SAPI 4 engine (Sam, Mike, Mary and their modes). */
#include <jni.h>
#include <android/log.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "sapi4tts.h"

typedef struct {
    int16_t *data;
    size_t count;
    size_t cap;
    int failed;
} pcm_buf;

static int on_pcm(const int16_t *pcm, size_t n, void *user) {
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

JNIEXPORT jint JNICALL
Java_org_sapidroid_tts_NativeSapi4_init(JNIEnv *env, jclass clazz, jstring dllPath) {
    (void)clazz;
    if (!dllPath) return -1;
    const char *p = (*env)->GetStringUTFChars(env, dllPath, NULL);
    int rc = s4_init(p);
    (*env)->ReleaseStringUTFChars(env, dllPath, p);
    __android_log_print(4, "SapiDroidSapi4", "s4_init rc=%d", rc);
    return rc;
}

JNIEXPORT jobjectArray JNICALL
Java_org_sapidroid_tts_NativeSapi4_listModes(JNIEnv *env, jclass clazz, jstring voiceDir) {
    (void)clazz;
    s4_mode modes[64];
    const char *d = (*env)->GetStringUTFChars(env, voiceDir, NULL);
    int n = s4_list_modes(d, modes, 64);
    (*env)->ReleaseStringUTFChars(env, voiceDir, d);
    if (n <= 0) return NULL;
    jclass strClass = (*env)->FindClass(env, "java/lang/String");
    jobjectArray out = (*env)->NewObjectArray(env, n, strClass, NULL);
    for (int i = 0; i < n; i++) {
        jstring s = (*env)->NewStringUTF(env, modes[i].name);
        (*env)->SetObjectArrayElement(env, out, i, s);
        (*env)->DeleteLocalRef(env, s);
    }
    return out;
}

JNIEXPORT jlong JNICALL
Java_org_sapidroid_tts_NativeSapi4_open(JNIEnv *env, jclass clazz,
                                        jstring voiceDir, jstring mode) {
    (void)clazz;
    if (!voiceDir || !mode) return 0;
    const char *d = (*env)->GetStringUTFChars(env, voiceDir, NULL);
    const char *m = (*env)->GetStringUTFChars(env, mode, NULL);
    char err[256];
    err[0] = 0;
    s4_engine *e = s4_open(d, m, err, sizeof err);
    if (!e) {
        __android_log_print(4, "SapiDroidSapi4", "s4_open(%s) failed: %s", m, err);
    }
    (*env)->ReleaseStringUTFChars(env, voiceDir, d);
    (*env)->ReleaseStringUTFChars(env, mode, m);
    return (jlong)(intptr_t)e;
}

JNIEXPORT jintArray JNICALL
Java_org_sapidroid_tts_NativeSapi4_limits(JNIEnv *env, jclass clazz, jlong h) {
    (void)clazz;
    s4_limits lim;
    memset(&lim, 0, sizeof lim);
    if (!h) return NULL;
    s4_get_limits((s4_engine *)(intptr_t)h, &lim);
    jint vals[6];
    vals[0] = (jint)lim.pitch_default;
    vals[1] = (jint)lim.pitch_min;
    vals[2] = (jint)lim.pitch_max;
    vals[3] = (jint)lim.speed_default;
    vals[4] = (jint)lim.speed_min;
    vals[5] = (jint)lim.speed_max;
    jintArray out = (*env)->NewIntArray(env, 6);
    if (out) (*env)->SetIntArrayRegion(env, out, 0, 6, vals);
    return out;
}

JNIEXPORT jint JNICALL
Java_org_sapidroid_tts_NativeSapi4_sampleRate(JNIEnv *env, jclass clazz, jlong h) {
    (void)env; (void)clazz;
    return h ? s4_sample_rate((s4_engine *)(intptr_t)h) : 0;
}

JNIEXPORT void JNICALL
Java_org_sapidroid_tts_NativeSapi4_setSpeed(JNIEnv *env, jclass clazz, jlong h, jint wpm) {
    (void)env; (void)clazz;
    if (h) s4_set_speed((s4_engine *)(intptr_t)h, (unsigned)wpm);
}

JNIEXPORT void JNICALL
Java_org_sapidroid_tts_NativeSapi4_setPitch(JNIEnv *env, jclass clazz, jlong h, jint pitch) {
    (void)env; (void)clazz;
    if (h) s4_set_pitch((s4_engine *)(intptr_t)h, (unsigned)pitch);
}

/* Blocking; s4_stop from another thread stops it. Text is Windows-1252. */
JNIEXPORT jshortArray JNICALL
Java_org_sapidroid_tts_NativeSapi4_speak(JNIEnv *env, jclass clazz, jlong h, jbyteArray text) {
    (void)clazz;
    if (!h || !text) return NULL;
    jsize len = (*env)->GetArrayLength(env, text);
    char *c_text = (char *)malloc((size_t)len + 1);
    if (!c_text) return NULL;
    (*env)->GetByteArrayRegion(env, text, 0, len, (jbyte *)c_text);
    c_text[len] = 0;

    pcm_buf buf;
    memset(&buf, 0, sizeof buf);
    int rc = s4_speak((s4_engine *)(intptr_t)h, c_text, 0, on_pcm, &buf);
    free(c_text);
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
        __android_log_print(4, "SapiDroidSapi4", "s4_speak failed rc=%d", rc);
    }
    return out;
}

JNIEXPORT void JNICALL
Java_org_sapidroid_tts_NativeSapi4_cancel(JNIEnv *env, jclass clazz, jlong h) {
    (void)env; (void)clazz;
    if (h) s4_stop((s4_engine *)(intptr_t)h);
}

JNIEXPORT void JNICALL
Java_org_sapidroid_tts_NativeSapi4_close(JNIEnv *env, jclass clazz, jlong h) {
    (void)env; (void)clazz;
    if (h) s4_close((s4_engine *)(intptr_t)h);
}
