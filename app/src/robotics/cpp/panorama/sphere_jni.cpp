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
#include <cstdint>
#include <algorithm>
#include <vector>
#include <mutex>
#include <chrono>
#include <functional>
#include <set>

#define LOG_TAG "SphereJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static constexpr double PI = 3.14159265358979323846;
static constexpr double DEG2RAD = PI / 180.0;
static constexpr double RAD2DEG = 180.0 / PI;

// Time budget for the entire addFrame operation (microseconds).
// Alignment refinement is skipped once this budget is exceeded.
static constexpr int64_t TIME_BUDGET_US = 150000; // 150ms
// Photo / offline replay: allow deeper align + warp (export quality).
static constexpr int64_t QUALITY_TIME_BUDGET_US = 500000; // 500ms

// Residual SSD above this threshold (after alignment) means poor match
static constexpr double REJECT_SSD_THRESH = 8000.0;
static constexpr double REJECT_SSD_RELAXED = REJECT_SSD_THRESH * 1.35;
// G1-fast IMU recovery thresholds
static constexpr double FAST_PAIRWISE_SSD_THRESH = 2000.0;
static constexpr double FAST_IMU_GAP_DEG_THRESH = 3.0;
static constexpr double FAST_ESCALATE_CORR_DEG = 5.0;
static constexpr double FAST_ESCALATE_SSD_THRESH = 2500.0;
static constexpr double FAST_DUBIOUS_SSD_THRESH = 2800.0;
static constexpr float FAST_TENTATIVE_SPLAT_WEIGHT = 0.35f;
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
/** When true, negate camera-frame X so bitmap + IMU handedness match (back camera). */
static bool sMirrorCameraX = false;
static std::vector<float> eqR, eqG, eqB, eqWeight;
/** Sobel gradient magnitude on equirect luminance (rotation search coarse stage). */
static std::vector<float> eqGradMag;
/** Per-pixel best view-dot during nearest-view export compositing (higher wins). */
static std::vector<float> eqSplatCenterDistSq;
static bool sNearestCenterSplat = false;
static std::mutex sphereMutex;

// ── Incremental coverage tracking (F1: avoids full-buffer scans per frame) ──
static constexpr int SA_GW = 32, SA_GH = 16;      // solid-angle grid resolution
static constexpr float SA_FILL_THRESH = 0.05f;    // per-pixel weight to count a cell
static int64_t sCoveredCount = 0;                 // eq pixels with weight > 0
static std::vector<int> sGridFilled;              // filled-pixel count per grid cell
static int sGridFilledCells = 0;                  // grid cells with ≥1 filled pixel

/** When true, eq buffer at eqW×eqH already matches stored poses (skip export resplat). */
static bool sEquirectBufferFresh = false;

static inline void resetCoverageCountersLocked() {
    sCoveredCount = 0;
    sGridFilledCells = 0;
    sGridFilled.assign(SA_GW * SA_GH, 0);
}

static inline void markEquirectFreshLocked() {
    sEquirectBufferFresh = true;
}

static inline void invalidateEquirectFreshLocked() {
    sEquirectBufferFresh = false;
}

// Full recompute from the weight buffer (used after direct buffer loads).
static void recomputeCoverageCountersLocked() {
    resetCoverageCountersLocked();
    if (eqW == 0 || eqH == 0) return;
    for (int y = 0; y < eqH; y++) {
        int gy = y * SA_GH / eqH;
        for (int x = 0; x < eqW; x++) {
            float w = eqWeight[y * eqW + x];
            if (w > 0.f) sCoveredCount++;
            if (w > SA_FILL_THRESH) {
                int cell = gy * SA_GW + (x * SA_GW / eqW);
                if (sGridFilled[cell]++ == 0) sGridFilledCells++;
            }
        }
    }
}

// ── Phase G: per-cell affine warp field (G2 estimate + G3 extend) ───────
struct CellAffine {
    float a = 1.f, b = 0.f, tx = 0.f;
    float c = 0.f, d = 1.f, ty = 0.f;
    float confidence = 0.f;    // 1 = measured in overlap; 0 = interpolated
};

struct FrameWarp {
    CellAffine cells[TILE_GAIN_N][TILE_GAIN_N];
};

static inline void initIdentityWarp(FrameWarp& w) {
    for (int y = 0; y < TILE_GAIN_N; y++) {
        for (int x = 0; x < TILE_GAIN_N; x++) {
            w.cells[y][x] = {};
            w.cells[y][x].a = 1.f;
            w.cells[y][x].d = 1.f;
        }
    }
}

// ── Frame store for two-phase (fast + background refine) stitching ──────
struct StoredFrame {
    std::vector<int32_t> px;   // downscaled RGB pixels
    std::vector<float> depth;  // G5: normalized nearness [0,1], 0.5=unknown; same w×h as px
    int w = 0, h = 0;
    Quat refinedQuat = {0, 0, 0, 1};
    Quat imuQuat = {0, 0, 0, 1};  // raw IMU pose at capture (prior for global refine)
    Quat fastCorrectedQuat = {0, 0, 0, 1};  // G1-fast live rotational correction
    float expGainR = 1.f, expGainG = 1.f, expGainB = 1.f;  // S12 capture-time exposure
    // G2 6-DoF: small camera-frame translation (optimized in global BA, baked into warp).
    float poseTx = 0.f, poseTy = 0.f, poseTz = 0.f;
    FrameWarp warp{};
    bool locked = false;       // locked frames are re-splatted but not re-aligned
    bool refined = false;      // true once the frame has been aligned at least once
};
static std::vector<StoredFrame> sFrames;

// ── I0: overlap correspondence bank for joint intrinsic + pose ground cost ──
struct OverlapCorrespondence {
    int frameI = 0;
    int frameJ = 0;
    float wx = 0.f, wy = 0.f, wz = 0.f;
    float uI = 0.f, vI = 0.f;
    float uJ = 0.f, vJ = 0.f;
};
static std::vector<OverlapCorrespondence> sOverlapBank;
static constexpr size_t OVERLAP_BANK_MAX = 50000;

// ── I1: session focal scale on tanH/tanV (1.0 = prior FOV from init) ──
static float sFocalScale = 1.f;
static constexpr float FOCAL_SCALE_MIN = 0.88f;
static constexpr float FOCAL_SCALE_MAX = 1.12f;
static constexpr double FOCAL_PRIOR_LAMBDA = 500.0;

// ── I2: session radial k1 (prior from init / LensDistortionStore) ──
static float distK1Prior = 0.f;
static constexpr double K1_PRIOR_LAMBDA = 2000.0;
static constexpr double K1_SEARCH_DELTA = 0.05;

static inline void sessionTanHalfFov(double& tanH, double& tanV) {
    tanH = std::tan(storedFovH * 0.5 * DEG2RAD) * sFocalScale;
    tanV = std::tan(storedFovV * 0.5 * DEG2RAD) * sFocalScale;
}

static constexpr int REALIGN_WINDOW = 40;  // most recent N frames get re-aligned
static int sFramesAtLastRefine = 0;
// IMU prior weight in global pose cost (rad²); higher = trust IMU more.
static constexpr double IMU_PRIOR_LAMBDA = 80.0;

static double adaptiveImuPriorLambda(double photometricSSD) {
    if (photometricSSD > 7000.0) return 8.0;
    if (photometricSSD > 4500.0) return 22.0;
    if (photometricSSD > 2500.0) return 45.0;
    return IMU_PRIOR_LAMBDA;
}
// Store at analysis resolution for refine rebuilds (no downscale below typical camera analysis).
static constexpr int STORE_MAX_DIM = 2560;
// F2: cap longest frame side for live-path splat (full-res stored for refine/export).
static constexpr int LIVE_SPLAT_DIM = 320;
// F2: skip equirect pixels on large live buffers (4096² tests / export-prep).
static constexpr int LIVE_EQ_SPLAT_STRIDE_THRESHOLD = 2048;
// Fast-path global translation warp budget (microseconds).
static constexpr int64_t FAST_WARP_BUDGET_US = 5000;
// G5: depth-weighted parallax scale for near geometry.
static constexpr float DEPTH_PARALLAX_MIN_SCALE = 0.35f;
static constexpr float DEPTH_PARALLAX_MAX_SCALE = 1.65f;
// G2 6-DoF: translation prior + bake scale (camera-frame units → pixels).
static constexpr double TRANS_PRIOR_LAMBDA = 120.0;
static constexpr float POSE_T_MAX = 0.06f;
static constexpr float POSE_T_TO_PX_SCALE = 0.22f;

// Per-frame alignment metrics
static int sFrameIndex = 0;
static int sLastOverlapCount = 0;
static int sLastTotalSamples = 0;
static float sLastAlignOffset = 0.f;
static Quat sLastCorrectedQuat = {0, 0, 0, 1};
static float sLastRollOffset = 0.f;
static float sLastResidualSSD = 0.f;
static int sLastRejected = 0;
static int sShiftX = 0;
static int sShiftY = 0;
static float sLastWarpTx = 0.f;
static float sLastWarpTy = 0.f;

// Last refinePending telemetry (PG-R3 / S25).
static float sLastRefineSsdBefore = 0.f;
static float sLastRefineSsdAfter = 0.f;
static int sLastRefineFramesRealigned = 0;
static int sLastRefineGlobalPose = 0;
static int sLastRefineRan = 0;
// True after the last successful refine used applyGlobalPose (L2b finish).
static int sCompletedRefineWasGlobal = 0;
// Set after mid-capture global (soft rebuild only); consumed by one DONE global pass.
static int sPendingFinishGlobalUpgrade = 0;
// E2E / perf tests: when >0, caps globalRefinePosesLocked outer passes (0 = production default).
static int sGlobalRefinePassesOverride = 0;
// Fast finish: chained pose + soft rebuild only (skip motion-warp pass for <30s E2E).
static int sFastFinishRefine = 0;

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

/** Forward Brown–Conrady on normalized plane (inverse of [undistortNorm]). */
static inline void distortNorm(double& nx, double& ny) {
    if (distK1 == 0.f && distK2 == 0.f && distP1 == 0.f && distP2 == 0.f) return;
    double x = nx, y = ny;
    double r2 = x * x + y * y;
    double radial = 1.0 + distK1 * r2 + distK2 * r2 * r2;
    double dx = 2.0 * distP1 * x * y + distP2 * (r2 + 2.0 * x * x);
    double dy = distP1 * (r2 + 2.0 * y * y) + 2.0 * distP2 * x * y;
    nx = x * radial + dx;
    ny = y * radial + dy;
}

static inline void pixelToRay(int fx, int fy, int frameW, int frameH,
                              double tanH, double tanV,
                              double& rx, double& ry, double& rz) {
    double nx = ((double)fx / (frameW - 1) - 0.5) * 2.0 * tanH;
    double ny = (0.5 - (double)fy / (frameH - 1)) * 2.0 * tanV;
    if (sMirrorCameraX) nx = -nx;
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

static inline void applyCellAffine(const CellAffine& c, float fx, float fy,
                                   float& ox, float& oy) {
    ox = c.a * fx + c.b * fy + c.tx;
    oy = c.c * fx + c.d * fy + c.ty;
}

static inline void bilinearCellAffine(const FrameWarp& warp, float fx, float fy,
                                      int frameW, int frameH, CellAffine& out) {
    double u = (double)fx / std::max(1, frameW - 1) * TILE_GAIN_N;
    double v = (double)fy / std::max(1, frameH - 1) * TILE_GAIN_N;
    int x0 = clampI((int)u, 0, TILE_GAIN_N - 1);
    int y0 = clampI((int)v, 0, TILE_GAIN_N - 1);
    int x1 = std::min(TILE_GAIN_N - 1, x0 + 1);
    int y1 = std::min(TILE_GAIN_N - 1, y0 + 1);
    double txi = u - x0, tyi = v - y0;
    auto lerp = [&](auto getter) -> float {
        float v00 = getter(warp.cells[y0][x0]);
        float v10 = getter(warp.cells[y0][x1]);
        float v01 = getter(warp.cells[y1][x0]);
        float v11 = getter(warp.cells[y1][x1]);
        return static_cast<float>(
            (1.0 - txi) * (1.0 - tyi) * v00 +
            txi * (1.0 - tyi) * v10 +
            (1.0 - txi) * tyi * v01 +
            txi * tyi * v11);
    };
    out.a = lerp([](const CellAffine& c) { return c.a; });
    out.b = lerp([](const CellAffine& c) { return c.b; });
    out.tx = lerp([](const CellAffine& c) { return c.tx; });
    out.c = lerp([](const CellAffine& c) { return c.c; });
    out.d = lerp([](const CellAffine& c) { return c.d; });
    out.ty = lerp([](const CellAffine& c) { return c.ty; });
    out.confidence = lerp([](const CellAffine& c) { return c.confidence; });
}

static inline void warpPixelCoords(const FrameWarp& warp, float fx, float fy,
                                   int frameW, int frameH, float& wx, float& wy) {
    CellAffine blended;
    bilinearCellAffine(warp, fx, fy, frameW, frameH, blended);
    applyCellAffine(blended, fx, fy, wx, wy);
}

static inline void pixelToRayFloat(double fx, double fy, int frameW, int frameH,
                                   double tanH, double tanV,
                                   double& rx, double& ry, double& rz) {
    double nx = (fx / (frameW - 1) - 0.5) * 2.0 * tanH;
    double ny = (0.5 - fy / (frameH - 1)) * 2.0 * tanV;
    if (sMirrorCameraX) nx = -nx;
    undistortNorm(nx, ny);
    double len = std::sqrt(nx * nx + ny * ny + 1.0);
    rx = nx / len;
    ry = ny / len;
    rz = -1.0 / len;
}

/** Equirect pixel center → unit direction in earth frame (X=East, Y=North, Z=Up). */
static inline void equirectPixelToWorldRay(double eqXf, double eqYf, int w, int h,
                                           double& wx, double& wy, double& wz) {
    double lon = (eqXf / w * 2.0 - 1.0) * PI;
    double lat = (0.5 - eqYf / h) * PI;
    double clat = std::cos(lat);
    wx = clat * std::sin(lon);
    wy = clat * std::cos(lon);
    wz = std::sin(lat);
}

/** Inverse of [quatRotate] for unit quaternions (conjugate). */
static inline void quatInverseRotate(const Quat& q, double vx, double vy, double vz,
                                     double& ox, double& oy, double& oz) {
    Quat qi{-q.x, -q.y, -q.z, q.w};
    quatRotate(qi, vx, vy, vz, ox, oy, oz);
}

/**
 * World ray → camera frame pixel (pinhole, camera looks along −Z).
 * Returns false if the ray is behind the camera or outside the image.
 */
static inline bool worldRayToFramePixel(
    double wx, double wy, double wz,
    const Quat& qCam,
    int frameW, int frameH, double tanH, double tanV,
    float& outFx, float& outFy) {

    double crx, cry, crz;
    quatInverseRotate(qCam, wx, wy, wz, crx, cry, crz);
    if (crz >= -1e-4) return false;

    double nx = crx / (-crz);
    double ny = cry / (-crz);
    if (sMirrorCameraX) nx = -nx;
    distortNorm(nx, ny);
    double fx = (nx / (2.0 * tanH) + 0.5) * (frameW - 1);
    double fy = (0.5 - ny / (2.0 * tanV)) * (frameH - 1);
    if (fx < 0.0 || fx > frameW - 1 || fy < 0.0 || fy > frameH - 1) return false;
    outFx = static_cast<float>(fx);
    outFy = static_cast<float>(fy);
    return true;
}

// Feature matching + pose-graph constraints (after pixelToRay / worldRayToFramePixel).
static void rebuildOverlapBankLocked();
static double quatAngularDistSq(const Quat& a, const Quat& b);
#include "sphere_feature_match.inl"

/** Parallax shift on ref pixel from relative camera-frame translation (6-DoF BA). */
static inline void applyParallaxPixelShift(
    float& refFx, float& refFy,
    const Quat& qRef, float refTx, float refTy, float refTz,
    const Quat& qSrc, float srcTx, float srcTy, float srcTz,
    int frameW, double tanH, float nearness) {

    double rtx, rty, rtz, stx, sty, stz;
    quatRotate(qRef, refTx, refTy, refTz, rtx, rty, rtz);
    quatRotate(qSrc, srcTx, srcTy, srcTz, stx, sty, stz);
    double bx = stx - rtx, by = sty - rty, bz = stz - rtz;
    double brx, bry, brz;
    quatInverseRotate(qRef, bx, by, bz, brx, bry, brz);
    (void)brz;
    float depthScale = 0.25f + 0.75f * (1.f - std::max(0.f, std::min(1.f, nearness)));
    double pxPerUnit = frameW / (2.0 * tanH);
    refFx += static_cast<float>(brx / depthScale * pxPerUnit * POSE_T_TO_PX_SCALE);
    refFy += static_cast<float>(-bry / depthScale * pxPerUnit * POSE_T_TO_PX_SCALE);
}

/** Project a frame corner through pose into equirect floating coords. */
static inline void framePixelToEquirect(
    int fx, int fy, int frameW, int frameH,
    double tanH, double tanV, const Quat& q,
    double& eqXf, double& eqYf) {

    double rx, ry, rz;
    pixelToRay(fx, fy, frameW, frameH, tanH, tanV, rx, ry, rz);
    double wx, wy, wz;
    quatRotate(q, rx, ry, rz, wx, wy, wz);
    double lon = std::atan2(wx, wy);
    double lat = std::asin(std::max(-1.0, std::min(1.0, wz)));
    eqXf = (lon / PI + 1.0) * 0.5 * eqW;
    eqYf = (0.5 - lat / PI) * eqH;
}

static inline void samplePixelBilinear(const int32_t* pixels, int w, int h,
                                       float fx, float fy,
                                       float& r, float& g, float& b) {
    fx = std::max(0.f, std::min((float)(w - 1), fx));
    fy = std::max(0.f, std::min((float)(h - 1), fy));
    int x0 = (int)std::floor(fx);
    int y0 = (int)std::floor(fy);
    int x1 = std::min(x0 + 1, w - 1);
    int y1 = std::min(y0 + 1, h - 1);
    float tfx = fx - x0, tfy = fy - y0;
    auto ch = [&](int x, int y, int shift) -> float {
        return static_cast<float>((pixels[y * w + x] >> shift) & 0xff);
    };
    r = (1.f - tfx) * (1.f - tfy) * ch(x0, y0, 16) +
        tfx * (1.f - tfy) * ch(x1, y0, 16) +
        (1.f - tfx) * tfy * ch(x0, y1, 16) +
        tfx * tfy * ch(x1, y1, 16);
    g = (1.f - tfx) * (1.f - tfy) * ch(x0, y0, 8) +
        tfx * (1.f - tfy) * ch(x1, y0, 8) +
        (1.f - tfx) * tfy * ch(x0, y1, 8) +
        tfx * tfy * ch(x1, y1, 8);
    b = (1.f - tfx) * (1.f - tfy) * ch(x0, y0, 0) +
        tfx * (1.f - tfy) * ch(x1, y0, 0) +
        (1.f - tfx) * tfy * ch(x0, y1, 0) +
        tfx * tfy * ch(x1, y1, 0);
}

static void smoothWarpField(FrameWarp& warp) {
    FrameWarp tmp = warp;
    for (int pass = 0; pass < 4; pass++) {
        for (int ty = 0; ty < TILE_GAIN_N; ty++) {
            for (int tx = 0; tx < TILE_GAIN_N; tx++) {
                if (warp.cells[ty][tx].confidence >= 0.99f) continue;
                float wa = 0.f, wb = 0.f, wtx = 0.f, wc = 0.f, wd = 0.f, wty = 0.f;
                float wsum = 0.f;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = tx + dx, ny = ty + dy;
                        if (nx < 0 || nx >= TILE_GAIN_N || ny < 0 || ny >= TILE_GAIN_N) continue;
                        float w = warp.cells[ny][nx].confidence;
                        if (w <= 0.f) continue;
                        const CellAffine& c = warp.cells[ny][nx];
                        wa += c.a * w; wb += c.b * w; wtx += c.tx * w;
                        wc += c.c * w; wd += c.d * w; wty += c.ty * w;
                        wsum += w;
                    }
                }
                if (wsum > 0.f) {
                    tmp.cells[ty][tx].a = wa / wsum;
                    tmp.cells[ty][tx].b = wb / wsum;
                    tmp.cells[ty][tx].tx = wtx / wsum;
                    tmp.cells[ty][tx].c = wc / wsum;
                    tmp.cells[ty][tx].d = wd / wsum;
                    tmp.cells[ty][tx].ty = wty / wsum;
                    tmp.cells[ty][tx].confidence = std::min(0.95f, wsum * 0.25f);
                }
            }
        }
        warp = tmp;
    }
}

// Recency bias during background refine rebuild — decay prior overlap weight
// so newer frames dominate instead of washing to a muddy average.
// (Legacy; refine now uses replace-on-overlap in forward splat.)

static bool isIdentityWarp(const FrameWarp& w) {
    for (int y = 0; y < TILE_GAIN_N; y++) {
        for (int x = 0; x < TILE_GAIN_N; x++) {
            const CellAffine& c = w.cells[y][x];
            if (std::abs(c.tx) > 0.01f || std::abs(c.ty) > 0.01f ||
                std::abs(c.a - 1.f) > 0.001f || std::abs(c.d - 1.f) > 0.001f ||
                std::abs(c.b) > 0.001f || std::abs(c.c) > 0.001f) {
                return false;
            }
        }
    }
    return true;
}

/** Fast-path warp is a single global translation (no per-cell field). */
static bool isUniformTranslationWarp(const FrameWarp& w, float& outTx, float& outTy) {
    const CellAffine& ref = w.cells[0][0];
    if (std::abs(ref.a - 1.f) > 0.001f || std::abs(ref.d - 1.f) > 0.001f ||
        std::abs(ref.b) > 0.001f || std::abs(ref.c) > 0.001f) {
        return false;
    }
    outTx = ref.tx;
    outTy = ref.ty;
    for (int y = 0; y < TILE_GAIN_N; y++) {
        for (int x = 0; x < TILE_GAIN_N; x++) {
            const CellAffine& c = w.cells[y][x];
            if (std::abs(c.tx - outTx) > 0.01f || std::abs(c.ty - outTy) > 0.01f ||
                std::abs(c.a - 1.f) > 0.001f || std::abs(c.d - 1.f) > 0.001f ||
                std::abs(c.b) > 0.001f || std::abs(c.c) > 0.001f) {
                return false;
            }
        }
    }
    return std::abs(outTx) > 0.01f || std::abs(outTy) > 0.01f;
}

