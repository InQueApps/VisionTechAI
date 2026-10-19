#include "robotics_test.h"

#include "../panorama/sphere_math.h"

using robotics::panorama::Quat;
using robotics::panorama::normalizeQuat;
using robotics::panorama::quatAngularDistSq;
using robotics::panorama::quatFromAxisAngle;
using robotics::panorama::quatMul;
using robotics::panorama::quatRotate;
using robotics::panorama::mat3FromQuat;
using robotics::panorama::mat3RotateVec;
using robotics::panorama::mat3Transpose;
using robotics::panorama::mat3AngularDistRad;
using robotics::panorama::estimateRotWahbaQuat;
using robotics::panorama::estimateRotWahba;

ROBOTICS_TEST(SphereMath_quatRotate_identity) {
    Quat id{0, 0, 0, 1};
    double ox, oy, oz;
    quatRotate(id, 1, 0, 0, ox, oy, oz);
    EXPECT_NEAR(ox, 1.0, 1e-9);
    EXPECT_NEAR(oy, 0.0, 1e-9);
    EXPECT_NEAR(oz, 0.0, 1e-9);
    return true;
}

ROBOTICS_TEST(SphereMath_quatRotate_90degZ) {
    constexpr double kPi = 3.14159265358979323846;
    Quat q = quatFromAxisAngle(0, 0, 1, kPi / 2.0);
    double ox, oy, oz;
    quatRotate(q, 1, 0, 0, ox, oy, oz);
    EXPECT_NEAR(ox, 0.0, 1e-6);
    EXPECT_NEAR(oy, 1.0, 1e-6);
    return true;
}

ROBOTICS_TEST(SphereMath_quatMul_identity) {
    Quat id{0, 0, 0, 1};
    Quat a{0.1, 0.2, 0.3, 0.9};
    normalizeQuat(a);
    Quat r = quatMul(id, a);
    EXPECT_NEAR(r.x, a.x, 1e-6);
    EXPECT_NEAR(r.w, a.w, 1e-6);
    return true;
}

ROBOTICS_TEST(SphereMath_quatAngularDistSq_identicalZero) {
    Quat q{0.1, 0.2, 0.3, 0.9};
    normalizeQuat(q);
    EXPECT_NEAR(quatAngularDistSq(q, q), 0.0, 1e-12);
    return true;
}

ROBOTICS_TEST(SphereMath_mat3FromQuat_matches_quatRotate_90degZ) {
    constexpr double kPi = 3.14159265358979323846;
    Quat q = quatFromAxisAngle(0, 0, 1, kPi / 2.0);
    double m[9];
    mat3FromQuat(q, m);
    double ox, oy, oz;
    mat3RotateVec(m, 1.0, 0.0, 0.0, ox, oy, oz);
    EXPECT_NEAR(ox, 0.0, 1e-6);
    EXPECT_NEAR(oy, 1.0, 1e-6);
    EXPECT_NEAR(oz, 0.0, 1e-6);
    quatRotate(q, 1, 0, 0, ox, oy, oz);
    EXPECT_NEAR(ox, 0.0, 1e-6);
    EXPECT_NEAR(oy, 1.0, 1e-6);
    EXPECT_NEAR(oz, 0.0, 1e-6);
    return true;
}

ROBOTICS_TEST(SphereMath_estimateRotWahba_maps_B_to_A) {
    constexpr double kPi = 3.14159265358979323846;
    constexpr double kDeg = kPi / 180.0;

    Quat qTrue = quatFromAxisAngle(0.0, 0.0, 1.0, 20.0 * kDeg);
    double Rtrue[9];
    mat3FromQuat(qTrue, Rtrue);

    const double dirsB[][3] = {
        {1.0, 0.0, 0.0},
        {0.0, 1.0, 0.0},
        {0.0, 0.0, 1.0},
        {0.6, 0.5, 0.2},
        {-0.3, 0.7, 0.4},
        {0.2, -0.4, 0.8},
        {0.9, 0.1, -0.2},
        {-0.5, -0.5, 0.6},
    };
    const int count = static_cast<int>(sizeof(dirsB) / sizeof(dirsB[0]));

    std::vector<double> raysB(count * 3);
    std::vector<double> raysA(count * 3);
    for (int i = 0; i < count; i++) {
        const double* b = dirsB[i];
        double len = std::sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
        double bx = b[0] / len, by = b[1] / len, bz = b[2] / len;
        double ax, ay, az;
        mat3RotateVec(Rtrue, bx, by, bz, ax, ay, az);
        raysB[i * 3] = bx;
        raysB[i * 3 + 1] = by;
        raysB[i * 3 + 2] = bz;
        raysA[i * 3] = ax;
        raysA[i * 3 + 1] = ay;
        raysA[i * 3 + 2] = az;
    }

    Quat qEst = estimateRotWahbaQuat(raysA.data(), raysB.data(), count);
    double Rest[9];
    mat3FromQuat(qEst, Rest);

    EXPECT_LT(mat3AngularDistRad(Rest, Rtrue), 0.01 * kDeg);

    double Rtrans[9];
    mat3Transpose(Rtrue, Rtrans);
    EXPECT_GT(mat3AngularDistRad(Rest, Rtrans), 1.0 * kDeg);
    return true;
}

ROBOTICS_TEST(SphereMath_quatAngularDistSq_increasesWithRotation) {
    constexpr double kPi = 3.14159265358979323846;
    Quat base{0, 0, 0, 1};
    Quat small = quatFromAxisAngle(0, 0, 1, 2.0 * kPi / 180.0);
    Quat large = quatFromAxisAngle(0, 0, 1, 10.0 * kPi / 180.0);
    EXPECT_LT(quatAngularDistSq(base, small), quatAngularDistSq(base, large));
    return true;
}
