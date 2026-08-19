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
scripts/images/img.jpg
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
**Synced test assets:** `app/src/androidTest/assets/planar_replay/`  
**Roundtrip output:** `scripts/images/roundtrip_output/<subdir>/`

### Step 1 — Generate planar images

Place a 2:1 equirect in `scripts/images/` (e.g. `img.jpg`). Run extract to create planar views with various heading, elevation, and roll offsets:

```bash
python scripts/extract_rectilinear_views.py scripts/images/img.jpg \
  --full-sphere --fov 90 --vfov 90 \
  --width 1024 --height 1024 \
  --random-roll --random-heading --random-elevation \
  --layout-seed 42
```

**`--full-sphere`** extracts 16 views: 6 on the horizon, 4 upper ring (+45°) + zenith, 4 lower ring (−45°) + nadir.

**Default jitter** (reproducible via `--layout-seed`):

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
python scripts/stitch_planar_roundtrip.py --source img.jpg --device <ADB_SERIAL>
```

On Windows, from repo root (Python 3 + Pillow + numpy):

```powershell
python scripts/stitch_planar_roundtrip.py --source img.jpg --device <ADB_SERIAL>
```

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
