# 360° Stitching — Non-Pinhole Camera & Inter-Frame Motion Plan

**Date:** 2026-07-17  
**Status:** G2a/G3a/G4a implemented (per-cell warp + background refine)  
**Symptoms:** Washed / muddy pixels, ghosting, seam artifacts when the phone moves between captures or subjects are not at infinity.  
**Related:** [SPHERE_GAPS.md](SPHERE_GAPS.md) (S8 partial) · [SPHERE_FEATURE_PLAN.md](SPHERE_FEATURE_PLAN.md) (Phase E) · [SPHERE_FAST_STITCH_PLAN.md](SPHERE_FAST_STITCH_PLAN.md)

---

## 1. Problem statement

The current engine assumes:

1. **Pinhole camera** — rays through a single center; only Brown–Conrady k1/k2/p1/p2 (often zero).
2. **Pure rotation** — device rotates about its optical center; IMU quaternion fully describes pose.
3. **Static scene** — no parallax between frames.

Real capture violates all three:

| Real-world effect | What users see | Current handling |
|-------------------|----------------|------------------|
| Translation (hand jitter, walking) | Double edges, ghosts | Global ±10 px micro-shift only |
| Close objects (parallax) | Misaligned overlap, smear | Rotational SSD; rejects or averages |
| Lens distortion / rolling shutter | Curved straight lines, shear | k1–p2 optional; no line-time sync |
| Exposure / white balance drift | Brightness seams, washed blend | 8×8 tile gain (overlap only) |
| Fast path without align | Soft double images | `addFrameFast` splats raw IMU |
| Weight accumulation | Muddy average in overlap | `eqWeight` sum + feather + cap |

**“Washed pixels”** specifically come from: weighted averaging of misaligned content, soft mismatch feathering, edge feather reducing contrast at frame borders, and bilinear splat + downscaled export smoothing high frequencies.

---

## 2. Target model (what we actually need)

### 2.1 Pose per frame

Each capture \(i\) has:

- **Rotation** \(R_i \in SO(3)\) — from IMU + extrinsic + rotational refinement (existing).
- **Translation** \(t_i \in \mathbb{R}^3\) — small but non-zero for handheld; dominates parallax on close geometry.
- **Intrinsics** \(K_i\) — focal length, principal point, distortion; may vary slightly with autofocus / digital zoom.

A pixel \(\mathbf{u}\) in frame \(i\) maps to a 3D ray \(\mathbf{r}_i(\mathbf{u})\) via **unified camera model** (not pure pinhole):

```
undistort(u) → normalized plane (x, y)
optional: rolling-shutter row time τ(y) → R(τ) applied per row
ray = normalize(R_i · K_i^{-1} [x, y, 1]^T)
```

### 2.2 Warp between consecutive frames

For overlap region \(\Omega\), find a mapping from frame \(j\) to frame \(i\):

| Model | DOF | Valid when | Use |
|-------|-----|------------|-----|
| Rotation only | 3 | Infinity, tripod | Current |
| Similarity (rot + uniform scale) | 4 | Small motion, far scene | Quick refine |
| Affine | 6 | Small perspective change | Per-tile default |
| Homography \(H\) | 8 | Planar / far scene | Pairwise align |
| Mesh / TPS control points | 2×N | General parallax | Export quality |

**Key idea (user request):** Estimate \(T_{j \to i}\) (affine or homography) on **overlap**, then **extend** to non-overlapping pixels via a smooth field so the whole frame is consistently warped before splatting into equirect.

---

## 3. Proposed architecture — Phase G

```
                    ┌─────────────────────────────────────┐
  Frame_i + IMU ──► │ G1: Intrinsics & time sync          │
                    │  K, distortion, row-time R(t)       │
                    └──────────────┬──────────────────────┘
                                   ▼
                    ┌─────────────────────────────────────┐
                    │ G2: Pairwise registration           │
                    │  overlap SSD / features → H or A    │
                    └──────────────┬──────────────────────┘
                                   ▼
                    ┌─────────────────────────────────────┐
                    │ G3: Warp field extension            │
                    │  grid affines → TPS / bilateral     │
                    │  warp full frame (incl. non-overlap)│
                    └──────────────┬──────────────────────┘
                                   ▼
                    ┌─────────────────────────────────────┐
                    │ G4: Smart composite (no wash-out)   │
                    │  winner / Laplacian / confidence    │
                    └──────────────┬──────────────────────┘
                                   ▼
                         equirect accumulation
```

Optional **G5** (later): monocular depth → per-ray parallax correction using existing `DepthEstimationProcessor`.

---

## 4. Phase G1 — Camera model & motion sync

**Goal:** Rays match real lens and capture time.

