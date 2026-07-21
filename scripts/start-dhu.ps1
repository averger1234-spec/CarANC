# CarANC: Start Android Auto Desktop Head Unit (PC acts as the car head unit)
# Usage (from project root):
#   .\scripts\start-dhu.ps1
#   .\scripts\start-dhu.ps1 -InstallOnly
#   .\scripts\start-dhu.ps1 -NoForward
#
# This is the REAL Android Auto path (protocol + audio routing), NOT an in-app fake switch.
# Phone USB to this PC -> DHU window = "car" UI.

param(
    [switch]$InstallOnly,
    [switch]$NoForward
)

$ErrorActionPreference = "Stop"

Write-Host "=== CarANC: Desktop Head Unit (PC as Android Auto) ===" -ForegroundColor Cyan

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptDir
Set-Location $projectRoot

# --- Locate SDK / adb / DHU ---
$sdkRoots = @(
    $env:ANDROID_HOME,
    $env:ANDROID_SDK_ROOT,
    "$env:LOCALAPPDATA\Android\Sdk",
    "C:\Android\Sdk"
) | Where-Object { $_ -and (Test-Path $_) }

$sdkRoot = $sdkRoots | Select-Object -First 1
if (-not $sdkRoot) {
    Write-Host "ERROR: Android SDK not found. Install Android Studio first." -ForegroundColor Red
    exit 1
}
Write-Host "SDK: $sdkRoot" -ForegroundColor Green

$adbCandidates = @(
    (Join-Path $sdkRoot "platform-tools\adb.exe"),
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)
$adbPath = $adbCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $adbPath) {
    Write-Host "ERROR: adb.exe not found under platform-tools." -ForegroundColor Red
    exit 1
}

$dhuPath = Join-Path $sdkRoot "extras\google\auto\desktop-head-unit.exe"
$sdkmanager = Join-Path $sdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"

function Install-DhuPackage {
    if (-not (Test-Path $sdkmanager)) {
        Write-Host "ERROR: sdkmanager.bat not found at $sdkmanager" -ForegroundColor Red
        Write-Host "Install Android SDK Command-line Tools via Android Studio SDK Manager." -ForegroundColor Yellow
        exit 1
    }
    if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
        $jbr = "C:\Program Files\Android\Android Studio\jbr"
        if (Test-Path "$jbr\bin\java.exe") {
            $env:JAVA_HOME = $jbr
            Write-Host "Auto-set JAVA_HOME=$env:JAVA_HOME" -ForegroundColor Green
        }
    }
    Write-Host "Installing package: extras;google;auto (Android Auto Desktop Head Unit)..." -ForegroundColor Yellow
    & $sdkmanager --install "extras;google;auto"
    if (-not (Test-Path $dhuPath)) {
        Write-Host "ERROR: Install finished but desktop-head-unit.exe still missing:" -ForegroundColor Red
        Write-Host "  $dhuPath" -ForegroundColor Red
        exit 1
    }
    Write-Host "DHU installed: $dhuPath" -ForegroundColor Green
}

if (-not (Test-Path $dhuPath)) {
    Write-Host "DHU not found at: $dhuPath" -ForegroundColor Yellow
    Install-DhuPackage
} else {
    Write-Host "Found DHU: $dhuPath" -ForegroundColor Green
}

if ($InstallOnly) {
    Write-Host "InstallOnly done. Run again without -InstallOnly to start DHU." -ForegroundColor Cyan
    exit 0
}

# --- Device check ---
Write-Host "Checking adb devices..." -ForegroundColor Yellow
& $adbPath start-server | Out-Null
$devicesOut = & $adbPath devices
Write-Host $devicesOut
$deviceLines = $devicesOut | Where-Object { $_ -match "\tdevice$" }
if (-not $deviceLines) {
    Write-Host ""
    Write-Host "ERROR: No phone in 'device' state." -ForegroundColor Red
    Write-Host "1) USB cable (data, not charge-only)" -ForegroundColor Yellow
    Write-Host "2) Phone: enable USB debugging, accept RSA prompt" -ForegroundColor Yellow
    Write-Host "3) Optional: Wireless debugging + adb connect IP:port" -ForegroundColor Yellow
    exit 1
}

if (-not $NoForward) {
    Write-Host "adb forward tcp:5277 tcp:5277 (DHU transport)..." -ForegroundColor Yellow
    & $adbPath forward --remove tcp:5277 2>$null
    & $adbPath forward tcp:5277 tcp:5277
    Write-Host "Forward OK." -ForegroundColor Green
}

Write-Host ""
Write-Host "=== Phone checklist (do these on the phone) ===" -ForegroundColor Cyan
Write-Host "1. Install Google Android Auto from Play Store (if missing)."
Write-Host "2. Open Android Auto app -> tap Version ~10 times -> Developer settings."
Write-Host "3. Enable: Unknown sources"
Write-Host "4. Developer settings -> Start head unit server (or 'Start head unit server' toggle)."
Write-Host "5. Keep USB connected; unlock phone screen."
Write-Host ""
Write-Host "=== CarANC tips ===" -ForegroundColor Cyan
Write-Host "- In TestLogPanel set connectionType to: dhu  (or usb_aa)"
Write-Host "- After AA connects, log should show phase aa_connected / aaConnected=true"
Write-Host "- audioBackend should be AUDIOTRACK_AA_SUBMIX on AA path"
Write-Host "- This is REAL AA protocol. There is NO in-app 'fake AA' switch."
Write-Host ""
Write-Host "Starting Desktop Head Unit..." -ForegroundColor Yellow
Write-Host "Close the DHU window to end the session." -ForegroundColor Gray

$dhuDir = Split-Path -Parent $dhuPath
Push-Location $dhuDir
try {
    # Launch DHU (blocks until window closed on most builds)
    & $dhuPath
} finally {
    Pop-Location
}

Write-Host "DHU exited." -ForegroundColor Cyan
