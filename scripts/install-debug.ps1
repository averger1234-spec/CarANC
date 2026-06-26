# CarANC quick build + install to phone (Windows PowerShell)
# Usage: Run from project root: .\scripts\install-debug.ps1
# Prerequisite: Phone has USB debugging (or wireless debugging) enabled, and adb can see the device

$ErrorActionPreference = "Stop"

Write-Host "=== CarANC Fast Iteration: Build + Install ===" -ForegroundColor Cyan

# Change to project root (in case script is run from elsewhere)
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptDir
Set-Location $projectRoot

# Try to find adb automatically (common when double-clicking .bat from Explorer without AS env)
$adbPath = $null
$possiblePaths = @(
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    "$env:APPDATA\..\Local\Android\Sdk\platform-tools\adb.exe",
    "C:\Android\Sdk\platform-tools\adb.exe",
    "$env:ProgramFiles\Android\Android Studio\platform-tools\adb.exe"
)
foreach ($p in $possiblePaths) {
    if (Test-Path $p) {
        $adbPath = $p
        break
    }
}

if (-not $adbPath) {
    # Fallback: try if 'adb' is already in PATH
    try {
        $null = adb version
        $adbPath = "adb"
    } catch {
        Write-Host "ERROR: adb command not found!" -ForegroundColor Red
        Write-Host "Android Studio platform-tools not in PATH." -ForegroundColor Yellow
        Write-Host "Best fix: Open this project in Android Studio, go to bottom 'Terminal' tab, and run the script from there." -ForegroundColor Yellow
        Write-Host "It will automatically have adb available." -ForegroundColor Yellow
        Write-Host ""
        Write-Host "Alternatively, add your SDK platform-tools folder to system PATH." -ForegroundColor Yellow
        Write-Host ""
        Write-Host "Press any key to exit..." -ForegroundColor Gray
        $null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
        exit 1
    }
} else {
    Write-Host "Found adb at: $adbPath" -ForegroundColor Green
}

# Auto-detect JAVA_HOME from Android Studio's bundled JBR (JBR) if not set
# This fixes the common "JAVA_HOME is not set" error when double-clicking .bat from Explorer
if (-not $env:JAVA_HOME) {
    $possibleJbr = @(
        "C:\Program Files\Android\Android Studio\jbr",
        "$env:LOCALAPPDATA\Android\Sdk",  # fallback, though JBR is usually with AS install
        "C:\Program Files\Android\Android Studio\jre"
    )
    foreach ($p in $possibleJbr) {
        if (Test-Path "$p\bin\java.exe") {
            $env:JAVA_HOME = $p
            Write-Host "Auto-set JAVA_HOME to: $env:JAVA_HOME (Android Studio JBR)" -ForegroundColor Green
            break
        }
    }
}
if (-not $env:JAVA_HOME) {
    Write-Host "WARNING: JAVA_HOME still not set. Gradle build may fail." -ForegroundColor Yellow
    Write-Host "Recommendation: Open the project in Android Studio and run the script from its Terminal tab (it sets up the env)." -ForegroundColor Yellow
}

Write-Host "Building debug APK (assembleDebug)..." -ForegroundColor Yellow
& .\gradlew :app:assembleDebug --console=plain

$apkPath = "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apkPath)) {
    throw "APK not found at $apkPath, please check if build succeeded"
}

Write-Host "APK found: $apkPath" -ForegroundColor Green
Write-Host "Installing to phone with adb install -r ..." -ForegroundColor Yellow

& $adbPath install -r $apkPath

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