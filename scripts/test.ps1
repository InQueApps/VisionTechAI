#!/usr/bin/env pwsh
<#
.SYNOPSIS
  Run unit tests, instrumented tests, or both on the emulator.

.EXAMPLE
  .\scripts\test.ps1                    # all tests (unit + instrumented on emulator)
  .\scripts\test.ps1 -Unit              # JVM unit tests only
  .\scripts\test.ps1 -Instrumented      # instrumented on emulator only
  .\scripts\test.ps1 -Suite smoke       # specific suite on emulator
  .\scripts\test.ps1 -Class "ab.inque.visiontechai.VisionWorkflowE2ETest"
#>
param(
    [switch]$Unit,
    [switch]$Instrumented,
    [ValidateSet("smoke", "ui", "native", "all")]
    [string]$Suite,
    [string]$Class,
    [string]$DeviceSerial,
    [switch]$NoBuild,
    [switch]$NoEmulatorStart
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$SDK = "C:\Users\inque\AppData\Local\Android\Sdk"
$ADB = Join-Path $SDK "platform-tools\adb.exe"
$EMULATOR = Join-Path $SDK "emulator\emulator.exe"

$Suites = @{
    smoke  = @("ab.inque.visiontechai.ExampleInstrumentedTest")
    ui     = @("ab.inque.visiontechai.MainActivityModeSwitchTest",
               "ab.inque.visiontechai.VisionWorkflowE2ETest")
    native = @("ab.inque.visiontechai.SphereStitchE2ETest",
               "ab.inque.visiontechai.SphereGoldenImageTest",
               "ab.inque.visiontechai.SpherePerfE2ETest",
               "ab.inque.visiontechai.SphereArtifactE2ETest",
               "ab.inque.visiontechai.SphereMuddinessE2ETest")
    all    = @("ab.inque.visiontechai.ExampleInstrumentedTest",
               "ab.inque.visiontechai.MainActivityModeSwitchTest",
               "ab.inque.visiontechai.VisionWorkflowE2ETest",
               "ab.inque.visiontechai.SphereStitchE2ETest",
               "ab.inque.visiontechai.SphereGoldenImageTest",
               "ab.inque.visiontechai.SpherePerfE2ETest",
               "ab.inque.visiontechai.SphereArtifactE2ETest",
               "ab.inque.visiontechai.SphereMuddinessE2ETest")
}

function Get-Emulator {
    $devices = & $ADB devices 2>$null | Select-Object -Skip 1
    foreach ($line in $devices) {
        if ($line -match '^(emulator-\d+)\s+device') {
            return $Matches[1]
        }
    }
    return $null
}

function Get-AnyDevice {
    $devices = & $ADB devices 2>$null | Select-Object -Skip 1
    foreach ($line in $devices) {
        if ($line -match '^(\S+)\s+device') {
            return $Matches[1]
        }
    }
    return $null
}

function Start-Emulator {
    $avds = @(& $EMULATOR -list-avds 2>$null) | Where-Object { $_ }
    if ($avds.Count -eq 0) {
        throw "No AVDs found. Create one in Android Studio Device Manager."
    }
    $avd = $avds[0]
    Write-Host "  Starting AVD: $avd" -ForegroundColor Cyan
    Start-Process -FilePath $EMULATOR -ArgumentList @("-avd", $avd, "-no-snapshot-save") -WindowStyle Normal
    $deadline = (Get-Date).AddSeconds(120)
    do {
        Start-Sleep -Seconds 3
        $emu = Get-Emulator
        if ($emu) {
            & $ADB -s $emu wait-for-device | Out-Null
            $boot = ""
            while ($boot -ne "1" -and (Get-Date) -lt $deadline) {
                $boot = (& $ADB -s $emu shell getprop sys.boot_completed 2>$null).Trim()
                Start-Sleep -Seconds 2
            }
            if ($boot -eq "1") {
                Write-Host "  Emulator ready: $emu" -ForegroundColor Green
                return $emu
            }
        }
    } while ((Get-Date) -lt $deadline)
    throw "Emulator did not boot within 120s."
}

function Select-Device {
    if ($DeviceSerial) { return $DeviceSerial }
    $emu = Get-Emulator
    if ($emu) { return $emu }
    if (-not $NoEmulatorStart) {
        Write-Host "[*] No emulator running. Starting one..." -ForegroundColor Yellow
        return Start-Emulator
    }
    $dev = Get-AnyDevice
    if ($dev) {
        Write-Warning "No emulator found, falling back to device: $dev"
        return $dev
    }
    throw "No devices connected and -NoEmulatorStart was set."
}

# Determine what to run
$runUnit = $Unit -or (-not $Unit -and -not $Instrumented -and -not $Suite -and -not $Class)
$runInstrumented = $Instrumented -or $Suite -or $Class -or (-not $Unit -and -not $Instrumented -and -not $Suite -and -not $Class)

Write-Host ""
Write-Host "==========================================" -ForegroundColor Green
Write-Host "  PRISM AI Test Runner" -ForegroundColor Green
Write-Host "==========================================" -ForegroundColor Green
Write-Host ""

# --- Unit Tests ---
if ($runUnit) {
    Write-Host "[UNIT] Running JVM unit tests..." -ForegroundColor Cyan
    Push-Location $ProjectRoot
    try {
        & .\gradlew.bat :app:testDebugUnitTest --no-daemon
        if ($LASTEXITCODE -ne 0) {
            Write-Host "[UNIT] FAILED" -ForegroundColor Red
        } else {
            Write-Host "[UNIT] PASSED" -ForegroundColor Green
        }
    } finally { Pop-Location }
    Write-Host ""
}

# --- Instrumented Tests ---
if ($runInstrumented) {
    $serial = Select-Device
    Write-Host "[E2E] Target: $serial" -ForegroundColor Cyan

    # Resolve classes
    if ($Class) {
        $classes = $Class
    } elseif ($Suite) {
        $classes = $Suites[$Suite] -join ","
    } else {
        $classes = $Suites["ui"] -join ","
    }

    Write-Host "[E2E] Classes: $classes" -ForegroundColor Cyan
    Write-Host ""

    $env:ANDROID_SERIAL = $serial
    Push-Location $ProjectRoot
    try {
        Write-Host "[E2E] Cleaning old install..." -ForegroundColor DarkGray
        & $ADB -s $serial uninstall ab.inque.prismai 2>$null | Out-Null
        & $ADB -s $serial uninstall ab.inque.visiontechai.test 2>$null | Out-Null

        Write-Host "[E2E] Disabling animations on emulator..." -ForegroundColor DarkGray
        & $ADB -s $serial shell settings put global window_animation_scale 0 2>$null
        & $ADB -s $serial shell settings put global transition_animation_scale 0 2>$null
        & $ADB -s $serial shell settings put global animator_duration_scale 0 2>$null

        if (-not $NoBuild) {
            Write-Host "[E2E] Building debug APKs..." -ForegroundColor Cyan
            & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
            if ($LASTEXITCODE -ne 0) { throw "Build failed." }

            Write-Host "[E2E] Installing APKs..." -ForegroundColor Cyan
            & .\gradlew.bat :app:installDebug :app:installDebugAndroidTest --no-daemon
            if ($LASTEXITCODE -ne 0) { throw "Install failed." }
        }

        Write-Host "[E2E] Granting permissions via adb..." -ForegroundColor DarkGray
        @("android.permission.CAMERA",
          "android.permission.RECORD_AUDIO",
          "android.permission.READ_MEDIA_IMAGES",
          "android.permission.READ_MEDIA_VIDEO",
          "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
          "android.permission.POST_NOTIFICATIONS",
          "android.permission.READ_EXTERNAL_STORAGE",
          "android.permission.WRITE_EXTERNAL_STORAGE"
        ) | ForEach-Object {
            & $ADB -s $serial shell pm grant ab.inque.prismai $_ 2>$null | Out-Null
        }

        Write-Host "[E2E] Running instrumented tests..." -ForegroundColor Cyan
        & .\gradlew.bat :app:connectedDebugAndroidTest `
            "-Pandroid.testInstrumentationRunnerArguments.class=$classes" `
            --no-daemon
        if ($LASTEXITCODE -ne 0) {
            Write-Host "[E2E] FAILED" -ForegroundColor Red
        } else {
            Write-Host "[E2E] PASSED" -ForegroundColor Green
        }
    } finally {
        Pop-Location
        Remove-Item Env:ANDROID_SERIAL -ErrorAction SilentlyContinue
    }
}

Write-Host ""
Write-Host "Done." -ForegroundColor Green
