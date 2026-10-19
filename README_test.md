# Native & instrumented tests

Minimal guide for robotics C++ host tests, 360° stitching on device, and the planar roundtrip workflow.

**Full inventory:** [docs/sphere/SPHERE_TEST_PLAN.md](docs/sphere/SPHERE_TEST_PLAN.md)

## Host-side robotics C++ tests (no device)

Fast desktop build of `app/src/robotics/cpp` test harness. **Does not** run `sphere_jni.cpp` stitching logic — only shared cores and `sphere_math.h`.

**Script:** `scripts/test-robotics-cpp.ps1`  
**Sources:** `app/src/robotics/cpp/test/` (`resample`, `video`, `text_cluster`, `sphere_math`)  
**Build dir:** `app/src/robotics/cpp/build-host/` (gitignored)

```powershell
.\scripts\test-robotics-cpp.ps1
```

Configure CMake only (no run):

```powershell
.\scripts\test-robotics-cpp.ps1 -ConfigureOnly
```

**Requires:** `cmake` on PATH, a C++17 compiler (Visual Studio Build Tools on Windows).

Same test cases also run on device via instrumented `RoboticsCppUnitTest` (debug APK loads `robotics_cpp_tests` JNI library):

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=ab.inque.visiontechai.RoboticsCppUnitTest
```

Not run in sphere CI today; use locally after changing robotics C++ cores or `sphere_math.h`.

## Prerequisites (device tests)

- Physical **ARM** device with USB debugging (recommended). Production planar replay is skipped on emulators.
- `adb` on PATH, device authorized.
- JDK 17, Android SDK, NDK (same as app build).

## JVM unit tests (no device)

```bash
./gradlew test
```

Sphere policy, worker scheduling, and controller logic live in `app/src/test/`.

## Instrumented tests (device)

Build and run all connected tests:

```bash
./gradlew connectedDebugAndroidTest
```

Run one class:

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=ab.inque.visiontechai.SpherePlanarReplayE2ETest
```

CI runs a fixed subset via [.github/workflows/android-sphere-e2e.yml](.github/workflows/android-sphere-e2e.yml) (`SphereStitchE2ETest`, `SphereSaveExportE2ETest`, `VisionWorkflowE2ETest`, etc.).

## Planar roundtrip (generate planars → stitch on device → compare)

End-to-end workflow for validating the native stitcher against a known equirect source:

```
scripts/images/input_images/img_eq4096.jpg   (or scripts/images/img.jpg)
  → extract_rectilinear_views.py     (planar JPEGs + manifest on PC)
  → stitch_planar_roundtrip.py       (sync assets, run device test, pull, compare)
  → SpherePlanarReplayE2ETest        (production worker: capture → DONE → SAVE → polish)
  → scripts/images/roundtrip_output/<subdir>/
```

| Step | Script / class | Role |
|------|----------------|------|
| 1. Generate planars | `scripts/extract_rectilinear_views.py` | Cut overlapping rectilinear views from a source equirect |
| 2. Sync + run | `scripts/stitch_planar_roundtrip.py` | Copy assets to `androidTest`, build APK, run test, pull result |
| 3. Stitch | `SpherePlanarReplayE2ETest` | **Default:** full production worker path (below) |
| 4. Compare | `scripts/compare_equirect.py` | Correlation, PSNR, diff images on PC |

**Default production worker path** (`useProductionWorker: true` in manifest):

1. `SphereStitchWorker.submit` per frame (photo mode)
2. Debounced **capture refine** while idle
3. **DONE** (`finishCapture`) + **pre-finish drain**
4. **SAVE** (`startBackgroundFullExport` → `runFinishGeneration` at **4096×2048**)
5. **Background polish** (intrinsics + light seams after export)

Metrics compare at manifest equirect resolution (e.g. 1024×512); export runs at gallery resolution.

Use `--direct-finish-replay` for the legacy shortcut (direct `runFinishExport` only, no worker).

**Working directory for inputs/outputs:** `scripts/images/`  
**Reference dataset (full sphere):** `scripts/images/input_images/`  
**Synced test assets:** `app/src/androidTest/assets/planar_replay/`  
**Roundtrip output:** `scripts/images/roundtrip_output/<subdir>/`

### Reference dataset — `scripts/images/input_images/`

Canonical offline replay inputs: one full-sphere equirect and 16 rectilinear views cut from it. Used by IMU-degrade sweeps (`--source img_eq4096.jpg`) and synced to `planar_replay` test assets.

| File | Size (typical) | Role |
|------|----------------|------|
| `img_original.jpg` | ~21278×10639 | Full-resolution 2:1 equirect (entire sphere) |
| `img_eq4096.jpg` | 4096×2048 | Downscaled working equirect (gallery / test resolution) |
| `planar_img_eq4096_00_…` … `_15_…` | 1024×1024 each | Rectilinear views extracted from `img_eq4096.jpg` |

