# Windows 64 位发行包构建；所有裁剪仅作用于本次独立打包副本。
param([switch]$Installer)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Invoke-BuildTool {
    param([string]$Executable, [string[]]$Arguments)
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Executable 执行失败，退出码：$LASTEXITCODE"
    }
}

function Get-DirectoryBytes {
    param([string]$Path)
    return (Get-ChildItem -LiteralPath $Path -Recurse -File | Measure-Object Length -Sum).Sum
}

function Optimize-OpenCvJar {
    param([string]$Path, [string]$JdkRoot, [string]$ToolsRoot)
    $before = (Get-Item -LiteralPath $Path).Length
    # 用 Java ZIP 实现重建 JAR，避免 .NET 更新 ZIP 后产生 Java 不接受的条目格式。
    Invoke-BuildTool (Join-Path $JdkRoot 'bin/java.exe') @('-cp', $ToolsRoot,
        'OptimizeOpenCv', $Path, "$Path.pruned")
    Move-Item -LiteralPath "$Path.pruned" -Destination $Path -Force
    Write-Host ('      OpenCV：{0:N1} → {1:N1} MiB' -f ($before / 1MB), ((Get-Item -LiteralPath $Path).Length / 1MB))
}

function Find-InnoCompiler {
    # 支持自定义安装位置；普通 ZIP 构建不依赖 Inno Setup。
    $candidates = @()
    if ($env:INNO_SETUP_COMPILER) { $candidates += $env:INNO_SETUP_COMPILER }
    $command = Get-Command ISCC.exe -ErrorAction SilentlyContinue
    if ($command) { $candidates += $command.Source }
    foreach ($base in @($env:ProgramFiles, ${env:ProgramFiles(x86)}, $env:LOCALAPPDATA)) {
        if ($base) {
            foreach ($relative in @('Inno Setup 7/ISCC.exe', 'Inno Setup 6/ISCC.exe',
                    'Programs/Inno Setup 7/ISCC.exe', 'Programs/Inno Setup 6/ISCC.exe')) {
                $candidates += Join-Path $base $relative
            }
        }
    }
    foreach ($registryRoot in @('HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall',
            'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall',
            'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall')) {
        $entries = @(Get-ChildItem -LiteralPath $registryRoot -ErrorAction SilentlyContinue |
            Get-ItemProperty -ErrorAction SilentlyContinue)
        foreach ($entry in $entries) {
            if ($entry.PSObject.Properties['DisplayName'] -and $entry.DisplayName -like 'Inno Setup*' -and
                $entry.PSObject.Properties['InstallLocation'] -and $entry.InstallLocation) {
                $candidates += Join-Path $entry.InstallLocation 'ISCC.exe'
            }
        }
    }
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return [IO.Path]::GetFullPath($candidate) }
    }
    throw '未找到 Inno Setup 编译器。请安装 Inno Setup 6.6+ 或 7，也可用 INNO_SETUP_COMPILER 指定 ISCC.exe。'
}

