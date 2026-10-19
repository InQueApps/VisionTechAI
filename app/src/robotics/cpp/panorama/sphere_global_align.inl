// Global rotation-only alignment for stored sphere frames.
// Included from sphere_jni.cpp after StoredFrame / camera model helpers.
//
//  1. Oriented-BRIEF corners per frame (binary tests: invariant to exposure, WB, DC offset).
//  2. Candidate pairs from predicted overlap + capture neighbours; rotation RANSAC per pair.
//  3. Max-inlier spanning tree init, then robust ray bundle adjustment over all frames.
//  4. Gauge fixed to the robust mean of IMU priors (keeps gravity, ignores IMU outliers).
//  5. Photometric fit: shared vignette + per-frame per-channel affine (gain + DC offset).
//  6. Frames without visual constraints or with relative blur become fill-only.

#ifndef SPHERE_GLOBAL_ALIGN_INL
#define SPHERE_GLOBAL_ALIGN_INL

#include <thread>
#include <numeric>
#include <cstdlib>
#include <cstdint>
#include <mutex>

static float kSrgbToLin[256];
static uint8_t kLinToSrgb[4096];
static std::once_flag kSrgbLutOnce;

static void initSrgbLuts() {
    std::call_once(kSrgbLutOnce, [] {
        for (int i = 0; i < 256; i++) {
            const double u = i / 255.0;
            kSrgbToLin[i] = (float)(u <= 0.04045 ? u / 12.92 : std::pow((u + 0.055) / 1.055, 2.4));
        }
        for (int i = 0; i < 4096; i++) {
            const double l = (i / 4095.0) * 8.0;
            const double u = l <= 0.0031308 ? 12.92 * l : 1.055 * std::pow(std::max(0.0, l), 1.0 / 2.4) - 0.055;
            kLinToSrgb[i] = (uint8_t)std::lround(std::max(0.0, std::min(1.0, u)) * 255.0);
        }
    });
}

static inline float srgb8ToLin(int s) {
    return kSrgbToLin[std::max(0, std::min(255, s))];
}

static inline float linToSrgb8(double l) {
    if (l <= 0.0) return 0.f;
    int i = (int)std::lround(l / 8.0 * 4095.0);
    if (i >= 4095) return 255.f;
    if (i < 0) return 0.f;
    return (float)kLinToSrgb[i];
}

namespace ga {

constexpr int GRAY_MAX_DIM = 640;
constexpr int GRID_COLS = 10;
constexpr int GRID_ROWS = 8;
constexpr int KPS_PER_CELL = 7;
constexpr int DESC_BITS = 256;
constexpr int DESC_WORDS = DESC_BITS / 64;
constexpr int ANGLE_BINS = 32;
constexpr int PATTERN_RADIUS = 13;
constexpr int ORIENT_RADIUS = 12;
constexpr int BORDER = 18;
constexpr int MAX_HAMMING = 72;
constexpr float RATIO = 0.82f;
constexpr int MIN_MATCHES = 16;
constexpr int MIN_INLIERS = 16;
constexpr float MIN_INLIER_RATIO = 0.2f;
constexpr int MAX_OBS_PER_EDGE = 160;
constexpr int VIDEO_NEIGHBOURS = 12;
constexpr int TEMPORAL_NEIGHBOURS = 2;
constexpr double EDGE_DROP_MEDIAN_DEG = 0.6;
constexpr double BLUR_RATIO = 0.55;
constexpr int PHOTO_LOWRES = 96;
constexpr double DENSE_ACCEPT_NCC = 0.6;
constexpr int SEAM_GN = 5;

struct Keypoint {
    float x = 0.f, y = 0.f;   // gray-image coordinates (sub-pixel)
    int ix = 0, iy = 0;       // integer detection location
    double ray[3]{};          // camera ray
    uint64_t desc[DESC_WORDS]{};    // oriented (unrectified fallback matching)
    uint64_t upright[DESC_WORDS]{}; // axis-aligned (rectified matching)
};

struct FrameData {
    int gw = 0, gh = 0;
    float sx = 1.f, sy = 1.f;  // gray → stored frame pixel scale
    int fw = 0, fh = 0;        // stored frame size
    std::vector<float> smooth;  // σ≈1 smoothed gray (detection, sharpness)
    std::vector<float> descImg; // σ≈1.4 smoothed gray (descriptors)
    std::vector<Keypoint> kps;
    std::vector<float> low;     // PHOTO_LOWRES RGB (area averaged)
    int lw = 0, lh = 0;
};

struct Edge {
    int a = 0, b = 0;
    double R[9]{};              // a_ray ≈ R · b_ray
    int inliers = 0;
    std::vector<float> obs;     // (a_ray xyz, b_ray xyz) per observation
    bool active = true;
};

struct FrameState {
    bool connected = false;
    bool fillOnly = false;
    bool discard = false;
    float sharpness = 1.f;
    float denseNcc = 0.f;
    float gain[3] = {1.f, 1.f, 1.f};
    float offset[3] = {0.f, 0.f, 0.f};
    float seamG[SEAM_GN * SEAM_GN * 3];
};

}  // namespace ga

static std::vector<ga::FrameState> sGaFrames;
static bool sGaValid = false;
static bool sGaDebug = std::getenv("SPHERE_GA_DEBUG") != nullptr;
/** Fraction of the last composed equirect not covered by any frame (before hole fill). */
static float sGaHoleFraction = 0.f;
static int sGaFrameCount = 0;
static double sGaVig[2] = {0.0, 0.0};  // log V(r) = a1 r² + a2 r⁴, r² ∈ [0,1] (corner = 1)
/** First accepted frame's camera EV; stitch gains are relative to this look. */
static float sRefCameraEv = 0.f;
static bool sHaveCameraEv = false;

static void gaResetLocked() {
    sGaFrames.clear();
    sGaValid = false;
    sGaFrameCount = 0;
    sGaVig[0] = sGaVig[1] = 0.0;
    sRefCameraEv = 0.f;
    sHaveCameraEv = false;
}

static bool gaValidForCurrentFramesLocked() {
    return sGaValid && sGaFrameCount == (int)sFrames.size() &&
           sGaFrames.size() == sFrames.size();
}

namespace ga {

struct PatternPair { int8_t x1, y1, x2, y2; };

static std::vector<std::vector<PatternPair>> buildPatternTable() {
    std::vector<std::vector<PatternPair>> table;
    {
        uint32_t s = 0x2545F491u;
        auto rnd = [&]() {
            s ^= s << 13; s ^= s >> 17; s ^= s << 5;
            return (s & 0xFFFFFF) / double(0x1000000);
        };
        auto gauss = [&]() {
            double u1 = std::max(1e-9, rnd()), u2 = rnd();
            return std::sqrt(-2.0 * std::log(u1)) * std::cos(2.0 * PI * u2);
        };
        std::vector<double> base;
        while ((int)base.size() < DESC_BITS * 4) {
            double x1 = gauss() * 5.0, y1 = gauss() * 5.0;
            double x2 = gauss() * 5.0, y2 = gauss() * 5.0;
            if (x1 * x1 + y1 * y1 > PATTERN_RADIUS * PATTERN_RADIUS) continue;
            if (x2 * x2 + y2 * y2 > PATTERN_RADIUS * PATTERN_RADIUS) continue;
            if (std::fabs(x1 - x2) + std::fabs(y1 - y2) < 2.0) continue;
            base.insert(base.end(), {x1, y1, x2, y2});
        }
        table.resize(ANGLE_BINS);
        for (int b = 0; b < ANGLE_BINS; b++) {
            const double a = 2.0 * PI * b / ANGLE_BINS;
            const double c = std::cos(a), sn = std::sin(a);
            auto& out = table[b];
            out.resize(DESC_BITS);
            for (int i = 0; i < DESC_BITS; i++) {
                const double* p = &base[i * 4];
                out[i].x1 = (int8_t)std::lround(c * p[0] - sn * p[1]);
                out[i].y1 = (int8_t)std::lround(sn * p[0] + c * p[1]);
                out[i].x2 = (int8_t)std::lround(c * p[2] - sn * p[3]);
                out[i].y2 = (int8_t)std::lround(sn * p[2] + c * p[3]);
            }
        }
    }
    return table;
}

static const std::vector<PatternPair>& rotatedPattern(int bin) {
    static const std::vector<std::vector<PatternPair>> table = buildPatternTable();
    return table[bin];
}

static void binomialBlur(const std::vector<float>& in, int w, int h, std::vector<float>& out) {
    std::vector<float> tmp((size_t)w * h);
    out.assign((size_t)w * h, 0.f);
    for (int y = 0; y < h; y++) {
        const float* r = &in[(size_t)y * w];
        float* t = &tmp[(size_t)y * w];
        for (int x = 0; x < w; x++) {
            const int x0 = std::max(0, x - 2), x1 = std::max(0, x - 1);
            const int x3 = std::min(w - 1, x + 1), x4 = std::min(w - 1, x + 2);
            t[x] = (r[x0] + 4.f * r[x1] + 6.f * r[x] + 4.f * r[x3] + r[x4]) * (1.f / 16.f);
        }
    }
    for (int y = 0; y < h; y++) {
        const int y0 = std::max(0, y - 2), y1 = std::max(0, y - 1);
        const int y3 = std::min(h - 1, y + 1), y4 = std::min(h - 1, y + 2);
        for (int x = 0; x < w; x++) {
            out[(size_t)y * w + x] =
                (tmp[(size_t)y0 * w + x] + 4.f * tmp[(size_t)y1 * w + x] +
                 6.f * tmp[(size_t)y * w + x] + 4.f * tmp[(size_t)y3 * w + x] +
                 tmp[(size_t)y4 * w + x]) * (1.f / 16.f);
        }
    }
}

/** Area-averaged downscale of ARGB to float gray (and optionally RGB). */
static void areaDownscale(const int32_t* px, int w, int h, int maxDim,
                          std::vector<float>& gray, std::vector<float>* rgb,
                          int& ow, int& oh) {
    const double s = std::max(1.0, (double)std::max(w, h) / maxDim);
    ow = std::max(16, (int)std::lround(w / s));
    oh = std::max(16, (int)std::lround(h / s));
    gray.assign((size_t)ow * oh, 0.f);
    if (rgb) rgb->assign((size_t)ow * oh * 3, 0.f);
    for (int y = 0; y < oh; y++) {
        const int sy0 = (int)((double)y * h / oh);
        const int sy1 = std::max(sy0 + 1, (int)((double)(y + 1) * h / oh));
        for (int x = 0; x < ow; x++) {
            const int sx0 = (int)((double)x * w / ow);
            const int sx1 = std::max(sx0 + 1, (int)((double)(x + 1) * w / ow));
            double r = 0, g = 0, b = 0;
            int n = 0;
            for (int yy = sy0; yy < std::min(h, sy1); yy++) {
                const int32_t* row = px + (size_t)yy * w;
                for (int xx = sx0; xx < std::min(w, sx1); xx++) {
                    const int32_t p = row[xx];
                    r += (p >> 16) & 0xff;
                    g += (p >> 8) & 0xff;
                    b += p & 0xff;
                    n++;
                }
            }
            if (n == 0) n = 1;
            r /= n; g /= n; b /= n;
            gray[(size_t)y * ow + x] = (float)(0.299 * r + 0.587 * g + 0.114 * b);
            if (rgb) {
                float* o = &(*rgb)[((size_t)y * ow + x) * 3];
                o[0] = (float)r; o[1] = (float)g; o[2] = (float)b;
            }
        }
    }
}

static void detectAndDescribe(const StoredFrame& f, FrameData& fd, double tanH, double tanV) {
    std::vector<float> gray;
    areaDownscale(f.px.data(), f.w, f.h, GRAY_MAX_DIM, gray, nullptr, fd.gw, fd.gh);
    const int gw = fd.gw, gh = fd.gh;
    fd.sx = (float)f.w / gw;
    fd.sy = (float)f.h / gh;
    fd.fw = f.w;
    fd.fh = f.h;
    binomialBlur(gray, gw, gh, fd.smooth);
    binomialBlur(fd.smooth, gw, gh, fd.descImg);
    const std::vector<float>& desc = fd.descImg;

    // Shi–Tomasi min-eigenvalue response on 5×5 window.
    const size_t N = (size_t)gw * gh;
    std::vector<float> ixx(N, 0.f), iyy(N, 0.f), ixy(N, 0.f);
    for (int y = 1; y < gh - 1; y++) {
        for (int x = 1; x < gw - 1; x++) {
            const size_t i = (size_t)y * gw + x;
            const float gx = 0.5f * (fd.smooth[i + 1] - fd.smooth[i - 1]);
            const float gy = 0.5f * (fd.smooth[i + gw] - fd.smooth[i - gw]);
            ixx[i] = gx * gx; iyy[i] = gy * gy; ixy[i] = gx * gy;
        }
    }
    auto box5 = [&](std::vector<float>& v) {
        std::vector<float> t(N, 0.f);
        for (int y = 0; y < gh; y++) {
            float acc = 0.f;
            const float* r = &v[(size_t)y * gw];
            float* o = &t[(size_t)y * gw];
            for (int x = 0; x < gw; x++) {
                acc += r[x];
                if (x >= 5) acc -= r[x - 5];
                if (x >= 2) o[x - 2] = acc;
            }
        }
        for (int x = 0; x < gw; x++) {
            float acc = 0.f;
            for (int y = 0; y < gh; y++) {
                acc += t[(size_t)y * gw + x];
                if (y >= 5) acc -= t[(size_t)(y - 5) * gw + x];
                if (y >= 2) v[(size_t)(y - 2) * gw + x] = acc;
            }
        }
    };
    box5(ixx); box5(iyy); box5(ixy);
    std::vector<float> resp(N, 0.f);
    float maxResp = 0.f;
    for (int y = BORDER; y < gh - BORDER; y++) {
        for (int x = BORDER; x < gw - BORDER; x++) {
            const size_t i = (size_t)y * gw + x;
            const float a = ixx[i], c = iyy[i], b = ixy[i];
            const float hd = 0.5f * (a - c);
            const float lmin = 0.5f * (a + c) - std::sqrt(hd * hd + b * b);
            resp[i] = lmin;
            maxResp = std::max(maxResp, lmin);
        }
    }
    const float thresh = std::max(10.f, 0.001f * maxResp);

    struct Cand { int x, y; float r; };
    fd.kps.clear();
    const auto& pattern0 = rotatedPattern(0);
    (void)pattern0;
    for (int cy = 0; cy < GRID_ROWS; cy++) {
        const int y0 = std::max(BORDER, cy * gh / GRID_ROWS);
        const int y1 = std::min(gh - BORDER, (cy + 1) * gh / GRID_ROWS);
        for (int cx = 0; cx < GRID_COLS; cx++) {
            const int x0 = std::max(BORDER, cx * gw / GRID_COLS);
            const int x1 = std::min(gw - BORDER, (cx + 1) * gw / GRID_COLS);
            std::vector<Cand> cands;
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    const size_t i = (size_t)y * gw + x;
                    const float r = resp[i];
                    if (r < thresh) continue;
                    if (r < resp[i - 1] || r < resp[i + 1] || r < resp[i - gw] || r < resp[i + gw] ||
                        r < resp[i - gw - 1] || r < resp[i - gw + 1] ||
                        r < resp[i + gw - 1] || r < resp[i + gw + 1]) continue;
                    cands.push_back({x, y, r});
                }
            }
            std::sort(cands.begin(), cands.end(), [](const Cand& a, const Cand& b) { return a.r > b.r; });
            int kept = 0;
            std::vector<Cand> chosen;
            for (const Cand& c : cands) {
                bool close = false;
                for (const Cand& o : chosen) {
                    if ((o.x - c.x) * (o.x - c.x) + (o.y - c.y) * (o.y - c.y) < 25) { close = true; break; }
                }
                if (close) continue;
                chosen.push_back(c);
                if (++kept >= KPS_PER_CELL) break;
            }
            for (const Cand& c : chosen) {
                double m10 = 0, m01 = 0;
                for (int dy = -ORIENT_RADIUS; dy <= ORIENT_RADIUS; dy++) {
                    const int span = (int)std::sqrt((double)(ORIENT_RADIUS * ORIENT_RADIUS - dy * dy));
                    const float* row = &fd.smooth[(size_t)(c.y + dy) * gw];
                    for (int dx = -span; dx <= span; dx++) {
                        const float v = row[c.x + dx];
                        m10 += dx * v;
                        m01 += dy * v;
                    }
                }
                double ang = std::atan2(m01, m10);
                if (ang < 0) ang += 2.0 * PI;
                const int bin = ((int)std::lround(ang / (2.0 * PI) * ANGLE_BINS)) % ANGLE_BINS;
                const auto& pat = rotatedPattern(bin);
                Keypoint kp;
                kp.ix = c.x;
                kp.iy = c.y;
                {
                    const size_t i = (size_t)c.y * gw + c.x;
                    const double l = resp[i - 1], r = resp[i + 1], u = resp[i - gw], dn = resp[i + gw];
                    const double cc = resp[i];
                    const double denX = l - 2.0 * cc + r, denY = u - 2.0 * cc + dn;
                    const double ox = denX < -1e-9 ? std::max(-0.5, std::min(0.5, 0.5 * (l - r) / denX)) : 0.0;
                    const double oy = denY < -1e-9 ? std::max(-0.5, std::min(0.5, 0.5 * (u - dn) / denY)) : 0.0;
                    kp.x = (float)(c.x + ox);
                    kp.y = (float)(c.y + oy);
                }
                const auto& upat = rotatedPattern(0);
                for (int d = 0; d < DESC_BITS; d++) {
                    const PatternPair& p = pat[d];
                    const float v1 = desc[(size_t)(c.y + p.y1) * gw + c.x + p.x1];
                    const float v2 = desc[(size_t)(c.y + p.y2) * gw + c.x + p.x2];
                    if (v1 < v2) kp.desc[d >> 6] |= (uint64_t)1 << (d & 63);
                    const PatternPair& u = upat[d];
                    const float u1 = desc[(size_t)(c.y + u.y1) * gw + c.x + u.x1];
                    const float u2 = desc[(size_t)(c.y + u.y2) * gw + c.x + u.x2];
                    if (u1 < u2) kp.upright[d >> 6] |= (uint64_t)1 << (d & 63);
                }
                const double fx = (kp.x + 0.5) * fd.sx - 0.5;
                const double fy = (kp.y + 0.5) * fd.sy - 0.5;
                pixelToRayFloat(fx, fy, f.w, f.h, tanH, tanV, kp.ray[0], kp.ray[1], kp.ray[2]);
                fd.kps.push_back(kp);
            }
        }
    }

    std::vector<float> lowGray;
    areaDownscale(f.px.data(), f.w, f.h, PHOTO_LOWRES, lowGray, &fd.low, fd.lw, fd.lh);
}

