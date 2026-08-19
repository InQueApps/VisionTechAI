# Refine Pass Overlap — Test Plan & Algorithm Proposals

**Date:** 2026-07-18  
**Status:** Draft — addresses “second pass doesn’t overlap properly”  
**Symptoms:** Live preview acceptable during pan; after `refinePending` (pause or every ~6 frames) overlap ghosts, shifts, or looks worse.  
**Related:** [SPHERE_NON_PINHOLE_MOTION_PLAN.md](SPHERE_NON_PINHOLE_MOTION_PLAN.md) · [SPHERE_TWO_PHASE_PERF_AND_TEST.md](SPHERE_TWO_PHASE_PERF_AND_TEST.md) · [SPHERE_TEST_PLAN.md](SPHERE_TEST_PLAN.md)

---

## 1. Problem summary

The two-phase pipeline has **two different stitch models**:

| Pass | Buffer | Alignment | Warp geometry | Overlap compositing |
|------|--------|-----------|---------------|---------------------|
| **Fast (live)** | Incremental | Raw IMU + fast global translation | Ray at `(fx,fy)`, sample at `(fx+tx,fy+ty)` | Simple feathered accumulate |
| **Refine (rebuild)** | **Clear + replay** | Full rotation search + per-cell warp | Ray at warped `(wx,wy)`, sample warped | `recencyComposite` 0.75 decay |

Tests must catch when refine **regresses** registration relative to fast path, not only when absolute quality is poor.

---

## 2. Test goals

| ID | Goal | Why it matters |
|----|------|----------------|
| G1 | Refine must not worsen overlap vs fast-only snapshot | User-visible “second pass broke it” |
| G2 | Refine must improve or match registration on known misalignment | Shifted / noisy-IMU synthetic cases |
| G3 | Fast and refine warp models must agree within tolerance | Geometry parity prevents preview jump |
| G4 | Multi-frame sessions must stay consistent at frame boundaries | `refined=true` freeze bug |
| G5 | Recency composite must not increase ghosting on rebuild | Root cause of overlap smear |
| G6 | Finish export must match last good refine preview | End-to-end fidelity |

---

## 3. Metrics (extend `SphereImageMetrics`)

### 3.1 Existing

| Metric | Use |
|--------|-----|
| `ghostingScore` / `ghostingScoreAtOffset` | Double-edge fraction in overlap |
| `laplacianVariance` | Sharpness proxy |
| `sharpnessRetentionRatio` | Before/after refine |

### 3.2 New (proposed)

| Metric | Definition | Threshold idea |
|--------|------------|----------------|
| **`overlapRegistrationError`** | In content region, mean \|L(x,y) − L(x+δx,y)\| at expected shift δ from ground truth; lower = better aligned | Synthetic shift δ=8px: error < 15 after refine |
| **`fastRefineGhostDelta`** | `ghostAfterRefine − ghostFastOnly` on same session | Must be ≤ 0.02 (refine must not add ghost) |
| **`overlapSSDDrop`** | Native `getLastFrameInfo().residualSSD` before vs after refine on dirty frames | SSD after ≤ 0.85 × before |
| **`equirectOverlapCorrelation`** | Normalized cross-correlation of fast vs refine equirect crops in overlap band | ≥ 0.92 |
| **`warpSeedAgreement`** | \|fastGlobalTx − refineGlobalTx\| + \|ty\| from logcat / JNI hook | ≤ 2 px on synthetic shift |

Implement `overlapRegistrationError` and `equirectOverlapCorrelation` in `SphereImageMetrics.kt`; expose warp globals via debug JNI if needed.

---

## 4. Automated test plan

### 4.1 New class: `SphereRefineOverlapE2ETest`

Add to `scripts/test.ps1` native suite.

| Test | Setup | Steps | Pass criteria |
|------|-------|-------|---------------|
| **R1 fastRefine_noRegression** | Vertical stripes, 2 frames, frame2 shifted +8px | `addFrameFast` ×2 → capture equirect A → `refinePending` → equirect B | `ghostAtOffset(B) ≤ ghostAtOffset(A) + 0.02` AND `lapVar(B) ≥ lapVar(A) × 0.90` |
| **R2 refine_improvesShiftedOverlap** | Same as R1 | After refine | `ghostAtOffset(B) ≤ ghostAtOffset(A) × 0.70` OR `ghostAtOffset(B) ≤ 0.08` |
| **R3 imuNoise_refineNotWorse** | 1 base + 3 frames with ±2–5° yaw on shifted bitmaps | Fast equirect → refine → compare | `fastRefineGhostDelta ≤ 0.02` |
| **R4 multiRefine_windowRealigns** | 8 frames: first 4 refined, add 4 more, refine again | Two refine cycles | Frame 5–8 overlap ghost ≤ single-refine baseline; no seam at frame 4/5 boundary (correlation ≥ 0.90) |
| **R5 periodicRefine_midCapture** | 12 frames fast-only, refine at frame 6 and 12 | Simulate worker periodic refine | No regression at either refine vs pre-refine snapshot |
| **R6 finish_matchesRefinedPreview** | 6-frame session | `refinePending` → preview P; `getEquirectBitmap` full → F | Downscaled F vs P: correlation ≥ 0.95 in content region |
| **R7 warpGeometry_parity** | Checkerboard, injected 6px shift | Fast path with known warp; refine; compare overlap SSD | Refine global warp within ±2px of fast warp (JNI test hook) |

