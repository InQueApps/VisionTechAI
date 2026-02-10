# Native segmentation (C++)

Segmentation is implemented in C++ and called from Kotlin via JNI. No ML or TensorFlow Lite is used.

## Current implementation

- **segmenter_jni.cpp**: K-means color clustering (6 clusters, 8 iterations) on RGB. Produces an ARGB overlay that is blended with the original in Kotlin.
- **JNI**: `nativeSegment(pixelsIn, pixelsOut, width, height)` — reads ARGB from `pixelsIn`, writes ARGB overlay to `pixelsOut`.

## Using OpenCV (cv2-style in C++)

To switch to OpenCV-based segmentation:

1. Add OpenCV Android SDK (e.g. from [opencv.org/android](https://opencv.org/android/)) and set `OpenCV_DIR` in `CMakeLists.txt` to the SDK’s `sdk/native/jni` path.
2. In `CMakeLists.txt`: `find_package(OpenCV REQUIRED)` and `target_link_libraries(vision_segmenter ${OpenCV_LIBS})`.
3. In `segmenter_jni.cpp`:
   - `#include <opencv2/opencv.hpp>`
   - In `segmentFrame()`: build `cv::Mat` from the pixel buffer, run e.g. `cv::cvtColor`, `cv::kmeans()` or `cv::grabCut()`, then fill the output buffer from the result `cv::Mat`.

Example sketch with OpenCV:

```cpp
#include <opencv2/opencv.hpp>
// In segmentFrame():
cv::Mat rgb(height, width, CV_8UC3);
// fill rgb from pixelsIn (skip alpha)
cv::Mat lab;
cv::cvtColor(rgb, lab, cv::COLOR_RGB2Lab);
cv::Mat samples = lab.reshape(1, width * height);
samples.convertTo(samples, CV_32F);
cv::Mat labels, centers;
cv::kmeans(samples, K_CLUSTERS, labels, ...);
// write pixelsOut from labels/centers with your colors
```

The existing JNI signature and Kotlin side stay the same; only the C++ implementation inside `segmentFrame()` needs to be replaced.