static inline int hamming(const uint64_t* a, const uint64_t* b) {
    int d = 0;
    for (int i = 0; i < DESC_WORDS; i++) d += popcount64(a[i] ^ b[i]);
    return d;
}

static void matchMutual(const FrameData& A, const FrameData& B,
                        std::vector<std::pair<int, int>>& out) {
    out.clear();
    const int na = (int)A.kps.size(), nb = (int)B.kps.size();
    if (na == 0 || nb == 0) return;
    std::vector<int> bestA(na, -1), d1A(na, 1 << 20), d2A(na, 1 << 20);
    std::vector<int> bestB(nb, -1), d1B(nb, 1 << 20);
    for (int i = 0; i < na; i++) {
        const uint64_t* da = A.kps[i].desc;
        for (int j = 0; j < nb; j++) {
            const int d = hamming(da, B.kps[j].desc);
            if (d < d1A[i]) { d2A[i] = d1A[i]; d1A[i] = d; bestA[i] = j; }
            else if (d < d2A[i]) d2A[i] = d;
            if (d < d1B[j]) { d1B[j] = d; bestB[j] = i; }
        }
    }
    for (int i = 0; i < na; i++) {
        const int j = bestA[i];
        if (j < 0 || bestB[j] != i) continue;
        if (d1A[i] > MAX_HAMMING) continue;
        if (d1A[i] > RATIO * d2A[i]) continue;
        out.push_back({i, j});
    }
}

static inline void grayToRay(const FrameData& F, double gx, double gy,
                             double tanH, double tanV, double* r) {
    pixelToRayFloat((gx + 0.5) * F.sx - 0.5, (gy + 0.5) * F.sy - 0.5,
                    F.fw, F.fh, tanH, tanV, r[0], r[1], r[2]);
}

static inline bool rayToGray(const FrameData& F, const double* r,
                             double tanH, double tanV, double& gx, double& gy) {
    if (r[2] >= -1e-6) return false;
    double nx = r[0] / -r[2], ny = r[1] / -r[2];
    if (sMirrorCameraX) nx = -nx;
    distortNorm(nx, ny);
    const double fx = (nx / (2.0 * tanH) + 0.5) * (F.fw - 1);
    const double fy = (0.5 - ny / (2.0 * tanV)) * (F.fh - 1);
    gx = (fx + 0.5) / F.sx - 0.5;
    gy = (fy + 0.5) / F.sy - 0.5;
    return true;
}

static inline float bilinearGray(const std::vector<float>& img, int w, int h, double x, double y) {
    x = std::max(0.0, std::min(w - 1.001, x));
    y = std::max(0.0, std::min(h - 1.001, y));
    const int x0 = (int)x, y0 = (int)y;
    const double ax = x - x0, ay = y - y0;
    const float* p = &img[(size_t)y0 * w + x0];
    return (float)((1 - ax) * (1 - ay) * p[0] + ax * (1 - ay) * p[1] +
                   (1 - ax) * ay * p[w] + ax * ay * p[w + 1]);
}

/**
 * Descriptor of B's keypoint resampled into A's image plane through the predicted
 * rotation (local affine of the homography). Removes perspective / keystone change.
 */
static bool rectifiedDescriptor(const FrameData& A, const FrameData& B, const Keypoint& kb,
                                const double* Rab, double tanH, double tanV, uint64_t* out) {
    double ra[3];
    mat3RotateVec(Rab, kb.ray, ra);
    double pax, pay;
    if (!rayToGray(A, ra, tanH, tanV, pax, pay)) return false;
    if (pax < -0.15 * A.gw || pax > 1.15 * A.gw || pay < -0.15 * A.gh || pay > 1.15 * A.gh) return false;
    double Rt[9];
    mat3Transpose(Rab, Rt);
    auto aToB = [&](double ax, double ay, double& bx, double& by) {
        double r[3], rb[3];
        grayToRay(A, ax, ay, tanH, tanV, r);
        mat3RotateVec(Rt, r, rb);
        return rayToGray(B, rb, tanH, tanV, bx, by);
    };
    constexpr double h = 4.0;
    double bx0, by0, bx1, by1, bx2, by2;
    if (!aToB(pax, pay, bx0, by0) || !aToB(pax + h, pay, bx1, by1) || !aToB(pax, pay + h, bx2, by2)) return false;
    const double j00 = (bx1 - bx0) / h, j10 = (by1 - by0) / h;
    const double j01 = (bx2 - bx0) / h, j11 = (by2 - by0) / h;
    const double det = j00 * j11 - j01 * j10;
    if (det < 0.2 || det > 5.0) return false;
    const double cx = kb.ix, cy = kb.iy;
    const auto& pat = rotatedPattern(0);
    for (int w = 0; w < DESC_WORDS; w++) out[w] = 0;
    for (int d = 0; d < DESC_BITS; d++) {
        const PatternPair& p = pat[d];
        const float v1 = bilinearGray(B.descImg, B.gw, B.gh, cx + j00 * p.x1 + j01 * p.y1, cy + j10 * p.x1 + j11 * p.y1);
        const float v2 = bilinearGray(B.descImg, B.gw, B.gh, cx + j00 * p.x2 + j01 * p.y2, cy + j10 * p.x2 + j11 * p.y2);
        if (v1 < v2) out[d >> 6] |= (uint64_t)1 << (d & 63);
    }
    return true;
}

/** Keypoints of A whose ray lands inside B (with margin) under a ≈ R b. */
static void overlapSubset(const FrameData& A, const FrameData& B, const double* Rab,
                          double tanH, double tanV, std::vector<int>& idx) {
    idx.clear();
    double Rt[9];
    mat3Transpose(Rab, Rt);
    for (int i = 0; i < (int)A.kps.size(); i++) {
        double rb[3], gx, gy;
        mat3RotateVec(Rt, A.kps[i].ray, rb);
        if (!rayToGray(B, rb, tanH, tanV, gx, gy)) continue;
        if (gx < -0.15 * B.gw || gx > 1.15 * B.gw || gy < -0.15 * B.gh || gy > 1.15 * B.gh) continue;
        idx.push_back(i);
    }
}

/** Mutual + ratio matching between two descriptor sets. */
static void matchSets(const std::vector<const uint64_t*>& da, const std::vector<const uint64_t*>& db,
                      std::vector<std::pair<int, int>>& out) {
    out.clear();
    const int na = (int)da.size(), nb = (int)db.size();
    if (na == 0 || nb == 0) return;
    std::vector<int> bestA(na, -1), d1A(na, 1 << 20), d2A(na, 1 << 20);
    std::vector<int> bestB(nb, -1), d1B(nb, 1 << 20);
    for (int i = 0; i < na; i++) {
        for (int j = 0; j < nb; j++) {
            const int d = hamming(da[i], db[j]);
            if (d < d1A[i]) { d2A[i] = d1A[i]; d1A[i] = d; bestA[i] = j; }
            else if (d < d2A[i]) d2A[i] = d;
            if (d < d1B[j]) { d1B[j] = d; bestB[j] = i; }
        }
    }
    for (int i = 0; i < na; i++) {
        const int j = bestA[i];
        if (j < 0 || bestB[j] != i) continue;
        if (d1A[i] > MAX_HAMMING) continue;
        if (d1A[i] > RATIO * d2A[i]) continue;
        out.push_back({i, j});
    }
}