### G1.1 Per-device intrinsics calibration

| Task | Detail |
|------|--------|
| Capture checkerboard / Charuco on each supported device class | Store k1,k2,p1,p2 + focal length + principal point in `LensDistortionStore` |
| Wire non-zero profiles in `SphereSessionController.startSession` | Already plumbed; coeffs default to zero today |
| Validate with `SphereRoundTripE2ETest` at edges | Edge PSNR should improve vs center-only |

**Acceptance:** Straight lines in scene stay straight in rectilinear extract at FOV edges (subjective ≤ 2/5 bend).

### G1.2 Rolling-shutter compensation (optional P1)

Many phone sensors read rows top-to-bottom (~5–15 ms full frame).

| Task | Detail |
|------|--------|
| Record `frameTimestampNanos` + row index \(y\) | Already have timestamp per frame |
| Interpolate IMU quaternion at \(t_0 + y \cdot \Delta t_{\text{row}}\) | Extend `OrientationQuaternionHistory` |
| Apply per-row rotation in `pixelToRay` | Changes ray per pixel, not per frame |

**Acceptance:** Fast horizontal pan: ghosting score improves vs single-quaternion splat (instrumented synthetic test with simulated RS).

### G1.3 Translation prior from IMU (if available)

Accelerometer double-integration is noisy but **visual odometry lite** (affine from G2) can estimate \(t\) magnitude to weight parallax model selection.

---

## 5. Phase G2 — Pairwise frame registration

**Goal:** Find transformation from previous (or reference) frame to current frame in **image plane** before splat.

### G2.1 Overlap detection (existing, extend)

- Sparse grid samples where `eqWeight > 0` (already in `processFrameLocked` Phase 1).
- Build patch correspondences: for each sample, \((\mathbf{u}_{\text{new}}, \mathbf{u}_{\text{ref}})\) in frame coordinates.

### G2.2 Transformation estimation

**Tier A — Native, no OpenCV (mobile-friendly)**

1. **Global affine** 6-DOF search on overlap SSD (extend current micro-shift):
   - Parameterize \(A = \begin{bmatrix} a & b & t_x \\ c & d & t_y \end{bmatrix}\).
   - Coarse grid on \(t_x, t_y, \theta, s\) then refine (similar to rotational search).
2. **Per-tile affine** on 8×8 grid (reuse tile gain grid):
   - Each cell: 6 params minimizing SSD in that cell’s overlap samples.
   - RANSAC-lite: reject cells with SSD > threshold.

**Tier B — Feature-based (Phase E / S8 full)**

1. ORB keypoints in overlap bands (OpenCV module or custom FAST).
2. `findHomography` with RANSAC → \(H_{j \to i}\).
3. Decompose: if \(H\) is “rotation-dominated”, apply as quaternion delta; else use \(H\) directly as 2D warp.

**Tier C — Optical flow seed (medium cost)**

- Lucas–Kanade on overlap pyramids → dense flow in overlap.
- Fit affine/homography to flow vectors; extend field (G3).

### G2.3 When to run

| Path | Registration |
|------|----------------|
| `addFrameFast` (live) | IMU only; optional **single** global affine from last frame if overlap > 30% and budget < 5 ms |
| `refinePending` / `addFrame` (quality) | Full G2 Tier A or B on dirty frames |
| `finishCapture` | Tier B + G3 on all frames; multiband composite |

Store result per frame: `StoredFrame { ..., Affine2x3 warp or Homography3x3, gridWarp[8][8] }`.

---

## 6. Phase G3 — Warp field extension to non-overlapping regions

**Goal:** Apply consistent deformation to the **entire** frame, not only where overlap exists.

### 6.1 Grid of local affines

1. Divide frame into \(G \times G\) cells (e.g. \(8 \times 8\) or \(16 \times 16\)).
2. Cells with overlap samples: solve affine \(A_k\) minimizing \(\sum \| A_k \mathbf{u}_{\text{ref}} - \mathbf{u}_{\text{new}} \|^2\).
3. Cells **without** overlap: **do not leave identity** — interpolate from neighbors.

### 6.2 Extension methods (pick one per quality tier)

| Method | How | Pros | Cons |
|--------|-----|------|------|
| **Bilinear blend of cell affines** | For pixel \(\mathbf{u}\), blend 4 corner \(A_k\) | Fast, C++ friendly | Discontinuities at cell borders |
| **Thin-plate spline (TPS)** | Control points at cell centers with known displacement | Smooth, handles non-overlap | Higher cost; need stable solver |
| **Coarse-to-fine flow propagation** | Solve flow in overlap; diffuse with edge-aware filter (bilateral) | Good for parallax gradients | Needs pyramid + iterations |
| **Homography extrapolation** | Single \(H\) from overlap; apply to all pixels | Simple | Wrong for close foreground objects |

