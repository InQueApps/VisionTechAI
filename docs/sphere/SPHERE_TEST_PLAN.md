# 360° Sphere Stitching — Test Plan

**Version:** 1.1 · **JVM inventory:** see [../skills/prismai/TEST_PLAN.md](../skills/prismai/TEST_PLAN.md) (131 tests)  
**Related:** [SPHERE_GAPS.md](SPHERE_GAPS.md) · [SPHERE_FEATURE_PLAN.md](SPHERE_FEATURE_PLAN.md)

This plan defines how every stitching gap (S1–S13) and feature (F-B1…F-F1) is verified across unit, integration, instrumented, performance, and manual layers.

---

## Test strategy overview

```
                    ┌─────────────────────────────────────┐
                    │         Manual QA (release)         │
                    │  real camera + IMU + visual seams   │
                    └──────────────────▲──────────────────┘
                                       │
┌──────────────┐   ┌───────────────────┴───────────────────┐   ┌──────────────┐
│  JVM unit    │   │     Instrumented (ARM device/emulator)   │   │  CI pipeline │
│  fast, no JNI│──▶│  JNI e2e + golden images + Espresso      │◀──│  test + FTL  │
└──────────────┘   └──────────────────────────────────────────┘   └──────────────┘
```

| Layer | Command | Runs on | Purpose |
|-------|---------|---------|---------|
| JVM unit | `./gradlew test` | CI (Ubuntu) | Math, policy, FOV, queue logic |
| JVM + Robolectric | `./gradlew test` | CI | Controller state without device |
| Instrumented | `./gradlew connectedAndroidTest` | ARM only | JNI, golden hashes, UI flows |
| Performance | `./gradlew connectedAndroidTest` + macro | Device | Latency, memory, reject rate |
| Manual | Checklist below | Physical device | Subjective seam quality |

---

## Gap → test mapping

| Gap | Feature | Unit tests | Instrumented | Manual | Phase |
|-----|---------|------------|--------------|--------|-------|
| S1 Lens distortion | F-C1 | `SphereProjectionMathTest` undistort | `SphereProjectionParityTest` | Straight-line scene | C |
| S2 2-DOF alignment | F-C2 | — | `SphereRollAlignmentTest` | Tilted phone seams | C |
| S3 FOV mismatch | F-C3 | `EffectiveFovCalculatorTest` | `SphereFovSessionTest` | H/L same horizon | C |
| S4 IMU extrinsic | F-C4 | `SphereExtrinsicCalibrationTest` | `SphereCalibrateE2ETest` | Horizon align | C |
| S5 Main-thread JNI | F-B1 | `SphereStitchWorkerTest` | `MainThreadGuardTest` | No jank 30s cap | B |
| S6 Fixed throttle | F-B2 | `SphereAdaptiveThrottlerTest` | `ContinuousCaptureRateTest` | Fast pan warnings | B |
| S7 Global gain | F-D1 | `TileGainMapTest` | `SeamBrightnessTest` | Exposure seam | D |
| S8 Parallax | F-E1 | — | `FeatureFallbackTest` | Indoor close objects | E |
| S9 Pixel coverage | F-D2 | `SolidAngleCoverageTest` | `HoleMaskTest` | Gap visible in UI | D |
| S10 Portrait FOV swap | F-C5 | `EffectiveFovCalculatorTest` | Portrait/landscape e2e | Orientation | C |
| S11 Timestamp sync | F-B3 | `ImuCameraClockSyncTest` | `OrientationTrackerE2ETest` | Laggy reject rate | B |
| S12 No blending | F-D3 | `FeatherWeightTest` | `MultibandGoldenTest` | High-contrast seam | D |
| S13 No golden CI | F-F1 | — | `SphereGoldenImageTest` | — | F |

---

## Current test inventory

> **Canonical list:** [../skills/prismai/TEST_PLAN.md](../skills/prismai/TEST_PLAN.md) — 131 JVM + 11 instrumented.  
> Section below is a historical Phase A+B snapshot; gap→test rows above list some planned tests not yet implemented.

