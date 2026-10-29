@echo off
chcp 65001 >nul
setlocal EnableExtensions DisableDelayedExpansion

REM 与便携包共用精简构建流程，同时生成 ZIP 和 EXE 安装包。
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build-windows.ps1" -Installer
set "BUILD_EXIT_CODE=%ERRORLEVEL%"
if /i not "%~1"=="--no-pause" pause
exit /b %BUILD_EXIT_CODE%
