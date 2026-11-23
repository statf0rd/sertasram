; stars installer for Windows (Inno Setup 6.3+).
; Built by .github/workflows/windows-installer.yml from the release zip bundle:
;   ISCC /DAppVersion=0.3.0 /DSourceDir=C:\path\to\stars-win stars.iss
; Installs per user (no admin rights) into %LOCALAPPDATA%\Programs\stars.
; The shortcut starts javaw.exe (no console window); the app log goes to
; %LOCALAPPDATA%\stars\stars.log. The console launcher
; "Zapustit stars.bat" from the zip is installed too, for diagnostics.
; Keep this file ASCII: Inno reads .iss files without a BOM as ANSI.

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#ifndef SourceDir
  #error Pass /DSourceDir=<unpacked stars-win folder>
#endif
#ifndef ServerUrl
  #define ServerUrl "ws://localhost:8080/signal"
#endif

; Same JVM flags as the .bat launcher in the zip. Q is a doubled quote: inside
; a quoted Inno value it becomes a single " character.
#define Q '""'
#define JavaArgs "-Dsertas.server=" + Q + ServerUrl + Q + " -Dsertas.capture.dll=" + Q + "{app}\lib\sertas_capture.dll" + Q + " -Dsertas.audio.dll=" + Q + "{app}\lib\sertas_audio.dll" + Q + " -Dsertas.demoaudio=on -Dsertas.log=" + Q + "{localappdata}\stars\stars.log" + Q + " -cp " + Q + "{app}\lib\*" + Q + " dev.sertas.app.Launcher"

[Setup]
AppId={{9949DBB8-3778-47FE-9375-0B0D46F8C582}
AppName=stars
AppVersion={#AppVersion}
AppVerName=stars {#AppVersion}
AppPublisher=Stanislav Rushkevich
AppPublisherURL=https://github.com/statf0rd/stars
DefaultDirName={autopf}\stars
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
LicenseFile={#SourceDir}\LICENSE.txt
OutputBaseFilename=stars-windows-x64-setup
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
UninstallDisplayName=stars
UninstallDisplayIcon={app}\jre\bin\javaw.exe

[Languages]
Name: "ru"; MessagesFile: "compiler:Languages\Russian.isl"
Name: "en"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[InstallDelete]
; lib\* is the classpath: jars of the previous version must not stay next to new ones.
Type: filesandordirs; Name: "{app}\lib"
Type: filesandordirs; Name: "{app}\jre"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\stars"; Filename: "{app}\jre\bin\javaw.exe"; Parameters: "{#JavaArgs}"; WorkingDir: "{app}"
Name: "{autodesktop}\stars"; Filename: "{app}\jre\bin\javaw.exe"; Parameters: "{#JavaArgs}"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\jre\bin\javaw.exe"; Parameters: "{#JavaArgs}"; WorkingDir: "{app}"; Description: "{cm:LaunchProgram,stars}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; Logs only. Saved join fields and stats in %USERPROFILE%\.sertas are kept.
Type: filesandordirs; Name: "{localappdata}\stars"
