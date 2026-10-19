#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
namespace robotics::panorama {

struct Quat {
    double x, y, z, w;
};

inline void quatRotate(const Quat& q, double vx, double vy, double vz,
                       double& ox, double& oy, double& oz) {
    double tx = 2.0 * (q.y * vz - q.z * vy);
    double ty = 2.0 * (q.z * vx - q.x * vz);
    double tz = 2.0 * (q.x * vy - q.y * vx);
    ox = vx + q.w * tx + (q.y * tz - q.z * ty);
    oy = vy + q.w * ty + (q.z * tx - q.x * tz);
    oz = vz + q.w * tz + (q.x * ty - q.y * tx);
}

inline Quat quatFromAxisAngle(double ax, double ay, double az, double angle) {
    double s = std::sin(angle * 0.5);
    return {ax * s, ay * s, az * s, std::cos(angle * 0.5)};
}

inline Quat quatMul(const Quat& a, const Quat& b) {
    return {
        a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
        a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
        a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
        a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z
    };
}

/** Small-angle rotation quaternion from tangent vector (tx, ty, tz) in radians. */
inline Quat quatExpTangent(double tx, double ty, double tz) {
    double angle = std::sqrt(tx * tx + ty * ty + tz * tz);
    if (angle < 1e-12) return {0, 0, 0, 1};
    double half = 0.5 * angle;
    double s = std::sin(half) / angle;
    return {tx * s, ty * s, tz * s, std::cos(half)};
}

/** World-frame SO(3) correction: exp(delta) * q (independent X/Y/Z tangent axes). */
inline Quat quatApplyTangentDelta(const Quat& q, double tx, double ty, double tz) {
    return quatMul(quatExpTangent(tx, ty, tz), q);
}

inline double quatAngularDistSq(const Quat& a, const Quat& b) {
    double dot = std::abs(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w);
    dot = std::min(1.0, std::max(-1.0, dot));
    double ang = 2.0 * std::acos(dot);
    return ang * ang;
}

inline void normalizeQuat(Quat& q) {
    double len = std::sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w);
    if (len < 1e-12) return;
    q.x /= len;
    q.y /= len;
    q.z /= len;
    q.w /= len;
}

/** Row-major 3×3 rotation matrix matching quatRotate() (active rotation). */
inline void mat3FromQuat(const Quat& q, double* m) {
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

inline void mat3RotateVec(const double* m, double vx, double vy, double vz,
                          double& ox, double& oy, double& oz) {
    ox = m[0] * vx + m[1] * vy + m[2] * vz;
    oy = m[3] * vx + m[4] * vy + m[5] * vz;
    oz = m[6] * vx + m[7] * vy + m[8] * vz;
}

inline void mat3Mul(const double* a, const double* b, double* out) {
    for (int r = 0; r < 3; r++) {
        for (int c = 0; c < 3; c++) {
            out[r * 3 + c] = a[r * 3 + 0] * b[0 * 3 + c]
                           + a[r * 3 + 1] * b[1 * 3 + c]
                           + a[r * 3 + 2] * b[2 * 3 + c];
        }
    }
}

inline void mat3Transpose(const double* a, double* out) {
    for (int r = 0; r < 3; r++)
        for (int c = 0; c < 3; c++)
            out[r * 3 + c] = a[c * 3 + r];
}

inline double mat3AngularDistRad(const double* a, const double* b) {
    double prod[9];
    double bt[9];
    mat3Transpose(b, bt);
    mat3Mul(a, bt, prod);
    double trace = prod[0] + prod[4] + prod[8];
    trace = std::max(-1.0, std::min(3.0, trace));
    return std::acos(std::max(-1.0, std::min(1.0, (trace - 1.0) * 0.5)));
}

/**
 * Davenport quaternion Wahba: find R with a ≈ R b (maps camera-B ray → camera-A ray).
 * S = Σ b_i a_i^T.
 */
inline Quat estimateRotWahbaQuat(
    const double* raysA, const double* raysB, int count) {

    double S[9] = {};
    for (int i = 0; i < count; i++) {
        const double* a = raysA + i * 3;
        const double* b = raysB + i * 3;
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++)
                S[r * 3 + c] += b[r] * a[c];
    }

    double N[4][4];
    N[0][0] = S[0] + S[4] + S[8];
    N[0][1] = S[5] - S[7];
    N[0][2] = S[6] - S[2];
    N[0][3] = S[1] - S[3];
    N[1][0] = N[0][1];
    N[1][1] = S[0] - S[4] - S[8];
    N[1][2] = S[1] + S[3];
    N[1][3] = S[6] + S[2];
    N[2][0] = N[0][2];
    N[2][1] = N[1][2];
    N[2][2] = -S[0] + S[4] - S[8];
    N[2][3] = S[5] + S[7];
    N[3][0] = N[0][3];
    N[3][1] = N[1][3];
    N[3][2] = N[2][3];
    N[3][3] = -S[0] - S[4] + S[8];

    const double shift = static_cast<double>(count);
    double q[4] = {1.0, 1.0, 1.0, 1.0};
    for (int iter = 0; iter < 20; iter++) {
        double nq[4] = {};
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                nq[i] += N[i][j] * q[j];
            }
            nq[i] += shift * q[i];
        }
        double len = std::sqrt(nq[0] * nq[0] + nq[1] * nq[1] + nq[2] * nq[2] + nq[3] * nq[3]);
        if (len < 1e-12) break;
        for (int i = 0; i < 4; i++) q[i] = nq[i] / len;
    }

    Quat out{q[1], q[2], q[3], q[0]};
    normalizeQuat(out);
    if (out.w == 0.0 && out.x == 0.0 && out.y == 0.0 && out.z == 0.0) {
        out = {0, 0, 0, 1};
    }
    return out;
}

inline void estimateRotWahba(
    const double* raysA, const double* raysB, int count, double* outR) {
    if (count < 1) {
        for (int i = 0; i < 9; i++) outR[i] = (i % 4 == 0) ? 1.0 : 0.0;
        return;
    }
    const Quat q = estimateRotWahbaQuat(raysA, raysB, count);
    mat3FromQuat(q, outR);
}

}  // namespace robotics::panorama