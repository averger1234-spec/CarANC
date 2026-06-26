@echo off
REM CarANC install-debug 方便啟動器
REM 直接雙擊這個 .bat 即可用 PowerShell 執行 install-debug.ps1（自動 bypass 執行原則 + 暫停）

cd /d "%~dp0.."
powershell -ExecutionPolicy Bypass -File "scripts\install-debug.ps1"

echo.
echo 腳本已結束，按任意鍵關閉視窗...
pause >nul