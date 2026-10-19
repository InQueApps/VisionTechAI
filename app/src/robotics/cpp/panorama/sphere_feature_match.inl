// Lightweight ORB-style feature matching for spherical panorama pose graph.
// Included from sphere_jni.cpp (camera −Z forward, same ray model as pixelToRay).

#ifndef SPHERE_FEATURE_MATCH_INL
#define SPHERE_FEATURE_MATCH_INL

#include <cstring>
#include <cstdint>
#include "sphere_math.h"

struct FeatureEdge {
    int frameA = 0;
    int frameB = 0;
    double relRot[9]{};  // row-major 3×3: maps camera-B unit ray → camera-A
    int inliers = 0;
    double weight = 0.0;
};

static std::vector<FeatureEdge> sFeatureEdges;

static constexpr int FEATURE_MAX_KPS = 320;
static constexpr int FEATURE_MIN_INLIERS = 22;
static constexpr double FEATURE_EDGE_LAMBDA = 120.0;
static constexpr double FEATURE_IMU_GRAPH_LAMBDA = 60.0;

static inline void mat3FromQuat(const Quat& q, double* m) {
    const double x = q.x, y = q.y, z = q.z, w = q.w;
    m[0] = 1.0 - 2.0 * (y * y + z * z);
    m[1] = 2.0 * (x * y - z * w);
    m[2] = 2.0 * (x * z + y * w);
    m[3] = 2.0 * (x * y + z * w);
    m[4] = 1.0 - 2.0 * (x * x + z * z);
    m[5] = 2.0 * (y * z - x * w);
    m[6] = 2.0 * (x * z - y * w);
    m[7] = 2.0 * (y * z + x * w);
    m[8] = 1.0 - 2.0 * (x * x + y * y);
}

static inline Quat quatFromMat3(const double* m) {
    double trace = m[0] + m[4] + m[8];
    Quat q{};
    if (trace > 0) {
        double s = std::sqrt(trace + 1.0) * 2.0;
        q.w = 0.25 * s;
        q.x = (m[7] - m[5]) / s;
        q.y = (m[2] - m[6]) / s;
        q.z = (m[3] - m[1]) / s;
    } else if (m[0] > m[4] && m[0] > m[8]) {
        double s = std::sqrt(1.0 + m[0] - m[4] - m[8]) * 2.0;
        q.w = (m[7] - m[5]) / s;
        q.x = 0.25 * s;
        q.y = (m[1] + m[3]) / s;
        q.z = (m[2] + m[6]) / s;
    } else if (m[4] > m[8]) {
        double s = std::sqrt(1.0 + m[4] - m[0] - m[8]) * 2.0;
        q.w = (m[2] - m[6]) / s;
        q.x = (m[1] + m[3]) / s;
        q.y = 0.25 * s;
        q.z = (m[5] + m[7]) / s;
    } else {
        double s = std::sqrt(1.0 + m[8] - m[0] - m[4]) * 2.0;
        q.w = (m[3] - m[1]) / s;
        q.x = (m[2] + m[6]) / s;
        q.y = (m[5] + m[7]) / s;
        q.z = 0.25 * s;
    }
  double len = std::sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w);
    if (len > 1e-9) {
        q.x /= len; q.y /= len; q.z /= len; q.w /= len;
    }
    return q;
}

static inline void mat3Mul(const double* a, const double* b, double* out) {
    for (int r = 0; r < 3; r++) {
        for (int c = 0; c < 3; c++) {
            out[r * 3 + c] = a[r * 3 + 0] * b[0 * 3 + c]
                           + a[r * 3 + 1] * b[1 * 3 + c]
                           + a[r * 3 + 2] * b[2 * 3 + c];
        }
    }
}

static inline void mat3Transpose(const double* a, double* out) {
    for (int r = 0; r < 3; r++)
        for (int c = 0; c < 3; c++)
            out[r * 3 + c] = a[c * 3 + r];
}

static inline double mat3AngularDistRad(const double* a, const double* b) {
    double prod[9];
    double bt[9];
    mat3Transpose(b, bt);
    mat3Mul(a, bt, prod);
    double trace = prod[0] + prod[4] + prod[8];
    trace = std::max(-1.0, std::min(3.0, trace));
    return std::acos(std::max(-1.0, std::min(1.0, (trace - 1.0) * 0.5)));
}

static constexpr int FEATURE_BRIEF_BITS = 128;
static constexpr int FEATURE_DESC_BYTES = FEATURE_BRIEF_BITS / 8;
static constexpr int FEATURE_GRID_COLS = 8;
static constexpr int FEATURE_GRID_ROWS = 6;
static constexpr int FEATURE_KPS_PER_CELL = 6;
static constexpr int FEATURE_RANSAC_ITERS = 96;
static constexpr double FEATURE_INLIER_COS = 0.9993908270190958;  // cos(2°)
static constexpr int FEATURE_MIN_MATCHES = 20;
static constexpr int FEATURE_MIN_INLIERS_LIVE = 18;
/** Min RANSAC inliers to classify HIGH (skip coarse3d safety fallback). */
static constexpr int FEATURE_MIN_INLIERS_STRONG = 22;
static constexpr double FEATURE_MIN_INLIER_RATIO = 0.30;
/** HIGH confidence = safe to skip coarse3d (ratio + cells + residual + inlier count). */
static constexpr float FEATURE_INLIER_RATIO_HIGH = 0.50f;
static constexpr int FEATURE_MIN_OCCUPIED_CELLS_HIGH = 6;
/** ≈1° equivalent (1−cos(1°)); inliers at 2° RANSAC are ~0.000609 max. */
static constexpr float FEATURE_STRONG_RESIDUAL = 0.00015f;
static constexpr int FEATURE_MIN_CELL_SEP = 6;

struct BriefPair {
    int8_t x1, y1, x2, y2;
};

