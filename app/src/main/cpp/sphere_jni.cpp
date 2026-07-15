/**
 * Sphere panorama engine (JNI).
 *
 * Accumulates camera frames into a 4096x2048 equirectangular buffer using
 * quaternion-based orientation from the device IMU. Supports rectilinear
 * (perspective) extraction for interactive viewing.
 *
 * v3 — Performance + quality:
 *   - Time-budgeted alignment: alignment stages are skipped if the budget
 *     (TIME_BUDGET_US) is exceeded, guaranteeing bounded per-frame latency.
 *   - Pre-computed ray directions in overlap samples, eliminating redundant
 *     sqrt / divide / multiply in the SSD evaluator inner loop.
 *   - Squared-distance mismatch check (no per-pixel sqrt in splatting).
 *   - Gain recomputation after alignment using the corrected quaternion.
 *   - Weight capping to prevent ghost accumulation in high-overlap regions.
 *
 * Coordinate convention:
 *   Earth frame (from Android rotation vector sensor): X=East, Y=North, Z=Up
 *   Equirect mapping: lon = atan2(east, north), lat = asin(up)
 *   Camera looks along -Z in device space (out the back of the phone)
 */

#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <cstring>
#include <algorithm>
#include <vector>
#include <mutex>
#include <chrono>

#define LOG_TAG "SphereJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static constexpr double PI = 3.14159265358979323846;
static constexpr double DEG2RAD = PI / 180.0;
static constexpr double RAD2DEG = 180.0 / PI;

// Time budget for the entire addFrame operation (microseconds).
// Alignment refinement is skipped once this budget is exceeded.
static constexpr int64_t TIME_BUDGET_US = 150000; // 150ms

// Residual SSD above this threshold (after alignment) means poor match
static constexpr double REJECT_SSD_THRESH = 8000.0;
static constexpr double REJECT_SSD_RELAXED = REJECT_SSD_THRESH * 1.35;
static constexpr int TILE_GAIN_N = 8;

// Per-pixel mismatch rejection during splatting (squared per-channel thresholds)
static constexpr float MISMATCH_SOFT_SQ  = 35.0f * 35.0f;  // ~1225
static constexpr float MISMATCH_HARD_SQ  = 60.0f * 60.0f;  // ~3600

// Maximum accumulated weight per equirect pixel.  Prevents early frames
// from dominating in high-overlap regions, reducing ghosting.
static constexpr float MAX_ACCUM_WEIGHT = 3.5f;

using SteadyClock = std::chrono::steady_clock;

// ── Quaternion helpers ──────────────────────────────────────────────────

struct Quat { double x, y, z, w; };

static inline void quatRotate(const Quat& q, double vx, double vy, double vz,
                               double& ox, double& oy, double& oz) {
    double tx = 2.0 * (q.y * vz - q.z * vy);
    double ty = 2.0 * (q.z * vx - q.x * vz);
    double tz = 2.0 * (q.x * vy - q.y * vx);
    ox = vx + q.w * tx + (q.y * tz - q.z * ty);
    oy = vy + q.w * ty + (q.z * tx - q.x * tz);
    oz = vz + q.w * tz + (q.x * ty - q.y * tx);
}

static inline Quat quatFromAxisAngle(double ax, double ay, double az, double angle) {
    double s = std::sin(angle * 0.5);
    return { ax * s, ay * s, az * s, std::cos(angle * 0.5) };
}

static inline Quat quatMul(const Quat& a, const Quat& b) {
    return {
        a.w*b.x + a.x*b.w + a.y*b.z - a.z*b.y,
        a.w*b.y - a.x*b.z + a.y*b.w + a.z*b.x,
        a.w*b.z + a.x*b.y - a.y*b.x + a.z*b.w,
        a.w*b.w - a.x*b.x - a.y*b.y - a.z*b.z
    };
}

static inline int64_t elapsedUs(SteadyClock::time_point start) {
    return std::chrono::duration_cast<std::chrono::microseconds>(
        SteadyClock::now() - start).count();
}

// ── Equirectangular buffer ──────────────────────────────────────────────

static int eqW = 0, eqH = 0;
static float storedFovH = 0, storedFovV = 0;
static std::vector<float> eqR, eqG, eqB, eqWeight;
static std::mutex sphereMutex;

// Per-frame alignment metrics
static int sFrameIndex = 0;
static int sLastOverlapCount = 0;
static int sLastTotalSamples = 0;
static float sLastAlignOffset = 0.f;
static float sLastRollOffset = 0.f;
static float sLastResidualSSD = 0.f;
static int sLastRejected = 0;
static int sShiftX = 0;
static int sShiftY = 0;

static float distK1 = 0.f, distK2 = 0.f, distP1 = 0.f, distP2 = 0.f;

static inline void undistortNorm(double& nx, double& ny) {
    if (distK1 == 0.f && distK2 == 0.f && distP1 == 0.f && distP2 == 0.f) return;
    double x = nx, y = ny;
    double ux = x, uy = y;
    for (int i = 0; i < 5; i++) {
        double r2 = ux * ux + uy * uy;
        double radial = 1.0 + distK1 * r2 + distK2 * r2 * r2;
        double dx = 2.0 * distP1 * ux * uy + distP2 * (r2 + 2.0 * ux * ux);
        double dy = distP1 * (r2 + 2.0 * uy * uy) + 2.0 * distP2 * ux * uy;
        ux = (x - dx) / radial;
        uy = (y - dy) / radial;
    }
    nx = ux;
    ny = uy;
}

static inline void pixelToRay(int fx, int fy, int frameW, int frameH,
                              double tanH, double tanV,
                              double& rx, double& ry, double& rz) {
    double nx = ((double)fx / (frameW - 1) - 0.5) * 2.0 * tanH;
    double ny = (0.5 - (double)fy / (frameH - 1)) * 2.0 * tanV;
    undistortNorm(nx, ny);
    double len = std::sqrt(nx * nx + ny * ny + 1.0);
    rx = nx / len;
    ry = ny / len;
    rz = -1.0 / len;
}

