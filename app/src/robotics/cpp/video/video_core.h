#pragma once

#include <cstdint>

namespace robotics::video {

/** Convert ARGB pixels to I420 (outI420 size = width*height*3/2). */
void argbToI420(const int32_t* argb, int width, int height, uint8_t* outI420);

}  // namespace robotics::video