### JVM unit tests — `app/src/test/java/ab/inque/visiontechai/` (snapshot)

| Class | Tests | Covers |
|-------|-------|--------|
| `QuaternionMathTest` | 3 | SLERP, normalize, axis-angle rotate |
| `SphereProjectionMathTest` | 3 | Ray, equirect pole, yaw shifts X |
| `FrameAlignmentInfoTest` | 5 | Overlap ratio, policy warnings |
| `SphereCaptureThrottlerTest` | 2 | 200 ms interval |
| `SphereAdaptiveThrottlerTest` | 5 | Pan-speed gating |
| `SphereMotionEstimatorTest` | 2 | Angular velocity |
| `ImuCameraClockSyncTest` | 3 | Camera↔sensor offset |
| `OrientationQuaternionHistoryTest` | 3 | SLERP history |
| `SphereStitchWorkerTest` | 2 | Off-main stitch, drop-if-busy |
| `SphereFovHelperTest` | 2 | Portrait H/V swap (interim) |
| `SphereXmpMetadataTest` | 1 | GPano XMP tags |
| `VisionAnalysisPipelineTest` | 1+ | SPHERE raw bitmap path |
| `MediaCapturePolicyTest` | 1+ | SPHERE capture policy |

**Run:** `./gradlew test` — **89 tests**, CI green.

### Instrumented — `app/src/androidTest/java/ab/inque/visiontechai/`

| Class | Tests | Covers |
|-------|-------|--------|
| `SphereStitchE2ETest` | 5 | Native load, init, coverage growth, frame info, rectilinear |

**Run:**

```bash
./gradlew connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=ab.inque.visiontechai.SphereStitchE2ETest
```

Uses `assumeTrue(SphereNative.isAvailable())` — skips on hosts without ARM native lib.

---

## Planned tests by phase

### Phase B — Threading & timing ✅

| New test class | Type | Cases |
|----------------|------|-------|
| `SphereStitchWorkerTest` | JVM (Robolectric) | off-main stitch, drop-if-busy |
| `SphereAdaptiveThrottlerTest` | JVM | stationary / fast / slow / busy |
| `SphereMotionEstimatorTest` | JVM | zero vel, 90°/s |
| `ImuCameraClockSyncTest` | JVM | median offset, reset |
| `OrientationQuaternionHistoryTest` | JVM | SLERP midpoint, bounds |

**Pass criteria (B):** ✅ `SphereStitchWorkerTest` asserts `ranOnMainThread == false`; dispatcher has no `SphereNative` import.

---

### Phase C — Geometry & calibration

| New test class | Type | Cases |
|----------------|------|-------|
| `EffectiveFovCalculatorTest` | JVM | sensor FOV + crop + rotation → expected H/V |
| `SphereExtrinsicCalibrationTest` | JVM | compose offset; persist round-trip |
| `SphereProjectionMathUndistortTest` | JVM | k1≠0 moves edge ray vs center |
| `SphereProjectionParityTest` | Instrumented | 100 pixels: Kotlin vs JNI hook |
| `SphereRollAlignmentTest` | Instrumented | injected roll corrected; SSD drops |
| `SphereFovSessionTest` | Instrumented | portrait/landscape same center equirect |

**Fixtures:**

- `src/test/resources/fov_cases.json` — input sensor/crop/orientation → expected FOV.
- `src/androidTest/assets/sphere/quaternions_roll_3deg.json`

**Pass criteria (C):**

- FOV calculator error < 0.5° on all JSON cases.
- Parity: max equirect delta ≤ 1 px for undistorted; ≤ 3 px with k1=0.1.

---

### Phase D — Blending & coverage

