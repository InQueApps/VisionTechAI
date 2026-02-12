/**
 * Native video processing: ARGB to I420 conversion for encoder input.
 * Used by ProcessedVideoRecorder so frame conversion runs in C++.
 */

#include <jni.h>
#include <android/log.h>
#include <cstring>

#define LOG_TAG "VideoJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static void argbToI420(const int32_t* argb, int width, int height, uint8_t* outI420) {
    const int ySize = width * height;
    const int uvSize = (width / 2) * (height / 2);
    int yIdx = 0;
    for (int row = 0; row < height; row++) {
        for (int col = 0; col < width; col++) {
            uint32_t p = static_cast<uint32_t>(argb[row * width + col]);
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            int y = ((66 * r + 129 * g + 25 * b + 128) >> 8);
            if (y < 0) y = 0;
            if (y > 255) y = 255;
            outI420[yIdx++] = static_cast<uint8_t>(y);
        }
    }
    for (int row = 0; row < height; row += 2) {
        for (int col = 0; col < width; col += 2) {
            uint32_t p = static_cast<uint32_t>(argb[row * width + col]);
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8);
            int v = ((112 * r - 94 * g - 18 * b + 128) >> 8);
            if (u < 0) u = 0;
            if (u > 255) u = 255;
            if (v < 0) v = 0;
            if (v > 255) v = 255;
            int uvIdx = (row / 2) * (width / 2) + (col / 2);
            outI420[ySize + uvIdx] = static_cast<uint8_t>(u);
            outI420[ySize + uvSize + uvIdx] = static_cast<uint8_t>(v);
        }
    }
}

extern "C" {

/**
 * Convert ARGB pixels (jintArray from Bitmap.getPixels) to I420 in outI420.
 * outI420 must be pre-allocated with size width*height*3/2.
 */
JNIEXPORT jboolean JNICALL
Java_ab_inque_visiontechai_VideoNative_argbToI420(
    JNIEnv* env, jclass /* clazz */,
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
    argbToI420(argb, width, height, reinterpret_cast<uint8_t*>(i420));
    env->ReleaseIntArrayElements(pixelsArgb, argb, JNI_ABORT);
    env->ReleaseByteArrayElements(outI420, i420, 0);
    return JNI_TRUE;
}

}  // extern "C"
