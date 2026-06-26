# CarANC 快速建置 + 安裝到手機（Windows PowerShell）
# 使用方式：在專案根目錄執行 .\scripts\install-debug.ps1
# 前提：手機已開啟 USB 偵錯（或無線偵錯），並 adb devices 可看到裝置

$ErrorActionPreference = "Stop"

Write-Host "=== CarANC 快速迭代：Build + Install ===" -ForegroundColor Cyan

# 切到專案根（如果從其他地方執行）
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptDir
Set-Location $projectRoot

# 簡單檢查 adb 是否可用
try {
    $null = adb version
} catch {
    Write-Host "❌ 找不到 adb 指令！" -ForegroundColor Red
    Write-Host "請確認 Android Studio 已安裝，且已把 platform-tools 加到 PATH（或直接用 Android Studio 的 Terminal 執行這個腳本）。" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "按任意鍵結束..." -ForegroundColor Gray
    $null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
    exit 1
}

Write-Host "正在組建 debug APK（assembleDebug）..." -ForegroundColor Yellow
& .\gradlew :app:assembleDebug --console=plain

$apkPath = "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apkPath)) {
    throw "找不到 APK：$apkPath，請確認 build 成功"
}

Write-Host "找到 APK：$apkPath" -ForegroundColor Green
Write-Host "正在用 adb install -r 覆蓋安裝到手機..." -ForegroundColor Yellow

adb install -r $apkPath

if ($LASTEXITCODE -eq 0) {
    Write-Host "✅ 安裝完成！手機上的 CarANC 已更新為最新 debug 版。" -ForegroundColor Green
    Write-Host "現在可以直接在手機上開 App 跑測試。" -ForegroundColor Green
} else {
    Write-Host "❌ adb install 失敗，請確認：1. 手機已連線 2. 允許 USB 偵錯 3. adb devices 有顯示" -ForegroundColor Red
}

Write-Host ""
Write-Host "小提醒：強烈建議在手機「開發人員選項」開啟「無線偵錯」，以後可以不用插線。" -ForegroundColor Cyan

Write-Host ""
Write-Host "按任意鍵結束..." -ForegroundColor Gray
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")