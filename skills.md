# VisionTechAI — Sphere stitch / IMU degrade replay knowledge base

Living notes from IMU-degraded planar replay work. Use for planning, ideation, and avoiding repeated dead ends.

---

## Problem

Live panorama capture seeds each frame pose from IMU. When IMU is wrong, frames misalign → visible ghosts (double edges, blur in overlap). We simulate this offline by replaying 16 extracted planars with randomized orientation error, then measuring stitch quality vs full-sphere truth.

**Primary pain point:** 16° per-frame IMU degrade — worst overlap quality, fails E2E gates, visibly ghosted horizon ring.

**What “bad stitch” looks like:** sharpness retention > 2.0 (stitched Laplacian variance ~2× reference), parallel edges in overlap band, seam tears at frame boundaries. Ghost score (~0.71 everywhere) does not correlate with IMU level — do not use for sweep decisions.

---

## Benchmark setup

| Item | Value |
|------|-------|
| Source | `img_eq4096.jpg` → 4096×2048 truth equirect |
| Replay export | 1024×512 equirect (`exportEqW/H` in manifest) |
| Frames | 16 planars: horizon ring (~60° az steps) + elevation tilts (±45°, ±90°) |
| FOV | 90° H / 90° V |
| IMU degrade model | `degradeImuQuaternion`: uniform angle in [0, degradeDeg], random axis, **independent per frame** (not accumulated drift) |
| Seed | `imuDegradeSeed=42` (reproducible) |
| Device | OnePlus A6013 (`328bea72`), Android 9 |
| Sweep levels | 0, 2, 4, 8, 16° |

**Important:** 16 planars do **not** cover the full sphere. Poles and much of the equirect were never photographed. Full-sphere correlation still scores ~524k “content” pixels because reference is full truth and dark-blue fill masks are permissive.

### How we run it

```powershell
python scripts/stitch_planar_roundtrip.py --device 328bea72 --source img_eq4096.jpg --imu-degrade-deg N --sweep-fast
python scripts/run_imu_degrade_sweep.py --device 328bea72 --imu-levels 0 2 4 8 16 --sweep-fast
python scripts/run_imu_degrade_sweep.py --plot-only --imu-levels 0 2 4 8 16 --sweep-fast
```

Outputs: `scripts/images/roundtrip_output/img_eq4096_imu{N}deg/` + `imu_degrade_sweep_summary.png`

Manifest flags (typical sweep): `productionReplay=true`, `useProductionWorker=true`, `sweepFast=true`, `compareFullEquirect=true`, `skipPlanarRoundtrip=true`, `overlapGate=0.1`.

---

## Pipeline architecture (how frames become the final image)

### Ingest (per frame during replay)

`SphereStitchWorker` → `nativeAddFrameFast` with degraded seed quaternion.

When `overlapGate > 0` (default 0.1 for degraded replay):

1. **G1-fast** (`processFrameLocked`, `doAlign=false` but G1 path active): edge-map coarse → RGB mid → RGB fine search against **current equirect accumulator** (~500 ms budget). Stores result as `fastCorrectedQuat`.
2. Optional G2/G3 fast warp (translation + per-cell warp).
3. Optional escalate to full 6-DoF rot search on dubious SSD.
4. Splat into live equirect; store frame pixels + `imuQuat` + `fastCorrectedQuat` + warp.

When `overlapGate=0`: no G1-fast; `fastCorrectedQuat` ≈ raw degraded IMU.

**Native tuning added during this work:** gradient-magnitude Sobel on equirect + planar overlap, ZNCC for coarse edge alignment, IMU-gap-scaled search radii, `globalImuPriorLambda` → 0 when gap > 8° or SSD > 3000.

### Export — sweep-fast degraded path (`SphereProductionStitchPipeline.runSweepFastFinishExport`)

Current production path for degraded IMU (reverted after failed experiment):

```
setFastFinishRefine(true)
setGlobalRefinePasses(0)          // C++: override 0 = production default (5 passes, full 6-DoF)
configureForCaptureRefine()       // also sets global passes to 0 (same effect)
refineGlobalPose()                // nativeRefinePending(applyGlobalPose=true)
rebuildFullAlign()                // nativeRebuildFullAlign
prepareExportEquirect()
refineSeams(light=true)
```

**0° truth path:** `rebuildExportNearestCenter()` only (no global, no full align).

