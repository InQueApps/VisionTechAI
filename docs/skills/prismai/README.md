# PRISM AI — architecture & operations

**App:** `ab.inque.visiontechai` · **Package root:** `app/src/main/java/ab/inque/visiontechai/`

## Vision modes

| Mode | Processor | Notes |
|------|-----------|-------|
| `SPHERE` | `SphereSessionController` | 360° capture; not a frame processor |
| `NIGHT_VISION` | `NightVisionFrameProcessor` | Slider in UI |
| `EDGE_DETECTION` | `EdgeDetectionFrameProcessor` | |
| `HD` | `HDFrameProcessor` | Cache cleared on mode exit |
| `DEPTH_FIELD` | `DepthFieldFrameProcessor` | MiDaS |
| `TEXT_TRANSLATION` | `TextTranslationFrameProcessor` | |
| Others | Optical flow, segmentation, color, object detection | |

Mode transitions: `VisionModeController` + `VisionModeTransitions` (sphere cleanup on leave).

## Sphere pipeline (production)

```
CameraX → VisionAnalysisPipeline (raw bitmap when SPHERE)
       → PipelineResultDispatcher (only when CAPTURING)
       → SphereCaptureController.addFrameFromPipeline()
       → SphereStitchWorker.submit()  [1024×512, off main thread]
       → SphereNative.addFrameFast / refinePending
```

### Resolution tiers

| Stage | Resolution | When |
|-------|------------|------|
| L1 live | 1024×512 | During capture |
| DONE preview | 1024×512 | `finishCapture()` — fast splat + pre-finish drain scheduled |
| SAVE export | 4096×2048 | `startBackgroundFullExport()` → `runFinishGeneration` |

### Refine layers

| Layer | Trigger | Native work |
|-------|---------|-------------|
| L1 | Every accepted frame | Fast rotational align, weighted accumulate, `liveRecency` |
| L2a | Idle / periodic (every 6 frames policy) | IMU-parity replay at capture res |
| L2b | Pre-finish drain, DONE fallback, SAVE | `SphereProductionStitchPipeline.runFinishExport` |
| L3 | Background after SAVE | Intrinsics + light seams (`runBackgroundGlobalPose` off by default) |

**Single source of truth for export:** `SphereProductionStitchPipeline.kt`

### Key classes

| Class | Role |
|-------|------|
| `SphereSessionController` | Activity lifecycle, SAVE to MediaStore, debug replay |
| `SphereCaptureController` | State machine: IDLE → CAPTURING → PREVIEW |
| `SphereStitchWorker` | Thread pools, refine scheduling, finish generation |
| `SphereRefinePolicy` | All thresholds and feature flags |
| `SphereNative` / `sphere_jni.cpp` | Native stitch, pose refine, seams |
| `SphereUiCoordinator` | Button bindings |

### SAVE workflow (post-2026-07 fix)

1. User taps **DONE** → `finishCapture()` → `State.PREVIEW`, viewer opens.
2. User taps **SAVE** (viewer overlay only) → `saveEquirectangular()`.
3. Guards: `PREVIEW`, not already exporting, frames present.
4. `startBackgroundFullExport()` queued **before** `dismissSphereUiKeepSession()`.
5. UI dismisses (`VisionMode.NONE`) but **controller + native session retained**.
6. On complete: JPEG + GPano XMP to MediaStore; `release()` when idle.

**Never** call `resetSession()` on mode switch during background export.

## Debug tooling

- **Record:** DEBUG build, `sphereDebugButton` — saves frames + IMU to app storage.
- **Replay:** Long-press debug → `SphereDebugSessionReplayer` → quality dialog.
- **Planar roundtrip:** `scripts/stitch_planar_roundtrip.py --device <serial>`

## Planar roundtrip (offline parity)

Python script extracts rectilinear views from equirect fixtures, pushes to device, runs native stitch. Use to compare live vs roundtrip without CameraX.

## Related docs

- [GAPS.md](GAPS.md) — backlog
- [E2E_TESTING.md](E2E_TESTING.md) — how to run tests
- [TEST_PLAN.md](TEST_PLAN.md) — regression matrix
- [docs/sphere/](../../sphere/) — native plans, S-tables
