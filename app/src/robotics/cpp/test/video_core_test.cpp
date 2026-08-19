#include "robotics_test.h"

#include <vector>

#include "../video/video_core.h"

namespace {

constexpr int32_t kArgb(int r, int g, int b) {
    return 0xFF000000 | (r << 16) | (g << 8) | b;
}

}  // namespace

ROBOTICS_TEST(VideoCore_argbToI420_blackPixel) {
    const int w = 4, h = 4;
    std::vector<int32_t> argb(w * h, kArgb(0, 0, 0));
    std::vector<uint8_t> i420(static_cast<size_t>(w * h * 3 / 2));
    robotics::video::argbToI420(argb.data(), w, h, i420.data());
    EXPECT_EQ(i420[0], 0);
    return true;
}

ROBOTICS_TEST(VideoCore_argbToI420_whitePixel) {
    const int w = 2, h = 2;
    std::vector<int32_t> argb(w * h, kArgb(255, 255, 255));
    std::vector<uint8_t> i420(static_cast<size_t>(w * h * 3 / 2));
    robotics::video::argbToI420(argb.data(), w, h, i420.data());
    for (int i = 0; i < w * h; i++) {
        EXPECT_GE(i420[i], 210);
    }
    return true;
}

ROBOTICS_TEST(VideoCore_argbToI420_outputSize) {
    const int w = 8, h = 8;
    std::vector<int32_t> argb(w * h, kArgb(200, 100, 50));
    std::vector<uint8_t> i420(static_cast<size_t>(w * h * 3 / 2), 0);
    robotics::video::argbToI420(argb.data(), w, h, i420.data());
    const int ySize = w * h;
    const int uvPlane = (w / 2) * (h / 2);
    EXPECT_GT(i420[0], 0);
    EXPECT_GT(i420[ySize + uvPlane], 0);
    return true;
}