### What `refineGlobalPose()` does (`nativeRefinePending`, `applyGlobalPose=true`)

1. Reset `refinedQuat` ← `fastCorrectedQuat` for all frames.
2. `globalRefinePosesLocked()`: 5 passes × (per-frame global rot GN + translation GN) on **pairwise ray SSD** graph; loop pair last↔frame0 in `neighborPairwiseSSD` (one extra pair, same weight as neighbors).
3. `refineMotionWarpsLocked()` unless fast override — per-frame parallax warp from equirect overlap.
4. SSD revert guard: if overlap-bank SSD does not improve by >0.5%, revert all poses to pre-global seeds.
5. On success: bake `refinedQuat` → `fastCorrectedQuat`.
6. `rebuildEquirectFromStoredFramesLocked(doAlign=false)` — resplat from graph poses.

### What `rebuildFullAlign()` does (`nativeRebuildFullAlign`)

1. Reset `refinedQuat` ← `fastCorrectedQuat`, `refined=false`, `locked=false`.
2. Clear equirect; for each stored frame sequentially: `processFrameLocked(doAlign=true, exportRebuild=true)` — **equirect SSD** search against **growing partial panorama**.
3. Can update `refinedQuat` per frame if align accepts.

**Architectural tension:** global uses pairwise rays; full align uses sequential equirect SSD. Different objectives. Theory was full align undoes global — **experiment showed it helps at 16°** (see below).

---

## Metrics — two formulas, two regions

| Source | Correlation formula | Region |
|--------|---------------------|--------|
| **Device E2E** (`SphereImageMetrics.equirectOverlapCorrelation`) | Luminance Pearson → **mapped** `(corr+1)/2` ∈ [0,1] | Content bbox ∩ overlap band on both images |
| **PC sweep** (`scripts/compare_equirect.py`, `--full`) | Luminance Pearson **raw** ∈ [-1,1] | `mask_both` = non-dark-blue in ref AND stitched (~entire 1024×512) |

**Conversion:** `mapped ≈ (raw + 1) / 2`. Example: raw 0.657 ↔ mapped 0.828.

**Gates (sweep-fast, full equirect, degraded):** corr ≥ 0.84 mapped, PSNR ≥ 14 dB, ghost ≤ 0.74.

**Planar roundtrip** (currently `skipPlanarRoundtrip=true` in manifest): re-extract each view at truth az/el, compare to source planar PSNR. Scores only captured regions — better signal for alignment quality vs coverage. Not yet primary sweep metric.

**Do not use for decisions:** ghost score (flat ~0.71), full-sphere corr alone at 16° (mixes uncovered poles with stitch error).

---

## IMU sweep results (PC `compare_equirect --full`, baseline pipeline)

Raw luminance correlation, 1024×512 full equirect vs reference in per-level folder.

| IMU | Raw corr | Mapped ≈ | PSNR | Sharp ret | Notes |
|-----|----------|----------|------|-----------|-------|
| 0° | 0.986 | 0.993 | 28.9 dB | 1.75 | Truth nearest-center |
| 2° | 0.896 | 0.948 | 20.6 dB | 1.73 | Retake after edge hybrid |
| 4° | 0.861 | 0.931 | 19.3 dB | 1.71 | |
| 8° | 0.775 | 0.888 | 17.1 dB | 1.78 | Regressed vs earlier 0.801 after edge tuning |
| 16° | 0.657 | 0.828 | 15.5 dB | 2.21 | Fails E2E gate; worst visual ghosts |

Edge-map coarse → RGB fine G1-fast helped 2°/4°; 8°/16° remain weak. `setGlobalRefinePasses(1)` hurts quality — do not use.

---

## Experiments and how we arrived at conclusions

### 1. Edge-map coarse → RGB fine hybrid (native G1-fast)

**Hypothesis:** RGB SSD fails on indoor/architectural texture at high IMU error; gradient ZNCC gives better coarse rotation seed.

**Method:** Added `eqGradMag`, `overlapGradZnccCost`, G1a edge wide → G1b RGB mid → G1c RGB fine; commit only if RGB improves.

**Result:** 2° improved (fresh retake ~0.896 raw). 8° regressed. 16° unchanged at baseline level. Tuning one IMU level often moves another — brittle stack, not root fix.

### 2. Remove `rebuildFullAlign` after global (export path simplification)

