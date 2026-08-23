# Capture phone -> Android Auto audio path from logcat (wireless adb).
# USB will be in the car, so this PC must already have wireless adb
# (phone hotspot or same Wi-Fi).
#
# Usage:
#   .\scripts\capture-aa-path-logcat.ps1
#   .\scripts\capture-aa-path-logcat.ps1 -Serial 10.138.210.102:5555
#   .\scripts\capture-aa-path-logcat.ps1 -Clear
#
# What it keeps:
#   CarANC route / MediaSession / aa_path_check
#   AudioFlinger / AudioPolicy / AudioTrack / focus
#   Android Auto / Gearhead / remote_submix / projection
#   MediaSessionStack (HU treating us as music)

param(
    [string]$Serial = "",
    [switch]$Clear
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptDir
Set-Location $projectRoot

$localLogDir = "log"
if (-not (Test-Path $localLogDir)) {
    New-Item -ItemType Directory -Path $localLogDir | Out-Null
}

$adbPath = $null
$possiblePaths = @(
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    "$env:APPDATA\..\Local\Android\Sdk\platform-tools\adb.exe",
    "C:\Android\Sdk\platform-tools\adb.exe"
)
foreach ($p in $possiblePaths) {
    if (Test-Path $p) {
        $adbPath = $p
        break
    }
}
if (-not $adbPath) { $adbPath = "adb" }

function Get-DeviceSerial {
    param([string]$Preferred)
    if ($Preferred -and $Preferred.Trim()) {
        & $adbPath connect $Preferred | Out-Host
        Start-Sleep -Seconds 1
        return $Preferred.Trim()
    }
    $lines = & $adbPath devices
    $found = @()
    foreach ($line in $lines) {
        if ($line -match "^(\S+)\s+device") {
            $found += $Matches[1]
        }
    }
    if ($found.Count -eq 0) {
        # last known phone-hotspot address
        & $adbPath connect "10.138.210.102:5555" | Out-Host
        Start-Sleep -Seconds 1
        $lines = & $adbPath devices
        foreach ($line in $lines) {
            if ($line -match "^(\S+)\s+device") { return $Matches[1] }
        }
        throw "No adb device. Connect wireless first (phone hotspot / Wi-Fi)."
    }
    $wifi = $found | Where-Object { $_ -match ":" } | Select-Object -First 1
    if ($wifi) { return $wifi }
    return $found[0]
}

$serial = Get-DeviceSerial -Preferred $Serial
Write-Host "Device: $serial" -ForegroundColor Green

& $adbPath -s $serial logcat -G 16M | Out-Host
if ($Clear) {
    & $adbPath -s $serial logcat -c
    Write-Host "logcat cleared" -ForegroundColor Yellow
}

$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$outPath = Join-Path $localLogDir "aa_path_logcat_$stamp.txt"
$dumpPath = Join-Path $localLogDir "aa_path_dumpsys_$stamp.txt"

Write-Host "Snapshot dumpsys audio + media_session ..." -ForegroundColor Yellow
@(
    "===== dumpsys audio (start) =====",
    (& $adbPath -s $serial shell dumpsys audio),
    "",
    "===== dumpsys media_session =====",
    (& $adbPath -s $serial shell dumpsys media_session),
    "",
    "===== dumpsys package Android Auto =====",
    (& $adbPath -s $serial shell dumpsys package com.google.android.projection.gearhead | Select-String "versionName|versionCode" | ForEach-Object { $_.Line })
) | Out-File -Encoding utf8 -FilePath $dumpPath

# Tags: app path + mixer + AA host. Silence the rest.
$tagSpec = @(
    "ANCService:V",
    "AncMediaSession:V",
    "AudioRouteManager:V",
    "AudioTrack:V",
    "AudioRecord:I",
    "AudioFlinger:I",
    "AudioPolicy:V",
    "APM_AudioPolicyManager:V",
    "AudioService:I",
    "MediaSessionStack:V",
    "MediaSessionService:I",
    "CarConnection:V",
    "GH.AUDIO:V",
    "GH.Audio:V",
    "Gearhead:V",
    "AndroidAuto:V",
    "CAR.AUDIO:V",
    "CarAudioService:V",
    "Projection:V",
    "remote_submix:V",
    "AAudio:I",
    "AudioFocus:V",
    "*:S"
) -join " "

Write-Host "Writing logcat to $outPath" -ForegroundColor Cyan
Write-Host "Ctrl+C stops capture. Then tell Grok to read this file." -ForegroundColor Cyan
Write-Host "Look for: aa_connected, remote_submix, aaLinkType=projection_submix, AUDIO_BACKEND, aa_path_check, AUDIOFOCUS, MediaSession PLAYING" -ForegroundColor Cyan

& $adbPath -s $serial logcat -v threadtime $tagSpec | Tee-Object -FilePath $outPath
