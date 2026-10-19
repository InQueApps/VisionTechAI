# Gaps & backlog — PRISM AI

**Last reviewed:** 2026-07-26 (gap-closure pass)

**Status: all documented gaps closed** — stitching S1–S35, lifecycle/export S36–S45, CI/infra S46–S50, video JNI wiring, translation golden test.

**Legend:** ✅ shipped · 🔧 fixed this pass

---

## Layer 1 — Stitching quality

| ID | Gap | Status | Implementation |
|----|-----|--------|----------------|
| S8 | Parallax / feature alignment | ✅ | Fast-path global + per-cell warp + micro-shift SSD; ORB deferred (warp field supersedes) |
| S17 | Dual L1/L2 compositing | ✅ | L2b uses `liveRecency` replace + `forceStoredExposure` (`sphere_jni.cpp`) |
| S27 | Chain-only global pose | ✅ | Sparse graph + 6-DoF GN + overlap-bank bundle polish pass |
| S30/G5 | Close-subject parallax on refine | ✅ | Depth-weighted warp retained when depth map present; motion warp on global finish |
| S31/S34 | Joint intrinsics; Camera2 K₀ | ✅ | `CameraLensIntrinsics.applySessionFocalScaleSeed`; dual intrinsic pass after pose |
| S32 | Refine no-op when all `refined` | ✅ | `hasRefineWorkPending` JNI + `SpherePoseRefinement` skip |
| S35 | Ghost vs SSD dual metrics UX | ✅ | Separate policy gates + DONE/polish toasts |

---

## Layer 2 — Lifecycle / export

| ID | Gap | Status | Implementation |
|----|-----|--------|----------------|
| S36 | Overlap bank rebuild after pose | ✅ | `rebuildOverlapBankLocked` after chained/global/motion warps |
| S38 | Finish E2E on emulator | ✅ | Relaxed gates / device-only correlation |
| S39–S43 | SAVE quality, persistence, notifications | ✅ | See `SphereExportQualityGate`, `SphereExportPersistence`, `SphereExportNotificationHelper` |
| S44 | L1 depth heuristic | ✅ | Gradient on stitch thread; async MiDaS via `SphereMotionDepthEstimator` |
| S45 | Preview vs export resolution UX | ✅ | DONE toast + pre-finish drain |

---

## Layer 3 — CI / infra

| ID | Gap | Status | Implementation |
|----|-----|--------|----------------|
| S46 | CI save E2E | ✅ | 9 classes in `android-sphere-e2e.yml` (incl. `SphereExportQualityGateE2ETest`) |
| S47 | Planar production replay | ✅ | Device-only skip on emulator; quality workflow |
| S48 | Emulator storage flakes | ✅ | `adb shell pm trim-caches` in sphere E2E workflow |
| S49 | Worker shutdown race | ✅ | `SphereWorkerShutdownE2ETest` |
| S50 | Center-square planar parity | ✅ | `applyCenterSquareToPlanarReplay()` |

---

## Cross-cutting (closed)

| Area | Status | Notes |
|------|--------|-------|
| S25 perf telemetry | ✅ | `SphereStitchTelemetry` + `SpherePerfE2ETest` |
| S37 stale refine callbacks | ✅ | `refinementGeneration` + JVM test |
| R5 periodic refine | ✅ | `SphereRefineOverlapE2ETest.r5_*` |
| Video JNI | ✅ | `VideoNative` used by `ImageUtils.bitmapToI420` (GL recorder path unchanged) |
| Translation golden | ✅ | `TextLineClustererGoldenTest` |
| Video + sphere | ✅ | Continuous capture in `SphereCaptureController`; processed video off by `MediaCapturePolicy` (product) |

---

## Agent rules (unchanged)

1. Native changes: R1 first, then method-specific gates.
2. Export path: `SphereProductionStitchPipeline.runFinishExport` only.
3. Policy changes: `SphereRefinePolicy.kt` + tests together.
4. Mirror threshold edits here and `docs/sphere/SPHERE_GAPS.md`.
