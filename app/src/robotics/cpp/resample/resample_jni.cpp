/**
 * Fast bicubic resampling and unsharp mask for HD image processing.
 */

#include "resample_core.h"

#include <jni.h>

extern "C" {

JNIEXPORT jintArray JNICALL
Java_ab_inque_robotics_resample_ResampleNative_bicubicResample(
    JNIEnv* env, jclass,
    jintArray inPixels, jint inW, jint inH, jint outW, jint outH) {
    jsize inLen = env->GetArrayLength(inPixels);
    if (inLen < inW * inH) return nullptr;
    jint* in = env->GetIntArrayElements(inPixels, nullptr);
    if (!in) return nullptr;
    jintArray outArray = env->NewIntArray(outW * outH);
    if (!outArray) {
        env->ReleaseIntArrayElements(inPixels, in, JNI_ABORT);
        return nullptr;
    }
    jint* out = env->GetIntArrayElements(outArray, nullptr);
    if (!out) {
        env->ReleaseIntArrayElements(inPixels, in, JNI_ABORT);
        return nullptr;
    }
    robotics::resample::bicubicResample(
        reinterpret_cast<const int32_t*>(in), inW, inH,
        reinterpret_cast<int32_t*>(out), outW, outH);
    env->ReleaseIntArrayElements(inPixels, in, JNI_ABORT);
    env->ReleaseIntArrayElements(outArray, out, 0);
    return outArray;
}

JNIEXPORT void JNICALL
Java_ab_inque_robotics_resample_ResampleNative_unsharpMask(
    JNIEnv* env, jclass,
    jintArray pixels, jint w, jint h, jint radius, jfloat amount) {
    if (env->GetArrayLength(pixels) < w * h) return;
    jint* px = env->GetIntArrayElements(pixels, nullptr);
    if (!px) return;
    robotics::resample::unsharpMask(
        reinterpret_cast<int32_t*>(px), w, h, radius, amount);
    env->ReleaseIntArrayElements(pixels, px, 0);
}

}  // extern "C"
