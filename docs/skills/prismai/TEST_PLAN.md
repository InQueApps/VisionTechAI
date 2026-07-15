# PRISM AI — Test Plan

**Total JVM tests:** 131 (`./gradlew test`)  
**Instrumented (ARM):** `SphereStitchE2ETest` (7) + `SphereGoldenImageTest` (3) + `ExampleInstrumentedTest` (1) = **11**

Run all sphere JNI tests locally:
```bash
./gradlew connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=ab.inque.visiontechai.SphereStitchE2ETest,ab.inque.visiontechai.SphereGoldenImageTest
```

---

## Feature → test traceability

Every shipped sphere feature (Phases A–F) maps to at least one automated test. App-wide vision features are covered where logic is pure Kotlin; JNI/ML paths rely on manual QA unless noted.

### Sphere stitching (Phases A–F) — fully covered

| Feature / module | Gap | Test class | Layer |
|------------------|-----|------------|-------|
| `QuaternionMath` (SLERP, rotate) | — | `QuaternionMathTest` | JVM |
| `QuaternionMath.multiply` | S4 | `QuaternionMathMultiplyTest` | JVM |
| `SphereProjectionMath` | S1 | `SphereProjectionMathTest` | JVM |
| `FrameAlignmentInfo` metrics | — | `FrameAlignmentInfoTest` | JVM |
| `SphereAlignmentPolicy` warnings | — | `FrameAlignmentInfoTest` | JVM |
| `SphereCaptureThrottler` (legacy 5 fps) | S6 | `SphereCaptureThrottlerTest` | JVM |
| `SphereAdaptiveThrottler` | S6 | `SphereAdaptiveThrottlerTest` | JVM |
| `SphereMotionEstimator` | S6 | `SphereMotionEstimatorTest` | JVM |
| `ImuCameraClockSync` | S11 | `ImuCameraClockSyncTest` | JVM |
| `OrientationQuaternionHistory` | S11 | `OrientationQuaternionHistoryTest` | JVM |
| `SphereStitchWorker` (off-main, drop-if-busy) | S5 | `SphereStitchWorkerTest` | JVM |
| `SphereStitchWorker` hole-mask preview | S12 | `SphereStitchWorkerTest` | JVM |
| `SphereCaptureController` state machine | — | `SphereCaptureControllerTest` | JVM |
| `SphereCaptureController` 85% finish gate | S12 | `SphereCaptureControllerTest` | JVM |
| `SphereCaptureController` continuous throttle | S6 | `SphereCaptureControllerTest` | JVM |
| `SpherePreviewBinder` + stitch → ImageView | preview | `SpherePreviewIntegrationTest` | JVM |
| `SphereSessionController` photo → preview pixel | preview | `SphereSessionControllerPreviewTest` | JVM |
| `PipelineResultDispatcher` sphere HUD | S9 | `PipelineResultDispatcherSphereTest` | JVM |
| `EffectiveFovCalculator` | S3, S10 | `EffectiveFovCalculatorTest` | JVM |
| `SphereFovHelper` | S10 | `SphereFovHelperTest` | JVM |
| `SphereExtrinsicCalibration` | S4 | `SphereExtrinsicCalibrationTest` | JVM |
| `LensDistortion` / undistort | S1 | `LensDistortionTest` | JVM |
| `TileGainMap` | S7 | `TileGainMapTest` | JVM |
| `SolidAngleCoverage` + hole mask | S9, S12 | `SolidAngleCoverageTest` | JVM |
| `SphereCoverageFormatter` | S9, S12 | `SphereCoverageFormatterTest` | JVM |
| `SphereHoleOverlay` | S12 | `SphereHoleOverlayTest` | JVM |
| `SphereAlignmentFallback` | S8 | `SphereAlignmentFallbackTest` | JVM |
| `SphereXmpMetadata` (GPano export) | — | `SphereXmpMetadataTest` | JVM |
| `SphereNative` init/add/coverage | — | `SphereStitchE2ETest` | ARM |
| `SphereNative` frame info / rectilinear | S2 | `SphereStitchE2ETest` | ARM |
| `SphereNative` solid-angle + hole mask JNI | S9, S12 | `SphereStitchE2ETest` | ARM |
| Golden stitch regression | S13 | `SphereGoldenImageTest` | ARM |
| Native tile gain + seam feather | S7 | `SphereGoldenImageTest` (checksum) | ARM |

