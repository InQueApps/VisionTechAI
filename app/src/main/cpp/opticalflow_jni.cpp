/**
 * Native Lucas-Kanade optical flow with configurable sensitivity (JNI).
 *
 * Pipeline per frame:
 *   1. Downscale input to (w/pixelation, h/pixelation)
 *   2. Convert to grayscale (luminance)
 *   3. Compute dense Lucas-Kanade flow with configurable window + gradient threshold
 *   4. Generate HSV color-wheel overlay (hue = direction, brightness = magnitude)
 *   5. Composite overlay onto a greyscale version of the original frame
 *
 * Sensitivity parameter (0.0–1.0) controls:
 *   - Gradient threshold:  3.0 → 0.3  (lower = more flow on smooth surfaces)
 *   - Window half-size:    3   → 7     (larger = spreads flow into flat regions)
 *   - Min valid pixels:    scales with window area
 *
 * Previous-frame state is held in static storage (single-threaded usage).
 */

#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <algorithm>
#include <vector>

#define LOG_TAG "OpticalFlowJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static constexpr float MIN_FLOW_MAG      = 1.0f;
static constexpr float MAX_FLOW_MAG      = 20.0f;
static constexpr float MAX_FLOW_VELOCITY = 20.0f;
static constexpr float PI                = 3.14159265358979f;

// Previous-frame state
static std::vector<float> s_prevGray;
static int s_prevW = 0;
static int s_prevH = 0;

static inline int clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

static inline float luminancef(uint32_t p) {
    int r = (p >> 16) & 0xFF;
    int g = (p >>  8) & 0xFF;
    int b =  p        & 0xFF;
    return 0.299f * r + 0.587f * g + 0.114f * b;
}

/**
 * Bilinear downscale ARGB pixels to grayscale float array.
 */
static void downscaleToGray(const int32_t* src, int srcW, int srcH,
                            float* dst, int dstW, int dstH) {
    float xRatio = static_cast<float>(srcW) / dstW;
    float yRatio = static_cast<float>(srcH) / dstH;

    for (int y = 0; y < dstH; y++) {
        float sy = y * yRatio;
        int sy0 = static_cast<int>(sy);
        int sy1 = std::min(sy0 + 1, srcH - 1);
        float fy = sy - sy0;

        for (int x = 0; x < dstW; x++) {
            float sx = x * xRatio;
            int sx0 = static_cast<int>(sx);
            int sx1 = std::min(sx0 + 1, srcW - 1);
            float fx = sx - sx0;

            float tl = luminancef(static_cast<uint32_t>(src[sy0 * srcW + sx0]));
            float tr = luminancef(static_cast<uint32_t>(src[sy0 * srcW + sx1]));
            float bl = luminancef(static_cast<uint32_t>(src[sy1 * srcW + sx0]));
            float br = luminancef(static_cast<uint32_t>(src[sy1 * srcW + sx1]));

            float top = tl + (tr - tl) * fx;
            float bot = bl + (br - bl) * fx;
            dst[y * dstW + x] = top + (bot - top) * fy;
        }
    }
}

/**
 * Compute Lucas-Kanade flow at a single point using a (2*halfWin+1)² window.
 * Gaussian weighting emphasizes center pixels for stability.
 */
