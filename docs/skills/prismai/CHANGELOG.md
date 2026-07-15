# PRISM AI Changelog (skills)

## 2026-07-15 — Sphere capture regression fix (R1–R6)

### Fixed
- Restored immediate "Frame added to sphere" on tap
- `SphereAlignmentPolicy` warns on rejected frame 1
- `lastRawFrameTimestampNanos` passed to `captureFrame`
- Removed `pendingPhotoCaptureToast`, `markPendingPhotoCapture`, `onFrameAccepted`
- Documented regression analysis in `GAPS.md`

## 2026-07-15 — Critical / high / medium gaps

### Sphere quality
- **S1** `LensDistortionStore` — per-device profiles + prefs; wired in `SphereSessionController`
- **S8** Native micro-shift search (±10 px) for parallax lite
- **S12** `nativeRefineSeams` — weight-gradient blend on finish/save

### App architecture & tests
- `SphereUiCoordinator`, `ContactEmailLauncher`, `CameraFovCalculator`, `PermissionDenialMessage`
- `NativeModuleAvailability` + `isAvailable()` on vision JNI modules
- Espresso `MainActivityModeSwitchTest`; JVM tests for G4/G6
- Widened `android-sphere-e2e.yml` path filters

### Tests
- JVM: 131 → **146** passing

## 2026-07-15 — Gap reanalysis + preview pipeline

### Docs
- Rewrote [GAPS.md](GAPS.md) — 131 JVM tests; new G4–G7; S1/S8/S12 partial status
- Synced [SPHERE_GAPS.md](../../sphere/SPHERE_GAPS.md) with Phases A–F reality
- [TEST_PLAN.md](TEST_PLAN.md) — preview integration tests, expanded deferrals

### Code (preview fix, prior)
- `SpherePreviewBinder`, `SpherePreviewIntegrationTest`, `SphereSessionControllerPreviewTest`
- Contact Us → `inqueapps@gmail.com`

### Tests
- JVM: 125 → **131** passing

## 2026-07-14 — Test coverage audit (sphere + integration)

### Added
- `SphereCaptureControllerTest` — state machine, dual coverage, 85% finish gate, continuous throttle
- `PipelineResultDispatcherSphereTest` — sphere HUD + capture-next-frame wiring
- `FrameAlignmentInfoTest` — low-overlap + clean-frame policy cases
- `SphereStitchWorkerTest` — hole-mask preview compositing
- `SphereStitchE2ETest` — solid-angle coverage + hole mask JNI cases
- Full **feature → test traceability** table in `TEST_PLAN.md`

### Tests
- JVM: 112 → **125** passing
- Instrumented sphere: 8 → **10** cases

## 2026-07-14 — Sphere Phases D–F

### Added
- `TileGainMap` — 8×8 per-tile RGB gain (Kotlin mirror of native)
- `SolidAngleCoverage` — 32×16 grid fraction + hole mask ARGB
- `SphereHoleOverlay` — composite red hints on live preview
- `SphereCoverageFormatter` — dual area/sphere HUD; 85% finish gate
- `SphereAlignmentFallback` — relaxed SSD policy (1.35× threshold, 80+ overlap)
- JNI: tile gain splat, seam feather, `nativeGetSolidAngleCoverage`, `nativeGetHoleMask`
- `SphereGoldenImageTest` — synthetic stitch checksum + coverage/mask e2e
- `.github/workflows/android-sphere-e2e.yml` — ARM emulator sphere tests
- Unit tests: `TileGainMapTest`, `SolidAngleCoverageTest`, `SphereCoverageFormatterTest`, `SphereAlignmentFallbackTest`, `SphereHoleOverlayTest`

### Changed
- `SphereStitchWorker` — solid-angle coverage + hole mask on preview
- `SphereCaptureController` — `lastSolidAngleCoverage`; finish blocked < 85%
- `PipelineResultDispatcher` / `SphereSessionController` — dual coverage text

### Tests
- JVM: 100 → **112** passing

## 2026-07-14 — Sphere Phase C

### Added
- `EffectiveFovCalculator` — sensor FOV + analysis size + rotation → frame FOV
- `SphereExtrinsicCalibration` — persistent IMU offset quaternion (`SharedPreferences`)
- `LensDistortion` / `LensDistortionMath` — Brown–Conrady undistort in Kotlin + JNI
- `QuaternionMath.multiply` — Hamilton product for extrinsic apply
- `FrameAlignmentInfo.rollOffsetDeg` — native roll alignment metric
- Unit tests: `EffectiveFovCalculatorTest`, `SphereExtrinsicCalibrationTest`, `LensDistortionTest`, `QuaternionMathMultiplyTest`

### Changed
- `sphere_jni.cpp` — distortion coeffs on init; `pixelToRay` helper; roll in SSD alignment
- `SphereNative.init` — accepts `LensDistortion` (k1, k2, p1, p2)
- `SphereSessionController` — analysis dimensions + display rotation for FOV; extrinsic apply
- `VisionState` — tracks `lastAnalysisWidth/Height`
- `PipelineResultDispatcher` — updates analysis dimensions each frame

### Tests
- JVM: 89 → **100** passing

## 2026-07-14 — Sphere Phase B

### Added
- `SphereStitchWorker`, `SphereAdaptiveThrottler`, `ImuCameraClockSync`, etc.
- JVM: 74 → 89 tests

## 2026-07-14 — Sphere Phase A

- Extracted sphere math helpers; `SphereStitchE2ETest`; 57 → 74 JVM tests

## 2026-07 — Sprint 1 / Play / CI

- Controllers extracted; 16 KB page support; GitHub Actions `./gradlew test`
