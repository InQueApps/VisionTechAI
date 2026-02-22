# Native C++ Modules (JNI)

All image-processing and computer-vision work that benefits from native speed is
implemented in C++ and called from Kotlin through JNI. Each `.cpp` file compiles
into its own shared library (see `CMakeLists.txt`).

---

## Files at a glance

| Library | Source | Purpose |
|---------|--------|---------|
| `vision_segmenter` | `segmenter_jni.cpp` | SLIC-style color segmentation with temporal tracking |
| `vision_nightvision` | `nightvision_jni.cpp` | Adaptive low-light image enhancement |
| `vision_depth` | `depth_jni.cpp` | Post-process TFLite depth maps → greyscale ARGB |
| `vision_translator` | `translation_jni.cpp` | Cluster OCR word boxes into lines for translation |
| `vision_video` | `video_jni.cpp` | ARGB → I420 color-space conversion for video encoding |
| `vision_resample` | `resample_jni.cpp` | Bicubic (Catmull-Rom) resampling and unsharp mask |
| `vision_accumulator` | `accumulator_jni.cpp` | Multi-frame exposure stacking with alignment |
| `vision_edge` | `edge_jni.cpp` | Sobel-based edge detection with adaptive threshold |
| `vision_opticalflow` | `opticalflow_jni.cpp` | Dense Lucas-Kanade optical flow with HSV overlay |
| `vision_sphere` | `sphere_jni.cpp` | 360° panorama stitching and rectilinear viewer |

---

## segmenter_jni.cpp

SLIC-like 5-D K-means (R, G, B, x, y) with K-means++ initialisation.
Cluster centres are tracked across frames so segment colours stay consistent.
Post-processing includes a label median filter and small-region merging.

**JNI:** `nativeSegment`, `nativeReset`

## nightvision_jni.cpp

Single-pass adaptive enhancement: computes global luminance stats, applies
adaptive gamma correction, brightness boost, contrast stretch, and colour
correction. No ML models involved.

**JNI:** `nativeEnhance`

## depth_jni.cpp

Takes raw TFLite depth-model output, normalises min→max, and renders it to a
greyscale ARGB buffer for display. Handles both row-major and column-major
tensor layouts.

**JNI:** `depthRenderToGreyscale`

## translation_jni.cpp

Groups word bounding boxes (from ML Kit text recognition) into logical lines by
sorting vertically, then merging boxes whose vertical centres are within 0.4×
average height and that are close horizontally.

**JNI:** `clusterWordsIntoLines`

## video_jni.cpp

Converts ARGB pixel buffers to I420 (YCbCr 4:2:0) using BT.601 coefficients,
ready for hardware video encoding.

**JNI:** `argbToI420`

## resample_jni.cpp

Bicubic image resampling using the Catmull-Rom kernel (proper negative-lobe
handling). Also provides a separable unsharp-mask sharpening pass.

**JNI:** `bicubicResample`, `unsharpMask`

## accumulator_jni.cpp

Multi-frame stacking for long-exposure effects. Uses a 3-level SSD pyramid for
translational alignment with parabolic sub-pixel refinement. Includes bilinear
blending with per-pixel motion rejection and a final unsharp-mask pass.

**JNI:** `nativeAccumulate`, `nativeClear`, `nativeGetFrameCount`

## edge_jni.cpp

3×3 Gaussian blur → Sobel X/Y gradient magnitude → adaptive threshold →
morphological cleanup (isolated-pixel removal + thinning).

**JNI:** `nativeDetectEdges`

## opticalflow_jni.cpp

Dense Lucas-Kanade optical flow on bilinear-downscaled greyscale frames.
Rendered as an HSV colour wheel overlay (hue = direction, brightness =
magnitude) with configurable sensitivity.

**JNI:** `nativeProcessOpticalFlow`, `nativeClear`

## sphere_jni.cpp

Builds a 4096×2048 equirectangular panorama from camera frames. Each frame is
projected using the device IMU quaternion, then refined with a coarse-to-fine
SSD overlap search (±80 px ≈ ±7°). Includes per-frame gain compensation,
cosine edge feathering, gap-filling, and a rectilinear viewer for interactive
360° browsing.

**JNI:** `nativeInit`, `nativeAddFrame`, `nativeGetEquirect`,
`nativeGetRectilinear`, `nativeGetCoverage`, `nativeGetLastFrameInfo`,
`nativeLoadEquirect`, `nativeReset`, `nativeRelease`

---

## Build

All libraries are built by `CMakeLists.txt` with `-O2 -Wall -Wextra`.
Arm64 and x86_64 targets include 16 KB page-size alignment for Android 15+
compatibility. Only `liblog` (and `libm` for sphere) are linked; no external
native dependencies are required.