static const BriefPair kBriefPairs[FEATURE_BRIEF_BITS] = {
    {-3,-4,0,-1}, {-1,-2,-3,4}, {-3,2,-4,-4}, {-3,-1,-1,4},
    {-4,4,-1,4}, {2,-1,3,0}, {-4,-2,2,1}, {0,-2,-1,1},
    {-3,-3,2,-3}, {1,1,0,-4}, {3,4,-3,2}, {-3,4,0,1},
    {-1,-3,-4,-1}, {0,-3,-1,-3}, {2,0,3,1}, {-2,1,1,-1},
    {0,-3,-2,4}, {-1,-2,3,2}, {0,4,-1,1}, {-4,-1,-4,1},
    {2,0,-3,-1}, {1,-1,3,2}, {3,-2,0,-2}, {-1,4,4,0},
    {2,2,1,-1}, {-2,4,3,-3}, {-4,-3,-2,-2}, {2,-3,2,2},
    {3,4,0,4}, {-4,-3,4,0}, {1,-3,0,2}, {-2,3,-4,0},
    {4,-2,4,-3}, {0,4,-1,-2}, {1,-2,4,4}, {-4,1,3,-4},
    {-3,1,0,-1}, {-4,-1,-3,-3}, {3,-3,4,-2}, {-2,3,4,-2},
    {0,4,2,-1}, {4,-1,0,2}, {1,3,4,3}, {-3,-1,-1,-3},
    {1,-4,4,-1}, {-1,-4,-3,-4}, {-1,-3,-4,1}, {-3,4,-1,0},
    {3,-1,4,-2}, {3,-1,3,2}, {-1,-3,-3,2}, {1,2,2,3},
    {-4,-3,-4,2}, {1,-3,-1,-1}, {-1,4,3,-2}, {2,-2,0,3},
    {-1,-3,3,4}, {-3,-4,4,-4}, {-3,-1,-2,2}, {3,3,-1,2},
    {-4,-2,2,-4}, {2,0,3,0}, {2,4,3,-2}, {-1,0,-1,-4},
    {4,-4,1,-4}, {-4,3,4,4}, {-2,-4,4,-3}, {-2,-3,-3,-1},
    {2,-3,-1,-4}, {-3,2,4,1}, {0,-1,1,-1}, {0,2,-2,0},
    {3,1,-3,-4}, {3,-3,-3,4}, {-1,4,0,-2}, {1,-3,-1,1},
    {0,-2,3,4}, {0,4,-4,4}, {0,-3,-2,0}, {-3,-3,4,-2},
    {0,0,-1,1}, {-1,0,4,3}, {0,-4,-3,2}, {0,-4,-4,1},
    {-2,0,-2,3}, {4,2,4,-4}, {-3,-3,-2,4}, {-4,1,4,-2},
    {2,-2,-4,0}, {1,-4,1,-1}, {-1,-3,1,4}, {2,-2,-1,-2},
    {-2,2,-4,-2}, {1,2,-1,0}, {-2,-3,2,-4}, {3,-1,-1,3},
    {1,0,-1,-1}, {-4,-1,2,1}, {0,-3,0,1}, {4,2,4,1},
    {-4,-3,0,-2}, {1,1,2,4}, {-3,2,-1,0}, {-4,2,-4,4},
    {4,-1,1,2}, {-3,1,1,-3}, {0,4,0,2}, {1,2,0,4},
    {-2,-1,2,2}, {-2,0,2,4}, {-4,0,0,-1}, {2,1,3,3},
    {3,-1,4,3}, {-2,-3,0,4}, {1,-3,-1,0}, {-1,-1,-2,-4},
    {-4,-1,3,-3}, {3,2,-1,2}, {3,2,-1,-2}, {-4,-3,2,-1},
    {-2,4,3,-4}, {4,-1,-3,3}, {-2,3,4,4}, {1,3,4,2},
    {4,3,-2,3}, {3,0,-1,0}, {4,3,-1,0}, {3,-3,0,-1},
};

#if defined(_MSC_VER)
#include <intrin.h>
static inline int popcount64(uint64_t v) {
    return static_cast<int>(__popcnt64(v));
}
#else
static inline int popcount64(uint64_t v) {
    return static_cast<int>(__builtin_popcountll(v));
}
#endif

static inline int popcount8(uint8_t v) {
    v = (v & 0x55) + ((v >> 1) & 0x55);
    v = (v & 0x33) + ((v >> 2) & 0x33);
    return (v & 0x0f) + ((v >> 4) & 0x0f);
}

/** Davenport Wahba via shared sphere_math (a ≈ R b, B-ray → A-ray). */
static inline Quat estimateRotWahbaQuat(
    const double* raysA, const double* raysB, int count) {
    robotics::panorama::Quat q =
        robotics::panorama::estimateRotWahbaQuat(raysA, raysB, count);
    return {q.x, q.y, q.z, q.w};
}

static inline void estimateRotWahba(
    const double* raysA, const double* raysB, int count, double* outR) {
    robotics::panorama::estimateRotWahba(raysA, raysB, count, outR);
}

static inline void mat3RotateVec(const double* m, const double* v, double* out) {
    out[0] = m[0] * v[0] + m[1] * v[1] + m[2] * v[2];
    out[1] = m[3] * v[0] + m[4] * v[1] + m[5] * v[2];
    out[2] = m[6] * v[0] + m[7] * v[1] + m[8] * v[2];
}

struct FeatureRotationResult {
    bool valid = false;
    double R[9]{};
    int matches = 0;
    int inliers = 0;
    float inlierRatio = 0.f;
    int occupiedCells = 0;
    float meanResidual = 0.f;
    float maxResidual = 0.f;
    enum Confidence { LOW, MEDIUM, HIGH } confidence = LOW;

    bool isStrongConfidence() const {
        // HIGH is defined as skip-coarse-safe; do not add separate ratio/residual checks here.
        return valid && confidence == HIGH;
    }
};

static void evaluateRotationInliers(
    const double* R,
    const double* raysA,
    const double* raysB,
    int count,
    int& outInliers,
    double& outMeanResidual,
    double& outMaxResidual,
    double* meanDotOut = nullptr) {

    int inliers = 0;
    double residualSum = 0.0;
    double residualMax = 0.0;
    double dotSum = 0.0;
    for (int i = 0; i < count; i++) {
        const double* rb = raysB + i * 3;
        const double* ra = raysA + i * 3;
        double pr[3];
        mat3RotateVec(R, rb, pr);
        double dot = pr[0] * ra[0] + pr[1] * ra[1] + pr[2] * ra[2];
        dot = std::max(-1.0, std::min(1.0, dot));
        if (dot > FEATURE_INLIER_COS) {
            const double e = 1.0 - dot;
            residualSum += e;
            residualMax = std::max(residualMax, e);
            dotSum += dot;
            inliers++;
        }
    }
    outInliers = inliers;
    outMeanResidual = inliers > 0 ? residualSum / inliers : 1e9;
    outMaxResidual = residualMax;
    if (meanDotOut) *meanDotOut = inliers > 0 ? dotSum / inliers : 0.0;
}