/** Horn absolute orientation via 4×4 Jacobi eigen-solve: a ≈ R b. */
static void wahba(const double* a, const double* b, const double* w, int n, double* R) {
    double S[9] = {};
    for (int k = 0; k < n; k++) {
        const double wk = w ? w[k] : 1.0;
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++) S[r * 3 + c] += wk * b[k * 3 + r] * a[k * 3 + c];
    }
    double N[4][4] = {
        {S[0] + S[4] + S[8], S[5] - S[7], S[6] - S[2], S[1] - S[3]},
        {S[5] - S[7], S[0] - S[4] - S[8], S[1] + S[3], S[6] + S[2]},
        {S[6] - S[2], S[1] + S[3], -S[0] + S[4] - S[8], S[5] + S[7]},
        {S[1] - S[3], S[6] + S[2], S[5] + S[7], -S[0] - S[4] + S[8]}};
    double V[4][4] = {{1, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, 1, 0}, {0, 0, 0, 1}};
    for (int sweep = 0; sweep < 30; sweep++) {
        double off = 0;
        for (int p = 0; p < 4; p++) for (int q = p + 1; q < 4; q++) off += N[p][q] * N[p][q];
        if (off < 1e-24) break;
        for (int p = 0; p < 4; p++) {
            for (int q = p + 1; q < 4; q++) {
                if (std::fabs(N[p][q]) < 1e-30) continue;
                const double theta = (N[q][q] - N[p][p]) / (2.0 * N[p][q]);
                const double t = (theta >= 0 ? 1.0 : -1.0) / (std::fabs(theta) + std::sqrt(theta * theta + 1.0));
                const double c = 1.0 / std::sqrt(t * t + 1.0), s = t * c;
                for (int k = 0; k < 4; k++) {
                    const double nkp = N[k][p], nkq = N[k][q];
                    N[k][p] = c * nkp - s * nkq;
                    N[k][q] = s * nkp + c * nkq;
                }
                for (int k = 0; k < 4; k++) {
                    const double npk = N[p][k], nqk = N[q][k];
                    N[p][k] = c * npk - s * nqk;
                    N[q][k] = s * npk + c * nqk;
                }
                for (int k = 0; k < 4; k++) {
                    const double vkp = V[k][p], vkq = V[k][q];
                    V[k][p] = c * vkp - s * vkq;
                    V[k][q] = s * vkp + c * vkq;
                }
            }
        }
    }
    int best = 0;
    for (int i = 1; i < 4; i++) if (N[i][i] > N[best][best]) best = i;
    Quat q{V[1][best], V[2][best], V[3][best], V[0][best]};
    const double l = std::sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w);
    if (l < 1e-12) q = {0, 0, 0, 1};
    else { q.x /= l; q.y /= l; q.z /= l; q.w /= l; }
    mat3FromQuat(q, R);
}

static inline double rotAngleDeg(const double* R) {
    const double tr = std::max(-1.0, std::min(3.0, R[0] + R[4] + R[8]));
    return std::acos(std::max(-1.0, std::min(1.0, (tr - 1.0) * 0.5))) * RAD2DEG;
}

/** Rotation RANSAC on matched rays; returns inlier indices into `ma/mb`. */
static bool ransacRotation(const std::vector<double>& ra, const std::vector<double>& rb,
                           double cosThresh, double* R, std::vector<int>& inl) {
    const int n = (int)(ra.size() / 3);
    inl.clear();
    if (n < MIN_MATCHES) return false;
    uint32_t s = 0x9E3779B9u ^ (uint32_t)(n * 2654435761u);
    auto rnd = [&]() { s ^= s << 13; s ^= s >> 17; s ^= s << 5; return s; };
    int bestCount = 0;
    double bestR[9];
    int maxIter = 400;
    std::vector<int> cur;
    for (int it = 0; it < maxIter; it++) {
        int i0 = rnd() % n, i1 = rnd() % n, i2 = rnd() % n;
        if (i0 == i1 || i0 == i2 || i1 == i2) continue;
        double sa[9], sb[9];
        const int idx[3] = {i0, i1, i2};
        for (int k = 0; k < 3; k++) {
            memcpy(sa + k * 3, &ra[idx[k] * 3], 3 * sizeof(double));
            memcpy(sb + k * 3, &rb[idx[k] * 3], 3 * sizeof(double));
        }
        double Rc[9];
        wahba(sa, sb, nullptr, 3, Rc);
        int cnt = 0;
        for (int k = 0; k < n; k++) {
            double p[3];
            mat3RotateVec(Rc, &rb[k * 3], p);
            if (p[0] * ra[k * 3] + p[1] * ra[k * 3 + 1] + p[2] * ra[k * 3 + 2] > cosThresh) cnt++;
        }
        if (cnt > bestCount) {
            bestCount = cnt;
            memcpy(bestR, Rc, sizeof(bestR));
            const double w = (double)cnt / n;
            const double denom = std::log(std::max(1e-12, 1.0 - w * w * w));
            if (denom < 0) maxIter = std::min(maxIter, (int)(std::log(1e-3) / denom) + 8);
        }
    }
    if (bestCount < MIN_INLIERS) return false;
    memcpy(R, bestR, sizeof(bestR));
    for (int pass = 0; pass < 3; pass++) {
        cur.clear();
        for (int k = 0; k < n; k++) {
            double p[3];
            mat3RotateVec(R, &rb[k * 3], p);
            if (p[0] * ra[k * 3] + p[1] * ra[k * 3 + 1] + p[2] * ra[k * 3 + 2] > cosThresh) cur.push_back(k);
        }
        if ((int)cur.size() < 3) return false;
        std::vector<double> ia(cur.size() * 3), ib(cur.size() * 3);
        for (size_t k = 0; k < cur.size(); k++) {
            memcpy(&ia[k * 3], &ra[cur[k] * 3], 3 * sizeof(double));
            memcpy(&ib[k * 3], &rb[cur[k] * 3], 3 * sizeof(double));
        }
        wahba(ia.data(), ib.data(), nullptr, (int)cur.size(), R);
    }
    inl = cur;
    return (int)inl.size() >= MIN_INLIERS && inl.size() >= MIN_INLIER_RATIO * n;
}

static inline void skew(const double* v, double* M) {
    M[0] = 0; M[1] = -v[2]; M[2] = v[1];
    M[3] = v[2]; M[4] = 0; M[5] = -v[0];
    M[6] = -v[1]; M[7] = v[0]; M[8] = 0;
}

/** Dense Cholesky solve of A x = b (A symmetric positive definite, n×n). */
static bool choleskySolve(std::vector<double>& A, std::vector<double>& b, int n) {
    for (int j = 0; j < n; j++) {
        double d = A[(size_t)j * n + j];
        for (int k = 0; k < j; k++) d -= A[(size_t)j * n + k] * A[(size_t)j * n + k];
        if (d <= 1e-18) return false;
        d = std::sqrt(d);
        A[(size_t)j * n + j] = d;
        for (int i = j + 1; i < n; i++) {
            double s = A[(size_t)i * n + j];
            for (int k = 0; k < j; k++) s -= A[(size_t)i * n + k] * A[(size_t)j * n + k];
            A[(size_t)i * n + j] = s / d;
        }
    }
    for (int i = 0; i < n; i++) {
        double s = b[i];
        for (int k = 0; k < i; k++) s -= A[(size_t)i * n + k] * b[k];
        b[i] = s / A[(size_t)i * n + i];
    }
    for (int i = n - 1; i >= 0; i--) {
        double s = b[i];
        for (int k = i + 1; k < n; k++) s -= A[(size_t)k * n + i] * b[k];
        b[i] = s / A[(size_t)i * n + i];
    }
    return true;
}

static inline Quat quatFromRotVec(const double* d) {
    return quatExpTangent(d[0], d[1], d[2]);
}

/** Robust ray bundle adjustment over all active edges; root frame is held fixed. */
static void bundleAdjust(std::vector<Edge>& edges, std::vector<Quat>& poses,
                         const std::vector<int>& comp, int root, double huber) {
    const int n = (int)poses.size();
    std::vector<int> varIdx(n, -1);
    int nv = 0;
    for (int i = 0; i < n; i++) if (comp[i] && i != root) varIdx[i] = nv++;
    if (nv == 0) return;
    const int dim = nv * 3;
    std::vector<double> H, g;
    double lambda = 1e-4;
    double prevCost = 1e300;
    for (int iter = 0; iter < 20; iter++) {
        H.assign((size_t)dim * dim, 0.0);
        g.assign(dim, 0.0);
        double cost = 0.0;
        std::vector<double> Ra((size_t)n * 9);
        for (int i = 0; i < n; i++) mat3FromQuat(poses[i], &Ra[(size_t)i * 9]);
        for (const Edge& e : edges) {
            if (!e.active || !comp[e.a] || !comp[e.b]) continue;
            const int va = varIdx[e.a], vb = varIdx[e.b];
            const double* MA = &Ra[(size_t)e.a * 9];
            const double* MB = &Ra[(size_t)e.b * 9];
            const int m = (int)(e.obs.size() / 6);
            for (int k = 0; k < m; k++) {
                const double a[3] = {e.obs[k * 6], e.obs[k * 6 + 1], e.obs[k * 6 + 2]};
                const double b[3] = {e.obs[k * 6 + 3], e.obs[k * 6 + 4], e.obs[k * 6 + 5]};
                double u[3], w[3];
                mat3RotateVec(MA, a, u);
                mat3RotateVec(MB, b, w);
                const double r[3] = {u[0] - w[0], u[1] - w[1], u[2] - w[2]};
                const double nr = std::sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]);
                const double wt = nr <= huber ? 1.0 : huber / nr;
                cost += nr <= huber ? 0.5 * nr * nr : huber * (nr - 0.5 * huber);
                double Ju[9], Jw[9];
                skew(u, Ju);  // J_a = -[u]x, J_b = +[w]x
                skew(w, Jw);
                for (int p = 0; p < 9; p++) Ju[p] = -Ju[p];
                const double* J[2] = {Ju, Jw};
                const int v[2] = {va, vb};
                for (int s = 0; s < 2; s++) {
                    if (v[s] < 0) continue;
                    for (int r0 = 0; r0 < 3; r0++) {
                        double gs = 0;
                        for (int c = 0; c < 3; c++) gs += J[s][c * 3 + r0] * r[c];
                        g[v[s] * 3 + r0] += wt * gs;
                    }
                    for (int t = 0; t < 2; t++) {
                        if (v[t] < 0) continue;
                        for (int r0 = 0; r0 < 3; r0++) {
                            for (int c0 = 0; c0 < 3; c0++) {
                                double h = 0;
                                for (int c = 0; c < 3; c++) h += J[s][c * 3 + r0] * J[t][c * 3 + c0];
                                H[(size_t)(v[s] * 3 + r0) * dim + v[t] * 3 + c0] += wt * h;
                            }
                        }
                    }
                }
            }
        }
        if (cost > prevCost * 1.0000001) lambda *= 10.0;
        else lambda = std::max(1e-7, lambda * 0.3);
        prevCost = std::min(prevCost, cost);
        for (int d = 0; d < dim; d++) H[(size_t)d * dim + d] += lambda + 1e-9;
        for (int d = 0; d < dim; d++) g[d] = -g[d];
        if (!choleskySolve(H, g, dim)) break;
        double maxStep = 0;
        for (int i = 0; i < n; i++) {
            if (varIdx[i] < 0) continue;
            const double* d = &g[varIdx[i] * 3];
            maxStep = std::max(maxStep, std::sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]));
            Quat q = quatMul(quatFromRotVec(d), poses[i]);
            const double l = std::sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w);
            poses[i] = {q.x / l, q.y / l, q.z / l, q.w / l};
        }
        if (maxStep < 1e-7) break;
    }
}

static double edgeMedianResidualDeg(const Edge& e, const std::vector<Quat>& poses) {
    double MA[9], MB[9];
    mat3FromQuat(poses[e.a], MA);
    mat3FromQuat(poses[e.b], MB);
    const int m = (int)(e.obs.size() / 6);
    std::vector<double> res(m);
    for (int k = 0; k < m; k++) {
        const double a[3] = {e.obs[k * 6], e.obs[k * 6 + 1], e.obs[k * 6 + 2]};
        const double b[3] = {e.obs[k * 6 + 3], e.obs[k * 6 + 4], e.obs[k * 6 + 5]};
        double u[3], w[3];
        mat3RotateVec(MA, a, u);
        mat3RotateVec(MB, b, w);
        const double d = std::max(-1.0, std::min(1.0, u[0] * w[0] + u[1] * w[1] + u[2] * w[2]));
        res[k] = std::acos(d) * RAD2DEG;
    }
    if (res.empty()) return 0.0;
    std::nth_element(res.begin(), res.begin() + m / 2, res.end());
    return res[m / 2];
}

