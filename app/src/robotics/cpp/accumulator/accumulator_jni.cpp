/**
 * Aligned frame accumulator (JNI) — v2.1
 *
 * Reusable multi-frame exposure stacking with translational alignment.
 * The NEWEST frame is always the reference (ground truth).
 *
 * Alignment pipeline:
 *   1. Coarse: 3-level pyramid SSD at 1/4 resolution → 4-pixel granularity
 *   2. Fine:   ±3 search at full resolution (stride-sampled) → 1-pixel exact
 *   3. Sub-pixel: parabolic fit at full resolution → ~0.3px precision
 *   4. Rejection: if aligned SSD isn't meaningfully better than zero-shift,
 *      fall back to no shift (still blend, just without offset)
 *
 * Blending:
 *   - Bilinear read from shifted accumulator (sub-pixel precision)
 *   - Per-pixel motion rejection via adaptive variance threshold
 *   - Temporal variance tracking for self-tuning noise estimation
 *   - Post-accumulation unsharp mask to recover detail lost to averaging
 */

#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <algorithm>
#include <vector>

#define LOG_TAG "AccumulatorJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static constexpr float MOTION_K          = 2.5f;
static constexpr float MIN_MOTION_THRESH = 15.0f;
static constexpr float INITIAL_VARIANCE  = 200.0f;
static constexpr float SHARPEN_AMOUNT    = 0.35f;

static constexpr int   REFINE_RADIUS     = 3;      // ±3 pixels at full resolution
static constexpr int   REFINE_STRIDE     = 8;      // sample every 8th pixel for speed
static constexpr int   SUBPIX_STRIDE     = 4;      // finer sampling for parabolic fit

// ── Accumulator state ────────────────────────────────────────────────────
static std::vector<float> s_accR, s_accG, s_accB;
static std::vector<float> s_varR, s_varG, s_varB;
static int s_accW = 0, s_accH = 0;
static int s_accCount = 0;