static int countRotationInliers(
    const double* R,
    const double* raysA,
    const double* raysB,
    int count,
    double* meanDotOut = nullptr) {

    double meanRes = 0.0, maxRes = 0.0;
    int inliers = 0;
    evaluateRotationInliers(R, raysA, raysB, count, inliers, meanRes, maxRes, meanDotOut);
    return inliers;
}

static bool estimateRotRansac(
    const double* raysA,
    const double* raysB,
    int matchCount,
    double* outR,
    int& outInliers,
    double& outMeanDot) {

    if (matchCount < FEATURE_MIN_MATCHES) return false;

    int bestInliers = 0;
    double bestScore = -1.0;
    double bestR[9];
    std::vector<int> bestIdx;
    std::vector<int> inlierIdx;
    inlierIdx.reserve(matchCount);

    uint32_t rng = 0x9E3779B9u ^
        static_cast<uint32_t>(matchCount * 2654435761u);
    auto nextRand = [&]() {
        rng ^= rng << 13;
        rng ^= rng >> 17;
        rng ^= rng << 5;
        return rng;
    };

    for (int iter = 0; iter < FEATURE_RANSAC_ITERS; iter++) {
        int i0 = 0, i1 = 0, i2 = 0;
        int guard = 0;
        do {
            i0 = static_cast<int>(nextRand() % static_cast<uint32_t>(matchCount));
            i1 = static_cast<int>(nextRand() % static_cast<uint32_t>(matchCount));
            i2 = static_cast<int>(nextRand() % static_cast<uint32_t>(matchCount));
            ++guard;
        } while ((i0 == i1 || i0 == i2 || i1 == i2) && guard < 16);
        if (i0 == i1 || i0 == i2 || i1 == i2) continue;

        double sampleA[9], sampleB[9];
        for (int k = 0; k < 3; k++) {
            const int idx = (k == 0) ? i0 : (k == 1) ? i1 : i2;
            memcpy(sampleA + k * 3, raysA + idx * 3, 3 * sizeof(double));
            memcpy(sampleB + k * 3, raysB + idx * 3, 3 * sizeof(double));
        }

        double R[9];
        estimateRotWahba(sampleA, sampleB, 3, R);

        inlierIdx.clear();
        double score = 0.0;
        for (int k = 0; k < matchCount; k++) {
            const double* rb = raysB + k * 3;
            const double* ra = raysA + k * 3;
            double pr[3];
            mat3RotateVec(R, rb, pr);
            const double dot = pr[0] * ra[0] + pr[1] * ra[1] + pr[2] * ra[2];
            if (dot > FEATURE_INLIER_COS) {
                inlierIdx.push_back(k);
                score += dot;
            }
        }

        const int inliers = (int)inlierIdx.size();
        if (inliers > bestInliers || (inliers == bestInliers && score > bestScore)) {
            bestInliers = inliers;
            bestScore = score;
            memcpy(bestR, R, 9 * sizeof(double));
            bestIdx = inlierIdx;
        }
    }

    if (bestInliers < FEATURE_MIN_INLIERS_LIVE ||
        bestInliers < static_cast<int>(matchCount * FEATURE_MIN_INLIER_RATIO)) {
        return false;
    }

    std::vector<double> inlierA(bestIdx.size() * 3);
    std::vector<double> inlierB(bestIdx.size() * 3);
    for (size_t k = 0; k < bestIdx.size(); k++) {
        memcpy(inlierA.data() + k * 3, raysA + bestIdx[k] * 3, 3 * sizeof(double));
        memcpy(inlierB.data() + k * 3, raysB + bestIdx[k] * 3, 3 * sizeof(double));
    }
    estimateRotWahba(inlierA.data(), inlierB.data(), (int)bestIdx.size(), outR);
    outInliers = countRotationInliers(outR, raysA, raysB, matchCount, &outMeanDot);
    return outInliers >= FEATURE_MIN_INLIERS_LIVE &&
           outInliers >= static_cast<int>(matchCount * FEATURE_MIN_INLIER_RATIO);
}

struct FeatureKeypoint {
    float x = 0.f, y = 0.f;
    uint8_t desc[FEATURE_DESC_BYTES]{};
};

struct FrameFeatures {
    int frameW = 0;
    int frameH = 0;
    int grayW = 0;
    int grayH = 0;
    float scaleX = 1.f;
    float scaleY = 1.f;
    bool initialized = false;
    std::vector<FeatureKeypoint> kps;
};

static std::vector<FrameFeatures> sFrameFeatures;
static std::vector<std::vector<int>> sFeatureEdgesByFrame;
static std::vector<int> sFeatureInlierSumByFrame;

static void clearFeatureCacheLocked() {
    sFrameFeatures.clear();
    sFeatureEdgesByFrame.clear();
    sFeatureInlierSumByFrame.clear();
}

/** Drop stored frames and feature cache together (always pair these). */
static void clearStoredFramesLocked() {
    clearFeatureCacheLocked();
    sFrames.clear();
}

static int storedFrameIndex(const StoredFrame* f) {
    if (!f) return -1;
    for (int i = 0; i < static_cast<int>(sFrames.size()); i++) {
        if (&sFrames[i] == f) return i;
    }
    return -1;
}