/** Max-inlier spanning forest; returns component labels (largest = 1) and initial poses. */
static int spanningTreeInit(const std::vector<Edge>& edges, int n, const std::vector<Quat>& prior,
                            std::vector<Quat>& poses, std::vector<int>& comp) {
    std::vector<int> parent(n);
    std::iota(parent.begin(), parent.end(), 0);
    std::function<int(int)> find = [&](int x) { return parent[x] == x ? x : parent[x] = find(parent[x]); };
    std::vector<int> order;
    for (int i = 0; i < (int)edges.size(); i++) if (edges[i].active) order.push_back(i);
    std::sort(order.begin(), order.end(), [&](int x, int y) { return edges[x].inliers > edges[y].inliers; });
    std::vector<std::vector<int>> adj(n);
    for (int ei : order) {
        const int ra = find(edges[ei].a), rb = find(edges[ei].b);
        if (ra == rb) continue;
        parent[ra] = rb;
        adj[edges[ei].a].push_back(ei);
        adj[edges[ei].b].push_back(ei);
    }
    std::vector<int> size(n, 0);
    for (int i = 0; i < n; i++) size[find(i)]++;
    int bestRoot = 0;
    for (int i = 0; i < n; i++) if (size[find(i)] > size[find(bestRoot)]) bestRoot = i;
    const int label = find(bestRoot);
    std::vector<int> inl(n, 0);
    for (const Edge& e : edges) if (e.active) { inl[e.a] += e.inliers; inl[e.b] += e.inliers; }
    int root = -1;
    for (int i = 0; i < n; i++) {
        if (find(i) != label) continue;
        if (root < 0 || inl[i] > inl[root]) root = i;
    }
    comp.assign(n, 0);
    poses = prior;
    std::vector<int> stack{root};
    comp[root] = 1;
    while (!stack.empty()) {
        const int cur = stack.back();
        stack.pop_back();
        double Mc[9];
        mat3FromQuat(poses[cur], Mc);
        for (int ei : adj[cur]) {
            const Edge& e = edges[ei];
            const int other = e.a == cur ? e.b : e.a;
            if (comp[other]) continue;
            double M[9];
            if (e.a == cur) {
                mat3Mul(Mc, e.R, M);           // R_b = R_a · R_ab
            } else {
                double Rt[9];
                mat3Transpose(e.R, Rt);
                mat3Mul(Mc, Rt, M);            // R_a = R_b · R_abᵀ
            }
            poses[other] = quatFromMat3(M);
            comp[other] = 1;
            stack.push_back(other);
        }
    }
    return root;
}

/** Robust mean of q_prior ⊗ conj(q_sol) over connected frames. */
static Quat robustGauge(const std::vector<Quat>& prior, const std::vector<Quat>& sol,
                        const std::vector<int>& comp) {
    std::vector<Quat> g;
    for (size_t i = 0; i < sol.size(); i++) {
        if (comp[i]) g.push_back(quatMul(prior[i], quatConjugate(sol[i])));
    }
    if (g.empty()) return {0, 0, 0, 1};
    std::vector<double> w(g.size(), 1.0);
    Quat mean = g[0];
    for (int it = 0; it < 8; it++) {
        double sx = 0, sy = 0, sz = 0, sw = 0;
        for (size_t k = 0; k < g.size(); k++) {
            Quat q = g[k];
            if (q.x * mean.x + q.y * mean.y + q.z * mean.z + q.w * mean.w < 0) { q.x = -q.x; q.y = -q.y; q.z = -q.z; q.w = -q.w; }
            sx += w[k] * q.x; sy += w[k] * q.y; sz += w[k] * q.z; sw += w[k] * q.w;
        }
        const double l = std::sqrt(sx * sx + sy * sy + sz * sz + sw * sw);
        if (l < 1e-12) break;
        mean = {sx / l, sy / l, sz / l, sw / l};
        std::vector<double> dev(g.size());
        for (size_t k = 0; k < g.size(); k++) dev[k] = std::sqrt(quatAngularDistSq(g[k], mean));
        std::vector<double> sorted = dev;
        std::nth_element(sorted.begin(), sorted.begin() + sorted.size() / 2, sorted.end());
        const double cut = std::max(2.0 * DEG2RAD, 2.5 * sorted[sorted.size() / 2]);
        for (size_t k = 0; k < g.size(); k++) w[k] = dev[k] <= cut ? 1.0 : 0.0;
    }
    return mean;
}

struct PhotoSample {
    int i, j;
    float ci[3], cj[3];
    float ri2, rj2;
    float ui, vi, uj, vj;  // frame NDC [-1,1]
};

static inline bool sampleLow(const FrameData& fd, double lx, double ly, float* out, float* gradOut) {
    if (lx < 1.0 || ly < 1.0 || lx > fd.lw - 2.0 || ly > fd.lh - 2.0) return false;
    const int x0 = (int)lx, y0 = (int)ly;
    const double tx = lx - x0, ty = ly - y0;
    for (int c = 0; c < 3; c++) {
        auto at = [&](int x, int y) { return fd.low[((size_t)y * fd.lw + x) * 3 + c]; };
        out[c] = (float)((1 - tx) * (1 - ty) * at(x0, y0) + tx * (1 - ty) * at(x0 + 1, y0) +
                         (1 - tx) * ty * at(x0, y0 + 1) + tx * ty * at(x0 + 1, y0 + 1));
    }
    if (gradOut) {
        auto lum = [&](int x, int y) {
            const float* p = &fd.low[((size_t)y * fd.lw + x) * 3];
            return 0.299f * p[0] + 0.587f * p[1] + 0.114f * p[2];
        };
        const float gx = lum(x0 + 1, y0) - lum(x0 - 1 < 0 ? 0 : x0 - 1, y0);
        const float gy = lum(x0, y0 + 1) - lum(x0, y0 - 1 < 0 ? 0 : y0 - 1);
        *gradOut = std::sqrt(gx * gx + gy * gy) * 0.5f;
    }
    return true;
}

/**
 * Equalize AE/WB with shared vignette + per-frame RGB gain (inverse of observed log-brightness).
 * Residual DC is tightly clamped. Camera EV gains are kept when present.
 */
static void fitPhotometric(const std::vector<PhotoSample>& samples, int n,
                           const std::vector<int>& use,
                           double* vig, std::vector<FrameState>& st) {
    vig[0] = vig[1] = 0.0;
    for (int i = 0; i < n; i++) {
        st[i].offset[0] = st[i].offset[1] = st[i].offset[2] = 0.f;
    }
    if (sHaveCameraEv) return;
    if (samples.size() >= 20) {
        const int dim = n + 2;
        std::vector<double> wts(samples.size(), 1.0);
        std::vector<double> x(dim, 0.0);
        for (int it = 0; it < 5; it++) {
            std::vector<double> A((size_t)dim * dim, 0.0), b(dim, 0.0);
            for (size_t s = 0; s < samples.size(); s++) {
                const PhotoSample& p = samples[s];
                const double li = 0.299 * p.ci[0] + 0.587 * p.ci[1] + 0.114 * p.ci[2];
                const double lj = 0.299 * p.cj[0] + 0.587 * p.cj[1] + 0.114 * p.cj[2];
                const double y = std::log(std::max(1.0, li)) - std::log(std::max(1.0, lj));
                double row[4];
                int idx[4];
                row[0] = 1.0; idx[0] = p.i;
                row[1] = -1.0; idx[1] = p.j;
                row[2] = p.ri2 - p.rj2; idx[2] = n;
                row[3] = p.ri2 * p.ri2 - p.rj2 * p.rj2; idx[3] = n + 1;
                double pred = 0;
                for (int k = 0; k < 4; k++) pred += row[k] * x[idx[k]];
                const double res = y - pred;
                const double w = wts[s] = std::fabs(res) < 0.06 ? 1.0 : 0.06 / std::fabs(res);
                for (int r = 0; r < 4; r++) {
                    b[idx[r]] += w * row[r] * y;
                    for (int c = 0; c < 4; c++) A[(size_t)idx[r] * dim + idx[c]] += w * row[r] * row[c];
                }
            }
            const double ridge = 1e-3 * samples.size() / std::max(1, n);
            for (int i = 0; i < n; i++) {
                if (sHaveCameraEv) {
                    const double lg0 = std::log(std::max(0.05, (double)st[i].gain[0]));
                    const double lockW = 1e4 * samples.size() / std::max(1, n);
                    A[(size_t)i * dim + i] += lockW + (use[i] ? 0.0 : 1e6);
                    b[i] += lockW * lg0;
                } else {
                    A[(size_t)i * dim + i] += ridge + (use[i] ? 0.0 : 1e6);
                    for (int k = 0; k < n; k++) A[(size_t)i * dim + k] += 1.0;  // Σ lg = 0 gauge
                }
            }
            A[(size_t)n * dim + n] += 1.0;
            A[(size_t)(n + 1) * dim + n + 1] += 1.0;
            if (!choleskySolve(A, b, dim)) break;
            x = b;
        }
        vig[0] = std::max(-1.5, std::min(0.5, x[n]));
        vig[1] = std::max(-1.0, std::min(1.0, x[n + 1]));
        if (!sHaveCameraEv) {
            // x[i] is observed log-brightness. Compose multiplies by gain, so store 1/exp(x).
            std::vector<double> lumaLg(n, 0.0);
            for (int i = 0; i < n; i++) {
                lumaLg[i] = -x[i];
                const float g = (float)std::max(0.20, std::min(5.0, std::exp(-x[i])));
                st[i].gain[0] = st[i].gain[1] = st[i].gain[2] = g;
            }
            auto Vfn = [&](double r2) { return std::exp(vig[0] * r2 + vig[1] * r2 * r2); };
            for (int c = 0; c < 3; c++) {
                std::vector<double> lg = lumaLg;
                for (int it = 0; it < 2; it++) {
                    std::vector<double> A((size_t)n * n, 0.0), b(n, 0.0);
                    for (const PhotoSample& p : samples) {
                        const double y = std::log(std::max(1.0, (double)p.cj[c] / std::max(1e-4, Vfn(p.rj2))))
                            - std::log(std::max(1.0, (double)p.ci[c] / std::max(1e-4, Vfn(p.ri2))));
                        const double pred = lg[p.i] - lg[p.j];
                        const double res = y - pred;
                        const double w = std::fabs(res) < 0.08 ? 1.0 : 0.08 / std::fabs(res);
                        A[(size_t)p.i * n + p.i] += w;
                        A[(size_t)p.j * n + p.j] += w;
                        A[(size_t)p.i * n + p.j] -= w;
                        A[(size_t)p.j * n + p.i] -= w;
                        b[p.i] += w * y;
                        b[p.j] -= w * y;
                    }
                    const double spf = (double)samples.size() / std::max(1, n);
                    for (int i = 0; i < n; i++) {
                        const double lockW = use[i] ? 0.0 : 1e6;
                        const double towardLuma = 0.08 * spf;
                        A[(size_t)i * n + i] += towardLuma + lockW;
                        b[i] += towardLuma * lumaLg[i];
                        for (int k = 0; k < n; k++) A[(size_t)i * n + k] += 1.0;
                    }
                    if (!choleskySolve(A, b, n)) break;
                    lg = b;
                }
                for (int i = 0; i < n; i++) {
                    st[i].gain[c] = (float)std::max(0.20, std::min(5.0, std::exp(lg[i])));
                }
            }
            // Residual DC after gain+vignette. Tight clamp so AE hunts cannot milk/crush.
            for (int c = 0; c < 3; c++) {
                std::vector<double> A((size_t)n * n, 0.0), b(n, 0.0);
                for (const PhotoSample& p : samples) {
                    const double yi = st[p.i].gain[c] * p.ci[c] / std::max(1e-4, Vfn(p.ri2));
                    const double yj = st[p.j].gain[c] * p.cj[c] / std::max(1e-4, Vfn(p.rj2));
                    const double y = yj - yi;
                    A[(size_t)p.i * n + p.i] += 1.0;
                    A[(size_t)p.j * n + p.j] += 1.0;
                    A[(size_t)p.i * n + p.j] -= 1.0;
                    A[(size_t)p.j * n + p.i] -= 1.0;
                    b[p.i] += y;
                    b[p.j] -= y;
                }
                const double spf = (double)samples.size() / std::max(1, n);
                for (int i = 0; i < n; i++) {
                    A[(size_t)i * n + i] += 40.0 * spf + (use[i] ? 0.0 : 1e6);
                    for (int k = 0; k < n; k++) A[(size_t)i * n + k] += 1.0;
                }
                if (!choleskySolve(A, b, n)) continue;
                for (int i = 0; i < n; i++) {
                    st[i].offset[c] = (float)std::max(-18.0, std::min(18.0, b[i]));
                }
            }
        }
    }
}