static void flowAtPoint(const float* prev, const float* curr,
                        int x, int y, int w, int h,
                        int halfWin, float gradThresh,
                        float& outU, float& outV) {
    float sumIx2 = 0, sumIy2 = 0, sumIxIy = 0;
    float sumIxIt = 0, sumIyIt = 0;
    int validPx = 0;

    float sigma = halfWin * 0.5f;
    float sigma2inv = 1.0f / (2.0f * sigma * sigma);

    for (int dy = -halfWin; dy <= halfWin; dy++) {
        int py = y + dy;
        if (py <= 0 || py >= h - 1) continue;
        for (int dx = -halfWin; dx <= halfWin; dx++) {
            int px = x + dx;
            if (px <= 0 || px >= w - 1) continue;

            int idx = py * w + px;
            float ix = (curr[idx + 1] - curr[idx - 1]) * 0.5f;
            float iy = (curr[idx + w] - curr[idx - w]) * 0.5f;
            float gradMag = std::sqrt(ix * ix + iy * iy);
            if (gradMag < gradThresh) continue;

            float it = curr[idx] - prev[idx];

            // Gaussian weight
            float gw = std::exp(-(dx*dx + dy*dy) * sigma2inv);

            sumIx2  += gw * ix * ix;
            sumIy2  += gw * iy * iy;
            sumIxIy += gw * ix * iy;
            sumIxIt += gw * ix * it;
            sumIyIt += gw * iy * it;
            validPx++;
        }
    }

    int minValid = std::max(5, (2 * halfWin + 1) * (2 * halfWin + 1) / 6);
    if (validPx < minValid) {
        outU = outV = 0;
        return;
    }

    float det = sumIx2 * sumIy2 - sumIxIy * sumIxIy;
    if (std::fabs(det) < 1e-6f) {
        outU = outV = 0;
        return;
    }

    float u = (sumIy2 * sumIxIt - sumIxIy * sumIyIt) / det;
    float v = (sumIx2 * sumIyIt - sumIxIy * sumIxIt) / det;

    if (std::fabs(u) > MAX_FLOW_VELOCITY || std::fabs(v) > MAX_FLOW_VELOCITY) {
        outU = outV = 0;
        return;
    }

    outU = u;
    outV = v;
}

/**
 * HSV to RGB conversion.
 * h in [0,360), s and v in [0,1].
 */