static void frameToGraySmall(const StoredFrame& f, int maxDim, std::vector<uint8_t>& gray, int& gw, int& gh) {
    gw = f.w; gh = f.h;
    if (gw <= 0 || gh <= 0) return;
    double scale = 1.0;
    if (std::max(gw, gh) > maxDim) scale = (double)maxDim / std::max(gw, gh);
    gw = std::max(32, (int)(gw * scale));
    gh = std::max(32, (int)(gh * scale));
    gray.assign((size_t)gw * gh, 0);
    for (int y = 0; y < gh; y++) {
        int sy = y * f.h / gh;
        for (int x = 0; x < gw; x++) {
            int sx = x * f.w / gw;
            int32_t p = f.px[(size_t)sy * f.w + sx];
            int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
            gray[(size_t)y * gw + x] = (uint8_t)((r * 77 + g * 150 + b * 29) >> 8);
        }
    }
}

static void pixelsToGraySmall(
    const int32_t* pixels, int pw, int ph, int maxDim,
    std::vector<uint8_t>& gray, int& gw, int& gh) {

    gw = pw; gh = ph;
    if (gw <= 0 || gh <= 0 || !pixels) return;
    double scale = 1.0;
    if (std::max(gw, gh) > maxDim) scale = (double)maxDim / std::max(gw, gh);
    gw = std::max(32, (int)(gw * scale));
    gh = std::max(32, (int)(gh * scale));
    gray.assign((size_t)gw * gh, 0);
    for (int y = 0; y < gh; y++) {
        int sy = y * ph / gh;
        for (int x = 0; x < gw; x++) {
            int sx = x * pw / gw;
            int32_t p = pixels[(size_t)sy * pw + sx];
            int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
            gray[(size_t)y * gw + x] = (uint8_t)((r * 77 + g * 150 + b * 29) >> 8);
        }
    }
}

static void computeBriefDescriptor(
    const std::vector<uint8_t>& gray, int gw, int gh, int x, int y,
    uint8_t* desc) {

    std::memset(desc, 0, FEATURE_DESC_BYTES);
    for (int d = 0; d < FEATURE_BRIEF_BITS; d++) {
        const BriefPair& p = kBriefPairs[d];
        const int x1 = x + p.x1;
        const int y1 = y + p.y1;
        const int x2 = x + p.x2;
        const int y2 = y + p.y2;
        const uint8_t bit = gray[(size_t)y1 * gw + x1] < gray[(size_t)y2 * gw + x2] ? 1 : 0;
        desc[d >> 3] |= static_cast<uint8_t>(bit << (d & 7));
    }
}

static void detectBriefFeatures(
    const std::vector<uint8_t>& gray, int gw, int gh,
    std::vector<FeatureKeypoint>& kps) {

    kps.clear();
    if (gw < 16 || gh < 16) return;

    constexpr int patch = 7;
    struct ScoredPoint {
        int x = 0;
        int y = 0;
        int score = -1;
    };

    const int minSepSq = FEATURE_MIN_CELL_SEP * FEATURE_MIN_CELL_SEP;

    for (int cy = 0; cy < FEATURE_GRID_ROWS; cy++) {
        const int y0 = cy * gh / FEATURE_GRID_ROWS;
        const int y1 = (cy + 1) * gh / FEATURE_GRID_ROWS;
        for (int cx = 0; cx < FEATURE_GRID_COLS; cx++) {
            const int x0 = cx * gw / FEATURE_GRID_COLS;
            const int x1 = (cx + 1) * gw / FEATURE_GRID_COLS;

            ScoredPoint best[FEATURE_KPS_PER_CELL];
            int bestCount = 0;

            auto tooClose = [&](int x, int y) {
                for (int i = 0; i < bestCount; i++) {
                    const int dx = x - best[i].x;
                    const int dy = y - best[i].y;
                    if (dx * dx + dy * dy < minSepSq) return true;
                }
                return false;
            };

            auto consider = [&](int x, int y, int score) {
                if (bestCount < FEATURE_KPS_PER_CELL) {
                    if (tooClose(x, y)) return;
                    best[bestCount++] = {x, y, score};
                    return;
                }
                int worst = 0;
                for (int i = 1; i < FEATURE_KPS_PER_CELL; i++) {
                    if (best[i].score < best[worst].score) worst = i;
                }
                if (score <= best[worst].score) return;
                for (int i = 0; i < bestCount; i++) {
                    if (i == worst) continue;
                    const int dx = x - best[i].x;
                    const int dy = y - best[i].y;
                    if (dx * dx + dy * dy < minSepSq) return;
                }
                best[worst] = {x, y, score};
            };

            for (int y = std::max(patch, y0); y < std::min(gh - patch, y1); y += 2) {
                for (int x = std::max(patch, x0); x < std::min(gw - patch, x1); x += 2) {
                    const int ix = gray[(size_t)y * gw + (x + 1)] - gray[(size_t)y * gw + (x - 1)];
                    const int iy = gray[(size_t)(y + 1) * gw + x] - gray[(size_t)(y - 1) * gw + x];
                    const int score = ix * ix + iy * iy;
                    if (score < 400) continue;
                    consider(x, y, score);
                }
            }

            for (int i = 0; i < bestCount; i++) {
                if ((int)kps.size() >= FEATURE_MAX_KPS) return;
                FeatureKeypoint kp;
                kp.x = static_cast<float>(best[i].x);
                kp.y = static_cast<float>(best[i].y);
                computeBriefDescriptor(gray, gw, gh, best[i].x, best[i].y, kp.desc);
                kps.push_back(kp);
            }
        }
    }
}

static inline int hammingDesc(const uint8_t* a, const uint8_t* b) {
    uint64_t a0, a1, b0, b1;
    std::memcpy(&a0, a, 8);
    std::memcpy(&a1, a + 8, 8);
    std::memcpy(&b0, b, 8);
    std::memcpy(&b1, b + 8, 8);
    return popcount64(a0 ^ b0) + popcount64(a1 ^ b1);
}

struct FeatureMatch {
    int ia = -1;
    int ib = -1;
    int dist = 0;
};

