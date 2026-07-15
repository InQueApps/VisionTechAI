# PRISM AI — Agent Skill

On-device Android vision app (`ab.inque.prismai`). Use this skill when working on VisionTechAI / PRISM AI.

## Quick reference

| Topic | Doc |
|-------|-----|
| Architecture & gaps | [GAPS.md](GAPS.md) |
| Test matrix | [TEST_PLAN.md](TEST_PLAN.md) — **feature → test map** |
| Sphere stitching gaps | [../../sphere/SPHERE_GAPS.md](../../sphere/SPHERE_GAPS.md) |
| Sphere feature roadmap | [../../sphere/SPHERE_FEATURE_PLAN.md](../../sphere/SPHERE_FEATURE_PLAN.md) |
| Sphere test plan | [../../sphere/SPHERE_TEST_PLAN.md](../../sphere/SPHERE_TEST_PLAN.md) |
| Changelog | [CHANGELOG.md](CHANGELOG.md) |

## Key paths

```
app/src/main/java/ab/inque/visiontechai/
  MainActivity.kt
  VisionAnalysisPipeline.kt
  SphereCaptureController.kt      # sphere session + worker
  SphereHoleOverlay.kt            # red hole hints on preview
  TileGainMap.kt / SolidAngleCoverage.kt
  SphereCoverageFormatter.kt
  SphereAlignmentFallback.kt
  SpherePreviewBinder.kt          # reliable ImageView refresh after stitch
  OrientationTracker.kt           # IMU + clock sync
  controller/
    SphereSessionController.kt
    PipelineResultDispatcher.kt
app/src/main/cpp/sphere_jni.cpp
app/src/test/java/ab/inque/visiontechai/   # JVM unit (131 tests)
  SphereCaptureControllerTest.kt
  SpherePreviewIntegrationTest.kt
  controller/SphereSessionControllerPreviewTest.kt
  PipelineResultDispatcherSphereTest.kt
app/src/androidTest/.../SphereStitchE2ETest.kt
app/src/androidTest/.../SphereGoldenImageTest.kt
.github/workflows/android-sphere-e2e.yml
docs/sphere/
```

## Commands

```bash
./gradlew test                    # JVM unit tests (CI)
./gradlew connectedAndroidTest    # ARM instrumented (sphere JNI + golden)
```

## Sphere stitching (current state)

- **Phase A** ✅ testable helpers + baseline tests
- **Phase B** ✅ background worker, adaptive throttle, IMU clock sync
- **Phase C** ✅ effective FOV, extrinsic cal, undistortion, 3-DOF roll alignment
- **Phase D** ✅ tile exposure gain, solid-angle coverage, hole overlay, seam feather
- **Phase E** ✅ relaxed SSD fallback on borderline rejects (no OpenCV)
- **Phase F** ✅ `SphereGoldenImageTest` + `android-sphere-e2e.yml` ARM CI
- **Preview pipeline** ✅ `SpherePreviewBinder`; integration tests for stitch → ImageView

All sphere features (A–F) have JVM and/or ARM tests — see [TEST_PLAN.md](TEST_PLAN.md#feature--test-traceability). Remaining sphere quality gaps: S1 per-device distortion, S8 ORB, S12 multiband — see [GAPS.md](GAPS.md).

## Conventions

- Extract pure Kotlin for anything testable without JNI
- Never recycle CameraX-owned bitmaps; copy in `SphereStitchWorker`
- Sphere JNI only on `SphereStitchWorker` executor thread
- Match existing controller/overlay patterns when adding modes