static inline int   clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
static inline float clampf(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

// ── Luminance ────────────────────────────────────────────────────────────

static inline float luminance(int r, int g, int b) {
    return 0.299f * r + 0.587f * g + 0.114f * b;
}

static inline float luminanceF(float r, float g, float b) {
    return 0.299f * r + 0.587f * g + 0.114f * b;
}

// ── Downscale helpers ────────────────────────────────────────────────────

static void downscaleArgbToGray(const int32_t* src, int srcW, int srcH,
                                float* dst, int dstW, int dstH, int factor) {
    float invArea = 1.0f / (factor * factor);
    for (int dy = 0; dy < dstH; dy++) {
        int sy0 = dy * factor;
        int sy1 = std::min(sy0 + factor, srcH);
        for (int dx = 0; dx < dstW; dx++) {
            int sx0 = dx * factor;
            int sx1 = std::min(sx0 + factor, srcW);
            float sum = 0;
            for (int y = sy0; y < sy1; y++)
                for (int x = sx0; x < sx1; x++) {
                    uint32_t p = static_cast<uint32_t>(src[y * srcW + x]);
                    sum += luminance((p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF);
                }
            dst[dy * dstW + dx] = sum * invArea;
        }
    }
}

static void downscaleFloatToGray(const float* accR, const float* accG, const float* accB,
                                 int srcW, int srcH,
                                 float* dst, int dstW, int dstH, int factor) {
    float invArea = 1.0f / (factor * factor);
    for (int dy = 0; dy < dstH; dy++) {
        int sy0 = dy * factor;
        int sy1 = std::min(sy0 + factor, srcH);
        for (int dx = 0; dx < dstW; dx++) {
            int sx0 = dx * factor;
            int sx1 = std::min(sx0 + factor, srcW);
            float sum = 0;
            for (int y = sy0; y < sy1; y++)
                for (int x = sx0; x < sx1; x++) {
                    int idx = y * srcW + x;
                    sum += luminanceF(accR[idx], accG[idx], accB[idx]);
                }
            dst[dy * dstW + dx] = sum * invArea;
        }
    }
}

static void halfGray(const float* src, int srcW, int srcH,
                     float* dst, int dstW, int dstH) {
    for (int dy = 0; dy < dstH; dy++) {
        int sy  = dy * 2;
        int sy1 = std::min(sy + 1, srcH - 1);
        for (int dx = 0; dx < dstW; dx++) {
            int sx  = dx * 2;
            int sx1 = std::min(sx + 1, srcW - 1);
            dst[dy * dstW + dx] = 0.25f * (
                src[sy  * srcW + sx ] + src[sy  * srcW + sx1] +
                src[sy1 * srcW + sx ] + src[sy1 * srcW + sx1]);
        }
    }
}

// ── Bilinear sampling ────────────────────────────────────────────────────

static inline float bilinearSample(const float* buf, int w, int h,
                                   float fx, float fy) {
    int x0 = static_cast<int>(floorf(fx));
    int y0 = static_cast<int>(floorf(fy));
    float fracX = fx - x0;
    float fracY = fy - y0;
    int x1 = x0 + 1, y1 = y0 + 1;
    x0 = std::max(0, std::min(x0, w - 1));
    x1 = std::max(0, std::min(x1, w - 1));
    y0 = std::max(0, std::min(y0, h - 1));
    y1 = std::max(0, std::min(y1, h - 1));
    float w00 = (1 - fracX) * (1 - fracY);
    float w10 = fracX       * (1 - fracY);
    float w01 = (1 - fracX) * fracY;
    float w11 = fracX       * fracY;
    return buf[y0 * w + x0] * w00
         + buf[y0 * w + x1] * w10
         + buf[y1 * w + x0] * w01
         + buf[y1 * w + x1] * w11;
}

// ── Sub-pixel refinement ─────────────────────────────────────────────────

static float parabolicMin(float sLeft, float sCenter, float sRight) {
    float denom = 2.0f * (sLeft + sRight - 2.0f * sCenter);
    if (fabsf(denom) < 1e-6f) return 0.0f;
    return clampf((sLeft - sRight) / denom, -0.5f, 0.5f);
}

// ── SSD on pre-computed grayscale (for pyramid levels) ───────────────────

static float computeSSD(const float* a, const float* b,
                        int w, int h, int dx, int dy) {
    float ssd = 0;
    int count = 0;
    for (int y = 0; y < h; y++) {
        int oy = y + dy;
        if (oy < 0 || oy >= h) continue;
        for (int x = 0; x < w; x++) {
            int ox = x + dx;
            if (ox < 0 || ox >= w) continue;
            float diff = a[y * w + x] - b[oy * w + ox];
            ssd += diff * diff;
            count++;
        }
    }
    if (count < 16) return 1e18f;
    return ssd / count;
}

// ── Full-resolution SSD (computes luminance inline, stride-sampled) ──────

static float fullResSSD(const int32_t* newArgb,
                        const float* accR, const float* accG, const float* accB,
                        int w, int h, int dx, int dy, int stride) {
    float ssd = 0;
    int count = 0;
    for (int y = 0; y < h; y += stride) {
        int oy = y + dy;
        if (oy < 0 || oy >= h) continue;
        for (int x = 0; x < w; x += stride) {
            int ox = x + dx;
            if (ox < 0 || ox >= w) continue;
            uint32_t p = static_cast<uint32_t>(newArgb[y * w + x]);
            float nL = luminance((p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF);
            int oi = oy * w + ox;
            float oL = luminanceF(accR[oi], accG[oi], accB[oi]);
            float diff = nL - oL;
            ssd += diff * diff;
            count++;
        }
    }
    if (count < 16) return 1e18f;
    return ssd / count;
}

// ── Coarse pyramid alignment (returns integer offset in input coords) ────

static void pyramidAlignCoarse(const float* newGray, const float* oldGray,
                               int w, int h, int& bestDx, int& bestDy) {
    bestDx = 0;
    bestDy = 0;

    int w0 = w, h0 = h;
    int w1 = w0 / 2, h1 = h0 / 2;
    int w2 = w1 / 2, h2 = h1 / 2;

    if (w2 < 8 || h2 < 8) {
        float bestSSD = computeSSD(newGray, oldGray, w, h, 0, 0);
        int radius = std::min(w / 4, 12);
        for (int dy = -radius; dy <= radius; dy++)
            for (int dx = -radius; dx <= radius; dx++) {
                float s = computeSSD(newGray, oldGray, w, h, dx, dy);
                if (s < bestSSD) { bestSSD = s; bestDx = dx; bestDy = dy; }
            }
        return;
    }

    std::vector<float> new1(w1 * h1), new2(w2 * h2);
    std::vector<float> old1(w1 * h1), old2(w2 * h2);
    halfGray(newGray, w0, h0, new1.data(), w1, h1);
    halfGray(oldGray, w0, h0, old1.data(), w1, h1);
    halfGray(new1.data(), w1, h1, new2.data(), w2, h2);
    halfGray(old1.data(), w1, h1, old2.data(), w2, h2);

    // Level 2 (coarsest): wide search
    int radius2 = std::min(w2 / 3, 20);
    float bestSSD = 1e18f;
    int bx = 0, by = 0;
    for (int dy = -radius2; dy <= radius2; dy += 2)
        for (int dx = -radius2; dx <= radius2; dx += 2) {
            float s = computeSSD(new2.data(), old2.data(), w2, h2, dx, dy);
            if (s < bestSSD) { bestSSD = s; bx = dx; by = dy; }
        }

    // Level 1: refine
    int cx1 = bx * 2, cy1 = by * 2;
    bestSSD = 1e18f;
    for (int dy = cy1 - 4; dy <= cy1 + 4; dy++)
        for (int dx = cx1 - 4; dx <= cx1 + 4; dx++) {
            float s = computeSSD(new1.data(), old1.data(), w1, h1, dx, dy);
            if (s < bestSSD) { bestSSD = s; bx = dx; by = dy; }
        }

    // Level 0: refine
    int cx0 = bx * 2, cy0 = by * 2;
    float finalBestSSD = 1e18f;
    for (int dy = cy0 - 2; dy <= cy0 + 2; dy++)
        for (int dx = cx0 - 2; dx <= cx0 + 2; dx++) {
            float s = computeSSD(newGray, oldGray, w, h, dx, dy);
            if (s < finalBestSSD) { finalBestSSD = s; bestDx = dx; bestDy = dy; }
        }
}

// ── JNI functions ────────────────────────────────────────────────────────

extern "C" {

JNIEXPORT jboolean JNICALL
Java_ab_inque_robotics_accumulate_FrameAccumulatorNative_nativeAccumulate(
    JNIEnv* env, jobject,
    jintArray jPixelsIn, jintArray jPixelsOut,
    jint width, jint height,
    jint numFrames, jboolean doAlign)
{
    if (!jPixelsIn || !jPixelsOut || width <= 0 || height <= 0 || numFrames < 1)
        return JNI_FALSE;
    int n = width * height;
    if (env->GetArrayLength(jPixelsIn) < n || env->GetArrayLength(jPixelsOut) < n)
        return JNI_FALSE;

    jint* pIn  = env->GetIntArrayElements(jPixelsIn,  nullptr);
    jint* pOut = env->GetIntArrayElements(jPixelsOut, nullptr);
    if (!pIn || !pOut) {
        if (pIn)  env->ReleaseIntArrayElements(jPixelsIn,  pIn,  JNI_ABORT);
        if (pOut) env->ReleaseIntArrayElements(jPixelsOut, pOut, JNI_ABORT);
        return JNI_FALSE;
    }

    // Reset if resolution changed
    if (s_accW != width || s_accH != height) {
        s_accR.resize(n); s_accG.resize(n); s_accB.resize(n);
        s_varR.resize(n); s_varG.resize(n); s_varB.resize(n);
        s_accW = width;
        s_accH = height;
        s_accCount = 0;
    }

    float alpha = 1.0f / static_cast<float>(numFrames);
    float decay = 1.0f - alpha;

    if (s_accCount == 0) {
        // ── First frame: seed accumulator, initialise variance ──
        for (int i = 0; i < n; i++) {
            uint32_t p = static_cast<uint32_t>(pIn[i]);
            s_accR[i] = static_cast<float>((p >> 16) & 0xFF);
            s_accG[i] = static_cast<float>((p >>  8) & 0xFF);
            s_accB[i] = static_cast<float>( p        & 0xFF);
            s_varR[i] = INITIAL_VARIANCE;
            s_varG[i] = INITIAL_VARIANCE;
            s_varB[i] = INITIAL_VARIANCE;
        }
    } else {
        // ── Step 1: Alignment ──
        int   bestDx = 0, bestDy = 0;
        float fracDx = 0.0f, fracDy = 0.0f;

        if (doAlign) {
            // --- Phase A: Coarse pyramid at 1/4 resolution ---
            int factor = 4;
            int smallW = width / factor;
            int smallH = height / factor;
            if (smallW < 16 || smallH < 16) {
                factor = 1; smallW = width; smallH = height;
            }

            std::vector<float> newGray(smallW * smallH);
            std::vector<float> oldGray(smallW * smallH);
            downscaleArgbToGray(pIn, width, height,
                                newGray.data(), smallW, smallH, factor);
            downscaleFloatToGray(s_accR.data(), s_accG.data(), s_accB.data(),
                                 width, height,
                                 oldGray.data(), smallW, smallH, factor);

            int coarseDx, coarseDy;
            pyramidAlignCoarse(newGray.data(), oldGray.data(),
                               smallW, smallH, coarseDx, coarseDy);

            // Scale coarse offset to full resolution
            int seedDx = coarseDx * factor;
            int seedDy = coarseDy * factor;

            // --- Phase B: Full-resolution integer refinement (±3 px) ---
            bestDx = seedDx;
            bestDy = seedDy;
            float bestSSD = fullResSSD(pIn, s_accR.data(), s_accG.data(), s_accB.data(),
                                       width, height, seedDx, seedDy, REFINE_STRIDE);

            for (int rdy = -REFINE_RADIUS; rdy <= REFINE_RADIUS; rdy++) {
                for (int rdx = -REFINE_RADIUS; rdx <= REFINE_RADIUS; rdx++) {
                    if (rdx == 0 && rdy == 0) continue;
                    int tdx = seedDx + rdx;
                    int tdy = seedDy + rdy;
                    float s = fullResSSD(pIn, s_accR.data(), s_accG.data(), s_accB.data(),
                                         width, height, tdx, tdy, REFINE_STRIDE);
                    if (s < bestSSD) {
                        bestSSD = s;
                        bestDx = tdx;
                        bestDy = tdy;
                    }
                }
            }

            // --- Phase C: Sub-pixel parabolic fit at full resolution ---
            float sC = fullResSSD(pIn, s_accR.data(), s_accG.data(), s_accB.data(),
                                  width, height, bestDx, bestDy, SUBPIX_STRIDE);
            float sL = fullResSSD(pIn, s_accR.data(), s_accG.data(), s_accB.data(),
                                  width, height, bestDx - 1, bestDy, SUBPIX_STRIDE);
            float sR = fullResSSD(pIn, s_accR.data(), s_accG.data(), s_accB.data(),
                                  width, height, bestDx + 1, bestDy, SUBPIX_STRIDE);
            float sU = fullResSSD(pIn, s_accR.data(), s_accG.data(), s_accB.data(),
                                  width, height, bestDx, bestDy - 1, SUBPIX_STRIDE);
            float sD = fullResSSD(pIn, s_accR.data(), s_accG.data(), s_accB.data(),
                                  width, height, bestDx, bestDy + 1, SUBPIX_STRIDE);
            fracDx = parabolicMin(sL, sC, sR);
            fracDy = parabolicMin(sU, sC, sD);

            // --- Phase D: Zero-shift rejection ---
            float zeroSSD = fullResSSD(pIn, s_accR.data(), s_accG.data(), s_accB.data(),
                                       width, height, 0, 0, REFINE_STRIDE);
            if (bestSSD > zeroSSD * 0.92f) {
                bestDx = 0; bestDy = 0;
                fracDx = 0.0f; fracDy = 0.0f;
            }

            if (bestDx != 0 || bestDy != 0 ||
                fabsf(fracDx) > 0.1f || fabsf(fracDy) > 0.1f) {
                LOGI("Align old->new: dx=%d+%.2f dy=%d+%.2f (SSD=%.1f zero=%.1f)",
                     bestDx, fracDx, bestDy, fracDy, bestSSD, zeroSSD);
            }
        }

        float totalDx = bestDx + fracDx;
        float totalDy = bestDy + fracDy;
        bool  subPixel = fabsf(fracDx) > 0.01f || fabsf(fracDy) > 0.01f;

        // ── Step 2: Blend with motion rejection + variance tracking ──
        std::vector<float> tmpR(n), tmpG(n), tmpB(n);
        std::vector<float> tmpVR(n), tmpVG(n), tmpVB(n);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;

                uint32_t p = static_cast<uint32_t>(pIn[i]);
                float nr = static_cast<float>((p >> 16) & 0xFF);
                float ng = static_cast<float>((p >>  8) & 0xFF);
                float nb = static_cast<float>( p        & 0xFF);

                float sx = x + totalDx;
                float sy = y + totalDy;

                if (sx < 0.0f || sx >= (width - 1) || sy < 0.0f || sy >= (height - 1)) {
                    tmpR[i]  = nr;  tmpG[i]  = ng;  tmpB[i]  = nb;
                    tmpVR[i] = INITIAL_VARIANCE;
                    tmpVG[i] = INITIAL_VARIANCE;
                    tmpVB[i] = INITIAL_VARIANCE;
                    continue;
                }

                float oR, oG, oB, vR, vG, vB;
                if (subPixel) {
                    oR = bilinearSample(s_accR.data(), width, height, sx, sy);
                    oG = bilinearSample(s_accG.data(), width, height, sx, sy);
                    oB = bilinearSample(s_accB.data(), width, height, sx, sy);
                    vR = bilinearSample(s_varR.data(), width, height, sx, sy);
                    vG = bilinearSample(s_varG.data(), width, height, sx, sy);
                    vB = bilinearSample(s_varB.data(), width, height, sx, sy);
                } else {
                    int ox = x + bestDx;
                    int oy = y + bestDy;
                    if (ox < 0 || ox >= width || oy < 0 || oy >= height) {
                        tmpR[i]  = nr;  tmpG[i]  = ng;  tmpB[i]  = nb;
                        tmpVR[i] = INITIAL_VARIANCE;
                        tmpVG[i] = INITIAL_VARIANCE;
                        tmpVB[i] = INITIAL_VARIANCE;
                        continue;
                    }
                    int oi = oy * width + ox;
                    oR = s_accR[oi]; oG = s_accG[oi]; oB = s_accB[oi];
                    vR = s_varR[oi]; vG = s_varG[oi]; vB = s_varB[oi];
                }

                float diffR = fabsf(nr - oR);
                float diffG = fabsf(ng - oG);
                float diffB = fabsf(nb - oB);
                float lumDiff = 0.299f * diffR + 0.587f * diffG + 0.114f * diffB;
                float avgVar  = 0.299f * vR    + 0.587f * vG    + 0.114f * vB;
                float thresh  = MOTION_K * sqrtf(std::max(avgVar, 0.0f)) + MIN_MOTION_THRESH;

                if (lumDiff > thresh) {
                    tmpR[i]  = nr;  tmpG[i]  = ng;  tmpB[i]  = nb;
                    tmpVR[i] = INITIAL_VARIANCE;
                    tmpVG[i] = INITIAL_VARIANCE;
                    tmpVB[i] = INITIAL_VARIANCE;
                } else {
                    tmpR[i] = oR * decay + nr * alpha;
                    tmpG[i] = oG * decay + ng * alpha;
                    tmpB[i] = oB * decay + nb * alpha;

                    float eR = nr - oR;
                    float eG = ng - oG;
                    float eB = nb - oB;
                    tmpVR[i] = vR * decay + eR * eR * alpha;
                    tmpVG[i] = vG * decay + eG * eG * alpha;
                    tmpVB[i] = vB * decay + eB * eB * alpha;
                }
            }
        }

        s_accR.swap(tmpR); s_accG.swap(tmpG); s_accB.swap(tmpB);
        s_varR.swap(tmpVR); s_varG.swap(tmpVG); s_varB.swap(tmpVB);
    }

    s_accCount++;

    // ── Step 3: Write output with optional unsharp mask ──
    float sharpenAmt = (s_accCount > 2)
        ? SHARPEN_AMOUNT * std::min(s_accCount * 0.1f, 1.0f) : 0.0f;

    if (sharpenAmt > 0.01f) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                float bR = 0, bG = 0, bB = 0;
                for (int ky = -1; ky <= 1; ky++) {
                    int sy = std::max(0, std::min(y + ky, height - 1));
                    for (int kx = -1; kx <= 1; kx++) {
                        int sx = std::max(0, std::min(x + kx, width - 1));
                        float gw = (kx == 0 ? 2.0f : 1.0f) * (ky == 0 ? 2.0f : 1.0f);
                        int si = sy * width + sx;
                        bR += s_accR[si] * gw;
                        bG += s_accG[si] * gw;
                        bB += s_accB[si] * gw;
                    }
                }
                constexpr float invKernelSum = 1.0f / 16.0f;
                bR *= invKernelSum; bG *= invKernelSum; bB *= invKernelSum;
                int r = clamp255(static_cast<int>(s_accR[i] + sharpenAmt * (s_accR[i] - bR)));
                int g = clamp255(static_cast<int>(s_accG[i] + sharpenAmt * (s_accG[i] - bG)));
                int b = clamp255(static_cast<int>(s_accB[i] + sharpenAmt * (s_accB[i] - bB)));
                pOut[i] = static_cast<jint>(0xFF000000u | (r << 16) | (g << 8) | b);
            }
        }
    } else {
        for (int i = 0; i < n; i++) {
            int r = clamp255(static_cast<int>(s_accR[i]));
            int g = clamp255(static_cast<int>(s_accG[i]));
            int b = clamp255(static_cast<int>(s_accB[i]));
            pOut[i] = static_cast<jint>(0xFF000000u | (r << 16) | (g << 8) | b);
        }
    }

    env->ReleaseIntArrayElements(jPixelsIn,  pIn,  JNI_ABORT);
    env->ReleaseIntArrayElements(jPixelsOut, pOut, 0);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_ab_inque_robotics_accumulate_FrameAccumulatorNative_nativeClear(
    JNIEnv*, jobject)
{
    s_accR.clear(); s_accR.shrink_to_fit();
    s_accG.clear(); s_accG.shrink_to_fit();
    s_accB.clear(); s_accB.shrink_to_fit();
    s_varR.clear(); s_varR.shrink_to_fit();
    s_varG.clear(); s_varG.shrink_to_fit();
    s_varB.clear(); s_varB.shrink_to_fit();
    s_accW = 0; s_accH = 0;
    s_accCount = 0;
    LOGI("Accumulator cleared");
}

JNIEXPORT jint JNICALL
Java_ab_inque_robotics_accumulate_FrameAccumulatorNative_nativeGetFrameCount(
    JNIEnv*, jobject)
{
    return s_accCount;
}

}  // extern "C"
