# CarANC direct adb pull latest log to local log/ folder (Windows PowerShell)
# Benefit: No need to manually upload to Google Drive, directly gets the file to ./log/ on your PC
# Prerequisites:
#   - Phone has USB / wireless debugging enabled
#   - App is debug build (release build run-as will fail)
#   - adb can connect

$ErrorActionPreference = "Stop"

$package = "com.example.caranc"
$remoteLogDir = "files/anc_logs"
$localLogDir = "log"

Write-Host "=== CarANC Fast Pull Latest Log ===" -ForegroundColor Cyan

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptDir
Set-Location $projectRoot

if (-not (Test-Path $localLogDir)) {
    New-Item -ItemType Directory -Path $localLogDir | Out-Null
}

# Try to find adb automatically
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

if (-not $adbPath) {
    try {
        $null = adb version
        $adbPath = "adb"
    } catch {
        Write-Host "ERROR: adb command not found! Please run from Android Studio Terminal or ensure platform-tools is in PATH." -ForegroundColor Red
        Write-Host ""
        Write-Host "Press any key to exit..." -ForegroundColor Gray
        $null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
        exit 1
    }
} else {
    Write-Host "Found adb at: $adbPath" -ForegroundColor Green
}

# Check for connected device
$devices = & $adbPath devices | Select-String "device$"
if (-not $devices) {
    Write-Host "ERROR: No connected phone detected." -ForegroundColor Red
    Write-Host "Please:"
    Write-Host "  1. Connect phone via USB and allow USB debugging on the phone"
    Write-Host "  2. Or use wireless debugging: adb connect your-phone-ip:port"
    Write-Host ""
    Write-Host "Press any key to exit..." -ForegroundColor Gray
    $null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
    exit 1
}

Write-Host "Finding the latest anc_session_*.log on the phone ..." -ForegroundColor Yellow

# Use run-as to access debug app's private directory (only works for debug builds)
$latestName = & $adbPath shell "run-as $package sh -c 'ls -1t $remoteLogDir/anc_session_*.log 2>/dev/null | head -1'" 
$latestName = ($latestName -split '[/\\]' | Select-Object -Last 1).Trim()

if (-not $latestName) {
    Write-Host "No log found on phone. Please first press 'Save to Downloads' or 'Share' in the App 'Test Platform', or confirm logging is enabled." -ForegroundColor Red
    exit 1
}

Write-Host "Latest log: $latestName" -ForegroundColor Green

$remotePath = "$remoteLogDir/$latestName"
$localPath = Join-Path $localLogDir $latestName

Write-Host "Pulling to $localPath using run-as + exec-out ..." -ForegroundColor Yellow

# exec-out + run-as cat is more reliable than pulling private dir
& $adbPath exec-out "run-as $package cat '$remotePath'" | Out-File -Encoding utf8 -FilePath $localPath -NoNewline

if (Test-Path $localPath) {
    $size = (Get-Item $localPath).Length
    Write-Host "SUCCESS! File size: $size bytes" -ForegroundColor Green
    Write-Host "Log location: $localPath" -ForegroundColor Green
    Write-Host ""
    Write-Host "You can now open the log in this folder for analysis, or upload to Google Drive for backup." -ForegroundColor Cyan
    Write-Host "Next time for analysis, just tell me: 'look at the latest log' or give me the filename, I will read the local file directly." -ForegroundColor Cyan
} else {
    Write-Host "ERROR: Pull failed" -ForegroundColor Red
}

Write-Host ""
Write-Host "Tip: If you test often, you can add this log/ folder to Google Drive desktop sync for automatic backup." -ForegroundColor Yellow

Write-Host ""
Write-Host "Press any key to close..." -ForegroundColor Gray
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")