**Hypothesis:** Sequential equirect align after global graph compounds chain error and overwrites graph solution; graph poses + single splat should be cleaner.

**Method (Aug 2026):** `runSweepFastFinishExport` — `refineGlobalPose()` only, no `rebuildFullAlign()`. Also tested with `overlapGate=0` (skip G1-fast ingest).

**Results at 16°:**

| Variant | PC raw corr | Device mapped | PSNR |
|---------|-------------|---------------|------|
| Baseline (global + full align + G1) | 0.657 | ~0.828 | 15.45 dB |
| Graph only (no G1, no full align) | 0.575 | 0.786 | 14.21 dB |
| G1 + no full align | 0.585 | 0.790 | 14.46 dB |

**Conclusion:** Removing full align **regressed** visible quality and metrics at 16°. Global graph alone (SSD 2632→2058 in logs) is insufficient; sequential full align still does corrective work. Skipping G1-fast slightly worsened seeds. **Reverted to baseline pipeline.**

**Outputs preserved:** `img_eq4096_imu16deg_graph_only/`, `img_eq4096_imu16deg_no_fullalign/`.

### 3. Metrics / planning proposals (rejected as primary strategy)

User rejected a roadmap framed as: unify correlation formulas, synthetic captured mask, loop closure as buzzword, host/emulator replay, two-speed global as phased weeks.

**Valid insight retained:** score alignment where data exists (planar roundtrip, horizon band) — not as mask gymnastics on full sphere.

### 4. Controller-free planar replay

**Problem:** Replay crashed without `SphereCaptureController` / SharedPreferences.

**Fix:** Production worker replay path in `SpherePlanarReplayE2ETest` — native init + `SphereStitchWorker` submit, no live IMU/controller state.

---

## Root causes of bad images (16°)

1. **Wrong frame poses** in overlap — ghosts are rotation error, not exposure/seam polish.
2. **Global graph insufficient** at 16° independent per-frame errors — improves SSD but leaves misalignment.
3. **`rebuildFullAlign` helps** despite messy architecture — sequential equirect align recovers what global misses.
4. **G1-fast seeds matter** — align to muddy accumulator still beats pure degraded IMU for global seeding.
5. **Motion warp after global** runs on 15/16 frames; logs show SSD 10k–18k on some frames — warps may fight bad rotation (`refineMotionWarpsLocked` in global path).
6. **Loop closure weak** — `neighborPairwiseSSD` adds last↔frame0 as **one equal-weight pair** among neighbors; wrap seam (0°↔300°) still hardest visually.
7. **Full-sphere compare misleading** — low corr at 16° partly reflects uncovered regions vs truth, not only stitch failure.

---

## Device / run reliability

| Failure | Cause |
|---------|-------|
| SIGKILL (signal 9) | OOM / low-memory killer during heavy export (global + full align, 16 frames, STORE_MAX_DIM up to 2560, grad rebuilds) |
| `INSTRUMENTATION_CODE: 0` | Process crash mid-test |
| adb `device offline` / exit 255 | Intermittent disconnect mid-sweep |
| Gradle `InstallException` | Occasional; manual `adb install -r` worked |
| Stale `metrics.json` in sweep | Test crash → script skips pull/compare; old numbers appear in plot |

**Mitigation:** Run levels individually; keep device connected; verify `metrics.json` timestamp after each level.

**Host x64:** `scripts/test-robotics-cpp.ps1` builds math/resample tests only — not `sphere_jni.cpp`. Full phone-free stitch replay needs new work.

---

## Key files

| Path | Role |
|------|------|
| `app/src/robotics/cpp/panorama/sphere_jni.cpp` | G1-fast, global refine, full align, edge/ZNCC |
| `app/src/main/java/ab/inque/visiontechai/SphereProductionStitchPipeline.kt` | Sweep-fast export orchestration |
| `app/src/main/java/ab/inque/visiontechai/SphereStitchWorker.kt` | Live/replay frame submit + refine scheduling |
| `app/src/androidTest/java/ab/inque/visiontechai/SpherePlanarReplayE2ETest.kt` | E2E replay, IMU degrade, gates |
| `app/src/androidTest/java/ab/inque/visiontechai/SphereImageMetrics.kt` | Device correlation, ghost, sharpness |
| `app/src/robotics/java/ab/inque/robotics/image/ImageOverlapMetrics.kt` | `(corr+1)/2` mapping |
| `scripts/stitch_planar_roundtrip.py` | Device roundtrip + compare |
| `scripts/compare_equirect.py` | PC metrics (raw corr) |
| `scripts/run_imu_degrade_sweep.py` | Multi-level sweep + plot |
| `app/src/androidTest/assets/planar_replay/manifest.json` | Replay config template |