static inline void splatPixel(int ex, int ey, float r, float g, float b, float w) {
    if (ex < 0 || ex >= eqW || ey < 0 || ey >= eqH) return;
    // S16: cos(lat) solid-angle weight — poles cover less sphere area.
    double eqYf = ey + 0.5;
    double lat = (0.5 - eqYf / eqH) * PI;
    w *= static_cast<float>(std::max(0.05, std::cos(lat)));
    int idx = ey * eqW + ex;
    float before = eqWeight[idx];
    float after = before + w;
    // Incremental coverage bookkeeping on weight-threshold transitions.
    if (before == 0.f && after > 0.f) sCoveredCount++;
    if (before <= SA_FILL_THRESH && after > SA_FILL_THRESH) {
        int cell = (ey * SA_GH / eqH) * SA_GW + (ex * SA_GW / eqW);
        if (sGridFilled[cell]++ == 0) sGridFilledCells++;
    }
    eqR[idx] += r * w;
    eqG[idx] += g * w;
    eqB[idx] += b * w;
    eqWeight[idx] = after;
}

/**
 * Nearest-neighbor downscale for F2 live splat. Output longest side ≤ maxDim.
 * Returns false when no downscale needed (caller uses source buffer).
 */
static bool downscaleArgbNearest(
    const int32_t* src, int srcW, int srcH,
    std::vector<int32_t>& dst, int& dstW, int& dstH, int maxDim) {

    const int maxSide = std::max(srcW, srcH);
    if (maxSide <= maxDim) {
        dstW = srcW;
        dstH = srcH;
        return false;
    }
    const double scale = (double)maxDim / maxSide;
    dstW = std::max(1, (int)std::lround(srcW * scale));
    dstH = std::max(1, (int)std::lround(srcH * scale));
    dst.resize((size_t)dstW * dstH);
    for (int y = 0; y < dstH; y++) {
        const int sy = clampI((int)((y + 0.5) * srcH / dstH - 0.5), 0, srcH - 1);
        for (int x = 0; x < dstW; x++) {
            const int sx = clampI((int)((x + 0.5) * srcW / dstW - 0.5), 0, srcW - 1);
            dst[(size_t)y * dstW + x] = src[(size_t)sy * srcW + sx];
        }
    }
    return true;
}

/**
 * Inverse spherical splat: for each equirect pixel in the frame footprint,
 * unproject to a camera ray, sample the frame, and write into the sphere grid.
 * Matches [nativeGetRectilinear] — overlap is aligned on spherical coordinates.
 * [eqStep] > 1 thins live-path splat on large equirect buffers (F2).
 */
static void splatFrameInverseSpherical(
    const int32_t* pixels, int frameW, int frameH,
    const Quat& qCorrected, double tanH, double tanV,
    const FrameWarp& warp,
    const float* tileGains,
    bool replaceOverlap,
    double featherX, double featherY,
    float globalGainR = 1.f, float globalGainG = 1.f, float globalGainB = 1.f,
    int eqStep = 1,
    float weightScale = 1.f) {

    eqStep = std::max(1, eqStep);

    const int margin = std::max(16, std::max(frameW, frameH) / 32);
    double minXf = eqW, maxXf = -1.0, minYf = eqH, maxYf = -1.0;
    const int corners[9][2] = {
        {0, 0}, {frameW - 1, 0}, {0, frameH - 1}, {frameW - 1, frameH - 1},
        {frameW / 2, frameH / 2},
        {frameW / 2, 0}, {frameW / 2, frameH - 1},
        {0, frameH / 2}, {frameW - 1, frameH / 2}
    };
    for (const auto& c : corners) {
        double ex, ey;
        framePixelToEquirect(c[0], c[1], frameW, frameH, tanH, tanV, qCorrected, ex, ey);
        minXf = std::min(minXf, ex);
        maxXf = std::max(maxXf, ex);
        minYf = std::min(minYf, ey);
        maxYf = std::max(maxYf, ey);
    }

    int y0 = clampI((int)std::floor(minYf) - margin, 0, eqH - 1);
    int y1 = clampI((int)std::ceil(maxYf) + margin, 0, eqH - 1);
    int x0 = clampI((int)std::floor(minXf) - margin, 0, eqW - 1);
    int x1 = clampI((int)std::ceil(maxXf) + margin, 0, eqW - 1);
    const bool wrapX = (maxXf - minXf) > eqW * 0.45;
    const bool seamCross = x0 > x1;

    float uniformTx = 0.f, uniformTy = 0.f;
    const bool uniformTrans = isUniformTranslationWarp(warp, uniformTx, uniformTy);
    const bool useFullWarp = !isIdentityWarp(warp) && !uniformTrans;

    auto splatEquirectPixel = [&](int ex, int ey) {
        double eqXf = ex + 0.5;
        double eqYf = ey + 0.5;
        double wx, wy, wz;
        equirectPixelToWorldRay(eqXf, eqYf, eqW, eqH, wx, wy, wz);

        float fx, fy;
        if (!worldRayToFramePixel(wx, wy, wz, qCorrected, frameW, frameH, tanH, tanV, fx, fy)) {
            return;
        }

        float sampleFx = fx;
        float sampleFy = fy;
        if (uniformTrans) {
            sampleFx += uniformTx;
            sampleFy += uniformTy;
        } else if (useFullWarp) {
            warpPixelCoords(warp, fx, fy, frameW, frameH, sampleFx, sampleFy);
        }

        if (sampleFx < 0.f || sampleFx > frameW - 1 || sampleFy < 0.f || sampleFy > frameH - 1) {
            return;
        }

        float gR = bilinearTileGain(tileGains, 0, (int)sampleFx, (int)sampleFy, frameW, frameH);
        float gG = bilinearTileGain(tileGains, 1, (int)sampleFx, (int)sampleFy, frameW, frameH);
        float gB = bilinearTileGain(tileGains, 2, (int)sampleFx, (int)sampleFy, frameW, frameH);

        double edgeDistX = std::min((double)sampleFx, (double)(frameW - 1 - sampleFx));
        double edgeDistY = std::min((double)sampleFy, (double)(frameH - 1 - sampleFy));
        double edgeFrac = std::min(1.0, std::min(edgeDistX / featherX, edgeDistY / featherY));
        float fw = static_cast<float>(0.5 - 0.5 * std::cos(edgeFrac * PI));
        fw *= weightScale;
        if (fw < 1e-6f) return;

        int idx = ey * eqW + ex;
        if (sNearestCenterSplat) {
            // Pick the frame whose optical axis best matches this equirect ray (not pixel-center distance).
            double fwx, fwy, fwz;
            quatRotate(qCorrected, 0.0, 0.0, -1.0, fwx, fwy, fwz);
            const float viewDot = static_cast<float>(wx * fwx + wy * fwy + wz * fwz);
            if (eqWeight[idx] > 0.f && viewDot <= eqSplatCenterDistSq[idx]) {
                return;
            }
            if (eqWeight[idx] > 0.f) {
                eqR[idx] = eqG[idx] = eqB[idx] = 0.f;
                eqWeight[idx] = 0.f;
            }
            eqSplatCenterDistSq[idx] = viewDot;
        } else if (replaceOverlap && eqWeight[idx] > 0.f) {
            eqR[idx] = eqG[idx] = eqB[idx] = 0.f;
            eqWeight[idx] = 0.f;
        }

        float pr, pg, pb;
        samplePixelBilinear(pixels, frameW, frameH, sampleFx, sampleFy, pr, pg, pb);
        pr *= gR * globalGainR;
        pg *= gG * globalGainG;
        pb *= gB * globalGainB;

        splatPixel(ex, ey, pr, pg, pb, fw);
    };

    for (int ey = y0; ey <= y1; ey += eqStep) {
        if (wrapX) {
            for (int ex = 0; ex < eqW; ex += eqStep) splatEquirectPixel(ex, ey);
        } else if (seamCross) {
            for (int ex = x0; ex < eqW; ex += eqStep) splatEquirectPixel(ex, ey);
            for (int ex = 0; ex <= x1; ex += eqStep) splatEquirectPixel(ex, ey);
        } else {
            for (int ex = x0; ex <= x1; ex += eqStep) splatEquirectPixel(ex, ey);
        }
    }
}

// ── S12 exposure + multiband seam helpers ───────────────────────────────

struct OverlapSample;

static void applyOverlapExposureNormalization(
    const std::vector<OverlapSample>& overlap,
    const Quat& qPose,
    double& gainR, double& gainG, double& gainB);

static void refineSeamsMultibandPassLocked(int bandRadius, float gradThreshold);

static float medianOf(std::vector<float> vals) {
    if (vals.empty()) return 0.f;
    size_t mid = vals.size() / 2;
    std::nth_element(vals.begin(), vals.begin() + (ptrdiff_t)mid, vals.end());
    return vals[mid];
}

static void blurHorizontal5(const float* src, float* dst, int w, int h) {
    static constexpr float k[5] = {1.f / 16.f, 4.f / 16.f, 6.f / 16.f, 4.f / 16.f, 1.f / 16.f};
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            float sum = 0.f;
            for (int dx = -2; dx <= 2; dx++) {
                int sx = (x + dx + w) % w;
                sum += src[y * w + sx] * k[dx + 2];
            }
            dst[y * w + x] = sum;
        }
    }
}

static void blurVertical5(const float* src, float* dst, int w, int h) {
    static constexpr float k[5] = {1.f / 16.f, 4.f / 16.f, 6.f / 16.f, 4.f / 16.f, 1.f / 16.f};
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            float sum = 0.f;
            for (int dy = -2; dy <= 2; dy++) {
                int sy = clampI(y + dy, 0, h - 1);
                sum += src[sy * w + x] * k[dy + 2];
            }
            dst[y * w + x] = sum;
        }
    }
}

static void gaussianBlur5(const std::vector<float>& src, std::vector<float>& tmp,
                          std::vector<float>& dst, int w, int h) {
    tmp.resize((size_t)w * h);
    dst.resize((size_t)w * h);
    blurHorizontal5(src.data(), tmp.data(), w, h);
    blurVertical5(tmp.data(), dst.data(), w, h);
}

