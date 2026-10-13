@echo off
chcp 65001 > nul
title Triki Live Logs Monitor

:: Check for ADB in PATH or Android SDK default location
where adb >nul 2>nul
if %errorlevel% equ 0 (
    set ADB=adb
) else if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
    set ADB="%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
) else (
    echo [ERROR] adb.exe not found!
    echo Please make sure Android SDK platform-tools is installed.
    pause
    exit /b 1
)

echo =====================================================================
echo             TRIKI CONTROLLER - LIVE LOGS MONITOR
echo =====================================================================
echo.
echo Device:
%ADB% devices
echo.
echo Streaming live logs (TrikiBleManager, TrikiForegroundService, TrikiEngine)...
echo Press Ctrl + C to stop.
echo =====================================================================
echo.

%ADB% logcat -v color -s TrikiBleManager:V TrikiForegroundService:V TrikiEngine:V TrikiVolume:V
pause
