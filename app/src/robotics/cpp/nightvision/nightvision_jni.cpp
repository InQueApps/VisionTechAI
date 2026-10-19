/**
 * Native night vision enhancement (JNI).
 *
 * Adaptive low-light enhancement pipeline:
 *   1. Compute global image statistics (mean luminance, per-channel means, contrast range)
 *   2. Derive adaptive parameters (gamma, enhancement factor, contrast factor)
 *   3. Per-pixel: gamma correction -> local brightness boost -> contrast stretch -> color correction
 *
 * All processing runs in a single pass over the pixel buffer for cache efficiency.
 */

#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <cstdint>
#include <algorithm>

#define LOG_TAG "NightVisionJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

struct ImageStats {
    double avgR, avgG, avgB;
    double avgLuminance;
    double minLuminance, maxLuminance;
    double luminanceRange;
    bool isLowLight;
    bool isLowContrast;
};

struct EnhancementParams {
    float enhancementFactor;
    float contrastFactor;
    float gammaFactor;
    float colorCorrectionFactor;
};

static ImageStats computeStats(const int32_t* pixels, int n) {
    double sumR = 0, sumG = 0, sumB = 0, sumLum = 0;
    double minLum = 255, maxLum = 0;

    for (int i = 0; i < n; i++) {
        uint32_t p = static_cast<uint32_t>(pixels[i]);
        int r = (p >> 16) & 0xFF;
        int g = (p >>  8) & 0xFF;
        int b = (p >>  0) & 0xFF;
        double lum = r * 0.299 + g * 0.587 + b * 0.114;

        sumR += r;
        sumG += g;
        sumB += b;
        sumLum += lum;
        if (lum < minLum) minLum = lum;
        if (lum > maxLum) maxLum = lum;
    }

    ImageStats s{};
    s.avgR = sumR / n;
    s.avgG = sumG / n;
    s.avgB = sumB / n;
    s.avgLuminance = sumLum / n;
    s.minLuminance = minLum;
    s.maxLuminance = maxLum;
    s.luminanceRange = maxLum - minLum;
    s.isLowLight = s.avgLuminance < 80.0;
    s.isLowContrast = s.luminanceRange < 100.0;
    return s;
}

static EnhancementParams deriveParams(const ImageStats& s, float intensity) {
    float si = intensity * 0.6f;

    float baseBright = s.isLowLight ? 1.4f : 1.1f;
    float enhFactor  = baseBright + si * 0.6f;

    float baseCon = s.isLowContrast ? 1.6f : 1.2f;
    float conFactor = baseCon + si * 0.4f;

    float gamma;
    if (s.isLowLight) {
        gamma = 0.6f - si * 0.2f;   // 0.4 – 0.6
    } else {
        gamma = 0.8f - si * 0.1f;   // 0.7 – 0.8
    }

    float ccf;
    if (s.avgLuminance < 50)       ccf = 1.3f + si * 0.4f;
    else if (s.avgLuminance < 100) ccf = 1.2f + si * 0.3f;
    else                           ccf = 1.05f + si * 0.2f;

    return {enhFactor, conFactor, gamma, ccf};
}

static void enhancePixels(const int32_t* in, int32_t* out, int n,
                          const ImageStats& stats,
                          const EnhancementParams& ep,
                          float intensity)
{
    float attW = intensity * 0.7f + 0.3f;
    float attInv = 1.0f - attW;

    // Pre-compute gamma LUT for 0-255
    float gammaLUT[256];
    for (int v = 0; v < 256; v++) {
        gammaLUT[v] = 255.0f * std::pow(v / 255.0f, ep.gammaFactor);
    }

    for (int i = 0; i < n; i++) {
        uint32_t p = static_cast<uint32_t>(in[i]);
        int channels[3] = {
            (int)((p >> 16) & 0xFF),
            (int)((p >>  8) & 0xFF),
            (int)((p >>  0) & 0xFF)
        };
        double avgs[3] = { stats.avgR, stats.avgG, stats.avgB };

        uint8_t result[3];
        for (int c = 0; c < 3; c++) {
            float gc = gammaLUT[channels[c]];
            float local = gc * ep.enhancementFactor;
            if (local > 255.0f) local = 255.0f;
            float global = (gc - (float)avgs[c]) * ep.contrastFactor + (float)avgs[c];
            if (global < 0.0f) global = 0.0f;
            if (global > 255.0f) global = 255.0f;
            float blended = local * attW + global * attInv;
            float corrected = blended * ep.colorCorrectionFactor;
            int val = (int)corrected;
            result[c] = (uint8_t)(val < 0 ? 0 : (val > 255 ? 255 : val));
        }

        out[i] = (int32_t)(0xFF000000u
                 | ((uint32_t)result[0] << 16)
                 | ((uint32_t)result[1] <<  8)
                 | (uint32_t)result[2]);
    }
}

extern "C" {

static bool s_logged = false;

JNIEXPORT jboolean JNICALL
Java_ab_inque_robotics_vision_night_NightVisionNative_nativeEnhance(
    JNIEnv* env, jobject /* thiz */,
    jintArray jPixelsIn, jintArray jPixelsOut,
    jint width, jint height, jfloat intensity)
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

    if (!s_logged) {
        LOGI("nativeEnhance %dx%d intensity=%.2f", (int)width, (int)height, (float)intensity);
        s_logged = true;
    }

    ImageStats stats = computeStats(pIn, n);
    EnhancementParams ep = deriveParams(stats, intensity);
    enhancePixels(pIn, pOut, n, stats, ep, intensity);

    env->ReleaseIntArrayElements(jPixelsIn,  pIn,  JNI_ABORT);
    env->ReleaseIntArrayElements(jPixelsOut, pOut, 0);
    return JNI_TRUE;
}

}  // extern "C"
