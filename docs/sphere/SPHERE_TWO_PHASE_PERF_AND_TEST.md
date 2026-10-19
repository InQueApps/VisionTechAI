# 360° Two-Phase Stitching — Test Plan & Performance Improvement Plan

**Scope:** the two-phase (fast splat + background refinement) pipeline added on this
branch. Covers (1) a full test plan for **short-time** and **long-time** panorama
generation, and (2) a root-caused **performance improvement plan** for the
regression where final generation now takes a long time.

**Related:** [SPHERE_TEST_PLAN.md](SPHERE_TEST_PLAN.md) · [SPHERE_FEATURE_PLAN.md](SPHERE_FEATURE_PLAN.md) · [SPHERE_GAPS.md](SPHERE_GAPS.md)

---

## 1. Architecture under test

| Stage | Entry point | Thread | Cost model |
|-------|-------------|--------|------------|
| Fast splat | `SphereNative.addFrameFast` → `processFrameLocked(doAlign=false, gate=0.20)` | stitch `executor` | O(frame px) splat, **no alignment** |
| Overlap gate | inside `processFrameLocked` | stitch `executor` | O(samples) |
| Background refine | `SphereNative.refinePending` (debounced 600 ms) | `refineExecutor` | O(N·align_dirty + N·splat) — **full clear + rebuild** |
| Finish generation | `SphereCaptureController.finishCapture` → `refinePending` + `refineSeams` + `getEquirectBitmap(4096×2048)` | **MAIN thread** (via `runWhenIdle`) | O(N·align_dirty + seam + full render) |
| Export | `getFullEquirect` | caller thread | same as finish |

Key tunables: `SphereStitchWorker.DEFAULT_OVERLAP_GATE=0.20`,
`DEFAULT_REFINE_DELAY_MS=600`, native `REALIGN_WINDOW=40`, `STORE_MAX_DIM=384`,
`EQ_W/EQ_H=4096×2048`.

---

## 2. Performance regression — root cause

Confirmed causes, highest-impact first:

1. **Finish runs on the main thread.** `runWhenIdle { … }` posts `onComplete` to
   `mainHandler`. `finishCapture` now calls `refinePending()` (full rebuild +
   re-align all dirty frames) **and** `refineSeams()` **and**
   `getEquirectBitmap(4096×2048)` there. → UI-thread stall of seconds.
2. **Alignment cost got bunched at finish.** The old path aligned each frame during
   capture (amortized while panning). The new path defers all alignment; if the
   600 ms idle window never occurs during a continuous pan, **all N frames are
   dirty at finish** and are aligned in one burst.
3. **Refine is a full clear-and-rebuild.** `refinePending` zeroes the entire
   4096×2048 accumulation buffer and re-splats **every** stored frame each time it
   fires — O(N) per refine, O(N²) across a session. It also holds `sphereMutex`
   for the whole rebuild, so a resumed fast-path frame blocks until it finishes.
4. **Double splatting.** Fast path splats with the raw quaternion; refine re-splats
   the same frames again after alignment.
5. **Per-frame full-buffer scans (pre-existing, now more visible).**
   `getCoverage()` scans 8.4M px and `getEquirectBitmap(1024×512)` samples the full
   4096×2048 buffer **every frame**.

---

## 3. Performance improvement plan (prioritized)

> **Status (2026-07-15):** P0 items below are **implemented** on this branch.
> P1/P2 remain for the next iteration.

### P0 — Stop freezing the UI at finish ✅
- Move `refinePending()` + `refineSeams()` + `getEquirectBitmap(EQ_W,EQ_H)` **off**
  the main thread. Run them on `refineExecutor`/`executor`; post only the resulting
  state change + bitmap back to main. Add `SphereStitchWorker.runHeavy { … }` that
  executes the block on the background executor and delivers the result via
  `mainHandler`.
- `getFullEquirect()` must not be called on the main thread; guarantee via the
  worker.
