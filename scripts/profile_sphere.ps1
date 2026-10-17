# Run Sphere stitch profile instrumentation tests and stream SphereProfile logcat.
# Usage: .\scripts\profile_sphere.ps1 [-Device SERIAL] [-Test profile_featureMatcher_isolated]

param(
    [string]$Device = "",
    [string]$Test = "profile_fastPathAndFinishExport"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

$adbArgs = @()
if ($Device) { $adbArgs += "-s", $Device }

Write-Host "Building debug + androidTest APKs..."
& .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Installing..."
& adb @adbArgs install -r "app\build\outputs\apk\debug\app-debug.apk"
& adb @adbArgs install -r "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"

Write-Host "Clearing logcat and starting SphereProfile capture..."
& adb @adbArgs logcat -c
$logJob = Start-Job -ScriptBlock {
    param($adb, $extra)
    & adb @extra logcat -s SphereProfile
} -ArgumentList (Get-Command adb).Source, $adbArgs

$class = "ab.inque.visiontechai.SphereStitchProfileE2ETest#$Test"
Write-Host "Running instrumentation: $class"
& adb @adbArgs shell am instrument -w -e class $class `
    ab.inque.prismai.test/androidx.test.runner.AndroidJUnitRunner

Receive-Job $logJob -Wait
Remove-Job $logJob -Force

Write-Host "Done. Filter locally: adb logcat -s SphereProfile"