**Recommended default:** 8×8 per-cell affine in overlap + **bilinear interpolation of affine parameters** across full frame (cheap, huge win over global shift). Export pass: optional TPS on 16×16 grid.

### 6.3 Warp-then-splat pipeline

Current: `pixel → ray(IMU) → equirect splat`.

Proposed:

```
for each pixel (fx, fy) in frame i:
    (fx', fy') = warp_i(fx, fy)      // G3 field, relative to reference
    ray = pixelToRay(fx', fy', K, dist, R_row(y))
    splat to equirect with corrected pose
```

Alternative (equivalent for small warps): warp in equirect space only in overlap band — harder to make consistent; prefer **source-space warp** before ray cast.

### 6.4 Reference frame choice

| Strategy | Best for |
|----------|----------|
| Previous frame | Low latency live preview |
| First frame in burst | Stable drift for short pans |
| Sliding window centroid | Long captures; refine in `refinePending` |
| Global bundle (offline) | `finishCapture` only |

---

## 7. Phase G4 — Compositing without washed pixels

**Goal:** Overlap regions stay sharp; no gray averaging of misaligned content.

### 7.1 Root causes of wash-out in current code

| Mechanism | Location | Effect |
|-----------|----------|--------|
| Weighted sum `eqR += color * w` | `splatPixel` | Averages disagreeing colors |
| `MAX_ACCUM_WEIGHT = 3.5` | Phase 4 cap | Caps but still blends |
| Mismatch soft feather | Phase 3 | Reduces weight → pulls toward fewer samples |
| Edge feather 10% | Phase 3 | Low weight at borders |
| Fast path no mismatch check | `doAlign=false` | Misaligned frames always merge |
| Bilinear splat + export downscale | splat + `getEquirect` | Low-pass filter |

### 7.2 Compositing strategies

| Strategy | Description | When |
|----------|-------------|------|
| **Confidence-weighted max** | Store best sample per equirect pixel by alignment SSD; replace if new frame better | Live preview |
| **Winner-take-most** | In overlap, pick frame whose ray is closest to pixel normal (center of frame weight) | Reduces double exposure |
| **Laplacian pyramid blend** | Blend only in narrow seam band after G2 alignment | Export (`finishCapture`) |
| **No accumulate in preview** | Show **latest warped frame** splat only in preview; full accumulate on finish | Eliminates live wash |
| **Exposure normalization per frame** | Match overlap histogram before splat (extend tile gain to full overlap) | Reduces color wash |

**Recommended:**

1. **Live:** After G3 warp, splat with **recency bias** — `w_new > w_old` in overlap instead of equal sum.
2. **Export:** Laplacian pyramid on 32 px seam bands only (not full-image Gaussian — lesson from removed `refineSeams` on finish).

### 7.3 Re-enable quality gates selectively

- Fast path: keep speed but **reject** frames where post-warp SSD still > threshold (don’t merge bad frames).
- Mismatch hard reject on fast path once G2 warp is cheap (< 5 ms).

---

## 8. Phase G5 — Depth-aware stitching (optional, high value)

The app already has `DepthEstimationProcessor` (monocular depth).

| Step | Detail |
|------|--------|
| Per frame, downscaled depth map \(D(\mathbf{u})\) | Relative depth sufficient |
| For correspondence \((\mathbf{u}_i, \mathbf{u}_j)\), triangulate ray pair | Estimate scene distance |
| Parallax correction | Adjust ray on frame \(j\) as function of \(D\) before splat |
| Multi-plane fallback | Split into 2–3 depth layers; stitch each layer separately |

**Acceptance:** Indoor scene with foreground object < 2 m: ghosting ≤ 2/5 vs current ≥ 4/5.

**Cost:** +1 depth inference per frame (GPU/NPU); cache at analysis resolution.

---

## 9. Implementation roadmap

| Phase | Scope | Effort | Depends on | User-visible impact |
|-------|--------|--------|------------|-------------------|
| **G1** | Intrinsics profiles + RS-lite | 1–2 wk | Device samples | Straighter edges, less shear |
| **G2a** | Global + 8×8 affine in overlap | 2 wk | G1 optional | Hand jitter, small translation |
| **G3a** | Bilinear extension of grid affine to full frame | 1 wk | G2a | **Non-overlap regions aligned** |
| **G4a** | Recency-bias composite + hard reject after warp | 1 wk | G2a | **Less washed preview** |
| **G2b** | ORB + homography RANSAC | 2–3 wk | OpenCV module or JNI | Indoor / planar scenes |
| **G3b** | TPS warp on 16×16 grid | 1 wk | G2b | Export quality |
| **G4b** | Laplacian seam band on finish | 1 wk | G2b | Clean seams, no full blur |
| **G5** | Depth-guided parallax | 3–4 wk | Depth processor | Close subjects |

