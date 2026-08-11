# Prepare / launch Windows → iPhone install of CarANC debug IPA via Sideloadly.
# Usage: .\scripts\install-ios-sideloadly.ps1
#
# Prerequisites (already installable via winget):
#   winget install Apple.AppleMobileDeviceSupport
#   winget install iOSGods.Sideloadly
#
# You still need: USB cable + trust computer + Apple ID in Sideloadly UI.

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptDir
$ipa = Join-Path $projectRoot "dist\CarANC-ios-kmp-debug.ipa"

Write-Host "=== CarANC iPhone install (Windows + Sideloadly) ===" -ForegroundColor Cyan
Write-Host "Project: $projectRoot"

if (-not (Test-Path $ipa)) {
    Write-Host "ERROR: IPA not found: $ipa" -ForegroundColor Red
    Write-Host "Run: git pull   then ensure dist/CarANC-ios-kmp-debug.ipa exists." -ForegroundColor Yellow
    pause
    exit 1
}

$item = Get-Item $ipa
Write-Host "IPA: $($item.FullName)" -ForegroundColor Green
Write-Host "Size: $([math]::Round($item.Length/1MB, 2)) MB  Modified: $($item.LastWriteTime)"

# Find Sideloadly
$sideloadly = $null
$candidates = @(
    "$env:LOCALAPPDATA\Sideloadly\Sideloadly.exe",
    "$env:ProgramFiles\Sideloadly\Sideloadly.exe",
    "${env:ProgramFiles(x86)}\Sideloadly\Sideloadly.exe",
    "C:\Sideloadly\Sideloadly.exe"
)
foreach ($p in $candidates) {
    if (Test-Path $p) { $sideloadly = $p; break }
}
if (-not $sideloadly) {
    $found = Get-ChildItem -Path "$env:LOCALAPPDATA","$env:ProgramFiles","${env:ProgramFiles(x86)}" -Recurse -Filter "Sideloadly.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($found) { $sideloadly = $found.FullName }
}

if (-not $sideloadly) {
    Write-Host "Sideloadly not found. Installing via winget..." -ForegroundColor Yellow
    winget install --id iOSGods.Sideloadly -e --accept-package-agreements --accept-source-agreements
    foreach ($p in $candidates) {
        if (Test-Path $p) { $sideloadly = $p; break }
    }
}

if (-not $sideloadly) {
    Write-Host "ERROR: Sideloadly still not found. Install from https://sideloadly.io/" -ForegroundColor Red
    pause
    exit 1
}

Write-Host "Sideloadly: $sideloadly" -ForegroundColor Green

# Copy IPA path to clipboard for easy paste
try {
    Set-Clipboard -Value $item.FullName
    Write-Host "IPA path copied to clipboard." -ForegroundColor Green
} catch {
    Write-Host "Clipboard copy skipped." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Next steps (manual in Sideloadly UI):" -ForegroundColor Cyan
Write-Host "  1. Connect iPhone via USB and tap Trust on the phone"
Write-Host "  2. In Sideloadly: select your iPhone"
Write-Host "  3. IPA field: paste path (already in clipboard) or drag:"
Write-Host "     $($item.FullName)"
Write-Host "  4. Apple ID: same account used when Mac signed the IPA (recommended)"
Write-Host "  5. Click Start and wait"
Write-Host "  6. On iPhone: Settings > General > VPN & Device Management > Trust developer"
Write-Host "  7. Open CarANC; grant Mic + Location when asked"
Write-Host ""
Write-Host "Note: Development IPA usually expires in ~7 days; then reinstall from a fresh Mac-built IPA." -ForegroundColor Yellow
Write-Host ""

Start-Process -FilePath $sideloadly
Write-Host "Sideloadly launched."
Write-Host "Press any key to close this window..."
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
