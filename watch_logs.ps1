# Triki Live Logs Monitor PowerShell Script
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$Host.UI.RawUI.WindowTitle = "Triki Live Logs Monitor"

$adb = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adb) {
    $sdkAdb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $sdkAdb) {
        $adb = $sdkAdb
    } else {
        Write-Host "Error: adb.exe not found!" -ForegroundColor Red
        pause
        exit
    }
} else {
    $adb = $adb.Source
}

Write-Host "=====================================================================" -ForegroundColor Cyan
Write-Host "            TRIKI CONTROLLER - LIVE LOGS MONITOR" -ForegroundColor Green
Write-Host "=====================================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Connected devices:" -ForegroundColor Yellow
& $adb devices
Write-Host ""
Write-Host "Streaming logs... Press Ctrl + C to exit." -ForegroundColor Gray
Write-Host "=====================================================================" -ForegroundColor Cyan
Write-Host ""

& $adb logcat -v color -s TrikiBleManager:V TrikiForegroundService:V TrikiEngine:V TrikiVolume:V
