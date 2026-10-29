; 只封装本次已通过检查的精简应用，不收录或清理用户数据。
#ifndef AppImage
  #error Missing AppImage
#endif
#ifndef AppVersion
  #error Missing AppVersion
#endif
#ifndef DisplayVersion
  #define DisplayVersion AppVersion
#endif
#ifndef PackageVersion
  #define PackageVersion AppVersion
#endif
#ifndef OutputDir
  #error Missing OutputDir
#endif

[Setup]
; 此标识跨版本保持固定，用于识别原安装并复用其位置。
AppId={{83D99CF6-963B-47DD-99B8-DF4DC67D8D08}
AppName=Macro Recorder
AppVersion={#AppVersion}
AppVerName=Macro Recorder {#DisplayVersion}
VersionInfoVersion={#AppVersion}
VersionInfoProductVersion={#AppVersion}
DefaultDirName={localappdata}\Programs\Macro Recorder
DefaultGroupName=Macro Recorder
DisableDirPage=no
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible and not arm64
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
WizardStyle=modern dark
SetupIconFile={#AppImage}\MacroRecorder.ico
UninstallDisplayIcon={app}\MacroRecorder.exe
OutputDir={#OutputDir}
OutputBaseFilename=MacroRecorder-{#PackageVersion}-Setup
Compression=lzma2/normal
SolidCompression=yes
; 不自动关闭正在录制/播放的程序，复用应用现有的单实例互斥量。
AppMutex={code:GetApplicationMutex}
CloseApplications=no
RestartApplications=no

[Languages]
Name: "chinesesimp"; MessagesFile: "ChineseSimplified.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; Flags: unchecked

[Files]
Source: "{#AppImage}\MacroRecorder.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#AppImage}\MacroRecorder.ico"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#AppImage}\app\*"; DestDir: "{app}\app"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#AppImage}\runtime\*"; DestDir: "{app}\runtime"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\Macro Recorder"; Filename: "{app}\MacroRecorder.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\Macro Recorder"; Filename: "{app}\MacroRecorder.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\MacroRecorder.exe"; Description: "启动 Macro Recorder"; Flags: nowait postinstall skipifsilent unchecked

; 不使用 InstallDelete/UninstallDelete；运行时创建的 save、ScreenCapture 和配置不归安装器管理。
[Code]
var
  ApplicationMutex: String;

function GetApplicationMutex(Param: String): String;
var
  Output: TExecOutput;
  ResultCode, I, Position: Integer;
  Line, Sid: String;
begin
  if ApplicationMutex = '' then begin
    { whoami 是 Windows 自带工具；只读取当前用户 SID，不启动或修改应用。 }
    if not ExecAndCaptureOutput(ExpandConstant('{sys}\whoami.exe'), '/user /fo csv /nh', '',
        SW_SHOWNORMAL, ewWaitUntilTerminated, ResultCode, Output) then
      RaiseException('无法确认当前用户，请重试安装或卸载。');
    if (ResultCode <> 0) or Output.Error then
      RaiseException('无法确认当前用户，请重试安装或卸载。');
    for I := 0 to GetArrayLength(Output.StdOut) - 1 do begin
      Line := Output.StdOut[I];
      Position := Pos('S-1-', Line);
      if Position > 0 then begin
        Sid := '';
        while Position <= Length(Line) do begin
          if (Line[Position] <> 'S') and (Line[Position] <> '-') and
              ((Line[Position] < '0') or (Line[Position] > '9')) then Break;
          Sid := Sid + Line[Position];
          Position := Position + 1;
        end;
        ApplicationMutex := 'Local\MacroRecorder.SingleInstance.' + Sid + '.Main';
        Break;
      end;
    end;
    if ApplicationMutex = '' then
      RaiseException('无法读取当前用户身份，已停止安装或卸载。');
  end;
  Result := ApplicationMutex;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  Result := '';
  if CheckForMutexes(GetApplicationMutex('')) then
    Result := 'Macro Recorder 正在运行。请先保存工作并关闭程序，再继续安装。';
end;