- **Expected effect:** eliminates the multi-second UI freeze; matches/*beats* the
  pre-change finish latency perceptually.

### P0 — Keep the background refine actually running during capture ✅
- Guarantee at least periodic refinement while panning so the finish "dirty" set is
  small. Options: fire refine every K accepted frames **in addition to** the 600 ms
  idle debounce (K≈8), or run a low-priority continuous refine that yields the lock
  between frames.
- **Expected effect:** finish only seam-blends + renders (near old cost) because
  most frames are already aligned.

### P1 — Make refine incremental (no full clear-and-rebuild)
- Keep a persistent **baked accumulation** for frames evicted past `REALIGN_WINDOW`,
  and only re-splat the ≤40 active frames on rebuild (start rebuild from the baked
  snapshot instead of zero). Removes the O(N²) re-splat growth.
- Alternatively, since the fast path already splatted each frame, have refine adjust
  only newly-dirty frames (align + additive splat of the delta) rather than clearing
  everything.

### P1 — Bound alignment cost with a warm-started micro-search
- The IMU quaternion is a good prior. Replace the full ±7° multi-level grid in the
  refine path with a small warm-started search (±2–3°, coarse→fine) seeded from the
  stored quaternion. Cap per-frame refine alignment to a hard budget (e.g. 30 ms).

### P1 — Two-resolution buffers
- Accumulate/refine/preview at **2048×1024** during capture; render **4096×2048**
  only once at export. Halves per-frame splat/scan and quarters refine memory
  traffic.

### P2 — Incremental coverage, avoid full scans per frame
- Maintain `coveredCount` and a solid-angle grid incrementally inside `splatPixel`
  (increment when a cell transitions 0→>0). Return cached values from
  `getCoverage()` / `getSolidAngleCoverage()` instead of scanning 8.4M px per frame.

### P2 — Reduce preview cost
- Downsample preview from a maintained low-res mip of the accumulation buffer rather
  than bilinear-sampling the full 4096×2048 every frame; throttle preview to ~10 Hz.

---

## 4. Performance budgets (pass/fail targets)

Measured on the reference ARM device (OnePlus A6013 / API 30) via instrumented
timers; capture per-frame at 320×240 analysis frames.

| Metric | Target | Notes |
|--------|--------|-------|
| Fast-path `addFrameFast` p95 | **< 20 ms** | no alignment |
| Per-frame preview post latency p95 | < 40 ms | splat + preview render |
| Background refine (dirty ≤ 8 frames) | < 250 ms | off-main, single burst |
| **Finish generation (30-frame short)** | **< 800 ms, 0 dropped UI frames** | after P0/P1 |
| **Finish generation (long, 40+ frames)** | **< 2.0 s, off-main** | after P0/P1 |
| Main-thread block during finish | **0 ms** (no JNI on main) | hard gate |
| Overlap-gate reject latency | < 15 ms | rejected before splat |
| Heap Δ, 25-frame session | < 80 MB | matches existing budget |
| Heap Δ, 80-frame long session | < 140 MB | 40 stored ×~0.44 MB + buffers |
| No OOM / no `sphereMutex` stall > 100 ms during resume | pass | P1 removes rebuild stall |

---

## 5. Test plan

### 5.1 Short-time generation (photo mode, few frames, quick finish)

| ID | Layer | Case | Assertion |
|----|-------|------|-----------|
| ST-1 | JNI E2E | `addFrameFast` first frame accepted, splats immediately | status `ACCEPTED`, coverage > 0, `< 20 ms` |
| ST-2 | JNI E2E | 6 well-overlapping frames | each accepted, coverage strictly grows, preview non-empty |
| ST-3 | JNI E2E | overlap gate: gate=1.5 forces reject | status `LOW_OVERLAP`, coverage unchanged, frame not stored |
| ST-4 | JNI E2E | `refinePending` after N frames | returns `true`, second call returns `false` (nothing dirty) |
| ST-5 | JNI E2E | fast→refine parity | equirect after refine has ≥ fast-path stitched-pixel count; alignment offset per frame recorded |
| ST-6 | Robolectric | controller finish at ≥85% solid angle | state → PREVIEW, `onPreviewReady` fired once |
| ST-7 | Robolectric | finish emits **no main-thread JNI** (P0) | assert stitch/refine ran off-main (`ranOnMainThread == false`) |
| ST-8 | Perf (instr.) | 10-frame photo finish | finish latency < 800 ms; main-thread block == 0 |
| ST-9 | UI (Espresso) | tap record ×10 → finish | viewer opens, coverage text updates, no ANR |

### 5.2 Long-time generation (continuous 360°, many frames, long session)

| ID | Layer | Case | Assertion |
|----|-------|------|-----------|
| LT-1 | JNI E2E | 60 frames spanning full azimuth (equator-oriented quats) | coverage monotonic non-decreasing; final solid angle ≥ target |
| LT-2 | JNI E2E | > `REALIGN_WINDOW` (e.g. 50) frames | frames beyond 40 marked `locked`; refine re-aligns only recent window; older frames still present (no holes) |
| LT-3 | JNI E2E | refine cost bound | refine time does **not** grow super-linearly across the session (P1: bounded per rebuild) |
| LT-4 | Perf (instr.) | 40+ frame finish | finish latency < 2.0 s, off-main; 0 dropped UI frames |
| LT-5 | Perf (instr.) | memory over 80-frame session | heap Δ < 140 MB, no OOM |
| LT-6 | Perf (instr.) | resume after refine | next `addFrameFast` not blocked > 100 ms by `sphereMutex` (P1) |
| LT-7 | Perf (instr.) | continuous 30 s real pan | reject rate < 10%; refine fires ≥ once per idle window |
| LT-8 | JNI E2E | double-splat guard | after refine, no pixel weight inflation (mean weight in overlap within tolerance) |
| LT-9 | Golden | 40-frame set → SHA-256 @ 512×256 | matches baseline within tolerance (extends `SphereGoldenImageTest`) |
| LT-10 | UI (Espresso) | continuous capture to ~100% then finish | viewer smooth; no black wedge > 15° |

### 5.3 Regression guards (specifically for this branch)

| ID | Case | Assertion |
|----|------|-----------|
| RG-1 | Finish path contains no synchronous main-thread `SphereNative` call | static check + `MainThreadGuardTest` extension |
| RG-2 | `refinePending` on clean state (no dirty) | returns fast (< 5 ms), no buffer rewrite |
| RG-3 | Fast-path latency vs old `addFrame` | `addFrameFast` p95 ≤ old `addFrame` p95 |
| RG-4 | Overlap gate does not fire on legitimate slow pan | equator-oriented consecutive frames at ≤ SLOW_PAN step → all accepted |
| RG-5 | Interleaved capture + refine | fast frames during a pending refine are not dropped or corrupted |

### 5.4 Instrumentation harness for perf tests
- Add `SpherePerfE2ETest` (androidTest) using `SystemClock.elapsedRealtimeNanos`
  around `addFrameFast`, `refinePending`, and the finish block; assert against the
  budgets in §4. Log a CSV to logcat for trend tracking.
- Reuse `scripts/test.ps1 -Suite native` and add a `perf` suite entry.

---

## 6. Suggested execution order
1. Land **P0** (off-main finish + periodic refine) → re-run ST-7, ST-8, LT-4, RG-1.
2. Add `SpherePerfE2ETest` and capture a baseline for §4.
3. Land **P1** (incremental refine, warm-start align, two-res) → re-run LT-3/6, RG-3.
4. Land **P2** (incremental coverage, preview mip) → confirm per-frame budgets.
5. Extend `SphereGoldenImageTest` to a 40-frame long set (LT-9) and wire into CI.