**Suggested order:** G2a → G3a → G4a (biggest ROI for artifacts + wash) → G1 → G2b → G4b → G5.

---

## 10. Data structures (native)

```cpp
struct CellAffine {
    float a, b, tx, c, d, ty;  // 2x3
    float confidence;           // from SSD / inlier count
};

struct FrameWarp {
    CellAffine grid[GRID_H][GRID_W];
    bool hasGlobalH;
    float H[9];                 // optional homography
};

struct StoredFrame {
  // existing: px, w, h, refinedQuat, ...
  FrameWarp warp;               // G2/G3
  float alignSSD;
  int32_t depthMapW, depthMapH; // G5 optional, downscaled
  std::vector<uint16_t> depth;
};
```

New JNI entry points (sketch):

- `nativeEstimateWarp(prevPx, curPx, ...) → FrameWarp`
- `nativeSplatWarpedFrame(px, warp, quat, ...)`
- `nativeCompositeMode(int: ACCUMULATE | RECENCY | PYRAMID)`

---

## 11. Test plan

### 11.1 Synthetic tests (ARM instrumented)

| Test | Scene | Pass criteria |
|------|-------|---------------|
| `translatedFrame_affineRecovery` | Same image shifted (5, 12) px | Recovered tx, ty within 1 px |
| `gridWarp_nonOverlap_extends` | Checkerboard + known affine in corner | Corner PSNR > threshold after warp |
| `parallax_twoPlane` | Two colors at different “depths” simulated by scale change | Ghosting metric < 10% |
| `wash_recencyComposite` | 3 misaligned overlapping frames | Laplacian variance ≥ 70% of single frame |
| `handheld_burst` | Recorded burst from fixture | Round-trip hue accuracy ≥ 75% |

Extend: `SphereMuddinessE2ETest`, `SphereRoundTripE2ETest`, new `SphereWarpE2ETest`.

### 11.2 Regression gates

| Gate | Budget |
|------|--------|
| `addFrameFast` p95 | < 80 ms with G2a global affine |
| `refinePending` per 10 frames | < 3 s on SD845 class |
| Wash score (Laplacian) | No regression vs baseline on aligned synthetic |
| Artifact rate | `SphereArtifactE2ETest` stays green |

### 11.3 Manual QA matrix

| Condition | Action | Pass |
|-----------|--------|------|
| Tripod, outdoor | Slow 360° | Seams ≤ 2/5, no wash |
| Handheld, outdoor | Slow pan | Ghosting ≤ 3/5 |
| Indoor, 1 m furniture | Photo mode step | Parallax acceptable ≤ 3/5 |
| Indoor, 0.5 m wall | Step pan | G5 or G2b required; document fail until shipped |

---

## 12. Risks & mitigations

| Risk | Mitigation |
|------|------------|
| Affine/homography wrong on 3D scenes | Depth layers (G5); reject high SSD frames |
| Performance regression | Tier A native only on fast path; Tier B on refine/finish only |
| OpenCV APK size | Optional `vision_sphere_cv` module; Tier A works without it |
| Overfitting synthetic tests | Record 3 real bursts as golden assets |
| Warp + IMU double correction | Apply warp in source space; IMU sets initial equirect pose only |

---

## 13. Summary

| Today | Planned |
|-------|---------|
| Rotation + global 10 px shift | Per-cell affine + extended warp field (full frame) |
| Pinhole + optional k1–p2 | Calibrated intrinsics + rolling shutter |
| Weighted average → washed overlap | Recency / confidence composite + seam-only pyramid |
| Fast path skips alignment | Cheap affine on fast path; full registration on refine |
| No parallax model | Depth-guided rays (G5) for close range |

The user's **transformation-matrix on overlap extended to non-overlap** maps directly to **G2 (estimate per-cell affine/homography on overlap) + G3 (interpolate warp field to full frame) + warp-then-splat**. That is the highest-ROI path before full depth-aware stitching.

---

## 14. References in repo

| File | Relevance |
|------|-----------|
| `sphere_jni.cpp` | `processFrameLocked`, micro-shift, splat, feather |
| `SphereStitchWorker.kt` | Fast vs refine scheduling |
| `LensDistortionStore.kt` | G1 intrinsics storage |
| `OrientationQuaternionHistory.kt` | G1 row-time sync |
| `DepthEstimationProcessor.kt` | G5 depth maps |
| `SphereImageMetrics.kt` | Wash / sharpness tests |
| `SphereRoundTripE2ETest.kt` | Fidelity baseline |
