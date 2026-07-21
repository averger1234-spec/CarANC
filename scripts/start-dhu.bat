@echo off
chcp 65001 >nul
cd /d "%~dp0\.."
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-dhu.ps1" %*
if errorlevel 1 (
  echo.
  echo start-dhu failed. See messages above.
  pause
  exit /b 1
)
echo.
pause
