@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0services.ps1" -Action stop
if errorlevel 1 pause
