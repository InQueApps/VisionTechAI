#!/usr/bin/env pwsh
<#
.SYNOPSIS
  Build and run host-side Google Test suite for app/src/robotics/cpp.

.EXAMPLE
  .\scripts\test-robotics-cpp.ps1
  .\scripts\test-robotics-cpp.ps1 -ConfigureOnly
#>
param(
    [switch]$ConfigureOnly
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$CppDir = Join-Path $ProjectRoot "app\src\robotics\cpp"
$BuildDir = Join-Path $CppDir "build-host"

if (-not (Get-Command cmake -ErrorAction SilentlyContinue)) {
    throw "cmake not found on PATH. Install CMake or use Android Studio SDK tools."
}

New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null

Push-Location $BuildDir
try {
    if (Test-Path CMakeCache.txt) { Remove-Item CMakeCache.txt -Force }
    cmake .. -DCMAKE_BUILD_TYPE=Debug
    cmake --build . --config Debug --target robotics_cpp_tests
    if (-not $ConfigureOnly) {
        $exe = Join-Path $BuildDir "Debug\robotics_cpp_tests.exe"
        if (-not (Test-Path $exe)) { $exe = Join-Path $BuildDir "robotics_cpp_tests.exe" }
        if (-not (Test-Path $exe)) { throw "robotics_cpp_tests executable not found under $BuildDir" }
        & $exe
        if ($LASTEXITCODE -ne 0) { throw "robotics_cpp_tests failed with exit code $LASTEXITCODE" }
    }
} finally {
    Pop-Location
}

Write-Host "robotics C++ host tests OK"
