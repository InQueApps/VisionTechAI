/**
 * Sphere panorama engine (JNI).
 *
 * Accumulates camera frames into a 4096x2048 equirectangular buffer using
 * quaternion-based orientation from the device IMU. Supports rectilinear
 * (perspective) extraction for interactive viewing.
 *
 * v3 — Performance + quality:
 *   - Adaptive live capture: IMU seed + tiered pairwise visual tracking.
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
#include <cstddef>
#include <algorithm>
#include <deque>
#include <mutex>
#include <atomic>
#include <chrono>
#include <functional>
#include <set>
#include <array>

#define LOG_TAG "SphereJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static constexpr double PI = 3.14159265358979323846;
static constexpr double DEG2RAD = PI / 180.0;
static constexpr double RAD2DEG = 180.0 / PI;

// Residual SSD above this threshold (after alignment) means poor match
static constexpr double REJECT_SSD_THRESH = 8000.0;
static constexpr double REJECT_SSD_RELAXED = REJECT_SSD_THRESH * 1.35;
static constexpr double FAST_DUBIOUS_SSD_THRESH = 2800.0;
static constexpr int TILE_GAIN_N = 8;

/** Minimum geometric overlap for pairwise rotation candidates. */
static constexpr int PAIRWISE_MIN_SAMPLES_FLOOR = 24;
static constexpr double PAIRWISE_MIN_OVERLAP = 0.08;
static constexpr double PAIRWISE_GOOD_SSD = 1200.0;
static constexpr double PAIRWISE_GOOD_OVERLAP = 0.12;
static constexpr double PAIRWISE_INF = 1e18;

static inline int pairwiseMinCount(int total) {
    return std::max(PAIRWISE_MIN_SAMPLES_FLOOR, static_cast<int>(0.08 * total));
}

struct PairwiseScore {
    double ssd = PAIRWISE_INF;
    int count = 0;
    int total = 0;
    double overlap = 0.0;

    bool valid() const {
        return ssd < PAIRWISE_INF * 0.5 &&
            count >= pairwiseMinCount(total) &&
            overlap >= PAIRWISE_MIN_OVERLAP;
    }
    bool good() const {
        return valid() && ssd < PAIRWISE_GOOD_SSD && overlap >= PAIRWISE_GOOD_OVERLAP;
    }
};

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

static inline Quat quatExpTangent(double tx, double ty, double tz) {
    double angle = std::sqrt(tx * tx + ty * ty + tz * tz);
    if (angle < 1e-12) return {0, 0, 0, 1};
    double half = 0.5 * angle;
    double s = std::sin(half) / angle;
    return {tx * s, ty * s, tz * s, std::cos(half)};
}

static inline Quat quatApplyTangentDelta(const Quat& q, double tx, double ty, double tz) {
    return quatMul(quatExpTangent(tx, ty, tz), q);
}

