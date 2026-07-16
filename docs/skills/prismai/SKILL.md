# PRISM AI — Agent Skill

On-device Android vision app (`ab.inque.visiontechai`). Use this skill when working on VisionTechAI / PRISM AI.

## Quick reference

| Topic | Doc |
|-------|-----|
| Architecture & gaps | [GAPS.md](GAPS.md) |
| Test matrix | [TEST_PLAN.md](TEST_PLAN.md) — **feature → test map** |
| Sphere stitching gaps | [../../sphere/SPHERE_GAPS.md](../../sphere/SPHERE_GAPS.md) |
| Sphere feature roadmap | [../../sphere/SPHERE_FEATURE_PLAN.md](../../sphere/SPHERE_FEATURE_PLAN.md) |
| Sphere test plan | [../../sphere/SPHERE_TEST_PLAN.md](../../sphere/SPHERE_TEST_PLAN.md) |
| **Fast stitch perf + two-phase** | [../../sphere/SPHERE_FAST_STITCH_PLAN.md](../../sphere/SPHERE_FAST_STITCH_PLAN.md) |
| **Two-phase perf & test gates** | [../../sphere/SPHERE_TWO_PHASE_PERF_AND_TEST.md](../../sphere/SPHERE_TWO_PHASE_PERF_AND_TEST.md) |
| E2E runbook | [E2E_TESTING.md](E2E_TESTING.md) |
| Changelog | [CHANGELOG.md](CHANGELOG.md) |

## Key paths

```
app/src/main/java/ab/inque/visiontechai/
  SphereCaptureController.kt      # sphere session + worker
  SphereStitchWorker.kt           # two-phase: fast splat + debounced refine + finish
  SphereStitchOperations.kt       # JNI interface (addFrameFast, refinePending)
  SphereCoverageFormatter.kt      # HUD only — no finish gate
  controller/SphereSessionController.kt
app/src/main/cpp/sphere_jni.cpp   # processFrameLocked, refinePending, seams
app/src/androidTest/.../
  SphereStitchE2ETest.kt          # JNI correctness
  SpherePerfE2ETest.kt            # latency budgets
  SphereArtifactE2ETest.kt        # black-line artifacts
  SphereMuddinessE2ETest.kt       # sharpness / mud
  SphereRoundTripE2ETest.kt       # stitch → rectilinear → compare source
  SphereImageMetrics.kt           # Laplacian, contrast, hue
  VisionWorkflowE2ETest.kt        # UI + camera workflow
scripts/test.ps1
docs/sphere/
```

## Commands

```bash
./gradlew test                    # JVM unit tests (CI)
./gradlew connectedAndroidTest    # ARM instrumented

# PowerShell — see E2E_TESTING.md
.\scripts\test.ps1 -Instrumented -Suite native
$env:ANDROID_SERIAL="emulator-5556"   # if Gradle targets wrong device
```

## Sphere stitching (current state)

### Two-phase pipeline
1. **Fast path** (`nativeAddFrameFast`): immediate splat with IMU quaternion; **20% overlap gate**; stride-1 splat; stores frames for refine.
2. **Background refine** (`nativeRefinePending`): **900 ms idle debounce only**; rebuild from stored frames (`STORE_MAX_DIM = 1280`).
3. **Finish** (`runFinishGeneration`): refine + full equirect **off main thread**; **no `refineSeams` on save**.

### Quality lessons (2026-07-15)
| Issue | Cause | Fix |
|-------|-------|-----|
| Black lines | Stride-2 splat; zero-weight bilinear | Stride-1; weight-aware sampling; gap fill |
| Muddy output | 384px refine; radius-12 seams; 25% feather | 1280px store; light seams; 10% feather; no seams on finish |
| Slow taps | refine + export on main thread | `refineExecutor`; debounce-only refine |
| Can't finish early | 85% coverage gate | Gate removed |

### Quaternion conventions (tests)
- Camera forward = **-Z** device; rectilinear default = **+Y** world.
- Z-axis spin alone does **not** aim camera at new sphere regions — use X/Y pitch/yaw.
- See `SphereRoundTripE2ETest.quatToAzEl` and [E2E_TESTING.md](E2E_TESTING.md).

### Phases A–F
Historical phases (helpers, worker, FOV, tile gain, golden CI) remain valid. See [TEST_PLAN.md](TEST_PLAN.md).

## Conventions

- Extract pure Kotlin for anything testable without JNI
- Never recycle CameraX-owned bitmaps; copy in `SphereStitchWorker`
- Sphere JNI on worker executor threads only
- Synthetic ARM tests prove JNI math; live camera → `VisionWorkflowE2ETest`
- Match existing controller/overlay patterns when adding modes
