@echo off
chcp 65001 >nul
cd /d "%~dp0.."
powershell -ExecutionPolicy Bypass -File "scripts\capture-aa-path-logcat.ps1"
echo.
echo Script finished. Press any key to close...
pause >nul