| New test class | Type | Cases |
|----------------|------|-------|
| `SolidAngleCoverageTest` | JVM | sparse weight grid → solid angle fraction |
| `TileGainMapTest` | JVM | two brightness regions → smooth gain |
| `FeatherWeightTest` | JVM | distance-to-edge weight monotonic |
| `HoleMaskTest` | Instrumented | skip 90° wedge → mask shows hole |
| `SeamBrightnessTest` | Instrumented | ΔL* in overlap < 5 after tile gain |
| `MultibandGoldenTest` | Instrumented | high-contrast seam hash within tolerance |

**Pass criteria (D):**

- Solid-angle coverage < pixel coverage when hole present (asserted in synthetic buffer test).
- Seam brightness delta < 5 (CI instrumented).

---

### Phase E — Feature fallback

| New test class | Type | Cases |
|----------------|------|-------|
| `FeatureFallbackTest` | Instrumented | IMU reject + textured overlap → accept |
| `FeatureFallbackBudgetTest` | Instrumented | fallback completes < 100 ms |

**Pass criteria (E):**

- Reject rate on `assets/sphere/indoor_close/` sequence drops ≥ 30% vs baseline.

---

### Phase F — Golden CI

| New test class | Type | Cases |
|----------------|------|-------|
| `SphereGoldenImageTest` | Instrumented | 6-frame stitch → SHA-256 @ 512×256 |
| `SphereGoldenImageTest_v2` | Instrumented | roll + distortion variants |

**Assets layout:**

```
app/src/androidTest/assets/sphere/
  frames/           # PNG 320×240
  poses.json        # quaternions per frame
  expected/
    equirect_512.sha256
    metadata.json   # tolerance, version
```

**CI workflow addition** (`.github/workflows/android-sphere-e2e.yml`):

```yaml
on:
  push:
    paths: ['app/src/main/cpp/sphere_jni.cpp', 'app/src/main/java/**/Sphere*.kt']
jobs:
  sphere-e2e:
    runs-on: ubuntu-latest  # or macos-14 + ARM emulator
    steps:
      - uses: actions/checkout@v4
      - name: Run sphere instrumented tests
        uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 34
          arch: arm64-v8a
          script: ./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ab.inque.visiontechai.SphereGoldenImageTest
```

**Baseline update process:**

1. Run test locally on reference device; copy `actual.sha256` to `expected/`.
2. PR must include `metadata.json` version bump + reviewer sign-off.

---

## Integration / controller tests (Robolectric)

| Class | Scope |
|-------|--------|
| `SphereCaptureControllerTest` | State machine IDLE→CAPTURING→PREVIEW; reset |
| `SphereSessionControllerTest` | enter/exit; mock worker |
| `PipelineResultDispatcherSphereTest` | SPHERE branch enqueues, does not JNI |

Add when F-B1 lands — use fake `SphereStitchWorker` interface.

---

## Performance & stress tests

| Scenario | Method | Target |
|----------|--------|--------|
| Worker latency | Instrumented timer around `addFrame` | p95 < 50 ms |
| Memory | 25 frames continuous | Heap Δ < 80 MB |
| Queue stall | Feed 60 fps, worker 5 fps | No OOM; drops logged |
| Coverage monotonicity | 12-frame e2e | coverage non-decreasing |
| Reject rate | 30 s continuous real pan | < 10% rejects |

---

## Manual QA checklist (release gate)

### Environment

- [ ] ARM64 device, API 30+
- [ ] Rotation vector sensor present
- [ ] Scene A: indoor — door frames, shelves (parallax)
- [ ] Scene B: outdoor — horizon, buildings (infinity)
- [ ] Tripod or slow pan discipline

### Functional

- [ ] Enter SPHERE → overlay + 0% coverage
- [ ] Photo mode: 10 frames @ ~30° → finish → viewer smooth
- [ ] Continuous: 360° in 25–35 s → no black wedge > 15°
- [ ] Save JPEG → GPano recognized in gallery app
- [ ] Portrait session start vs landscape
- [ ] Mode switch mid-capture → no crash
- [ ] Calibration reset (after F-C4) → horizon improves

### Quality scoring (1–5, lower is better)

