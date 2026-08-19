#pragma once

#include <algorithm>
#include <cmath>

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

}  // namespace robotics::panorama
