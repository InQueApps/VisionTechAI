/**
 * Sphere panorama engine (JNI).
 *
 * Accumulates camera frames into a 4096x2048 equirectangular buffer using
 * quaternion-based orientation from the device IMU. Supports rectilinear
 * (perspective) extraction for interactive viewing.
 *
 * Coordinate convention:
 *   Earth frame (from Android rotation vector sensor): X=East, Y=North, Z=Up
 *   Equirect mapping: lon = atan2(east, north), lat = asin(up)
 *   Camera looks along -Z in device space (out the back of the phone)
 *
 * No external dependencies beyond standard math and JNI.
 */

#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <cstring>
#include <algorithm>
#include <vector>
#include <mutex>

#define LOG_TAG "SphereJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static constexpr double PI = 3.14159265358979323846;

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

// ── Equirectangular buffer ──────────────────────────────────────────────

static int eqW = 0, eqH = 0;
static float storedFovH = 0, storedFovV = 0;
static std::vector<float> eqR, eqG, eqB, eqWeight;
static std::mutex sphereMutex;

static inline int clampI(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

static inline void splatPixel(int ex, int ey, float r, float g, float b, float w) {
    if (ex < 0 || ex >= eqW || ey < 0 || ey >= eqH) return;
    int idx = ey * eqW + ex;
    eqR[idx] += r * w;
    eqG[idx] += g * w;
    eqB[idx] += b * w;
    eqWeight[idx] += w;
}

// ── JNI functions ───────────────────────────────────────────────────────

extern "C" {

JNIEXPORT jboolean JNICALL
Java_ab_inque_visiontechai_SphereNative_nativeInit(
    JNIEnv*, jobject, jint width, jint height, jfloat fovH, jfloat fovV) {
    std::lock_guard<std::mutex> lock(sphereMutex);
    eqW = width;
    eqH = height;
    storedFovH = fovH;
    storedFovV = fovV;
    int sz = eqW * eqH;
    eqR.assign(sz, 0.f);
    eqG.assign(sz, 0.f);
    eqB.assign(sz, 0.f);
    eqWeight.assign(sz, 0.f);
    LOGI("Sphere init: %dx%d  fov=%.1f x %.1f", eqW, eqH, fovH, fovV);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_ab_inque_visiontechai_SphereNative_nativeAddFrame(
    JNIEnv* env, jobject,
    jintArray jPixels, jint frameW, jint frameH,
    jfloat qx, jfloat qy, jfloat qz, jfloat qw) {

    std::lock_guard<std::mutex> lock(sphereMutex);
    if (eqW == 0 || eqH == 0) return JNI_FALSE;

    jint* pixels = env->GetIntArrayElements(jPixels, nullptr);
    if (!pixels) return JNI_FALSE;

    Quat q = { qx, qy, qz, qw };
    double qlen = std::sqrt(q.x*q.x + q.y*q.y + q.z*q.z + q.w*q.w);
    if (qlen < 1e-9) { env->ReleaseIntArrayElements(jPixels, pixels, JNI_ABORT); return JNI_FALSE; }
    q.x /= qlen; q.y /= qlen; q.z /= qlen; q.w /= qlen;

    double halfFovH = storedFovH * 0.5 * PI / 180.0;
    double halfFovV = storedFovV * 0.5 * PI / 180.0;
    double tanH = std::tan(halfFovH);
    double tanV = std::tan(halfFovV);

    // ── Phase 1: sparse sampling for gain compensation + overlap collection ──
    // Project a sparse grid of frame pixels to equirect coordinates. For pixels
    // that land on already-filled equirect regions, record the mapping for both
    // gain estimation and translational alignment refinement.
    struct OverlapSample {
        double eqX, eqY;
        float newR, newG, newB;
    };

    constexpr int SAMPLE_STEP = 8;
    std::vector<OverlapSample> overlap;
    overlap.reserve((frameW / SAMPLE_STEP + 1) * (frameH / SAMPLE_STEP + 1));
    double sumExR = 0, sumExG = 0, sumExB = 0;
    double sumNewR = 0, sumNewG = 0, sumNewB = 0;

    for (int fy = 0; fy < frameH; fy += SAMPLE_STEP) {
        double ny = (0.5 - (double)fy / (frameH - 1)) * 2.0 * tanV;
        for (int fx = 0; fx < frameW; fx += SAMPLE_STEP) {
            double nx = ((double)fx / (frameW - 1) - 0.5) * 2.0 * tanH;
            double len = std::sqrt(nx * nx + ny * ny + 1.0);
            double rx = nx / len, ry = ny / len, rz = -1.0 / len;
            double wx, wy, wz;
            quatRotate(q, rx, ry, rz, wx, wy, wz);
            double lon = std::atan2(wx, wy);
            double lat = std::asin(std::max(-1.0, std::min(1.0, wz)));
            double eqXf = (lon / PI + 1.0) * 0.5 * eqW;
            double eqYf = (0.5 - lat / PI) * eqH;
            int eqX = ((int)eqXf + eqW) % eqW;
            int eqY = clampI((int)eqYf, 0, eqH - 1);
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
                overlap.push_back({eqXf, eqYf, nr, ng, nb});
            }
        }
    }

    double gainR = 1.0, gainG = 1.0, gainB = 1.0;
    if (overlap.size() > 30 && sumNewR > 1 && sumNewG > 1 && sumNewB > 1) {
        gainR = std::max(0.5, std::min(2.0, sumExR / sumNewR));
        gainG = std::max(0.5, std::min(2.0, sumExG / sumNewG));
        gainB = std::max(0.5, std::min(2.0, sumExB / sumNewB));
    }

    // ── Phase 2: coarse-to-fine translational alignment ──
    // Two-level pyramid search to correct IMU timing lag and drift.
    // Coarse: large radius, step 4, using every 4th overlap sample (~7° range)
    // Fine:   ±4 around coarse best, step 1, all overlap samples (sub-pixel grid)
    double bestOffX = 0.0, bestOffY = 0.0;

    if (overlap.size() > 50) {
        // ── Coarse pass ──
        constexpr int COARSE_RADIUS = 80;   // ±80 px ≈ ±7° on 4096-wide equirect
        constexpr int COARSE_STEP = 4;
        constexpr int COARSE_SUBSAMPLE = 4; // use every 4th overlap sample
        double bestSSD = 1e18;

        for (int oy = -COARSE_RADIUS; oy <= COARSE_RADIUS; oy += COARSE_STEP) {
            for (int ox = -COARSE_RADIUS; ox <= COARSE_RADIUS; ox += COARSE_STEP) {
                double ssd = 0.0;
                int count = 0;
                for (size_t i = 0; i < overlap.size(); i += COARSE_SUBSAMPLE) {
                    const auto& s = overlap[i];
                    int sx = ((int)(s.eqX + ox) + eqW) % eqW;
                    int sy = clampI((int)(s.eqY + oy), 0, eqH - 1);
                    int sidx = sy * eqW + sx;
                    if (eqWeight[sidx] > 0.f) {
                        float inv = 1.f / eqWeight[sidx];
                        double dr = s.newR * gainR - eqR[sidx] * inv;
                        double dg = s.newG * gainG - eqG[sidx] * inv;
                        double db = s.newB * gainB - eqB[sidx] * inv;
                        ssd += dr * dr + dg * dg + db * db;
                        count++;
                    }
                }
                if (count > 20) {
                    ssd /= count;
                    if (ssd < bestSSD) {
                        bestSSD = ssd;
                        bestOffX = ox;
                        bestOffY = oy;
                    }
                }
            }
        }

        // ── Fine pass ──
        constexpr int FINE_RADIUS = 4;
        int coarseX = static_cast<int>(bestOffX);
        int coarseY = static_cast<int>(bestOffY);
        bestSSD = 1e18;

        for (int oy = coarseY - FINE_RADIUS; oy <= coarseY + FINE_RADIUS; oy++) {
            for (int ox = coarseX - FINE_RADIUS; ox <= coarseX + FINE_RADIUS; ox++) {
                double ssd = 0.0;
                int count = 0;
                for (const auto& s : overlap) {
                    int sx = ((int)(s.eqX + ox) + eqW) % eqW;
                    int sy = clampI((int)(s.eqY + oy), 0, eqH - 1);
                    int sidx = sy * eqW + sx;
                    if (eqWeight[sidx] > 0.f) {
                        float inv = 1.f / eqWeight[sidx];
                        double dr = s.newR * gainR - eqR[sidx] * inv;
                        double dg = s.newG * gainG - eqG[sidx] * inv;
                        double db = s.newB * gainB - eqB[sidx] * inv;
                        ssd += dr * dr + dg * dg + db * db;
                        count++;
                    }
                }
                if (count > 30) {
                    ssd /= count;
                    if (ssd < bestSSD) {
                        bestSSD = ssd;
                        bestOffX = ox;
                        bestOffY = oy;
                    }
                }
            }
        }

        if (bestOffX != 0.0 || bestOffY != 0.0) {
            LOGI("Alignment correction: dx=%.0f dy=%.0f (%zu overlap samples)",
                 bestOffX, bestOffY, overlap.size());
        }
    }

    // ── Phase 3: project and splat with gain + alignment offset ──
    double featherX = frameW * 0.25;
    double featherY = frameH * 0.25;

    for (int fy = 0; fy < frameH; fy++) {
        double ny = (0.5 - (double)fy / (frameH - 1)) * 2.0 * tanV;
        for (int fx = 0; fx < frameW; fx++) {
            double nx = ((double)fx / (frameW - 1) - 0.5) * 2.0 * tanH;

            double len = std::sqrt(nx*nx + ny*ny + 1.0);
            double rx = nx / len, ry = ny / len, rz = -1.0 / len;

            double wx, wy, wz;
            quatRotate(q, rx, ry, rz, wx, wy, wz);

            double lon = std::atan2(wx, wy);
            double lat = std::asin(std::max(-1.0, std::min(1.0, wz)));

            double eqXf = (lon / PI + 1.0) * 0.5 * eqW + bestOffX;
            double eqYf = (0.5 - lat / PI) * eqH + bestOffY;

            int32_t p = pixels[fy * frameW + fx];
            float pr = static_cast<float>((p >> 16) & 0xff) * static_cast<float>(gainR);
            float pg = static_cast<float>((p >> 8) & 0xff) * static_cast<float>(gainG);
            float pb = static_cast<float>(p & 0xff) * static_cast<float>(gainB);

            double edgeDistX = std::min((double)fx, (double)(frameW - 1 - fx));
            double edgeDistY = std::min((double)fy, (double)(frameH - 1 - fy));
            double edgeFrac = std::min(1.0, std::min(edgeDistX / featherX, edgeDistY / featherY));
            float fw = static_cast<float>(0.5 - 0.5 * std::cos(edgeFrac * PI));

            int x0 = static_cast<int>(std::floor(eqXf));
            int y0 = static_cast<int>(std::floor(eqYf));
            double fracX = eqXf - x0;
            double fracY = eqYf - y0;
            int x1 = x0 + 1;
            int y1 = y0 + 1;

            if (x0 < 0) x0 += eqW;
            if (x1 >= eqW) x1 -= eqW;
            x0 = ((x0 % eqW) + eqW) % eqW;

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

    env->ReleaseIntArrayElements(jPixels, pixels, JNI_ABORT);
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

    // First pass: resolve weighted averages
    for (int i = 0; i < sz; i++) {
        if (eqWeight[i] > 0.f) {
            float inv = 1.f / eqWeight[i];
            int r = clampI(static_cast<int>(eqR[i] * inv), 0, 255);
            int g = clampI(static_cast<int>(eqG[i] * inv), 0, 255);
            int b = clampI(static_cast<int>(eqB[i] * inv), 0, 255);
            out[i] = static_cast<int32_t>(0xff000000 | (r << 16) | (g << 8) | b);
        } else {
            out[i] = 0; // mark as unfilled
        }
    }

    // Second pass: fill isolated gaps (unfilled pixels surrounded by filled ones)
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

    /*
     * Viewer coordinate system (equirect space):
     *   X = East (right), Y = North (forward at lon=0), Z = Up (sky)
     *
     * At azimuth=0, elevation=0 we look North (+Y).
     * Forward ray = (0, 1, 0), Right = (1, 0, 0), Up = (0, 0, 1).
     *
     * Azimuth rotates around Z (up), negative angle so positive azimuth = look East (right).
     * Elevation rotates around X (right), positive angle = look up.
     */
    Quat qAz = quatFromAxisAngle(0, 0, 1, -azimuth);
    Quat qEl = quatFromAxisAngle(1, 0, 0, elevation);
    Quat qLook = quatMul(qAz, qEl);

    double halfFov = fov * 0.5 * PI / 180.0;
    double tanFov = std::tan(halfFov);
    double aspect = static_cast<double>(outW) / outH;

    for (int y = 0; y < outH; y++) {
        double upOff = (0.5 - (double)y / (outH - 1)) * 2.0 * tanFov;
        for (int x = 0; x < outW; x++) {
            double rightOff = ((double)x / (outW - 1) - 0.5) * 2.0 * tanFov * aspect;

            // Ray in viewer local space: right=X, forward=Y, up=Z
            double rx = rightOff;
            double ry = 1.0;
            double rz = upOff;
            double len = std::sqrt(rx*rx + ry*ry + rz*rz);
            rx /= len; ry /= len; rz /= len;

            double wx, wy, wz;
            quatRotate(qLook, rx, ry, rz, wx, wy, wz);

            // Same equirect mapping as nativeAddFrame
            double lon = std::atan2(wx, wy);
            double lat = std::asin(std::max(-1.0, std::min(1.0, wz)));

            double eqXf = (lon / PI + 1.0) * 0.5 * eqW;
            double eqYf = (0.5 - lat / PI) * eqH;

            // Bilinear sampling
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
