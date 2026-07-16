# E2E testing (instrumented)

Canonical skill: **[Work/skills/android-instrumented-e2e](../../../../skills/android-instrumented-e2e/SKILL.md)**

## Run PRISM AI workflows locally

**Default: emulator** (auto-starts an ARM64 AVD from config if none is running).

```powershell
cd Work\skills\android-instrumented-e2e
.\scripts\e2e-run.ps1 -Config apps\prismai.e2e.json -Suite ui
```

Physical device override:

```powershell
.\scripts\e2e-run.ps1 -Config apps\prismai.e2e.json -Suite ui -PreferPhysical -DeviceSerial <serial>
```

## Run sphere JNI tests (repo `scripts/test.ps1`)

```powershell
# From VisionTechAI repo root
.\scripts\test.ps1 -Instrumented -Suite native

# Individual suites
.\scripts\test.ps1 -Instrumented -Class "ab.inque.visiontechai.SphereRoundTripE2ETest"
.\scripts\test.ps1 -Instrumented -Class "ab.inque.visiontechai.SphereMuddinessE2ETest"
.\scripts\test.ps1 -Instrumented -Class "ab.inque.visiontechai.SphereArtifactE2ETest"
.\scripts\test.ps1 -Instrumented -Class "ab.inque.visiontechai.SpherePerfE2ETest"

# Force emulator if Gradle picks a disconnected physical serial
$env:ANDROID_SERIAL="emulator-5556"
.\gradlew.bat ":app:connectedDebugAndroidTest" `
  "-Pandroid.testInstrumentationRunnerArguments.class=ab.inque.visiontechai.SphereRoundTripE2ETest"
```

### Native suite classes (`scripts/test.ps1 -Suite native`)

| Class | What it proves |
|-------|----------------|
| `SphereStitchE2ETest` | JNI init, add frame, coverage, two-phase fast+refine, golden pipeline |
| `SphereGoldenImageTest` | Synthetic stitch checksum regression |
| `SpherePerfE2ETest` | **Gating** latency budgets (addFrameFast p95, tap p95, finish off main) |
| `SphereStitchDiagnosticTest` | **Non-gating** logcat phase timings — use for profiling |
| `SphereArtifactE2ETest` | Black-line / gap artifacts (dark pixels vs bright neighbors) |
| `SphereMuddinessE2ETest` | Sharpness retention (Laplacian), contrast, refine/seam mud |
| `SphereRoundTripE2ETest` | Stitch → rectilinear extract → PSNR / hue vs source |

**Note:** JNI tests use synthetic bitmaps (gradients, checkerboards), not the emulator camera. They validate projection math and pipeline quality. Live camera + sensors → `VisionWorkflowE2ETest`.

## Workflow coverage (`VisionWorkflowE2ETest`)

| Workflow | Test method |
|----------|-------------|
| Launch | `workflow_launch_mainControlsVisible` |
| Night vision | `workflow_nightVision_toggleAndSlider` |
| IMG ED / COL / HD | `workflow_img_*` |
| ROB OF / SEG / DF | `workflow_rob_*` |
| Text translation | `workflow_textTranslation` |
| Sphere | `workflow_sphere_enterAndExit`, `workflow_sphere_captureUpdatesPreview` |
| Info panel | `workflow_infoPanel_toggle` |
| Gallery | `workflow_gallery_opensSavedMedia` |
| Full walkthrough | `allPrimaryWorkflows_sequentialWalkthrough` |

Config: [e2e/prismai.e2e.json](../../../e2e/prismai.e2e.json)

## Round-trip quality test pattern

To prove stitching places content correctly:

1. Create frame with known hue/pattern at quaternion `q`.
2. `SphereNative.addFrameFast` or `addFrame`.
3. Compute view angles: rotate device forward `(0,0,-1)` by `q`, then `atan2(wx,wy)` / `asin(wz)` — see `SphereRoundTripE2ETest.quatToAzEl`.
4. `getRectilinearBitmap(w, h, az, el, fovV)`.
5. Compare: PSNR (center crop), `dominantHueAccuracy`, or `SphereImageMetrics.laplacianVariance`.

**Pitfall:** Z-axis azimuth spins do not change where the camera points; use X/Y rotations to aim at N/E/S/W on the sphere.
