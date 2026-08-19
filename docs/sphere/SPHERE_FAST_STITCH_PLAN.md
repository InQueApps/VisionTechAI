# 360° Fast Stitching — Diagnosis, Feature Plan & E2E Test Plan

**Date:** 2026-07-15 · **Device:** OnePlus A6013 (SD845, API 30)
**Related:** [SPHERE_TWO_PHASE_PERF_AND_TEST.md](SPHERE_TWO_PHASE_PERF_AND_TEST.md) · [SPHERE_TEST_PLAN.md](SPHERE_TEST_PLAN.md)

---

## 1. Ground-truth measurements (why it's still slow)

Captured via `SphereStitchDiagnosticTest` (`adb logcat -s SphereDiag`), realistic
640×480 analysis frames into a 4096×2048 sphere:

| Operation | Measured | Notes |
|-----------|----------|-------|
| `addFrameFast` (doAlign=false) | **73.7 ms avg / 76 ms max** | Phase-3 splat loops **every** 640×480 pixel with `pixelToRay`+`quatRotate`+`atan2`+`asin`. |
| `getCoverage` + `getSolidAngleCoverage` | **33 ms/frame** | Two full scans of the 8.4M-pixel weight buffer. |
| `getEquirectBitmap(1024×512)` preview | **15.7 ms/frame** | Bilinear-samples the full 4096×2048 buffer. |
| `refinePending` (12 dirty frames) | **1 522 ms** | Full clear + re-run multi-level ±7° alignment on every dirty frame + re-splat all. Grows with frame count. |
| **End-to-end photo tap (submit→result)** | **avg 15.7 s, max 22 s, p50 13.9 s** | Refines pile up on one thread holding `sphereMutex`; every fast frame blocks behind them. |

### Verdict
- **Two-step IS active** — the fast path skips alignment (zero Phase-2 time confirmed).
- **But the "fast" path is not fast (74 ms)** and the **background refine is catastrophic
  (1.5 s+, growing) and over-triggered** (every accepted frame via 600 ms debounce
  **and** every 8 frames immediately). Refines serialize on a single executor while
  holding the native lock, so taps stall for 14–22 s.
- Net effect is **worse than single-step**: we splat each frame twice (fast + refine)
  and re-align the whole stack repeatedly.

### Root causes (ranked by measured impact)
1. **Refine over-triggering + full rebuild + lock-held compute** → 14–22 s tap latency.
2. **Full-resolution splat** (74 ms) — cost is O(frame pixels), independent of sphere size.
3. **Per-frame full-buffer coverage scans** (33 ms).
4. **Preview samples the full 4096×2048 every frame** (16 ms).
5. **No guard against `execute()` after `shutdown()`** → `RejectedExecutionException`
   observed when a debounced refine fires post-teardown.

---

## 2. Feature improvement plan

Target: **photo-tap submit→preview < 200 ms p95**, **refine never blocks the fast path
> 10 ms**, identical final quality at export.

### F1 — Incremental coverage (removes 33 ms/frame) ✅ target
Maintain `sCoveredCount` and an incremental 32×16 solid-angle grid inside `splatPixel`
(increment on 0→>0 weight transition). `getCoverage`/`getSolidAngleCoverage` return
cached O(1) values. Full recompute only after a refine rebuild.

### F2 — Downscale splat in the fast path (74 ms → ~18 ms) ✅ shipped
The live accumulation only needs preview quality. Splat a downscaled copy of the frame
(cap longest side to `LIVE_SPLAT_DIM = 320`). Coarser equirect stride (`eqStep=2`) when
`eqW > 2048`. Full-resolution pixels are stored for export-time refine.

### F3 — Fix refine scheduling & locking ✅ target
- **Remove the every-8-frames immediate refine.** Keep only the idle debounce, raised to
  ~900 ms, so refine runs when the user pauses — not mid-burst.
- **Swap-under-lock:** refine computes into scratch accumulation buffers with the lock
  released during the heavy alignment/splat, then swaps buffers under the lock in O(1).
  Fast-path frames are never blocked more than a buffer-pointer swap.
