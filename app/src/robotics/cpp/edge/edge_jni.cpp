/**
 * Native Sobel edge detection (JNI).
 *
 * Pipeline per frame:
 *   1. Gaussian blur (3×3) to reduce noise
 *   2. Sobel X/Y gradient magnitude with adaptive threshold
 *   3. Morphological cleanup (isolated pixel removal + thinning)
 *
 * All kernels operate on the luminance channel for speed.
 * Output: black edges on white background (ARGB_8888).
 */

#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <algorithm>

#define LOG_TAG "EdgeDetectionJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static inline int clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

static inline uint8_t luminance(uint32_t p) {
    int r = (p >> 16) & 0xFF;
    int g = (p >>  8) & 0xFF;
    int b =  p        & 0xFF;
    return static_cast<uint8_t>((r * 77 + g * 150 + b * 29) >> 8);
}

static void gaussianBlur3x3(const uint8_t* in, uint8_t* out, int w, int h) {
    // 1/16 kernel: [1 2 1; 2 4 2; 1 2 1]
    for (int y = 1; y < h - 1; y++) {
        for (int x = 1; x < w - 1; x++) {
            int sum =
                in[(y-1)*w + (x-1)]     + in[(y-1)*w + x] * 2 + in[(y-1)*w + (x+1)] +
                in[ y   *w + (x-1)] * 2 + in[ y   *w + x] * 4 + in[ y   *w + (x+1)] * 2 +
                in[(y+1)*w + (x-1)]     + in[(y+1)*w + x] * 2 + in[(y+1)*w + (x+1)];
            out[y * w + x] = static_cast<uint8_t>(sum >> 4);
        }
    }
    // Border: copy
    for (int x = 0; x < w; x++) {
        out[x] = in[x];
        out[(h-1)*w + x] = in[(h-1)*w + x];
    }
    for (int y = 0; y < h; y++) {
        out[y*w] = in[y*w];
        out[y*w + w - 1] = in[y*w + w - 1];
    }
}

static void sobelEdge(const uint8_t* gray, uint8_t* edge, int w, int h, float sensitivity) {
    int threshold = static_cast<int>(255.0f * (0.1f + sensitivity * 0.4f));

    for (int y = 1; y < h - 1; y++) {
        for (int x = 1; x < w - 1; x++) {
            int tl = gray[(y-1)*w + (x-1)];
            int tc = gray[(y-1)*w +  x   ];
            int tr = gray[(y-1)*w + (x+1)];
            int ml = gray[ y   *w + (x-1)];
            int mr = gray[ y   *w + (x+1)];
            int bl = gray[(y+1)*w + (x-1)];
            int bc = gray[(y+1)*w +  x   ];
            int br = gray[(y+1)*w + (x+1)];

            int gx = -tl + tr - 2*ml + 2*mr - bl + br;
            int gy = -tl - 2*tc - tr + bl + 2*bc + br;

            int mag = static_cast<int>(std::sqrt(static_cast<float>(gx*gx + gy*gy)));
            edge[y*w + x] = (mag > threshold) ? 0 : 255;
        }
    }
    // Border: white
    for (int x = 0; x < w; x++) {
        edge[x] = 255;
        edge[(h-1)*w + x] = 255;
    }
    for (int y = 0; y < h; y++) {
        edge[y*w] = 255;
        edge[y*w + w - 1] = 255;
    }
}

static void morphClean(const uint8_t* in, uint8_t* out, int w, int h) {
    std::memcpy(out, in, w * h);

    // Pass 1: remove isolated pixels (< 2 black neighbors)
    for (int y = 1; y < h - 1; y++) {
        for (int x = 1; x < w - 1; x++) {
            if (in[y*w + x] != 0) continue; // skip white
            int nb = 0;
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    if (in[(y+dy)*w + (x+dx)] == 0) nb++;
                }
            if (nb < 2) out[y*w + x] = 255;
        }
    }

    // Pass 2: thin edges — remove pixels that don't break connectivity
    uint8_t* tmp = new uint8_t[w * h];
    std::memcpy(tmp, out, w * h);

    for (int y = 1; y < h - 1; y++) {
        for (int x = 1; x < w - 1; x++) {
            if (out[y*w + x] != 0) continue;
            int hEdge = 0, vEdge = 0;
            if (out[y*w + (x-1)] == 0) hEdge++;
            if (out[y*w + (x+1)] == 0) hEdge++;
            if (out[(y-1)*w + x] == 0) vEdge++;
            if (out[(y+1)*w + x] == 0) vEdge++;
            if (hEdge <= 1 || vEdge <= 1)
                tmp[y*w + x] = 255;
        }
    }

    std::memcpy(out, tmp, w * h);
    delete[] tmp;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_ab_inque_robotics_vision_edge_EdgeDetectionNative_nativeDetectEdges(
    JNIEnv* env, jobject /* thiz */,
    jintArray jPixelsIn, jintArray jPixelsOut,
    jint width, jint height, jfloat sensitivity)
{
    if (!jPixelsIn || !jPixelsOut || width <= 0 || height <= 0) return JNI_FALSE;
    int n = width * height;
    if (env->GetArrayLength(jPixelsIn) < n || env->GetArrayLength(jPixelsOut) < n) return JNI_FALSE;

    jint* pIn  = env->GetIntArrayElements(jPixelsIn,  nullptr);
    jint* pOut = env->GetIntArrayElements(jPixelsOut, nullptr);
    if (!pIn || !pOut) {
        if (pIn)  env->ReleaseIntArrayElements(jPixelsIn,  pIn,  JNI_ABORT);
        if (pOut) env->ReleaseIntArrayElements(jPixelsOut, pOut, JNI_ABORT);
        return JNI_FALSE;
    }

    // Convert to luminance
    uint8_t* gray    = new uint8_t[n];
    uint8_t* blurred = new uint8_t[n];
    uint8_t* edges   = new uint8_t[n];
    uint8_t* cleaned = new uint8_t[n];

    for (int i = 0; i < n; i++)
        gray[i] = luminance(static_cast<uint32_t>(pIn[i]));

    gaussianBlur3x3(gray, blurred, width, height);
    sobelEdge(blurred, edges, width, height, sensitivity);
    morphClean(edges, cleaned, width, height);

    // Write output: black edges on white (ARGB)
    for (int i = 0; i < n; i++) {
        uint8_t v = cleaned[i];
        pOut[i] = static_cast<jint>(0xFF000000u | (v << 16) | (v << 8) | v);
    }

    delete[] gray;
    delete[] blurred;
    delete[] edges;
    delete[] cleaned;

    env->ReleaseIntArrayElements(jPixelsIn,  pIn,  JNI_ABORT);
    env->ReleaseIntArrayElements(jPixelsOut, pOut, 0);
    return JNI_TRUE;
}

}  // extern "C"