$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    Write-Host "`n============================================================"
    Write-Host '  Macro Recorder - Windows x64 精简构建'
    Write-Host "============================================================`n"
    Write-Host '[1/6] 检查项目与构建环境'
    [xml]$pom = Get-Content -LiteralPath 'pom.xml' -Raw -Encoding UTF8
    $version = ([string]$pom.project.version).Trim()
    $artifactId = ([string]$pom.project.artifactId).Trim()
    if ($version -notmatch '^[0-9A-Za-z][0-9A-Za-z._-]*$' -or
        $artifactId -notmatch '^[0-9A-Za-z][0-9A-Za-z._-]*$') {
        throw 'pom.xml 中的版本或 artifactId 无效。'
    }
    $applicationInfo = Get-Content -LiteralPath 'src/main/java/com/macro/constant/ApplicationInfo.java' -Raw -Encoding UTF8
    if ($applicationInfo -notmatch 'APP_VERSION\s*=\s*"(?<applicationVersion>(?<softwareVersion>\d+\.\d+\.\d+)[^"]*)"') {
        throw '无法读取 ApplicationInfo 中的软件版本。'
    }
    $softwareVersion = $Matches.softwareVersion
    $applicationVersion = $Matches.applicationVersion
    if ($version -notmatch ('^' + [regex]::Escape($softwareVersion) + '(?:[^0-9.]|$)')) {
        throw "Maven 版本 $version 与软件版本 $softwareVersion 不一致，请先核对版本号。"
    }
    $maven = (Get-Command mvn.cmd -ErrorAction Stop).Source
    $innoCompiler = $null
    if ($Installer) {
        $innoCompiler = Find-InnoCompiler
        Write-Host "      Inno Setup：$innoCompiler"
    }
    $jdkRoot = if ($env:JAVA_HOME) {
        [IO.Path]::GetFullPath($env:JAVA_HOME)
    } else {
        Split-Path -Parent (Split-Path -Parent (Get-Command javac.exe -ErrorAction Stop).Source)
    }
    $jdkRelease = Get-Content -LiteralPath (Join-Path $jdkRoot 'release') -Raw
    if ($jdkRelease -notmatch '(?m)^JAVA_VERSION="17(?:\.|\")' -or
        $jdkRelease -notmatch '(?m)^OS_ARCH="(?:amd64|x86_64)"' -or
        $env:OS -ne 'Windows_NT') {
        throw '请使用 Windows x64 JDK 17 构建此发行包。'
    }
    foreach ($tool in @('java', 'javac', 'jdeps', 'jlink', 'jpackage')) {
        if (-not (Test-Path -LiteralPath (Join-Path $jdkRoot "bin/$tool.exe") -PathType Leaf)) {
            throw "JDK 中缺少 $tool.exe：$jdkRoot"
        }
    }
    $env:JAVA_HOME = $jdkRoot
    $buildId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
    $workRoot = Join-Path $projectRoot "target/package-$buildId"
    $outputRoot = Join-Path $projectRoot "dist/build-$buildId"
    $inputRoot = Join-Path $workRoot 'input'
    $libRoot = Join-Path $inputRoot 'lib'
    $runtimeRoot = Join-Path $workRoot 'runtime'
    $appRoot = Join-Path $outputRoot 'MacroRecorder'
    $mainJar = "$artifactId-$version.jar"
    $zipPath = Join-Path $outputRoot "MacroRecorder $version.zip"
    New-Item -ItemType Directory -Path $libRoot, $outputRoot | Out-Null
    Write-Host "      版本：$version"
    Write-Host "      输出：$outputRoot"

    Write-Host "`n[2/6] Maven 构建并收集运行依赖"
    # 不执行 clean；独立解析运行依赖，避免把 target/lib 中残留的旧依赖带进发行包。
    Invoke-BuildTool $maven @('package', 'dependency:copy-dependencies', '-DskipTests',
        "-DoutputDirectory=$libRoot", '-DincludeScope=runtime')
    Copy-Item -LiteralPath (Join-Path $projectRoot "target/$mainJar") -Destination $inputRoot

    Write-Host "`n[3/6] 裁剪 OpenCV 并生成精简 Java 环境"
    $toolsRoot = Join-Path $workRoot 'tools'
    New-Item -ItemType Directory -Path $toolsRoot | Out-Null
    Invoke-BuildTool (Join-Path $jdkRoot 'bin/javac.exe') @('--release', '17', '-encoding', 'UTF-8',
        '-d', $toolsRoot, (Join-Path $PSScriptRoot 'OptimizeOpenCv.java'))
    $opencvJars = @(Get-ChildItem -LiteralPath $libRoot -Filter 'opencv-*.jar' -File)
    if ($opencvJars.Count -ne 1) { throw '运行依赖中未找到唯一的 OpenCV 包。' }
    Optimize-OpenCvJar $opencvJars[0].FullName $jdkRoot $toolsRoot
    $moduleOutput = @(& (Join-Path $jdkRoot 'bin/jdeps.exe') --multi-release 17 --print-module-deps `
        --class-path "$libRoot/*" (Join-Path $inputRoot $mainJar))
    if ($LASTEXITCODE -ne 0) { throw 'jdeps 依赖分析失败，不能生成精简运行环境。' }
    $moduleLines = @($moduleOutput | Where-Object { $_ -match '^java\.base(?:,[a-zA-Z0-9_.]+)*$' })
    if ($moduleLines.Count -ne 1) { throw '无法读取 jdeps 的模块分析结果。' }
    # 静态分析无法发现反射、字符集和安全提供者，显式保留对应运行模块。
    $modules = @($moduleLines[0].Split(',') + @(
        'jdk.unsupported', 'jdk.crypto.ec', 'jdk.charsets', 'jdk.localedata', 'jdk.accessibility'
    ) | Sort-Object -Unique) -join ','
    Write-Host "      Java 模块：$modules"
    Invoke-BuildTool (Join-Path $jdkRoot 'bin/jlink.exe') @('--add-modules', $modules,
        '--strip-debug', '--no-header-files', '--no-man-pages', '--compress=2', '--output', $runtimeRoot)

    Write-Host "`n[4/6] 生成 Windows 应用"
    Invoke-BuildTool (Join-Path $jdkRoot 'bin/jpackage.exe') @('--input', $inputRoot,
        '--name', 'MacroRecorder', '--main-jar', $mainJar, '--main-class', 'com.macro.MacroApplication',
        '--type', 'app-image', '--icon', (Join-Path $projectRoot 'MacroRecorder.ico'),
        '--app-version', $softwareVersion, '--runtime-image', $runtimeRoot, '--dest', $outputRoot)
    if (-not (Test-Path -LiteralPath (Join-Path $appRoot 'MacroRecorder.exe') -PathType Leaf)) {
        throw 'jpackage 未生成应用启动程序。'
    }

    Write-Host "`n[5/6] 检查打包后的运行环境和依赖"
    $smokeRoot = Join-Path $workRoot 'smoke'
    $tempRoot = Join-Path $workRoot 'smoke-temp'
    New-Item -ItemType Directory -Path $smokeRoot, $tempRoot | Out-Null
    $packagedClasspath = "$(Join-Path $appRoot "app/$mainJar");$(Join-Path $appRoot 'app/lib/*')"
    Invoke-BuildTool (Join-Path $jdkRoot 'bin/javac.exe') @('--release', '17', '-encoding', 'UTF-8',
        '-cp', $packagedClasspath, '-d', $smokeRoot, (Join-Path $PSScriptRoot 'PackagingSmokeTest.java'))
    # 用发行包自己的 Java 执行，不注册全局钩子、不发送输入，也不读写个人配置。
    Invoke-BuildTool (Join-Path $appRoot 'runtime/bin/java.exe') @('-Dfile.encoding=UTF-8',
        "-Djava.io.tmpdir=$tempRoot", '-cp', "$smokeRoot;$packagedClasspath", 'PackagingSmokeTest', $applicationVersion)

    Write-Host "`n[6/6] 生成发布文件"
    Compress-Archive -LiteralPath $appRoot -DestinationPath $zipPath -CompressionLevel Optimal
    $installerPath = $null
    $installerBytes = $null
    if ($Installer) {
        # 封装已通过检查的同一份应用目录，版本后缀仅用于显示及文件名。
        Invoke-BuildTool $innoCompiler @("/DAppImage=$appRoot", "/DAppVersion=$softwareVersion",
            "/DDisplayVersion=$applicationVersion", "/DPackageVersion=$version", "/DOutputDir=$outputRoot",
            (Join-Path $PSScriptRoot 'installer/MacroRecorder.iss'))
        $installerPath = Join-Path $outputRoot "MacroRecorder-$version-Setup.exe"
        $installerBytes = (Get-Item -LiteralPath $installerPath).Length
    }
    $appBytes = Get-DirectoryBytes $appRoot
    $runtimeBytes = Get-DirectoryBytes (Join-Path $appRoot 'runtime')
    $zipBytes = (Get-Item -LiteralPath $zipPath).Length
    [ordered]@{
        version = $version
        softwareVersion = $softwareVersion
        modules = $modules.Split(',')
        appBytes = $appBytes
        runtimeBytes = $runtimeBytes
        zipBytes = $zipBytes
        smokeTest = 'passed'
        installerFile = if ($installerPath) { [IO.Path]::GetFileName($installerPath) } else { $null }
        installerBytes = $installerBytes
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $outputRoot 'build-info.json') -Encoding UTF8
    Write-Host "`n============================================================"
    Write-Host '  构建成功（已跳过 Maven 测试；打包基础检查通过）'
    Write-Host "  应用目录：$appRoot"
    Write-Host "  发布文件：$zipPath"
    if ($installerPath) {
        Write-Host "  安装程序：$installerPath"
        Write-Host ('  安装包：{0:N1} MiB' -f ($installerBytes / 1MB))
    }
    Write-Host ('  应用：{0:N1} MiB；Java：{1:N1} MiB；ZIP：{2:N1} MiB' -f
        ($appBytes / 1MB), ($runtimeBytes / 1MB), ($zipBytes / 1MB))
    Write-Host "============================================================`n"
} catch {
    Write-Host "`n[构建失败] $($_.Exception.Message)" -ForegroundColor Red
    Write-Host '已保留本次中间文件供检查，现有发行包未被替换。'
    exit 1
} finally {
    Pop-Location
}
exit 0