**Source chain:**

```
img_original.jpg  (full-res sphere)
       ↓ downscale to 4096 px wide (manual, or stitch script --max-eq-width / --slow-e2e)
img_eq4096.jpg    (4096×2048 truth equirect)
       ↓ extract_rectilinear_views.py --full-sphere
16 × planar_img_eq4096_{index}_az{deg}_{el}.jpg
       ↓ stitch_planar_roundtrip.py → device → stitch back
roundtrip_output/<subdir>/stitched_equirect.jpg
```

Planars are **not** captured on a phone. `extract_rectilinear_views.py` simulates pinhole cameras at fixed look directions and bilinear-samples the equirect. Math matches native `nativeGetRectilinear` in `sphere_jni.cpp` (earth frame: X=East, Y=North, Z=Up; camera looks along +Y before azimuth/elevation).

**16-view layout** (`--full-sphere`, 90° hFOV, ring elevation default 45°):

| Index | Label | Azimuth | Elevation | Example filename suffix |
|-------|-------|---------|-----------|-------------------------|
| 00–05 | horizon ring | 0°, 60°, 120°, 180°, 240°, 300° | 0° | `az000_elp0` … `az300_elp0` |
| 06–09 | upper ring | 45°, 135°, 225°, 315° | +45° | `az045_elp45` … `az315_elp45` |
| 10 | zenith | 0° | +90° | `az000_elp90` |
| 11–14 | lower ring | 45°, 135°, 225°, 315° | −45° | `az045_elm45` … `az315_elm45` |
| 15 | nadir | 0° | −90° | `az000_elm90` |

Six horizon views at 60° steps with 90° FOV give ~30° overlap between neighbors (by design for stitching). Upper/lower rings at ±45° overlap the horizon ring and poles.

**Filename pattern:** `planar_{stem}_{index:02d}_az{az:03.0f}_{el_tag}.jpg` where `el_tag` is signed elevation (`elp45` = +45°, `elm90` = −90°).

**Reproduce `input_images` planars** (exact poses, no jitter — matches IMU sweep / `sweep-fast` assets):

```powershell
python scripts/extract_rectilinear_views.py scripts/images/input_images/img_eq4096.jpg `
  --full-sphere --fov 90 --vfov 90 `
  --width 1024 --height 1024 `
  --no-random-roll --no-random-heading --no-random-elevation `
  -o scripts/images/input_images
```

To downscale `img_original.jpg` to `img_eq4096.jpg` before extract (Python + Pillow):

```powershell
python -c "from PIL import Image; Image.MAX_IMAGE_PIXELS=500_000_000; im=Image.open('scripts/images/input_images/img_original.jpg'); im=im.convert('RGB'); w,h=im.size; nw=4096; nh=max(1,round(h*nw/w)); im.resize((nw,nh),Image.Resampling.LANCZOS).save('scripts/images/input_images/img_eq4096.jpg',quality=95)"
```

Extract also writes `planar_img_eq4096_manifest.json` in the output dir (per-frame `azimuth`, `elevation`, `roll`). `stitch_planar_roundtrip.py` reads this to detect `full_sphere: true` and frame poses.

### Step 1 — Generate planar images

Place a 2:1 equirect in `scripts/images/` (e.g. `img.jpg` or `input_images/img_eq4096.jpg`). Run extract to create planar views with various heading, elevation, and roll offsets:

```bash
python scripts/extract_rectilinear_views.py scripts/images/img.jpg \
  --full-sphere --fov 90 --vfov 90 \
  --width 1024 --height 1024 \
  --random-roll --random-heading --random-elevation \
  --layout-seed 42
```

**`--full-sphere`** extracts the 16 views listed in the reference dataset table above.

**Default jitter** (reproducible via `--layout-seed`; disable with `--no-random-roll`, `--no-random-heading`, `--no-random-elevation` for exact layout poses):

| Option | Default | Effect |
|--------|---------|--------|
| `--random-heading` | on | ±15° azimuth jitter per view |
| `--random-elevation` | on | ±8° elevation jitter |
| `--random-roll` | on | Roll in [−10°, +10°] about the view axis |

**Outputs in `scripts/images/`:**

| File | Description |
|------|-------------|
| `planar_img_00_az004_elm8.jpg` … `planar_img_15_….jpg` | Planar views (`planar_{stem}_{index}_az{deg}_{el}.jpg`) |
| `planar_img_manifest.json` | Per-frame `azimuth`, `elevation`, `roll` (actual poses after jitter) |
| `reference_img_h360_v090.jpg` | Horizontal band reference (partial-sphere runs) |

Projection matches native `nativeGetRectilinear` in `sphere_jni.cpp`. The device test converts manifest poses to stitch quaternions in `SpherePlanarReplayE2ETest`.