/** Multiband (frequency separation) seam blend — low freq smoothed across seams, detail kept. */
static void refineSeamsMultibandPassLocked(int bandRadius, float gradThreshold) {
    if (eqW == 0 || eqH == 0) return;
    const int w = eqW, h = eqH;
    const int sz = w * h;
    const int radius = std::max(3, bandRadius);

    std::vector<float> nr(sz, 0.f), ng(sz, 0.f), nb(sz, 0.f);
    std::vector<uint8_t> seam(sz, 0);
    for (int i = 0; i < sz; i++) {
        if (eqWeight[i] > 0.f) {
            float inv = 1.f / eqWeight[i];
            nr[i] = eqR[i] * inv;
            ng[i] = eqG[i] * inv;
            nb[i] = eqB[i] * inv;
        }
    }

    for (int y = radius; y < h - radius; y++) {
        for (int x = 0; x < w; x++) {
            int idx = y * w + x;
            if (eqWeight[idx] < 0.05f) continue;
            float wCenter = eqWeight[idx];
            float maxGrad = 0.f;
            for (int dy = -radius; dy <= radius; dy += 2) {
                int ny = y + dy;
                if (ny < 0 || ny >= h) continue;
                for (int dx = -radius; dx <= radius; dx += 2) {
                    int nx = (x + dx + w) % w;
                    maxGrad = std::max(maxGrad, std::abs(eqWeight[ny * w + nx] - wCenter));
                }
            }
            if (maxGrad >= gradThreshold) seam[idx] = 1;
        }
    }

    std::vector<float> nrLo, ngLo, nbLo, tmp;
    gaussianBlur5(nr, tmp, nrLo, w, h);
    gaussianBlur5(ng, tmp, ngLo, w, h);
    gaussianBlur5(nb, tmp, nbLo, w, h);

    for (int y = radius; y < h - radius; y++) {
        for (int x = 0; x < w; x++) {
            int idx = y * w + x;
            if (!seam[idx]) continue;

            float sr = 0.f, sg = 0.f, sb = 0.f, sw = 0.f;
            for (int dy = -radius; dy <= radius; dy += 2) {
                int ny = y + dy;
                if (ny < 0 || ny >= h) continue;
                for (int dx = -radius; dx <= radius; dx += 2) {
                    int nx = (x + dx + w) % w;
                    int j = ny * w + nx;
                    if (eqWeight[j] < 0.05f) continue;
                    float gw = 1.f / (1.f + (float)(dx * dx + dy * dy));
                    sr += nrLo[j] * gw;
                    sg += ngLo[j] * gw;
                    sb += nbLo[j] * gw;
                    sw += gw;
                }
            }
            if (sw > 0.f) {
                float loR = sr / sw, loG = sg / sw, loB = sb / sw;
                nr[idx] = loR + (nr[idx] - nrLo[idx]);
                ng[idx] = loG + (ng[idx] - ngLo[idx]);
                nb[idx] = loB + (nb[idx] - nbLo[idx]);
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

// Weight-gradient seam blend — only at sharp weight discontinuities (narrow seams).
static void refineSeamsPassLocked(int radius, float gradThreshold) {
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
            if (maxGrad < gradThreshold) continue;

            float sr = 0.f, sg = 0.f, sb = 0.f, sw = 0.f;
            for (int dy = -radius; dy <= radius; dy += 2) {
                int ny = y + dy;
                if (ny < 0 || ny >= eqH) continue;
                for (int dx = -radius; dx <= radius; dx += 2) {
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

/** Narrow weight-gradient blend only — used on fast DONE export to avoid multiband blur. */
static void refineSeamsLightLocked() {
    refineSeamsPassLocked(1, 2.5f);
}

/** S12: gradient pass + multiband frequency separation at seam bands. */
static void refineSeamsLocked(int radius = 3) {
    refineSeamsPassLocked(radius, 1.4f);
    refineSeamsMultibandPassLocked(std::max(radius + 2, 6), 1.0f);
    refineSeamsPassLocked(std::max(radius + 1, 4), 1.1f);
}

// ── JNI functions ───────────────────────────────────────────────────────

extern "C" {

JNIEXPORT jboolean JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeInit(
    JNIEnv*, jobject, jint width, jint height, jfloat fovH, jfloat fovV,
    jfloat k1, jfloat k2, jfloat p1, jfloat p2) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    eqW = width;
    eqH = height;
    storedFovH = fovH;
    storedFovV = fovV;
    distK1 = k1;
    distK1Prior = k1;
    distK2 = k2;
    distP1 = p1;
    distP2 = p2;
    int sz = eqW * eqH;
    eqR.assign(sz, 0.f);
    eqG.assign(sz, 0.f);
    eqB.assign(sz, 0.f);
    eqWeight.assign(sz, 0.f);
    resetCoverageCountersLocked();
    sFrames.clear();
    sOverlapBank.clear();
    sFeatureEdges.clear();
    sFocalScale = 1.f;
    sFrameIndex = 0;
    sLastRejected = 0;
    sLastRollOffset = 0.f;
    sMirrorCameraX = false;
    LOGI("Sphere init: %dx%d  fov=%.1f x %.1f  dist k1=%.4f", eqW, eqH, fovH, fovV, k1);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeSetSessionFov(
    JNIEnv*, jobject, jfloat fovH, jfloat fovV) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    if (eqW == 0 || eqH == 0) return JNI_FALSE;
    storedFovH = fovH;
    storedFovV = fovV;
    LOGI("Session FOV updated: %.2f x %.2f (focalScale=%.4f)", fovH, fovV, sFocalScale);
    return JNI_TRUE;
}

// Outcome of a single stitch attempt.
struct FrameOutcome {
    bool accepted = false;
    bool rejectedOverlap = false;
    int overlapCount = 0;
    int totalSamples = 0;
    float overlapRatio = 0.f;
    double finalSSD = 0.0;
    Quat corrected = {0, 0, 0, 1};
    float expGainR = 1.f, expGainG = 1.f, expGainB = 1.f;
    FrameWarp warp{};
};

struct OverlapSample {
    float newR, newG, newB;
    double rx, ry, rz;
    int fx, fy;
    float newGrad;
};

/** Median luminance + per-channel p50 match in overlap (robust exposure normalization). */
static void applyOverlapExposureNormalization(
    const std::vector<OverlapSample>& overlap,
    const Quat& qPose,
    double& gainR, double& gainG, double& gainB) {

    if (overlap.size() < 24) return;

    std::vector<float> exR, exG, exB, newR, newG, newB, exL, newL;
    exR.reserve(overlap.size());
    newR.reserve(overlap.size());

    for (const auto& s : overlap) {
        double wx, wy, wz;
        quatRotate(qPose, s.rx, s.ry, s.rz, wx, wy, wz);
        double lon = std::atan2(wx, wy);
        double lat = std::asin(std::max(-1.0, std::min(1.0, wz)));
        int eqX = ((int)((lon / PI + 1.0) * 0.5 * eqW) + eqW) % eqW;
        int eqY = clampI((int)((0.5 - lat / PI) * eqH), 0, eqH - 1);
        int idx = eqY * eqW + eqX;
        if (eqWeight[idx] <= 0.f) continue;

        float inv = 1.f / eqWeight[idx];
        float er = eqR[idx] * inv;
        float eg = eqG[idx] * inv;
        float eb = eqB[idx] * inv;
        exR.push_back(er);
        exG.push_back(eg);
        exB.push_back(eb);
        newR.push_back(s.newR);
        newG.push_back(s.newG);
        newB.push_back(s.newB);
        exL.push_back(0.299f * er + 0.587f * eg + 0.114f * eb);
        newL.push_back(0.299f * s.newR + 0.587f * s.newG + 0.114f * s.newB);
    }
    if (exR.size() < 20) return;

    float medExL = medianOf(exL);
    float medNewL = medianOf(newL);
    if (medNewL > 6.f && medExL > 6.f) {
        float lumGain = std::max(0.55f, std::min(2.0f, medExL / medNewL));
        gainR = gainG = gainB = std::max(0.5, std::min(2.0, (double)lumGain));
    }

    auto channelGain = [](const std::vector<float>& ex, const std::vector<float>& nw) -> float {
        float medEx = medianOf(std::vector<float>(ex));
        float medNew = medianOf(std::vector<float>(nw));
        if (medNew < 4.f || medEx < 4.f) return 1.f;
        return std::max(0.55f, std::min(2.0f, medEx / medNew));
    };
    // Per-channel tint correction on top of luminance match (avoid double-count on gray).
    float chR = channelGain(exR, newR);
    float chG = channelGain(exG, newG);
    float chB = channelGain(exB, newB);
    float lumRef = (medNewL > 6.f && medExL > 6.f)
        ? std::max(0.55f, std::min(2.0f, medExL / medNewL)) : 1.f;
    if (std::abs(chR - lumRef) > 0.12f) {
        gainR = std::max(0.5, std::min(2.0, gainR * chR / lumRef));
    }
    if (std::abs(chG - lumRef) > 0.12f) {
        gainG = std::max(0.5, std::min(2.0, gainG * chG / lumRef));
    }
    if (std::abs(chB - lumRef) > 0.12f) {
        gainB = std::max(0.5, std::min(2.0, gainB * chB / lumRef));
    }
}

static double computeWarpShiftSSD(
    const std::vector<OverlapSample>& samples,
    size_t begin, size_t end,
    const Quat& qC,
    int shiftX, int shiftY,
    int frameW, int frameH, double tanH, double tanV,
    double gainR, double gainG, double gainB,
    int stride = 1,
    const float* depthMap = nullptr) {

    double ssd = 0.0;
    double weightSum = 0.0;
    int count = 0;
    for (size_t i = begin; i < end; i += (size_t)stride) {
        const auto& s = samples[i];
        double wx = s.fx + shiftX;
        double wy = s.fy + shiftY;
        if (wx < 0 || wy < 0 || wx >= frameW || wy >= frameH) continue;
        double rx, ry, rz;
        pixelToRayFloat(wx, wy, frameW, frameH, tanH, tanV, rx, ry, rz);
        double ewx, ewy, ewz;
        quatRotate(qC, rx, ry, rz, ewx, ewy, ewz);
        double lon = std::atan2(ewx, ewy);
        double lat = std::asin(std::max(-1.0, std::min(1.0, ewz)));
        int eqX = ((int)((lon / PI + 1.0) * 0.5 * eqW) + eqW) % eqW;
        int eqY = clampI((int)((0.5 - lat / PI) * eqH), 0, eqH - 1);
        int idx = eqY * eqW + eqX;
        if (eqWeight[idx] > 0.f) {
            float inv = 1.f / eqWeight[idx];
            double dr = s.newR * gainR - eqR[idx] * inv;
            double dg = s.newG * gainG - eqG[idx] * inv;
            double db = s.newB * gainB - eqB[idx] * inv;
            float dw = 1.f;
            if (depthMap != nullptr) {
                int ix = clampI((int)std::lround(s.fx), 0, frameW - 1);
                int iy = clampI((int)std::lround(s.fy), 0, frameH - 1);
                float d = depthMap[iy * frameW + ix];
                dw = 0.25f + 1.75f * std::max(0.f, std::min(1.f, d));
            }
            ssd += dw * (dr * dr + dg * dg + db * db);
            weightSum += dw;
            count++;
        }
    }
    if (count < 3) return 1e18;
    return ssd / std::max(1.0, weightSum);
}

// G2: estimate per-cell translation affines in overlap, then smooth to full frame.
// When seedFromExisting is true, start global search at the stored fast-path warp (S20).
static void estimatePerCellWarp(
    const std::vector<OverlapSample>& overlap,
    const Quat& qCorrected,
    double gainR, double gainG, double gainB,
    int frameW, int frameH,
    double tanH, double tanV,
    FrameWarp& warp,
    double& outSSD,
    bool seedFromExisting = false,
    const float* depthMap = nullptr) {

    int seedGx = 0, seedGy = 0;
    bool hadSeed = false;
    if (seedFromExisting) {
        float stx = 0.f, sty = 0.f;
        if (isUniformTranslationWarp(warp, stx, sty)) {
            seedGx = static_cast<int>(std::lround(stx));
            seedGy = static_cast<int>(std::lround(sty));
            hadSeed = true;
        }
    }

    initIdentityWarp(warp);
    if (overlap.size() < 20) {
        outSSD = 0.0;
        return;
    }

    // Global translation search (replaces legacy micro-shift).
    int bestGx = seedGx, bestGy = seedGy;
    double bestGSSD = computeWarpShiftSSD(
        overlap, 0, overlap.size(), qCorrected, seedGx, seedGy,
        frameW, frameH, tanH, tanV, gainR, gainG, gainB, 1, depthMap);
    const int searchRadius = hadSeed ? 8 : 10;
    const int searchStep = hadSeed ? 2 : 5;
    for (int sy = seedGy - searchRadius; sy <= seedGy + searchRadius; sy += searchStep) {
        for (int sx = seedGx - searchRadius; sx <= seedGx + searchRadius; sx += searchStep) {
            if (sx == seedGx && sy == seedGy) continue;
            double s = computeWarpShiftSSD(
                overlap, 0, overlap.size(), qCorrected, sx, sy,
                frameW, frameH, tanH, tanV, gainR, gainG, gainB, 1, depthMap);
            if (s < bestGSSD) {
                bestGSSD = s;
                bestGx = sx;
                bestGy = sy;
            }
        }
    }
    if (hadSeed) {
        for (int sy = bestGy - 2; sy <= bestGy + 2; sy++) {
            for (int sx = bestGx - 2; sx <= bestGx + 2; sx++) {
                double s = computeWarpShiftSSD(
                    overlap, 0, overlap.size(), qCorrected, sx, sy,
                    frameW, frameH, tanH, tanV, gainR, gainG, gainB, 1, depthMap);
                if (s < bestGSSD) {
                    bestGSSD = s;
                    bestGx = sx;
                    bestGy = sy;
                }
            }
        }
    }

    // Per-tile refinement relative to global shift.
    std::vector<size_t> tileSamples[TILE_GAIN_N * TILE_GAIN_N];
    for (size_t i = 0; i < overlap.size(); i++) {
        int tx = clampI(overlap[i].fx * TILE_GAIN_N / std::max(1, frameW), 0, TILE_GAIN_N - 1);
        int ty = clampI(overlap[i].fy * TILE_GAIN_N / std::max(1, frameH), 0, TILE_GAIN_N - 1);
        tileSamples[ty * TILE_GAIN_N + tx].push_back(i);
    }

    for (int ty = 0; ty < TILE_GAIN_N; ty++) {
        for (int tx = 0; tx < TILE_GAIN_N; tx++) {
            const auto& indices = tileSamples[ty * TILE_GAIN_N + tx];
            CellAffine& cell = warp.cells[ty][tx];
            cell.a = 1.f;
            cell.d = 1.f;
            if (indices.size() < 3) {
                cell.tx = static_cast<float>(bestGx);
                cell.ty = static_cast<float>(bestGy);
                cell.confidence = indices.empty() ? 0.f : 0.35f;
                continue;
            }
            int bestTx = bestGx, bestTy = bestGy;
            double bestSSD = 1e18;
            for (int sy = bestGy - 6; sy <= bestGy + 6; sy += 2) {
                for (int sx = bestGx - 6; sx <= bestGx + 6; sx += 2) {
                    double s = 0.0;
                    int count = 0;
                    for (size_t idx : indices) {
                        const auto& samp = overlap[idx];
                        double wx = samp.fx + sx;
                        double wy = samp.fy + sy;
                        if (wx < 0 || wy < 0 || wx >= frameW || wy >= frameH) continue;
                        double rx, ry, rz;
                        pixelToRayFloat(wx, wy, frameW, frameH, tanH, tanV, rx, ry, rz);
                        double ewx, ewy, ewz;
                        quatRotate(qCorrected, rx, ry, rz, ewx, ewy, ewz);
                        double lon = std::atan2(ewx, ewy);
                        double lat = std::asin(std::max(-1.0, std::min(1.0, ewz)));
                        int eqX = ((int)((lon / PI + 1.0) * 0.5 * eqW) + eqW) % eqW;
                        int eqY = clampI((int)((0.5 - lat / PI) * eqH), 0, eqH - 1);
                        int eidx = eqY * eqW + eqX;
                        if (eqWeight[eidx] > 0.f) {
                            float inv = 1.f / eqWeight[eidx];
                            double dr = samp.newR * gainR - eqR[eidx] * inv;
                            double dg = samp.newG * gainG - eqG[eidx] * inv;
                            double db = samp.newB * gainB - eqB[eidx] * inv;
                            s += dr * dr + dg * dg + db * db;
                            count++;
                        }
                    }
                    if (count >= 2) {
                        s /= count;
                        if (s < bestSSD) {
                            bestSSD = s;
                            bestTx = sx;
                            bestTy = sy;
                        }
                    }
                }
            }
            cell.tx = static_cast<float>(bestTx);
            cell.ty = static_cast<float>(bestTy);
            cell.confidence = std::min(1.f, static_cast<float>(indices.size()) / 8.f);
        }
    }

    smoothWarpField(warp);
    outSSD = bestGSSD;
    LOGI("Warp field: global=(%d,%d) SSD=%.1f", bestGx, bestGy, bestGSSD);
}

// G2-fast: global translation only, budgeted for addFrameFast live preview (<5ms).
static void estimateFastGlobalWarp(
    const std::vector<OverlapSample>& overlap,
    const Quat& q,
    double gainR, double gainG, double gainB,
    int frameW, int frameH,
    double tanH, double tanV,
    FrameWarp& warp,
    const float* depthMap = nullptr) {

    initIdentityWarp(warp);
    if (overlap.size() < 25) return;

    int bestGx = 0, bestGy = 0;
    double bestGSSD = computeWarpShiftSSD(
        overlap, 0, overlap.size(), q, 0, 0,
        frameW, frameH, tanH, tanV, gainR, gainG, gainB, 4, depthMap);
    for (int sy = -15; sy <= 15; sy += 5) {
        for (int sx = -15; sx <= 15; sx += 5) {
            if (sx == 0 && sy == 0) continue;
            double s = computeWarpShiftSSD(
                overlap, 0, overlap.size(), q, sx, sy,
                frameW, frameH, tanH, tanV, gainR, gainG, gainB, 4, depthMap);
            if (s < bestGSSD) {
                bestGSSD = s;
                bestGx = sx;
                bestGy = sy;
            }
        }
    }
    if (bestGx != 0 || bestGy != 0) {
        for (int sy = bestGy - 2; sy <= bestGy + 2; sy++) {
            for (int sx = bestGx - 2; sx <= bestGx + 2; sx++) {
                double s = computeWarpShiftSSD(
                    overlap, 0, overlap.size(), q, sx, sy,
                    frameW, frameH, tanH, tanV, gainR, gainG, gainB, 2, depthMap);
                if (s < bestGSSD) {
                    bestGSSD = s;
                    bestGx = sx;
                    bestGy = sy;
                }
            }
        }
    }
    for (int ty = 0; ty < TILE_GAIN_N; ty++) {
        for (int tx = 0; tx < TILE_GAIN_N; tx++) {
            warp.cells[ty][tx].a = 1.f;
            warp.cells[ty][tx].d = 1.f;
            warp.cells[ty][tx].tx = static_cast<float>(bestGx);
            warp.cells[ty][tx].ty = static_cast<float>(bestGy);
            warp.cells[ty][tx].confidence = 1.f;
        }
    }
    LOGI("Fast warp: global=(%d,%d) SSD=%.1f", bestGx, bestGy, bestGSSD);
}

/**
 * Core stitch: optionally aligns and then splats one frame into the global
 * equirect accumulation buffers. Caller must hold sphereMutex and owns `pixels`.
 *
 *  doAlign      — run the (expensive) multi-level rotational alignment search.
 *  allowReject  — allow frame-level rejection (quality / very-low-overlap).
 *  overlapGate  — if >0 and not the first frame, reject when the fraction of the
 *                 frame overlapping existing content is below this threshold.
 */
static void applyDepthParallaxWarp(
    FrameWarp& warp, const float* depthMap, int frameW, int frameH);

static double quatAngularDistRad(const Quat& a, const Quat& b);
static double liveFramePairwiseRotSSD(
    const int32_t* pixels, int w, int h,
    const Quat& qCand,
    const StoredFrame& prev, const Quat& qPrev,
    double tanH, double tanV,
    int step);

static inline float pixelLumaF(float r, float g, float b) {
    return 0.299f * r + 0.587f * g + 0.114f * b;
}

static float sobelGradMag9(
    float lum_tl, float lum_tc, float lum_tr,
    float lum_ml, float lum_mr,
    float lum_bl, float lum_bc, float lum_br) {
    float gx = -lum_tl + lum_tr - 2.f * lum_ml + 2.f * lum_mr - lum_bl + lum_br;
    float gy = -lum_tl - 2.f * lum_tc - lum_tr + lum_bl + 2.f * lum_bc + lum_br;
    return std::sqrt(gx * gx + gy * gy);
}

static float framePixelGradMag(const int32_t* pixels, int w, int h, int fx, int fy) {
    if (fx < 1 || fy < 1 || fx >= w - 1 || fy >= h - 1) return 0.f;
    auto lum = [&](int x, int y) {
        int32_t p = pixels[y * w + x];
        return pixelLumaF(
            static_cast<float>((p >> 16) & 0xff),
            static_cast<float>((p >> 8) & 0xff),
            static_cast<float>(p & 0xff));
    };
    return sobelGradMag9(
        lum(fx - 1, fy - 1), lum(fx, fy - 1), lum(fx + 1, fy - 1),
        lum(fx - 1, fy), lum(fx + 1, fy),
        lum(fx - 1, fy + 1), lum(fx, fy + 1), lum(fx + 1, fy + 1));
}

static float storedPixelGradMag(const StoredFrame& f, int fx, int fy) {
    if (fx < 1 || fy < 1 || fx >= f.w - 1 || fy >= f.h - 1) return 0.f;
    auto lum = [&](int x, int y) {
        int32_t p = f.px[(size_t)y * f.w + x];
        return pixelLumaF(
            static_cast<float>((p >> 16) & 0xff),
            static_cast<float>((p >> 8) & 0xff),
            static_cast<float>(p & 0xff));
    };
    return sobelGradMag9(
        lum(fx - 1, fy - 1), lum(fx, fy - 1), lum(fx + 1, fy - 1),
        lum(fx - 1, fy), lum(fx + 1, fy),
        lum(fx - 1, fy + 1), lum(fx, fy + 1), lum(fx + 1, fy + 1));
}

/** Sobel magnitude on weighted equirect luminance for edge-based coarse rotation search. */
static void rebuildEquirectGradMagLocked() {
    const int sz = eqW * eqH;
    if (sz <= 0) return;
    if ((int)eqGradMag.size() != sz) eqGradMag.assign(sz, 0.f);
    std::fill(eqGradMag.begin(), eqGradMag.end(), 0.f);
    for (int y = 1; y < eqH - 1; y++) {
        for (int x = 1; x < eqW - 1; x++) {
            int idx = y * eqW + x;
            if (eqWeight[idx] <= 0.f) continue;
            float inv = 1.f / eqWeight[idx];
            auto lumAt = [&](int cx, int cy) -> float {
                int i = cy * eqW + cx;
                if (eqWeight[i] <= 0.f) return -1.f;
                return pixelLumaF(eqR[i] * inv, eqG[i] * inv, eqB[i] * inv);
            };
            float tl = lumAt(x - 1, y - 1);
            float tc = lumAt(x, y - 1);
            float tr = lumAt(x + 1, y - 1);
            float ml = lumAt(x - 1, y);
            float mr = lumAt(x + 1, y);
            float bl = lumAt(x - 1, y + 1);
            float bc = lumAt(x, y + 1);
            float br = lumAt(x + 1, y + 1);
            if (tl < 0.f) continue;
            eqGradMag[idx] = sobelGradMag9(tl, tc, tr, ml, mr, bl, bc, br);
        }
    }
}

/** 1 − ZNCC on gradient magnitudes; 0 = perfect, lower is better. */
static double overlapGradZnccCost(
    const std::vector<OverlapSample>& overlap,
    const Quat& qC,
    int subsample) {
    double sumA = 0.0, sumB = 0.0, sumAA = 0.0, sumBB = 0.0, sumAB = 0.0;
    int count = 0;
    for (size_t i = 0; i < overlap.size(); i += (size_t)subsample) {
        const auto& s = overlap[i];
        double wx, wy, wz;
        quatRotate(qC, s.rx, s.ry, s.rz, wx, wy, wz);
        double lon = std::atan2(wx, wy);
        double lat = std::asin(std::max(-1.0, std::min(1.0, wz)));
        int eqX = ((int)((lon / PI + 1.0) * 0.5 * eqW) + eqW) % eqW;
        int eqY = clampI((int)((0.5 - lat / PI) * eqH), 0, eqH - 1);
        int idx = eqY * eqW + eqX;
        if (eqWeight[idx] > 0.f && eqGradMag[idx] > 0.f && s.newGrad > 0.f) {
            const double a = s.newGrad;
            const double b = eqGradMag[idx];
            sumA += a;
            sumB += b;
            sumAA += a * a;
            sumBB += b * b;
            sumAB += a * b;
            count++;
        }
    }
    if (count < 20) return 1e18;
    const double n = static_cast<double>(count);
    const double va = sumAA - sumA * sumA / n;
    const double vb = sumBB - sumB * sumB / n;
    if (va < 1e-6 || vb < 1e-6) return 1.0;
    double zncc = (sumAB - sumA * sumB / n) / std::sqrt(va * vb);
    zncc = std::max(-1.0, std::min(1.0, zncc));
    return 1.0 - zncc;
}

/** G1-fast wide coarse radius scales with IMU misalignment and RGB SSD. */
static double fastG1WideRadiusRad(double imuGapDeg, double rgbBaseline) {
    double rDeg = 12.0;
    if (imuGapDeg > 10.0) rDeg = 20.0;
    else if (imuGapDeg > 6.0) rDeg = 16.0;
    else if (imuGapDeg > 3.0) rDeg = 14.0;
    if (rgbBaseline > 4000.0) rDeg = std::max(rDeg, 16.0);
    return rDeg * DEG2RAD;
}

static double fastG1MidRadiusRad(double imuGapDeg, double rgbBaseline) {
    double rDeg = (rgbBaseline > 3000.0) ? 10.0 : 6.0;
    if (imuGapDeg > 10.0) rDeg = std::max(rDeg, 14.0);
    else if (imuGapDeg > 6.0) rDeg = std::max(rDeg, 10.0);
    return rDeg * DEG2RAD;
}

static FrameOutcome processFrameLocked(
    const int32_t* pixels, int frameW, int frameH,
    Quat q, bool doAlign, bool allowReject, float overlapGate,
    FrameWarp* warpInOut = nullptr,
    bool recencyComposite = false,
    const float* depthMap = nullptr,
    float forcedGainR = 0.f, float forcedGainG = 0.f, float forcedGainB = 0.f,
    bool exportRebuild = false) {

    FrameOutcome outcome{};
    auto t0 = SteadyClock::now();
    const int64_t frameTimeBudgetUs =
        (!allowReject && doAlign) ? QUALITY_TIME_BUDGET_US : TIME_BUDGET_US;
    sShiftX = 0;
    sShiftY = 0;

    FrameWarp localWarp{};
    initIdentityWarp(localWarp);
    FrameWarp* warp = warpInOut ? warpInOut : &localWarp;

    double qlen = std::sqrt(q.x*q.x + q.y*q.y + q.z*q.z + q.w*q.w);
    if (qlen < 1e-9) return outcome;
    q.x /= qlen; q.y /= qlen; q.z /= qlen; q.w /= qlen;

    double tanH, tanV;
    sessionTanHalfFov(tanH, tanV);

    // ── Phase 1: sparse sampling for gain + overlap ──
    // Pre-compute ray direction per sample so the alignment inner loop
    // avoids redundant sqrt / divide per evaluation.
    std::vector<OverlapSample> overlap;
    constexpr int SAMPLE_STEP = 8;
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
                overlap.push_back({
                    nr, ng, nb, rx, ry, rz, fx, fy,
                    framePixelGradMag(pixels, frameW, frameH, fx, fy)});
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

    // ── Overlap gate: require sufficient edge overlap with existing content ──
    // Forces the user to pan slowly enough that consecutive frames share area.
    float overlapRatio = totalSamples > 0
        ? static_cast<float>(overlap.size()) / totalSamples : 0.f;
    if (overlapGate > 0.f && sFrameIndex > 0 && overlapRatio < overlapGate) {
        // Bad IMU seed shrinks projected overlap — defer gate when we can still fast-align.
        if (overlap.size() < 50) {
            sFrameIndex++;
            sLastOverlapCount = static_cast<int>(overlap.size());
            sLastTotalSamples = totalSamples;
            sLastAlignOffset = 0.f;
            sLastResidualSSD = 0.f;
            sLastRollOffset = 0.f;
            sLastRejected = 1;
            LOGW("Frame %d gated: overlap %.1f%% < %.1f%% (pan slower)",
                 sFrameIndex, overlapRatio * 100.f, overlapGate * 100.f);
            outcome.accepted = false;
            outcome.rejectedOverlap = true;
            outcome.overlapCount = static_cast<int>(overlap.size());
            outcome.totalSamples = totalSamples;
            outcome.overlapRatio = overlapRatio;
            return outcome;
        }
        LOGW("Frame %d overlap %.1f%% < %.1f%% — deferring gate for fast align (%zu samples)",
             sFrameIndex + 1, overlapRatio * 100.f, overlapGate * 100.f, overlap.size());
    }

    // ── Phase 2: Rotational alignment (time-budgeted) ──
    Quat qCorrected = q;
    double finalSSD = 0.0;
    bool frameRejected = false;
    int alignLevelsCompleted = 0;
    bool usedFullRotAlign = doAlign;
    bool tentativeSplatFrame = false;

    if (doAlign && overlap.size() > 50) {
        const bool limitedAlign = recencyComposite;

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

        auto computeRotGradSSD = [&](double dyaw, double dpitch, double droll, int subsample) -> double {
            Quat qY = quatFromAxisAngle(0, 0, 1, dyaw);
            Quat qP = quatFromAxisAngle(1, 0, 0, dpitch);
            Quat qR = quatFromAxisAngle(0, 0, 1, droll);
            Quat qC = quatMul(qY, quatMul(qP, quatMul(qR, q)));
            return overlapGradZnccCost(overlap, qC, subsample);
        };

        double baselineSSD = computeRotSSD(0, 0, 0, 1);
        double bestDyaw = 0, bestDpitch = 0, bestDroll = 0;
        double bestSSD = baselineSSD;

        constexpr double TRUST_SEED_SSD = 300.0;
        const bool trustSeed = !allowReject && baselineSSD < TRUST_SEED_SSD;
        const bool edgeCoarseAlign =
            !allowReject && baselineSSD > 2500.0;
        double baselineGradSSD = 1e18;
        double bestGradSSD = 1e18;
        if (edgeCoarseAlign) {
            rebuildEquirectGradMagLocked();
            baselineGradSSD = computeRotGradSSD(0, 0, 0, 6);
            bestGradSSD = baselineGradSSD;
        }

        if (recencyComposite && baselineSSD < 800.0) {
            qCorrected = q;
            finalSSD = baselineSSD;
        } else if (trustSeed) {
            qCorrected = q;
            finalSSD = baselineSSD;
            LOGI("Align: trust seed SSD=%.1f (<%g)", baselineSSD, TRUST_SEED_SSD);
        } else {

        auto parabolicMin = [](double sm, double s0, double sp) -> double {
            double denom = 2.0 * (sm + sp - 2.0 * s0);
            if (std::abs(denom) < 1e-12) return 0.0;
            return std::max(-0.5, std::min(0.5, (sm - sp) / denom));
        };

        // Offline / quality: ultra-wide edge coarse when IMU seed is far from minimum.
        if (!allowReject && baselineSSD > 2500.0) {
            constexpr double R = 18.0 * DEG2RAD;
            constexpr double S = 2.0 * DEG2RAD;
            for (double dp = -R; dp <= R; dp += S)
                for (double dy = -R; dy <= R; dy += S) {
                    if (edgeCoarseAlign) {
                        double s = computeRotGradSSD(dy, dp, 0, 6);
                        if (s < bestGradSSD) {
                            bestGradSSD = s;
                            bestDyaw = dy;
                            bestDpitch = dp;
                        }
                    } else {
                        double s = computeRotSSD(dy, dp, 0, 6);
                        if (s < bestSSD) { bestSSD = s; bestDyaw = dy; bestDpitch = dp; }
                    }
                }
            if (edgeCoarseAlign) {
                bestSSD = computeRotSSD(bestDyaw, bestDpitch, bestDroll, 2);
            }
            alignLevelsCompleted = 1;
        }

        // Level 2 (coarsest): ±7° (±5° during refine rebuild) — RGB
        {
            const double R_FULL = (!allowReject) ? (9.0 * DEG2RAD) : (7.0 * DEG2RAD);
            constexpr double R_LIMITED = 5.0 * DEG2RAD;
            constexpr double S = 1.0 * DEG2RAD;
            const double R = limitedAlign ? R_LIMITED : R_FULL;
            for (double dp = -R; dp <= R; dp += S)
                for (double dy = -R; dy <= R; dy += S) {
                    double s = computeRotSSD(dy, dp, 0, 4);
                    if (s < bestSSD) { bestSSD = s; bestDyaw = dy; bestDpitch = dp; }
                }
            alignLevelsCompleted = 1;
        }

        // Roll search ±5° (1° step) with best yaw/pitch — RGB
        if (elapsedUs(t0) < frameTimeBudgetUs * 35 / 100) {
            constexpr double R = 5.0 * DEG2RAD;
            constexpr double S = 1.0 * DEG2RAD;
            for (double dr = -R; dr <= R; dr += S) {
                double s = computeRotSSD(bestDyaw, bestDpitch, dr, 4);
                if (s < bestSSD) { bestSSD = s; bestDroll = dr; }
            }
        }

        // Level 1: ±1.5° with 0.25° step
        if (elapsedUs(t0) < frameTimeBudgetUs * 40 / 100) {
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
        if (elapsedUs(t0) < frameTimeBudgetUs * 55 / 100) {
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
        if (elapsedUs(t0) < frameTimeBudgetUs * 65 / 100) {
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
        if (elapsedUs(t0) < frameTimeBudgetUs * 75 / 100) {
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

        // Phase 2b (G2) runs after qCorrected is built — see below.

        finalSSD = bestSSD;

        // Build corrected quaternion
        const double alignAcceptRatio = (!allowReject)
            ? (baselineSSD < 500.0 ? 0.98 : 0.88)
            : (recencyComposite ? 0.99 : 0.92);
        if (baselineSSD < 1e17 && bestSSD > baselineSSD * alignAcceptRatio) {
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

        // Phase 2b (G2): per-cell affine warp field estimated in overlap.
        if (!exportRebuild &&
            elapsedUs(t0) < frameTimeBudgetUs * 82 / 100 && overlap.size() > 60) {
            FrameWarp warpBeforeEstimate = *warp;
            double warpSSD = bestSSD;
            estimatePerCellWarp(
                overlap, qCorrected, gainR, gainG, gainB,
                frameW, frameH, tanH, tanV, *warp, warpSSD,
                recencyComposite);
            if (warpSSD < bestSSD * 0.95) {
                bestSSD = warpSSD;
                finalSSD = warpSSD;
            } else if (!allowReject) {
                // Quality/offline: keep parallax warp even when gain is marginal.
            } else if (recencyComposite && !isIdentityWarp(warpBeforeEstimate) && depthMap == nullptr) {
                *warp = warpBeforeEstimate;
            } else {
                initIdentityWarp(*warp);
            }
        }
        } // end rotational / warp search (skipped when refine trusts IMU)

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
        if (allowReject && finalSSD > REJECT_SSD_THRESH && sFrameIndex > 0) {
            if (finalSSD <= REJECT_SSD_RELAXED && overlap.size() > 80) {
                LOGI("Frame %d fallback ACCEPT: SSD=%.1f (relaxed %.1f)",
                     sFrameIndex + 1, finalSSD, REJECT_SSD_RELAXED);
            } else {
                frameRejected = true;
                LOGW("Frame %d REJECTED: SSD=%.1f (thresh=%.1f)",
                     sFrameIndex + 1, finalSSD, REJECT_SSD_THRESH);
            }
        }
    } else if (!doAlign && !recencyComposite && !exportRebuild &&
               overlapGate > 0.f && overlap.size() > 50 && sFrameIndex > 0) {
        // G1-fast IMU recovery: only on live capture when overlapGate > 0 (degraded seed).
        // Stored-pose replay and truth replay (overlapGate=0) skip this path entirely.
        const StoredFrame& prevFrame = sFrames[sFrameIndex - 1];
        const Quat qPrev = prevFrame.fastCorrectedQuat;

        auto computeEquirectRotSSD = [&](const Quat& base, double dyaw, double dpitch,
                                         int subsample) -> double {
            Quat qY = quatFromAxisAngle(0, 0, 1, dyaw);
            Quat qP = quatFromAxisAngle(1, 0, 0, dpitch);
            Quat qC = quatMul(qY, quatMul(qP, base));
            double ssd = 0.0;
            int count = 0;
            for (size_t i = 0; i < overlap.size(); i += (size_t)subsample) {
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

        auto computeEquirectRotGradSSD = [&](const Quat& base, double dyaw, double dpitch,
                                             int subsample) -> double {
            Quat qY = quatFromAxisAngle(0, 0, 1, dyaw);
            Quat qP = quatFromAxisAngle(1, 0, 0, dpitch);
            Quat qC = quatMul(qY, quatMul(qP, base));
            return overlapGradZnccCost(overlap, qC, subsample);
        };

        const double rgbBaselineStart = computeEquirectRotSSD(q, 0, 0, 2);
        double bestDyaw = 0, bestDpitch = 0;
        double bestRgbSSD = rgbBaselineStart;
        finalSSD = rgbBaselineStart;

        // G1a ultra-coarse edge-map pyramid when IMU seed looks misaligned.
        const bool needWideCoarse =
            rgbBaselineStart > FAST_PAIRWISE_SSD_THRESH ||
            (overlapGate > 0.f && overlapRatio < overlapGate * 1.5f);

        double gradBaselineStart = 1e18;
        double bestGradSSD = 1e18;
        if (needWideCoarse) {
            rebuildEquirectGradMagLocked();
            gradBaselineStart = computeEquirectRotGradSSD(q, 0, 0, 8);
            bestGradSSD = gradBaselineStart;
        }

        const double prevImuFastDeg =
            quatAngularDistRad(prevFrame.imuQuat, prevFrame.fastCorrectedQuat) * RAD2DEG;
        const double interImuDeg =
            quatAngularDistRad(q, prevFrame.imuQuat) * RAD2DEG;
        const double imuGapDeg = std::max(prevImuFastDeg, interImuDeg);
        LOGI("Fast edge G1: rgb SSD %.1f grad SSD %.1f imuGap %.1f°",
             rgbBaselineStart, gradBaselineStart, imuGapDeg);

        double g1aBestDyaw = 0, g1aBestDpitch = 0;
        if (needWideCoarse && elapsedUs(t0) < frameTimeBudgetUs * 30 / 100) {
            const double Rwide = fastG1WideRadiusRad(imuGapDeg, rgbBaselineStart);
            const double Swide = (Rwide > 14.0 * DEG2RAD) ? (4.0 * DEG2RAD) : (3.0 * DEG2RAD);
            for (double dp = -Rwide; dp <= Rwide; dp += Swide) {
                for (double dy = -Rwide; dy <= Rwide; dy += Swide) {
                    double s = computeEquirectRotGradSSD(q, dy, dp, 8);
                    if (s < bestGradSSD) {
                        bestGradSSD = s;
                        bestDyaw = dy;
                        bestDpitch = dp;
                    }
                }
            }
            g1aBestDyaw = bestDyaw;
            g1aBestDpitch = bestDpitch;
        }

        // G1b mid: RGB refine around G1a edge seed (±6–14° at 1.5° steps).
        double midRad = fastG1MidRadiusRad(imuGapDeg, rgbBaselineStart);
        if (overlapGate > 0.f && overlapRatio < overlapGate * 1.25f) {
            midRad = std::max(midRad, 12.0 * DEG2RAD);
        }
        constexpr double Smid = 1.5 * DEG2RAD;
        const double sy0 = bestDyaw;
        const double sp0 = bestDpitch;
        for (double dp = sp0 - midRad; dp <= sp0 + midRad; dp += Smid) {
            if (elapsedUs(t0) >= frameTimeBudgetUs * 42 / 100) break;
            for (double dy = sy0 - midRad; dy <= sy0 + midRad; dy += Smid) {
                if (elapsedUs(t0) >= frameTimeBudgetUs * 42 / 100) break;
                double s = computeEquirectRotSSD(q, dy, dp, 4);
                if (s < bestRgbSSD) {
                    bestRgbSSD = s;
                    bestDyaw = dy;
                    bestDpitch = dp;
                }
            }
        }

        // G1c fine: RGB polish ±0.5° at 0.1° steps.
        if (elapsedUs(t0) < frameTimeBudgetUs * 50 / 100) {
            constexpr double Rf = 0.5 * DEG2RAD;
            constexpr double Sf = 0.1 * DEG2RAD;
            for (double dp = bestDpitch - Rf; dp <= bestDpitch + Rf; dp += Sf) {
                for (double dy = bestDyaw - Rf; dy <= bestDyaw + Rf; dy += Sf) {
                    double s = computeEquirectRotSSD(q, dy, dp, 2);
                    if (s < bestRgbSSD) {
                        bestRgbSSD = s;
                        bestDyaw = dy;
                        bestDpitch = dp;
                    }
                }
            }
        }

        const bool rgbImproved =
            rgbBaselineStart < 1e17 && bestRgbSSD < rgbBaselineStart * 0.96;
        if (rgbImproved) {
            Quat qY = quatFromAxisAngle(0, 0, 1, bestDyaw);
            Quat qP = quatFromAxisAngle(1, 0, 0, bestDpitch);
            qCorrected = quatMul(qY, quatMul(qP, q));
            LOGI("Fast align edge+rgb: yaw=%.3f° pitch=%.3f° grad %.1f->%.1f rgb %.1f->%.1f",
                 bestDyaw * RAD2DEG, bestDpitch * RAD2DEG,
                 gradBaselineStart, bestGradSSD, rgbBaselineStart, bestRgbSSD);
        }
        if (bestRgbSSD < 1e17) {
            finalSSD = bestRgbSSD;
        }
        if (overlapGate > 0.f && finalSSD > FAST_DUBIOUS_SSD_THRESH) {
            tentativeSplatFrame = true;
        }

        // G2-fast + G3-fast: translation warp after rotational correction.
        if (overlap.size() > 40) {
            auto tWarp = SteadyClock::now();
            estimateFastGlobalWarp(
                overlap, qCorrected, gainR, gainG, gainB,
                frameW, frameH, tanH, tanV, *warp, depthMap);
            if (overlap.size() > 60 &&
                elapsedUs(tWarp) < FAST_WARP_BUDGET_US / 2) {
                double warpSSD = 0.0;
                estimatePerCellWarp(
                    overlap, qCorrected, gainR, gainG, gainB,
                    frameW, frameH, tanH, tanV, *warp, warpSSD,
                    /*seedFromExisting=*/true, depthMap);
                if (depthMap != nullptr) {
                    applyDepthParallaxWarp(*warp, depthMap, frameW, frameH);
                }
            }
            int64_t warpUs = elapsedUs(tWarp);
            if (warpUs > FAST_WARP_BUDGET_US) {
                LOGW("Fast warp over budget: %lldus > %lldus",
                     (long long)warpUs, (long long)FAST_WARP_BUDGET_US);
            }
        }

        // Escalate to full rotational search on this tap only (500 ms budget).
        bool escalateFullAlign = false;
        if (needWideCoarse) {
            const double g1aCorrDeg =
                std::sqrt(g1aBestDyaw * g1aBestDyaw + g1aBestDpitch * g1aBestDpitch) * RAD2DEG;
            if (g1aCorrDeg > FAST_ESCALATE_CORR_DEG) escalateFullAlign = true;
        }
        if (bestRgbSSD > FAST_ESCALATE_SSD_THRESH && bestRgbSSD >= rgbBaselineStart * 0.92) {
            escalateFullAlign = true;
        }

        if (overlapGate > 0.f && escalateFullAlign && !frameRejected) {
            LOGI("Fast escalate full rot: equirect SSD %.1f (baseline %.1f)",
                 bestRgbSSD, rgbBaselineStart);
            const Quat alignBase = qCorrected;
            auto computeEscSSD = [&](double dyaw, double dpitch, double droll,
                                     int subsample) -> double {
                Quat qY = quatFromAxisAngle(0, 0, 1, dyaw);
                Quat qP = quatFromAxisAngle(1, 0, 0, dpitch);
                Quat qR = quatFromAxisAngle(0, 0, 1, droll);
                Quat qC = quatMul(qY, quatMul(qP, quatMul(qR, alignBase)));
                double ssd = 0.0;
                int count = 0;
                for (size_t i = 0; i < overlap.size(); i += (size_t)subsample) {
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

            double escBaseline = computeEscSSD(0, 0, 0, 2);
            double escBestDyaw = 0, escBestDpitch = 0, escBestDroll = 0;
            double escBestSSD = escBaseline;
            const int64_t escBudget = QUALITY_TIME_BUDGET_US;

            if (escBaseline > 2500.0 && elapsedUs(t0) < escBudget * 25 / 100) {
                constexpr double R = 18.0 * DEG2RAD;
                constexpr double S = 2.0 * DEG2RAD;
                for (double dp = -R; dp <= R; dp += S) {
                    for (double dy = -R; dy <= R; dy += S) {
                        double s = computeEscSSD(dy, dp, 0, 6);
                        if (s < escBestSSD) {
                            escBestSSD = s;
                            escBestDyaw = dy;
                            escBestDpitch = dp;
                        }
                    }
                }
            }
            if (elapsedUs(t0) < escBudget * 40 / 100) {
                constexpr double R = 9.0 * DEG2RAD;
                constexpr double S = 1.0 * DEG2RAD;
                for (double dp = -R; dp <= R; dp += S) {
                    for (double dy = -R; dy <= R; dy += S) {
                        double s = computeEscSSD(dy, dp, 0, 4);
                        if (s < escBestSSD) {
                            escBestSSD = s;
                            escBestDyaw = dy;
                            escBestDpitch = dp;
                        }
                    }
                }
            }
            if (elapsedUs(t0) < escBudget * 50 / 100) {
                constexpr double R = 5.0 * DEG2RAD;
                constexpr double S = 1.0 * DEG2RAD;
                for (double dr = -R; dr <= R; dr += S) {
                    double s = computeEscSSD(escBestDyaw, escBestDpitch, dr, 4);
                    if (s < escBestSSD) {
                        escBestSSD = s;
                        escBestDroll = dr;
                    }
                }
            }
            if (elapsedUs(t0) < escBudget * 65 / 100) {
                constexpr double R = 1.5 * DEG2RAD;
                constexpr double S = 0.25 * DEG2RAD;
                for (double dp = escBestDpitch - R; dp <= escBestDpitch + R; dp += S) {
                    for (double dy = escBestDyaw - R; dy <= escBestDyaw + R; dy += S) {
                        double s = computeEscSSD(dy, dp, escBestDroll, 2);
                        if (s < escBestSSD) {
                            escBestSSD = s;
                            escBestDyaw = dy;
                            escBestDpitch = dp;
                        }
                    }
                }
            }

            if (escBestSSD < escBaseline * 0.94) {
                Quat qY = quatFromAxisAngle(0, 0, 1, escBestDyaw);
                Quat qP = quatFromAxisAngle(1, 0, 0, escBestDpitch);
                Quat qR = quatFromAxisAngle(0, 0, 1, escBestDroll);
                qCorrected = quatMul(qY, quatMul(qP, quatMul(qR, alignBase)));
                bestRgbSSD = escBestSSD;
                LOGI("Fast escalate: yaw=%.2f pitch=%.2f roll=%.2f SSD %.1f->%.1f",
                     escBestDyaw * RAD2DEG, escBestDpitch * RAD2DEG, escBestDroll * RAD2DEG,
                     escBaseline, escBestSSD);
            }
            finalSSD = computeEquirectRotSSD(qCorrected, 0, 0, 2);
            usedFullRotAlign = true;
        }

        if (allowReject && finalSSD > REJECT_SSD_THRESH && sFrameIndex > 0) {
            const bool alignHelped = finalSSD < rgbBaselineStart * 0.95;
            if (alignHelped) {
                LOGI("Frame %d fast ACCEPT after align: SSD %.1f (baseline %.1f)",
                     sFrameIndex + 1, finalSSD, rgbBaselineStart);
            } else if (finalSSD <= REJECT_SSD_RELAXED && overlap.size() > 80) {
                LOGI("Frame %d fast fallback ACCEPT: SSD=%.1f", sFrameIndex + 1, finalSSD);
            } else {
                frameRejected = true;
                LOGW("Frame %d fast REJECTED: SSD=%.1f (thresh=%.1f)",
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

    if (allowReject && sFrameIndex > 1 && sLastOverlapCount < 20) {
        LOGW("Frame %d: very low overlap (%d/%d)",
             sFrameIndex, sLastOverlapCount, totalSamples);
        frameRejected = true;
        sLastRejected = 1;
    }

    // S12: robust overlap exposure match before splat (median luminance + per-channel).
    const bool useStoredExposure = forcedGainR > 1e-6f;
    if (useStoredExposure) {
        gainR = forcedGainR;
        gainG = forcedGainG > 1e-6f ? forcedGainG : forcedGainR;
        gainB = forcedGainB > 1e-6f ? forcedGainB : forcedGainR;
    } else if (!frameRejected && !overlap.empty() && sFrameIndex > 0 && !exportRebuild) {
        applyOverlapExposureNormalization(overlap, qCorrected, gainR, gainG, gainB);
    }

    // ── Phase 3: project and splat with per-pixel mismatch rejection ──
    int rejectedPixels = 0, totalOverlapPixels = 0;
    int minSplatY = eqH, maxSplatY = 0;

    float tileGains[TILE_GAIN_N * TILE_GAIN_N * 3];
    for (int i = 0; i < TILE_GAIN_N * TILE_GAIN_N * 3; i++) tileGains[i] = 1.f;
    if (!overlap.empty() && !exportRebuild) {
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
            const double gR = useStoredExposure ? gainR : 1.0;
            const double gG = useStoredExposure ? gainG : 1.0;
            const double gB = useStoredExposure ? gainB : 1.0;
            tNewR[t] += s.newR * gR;
            tNewG[t] += s.newG * gG;
            tNewB[t] += s.newB * gB;
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
        // Tentative splat when alignment is still dubious (pre-finish rebuild corrects pose).
        float splatWeightScale = 1.f;
        if (tentativeSplatFrame) {
            splatWeightScale = FAST_TENTATIVE_SPLAT_WEIGHT;
            LOGI("Frame %d tentative splat: SSD %.1f weight %.2f",
                 sFrameIndex, finalSSD, splatWeightScale);
        }

        // Export: minimal edge feather; fast-finish uses nearest-view pick (no overlap wash).
        const double featherFrac =
            exportRebuild ? (sNearestCenterSplat ? 0.03 : 0.05)
                          : (usedFullRotAlign ? 0.04 : 0.06);
        double featherX = frameW * featherFrac;
        double featherY = frameH * featherFrac;
        float mismatchSqRange = MISMATCH_HARD_SQ - MISMATCH_SOFT_SQ;
        const int splatStep = 1;
        const bool doMismatchCheck = usedFullRotAlign && !recencyComposite && sFrameIndex > 0 &&
            (elapsedUs(t0) < frameTimeBudgetUs * 85 / 100);

        const float gExpR = useStoredExposure ? static_cast<float>(gainR) : 1.f;
        const float gExpG = useStoredExposure ? static_cast<float>(gainG) : 1.f;
        const float gExpB = useStoredExposure ? static_cast<float>(gainB) : 1.f;

        const bool liveFastPath = !usedFullRotAlign && !exportRebuild;
        const int32_t* splatPixels = pixels;
        int splatW = frameW;
        int splatH = frameH;
        std::vector<int32_t> liveSplatPx;
        if (liveFastPath) {
            downscaleArgbNearest(pixels, frameW, frameH, liveSplatPx, splatW, splatH, LIVE_SPLAT_DIM);
            if (!liveSplatPx.empty()) splatPixels = liveSplatPx.data();
        }
        const int eqSplatStep =
            (liveFastPath && eqW > LIVE_EQ_SPLAT_STRIDE_THRESHOLD) ? 2 : 1;
        const double splatFeatherX = featherX * splatW / std::max(1, frameW);
        const double splatFeatherY = featherY * splatH / std::max(1, frameH);

        if (usedFullRotAlign && !recencyComposite) {
            // S14: inverse spherical mapping for full-quality single-frame stitch.
            // Export resplat accumulates all frames; replace is only for live fast capture.
            const bool replaceOverlap = false;
            splatFrameInverseSpherical(
                splatPixels, splatW, splatH, qCorrected, tanH, tanV, *warp,
                tileGains, replaceOverlap, splatFeatherX, splatFeatherY,
                gExpR, gExpG, gExpB, /*eqStep=*/1, splatWeightScale);
        } else {
            // Fast capture + refine rebuild: inverse spherical (matches rectilinear export).
            const bool replaceOverlap =
                !exportRebuild && !usedFullRotAlign && !recencyComposite && sFrameIndex > 0;
            splatFrameInverseSpherical(
                splatPixels, splatW, splatH, qCorrected, tanH, tanV, *warp,
                tileGains, replaceOverlap, splatFeatherX, splatFeatherY,
                gExpR, gExpG, gExpB, eqSplatStep, splatWeightScale);
            minSplatY = 0;
            maxSplatY = eqH - 1;
        }

        // ── Phase 4: weight capping in the splatted Y-band ──
        // Prevents early frames from dominating in high-overlap regions.
        // Capping preserves the average color (both numerator and denominator
        // scale equally) but ensures new frames always contribute ≥ ~20%.
        // Phase 4 weight capping: full-quality and refine rebuild paths.
        if (!sNearestCenterSplat && minSplatY <= maxSplatY) {
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

    outcome.accepted = !frameRejected;
    outcome.rejectedOverlap = false;
    outcome.overlapCount = static_cast<int>(overlap.size());
    outcome.totalSamples = totalSamples;
    outcome.overlapRatio = overlapRatio;
    outcome.finalSSD = finalSSD;
    outcome.corrected = qCorrected;
    sLastCorrectedQuat = qCorrected;
    outcome.expGainR = static_cast<float>(gainR);
    outcome.expGainG = static_cast<float>(gainG);
    outcome.expGainB = static_cast<float>(gainB);
    outcome.warp = *warp;
    {
        float wtx = 0.f, wty = 0.f;
        if (isUniformTranslationWarp(outcome.warp, wtx, wty)) {
            sLastWarpTx = wtx;
            sLastWarpTy = wty;
        } else {
            sLastWarpTx = outcome.warp.cells[0][0].tx;
            sLastWarpTy = outcome.warp.cells[0][0].ty;
        }
    }
    return outcome;
}

// Store an accepted frame (downscaled when above STORE_MAX_DIM) for background re-alignment.
static void appendOverlapBankForNewFrame(int newIdx);

static void fillGradientNearnessFromPixels(
    std::vector<float>& depth, const int32_t* px, int w, int h) {

    depth.assign((size_t)w * h, 0.5f);
    if (w < 3 || h < 3) return;
    for (int y = 1; y < h - 1; y++) {
        for (int x = 1; x < w - 1; x++) {
            auto lum = [&](int cx, int cy) -> float {
                int32_t p = px[(size_t)cy * w + cx];
                return 0.299f * static_cast<float>((p >> 16) & 0xff)
                     + 0.587f * static_cast<float>((p >> 8) & 0xff)
                     + 0.114f * static_cast<float>(p & 0xff);
            };
            float gx = lum(x + 1, y) - lum(x - 1, y);
            float gy = lum(x, y + 1) - lum(x, y - 1);
            float g = std::sqrt(gx * gx + gy * gy);
            depth[(size_t)y * w + x] = std::max(0.f, std::min(1.f, g / 48.f));
        }
    }
}

static void storeFrameForRefine(
    const int32_t* pixels, int frameW, int frameH,
    Quat imuQuat, Quat correctedQuat,
    const FrameWarp& warp,
    float expGainR, float expGainG, float expGainB,
    const float* depthIn = nullptr, int depthCount = 0) {
    // New overlap constraints → re-minimize pose graph for recent frames only.
    const int n = (int)sFrames.size();
    for (int i = 0; i < n; i++) {
        const int ageFromNewest = n - 1 - i;
        if (ageFromNewest < REALIGN_WINDOW) {
            sFrames[i].refined = false;
            sFrames[i].locked = false;
        }
    }
    int outW = frameW, outH = frameH;
    if (std::max(frameW, frameH) > STORE_MAX_DIM) {
        double s = (double)STORE_MAX_DIM / std::max(frameW, frameH);
        outW = std::max(1, (int)std::lround(frameW * s));
        outH = std::max(1, (int)std::lround(frameH * s));
    }
    StoredFrame f;
    f.w = outW;
    f.h = outH;
    f.refinedQuat = correctedQuat;
    f.imuQuat = imuQuat;
    f.fastCorrectedQuat = correctedQuat;
    f.expGainR = expGainR;
    f.expGainG = expGainG;
    f.expGainB = expGainB;
    f.locked = false;
    f.warp = warp;
    f.refined = false;
    f.px.resize((size_t)outW * outH);
    f.depth.assign((size_t)outW * outH, 0.5f);
    for (int y = 0; y < outH; y++) {
        int sy = (outH == frameH) ? y : clampI((int)((y + 0.5) * frameH / outH), 0, frameH - 1);
        for (int x = 0; x < outW; x++) {
            int sx = (outW == frameW) ? x : clampI((int)((x + 0.5) * frameW / outW), 0, frameW - 1);
            f.px[(size_t)y * outW + x] = pixels[(size_t)sy * frameW + sx];
            if (depthIn != nullptr && depthCount == frameW * frameH) {
                f.depth[(size_t)y * outW + x] = depthIn[(size_t)sy * frameW + sx];
            }
        }
    }
    if (depthIn == nullptr) {
        fillGradientNearnessFromPixels(f.depth, f.px.data(), outW, outH);
    }
    sFrames.push_back(std::move(f));
    appendOverlapBankForNewFrame((int)sFrames.size() - 1);
}

static void appendPairwiseOverlapToBank(
    int frameI, int frameJ, double tanH, double tanV) {

    if (frameI < 0 || frameJ < 0 ||
        frameI >= (int)sFrames.size() || frameJ >= (int)sFrames.size()) {
        return;
    }
    const StoredFrame& newer = sFrames[frameI];
    const StoredFrame& older = sFrames[frameJ];
    constexpr int step = 10;
    for (int fy = 0; fy < newer.h; fy += step) {
        for (int fx = 0; fx < newer.w; fx += step) {
            if (sOverlapBank.size() >= OVERLAP_BANK_MAX) return;
            double rx, ry, rz;
            pixelToRay(fx, fy, newer.w, newer.h, tanH, tanV, rx, ry, rz);
            double wx, wy, wz;
            quatRotate(newer.refinedQuat, rx, ry, rz, wx, wy, wz);
            float uOlder, vOlder;
            if (!worldRayToFramePixel(
                    wx, wy, wz, older.refinedQuat, older.w, older.h,
                    tanH, tanV, uOlder, vOlder)) {
                continue;
            }
            OverlapCorrespondence c{};
            c.frameI = frameI;
            c.frameJ = frameJ;
            c.wx = static_cast<float>(wx);
            c.wy = static_cast<float>(wy);
            c.wz = static_cast<float>(wz);
            c.uI = static_cast<float>(fx);
            c.vI = static_cast<float>(fy);
            c.uJ = uOlder;
            c.vJ = vOlder;
            sOverlapBank.push_back(c);
        }
    }
}

static void appendOverlapBankForNewFrame(int newIdx) {
    if (newIdx <= 0) return;
    double tanH, tanV;
    sessionTanHalfFov(tanH, tanV);
    const int firstNeighbor = std::max(0, newIdx - 3);
    for (int j = firstNeighbor; j < newIdx; j++) {
        appendPairwiseOverlapToBank(newIdx, j, tanH, tanV);
    }
}

/** S36: rebuild overlap bank after global pose moves refinedQuat. */
static void rebuildOverlapBankLocked() {
    sOverlapBank.clear();
    double tanH, tanV;
    sessionTanHalfFov(tanH, tanV);
    const int n = (int)sFrames.size();
    for (int i = 1; i < n; i++) {
        const int firstNeighbor = std::max(0, i - 3);
        for (int j = firstNeighbor; j < i; j++) {
            appendPairwiseOverlapToBank(i, j, tanH, tanV);
        }
    }
    LOGI("Overlap bank rebuilt: %zu correspondences (%d frames)", sOverlapBank.size(), n);
}

/** Photometric SSD over the overlap bank at candidate intrinsics. */
static double evalOverlapBankSSD(
    double focalScale, double k1Candidate, bool includePrior = true) {

    if (sOverlapBank.empty() || sFrames.size() < 2) return 1e18;
    double tanH = std::tan(storedFovH * 0.5 * DEG2RAD) * focalScale;
    double tanV = std::tan(storedFovV * 0.5 * DEG2RAD) * focalScale;
    const float savedK1 = distK1;
    distK1 = static_cast<float>(k1Candidate);
    double ssd = 0.0;
    int count = 0;
    for (const auto& c : sOverlapBank) {
        if (c.frameI < 0 || c.frameJ < 0 ||
            c.frameI >= (int)sFrames.size() || c.frameJ >= (int)sFrames.size()) {
            continue;
        }
        const StoredFrame& a = sFrames[c.frameI];
        const StoredFrame& b = sFrames[c.frameJ];
        float uB, vB;
        if (!worldRayToFramePixel(
                c.wx, c.wy, c.wz, b.refinedQuat, b.w, b.h, tanH, tanV, uB, vB)) {
            continue;
        }
        float rA, gA, bA, rB, gB, bB;
        samplePixelBilinear(a.px.data(), a.w, a.h, c.uI, c.vI, rA, gA, bA);
        samplePixelBilinear(b.px.data(), b.w, b.h, uB, vB, rB, gB, bB);
        double dr = rA - rB, dg = gA - gB, db = bA - bB;
        ssd += dr * dr + dg * dg + db * db;
        count++;
    }
    distK1 = savedK1;
    if (count < 12) return 1e18;
    double mean = ssd / count;
    if (includePrior) {
        double df = focalScale - 1.0;
        mean += FOCAL_PRIOR_LAMBDA * df * df;
        double dk = k1Candidate - distK1Prior;
        mean += K1_PRIOR_LAMBDA * dk * dk;
    }
    return mean;
}

/** 1D focal-scale + k1 search over the overlap bank (I1, I2). */
static bool refineIntrinsicsFromOverlapBankLocked() {
    if (sOverlapBank.size() < 20 || sFrames.size() < 2) return false;

    bool changed = false;
    double baseline = evalOverlapBankSSD(sFocalScale, distK1, /*includePrior=*/false);

    // I1: focal scale
    double bestScale = sFocalScale;
    double bestE = evalOverlapBankSSD(bestScale, distK1, /*includePrior=*/false);
    for (int k = -15; k <= 15; k++) {
        double s = 1.0 + k * 0.004;
        if (s < FOCAL_SCALE_MIN || s > FOCAL_SCALE_MAX) continue;
        double e = evalOverlapBankSSD(s, distK1, /*includePrior=*/false);
        if (e < bestE) {
            bestE = e;
            bestScale = s;
        }
    }
    if (std::abs(bestScale - sFocalScale) > 1e-4) {
        double newRaw = evalOverlapBankSSD(bestScale, distK1, /*includePrior=*/false);
        if (newRaw < baseline * 0.998) {
            float oldScale = sFocalScale;
            sFocalScale = static_cast<float>(bestScale);
            baseline = newRaw;
            changed = true;
            LOGI("Intrinsic refine: focalScale %.4f -> %.4f (ray SSD -> %.1f, bank=%zu)",
                 oldScale, sFocalScale, baseline, sOverlapBank.size());
        }
    }

    // I2: k1 (needs multi-frame overlap for observability)
    if (sFrames.size() >= 3) {
        double bestK1 = distK1;
        bestE = evalOverlapBankSSD(sFocalScale, bestK1, /*includePrior=*/false);
        const double k1Base = distK1;
        for (int k = -10; k <= 10; k++) {
            double candidate = distK1 + k * 0.005;
            if (candidate < distK1Prior - K1_SEARCH_DELTA ||
                candidate > distK1Prior + K1_SEARCH_DELTA) {
                continue;
            }
            double e = evalOverlapBankSSD(sFocalScale, candidate, /*includePrior=*/false);
            if (e < bestE) {
                bestE = e;
                bestK1 = candidate;
            }
        }
        if (std::abs(bestK1 - k1Base) > 1e-5) {
            double newRaw = evalOverlapBankSSD(sFocalScale, bestK1, /*includePrior=*/false);
            if (newRaw < baseline * 0.998) {
                float oldK1 = distK1;
                distK1 = static_cast<float>(bestK1);
                changed = true;
                LOGI("Intrinsic refine: k1 %.4f -> %.4f (ray SSD %.1f -> %.1f)",
                     oldK1, distK1, baseline, newRaw);
            }
        }
    }
    return changed;
}

// ── Global pairwise pose refine (ray-space, frame-to-frame, not vs muddy equirect) ──

/** Photometric SSD between two stored frames in shared spherical directions (6-DoF). */
static double pairwiseFrameRaySSD6D(
    const StoredFrame& src, const Quat& qSrc, float srcTx, float srcTy, float srcTz,
    const StoredFrame& ref, const Quat& qRef, float refTx, float refTy, float refTz,
    double tanH, double tanV,
    int step = 10) {

    const bool hasDepth = (int)src.depth.size() == src.w * src.h;
    double ssd = 0.0;
    int count = 0;
    for (int fy = 0; fy < src.h; fy += step) {
        for (int fx = 0; fx < src.w; fx += step) {
            double rx, ry, rz;
            pixelToRay(fx, fy, src.w, src.h, tanH, tanV, rx, ry, rz);
            double wx, wy, wz;
            quatRotate(qSrc, rx, ry, rz, wx, wy, wz);
            float refFx, refFy;
            if (!worldRayToFramePixel(wx, wy, wz, qRef, ref.w, ref.h, tanH, tanV, refFx, refFy)) {
                continue;
            }
            float near = 0.5f;
            if (hasDepth) near = src.depth[(size_t)fy * src.w + fx];
            applyParallaxPixelShift(
                refFx, refFy, qRef, refTx, refTy, refTz,
                qSrc, srcTx, srcTy, srcTz, ref.w, tanH, near);
            if (refFx < 0.f || refFx > ref.w - 1 || refFy < 0.f || refFy > ref.h - 1) continue;

            int32_t pSrc = src.px[(size_t)fy * src.w + fx];
            float sr = static_cast<float>((pSrc >> 16) & 0xff);
            float sg = static_cast<float>((pSrc >> 8) & 0xff);
            float sb = static_cast<float>(pSrc & 0xff);
            float rr, rg, rb;
            samplePixelBilinear(ref.px.data(), ref.w, ref.h, refFx, refFy, rr, rg, rb);
            double dr = sr - rr, dg = sg - rg, db = sb - rb;
            ssd += dr * dr + dg * dg + db * db;
            count++;
        }
    }
    if (count < 12) return 1e18;
    return ssd / count;
}

static double pairwiseFrameRaySSD(
    const StoredFrame& src, const Quat& qSrc,
    const StoredFrame& ref, const Quat& qRef,
    double tanH, double tanV,
    int step = 10) {

    return pairwiseFrameRaySSD6D(
        src, qSrc, src.poseTx, src.poseTy, src.poseTz,
        ref, qRef, ref.poseTx, ref.poseTy, ref.poseTz,
        tanH, tanV, step);
}

/** Live capture: current frame pixels vs immediate predecessor (ray-space, not equirect). */
static double liveFramePairwiseRotSSD(
    const int32_t* pixels, int w, int h,
    const Quat& qCand,
    const StoredFrame& prev, const Quat& qPrev,
    double tanH, double tanV,
    int step = 8) {

    double ssd = 0.0;
    int count = 0;
    for (int fy = 0; fy < h; fy += step) {
        for (int fx = 0; fx < w; fx += step) {
            double rx, ry, rz;
            pixelToRay(fx, fy, w, h, tanH, tanV, rx, ry, rz);
            double wx, wy, wz;
            quatRotate(qCand, rx, ry, rz, wx, wy, wz);
            float refFx, refFy;
            if (!worldRayToFramePixel(wx, wy, wz, qPrev, prev.w, prev.h, tanH, tanV, refFx, refFy)) {
                continue;
            }
            applyParallaxPixelShift(
                refFx, refFy, qPrev, prev.poseTx, prev.poseTy, prev.poseTz,
                qCand, 0.f, 0.f, 0.f, w, tanH, 0.5f);
            if (refFx < 0.f || refFx > prev.w - 1 || refFy < 0.f || refFy > prev.h - 1) continue;

            int32_t pSrc = pixels[(size_t)fy * w + fx];
            float sr = static_cast<float>((pSrc >> 16) & 0xff);
            float sg = static_cast<float>((pSrc >> 8) & 0xff);
            float sb = static_cast<float>(pSrc & 0xff);
            float rr, rg, rb;
            samplePixelBilinear(prev.px.data(), prev.w, prev.h, refFx, refFy, rr, rg, rb);
            double dr = sr - rr, dg = sg - rg, db = sb - rb;
            ssd += dr * dr + dg * dg + db * db;
            count++;
        }
    }
    if (count < 12) return 1e18;
    return ssd / count;
}

static double quatAngularDistSq(const Quat& a, const Quat& b) {
    double dot = std::abs(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w);
    dot = std::min(1.0, std::max(-1.0, dot));
    double ang = 2.0 * std::acos(dot);
    return ang * ang;
}

/** Squared angular distance in radians between two orientations. */
static double quatAngularDistRad(const Quat& a, const Quat& b) {
    return std::sqrt(quatAngularDistSq(a, b));
}

/**
 * Global graph IMU prior: when fast correction already moved off raw IMU (bad seed),
 * do not penalize photometric solutions that depart from the erroneous sensor prior.
 */
static double globalImuPriorLambda(const StoredFrame& f, double photometricSSD) {
    const double imuFastDeg = quatAngularDistRad(f.imuQuat, f.fastCorrectedQuat) * RAD2DEG;
    if (imuFastDeg > 8.0 || photometricSSD > 3000.0) return 0.0;
    double lambda = adaptiveImuPriorLambda(photometricSSD);
    if (imuFastDeg > 2.0) {
        const double fade = std::max(0.0, 1.0 - (imuFastDeg - 2.0) / 5.0);
        lambda *= fade;
    }
    return lambda;
}

/** Widen rotation search when seeds disagree or overlap SSD is high (IMU recovery). */
static double globalPoseSearchRadiusRad(const StoredFrame& f, double photometricSSD) {
    const double imuFastDeg = quatAngularDistRad(f.imuQuat, f.fastCorrectedQuat) * RAD2DEG;
    if (imuFastDeg > 10.0 || photometricSSD > 3500.0) return 18.0 * DEG2RAD;
    if (imuFastDeg > 5.0 || photometricSSD > 2000.0) return 14.0 * DEG2RAD;
    return 5.0 * DEG2RAD;
}

static double neighborPairwiseSSD(
    int frameIdx, const Quat& qCandidate,
    double tanH, double tanV) {

    const StoredFrame& src = sFrames[frameIdx];
    const int n = (int)sFrames.size();
    double total = 0.0;
    int pairs = 0;
    // S27: wider neighbor graph (i-3..i-1 and i+1).
    const int firstNeighbor = std::max(0, frameIdx - 3);
    for (int j = firstNeighbor; j < frameIdx; j++) {
        double sFwd = pairwiseFrameRaySSD(
            src, qCandidate, sFrames[j], sFrames[j].refinedQuat, tanH, tanV);
        double sRev = pairwiseFrameRaySSD(
            sFrames[j], sFrames[j].refinedQuat, src, qCandidate, tanH, tanV);
        if (sFwd < 1e17 && sRev < 1e17) {
            total += 0.5 * (sFwd + sRev);
            pairs++;
        }
    }
    if (frameIdx + 1 < n) {
        const StoredFrame& next = sFrames[frameIdx + 1];
        double sFwd = pairwiseFrameRaySSD(
            src, qCandidate, next, next.refinedQuat, tanH, tanV);
        double sRev = pairwiseFrameRaySSD(
            next, next.refinedQuat, src, qCandidate, tanH, tanV);
        if (sFwd < 1e17 && sRev < 1e17) {
            total += 0.5 * (sFwd + sRev);
            pairs++;
        }
    }
    // S27: loop-closure pair (last frame vs anchor) for wrap-around sessions.
    constexpr double kLoopClosureWeight = 3.0;
    if (n >= 4 && frameIdx == n - 1) {
        const StoredFrame& anchor = sFrames[0];
        double sFwd = pairwiseFrameRaySSD(
            src, qCandidate, anchor, anchor.refinedQuat, tanH, tanV);
        double sRev = pairwiseFrameRaySSD(
            anchor, anchor.refinedQuat, src, qCandidate, tanH, tanV);
        if (sFwd < 1e17 && sRev < 1e17) {
            total += kLoopClosureWeight * 0.5 * (sFwd + sRev);
            pairs += static_cast<int>(kLoopClosureWeight);
        }
    }
    return pairs > 0 ? total / pairs : 1e18;
}

/** G1/G2: sparse pose graph SSD with optional 6-DoF candidate for [frameIdx]. */
static double sparseGraphPairwiseSSD6D(
    int frameIdx, const Quat& qCandidate,
    float candTx, float candTy, float candTz,
    double tanH, double tanV) {

    const StoredFrame& srcFrame = sFrames[frameIdx];
    const int n = (int)sFrames.size();
    std::set<int> neighbors;
    for (const auto& c : sOverlapBank) {
        if (c.frameI == frameIdx && c.frameJ >= 0 && c.frameJ < n && c.frameJ != frameIdx) {
            neighbors.insert(c.frameJ);
        }
        if (c.frameJ == frameIdx && c.frameI >= 0 && c.frameI < n && c.frameI != frameIdx) {
            neighbors.insert(c.frameI);
        }
    }

    auto pairCost = [&](int j) -> double {
        const StoredFrame& other = sFrames[j];
        double sFwd = pairwiseFrameRaySSD6D(
            srcFrame, qCandidate, candTx, candTy, candTz,
            other, other.refinedQuat, other.poseTx, other.poseTy, other.poseTz,
            tanH, tanV);
        double sRev = pairwiseFrameRaySSD6D(
            other, other.refinedQuat, other.poseTx, other.poseTy, other.poseTz,
            srcFrame, qCandidate, candTx, candTy, candTz,
            tanH, tanV);
        if (sFwd < 1e17 && sRev < 1e17) return 0.5 * (sFwd + sRev);
        return 1e18;
    };

    if (neighbors.empty()) {
        return neighborPairwiseSSD(frameIdx, qCandidate, tanH, tanV);
    }

    double total = 0.0;
    int pairs = 0;
    for (int j : neighbors) {
        double s = pairCost(j);
        if (s < 1e17) {
            total += s;
            pairs++;
        }
    }
    if (n >= 4 && frameIdx == n - 1) {
        const StoredFrame& anchor = sFrames[0];
        double sFwd = pairwiseFrameRaySSD6D(
            srcFrame, qCandidate, candTx, candTy, candTz,
            anchor, anchor.refinedQuat, anchor.poseTx, anchor.poseTy, anchor.poseTz,
            tanH, tanV);
        double sRev = pairwiseFrameRaySSD6D(
            anchor, anchor.refinedQuat, anchor.poseTx, anchor.poseTy, anchor.poseTz,
            srcFrame, qCandidate, candTx, candTy, candTz,
            tanH, tanV);
        if (sFwd < 1e17 && sRev < 1e17) {
            total += 0.5 * (sFwd + sRev);
            pairs++;
        }
    }
    return pairs > 0 ? total / pairs : 1e18;
}

static double sparseGraphPairwiseSSD(
    int frameIdx, const Quat& qCandidate,
    double tanH, double tanV) {

    const StoredFrame& f = sFrames[frameIdx];
    return sparseGraphPairwiseSSD6D(
        frameIdx, qCandidate, f.poseTx, f.poseTy, f.poseTz, tanH, tanV);
}

/** Max IMU vs fast-corrected gap across stored frames (degrees). */
static double sessionMaxImuFastGapDeg() {
    double maxGap = 0.0;
    for (const auto& f : sFrames) {
        maxGap = std::max(
            maxGap, quatAngularDistRad(f.imuQuat, f.fastCorrectedQuat) * RAD2DEG);
    }
    return maxGap;
}

/** Pairwise ray SSD vs immediate predecessor (rotation only). */
static double chainedNeighborRaySSD(
    int frameIdx, const Quat& qCand, double tanH, double tanV) {

    if (frameIdx <= 0) return 1e18;
    const StoredFrame& src = sFrames[frameIdx];
    const StoredFrame& prev = sFrames[frameIdx - 1];
    double sFwd = pairwiseFrameRaySSD(
        src, qCand, prev, prev.refinedQuat, tanH, tanV);
    double sRev = pairwiseFrameRaySSD(
        prev, prev.refinedQuat, src, qCand, tanH, tanV);
    if (sFwd < 1e17 && sRev < 1e17) return 0.5 * (sFwd + sRev);
    return 1e18;
}

/** Pairwise ray SSD for loop closure (last frame vs anchor). */
static double loopClosureRaySSD(
    int lastIdx, const Quat& qCand, double tanH, double tanV) {

    if (lastIdx <= 0) return 1e18;
    const StoredFrame& src = sFrames[lastIdx];
    const StoredFrame& anchor = sFrames[0];
    double sFwd = pairwiseFrameRaySSD(
        src, qCand, anchor, anchor.refinedQuat, tanH, tanV);
    double sRev = pairwiseFrameRaySSD(
        anchor, anchor.refinedQuat, src, qCand, tanH, tanV);
    if (sFwd < 1e17 && sRev < 1e17) return 0.5 * (sFwd + sRev);
    return 1e18;
}

/** Grid search yaw/pitch/roll minimizing pairwise ray SSD (not equirect). */
static bool searchPairwiseRotation(
    const std::function<double(const Quat&)>& evalSSD,
    const Quat& qBase,
    double searchRad,
    double coarseStepRad,
    Quat& qOut,
    double& bestSSD,
    double acceptRatio = 0.96) {

    const double baseSSD = evalSSD(qBase);
    if (baseSSD > 1e17) return false;
    bestSSD = baseSSD;
    double bestDy = 0.0, bestDp = 0.0, bestDr = 0.0;

    auto searchAtStep = [&](double stepRad) {
        for (double dp = -searchRad; dp <= searchRad; dp += stepRad) {
            for (double dy = -searchRad; dy <= searchRad; dy += stepRad) {
                for (double dr = -searchRad; dr <= searchRad; dr += stepRad) {
                    Quat qY = quatFromAxisAngle(0, 0, 1, dy);
                    Quat qP = quatFromAxisAngle(1, 0, 0, dp);
                    Quat qR = quatFromAxisAngle(0, 0, 1, dr);
                    Quat qCand = quatMul(qY, quatMul(qP, quatMul(qR, qBase)));
                    double s = evalSSD(qCand);
                    if (s < bestSSD) {
                        bestSSD = s;
                        bestDy = dy;
                        bestDp = dp;
                        bestDr = dr;
                    }
                }
            }
        }
    };

    searchAtStep(coarseStepRad);
    const double fineStep = std::max(coarseStepRad * 0.5, 0.5 * DEG2RAD);
    const double fineRad = std::min(searchRad, coarseStepRad * 2.0);
    for (double dp = bestDp - fineRad; dp <= bestDp + fineRad; dp += fineStep) {
        for (double dy = bestDy - fineRad; dy <= bestDy + fineRad; dy += fineStep) {
            for (double dr = bestDr - fineRad; dr <= bestDr + fineRad; dr += fineStep) {
                Quat qY = quatFromAxisAngle(0, 0, 1, dy);
                Quat qP = quatFromAxisAngle(1, 0, 0, dp);
                Quat qR = quatFromAxisAngle(0, 0, 1, dr);
                Quat qCand = quatMul(qY, quatMul(qP, quatMul(qR, qBase)));
                double s = evalSSD(qCand);
                if (s < bestSSD) {
                    bestSSD = s;
                    bestDy = dy;
                    bestDp = dp;
                    bestDr = dr;
                }
            }
        }
    }

    if (bestSSD < baseSSD * acceptRatio) {
        Quat qY = quatFromAxisAngle(0, 0, 1, bestDy);
        Quat qP = quatFromAxisAngle(1, 0, 0, bestDp);
        Quat qR = quatFromAxisAngle(0, 0, 1, bestDr);
        qOut = quatMul(qY, quatMul(qP, quatMul(qR, qBase)));
        return true;
    }
    return false;
}

/**
 * Seed export global graph from pairwise ray chain (i vs i-1), not equirect accumulator.
 */
static int seedPosesPairwiseChainLocked() {
    const int n = (int)sFrames.size();
    if (n < 2) return 0;

    double tanH, tanV;
    sessionTanHalfFov(tanH, tanV);
    const double maxImuFastDeg = sessionMaxImuFastGapDeg();
    const double searchRad =
        maxImuFastDeg > 12.0 ? (14.0 * DEG2RAD)
        : (maxImuFastDeg > 6.0 ? (10.0 * DEG2RAD) : (6.0 * DEG2RAD));
    const double coarseStep =
        searchRad > 10.0 * DEG2RAD ? (3.0 * DEG2RAD) : (2.0 * DEG2RAD);

    int adjusted = 0;
    for (int i = 1; i < n; i++) {
        StoredFrame& f = sFrames[i];
        Quat qBase = f.refinedQuat;
        double baseSSD = chainedNeighborRaySSD(i, qBase, tanH, tanV);
        if (baseSSD > 1e17) continue;

        double bestSSD = baseSSD;
        double bestDy = 0.0, bestDp = 0.0;
        for (double dp = -searchRad; dp <= searchRad; dp += coarseStep) {
            for (double dy = -searchRad; dy <= searchRad; dy += coarseStep) {
                Quat qY = quatFromAxisAngle(0, 0, 1, dy);
                Quat qP = quatFromAxisAngle(1, 0, 0, dp);
                Quat qCand = quatMul(qY, quatMul(qP, qBase));
                double s = chainedNeighborRaySSD(i, qCand, tanH, tanV);
                if (s < bestSSD) {
                    bestSSD = s;
                    bestDy = dy;
                    bestDp = dp;
                }
            }
        }
        const double fineStep = 0.75 * DEG2RAD;
        const double fineRad = coarseStep;
        for (double dp = bestDp - fineRad; dp <= bestDp + fineRad; dp += fineStep) {
            for (double dy = bestDy - fineRad; dy <= bestDy + fineRad; dy += fineStep) {
                Quat qY = quatFromAxisAngle(0, 0, 1, dy);
                Quat qP = quatFromAxisAngle(1, 0, 0, dp);
                Quat qCand = quatMul(qY, quatMul(qP, qBase));
                double s = chainedNeighborRaySSD(i, qCand, tanH, tanV);
                if (s < bestSSD) {
                    bestSSD = s;
                    bestDy = dy;
                    bestDp = dp;
                }
            }
        }
        if (bestSSD < baseSSD * 0.96) {
            Quat qY = quatFromAxisAngle(0, 0, 1, bestDy);
            Quat qP = quatFromAxisAngle(1, 0, 0, bestDp);
            f.refinedQuat = quatMul(qY, quatMul(qP, qBase));
            f.fastCorrectedQuat = f.refinedQuat;
            adjusted++;
        }
    }

    if (n >= 4) {
        const int last = n - 1;
        StoredFrame& f = sFrames[last];
        Quat qBase = f.refinedQuat;
        double baseSSD = loopClosureRaySSD(last, qBase, tanH, tanV);
        if (baseSSD < 1e17) {
            double bestSSD = baseSSD;
            double bestDy = 0.0, bestDp = 0.0;
            const double loopRad = std::min(searchRad, 10.0 * DEG2RAD);
            for (double dp = -loopRad; dp <= loopRad; dp += coarseStep) {
                for (double dy = -loopRad; dy <= loopRad; dy += coarseStep) {
                    Quat qY = quatFromAxisAngle(0, 0, 1, dy);
                    Quat qP = quatFromAxisAngle(1, 0, 0, dp);
                    Quat qCand = quatMul(qY, quatMul(qP, qBase));
                    double s = loopClosureRaySSD(last, qCand, tanH, tanV);
                    if (s < bestSSD) {
                        bestSSD = s;
                        bestDy = dy;
                        bestDp = dp;
                    }
                }
            }
            if (bestSSD < baseSSD * 0.94) {
                Quat qY = quatFromAxisAngle(0, 0, 1, bestDy);
                Quat qP = quatFromAxisAngle(1, 0, 0, bestDp);
                f.refinedQuat = quatMul(qY, quatMul(qP, qBase));
                f.fastCorrectedQuat = f.refinedQuat;
                adjusted++;
            }
        }
    }

    LOGI("Pairwise chain seed: adjusted %d frames (search %.1f deg, imuGap %.1f deg)",
         adjusted, searchRad * RAD2DEG, maxImuFastDeg);
    if (adjusted > 0) rebuildOverlapBankLocked();
    return adjusted;
}

/** Align each frame to its immediate predecessor (robust for desk / chained capture). */
static void refineChainedPosesLocked() {
    const int n = (int)sFrames.size();
    if (n < 2) return;

    double tanH, tanV;
    sessionTanHalfFov(tanH, tanV);
    int adjusted = 0;

    auto neighborSSD = [&](int frameIdx, const Quat& qCand) -> double {
        return chainedNeighborRaySSD(frameIdx, qCand, tanH, tanV);
    };

    for (int i = 1; i < n; i++) {
        StoredFrame& f = sFrames[i];
        Quat qBase = f.refinedQuat;
        double baseSSD = neighborSSD(i, qBase);
        if (baseSSD > 1e17) continue;

        double bestSSD = baseSSD;
        double bestDy = 0.0, bestDp = 0.0;
        const double imuGapRad = quatAngularDistRad(f.imuQuat, f.fastCorrectedQuat);
        const double searchRad =
            (sFastFinishRefine && imuGapRad > 8.0 * DEG2RAD) ? (10.0 * DEG2RAD)
            : (imuGapRad > 6.0 * DEG2RAD) ? (7.0 * DEG2RAD) : (5.0 * DEG2RAD);
        const double stepRad = searchRad > 6.0 * DEG2RAD ? 0.8 * DEG2RAD : 0.6 * DEG2RAD;
        for (double dp = -searchRad; dp <= searchRad; dp += stepRad) {
            for (double dy = -searchRad; dy <= searchRad; dy += stepRad) {
                Quat qY = quatFromAxisAngle(0, 0, 1, dy);
                Quat qP = quatFromAxisAngle(1, 0, 0, dp);
                Quat qCand = quatMul(qY, quatMul(qP, qBase));
                double s = neighborSSD(i, qCand);
                if (s < bestSSD) {
                    bestSSD = s;
                    bestDy = dy;
                    bestDp = dp;
                }
            }
        }
        if (bestSSD < baseSSD * 0.94) {
            Quat qY = quatFromAxisAngle(0, 0, 1, bestDy);
            Quat qP = quatFromAxisAngle(1, 0, 0, bestDp);
            Quat qNew = quatMul(qY, quatMul(qP, qBase));
            // Reject large hops unless SSD improves substantially (desk texture false matches).
            const double imuFastDeg =
                quatAngularDistRad(f.imuQuat, f.fastCorrectedQuat) * RAD2DEG;
            double maxStepRad = 4.0 * DEG2RAD;
            if (imuFastDeg > 4.0) maxStepRad = 8.0 * DEG2RAD;
            const double stepRad2 = std::sqrt(bestDy * bestDy + bestDp * bestDp);
            const double ssdGain = (baseSSD - bestSSD) / std::max(baseSSD, 1.0);
            if (stepRad2 > maxStepRad && ssdGain < 0.12) continue;
            f.fastCorrectedQuat = qNew;
            f.refinedQuat = f.fastCorrectedQuat;
            adjusted++;
        }
    }
    LOGI("Chained pose refine: adjusted %d/%d frames", adjusted, n);
    if (adjusted > 0) rebuildOverlapBankLocked();
}

/** G1: one damped Gauss-Newton step on yaw/pitch/roll after grid search. */
static void refineFramePoseGaussNewton(int frameIdx, double tanH, double tanV) {
    if (frameIdx <= 0 || frameIdx >= (int)sFrames.size()) return;
    StoredFrame& f = sFrames[frameIdx];
    if (f.locked) return;

    Quat qBase = f.refinedQuat;
    auto evalDelta = [&](double dyaw, double dpitch, double droll) -> double {
        Quat qY = quatFromAxisAngle(0, 0, 1, dyaw);
        Quat qP = quatFromAxisAngle(1, 0, 0, dpitch);
        Quat qR = quatFromAxisAngle(0, 0, 1, droll);
        Quat qCand = quatMul(qY, quatMul(qP, quatMul(qR, qBase)));
        double photometric = sparseGraphPairwiseSSD(frameIdx, qCand, tanH, tanV);
        const int featInliers = frameFeatureInlierSum(frameIdx);
        double photoScale = 1.0;
        if (featInliers >= 80) photoScale = 0.35;
        if (featInliers >= 140) photoScale = 0.15;
        photometric *= photoScale;
        const double imuLambda = globalImuPriorLambda(f, photometric);
        double imuPenalty = imuLambda * quatAngularDistSq(qCand, f.imuQuat);
        double featurePenalty = featureGraphPenaltyForFrame(frameIdx, qCand);
        return photometric + imuPenalty + featurePenalty;
    };

    constexpr double eps = 0.08 * DEG2RAD;
    double base = evalDelta(0, 0, 0);
    double gy = (evalDelta(eps, 0, 0) - evalDelta(-eps, 0, 0)) / (2.0 * eps);
    double gp = (evalDelta(0, eps, 0) - evalDelta(0, -eps, 0)) / (2.0 * eps);
    double gr = (evalDelta(0, 0, eps) - evalDelta(0, 0, -eps)) / (2.0 * eps);

    constexpr double damp = 0.35;
    constexpr double maxStep = 1.5 * DEG2RAD;
    double dy = std::max(-maxStep, std::min(maxStep, -damp * gy));
    double dp = std::max(-maxStep, std::min(maxStep, -damp * gp));
    double dr = std::max(-maxStep, std::min(maxStep, -damp * gr));

    if (std::abs(dy) < 1e-6 && std::abs(dp) < 1e-6 && std::abs(dr) < 1e-6) return;

    double trial = evalDelta(dy, dp, dr);
    if (trial < base * 0.995) {
        Quat qY = quatFromAxisAngle(0, 0, 1, dy);
        Quat qP = quatFromAxisAngle(1, 0, 0, dp);
        Quat qR = quatFromAxisAngle(0, 0, 1, dr);
        f.refinedQuat = quatMul(qY, quatMul(qP, quatMul(qR, qBase)));
        LOGI("GN pose frame %d: yaw=%.2f pitch=%.2f roll=%.2f (SSD %.1f->%.1f)",
             frameIdx, dy * RAD2DEG, dp * RAD2DEG, dr * RAD2DEG, base, trial);
    }
}

/** G2: grid search on camera-frame translation after rotation refine. */
static void refineFrameTranslationGlobal(int frameIdx, double tanH, double tanV) {
    if (frameIdx <= 0 || frameIdx >= (int)sFrames.size()) return;
    StoredFrame& f = sFrames[frameIdx];
    if (f.locked) return;

    auto evalT = [&](float tx, float ty, float tz) -> double {
        double photometric = sparseGraphPairwiseSSD6D(
            frameIdx, f.refinedQuat, tx, ty, tz, tanH, tanV);
        double transPenalty = TRANS_PRIOR_LAMBDA *
            (tx * tx + ty * ty + tz * tz);
        return photometric + transPenalty;
    };

    float bestTx = f.poseTx, bestTy = f.poseTy, bestTz = f.poseTz;
    double bestCost = evalT(bestTx, bestTy, bestTz);

    constexpr float step = 0.02f;
    for (float ty = -POSE_T_MAX; ty <= POSE_T_MAX; ty += step) {
        for (float tx = -POSE_T_MAX; tx <= POSE_T_MAX; tx += step) {
            double c = evalT(tx, ty, f.poseTz);
            if (c < bestCost) {
                bestCost = c;
                bestTx = tx;
                bestTy = ty;
            }
        }
    }

    double baseline = evalT(f.poseTx, f.poseTy, f.poseTz);
    if (bestCost < baseline * 0.98) {
        f.poseTx = bestTx;
        f.poseTy = bestTy;
        f.poseTz = bestTz;
        LOGI("Global trans frame %d: tx=%.3f ty=%.3f tz=%.3f (cost %.1f->%.1f)",
             frameIdx, bestTx, bestTy, bestTz, baseline, bestCost);
    }
}

/** G2: damped GN step on translation (tx, ty, tz). */
static void refineFrameTranslationGaussNewton(int frameIdx, double tanH, double tanV) {
    if (frameIdx <= 0 || frameIdx >= (int)sFrames.size()) return;
    StoredFrame& f = sFrames[frameIdx];
    if (f.locked) return;

    auto evalT = [&](float tx, float ty, float tz) -> double {
        double photometric = sparseGraphPairwiseSSD6D(
            frameIdx, f.refinedQuat, tx, ty, tz, tanH, tanV);
        double transPenalty = TRANS_PRIOR_LAMBDA *
            (tx * tx + ty * ty + tz * tz);
        return photometric + transPenalty;
    };

    constexpr float eps = 0.006f;
    float tx0 = f.poseTx, ty0 = f.poseTy, tz0 = f.poseTz;
    double base = evalT(tx0, ty0, tz0);
    double gx = (evalT(tx0 + eps, ty0, tz0) - evalT(tx0 - eps, ty0, tz0)) / (2.0 * eps);
    double gy = (evalT(tx0, ty0 + eps, tz0) - evalT(tx0, ty0 - eps, tz0)) / (2.0 * eps);
    double gz = (evalT(tx0, ty0, tz0 + eps) - evalT(tx0, ty0, tz0 - eps)) / (2.0 * eps);

    constexpr float damp = 0.4f;
    constexpr float maxStep = POSE_T_MAX;
    float dtx = static_cast<float>(-damp * gx);
    float dty = static_cast<float>(-damp * gy);
    float dtz = static_cast<float>(-damp * gz);
    dtx = std::max(-maxStep, std::min(maxStep, dtx));
    dty = std::max(-maxStep, std::min(maxStep, dty));
    dtz = std::max(-maxStep, std::min(maxStep, dtz));

    if (std::abs(dtx) < 1e-5f && std::abs(dty) < 1e-5f && std::abs(dtz) < 1e-5f) return;

    float ntx = std::max(-maxStep, std::min(maxStep, tx0 + dtx));
    float nty = std::max(-maxStep, std::min(maxStep, ty0 + dty));
    float ntz = std::max(-maxStep, std::min(maxStep, tz0 + dtz));

    double trial = evalT(ntx, nty, ntz);
    if (trial < base * 0.995) {
        f.poseTx = ntx;
        f.poseTy = nty;
        f.poseTz = ntz;
        LOGI("GN trans frame %d: tx=%.3f ty=%.3f tz=%.3f (cost %.1f->%.1f)",
             frameIdx, ntx, nty, ntz, base, trial);
    }
}

/** Bake optimized pose translation into per-frame warp (anchor frame stays 0). */
static void syncPoseTranslationIntoWarpLocked() {
    double tanH, tanV;
    sessionTanHalfFov(tanH, tanV);
    for (size_t i = 0; i < sFrames.size(); i++) {
        StoredFrame& f = sFrames[i];
        if (i == 0) {
            f.poseTx = f.poseTy = f.poseTz = 0.f;
            continue;
        }
        if (f.poseTx == 0.f && f.poseTy == 0.f && f.poseTz == 0.f) continue;

        double pxPerUnit = f.w / (2.0 * tanH);
        float dx = static_cast<float>(f.poseTx * pxPerUnit * POSE_T_TO_PX_SCALE);
        float dy = static_cast<float>(-f.poseTy * pxPerUnit * POSE_T_TO_PX_SCALE);
        for (int ty = 0; ty < TILE_GAIN_N; ty++) {
            for (int tx = 0; tx < TILE_GAIN_N; tx++) {
                f.warp.cells[ty][tx].tx += dx;
                f.warp.cells[ty][tx].ty += dy;
            }
        }
        smoothWarpField(f.warp);
        f.poseTx = f.poseTy = f.poseTz = 0.f;
    }
}

/** Coordinate-descent rotation search for one frame against prior neighbors. */
static void buildOverlapSamplesFromEquirect(int frameIdx, std::vector<OverlapSample>& overlap);

static void refineFramePoseGlobal(int frameIdx, double tanH, double tanV) {
    if (frameIdx <= 0 || frameIdx >= (int)sFrames.size()) return;
    StoredFrame& f = sFrames[frameIdx];
    if (f.locked) return;

    Quat qBase = f.refinedQuat;
    const double basePhotometric = sparseGraphPairwiseSSD(frameIdx, qBase, tanH, tanV);
    if (sGlobalRefinePassesOverride > 0 && basePhotometric < 220.0) return;
    double bestDyaw = 0, bestDpitch = 0, bestDroll = 0;

    // Edge-map coarse (ZNCC) when photometric SSD is high, then RGB fine below.
    if (basePhotometric > 2000.0) {
        rebuildEquirectGradMagLocked();
        std::vector<OverlapSample> overlap;
        buildOverlapSamplesFromEquirect(frameIdx, overlap);
        if (overlap.size() >= 20) {
            double bestGradCost = overlapGradZnccCost(overlap, qBase, 8);
            double edgeR = globalPoseSearchRadiusRad(f, basePhotometric);
            const double edgeS = (edgeR > 10.0 * DEG2RAD) ? (2.0 * DEG2RAD) : (1.5 * DEG2RAD);
            for (double dp = -edgeR; dp <= edgeR; dp += edgeS) {
                for (double dy = -edgeR; dy <= edgeR; dy += edgeS) {
                    Quat qY = quatFromAxisAngle(0, 0, 1, dy);
                    Quat qP = quatFromAxisAngle(1, 0, 0, dp);
                    Quat qC = quatMul(qY, quatMul(qP, qBase));
                    double c = overlapGradZnccCost(overlap, qC, 6);
                    if (c < bestGradCost) {
                        bestGradCost = c;
                        bestDyaw = dy;
                        bestDpitch = dp;
                    }
                }
            }
        }
    }

    double bestSSD = 1e18;

    auto evalDelta = [&](double dyaw, double dpitch, double droll) -> double {
        Quat qY = quatFromAxisAngle(0, 0, 1, dyaw);
        Quat qP = quatFromAxisAngle(1, 0, 0, dpitch);
        Quat qR = quatFromAxisAngle(0, 0, 1, droll);
        Quat qCand = quatMul(qY, quatMul(qP, quatMul(qR, qBase)));
        double photometric = sparseGraphPairwiseSSD(frameIdx, qCand, tanH, tanV);
        const int featInliers = frameFeatureInlierSum(frameIdx);
        double photoScale = 1.0;
        if (featInliers >= 80) photoScale = 0.35;
        if (featInliers >= 140) photoScale = 0.15;
        photometric *= photoScale;
        const double imuLambda = globalImuPriorLambda(f, photometric);
        double imuPenalty = imuLambda * quatAngularDistSq(qCand, f.imuQuat);
        double featurePenalty = featureGraphPenaltyForFrame(frameIdx, qCand);
        return photometric + imuPenalty + featurePenalty;
    };

    bestSSD = evalDelta(bestDyaw, bestDpitch, bestDroll);
    const double R = globalPoseSearchRadiusRad(f, basePhotometric);
    const double S = (R > 8.0 * DEG2RAD) ? (1.25 * DEG2RAD) : (0.75 * DEG2RAD);
    const double seedDy = bestDyaw;
    const double seedDp = bestDpitch;
    for (double dp = seedDp - R; dp <= seedDp + R; dp += S) {
        for (double dy = seedDy - R; dy <= seedDy + R; dy += S) {
            double s = evalDelta(dy, dp, bestDroll);
            if (s < bestSSD) {
                bestSSD = s;
                bestDyaw = dy;
                bestDpitch = dp;
            }
        }
    }
    for (double dr = -R; dr <= R; dr += S) {
        double s = evalDelta(bestDyaw, bestDpitch, dr);
        if (s < bestSSD) {
            bestSSD = s;
            bestDroll = dr;
        }
    }
    constexpr double Rf = 0.5 * DEG2RAD;
    constexpr double Sf = 0.1 * DEG2RAD;
    for (double dp = bestDpitch - Rf; dp <= bestDpitch + Rf; dp += Sf) {
        for (double dy = bestDyaw - Rf; dy <= bestDyaw + Rf; dy += Sf) {
            double s = evalDelta(dy, dp, bestDroll);
            if (s < bestSSD) {
                bestSSD = s;
                bestDyaw = dy;
                bestDpitch = dp;
            }
        }
    }

    const double baselineAtZero = evalDelta(0, 0, 0);
    const double acceptRatio = (baselineAtZero < 300.0) ? 0.995 : 0.98;
    if (bestSSD < baselineAtZero * acceptRatio) {
        Quat qY = quatFromAxisAngle(0, 0, 1, bestDyaw);
        Quat qP = quatFromAxisAngle(1, 0, 0, bestDpitch);
        Quat qR = quatFromAxisAngle(0, 0, 1, bestDroll);
        f.refinedQuat = quatMul(qY, quatMul(qP, quatMul(qR, qBase)));
        double corrDeg = std::sqrt(
            bestDyaw * bestDyaw + bestDpitch * bestDpitch + bestDroll * bestDroll) * RAD2DEG;
        LOGI("Global pose frame %d: yaw=%.2f pitch=%.2f roll=%.2f (%.2f deg, SSD %.1f->%.1f)",
             frameIdx, bestDyaw * RAD2DEG, bestDpitch * RAD2DEG, bestDroll * RAD2DEG,
             corrDeg, baselineAtZero, bestSSD);
    }

    // G2: per-frame warp (skip in fast global — expensive, marginal for rotation recovery).
    if (sGlobalRefinePassesOverride <= 0 && basePhotometric >= 300.0) {
        std::vector<OverlapSample> overlap;
        buildOverlapSamplesFromEquirect(frameIdx, overlap);
        if (overlap.size() >= 20) {
            const float* depthPtr =
                (int)f.depth.size() == f.w * f.h ? f.depth.data() : nullptr;
            estimateFastGlobalWarp(
                overlap, f.refinedQuat, 1.0, 1.0, 1.0,
                f.w, f.h, tanH, tanV, f.warp, depthPtr);
        }
    }
}

/** Multi-pass global pose adjustment before equirect rebuild. */
static void globalRefinePosesLocked() {
    const int n = (int)sFrames.size();
    if (n < 2) return;

    double tanH, tanV;
    sessionTanHalfFov(tanH, tanV);
    auto t0 = SteadyClock::now();

    double meanSsd = 0.0;
    int ssdCount = 0;
    for (int i = 1; i < n; i++) {
        double s = sparseGraphPairwiseSSD(i, sFrames[i].refinedQuat, tanH, tanV);
        if (s < 1e17) {
            meanSsd += s;
            ssdCount++;
        }
    }
    // Finish-quality global graph: more passes than mid-capture debounce (IMU seed recovery).
    const int passes = sGlobalRefinePassesOverride > 0
        ? sGlobalRefinePassesOverride
        : ((n <= 4) ? 6 : ((n <= 12) ? 5 : 4));
    const bool fastGlobal = sGlobalRefinePassesOverride > 0;

    // Fast E2E only: skip when neighbors already agree. Production must still run global
    // (low local SSD does not imply correct global pose under IMU drift).
    if (fastGlobal && ssdCount > 0 && meanSsd / ssdCount < 250.0) {
        LOGI("Global pose refine: skipped fast path (mean SSD %.1f already low)",
             meanSsd / ssdCount);
        return;
    }

    for (int pass = 0; pass < passes; pass++) {
        for (int i = 1; i < n; i++) {
            refineFramePoseGlobal(i, tanH, tanV);
        }
        for (int i = 1; i < n; i++) {
            refineFramePoseGaussNewton(i, tanH, tanV);
        }
        if (!fastGlobal) {
            // G2 6-DoF: translation in sparse graph after rotation converges.
            for (int i = 1; i < n; i++) {
                refineFrameTranslationGlobal(i, tanH, tanV);
                refineFrameTranslationGaussNewton(i, tanH, tanV);
            }
        }
    }
    if (!fastGlobal) {
        syncPoseTranslationIntoWarpLocked();
    }
    // S27: coupled bundle polish over overlap-bank graph (extra GN pass).
    if (!fastGlobal && sOverlapBank.size() >= 24) {
        for (int i = 1; i < n; i++) {
            refineFramePoseGaussNewton(i, tanH, tanV);
            refineFrameTranslationGaussNewton(i, tanH, tanV);
        }
        rebuildOverlapBankLocked();
    }
    LOGI("Global pose refine (%s): %d frames, %d passes in %lldus",
         fastGlobal ? "rotation-only" : "6-DoF", n, passes, (long long)elapsedUs(t0));
}

/** Build overlap samples for a stored frame against the current equirect. */
static void buildOverlapSamplesFromEquirect(
    int frameIdx, std::vector<OverlapSample>& overlap) {

    overlap.clear();
    if (frameIdx < 0 || frameIdx >= (int)sFrames.size()) return;
    const StoredFrame& f = sFrames[frameIdx];
    double tanH, tanV;
    sessionTanHalfFov(tanH, tanV);
    constexpr int SAMPLE_STEP = 8;
    overlap.reserve((f.w / SAMPLE_STEP + 1) * (f.h / SAMPLE_STEP + 1));
    for (int fy = 0; fy < f.h; fy += SAMPLE_STEP) {
        for (int fx = 0; fx < f.w; fx += SAMPLE_STEP) {
            int32_t p = f.px[(size_t)fy * f.w + fx];
            OverlapSample s{};
            s.newR = static_cast<float>((p >> 16) & 0xff);
            s.newG = static_cast<float>((p >> 8) & 0xff);
            s.newB = static_cast<float>(p & 0xff);
            s.fx = fx;
            s.fy = fy;
            s.newGrad = storedPixelGradMag(f, fx, fy);
            pixelToRay(fx, fy, f.w, f.h, tanH, tanV, s.rx, s.ry, s.rz);
            double wx, wy, wz;
            quatRotate(f.refinedQuat, s.rx, s.ry, s.rz, wx, wy, wz);
            double lon = std::atan2(wx, wy);
            double lat = std::asin(std::max(-1.0, std::min(1.0, wz)));
            int eqX = ((int)((lon / PI + 1.0) * 0.5 * eqW) + eqW) % eqW;
            int eqY = clampI((int)((0.5 - lat / PI) * eqH), 0, eqH - 1);
            if (eqWeight[eqY * eqW + eqX] > 0.f) {
                overlap.push_back(s);
            }
        }
    }
}

/** G5: scale per-cell translation by local depth (near = larger parallax correction). */
static void applyDepthParallaxWarp(
    FrameWarp& warp, const float* depthMap, int frameW, int frameH) {

    if (depthMap == nullptr || frameW <= 0 || frameH <= 0) return;
    for (int ty = 0; ty < TILE_GAIN_N; ty++) {
        for (int tx = 0; tx < TILE_GAIN_N; tx++) {
            int x0 = tx * frameW / TILE_GAIN_N;
            int x1 = (tx + 1) * frameW / TILE_GAIN_N;
            int y0 = ty * frameH / TILE_GAIN_N;
            int y1 = (ty + 1) * frameH / TILE_GAIN_N;
            double sumD = 0.0;
            int count = 0;
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    sumD += depthMap[(size_t)y * frameW + x];
                    count++;
                }
            }
            if (count == 0) continue;
            float meanNear = static_cast<float>(sumD / count);
            float scale = DEPTH_PARALLAX_MIN_SCALE +
                (DEPTH_PARALLAX_MAX_SCALE - DEPTH_PARALLAX_MIN_SCALE) * meanNear;
            CellAffine& c = warp.cells[ty][tx];
            c.tx *= scale;
            c.ty *= scale;
        }
    }
    smoothWarpField(warp);
}

static void applyDepthParallaxWarpLocked(StoredFrame& f) {
    if ((int)f.depth.size() != f.w * f.h) return;
    applyDepthParallaxWarp(f.warp, f.depth.data(), f.w, f.h);
}

/**
 * G2/G3/G5 motion refine after global rotation: re-estimate translation +
 * per-cell warp from overlap, depth-weighted when available.
 */
// Skip parallax warp when rotation is still badly misaligned (avoids ghost amplification).
static constexpr double MOTION_WARP_SKIP_PAIRWISE_SSD = 3000.0;

static void refineMotionWarpsLocked() {
    const int n = (int)sFrames.size();
    if (n < 2) return;

    double tanH, tanV;
    sessionTanHalfFov(tanH, tanV);
    auto t0 = SteadyClock::now();
    int refined = 0;
    int skipped = 0;

    for (int i = 1; i < n; i++) {
        StoredFrame& f = sFrames[i];
        if (f.locked) continue;

        const double pairwiseSSD = sparseGraphPairwiseSSD(i, f.refinedQuat, tanH, tanV);
        if (pairwiseSSD > MOTION_WARP_SKIP_PAIRWISE_SSD) {
            skipped++;
            LOGI("Motion warp skip frame %d: pairwise SSD %.1f", i, pairwiseSSD);
            continue;
        }

        std::vector<OverlapSample> overlap;
        buildOverlapSamplesFromEquirect(i, overlap);
        if (overlap.size() < 20) continue;

        const float* depthPtr = (int)f.depth.size() == f.w * f.h ? f.depth.data() : nullptr;
        const double gainR = 1.0, gainG = 1.0, gainB = 1.0;

        // G2-fast: global translation on refine (re-seed from capture warp).
        estimateFastGlobalWarp(
            overlap, f.refinedQuat, gainR, gainG, gainB,
            f.w, f.h, tanH, tanV, f.warp, depthPtr);

        // G3: per-cell affine field in overlap.
        double warpSSD = 0.0;
        estimatePerCellWarp(
            overlap, f.refinedQuat, gainR, gainG, gainB,
            f.w, f.h, tanH, tanV, f.warp, warpSSD,
            /*seedFromExisting=*/true, depthPtr);

        // G5: depth-modulated parallax scaling.
        applyDepthParallaxWarpLocked(f);
        refined++;
    }
    LOGI("Motion warp refine: %d/%d frames skipped=%d in %lldus",
         refined, n, skipped, (long long)elapsedUs(t0));
    if (refined > 0) rebuildOverlapBankLocked();
}

// Lock only refined frames that fall outside the re-align sliding window.
static void updateLockedFramesAfterRefine() {
    const int n = (int)sFrames.size();
    for (int i = 0; i < n; i++) {
        StoredFrame& f = sFrames[i];
        if (!f.refined) {
            f.locked = false;
            continue;
        }
        int ageFromNewest = n - 1 - i;
        f.locked = ageFromNewest > REALIGN_WINDOW;
    }
}

/** Reset stored poses to raw IMU seeds (clears per-frame fast-align drift before global/export). */
static void resetPosesToImuLocked() {
    for (auto& f : sFrames) {
        f.refinedQuat = f.imuQuat;
        f.fastCorrectedQuat = f.imuQuat;
        initIdentityWarp(f.warp);
        f.poseTx = f.poseTy = f.poseTz = 0.f;
    }
    invalidateEquirectFreshLocked();
}

/** L2b export: clear and rebuild with full per-frame visual alignment (photo-quality). */
static int rebuildEquirectFullAlignLocked() {
    std::fill(eqR.begin(), eqR.end(), 0.f);
    std::fill(eqG.begin(), eqG.end(), 0.f);
    std::fill(eqB.begin(), eqB.end(), 0.f);
    std::fill(eqWeight.begin(), eqWeight.end(), 0.f);
    eqSplatCenterDistSq.assign((size_t)eqW * eqH, -1e18f);
    sNearestCenterSplat = true;
    resetCoverageCountersLocked();
    sFrameIndex = 0;
    sLastRejected = 0;
    sLastRollOffset = 0.f;

    int aligned = 0;
    for (auto& f : sFrames) {
        initIdentityWarp(f.warp);
        f.poseTx = f.poseTy = f.poseTz = 0.f;
        const float* depthPtr = f.depth.empty() ? nullptr : f.depth.data();
        FrameOutcome o = processFrameLocked(
            f.px.data(), f.w, f.h, f.refinedQuat,
            /*doAlign=*/true, /*allowReject=*/false, /*overlapGate=*/0.f,
            &f.warp, /*recencyComposite=*/false, depthPtr,
            f.expGainR, f.expGainG, f.expGainB,
            /*exportRebuild=*/true);
        if (o.accepted) {
            f.refinedQuat = o.corrected;
            if (!f.locked) aligned++;
        }
        f.refined = true;
    }
    sNearestCenterSplat = false;
    updateLockedFramesAfterRefine();
    sFramesAtLastRefine = (int)sFrames.size();
    markEquirectFreshLocked();
    return aligned;
}

/** Re-splat all stored frames after pose or intrinsic changes (layer 2/3 rebuild). */
static int rebuildEquirectFromStoredFramesLocked(
    bool recencyComposite = false, bool forceStoredExposure = false) {
    std::fill(eqR.begin(), eqR.end(), 0.f);
    std::fill(eqG.begin(), eqG.end(), 0.f);
    std::fill(eqB.begin(), eqB.end(), 0.f);
    std::fill(eqWeight.begin(), eqWeight.end(), 0.f);
    resetCoverageCountersLocked();
    sFrameIndex = 0;
    sLastRejected = 0;
    sLastRollOffset = 0.f;

    int aligned = 0;
    for (auto& f : sFrames) {
        const float* depthPtr = f.depth.empty() ? nullptr : f.depth.data();
        const bool applyStoredExposure = recencyComposite || forceStoredExposure;
        const float forcedR = applyStoredExposure ? f.expGainR : 0.f;
        const float forcedG = applyStoredExposure ? f.expGainG : 0.f;
        const float forcedB = applyStoredExposure ? f.expGainB : 0.f;
        FrameOutcome o = processFrameLocked(
            f.px.data(), f.w, f.h, f.refinedQuat,
            /*doAlign=*/false, /*allowReject=*/false, /*overlapGate=*/0.f,
            &f.warp, recencyComposite, depthPtr,
            forcedR, forcedG, forcedB,
            /*exportRebuild=*/true);
        if (!f.locked) aligned++;
        f.refined = true;
        (void)o;
    }
    updateLockedFramesAfterRefine();
    sFramesAtLastRefine = (int)sFrames.size();
    markEquirectFreshLocked();
    return aligned;
}

/** Truth export: resplat stored poses with nearest-center overlap (no photometric align). */
static int rebuildEquirectExportNearestCenterLocked() {
    eqSplatCenterDistSq.assign((size_t)eqW * eqH, -1e18f);
    sNearestCenterSplat = true;
    for (auto& f : sFrames) {
        initIdentityWarp(f.warp);
        f.poseTx = f.poseTy = f.poseTz = 0.f;
    }
    const int aligned = rebuildEquirectFromStoredFramesLocked(/*recencyComposite=*/false);
    sNearestCenterSplat = false;
    return aligned;
}

/** DONE export: resplat at full resolution without re-running photometric align. */
static bool prepareExportEquirectLocked(int outW, int outH) {
    if (outW <= 0 || outH <= 0 || sFrames.empty()) return false;
    if (sEquirectBufferFresh && outW == eqW && outH == eqH && !sFastFinishRefine) {
        LOGI("Export prep: skip resplat (%dx%d fresh)", eqW, eqH);
        return true;
    }
    auto t0 = SteadyClock::now();
    if (outW != eqW || outH != eqH) {
        eqW = outW;
        eqH = outH;
        const int sz = eqW * eqH;
        eqR.assign(sz, 0.f);
        eqG.assign(sz, 0.f);
        eqB.assign(sz, 0.f);
        eqWeight.assign(sz, 0.f);
        eqSplatCenterDistSq.assign(sz, -1e18f);
    } else {
        std::fill(eqR.begin(), eqR.end(), 0.f);
        std::fill(eqG.begin(), eqG.end(), 0.f);
        std::fill(eqB.begin(), eqB.end(), 0.f);
        std::fill(eqWeight.begin(), eqWeight.end(), 0.f);
        eqSplatCenterDistSq.assign((size_t)eqW * eqH, -1e18f);
    }
    sNearestCenterSplat = sFastFinishRefine;
    resetCoverageCountersLocked();
    sFrameIndex = 0;
    sLastRejected = 0;
    sLastRollOffset = 0.f;
    const int aligned = rebuildEquirectFromStoredFramesLocked(/*recencyComposite=*/false);
    sNearestCenterSplat = false;
    LOGI("Export prep: %dx%d resplat %zu frames (%d aligned, nearestCenter=%d) in %lldus",
         eqW, eqH, sFrames.size(), aligned, sFastFinishRefine ? 1 : 0,
         (long long)elapsedUs(t0));
    return true;
}

/** Refresh parallax warps from current equirect (offline between full-align passes). */
static bool refineStoredPosesLocked() {
    const int n = (int)sFrames.size();
    if (n < 2) return false;

    auto t0 = SteadyClock::now();
    refineChainedPosesLocked();
    rebuildEquirectFromStoredFramesLocked(/*recencyComposite=*/false);
    if (!sFastFinishRefine) {
        refineMotionWarpsLocked();
    }
    rebuildOverlapBankLocked();
    LOGI("Stored pose refine: %d frames fast=%d in %lldus",
         n, sFastFinishRefine, (long long)elapsedUs(t0));
    return true;
}

/** Layer 3: intrinsic pass only (after global pose minimum). */
static bool refineIntrinsicsPendingLocked() {
    if (sFrames.size() < 2) return false;
    rebuildOverlapBankLocked();
    if (sOverlapBank.size() < 20) return false;
    auto t0 = SteadyClock::now();
    if (!refineIntrinsicsFromOverlapBankLocked()) return false;
    rebuildEquirectFromStoredFramesLocked(/*recencyComposite=*/false, /*forceStoredExposure=*/true);
    LOGI("Intrinsic refine rebuild: %zu frames in %lldus",
         sFrames.size(), (long long)elapsedUs(t0));
    return true;
}

JNIEXPORT jboolean JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeAddFrame(
    JNIEnv* env, jobject,
    jintArray jPixels, jint frameW, jint frameH,
    jfloat qx, jfloat qy, jfloat qz, jfloat qw,
    jboolean allowReject) {

    std::lock_guard<std::mutex> lock(sphereMutex);
    if (eqW == 0 || eqH == 0) return JNI_FALSE;
    jint* pixels = env->GetIntArrayElements(jPixels, nullptr);
    if (!pixels) return JNI_FALSE;
    FrameOutcome o = processFrameLocked(
        reinterpret_cast<const int32_t*>(pixels), frameW, frameH,
        Quat{qx, qy, qz, qw}, /*doAlign=*/true, allowReject != JNI_FALSE, /*overlapGate=*/0.f);
    if (o.accepted) {
        Quat imuQ{qx, qy, qz, qw};
        double l = std::sqrt(imuQ.x * imuQ.x + imuQ.y * imuQ.y + imuQ.z * imuQ.z + imuQ.w * imuQ.w);
        if (l > 1e-9) { imuQ.x /= l; imuQ.y /= l; imuQ.z /= l; imuQ.w /= l; }
        storeFrameForRefine(
            reinterpret_cast<const int32_t*>(pixels), frameW, frameH,
            imuQ, o.corrected, o.warp,
            o.expGainR, o.expGainG, o.expGainB,
            nullptr, 0);
    }
    env->ReleaseIntArrayElements(jPixels, pixels, JNI_ABORT);
    return o.accepted ? JNI_TRUE : JNI_FALSE;
}

// Fast path: splat immediately using the raw IMU quaternion (no alignment),
// gated by minimum edge overlap. Stores the frame for background refinement.
// Returns: 1 = accepted, 0 = rejected (quality), 2 = rejected (low overlap).
JNIEXPORT jint JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeAddFrameFast(
    JNIEnv* env, jobject,
    jintArray jPixels, jint frameW, jint frameH,
    jfloat qx, jfloat qy, jfloat qz, jfloat qw, jfloat overlapGate,
    jfloatArray jDepth, jboolean allowReject) {

    std::lock_guard<std::mutex> lock(sphereMutex);
    if (eqW == 0 || eqH == 0) return 0;
    invalidateEquirectFreshLocked();
    jint* pixels = env->GetIntArrayElements(jPixels, nullptr);
    if (!pixels) return 0;
    const float* depthPtr = nullptr;
    std::vector<float> depthCopy;
    if (jDepth != nullptr) {
        jsize depthLen = env->GetArrayLength(jDepth);
        if (depthLen == frameW * frameH) {
            depthCopy.resize((size_t)depthLen);
            env->GetFloatArrayRegion(jDepth, 0, depthLen, depthCopy.data());
            depthPtr = depthCopy.data();
        }
    }
    Quat q{qx, qy, qz, qw};
    FrameOutcome o = processFrameLocked(
        reinterpret_cast<const int32_t*>(pixels), frameW, frameH,
        q, /*doAlign=*/false, /*allowReject=*/allowReject != JNI_FALSE, /*overlapGate=*/overlapGate,
        nullptr, /*recencyComposite=*/false, depthPtr);
    jint result;
    if (o.rejectedOverlap) {
        result = 2;
    } else if (o.accepted) {
        Quat imuQ = q;
        double l = std::sqrt(imuQ.x*imuQ.x + imuQ.y*imuQ.y + imuQ.z*imuQ.z + imuQ.w*imuQ.w);
        if (l > 1e-9) { imuQ.x/=l; imuQ.y/=l; imuQ.z/=l; imuQ.w/=l; }
        // Store IMU prior + fast-corrected pose for global refine seed (R1 uses IMU replay).
        storeFrameForRefine(
            reinterpret_cast<const int32_t*>(pixels), frameW, frameH,
            imuQ, o.corrected, o.warp,
            o.expGainR, o.expGainG, o.expGainB,
            depthPtr, depthPtr ? frameW * frameH : 0);
        result = 1;
    } else {
        result = 0;
    }
    env->ReleaseIntArrayElements(jPixels, pixels, JNI_ABORT);
    return result;
}

// Background refinement: rebuild the panorama from stored frames.
// applyGlobalPose=true runs pairwise pose graph (export quality); false replays
// capture-time IMU poses (preview parity with fast path).
static bool hasRefineWorkPendingLocked(bool applyGlobalPose) {
    if (eqW == 0 || eqH == 0 || sFrames.empty()) return false;
    if ((int)sFrames.size() < 2) return false;

    bool anyDirty = false;
    for (const auto& f : sFrames) {
        if (!f.refined) { anyDirty = true; break; }
    }
    if (!anyDirty && (int)sFrames.size() <= sFramesAtLastRefine) {
        if (!applyGlobalPose) return false;
        const bool globalCommitted = sCompletedRefineWasGlobal &&
            sLastRefineSsdBefore > 0.f &&
            sLastRefineSsdAfter <= sLastRefineSsdBefore * 0.995f;
        if (globalCommitted && !sPendingFinishGlobalUpgrade) return false;
        if (globalCommitted && sPendingFinishGlobalUpgrade) {
            return true;
        }
    }
    return true;
}

JNIEXPORT jboolean JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeHasRefineWorkPending(
    JNIEnv*, jobject, jboolean applyGlobalPose) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    return hasRefineWorkPendingLocked(applyGlobalPose != JNI_FALSE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeRefinePending(
    JNIEnv*, jobject, jboolean applyGlobalPose) {

    std::lock_guard<std::mutex> lock(sphereMutex);
    if (!hasRefineWorkPendingLocked(applyGlobalPose != JNI_FALSE)) return JNI_FALSE;
    if (applyGlobalPose != JNI_FALSE && sPendingFinishGlobalUpgrade) {
        sPendingFinishGlobalUpgrade = 0;
    }

    sLastRefineRan = 1;
    sLastRefineGlobalPose = applyGlobalPose ? 1 : 0;
    sLastRefineSsdBefore = 0.f;
    sLastRefineSsdAfter = 0.f;
    sLastRefineFramesRealigned = 0;

    auto tRefine = SteadyClock::now();
    bool globalPoseReverted = false;
    for (auto& f : sFrames) {
        if (!f.refined) f.locked = false;
    }

    if (applyGlobalPose) {
        const double maxImuFastDeg = sessionMaxImuFastGapDeg();
        const bool useImuSeed = sFastFinishRefine || maxImuFastDeg > 4.0;
        for (auto& f : sFrames) {
            f.refinedQuat = useImuSeed ? f.imuQuat : f.fastCorrectedQuat;
            f.poseTx = f.poseTy = f.poseTz = 0.f;
        }
        LOGI("Global refine seed: %s (max imu-fast gap %.1f deg, fastFinish=%d)",
             useImuSeed ? "imuQuat" : "fastCorrected", maxImuFastDeg, sFastFinishRefine ? 1 : 0);
        rebuildOverlapBankLocked();
        if (sFrames.size() >= 3 && sOverlapBank.size() >= 20 &&
            sGlobalRefinePassesOverride <= 0) {
            refineIntrinsicsFromOverlapBankLocked();
            rebuildOverlapBankLocked();
        }
        if (sOverlapBank.size() >= 20) {
            sLastRefineSsdBefore = static_cast<float>(
                evalOverlapBankSSD(sFocalScale, distK1, /*includePrior=*/false));
        }
        // Feature graph helps high IMU error; skip on sweep-fast low-degrade (noisy matches).
        const bool useFeatureGraph = !sFastFinishRefine || maxImuFastDeg >= 8.0;
        if (useFeatureGraph) {
            buildFeatureEdgesLocked();
            const int featureSeeded = seedPosesFromFeatureGraphLocked();
            if (featureSeeded < 2) {
                seedPosesPairwiseChainLocked();
            }
        } else {
            LOGI("Global refine: skip feature graph (fastFinish imuGap %.1f deg)", maxImuFastDeg);
        }
        // Optional pose-graph seed; revert if overlap SSD regresses.
        std::vector<Quat> seedQuats;
        seedQuats.reserve(sFrames.size());
        for (const auto& f : sFrames) seedQuats.push_back(f.refinedQuat);

        globalRefinePosesLocked();
        if (sGlobalRefinePassesOverride <= 0 && !sFastFinishRefine) {
            refineMotionWarpsLocked();
        }
        rebuildOverlapBankLocked();
        if (sOverlapBank.size() >= 20) {
            sLastRefineSsdAfter = static_cast<float>(
                evalOverlapBankSSD(sFocalScale, distK1, /*includePrior=*/false));
            if (sGlobalRefinePassesOverride <= 0 &&
                sLastRefineSsdAfter > sLastRefineSsdBefore * 0.995f) {
                for (size_t i = 0; i < sFrames.size(); i++) {
                    sFrames[i].refinedQuat = seedQuats[i];
                }
                globalPoseReverted = true;
                LOGI("Global pose seed reverted (SSD %.4f->%.4f)",
                     sLastRefineSsdBefore, sLastRefineSsdAfter);
            }
        }
        if (!globalPoseReverted && sFrames.size() >= 3 && sOverlapBank.size() >= 20 &&
            sGlobalRefinePassesOverride <= 0) {
            // S34: joint polish — second intrinsic pass after pose graph converges.
            refineIntrinsicsFromOverlapBankLocked();
            rebuildOverlapBankLocked();
        }
        // Bake successful global rotations so rebuildFullAlign keeps the graph solution.
        if (!globalPoseReverted) {
            for (auto& f : sFrames) {
                f.fastCorrectedQuat = f.refinedQuat;
            }
        }
    } else {
        // L2a preview: replay from per-frame fast rotational corrections (not raw IMU).
        for (auto& f : sFrames) {
            f.refinedQuat = f.fastCorrectedQuat;
            f.poseTx = f.poseTy = f.poseTz = 0.f;
        }
    }

    // L2b finish: liveRecency compositing + stored exposure (S17 unified with L1).
    int aligned = applyGlobalPose
        ? rebuildEquirectFromStoredFramesLocked(
            /*recencyComposite=*/false, /*forceStoredExposure=*/true)
        : rebuildEquirectFromStoredFramesLocked(/*recencyComposite=*/false);
    sLastRefineFramesRealigned = aligned;
    LOGI("Refine rebuild: %zu frames (%d re-aligned, globalPose=%d) in %lldus SSD %.4f->%.4f",
         sFrames.size(), aligned, (int)applyGlobalPose, (long long)elapsedUs(tRefine),
         sLastRefineSsdBefore, sLastRefineSsdAfter);
    sCompletedRefineWasGlobal =
        (applyGlobalPose && !globalPoseReverted) ? 1 : 0;
    if (applyGlobalPose) {
        sPendingFinishGlobalUpgrade = 1;
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeRefineIntrinsicsPending(
    JNIEnv*, jobject) {

    std::lock_guard<std::mutex> lock(sphereMutex);
    if (eqW == 0 || eqH == 0 || sFrames.empty()) return JNI_FALSE;
    if ((int)sFrames.size() < 2) return JNI_FALSE;
    return refineIntrinsicsPendingLocked() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeRefineStoredPoses(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    if (eqW == 0 || eqH == 0 || sFrames.size() < 2) return JNI_FALSE;
    return refineStoredPosesLocked() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeRebuildFullAlign(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    if (eqW == 0 || eqH == 0 || sFrames.empty()) return JNI_FALSE;
    sPendingFinishGlobalUpgrade = 0;
    for (auto& f : sFrames) {
        f.refinedQuat = f.fastCorrectedQuat;
        f.refined = false;
        f.locked = false;
    }
    rebuildEquirectFullAlignLocked();
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeResetPosesToImu(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    if (sFrames.empty()) return JNI_FALSE;
    resetPosesToImuLocked();
    LOGI("Reset %zu frame poses to imuQuat", sFrames.size());
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeRebuildExportNearestCenter(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    if (eqW == 0 || eqH == 0 || sFrames.empty()) return JNI_FALSE;
    rebuildEquirectExportNearestCenterLocked();
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativePrepareExportEquirect(
    JNIEnv*, jobject, jint exportW, jint exportH) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    return prepareExportEquirectLocked((int)exportW, (int)exportH) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeRefineSeams(
    JNIEnv*, jobject, jboolean light) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    if (eqW == 0 || eqH == 0) return JNI_FALSE;
    if (light) {
        refineSeamsLightLocked();
        LOGI("Sphere seams refined (light)");
    } else {
        refineSeamsLocked(3);
        LOGI("Sphere seams refined");
    }
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeGetOverlapBankSize(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    return static_cast<jint>(sOverlapBank.size());
}

JNIEXPORT jfloat JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeGetSessionFocalScale(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    return sFocalScale;
}

JNIEXPORT jboolean JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeSetSessionFocalScale(
    JNIEnv*, jobject, jfloat scale) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    if (scale < FOCAL_SCALE_MIN || scale > FOCAL_SCALE_MAX) return JNI_FALSE;
    sFocalScale = scale;
    LOGI("Session focal scale seed: %.4f", sFocalScale);
    return JNI_TRUE;
}

JNIEXPORT jfloat JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeGetSessionDistortionK1(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    return distK1;
}

JNIEXPORT jfloat JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeEvalOverlapBankSSD(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    return static_cast<jfloat>(
        evalOverlapBankSSD(sFocalScale, distK1, /*includePrior=*/false));
}

JNIEXPORT jfloatArray JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeGetLastRefineStats(
    JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    jfloatArray out = env->NewFloatArray(5);
    if (!out) return nullptr;
    jfloat buf[5] = {
        sLastRefineSsdBefore,
        sLastRefineSsdAfter,
        static_cast<jfloat>(sLastRefineFramesRealigned),
        static_cast<jfloat>(sLastRefineGlobalPose),
        static_cast<jfloat>(sLastRefineRan)
    };
    env->SetFloatArrayRegion(out, 0, 5, buf);
    return out;
}

static inline int32_t sampleEquirectRgb(int sx, int sy) {
    int idx = sy * eqW + sx;
    if (eqWeight[idx] > 0.f) {
        float inv = 1.f / eqWeight[idx];
        int r = clampI(static_cast<int>(eqR[idx] * inv), 0, 255);
        int g = clampI(static_cast<int>(eqG[idx] * inv), 0, 255);
        int b = clampI(static_cast<int>(eqB[idx] * inv), 0, 255);
        return (r << 16) | (g << 8) | b;
    }
    return 0;
}

static void fillEquirectGaps(int32_t* out, int w, int h) {
    // Multiple passes to propagate color into stride-2 gaps and larger holes.
    for (int pass = 0; pass < 3; pass++) {
        bool filled = false;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (out[idx] != 0) continue;
                int sumR = 0, sumG = 0, sumB = 0, count = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = (x + dx + w) % w;
                        int32_t p = out[ny * w + nx];
                        if (p != 0) {
                            sumR += (p >> 16) & 0xff;
                            sumG += (p >> 8) & 0xff;
                            sumB += p & 0xff;
                            count++;
                        }
                    }
                }
                if (count >= 2) {
                    out[idx] = static_cast<int32_t>(0xff000000 |
                        ((sumR / count) << 16) | ((sumG / count) << 8) | (sumB / count));
                    filled = true;
                }
            }
        }
        if (!filled) break;
    }
    // Final pass: fill any remaining holes with the dark fallback.
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            int idx = y * w + x;
            if (out[idx] == 0) {
                out[idx] = static_cast<int32_t>(0xff1a1a2e);
            }
        }
    }
}

JNIEXPORT jboolean JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeGetEquirect(
    JNIEnv* env, jobject, jintArray jOut, jint outW, jint outH) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    if (eqW == 0 || eqH == 0 || outW <= 0 || outH <= 0) return JNI_FALSE;
    if (env->GetArrayLength(jOut) < outW * outH) return JNI_FALSE;

    jint* out = env->GetIntArrayElements(jOut, nullptr);
    if (!out) return JNI_FALSE;

    if (outW == eqW && outH == eqH) {
        int sz = eqW * eqH;
        for (int i = 0; i < sz; i++) {
            int32_t rgb = sampleEquirectRgb(i % eqW, i / eqW);
            out[i] = rgb != 0
                ? static_cast<int32_t>(0xff000000 | rgb)
                : 0;
        }
        fillEquirectGaps(reinterpret_cast<int32_t*>(out), eqW, eqH);
    } else {
        for (int y = 0; y < outH; y++) {
            double eqYf = (y + 0.5) * eqH / outH - 0.5;
            for (int x = 0; x < outW; x++) {
                double eqXf = (x + 0.5) * eqW / outW - 0.5;

                int x0 = static_cast<int>(std::floor(eqXf));
                int y0 = static_cast<int>(std::floor(eqYf));
                double fx = eqXf - x0;
                double fy = eqYf - y0;

                int x1 = (x0 + 1) % eqW;
                if (x0 < 0) x0 += eqW;
                x0 = x0 % eqW;
                int y1 = std::min(y0 + 1, eqH - 1);
                y0 = clampI(y0, 0, eqH - 1);

                int32_t p00 = sampleEquirectRgb(x0, y0);
                int32_t p10 = sampleEquirectRgb(x1, y0);
                int32_t p01 = sampleEquirectRgb(x0, y1);
                int32_t p11 = sampleEquirectRgb(x1, y1);

                if (p00 == 0 && p10 == 0 && p01 == 0 && p11 == 0) {
                    out[y * outW + x] = static_cast<int32_t>(0xff1a1a2e);
                    continue;
                }

                // Weight-aware bilinear: skip zero-weight samples to avoid
                // blending real color with black (which causes dark line artifacts).
                double w00 = (p00 != 0) ? (1.0 - fx) * (1.0 - fy) : 0.0;
                double w10 = (p10 != 0) ? fx * (1.0 - fy) : 0.0;
                double w01 = (p01 != 0) ? (1.0 - fx) * fy : 0.0;
                double w11 = (p11 != 0) ? fx * fy : 0.0;
                double wSum = w00 + w10 + w01 + w11;
                if (wSum < 1e-9) {
                    out[y * outW + x] = static_cast<int32_t>(0xff1a1a2e);
                    continue;
                }
                double invW = 1.0 / wSum;
                int r = clampI(static_cast<int>(
                    (w00 * ((p00 >> 16) & 0xff) + w10 * ((p10 >> 16) & 0xff) +
                     w01 * ((p01 >> 16) & 0xff) + w11 * ((p11 >> 16) & 0xff)) * invW), 0, 255);
                int g = clampI(static_cast<int>(
                    (w00 * ((p00 >> 8) & 0xff) + w10 * ((p10 >> 8) & 0xff) +
                     w01 * ((p01 >> 8) & 0xff) + w11 * ((p11 >> 8) & 0xff)) * invW), 0, 255);
                int b = clampI(static_cast<int>(
                    (w00 * (p00 & 0xff) + w10 * (p10 & 0xff) +
                     w01 * (p01 & 0xff) + w11 * (p11 & 0xff)) * invW), 0, 255);

                out[y * outW + x] = static_cast<int32_t>(0xff000000 | (r << 16) | (g << 8) | b);
            }
        }
        // Fill stride-2 gaps in the downscaled output as well.
        fillEquirectGaps(reinterpret_cast<int32_t*>(out), outW, outH);
    }

    env->ReleaseIntArrayElements(jOut, out, 0);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeGetRectilinear(
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

            auto sampleHasWeight = [&](int sx, int sy) -> bool {
                return eqWeight[sy * eqW + sx] > 0.f;
            };
            auto sampleRgb = [&](int sx, int sy) -> int32_t {
                int idx = sy * eqW + sx;
                float inv = 1.f / eqWeight[idx];
                int r = clampI(static_cast<int>(eqR[idx] * inv), 0, 255);
                int g = clampI(static_cast<int>(eqG[idx] * inv), 0, 255);
                int b = clampI(static_cast<int>(eqB[idx] * inv), 0, 255);
                return (r << 16) | (g << 8) | b;
            };

            bool h00 = sampleHasWeight(x0, y0);
            bool h10 = sampleHasWeight(x1, y0);
            bool h01 = sampleHasWeight(x0, y1);
            bool h11 = sampleHasWeight(x1, y1);

            if (!h00 && !h10 && !h01 && !h11) {
                out[y * outW + x] = static_cast<int32_t>(0xff1a1a2e);
                continue;
            }

            int32_t p00 = h00 ? sampleRgb(x0, y0) : 0;
            int32_t p10 = h10 ? sampleRgb(x1, y0) : 0;
            int32_t p01 = h01 ? sampleRgb(x0, y1) : 0;
            int32_t p11 = h11 ? sampleRgb(x1, y1) : 0;

            double w00 = h00 ? (1.0 - fx) * (1.0 - fy) : 0.0;
            double w10 = h10 ? fx * (1.0 - fy) : 0.0;
            double w01 = h01 ? (1.0 - fx) * fy : 0.0;
            double w11 = h11 ? fx * fy : 0.0;
            double wSum = w00 + w10 + w01 + w11;
            double invW = 1.0 / wSum;

            int r = clampI(static_cast<int>(
                (w00 * ((p00 >> 16) & 0xff) + w10 * ((p10 >> 16) & 0xff) +
                 w01 * ((p01 >> 16) & 0xff) + w11 * ((p11 >> 16) & 0xff)) * invW), 0, 255);
            int g = clampI(static_cast<int>(
                (w00 * ((p00 >> 8) & 0xff) + w10 * ((p10 >> 8) & 0xff) +
                 w01 * ((p01 >> 8) & 0xff) + w11 * ((p11 >> 8) & 0xff)) * invW), 0, 255);
            int b = clampI(static_cast<int>(
                (w00 * (p00 & 0xff) + w10 * (p10 & 0xff) +
                 w01 * (p01 & 0xff) + w11 * (p11 & 0xff)) * invW), 0, 255);

            out[y * outW + x] = static_cast<int32_t>(0xff000000 | (r << 16) | (g << 8) | b);
        }
    }

    env->ReleaseIntArrayElements(jOut, out, 0);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeGetLiveSplatDim(
    JNIEnv*, jobject) {
    return LIVE_SPLAT_DIM;
}

JNIEXPORT jfloat JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeGetCoverage(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    int64_t sz = static_cast<int64_t>(eqW) * eqH;
    if (sz == 0) return 0.f;
    // F1: cached incremental count, maintained in splatPixel.
    return static_cast<float>(sCoveredCount) / sz;
}

JNIEXPORT jfloat JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeGetSolidAngleCoverage(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    // F1: cached incremental grid-cell count.
    return static_cast<float>(sGridFilledCells) / (SA_GW * SA_GH);
}

JNIEXPORT jboolean JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeGetHoleMask(
    JNIEnv* env, jobject, jintArray jPixels, jint outW, jint outH) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    if (eqW == 0 || eqH == 0 || outW <= 0 || outH <= 0) return JNI_FALSE;
    jint* out = env->GetIntArrayElements(jPixels, nullptr);
    if (!out) return JNI_FALSE;
    // F1: read the incremental fill grid (O(cells)) instead of scanning the
    // full weight buffer. Grid resolution matches SA_GW x SA_GH.
    for (int oy = 0; oy < outH; oy++) {
        int gy = oy * SA_GH / outH;
        for (int ox = 0; ox < outW; ox++) {
            int gx = ox * SA_GW / outW;
            bool filled = sGridFilled[gy * SA_GW + gx] > 0;
            out[oy * outW + ox] = filled ? 0 : 0x88FF0000;
        }
    }
    env->ReleaseIntArrayElements(jPixels, out, 0);
    return JNI_TRUE;
}

JNIEXPORT jfloatArray JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeGetLastCorrectedQuaternion(
    JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    jfloatArray out = env->NewFloatArray(4);
    if (!out) return nullptr;
    jfloat buf[4] = {
        static_cast<jfloat>(sLastCorrectedQuat.x),
        static_cast<jfloat>(sLastCorrectedQuat.y),
        static_cast<jfloat>(sLastCorrectedQuat.z),
        static_cast<jfloat>(sLastCorrectedQuat.w)
    };
    env->SetFloatArrayRegion(out, 0, 4, buf);
    return out;
}

JNIEXPORT jfloatArray JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeGetStoredFrameQuaternions(
    JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    const jsize n = static_cast<jsize>(sFrames.size() * 4);
    jfloatArray out = env->NewFloatArray(n);
    if (!out) return nullptr;
    if (n == 0) return out;
    std::vector<jfloat> buf((size_t)n);
    for (size_t i = 0; i < sFrames.size(); i++) {
        const Quat& q = sFrames[i].fastCorrectedQuat;
        buf[i * 4 + 0] = static_cast<jfloat>(q.x);
        buf[i * 4 + 1] = static_cast<jfloat>(q.y);
        buf[i * 4 + 2] = static_cast<jfloat>(q.z);
        buf[i * 4 + 3] = static_cast<jfloat>(q.w);
    }
    env->SetFloatArrayRegion(out, 0, n, buf.data());
    return out;
}

JNIEXPORT jfloatArray JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeGetLastFrameInfo(
    JNIEnv* env, jobject) {
    jfloatArray result = env->NewFloatArray(9);
    float data[9] = {
        static_cast<float>(sFrameIndex),
        static_cast<float>(sLastOverlapCount),
        static_cast<float>(sLastTotalSamples),
        sLastAlignOffset,
        sLastResidualSSD,
        static_cast<float>(sLastRejected),
        sLastRollOffset,
        sLastWarpTx,
        sLastWarpTy
    };
    env->SetFloatArrayRegion(result, 0, 9, data);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeLoadEquirect(
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
    recomputeCoverageCountersLocked();
    LOGI("Sphere loaded equirect: %dx%d", eqW, eqH);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeSetMirrorCameraX(
    JNIEnv*, jobject, jboolean mirror) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    sMirrorCameraX = mirror != JNI_FALSE;
    LOGI("Sphere mirrorCameraX=%d", sMirrorCameraX ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeSetGlobalRefinePasses(
    JNIEnv*, jobject, jint passes) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    sGlobalRefinePassesOverride = passes < 0 ? 0 : (int)passes;
}

JNIEXPORT void JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeSetFastFinishRefine(
    JNIEnv*, jobject, jboolean enabled) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    sFastFinishRefine = enabled != JNI_FALSE ? 1 : 0;
}

JNIEXPORT void JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeReset(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    int sz = eqW * eqH;
    if (sz > 0) {
        std::memset(eqR.data(), 0, sz * sizeof(float));
        std::memset(eqG.data(), 0, sz * sizeof(float));
        std::memset(eqB.data(), 0, sz * sizeof(float));
        std::memset(eqWeight.data(), 0, sz * sizeof(float));
    }
    resetCoverageCountersLocked();
    sFrames.clear();
    sOverlapBank.clear();
    sFeatureEdges.clear();
    sFocalScale = 1.f;
    distK1Prior = distK1;
    sFrameIndex = 0;
    sLastRejected = 0;
    sLastCorrectedQuat = {0, 0, 0, 1};
    sFramesAtLastRefine = 0;
    sLastRefineSsdBefore = 0.f;
    sLastRefineSsdAfter = 0.f;
    sLastRefineFramesRealigned = 0;
    sLastRefineGlobalPose = 0;
    sLastRefineRan = 0;
    sCompletedRefineWasGlobal = 0;
    sPendingFinishGlobalUpgrade = 0;
    sGlobalRefinePassesOverride = 0;
    sFastFinishRefine = 0;
    invalidateEquirectFreshLocked();
    LOGI("Sphere reset");
}

JNIEXPORT void JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeRelease(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    sFrames.clear(); sFrames.shrink_to_fit();
    sOverlapBank.clear(); sOverlapBank.shrink_to_fit();
    sFocalScale = 1.f;
    eqR.clear(); eqR.shrink_to_fit();
    eqG.clear(); eqG.shrink_to_fit();
    eqB.clear(); eqB.shrink_to_fit();
    eqWeight.clear(); eqWeight.shrink_to_fit();
    eqGradMag.clear(); eqGradMag.shrink_to_fit();
    eqW = 0; eqH = 0;
    LOGI("Sphere released");
}

} // extern "C"