### 4.2 Extend `SpherePerfE2ETest`

| Gate | Budget | Notes |
|------|--------|-------|
| PG-R1 refine 10 frames | < 8 s | Unchanged |
| PG-R2 fast p95 with warp | < 130 ms | Unchanged |
| **PG-R3 refine must improve SSD** | median SSD drop ≥ 5% on 5-frame noisy session | New — fails if refine is no-op or harmful |

### 4.3 JVM / worker tests

| Class | Case |
|-------|------|
| `SphereStitchWorkerTest` | Periodic refine at frame 6 invokes `refinePending`; preview callback `refined=true` |
| `SphereRefinePolicyTest` (new) | Document when refine should run: idle debounce, periodic K, finish-only |

### 4.4 Native unit hooks (C++ test or JNI debug)

| Hook | Purpose |
|------|---------|
| `nativeGetLastFastWarp(tx, ty)` | R7 parity |
| `nativeGetRefineRebuildStats(aligned, recencyPixels)` | Logging in tests |
| Build flag `SPHERE_REFINE_REPLACE=1` | A/B recency vs replace mode |

---

## 5. Manual QA protocol

### 5.1 Environment

- Physical ARM device (not x86 emulator for native)
- Scene A: door frame + poster at 1–2 m (parallax)
- Scene B: outdoor horizon (rotation-only)
- Slow pan: ~20°/s, pause 1 s every 3 frames

### 5.2 Procedure

1. Start sphere capture, pan 12–15 frames with pauses.
2. **At each pause**, note preview before/after refine flash (logcat `Refine rebuild`).
3. Score overlap at last pause (1–5, lower better): ghosting, sharpness, seam jump.
4. Finish capture; compare viewer to last live preview.
5. Repeat with continuous pan (no pause) — periodic refine at ~6 frames.

### 5.3 Scorecard

| Criterion | Scene | Target | Blocker if |
|-----------|-------|--------|------------|
| Refine improves or matches live overlap | A | ≤ 2 | Refine visibly worse (≥ 4) |
| No full-image “jump” on refine | A, B | ≤ 2 | Large shift at refine |
| Periodic refine during motion | B | ≤ 3 | Every 6 frames degrades |
| Finish vs last preview | A | ≤ 2 | Finish clearly worse |
| IMU lag (fast pan) | A | ≤ 3 | > 30% gated frames |

### 5.4 Logcat signals (failure indicators)

```
Refine rebuild: N frames (M re-aligned)
Fast warp: global=(tx,ty)
Warp field: global=(Tx,Ty)    # should match Fast warp ± few px
Align: yaw=... pitch=...       # large corrections (>3°) on every frame = IMU/FOV issue
Frame gated: overlap ...       # pan too fast for overlap gate
```

---

## 6. CI integration

```bash
# New overlap/refine suite
.\scripts\test.ps1 -Instrumented -Class "ab.inque.visiontechai.SphereRefineOverlapE2ETest"

# Full native gate (add to native suite in test.ps1)
SphereRefineOverlapE2ETest
SphereGhostingE2ETest
SphereWarpE2ETest
SpherePerfE2ETest
```

**Release gate:** R1, R2, R3 must pass on reference device (OnePlus A6013 or equivalent). R4 blocks until `refined=true` re-align policy is fixed.

---

## 7. Algorithm proposals (better overlap)

Prioritized by impact on “second pass overlap” and implementation cost.

### P0 — Must fix (overlap regression)

#### A1. **Replace mode on full rebuild** (not recency blend)

**Problem:** `recencyComposite` with `RECENCY_DECAY=0.75` stacks misaligned layers during `refinePending` clear-and-rebuild.

**Change:**
```cpp
// refinePending replay
processFrameLocked(..., recencyComposite = false, replaceOverlap = true);

// replaceOverlap: in overlap band, zero weight before splat (or decay → 0)
if (replaceOverlap && eqWeight[nearIdx] > 0.f) {
    eqR[nearIdx] = eqG[nearIdx] = eqB[nearIdx] = 0.f;
    eqWeight[nearIdx] = 0.f;
}
```

**Test:** R1, R5 pass; ghost delta ≤ 0.

---

#### A2. **Unify fast-path and refine warp geometry**

**Problem:** Fast path uses ray@(fx,fy) + sample@(fx+tx,fy+ty); refine uses ray@(wx,wy) + sample@(wx,wy).

**Change (fast uniform translation):**
```cpp
// Match estimatePerCellWarp / full warp convention
float sx = fx + uniformTx, sy = fy + uniformTy;
pixelToRayFloat(sx, sy, frameW, frameH, tanH, tanV, rx, ry, rz);
samplePixelBilinear(pixels, frameW, frameH, sx, sy, pr, pg, pb);
```