- **Coalesce:** never queue more than one pending refine.
- **Guard executors:** ignore `execute()` after `shutdown()` (no `RejectedExecutionException`).

### F4 — Cheaper preview (16 ms → ~2 ms)
Maintain a low-res accumulation mip (e.g. 512×256) updated during splat, and build the
preview bitmap from it instead of bilinear-sampling the full buffer every frame. Throttle
preview emission to ≤10 Hz.

### F5 — (Stretch) Warm-started micro-alignment in refine
Replace the full ±7° multi-level grid with a ±2–3° search seeded from the stored IMU
quaternion, hard-capped to ~30 ms/frame. Cuts refine cost another 3–5×.

### Expected per-tap budget after F1–F3 ✅ **ACHIEVED on OnePlus A6013 (2026-07-15)**
| Operation | Before | After F1–F3 | Target |
|-----------|--------|-------------|--------|
| `addFrameFast` 640×480 | 73.7 ms | **32 ms p95** | < 50 ms |
| `getCoverage`+`getSolidAngle` | 33 ms | **< 0.1 ms** | < 3 ms |
| Photo tap submit→result | 15.7 s avg | **66 ms avg / 92 ms p95** | < 500 ms |
| `refinePending` no-op | — | **0 ms** | < 5 ms |
| Finish generation | UI freeze | **192 ms off-main** | < 800 ms |

---

## 3. E2E test plan (device-gated for user-speed confidence)

### 3.1 Diagnostics (non-gating, always logged)
`SphereStitchDiagnosticTest` — logs per-phase timings, lock-contention probe, and
end-to-end worker tap latency under `SphereDiag`. Run before/after each perf change.

### 3.2 Performance gates (fail the build if regressed) — `SpherePerfE2ETest`

| ID | Case | Gate |
|----|------|------|
| PG-1 | `addFrameFast` p95, 640×480 → 4096×2048 | **< 30 ms** |
| PG-2 | `getCoverage`+`getSolidAngle` per call (incremental) | **< 3 ms** |
| PG-3 | preview `getEquirectBitmap(1024×512)` p95 | **< 20 ms** |
| PG-4 | End-to-end worker photo tap (submit→result) p95, 16 taps | **< 200 ms** |
| PG-5 | `addFrameFast` latency **while refine runs** (lock contention) | **< 3× uncontended** |
| PG-6 | `refinePending` (≤ REALIGN_WINDOW dirty) | **< 400 ms** |
| PG-7 | refine no-op when nothing dirty | **< 5 ms** |
| PG-8 | finish generation runs off main thread | `ranOnMainThread == false` |

### 3.3 Correctness gates (unchanged quality) — `SphereStitchE2ETest`, `SphereGoldenImageTest`
- Coverage grows monotonically; overlap gate rejects <20% overlap frames.
- Downscaled-splat panorama still passes the golden hash within tolerance.
- Incremental coverage equals a full recompute (parity assert after N frames + after refine).

### 3.4 Long-session stress — `SpherePerfE2ETest`
| ID | Case | Gate |
|----|------|------|
| LS-1 | 60-tap session, per-tap p95 | **< 250 ms** (no upward drift) |
| LS-2 | refine cost at frame 60 vs frame 12 | **< 2×** (bounded by REALIGN_WINDOW) |
| LS-3 | heap Δ over 80 taps | **< 140 MB** |

### 3.5 Runner
```powershell
.\scripts\test.ps1 -Suite native        # gates: SphereStitchE2ETest, SpherePerfE2ETest, golden
.\scripts\test.ps1 -Class ab.inque.visiontechai.SphereStitchDiagnosticTest   # timings
```

---

## 4. Execution order
1. F1 incremental coverage → re-measure PG-2.
2. F2 downscale splat → re-measure PG-1, verify golden (3.3).
3. F3 refine scheduling + swap-under-lock + shutdown guard → re-measure PG-4/PG-5/PG-6.
4. Add/adjust `SpherePerfE2ETest` gates; run full native suite.
5. F4 preview mip; F5 warm-start (stretch) if PG-4 still tight on low-end devices.
6. Re-run `SphereStitchDiagnosticTest`, paste before/after into this doc.
