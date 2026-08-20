// Lightweight ORB-style feature matching for spherical panorama pose graph.
// Included from sphere_jni.cpp (camera −Z forward, same ray model as pixelToRay).

#ifndef SPHERE_FEATURE_MATCH_INL
#define SPHERE_FEATURE_MATCH_INL

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
    m[0] = 1 - 2 * (y * y + z * z);
    m[1] = 2 * (x * y + z * w);
    m[2] = 2 * (x * z - y * w);
    m[3] = 2 * (x * y - z * w);
    m[4] = 1 - 2 * (x * x + z * z);
    m[5] = 2 * (y * z + x * w);
    m[6] = 2 * (x * z + y * w);
    m[7] = 2 * (y * z - x * w);
    m[8] = 1 - 2 * (x * x + y * y);
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

static inline void estimateRotWahba(
    const double* raysA, const double* raysB, int count, double* outR) {

    double h[9] = {};
    for (int i = 0; i < count; i++) {
        const double* a = raysA + i * 3;
        const double* b = raysB + i * 3;
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++)
                h[r * 3 + c] += b[r] * a[c];
    }
    // SVD 3x3 via Jacobi — use power iteration for dominant singular vectors (small).
    // Fallback: normalize h as rank-1 approx then orthogonalize via Gram-Schmidt.
    double u0[3] = {h[0], h[3], h[6]};
    double len0 = std::sqrt(u0[0]*u0[0]+u0[1]*u0[1]+u0[2]*u0[2]);
    if (len0 < 1e-9) {
        for (int i = 0; i < 9; i++) outR[i] = (i % 4 == 0) ? 1.0 : 0.0;
        return;
    }
    u0[0]/=len0; u0[1]/=len0; u0[2]/=len0;
    double u1[3] = {h[1], h[4], h[7]};
    double dot01 = u1[0]*u0[0]+u1[1]*u0[1]+u1[2]*u0[2];
    u1[0]-=dot01*u0[0]; u1[1]-=dot01*u0[1]; u1[2]-=dot01*u0[2];
    double len1 = std::sqrt(u1[0]*u1[0]+u1[1]*u1[1]+u1[2]*u1[2]);
    if (len1 < 1e-9) {
        u1[0] = u0[1]; u1[1] = -u0[0]; u1[2] = 0;
        len1 = std::sqrt(u1[0]*u1[0]+u1[1]*u1[1]+u1[2]*u1[2]);
    }
    u1[0]/=len1; u1[1]/=len1; u1[2]/=len1;
    double u2[3] = {
        u0[1]*u1[2]-u0[2]*u1[1],
        u0[2]*u1[0]-u0[0]*u1[2],
        u0[0]*u1[1]-u0[1]*u1[0]
    };
    // R = U * Vt with U=[u0,u1,u2], V from H^T columns similarly.
    double v0[3] = {h[0], h[1], h[2]};
    double lv0 = std::sqrt(v0[0]*v0[0]+v0[1]*v0[1]+v0[2]*v0[2]);
    if (lv0 < 1e-9) lv0 = 1.0;
    v0[0]/=lv0; v0[1]/=lv0; v0[2]/=lv0;
    double v1[3] = {h[3], h[4], h[5]};
    double dotv = v1[0]*v0[0]+v1[1]*v0[1]+v1[2]*v0[2];
    v1[0]-=dotv*v0[0]; v1[1]-=dotv*v0[1]; v1[2]-=dotv*v0[2];
    double lv1 = std::sqrt(v1[0]*v1[1]*0+v1[1]*v1[1]+v1[2]*v1[2]);
    lv1 = std::sqrt(v1[0]*v1[0]+v1[1]*v1[1]+v1[2]*v1[2]);
    if (lv1 < 1e-9) { v1[0]=v0[1]; v1[1]=-v0[0]; v1[2]=0; lv1=std::sqrt(v1[0]*v1[0]+v1[1]*v1[1]); }
    v1[0]/=lv1; v1[1]/=lv1; v1[2]/=lv1;
    double v2[3] = {v0[1]*v1[2]-v0[2]*v1[1], v0[2]*v1[0]-v0[0]*v1[2], v0[0]*v1[1]-v0[1]*v1[0]};
    for (int r = 0; r < 3; r++) {
        for (int c = 0; c < 3; c++) {
            outR[r*3+c] = u0[r]*v0[c] + u1[r]*v1[c] + u2[r]*v2[c];
        }
    }
    double det = outR[0]*(outR[4]*outR[8]-outR[5]*outR[7])
               - outR[1]*(outR[3]*outR[8]-outR[5]*outR[6])
               + outR[2]*(outR[3]*outR[7]-outR[4]*outR[6]);
    if (det < 0) {
        for (int i = 0; i < 3; i++) outR[2*3+i] = -outR[2*3+i];
    }
}

struct FeatureKeypoint {
    float x = 0.f, y = 0.f;
    uint8_t desc[32]{};
};

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