**Test:** R7 warp parity; visual jump on refine eliminated in manual QA.

---

#### A3. **Re-align sliding window, not “unrefined only”**

**Problem:** `doAlign = !f.locked && !f.refined` freezes all frames after first refine.

**Change:**
```cpp
int ageFromNewest = n - 1 - i;
bool doAlign = !f.locked && (ageFromNewest <= REALIGN_WINDOW);
// Drop !f.refined gate — recent frames always re-align when new data arrives
```

**Test:** R4 multi-refine session; no seam at old/new boundary.

---

#### A4. **Seed refine warp from fast-path warp**

**Problem:** `estimatePerCellWarp` calls `initIdentityWarp`, discarding good fast global translation.

**Change:**
```cpp
void estimatePerCellWarp(..., const FrameWarp* seedWarp) {
    if (seedWarp && isUniformTranslationWarp(*seedWarp, tx, ty)) {
        // Start global search at (tx, ty) ± 2, not (0,0) ± 10
    } else {
        initIdentityWarp(warp);
    }
}
```

**Test:** R7; refine SSD drops faster (PG-R3).

---

### P1 — Strong overlap quality

#### B1. **Incremental refine (no full buffer clear)**

**Problem:** Clear-and-rebuild throws away good fast-path composite.

**Change:**
- **Option 1 (simple):** Only re-splat frames where `!refined || ageInWindow`; leave baked buffer for locked frames.
- **Option 2 (safer):** Rebuild in temp buffers; swap only if `overlapSSDDrop` improves.

```
if (refineQuality(temp) > liveQuality) swap(eq, temp);
else keep fast-path buffer;
```

**Test:** R1 no regression; R5 periodic refine; perf PG-R1 unchanged.

---

#### B2. **Quality gate before preview swap**

**Problem:** `handleStitchResult(refined=true)` always replaces preview.

**Change (Kotlin):**
```kotlin
if (result.refined) {
    val improved = nativeRefineImprovedOverlap() // SSD or ghost delta
    if (improved) applyPreview(result.previewBitmap)
    else Log.w(TAG, "Refine skipped — would worsen overlap")
}
```

**Test:** Manual QA “no jump when refine harmful”; R1.

---

#### B3. **Fast-path micro rotation align (±2–3°, <15 ms)**

**Problem:** Translation warp alone cannot fix yaw/pitch IMU error during motion.

**Change:** After fast global warp, run single-level rotation search ±2° step 0.5° on overlap samples (subsampled), same budget as warp.

**Test:** R3 IMU noise; fast-only ghost lower; live preview sharper during continuous pan.

---

#### B4. **Overlap-weighted replace splat**

**Problem:** Bilateral splat + feather smears edges in overlap.

**Change:** In overlap where `eqWeight > threshold`, splat with `fw = max(fw, replaceWeight)` and cap prior weight before add:

```cpp
if (inOverlap && replaceMode) {
    eqWeight[nearIdx] = min(eqWeight[nearIdx], REPLACE_CAP);
}
```

**Test:** R2 ghost reduction; artifact rate unchanged (`SphereArtifactE2ETest`).

---

### P2 — Robustness

| ID | Change | Benefit |
|----|--------|---------|
| C1 | Apply recency decay to all 4 bilinear splat targets, not just `nearIdx` | Even overlap weighting |
| C2 | Overlap phase 1 uses warped sample coords when warp active | Align SSD matches splat |
| C3 | Store `o.corrected` quat on fast path when micro-rot added | Better refine seed |
| C4 | `finishCapture` always runs one final refine with `replaceOverlap=true` | Export matches best alignment |
| C5 | Reduce periodic refine to idle-only until B1 lands | Fewer mid-pan jumps |

---

## 8. Recommended implementation order

```
Week 1 (P0):  A2 geometry parity → A1 replace mode → A3 re-align window → A4 warp seed
              + R1–R3 automated tests
Week 2 (P1):  B2 quality gate → B1 incremental refine → B3 fast micro-rot
              + R4–R7 tests
Week 3 (P2):  C1–C5 polish + manual QA sign-off
```

---

## 9. Success criteria

| Metric | Target |
|--------|--------|
| R1 fastRefine_noRegression | 100% pass on reference device |
| R2 refine_improvesShiftedOverlap | 100% pass |
| Manual refine score (Scene A) | ≤ 2 at pause |
| User report “second pass worse” | Eliminated |
| Fast p95 | < 130 ms (with B3 micro-rot < 150 ms) |
| Refine 10 frames | < 8 s |

---

## 10. Traceability

| Algorithm | Test |
|-----------|------|
| A1 replace mode | R1, R5, G5 |
| A2 geometry parity | R7, G3 |
| A3 re-align window | R4, G4 |
| A4 warp seed | R7, PG-R3 |
| B1 incremental refine | R5, R6 |
| B2 quality gate | Manual 5.2 |
| B3 fast micro-rot | R3, manual continuous pan |

Update `Work/skills/prismai/README.md` (sphere test plan section) when `SphereRefineOverlapE2ETest` lands.
