#include "robotics_test.h"

#include "../panorama/sphere_math.h"

using robotics::panorama::Quat;
using robotics::panorama::normalizeQuat;
using robotics::panorama::quatAngularDistSq;
using robotics::panorama::quatFromAxisAngle;
using robotics::panorama::quatMul;
using robotics::panorama::quatRotate;

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

ROBOTICS_TEST(SphereMath_quatAngularDistSq_increasesWithRotation) {
    constexpr double kPi = 3.14159265358979323846;
    Quat base{0, 0, 0, 1};
    Quat small = quatFromAxisAngle(0, 0, 1, 2.0 * kPi / 180.0);
    Quat large = quatFromAxisAngle(0, 0, 1, 10.0 * kPi / 180.0);
    EXPECT_LT(quatAngularDistSq(base, small), quatAngularDistSq(base, large));
    return true;
}