/** Smooth per-frame gain maps so overlapping neighbors agree at stitch sides. */
static void fitSeamGains(const std::vector<PhotoSample>& samples, int n,
                         const std::vector<int>& use, const double* vig,
                         std::vector<FrameState>& st) {
    const int dim = SEAM_GN * SEAM_GN;
    for (int i = 0; i < n; i++) {
        for (int k = 0; k < dim * 3; k++) st[i].seamG[k] = 1.f;
    }
    if (sHaveCameraEv || samples.size() < 40) return;
    if (std::getenv("SPHERE_GA_NO_SEAM_GAINS") != nullptr) {
        LOGI("Seam gain maps: n=%d samples=%zu range=1.000..1.000 (disabled)", n, (size_t)samples.size());
        return;
    }
    auto Vfn = [&](double r2) { return std::exp(vig[0] * r2 + vig[1] * r2 * r2); };
    auto addBilinear = [&](std::vector<double>& A, std::vector<double>& b,
                           double u, double v, double y, double w) {
        const double gx = std::max(0.0, std::min((double)(SEAM_GN - 1), (u * 0.5 + 0.5) * (SEAM_GN - 1)));
        const double gy = std::max(0.0, std::min((double)(SEAM_GN - 1), (v * 0.5 + 0.5) * (SEAM_GN - 1)));
        const int x0 = (int)gx, y0 = (int)gy;
        const int x1 = std::min(SEAM_GN - 1, x0 + 1), y1 = std::min(SEAM_GN - 1, y0 + 1);
        const double ax = gx - x0, ay = gy - y0;
        const int idx[4] = {y0 * SEAM_GN + x0, y0 * SEAM_GN + x1, y1 * SEAM_GN + x0, y1 * SEAM_GN + x1};
        const double wt[4] = {(1 - ax) * (1 - ay), ax * (1 - ay), (1 - ax) * ay, ax * ay};
        for (int p = 0; p < 4; p++) {
            b[idx[p]] += w * wt[p] * y;
            for (int q = 0; q < 4; q++) A[(size_t)idx[p] * dim + idx[q]] += w * wt[p] * wt[q];
        }
    };
    for (int f = 0; f < n; f++) {
        if (!use[f]) continue;
        for (int c = 0; c < 3; c++) {
            std::vector<double> A((size_t)dim * dim, 0.0), b(dim, 0.0);
            int ns = 0;
            for (const PhotoSample& p : samples) {
                int other = -1;
                const float* cs = nullptr, *co = nullptr;
                float us = 0, vs = 0, rs = 0, ro = 0;
                if (p.i == f) {
                    other = p.j; cs = p.ci; co = p.cj;
                    us = p.ui; vs = p.vi; rs = p.ri2; ro = p.rj2;
                } else if (p.j == f) {
                    other = p.i; cs = p.cj; co = p.ci;
                    us = p.uj; vs = p.vj; rs = p.rj2; ro = p.ri2;
                } else continue;
                if (!use[other]) continue;
                const double Is = st[f].gain[c] * cs[c] / std::max(1e-4, Vfn(rs)) + st[f].offset[c];
                const double Io = st[other].gain[c] * co[c] / std::max(1e-4, Vfn(ro)) + st[other].offset[c];
                if (Is < 6.0 || Is > 250.0 || Io < 6.0 || Io > 250.0) continue;
                const double y = 0.6 * (std::log(std::max(1.0, Io)) - std::log(std::max(1.0, Is)));
                if (std::fabs(y) > 0.70) continue;
                const double w = std::fabs(y) < 0.08 ? 1.0 : 0.08 / std::fabs(y);
                addBilinear(A, b, us, vs, y, w);
                ns++;
            }
            if (ns < 16) continue;
            for (int k = 0; k < dim; k++) {
                const double cx = (k % SEAM_GN) / (double)(SEAM_GN - 1) - 0.5;
                const double cy = (k / SEAM_GN) / (double)(SEAM_GN - 1) - 0.5;
                const double center = 1.0 - std::min(1.0, 4.0 * (cx * cx + cy * cy));
                A[(size_t)k * dim + k] += 0.6 + 22.0 * center * center;
            }
            if (!choleskySolve(A, b, dim)) continue;
            for (int k = 0; k < dim; k++) {
                st[f].seamG[k * 3 + c] = (float)std::max(0.62, std::min(1.55, std::exp(b[k])));
            }
        }
    }
    double mn = 1, mx = 1;
    for (int i = 0; i < n; i++) {
        for (int k = 0; k < dim * 3; k++) {
            mn = std::min(mn, (double)st[i].seamG[k]);
            mx = std::max(mx, (double)st[i].seamG[k]);
        }
    }
    LOGI("Seam gain maps: n=%d samples=%zu range=%.3f..%.3f", n, samples.size(), mn, mx);
}

}  // namespace ga