---

## Open hypotheses (prioritized for next work)

1. **Strengthen loop closure** — higher weight or dedicated pass for frame N−1 ↔ frame 0 pairwise ray SSD before/after global; targets wrap seam ghosts.

2. **Gate motion warp** — skip `refineMotionWarpsLocked` when per-frame pairwise SSD or post-global overlap SSD above threshold (logs: warp on 10k+ SSD frames).

3. **Pairwise chain seed** — before global, align frame i to i−1 in ray space (full budget) instead of equirect G1-fast seeds; keeps ingest light, improves global convergence.

4. **Planar roundtrip as primary metric** — set `skipPlanarRoundtrip=false`; sweep plot mean/min planar PSNR/corr; separates alignment from coverage.

5. **Horizon-band equirect secondary** — compare ±35% lat band only, not full sphere or band-vs-full mismatch.

**Avoid (validated dead ends):**

- Removing `rebuildFullAlign` without replacement at high degrade.
- More G1-fast / edge / ZNCC knobs as main lever (moves one level, hurts another).
- Ghost score on sweep plots.
- `setGlobalRefinePasses(1)`.
- Full-sphere corr as sole 16° success criterion.

---

## Native bugs fixed during this work

- Gradient SSD compared against RGB baseline in coarse search (wrong minima).
- G1-fast committed when only gradient improved (RGB worse).
- Stale variable names in G1-fast (`g1aBestDyaw`, `bestSSD` vs `bestRgbSSD`).
- `refineFramePoseGlobal` accept logic compared against post-search baseline incorrectly.

---

## Recommended next steps (fundamental strategy)

### What we're actually solving

At 16° degrade, **frame poses are still wrong in overlap regions** after export. Seams and exposure cannot fix that. The pipeline runs three alignment stages that interact:

| Stage | When | Signal | Role at 16° |
|-------|------|--------|----------------|
| G1-fast | Ingest | Equirect SSD vs muddy accumulator | Seeds global; imperfect but better than raw IMU |
| Global graph | Export | Pairwise ray SSD (+ weak loop pair) | Bulk correction; SSD improves but poses still off |
| `rebuildFullAlign` | Export | Sequential equirect SSD vs partial pano | **Recovers what global misses** — removing it regressed quality |

**Fundamental goal:** strengthen global + seeds so overlap ghosts disappear, without relying on sequential full align chain drift. Until then, keep full align — it's doing real work.

**Not the goal:** more G1-fast/edge/ZNCC tuning, full-sphere corr optimization, ghost score, or infrastructure (emulator/host replay) before alignment is fixed.

### Phase 0 — Fix the scoreboard (do first, ~1 day)

**Problem:** We've been optimizing against the wrong metric. Full-sphere corr at 16° mixes uncovered poles with stitch error; device vs PC use different corr mapping; `skipPlanarRoundtrip=true`.

**Actions:**
1. `skipPlanarRoundtrip=false` in sweep manifest.
2. Log + plot **mean and min planar roundtrip PSNR** across 16 frames (device E2E already has the code).
3. Extend `run_imu_degrade_sweep.py` / `metrics.json` with planar roundtrip fields.
4. Add horizon-band equirect compare as secondary (not primary).
5. Align PC compare gate with device: use mapped corr or document raw↔mapped in plot labels.

**Decision gate after Phase 0:**
- If **planar roundtrip good** at 16° but full-sphere corr bad → coverage problem, not stitch; tighten gates and compare region.
- If **planar roundtrip bad** at 16° → real alignment failure in captured views; proceed to Phase 1–2.

No native changes until this gate is read.

### Phase 1 — Surgical native fixes (low risk, ~2–3 days)

Two changes that target logged failure modes without removing full align:

**1. Gate motion warp after global**

Logs: `refineMotionWarpsLocked` runs on 15/16 frames; SSD 10k–18k on several frames during warp search. Parallax warp on badly rotated frames adds ghost energy.

