#pragma once

#include <cstdint>

namespace robotics::resample {

double cubicWeight(double x);

void bicubicResample(
    const int32_t* in, int inW, int inH,
    int32_t* out, int outW, int outH);

void unsharpMask(int32_t* pixels, int w, int h, int radius, float amount);

}  // namespace robotics::resample
