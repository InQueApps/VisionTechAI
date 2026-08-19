---
name: prismai
description: PRISM AI Android app — vision modes, 360° sphere stitching, testing, and gap backlog.
---

# PRISM AI agent skill

Use this skill when working on **VisionTechAI** (`ab.inque.visiontechai`).

## Quick routing

| Task | Read first |
|------|------------|
| Stitching quality, seams, ghosting | [sphere-stitching/SKILL.md](../sphere-stitching/SKILL.md) |
| SAVE / export / lifecycle bugs | [sphere-stitching/FIXES.md](../sphere-stitching/FIXES.md) |
| What's broken or missing | [GAPS.md](GAPS.md) |
| Run E2E on device/emulator | [E2E_TESTING.md](E2E_TESTING.md) |
| Regression thresholds | [TEST_PLAN.md](TEST_PLAN.md) + `SphereRefinePolicy.kt` |
| Native geometry / overlap | [docs/sphere/SPHERE_GAPS.md](../../sphere/SPHERE_GAPS.md) |

## Architecture (one paragraph)

CameraX → `VisionAnalysisPipeline` → `PipelineResultDispatcher` → mode-specific processors. **Sphere mode** (`VisionMode.SPHERE`) feeds raw bitmaps to `SphereSessionController` → `SphereCaptureController` → `SphereStitchWorker` → `SphereNative` / `sphere_jni.cpp`. Live capture stitches at **1024×512**; DONE shows preview; **SAVE** runs `runFinishGeneration` at **4096×2048** via `SphereProductionStitchPipeline.runFinishExport`.

## Non-negotiables

1. **`SphereNative` is a singleton** — never `resetSession()` / `release()` while background export is in flight.
2. **SAVE requires `State.PREVIEW`** (user tapped DONE first).
3. **Policy thresholds** live in `SphereRefinePolicy.kt` — change tests when changing gates.
4. **Production finish path** must match `SphereProductionStitchPipeline` (used by debug replay and planar E2E).
5. **Never regress R1** (`fastRefine_noRegression`) on native fast-path changes.

## Agent workflow

1. Classify change: L1 live / L2 refine / L3 export / lifecycle / UX.
2. Read relevant gap IDs in [GAPS.md](GAPS.md).
3. Implement minimal diff; mirror conventions in surrounding files.
4. Run JVM tests: `./gradlew test`
5. Sphere native changes: run targeted instrumented class on **ARM device** (see [E2E_TESTING.md](E2E_TESTING.md)).