static void matchFeaturesMutual(
    const std::vector<FeatureKeypoint>& kpa,
    const std::vector<FeatureKeypoint>& kpb,
    std::vector<FeatureMatch>& matches) {

    matches.clear();
    const int nA = static_cast<int>(kpa.size());
    const int nB = static_cast<int>(kpb.size());
    if (nA == 0 || nB == 0) return;

    std::vector<int> bestB(nA, -1);
    std::vector<int> bestDistA(nA, 256);
    std::vector<int> secondDistA(nA, 256);
    std::vector<int> bestA(nB, -1);
    std::vector<int> bestDistB(nB, 256);

    for (int i = 0; i < nA; i++) {
        for (int j = 0; j < nB; j++) {
            const int d = hammingDesc(kpa[i].desc, kpb[j].desc);
            if (d < bestDistA[i]) {
                secondDistA[i] = bestDistA[i];
                bestDistA[i] = d;
                bestB[i] = j;
            } else if (d < secondDistA[i]) {
                secondDistA[i] = d;
            }
            if (d < bestDistB[j]) {
                bestDistB[j] = d;
                bestA[j] = i;
            }
        }
    }

    for (int i = 0; i < nA; i++) {
        const int j = bestB[i];
        if (j < 0) continue;
        if (bestDistA[i] >= static_cast<int>(0.8 * secondDistA[i])) continue;
        if (bestA[j] != i) continue;
        matches.push_back({i, j, bestDistA[i]});
    }
}

static int countInlierOccupiedCells(
    const double* R,
    const double* raysA,
    const double* raysB,
    const std::vector<FeatureMatch>& matches,
    const FrameFeatures& fb) {

    const int gw = fb.grayW > 0 ? fb.grayW : 1;
    const int gh = fb.grayH > 0 ? fb.grayH : 1;
    constexpr int cellCount = FEATURE_GRID_COLS * FEATURE_GRID_ROWS;
    bool occupied[cellCount]{};
    int occupiedCount = 0;
    const int matchCount = static_cast<int>(matches.size());
    for (int k = 0; k < matchCount; k++) {
        const double* rb = raysB + k * 3;
        const double* ra = raysA + k * 3;
        double pr[3];
        mat3RotateVec(R, rb, pr);
        const double dot = pr[0] * ra[0] + pr[1] * ra[1] + pr[2] * ra[2];
        if (dot <= FEATURE_INLIER_COS) continue;
        const int ib = matches[k].ib;
        if (ib < 0 || ib >= static_cast<int>(fb.kps.size())) continue;
        const auto& kp = fb.kps[ib];
        const int cx = clampI(
            static_cast<int>(kp.x * FEATURE_GRID_COLS / gw), 0, FEATURE_GRID_COLS - 1);
        const int cy = clampI(
            static_cast<int>(kp.y * FEATURE_GRID_ROWS / gh), 0, FEATURE_GRID_ROWS - 1);
        const int cell = cy * FEATURE_GRID_COLS + cx;
        if (!occupied[cell]) {
            occupied[cell] = true;
            occupiedCount++;
        }
    }
    return occupiedCount;
}

static FeatureRotationResult::Confidence classifyFeatureConfidence(
    int inliers, int matchCount, int occupiedCells, float meanResidual) {

    const float ratio = matchCount > 0
        ? static_cast<float>(inliers) / static_cast<float>(matchCount) : 0.f;
  // HIGH = safe to skip coarse3d (same gate as isStrongConfidence()).
    if (inliers >= FEATURE_MIN_INLIERS_STRONG &&
        ratio >= FEATURE_INLIER_RATIO_HIGH &&
        occupiedCells >= FEATURE_MIN_OCCUPIED_CELLS_HIGH &&
        meanResidual < FEATURE_STRONG_RESIDUAL) {
        return FeatureRotationResult::HIGH;
    }
    if (ratio >= static_cast<float>(FEATURE_MIN_INLIER_RATIO)) {
        return FeatureRotationResult::MEDIUM;
    }
    return FeatureRotationResult::LOW;
}

static bool estimateFeatureRotationFromMatches(
    const FrameFeatures& fa,
    const FrameFeatures& fb,
    const std::vector<FeatureMatch>& matches,
    double tanH, double tanV,
    FeatureRotationResult& out) {

    out = FeatureRotationResult{};
    const int matchCount = static_cast<int>(matches.size());
    out.matches = matchCount;
    if (matchCount < FEATURE_MIN_MATCHES) return false;

    std::vector<double> raysA(static_cast<size_t>(matchCount) * 3);
    std::vector<double> raysB(static_cast<size_t>(matchCount) * 3);
    for (int k = 0; k < matchCount; k++) {
        const auto& m = matches[k];
        const float fxA = fa.kps[m.ia].x * fa.scaleX;
        const float fyA = fa.kps[m.ia].y * fa.scaleY;
        const float fxB = fb.kps[m.ib].x * fb.scaleX;
        const float fyB = fb.kps[m.ib].y * fb.scaleY;
        double rax, ray, raz, rbx, rby, rbz;
        pixelToRayFloat(fxA, fyA, fa.frameW, fa.frameH, tanH, tanV, rax, ray, raz);
        pixelToRayFloat(fxB, fyB, fb.frameW, fb.frameH, tanH, tanV, rbx, rby, rbz);
        raysA[k * 3] = rax;
        raysA[k * 3 + 1] = ray;
        raysA[k * 3 + 2] = raz;
        raysB[k * 3] = rbx;
        raysB[k * 3 + 1] = rby;
        raysB[k * 3 + 2] = rbz;
    }

    double meanDot = 0.0;
    if (!estimateRotRansac(raysA.data(), raysB.data(), matchCount,
                           out.R, out.inliers, meanDot)) {
        out.confidence = FeatureRotationResult::LOW;
        return false;
    }

    double meanRes = 0.0, maxRes = 0.0;
    evaluateRotationInliers(out.R, raysA.data(), raysB.data(), matchCount,
                            out.inliers, meanRes, maxRes, &meanDot);
    out.meanResidual = static_cast<float>(meanRes);
    out.maxResidual = static_cast<float>(maxRes);
    out.inlierRatio = matchCount > 0
        ? static_cast<float>(out.inliers) / static_cast<float>(matchCount) : 0.f;
    out.occupiedCells = countInlierOccupiedCells(
        out.R, raysA.data(), raysB.data(), matches, fb);
    out.confidence = classifyFeatureConfidence(
        out.inliers, matchCount, out.occupiedCells, out.meanResidual);
    out.valid = out.confidence != FeatureRotationResult::LOW;
    return out.valid;
}

