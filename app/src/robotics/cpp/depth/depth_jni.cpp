/**
 * Native depth postprocess: normalize depth buffer and render to ARGB greyscale.
 * Called after TFLite depth model output; avoids heavy Java loops.
 */

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cmath>
#include <algorithm>
#include <vector>

#define LOG_TAG "DepthJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

/**
 * Normalize depth values to [0, 1] in place (min-max over the array).
 */
static void normalizeDepth(float* depth, int count) {
    float minVal = 1e9f, maxVal = -1e9f;
    for (int i = 0; i < count; i++) {
        float v = depth[i];
        if (std::isfinite(v)) {
            if (v < minVal) minVal = v;
            if (v > maxVal) maxVal = v;
        }
    }
    float range = maxVal - minVal;
    if (range <= 0.f) return;
    for (int i = 0; i < count; i++) {
        float v = (depth[i] - minVal) / range;
        if (v < 0.f) v = 0.f;
        if (v > 1.f) v = 1.f;
        depth[i] = v;
    }
}

/**
 * Render depth map to ARGB greyscale pixels.
 * depth: normalized [0,1] float array.
 * Supports both row-major (height, width) and column-major (width, height) layouts.
 * Use rowMajor=true for index = row*cols+col; false for index = col*rows+row.
 */
static void renderToGreyscale(const float* depth, int depthRows, int depthCols,
                              int32_t* outPixels, int outWidth, int outHeight,
                              bool rowMajor) {
    for (int y = 0; y < outHeight; y++) {
        int dy = (depthRows * y) / outHeight;
        if (dy >= depthRows) dy = depthRows - 1;
        for (int x = 0; x < outWidth; x++) {
            int dx = (depthCols * x) / outWidth;
            if (dx >= depthCols) dx = depthCols - 1;
            int depthIdx = rowMajor ? (dy * depthCols + dx) : (dx * depthRows + dy);
            float d = depth[depthIdx];
            int grey = static_cast<int>(d * 255.f);
            if (grey < 0) grey = 0;
            if (grey > 255) grey = 255;
            int idx = y * outWidth + x;
            outPixels[idx] = 0xFF000000u
                | (static_cast<uint32_t>(grey) << 16)
                | (static_cast<uint32_t>(grey) << 8)
                | static_cast<uint32_t>(grey);
        }
    }
}

extern "C" {

/**
 * JNI: Read depth from direct ByteBuffer, normalize, render to outPixels.
 * depthBuffer: direct ByteBuffer - either float (depthRows*depthCols*4) or uint8 (depthRows*depthCols)
 * quantized: true = uint8, false = float
 * outPixels: pre-allocated jintArray of length outWidth*outHeight; filled with ARGB.
 */
JNIEXPORT jboolean JNICALL
Java_ab_inque_robotics_vision_depth_DepthNative_depthRenderToGreyscale(
    JNIEnv* env, jclass /* clazz */,
    jobject depthBuffer, jint depthRows, jint depthCols, jboolean quantized,
    jint outWidth, jint outHeight, jintArray outPixels) {
    if (depthBuffer == nullptr || outPixels == nullptr) return JNI_FALSE;
    if (depthRows <= 0 || depthCols <= 0 || outWidth <= 0 || outHeight <= 0) return JNI_FALSE;

    void* ptr = env->GetDirectBufferAddress(depthBuffer);
    if (ptr == nullptr) return JNI_FALSE;

    const int depthCount = depthRows * depthCols;
    const int outCount = outWidth * outHeight;
    if (env->GetArrayLength(outPixels) < outCount) return JNI_FALSE;

    jint* pixels = env->GetIntArrayElements(outPixels, nullptr);
    if (pixels == nullptr) return JNI_FALSE;

    std::vector<float> depthFloat(static_cast<size_t>(depthCount));
    if (quantized) {
        const uint8_t* src = static_cast<const uint8_t*>(ptr);
        for (int i = 0; i < depthCount; i++) {
            depthFloat[i] = src[i] / 255.f;
        }
    } else {
        const float* src = static_cast<const float*>(ptr);
        std::memcpy(depthFloat.data(), src, static_cast<size_t>(depthCount) * sizeof(float));
    }

    normalizeDepth(depthFloat.data(), depthCount);
    // TFLite layout is row-major (index = row*cols+col). Column-major was only needed when Kotlin passed cols=1 due to 4D shape bug.
    renderToGreyscale(depthFloat.data(), depthRows, depthCols, pixels, outWidth, outHeight, true);

    env->ReleaseIntArrayElements(outPixels, pixels, 0);
    return JNI_TRUE;
}

}  // extern "C"
