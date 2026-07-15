# PRISM AI — Gaps & Backlog

**Version:** 0.5.1 · **Tests:** 146 JVM + 14 instrumented

## Active regression — sphere photo capture (2026-07-15)

Preview-fix commit introduced silent capture failures. Root causes and status:

| ID | Regression | Cause in diff | Fix | Status |
|----|------------|---------------|-----|--------|
| R1 | Tap record → no toast, feels like nothing happened | `pendingPhotoCaptureToast` deferred success until `onFrameAccepted`; cleared on any `onFrameWarning` | Restore immediate tap feedback; remove pending-toast gate | ✅ Fixed |
| R2 | First rejected frame completely silent | `SphereAlignmentPolicy` skipped all warnings when `frameIndex <= 1` | Warn on `wasRejected` even for frame 1 | ✅ Fixed |
| R3 | Wrong IMU time on photo tap | `copyToArgb8888(raw)` dropped `frameTimestampNanos` | Store `lastRawFrameTimestampNanos`; pass to `captureFrame` | ✅ Fixed |
| R4 | Redundant bitmap copy on tap | Double `copyToArgb8888` in session + worker | Let worker own copy; pass pipeline bitmap + timestamp | ✅ Fixed |
| R5 | Preview still blank after accept | `SpherePreviewBinder` / UI-thread path | Keep `runOnUiThread` + `setImageBitmap` copy (retain) | 🟡 Monitor on device |
| R6 | Toast in dispatcher | `markPendingPhotoCapture` + toast in `PipelineResultDispatcher` | Remove; session owns UX | ✅ Removed |

### Remove / do not re-introduce

- `pendingPhotoCaptureToast` flag — couples tap UX to async accept; hides failures
- `markPendingPhotoCapture()` on dispatcher — wrong layer for toasts
- `onFrameAccepted` callback used only for toast — use `onCoverageUpdate` for preview
- Double bitmap copy before `captureFrame` — worker already copies in `submit()`
- `SphereAlignmentPolicy` blanket `isFirstFrame` early return before reject check

### Keep from preview fix

- `SpherePreviewBinder.apply()` with `setImageBitmap` + main-thread post
- `onCoverageUpdate` on UI thread in `SphereSessionController`
- `lastRawFrame` + timestamp wiring via `onSphereRawFrame`
- `captureNextFrame` deferred path (when no raw frame yet)
- Worker-owned `copyToArgb8888` in `submit()`

### Verify on device (manual)

1. Enter sphere → tap record (photo mode) → toast + preview wedge updates within ~1s
2. Reject path: misalign on purpose → "Frame skipped…" toast
3. Exit sphere → normal photo mode `takePhoto()` still saves to gallery
4. Logcat: `SphereCaptureCtrl` shows `Frame accepted` not only `Frame rejected`

---

## Resolved (recent)

| Area | Status |
|------|--------|
| Sphere Phases A–F + preview pipeline | ✅ |
| S1 lens distortion store | ✅ |
| S8 micro-shift / S12 seam refine | ✅ |
| G7 CI paths / G4 controller tests | ✅ |
| Contact Us recipient | ✅ |

## Open — app-wide

| ID | Gap | Priority |
|----|-----|----------|
| G1 | `MainActivity` still large (~1.4k LOC) | P2 |
| G2 | Sphere save/MediaStore Espresso | P3 |
| G5 | Video recording pipeline deep tests | P3 |
| G8 | Sphere capture integration test on device (tap → preview pixel) | P2 |

## Open — sphere stitching (residual)

| ID | Gap | Status |
|----|-----|--------|
| S8 | Full ORB / OpenCV | ❌ Deferred |
| S12 | Full Laplacian multiband | 🟡 Lite seam refine only |

## Next recommended work

1. **G8** — instrumented test: sphere tap → wait → assert `spherePreviewImage` pixel ≠ background
2. **G1** — extract recording + slider wiring from `MainActivity`
3. **S12 v2** — Laplacian pyramid on export