### App-wide vision — covered (pure Kotlin)

| Feature / module | Test class |
|------------------|------------|
| `VisionMode` | `VisionModeTest` |
| `VisionModeTransitions` | `VisionModeTransitionsTest` |
| `VisionOverlayHost` | `VisionOverlayHostTest` |
| `MediaCapturePolicy` | `MediaCapturePolicyTest` |
| `VisionAnalysisPipeline` | `VisionAnalysisPipelineTest` |
| `PipelineResult` | `PipelineResultTest` |
| `Yolo11OutputParser` | `Yolo11OutputParserTest` |
| `ObjectDetectionOverlay` | `ObjectDetectionOverlayTest` |
| `HdScaleMapper` | `HdScaleMapperTest` |
| `ImageUtils` | `ImageUtilsTest` |
| `ImageResampleUtils` | `ImageResampleUtilsTest` |
| `FrameProcessorAdapters` | `FrameProcessorAdaptersTest` |
| `ProcessorParams` | `ProcessorParamsTest` |
| `TranslationLanguages` | `TranslationLanguagesTest` |
| `TranslationExportFormatter` | `TranslationExportFormatterTest` |

### Not automated (documented deferrals)

| Feature | Reason | Tracking |
|---------|--------|----------|
| `SphereSessionController` enter/exit/save/MediaStore | Activity + MediaStore | G2 |
| `SphereViewerView` OpenGL | GPU surface; manual QA | — |
| `MainActivity` mode orchestration | Large activity; partial via dispatcher + preview tests | G1 |
| `CameraController` / `VisionModeController` / permissions | CameraX lifecycle | G4 |
| `OrientationTracker` sensor fusion | Hardware sensors | G4 |
| Depth / segmentation / optical flow / night vision JNI | ML golden outputs | G3 |
| Video recording (`ProcessedVideoRecorder`) | Encoder surface | G5 |
| `SavedMediaActivity` | UI gallery | G6 |
| ORB feature fallback (S8 full) | OpenCV not integrated | S8 in GAPS |
| Multiband / Laplacian seam blend (S12 v2) | Only edge feather shipped | S12 in GAPS |
| Per-device lens distortion (S1) | Always `LensDistortion.NONE` in session | S1 in GAPS |
| Seam brightness ΔL* instrumented | Needs golden assets | SPHERE_TEST_PLAN |
| Kotlin vs JNI ray parity | `SphereProjectionParityTest` planned | deferred |
| Sphere CI on preview/dispatcher paths | Path filter on `Sphere*.kt` only | G7 |

---

## Test class index (by count)

| Area | Classes | ~Tests |
|------|---------|--------|
| **Sphere** | 26 | ~62 |
| YOLO / pipeline | 4 | ~15 |
| Overlay / modes | 4 | ~12 |
| HD / image | 3 | ~10 |
| Translation | 2 | ~5 |
| Misc | 2 | ~3 |

---

## CI

| Workflow | Trigger | Command |
|----------|---------|---------|
| `android-test.yml` | all push/PR | `./gradlew test` (131 JVM) |
| `android-sphere-e2e.yml` | sphere paths | `SphereStitchE2ETest` + `SphereGoldenImageTest` on ARM emulator |

---

## Adding a new feature — checklist

1. Extract pure logic to a testable Kotlin `object` / class when possible.
2. Add a `*Test.kt` in `app/src/test/...` (Robolectric for Android types).
3. If JNI is involved, add/extend `androidTest/.../Sphere*E2ETest.kt`.
4. Update this file's traceability table and [CHANGELOG.md](CHANGELOG.md).
5. Run `./gradlew test` before PR.

Manual release checklist: [../../sphere/SPHERE_TEST_PLAN.md](../../sphere/SPHERE_TEST_PLAN.md#manual-qa-checklist).