static void buildFrameFeaturesFromStored(const StoredFrame& f, FrameFeatures& out) {
    out.frameW = f.w;
    out.frameH = f.h;
    std::vector<uint8_t> gray;
    int gw = 0, gh = 0;
    frameToGraySmall(f, 384, gray, gw, gh);
    out.grayW = gw;
    out.grayH = gh;
    out.scaleX = gw > 0 ? static_cast<float>(f.w) / gw : 1.f;
    out.scaleY = gh > 0 ? static_cast<float>(f.h) / gh : 1.f;
    detectBriefFeatures(gray, gw, gh, out.kps);
    out.initialized = true;
}

static void buildFrameFeaturesFromPixels(
    const int32_t* pixels, int pw, int ph, FrameFeatures& out) {

    out.frameW = pw;
    out.frameH = ph;
    std::vector<uint8_t> gray;
    int gw = 0, gh = 0;
    pixelsToGraySmall(pixels, pw, ph, 384, gray, gw, gh);
    out.grayW = gw;
    out.grayH = gh;
    out.scaleX = gw > 0 ? static_cast<float>(pw) / gw : 1.f;
    out.scaleY = gh > 0 ? static_cast<float>(ph) / gh : 1.f;
    detectBriefFeatures(gray, gw, gh, out.kps);
    out.initialized = true;
}

static void cacheStoredFrameFeatures(int frameIdx) {
    if (frameIdx < 0 || frameIdx >= static_cast<int>(sFrames.size())) return;
    if (sFrameFeatures.size() <= static_cast<size_t>(frameIdx)) {
        sFrameFeatures.resize(frameIdx + 1);
    }
    buildFrameFeaturesFromStored(sFrames[frameIdx], sFrameFeatures[frameIdx]);
}

static FrameFeatures& ensureFrameFeatures(int frameIdx) {
    if (frameIdx < 0 || frameIdx >= static_cast<int>(sFrames.size())) {
        static FrameFeatures empty;
        empty.kps.clear();
        return empty;
    }
    if (sFrameFeatures.size() <= static_cast<size_t>(frameIdx)) {
        sFrameFeatures.resize(frameIdx + 1);
    }
    FrameFeatures& feat = sFrameFeatures[frameIdx];
    if (!feat.initialized) {
        buildFrameFeaturesFromStored(sFrames[frameIdx], feat);
    }
    return feat;
}

static int estimateFeaturePairRotation(
    const FrameFeatures& fa,
    const FrameFeatures& fb,
    const std::vector<FeatureMatch>& matches,
    double tanH, double tanV,
    double* outRelRot,
    int& outInliers) {

    if (matches.size() < FEATURE_MIN_MATCHES) return 0;

    std::vector<double> raysA(matches.size() * 3);
    std::vector<double> raysB(matches.size() * 3);
    for (size_t k = 0; k < matches.size(); k++) {
        const auto& m = matches[k];
        const float fxA = fa.kps[m.ia].x * fa.scaleX;
        const float fyA = fa.kps[m.ia].y * fa.scaleY;
        const float fxB = fb.kps[m.ib].x * fb.scaleX;
        const float fyB = fb.kps[m.ib].y * fb.scaleY;
        double rax, ray, raz, rbx, rby, rbz;
        pixelToRayFloat(fxA, fyA, fa.frameW, fa.frameH, tanH, tanV, rax, ray, raz);
        pixelToRayFloat(fxB, fyB, fb.frameW, fb.frameH, tanH, tanV, rbx, rby, rbz);
        raysA[k * 3] = rax;
        raysA[k * 3 + 1] = ray;
        raysA[k * 3 + 2] = raz;
        raysB[k * 3] = rbx;
        raysB[k * 3 + 1] = rby;
        raysB[k * 3 + 2] = rbz;
    }

    double meanDot = 0.0;
    if (!estimateRotRansac(raysA.data(), raysB.data(), (int)matches.size(),
                           outRelRot, outInliers, meanDot)) {
        outInliers = 0;
        return (int)matches.size();
    }
    return (int)matches.size();
}

static int matchFeaturePairCached(
    const FrameFeatures& fa,
    const FrameFeatures& fb,
    double tanH, double tanV,
    double* outRelRot, int& outInliers) {

    if (fa.kps.size() < 12 || fb.kps.size() < 12) return 0;

    std::vector<FeatureMatch> matches;
    matchFeaturesMutual(fa.kps, fb.kps, matches);
    if (matches.size() < FEATURE_MIN_MATCHES) return 0;

    return estimateFeaturePairRotation(
        fa, fb, matches, tanH, tanV, outRelRot, outInliers);
}

static int matchFeaturePair(
    const StoredFrame& fa, const StoredFrame& fb,
    double tanH, double tanV,
    double* outRelRot, int& outInliers) {

    const int ia = storedFrameIndex(&fa);
    const int ib = storedFrameIndex(&fb);
    if (ia < 0 || ib < 0) return 0;
    return matchFeaturePairCached(
        ensureFrameFeatures(ia), ensureFrameFeatures(ib),
        tanH, tanV, outRelRot, outInliers);
}

/** Legacy int-only matcher; live pairwise uses estimateLiveFeatureRotation() instead. */
static int matchFeaturePairPixels(
    const int32_t* pixels, int pw, int ph,
    const StoredFrame& prev,
    double tanH, double tanV,
    double* outRelRot, int& outInliers) {

    const int prevIdx = storedFrameIndex(&prev);
    if (prevIdx < 0) return 0;

    const FrameFeatures& featA = ensureFrameFeatures(prevIdx);
    if (featA.kps.size() < 12) return 0;

    FrameFeatures featB;
    buildFrameFeaturesFromPixels(pixels, pw, ph, featB);
    if (featB.kps.size() < 12) return 0;

    return matchFeaturePairCached(featA, featB, tanH, tanV, outRelRot, outInliers);
}

