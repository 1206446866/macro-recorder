@echo off
chcp 65001 >nul
setlocal EnableExtensions DisableDelayedExpansion

REM 从脚本目录调用打包流程，保留完整 Maven 输出和原有暂停开关。
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build-windows.ps1"
set "BUILD_EXIT_CODE=%ERRORLEVEL%"
if /i not "%~1"=="--no-pause" pause
exit /b %BUILD_EXIT_CODE%
