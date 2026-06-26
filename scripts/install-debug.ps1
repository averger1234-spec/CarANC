# CarANC quick build + install to phone (Windows PowerShell)
# Usage: Run from project root: .\scripts\install-debug.ps1
# Prerequisite: Phone has USB debugging (or wireless debugging) enabled, and adb can see the device

$ErrorActionPreference = "Stop"

Write-Host "=== CarANC Fast Iteration: Build + Install ===" -ForegroundColor Cyan

# Change to project root (in case script is run from elsewhere)
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptDir
Set-Location $projectRoot

# Quick check if adb is available
try {
    $null = adb version
} catch {
    Write-Host "ERROR: adb command not found!" -ForegroundColor Red
    Write-Host "Please make sure Android Studio is installed and platform-tools is in PATH (or run this script from Android Studio's Terminal)." -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Press any key to exit..." -ForegroundColor Gray
    $null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
    exit 1
}

Write-Host "Building debug APK (assembleDebug)..." -ForegroundColor Yellow
& .\gradlew :app:assembleDebug --console=plain

$apkPath = "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apkPath)) {
    throw "APK not found at $apkPath, please check if build succeeded"
}

Write-Host "APK found: $apkPath" -ForegroundColor Green
Write-Host "Installing to phone with adb install -r ..." -ForegroundColor Yellow

adb install -r $apkPath

if ($LASTEXITCODE -eq 0) {
    Write-Host "SUCCESS: Installation complete! CarANC on your phone is now the latest debug version." -ForegroundColor Green
    Write-Host "You can now open the app on your phone and start testing." -ForegroundColor Green
} else {
    Write-Host "ERROR: adb install failed. Please check: 1. Phone is connected 2. USB debugging allowed 3. adb devices shows your phone" -ForegroundColor Red
}

Write-Host ""
Write-Host "Tip: Strongly recommend enabling 'Wireless debugging' in Developer Options on your phone so you don't need USB cable anymore." -ForegroundColor Cyan

Write-Host ""
Write-Host "Press any key to close..." -ForegroundColor Gray
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")