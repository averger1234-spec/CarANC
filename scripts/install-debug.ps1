# CarANC quick build + install to phone (Windows PowerShell)
# Usage:
#   .\scripts\install-debug.ps1                  # internal debug (default, tuning lab)
#   .\scripts\install-debug.ps1 -Flavor store    # store debug (consumer UI, com.caranc.app)
# Prerequisite: USB / wireless debugging; adb can see the device

param(
    [ValidateSet("internal", "store")]
    [string]$Flavor = "internal"
)

$ErrorActionPreference = "Stop"

Write-Host "=== CarANC Fast Iteration: Build + Install ($Flavor) ===" -ForegroundColor Cyan

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptDir
Set-Location $projectRoot

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
    try {
        $null = adb version
        $adbPath = "adb"
    } catch {
        Write-Host "ERROR: adb command not found!" -ForegroundColor Red
        Write-Host "Open this project in Android Studio Terminal, or add platform-tools to PATH." -ForegroundColor Yellow
        Write-Host ""
        Write-Host "Press any key to exit..." -ForegroundColor Gray
        $null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
        exit 1
    }
} else {
    Write-Host "Found adb at: $adbPath" -ForegroundColor Green
}

if (-not $env:JAVA_HOME) {
    $possibleJbr = @(
        "C:\Program Files\Android\Android Studio\jbr",
        "C:\Program Files\Android\Android Studio\jre"
    )
    foreach ($p in $possibleJbr) {
        if (Test-Path "$p\bin\java.exe") {
            $env:JAVA_HOME = $p
            Write-Host "Auto-set JAVA_HOME to: $env:JAVA_HOME" -ForegroundColor Green
            break
        }
    }
}
if (-not $env:JAVA_HOME) {
    Write-Host "WARNING: JAVA_HOME still not set. Gradle build may fail." -ForegroundColor Yellow
}

$taskName = if ($Flavor -eq "internal") { ":app:assembleInternalDebug" } else { ":app:assembleStoreDebug" }
Write-Host "Building $taskName ..." -ForegroundColor Yellow
& .\gradlew $taskName --console=plain
if ($LASTEXITCODE -ne 0) {
    throw "Gradle build failed with exit code $LASTEXITCODE"
}

$apkPath = "app\build\outputs\apk\$Flavor\debug\app-$Flavor-debug.apk"
if (-not (Test-Path $apkPath)) {
    throw "APK not found at $apkPath, please check if build succeeded"
}

Write-Host "APK found: $apkPath" -ForegroundColor Green
Write-Host "Installing to phone with adb install -r ..." -ForegroundColor Yellow

& $adbPath install -r $apkPath

if ($LASTEXITCODE -eq 0) {
    $pkg = if ($Flavor -eq "store") { "com.caranc.app" } else { "com.example.caranc" }
    Write-Host "SUCCESS: Installed $Flavor debug ($pkg)." -ForegroundColor Green
    Write-Host "Internal = tuning lab (Dev). Store = consumer UI preview." -ForegroundColor Green
} else {
    Write-Host "ERROR: adb install failed. Check phone connected + USB debugging allowed." -ForegroundColor Red
}

Write-Host ""
Write-Host "Press any key to close..." -ForegroundColor Gray
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
