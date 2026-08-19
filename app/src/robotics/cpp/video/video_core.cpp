#include "video_core.h"

namespace robotics::video {

void argbToI420(const int32_t* argb, int width, int height, uint8_t* outI420) {
    const int ySize = width * height;
    const int uvSize = (width / 2) * (height / 2);
    int yIdx = 0;
    for (int row = 0; row < height; row++) {
        for (int col = 0; col < width; col++) {
            uint32_t p = static_cast<uint32_t>(argb[row * width + col]);
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            int y = ((66 * r + 129 * g + 25 * b + 128) >> 8);
            if (y < 0) y = 0;
            if (y > 255) y = 255;
            outI420[yIdx++] = static_cast<uint8_t>(y);
        }
    }
    for (int row = 0; row < height; row += 2) {
        for (int col = 0; col < width; col += 2) {
            uint32_t p = static_cast<uint32_t>(argb[row * width + col]);
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8);
            int v = ((112 * r - 94 * g - 18 * b + 128) >> 8);
            if (u < 0) u = 0;
            if (u > 255) u = 255;
            if (v < 0) v = 0;
            if (v > 255) v = 255;
            int uvIdx = (row / 2) * (width / 2) + (col / 2);
            outI420[ySize + uvIdx] = static_cast<uint8_t>(u);
            outI420[ySize + uvSize + uvIdx] = static_cast<uint8_t>(v);
        }
    }
}

}  // namespace robotics::video