static void detectBriefFeatures(
    const std::vector<uint8_t>& gray, int gw, int gh,
    std::vector<FeatureKeypoint>& kps) {

    kps.clear();
    if (gw < 16 || gh < 16) return;
    constexpr int patch = 7;
    static const int offsets[32][4] = {
        {-3,-2, 3,2}, {-1,-4, 2,3}, {-4,1, 2,-2}, {0,-3, 3,1},
        {1,-2,-3,3}, {-2,0, 2,4}, {3,-3,-1,2}, {-4,-1, 1,4},
        {2,2,-2,-3}, {0,3,-3,-1}, {-3,3, 3,-3}, {4,0,-4,1},
        {-1,-1, 1,1}, {2,-4,-2,4}, {-4,2, 4,-2}, {1,4,-1,-4},
        {-2,-3, 3,2}, {4,1,-3,-2}, {-3,0, 1,3}, {0,2,-2,-2},
        {2,-1,-4,3}, {-1,3, 2,-3}, {3,3,-3,-1}, {-4,-3, 0,4},
        {1,-3,-2,2}, {-2,4, 4,-4}, {0,-2, 2,0}, {-3,-4, 1,3},
        {4,-2,-1,1}, {-2,2, 3,-1}, {1,0,-3,2}, {-1,-2, 2,3}
    };
    for (int y = patch; y < gh - patch; y += 3) {
        for (int x = patch; x < gw - patch; x += 3) {
            int ix = gray[(size_t)y * gw + (x + 1)] - gray[(size_t)y * gw + (x - 1)];
            int iy = gray[(size_t)(y + 1) * gw + x] - gray[(size_t)(y - 1) * gw + x];
            int score = ix * ix + iy * iy;
            if (score < 400) continue;
            FeatureKeypoint kp;
            kp.x = (float)x * (sFrames.empty() ? 1.f : 1.f); // scaled later
            kp.y = (float)y;
            for (int d = 0; d < 32; d++) {
                int x1 = x + offsets[d][0], y1 = y + offsets[d][1];
                int x2 = x + offsets[d][2], y2 = y + offsets[d][3];
                x1 = clampI(x1, 0, gw-1); x2 = clampI(x2, 0, gw-1);
                y1 = clampI(y1, 0, gh-1); y2 = clampI(y2, 0, gh-1);
                uint8_t bit = gray[(size_t)y1*gw+x1] < gray[(size_t)y2*gw+x2] ? 1 : 0;
                kp.desc[d] |= (bit << (d % 8));
            }
            kps.push_back(kp);
            if ((int)kps.size() >= FEATURE_MAX_KPS) return;
        }
    }
}

static int hamming32(const uint8_t* a, const uint8_t* b) {
    int d = 0;
    for (int i = 0; i < 32; i++) {
        uint8_t v = a[i] ^ b[i];
        v = (v & 0x55) + ((v >> 1) & 0x55);
        v = (v & 0x33) + ((v >> 2) & 0x33);
        d += (v & 0x0f) + ((v >> 4) & 0x0f);
    }
    return d;
}

static int matchFeaturePair(
    const StoredFrame& fa, const StoredFrame& fb,
    double tanH, double tanV,
    double* outRelRot, int& outInliers) {

    std::vector<uint8_t> ga, gb;
    int gwa, gha, gwb, ghb;
    frameToGraySmall(fa, 384, ga, gwa, gha);
    frameToGraySmall(fb, 384, gb, gwb, ghb);
    std::vector<FeatureKeypoint> kpa, kpb;
    detectBriefFeatures(ga, gwa, gha, kpa);
    detectBriefFeatures(gb, gwb, ghb, kpb);
    if (kpa.size() < 12 || kpb.size() < 12) return 0;

    const float scaleA_x = (float)fa.w / gwa, scaleA_y = (float)fa.h / gha;
    const float scaleB_x = (float)fb.w / gwb, scaleB_y = (float)fb.h / ghb;

    struct Match { int ia; int ib; int dist; };
    std::vector<Match> matches;
    for (size_t i = 0; i < kpa.size(); i++) {
        int best = 256, second = 256, bestJ = -1;
        for (size_t j = 0; j < kpb.size(); j++) {
            int d = hamming32(kpa[i].desc, kpb[j].desc);
            if (d < best) { second = best; best = d; bestJ = (int)j; }
            else if (d < second) second = d;
        }
        if (bestJ >= 0 && best < 0.8 * second) {
            matches.push_back({(int)i, bestJ, best});
        }
    }
    if (matches.size() < 12) return 0;

    std::vector<double> raysA, raysB;
    raysA.reserve(matches.size() * 3);
    raysB.reserve(matches.size() * 3);
    for (const auto& m : matches) {
        float fxA = kpa[m.ia].x * scaleA_x, fyA = kpa[m.ia].y * scaleA_y;
        float fxB = kpb[m.ib].x * scaleB_x, fyB = kpb[m.ib].y * scaleB_y;
        double rax, ray, raz, rbx, rby, rbz;
        pixelToRayFloat(fxA, fyA, fa.w, fa.h, tanH, tanV, rax, ray, raz);
        pixelToRayFloat(fxB, fyB, fb.w, fb.h, tanH, tanV, rbx, rby, rbz);
        raysA.push_back(rax); raysA.push_back(ray); raysA.push_back(raz);
        raysB.push_back(rbx); raysB.push_back(rby); raysB.push_back(rbz);
    }

    double r[9];
    estimateRotWahba(raysA.data(), raysB.data(), (int)matches.size(), r);

    int inliers = 0;
    for (size_t k = 0; k < matches.size(); k++) {
        const double* rb = raysB.data() + k * 3;
        double pr[3] = {
            r[0]*rb[0]+r[1]*rb[1]+r[2]*rb[2],
            r[3]*rb[0]+r[4]*rb[1]+r[5]*rb[2],
            r[6]*rb[0]+r[7]*rb[1]+r[8]*rb[2]
        };
        const double* ra = raysA.data() + k * 3;
        double dot = pr[0]*ra[0]+pr[1]*ra[1]+pr[2]*ra[2];
        dot = std::max(-1.0, std::min(1.0, dot));
        if (std::acos(dot) < 2.5 * DEG2RAD) inliers++;
    }

    for (int i = 0; i < 9; i++) outRelRot[i] = r[i];
    outInliers = inliers;
    return (int)matches.size();
}