namespace ga {

struct MatchContext {
    const std::vector<FrameData>* fd = nullptr;
    double tanH = 0, tanV = 0, cosThr = 0, diagHalf = 0;
};

struct MatchScratch {
    std::vector<std::pair<int, int>> m, mSet;
    std::vector<double> ra, rb;
    std::vector<int> inl, subA, subB;
    std::vector<const uint64_t*> da, db;
    std::vector<std::array<uint64_t, DESC_WORDS>> rectB;
};

/** R_ab = R_aᵀ R_b, so that a_ray ≈ R_ab · b_ray. */
static inline void relativeRotation(const Quat& qa, const Quat& qb, double* Rab) {
    double Ma[9], Mb[9], Mat[9];
    mat3FromQuat(qa, Ma);
    mat3FromQuat(qb, Mb);
    mat3Transpose(Ma, Mat);
    mat3Mul(Mat, Mb, Rab);
}

static inline void forwardAxis(const Quat& q, double* f) {
    quatRotate(q, 0, 0, -1, f[0], f[1], f[2]);
}

static inline double axisAngle(const double* a, const double* b) {
    return std::acos(std::max(-1.0, std::min(1.0, a[0] * b[0] + a[1] * b[1] + a[2] * b[2])));
}

static void rectifiedMatches(const MatchContext& c, int a, int b, const double* Rab,
                             MatchScratch& s, std::vector<std::pair<int, int>>& out) {
    const std::vector<FrameData>& fd = *c.fd;
    out.clear();
    overlapSubset(fd[a], fd[b], Rab, c.tanH, c.tanV, s.subA);
    s.subB.clear();
    s.rectB.clear();
    for (int j = 0; j < (int)fd[b].kps.size(); j++) {
        std::array<uint64_t, DESC_WORDS> d{};
        if (!rectifiedDescriptor(fd[a], fd[b], fd[b].kps[j], Rab, c.tanH, c.tanV, d.data())) continue;
        s.subB.push_back(j);
        s.rectB.push_back(d);
    }
    if ((int)s.subA.size() < MIN_MATCHES || (int)s.subB.size() < MIN_MATCHES) return;
    s.da.clear();
    s.db.clear();
    for (int i : s.subA) s.da.push_back(fd[a].kps[i].upright);
    for (auto& d : s.rectB) s.db.push_back(d.data());
    matchSets(s.da, s.db, s.mSet);
    for (auto& pr : s.mSet) out.push_back({s.subA[pr.first], s.subB[pr.second]});
}

/** RANSAC + geometric sanity checks; fills `e` with subsampled inlier ray pairs. */
static bool verifyMatches(const MatchContext& c, int a, int b, const double* Rprior,
                          const std::vector<std::pair<int, int>>& mm, MatchScratch& s, Edge& e) {
    const std::vector<FrameData>& fd = *c.fd;
    if ((int)mm.size() < MIN_MATCHES) return false;
    s.ra.resize(mm.size() * 3);
    s.rb.resize(mm.size() * 3);
    for (size_t k = 0; k < mm.size(); k++) {
        memcpy(&s.ra[k * 3], fd[a].kps[mm[k].first].ray, 3 * sizeof(double));
        memcpy(&s.rb[k * 3], fd[b].kps[mm[k].second].ray, 3 * sizeof(double));
    }
    e = Edge{};
    e.a = a;
    e.b = b;
    if (!ransacRotation(s.ra, s.rb, c.cosThr, e.R, s.inl)) return false;
    // Spatial spread: inliers must cover ≥3 cells of a 4×4 grid in both frames.
    int cellsA = 0, cellsB = 0;
    bool occA[16]{}, occB[16]{};
    for (int k : s.inl) {
        const Keypoint& ka = fd[a].kps[mm[k].first];
        const Keypoint& kb = fd[b].kps[mm[k].second];
        const int ca = clampI((int)(ka.y * 4 / fd[a].gh), 0, 3) * 4 + clampI((int)(ka.x * 4 / fd[a].gw), 0, 3);
        const int cb = clampI((int)(kb.y * 4 / fd[b].gh), 0, 3) * 4 + clampI((int)(kb.x * 4 / fd[b].gw), 0, 3);
        if (!occA[ca]) { occA[ca] = true; cellsA++; }
        if (!occB[cb]) { occB[cb] = true; cellsB++; }
    }
    if (cellsA < 3 || cellsB < 3) return false;
    // Relative rotation must keep the frames overlapping.
    const double fwdB[3] = {0, 0, -1};
    double inA[3];
    mat3RotateVec(e.R, fwdB, inA);
    if (std::acos(std::max(-1.0, std::min(1.0, -inA[2]))) > 2.0 * c.diagHalf) return false;
    // Large disagreement with the prior needs stronger evidence.
    double Rt[9], D[9];
    mat3Transpose(e.R, Rt);
    mat3Mul(Rprior, Rt, D);
    if (rotAngleDeg(D) > 45.0 && (int)s.inl.size() < 40) return false;
    e.inliers = (int)s.inl.size();
    const int stride = std::max(1, (int)s.inl.size() / MAX_OBS_PER_EDGE);
    for (size_t k = 0; k < s.inl.size(); k += stride) {
        for (int q = 0; q < 3; q++) e.obs.push_back((float)s.ra[s.inl[k] * 3 + q]);
        for (int q = 0; q < 3; q++) e.obs.push_back((float)s.rb[s.inl[k] * 3 + q]);
    }
    return true;
}

/**
 * Prior-rectified matching, re-rectified with the visual estimate; optional unrectified
 * oriented-BRIEF fallback when the prior is too far off for rectification.
 */
static bool matchPair(const MatchContext& c, int a, int b, const double* Rprior,
                      bool allowUnrectified, MatchScratch& s, Edge& out) {
    Edge cand;
    bool have = false;
    rectifiedMatches(c, a, b, Rprior, s, s.m);
    if (verifyMatches(c, a, b, Rprior, s.m, s, cand)) { out = std::move(cand); have = true; }
    if (have) {
        double Rv[9];
        memcpy(Rv, out.R, sizeof(Rv));
        rectifiedMatches(c, a, b, Rv, s, s.m);
        if (verifyMatches(c, a, b, Rprior, s.m, s, cand) && cand.inliers > out.inliers) out = std::move(cand);
    } else if (allowUnrectified) {
        matchMutual((*c.fd)[a], (*c.fd)[b], s.m);
        if (verifyMatches(c, a, b, Rprior, s.m, s, cand)) { out = std::move(cand); have = true; }
    }
    return have;
}

/** Spanning tree + BA, drop inconsistent edges once, then fix gauge to the IMU priors. */
static int solvePoses(std::vector<Edge>& edges, int n, const std::vector<Quat>& prior, double huber,
                      std::vector<Quat>& poses, std::vector<int>& comp, int& dropped) {
    int root = spanningTreeInit(edges, n, prior, poses, comp);
    bundleAdjust(edges, poses, comp, root, huber);
    int newlyDropped = 0;
    for (Edge& e : edges) {
        if (!e.active || !comp[e.a] || !comp[e.b]) continue;
        if (edgeMedianResidualDeg(e, poses) > EDGE_DROP_MEDIAN_DEG) { e.active = false; newlyDropped++; }
    }
    if (newlyDropped > 0) {
        root = spanningTreeInit(edges, n, prior, poses, comp);
        bundleAdjust(edges, poses, comp, root, huber);
    }
    dropped += newlyDropped;
    int connected = 0;
    for (int i = 0; i < n; i++) connected += comp[i];
    if (connected < 2) return connected;
    const Quat G = robustGauge(prior, poses, comp);
    for (int i = 0; i < n; i++) if (comp[i]) poses[i] = quatMul(G, poses[i]);
    return connected;
}

/** Candidate orientations for a frame with no visual constraint (IMU chained from both sides). */
static void poseHypotheses(int i, const std::vector<Quat>& poses, const std::vector<int>& ok,
                           const std::vector<Quat>& prior, std::vector<Quat>& out) {
    out.clear();
    auto add = [&](const Quat& q) {
        for (const Quat& o : out) if (quatAngularDistSq(o, q) < 9.0 * DEG2RAD * DEG2RAD) return;
        out.push_back(q);
    };
    const int n = (int)poses.size();
    for (int k = i - 1; k >= 0; k--) {
        if (ok[k]) { add(quatMul(poses[k], quatMul(quatConjugate(prior[k]), prior[i]))); break; }
    }
    for (int k = i + 1; k < n; k++) {
        if (ok[k]) { add(quatMul(poses[k], quatMul(quatConjugate(prior[k]), prior[i]))); break; }
    }
    add(prior[i]);
}

struct Mosaic {
    int w = 0, h = 0;
    std::vector<float> v;
    std::vector<uint8_t> ok;
};

/** Low-res luminance equirect pyramid from frames with visual poses (nearest-centre). */
static void buildMosaicPyramid(const std::vector<FrameData>& fd, const std::vector<Quat>& poses,
                               const std::vector<int>& use, double tanH, double tanV,
                               std::vector<Mosaic>& pyr) {
    const int n = (int)fd.size();
    pyr.assign(3, Mosaic{});
    Mosaic& m0 = pyr[0];
    m0.w = 720;
    m0.h = 360;
    m0.v.assign((size_t)m0.w * m0.h, 0.f);
    m0.ok.assign((size_t)m0.w * m0.h, 0);
    std::vector<std::array<double, 9>> M(n);
    for (int i = 0; i < n; i++) mat3FromQuat(poses[i], M[i].data());
    for (int y = 0; y < m0.h; y++) {
        for (int x = 0; x < m0.w; x++) {
            double d[3];
            equirectPixelToWorldRay(x + 0.5, y + 0.5, m0.w, m0.h, d[0], d[1], d[2]);
            double best = 1e9;
            int bi = -1;
            double bgx = 0, bgy = 0;
            for (int i = 0; i < n; i++) {
                if (!use[i]) continue;
                const double* R = M[i].data();
                const double c[3] = {R[0] * d[0] + R[3] * d[1] + R[6] * d[2],
                                     R[1] * d[0] + R[4] * d[1] + R[7] * d[2],
                                     R[2] * d[0] + R[5] * d[1] + R[8] * d[2]};
                if (c[2] >= -1e-6) continue;
                double gx, gy;
                if (!rayToGray(fd[i], c, tanH, tanV, gx, gy)) continue;
                if (gx < 1 || gy < 1 || gx > fd[i].gw - 2 || gy > fd[i].gh - 2) continue;
                const double s = std::hypot(gx / fd[i].gw - 0.5, gy / fd[i].gh - 0.5);
                if (s < best) { best = s; bi = i; bgx = gx; bgy = gy; }
            }
            if (bi < 0) continue;
            m0.v[(size_t)y * m0.w + x] = bilinearGray(fd[bi].smooth, fd[bi].gw, fd[bi].gh, bgx, bgy);
            m0.ok[(size_t)y * m0.w + x] = 1;
        }
    }
    for (int l = 1; l < 3; l++) {
        const Mosaic& p = pyr[l - 1];
        Mosaic& m = pyr[l];
        m.w = p.w / 2;
        m.h = p.h / 2;
        m.v.assign((size_t)m.w * m.h, 0.f);
        m.ok.assign((size_t)m.w * m.h, 0);
        for (int y = 0; y < m.h; y++) {
            for (int x = 0; x < m.w; x++) {
                double s = 0;
                int c = 0;
                for (int dy = 0; dy < 2; dy++)
                    for (int dx = 0; dx < 2; dx++) {
                        const size_t i = (size_t)(2 * y + dy) * p.w + 2 * x + dx;
                        if (p.ok[i]) { s += p.v[i]; c++; }
                    }
                if (c == 4) {
                    m.v[(size_t)y * m.w + x] = (float)(s / c);
                    m.ok[(size_t)y * m.w + x] = 1;
                }
            }
        }
    }
}

static inline bool sampleMosaic(const Mosaic& m, const double* w, double& out) {
    const double lon = std::atan2(w[0], w[1]);
    const double lat = std::asin(std::max(-1.0, std::min(1.0, w[2])));
    const double xf = (lon / PI + 1.0) * 0.5 * m.w - 0.5;
    const double yf = (0.5 - lat / PI) * m.h - 0.5;
    if (yf < 0 || yf > m.h - 1.001) return false;
    int x0 = (int)std::floor(xf);
    const int y0 = (int)yf;
    const double ax = xf - x0, ay = yf - y0;
    x0 = ((x0 % m.w) + m.w) % m.w;
    const int x1 = (x0 + 1) % m.w;
    const size_t i00 = (size_t)y0 * m.w + x0, i10 = (size_t)y0 * m.w + x1;
    const size_t i01 = i00 + m.w, i11 = i10 + m.w;
    if (!m.ok[i00] || !m.ok[i10] || !m.ok[i01] || !m.ok[i11]) return false;
    out = (1 - ax) * (1 - ay) * m.v[i00] + ax * (1 - ay) * m.v[i10] +
          (1 - ax) * ay * m.v[i01] + ax * ay * m.v[i11];
    return true;
}

struct DenseResult {
    Quat q{0, 0, 0, 1};
    double ncc = -1.0;
    double overlap = 0.0;
};

/**
 * Direct rotation alignment of one frame against the mosaic (coarse-to-fine Gauss–Newton
 * with per-frame affine intensity a·I + b, so exposure / DC offset do not bias the pose).
 */
static DenseResult denseAlign(const FrameData& F, const std::vector<Mosaic>& pyr, Quat q,
                              double tanH, double tanV, int finestLevel) {
    DenseResult res;
    double a = 1.0, b = 0.0;
    bool gainInit = false;
    for (int l = (int)pyr.size() - 1; l >= finestLevel; l--) {
        const Mosaic& m = pyr[l];
        const int lw = std::max(16, 160 >> l);
        const int lh = std::max(16, (int)std::lround((double)lw * F.gh / F.gw));
        std::vector<float> img((size_t)lw * lh);
        std::vector<std::array<double, 3>> rays((size_t)lw * lh);
        const double fx = (double)F.gw / lw, fy = (double)F.gh / lh;
        for (int y = 0; y < lh; y++) {
            for (int x = 0; x < lw; x++) {
                double s = 0;
                int c = 0;
                const int x0 = (int)(x * fx), x1 = std::max(x0 + 1, (int)((x + 1) * fx));
                const int y0 = (int)(y * fy), y1 = std::max(y0 + 1, (int)((y + 1) * fy));
                for (int yy = y0; yy < std::min(F.gh, y1); yy++)
                    for (int xx = x0; xx < std::min(F.gw, x1); xx++) { s += F.smooth[(size_t)yy * F.gw + xx]; c++; }
                img[(size_t)y * lw + x] = (float)(s / std::max(1, c));
                grayToRay(F, (x + 0.5) * fx - 0.5, (y + 0.5) * fy - 0.5, tanH, tanV, rays[(size_t)y * lw + x].data());
            }
        }
        const double eps = 2.0 * PI / m.w;
        for (int it = 0; it < 15; it++) {
            double Rm[9];
            mat3FromQuat(q, Rm);
            double H[25] = {}, g[5] = {};
            double si = 0, sm = 0, sii = 0, smm = 0, sim = 0;
            int cnt = 0;
            std::vector<double> rs;
            rs.reserve(img.size());
            struct S { double I, M, gk[3]; };
            std::vector<S> samples;
            samples.reserve(img.size());
            for (size_t p = 0; p < img.size(); p++) {
                double w[3];
                mat3RotateVec(Rm, rays[p].data(), w);
                double M0;
                if (!sampleMosaic(m, w, M0)) continue;
                S s;
                s.I = img[p];
                s.M = M0;
                bool okg = true;
                for (int k = 0; k < 3 && okg; k++) {
                    double ax[3] = {0, 0, 0};
                    ax[k] = eps;
                    const double wk[3] = {w[0] + ax[1] * w[2] - ax[2] * w[1],
                                          w[1] + ax[2] * w[0] - ax[0] * w[2],
                                          w[2] + ax[0] * w[1] - ax[1] * w[0]};
                    double Mk;
                    if (!sampleMosaic(m, wk, Mk)) { okg = false; break; }
                    s.gk[k] = (Mk - M0) / eps;
                }
                if (!okg) continue;
                samples.push_back(s);
                si += s.I; sm += s.M; sii += s.I * s.I; smm += s.M * s.M; sim += s.I * s.M;
                cnt++;
            }
            res.overlap = (double)cnt / img.size();
            if (cnt < 40) return res;
            const double mi = si / cnt, mmn = sm / cnt;
            const double vi = sii / cnt - mi * mi, vm = smm / cnt - mmn * mmn;
            res.ncc = (vi > 1e-9 && vm > 1e-9) ? (sim / cnt - mi * mmn) / std::sqrt(vi * vm) : 0.0;
            if (!gainInit) {
                a = vi > 1e-9 ? std::sqrt(std::max(1e-9, vm) / vi) : 1.0;
                b = mmn - a * mi;
                gainInit = true;
            }
            double absSum = 0;
            for (const S& s : samples) absSum += std::fabs(a * s.I + b - s.M);
            const double huber = std::max(2.0, 1.5 * absSum / samples.size());
            for (const S& s : samples) {
                const double r = a * s.I + b - s.M;
                const double wt = std::fabs(r) <= huber ? 1.0 : huber / std::fabs(r);
                const double J[5] = {-s.gk[0], -s.gk[1], -s.gk[2], s.I, 1.0};
                for (int r0 = 0; r0 < 5; r0++) {
                    g[r0] += wt * J[r0] * r;
                    for (int c0 = 0; c0 < 5; c0++) H[r0 * 5 + c0] += wt * J[r0] * J[c0];
                }
            }
            for (int d = 0; d < 5; d++) H[d * 5 + d] *= 1.0 + 1e-3;
            for (int d = 0; d < 3; d++) H[d * 5 + d] += 1e-6 * cnt;
            std::vector<double> Hv(H, H + 25), gv(5);
            for (int d = 0; d < 5; d++) gv[d] = -g[d];
            if (!choleskySolve(Hv, gv, 5)) break;
            double step[3] = {gv[0], gv[1], gv[2]};
            const double sn = std::sqrt(step[0] * step[0] + step[1] * step[1] + step[2] * step[2]);
            const double maxStep = 3.0 * DEG2RAD;
            if (sn > maxStep) for (double& v : step) v *= maxStep / sn;
            q = quatMul(quatExpTangent(step[0], step[1], step[2]), q);
            a += gv[3];
            b += gv[4];
            if (sn < 1e-5) break;
        }
    }
    res.q = q;
    return res;
}

/**
 * Fills pixels with zero weight from a coverage-weighted image pyramid (push-pull).
 * Covered pixels are untouched; filled pixels get weight 1 so they export as covered.
 */
static void pushPullFill(std::vector<float>& R, std::vector<float>& G, std::vector<float>& B,
                         std::vector<float>& W, int w, int h) {
    struct Level { int w, h; std::vector<float> c[3]; std::vector<float> wt; };
    std::vector<Level> lv;
    {
        Level l0;
        l0.w = w; l0.h = h;
        l0.wt.resize((size_t)w * h);
        for (int c = 0; c < 3; c++) l0.c[c].resize((size_t)w * h);
        for (size_t i = 0; i < l0.wt.size(); i++) {
            const float m = W[i] > 0.f ? 1.f : 0.f;
            l0.wt[i] = m;
            l0.c[0][i] = R[i] * m;
            l0.c[1][i] = G[i] * m;
            l0.c[2][i] = B[i] * m;
        }
        lv.push_back(std::move(l0));
    }
    while (lv.back().w > 1 || lv.back().h > 1) {
        const Level& p = lv.back();
        Level q;
        q.w = (p.w + 1) / 2; q.h = (p.h + 1) / 2;
        q.wt.assign((size_t)q.w * q.h, 0.f);
        for (int c = 0; c < 3; c++) q.c[c].assign((size_t)q.w * q.h, 0.f);
        for (int y = 0; y < p.h; y++) {
            for (int x = 0; x < p.w; x++) {
                const size_t s = (size_t)y * p.w + x;
                const size_t d = (size_t)(y / 2) * q.w + x / 2;
                q.wt[d] += p.wt[s];
                for (int c = 0; c < 3; c++) q.c[c][d] += p.c[c][s];
            }
        }
        lv.push_back(std::move(q));
    }
    // Pull: normalise each level, then fill its holes from the (already filled) coarser level.
    for (int li = (int)lv.size() - 1; li >= 0; li--) {
        Level& l = lv[li];
        for (size_t i = 0; i < l.wt.size(); i++) {
            if (l.wt[i] > 0.f) {
                for (int c = 0; c < 3; c++) l.c[c][i] /= l.wt[i];
                l.wt[i] = 1.f;
            }
        }
        if (li + 1 >= (int)lv.size()) continue;
        const Level& q = lv[li + 1];
        for (int y = 0; y < l.h; y++) {
            for (int x = 0; x < l.w; x++) {
                const size_t i = (size_t)y * l.w + x;
                if (l.wt[i] > 0.f) continue;
                const double qx = (x + 0.5) * 0.5 - 0.5;
                const double qy = std::clamp((y + 0.5) * 0.5 - 0.5, 0.0, (double)q.h - 1);
                const int x0 = (int)std::floor(qx);
                const int y0 = (int)std::floor(qy);
                const double fx = qx - x0, fy = qy - y0;
                const int y1 = std::min(y0 + 1, q.h - 1);
                const int xa = ((x0 % q.w) + q.w) % q.w;
                const int xb = (xa + 1) % q.w;
                for (int c = 0; c < 3; c++) {
                    const auto& s = q.c[c];
                    l.c[c][i] = (float)((1 - fy) * ((1 - fx) * s[(size_t)y0 * q.w + xa] + fx * s[(size_t)y0 * q.w + xb]) +
                                        fy * ((1 - fx) * s[(size_t)y1 * q.w + xa] + fx * s[(size_t)y1 * q.w + xb]));
                }
                l.wt[i] = 1.f;
            }
        }
    }
    const Level& l0 = lv[0];
    for (size_t i = 0; i < W.size(); i++) {
        if (W[i] > 0.f) continue;
        R[i] = l0.c[0][i];
        G[i] = l0.c[1][i];
        B[i] = l0.c[2][i];
        W[i] = 1.f;
    }
}

}  // namespace ga