- Skip motion warp when per-frame pairwise SSD or post-global mean SSD > threshold (e.g. 3000–5000).
- Retest 16° only. Expect sharper overlap, possibly lower sharpness retention.

**2. Strengthen loop closure in global graph**

`neighborPairwiseSSD` adds last↔frame0 as **one equal-weight pair**. Wrap seam (az 0° ↔ 300°) is the worst visual artifact.

- Weight loop pair 3× (or dedicated loop-closure GN pass on frame N−1 and frame 0 only).
- Optionally add frame N−1 ↔ frame 0 in `sparseGraphPairwiseSSD` for Gauss-Newton, not just neighbors.

Retest 16°. Success = planar roundtrip ↑ and visible wrap seam improvement.

**Keep:** `rebuildFullAlign` until Phase 2 proves global+seeds are enough alone.

### Phase 2 — Fix seeds, not accumulators (~1 week)

G1-fast aligns to **equirect accumulator** during ingest — wrong signal when prior frames are misaligned. Skipping G1-fast hurt global seeds. Need a third option:

**Pairwise chain seed before global** (export-only for replay; optional for live pre-finish):

```
For i = 1..N−1:
  search rotation of frame i vs frame i−1 using pairwiseFrameRaySSD (full budget, ±search from IMU gap)
  chain-compose into global poses
→ feed into globalRefinePosesLocked (existing)
→ rebuildFullAlign (existing, for now)
```

- Uses same photometric signal as global graph, not equirect mud.
- Replay can use `overlapGate=0` on ingest (no G1-fast) — faster ingest, cleaner experiment.
- Live capture can run chain seed at pre-finish/finish instead of relying on ingest G1-fast corrections.

**Decision gate:** If chain seed + Phase 1 fixes planar roundtrip at 16° to ≥ ~32 dB min / ≥ 0.90 mean corr, full align may become optional — then try removing it again.

### Phase 3 — Replace sequential full align (only if Phase 2 insufficient)

`rebuildFullAlign` helps but compounds chain error. Replacement options (pick one):

| Option | Idea | Pros | Cons |
|--------|------|------|------|
| **A. Batch pairwise align** | After global, one pass: each frame aligns vs i−1 and i+1 ray SSD jointly, no sequential accumulator | Same signal as global; no chain drift | New export path |
| **B. Hub frame** | Build equirect from frame 0 only; align all others to hub; then global polish | Simple anchor | Hub error propagates |
| **C. More global passes + loop** | 8–10 passes, stronger loop, no full align | Minimal new code | May not converge; slow |

Do not implement until Phase 1–2 results are in.

### Phase 4 — Benchmark realism & reliability (parallel, not blocking)

**IMU model:** Current degrade is **independent per-frame** random error up to N° — harsher than real IMU bias drift. Add optional `imuDegradeMode: drift` (accumulated error along capture path) alongside existing model so we don't over-fit to worst-case independent noise.

**OOM:** Export at 16° ~312s, SIGKILL risk. For replay sweeps: cap `STORE_MAX_DIM` during replay, or run levels individually (already mitigating). Consider `refineMotionWarpsLocked` skip as memory win too.

**Sweep hygiene:** Script must not plot stale `metrics.json` on test failure; fail loudly or pull partial stitch anyway.

### What NOT to do next

- Remove `rebuildFullAlign` without Phase 2 chain seeds + Phase 1 loop/warp fixes (validated regression).
- More edge/ZNCC/G1-fast search tuning as primary lever.
- Host x64 / emulator replay before alignment fixed (high effort, doesn't fix ghosts).
- `setGlobalRefinePasses(1)`.
- Ghost score on sweep plots.

### Suggested execution order

```
Phase 0 (metrics)     → read gate
Phase 1a (warp gate)  → 16° retake
Phase 1b (loop weight)→ 16° retake
Phase 2 (chain seed)  → 16° retake + try drop full align
Phase 3 (batch align) → only if still bad
Phase 4 (drift model) → ongoing
```

**First PR:** Phase 0 only (manifest + sweep plot + metrics.json fields). **Second PR:** Phase 1a+1b in `sphere_jni.cpp`. Measure everything at 16° with planar roundtrip as headline number.

---

*Last updated: 2026-08-19. Scoreboard: planar roundtrip enabled, replay_metrics.json, sweep plot uses planar PSNR. Phase 1: loop closure 3× weight, motion warp gated at pairwise SSD > 3000.*
