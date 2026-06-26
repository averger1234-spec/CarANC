@echo off
chcp 65001 >nul
REM CarANC pull-latest-log launcher (English only to avoid encoding issues)
REM Double-click this .bat to run the PowerShell script with bypass

cd /d "%~dp0.."
powershell -ExecutionPolicy Bypass -File "scripts\pull-latest-log.ps1"

echo.
echo Script finished. Press any key to close...
pause >nul