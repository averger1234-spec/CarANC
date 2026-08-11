# Build Play-ready AAB for STORE flavor (release).
# Usage: .\scripts\bundle-store-release.ps1
#
# For real Play upload signing:
#   1. Create upload keystore (once)
#   2. Copy keystore.properties.example → keystore.properties (project root)
#   3. Fill storeFile / passwords / keyAlias
# Without keystore.properties, AAB is still built but signed with debug key (NOT for Play).

$ErrorActionPreference = "Stop"

Write-Host "=== CarANC bundleStoreRelease ===" -ForegroundColor Cyan

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptDir
Set-Location $projectRoot

if (-not $env:JAVA_HOME) {
    foreach ($p in @(
        "C:\Program Files\Android\Android Studio\jbr",
        "C:\Program Files\Android\Android Studio\jre"
    )) {
        if (Test-Path "$p\bin\java.exe") {
            $env:JAVA_HOME = $p
            Write-Host "JAVA_HOME=$env:JAVA_HOME" -ForegroundColor Green
            break
        }
    }
}

$hasKeystore = Test-Path "keystore.properties"
if (-not $hasKeystore) {
    Write-Host "NOTE: keystore.properties not found — release will use debug signing (local only)." -ForegroundColor Yellow
    Write-Host "Copy keystore.properties.example to keystore.properties for Play upload key." -ForegroundColor Yellow
} else {
    Write-Host "Found keystore.properties — will use release signingConfig if valid." -ForegroundColor Green
}

& .\gradlew :app:bundleStoreRelease --console=plain
if ($LASTEXITCODE -ne 0) {
    throw "bundleStoreRelease failed"
}

$aab = "app\build\outputs\bundle\storeRelease\app-store-release.aab"
if (-not (Test-Path $aab)) {
    # AGP path variants
    $candidates = Get-ChildItem -Path "app\build\outputs\bundle" -Recurse -Filter "*.aab" -ErrorAction SilentlyContinue
    if ($candidates) {
        $aab = $candidates | Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
    }
}

if (Test-Path $aab) {
    $item = Get-Item $aab
    Write-Host "SUCCESS: AAB ready" -ForegroundColor Green
    Write-Host "  Path: $($item.FullName)"
    Write-Host "  Size: $($item.Length) bytes"
    Write-Host "  applicationId: com.caranc.app (store flavor)"
    Write-Host "Upload this AAB to Play Console (internal test track first)." -ForegroundColor Cyan
} else {
    Write-Host "ERROR: AAB not found under app/build/outputs/bundle" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "Press any key to close..." -ForegroundColor Gray
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
