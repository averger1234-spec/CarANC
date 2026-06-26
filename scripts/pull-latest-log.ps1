# CarANC 直接從手機 adb pull 最新 log 到本機 log/ 資料夾（Windows PowerShell）
# 優點：不需要手動上傳 Google Drive，下載後直接在本機 ./log/ 裡面
# 前提：
#   - 手機已開啟 USB / 無線偵錯
#   - App 是 debug build（release 版 run-as 會失敗）
#   - adb 可連線

$ErrorActionPreference = "Stop"

$package = "com.example.caranc"
$remoteLogDir = "files/anc_logs"
$localLogDir = "log"

Write-Host "=== CarANC 快速拉取最新 log ===" -ForegroundColor Cyan

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptDir
Set-Location $projectRoot

if (-not (Test-Path $localLogDir)) {
    New-Item -ItemType Directory -Path $localLogDir | Out-Null
}

# 簡單檢查 adb 是否可用
try {
    $null = adb version
} catch {
    Write-Host "❌ 找不到 adb 指令！請用 Android Studio 的 Terminal 執行，或確認 platform-tools 在 PATH。" -ForegroundColor Red
    Write-Host ""
    Write-Host "按任意鍵結束..." -ForegroundColor Gray
    $null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
    exit 1
}

# 確認 adb 裝置
$devices = adb devices | Select-String "device$"
if (-not $devices) {
    Write-Host "❌ 沒有偵測到已連線的手機。" -ForegroundColor Red
    Write-Host "請："
    Write-Host "  1. 用 USB 連線手機，並在手機允許 USB 偵錯"
    Write-Host "  2. 或使用無線偵錯： adb connect 你的手機IP:端口"
    Write-Host ""
    Write-Host "按任意鍵結束..." -ForegroundColor Gray
    $null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
    exit 1
}

Write-Host "正在手機上找最新的 anc_session_*.log ..." -ForegroundColor Yellow

# 使用 run-as 取得 debug app 的私有目錄內容（debug build 才有效）
$latestName = adb shell "run-as $package sh -c 'ls -1t $remoteLogDir/anc_session_*.log 2>/dev/null | head -1'" 
$latestName = $latestName.Trim()

if (-not $latestName) {
    Write-Host "手機上找不到 log。請先在 App「測試平台」按「儲存到下載」或「分享」，或確認 logging 已開啟。" -ForegroundColor Red
    exit 1
}

Write-Host "最新 log：$latestName" -ForegroundColor Green

$remotePath = "$remoteLogDir/$latestName"
$localPath = Join-Path $localLogDir $latestName

Write-Host "正在用 run-as + exec-out 拉取到 $localPath ..." -ForegroundColor Yellow

# exec-out + run-as cat 可以把內容導出來（比 pull 私有目錄更可靠）
adb exec-out "run-as $package cat '$remotePath'" | Out-File -Encoding utf8 -FilePath $localPath -NoNewline

if (Test-Path $localPath) {
    $size = (Get-Item $localPath).Length
    Write-Host "✅ 拉取成功！檔案大小：$size bytes" -ForegroundColor Green
    Write-Host "log 位置：$localPath" -ForegroundColor Green
    Write-Host ""
    Write-Host "現在你可以直接在這個資料夾打開 log 給我分析，或上傳到 Google Drive 備份。" -ForegroundColor Cyan
    Write-Host "下次分析時請告訴我：「看一下最新的 log」或給我檔名，我會直接讀本機檔案。" -ForegroundColor Cyan
} else {
    Write-Host "❌ 拉取失敗" -ForegroundColor Red
}

Write-Host ""
Write-Host "提示：如果經常路測，可以把這個資料夾加入 Google Drive 桌面同步，自動備份。" -ForegroundColor Yellow

Write-Host ""
Write-Host "按任意鍵結束..." -ForegroundColor Gray
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")