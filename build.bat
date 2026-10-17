@echo off
chcp 65001 >nul
setlocal EnableExtensions DisableDelayedExpansion

REM 始终从脚本所在目录执行，支持在其他目录中双击或调用。
pushd "%~dp0"
if errorlevel 1 (
    echo [失败] 无法进入项目目录。
    goto :failed_without_project
)

set "APP_NAME=MacroRecorder"
set "MAIN_CLASS=com.macro.MacroApplication"
set "DIST_DIR=dist"
set "APP_DIR=%DIST_DIR%\%APP_NAME%"

echo.
echo ============================================================
echo   Macro Recorder - 构建与发布
echo ============================================================
echo.
echo [1/4] 读取 Maven 项目信息

for /f "usebackq delims=" %%V in (`powershell.exe -NoLogo -NoProfile -Command "$ErrorActionPreference='Stop'; [xml]$pom=Get-Content -LiteralPath '.\pom.xml' -Raw -Encoding UTF8; $value=([string]$pom.project.version).Trim(); if($value -notmatch '^[0-9A-Za-z][0-9A-Za-z._-]*$'){throw 'pom.xml 中的版本无效'}; $value"`) do set "VERSION=%%V"
if errorlevel 1 (
    set "ERROR_MESSAGE=无法读取 pom.xml 中的 Maven 版本。"
    goto :failed
)

for /f "usebackq delims=" %%A in (`powershell.exe -NoLogo -NoProfile -Command "$ErrorActionPreference='Stop'; [xml]$pom=Get-Content -LiteralPath '.\pom.xml' -Raw -Encoding UTF8; $value=([string]$pom.project.artifactId).Trim(); if($value -notmatch '^[0-9A-Za-z][0-9A-Za-z._-]*$'){throw 'pom.xml 中的 artifactId 无效'}; $value"`) do set "ARTIFACT_ID=%%A"
if errorlevel 1 (
    set "ERROR_MESSAGE=无法读取 pom.xml 中的 artifactId。"
    goto :failed
)

if not defined VERSION (
    set "ERROR_MESSAGE=Maven 版本为空。"
    goto :failed
)
if not defined ARTIFACT_ID (
    set "ERROR_MESSAGE=Maven artifactId 为空。"
    goto :failed
)

set "MAIN_JAR=%ARTIFACT_ID%-%VERSION%.jar"
set "ZIP_NAME=%APP_NAME% %VERSION%.zip"
set "ZIP_PATH=%DIST_DIR%\%ZIP_NAME%"

echo       软件：%APP_NAME%
echo       版本：%VERSION%
echo       主包：%MAIN_JAR%
echo       [完成]

echo.
echo [2/4] Maven 构建
call mvn -q clean package -DskipTests
if errorlevel 1 (
    set "ERROR_MESSAGE=Maven 构建失败。"
    goto :failed
)
if not exist "target\%MAIN_JAR%" (
    set "ERROR_MESSAGE=构建完成后未找到 target\%MAIN_JAR%。"
    goto :failed
)
echo       [完成]

echo.
echo [3/4] 生成 Windows 应用
if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"
if errorlevel 1 (
    set "ERROR_MESSAGE=无法创建 dist 目录。"
    goto :failed
)

if exist "%APP_DIR%" rmdir /s /q "%APP_DIR%"
if exist "%APP_DIR%" (
    set "ERROR_MESSAGE=无法清理旧应用目录 %APP_DIR%。"
    goto :failed
)

jpackage ^
  --input target ^
  --name "%APP_NAME%" ^
  --main-jar "%MAIN_JAR%" ^
  --main-class "%MAIN_CLASS%" ^
  --type app-image ^
  --icon MacroRecorder.ico ^
  --dest "%DIST_DIR%"
if errorlevel 1 (
    set "ERROR_MESSAGE=jpackage 创建应用失败。"
    goto :failed
)
if not exist "%APP_DIR%\%APP_NAME%.exe" (
    set "ERROR_MESSAGE=应用目录中未找到 %APP_NAME%.exe。"
    goto :failed
)
echo       [完成]

echo.
echo [4/4] 生成发布压缩包
if exist "%ZIP_PATH%" del /f /q "%ZIP_PATH%"

powershell.exe -NoLogo -NoProfile -Command ^
    "$ErrorActionPreference='Stop';" ^
    "Import-Module Microsoft.PowerShell.Archive -ErrorAction Stop;" ^
    "Compress-Archive -LiteralPath '.\%APP_DIR%' -DestinationPath '.\%ZIP_PATH%' -Force;" ^
    "$zip=Get-Item -LiteralPath '.\%ZIP_PATH%';" ^
    "Write-Host ('      大小：{0:N1} MB' -f ($zip.Length / 1MB));"
if errorlevel 1 (
    set "ERROR_MESSAGE=生成 ZIP 压缩包失败。"
    goto :failed
)
if not exist "%ZIP_PATH%" (
    set "ERROR_MESSAGE=压缩完成后未找到 %ZIP_PATH%。"
    goto :failed
)
echo       [完成]

echo.
echo ============================================================
echo   构建成功
echo   应用目录：%APP_DIR%
echo   发布文件：%ZIP_PATH%
echo ============================================================
echo.
set "BUILD_EXIT_CODE=0"
goto :finish

:failed
echo.
echo ============================================================
echo   构建失败
echo   原因：%ERROR_MESSAGE%
echo ============================================================
echo.
set "BUILD_EXIT_CODE=1"
goto :finish

:failed_without_project
set "BUILD_EXIT_CODE=1"

:finish
popd
if /i not "%~1"=="--no-pause" pause
exit /b %BUILD_EXIT_CODE%