static void buildFeatureEdgesLocked() {
    sFeatureEdges.clear();
    const int n = (int)sFrames.size();
    if (n < 2) return;
    double tanH, tanV;
    sessionTanHalfFov(tanH, tanV);
    auto t0 = SteadyClock::now();
    for (int a = 1; a < n; a++) {
        for (int b = std::max(0, a - 3); b < a; b++) {
            double rel[9];
            int inliers = 0;
            int matches = matchFeaturePair(sFrames[a], sFrames[b], tanH, tanV, rel, inliers);
            if (matches < 12 || inliers < FEATURE_MIN_INLIERS) continue;
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
        int matches = matchFeaturePair(sFrames[0], sFrames[n-1], tanH, tanV, rel, inliers);
        if (matches >= 12 && inliers >= FEATURE_MIN_INLIERS) {
            FeatureEdge e;
            e.frameA = 0;
            e.frameB = n - 1;
            for (int i = 0; i < 9; i++) e.relRot[i] = rel[i];
            e.inliers = inliers;
            e.weight = std::min(4.0, (double)inliers / 30.0) * 1.5;
            sFeatureEdges.push_back(e);
        }
    }
    LOGI("Feature edges: %zu pairs built in %lldus", sFeatureEdges.size(), (long long)elapsedUs(t0));
}

static double featureGraphPenaltyForFrame(int frameIdx, const Quat& qCand) {
    if (sFeatureEdges.empty()) return 0.0;
    double mCand[9];
    mat3FromQuat(qCand, mCand);
    double penalty = 0.0;
    for (const FeatureEdge& e : sFeatureEdges) {
        if (e.frameA != frameIdx && e.frameB != frameIdx) continue;
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
    int sum = 0;
    for (const auto& e : sFeatureEdges) {
        if (e.frameA == frameIdx || e.frameB == frameIdx) sum += e.inliers;
    }
    return sum;
}

static Quat weightedQuatAverage(const std::vector<Quat>& qs, const std::vector<double>& ws) {
    double sx = 0, sy = 0, sz = 0, sw = 0, tw = 0;
    for (size_t i = 0; i < qs.size(); i++) {
        tw += ws[i];
        sx += ws[i] * qs[i].x;
        sy += ws[i] * qs[i].y;
        sz += ws[i] * qs[i].z;
        sw += ws[i] * qs[i].w;
    }
    if (tw < 1e-9) return {0, 0, 0, 1};
    Quat q{sx / tw, sy / tw, sz / tw, sw / tw};
    double len = std::sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w);
    if (len > 1e-9) { q.x /= len; q.y /= len; q.z /= len; q.w /= len; }
    return q;
}

static int seedPosesFromFeatureGraphLocked() {
    const int n = (int)sFrames.size();
    if (n < 2 || sFeatureEdges.empty()) return 0;

    std::vector<double> mats(n * 9);
    for (int i = 0; i < n; i++) {
        mat3FromQuat(sFrames[i].imuQuat, mats.data() + i * 9);
        sFrames[i].refinedQuat = sFrames[i].imuQuat;
    }

    for (int iter = 0; iter < 12; iter++) {
        for (int i = 0; i < n; i++) {
            std::vector<Quat> cand;
            std::vector<double> wts;
            cand.push_back(sFrames[i].imuQuat);
            wts.push_back(FEATURE_IMU_GRAPH_LAMBDA);
            for (const FeatureEdge& e : sFeatureEdges) {
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
        if (std::sqrt(quatAngularDistSq(sFrames[i].refinedQuat, sFrames[i].imuQuat)) > 0.01) adjusted++;
        sFrames[i].fastCorrectedQuat = sFrames[i].refinedQuat;
    }
    LOGI("Feature pose graph seed: %d frames adjusted from %zu edges", adjusted, sFeatureEdges.size());
    if (adjusted > 0) rebuildOverlapBankLocked();
    return adjusted;
}

#endif  // SPHERE_FEATURE_MATCH_INL