| Criterion | Scene | Target |
|-----------|-------|--------|
| Seam visibility | B outdoor slow pan | ≤ 2 |
| Ghosting (moving objects) | A indoor | ≤ 3 |
| Color/exposure match at seam | A indoor | ≤ 2 |
| Straight lines at equator | B outdoor | ≤ 2 |
| Hole / gap presence | intentional 90° skip | visible in UI (F-D2) |

### Regression triggers

- [ ] Low light (gain > ISO 1600 equivalent)
- [ ] Ultra-wide vs standard camera if supported
- [ ] 20+ frame session without OOM

---

## Coverage matrix (target end state)

| Component | Unit | Integration | E2E | Manual | Status |
|-----------|------|-------------|-----|--------|--------|
| `QuaternionMath` | ✅ | — | — | — | Done |
| `SphereProjectionMath` | ✅ | — | parity ⬜ | — | Partial |
| `SphereAlignmentPolicy` | ✅ | — | — | — | Done |
| `SphereCaptureThrottler` | ✅ | — | — | — | Done |
| `SphereAdaptiveThrottler` | ✅ | — | — | — | Done |
| `SphereMotionEstimator` | ✅ | — | — | — | Done |
| `ImuCameraClockSync` | ✅ | — | — | — | Done |
| `OrientationQuaternionHistory` | ✅ | — | — | — | Done |
| `SphereFovHelper` | ✅ | — | ⬜ | ⬜ | Interim |
| `EffectiveFovCalculator` | ⬜ | ⬜ | ⬜ | ⬜ | Phase C |
| `SphereExtrinsicCalibration` | ⬜ | ⬜ | ⬜ | ⬜ | Phase C |
| `SphereStitchWorker` | ✅ | — | ✅ | — | Done |
| `OrientationTracker` | partial | — | — | ⬜ | Partial |
| `SphereNative` / JNI | — | — | ✅ | ✅ | Partial |
| Undistortion / 3-DOF | ⬜ | — | ⬜ | ⬜ | Phase C |
| Tile gain / multiband | ✅ | — | ⬜ | ⬜ | `TileGainMapTest` |
| Solid-angle coverage | ✅ | — | ⬜ | ⬜ | `SolidAngleCoverageTest` |
| Feature fallback | — | — | 🟡 | ⬜ | `SphereAlignmentFallbackTest` (policy only) |
| Golden regression | — | — | ✅ | — | `SphereGoldenImageTest` |
| End-to-end stitch quality | — | — | partial | ✅ | Ongoing |

Legend: ✅ done · ⬜ planned

---

## Test data & fixtures

| Asset | Purpose |
|-------|---------|
| Solid-color frames + known quats | Coverage monotonicity |
| Checkerboard PNG | SSD alignment / feature fallback |
| Gradient frame | Seam brightness tests |
| `fov_cases.json` | FOV calculator |
| 6-frame living room set | Golden hash baseline |
| Indoor close-range set | Parallax / E1 |

Store under `app/src/test/resources/` (JVM) and `app/src/androidTest/assets/sphere/` (instrumented).

---

## CI configuration summary

| Job | Trigger | Tests |
|-----|---------|-------|
| `android-test.yml` (existing) | all push/PR | `./gradlew test` (89) |
| `android-sphere-e2e.yml` (new, Phase F) | sphere paths | `SphereGoldenImageTest` on ARM |
| Manual | release tag | Full checklist + 2 devices |

---

## Exit criteria (test initiative complete)

- [ ] Every row in gap→test mapping has ≥ 1 automated test OR explicit manual-only justification
- [ ] JVM count ≥ 95 (sphere-related ≥ 25)
- [ ] Instrumented sphere suite ≥ 15 tests on ARM
- [ ] Golden hash CI on main
- [ ] Manual checklist signed per release
- [ ] [SPHERE_FEATURE_PLAN.md](SPHERE_FEATURE_PLAN.md) Definition of Done satisfied