/** Live capture with rotation confidence (parallax vs pure rotation). */
static bool estimateLiveFeatureRotation(
    const int32_t* pixels, int pw, int ph,
    const StoredFrame& prev,
    double tanH, double tanV,
    FeatureRotationResult& out) {

    out = FeatureRotationResult{};
    const int prevIdx = storedFrameIndex(&prev);
    if (prevIdx < 0) return false;

    const FrameFeatures& featA = ensureFrameFeatures(prevIdx);
    if (featA.kps.size() < 12) return false;

    auto t0 = SteadyClock::now();
    FrameFeatures featB;
    buildFrameFeaturesFromPixels(pixels, pw, ph, featB);
    const int64_t usDetect = elapsedUs(t0);
    if (featB.kps.size() < 12) return false;

    t0 = SteadyClock::now();
    std::vector<FeatureMatch> matches;
    matchFeaturesMutual(featA.kps, featB.kps, matches);
    const int64_t usMatch = elapsedUs(t0);

    t0 = SteadyClock::now();
    const bool ok = estimateFeatureRotationFromMatches(
        featA, featB, matches, tanH, tanV, out);
    const int64_t usRansac = elapsedUs(t0);

    if (ok) {
        __android_log_print(
            ANDROID_LOG_INFO, SPHERE_PROFILE_TAG,
            "SphereProfile live_feature_total=%lldus detect=%lld match=%lld ransac=%lld "
            "kps_prev=%zu kps_curr=%zu matches=%zu inliers=%d ratio=%.2f cells=%d conf=%d",
            (long long)(usDetect + usMatch + usRansac),
            (long long)usDetect, (long long)usMatch, (long long)usRansac,
            featA.kps.size(), featB.kps.size(), matches.size(),
            out.inliers, out.inlierRatio, out.occupiedCells,
            static_cast<int>(out.confidence));
    }
    return ok;
}

static void buildFeatureEdgesLocked() {
    sFeatureEdges.clear();
    sFeatureEdgesByFrame.clear();
    sFeatureInlierSumByFrame.clear();
    const int n = (int)sFrames.size();
    if (n < 2) return;
    if (static_cast<int>(sFrameFeatures.size()) < n) {
        sFrameFeatures.resize(n);
    }
    double tanH, tanV;
    sessionTanHalfFov(tanH, tanV);
    auto t0 = SteadyClock::now();
    for (int a = 1; a < n; a++) {
        const FrameFeatures& featA = ensureFrameFeatures(a);
        for (int b = std::max(0, a - 3); b < a; b++) {
            double rel[9];
            int inliers = 0;
            const int matches = matchFeaturePairCached(
                featA, ensureFrameFeatures(b), tanH, tanV, rel, inliers);
            if (matches < FEATURE_MIN_MATCHES || inliers < FEATURE_MIN_INLIERS) continue;
            FeatureEdge e;
            e.frameA = a;
            e.frameB = b;
            for (int i = 0; i < 9; i++) e.relRot[i] = rel[i];
            e.inliers = inliers;
            e.weight = std::min(3.0, (double)inliers / 40.0);
            sFeatureEdges.push_back(e);
        }
    }
    if (n >= 4) {
        double rel[9];
        int inliers = 0;
        const int matches = matchFeaturePairCached(
            ensureFrameFeatures(0), ensureFrameFeatures(n - 1),
            tanH, tanV, rel, inliers);
        if (matches >= FEATURE_MIN_MATCHES && inliers >= FEATURE_MIN_INLIERS) {
            FeatureEdge e;
            e.frameA = 0;
            e.frameB = n - 1;
            for (int i = 0; i < 9; i++) e.relRot[i] = rel[i];
            e.inliers = inliers;
            e.weight = std::min(4.0, (double)inliers / 30.0) * 1.5;
            sFeatureEdges.push_back(e);
        }
    }

    sFeatureEdgesByFrame.assign(n, {});
    sFeatureInlierSumByFrame.assign(n, 0);
    for (int ei = 0; ei < static_cast<int>(sFeatureEdges.size()); ei++) {
        const FeatureEdge& e = sFeatureEdges[ei];
        sFeatureEdgesByFrame[e.frameA].push_back(ei);
        sFeatureEdgesByFrame[e.frameB].push_back(ei);
        sFeatureInlierSumByFrame[e.frameA] += e.inliers;
        sFeatureInlierSumByFrame[e.frameB] += e.inliers;
    }
    LOGI("Feature edges: %zu pairs built in %lldus", sFeatureEdges.size(), (long long)elapsedUs(t0));
    sphereProfileLogUs("feature_graph_edges", elapsedUs(t0));
}

static double featureGraphPenaltyForFrame(int frameIdx, const Quat& qCand) {
    if (sFeatureEdges.empty() || frameIdx < 0 ||
        frameIdx >= static_cast<int>(sFeatureEdgesByFrame.size())) {
        return 0.0;
    }
    double mCand[9];
    mat3FromQuat(qCand, mCand);
    double penalty = 0.0;
    for (int ei : sFeatureEdgesByFrame[frameIdx]) {
        const FeatureEdge& e = sFeatureEdges[ei];
        double mOther[9], mPred[9], mRelT[9];
        if (e.frameA == frameIdx) {
            mat3FromQuat(sFrames[e.frameB].refinedQuat, mOther);
            mat3Transpose(e.relRot, mRelT);
            mat3Mul(mOther, mRelT, mPred);
        } else {
            mat3FromQuat(sFrames[e.frameA].refinedQuat, mOther);
            mat3Mul(mOther, e.relRot, mPred);
        }
        double ang = mat3AngularDistRad(mCand, mPred);
        penalty += e.weight * FEATURE_EDGE_LAMBDA * ang * ang;
    }
    return penalty;
}

static int frameFeatureInlierSum(int frameIdx) {
    if (frameIdx < 0 || frameIdx >= static_cast<int>(sFeatureInlierSumByFrame.size())) {
        return 0;
    }
    return sFeatureInlierSumByFrame[frameIdx];
}

static Quat weightedQuatAverage(const std::vector<Quat>& qs, const std::vector<double>& ws) {
    if (qs.empty()) return {0, 0, 0, 1};
    const Quat ref = qs[0];
    double sx = 0, sy = 0, sz = 0, sw = 0, tw = 0;
    for (size_t i = 0; i < qs.size(); i++) {
        Quat q = qs[i];
        const double dot = q.x * ref.x + q.y * ref.y + q.z * ref.z + q.w * ref.w;
        if (dot < 0.0) {
            q.x = -q.x;
            q.y = -q.y;
            q.z = -q.z;
            q.w = -q.w;
        }
        tw += ws[i];
        sx += ws[i] * q.x;
        sy += ws[i] * q.y;
        sz += ws[i] * q.z;
        sw += ws[i] * q.w;
    }
    if (tw < 1e-9) return {0, 0, 0, 1};
    Quat out{sx / tw, sy / tw, sz / tw, sw / tw};
    double len = std::sqrt(out.x * out.x + out.y * out.y + out.z * out.z + out.w * out.w);
    if (len > 1e-9) { out.x /= len; out.y /= len; out.z /= len; out.w /= len; }
    return out;
}