static uint32_t hsvToRgb(float h, float s, float v) {
    float c = v * s;
    float x = c * (1.0f - std::fabs(std::fmod(h / 60.0f, 2.0f) - 1.0f));
    float m = v - c;
    float r, g, b;

    if      (h < 60)  { r = c; g = x; b = 0; }
    else if (h < 120) { r = x; g = c; b = 0; }
    else if (h < 180) { r = 0; g = c; b = x; }
    else if (h < 240) { r = 0; g = x; b = c; }
    else if (h < 300) { r = x; g = 0; b = c; }
    else              { r = c; g = 0; b = x; }

    int ri = clamp255(static_cast<int>((r + m) * 255));
    int gi = clamp255(static_cast<int>((g + m) * 255));
    int bi = clamp255(static_cast<int>((b + m) * 255));
    return (ri << 16) | (gi << 8) | bi;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_ab_inque_visiontechai_OpticalFlowNative_nativeProcessOpticalFlow(
    JNIEnv* env, jobject /* thiz */,
    jintArray jPixelsIn, jintArray jPixelsOut,
    jint width, jint height,
    jint downscale, jfloat intensity, jfloat sensitivity)
{
    if (!jPixelsIn || !jPixelsOut || width <= 0 || height <= 0) return JNI_FALSE;
    int n = width * height;
    if (env->GetArrayLength(jPixelsIn) < n || env->GetArrayLength(jPixelsOut) < n) return JNI_FALSE;

    int ds = std::max(2, static_cast<int>(downscale));
    int smallW = std::max(8, static_cast<int>(width)  / ds);
    int smallH = std::max(8, static_cast<int>(height) / ds);
    int smallN = smallW * smallH;

    jint* pIn  = env->GetIntArrayElements(jPixelsIn,  nullptr);
    jint* pOut = env->GetIntArrayElements(jPixelsOut, nullptr);
    if (!pIn || !pOut) {
        if (pIn)  env->ReleaseIntArrayElements(jPixelsIn,  pIn,  JNI_ABORT);
        if (pOut) env->ReleaseIntArrayElements(jPixelsOut, pOut, JNI_ABORT);
        return JNI_FALSE;
    }

    // --- 1. Downscale + grayscale ---
    std::vector<float> currGray(smallN);
    downscaleToGray(pIn, width, height, currGray.data(), smallW, smallH);

    // --- 2. Greyscale base output (desaturate original) ---
    for (int i = 0; i < n; i++) {
        uint32_t p = static_cast<uint32_t>(pIn[i]);
        uint8_t lum = static_cast<uint8_t>(luminancef(p));
        pOut[i] = static_cast<jint>(0xFF000000u | (lum << 16) | (lum << 8) | lum);
    }

    // --- 3. Compute flow if we have a previous frame ---
    bool hasFlow = false;
    std::vector<float> uGrid, vGrid;

    if (!s_prevGray.empty() && s_prevW == smallW && s_prevH == smallH) {
        uGrid.resize(smallN, 0);
        vGrid.resize(smallN, 0);

        // Sensitivity mapping
        float sens = std::max(0.0f, std::min(1.0f, sensitivity));
        float gradThresh = 3.0f - sens * 2.7f;   // [3.0, 0.3]
        int halfWin = 3 + static_cast<int>(sens * 4.0f); // [3, 7]

        int margin = halfWin + 1;
        for (int y = margin; y < smallH - margin; y++) {
            for (int x = margin; x < smallW - margin; x++) {
                float u, v;
                flowAtPoint(s_prevGray.data(), currGray.data(),
                            x, y, smallW, smallH,
                            halfWin, gradThresh, u, v);
                uGrid[y * smallW + x] = u;
                vGrid[y * smallW + x] = v;
            }
        }
        hasFlow = true;
    }

    // Save current as previous
    s_prevGray = currGray;
    s_prevW = smallW;
    s_prevH = smallH;

    // --- 4. Generate HSV overlay and composite ---
    if (hasFlow && intensity > 0.0f) {
        int alphaMin = static_cast<int>(100 * intensity);
        int alphaMax = static_cast<int>(255 * intensity);

        // Build small overlay
        std::vector<uint32_t> overlay(smallN, 0); // ARGB, transparent

        for (int y = 0; y < smallH; y++) {
            for (int x = 0; x < smallW; x++) {
                int idx = y * smallW + x;
                float u = uGrid[idx];
                float v = vGrid[idx];
                float mag = std::sqrt(u * u + v * v);
                if (mag < MIN_FLOW_MAG) continue;

                float normMag = std::min(mag / MAX_FLOW_MAG, 1.0f);
                float angle = std::atan2(v, u);
                float hue = std::fmod(angle * 180.0f / PI + 360.0f, 360.0f);
                float sat = 0.85f + 0.15f * normMag;
                float val = 0.7f + 0.3f * normMag;

                uint32_t rgb = hsvToRgb(hue, sat, val);
                int alpha = clamp255(alphaMin + static_cast<int>((alphaMax - alphaMin) * normMag));
                overlay[idx] = (static_cast<uint32_t>(alpha) << 24) | rgb;
            }
        }

        // Composite overlay onto output using nearest-neighbor upscale + alpha blend
        float xRatio = static_cast<float>(smallW) / width;
        float yRatio = static_cast<float>(smallH) / height;

        for (int y = 0; y < static_cast<int>(height); y++) {
            int sy = static_cast<int>(y * yRatio);
            if (sy >= smallH) sy = smallH - 1;
            for (int x = 0; x < static_cast<int>(width); x++) {
                int sx = static_cast<int>(x * xRatio);
                if (sx >= smallW) sx = smallW - 1;

                uint32_t ov = overlay[sy * smallW + sx];
                int oa = (ov >> 24) & 0xFF;
                if (oa == 0) continue;

                uint32_t base = static_cast<uint32_t>(pOut[y * width + x]);
                int br = (base >> 16) & 0xFF;
                int bg = (base >>  8) & 0xFF;
                int bb =  base        & 0xFF;
                int or_ = (ov >> 16) & 0xFF;
                int og  = (ov >>  8) & 0xFF;
                int ob  =  ov        & 0xFF;

                float a = oa / 255.0f;
                int fr = static_cast<int>(or_ * a + br * (1.0f - a));
                int fg = static_cast<int>(og * a + bg * (1.0f - a));
                int fb = static_cast<int>(ob * a + bb * (1.0f - a));

                pOut[y * width + x] = static_cast<jint>(
                    0xFF000000u |
                    (clamp255(fr) << 16) |
                    (clamp255(fg) <<  8) |
                     clamp255(fb));
            }
        }
    }

    env->ReleaseIntArrayElements(jPixelsIn,  pIn,  JNI_ABORT);
    env->ReleaseIntArrayElements(jPixelsOut, pOut, 0);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_ab_inque_visiontechai_OpticalFlowNative_nativeClear(
    JNIEnv* /* env */, jobject /* thiz */)
{
    s_prevGray.clear();
    s_prevW = 0;
    s_prevH = 0;
}

}  // extern "C"
