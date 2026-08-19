# Changelog — agent skills

## 2026-07-26 (gap closure)

- **Native:** S17 L2b liveRecency + stored exposure; S27 bundle polish; S30 depth warp retain; S32 `hasRefineWorkPending`; S34 dual intrinsic; S36 overlap bank rebuild hooks.
- **Kotlin:** Camera2 focal scale seed; depth on all capture frames; `VideoNative` in `ImageUtils.bitmapToI420`.
- **Tests:** `TextLineClustererGoldenTest`; planar replay emulator skip (S47); CI trim-caches (S48).
- **GAPS.md:** All items marked closed.

## 2026-07-26

- **SphereSessionSaveE2ETest:** Fixed main-thread deadlock (stitch off `onActivity` + `waitForIdleSync`); passes on device.
- **S35:** Toast when background alignment polish updates the viewer after DONE.
- **CI:** Added `SphereSessionSaveE2ETest` to sphere E2E workflow (4096 test skips on emulator).

## 2026-07-25

- Created in-repo skill docs under `docs/skills/` (canonical for this workspace).
- **GAPS.md:** Full three-layer audit — stitching S1–S35 summary, lifecycle S36–S45, CI S46–S50.
- Documented SAVE/export lifecycle fixes (S36a–S36h) from production bug investigation.
- Identified open gaps: runtime SAVE quality gate (S39), CI save E2E (S46), export progress UI (S43).

## 2026-07-21

- Critical refine/overlap sprint analysis in `docs/sphere/SPHERE_GAPS.md`.
- G0 global pose refine; IMU-parity L2; dual compositing (S17).

## 2026-07-20

- `SphereProductionStitchPipeline` as single export path.
- Pre-finish drain on DONE; background export at 4096².