static int seedPosesFromFeatureGraphLocked(bool skipOverlapRebuild = false) {
    const int n = (int)sFrames.size();
    if (n < 2 || sFeatureEdges.empty()) return 0;

    std::vector<double> mats(n * 9);
    for (int i = 0; i < n; i++) {
        mat3FromQuat(sFrames[i].fastCorrectedQuat, mats.data() + i * 9);
        sFrames[i].refinedQuat = sFrames[i].fastCorrectedQuat;
    }

    for (int iter = 0; iter < 12; iter++) {
        for (int i = 0; i < n; i++) {
            std::vector<Quat> cand;
            std::vector<double> wts;
            cand.push_back(sFrames[i].fastCorrectedQuat);
            wts.push_back(FEATURE_IMU_GRAPH_LAMBDA);
            for (int ei : sFeatureEdgesByFrame[i]) {
                const FeatureEdge& e = sFeatureEdges[ei];
                double mb[9], relT[9], target[9];
                if (e.frameA == i) {
                    memcpy(mb, mats.data() + e.frameB * 9, 9 * sizeof(double));
                    mat3Transpose(e.relRot, relT);
                    mat3Mul(mb, relT, target);
                    cand.push_back(quatFromMat3(target));
                    wts.push_back(e.weight * FEATURE_EDGE_LAMBDA);
                } else if (e.frameB == i) {
                    memcpy(mb, mats.data() + e.frameA * 9, 9 * sizeof(double));
                    mat3Mul(mb, e.relRot, target);
                    cand.push_back(quatFromMat3(target));
                    wts.push_back(e.weight * FEATURE_EDGE_LAMBDA);
                }
            }
            Quat qNew = weightedQuatAverage(cand, wts);
            sFrames[i].refinedQuat = qNew;
            mat3FromQuat(qNew, mats.data() + i * 9);
        }
    }

    int adjusted = 0;
    for (int i = 0; i < n; i++) {
        if (std::sqrt(quatAngularDistSq(sFrames[i].refinedQuat, sFrames[i].fastCorrectedQuat)) > 0.01) {
            adjusted++;
        }
        sFrames[i].fastCorrectedQuat = sFrames[i].refinedQuat;
    }
    LOGI("Feature pose graph seed: %d frames adjusted from %zu edges", adjusted, sFeatureEdges.size());
    if (adjusted > 0 && !skipOverlapRebuild) rebuildOverlapBankLocked();
    return adjusted;
}

/** Isolated benchmark: gray + BRIEF detect, mutual match, RANSAC (cold + 3x warm). */
static void profileFeatureMatcherLocked(
    const int32_t* pxA, int wA, int hA,
    const int32_t* pxB, int wB, int hB) {

    double tanH, tanV;
    sessionTanHalfFov(tanH, tanV);

    auto t0 = SteadyClock::now();
    FrameFeatures fa;
    buildFrameFeaturesFromPixels(pxA, wA, hA, fa);
    sphereProfileLogUs("bench_feature_detect_a", elapsedUs(t0));

    t0 = SteadyClock::now();
    FrameFeatures fb;
    buildFrameFeaturesFromPixels(pxB, wB, hB, fb);
    sphereProfileLogUs("bench_feature_detect_b", elapsedUs(t0));

    __android_log_print(
        ANDROID_LOG_INFO, SPHERE_PROFILE_TAG,
        "SphereProfile bench_feature_kps_a=%zu bench_feature_kps_b=%zu",
        fa.kps.size(), fb.kps.size());

    t0 = SteadyClock::now();
    std::vector<FeatureMatch> matches;
    matchFeaturesMutual(fa.kps, fb.kps, matches);
    sphereProfileLogUs("bench_feature_mutual_match", elapsedUs(t0));

    t0 = SteadyClock::now();
    FeatureRotationResult result;
    const bool ok = estimateFeatureRotationFromMatches(
        fa, fb, matches, tanH, tanV, result);
    sphereProfileLogUs("bench_feature_ransac", elapsedUs(t0));

    __android_log_print(
        ANDROID_LOG_INFO, SPHERE_PROFILE_TAG,
        "SphereProfile bench_feature_matches=%zu inliers=%d ratio=%.2f cells=%d "
        "conf=%d meanRes=%.6f valid=%d",
        matches.size(), result.inliers, result.inlierRatio, result.occupiedCells,
        static_cast<int>(result.confidence), result.meanResidual, ok ? 1 : 0);

    t0 = SteadyClock::now();
    for (int rep = 0; rep < 3; rep++) {
        FrameFeatures fbt;
        buildFrameFeaturesFromPixels(pxB, wB, hB, fbt);
        matchFeaturesMutual(fa.kps, fbt.kps, matches);
        estimateFeatureRotationFromMatches(fa, fbt, matches, tanH, tanV, result);
    }
    sphereProfileLogUs("bench_feature_pipeline_x3", elapsedUs(t0));
}

/** Benchmark feature graph edge build for stored frames. */
static void profileFeatureGraphLocked() {
    if (sFrames.size() < 2) {
        LOGW("SphereProfile feature_graph_skip (need >=2 frames, have %zu)", sFrames.size());
        return;
    }
    auto t0 = SteadyClock::now();
    buildFeatureEdgesLocked();
    sphereProfileLogUs("bench_feature_graph_total", elapsedUs(t0));
    __android_log_print(
        ANDROID_LOG_INFO, SPHERE_PROFILE_TAG,
        "SphereProfile bench_feature_graph_frames=%zu edges=%zu",
        sFrames.size(), sFeatureEdges.size());
}

#endif  // SPHERE_FEATURE_MATCH_INL
