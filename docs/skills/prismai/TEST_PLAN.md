# Test plan — sphere regression matrix

Thresholds are defined in `SphereRefinePolicy.kt`. Update tests when changing policy.

## Fast path (L1) — run first on any native change

| ID | Test | Gate |
|----|------|------|
| R1 | `fastRefine_noRegression` | ghost Δ ≤ 0.02, corr ≥ 0.88 |
| A1–A4 | Fast align + preview + global | SSD, reject, IMU parity |
| E1–E4 | Exposure + seams + stored gains | luminance, sharpness |

## Refine / global (L2)

| ID | Test | Gate | Status |
|----|------|------|--------|
| R2 | IMU-parity | no regression | ✅ |
| R3 | global, 4 noisy frames | ghost Δ ≤ 0.02 | ✅ |
| R4 | global, 8 frames, 2× refine | boundary corr | ✅ |
| **R5** | **periodic mid-capture** | — | ❌ not implemented |
| R6 | global + `refineSeams()` | corr ≥ 0.95 | ✅ |
| R7 | global, 2 frames | ghost Δ ≤ 0.12 | ✅ |
| G1 | sparse graph | overlap SSD ↓ | ✅ |

## Finish / export (SAVE path)

| ID | Test | Gate | Status |
|----|------|------|--------|
| S38 | `SphereFinishE2ETest` | corr ≥ 0.75, ghost Δ ≤ 0.05 | 🟡 emulator |
| — | `SphereSaveExportE2ETest` | export bitmap non-null, dims | ✅ emulator |
| — | `SphereSessionSaveE2ETest` | UI SAVE workflow | device only |
| RW1–RW5 | `SphereRealWorldE2ETest` | ghost Δ ≤ 0.05, band corr ≥ 0.90 | 🟡 |

## Lifecycle (unit)

| Test | Covers |
|------|--------|
| `SphereSessionControllerSaveTest` | PREVIEW guard, session retention on dismiss |
| `SphereCaptureControllerTest` | Queued export callbacks |
| `SphereRefinePolicyTest` | Periodic refine threshold |

## Recommended run order

1. `./gradlew test` (JVM)
2. Native fast change → R1 class method only
3. Global/finish change → A4 + R7 + `SphereFinishE2ETest`
4. Lifecycle/export → `SphereSessionControllerSaveTest` + `SphereSaveExportE2ETest`
5. Before release → full ARM device suite + planar roundtrip

## Success metrics (product)

| Metric | Target | Current |
|--------|--------|---------|
| Seam visibility (1–5) | ≤ 2 tripod | Manual QA |
| Frame reject rate | < 10% | Not instrumented (S25) |
| `addFrame` p95 | < 50 ms | Not instrumented (S25) |
| Coverage vs holes | Overlay + 85% gate | ✅ |
