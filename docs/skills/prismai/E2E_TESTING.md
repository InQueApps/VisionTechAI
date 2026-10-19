# E2E testing — PRISM AI sphere

## Prerequisites

- ARM64 device or API 34 arm64 emulator (x86 is slow / OOM for heavy tests).
- Native libs built: `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`
- Set serial when multiple devices: `$env:ANDROID_SERIAL="emulator-5554"` (PowerShell).

## Run a single test class

```powershell
adb shell am instrument -w `
  -e class "ab.inque.visiontechai.SphereSaveExportE2ETest" `
  ab.inque.prismai.test/androidx.test.runner.AndroidJUnitRunner
```

Gradle (quote `-P` on Windows):

```powershell
./gradlew connectedDebugAndroidTest `
  "-Pandroid.testInstrumentationRunnerArguments.class=ab.inque.visiontechai.SphereSaveExportE2ETest"
```

## Test classes by purpose

| Class | What it validates | Device notes |
|-------|-------------------|--------------|
| `SphereStitchE2ETest` | Basic native stitch | CI |
| `SphereGoldenImageTest` | Checksum regression | CI |
| `SphereSaveExportE2ETest` | DONE → finish export, queued callbacks | 4096 tests skip emulator |
| `SphereSessionSaveE2ETest` | Full UI session inject → SAVE | Skip emulator |
| `SphereFinishE2ETest` | Worker finish correlation / ghost | Stripe gate may fail emulator |
| `SpherePlanarReplayE2ETest` | Production pipeline replay | 300s+; ARM device |
| `SphereRefineOverlapE2ETest` | R1–R7 gates | Run per-method on ARM |
| `SphereRealWorldE2ETest` | Textured fixtures | Tight gates |
| `MainActivityModeSwitchTest` | Mode switch lifecycle | CI |
| `VisionWorkflowE2ETest` | CameraX sharpness | Device-dependent |

## E2E gotchas

1. **Chain `worker.onResult`** — don't replace controller handler or `frameCount` stays 0.
2. **Don't stitch on main thread** — deadlock with worker callbacks.
3. **Test assets:** `InstrumentationRegistry.getInstrumentation().context.assets` (test APK).
4. **Teardown:** `SphereNative.reset()` + `release()` + `System.gc()` in `@After`.
5. **Emulator storage:** if `INSTALL_FAILED_INSUFFICIENT_STORAGE`, wipe data or `adb install -r`.

## Logcat filters

```
adb logcat -s SphereSessionCtrl SphereCaptureCtrl SphereStitchWorker sphere_jni
```

## CI

`.github/workflows/android-sphere-e2e.yml` — only 4 classes today. See [GAPS.md S46](GAPS.md).

## Planar roundtrip (host script)

```bash
python scripts/stitch_planar_roundtrip.py --device <serial> --reextract
```

Compares device stitch output to reference equirect under `scripts/images/`.