/** Unit quaternion conjugate (= inverse rotation). */
static inline Quat quatConjugate(const Quat& q) {
    return {-q.x, -q.y, -q.z, q.w};
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
/** Refined panorama back-buffer; atomically swapped into display after background refine. */
static std::vector<float> eqBackR, eqBackG, eqBackB, eqBackWeight;

/** Thread-local splat destination (offline rebuild runs unlocked on refine thread). */
struct SplatAccumContext {
    std::vector<float>* r = nullptr;
    std::vector<float>* g = nullptr;
    std::vector<float>* b = nullptr;
    std::vector<float>* weight = nullptr;
    bool skipLiveCoverage = false;
    bool nearestCenterSplat = false;
    bool previewFeatherSplat = false;
    std::vector<float>* splatCenterDistSq = nullptr;
};
static thread_local SplatAccumContext* tlsSplatAccum = nullptr;

struct ScopedSplatAccum {
    SplatAccumContext ctx;
    explicit ScopedSplatAccum(SplatAccumContext in) : ctx(std::move(in)) {
        tlsSplatAccum = &ctx;
    }
    ~ScopedSplatAccum() { tlsSplatAccum = nullptr; }
};

static inline bool accumSkipLiveCoverage() {
    return tlsSplatAccum && tlsSplatAccum->skipLiveCoverage;
}
static inline bool nearestCenterSplatActive() {
    return tlsSplatAccum && tlsSplatAccum->nearestCenterSplat;
}
static inline bool previewFeatherSplatActive() {
    return tlsSplatAccum && tlsSplatAccum->previewFeatherSplat;
}

static inline float& accumR(int idx) {
    if (tlsSplatAccum && tlsSplatAccum->r) return (*tlsSplatAccum->r)[idx];
    return eqR[idx];
}
static inline float& accumG(int idx) {
    if (tlsSplatAccum && tlsSplatAccum->g) return (*tlsSplatAccum->g)[idx];
    return eqG[idx];
}
static inline float& accumB(int idx) {
    if (tlsSplatAccum && tlsSplatAccum->b) return (*tlsSplatAccum->b)[idx];
    return eqB[idx];
}
static inline float& accumW(int idx) {
    if (tlsSplatAccum && tlsSplatAccum->weight) return (*tlsSplatAccum->weight)[idx];
    return eqWeight[idx];
}
static inline float accumWVal(int idx) {
    if (tlsSplatAccum && tlsSplatAccum->weight) return (*tlsSplatAccum->weight)[idx];
    return eqWeight[idx];
}
/** Precomputed equirect trig (pixel-center lon/lat). */
static std::vector<float> eqSinLon, eqCosLon, eqSinLat, eqCosLat;
/** Dense occupancy for overlap tests (stride-2 live splat leaves sparse eqWeight). */
static std::vector<uint8_t> eqCoverage;
/** Sobel gradient magnitude on equirect luminance (rotation search coarse stage). */
static std::vector<float> eqGradMag;
/** Per-pixel best view-dot during nearest-view export compositing (higher wins). */
static std::vector<float> eqSplatCenterDistSq;

static inline std::vector<float>& activeSplatCenterDistSq() {
    if (tlsSplatAccum && tlsSplatAccum->splatCenterDistSq) {
        return *tlsSplatAccum->splatCenterDistSq;
    }
    return eqSplatCenterDistSq;
}

static std::mutex sphereMutex;

// ── Incremental coverage tracking (F1: avoids full-buffer scans per frame) ──
static constexpr int SA_GW = 32, SA_GH = 16;      // solid-angle grid resolution
static constexpr float SA_FILL_THRESH = 0.05f;    // per-pixel weight to count a cell
static int64_t sCoveredCount = 0;                 // eq pixels with weight > 0
static std::vector<int> sGridFilled;              // filled-pixel count per grid cell
static int sGridFilledCells = 0;                  // grid cells with ≥1 filled pixel

/** When true, eq buffer at eqW×eqH already matches stored poses (skip export resplat). */
static bool sEquirectBufferFresh = false;
/** True only after nearest-center / GA export compose — preview resplat must not skip SAVE. */
static bool sEquirectExportFresh = false;

static inline void resetCoverageCountersLocked() {
    sCoveredCount = 0;
    sGridFilledCells = 0;
    sGridFilled.assign(SA_GW * SA_GH, 0);
}

static inline void markEquirectFreshLocked() {
    sEquirectBufferFresh = true;
    sEquirectExportFresh = false;
}

static inline void markEquirectExportFreshLocked() {
    sEquirectBufferFresh = true;
    sEquirectExportFresh = true;
}

static inline void invalidateEquirectFreshLocked() {
    sEquirectBufferFresh = false;
    sEquirectExportFresh = false;
}

static void rebuildEquirectTrigTablesLocked() {
    if (eqW <= 0 || eqH <= 0) {
        eqSinLon.clear();
        eqCosLon.clear();
        eqSinLat.clear();
        eqCosLat.clear();
        return;
    }
    eqSinLon.resize(eqW);
    eqCosLon.resize(eqW);
    for (int ex = 0; ex < eqW; ex++) {
        const double lon = ((ex + 0.5) / eqW * 2.0 - 1.0) * PI;
        eqSinLon[ex] = static_cast<float>(std::sin(lon));
        eqCosLon[ex] = static_cast<float>(std::cos(lon));
    }
    eqSinLat.resize(eqH);
    eqCosLat.resize(eqH);
    for (int ey = 0; ey < eqH; ey++) {
        const double lat = (0.5 - (ey + 0.5) / eqH) * PI;
        eqSinLat[ey] = static_cast<float>(std::sin(lat));
        eqCosLat[ey] = static_cast<float>(std::cos(lat));
    }
}

// Live pairwise acceptance counters (log ratio enteredCoarse/total).
static int sPairwiseTotalFrames = 0;
static int sFramesImuAccepted = 0;
static int sFramesLocalAccepted = 0;
static int sFramesFeatureAccepted = 0;
static int sFramesFeatureStrongAccepted = 0;
static int sFramesMultiRefFeatureAccepted = 0;
static int sFramesEnteredCoarse = 0;
static int sFramesCoarseBudgetExpired = 0;

static void resetPairwiseAcceptCountersLocked() {
    sPairwiseTotalFrames = 0;
    sFramesImuAccepted = 0;
    sFramesLocalAccepted = 0;
    sFramesFeatureAccepted = 0;
    sFramesFeatureStrongAccepted = 0;
    sFramesMultiRefFeatureAccepted = 0;
    sFramesEnteredCoarse = 0;
    sFramesCoarseBudgetExpired = 0;
}

static void logPairwiseAcceptCountersLocked() {
    if (sPairwiseTotalFrames <= 0) return;
    const float coarseRate =
        static_cast<float>(sFramesEnteredCoarse) / static_cast<float>(sPairwiseTotalFrames);
    __android_log_print(
        ANDROID_LOG_INFO, "SphereProfile",
        "SphereProfile pairwise_accept total=%d imu=%d local=%d feature=%d "
        "featureStrong=%d multiRef=%d enteredCoarse=%d coarseBudgetExpired=%d coarseRate=%.3f",
        sPairwiseTotalFrames, sFramesImuAccepted, sFramesLocalAccepted,
        sFramesFeatureAccepted, sFramesFeatureStrongAccepted,
        sFramesMultiRefFeatureAccepted,
        sFramesEnteredCoarse, sFramesCoarseBudgetExpired, coarseRate);
}

// Full recompute from the weight buffer (used after direct buffer loads).
static void recomputeCoverageCountersLocked() {
    resetCoverageCountersLocked();
    if (eqW == 0 || eqH == 0) return;
    const int sz = eqW * eqH;
    eqCoverage.assign(sz, 0);
    for (int y = 0; y < eqH; y++) {
        int gy = y * SA_GH / eqH;
        for (int x = 0; x < eqW; x++) {
            const int idx = y * eqW + x;
            const float w = eqWeight[idx];
            if (w > 0.f) {
                eqCoverage[idx] = 1;
                sCoveredCount++;
            }
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
    std::vector<int32_t> px;   // refine-resolution RGB (≤ STORE_MAX_DIM)
    std::vector<int32_t> pxExport; // full capture resolution for export resplat (empty when same as px)
    std::vector<float> depth;  // G5: normalized nearness [0,1], 0.5=unknown; same w×h as px
    bool hasRealDepth = false; // true only when caller supplied a depth/nearness map
    int w = 0, h = 0;
    int srcW = 0, srcH = 0;    // original capture size when px was downscaled
    Quat refinedQuat = {0, 0, 0, 1};
    Quat imuQuat = {0, 0, 0, 1};  // raw IMU pose at capture (prior for global refine)
    Quat fastCorrectedQuat = {0, 0, 0, 1};  // G1-fast live rotational correction
    float expGainR = 1.f, expGainG = 1.f, expGainB = 1.f;  // S12 capture-time exposure
    FrameWarp warp{};
    bool locked = false;       // locked frames are re-splatted but not re-aligned
    bool refined = false;      // true once the frame has been aligned at least once
    bool poseTrusted = true;   // false = do not use as live visual anchor / overlap bank
};
static std::vector<StoredFrame> sFrames;
/** Bumped on each accepted frame; stale refine commits are discarded. */
static uint64_t sGeneration = 0;
static std::atomic<int> sRefineDirty{0};

/** Frames waiting for background store (pixels + pose); not on live preview critical path. */
struct PendingFrameStore {
    std::vector<int32_t> px;
    std::vector<float> depth;
    int w = 0, h = 0;
    Quat imuQuat = {0, 0, 0, 1};
    Quat correctedQuat = {0, 0, 0, 1};
    FrameWarp warp{};
    float expGainR = 1.f, expGainG = 1.f, expGainB = 1.f;
    bool poseTrusted = true;
    bool hasRealDepth = false;
};
static std::mutex sPendingStoreMutex;
static std::deque<PendingFrameStore> sPendingFrameStores;
static std::vector<int> sDeferredFeatureFrameIndices;

static void clearPendingFrameStores() {
    std::lock_guard<std::mutex> lock(sPendingStoreMutex);
    sPendingFrameStores.clear();
}

static void enqueuePendingFrameStore(PendingFrameStore&& job) {
    std::lock_guard<std::mutex> lock(sPendingStoreMutex);
    sPendingFrameStores.push_back(std::move(job));
}

enum class StitchMode {
    LIVE_PREVIEW,  // IMU + local + feature only — no coarse3d
    REFINE         // full pairwise recovery including coarse search
};

/** Most recent frames safe to use as pairwise visual predecessors (up to maxCount). */
static void findTrustedFrames(int maxCount, std::vector<const StoredFrame*>& out) {
    out.clear();
    for (int i = static_cast<int>(sFrames.size()) - 1; i >= 0; --i) {
        if (!sFrames[i].poseTrusted) continue;
        out.push_back(&sFrames[i]);
        if ((int)out.size() >= maxCount) break;
    }
    if (out.empty() && !sFrames.empty()) {
        out.push_back(&sFrames.back());
    }
}

static const StoredFrame* findLastTrustedFrame() {
    for (int i = static_cast<int>(sFrames.size()) - 1; i >= 0; i--) {
        if (sFrames[i].poseTrusted) return &sFrames[i];
    }
    return sFrames.empty() ? nullptr : &sFrames.back();
}

/** q_pred = q_prev_visual * inv(q_prev_imu) * q_curr_imu (relative IMU delta). */
static Quat predictRelativeImuPose(const StoredFrame& prev, const Quat& qCurrImu) {
    Quat imuDelta = quatMul(quatConjugate(prev.imuQuat), qCurrImu);
    return quatMul(prev.fastCorrectedQuat, imuDelta);
}

// ── I0: overlap correspondence bank for pose-graph photometric cost ──
struct OverlapCorrespondence {
    int frameI = 0;
    int frameJ = 0;
    float uI = 0.f, vI = 0.f;
    float uJ = 0.f, vJ = 0.f;
};
static std::vector<OverlapCorrespondence> sOverlapBank;
static constexpr size_t OVERLAP_BANK_MAX = 50000;

static inline void sessionTanHalfFov(double& tanH, double& tanV) {
    tanH = std::tan(storedFovH * 0.5 * DEG2RAD);
    tanV = std::tan(storedFovV * 0.5 * DEG2RAD);
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
// Keep stored frames at capture resolution — do not downscale for export.
static constexpr int STORE_MAX_DIM = 8192;
// F2: cap longest frame side for live-path splat (full-res stored for refine/export).
static constexpr int LIVE_SPLAT_DIM = 1024;
// Diagnostic: rotation-only export until sharpness baseline is established.
static constexpr bool ENABLE_MOTION_WARP_REFINE = false;
static constexpr bool ENABLE_LIVE_MOTION_WARP = false;
// F2: skip equirect pixels on large live buffers (4096² tests / export-prep).
static constexpr int LIVE_EQ_SPLAT_STRIDE_THRESHOLD = 2048;
// Fast-path global translation warp budget (microseconds).
static constexpr int64_t FAST_WARP_BUDGET_US = 5000;
// G5: depth-weighted parallax scale for near geometry (real depth only).
static constexpr float DEPTH_PARALLAX_MIN_SCALE = 0.35f;
static constexpr float DEPTH_PARALLAX_MAX_SCALE = 1.65f;

// Per-frame alignment metrics
static int sFrameIndex = 0;
static int sLastOverlapCount = 0;
static int sLastTotalSamples = 0;
static float sLastAlignOffset = 0.f;
static Quat sLastCorrectedQuat = {0, 0, 0, 1};
static float sLastRollOffset = 0.f;
static float sLastResidualSSD = 0.f;
static int sLastRejected = 0;
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
/** DONE preview: feature-edge SO(3) graph only — no image SSD global iterations. */
static int sPoseGraphOnlyGlobal = 0;

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

/** Scale per-cell translations when splatting at higher resolution than warp estimate. */
static FrameWarp scaleWarpForResolution(
    const FrameWarp& warp, int refineW, int refineH, int exportW, int exportH) {
    if (refineW == exportW && refineH == exportH) return warp;
    FrameWarp out = warp;
    const float sx = (float)exportW / std::max(1, refineW);
    const float sy = (float)exportH / std::max(1, refineH);
    for (int y = 0; y < TILE_GAIN_N; y++) {
        for (int x = 0; x < TILE_GAIN_N; x++) {
            out.cells[y][x].tx *= sx;
            out.cells[y][x].ty *= sy;
        }
    }
    return out;
}

static inline const int32_t* splatPixelsForExport(const StoredFrame& f) {
    return !f.pxExport.empty() ? f.pxExport.data() : f.px.data();
}

static inline int splatWForExport(const StoredFrame& f) {
    return f.srcW > 0 ? f.srcW : f.w;
}

static inline int splatHForExport(const StoredFrame& f) {
    return f.srcH > 0 ? f.srcH : f.h;
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
#include "sphere_profile.h"
#include "sphere_feature_match.inl"
#include "sphere_global_align.inl"

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
    if (!eqCosLat.empty()) {
        w *= std::max(0.05f, eqCosLat[ey]);
    } else {
        const double eqYf = ey + 0.5;
        const double lat = (0.5 - eqYf / eqH) * PI;
        w *= static_cast<float>(std::max(0.05, std::cos(lat)));
    }
    int idx = ey * eqW + ex;
    if (!accumSkipLiveCoverage() && !eqCoverage.empty()) eqCoverage[idx] = 1;
    float before = accumW(idx);
    float after = before + w;
    if (!accumSkipLiveCoverage()) {
        if (before == 0.f && after > 0.f) sCoveredCount++;
        if (before <= SA_FILL_THRESH && after > SA_FILL_THRESH) {
            int cell = (ey * SA_GH / eqH) * SA_GW + (ex * SA_GW / eqW);
            if (sGridFilled[cell]++ == 0) sGridFilledCells++;
        }
    }
    accumR(idx) += r * w;
    accumG(idx) += g * w;
    accumB(idx) += b * w;
    accumW(idx) = after;
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

/** Bilinear downscale for F2 live splat (less aliasing than nearest). */
static bool downscaleArgbBilinear(
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
        const double sy = (y + 0.5) * srcH / dstH - 0.5;
        for (int x = 0; x < dstW; x++) {
            const double sx = (x + 0.5) * srcW / dstW - 0.5;
            float pr, pg, pb;
            samplePixelBilinear(src, srcW, srcH, (float)sx, (float)sy, pr, pg, pb);
            const int r = clampI((int)std::lround(pr), 0, 255);
            const int g = clampI((int)std::lround(pg), 0, 255);
            const int b = clampI((int)std::lround(pb), 0, 255);
            dst[(size_t)y * dstW + x] =
                static_cast<int32_t>(0xff000000 | (r << 16) | (g << 8) | b);
        }
    }
    return true;
}

/** Soft square edge falloff for overlap blend — keeps full frame area (no circular disc). */
static inline float previewCenterFeatherWeight(
    float sampleFx, float sampleFy, int frameW, int frameH) {
    const float cx = (frameW - 1) * 0.5f;
    const float cy = (frameH - 1) * 0.5f;
    const float halfW = std::max(cx, 1.f);
    const float halfH = std::max(cy, 1.f);
    const float nx = std::abs(sampleFx - cx) / halfW;
    const float ny = std::abs(sampleFy - cy) / halfH;
    const float edge = std::max(nx, ny);
    const float t = std::clamp((edge - 0.90f) / 0.10f, 0.f, 1.f);
    const float smooth = t * t * (3.f - 2.f * t);
    return 1.f - 0.30f * smooth;
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

    double axisX = 0.0, axisY = 0.0, axisZ = -1.0;
  if (nearestCenterSplatActive()) {
        quatRotate(qCorrected, 0.0, 0.0, -1.0, axisX, axisY, axisZ);
    }

    const bool haveTrig = eqSinLon.size() == static_cast<size_t>(eqW) &&
        eqSinLat.size() == static_cast<size_t>(eqH);

    auto splatEquirectPixel = [&](int ex, int ey) {
        double wx, wy, wz;
        if (haveTrig) {
            const float clat = eqCosLat[ey];
            wx = clat * eqSinLon[ex];
            wy = clat * eqCosLon[ex];
            wz = eqSinLat[ey];
        } else {
            const double eqXf = ex + 0.5;
            const double eqYf = ey + 0.5;
            equirectPixelToWorldRay(eqXf, eqYf, eqW, eqH, wx, wy, wz);
        }

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
        float fw = weightScale;
        if (edgeDistX < featherX || edgeDistY < featherY) {
            const double edgeFrac = std::min(
                edgeDistX / featherX, edgeDistY / featherY);
            fw *= static_cast<float>(0.5 - 0.5 * std::cos(edgeFrac * PI));
        }
        if (previewFeatherSplatActive()) {
            fw *= previewCenterFeatherWeight(sampleFx, sampleFy, frameW, frameH);
        }
        if (fw < 1e-6f) return;

        int idx = ey * eqW + ex;
        float pr, pg, pb;
        samplePixelBilinear(pixels, frameW, frameH, sampleFx, sampleFy, pr, pg, pb);
        if (sHaveCameraEv) {
            const float gn = (globalGainR + globalGainG + globalGainB) * (1.f / 3.f);
            if (gn < 0.92f && (pr >= 250.f || pg >= 250.f || pb >= 250.f) && accumWVal(idx) > 0.f) {
                return;
            }
            initSrgbLuts();
            pr = linToSrgb8(srgb8ToLin((int)(pr + 0.5f)) * globalGainR * gR);
            pg = linToSrgb8(srgb8ToLin((int)(pg + 0.5f)) * globalGainG * gG);
            pb = linToSrgb8(srgb8ToLin((int)(pb + 0.5f)) * globalGainB * gB);
        } else {
            pr *= gR * globalGainR;
            pg *= gG * globalGainG;
            pb *= gB * globalGainB;
        }
        if (nearestCenterSplatActive()) {
            const float viewDot = static_cast<float>(
                wx * axisX + wy * axisY + wz * axisZ);
            auto& centerDistSq = activeSplatCenterDistSq();
            if (accumWVal(idx) > 0.f && viewDot <= centerDistSq[idx]) {
                return;
            }
            if (accumWVal(idx) > 0.f) {
                accumR(idx) = accumG(idx) = accumB(idx) = 0.f;
                accumW(idx) = 0.f;
            }
            centerDistSq[idx] = viewDot;
        } else if (replaceOverlap && accumWVal(idx) > 0.f) {
            accumR(idx) = accumG(idx) = accumB(idx) = 0.f;
            accumW(idx) = 0.f;
        }

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
    distK2 = k2;
    distP1 = p1;
    distP2 = p2;
    int sz = eqW * eqH;
    eqR.assign(sz, 0.f);
    eqG.assign(sz, 0.f);
    eqB.assign(sz, 0.f);
    eqWeight.assign(sz, 0.f);
    eqCoverage.assign(sz, 0);
    eqBackR.assign(sz, 0.f);
    eqBackG.assign(sz, 0.f);
    eqBackB.assign(sz, 0.f);
    eqBackWeight.assign(sz, 0.f);
    sGeneration = 0;
    sRefineDirty.store(0, std::memory_order_relaxed);
    resetCoverageCountersLocked();
    rebuildEquirectTrigTablesLocked();
    resetPairwiseAcceptCountersLocked();
    clearStoredFramesLocked();
    gaResetLocked();
    sDeferredFeatureFrameIndices.clear();
    clearPendingFrameStores();
    sOverlapBank.clear();
    sFeatureEdges.clear();
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
    LOGI("Session FOV updated: %.2f x %.2f", fovH, fovV);
    return JNI_TRUE;
}

// Outcome of a single stitch attempt.
struct FrameOutcome {
    bool accepted = false;
    bool rejectedOverlap = false;
    bool poseTrusted = true;
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

/** Match overlap luminance with one RGB gain so AE jumps don't leave brightness seams. */
static void applyOverlapExposureNormalization(
    const std::vector<OverlapSample>& overlap,
    const Quat& qPose,
    double& gainR, double& gainG, double& gainB) {

    if (overlap.size() < 24) return;

    std::vector<float> exL, newL;
    exL.reserve(overlap.size());
    newL.reserve(overlap.size());

    for (const auto& s : overlap) {
        double wx, wy, wz;
        quatRotate(qPose, s.rx, s.ry, s.rz, wx, wy, wz);
        double lon = std::atan2(wx, wy);
        double lat = std::asin(std::max(-1.0, std::min(1.0, wz)));
        int eqX = ((int)((lon / PI + 1.0) * 0.5 * eqW) + eqW) % eqW;
        int eqY = clampI((int)((0.5 - lat / PI) * eqH), 0, eqH - 1);
        int idx = eqY * eqW + eqX;
        if (accumWVal(idx) <= 0.f) continue;

        float inv = 1.f / accumWVal(idx);
        float er = accumR(idx) * inv;
        float eg = accumG(idx) * inv;
        float eb = accumB(idx) * inv;
        exL.push_back(0.299f * er + 0.587f * eg + 0.114f * eb);
        newL.push_back(0.299f * s.newR + 0.587f * s.newG + 0.114f * s.newB);
    }
    if (exL.size() < 20) return;

    float medExL = medianOf(exL);
    float medNewL = medianOf(newL);
    if (medNewL > 6.f && medExL > 6.f) {
        const double lumGain = std::max(0.20, std::min(5.0, (double)(medExL / medNewL)));
        gainR = gainG = gainB = lumGain;
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
 *  allowReject  — allow frame-level rejection (quality / very-low-overlap).
 *  overlapGate  — if >0 and not the first frame, reject when the fraction of the
 *                 frame overlapping existing content is below this threshold.
 */
static void applyDepthParallaxWarp(
    FrameWarp& warp, const float* depthMap, int frameW, int frameH);

static double quatAngularDistRad(const Quat& a, const Quat& b);
static PairwiseScore evaluateLivePairwise(
    const int32_t* pixels, int w, int h,
    const Quat& qCand,
    const StoredFrame& prev, const Quat& qPrev,
    double tanH, double tanV,
    int step);

static PairwiseScore evaluateLivePairwiseGradZncc(
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

static float sampleStoredGradBilinear(const StoredFrame& f, float fx, float fy) {
    fx = std::max(1.f, std::min((float)(f.w - 2), fx));
    fy = std::max(1.f, std::min((float)(f.h - 2), fy));
    const int x0 = (int)std::floor(fx);
    const int y0 = (int)std::floor(fy);
    const int x1 = std::min(x0 + 1, f.w - 2);
    const int y1 = std::min(y0 + 1, f.h - 2);
    const float tfx = fx - x0;
    const float tfy = fy - y0;
    const float g00 = storedPixelGradMag(f, x0, y0);
    const float g10 = storedPixelGradMag(f, x1, y0);
    const float g01 = storedPixelGradMag(f, x0, y1);
    const float g11 = storedPixelGradMag(f, x1, y1);
    return (1.f - tfx) * (1.f - tfy) * g00 +
           tfx * (1.f - tfy) * g10 +
           (1.f - tfx) * tfy * g01 +
           tfx * tfy * g11;
}

/** Ray-space gradient ZNCC cost (ssd field holds 1−ZNCC). */
static PairwiseScore evaluateLivePairwiseGradZncc(
    const int32_t* pixels, int w, int h,
    const Quat& qCand,
    const StoredFrame& prev, const Quat& qPrev,
    double tanH, double tanV,
    int step) {

    PairwiseScore out;
    double sumA = 0.0, sumB = 0.0, sumAA = 0.0, sumBB = 0.0, sumAB = 0.0;
    int znccCount = 0;

    for (int fy = 0; fy < h; fy += step) {
        for (int fx = 0; fx < w; fx += step) {
            out.total++;
            const float gNew = framePixelGradMag(pixels, w, h, fx, fy);
            if (gNew <= 0.f) continue;

            double rx, ry, rz;
            pixelToRay(fx, fy, w, h, tanH, tanV, rx, ry, rz);
            double wx, wy, wz;
            quatRotate(qCand, rx, ry, rz, wx, wy, wz);
            float refFx, refFy;
            if (!worldRayToFramePixel(wx, wy, wz, qPrev, prev.w, prev.h, tanH, tanV, refFx, refFy)) {
                continue;
            }
            const float gRef = sampleStoredGradBilinear(prev, refFx, refFy);
            if (gRef <= 0.f) continue;

            out.count++;
            const double a = gNew;
            const double b = gRef;
            sumA += a;
            sumB += b;
            sumAA += a * a;
            sumBB += b * b;
            sumAB += a * b;
            znccCount++;
        }
    }
    out.overlap = out.total > 0 ? static_cast<double>(out.count) / out.total : 0.0;
    if (znccCount < pairwiseMinCount(out.total) || out.overlap < PAIRWISE_MIN_OVERLAP) {
        return out;
    }
    const double n = static_cast<double>(znccCount);
    const double va = sumAA - sumA * sumA / n;
    const double vb = sumBB - sumB * sumB / n;
    if (va < 1e-6 || vb < 1e-6) {
        out.ssd = 1.0;
        return out;
    }
    double zncc = (sumAB - sumA * sumB / n) / std::sqrt(va * vb);
    zncc = std::max(-1.0, std::min(1.0, zncc));
    out.ssd = 1.0 - zncc;
    return out;
}

static bool estimateLivePairwiseVisualRotation(
    const int32_t* pixels, int w, int h,
    const StoredFrame& prev,
    double tanH, double tanV,
    Quat& qCurrWorld,
    int& inliers) {

    FeatureRotationResult feat;
    if (!estimateLiveFeatureRotation(pixels, w, h, prev, tanH, tanV, feat)) {
        inliers = 0;
        return false;
    }
    inliers = feat.inliers;

    double mPrev[9];
    mat3FromQuat(prev.fastCorrectedQuat, mPrev);
    double mCurr[9];
    mat3Mul(mPrev, feat.R, mCurr);
    qCurrWorld = quatFromMat3(mCurr);
    return feat.confidence != FeatureRotationResult::LOW;
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
            auto lumAt = [&](int cx, int cy) -> float {
                int i = cy * eqW + cx;
                if (eqWeight[i] <= 0.f) return -1.f;
                const float inv = 1.f / eqWeight[i];
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
static bool findCoveredEquirectPixel(int ex, int ey, int& outIdx);

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
        int idx = 0;
        if (!findCoveredEquirectPixel(eqX, eqY, idx)) continue;
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

/** Tolerant occupancy: stride-2 live splat leaves sparse eqWeight; search neighborhood. */
static bool findCoveredEquirectPixel(int ex, int ey, int& outIdx) {
    const int radius = eqW > LIVE_EQ_SPLAT_STRIDE_THRESHOLD ? 2 : 1;
    for (int dy = -radius; dy <= radius; ++dy) {
        const int y = ey + dy;
        if (y < 0 || y >= eqH) continue;
        for (int dx = -radius; dx <= radius; ++dx) {
            const int x = (ex + dx + eqW) % eqW;
            const int idx = y * eqW + x;
            if (eqWeight[idx] > 0.f) {
                outIdx = idx;
                return true;
            }
        }
    }
    return false;
}

/** Sparse overlap samples for panorama overlap gate / equirect alignment (uses given pose). */
static void gatherOverlapSamples(
    const int32_t* pixels, int frameW, int frameH,
    const Quat& qPose,
    double tanH, double tanV,
    int sampleStep,
    std::vector<OverlapSample>& overlap,
    int& totalSamples,
    double& sumExR, double& sumExG, double& sumExB,
    double& sumNewR, double& sumNewG, double& sumNewB) {

    overlap.clear();
    overlap.reserve((frameW / sampleStep + 1) * (frameH / sampleStep + 1));
    totalSamples = 0;
    sumExR = sumExG = sumExB = 0;
    sumNewR = sumNewG = sumNewB = 0;

    for (int fy = 0; fy < frameH; fy += sampleStep) {
        for (int fx = 0; fx < frameW; fx += sampleStep) {
            totalSamples++;
            double rx, ry, rz;
            pixelToRay(fx, fy, frameW, frameH, tanH, tanV, rx, ry, rz);
            double wx, wy, wz;
            quatRotate(qPose, rx, ry, rz, wx, wy, wz);
            double lon = std::atan2(wx, wy);
            double lat = std::asin(std::max(-1.0, std::min(1.0, wz)));
            int eqX = ((int)((lon / PI + 1.0) * 0.5 * eqW) + eqW) % eqW;
            int eqY = clampI((int)((0.5 - lat / PI) * eqH), 0, eqH - 1);
            int idx = 0;
            if (!findCoveredEquirectPixel(eqX, eqY, idx)) continue;

            int32_t p = pixels[fy * frameW + fx];
            float nr = static_cast<float>((p >> 16) & 0xff);
            float ng = static_cast<float>((p >> 8) & 0xff);
            float nb = static_cast<float>(p & 0xff);

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

/**
 * Live capture: align current frame to immediate predecessor (not equirect).
 * IMU seed → feature fallback → coarse 3-D gradient search → RGB polish.
 */
static constexpr int64_t LIVE_COARSE_BUDGET_US = 40000;
/** Interactive preview: IMU + local + feature only (no coarse3d). */
static constexpr int64_t LIVE_PREVIEW_POSE_BUDGET_US = 20000;
/** Full pairwise recovery budget (refine / offline paths). */
static constexpr int64_t LIVE_PAIRWISE_FRAME_BUDGET_US = 50000;
static constexpr int LIVE_COARSE_SAMPLE_STEP = 56;
static constexpr int LIVE_COARSE_RGB_STEP = 22;
static constexpr int LIVE_COARSE_POLISH_STEP = 10;

static Quat featureWorldFromRef(
    const StoredFrame& ref, const FeatureRotationResult& feat) {
    double mPrev[9];
    mat3FromQuat(ref.fastCorrectedQuat, mPrev);
    double mFeat[9];
    mat3Mul(mPrev, feat.R, mFeat);
    return quatFromMat3(mFeat);
}

static Quat searchLivePairwiseTangent(
    const int32_t* pixels, int w, int h,
    Quat qSeed,
    const StoredFrame& prev,
    double tanH, double tanV,
    PairwiseScore* outBestScore = nullptr,
    int* outEvals = nullptr,
    SteadyClock::time_point frameBudgetStart = SteadyClock::time_point{},
    bool* outStrongFeature = nullptr,
    StitchMode mode = StitchMode::LIVE_PREVIEW) {

    const int64_t poseBudgetUs =
        mode == StitchMode::LIVE_PREVIEW
            ? LIVE_PREVIEW_POSE_BUDGET_US
            : LIVE_PAIRWISE_FRAME_BUDGET_US;

    auto frameBudgetExpired = [&]() {
        return frameBudgetStart != SteadyClock::time_point{} &&
            elapsedUs(frameBudgetStart) > poseBudgetUs;
    };

    const Quat qPrev = prev.fastCorrectedQuat;
    int evals = 0;
    FeatureRotationResult::Confidence featConfidence = FeatureRotationResult::LOW;
    const auto tPairwiseTotal = SteadyClock::now();
    int64_t usImu = 0, usLocal = 0, usFeature = 0, usCoarse = 0, usPolish = 0;

    auto pairwiseCost = [](const PairwiseScore& s) {
        return s.ssd + 0.8 * std::max(0.0, 0.25 - s.overlap);
    };
    auto pairwiseBetter = [&](const PairwiseScore& cand, const PairwiseScore& best) {
        if (!cand.valid()) return false;
        if (!best.valid()) return true;
        if (cand.overlap >= best.overlap + 0.10 && cand.ssd < best.ssd * 1.25) return true;
        if (cand.overlap < best.overlap - 0.10) return false;
        return pairwiseCost(cand) < pairwiseCost(best);
    };
    auto gradCost = [&](const PairwiseScore& s) {
        return s.ssd + 0.8 * std::max(0.0, 0.25 - s.overlap);
    };
    auto gradBetter = [&](const PairwiseScore& cand, const PairwiseScore& best) {
        if (!cand.valid()) return false;
        if (!best.valid()) return true;
        if (cand.overlap >= best.overlap + 0.10 && cand.ssd < best.ssd * 1.25) return true;
        if (cand.overlap < best.overlap - 0.10) return false;
        return gradCost(cand) < gradCost(best);
    };

    auto evalRgb = [&](const Quat& qCand, int step) -> PairwiseScore {
        ++evals;
        return evaluateLivePairwise(pixels, w, h, qCand, prev, qPrev, tanH, tanV, step);
    };
    auto evalGrad = [&](const Quat& qCand, int step) -> PairwiseScore {
        ++evals;
        return evaluateLivePairwiseGradZncc(pixels, w, h, qCand, prev, qPrev, tanH, tanV, step);
    };

    auto finishGood = [&](const PairwiseScore& score, const Quat& qOut,
                          const char* tag, const PairwiseScore& base) -> Quat {
        if (outBestScore) *outBestScore = score;
        if (outEvals) *outEvals = evals;
        LOGI("Pairwise %s: SSD %.1f overlap=%.1f%% evals=%d",
             tag,
             score.ssd, score.overlap * 100.0, evals);
        return qOut;
    };

  // LEVEL 0: relative IMU prediction (~1 eval).
    auto tSection = SteadyClock::now();
    const PairwiseScore base = evalRgb(qSeed, 32);
    usImu = elapsedUs(tSection);
    if (base.good()) {
        sFramesImuAccepted++;
        sphereProfileLogUs("pairwise_imu", usImu);
        sphereProfileLogUs("pairwise_total", elapsedUs(tPairwiseTotal));
        return finishGood(base, qSeed, "IMU", base);
    }

    Quat qBest = qSeed;
    PairwiseScore best = base;
    double bestDx = 0.0, bestDy = 0.0, bestDz = 0.0;

    auto adopt = [&](const PairwiseScore& s, const Quat& q, double dx, double dy, double dz) {
        if (pairwiseBetter(s, best)) {
            best = s;
            qBest = q;
            bestDx = dx;
            bestDy = dy;
            bestDz = dz;
        }
    };

    auto polishRgb = [&](Quat q, double initialDeg, int step) -> std::pair<Quat, PairwiseScore> {
        PairwiseScore bestS = evalRgb(q, step);
        for (double dDeg : {initialDeg, initialDeg * 0.5, 0.5}) {
            if (frameBudgetExpired()) break;
            const double d = dDeg * DEG2RAD;
            bool improved = true;
            while (improved && !frameBudgetExpired()) {
                improved = false;
                for (int axis = 0; axis < 3; axis++) {
                    for (double sign : {-1.0, 1.0}) {
                        double dx = 0.0, dy = 0.0, dz = 0.0;
                        if (axis == 0) dx = sign * d;
                        else if (axis == 1) dy = sign * d;
                        else dz = sign * d;
                        Quat cand = quatApplyTangentDelta(q, dx, dy, dz);
                        PairwiseScore s = evalRgb(cand, step);
                        if (!bestS.valid() && s.valid()) {
                            q = cand;
                            bestS = s;
                            improved = true;
                        } else if (pairwiseBetter(s, bestS)) {
                            q = cand;
                            bestS = s;
                            improved = true;
                        }
                    }
                }
            }
        }
        return std::pair<Quat, PairwiseScore>{q, bestS};
    };

    auto polishAndAdopt = [&](Quat qCenter, double radiusDeg, int step,
                              double dx, double dy, double dz) {
        auto polished = polishRgb(qCenter, radiusDeg, step);
        adopt(polished.second, polished.first, dx, dy, dz);
    };

    // LEVEL 1: small local coordinate descent (~20–40 evals), not a 729-point cube.
    tSection = SteadyClock::now();
    {
        double cx = 0.0, cy = 0.0, cz = 0.0;
        for (double radiusDeg : {2.0, 1.0, 0.5}) {
            const double step = 0.5 * DEG2RAD;
            bool improved = true;
            while (improved) {
                improved = false;
                for (int axis = 0; axis < 3; axis++) {
                    for (double sign : {-step, step}) {
                        double tx = cx, ty = cy, tz = cz;
                        if (axis == 0) tx += sign;
                        else if (axis == 1) ty += sign;
                        else tz += sign;
                        Quat qCand = quatApplyTangentDelta(qSeed, tx, ty, tz);
                        PairwiseScore s = evalRgb(qCand, 16);
                        if (pairwiseBetter(s, best)) {
                            best = s;
                            qBest = qCand;
                            cx = tx; cy = ty; cz = tz;
                            bestDx = tx; bestDy = ty; bestDz = tz;
                            improved = true;
                        }
                    }
                }
            }
        }
    }
    usLocal = elapsedUs(tSection);
    if (best.good()) {
        sFramesLocalAccepted++;
        sphereProfileLogUs("pairwise_local", usLocal);
        sphereProfileLogUs("pairwise_total", elapsedUs(tPairwiseTotal));
        return finishGood(best, qBest, "local", base);
    }

    // LEVEL 2: estimateLiveFeatureRotation() — isStrongConfidence() (HIGH) skips coarse3d.
    tSection = SteadyClock::now();
    {
        FeatureRotationResult featResult;
        if (estimateLiveFeatureRotation(pixels, w, h, prev, tanH, tanV, featResult)) {
            featConfidence = featResult.confidence;

            if (featResult.isStrongConfidence()) {
                usFeature = elapsedUs(tSection);
                Quat qStrong = featureWorldFromRef(prev, featResult);
                PairwiseScore outScore;
                Quat qOut = qStrong;
                if (mode == StitchMode::LIVE_PREVIEW) {
                    outScore = evalRgb(qStrong, LIVE_COARSE_POLISH_STEP);
                } else {
                    auto polished = polishRgb(qStrong, 1.0, LIVE_COARSE_POLISH_STEP);
                    outScore = polished.second;
                    qOut = polished.first;
                }
                if (!outScore.valid()) {
                    outScore.overlap = std::max(outScore.overlap, PAIRWISE_MIN_OVERLAP);
                    outScore.count = std::max(outScore.count, pairwiseMinCount(std::max(1, outScore.total)));
                    outScore.total = std::max(outScore.total, outScore.count);
                    if (outScore.ssd >= PAIRWISE_INF * 0.5) {
                        outScore.ssd = base.valid() ? base.ssd : PAIRWISE_GOOD_SSD;
                    }
                }
                if (outStrongFeature) *outStrongFeature = true;
                sFramesFeatureStrongAccepted++;
                sphereProfileLogUs("pairwise_feature", usFeature);
                sphereProfileLogUs("pairwise_total", elapsedUs(tPairwiseTotal));
                LOGI(
                    "Pairwise feature_strong: inliers=%d ratio=%.2f cells=%d "
                    "meanRes=%.6f overlap=%.1f%% photometric=%d (skip coarse)",
                    featResult.inliers, featResult.inlierRatio,
                    featResult.occupiedCells, featResult.meanResidual,
                    outScore.overlap * 100.0,
                    outScore.valid() ? 1 : 0);
                return finishGood(outScore, qOut, "feature_strong", base);
            }

            Quat qCandidate = qSeed;
            if (featResult.confidence == FeatureRotationResult::HIGH) {
                qCandidate = featureWorldFromRef(prev, featResult);
            } else if (featResult.confidence == FeatureRotationResult::MEDIUM) {
                const Quat qFeatureWorld = featureWorldFromRef(prev, featResult);
                const double correction = quatAngularDistRad(qSeed, qFeatureWorld);
                if (correction < 4.0 * DEG2RAD) {
                    qCandidate = qFeatureWorld;
                }
            }

            PairwiseScore featureScore = evalRgb(qCandidate, 16);
            adopt(featureScore, qCandidate, 0.0, 0.0, 0.0);

            if (featResult.confidence == FeatureRotationResult::MEDIUM &&
                mode != StitchMode::LIVE_PREVIEW) {
                polishAndAdopt(qCandidate, 2.0, 16, 0.0, 0.0, 0.0);
            }

            LOGI(
                "Pairwise feature: conf=%d inliers=%d ratio=%.2f cells=%d "
                "meanRes=%.6f maxRes=%.6f SSD=%.1f overlap=%.1f%%",
                static_cast<int>(featResult.confidence),
                featResult.inliers,
                featResult.inlierRatio,
                featResult.occupiedCells,
                featResult.meanResidual,
                featResult.maxResidual,
                best.valid() ? best.ssd : PAIRWISE_INF,
                best.overlap * 100.0);
        }
    }
    usFeature = elapsedUs(tSection);
    if (best.good()) {
        sFramesFeatureAccepted++;
        sphereProfileLogUs("pairwise_feature", usFeature);
        sphereProfileLogUs("pairwise_total", elapsedUs(tPairwiseTotal));
        return finishGood(best, qBest, "feature", base);
    }

    if (mode == StitchMode::LIVE_PREVIEW) {
        if (outBestScore) *outBestScore = best.valid() ? best : base;
        if (outEvals) *outEvals = evals;
        sphereProfileLogUs("pairwise_imu", usImu);
        sphereProfileLogUs("pairwise_local", usLocal);
        sphereProfileLogUs("pairwise_feature", usFeature);
        sphereProfileLogUs("pairwise_total", elapsedUs(tPairwiseTotal));
        __android_log_print(
            ANDROID_LOG_INFO, SPHERE_PROFILE_TAG,
            "SphereProfile preview_pose evals=%d overlap=%.1f valid=%d",
            evals, best.overlap * 100.0, best.valid() ? 1 : 0);
        if (best.valid() && pairwiseBetter(best, base)) return qBest;
        return qSeed;
    }

    if (frameBudgetExpired()) {
        if (outBestScore) *outBestScore = best.valid() ? best : base;
        if (outEvals) *outEvals = evals;
        sphereProfileLogUs("pairwise_total", elapsedUs(tPairwiseTotal));
        if (best.valid() && pairwiseBetter(best, base)) return qBest;
        return qSeed;
    }

    struct Hypothesis { Quat q; PairwiseScore score; double dx, dy, dz; };
    std::vector<Hypothesis> hypos;

    sFramesEnteredCoarse++;
    tSection = SteadyClock::now();
    const auto coarseBudgetStart = SteadyClock::now();
    bool coarseBudgetExpired = false;

    auto coarseBudgetExceeded = [&]() {
        if (frameBudgetExpired()) {
            coarseBudgetExpired = true;
            return true;
        }
        if (elapsedUs(coarseBudgetStart) > LIVE_COARSE_BUDGET_US) {
            coarseBudgetExpired = true;
            sFramesCoarseBudgetExpired++;
            return true;
        }
        return false;
    };

    auto runCoarse3D = [&](double yawDeg, double yawStepDeg,
                           double tiltDeg, double tiltStepDeg) {
        if (coarseBudgetExpired) return;
        hypos.clear();
        const double yawR = yawDeg * DEG2RAD;
        const double yawS = yawStepDeg * DEG2RAD;
        const double tiltR = tiltDeg * DEG2RAD;
        const double tiltS = tiltStepDeg * DEG2RAD;
        for (double dz = -yawR; dz <= yawR; dz += yawS) {
            if (coarseBudgetExceeded()) {
                coarseBudgetExpired = true;
                sFramesCoarseBudgetExpired++;
                break;
            }
            for (double ty = -tiltR; ty <= tiltR; ty += tiltS) {
                for (double tx = -tiltR; tx <= tiltR; tx += tiltS) {
                    Quat qCand = quatApplyTangentDelta(qSeed, tx, ty, dz);
                    PairwiseScore s = evalGrad(qCand, LIVE_COARSE_SAMPLE_STEP);
                    if (!s.valid()) continue;
                    hypos.push_back({qCand, s, tx, ty, dz});
                }
            }
        }
        if (hypos.empty()) return;
        std::sort(hypos.begin(), hypos.end(),
            [&](const Hypothesis& a, const Hypothesis& b) {
                return gradCost(a.score) < gradCost(b.score);
            });
        const int keep = std::min(4, static_cast<int>(hypos.size()));
        for (int i = 0; i < keep; i++) {
            PairwiseScore rgbS = evalRgb(hypos[i].q, LIVE_COARSE_RGB_STEP);
            adopt(rgbS, hypos[i].q, hypos[i].dx, hypos[i].dy, hypos[i].dz);
        }
    };

    static const std::vector<double> kGradRadiiFull = {4.0, 2.0, 1.0};
    static const std::vector<double> kGradRadiiLimited = {2.0, 1.0};
    const std::vector<double>& gradRadii =
        featConfidence == FeatureRotationResult::MEDIUM ? kGradRadiiLimited : kGradRadiiFull;

    if (featConfidence == FeatureRotationResult::MEDIUM) {
        runCoarse3D(12.0, 4.0, 6.0, 4.0);
    } else {
        runCoarse3D(30.0, 8.0, 8.0, 8.0);
    }

    auto coordDescentGrad = [&](double dx, double dy, double dz,
                                const std::vector<double>& radiiDeg) {
        Quat qGradBest = quatApplyTangentDelta(qSeed, dx, dy, dz);
        PairwiseScore gradBest = evalGrad(qGradBest, LIVE_COARSE_SAMPLE_STEP);
        double cx = dx, cy = dy, cz = dz;
        for (double radiusDeg : radiiDeg) {
            if (coarseBudgetExpired) return;
            for (double dDeg : {radiusDeg, radiusDeg * 0.5}) {
                const double d = dDeg * DEG2RAD;
                bool improved = true;
                while (improved && !coarseBudgetExpired) {
                    improved = false;
                    for (int axis = 0; axis < 3; axis++) {
                        for (double sign : {-1.0, 1.0}) {
                            if (coarseBudgetExceeded()) return;
                            double tx = cx, ty = cy, tz = cz;
                            if (axis == 0) tx += sign * d;
                            else if (axis == 1) ty += sign * d;
                            else tz += sign * d;
                            Quat qCand = quatApplyTangentDelta(qSeed, tx, ty, tz);
                            PairwiseScore s = evalGrad(qCand, LIVE_COARSE_SAMPLE_STEP);
                            if (gradBetter(s, gradBest)) {
                                gradBest = s;
                                qGradBest = qCand;
                                cx = tx; cy = ty; cz = tz;
                                improved = true;
                            }
                        }
                    }
                }
            }
        }
        PairwiseScore rgbS = evalRgb(qGradBest, LIVE_COARSE_RGB_STEP);
        adopt(rgbS, qGradBest, cx, cy, cz);
    };

    const int topN = std::min(4, static_cast<int>(hypos.size()));
    for (int i = 0; i < topN && !coarseBudgetExpired; i++) {
        coordDescentGrad(hypos[i].dx, hypos[i].dy, hypos[i].dz, gradRadii);
    }

    const double polishRadiusDeg =
        featConfidence == FeatureRotationResult::MEDIUM ? 2.0 : 1.0;
    if (!coarseBudgetExpired) {
        auto polished = polishRgb(qBest, polishRadiusDeg, LIVE_COARSE_POLISH_STEP);
        adopt(polished.second, polished.first, bestDx, bestDy, bestDz);
    }
    usCoarse = elapsedUs(tSection);
    usPolish = usCoarse;

    if (best.good()) {
        sphereProfileLogUs("pairwise_coarse3d", usCoarse);
        sphereProfileLogUs("pairwise_total", elapsedUs(tPairwiseTotal));
        return finishGood(best, qBest, "coarse3d", base);
    }

    // LEVEL 4: limited wide yaw fallback (same budget).
    if (!best.good() && !coarseBudgetExpired &&
        featConfidence != FeatureRotationResult::MEDIUM) {
        runCoarse3D(45.0, 12.0, 10.0, 10.0);
        for (int i = 0; i < std::min(2, static_cast<int>(hypos.size())) && !coarseBudgetExpired; i++) {
            coordDescentGrad(hypos[i].dx, hypos[i].dy, hypos[i].dz, kGradRadiiLimited);
        }
        if (!coarseBudgetExpired) {
            auto polished = polishRgb(qBest, 1.0, LIVE_COARSE_POLISH_STEP);
            adopt(polished.second, polished.first, bestDx, bestDy, bestDz);
        }
        usPolish += elapsedUs(tSection);
    }

    if (outBestScore) *outBestScore = best.valid() ? best : base;
    if (outEvals) *outEvals = evals;

    sphereProfileLogUs("pairwise_imu", usImu);
    sphereProfileLogUs("pairwise_local", usLocal);
    sphereProfileLogUs("pairwise_feature", usFeature);
    sphereProfileLogUs("pairwise_coarse3d", usCoarse);
    sphereProfileLogUs("pairwise_polish_wide", usPolish);
    sphereProfileLogUs("pairwise_total", elapsedUs(tPairwiseTotal));
    __android_log_print(
        ANDROID_LOG_INFO, SPHERE_PROFILE_TAG,
        "SphereProfile pairwise_evals=%d feat_conf=%d overlap=%.1f",
        evals, static_cast<int>(featConfidence), best.overlap * 100.0);
    if (sPairwiseTotalFrames % 8 == 0) {
        logPairwiseAcceptCountersLocked();
    }

    if (best.valid() && pairwiseBetter(best, base)) {
        LOGI("Pairwise pre-align: tx=%.2f° ty=%.2f° tz=%.2f° SSD %.1f->%.1f overlap=%.1f%% evals=%d",
             bestDx * RAD2DEG, bestDy * RAD2DEG, bestDz * RAD2DEG,
             base.valid() ? base.ssd : PAIRWISE_INF, best.ssd, best.overlap * 100.0, evals);
        return qBest;
    }
    return qSeed;
}

/** Benchmark live pairwise search against last stored frame. */
static void profileLivePairwiseLocked(
    const int32_t* px, int w, int h, Quat qSeed) {

    if (sFrames.empty()) {
        LOGW("SphereProfile live_pairwise_skip (no stored frames)");
        return;
    }
    double tanH, tanV;
    sessionTanHalfFov(tanH, tanV);
    const StoredFrame& prev = sFrames.back();

    auto t0 = SteadyClock::now();
    PairwiseScore score;
    int evals = 0;
    searchLivePairwiseTangent(px, w, h, qSeed, prev, tanH, tanV, &score, &evals);
    const int64_t totalUs = elapsedUs(t0);

    sphereProfileLogUs("bench_live_pairwise", totalUs);
    __android_log_print(
        ANDROID_LOG_INFO, SPHERE_PROFILE_TAG,
        "SphereProfile bench_live_pairwise_evals=%d overlap=%.1f ssd=%.1f valid=%d",
        evals, score.overlap * 100.0, score.valid() ? score.ssd : -1.0f,
        score.valid() ? 1 : 0);
}

static FrameOutcome processFrameLocked(
    const int32_t* pixels, int frameW, int frameH,
    Quat q, bool allowReject, float overlapGate,
    FrameWarp* warpInOut = nullptr,
    bool recencyComposite = false,
    const float* depthMap = nullptr,
    float forcedGainR = 0.f, float forcedGainG = 0.f, float forcedGainB = 0.f,
    bool exportRebuild = false) {

    FrameOutcome outcome{};
    const auto tFrameStart = SteadyClock::now();
    int64_t pairwiseUs = 0;
    PairwiseScore livePairScore{};

    FrameWarp localWarp{};
    initIdentityWarp(localWarp);
    FrameWarp* warp = warpInOut ? warpInOut : &localWarp;

    double qlen = std::sqrt(q.x*q.x + q.y*q.y + q.z*q.z + q.w*q.w);
    if (qlen < 1e-9) return outcome;
    q.x /= qlen; q.y /= qlen; q.z /= qlen; q.w /= qlen;

    const int frameNum = static_cast<int>(sFrames.size()) + 1;

    if (!exportRebuild) {
        static bool sLoggedInputFrameDim = false;
        if (!sLoggedInputFrameDim) {
            LOGI("INPUT FRAME %dx%d (store_max=%d live_splat_max=%d)",
                 frameW, frameH, STORE_MAX_DIM, LIVE_SPLAT_DIM);
            sLoggedInputFrameDim = true;
        }
    }

    double tanH, tanV;
    sessionTanHalfFov(tanH, tanV);

    // Pairwise visual alignment BEFORE panorama overlap gate (breaks IMU bootstrap).
    Quat qWork = q;
    bool hadPairReference = false;
    if (!exportRebuild) {
        std::vector<const StoredFrame*> trustedRefs;
        findTrustedFrames(1, trustedRefs);
        if (!trustedRefs.empty()) {
            hadPairReference = true;
            sPairwiseTotalFrames++;
            const auto tPairwise = SteadyClock::now();
            const auto pairwiseDeadlineStart = tPairwise;
            auto pairwiseBetter = [](const PairwiseScore& cand, const PairwiseScore& best) {
                if (!cand.valid()) return false;
                if (!best.valid()) return true;
                const double candCost = cand.ssd + 0.8 * std::max(0.0, 0.25 - cand.overlap);
                const double bestCost = best.ssd + 0.8 * std::max(0.0, 0.25 - best.overlap);
                if (cand.overlap >= best.overlap + 0.10 && cand.ssd < best.ssd * 1.25) return true;
                if (cand.overlap < best.overlap - 0.10) return false;
                return candCost < bestCost;
            };

            Quat qBest = predictRelativeImuPose(*trustedRefs[0], q);
            PairwiseScore scoreBest;
            int evalsBest = 0;
            bool haveBest = false;

            for (size_t ri = 0; ri < trustedRefs.size(); ++ri) {
                if (elapsedUs(pairwiseDeadlineStart) > LIVE_PREVIEW_POSE_BUDGET_US) break;
                if (ri > 0 && haveBest && scoreBest.good()) break;

                const StoredFrame* ref = trustedRefs[ri];
                Quat qPred = predictRelativeImuPose(*ref, q);
                PairwiseScore pairScore;
                int pairEvals = 0;
                bool refStrongFeature = false;
                Quat qCand = searchLivePairwiseTangent(
                    pixels, frameW, frameH, qPred, *ref, tanH, tanV,
                    &pairScore, &pairEvals, pairwiseDeadlineStart, &refStrongFeature,
                    StitchMode::LIVE_PREVIEW);

                if (refStrongFeature && ri > 0) {
                    sFramesMultiRefFeatureAccepted++;
                }

                if (refStrongFeature || !haveBest || pairwiseBetter(pairScore, scoreBest)) {
                    qBest = qCand;
                    scoreBest = pairScore;
                    evalsBest = pairEvals;
                    haveBest = pairScore.valid() || refStrongFeature;
                }

                LOGI("Frame %d pairwise ref[%zu]: overlap=%.1f%% SSD=%.1f evals=%d trusted=%d strong=%d",
                     frameNum, ri, pairScore.overlap * 100.0,
                     pairScore.valid() ? pairScore.ssd : PAIRWISE_INF, pairEvals,
                     ref->poseTrusted ? 1 : 0, refStrongFeature ? 1 : 0);
            }

            qWork = qBest;
            livePairScore = scoreBest;
            LOGI("Frame %d pairwise best: overlap=%.1f%% SSD=%.1f evals=%d",
                 frameNum, scoreBest.overlap * 100.0,
                 scoreBest.valid() ? scoreBest.ssd : PAIRWISE_INF, evalsBest);
            q = qWork;
            pairwiseUs = elapsedUs(tPairwise);
            sphereProfileLogUs("frame_pairwise", pairwiseUs);
        }
    }

  // ── Phase 1: sparse sampling for gain + overlap (corrected pose) ──
    const auto tPhase1 = SteadyClock::now();
    std::vector<OverlapSample> overlap;
    constexpr int SAMPLE_STEP = 8;
    int totalSamples = 0;
    double sumExR = 0, sumExG = 0, sumExB = 0;
    double sumNewR = 0, sumNewG = 0, sumNewB = 0;
    gatherOverlapSamples(
        pixels, frameW, frameH, qWork, tanH, tanV, SAMPLE_STEP,
        overlap, totalSamples, sumExR, sumExG, sumExB, sumNewR, sumNewG, sumNewB);

    double gainR = 1.0, gainG = 1.0, gainB = 1.0;
    {
        const double lumEx = 0.299 * sumExR + 0.587 * sumExG + 0.114 * sumExB;
        const double lumNew = 0.299 * sumNewR + 0.587 * sumNewG + 0.114 * sumNewB;
        if (overlap.size() > 30 && lumNew > 1.0 && lumEx > 1.0) {
            const double g = std::max(0.20, std::min(5.0, lumEx / lumNew));
            gainR = gainG = gainB = g;
        }
    }

    int64_t phase1Us = elapsedUs(tPhase1);

    // ── Overlap gate: require sufficient edge overlap with existing content ──
    // Forces the user to pan slowly enough that consecutive frames share area.
    float overlapRatio = totalSamples > 0
        ? static_cast<float>(overlap.size()) / totalSamples : 0.f;
    if (overlapGate > 0.f && !sFrames.empty() && overlapRatio < overlapGate) {
        sLastOverlapCount = static_cast<int>(overlap.size());
        sLastTotalSamples = totalSamples;
        sLastAlignOffset = 0.f;
        sLastResidualSSD = 0.f;
        sLastRollOffset = 0.f;
        sLastRejected = 1;
        LOGW("Frame %d gated: overlap %.1f%% < %.1f%% after pairwise (%zu samples)",
             frameNum, overlapRatio * 100.f, overlapGate * 100.f, overlap.size());
        outcome.accepted = false;
        outcome.rejectedOverlap = true;
        outcome.overlapCount = static_cast<int>(overlap.size());
        outcome.totalSamples = totalSamples;
        outcome.overlapRatio = overlapRatio;
        return outcome;
    }

    // ── Phase 2: live confidence + optional warp (rotation from pairwise pre-align) ──
    const auto tPhase2 = SteadyClock::now();
    Quat qCorrected = q;
    double finalSSD = hadPairReference ? PAIRWISE_INF : 0.0;
    bool frameRejected = false;
    bool tentativeSplatFrame = false;
    const bool usedFullRotAlign = exportRebuild;

    if (!exportRebuild && !recencyComposite) {
        if (livePairScore.valid()) {
            finalSSD = livePairScore.ssd;
        }
        if (hadPairReference && !livePairScore.good()) {
            tentativeSplatFrame = true;
            if (!livePairScore.valid()) {
                LOGW("Frame %d tentative: no valid visual pairwise match", frameNum);
            } else {
                LOGI("Frame %d tentative: pairwise overlap=%.1f%% SSD=%.1f",
                     frameNum, livePairScore.overlap * 100.0, finalSSD);
            }
        } else if (hadPairReference && livePairScore.valid() && overlapGate > 0.f &&
                   finalSSD > FAST_DUBIOUS_SSD_THRESH) {
            tentativeSplatFrame = true;
            LOGI("Frame %d tentative (no splat): SSD %.1f > thresh %.1f",
                 frameNum, finalSSD, FAST_DUBIOUS_SSD_THRESH);
        }

        if (!tentativeSplatFrame && ENABLE_LIVE_MOTION_WARP && overlap.size() > 40) {
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
                LOGW("Live warp over budget: %lldus > %lldus",
                     (long long)warpUs, (long long)FAST_WARP_BUDGET_US);
            }
        }

        if (allowReject && hadPairReference && !livePairScore.good() &&
            overlap.size() < 30) {
            frameRejected = true;
            LOGW("Frame %d REJECTED: pairwise not good (SSD=%.1f overlap=%.1f%%)",
                 frameNum, finalSSD, livePairScore.overlap * 100.0);
        } else if (allowReject && hadPairReference && livePairScore.valid() &&
                   finalSSD > REJECT_SSD_THRESH && !sFrames.empty()) {
            if (finalSSD <= REJECT_SSD_RELAXED && overlap.size() > 80) {
                LOGI("Frame %d fallback ACCEPT: SSD=%.1f (relaxed %.1f)",
                     frameNum, finalSSD, REJECT_SSD_RELAXED);
            } else if (!tentativeSplatFrame) {
                frameRejected = true;
                LOGW("Frame %d REJECTED: SSD=%.1f (thresh=%.1f)",
                     frameNum, finalSSD, REJECT_SSD_THRESH);
            }
        }
    }

    int64_t phase2Us = elapsedUs(tPhase2);

    // Per-frame metrics (sFrameIndex synced when frame is stored, not here).
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

    if (allowReject && !sFrames.empty() && sLastOverlapCount < 20) {
        LOGW("Frame %d: very low overlap (%d/%d)",
             frameNum, sLastOverlapCount, totalSamples);
        frameRejected = true;
        sLastRejected = 1;
    }

    // S12: robust overlap exposure match before splat (median luminance + per-channel).
    const bool useStoredExposure = forcedGainR > 1e-6f && !exportRebuild;
    if (useStoredExposure) {
        gainR = forcedGainR;
        gainG = forcedGainG > 1e-6f ? forcedGainG : forcedGainR;
        gainB = forcedGainB > 1e-6f ? forcedGainB : forcedGainR;
    } else if (!frameRejected && !overlap.empty() && !sFrames.empty()) {
        applyOverlapExposureNormalization(overlap, qCorrected, gainR, gainG, gainB);
    }

    // ── Phase 3: inverse spherical splat ──
    const auto tPhase3 = SteadyClock::now();
    int minSplatY = eqH, maxSplatY = 0;

    float tileGains[TILE_GAIN_N * TILE_GAIN_N * 3];
    for (int i = 0; i < TILE_GAIN_N * TILE_GAIN_N * 3; i++) tileGains[i] = 1.f;
    if (!overlap.empty() && !useStoredExposure) {
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
            if (accumWVal(idx) <= 0.f) continue;
            int tx = clampI(s.fx * TILE_GAIN_N / std::max(1, frameW), 0, TILE_GAIN_N - 1);
            int ty = clampI(s.fy * TILE_GAIN_N / std::max(1, frameH), 0, TILE_GAIN_N - 1);
            int t = ty * TILE_GAIN_N + tx;
            float inv = 1.f / accumWVal(idx);
            tExR[t] += accumR(idx) * inv;
            tExG[t] += accumG(idx) * inv;
            tExB[t] += accumB(idx) * inv;
            tNewR[t] += s.newR * gainR;
            tNewG[t] += s.newG * gainG;
            tNewB[t] += s.newB * gainB;
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
        const bool livePreviewPath = !exportRebuild && !usedFullRotAlign;
        const bool skipSplat = tentativeSplatFrame && !livePreviewPath;
        if (!skipSplat) {
        // Tentative frames are still splatted on live preview; skip only on export rebuild.
        float splatWeightScale = 1.f;

        // Export: minimal edge feather; fast-finish uses nearest-view pick (no overlap wash).
        const double featherFrac =
            exportRebuild ? (nearestCenterSplatActive() ? 0.03 : 0.05)
                          : (usedFullRotAlign ? 0.04 : 0.06);
        double featherX = frameW * featherFrac;
        double featherY = frameH * featherFrac;

        const float gExpR = static_cast<float>(gainR);
        const float gExpG = static_cast<float>(gainG);
        const float gExpB = static_cast<float>(gainB);

        const bool liveFastPath = !usedFullRotAlign && !exportRebuild;
        const int32_t* splatPixels = pixels;
        int splatW = frameW;
        int splatH = frameH;
        std::vector<int32_t> liveSplatPx;
        if (liveFastPath) {
            downscaleArgbBilinear(pixels, frameW, frameH, liveSplatPx, splatW, splatH, LIVE_SPLAT_DIM);
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
            const bool livePreviewPath = !exportRebuild && !usedFullRotAlign;
            const bool replaceOverlap =
                !exportRebuild && !usedFullRotAlign && !recencyComposite && !tentativeSplatFrame
                && sFrames.size() > 1;
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
        if (!nearestCenterSplatActive() && minSplatY <= maxSplatY) {
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

        }
    }

    int64_t phase3Us = elapsedUs(tPhase3);
    int64_t totalUs = elapsedUs(tFrameStart);

    LOGI("Frame %d: pairwise=%lldus P1=%lldus P2=%lldus P3+4=%lldus total=%lldus (%zu overlap, tentative=%d)",
         frameNum, (long long)pairwiseUs, (long long)phase1Us, (long long)phase2Us,
         (long long)phase3Us, (long long)totalUs,
         overlap.size(), tentativeSplatFrame ? 1 : 0);

    sphereProfileLogUs("frame_phase1_overlap", phase1Us);
    sphereProfileLogUs("frame_phase2_align", phase2Us);
    sphereProfileLogUs("frame_phase3_splat", phase3Us);
    sphereProfileLogUs("frame_total", totalUs);

    outcome.accepted = !frameRejected;
    outcome.poseTrusted = outcome.accepted && !tentativeSplatFrame;
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

/** Single adaptive live-capture path: fast preview pose + cheap splat only. */
static FrameOutcome processLivePreviewLocked(
    const int32_t* pixels, int frameW, int frameH,
    Quat q, bool allowReject, float overlapGate,
    FrameWarp* warpInOut = nullptr,
    const float* depthMap = nullptr,
    float cameraEv = 0.f) {
    float forcedR = 0.f, forcedG = 0.f, forcedB = 0.f;
    if (cameraEv > 1e-12f) {
        if (sRefCameraEv <= 0.f) sRefCameraEv = cameraEv;
        sHaveCameraEv = true;
        // Linear-light gain: YUV is sRGB-encoded, AE is ISO×t.
        double g = (double)sRefCameraEv / (double)cameraEv;
        g = std::max(0.125, std::min(8.0, g));
        forcedR = forcedG = forcedB = (float)g;
        LOGI("Camera EV gain=%.3f (ev=%.5g ref=%.5g)", forcedR, cameraEv, sRefCameraEv);
    }
    return processFrameLocked(
        pixels, frameW, frameH, q, allowReject, overlapGate,
        warpInOut, /*recencyComposite=*/false, depthMap,
        forcedR, forcedG, forcedB, /*exportRebuild=*/false);
}

static FrameOutcome processLiveFrameLocked(
    const int32_t* pixels, int frameW, int frameH,
    Quat q, bool allowReject, float overlapGate,
    FrameWarp* warpInOut = nullptr,
    const float* depthMap = nullptr,
    float cameraEv = 0.f) {
    return processLivePreviewLocked(
        pixels, frameW, frameH, q, allowReject, overlapGate, warpInOut, depthMap, cameraEv);
}

// Store refine-resolution copy (≤ STORE_MAX_DIM) plus full-res export copy when downscaled.
static void appendOverlapBankForNewFrame(int newIdx);

static void storeFrameForRefine(
    const int32_t* pixels, int frameW, int frameH,
    Quat imuQuat, Quat correctedQuat,
    const FrameWarp& warp,
    float expGainR, float expGainG, float expGainB,
    const float* depthIn = nullptr, int depthCount = 0,
    bool poseTrusted = true,
    bool deferFeatures = false) {
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
    if (outW != frameW || outH != frameH) {
        f.srcW = frameW;
        f.srcH = frameH;
        f.pxExport.resize((size_t)frameW * frameH);
        std::memcpy(f.pxExport.data(), pixels,
                    f.pxExport.size() * sizeof(int32_t));
    }
    f.refinedQuat = correctedQuat;
    f.imuQuat = imuQuat;
    f.fastCorrectedQuat = correctedQuat;
    f.expGainR = expGainR;
    f.expGainG = expGainG;
    f.expGainB = expGainB;
    f.locked = false;
    f.warp = warp;
    f.refined = false;
    f.poseTrusted = poseTrusted;
    const bool hasRealDepth = depthIn != nullptr && depthCount == frameW * frameH;
    f.hasRealDepth = hasRealDepth;
    f.px.resize((size_t)outW * outH);
    if (hasRealDepth) {
        f.depth.resize((size_t)outW * outH);
        for (int y = 0; y < outH; y++) {
            int sy = (outH == frameH) ? y : clampI((int)((y + 0.5) * frameH / outH), 0, frameH - 1);
            for (int x = 0; x < outW; x++) {
                int sx = (outW == frameW) ? x : clampI((int)((x + 0.5) * frameW / outW), 0, frameW - 1);
                f.px[(size_t)y * outW + x] = pixels[(size_t)sy * frameW + sx];
                f.depth[(size_t)y * outW + x] = depthIn[(size_t)sy * frameW + sx];
            }
        }
    } else {
        f.depth.clear();
        for (int y = 0; y < outH; y++) {
            int sy = (outH == frameH) ? y : clampI((int)((y + 0.5) * frameH / outH), 0, frameH - 1);
            for (int x = 0; x < outW; x++) {
                int sx = (outW == frameW) ? x : clampI((int)((x + 0.5) * frameW / outW), 0, frameW - 1);
                f.px[(size_t)y * outW + x] = pixels[(size_t)sy * frameW + sx];
            }
        }
    }
    sFrames.push_back(std::move(f));
    sRefineDirty.store(1, std::memory_order_relaxed);
    const int newIdx = (int)sFrames.size() - 1;
    sFrameIndex = static_cast<int>(sFrames.size());
    if (deferFeatures) {
        sDeferredFeatureFrameIndices.push_back(newIdx);
        if (!poseTrusted) {
            LOGI("Stored untrusted frame %d (deferred features)", newIdx);
        }
    } else {
        cacheStoredFrameFeatures(newIdx);
        if (poseTrusted) {
            appendOverlapBankForNewFrame(newIdx);
        } else {
            LOGI("Stored untrusted frame %d (skipped overlap bank)", newIdx);
        }
    }
}

static void flushDeferredFrameFeaturesLocked() {
    for (int idx : sDeferredFeatureFrameIndices) {
        if (idx < 0 || idx >= (int)sFrames.size()) continue;
        cacheStoredFrameFeatures(idx);
        if (sFrames[idx].poseTrusted) {
            appendOverlapBankForNewFrame(idx);
        }
    }
    sDeferredFeatureFrameIndices.clear();
}

/** Commit queued frame stores (downscale; BRIEF/overlap bank on refine). */
static void flushPendingFrameStoresLocked() {
    std::deque<PendingFrameStore> batch;
    {
        std::lock_guard<std::mutex> lock(sPendingStoreMutex);
        batch.swap(sPendingFrameStores);
    }
    for (const PendingFrameStore& job : batch) {
        const float* depthPtr = job.hasRealDepth ? job.depth.data() : nullptr;
        const int depthCount = job.hasRealDepth ? job.w * job.h : 0;
        storeFrameForRefine(
            job.px.data(), job.w, job.h,
            job.imuQuat, job.correctedQuat, job.warp,
            job.expGainR, job.expGainG, job.expGainB,
            depthPtr, depthCount, job.poseTrusted,
            /*deferFeatures=*/true);
    }
}

static void appendPairwiseOverlapToBank(
    int frameI, int frameJ, double tanH, double tanV) {

    if (frameI < 0 || frameJ < 0 ||
        frameI >= (int)sFrames.size() || frameJ >= (int)sFrames.size()) {
        return;
    }
    const StoredFrame& newer = sFrames[frameI];
    const StoredFrame& older = sFrames[frameJ];
    if (!newer.poseTrusted || !older.poseTrusted) return;
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
    if (!sFrames[newIdx].poseTrusted) return;
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

/** Photometric SSD over the overlap bank at session-calibrated intrinsics. */
static double evalOverlapBankSSD() {
    if (sOverlapBank.empty() || sFrames.size() < 2) return 1e18;
    double tanH, tanV;
    sessionTanHalfFov(tanH, tanV);
    double ssd = 0.0;
    int count = 0;
    for (const auto& c : sOverlapBank) {
        if (c.frameI < 0 || c.frameJ < 0 ||
            c.frameI >= (int)sFrames.size() || c.frameJ >= (int)sFrames.size()) {
            continue;
        }
        const StoredFrame& a = sFrames[c.frameI];
        const StoredFrame& b = sFrames[c.frameJ];
        double rx, ry, rz;
        pixelToRayFloat(c.uI, c.vI, a.w, a.h, tanH, tanV, rx, ry, rz);
        double wx, wy, wz;
        quatRotate(a.refinedQuat, rx, ry, rz, wx, wy, wz);
        float uB, vB;
        if (!worldRayToFramePixel(
                wx, wy, wz, b.refinedQuat, b.w, b.h, tanH, tanV, uB, vB)) {
            continue;
        }
        float rA, gA, bA, rB, gB, bB;
        samplePixelBilinear(a.px.data(), a.w, a.h, c.uI, c.vI, rA, gA, bA);
        samplePixelBilinear(b.px.data(), b.w, b.h, uB, vB, rB, gB, bB);
        double dr = rA - rB, dg = gA - gB, db = bA - bB;
        ssd += dr * dr + dg * dg + db * db;
        count++;
    }
    if (count < 12) return 1e18;
    return ssd / count;
}

// ── Global pairwise pose refine (ray-space, frame-to-frame, not vs muddy equirect) ──

static double pairwiseFrameRaySSD(
    const StoredFrame& src, const Quat& qSrc,
    const StoredFrame& ref, const Quat& qRef,
    double tanH, double tanV,
    int step = 10) {

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

/** Live capture: current frame pixels vs immediate predecessor (ray-space, not equirect). */
static PairwiseScore evaluateLivePairwise(
    const int32_t* pixels, int w, int h,
    const Quat& qCand,
    const StoredFrame& prev, const Quat& qPrev,
    double tanH, double tanV,
    int step) {

    PairwiseScore out;
    double ssd = 0.0;
    for (int fy = 0; fy < h; fy += step) {
        for (int fx = 0; fx < w; fx += step) {
            out.total++;
            double rx, ry, rz;
            pixelToRay(fx, fy, w, h, tanH, tanV, rx, ry, rz);
            double wx, wy, wz;
            quatRotate(qCand, rx, ry, rz, wx, wy, wz);
            float refFx, refFy;
            if (!worldRayToFramePixel(wx, wy, wz, qPrev, prev.w, prev.h, tanH, tanV, refFx, refFy)) {
                continue;
            }
            int32_t pSrc = pixels[(size_t)fy * w + fx];
            float sr = static_cast<float>((pSrc >> 16) & 0xff);
            float sg = static_cast<float>((pSrc >> 8) & 0xff);
            float sb = static_cast<float>(pSrc & 0xff);
            float rr, rg, rb;
            samplePixelBilinear(prev.px.data(), prev.w, prev.h, refFx, refFy, rr, rg, rb);
            double dr = sr - rr, dg = sg - rg, db = sb - rb;
            ssd += dr * dr + dg * dg + db * db;
            out.count++;
        }
    }
    out.overlap = out.total > 0 ? static_cast<double>(out.count) / out.total : 0.0;
    if (out.count < pairwiseMinCount(out.total) || out.overlap < PAIRWISE_MIN_OVERLAP) {
        return out;
    }
    out.ssd = ssd / out.count;
    return out;
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

/** Sparse pose graph SSD over overlap-bank neighbors (rotation only). */
static double sparseGraphPairwiseSSD(
    int frameIdx, const Quat& qCandidate,
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
        double sFwd = pairwiseFrameRaySSD(
            srcFrame, qCandidate, other, other.refinedQuat, tanH, tanV);
        double sRev = pairwiseFrameRaySSD(
            other, other.refinedQuat, srcFrame, qCandidate, tanH, tanV);
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
        double sFwd = pairwiseFrameRaySSD(
            srcFrame, qCandidate, anchor, anchor.refinedQuat, tanH, tanV);
        double sRev = pairwiseFrameRaySSD(
            anchor, anchor.refinedQuat, srcFrame, qCandidate, tanH, tanV);
        if (sFwd < 1e17 && sRev < 1e17) {
            total += 0.5 * (sFwd + sRev);
            pairs++;
        }
    }
    return pairs > 0 ? total / pairs : 1e18;
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
                    Quat qCand = quatApplyTangentDelta(qBase, dy, dp, dr);
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
                Quat qCand = quatApplyTangentDelta(qBase, dy, dp, dr);
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
        qOut = quatApplyTangentDelta(qBase, bestDy, bestDp, bestDr);
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
        if (baseSSD > 1e17) {
            qBase = predictRelativeImuPose(sFrames[i - 1], f.imuQuat);
            baseSSD = chainedNeighborRaySSD(i, qBase, tanH, tanV);
        }

        auto evalSSD = [&](const Quat& qCand) -> double {
            return chainedNeighborRaySSD(i, qCand, tanH, tanV);
        };
        Quat qNew = qBase;
        double bestSSD = baseSSD;
        if (searchPairwiseRotation(evalSSD, qBase, searchRad, coarseStep, qNew, bestSSD, 0.96)) {
            f.refinedQuat = qNew;
            adjusted++;
        }
    }

    if (n >= 4) {
        const int last = n - 1;
        StoredFrame& f = sFrames[last];
        Quat qBase = f.refinedQuat;
        double baseSSD = loopClosureRaySSD(last, qBase, tanH, tanV);
        if (baseSSD < 1e17) {
            auto evalLoop = [&](const Quat& qCand) -> double {
                return loopClosureRaySSD(last, qCand, tanH, tanV);
            };
            Quat qNew = qBase;
            double bestSSD = baseSSD;
            const double loopRad = std::min(searchRad, 10.0 * DEG2RAD);
            if (searchPairwiseRotation(evalLoop, qBase, loopRad, coarseStep, qNew, bestSSD, 0.94)) {
                f.refinedQuat = qNew;
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
        if (baseSSD > 1e17) {
            qBase = predictRelativeImuPose(sFrames[i - 1], f.imuQuat);
            baseSSD = neighborSSD(i, qBase);
        }

        const double imuGapRad = quatAngularDistRad(f.imuQuat, f.fastCorrectedQuat);
        const double searchRad =
            (sFastFinishRefine && imuGapRad > 8.0 * DEG2RAD) ? (10.0 * DEG2RAD)
            : (imuGapRad > 6.0 * DEG2RAD) ? (7.0 * DEG2RAD) : (5.0 * DEG2RAD);
        const double stepRad = searchRad > 6.0 * DEG2RAD ? 0.8 * DEG2RAD : 0.6 * DEG2RAD;

        auto evalAt = [&](const Quat& qCand) -> double {
            return neighborSSD(i, qCand);
        };

        double bestSSD = baseSSD;
        Quat qNew = qBase;
        if (searchPairwiseRotation(evalAt, qBase, searchRad, stepRad, qNew, bestSSD, 0.94)) {
            // Reject large hops unless SSD improves substantially (desk texture false matches).
            const double imuFastDeg =
                quatAngularDistRad(f.imuQuat, f.fastCorrectedQuat) * RAD2DEG;
            double maxStepRad = 4.0 * DEG2RAD;
            if (imuFastDeg > 4.0) maxStepRad = 8.0 * DEG2RAD;
            const double stepRad2 = quatAngularDistRad(qNew, qBase);
            const double ssdGain = (baseSSD - bestSSD) / std::max(baseSSD, 1.0);
            if (stepRad2 > maxStepRad && ssdGain < 0.12) continue;
            f.refinedQuat = qNew;
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
        Quat qCand = quatApplyTangentDelta(qBase, dyaw, dpitch, droll);
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
        f.refinedQuat = quatApplyTangentDelta(qBase, dy, dp, dr);
        LOGI("GN pose frame %d: yaw=%.2f pitch=%.2f roll=%.2f (SSD %.1f->%.1f)",
             frameIdx, dy * RAD2DEG, dp * RAD2DEG, dr * RAD2DEG, base, trial);
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
                    Quat qC = quatApplyTangentDelta(qBase, dy, dp, 0);
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
        Quat qCand = quatApplyTangentDelta(qBase, dyaw, dpitch, droll);
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
        f.refinedQuat = quatApplyTangentDelta(qBase, bestDyaw, bestDpitch, bestDroll);
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

// ── Targeted post-DONE polish (replaces brute-force globalRefinePosesLocked schedule) ──

static constexpr double TARGETED_PHOTO_SSD_THRESH = 400.0;
static constexpr int TARGETED_MIN_FEATURE_INLIERS = 80;
static constexpr int TARGETED_GN_ITERS = 3;
static constexpr double TARGETED_BROAD_SSD_THRESH = 2000.0;
static constexpr double PARALLAX_SKIP_SSD = 200.0;
static constexpr double PARALLAX_SIMPLE_SSD = 800.0;
static constexpr double MOTION_WARP_SKIP_PAIRWISE_SSD = 3000.0;

static bool frameNeedsPhotometricPolish(int frameIdx, double tanH, double tanV) {
    if (frameIdx <= 0 || frameIdx >= (int)sFrames.size()) return false;
    const StoredFrame& f = sFrames[frameIdx];
    if (f.locked) return false;
    const double ssd = sparseGraphPairwiseSSD(frameIdx, f.refinedQuat, tanH, tanV);
    const int inliers = frameFeatureInlierSum(frameIdx);
    if (ssd > TARGETED_PHOTO_SSD_THRESH) return true;
    if (inliers < TARGETED_MIN_FEATURE_INLIERS) return true;
    return false;
}

/** ±1° yaw/pitch, ±0.5° roll local search when GN alone is insufficient. */
static void refineFramePoseSmallSearch(int frameIdx, double tanH, double tanV) {
    if (frameIdx <= 0 || frameIdx >= (int)sFrames.size()) return;
    StoredFrame& f = sFrames[frameIdx];
    if (f.locked) return;

    Quat qBase = f.refinedQuat;
    auto evalDelta = [&](double dyaw, double dpitch, double droll) -> double {
        Quat qCand = quatApplyTangentDelta(qBase, dyaw, dpitch, droll);
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

    double bestSSD = evalDelta(0, 0, 0);
    double bestDy = 0, bestDp = 0, bestDr = 0;
    const double Ryp = 1.0 * DEG2RAD;
    const double Sr = 0.25 * DEG2RAD;
    for (double dp = -Ryp; dp <= Ryp; dp += Sr) {
        for (double dy = -Ryp; dy <= Ryp; dy += Sr) {
            double s = evalDelta(dy, dp, bestDr);
            if (s < bestSSD) {
                bestSSD = s;
                bestDy = dy;
                bestDp = dp;
            }
        }
    }
    const double Rr = 0.5 * DEG2RAD;
    for (double dr = -Rr; dr <= Rr; dr += Sr) {
        double s = evalDelta(bestDy, bestDp, dr);
        if (s < bestSSD) {
            bestSSD = s;
            bestDr = dr;
        }
    }
    const double baseline = evalDelta(0, 0, 0);
    if (bestSSD < baseline * 0.995) {
        f.refinedQuat = quatApplyTangentDelta(qBase, bestDy, bestDp, bestDr);
        LOGI("Small search frame %d: yaw=%.2f pitch=%.2f roll=%.2f SSD %.1f->%.1f",
             frameIdx, bestDy * RAD2DEG, bestDp * RAD2DEG, bestDr * RAD2DEG,
             baseline, bestSSD);
    }
}

/** GN + small/broad search only on frames that need photometric polish. */
static int refineTargetedPhotometricLocked() {
    const int n = (int)sFrames.size();
    if (n < 2) return 0;
    if (gaValidForCurrentFramesLocked()) {
        LOGI("Targeted photometric: skipped (global align valid)");
        return 0;
    }
    double tanH, tanV;
    sessionTanHalfFov(tanH, tanV);
    auto t0 = SteadyClock::now();
    int polished = 0;
    for (int i = 1; i < n; i++) {
        if (!frameNeedsPhotometricPolish(i, tanH, tanV)) continue;
        const double baseSSD = sparseGraphPairwiseSSD(i, sFrames[i].refinedQuat, tanH, tanV);
        for (int iter = 0; iter < TARGETED_GN_ITERS; iter++) {
            refineFramePoseGaussNewton(i, tanH, tanV);
        }
        const double afterGN = sparseGraphPairwiseSSD(i, sFrames[i].refinedQuat, tanH, tanV);
        if (afterGN > TARGETED_PHOTO_SSD_THRESH && afterGN >= baseSSD * 0.92) {
            refineFramePoseSmallSearch(i, tanH, tanV);
        }
        const double afterSmall =
            sparseGraphPairwiseSSD(i, sFrames[i].refinedQuat, tanH, tanV);
        if (afterSmall > TARGETED_BROAD_SSD_THRESH) {
            refineFramePoseGlobal(i, tanH, tanV);
        }
        polished++;
    }
    LOGI("Targeted photometric: %d/%d frames in %lldus",
         polished, n, (long long)elapsedUs(t0));
    if (polished > 0) rebuildOverlapBankLocked();
    return polished;
}

/** One cheap SO(3) reconciliation after local corrections. */
static bool refineFeatureGraphReconcileLocked() {
    if ((int)sFrames.size() < 2) return false;
    if (gaValidForCurrentFramesLocked()) return false;
    auto t0 = SteadyClock::now();
    flushDeferredFrameFeaturesLocked();
    if (gaGlobalAlignLocked()) {
        rebuildOverlapBankLocked();
        LOGI("Feature graph reconcile: global align in %lldus", (long long)elapsedUs(t0));
        return true;
    }
    buildFeatureEdgesLocked();
    const int adjusted = seedPosesFromFeatureGraphLocked(/*skipOverlapRebuild=*/true);
    for (auto& f : sFrames) {
        f.fastCorrectedQuat = f.refinedQuat;
    }
    LOGI("Feature graph reconcile: %d frames adjusted in %lldus",
         adjusted, (long long)elapsedUs(t0));
    return adjusted > 0;
}

/** Parallax warp only when rotation-only residual is still non-trivial. */
static int refineConditionalParallaxLocked() {
    const int n = (int)sFrames.size();
    if (n < 2) return 0;
    if (gaValidForCurrentFramesLocked()) return 0;
    double tanH, tanV;
    sessionTanHalfFov(tanH, tanV);
    auto t0 = SteadyClock::now();
    int warped = 0;
    int skipped = 0;
    for (int i = 1; i < n; i++) {
        StoredFrame& f = sFrames[i];
        if (f.locked) continue;
        const double pairwiseSSD = sparseGraphPairwiseSSD(i, f.refinedQuat, tanH, tanV);
        if (pairwiseSSD < PARALLAX_SKIP_SSD) {
            skipped++;
            continue;
        }
        if (pairwiseSSD > MOTION_WARP_SKIP_PAIRWISE_SSD) {
            skipped++;
            continue;
        }
        std::vector<OverlapSample> overlap;
        buildOverlapSamplesFromEquirect(i, overlap);
        if (overlap.size() < 20) continue;

        const float* depthPtr = f.hasRealDepth ? f.depth.data() : nullptr;
        const double gainR = 1.0, gainG = 1.0, gainB = 1.0;
        estimateFastGlobalWarp(
            overlap, f.refinedQuat, gainR, gainG, gainB,
            f.w, f.h, tanH, tanV, f.warp, depthPtr);
        if (pairwiseSSD >= PARALLAX_SIMPLE_SSD) {
            double warpSSD = 0.0;
            estimatePerCellWarp(
                overlap, f.refinedQuat, gainR, gainG, gainB,
                f.w, f.h, tanH, tanV, f.warp, warpSSD,
                /*seedFromExisting=*/true, depthPtr);
        }
        if (depthPtr != nullptr) {
            applyDepthParallaxWarp(f.warp, depthPtr, f.w, f.h);
        }
        warped++;
    }
    LOGI("Conditional parallax: warped %d skipped %d in %lldus",
         warped, skipped, (long long)elapsedUs(t0));
    if (warped > 0) rebuildOverlapBankLocked();
    return warped;
}

/** Full state snapshot for transactional global optimization rollback. */
struct GlobalOptSnapshot {
    std::vector<Quat> refinedQuat;
    std::vector<FrameWarp> warps;
};

static GlobalOptSnapshot captureGlobalOptSnapshotLocked() {
    GlobalOptSnapshot snap;
    for (const auto& f : sFrames) {
        snap.refinedQuat.push_back(f.refinedQuat);
        snap.warps.push_back(f.warp);
    }
    return snap;
}

static void restoreGlobalOptSnapshotLocked(const GlobalOptSnapshot& snap) {
    if (snap.refinedQuat.size() != sFrames.size()) return;
    for (size_t i = 0; i < sFrames.size(); i++) {
        sFrames[i].refinedQuat = snap.refinedQuat[i];
        sFrames[i].warp = snap.warps[i];
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
        const auto tPass = SteadyClock::now();
        for (int i = 1; i < n; i++) {
            refineFramePoseGlobal(i, tanH, tanV);
        }
        for (int i = 1; i < n; i++) {
            refineFramePoseGaussNewton(i, tanH, tanV);
        }
        sphereProfileLogUs("global_iteration", elapsedUs(tPass));
        LOGI("Global pose refine pass %d/%d in %lldus", pass + 1, passes,
             (long long)elapsedUs(tPass));
    }
    // Extra GN polish over overlap-bank graph.
    if (!fastGlobal && sOverlapBank.size() >= 24) {
        for (int i = 1; i < n; i++) {
            refineFramePoseGaussNewton(i, tanH, tanV);
        }
        rebuildOverlapBankLocked();
    }
    LOGI("Global pose refine (%s): %d frames, %d passes in %lldus",
         fastGlobal ? "rotation-only" : "rotation+warp", n, passes, (long long)elapsedUs(t0));
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
    if (!f.hasRealDepth || f.depth.empty()) return;
    applyDepthParallaxWarp(f.warp, f.depth.data(), f.w, f.h);
}

/**
 * G2/G3/G5 motion refine after global rotation: re-estimate translation +
 * per-cell warp from overlap, depth-weighted when available.
 */
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

        const float* depthPtr = f.hasRealDepth ? f.depth.data() : nullptr;
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
        f.poseTrusted = true;
        initIdentityWarp(f.warp);
    }
    invalidateEquirectFreshLocked();
}

static void ensureBackBufferSizeLocked() {
    const int sz = eqW * eqH;
    if (sz <= 0) return;
    if ((int)eqBackR.size() != sz) {
        eqBackR.assign(sz, 0.f);
        eqBackG.assign(sz, 0.f);
        eqBackB.assign(sz, 0.f);
        eqBackWeight.assign(sz, 0.f);
    }
}

static void swapDisplayWithRefinedBackLocked() {
    if (eqBackR.size() != eqR.size()) return;
    eqR.swap(eqBackR);
    eqG.swap(eqBackG);
    eqB.swap(eqBackB);
    eqWeight.swap(eqBackWeight);
    recomputeCoverageCountersLocked();
    rebuildEquirectGradMagLocked();
    markEquirectFreshLocked();
}

static void revertRefinedQuatsLocked(const std::vector<Quat>& backup) {
    for (size_t i = 0; i < backup.size() && i < sFrames.size(); ++i) {
        sFrames[i].refinedQuat = backup[i];
    }
}

/**
 * Full-res panorama rebuild into caller buffers (no display buffer mutation).
 * Used for background refine commit via atomic swap.
 */
static int rebuildPanoramaFromFrames(
    const std::vector<StoredFrame>& frames,
    std::vector<float>& outR, std::vector<float>& outG,
    std::vector<float>& outB, std::vector<float>& outWeight,
    bool nearestCenter, bool forceStoredExposure, bool previewFeather = false) {

    const int sz = eqW * eqH;
    if (sz <= 0 || frames.empty()) return 0;

    outR.assign(sz, 0.f);
    outG.assign(sz, 0.f);
    outB.assign(sz, 0.f);
    outWeight.assign(sz, 0.f);

    std::vector<float> localCenterDistSq;
    SplatAccumContext accumCtx;
    accumCtx.r = &outR;
    accumCtx.g = &outG;
    accumCtx.b = &outB;
    accumCtx.weight = &outWeight;
    accumCtx.skipLiveCoverage = true;
    accumCtx.nearestCenterSplat = nearestCenter;
    accumCtx.previewFeatherSplat = previewFeather && !nearestCenter;
    if (nearestCenter) {
        localCenterDistSq.assign(sz, -1e18f);
        accumCtx.splatCenterDistSq = &localCenterDistSq;
    }
    ScopedSplatAccum accumGuard(accumCtx);

    int aligned = 0;
    for (const StoredFrame& f : frames) {
        const float* depthPtr = f.hasRealDepth ? f.depth.data() : nullptr;
        const float forcedR = forceStoredExposure ? f.expGainR : 0.f;
        const float forcedG = forceStoredExposure ? f.expGainG : 0.f;
        const float forcedB = forceStoredExposure ? f.expGainB : 0.f;
        FrameWarp warpCopy = f.warp;
        FrameOutcome o = processFrameLocked(
            f.px.data(), f.w, f.h, f.refinedQuat,
            /*allowReject=*/false, /*overlapGate=*/0.f,
            &warpCopy, /*recencyComposite=*/false, depthPtr,
            forcedR, forcedG, forcedB,
            /*exportRebuild=*/true);
        if (!f.locked) aligned++;
        (void)o;
    }

    return aligned;
}

/** Global pose graph + optional motion warp (refine only — never on live preview path). */
static bool refinePoseGraphLocked(bool applyGlobalPose, bool* globalPoseRevertedOut) {
    if (globalPoseRevertedOut) *globalPoseRevertedOut = false;
    flushDeferredFrameFeaturesLocked();

    if (applyGlobalPose) {
        const double maxImuFastDeg = sessionMaxImuFastGapDeg();
        for (auto& f : sFrames) {
            f.refinedQuat = f.fastCorrectedQuat;
        }
        LOGI("Global refine seed: fastCorrected (max imu-fast gap %.1f deg, fastFinish=%d poseGraphOnly=%d)",
             maxImuFastDeg, sFastFinishRefine ? 1 : 0, sPoseGraphOnlyGlobal ? 1 : 0);

        if (sPoseGraphOnlyGlobal && gaGlobalAlignLocked()) {
            sLastRefineSsdBefore = 0.f;
            sLastRefineSsdAfter = 0.f;
            return true;
        }
        if (sPoseGraphOnlyGlobal) {
            auto tEdges = SteadyClock::now();
            buildFeatureEdgesLocked();
            const int64_t edgesUs = elapsedUs(tEdges);
            sphereProfileLogUs("build_feature_edges", edgesUs);

            auto tSolve = SteadyClock::now();
            const int adjusted = seedPosesFromFeatureGraphLocked(/*skipOverlapRebuild=*/true);
            const int64_t solveUs = elapsedUs(tSolve);
            sphereProfileLogUs("pose_graph_solve", solveUs);

            for (auto& f : sFrames) {
                f.fastCorrectedQuat = f.refinedQuat;
            }
            LOGI("Pose graph only: %zu edges, %d frames adjusted (edges %lldus solve %lldus)",
                 sFeatureEdges.size(), adjusted, (long long)edgesUs, (long long)solveUs);
            sLastRefineSsdBefore = 0.f;
            sLastRefineSsdAfter = 0.f;
            return true;
        }

        auto tOverlap = SteadyClock::now();
        rebuildOverlapBankLocked();
        sphereProfileLogUs("build_overlap_bank", elapsedUs(tOverlap));
        if (sOverlapBank.size() >= 20) {
            sLastRefineSsdBefore = static_cast<float>(evalOverlapBankSSD());
        }
        const bool useFeatureGraph = !sFastFinishRefine && maxImuFastDeg >= 8.0;
        if (useFeatureGraph) {
            auto tFeat = SteadyClock::now();
            buildFeatureEdgesLocked();
            sphereProfileLogUs("build_feature_edges", elapsedUs(tFeat));
            const int featureSeeded = seedPosesFromFeatureGraphLocked();
            sphereProfileLogUs("feature_seed", elapsedUs(tFeat));
            if (featureSeeded < 2) {
                auto tChain = SteadyClock::now();
                seedPosesPairwiseChainLocked();
                sphereProfileLogUs("pairwise_chain_seed", elapsedUs(tChain));
            }
        } else {
            LOGI("Global refine: skip feature graph (fastFinish imuGap %.1f deg)", maxImuFastDeg);
        }
        const GlobalOptSnapshot preGlobalSnap = captureGlobalOptSnapshotLocked();

        auto tGlobal = SteadyClock::now();
        globalRefinePosesLocked();
        sphereProfileLogUs("global_pose_iterations", elapsedUs(tGlobal));

        if (ENABLE_MOTION_WARP_REFINE &&
            sGlobalRefinePassesOverride <= 0 && !sFastFinishRefine) {
            auto tWarp = SteadyClock::now();
            refineMotionWarpsLocked();
            sphereProfileLogUs("motion_warp", elapsedUs(tWarp));
        }
        auto tPostOverlap = SteadyClock::now();
        rebuildOverlapBankLocked();
        sphereProfileLogUs("post_overlap_bank", elapsedUs(tPostOverlap));
        if (sOverlapBank.size() >= 20) {
            sLastRefineSsdAfter = static_cast<float>(evalOverlapBankSSD());
            if (sGlobalRefinePassesOverride <= 0 &&
                sLastRefineSsdAfter > sLastRefineSsdBefore * 0.995f) {
                restoreGlobalOptSnapshotLocked(preGlobalSnap);
                if (globalPoseRevertedOut) *globalPoseRevertedOut = true;
                LOGI("Global optimization reverted (SSD %.4f->%.4f)",
                     sLastRefineSsdBefore, sLastRefineSsdAfter);
            }
        }
    } else {
        for (auto& f : sFrames) {
            f.refinedQuat = f.fastCorrectedQuat;
        }
    }
    return true;
}

/** Pre-finish idle: BRIEF features + relative rotation edges (no pose solve / rebuild). */
static void buildFeatureGraphCacheLocked() {
    if ((int)sFrames.size() < 2) return;
    if (gaValidForCurrentFramesLocked()) return;
    auto t0 = SteadyClock::now();
    buildFeatureEdgesLocked();
    sphereProfileLogUs("prefinish_graph_cache", elapsedUs(t0));
    LOGI("Feature graph cache: %zu edges in %lldus", sFeatureEdges.size(),
         (long long)elapsedUs(t0));
}

/** Rebuild refined panorama into back buffer (display untouched until swap). */
static int rebuildRefinedPanoramaLocked(bool applyGlobalPose) {
    ensureBackBufferSizeLocked();
    std::vector<StoredFrame> framesCopy = sFrames;
    std::vector<float> backR, backG, backB, backWeight;
    const bool nearestCenter = !sFastFinishRefine;
    const int aligned = rebuildPanoramaFromFrames(
        framesCopy, backR, backG, backB, backWeight,
        nearestCenter, applyGlobalPose);
    eqBackR = std::move(backR);
    eqBackG = std::move(backG);
    eqBackB = std::move(backB);
    eqBackWeight = std::move(backWeight);
    return aligned;
}

/** Re-splat all stored frames after pose or intrinsic changes (layer 2/3 rebuild). */
static int rebuildEquirectFromStoredFramesLocked(
    bool recencyComposite = false, bool forceStoredExposure = false,
    bool useFullResSource = false) {
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
        const int splatW = useFullResSource ? splatWForExport(f) : f.w;
        const int splatH = useFullResSource ? splatHForExport(f) : f.h;
        const int32_t* splatPx = useFullResSource ? splatPixelsForExport(f) : f.px.data();
        const float* depthPtr = f.hasRealDepth ? f.depth.data() : nullptr;
        const bool applyStoredExposure = recencyComposite || forceStoredExposure;
        const float forcedR = applyStoredExposure ? f.expGainR : 0.f;
        const float forcedG = applyStoredExposure ? f.expGainG : 0.f;
        const float forcedB = applyStoredExposure ? f.expGainB : 0.f;
        FrameWarp warpCopy = f.warp;
        if (useFullResSource && splatW != f.w && splatH != f.h) {
            warpCopy = scaleWarpForResolution(f.warp, f.w, f.h, splatW, splatH);
        }
        FrameWarp* warpPtr = useFullResSource ? &warpCopy : &f.warp;
        FrameOutcome o = processFrameLocked(
            splatPx, splatW, splatH, f.refinedQuat,
            /*allowReject=*/false, /*overlapGate=*/0.f,
            warpPtr, recencyComposite, depthPtr,
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

/** Final export: nearest-view compositing at full stored resolution (no overlap averaging). */
static int rebuildEquirectNearestCenterExportLocked(bool forceStoredExposure = false) {
    eqSplatCenterDistSq.assign((size_t)eqW * eqH, -1e18f);
    SplatAccumContext accumCtx;
    accumCtx.nearestCenterSplat = true;
    accumCtx.splatCenterDistSq = &eqSplatCenterDistSq;
    ScopedSplatAccum accumGuard(accumCtx);
    if (!ENABLE_MOTION_WARP_REFINE) {
        for (auto& f : sFrames) {
            initIdentityWarp(f.warp);
        }
    }
    return rebuildEquirectFromStoredFramesLocked(
        false, forceStoredExposure, /*useFullResSource=*/true);
}

/** L2b export: deterministic resplat from globally refined poses (nearest-center). */
static int rebuildEquirectFullAlignLocked() {
    return rebuildEquirectNearestCenterExportLocked(true);
}

/** Truth export: resplat stored poses with nearest-center overlap (no photometric align). */
static int rebuildEquirectExportNearestCenterLocked() {
    return rebuildEquirectNearestCenterExportLocked(false);
}

/** DONE export: resplat at full resolution without re-running photometric align. */
static bool prepareExportEquirectLocked(int outW, int outH) {
    if (outW <= 0 || outH <= 0 || sFrames.empty()) return false;
    if (sEquirectExportFresh && outW == eqW && outH == eqH && !sFastFinishRefine) {
        LOGI("Export prep: skip resplat (%dx%d export-fresh)", eqW, eqH);
        return true;
    }
    if (gaValidForCurrentFramesLocked()) {
        if (outW != eqW || outH != eqH) {
            eqW = outW;
            eqH = outH;
            eqCoverage.assign((size_t)eqW * eqH, 0);
            eqSplatCenterDistSq.assign((size_t)eqW * eqH, -1e18f);
            rebuildEquirectTrigTablesLocked();
        }
        gaComposeEquirectLocked(eqW, eqH);
        markEquirectExportFreshLocked();
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
        eqCoverage.assign(sz, 0);
        eqSplatCenterDistSq.assign(sz, -1e18f);
        rebuildEquirectTrigTablesLocked();
    } else {
        std::fill(eqR.begin(), eqR.end(), 0.f);
        std::fill(eqG.begin(), eqG.end(), 0.f);
        std::fill(eqB.begin(), eqB.end(), 0.f);
        std::fill(eqWeight.begin(), eqWeight.end(), 0.f);
        if (!eqCoverage.empty()) std::fill(eqCoverage.begin(), eqCoverage.end(), 0);
        eqSplatCenterDistSq.assign((size_t)eqW * eqH, -1e18f);
    }
    resetCoverageCountersLocked();
    sFrameIndex = 0;
    sLastRejected = 0;
    sLastRollOffset = 0.f;
    if (!ENABLE_MOTION_WARP_REFINE) {
        for (auto& f : sFrames) {
            initIdentityWarp(f.warp);
        }
    }
    const int aligned = rebuildEquirectNearestCenterExportLocked(false);
    markEquirectExportFreshLocked();
    LOGI("Export prep: %dx%d nearest-center full-res resplat %zu frames (%d aligned) in %lldus",
         eqW, eqH, sFrames.size(), aligned, (long long)elapsedUs(t0));
    return true;
}

/** DONE preview: center-weighted feather resplat at target resolution (no seams / full align). */
static bool preparePreviewResplatLocked(int outW, int outH) {
    if (outW <= 0 || outH <= 0 || sFrames.empty()) return false;
    auto t0 = SteadyClock::now();
    if (outW != eqW || outH != eqH) {
        eqW = outW;
        eqH = outH;
        const int sz = eqW * eqH;
        eqR.assign(sz, 0.f);
        eqG.assign(sz, 0.f);
        eqB.assign(sz, 0.f);
        eqWeight.assign(sz, 0.f);
        eqCoverage.assign(sz, 0);
        eqSplatCenterDistSq.assign(sz, -1e18f);
        rebuildEquirectTrigTablesLocked();
    } else {
        std::fill(eqR.begin(), eqR.end(), 0.f);
        std::fill(eqG.begin(), eqG.end(), 0.f);
        std::fill(eqB.begin(), eqB.end(), 0.f);
        std::fill(eqWeight.begin(), eqWeight.end(), 0.f);
        if (!eqCoverage.empty()) std::fill(eqCoverage.begin(), eqCoverage.end(), 0);
    }
    SplatAccumContext accumCtx;
    accumCtx.previewFeatherSplat = true;
    accumCtx.skipLiveCoverage = true;
    ScopedSplatAccum accumGuard(accumCtx);
    resetCoverageCountersLocked();
    sFrameIndex = 0;
    sLastRejected = 0;
    sLastRollOffset = 0.f;
    const int aligned = rebuildEquirectFromStoredFramesLocked(
        false, false, /*useFullResSource=*/true);
    markEquirectFreshLocked();
    LOGI("Preview resplat: %dx%d feather full-res %zu frames (%d aligned) in %lldus",
         eqW, eqH, sFrames.size(), aligned, (long long)elapsedUs(t0));
    return true;
}

/** Refresh parallax warps from current equirect (offline between full-align passes). */
static bool refineStoredPosesLocked() {
    const int n = (int)sFrames.size();
    if (n < 2) return false;

    auto t0 = SteadyClock::now();
    refineChainedPosesLocked();
    rebuildEquirectFromStoredFramesLocked(/*recencyComposite=*/false);
    if (ENABLE_MOTION_WARP_REFINE && !sFastFinishRefine) {
        refineMotionWarpsLocked();
    }
    rebuildOverlapBankLocked();
    LOGI("Stored pose refine: %d frames fast=%d in %lldus",
         n, sFastFinishRefine, (long long)elapsedUs(t0));
    return true;
}

/** Intrinsic optimization disabled — session calibration is fixed at init. */
static bool refineIntrinsicsPendingLocked() {
    return false;
}

JNIEXPORT jboolean JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeAddFrame(
    JNIEnv* env, jobject,
    jintArray jPixels, jint frameW, jint frameH,
    jfloat qx, jfloat qy, jfloat qz, jfloat qw,
    jboolean allowReject) {

    std::lock_guard<std::mutex> lock(sphereMutex);
    if (eqW == 0 || eqH == 0) return JNI_FALSE;
    invalidateEquirectFreshLocked();
    jint* pixels = env->GetIntArrayElements(jPixels, nullptr);
    if (!pixels) return JNI_FALSE;
    FrameOutcome o = processLiveFrameLocked(
        reinterpret_cast<const int32_t*>(pixels), frameW, frameH,
        Quat{qx, qy, qz, qw}, allowReject != JNI_FALSE, /*overlapGate=*/0.f);
    if (o.accepted) {
        Quat imuQ{qx, qy, qz, qw};
        double l = std::sqrt(imuQ.x * imuQ.x + imuQ.y * imuQ.y + imuQ.z * imuQ.z + imuQ.w * imuQ.w);
        if (l > 1e-9) { imuQ.x /= l; imuQ.y /= l; imuQ.z /= l; imuQ.w /= l; }
        ++sGeneration;
        sRefineDirty.store(1, std::memory_order_relaxed);
        storeFrameForRefine(
            reinterpret_cast<const int32_t*>(pixels), frameW, frameH,
            imuQ, o.corrected, o.warp,
            o.expGainR, o.expGainG, o.expGainB,
            nullptr, 0, o.poseTrusted);
    }
    env->ReleaseIntArrayElements(jPixels, pixels, JNI_ABORT);
    return o.accepted ? JNI_TRUE : JNI_FALSE;
}

// Live capture path: adaptive IMU + pairwise visual tracking, overlap-gated.
// Returns: 1 = accepted, 0 = rejected (quality), 2 = rejected (low overlap).
JNIEXPORT jint JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeAddFrameFast(
    JNIEnv* env, jobject,
    jintArray jPixels, jint frameW, jint frameH,
    jfloat qx, jfloat qy, jfloat qz, jfloat qw, jfloat overlapGate,
    jfloatArray jDepth, jboolean allowReject, jfloat cameraEv) {

    std::lock_guard<std::mutex> lock(sphereMutex);
    if (eqW == 0 || eqH == 0) return 0;
    flushPendingFrameStoresLocked();
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
    FrameOutcome o = processLiveFrameLocked(
        reinterpret_cast<const int32_t*>(pixels), frameW, frameH,
        q, allowReject != JNI_FALSE, overlapGate, nullptr, depthPtr, cameraEv);
    jint result;
    if (o.rejectedOverlap) {
        result = 2;
    } else if (o.accepted) {
        Quat imuQ = q;
        double l = std::sqrt(imuQ.x*imuQ.x + imuQ.y*imuQ.y + imuQ.z*imuQ.z + imuQ.w*imuQ.w);
        if (l > 1e-9) { imuQ.x/=l; imuQ.y/=l; imuQ.z/=l; imuQ.w/=l; }
        PendingFrameStore job;
        job.w = frameW;
        job.h = frameH;
        job.px.resize((size_t)frameW * frameH);
        std::memcpy(job.px.data(), pixels, job.px.size() * sizeof(int32_t));
        if (depthPtr) {
            job.hasRealDepth = true;
            job.depth = depthCopy;
        }
        job.imuQuat = imuQ;
        job.correctedQuat = o.corrected;
        job.warp = o.warp;
        job.expGainR = o.expGainR;
        job.expGainG = o.expGainG;
        job.expGainB = o.expGainB;
        job.poseTrusted = o.poseTrusted;
        ++sGeneration;
        enqueuePendingFrameStore(std::move(job));
        sRefineDirty.store(1, std::memory_order_relaxed);
        result = 1;
    } else {
        result = 0;
    }
    env->ReleaseIntArrayElements(jPixels, pixels, JNI_ABORT);
    return result;
}

JNIEXPORT void JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeFlushPendingFrameStores(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    flushPendingFrameStoresLocked();
}

static bool hasPendingFrameStoresLocked() {
    std::lock_guard<std::mutex> lock(sPendingStoreMutex);
    return !sPendingFrameStores.empty();
}

// Background refinement: rebuild the panorama from stored frames.
// applyGlobalPose=true runs pairwise pose graph (export quality); false replays
// capture-time IMU poses (preview parity with fast path).
static bool hasRefineWorkPendingLocked(bool applyGlobalPose) {
    if (hasPendingFrameStoresLocked()) return true;
    if (!sDeferredFeatureFrameIndices.empty()) return true;
    if (sRefineDirty.load(std::memory_order_relaxed)) return true;
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

    const bool applyGlobal = applyGlobalPose != JNI_FALSE;
    std::vector<Quat> refinedBackup;
    std::vector<StoredFrame> splatSnapshot;
    uint64_t snapshotGeneration = 0;
    bool globalPoseReverted = false;
    int aligned = 0;

    {
        std::unique_lock<std::mutex> lock(sphereMutex);
        flushPendingFrameStoresLocked();
        if (!hasRefineWorkPendingLocked(applyGlobal)) return JNI_FALSE;
        if (applyGlobal && sPendingFinishGlobalUpgrade) {
            sPendingFinishGlobalUpgrade = 0;
        }

        sLastRefineRan = 1;
        sLastRefineGlobalPose = applyGlobal ? 1 : 0;
        sLastRefineSsdBefore = 0.f;
        sLastRefineSsdAfter = 0.f;
        sLastRefineFramesRealigned = 0;

        for (auto& f : sFrames) {
            if (!f.refined) f.locked = false;
        }

        refinedBackup.resize(sFrames.size());
        for (size_t i = 0; i < sFrames.size(); ++i) {
            refinedBackup[i] = sFrames[i].refinedQuat;
        }

        refinePoseGraphLocked(applyGlobal, &globalPoseReverted);

        snapshotGeneration = sGeneration;
        const auto tSnapshotCopy = SteadyClock::now();
        splatSnapshot = sFrames;
        LOGI("Refine snapshot_copy=%lld us frames=%zu",
             (long long)elapsedUs(tSnapshotCopy), splatSnapshot.size());
        ensureBackBufferSizeLocked();
        lock.unlock();

        if (!sFastFinishRefine) {
            std::vector<float> backR, backG, backB, backWeight;
            const bool usePreviewFeather = sPoseGraphOnlyGlobal;
            const bool nearestCenter = !usePreviewFeather && !sFastFinishRefine;
            aligned = rebuildPanoramaFromFrames(
                splatSnapshot, backR, backG, backB, backWeight,
                nearestCenter, applyGlobal, usePreviewFeather);
            lock.lock();
            if (snapshotGeneration != sGeneration ||
                splatSnapshot.size() != sFrames.size()) {
                revertRefinedQuatsLocked(refinedBackup);
                sRefineDirty.store(1, std::memory_order_relaxed);
                LOGI("Refine commit stale (gen %llu vs %llu frames %zu vs %zu)",
                     (unsigned long long)snapshotGeneration,
                     (unsigned long long)sGeneration,
                     splatSnapshot.size(), sFrames.size());
                return JNI_FALSE;
            }
            eqBackR = std::move(backR);
            eqBackG = std::move(backG);
            eqBackB = std::move(backB);
            eqBackWeight = std::move(backWeight);
            swapDisplayWithRefinedBackLocked();
        } else {
            lock.lock();
            if (snapshotGeneration != sGeneration ||
                splatSnapshot.size() != sFrames.size()) {
                revertRefinedQuatsLocked(refinedBackup);
                sRefineDirty.store(1, std::memory_order_relaxed);
                return JNI_FALSE;
            }
            invalidateEquirectFreshLocked();
        }

        for (auto& f : sFrames) {
            f.refined = true;
        }
        updateLockedFramesAfterRefine();
        sFramesAtLastRefine = (int)sFrames.size();
        rebuildOverlapBankLocked();
        sRefineDirty.store(0, std::memory_order_relaxed);
        sLastRefineFramesRealigned = aligned;
        sCompletedRefineWasGlobal =
            (applyGlobal && !globalPoseReverted) ? 1 : 0;
        if (applyGlobal) {
            sPendingFinishGlobalUpgrade = 1;
        }
        LOGI("Refine commit: %zu frames (%d re-aligned, globalPose=%d) SSD %.4f->%.4f",
             sFrames.size(), aligned, applyGlobal ? 1 : 0,
             sLastRefineSsdBefore, sLastRefineSsdAfter);
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
        f.refined = true;
        f.locked = false;
    }
    const int aligned = rebuildEquirectFromStoredFramesLocked(
        false, /*forceStoredExposure=*/true, /*useFullResSource=*/true);
    LOGI("RebuildFullAlign: resplat refined poses (%d/%zu aligned)",
         aligned, sFrames.size());
    return aligned > 0 ? JNI_TRUE : JNI_FALSE;
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
Java_ab_inque_robotics_panorama_SphereNative_nativePreparePreviewResplat(
    JNIEnv*, jobject, jint outW, jint outH) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    return preparePreviewResplatLocked((int)outW, (int)outH) ? JNI_TRUE : JNI_FALSE;
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
  return 1.f;
}

JNIEXPORT jboolean JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeSetSessionFocalScale(
    JNIEnv*, jobject, jfloat scale) {
    (void)scale;
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
    return static_cast<jfloat>(evalOverlapBankSSD());
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

    if (outW > eqW || outH > eqH) {
        env->ReleaseIntArrayElements(jOut, out, JNI_ABORT);
        return JNI_FALSE;
    }
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

JNIEXPORT jint JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeGetEquirectWidth(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    return eqW;
}

JNIEXPORT jint JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeGetEquirectHeight(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    return eqH;
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

JNIEXPORT void JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeProfileFeatureMatcher(
    JNIEnv* env, jobject,
    jintArray jPixelsA, jint wA, jint hA,
    jintArray jPixelsB, jint wB, jint hB) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    jint* pxA = env->GetIntArrayElements(jPixelsA, nullptr);
    jint* pxB = env->GetIntArrayElements(jPixelsB, nullptr);
    if (!pxA || !pxB) {
        if (pxA) env->ReleaseIntArrayElements(jPixelsA, pxA, JNI_ABORT);
        if (pxB) env->ReleaseIntArrayElements(jPixelsB, pxB, JNI_ABORT);
        return;
    }
    profileFeatureMatcherLocked(
        reinterpret_cast<const int32_t*>(pxA), wA, hA,
        reinterpret_cast<const int32_t*>(pxB), wB, hB);
    env->ReleaseIntArrayElements(jPixelsA, pxA, JNI_ABORT);
    env->ReleaseIntArrayElements(jPixelsB, pxB, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeProfileLivePairwise(
    JNIEnv* env, jobject,
    jintArray jPixels, jint w, jint h,
    jfloat qx, jfloat qy, jfloat qz, jfloat qw) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    jint* pixels = env->GetIntArrayElements(jPixels, nullptr);
    if (!pixels) return;
    Quat q{qx, qy, qz, qw};
    profileLivePairwiseLocked(reinterpret_cast<const int32_t*>(pixels), w, h, q);
    env->ReleaseIntArrayElements(jPixels, pixels, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeProfileFeatureGraph(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    profileFeatureGraphLocked();
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
    eqCoverage.assign(sz, 0);
    eqBackR.assign(sz, 0.f);
    eqBackG.assign(sz, 0.f);
    eqBackB.assign(sz, 0.f);
    eqBackWeight.assign(sz, 0.f);

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
    rebuildEquirectTrigTablesLocked();
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
Java_ab_inque_robotics_panorama_SphereNative_nativeSetPoseGraphOnlyGlobal(
    JNIEnv*, jobject, jboolean enabled) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    sPoseGraphOnlyGlobal = enabled != JNI_FALSE ? 1 : 0;
}

JNIEXPORT jboolean JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeBuildFeatureGraphCache(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    if ((int)sFrames.size() < 2) return JNI_FALSE;
    flushDeferredFrameFeaturesLocked();
    buildFeatureGraphCacheLocked();
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeRefineTargetedPhotometric(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    if (eqW == 0 || eqH == 0 || sFrames.size() < 2) return 0;
    flushPendingFrameStoresLocked();
    return refineTargetedPhotometricLocked();
}

JNIEXPORT jboolean JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeRefineFeatureGraphReconcile(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    if (eqW == 0 || eqH == 0 || sFrames.size() < 2) return JNI_FALSE;
    flushPendingFrameStoresLocked();
    return refineFeatureGraphReconcileLocked() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeRefineConditionalParallax(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    if (eqW == 0 || eqH == 0 || sFrames.size() < 2) return 0;
    return refineConditionalParallaxLocked();
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
        if (eqCoverage.size() != static_cast<size_t>(sz)) {
            eqCoverage.assign(sz, 0);
        } else {
            std::fill(eqCoverage.begin(), eqCoverage.end(), 0);
        }
    }
    resetCoverageCountersLocked();
    if (sPairwiseTotalFrames > 0) {
        logPairwiseAcceptCountersLocked();
    }
    resetPairwiseAcceptCountersLocked();
    clearStoredFramesLocked();
    gaResetLocked();
    sDeferredFeatureFrameIndices.clear();
    clearPendingFrameStores();
    sOverlapBank.clear();
    sFeatureEdges.clear();
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
    sPoseGraphOnlyGlobal = 0;
    sGeneration = 0;
    sRefineDirty.store(0, std::memory_order_relaxed);
    eqBackR.clear(); eqBackG.clear(); eqBackB.clear(); eqBackWeight.clear();
    invalidateEquirectFreshLocked();
    LOGI("Sphere reset");
}

JNIEXPORT void JNICALL
Java_ab_inque_robotics_panorama_SphereNative_nativeRelease(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    if (sPairwiseTotalFrames > 0) {
        logPairwiseAcceptCountersLocked();
    }
    clearStoredFramesLocked();
    gaResetLocked();
    sDeferredFeatureFrameIndices.clear();
    clearPendingFrameStores();
    sFrames.shrink_to_fit();
    resetPairwiseAcceptCountersLocked();
    sOverlapBank.clear(); sOverlapBank.shrink_to_fit();
    eqR.clear(); eqR.shrink_to_fit();
    eqG.clear(); eqG.shrink_to_fit();
    eqB.clear(); eqB.shrink_to_fit();
    eqWeight.clear(); eqWeight.shrink_to_fit();
    eqGradMag.clear(); eqGradMag.shrink_to_fit();
    eqCoverage.clear(); eqCoverage.shrink_to_fit();
    eqW = 0; eqH = 0;
    LOGI("Sphere released");
}

// ---------------------------------------------------------------------------
// SphereViewerNative — display-only equirect buffer (never touches stitch state)
// ---------------------------------------------------------------------------

static std::mutex viewerMutex;
static int viewerEqW = 0;
static int viewerEqH = 0;
static std::vector<int32_t> viewerPixels;
static constexpr int32_t VIEWER_HOLE_PIXEL = 0x001a1a2e;

static inline bool viewerPixelCovered(int32_t pixel) {
    return pixel != 0 && (pixel & 0xffffff) != VIEWER_HOLE_PIXEL;
}

static bool viewerGetRectilinearLocked(
    int32_t* out, int outW, int outH,
    float azimuth, float elevation, float fov) {
    if (viewerEqW == 0 || viewerEqH == 0 || viewerPixels.empty()) return false;

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
            double len = std::sqrt(rx * rx + ry * ry + rz * rz);
            rx /= len; ry /= len; rz /= len;

            double wx, wy, wz;
            quatRotate(qLook, rx, ry, rz, wx, wy, wz);

            double lon = std::atan2(wx, wy);
            double lat = std::asin(std::max(-1.0, std::min(1.0, wz)));

            double eqXf = (lon / PI + 1.0) * 0.5 * viewerEqW;
            double eqYf = (0.5 - lat / PI) * viewerEqH;

            int x0 = static_cast<int>(std::floor(eqXf));
            int y0 = static_cast<int>(std::floor(eqYf));
            double fx = eqXf - x0;
            double fy = eqYf - y0;

            int x1 = (x0 + 1) % viewerEqW;
            if (x0 < 0) x0 += viewerEqW;
            x0 = x0 % viewerEqW;
            int y1 = std::min(y0 + 1, viewerEqH - 1);
            y0 = clampI(y0, 0, viewerEqH - 1);

            int32_t p00 = viewerPixels[y0 * viewerEqW + x0];
            int32_t p10 = viewerPixels[y0 * viewerEqW + x1];
            int32_t p01 = viewerPixels[y1 * viewerEqW + x0];
            int32_t p11 = viewerPixels[y1 * viewerEqW + x1];

            bool h00 = viewerPixelCovered(p00);
            bool h10 = viewerPixelCovered(p10);
            bool h01 = viewerPixelCovered(p01);
            bool h11 = viewerPixelCovered(p11);

            if (!h00 && !h10 && !h01 && !h11) {
                out[y * outW + x] = static_cast<int32_t>(0xff1a1a2e);
                continue;
            }

            int32_t sample00 = h00 ? p00 : 0;
            int32_t sample10 = h10 ? p10 : 0;
            int32_t sample01 = h01 ? p01 : 0;
            int32_t sample11 = h11 ? p11 : 0;

            double w00 = h00 ? (1.0 - fx) * (1.0 - fy) : 0.0;
            double w10 = h10 ? fx * (1.0 - fy) : 0.0;
            double w01 = h01 ? (1.0 - fx) * fy : 0.0;
            double w11 = h11 ? fx * fy : 0.0;
            double wSum = w00 + w10 + w01 + w11;
            double invW = 1.0 / wSum;

            int r = clampI(static_cast<int>(
                (w00 * ((sample00 >> 16) & 0xff) + w10 * ((sample10 >> 16) & 0xff) +
                 w01 * ((sample01 >> 16) & 0xff) + w11 * ((sample11 >> 16) & 0xff)) * invW), 0, 255);
            int g = clampI(static_cast<int>(
                (w00 * ((sample00 >> 8) & 0xff) + w10 * ((sample10 >> 8) & 0xff) +
                 w01 * ((sample01 >> 8) & 0xff) + w11 * ((sample11 >> 8) & 0xff)) * invW), 0, 255);
            int b = clampI(static_cast<int>(
                (w00 * (sample00 & 0xff) + w10 * (sample10 & 0xff) +
                 w01 * (sample01 & 0xff) + w11 * (sample11 & 0xff)) * invW), 0, 255);

            out[y * outW + x] = static_cast<int32_t>(0xff000000 | (r << 16) | (g << 8) | b);
        }
    }
    return true;
}

JNIEXPORT jboolean JNICALL
Java_ab_inque_robotics_panorama_SphereViewerNative_nativeLoadEquirect(
    JNIEnv* env, jobject,
    jintArray jPixels, jint width, jint height) {
    std::lock_guard<std::mutex> lock(viewerMutex);
    if (width <= 0 || height <= 0) return JNI_FALSE;
    int sz = width * height;
    if (env->GetArrayLength(jPixels) < sz) return JNI_FALSE;

    viewerEqW = width;
    viewerEqH = height;
    viewerPixels.resize(sz);

    jint* pixels = env->GetIntArrayElements(jPixels, nullptr);
    if (!pixels) return JNI_FALSE;
    std::memcpy(viewerPixels.data(), pixels, sz * sizeof(int32_t));
    env->ReleaseIntArrayElements(jPixels, pixels, JNI_ABORT);
    LOGI("Viewer loaded equirect: %dx%d", viewerEqW, viewerEqH);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_ab_inque_robotics_panorama_SphereViewerNative_nativeGetRectilinear(
    JNIEnv* env, jobject,
    jintArray jOut, jint outW, jint outH,
    jfloat azimuth, jfloat elevation, jfloat fov) {
    std::lock_guard<std::mutex> lock(viewerMutex);
    if (viewerEqW == 0 || viewerEqH == 0) return JNI_FALSE;
    if (env->GetArrayLength(jOut) < outW * outH) return JNI_FALSE;

    jint* out = env->GetIntArrayElements(jOut, nullptr);
    if (!out) return JNI_FALSE;
    bool ok = viewerGetRectilinearLocked(out, outW, outH, azimuth, elevation, fov);
    env->ReleaseIntArrayElements(jOut, out, 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_ab_inque_robotics_panorama_SphereViewerNative_nativeRelease(
    JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(viewerMutex);
    viewerPixels.clear();
    viewerPixels.shrink_to_fit();
    viewerEqW = 0;
    viewerEqH = 0;
    LOGI("Viewer released");
}

} // extern "C"