/** Full global alignment of all stored frames. Returns true when poses were updated. */
static bool gaGlobalAlignLocked() {
    using namespace ga;
    const int n = (int)sFrames.size();
    sGaValid = false;
    if (n < 2) return false;
    if (std::getenv("SPHERE_GA_DISABLE") != nullptr) return false;
    auto t0 = SteadyClock::now();
    double tanH, tanV;
    sessionTanHalfFov(tanH, tanV);
    const int threads = std::max(1, std::min(8, (int)std::thread::hardware_concurrency()));
    auto parallelFor = [&](int count, const std::function<void(int, int)>& fn) {
        std::vector<std::thread> pool;
        std::atomic<int> next{0};
        for (int t = 0; t < threads; t++) {
            pool.emplace_back([&, t]() {
                for (int i = next++; i < count; i = next++) fn(i, t);
            });
        }
        for (auto& th : pool) th.join();
    };

    std::vector<FrameData> fd(n);
    parallelFor(n, [&](int i, int) { detectAndDescribe(sFrames[i], fd[i], tanH, tanV); });
    const int64_t detectUs = elapsedUs(t0);
    if (sGaDebug) {
        for (int i = 0; i < n; i++) LOGI("GA frame %d: %zu keypoints", i, fd[i].kps.size());
    }

    std::vector<Quat> prior(n), seed(n);
    for (int i = 0; i < n; i++) {
        prior[i] = sFrames[i].imuQuat;
        seed[i] = sFrames[i].fastCorrectedQuat;
    }

    MatchContext ctx;
    ctx.fd = &fd;
    ctx.tanH = tanH;
    ctx.tanV = tanV;
    ctx.diagHalf = std::atan(std::sqrt(tanH * tanH + tanV * tanV));
    const double pixAng = 2.0 * tanH / std::max(1, fd[0].gw);
    const double thrRad = std::max(0.2 * DEG2RAD, std::min(1.0 * DEG2RAD, 3.0 * pixAng));
    ctx.cosThr = std::cos(thrRad);
    const double pairLimit = 2.0 * ctx.diagHalf + 25.0 * DEG2RAD;

    // Candidate pairs: predicted overlap (generous margin) + capture neighbours.
    std::vector<std::pair<int, int>> pairs;
    {
        std::vector<std::array<double, 3>> fwd(n);
        for (int i = 0; i < n; i++) forwardAxis(seed[i], fwd[i].data());
        std::set<std::pair<int, int>> chosen;
        for (int i = 0; i < n; i++) {
            std::vector<std::pair<double, int>> near;
            for (int j = 0; j < n; j++) {
                if (j == i) continue;
                const double ang = axisAngle(fwd[i].data(), fwd[j].data());
                if (std::abs(i - j) <= TEMPORAL_NEIGHBOURS) near.push_back({-1.0, j});
                else if (ang < pairLimit) near.push_back({ang, j});
            }
            std::sort(near.begin(), near.end());
            const int cap = n <= 40 ? (int)near.size() : VIDEO_NEIGHBOURS;
            for (int k = 0; k < std::min(cap, (int)near.size()); k++) {
                const int j = near[k].second;
                chosen.insert({std::min(i, j), std::max(i, j)});
            }
        }
        pairs.assign(chosen.begin(), chosen.end());
    }

    std::vector<Edge> edges;
    {
        std::vector<Edge> found(pairs.size());
        std::vector<char> ok(pairs.size(), 0);
        std::vector<MatchScratch> scratch(threads);
        parallelFor((int)pairs.size(), [&](int p, int t) {
            const int a = pairs[p].first, b = pairs[p].second;
            double Rprior[9];
            relativeRotation(seed[a], seed[b], Rprior);
            if (matchPair(ctx, a, b, Rprior, /*allowUnrectified=*/true, scratch[t], found[p])) ok[p] = 1;
        });
        for (size_t p = 0; p < pairs.size(); p++) if (ok[p]) edges.push_back(std::move(found[p]));
    }
    const int64_t matchUs = elapsedUs(t0) - detectUs;
    if (edges.empty()) {
        LOGW("Global align: no visual edges (%d frames, %zu pairs)", n, pairs.size());
        return false;
    }

    std::vector<Quat> poses;
    std::vector<int> comp;
    int dropped = 0;
    int connected = solvePoses(edges, n, prior, thrRad, poses, comp, dropped);
    if (connected < 2) {
        LOGW("Global align: largest component has %d frames", connected);
        return false;
    }

    // Re-seed frames left out of the main component from IMU chains on either side and
    // retry rectified matching against connected frames (recovers IMU jumps / resets).
    int reseeded = 0;
    for (int round = 0; round < 2; round++) {
        std::vector<int> un;
        for (int i = 0; i < n; i++) if (!comp[i]) un.push_back(i);
        if (un.empty()) break;
        std::vector<std::vector<Edge>> perFrame(un.size());
        std::vector<MatchScratch> scratch(threads);
        parallelFor((int)un.size(), [&](int u, int t) {
            const int i = un[u];
            std::vector<Quat> hyps;
            poseHypotheses(i, poses, comp, prior, hyps);
            for (const Quat& h : hyps) {
                double fi[3];
                forwardAxis(h, fi);
                std::vector<Edge> got;
                for (int j = 0; j < n; j++) {
                    if (!comp[j]) continue;
                    double fj[3];
                    forwardAxis(poses[j], fj);
                    if (axisAngle(fi, fj) > pairLimit) continue;
                    const int a = std::min(i, j), b = std::max(i, j);
                    double Rprior[9];
                    relativeRotation(a == i ? h : poses[a], b == i ? h : poses[b], Rprior);
                    Edge e;
                    if (matchPair(ctx, a, b, Rprior, /*allowUnrectified=*/false, scratch[t], e)) got.push_back(std::move(e));
                }
                if (!got.empty()) { perFrame[u] = std::move(got); break; }
            }
        });
        int added = 0;
        for (auto& v : perFrame) {
            if (!v.empty()) reseeded++;
            for (auto& e : v) {
                bool dup = false;
                for (const Edge& o : edges) if (o.a == e.a && o.b == e.b && o.active) { dup = true; break; }
                if (!dup) { edges.push_back(std::move(e)); added++; }
            }
        }
        if (added == 0) break;
        connected = solvePoses(edges, n, prior, thrRad, poses, comp, dropped);
    }

    sGaFrames.assign(n, FrameState{});
    for (int i = 0; i < n; i++) {
        sGaFrames[i].connected = comp[i] != 0;
        sGaFrames[i].fillOnly = !comp[i];
    }

    // Frames still without feature constraints: dense alignment against the connected mosaic.
    // Good correlation → fill-only with refined pose; poor correlation → discarded.
    int denseOk = 0, discarded = 0;
    std::vector<int> reliable(n);
    for (int i = 0; i < n; i++) reliable[i] = comp[i];
    {
        std::vector<int> un;
        for (int i = 0; i < n; i++) if (!comp[i]) un.push_back(i);
        if (!un.empty()) {
            std::vector<Mosaic> pyr;
            buildMosaicPyramid(fd, poses, comp, tanH, tanV, pyr);
            std::vector<DenseResult> results(un.size());
            parallelFor((int)un.size(), [&](int u, int) {
                const int i = un[u];
                std::vector<Quat> hyps;
                poseHypotheses(i, poses, comp, prior, hyps);
                DenseResult best;
                for (const Quat& h : hyps) {
                    DenseResult r = denseAlign(fd[i], pyr, h, tanH, tanV, /*finestLevel=*/2);
                    if (r.ncc > best.ncc) best = r;
                }
                if (best.ncc < -0.5) { results[u] = best; return; }
                DenseResult fine = denseAlign(fd[i], pyr, best.q, tanH, tanV, /*finestLevel=*/0);
                const double moved = std::sqrt(quatAngularDistSq(fine.q, best.q)) * RAD2DEG;
                if (moved > 12.0) fine.ncc = std::min(fine.ncc, 0.0);
                results[u] = fine.overlap > 0 ? fine : best;
            });
            for (size_t u = 0; u < un.size(); u++) {
                const int i = un[u];
                const DenseResult& r = results[u];
                std::vector<Quat> hyps;
                poseHypotheses(i, poses, comp, prior, hyps);
                if (r.overlap < 0.10) {
                    poses[i] = hyps.empty() ? prior[i] : hyps[0];
                } else if (r.ncc >= DENSE_ACCEPT_NCC) {
                    poses[i] = r.q;
                    reliable[i] = 1;
                    denseOk++;
                } else {
                    poses[i] = hyps.empty() ? prior[i] : hyps[0];
                    sGaFrames[i].discard = true;
                    discarded++;
                }
                sGaFrames[i].denseNcc = (float)r.ncc;
                if (sGaDebug) {
                    LOGI("GA dense frame %d: ncc=%.3f overlap=%.2f -> %s", i, r.ncc, r.overlap,
                         sGaFrames[i].discard ? "discard" : (reliable[i] ? "aligned" : "imu"));
                }
            }
        }
    }

    // Relative sharpness from gradient energy at shared world points (feature edges only).
    {
        std::vector<std::vector<double>> logRatios(n);
        for (const Edge& e : edges) {
            if (!e.active || !comp[e.a] || !comp[e.b]) continue;
            const FrameData& A = fd[e.a];
            const FrameData& B = fd[e.b];
            double ea = 0, eb = 0;
            int cnt = 0;
            for (int y = BORDER; y < A.gh - BORDER; y += 8) {
                for (int x = BORDER; x < A.gw - BORDER; x += 8) {
                    double r[3], wr[3];
                    grayToRay(A, x, y, tanH, tanV, r);
                    quatRotate(poses[e.a], r[0], r[1], r[2], wr[0], wr[1], wr[2]);
                    float bfx, bfy;
                    if (!worldRayToFramePixel(wr[0], wr[1], wr[2], poses[e.b],
                                              sFrames[e.b].w, sFrames[e.b].h, tanH, tanV, bfx, bfy)) continue;
                    const int bx = (int)((bfx + 0.5) / B.sx), by = (int)((bfy + 0.5) / B.sy);
                    if (bx < 2 || by < 2 || bx >= B.gw - 2 || by >= B.gh - 2) continue;
                    auto grad = [](const FrameData& F, int px, int py) {
                        const size_t i = (size_t)py * F.gw + px;
                        const float gx = F.smooth[i + 1] - F.smooth[i - 1];
                        const float gy = F.smooth[i + F.gw] - F.smooth[i - F.gw];
                        return (double)std::fabs(gx) + std::fabs(gy);
                    };
                    ea += grad(A, x, y);
                    eb += grad(B, bx, by);
                    cnt++;
                }
            }
            if (cnt < 20 || ea < 1e-3 || eb < 1e-3) continue;
            const double lr = std::log(ea / eb);
            logRatios[e.a].push_back(lr);
            logRatios[e.b].push_back(-lr);
        }
        for (int i = 0; i < n; i++) {
            auto& v = logRatios[i];
            if (v.empty()) continue;
            std::nth_element(v.begin(), v.begin() + v.size() / 2, v.end());
            sGaFrames[i].sharpness = (float)std::exp(v[v.size() / 2]);
            if (sGaFrames[i].sharpness < BLUR_RATIO) sGaFrames[i].fillOnly = true;
        }
    }

    // Photometric samples between every pair of reliably posed, overlapping frames.
    std::vector<PhotoSample> samples;
    {
        std::vector<std::array<double, 3>> fwd(n);
        for (int i = 0; i < n; i++) forwardAxis(poses[i], fwd[i].data());
        for (int ia = 0; ia < n; ia++) {
            if (!reliable[ia] || sGaFrames[ia].discard) continue;
            for (int ib = ia + 1; ib < n; ib++) {
                if (!reliable[ib] || sGaFrames[ib].discard) continue;
                if (axisAngle(fwd[ia].data(), fwd[ib].data()) > 1.5 * ctx.diagHalf) continue;
                const FrameData& A = fd[ia];
                const FrameData& B = fd[ib];
                const StoredFrame& fa = sFrames[ia];
                const StoredFrame& fb = sFrames[ib];
                int taken = 0;
                for (int y = 1; y < A.lh - 1 && taken < 400; y += 2) {
                    for (int x = 1; x < A.lw - 1 && taken < 400; x += 2) {
                        const double fax = (x + 0.5) * fa.w / A.lw - 0.5;
                        const double fay = (y + 0.5) * fa.h / A.lh - 0.5;
                        double r[3], wr[3];
                        pixelToRayFloat(fax, fay, fa.w, fa.h, tanH, tanV, r[0], r[1], r[2]);
                        quatRotate(poses[ia], r[0], r[1], r[2], wr[0], wr[1], wr[2]);
                        float bfx, bfy;
                        if (!worldRayToFramePixel(wr[0], wr[1], wr[2], poses[ib], fb.w, fb.h, tanH, tanV, bfx, bfy)) continue;
                        const double lbx = (bfx + 0.5) * B.lw / fb.w - 0.5;
                        const double lby = (bfy + 0.5) * B.lh / fb.h - 0.5;
                        PhotoSample s;
                        float ga = 0, gb = 0;
                        if (!sampleLow(A, x, y, s.ci, &ga)) continue;
                        if (!sampleLow(B, lbx, lby, s.cj, &gb)) continue;
                        if (ga > 10.f || gb > 10.f) continue;
                        bool bad = false;
                        for (int c = 0; c < 3; c++) {
                            if (s.ci[c] < 4.f || s.ci[c] > 250.f || s.cj[c] < 4.f || s.cj[c] > 250.f) bad = true;
                        }
                        if (bad) continue;
                        const double ua = (fax / (fa.w - 1) - 0.5) * 2.0, va = (fay / (fa.h - 1) - 0.5) * 2.0;
                        const double ub = (bfx / (fb.w - 1) - 0.5) * 2.0, vb = (bfy / (fb.h - 1) - 0.5) * 2.0;
                        s.ri2 = (float)std::min(1.0, 0.5 * (ua * ua + va * va));
                        s.rj2 = (float)std::min(1.0, 0.5 * (ub * ub + vb * vb));
                        s.ui = (float)ua; s.vi = (float)va;
                        s.uj = (float)ub; s.vj = (float)vb;
                        s.i = ia;
                        s.j = ib;
                        samples.push_back(s);
                        taken++;
                    }
                }
            }
        }
        std::vector<int> use(n);
        for (int i = 0; i < n; i++) {
            use[i] = reliable[i] && !sGaFrames[i].discard ? 1 : 0;
            const float live = (sFrames[i].expGainR + sFrames[i].expGainG + sFrames[i].expGainB) / 3.f;
            const float g = std::max(0.20f, std::min(5.0f, live));
            sGaFrames[i].gain[0] = sGaFrames[i].gain[1] = sGaFrames[i].gain[2] = g;
            sGaFrames[i].offset[0] = sGaFrames[i].offset[1] = sGaFrames[i].offset[2] = 0.f;
        }
        fitPhotometric(samples, n, use, sGaVig, sGaFrames);
        fitSeamGains(samples, n, use, sGaVig, sGaFrames);
        for (int i = 0; i < n; i++) {
            sFrames[i].expGainR = sGaFrames[i].gain[0];
            sFrames[i].expGainG = sGaFrames[i].gain[1];
            sFrames[i].expGainB = sGaFrames[i].gain[2];
        }
    }

    const bool keepInputPoses = std::getenv("SPHERE_GA_KEEP_POSES") != nullptr;
    for (int i = 0; i < n; i++) {
        if (keepInputPoses) poses[i] = sFrames[i].imuQuat;
        sFrames[i].refinedQuat = poses[i];
        sFrames[i].fastCorrectedQuat = poses[i];
        sFrames[i].poseTrusted = reliable[i] != 0 && !sGaFrames[i].discard;
        initIdentityWarp(sFrames[i].warp);
    }
    sGaValid = true;
    sGaFrameCount = n;

    int fill = 0, activeEdges = 0;
    for (int i = 0; i < n; i++) fill += sGaFrames[i].fillOnly && !sGaFrames[i].discard ? 1 : 0;
    for (const Edge& e : edges) activeEdges += e.active ? 1 : 0;
    LOGI("Global align: %d frames, %d connected (%d re-seeded), %d dense, %d fill-only, %d discarded; "
         "%d/%zu edges (%d dropped) from %zu pairs; vignette=(%.3f,%.3f) samples=%zu "
         "detect=%lldus match=%lldus total=%lldus",
         n, connected, reseeded, denseOk, fill, discarded, activeEdges, edges.size(), dropped, pairs.size(),
         sGaVig[0], sGaVig[1], samples.size(),
         (long long)detectUs, (long long)matchUs, (long long)elapsedUs(t0));
    for (int i = 0; i < n; i++) {
        const auto& s = sGaFrames[i];
        LOGI("Global align frame %d: connected=%d fill=%d discard=%d sharp=%.2f ncc=%.2f "
             "gain=(%.2f,%.2f,%.2f) off=(%.1f,%.1f,%.1f)",
             i, s.connected ? 1 : 0, s.fillOnly ? 1 : 0, s.discard ? 1 : 0, s.sharpness, s.denseNcc,
             s.gain[0], s.gain[1], s.gain[2], s.offset[0], s.offset[1], s.offset[2]);
    }
    sphereProfileLogUs("global_align_total", elapsedUs(t0));
    return true;
}
/** Nearest-centre compositor with photometric correction and overlap seam gains. */
static void gaComposeEquirectLocked(int outW, int outH) {
    using namespace ga;
    initSrgbLuts();
    const int n = (int)sFrames.size();
    auto t0 = SteadyClock::now();
    double tanH, tanV;
    sessionTanHalfFov(tanH, tanV);
    const double diagHalf = std::atan(std::sqrt(tanH * tanH + tanV * tanV));

    struct FrameView {
        double M[9];   // camera → world
        double fwd[3];
        const int32_t* px;
        int w, h;
        bool fill;
        bool skip;
        float gain[3], offset[3];
        const float* seamG;
    };
    std::vector<FrameView> views(n);
    for (int i = 0; i < n; i++) {
        const StoredFrame& f = sFrames[i];
        FrameView& v = views[i];
        mat3FromQuat(f.refinedQuat, v.M);
        v.fwd[0] = -v.M[2]; v.fwd[1] = -v.M[5]; v.fwd[2] = -v.M[8];
        v.px = splatPixelsForExport(f);
        v.w = splatWForExport(f);
        v.h = splatHForExport(f);
        v.fill = sGaFrames[i].fillOnly;
        v.skip = sGaFrames[i].discard;
        for (int c = 0; c < 3; c++) { v.gain[c] = sGaFrames[i].gain[c]; v.offset[c] = sGaFrames[i].offset[c]; }
        v.seamG = sGaFrames[i].seamG;
    }

    eqR.assign((size_t)outW * outH, 0.f);
    eqG.assign((size_t)outW * outH, 0.f);
    eqB.assign((size_t)outW * outH, 0.f);
    eqWeight.assign((size_t)outW * outH, 0.f);

    constexpr int TILE = 32;
    const int tilesX = (outW + TILE - 1) / TILE;
    const int tilesY = (outH + TILE - 1) / TILE;
    const double vig0 = sGaVig[0], vig1 = sGaVig[1];
    const bool mirror = sMirrorCameraX;

    auto worker = [&](std::atomic<int>* next) {
        std::vector<int> cand;
        for (int t = (*next)++; t < tilesX * tilesY; t = (*next)++) {
            const int tx = t % tilesX, ty = t / tilesX;
            const int x0 = tx * TILE, y0 = ty * TILE;
            const int x1 = std::min(outW, x0 + TILE), y1 = std::min(outH, y0 + TILE);
            double cr[3];
            equirectPixelToWorldRay(0.5 * (x0 + x1), 0.5 * (y0 + y1), outW, outH, cr[0], cr[1], cr[2]);
            double tileRad = 0;
            const int cxs[4] = {x0, x1, x0, x1}, cys[4] = {y0, y0, y1, y1};
            for (int k = 0; k < 4; k++) {
                double r[3];
                equirectPixelToWorldRay(cxs[k], cys[k], outW, outH, r[0], r[1], r[2]);
                tileRad = std::max(tileRad, std::acos(std::max(-1.0, std::min(1.0, r[0] * cr[0] + r[1] * cr[1] + r[2] * cr[2]))));
            }
            const double lim = std::cos(std::min(PI, diagHalf + tileRad + 0.01));
            cand.clear();
            for (int i = 0; i < n; i++) {
                const FrameView& v = views[i];
                if (v.skip) continue;
                if (v.fwd[0] * cr[0] + v.fwd[1] * cr[1] + v.fwd[2] * cr[2] >= lim) cand.push_back(i);
            }
            if (cand.empty()) continue;
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    double d[3];
                    equirectPixelToWorldRay(x + 0.5, y + 0.5, outW, outH, d[0], d[1], d[2]);
                    struct Hit { int i; double s, edge, fx, fy, r2; };
                    Hit bestPri{}, bestAny{};
                    double scorePri = 1e9, scoreAny = 1e9;
                    bool havePri = false, haveAny = false;
                    for (int i : cand) {
                        const FrameView& v = views[i];
                        const double cx = v.M[0] * d[0] + v.M[3] * d[1] + v.M[6] * d[2];
                        const double cy = v.M[1] * d[0] + v.M[4] * d[1] + v.M[7] * d[2];
                        const double cz = v.M[2] * d[0] + v.M[5] * d[1] + v.M[8] * d[2];
                        if (cz >= -1e-6) continue;
                        double nx = cx / -cz, ny = cy / -cz;
                        if (mirror) nx = -nx;
                        distortNorm(nx, ny);
                        const double u = nx / tanH, w = ny / tanV;
                        if (std::fabs(u) >= 1.0 || std::fabs(w) >= 1.0) continue;
                        Hit candH;
                        candH.i = i;
                        candH.s = std::sqrt(u * u + w * w);
                        candH.edge = 1.0 - std::max(std::fabs(u), std::fabs(w));
                        candH.fx = (u * 0.5 + 0.5) * (v.w - 1);
                        candH.fy = (0.5 - w * 0.5) * (v.h - 1);
                        candH.r2 = std::min(1.0, 0.5 * (u * u + w * w));
                        const int px = std::min(v.w - 1, std::max(0, (int)std::lround(candH.fx)));
                        const int py = std::min(v.h - 1, std::max(0, (int)std::lround(candH.fy)));
                        const int32_t cp = v.px[(size_t)py * v.w + px];
                        const int cr = (cp >> 16) & 0xff, cg = (cp >> 8) & 0xff, cb = cp & 0xff;
                        double score = candH.s;
                        if (cr >= 248 || cg >= 248 || cb >= 248) score += 2.0;
                        if (cr <= 6 && cg <= 6 && cb <= 6) score += 2.0;
                        if (sHaveCameraEv) {
                            const double gn = (v.gain[0] + v.gain[1] + v.gain[2]) / 3.0;
                            score += 0.55 * std::fabs(std::log(std::max(0.05, gn)));
                        }
                        if (score < scoreAny) { scoreAny = score; bestAny = candH; haveAny = true; }
                        if (!v.fill && score < scorePri) { scorePri = score; bestPri = candH; havePri = true; }
                    }
                    if (!haveAny) continue;
                    const Hit& chosen = havePri ? bestPri : bestAny;
                    auto evalHit = [&](const Hit& hh, double* rgb) {
                        const FrameView& vv = views[hh.i];
                        const double invVv = sHaveCameraEv ? 1.0
                            : std::exp(-(vig0 * hh.r2 + vig1 * hh.r2 * hh.r2));
                        const double fxx = std::max(0.0, std::min((double)(vv.w - 1), hh.fx));
                        const double fyy = std::max(0.0, std::min((double)(vv.h - 1), hh.fy));
                        const int xa = (int)fxx, ya = (int)fyy;
                        const int xb = std::min(vv.w - 1, xa + 1), yb = std::min(vv.h - 1, ya + 1);
                        const double axx = fxx - xa, ayy = fyy - ya;
                        const int32_t q00 = vv.px[(size_t)ya * vv.w + xa];
                        const int32_t q10 = vv.px[(size_t)ya * vv.w + xb];
                        const int32_t q01 = vv.px[(size_t)yb * vv.w + xa];
                        const int32_t q11 = vv.px[(size_t)yb * vv.w + xb];
                        auto chn = [](int32_t p, int sh) { return (p >> sh) & 0xff; };
                        const double gx = std::max(0.0, std::min((double)(SEAM_GN - 1),
                            fxx / std::max(1, vv.w - 1) * (SEAM_GN - 1)));
                        const double gy = std::max(0.0, std::min((double)(SEAM_GN - 1),
                            fyy / std::max(1, vv.h - 1) * (SEAM_GN - 1)));
                        const int xg0 = (int)gx, yg0 = (int)gy;
                        const int xg1 = std::min(SEAM_GN - 1, xg0 + 1), yg1 = std::min(SEAM_GN - 1, yg0 + 1);
                        const double agx = gx - xg0, agy = gy - yg0;
                        for (int c = 0; c < 3; c++) {
                            const int sh = 16 - 8 * c;
                            const float* sg = vv.seamG;
                            const double sm =
                                (1 - agx) * (1 - agy) * sg[(yg0 * SEAM_GN + xg0) * 3 + c] +
                                agx * (1 - agy) * sg[(yg0 * SEAM_GN + xg1) * 3 + c] +
                                (1 - agx) * agy * sg[(yg1 * SEAM_GN + xg0) * 3 + c] +
                                agx * agy * sg[(yg1 * SEAM_GN + xg1) * 3 + c];
                            if (sHaveCameraEv) {
                                const double lin =
                                    (1.0 - axx) * (1.0 - ayy) * srgb8ToLin(chn(q00, sh)) +
                                    axx * (1.0 - ayy) * srgb8ToLin(chn(q10, sh)) +
                                    (1.0 - axx) * ayy * srgb8ToLin(chn(q01, sh)) +
                                    axx * ayy * srgb8ToLin(chn(q11, sh));
                                rgb[c] = linToSrgb8(lin * vv.gain[c] * invVv * sm);
                            } else {
                                const double s =
                                    (1.0 - axx) * (1.0 - ayy) * (double)chn(q00, sh) +
                                    axx * (1.0 - ayy) * (double)chn(q10, sh) +
                                    (1.0 - axx) * ayy * (double)chn(q01, sh) +
                                    axx * ayy * (double)chn(q11, sh);
                                rgb[c] = (vv.gain[c] * s * invVv + vv.offset[c]) * sm;
                            }
                        }
                    };
                    double col[3];
                    evalHit(chosen, col);
                    const size_t idx = (size_t)y * outW + x;
                    eqR[idx] = (float)std::max(0.0, std::min(255.0, col[0]));
                    eqG[idx] = (float)std::max(0.0, std::min(255.0, col[1]));
                    eqB[idx] = (float)std::max(0.0, std::min(255.0, col[2]));
                    eqWeight[idx] = 1.f;
                }
            }
        }
    };
    const int threads = std::max(1, std::min(8, (int)std::thread::hardware_concurrency()));
    std::atomic<int> next{0};
    std::vector<std::thread> pool;
    for (int t = 0; t < threads; t++) pool.emplace_back(worker, &next);
    for (auto& th : pool) th.join();

    size_t holes = 0;
    for (float w : eqWeight) holes += w > 0.f ? 0 : 1;
    sGaHoleFraction = (float)holes / std::max<size_t>(1, eqWeight.size());
    if (holes > 0) ga::pushPullFill(eqR, eqG, eqB, eqWeight, outW, outH);
    LOGI("Global align compose: %dx%d from %d frames, holes=%.3f%% in %lldus",
         outW, outH, n, 100.0 * sGaHoleFraction, (long long)elapsedUs(t0));
    sphereProfileLogUs("global_align_compose", elapsedUs(t0));
}

#endif  // SPHERE_GLOBAL_ALIGN_INL