static inline int clampI(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

static inline float bilinearTileGain(const float* tileGains, int channel,
                                     int fx, int fy, int frameW, int frameH) {
    double u = (double)fx / std::max(1, frameW - 1) * TILE_GAIN_N;
    double v = (double)fy / std::max(1, frameH - 1) * TILE_GAIN_N;
    int x0 = clampI((int)u, 0, TILE_GAIN_N - 1);
    int y0 = clampI((int)v, 0, TILE_GAIN_N - 1);
    int x1 = std::min(TILE_GAIN_N - 1, x0 + 1);
    int y1 = std::min(TILE_GAIN_N - 1, y0 + 1);
    double tx = u - x0, ty = v - y0;
    auto at = [&](int tx, int ty) -> float {
        return tileGains[(ty * TILE_GAIN_N + tx) * 3 + channel];
    };
    return static_cast<float>(
        (1.0 - tx) * (1.0 - ty) * at(x0, y0) +
        tx * (1.0 - ty) * at(x1, y0) +
        (1.0 - tx) * ty * at(x0, y1) +
        tx * ty * at(x1, y1));
}

static float computeSolidAngleCoverageLocked() {
    constexpr int GW = 32, GH = 16;
    if (eqW == 0 || eqH == 0) return 0.f;
    int filled = 0;
    for (int gy = 0; gy < GH; gy++) {
        for (int gx = 0; gx < GW; gx++) {
            int x0 = gx * eqW / GW, x1 = (gx + 1) * eqW / GW;
            int y0 = gy * eqH / GH, y1 = (gy + 1) * eqH / GH;
            float maxW = 0.f;
            for (int y = y0; y < y1; y++)
                for (int x = x0; x < x1; x++)
                    maxW = std::max(maxW, eqWeight[y * eqW + x]);
            if (maxW > 0.05f) filled++;
        }
    }
    return static_cast<float>(filled) / (GW * GH);
}

static inline void splatPixel(int ex, int ey, float r, float g, float b, float w) {
    if (ex < 0 || ex >= eqW || ey < 0 || ey >= eqH) return;
    int idx = ey * eqW + ex;
    eqR[idx] += r * w;
    eqG[idx] += g * w;
    eqB[idx] += b * w;
    eqWeight[idx] += w;
}

// Weight-gradient seam blend (export / finish pass — S12 multiband lite).
static void refineSeamsLocked(int radius = 12) {
    if (eqW == 0 || eqH == 0) return;
    const int sz = eqW * eqH;
    std::vector<float> nr(sz, 0.f), ng(sz, 0.f), nb(sz, 0.f);
    for (int i = 0; i < sz; i++) {
        if (eqWeight[i] > 0.f) {
            float inv = 1.f / eqWeight[i];
            nr[i] = eqR[i] * inv;
            ng[i] = eqG[i] * inv;
            nb[i] = eqB[i] * inv;
        }
    }
    const float sigma = static_cast<float>(radius) * 0.5f;
    const float invSigma2 = 1.f / (2.f * sigma * sigma);

    for (int y = radius; y < eqH - radius; y++) {
        for (int x = 0; x < eqW; x++) {
            int idx = y * eqW + x;
            if (eqWeight[idx] < 0.05f) continue;

            float wCenter = eqWeight[idx];
            float maxGrad = 0.f;
            for (int dy = -radius; dy <= radius; dy += 4) {
                int ny = y + dy;
                if (ny < 0 || ny >= eqH) continue;
                for (int dx = -radius; dx <= radius; dx += 4) {
                    int nx = (x + dx + eqW) % eqW;
                    maxGrad = std::max(maxGrad, std::abs(eqWeight[ny * eqW + nx] - wCenter));
                }
            }
            if (maxGrad < 0.3f) continue;

            float sr = 0.f, sg = 0.f, sb = 0.f, sw = 0.f;
            for (int dy = -radius; dy <= radius; dy++) {
                int ny = y + dy;
                if (ny < 0 || ny >= eqH) continue;
                for (int dx = -radius; dx <= radius; dx++) {
                    int nx = (x + dx + eqW) % eqW;
                    int j = ny * eqW + nx;
                    if (eqWeight[j] < 0.05f) continue;
                    float d2 = static_cast<float>(dx * dx + dy * dy);
                    float gw = std::exp(-d2 * invSigma2);
                    sr += nr[j] * gw;
                    sg += ng[j] * gw;
                    sb += nb[j] * gw;
                    sw += gw;
                }
            }
            if (sw > 0.f) {
                nr[idx] = sr / sw;
                ng[idx] = sg / sw;
                nb[idx] = sb / sw;
            }
        }
    }

    for (int i = 0; i < sz; i++) {
        if (eqWeight[i] > 0.f) {
            eqR[i] = nr[i] * eqWeight[i];
            eqG[i] = ng[i] * eqWeight[i];
            eqB[i] = nb[i] * eqWeight[i];
        }
    }
}

// ── JNI functions ───────────────────────────────────────────────────────

extern "C" {

JNIEXPORT jboolean JNICALL
Java_ab_inque_visiontechai_SphereNative_nativeInit(
    JNIEnv*, jobject, jint width, jint height, jfloat fovH, jfloat fovV,
    jfloat k1, jfloat k2, jfloat p1, jfloat p2) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    eqW = width;
    eqH = height;
    storedFovH = fovH;
    storedFovV = fovV;
    distK1 = k1;
    distK2 = k2;
    distP1 = p1;
    distP2 = p2;
    int sz = eqW * eqH;
    eqR.assign(sz, 0.f);
    eqG.assign(sz, 0.f);
    eqB.assign(sz, 0.f);
    eqWeight.assign(sz, 0.f);
    sFrameIndex = 0;
    sLastRejected = 0;
    sLastRollOffset = 0.f;
    LOGI("Sphere init: %dx%d  fov=%.1f x %.1f  dist k1=%.4f", eqW, eqH, fovH, fovV, k1);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_ab_inque_visiontechai_SphereNative_nativeAddFrame(
    JNIEnv* env, jobject,
    jintArray jPixels, jint frameW, jint frameH,
    jfloat qx, jfloat qy, jfloat qz, jfloat qw) {

    std::lock_guard<std::mutex> lock(sphereMutex);
    if (eqW == 0 || eqH == 0) return JNI_FALSE;

    auto t0 = SteadyClock::now();
    sShiftX = 0;
    sShiftY = 0;

    jint* pixels = env->GetIntArrayElements(jPixels, nullptr);
    if (!pixels) return JNI_FALSE;

    Quat q = { qx, qy, qz, qw };
    double qlen = std::sqrt(q.x*q.x + q.y*q.y + q.z*q.z + q.w*q.w);
    if (qlen < 1e-9) { env->ReleaseIntArrayElements(jPixels, pixels, JNI_ABORT); return JNI_FALSE; }
    q.x /= qlen; q.y /= qlen; q.z /= qlen; q.w /= qlen;

    double halfFovH = storedFovH * 0.5 * DEG2RAD;
    double halfFovV = storedFovV * 0.5 * DEG2RAD;
    double tanH = std::tan(halfFovH);
    double tanV = std::tan(halfFovV);

    // ── Phase 1: sparse sampling for gain + overlap ──
    // Pre-compute ray direction per sample so the alignment inner loop
    // avoids redundant sqrt / divide per evaluation.
    struct OverlapSample {
        float newR, newG, newB;
        double rx, ry, rz;
        int fx, fy;
    };

    constexpr int SAMPLE_STEP = 8;
    std::vector<OverlapSample> overlap;
    overlap.reserve((frameW / SAMPLE_STEP + 1) * (frameH / SAMPLE_STEP + 1));
    double sumExR = 0, sumExG = 0, sumExB = 0;
    double sumNewR = 0, sumNewG = 0, sumNewB = 0;
    int totalSamples = 0;

    for (int fy = 0; fy < frameH; fy += SAMPLE_STEP) {
        for (int fx = 0; fx < frameW; fx += SAMPLE_STEP) {
            totalSamples++;
            double rx, ry, rz;
            pixelToRay(fx, fy, frameW, frameH, tanH, tanV, rx, ry, rz);
            double wx, wy, wz;
            quatRotate(q, rx, ry, rz, wx, wy, wz);
            double lon = std::atan2(wx, wy);
            double lat = std::asin(std::max(-1.0, std::min(1.0, wz)));
            int eqX = ((int)((lon / PI + 1.0) * 0.5 * eqW) + eqW) % eqW;
            int eqY = clampI((int)((0.5 - lat / PI) * eqH), 0, eqH - 1);
            int idx = eqY * eqW + eqX;

            int32_t p = pixels[fy * frameW + fx];
            float nr = static_cast<float>((p >> 16) & 0xff);
            float ng = static_cast<float>((p >> 8) & 0xff);
            float nb = static_cast<float>(p & 0xff);

            if (eqWeight[idx] > 0.f) {
                float inv = 1.f / eqWeight[idx];
                sumExR += eqR[idx] * inv;
                sumExG += eqG[idx] * inv;
                sumExB += eqB[idx] * inv;
                sumNewR += nr;
                sumNewG += ng;
                sumNewB += nb;
                overlap.push_back({nr, ng, nb, rx, ry, rz, fx, fy});
            }
        }
    }

    double gainR = 1.0, gainG = 1.0, gainB = 1.0;
    if (overlap.size() > 30 && sumNewR > 1 && sumNewG > 1 && sumNewB > 1) {
        gainR = std::max(0.5, std::min(2.0, sumExR / sumNewR));
        gainG = std::max(0.5, std::min(2.0, sumExG / sumNewG));
        gainB = std::max(0.5, std::min(2.0, sumExB / sumNewB));
    }

    int64_t phase1Us = elapsedUs(t0);

    // ── Phase 2: Rotational alignment (time-budgeted) ──
    Quat qCorrected = q;
    double finalSSD = 0.0;
    bool frameRejected = false;
    int alignLevelsCompleted = 0;

    if (overlap.size() > 50) {

        // SSD evaluator with pre-computed rays
        auto computeRotSSD = [&](double dyaw, double dpitch, double droll, int subsample) -> double {
            Quat qY = quatFromAxisAngle(0, 0, 1, dyaw);
            Quat qP = quatFromAxisAngle(1, 0, 0, dpitch);
            Quat qR = quatFromAxisAngle(0, 0, 1, droll);
            Quat qC = quatMul(qY, quatMul(qP, quatMul(qR, q)));

            double ssd = 0.0;
            int count = 0;
            for (size_t i = 0; i < overlap.size(); i += subsample) {
                const auto& s = overlap[i];
                double wx, wy, wz;
                quatRotate(qC, s.rx, s.ry, s.rz, wx, wy, wz);
                double lon = std::atan2(wx, wy);
                double lat = std::asin(std::max(-1.0, std::min(1.0, wz)));
                int eqX = ((int)((lon / PI + 1.0) * 0.5 * eqW) + eqW) % eqW;
                int eqY = clampI((int)((0.5 - lat / PI) * eqH), 0, eqH - 1);
                int idx = eqY * eqW + eqX;

                if (eqWeight[idx] > 0.f) {
                    float inv = 1.f / eqWeight[idx];
                    double dr = s.newR * gainR - eqR[idx] * inv;
                    double dg = s.newG * gainG - eqG[idx] * inv;
                    double db = s.newB * gainB - eqB[idx] * inv;
                    ssd += dr * dr + dg * dg + db * db;
                    count++;
                }
            }
            if (count < 20) return 1e18;
            return ssd / count;
        };

        double baselineSSD = computeRotSSD(0, 0, 0, 1);
        double bestDyaw = 0, bestDpitch = 0, bestDroll = 0;
        double bestSSD = baselineSSD;

        auto parabolicMin = [](double sm, double s0, double sp) -> double {
            double denom = 2.0 * (sm + sp - 2.0 * s0);
            if (std::abs(denom) < 1e-12) return 0.0;
            return std::max(-0.5, std::min(0.5, (sm - sp) / denom));
        };

        // Level 2 (coarsest): ±7° with 1° step
        {
            constexpr double R = 7.0 * DEG2RAD;
            constexpr double S = 1.0 * DEG2RAD;
            for (double dp = -R; dp <= R; dp += S)
                for (double dy = -R; dy <= R; dy += S) {
                    double s = computeRotSSD(dy, dp, 0, 4);
                    if (s < bestSSD) { bestSSD = s; bestDyaw = dy; bestDpitch = dp; }
                }
            alignLevelsCompleted = 1;
        }

        // Roll search ±5° (1° step) with best yaw/pitch
        if (elapsedUs(t0) < TIME_BUDGET_US * 35 / 100) {
            constexpr double R = 5.0 * DEG2RAD;
            constexpr double S = 1.0 * DEG2RAD;
            for (double dr = -R; dr <= R; dr += S) {
                double s = computeRotSSD(bestDyaw, bestDpitch, dr, 4);
                if (s < bestSSD) { bestSSD = s; bestDroll = dr; }
            }
        }

        // Level 1: ±1.5° with 0.25° step
        if (elapsedUs(t0) < TIME_BUDGET_US * 40 / 100) {
            constexpr double R = 1.5 * DEG2RAD;
            constexpr double S = 0.25 * DEG2RAD;
            double sy = bestDyaw, sp = bestDpitch;
            bestSSD = 1e18;
            for (double dp = sp - R; dp <= sp + R; dp += S)
                for (double dy = sy - R; dy <= sy + R; dy += S) {
                    double s = computeRotSSD(dy, dp, bestDroll, 2);
                    if (s < bestSSD) { bestSSD = s; bestDyaw = dy; bestDpitch = dp; }
                }
            alignLevelsCompleted = 2;
        }

        // Level 0: ±0.3° with 0.05° step
        if (elapsedUs(t0) < TIME_BUDGET_US * 55 / 100) {
            constexpr double R = 0.3 * DEG2RAD;
            constexpr double S = 0.05 * DEG2RAD;
            double sy = bestDyaw, sp = bestDpitch;
            bestSSD = 1e18;
            for (double dp = sp - R; dp <= sp + R; dp += S)
                for (double dy = sy - R; dy <= sy + R; dy += S) {
                    double s = computeRotSSD(dy, dp, bestDroll, 1);
                    if (s < bestSSD) { bestSSD = s; bestDyaw = dy; bestDpitch = dp; }
                }
            alignLevelsCompleted = 3;
        }

        // Parabolic refinement
        if (elapsedUs(t0) < TIME_BUDGET_US * 65 / 100) {
            constexpr double EPS = 0.02 * DEG2RAD;
            double sC = bestSSD;
            double sL = computeRotSSD(bestDyaw - EPS, bestDpitch, bestDroll, 1);
            double sR = computeRotSSD(bestDyaw + EPS, bestDpitch, bestDroll, 1);
            double sU = computeRotSSD(bestDyaw, bestDpitch - EPS, bestDroll, 1);
            double sD = computeRotSSD(bestDyaw, bestDpitch + EPS, bestDroll, 1);
            bestDyaw   += parabolicMin(sL, sC, sR) * EPS;
            bestDpitch += parabolicMin(sU, sC, sD) * EPS;
            alignLevelsCompleted = 4;
        }

        // Ultra-fine refinement (single iteration, time-gated)
        if (elapsedUs(t0) < TIME_BUDGET_US * 75 / 100) {
            constexpr double R = 0.08 * DEG2RAD;
            constexpr double S = 0.01 * DEG2RAD;
            double sy = bestDyaw, sp = bestDpitch;
            bestSSD = 1e18;
            for (double dp = sp - R; dp <= sp + R; dp += S)
                for (double dy = sy - R; dy <= sy + R; dy += S) {
                    double s = computeRotSSD(dy, dp, bestDroll, 1);
                    if (s < bestSSD) { bestSSD = s; bestDyaw = dy; bestDpitch = dp; }
                }

            constexpr double EPS2 = 0.005 * DEG2RAD;
            double sC2 = bestSSD;
            double sL2 = computeRotSSD(bestDyaw - EPS2, bestDpitch, bestDroll, 1);
            double sR2 = computeRotSSD(bestDyaw + EPS2, bestDpitch, bestDroll, 1);
            double sU2 = computeRotSSD(bestDyaw, bestDpitch - EPS2, bestDroll, 1);
            double sD2 = computeRotSSD(bestDyaw, bestDpitch + EPS2, bestDroll, 1);
            bestDyaw   += parabolicMin(sL2, sC2, sR2) * EPS2;
            bestDpitch += parabolicMin(sU2, sC2, sD2) * EPS2;
            bestSSD = computeRotSSD(bestDyaw, bestDpitch, bestDroll, 1);
            alignLevelsCompleted = 5;
        }

        // Phase 2b: frame-plane micro shift for parallax / IMU drift (S8 lite, no OpenCV)
        if (elapsedUs(t0) < TIME_BUDGET_US * 78 / 100 && overlap.size() > 60) {
            auto computeShiftSSD = [&](int shiftX, int shiftY) -> double {
                Quat qY = quatFromAxisAngle(0, 0, 1, bestDyaw);
                Quat qP = quatFromAxisAngle(1, 0, 0, bestDpitch);
                Quat qR = quatFromAxisAngle(0, 0, 1, bestDroll);
                Quat qC = quatMul(qY, quatMul(qP, quatMul(qR, q)));
                double ssd = 0.0;
                int count = 0;
                for (const auto& s : overlap) {
                    int sfx = clampI(s.fx + shiftX, 0, frameW - 1);
                    int sfy = clampI(s.fy + shiftY, 0, frameH - 1);
                    double rx, ry, rz;
                    pixelToRay(sfx, sfy, frameW, frameH, tanH, tanV, rx, ry, rz);
                    double wx, wy, wz;
                    quatRotate(qC, rx, ry, rz, wx, wy, wz);
                    double lon = std::atan2(wx, wy);
                    double lat = std::asin(std::max(-1.0, std::min(1.0, wz)));
                    int eqX = ((int)((lon / PI + 1.0) * 0.5 * eqW) + eqW) % eqW;
                    int eqY = clampI((int)((0.5 - lat / PI) * eqH), 0, eqH - 1);
                    int idx = eqY * eqW + eqX;
                    if (eqWeight[idx] > 0.f) {
                        float inv = 1.f / eqWeight[idx];
                        double dr = s.newR * gainR - eqR[idx] * inv;
                        double dg = s.newG * gainG - eqG[idx] * inv;
                        double db = s.newB * gainB - eqB[idx] * inv;
                        ssd += dr * dr + dg * dg + db * db;
                        count++;
                    }
                }
                if (count < 20) return 1e18;
                return ssd / count;
            };

            double shiftBestSSD = bestSSD;
            int bestSx = 0, bestSy = 0;
            for (int sy = -10; sy <= 10; sy += 5) {
                for (int sx = -10; sx <= 10; sx += 5) {
                    double s = computeShiftSSD(sx, sy);
                    if (s < shiftBestSSD) {
                        shiftBestSSD = s;
                        bestSx = sx;
                        bestSy = sy;
                    }
                }
            }
            if (shiftBestSSD < bestSSD * 0.95) {
                sShiftX = bestSx;
                sShiftY = bestSy;
                bestSSD = shiftBestSSD;
                LOGI("Micro-shift: (%d,%d) SSD -> %.1f", bestSx, bestSy, bestSSD);
            }
        }

        finalSSD = bestSSD;

        // Build corrected quaternion
        if (baselineSSD < 1e17 && bestSSD > baselineSSD * 0.92) {
            qCorrected = q;
            finalSSD = baselineSSD;
        } else {
            Quat qY = quatFromAxisAngle(0, 0, 1, bestDyaw);
            Quat qP = quatFromAxisAngle(1, 0, 0, bestDpitch);
            Quat qR = quatFromAxisAngle(0, 0, 1, bestDroll);
            qCorrected = quatMul(qY, quatMul(qP, quatMul(qR, q)));

            double corrDeg = std::sqrt(bestDyaw * bestDyaw + bestDpitch * bestDpitch + bestDroll * bestDroll) * RAD2DEG;
            sLastRollOffset = static_cast<float>(bestDroll * RAD2DEG);
            LOGI("Align: yaw=%.3f° pitch=%.3f° roll=%.3f° (%.3f° total, SSD %.1f->%.1f, L%d, %zu samp)",
                 bestDyaw * RAD2DEG, bestDpitch * RAD2DEG, bestDroll * RAD2DEG, corrDeg,
                 baselineSSD, bestSSD, alignLevelsCompleted, overlap.size());
        }

        // Recompute gain with corrected quaternion for better color matching
        {
            double reExR = 0, reExG = 0, reExB = 0;
            double reNewR = 0, reNewG = 0, reNewB = 0;
            int reCount = 0;
            for (const auto& s : overlap) {
                double wx, wy, wz;
                quatRotate(qCorrected, s.rx, s.ry, s.rz, wx, wy, wz);
                double lon = std::atan2(wx, wy);
                double lat = std::asin(std::max(-1.0, std::min(1.0, wz)));
                int eqX = ((int)((lon / PI + 1.0) * 0.5 * eqW) + eqW) % eqW;
                int eqY = clampI((int)((0.5 - lat / PI) * eqH), 0, eqH - 1);
                int idx = eqY * eqW + eqX;
                if (eqWeight[idx] > 0.f) {
                    float inv = 1.f / eqWeight[idx];
                    reExR += eqR[idx] * inv;
                    reExG += eqG[idx] * inv;
                    reExB += eqB[idx] * inv;
                    reNewR += s.newR;
                    reNewG += s.newG;
                    reNewB += s.newB;
                    reCount++;
                }
            }
            if (reCount > 30 && reNewR > 1 && reNewG > 1 && reNewB > 1) {
                gainR = std::max(0.5, std::min(2.0, reExR / reNewR));
                gainG = std::max(0.5, std::min(2.0, reExG / reNewG));
                gainB = std::max(0.5, std::min(2.0, reExB / reNewB));
            }
        }

        // Quality-based rejection with relaxed fallback for strong overlap
        if (finalSSD > REJECT_SSD_THRESH && sFrameIndex > 0) {
            if (finalSSD <= REJECT_SSD_RELAXED && overlap.size() > 80) {
                LOGI("Frame %d fallback ACCEPT: SSD=%.1f (relaxed %.1f)",
                     sFrameIndex + 1, finalSSD, REJECT_SSD_RELAXED);
            } else {
                frameRejected = true;
                LOGW("Frame %d REJECTED: SSD=%.1f (thresh=%.1f)",
                     sFrameIndex + 1, finalSSD, REJECT_SSD_THRESH);
            }
        }
    }

    int64_t phase2Us = elapsedUs(t0) - phase1Us;

    // Store per-frame metrics
    sFrameIndex++;
    sLastOverlapCount = static_cast<int>(overlap.size());
    sLastTotalSamples = totalSamples;
    double corrDeg = 0;
    if (!frameRejected) {
        corrDeg = std::sqrt(
            std::pow(std::atan2(2.0*(qCorrected.w*qCorrected.z + qCorrected.x*qCorrected.y),
                                1.0 - 2.0*(qCorrected.y*qCorrected.y + qCorrected.z*qCorrected.z))
                     - std::atan2(2.0*(q.w*q.z + q.x*q.y),
                                  1.0 - 2.0*(q.y*q.y + q.z*q.z)), 2.0) +
            std::pow(std::asin(std::max(-1.0, std::min(1.0, 2.0*(qCorrected.w*qCorrected.y - qCorrected.z*qCorrected.x))))
                     - std::asin(std::max(-1.0, std::min(1.0, 2.0*(q.w*q.y - q.z*q.x)))), 2.0)
        ) * RAD2DEG;
    }
    sLastAlignOffset = static_cast<float>(corrDeg);
    sLastResidualSSD = static_cast<float>(finalSSD);
    sLastRejected = frameRejected ? 1 : 0;

    if (sFrameIndex > 1 && sLastOverlapCount < 20) {
        LOGW("Frame %d: very low overlap (%d/%d)",
             sFrameIndex, sLastOverlapCount, totalSamples);
        frameRejected = true;
        sLastRejected = 1;
    }

    // ── Phase 3: project and splat with per-pixel mismatch rejection ──
    int rejectedPixels = 0, totalOverlapPixels = 0;
    int minSplatY = eqH, maxSplatY = 0;

    float tileGains[TILE_GAIN_N * TILE_GAIN_N * 3];
    for (int i = 0; i < TILE_GAIN_N * TILE_GAIN_N * 3; i++) tileGains[i] = 1.f;
    if (!overlap.empty()) {
        double tExR[64] = {}, tExG[64] = {}, tExB[64] = {};
        double tNewR[64] = {}, tNewG[64] = {}, tNewB[64] = {};
        int tCount[64] = {};
        for (const auto& s : overlap) {
            double wx, wy, wz;
            quatRotate(qCorrected, s.rx, s.ry, s.rz, wx, wy, wz);
            double lon = std::atan2(wx, wy);
            double lat = std::asin(std::max(-1.0, std::min(1.0, wz)));
            int eqX = ((int)((lon / PI + 1.0) * 0.5 * eqW) + eqW) % eqW;
            int eqY = clampI((int)((0.5 - lat / PI) * eqH), 0, eqH - 1);
            int idx = eqY * eqW + eqX;
            if (eqWeight[idx] <= 0.f) continue;
            int tx = clampI(s.fx * TILE_GAIN_N / std::max(1, frameW), 0, TILE_GAIN_N - 1);
            int ty = clampI(s.fy * TILE_GAIN_N / std::max(1, frameH), 0, TILE_GAIN_N - 1);
            int t = ty * TILE_GAIN_N + tx;
            float inv = 1.f / eqWeight[idx];
            tExR[t] += eqR[idx] * inv;
            tExG[t] += eqG[idx] * inv;
            tExB[t] += eqB[idx] * inv;
            tNewR[t] += s.newR;
            tNewG[t] += s.newG;
            tNewB[t] += s.newB;
            tCount[t]++;
        }
        for (int t = 0; t < TILE_GAIN_N * TILE_GAIN_N; t++) {
            if (tCount[t] < 2 || tNewR[t] < 1) continue;
            tileGains[t * 3 + 0] = static_cast<float>(
                std::max(0.5, std::min(2.0, tExR[t] / tNewR[t])));
            tileGains[t * 3 + 1] = static_cast<float>(
                std::max(0.5, std::min(2.0, tExG[t] / tNewG[t])));
            tileGains[t * 3 + 2] = static_cast<float>(
                std::max(0.5, std::min(2.0, tExB[t] / tNewB[t])));
        }
    }

    if (!frameRejected) {
        double featherX = frameW * 0.25;
        double featherY = frameH * 0.25;
        float mismatchSqRange = MISMATCH_HARD_SQ - MISMATCH_SOFT_SQ;
        bool doMismatchCheck = (elapsedUs(t0) < TIME_BUDGET_US * 85 / 100);

        for (int fy = 0; fy < frameH; fy++) {
            for (int fx = 0; fx < frameW; fx++) {
                int sfx = clampI(fx + sShiftX, 0, frameW - 1);
                int sfy = clampI(fy + sShiftY, 0, frameH - 1);
                double rx, ry, rz;
                pixelToRay(sfx, sfy, frameW, frameH, tanH, tanV, rx, ry, rz);

                double wx, wy, wz;
                quatRotate(qCorrected, rx, ry, rz, wx, wy, wz);

                double lon = std::atan2(wx, wy);
                double lat = std::asin(std::max(-1.0, std::min(1.0, wz)));

                double eqXf = (lon / PI + 1.0) * 0.5 * eqW;
                double eqYf = (0.5 - lat / PI) * eqH;

                int32_t p = pixels[fy * frameW + fx];
                float gR = bilinearTileGain(tileGains, 0, fx, fy, frameW, frameH);
                float gG = bilinearTileGain(tileGains, 1, fx, fy, frameW, frameH);
                float gB = bilinearTileGain(tileGains, 2, fx, fy, frameW, frameH);
                float pr = static_cast<float>((p >> 16) & 0xff) * gR;
                float pg = static_cast<float>((p >> 8) & 0xff) * gG;
                float pb = static_cast<float>(p & 0xff) * gB;

                double edgeDistX = std::min((double)fx, (double)(frameW - 1 - fx));
                double edgeDistY = std::min((double)fy, (double)(frameH - 1 - fy));
                double edgeFrac = std::min(1.0, std::min(edgeDistX / featherX, edgeDistY / featherY));
                float fw = static_cast<float>(0.5 - 0.5 * std::cos(edgeFrac * PI));

                // Per-pixel mismatch (squared comparison — no sqrt)
                if (doMismatchCheck) {
                    int nearX = ((int)std::round(eqXf) + eqW) % eqW;
                    int nearY = clampI((int)std::round(eqYf), 0, eqH - 1);
                    int nearIdx = nearY * eqW + nearX;

                    if (eqWeight[nearIdx] > 0.f) {
                        totalOverlapPixels++;
                        float seamFeather = std::min(1.f, (MAX_ACCUM_WEIGHT - eqWeight[nearIdx]) / 1.5f);
                        fw *= seamFeather;
                        float inv = 1.f / eqWeight[nearIdx];
                        float dr = pr - eqR[nearIdx] * inv;
                        float dg = pg - eqG[nearIdx] * inv;
                        float db = pb - eqB[nearIdx] * inv;
                        float diffSq = (dr*dr + dg*dg + db*db) / 3.0f;

                        if (diffSq > MISMATCH_HARD_SQ) {
                            rejectedPixels++;
                            continue;
                        } else if (diffSq > MISMATCH_SOFT_SQ) {
                            float t = (diffSq - MISMATCH_SOFT_SQ) / mismatchSqRange;
                            fw *= (1.0f - t);
                        }
                    }
                }

                if (fw < 1e-6f) continue;

                int x0 = static_cast<int>(std::floor(eqXf));
                int y0 = static_cast<int>(std::floor(eqYf));
                double fracX = eqXf - x0;
                double fracY = eqYf - y0;
                int x1 = x0 + 1;
                int y1 = y0 + 1;

                if (x0 < 0) x0 += eqW;
                if (x1 >= eqW) x1 -= eqW;
                x0 = ((x0 % eqW) + eqW) % eqW;

                minSplatY = std::min(minSplatY, y0);
                maxSplatY = std::max(maxSplatY, std::min(y1, eqH - 1));

                float w00 = fw * static_cast<float>((1.0 - fracX) * (1.0 - fracY));
                float w10 = fw * static_cast<float>(fracX * (1.0 - fracY));
                float w01 = fw * static_cast<float>((1.0 - fracX) * fracY);
                float w11 = fw * static_cast<float>(fracX * fracY);

                splatPixel(x0, y0, pr, pg, pb, w00);
                splatPixel(x1, y0, pr, pg, pb, w10);
                splatPixel(x0, y1, pr, pg, pb, w01);
                splatPixel(x1, y1, pr, pg, pb, w11);
            }
        }

        // ── Phase 4: weight capping in the splatted Y-band ──
        // Prevents early frames from dominating in high-overlap regions.
        // Capping preserves the average color (both numerator and denominator
        // scale equally) but ensures new frames always contribute ≥ ~20%.
        if (minSplatY <= maxSplatY) {
            for (int y = minSplatY; y <= maxSplatY; y++) {
                for (int x = 0; x < eqW; x++) {
                    int idx = y * eqW + x;
                    if (eqWeight[idx] > MAX_ACCUM_WEIGHT) {
                        float scale = MAX_ACCUM_WEIGHT / eqWeight[idx];
                        eqR[idx] *= scale;
                        eqG[idx] *= scale;
                        eqB[idx] *= scale;
                        eqWeight[idx] = MAX_ACCUM_WEIGHT;
                    }
                }
            }
        }

        if (totalOverlapPixels > 0) {
            float rejectPct = 100.f * rejectedPixels / totalOverlapPixels;
            if (rejectPct > 5.f) {
                LOGI("Frame %d: %.1f%% pixels rejected (%d/%d)",
                     sFrameIndex, rejectPct, rejectedPixels, totalOverlapPixels);
            }
        }
    }

    int64_t totalUs = elapsedUs(t0);

    LOGI("Frame %d: P1=%lldus P2=%lldus P3+4=%lldus total=%lldus (L%d, %zu overlap)",
         sFrameIndex, (long long)phase1Us, (long long)phase2Us,
         (long long)(totalUs - phase1Us - phase2Us), (long long)totalUs,
         alignLevelsCompleted, overlap.size());

    env->ReleaseIntArrayElements(jPixels, pixels, JNI_ABORT);
    return frameRejected ? JNI_FALSE : JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_ab_inque_visiontechai_SphereNative_nativeRefineSeams(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    if (eqW == 0 || eqH == 0) return JNI_FALSE;
    refineSeamsLocked(12);
    LOGI("Sphere seams refined");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_ab_inque_visiontechai_SphereNative_nativeGetEquirect(
    JNIEnv* env, jobject, jintArray jOut) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    int sz = eqW * eqH;
    if (sz == 0) return JNI_FALSE;
    if (env->GetArrayLength(jOut) < sz) return JNI_FALSE;

    jint* out = env->GetIntArrayElements(jOut, nullptr);
    if (!out) return JNI_FALSE;

    for (int i = 0; i < sz; i++) {
        if (eqWeight[i] > 0.f) {
            float inv = 1.f / eqWeight[i];
            int r = clampI(static_cast<int>(eqR[i] * inv), 0, 255);
            int g = clampI(static_cast<int>(eqG[i] * inv), 0, 255);
            int b = clampI(static_cast<int>(eqB[i] * inv), 0, 255);
            out[i] = static_cast<int32_t>(0xff000000 | (r << 16) | (g << 8) | b);
        } else {
            out[i] = 0;
        }
    }

    // Fill isolated gaps
    for (int y = 0; y < eqH; y++) {
        for (int x = 0; x < eqW; x++) {
            int idx = y * eqW + x;
            if (out[idx] != 0) continue;
            int sumR = 0, sumG = 0, sumB = 0, count = 0;
            for (int dy = -1; dy <= 1; dy++) {
                int ny = y + dy;
                if (ny < 0 || ny >= eqH) continue;
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    int nx = (x + dx + eqW) % eqW;
                    int32_t p = out[ny * eqW + nx];
                    if (p != 0) {
                        sumR += (p >> 16) & 0xff;
                        sumG += (p >> 8) & 0xff;
                        sumB += p & 0xff;
                        count++;
                    }
                }
            }
            if (count >= 5) {
                out[idx] = static_cast<int32_t>(0xff000000 |
                    ((sumR / count) << 16) | ((sumG / count) << 8) | (sumB / count));
            } else {
                out[idx] = static_cast<int32_t>(0xff1a1a2e);
            }
        }
    }

    env->ReleaseIntArrayElements(jOut, out, 0);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_ab_inque_visiontechai_SphereNative_nativeGetRectilinear(
    JNIEnv* env, jobject,
    jintArray jOut, jint outW, jint outH,
    jfloat azimuth, jfloat elevation, jfloat fov) {

    std::lock_guard<std::mutex> lock(sphereMutex);
    if (eqW == 0 || eqH == 0) return JNI_FALSE;
    if (env->GetArrayLength(jOut) < outW * outH) return JNI_FALSE;

    jint* out = env->GetIntArrayElements(jOut, nullptr);
    if (!out) return JNI_FALSE;

    Quat qAz = quatFromAxisAngle(0, 0, 1, -azimuth);
    Quat qEl = quatFromAxisAngle(1, 0, 0, elevation);
    Quat qLook = quatMul(qAz, qEl);

    double halfFov = fov * 0.5 * DEG2RAD;
    double tanFov = std::tan(halfFov);
    double aspect = static_cast<double>(outW) / outH;

    for (int y = 0; y < outH; y++) {
        double upOff = (0.5 - (double)y / (outH - 1)) * 2.0 * tanFov;
        for (int x = 0; x < outW; x++) {
            double rightOff = ((double)x / (outW - 1) - 0.5) * 2.0 * tanFov * aspect;

            double rx = rightOff;
            double ry = 1.0;
            double rz = upOff;
            double len = std::sqrt(rx*rx + ry*ry + rz*rz);
            rx /= len; ry /= len; rz /= len;

            double wx, wy, wz;
            quatRotate(qLook, rx, ry, rz, wx, wy, wz);

            double lon = std::atan2(wx, wy);
            double lat = std::asin(std::max(-1.0, std::min(1.0, wz)));

            double eqXf = (lon / PI + 1.0) * 0.5 * eqW;
            double eqYf = (0.5 - lat / PI) * eqH;

            int x0 = static_cast<int>(std::floor(eqXf));
            int y0 = static_cast<int>(std::floor(eqYf));
            double fx = eqXf - x0;
            double fy = eqYf - y0;

            int x1 = (x0 + 1) % eqW;
            if (x0 < 0) x0 += eqW;
            x0 = x0 % eqW;
            int y1 = std::min(y0 + 1, eqH - 1);
            y0 = clampI(y0, 0, eqH - 1);

            auto sample = [&](int sx, int sy) -> int32_t {
                int idx = sy * eqW + sx;
                if (eqWeight[idx] > 0.f) {
                    float inv = 1.f / eqWeight[idx];
                    int r = clampI(static_cast<int>(eqR[idx] * inv), 0, 255);
                    int g = clampI(static_cast<int>(eqG[idx] * inv), 0, 255);
                    int b = clampI(static_cast<int>(eqB[idx] * inv), 0, 255);
                    return (r << 16) | (g << 8) | b;
                }
                return 0x1a1a2e;
            };

            int32_t p00 = sample(x0, y0);
            int32_t p10 = sample(x1, y0);
            int32_t p01 = sample(x0, y1);
            int32_t p11 = sample(x1, y1);

            auto lerp = [](int a, int b, double t) -> int {
                return clampI(static_cast<int>(a + (b - a) * t), 0, 255);
            };

            int r = lerp(lerp((p00>>16)&0xff, (p10>>16)&0xff, fx),
                         lerp((p01>>16)&0xff, (p11>>16)&0xff, fx), fy);
            int g = lerp(lerp((p00>>8)&0xff, (p10>>8)&0xff, fx),
                         lerp((p01>>8)&0xff, (p11>>8)&0xff, fx), fy);
            int b = lerp(lerp(p00&0xff, p10&0xff, fx),
                         lerp(p01&0xff, p11&0xff, fx), fy);

            out[y * outW + x] = static_cast<int32_t>(0xff000000 | (r << 16) | (g << 8) | b);
        }
    }

    env->ReleaseIntArrayElements(jOut, out, 0);
    return JNI_TRUE;
}

JNIEXPORT jfloat JNICALL
Java_ab_inque_visiontechai_SphereNative_nativeGetCoverage(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    int sz = eqW * eqH;
    if (sz == 0) return 0.f;
    int covered = 0;
    for (int i = 0; i < sz; i++) {
        if (eqWeight[i] > 0.f) covered++;
    }
    return static_cast<float>(covered) / sz;
}

JNIEXPORT jfloat JNICALL
Java_ab_inque_visiontechai_SphereNative_nativeGetSolidAngleCoverage(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    return computeSolidAngleCoverageLocked();
}

JNIEXPORT jboolean JNICALL
Java_ab_inque_visiontechai_SphereNative_nativeGetHoleMask(
    JNIEnv* env, jobject, jintArray jPixels, jint outW, jint outH) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    if (eqW == 0 || eqH == 0 || outW <= 0 || outH <= 0) return JNI_FALSE;
    jint* out = env->GetIntArrayElements(jPixels, nullptr);
    if (!out) return JNI_FALSE;
    constexpr int GW = 32, GH = 16;
    for (int oy = 0; oy < outH; oy++) {
        for (int ox = 0; ox < outW; ox++) {
            int gx = ox * GW / outW;
            int gy = oy * GH / outH;
            int x0 = gx * eqW / GW, x1 = (gx + 1) * eqW / GW;
            int y0 = gy * eqH / GH, y1 = (gy + 1) * eqH / GH;
            float maxW = 0.f;
            for (int y = y0; y < y1; y++)
                for (int x = x0; x < x1; x++)
                    maxW = std::max(maxW, eqWeight[y * eqW + x]);
            out[oy * outW + ox] = (maxW > 0.05f) ? 0 : 0x88FF0000;
        }
    }
    env->ReleaseIntArrayElements(jPixels, out, 0);
    return JNI_TRUE;
}

JNIEXPORT jfloatArray JNICALL
Java_ab_inque_visiontechai_SphereNative_nativeGetLastFrameInfo(
    JNIEnv* env, jobject) {
    jfloatArray result = env->NewFloatArray(7);
    float data[7] = {
        static_cast<float>(sFrameIndex),
        static_cast<float>(sLastOverlapCount),
        static_cast<float>(sLastTotalSamples),
        sLastAlignOffset,
        sLastResidualSSD,
        static_cast<float>(sLastRejected),
        sLastRollOffset
    };
    env->SetFloatArrayRegion(result, 0, 7, data);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_ab_inque_visiontechai_SphereNative_nativeLoadEquirect(
    JNIEnv* env, jobject,
    jintArray jPixels, jint width, jint height) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    eqW = width;
    eqH = height;
    int sz = eqW * eqH;
    eqR.resize(sz);
    eqG.resize(sz);
    eqB.resize(sz);
    eqWeight.resize(sz);

    jint* pixels = env->GetIntArrayElements(jPixels, nullptr);
    if (!pixels) return JNI_FALSE;

    for (int i = 0; i < sz; i++) {
        int32_t p = pixels[i];
        eqR[i] = static_cast<float>((p >> 16) & 0xff);
        eqG[i] = static_cast<float>((p >> 8) & 0xff);
        eqB[i] = static_cast<float>(p & 0xff);
        eqWeight[i] = 1.0f;
    }

    env->ReleaseIntArrayElements(jPixels, pixels, JNI_ABORT);
    LOGI("Sphere loaded equirect: %dx%d", eqW, eqH);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_ab_inque_visiontechai_SphereNative_nativeReset(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    int sz = eqW * eqH;
    if (sz > 0) {
        std::memset(eqR.data(), 0, sz * sizeof(float));
        std::memset(eqG.data(), 0, sz * sizeof(float));
        std::memset(eqB.data(), 0, sz * sizeof(float));
        std::memset(eqWeight.data(), 0, sz * sizeof(float));
    }
    sFrameIndex = 0;
    sLastRejected = 0;
    LOGI("Sphere reset");
}

JNIEXPORT void JNICALL
Java_ab_inque_visiontechai_SphereNative_nativeRelease(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    eqR.clear(); eqR.shrink_to_fit();
    eqG.clear(); eqG.shrink_to_fit();
    eqB.clear(); eqB.shrink_to_fit();
    eqWeight.clear(); eqWeight.shrink_to_fit();
    eqW = 0; eqH = 0;
    LOGI("Sphere released");
}

} // extern "C"
