#include <jni.h>

#include <memory>
#include <vector>

#include "DrumEngine.h"

namespace {
std::unique_ptr<DrumEngine> gEngine;

DrumEngine &engine() {
    if (!gEngine) gEngine = std::make_unique<DrumEngine>();
    return *gEngine;
}
}  // namespace

#define JNI_FN(name) Java_com_amihay_drumtrainer_audio_NativeEngine_##name

extern "C" {

JNIEXPORT jboolean JNICALL JNI_FN(nativeStart)(JNIEnv *, jobject) {
    return engine().start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nativeStop)(JNIEnv *, jobject) { engine().stop(); }

JNIEXPORT jboolean JNICALL JNI_FN(nativeIsRunning)(JNIEnv *, jobject) {
    return engine().isRunning() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeNeedsRestart)(JNIEnv *, jobject) {
    return engine().needsRestart() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL JNI_FN(nativeSampleRate)(JNIEnv *, jobject) { return engine().sampleRate(); }

JNIEXPORT jlong JNICALL JNI_FN(nativePlayheadFrame)(JNIEnv *, jobject) {
    return static_cast<jlong>(engine().playheadFrame());
}

JNIEXPORT jlong JNICALL JNI_FN(nativeWriteFrame)(JNIEnv *, jobject) {
    return static_cast<jlong>(engine().writeFrame());
}

JNIEXPORT void JNICALL JNI_FN(nativeSetClicks)(JNIEnv *env, jobject, jlongArray frames, jintArray types) {
    jsize n = env->GetArrayLength(frames);
    std::vector<jlong> f(n);
    std::vector<jint> t(n);
    env->GetLongArrayRegion(frames, 0, n, f.data());
    env->GetIntArrayRegion(types, 0, n, t.data());
    std::vector<ScheduledClick> clicks(n);
    for (jsize i = 0; i < n; ++i) clicks[i] = {static_cast<int64_t>(f[i]), static_cast<int>(t[i])};
    engine().setClicks(clicks);
}

JNIEXPORT void JNICALL JNI_FN(nativeClearClicks)(JNIEnv *, jobject) { engine().clearClicks(); }

JNIEXPORT void JNICALL JNI_FN(nativeSetSensitivity)(JNIEnv *, jobject, jfloat s) {
    engine().setSensitivity(s);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetNotch)(JNIEnv *, jobject, jboolean enabled) {
    engine().setNotchEnabled(enabled == JNI_TRUE);
}

// Fills out[] with (frame, strength) pairs; returns the number of onsets.
JNIEXPORT jint JNICALL JNI_FN(nativePollOnsets)(JNIEnv *env, jobject, jdoubleArray out) {
    jsize cap = env->GetArrayLength(out) / 2;
    std::vector<AlignedOnset> buf(cap);
    int n = engine().pollOnsets(buf.data(), cap);
    std::vector<jdouble> flat(n * 2);
    for (int i = 0; i < n; ++i) {
        flat[2 * i] = buf[i].frame;
        flat[2 * i + 1] = buf[i].strength;
    }
    if (n > 0) env->SetDoubleArrayRegion(out, 0, n * 2, flat.data());
    return n;
}

JNIEXPORT jfloat JNICALL JNI_FN(nativeInputPeak)(JNIEnv *, jobject) { return engine().inputPeak(); }

JNIEXPORT jboolean JNICALL JNI_FN(nativeTimestampsAligned)(JNIEnv *, jobject) {
    return engine().timestampsAligned() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL JNI_FN(nativeXruns)(JNIEnv *, jobject) { return engine().xruns(); }

}  // extern "C"
