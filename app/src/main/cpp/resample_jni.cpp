/**
 * Fast bicubic resampling and unsharp mask for HD image processing.
 * ARGB pixels (int32 per pixel); core loops in C++ for speed.
 */

#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

#define LOG_TAG "ResampleJni"

static inline double cubicWeight(double x) {
    double ax = std::fabs(x);
    if (ax <= 1.0) return 1.5 * ax * ax * ax - 2.5 * ax * ax + 1.0;
    if (ax <= 2.0) return -0.5 * ax * ax * ax + 2.5 * ax * ax - 4.0 * ax + 2.0;
    return 0.0;
}

static inline int clamp(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

static void bicubicResampleImpl(const int32_t* in, int inW, int inH,
                                int32_t* out, int outW, int outH) {
    if (inW < 1 || inH < 1 || outW < 1 || outH < 1) return;
    double scaleX = (outW > 1) ? (inW - 1) / static_cast<double>(outW - 1) : 0;
    double scaleY = (outH > 1) ? (inH - 1) / static_cast<double>(outH - 1) : 0;

    for (int y = 0; y < outH; y++) {
        double srcY = y * scaleY;
        int cy = static_cast<int>(srcY);
        cy = clamp(cy, 0, inH - 1);
        for (int x = 0; x < outW; x++) {
            double srcX = x * scaleX;
            int cx = static_cast<int>(srcX);
            cx = clamp(cx, 0, inW - 1);

            double r = 0, g = 0, b = 0, wSum = 0;
            for (int jy = -1; jy <= 2; jy++) {
                for (int jx = -1; jx <= 2; jx++) {
                    int px = clamp(cx + jx, 0, inW - 1);
                    int py = clamp(cy + jy, 0, inH - 1);
                    double wx = cubicWeight(srcX - px);
                    double wy = cubicWeight(srcY - py);
                    double w = wx * wy;
                    if (w <= 0) continue;
                    int32_t p = in[py * inW + px];
                    r += ((p >> 16) & 0xff) * w;
                    g += ((p >> 8) & 0xff) * w;
                    b += (p & 0xff) * w;
                    wSum += w;
                }
            }
            int ri, gi, bi;
            if (wSum <= 0) {
                int32_t p = in[cy * inW + cx];
                ri = (p >> 16) & 0xff;
                gi = (p >> 8) & 0xff;
                bi = p & 0xff;
            } else {
                ri = static_cast<int>(r / wSum);
                gi = static_cast<int>(g / wSum);
                bi = static_cast<int>(b / wSum);
                ri = clamp(ri, 0, 255);
                gi = clamp(gi, 0, 255);
                bi = clamp(bi, 0, 255);
            }
            out[y * outW + x] = 0xff000000 | (ri << 16) | (gi << 8) | bi;
        }
    }
}

static void unsharpMaskImpl(int32_t* pixels, int w, int h, int radius, float amount) {
    if (radius < 1 || amount <= 0.f || w < 1 || h < 1) return;
    int r = std::min(radius, 3);
    int size = w * h;
    std::vector<int32_t> blur(size);
    int d = 2 * r + 1;
    int n = d * d;

    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            int64_t sumR = 0, sumG = 0, sumB = 0;
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    int nx = clamp(x + dx, 0, w - 1);
                    int ny = clamp(y + dy, 0, h - 1);
                    int32_t p = pixels[ny * w + nx];
                    sumR += (p >> 16) & 0xff;
                    sumG += (p >> 8) & 0xff;
                    sumB += p & 0xff;
                }
            }
            int br = static_cast<int>(sumR / n);
            int bg = static_cast<int>(sumG / n);
            int bb = static_cast<int>(sumB / n);
            int32_t p = pixels[y * w + x];
            int orr= (p >> 16) & 0xff, og = (p >> 8) & 0xff, ob = p & 0xff;
            int nr = static_cast<int>(orr + (orr - br) * amount);
            int ng = static_cast<int>(og + (og - bg) * amount);
            int nb = static_cast<int>(ob + (ob - bb) * amount);
            nr = clamp(nr, 0, 255);
            ng = clamp(ng, 0, 255);
            nb = clamp(nb, 0, 255);
            blur[y * w + x] = 0xff000000 | (nr << 16) | (ng << 8) | nb;
        }
    }
    std::memcpy(pixels, blur.data(), static_cast<size_t>(size) * sizeof(int32_t));
}

extern "C" {

JNIEXPORT jintArray JNICALL
Java_ab_inque_visiontechai_ResampleNative_bicubicResample(
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
        return outArray;
    }
    bicubicResampleImpl(in, inW, inH, out, outW, outH);
    env->ReleaseIntArrayElements(inPixels, in, JNI_ABORT);
    env->ReleaseIntArrayElements(outArray, out, 0);
    return outArray;
}

JNIEXPORT void JNICALL
Java_ab_inque_visiontechai_ResampleNative_unsharpMask(
    JNIEnv* env, jclass, jintArray pixels, jint w, jint h, jint radius, jfloat amount) {
    jsize len = env->GetArrayLength(pixels);
    if (len < w * h) return;
    jint* p = env->GetIntArrayElements(pixels, nullptr);
    if (!p) return;
    unsharpMaskImpl(p, w, h, radius, amount);
    env->ReleaseIntArrayElements(pixels, p, 0);
}

}  // extern "C"
