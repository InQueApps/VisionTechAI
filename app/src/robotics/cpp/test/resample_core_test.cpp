#include "robotics_test.h"

#include "../resample/resample_core.h"

namespace {

constexpr int32_t kArgb(int r, int g, int b) {
    return 0xFF000000 | (r << 16) | (g << 8) | b;
}

}  // namespace

ROBOTICS_TEST(ResampleCore_cubicWeight_atZero_isOne) {
    EXPECT_NEAR(robotics::resample::cubicWeight(0.0), 1.0, 1e-6);
    return true;
}

ROBOTICS_TEST(ResampleCore_cubicWeight_outsideSupport_isZero) {
    EXPECT_EQ(robotics::resample::cubicWeight(3.0), 0.0);
    return true;
}

ROBOTICS_TEST(ResampleCore_bicubicUpsample_2x2_redBlock) {
    const int32_t in[] = {
        kArgb(255, 0, 0), kArgb(255, 0, 0),
        kArgb(255, 0, 0), kArgb(255, 0, 0),
    };
    int32_t out[16] = {};
    robotics::resample::bicubicResample(in, 2, 2, out, 4, 4);
    // Interior pixels stay red; edges may feather toward zero-initialized black.
    EXPECT_EQ((out[5] >> 16) & 0xFF, 255);
    EXPECT_LE((out[5] >> 8) & 0xFF, 5);
    EXPECT_LE(out[5] & 0xFF, 5);
    return true;
}

ROBOTICS_TEST(ResampleCore_bicubicIdentity_sameSize) {
    const int32_t in[] = {
        kArgb(10, 20, 30), kArgb(40, 50, 60),
        kArgb(70, 80, 90), kArgb(100, 110, 120),
    };
    int32_t out[4] = {};
    robotics::resample::bicubicResample(in, 2, 2, out, 2, 2);
    for (int i = 0; i < 4; i++) {
        EXPECT_NEAR((out[i] >> 16) & 0xFF, (in[i] >> 16) & 0xFF, 2);
        EXPECT_NEAR((out[i] >> 8) & 0xFF, (in[i] >> 8) & 0xFF, 2);
        EXPECT_NEAR(out[i] & 0xFF, in[i] & 0xFF, 2);
    }
    return true;
}

ROBOTICS_TEST(ResampleCore_unsharpMask_increasesContrast) {
    int32_t pixels[] = {
        kArgb(100, 100, 100), kArgb(200, 200, 200),
        kArgb(100, 100, 100), kArgb(200, 200, 200),
    };
    robotics::resample::unsharpMask(pixels, 2, 2, 1, 0.5f);
    int bright = pixels[1] & 0xFF;
    int dark = pixels[0] & 0xFF;
    EXPECT_GT(bright, 200);
    EXPECT_LT(dark, 100);
    return true;
}

ROBOTICS_TEST(ResampleCore_unsharpMask_zeroAmount_noOp) {
    int32_t pixels[] = {kArgb(1, 2, 3), kArgb(4, 5, 6)};
    int32_t before[] = {pixels[0], pixels[1]};
    robotics::resample::unsharpMask(pixels, 2, 1, 1, 0.f);
    EXPECT_EQ(pixels[0], before[0]);
    EXPECT_EQ(pixels[1], before[1]);
    return true;
}
