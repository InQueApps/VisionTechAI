# Sphere SAVE / export lifecycle fixes

**Date:** 2026-07-23 — 2026-07-25  
**Symptom:** "Can't export" when saving 360° photos; poor live vs roundtrip quality.

## Root cause — export failure

`saveEquirectangular()` called `dismissSphereUiKeepSession()` which set `VisionMode.NONE` → `onSphereCleanupFromModeSwitch()` → **`resetSession()`** wiped native frames **before** `startBackgroundFullExport()` completed.

## Fixes applied

### SphereSessionController.kt

- `enter()` blocks re-entry while `pendingBackgroundSave` or export in progress.
- `onSphereCleanupFromModeSwitch()` uses **`release()`** instead of `resetSession()` when not retaining session; skips cleanup entirely during background export.
- `cleanupOnDestroy()` awaits worker termination (10s) before native release.
- `saveEquirectangular()` requires **`State.PREVIEW`**; starts export **before** UI dismiss.
- `pendingBackgroundSave` lifecycle guards; `deliverBackgroundSaveResult()` checks `activity.isDestroyed`.
- Block cancel/close/add-more during background export.

### SphereCaptureController.kt

- Queued export callbacks (`CopyOnWriteArrayList`) for double-tap SAVE.
- `release()` awaits worker shutdown before `operations.release()`.
- `restartCapture()` / `resumeCapture()` blocked during export.
- Post-DONE: `stitchWorker.schedulePreFinishDrain(0L)`.

### SphereStitchWorker.kt

- `invalidatePendingRefinement()` resets `preFinishProgress`.
- Public `schedulePreFinishDrain()`.
- `refinementGeneration` invalidates stale L2/L3 callbacks (S37).

### UI

- Capture bar SAVE hidden: `sphereCaptureSaveButton` `visibility=gone`.
- SAVE only on viewer overlay (`sphereSaveButton`) after DONE.

### Debug parity

- `SphereDebugSessionReplayer` aligned with `SphereProductionStitchPipeline.runFinishExport`.

## Tests added/updated

| File | Coverage |
|------|----------|
| `SphereSessionControllerSaveTest.kt` | Session retention, SAVE before DONE rejected |
| `SphereCaptureControllerTest.kt` | Queued export callbacks |
| `SphereSaveExportE2ETest.kt` | DONE→finish/export, save-before-DONE, queued callbacks |
| `SphereSessionSaveE2ETest.kt` | Session inject→DONE→SAVE; re-enter blocked during export |

## Remaining gaps (do not assume fixed)

- No runtime quality gate on production SAVE (S39).
- No export progress UI (S43).
- `refreshViewerAfterBackgroundFinish = false` (S40).
- Process death loses in-flight export (S42).
- CI does not run save E2E classes (S46).

See [../prismai/GAPS.md](../prismai/GAPS.md).
