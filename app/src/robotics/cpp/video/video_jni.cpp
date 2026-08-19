/**
 * Native video processing: ARGB to I420 conversion for encoder input.
 */

#include "video_core.h"

#include <jni.h>

extern "C" {

JNIEXPORT jboolean JNICALL
Java_ab_inque_robotics_video_VideoNative_argbToI420(
    JNIEnv* env, jclass,
    jintArray pixelsArgb, jint width, jint height, jbyteArray outI420) {
    if (pixelsArgb == nullptr || outI420 == nullptr || width <= 0 || height <= 0)
        return JNI_FALSE;
    const int ySize = width * height;
    const int i420Size = ySize + (width / 2) * (height / 2) * 2;
    if (env->GetArrayLength(outI420) < i420Size)
        return JNI_FALSE;
    jint* argb = env->GetIntArrayElements(pixelsArgb, nullptr);
    jbyte* i420 = env->GetByteArrayElements(outI420, nullptr);
    if (argb == nullptr || i420 == nullptr) {
        if (argb) env->ReleaseIntArrayElements(pixelsArgb, argb, JNI_ABORT);
        if (i420) env->ReleaseByteArrayElements(outI420, i420, JNI_ABORT);
        return JNI_FALSE;
    }
    robotics::video::argbToI420(
        reinterpret_cast<const int32_t*>(argb), width, height,
        reinterpret_cast<uint8_t*>(i420));
    env->ReleaseIntArrayElements(pixelsArgb, argb, JNI_ABORT);
    env->ReleaseByteArrayElements(outI420, i420, 0);
    return JNI_TRUE;
}

}  // extern "C"
