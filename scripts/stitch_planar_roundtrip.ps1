#!/usr/bin/env pwsh
<#
.SYNOPSIS
  Stitch planar views, pull to scripts/images/roundtrip_output/, compare to img1.jpg.

.EXAMPLE
  .\scripts\stitch_planar_roundtrip.ps1
  .\scripts\stitch_planar_roundtrip.ps1 -DeviceSerial 328bea72
#>
param(
    [string]$ImagesDir,
    [string]$Source = "img1.jpg",
    [string]$DeviceSerial,
    [switch]$SyncOnly,
    [switch]$NoBuild
)

$ErrorActionPreference = "Stop"
$pyArgs = @(
    (Join-Path $PSScriptRoot "stitch_planar_roundtrip.py")
)
if ($ImagesDir) { $pyArgs += @("--images", $ImagesDir) }
$pyArgs += @("--source", $Source)
if ($DeviceSerial) { $pyArgs += @("--device", $DeviceSerial) }
if ($SyncOnly) { $pyArgs += "--sync-only" }
if ($NoBuild) { $pyArgs += "--no-build" }

python @pyArgs
exit $LASTEXITCODE
