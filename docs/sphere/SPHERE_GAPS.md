# 360° Sphere Stitching — Gaps & Feature Plan

**Product:** PRISM AI (`VisionMode.SPHERE`)  
**Stack:** CameraX → `VisionAnalysisPipeline` (raw bitmap) → `PipelineResultDispatcher` → `SphereCaptureController` → `SphereStitchWorker` → `SphereNative` / `sphere_jni.cpp` (4096×2048 equirect, IMU quaternions, rotational SSD alignment)

**Last reviewed:** 2026-07-15

---

## Problem statement

Users report poor 360° stitching: seams, ghosting, misalignment, gaps in coverage, and “wobbly” panoramas when panning during continuous capture. Phases A–F addressed threading, geometry, exposure seams, coverage UX, and CI regression — but photorealistic quality on close subjects and high-contrast seams still lags desktop stitchers.

---

## Gap status (S1–S13)

| ID | Gap | Status | Impact / notes | Key files |
|----|-----|--------|----------------|-----------|
| S1 | **Pinhole projection only** | ✅ Done | `LensDistortionStore` + per-device k1 profiles | `LensDistortionStore.kt` |
| S2 | **2-DOF alignment** | ✅ Done | Roll search ±5° in native SSD | `sphere_jni.cpp` |
| S3 | **FOV mismatch** | ✅ Done | Analysis size + sensor FOV | `EffectiveFovCalculator.kt` |
| S4 | **No camera–IMU extrinsic** | ✅ Done | Persistent offset quaternion; limited calibrate UX | `SphereExtrinsicCalibration.kt` |
| S5 | **Native `addFrame` on main thread** | ✅ Done | Single-frame queue, drop-if-busy | `SphereStitchWorker.kt` |
| S6 | **5 fps continuous throttle** | ✅ Done | Adaptive throttle from angular velocity | `SphereAdaptiveThrottler.kt` |
| S7 | **Global per-channel gain only** | ✅ Done | 8×8 tile gain + bilinear field | `TileGainMap.kt`, `sphere_jni.cpp` |
| S8 | **Parallax not handled** | 🟡 Partial | Micro-shift ±10 px + relaxed SSD; no ORB | `sphere_jni.cpp`, `SphereAlignmentFallback.kt` |
| S9 | **Coverage = weighted pixel fraction** | ✅ Done | Solid-angle grid + hole mask + dual HUD | `SolidAngleCoverage.kt`, `SphereCoverageFormatter.kt` |
| S10 | **Portrait FOV swap heuristic** | ✅ Done | Aspect-ratio calculator | `EffectiveFovCalculator.kt` |
| S11 | **Pipeline lag vs IMU timestamp** | ✅ Done | Clock sync + quaternion history | `ImuCameraClockSync.kt`, `OrientationTracker.kt` |
| S12 | **No seam blending / multiband** | 🟡 Partial | Frame feather + `nativeRefineSeams` weight-gradient pass | `sphere_jni.cpp` |
| S13 | **No golden-image regression** | ✅ Done | ARM CI + checksum test | `SphereGoldenImageTest.kt`, `android-sphere-e2e.yml` |

**Legend:** ✅ shipped · 🟡 partial / lite · ❌ open

---

## Recently resolved (not in S-table)

| Issue | Fix |
|-------|-----|
| Live preview stale after stitch | `SpherePreviewBinder` + apply on `onCoverageUpdate` and stitch complete |
| Photo mode waited for next pipeline frame | `lastRawFrame` + immediate capture in `SphereSessionController` |
| Worker dropped frames while busy | Single pending frame queue |
| Hole overlay PorterDuff SCREEN unreliable | Alpha composite in `SphereHoleOverlay` |

Tests: `SpherePreviewIntegrationTest`, `SphereSessionControllerPreviewTest`.

---

## Phased delivery status

| Phase | Gaps | Status |
|-------|------|--------|
| A — Testability | helpers, baseline e2e | ✅ |
| B — Threading & timing | S5, S6, S11 | ✅ |
| C — Geometry & calibration | S1–S4, S10 | ✅ (S1 coeffs default zero) |
| D — Blending & coverage | S7, S9, S12 | 🟡 S12 seam refine on export |
| E — Advanced alignment | S8 | 🟡 micro-shift + SSD fallback |
| F — CI & golden assets | S13 | ✅ |

> Implementation detail: [SPHERE_FEATURE_PLAN.md](SPHERE_FEATURE_PLAN.md) · Verification: [SPHERE_TEST_PLAN.md](SPHERE_TEST_PLAN.md)

---

## Success criteria

| Metric | Target | Current |
|--------|--------|---------|
| Seam visibility (subjective 1–5) | ≤ 2 on tripod slow pan | Manual QA only |
| Frame reject rate (continuous) | < 10% in normal use | Not instrumented |
| `addFrame` p95 latency | < 50 ms off main thread | Not instrumented |
| Coverage vs visual holes | Holes visible when solid angle low | ✅ hole overlay + 85% gate |
| Unit test count (sphere-related) | ≥ 15 JVM + 5 instrumented | ~60 JVM + 10 ARM |
| CI | `./gradlew test` green; sphere e2e on ARM | ✅ JVM 131; ARM on path-filtered PRs |

---

## Architecture reference

```
CameraX ImageAnalysis
  → VisionAnalysisPipeline (SPHERE: raw Bitmap)
  → PipelineResultDispatcher (lastRawFrame, sphere HUD)
  → SphereSessionController / SphereCaptureController
       → OrientationTracker.getQuaternion(At) + extrinsic
       → SphereStitchWorker (executor)
            → SphereNative.addFrame (JNI)
       → SpherePreviewBinder → spherePreviewImage
  → finishCapture → GPano XMP → MediaStore
  → SphereViewerView (rectilinear preview)
```

Native engine (`sphere_jni.cpp` v3): sparse overlap samples → global + tile gain → time-budgeted rotational SSD (yaw/pitch/roll) → splat with mismatch rejection, edge feather, weight cap → pixel + solid-angle coverage + hole mask.

---

## Out of scope (for now)

- Body pose / Asense
- SpaceMap3D
- Full PTGui-class offline stitcher on device
- OpenCV in base APK (optional module for S8 full)