**Horizon-only (6 views)** — omit `--full-sphere` and set `--count 6`. The stitch script will **not** auto-extract horizon-only layouts; run extract manually first.

### Step 2 — Stitch on device and compare

After planars exist, run the full roundtrip (sync + device + pull + metrics):

```bash
python scripts/stitch_planar_roundtrip.py --source img_eq4096.jpg --images scripts/images/input_images --device <ADB_SERIAL>
```

On Windows, from repo root (Python 3 + Pillow + numpy):

```powershell
python scripts/stitch_planar_roundtrip.py --source img_eq4096.jpg --images scripts/images/input_images --device <ADB_SERIAL>
```

For IMU-degrade sweeps at 4096 resolution, add `--slow-e2e` or point `--images` at `input_images` where planars already exist.

**What the stitch script does:**

1. Reads `planar_img_manifest.json` if present (detects `full_sphere: true`)
2. Re-extracts planars only with `--reextract` or when manifest options changed
3. Downscales source equirect to **1024 px wide** by default (`img_eq1024.jpg`) — use `--slow-e2e` for 4096
4. Copies planars + equirect + `manifest.json` → `app/src/androidTest/assets/planar_replay/`
5. Builds/installs debug + androidTest APKs
6. Runs `SpherePlanarReplayE2ETest` on the device
7. Pulls `stitched_equirect.jpg` → `scripts/images/roundtrip_output/img_fullsphere/`
8. Runs `compare_equirect.py` → `metrics.json`, `comparison.jpg`, `diff.jpg`

**Regenerate planars and stitch in one command** (only when manifest already has `full_sphere: true`):

```bash
python scripts/stitch_planar_roundtrip.py --source img.jpg --reextract --device <ADB_SERIAL>
```

**If planars are missing**, run Step 1 first. The stitch script fails with “No planars” unless a full-sphere manifest already exists and `--reextract` is set.

### What the Android test does

**Production worker** (default when `productionReplay` and not `--fast-e2e` / `--direct-finish-replay`):

`SphereCaptureController` + `SphereStitchWorker` → debounced capture refine → `finishCapture` → pre-finish drain → `startBackgroundFullExport` (4096×2048) → wait for background intrinsics + light seams → compare at manifest resolution.

**Direct finish** (`--direct-finish-replay` or legacy manifest):

1. `SphereNative.init` at manifest `eqW` × `eqH`
2. `addFrameFast` per frame with manifest quaternions
3. `SphereProductionStitchPipeline.runFinishExport` at manifest resolution

### Useful stitch-script flags

| Flag | Purpose |
|------|---------|
| `--sync-only` | Sync assets only; do not run on device |
| `--skip-device` | Pull + compare only (device already ran the test) |
| `--no-build` | Skip assemble; install + run only |
| `--out-subdir NAME` | Output under `roundtrip_output/NAME/` |
| `--reextract` | Regenerate planar views before sync (full-sphere manifest required) |
| `--direct-finish-replay` | Legacy: skip worker, call `runFinishExport` directly |
| `--fast-e2e` | Legacy fast smoke (&lt;30s, **not** production finish) |
| `--slow-e2e` | 4096-wide equirect on device (longer) |
| `--imu-degrade-deg 5` | Corrupt orientation seeds before `addFrameFast` (IMU error sim) |
| `--run-capture-refine` | Also run mid-capture refine before finish export |

### Roundtrip output files

| File | Description |
|------|-------------|
| `stitched_equirect.jpg` | Device stitch result |
| `reference_equirect.jpg` | Source equirect (or band) used for compare |
| `metrics.json` | Correlation, PSNR, sharpness retention |
| `comparison.jpg` | Side-by-side |
| `diff.jpg` | Absolute difference |
| `sharpness.jpg` | Laplacian sharpness map |

### vs live capture

The roundtrip uses the same **native finish export config** as production (`SphereProductionStitchPipeline.runFinishExport`) when `productionReplay` is true (default).

It does **not** replay the full live path: no CameraX, no IMU tracker, no debounced capture refine or pre-finish idle timing. It batches `addFrameFast` then runs finish export at manifest resolution (typically 1024×512), not the 4096×2048 gallery SAVE path.

Closer matches for **DONE → SAVE** on device: `SphereSaveExportE2ETest`, `SphereSessionSaveE2ETest`. Real camera without SAVE: `VisionWorkflowE2ETest`.

## Troubleshooting

- **INSTALL_FAILED_INSUFFICIENT_STORAGE** on emulator: use a physical device or `adb shell pm trim-caches 2G`.
- **Production replay skipped on emulator:** expected; use ARM hardware or `--fast-e2e` for a quick smoke only.
- **OOM on large suites:** run E2E classes individually, not the full `Sphere*` batch at 2048×1024.
