[Setup]
AppName=Windows Messenger App
AppVersion=1.0.0
AppPublisher=MessengerApp Team
AppPublisherURL=https://github.com/Aamirkadivar/messenger_app
AppSupportURL=https://github.com/Aamirkadivar/messenger_app/issues
AppUpdatesURL=https://github.com/Aamirkadivar/messenger_app/releases
DefaultDirName={autopf}\MessengerApp
DefaultGroupName=MessengerApp
UninstallDisplayIcon={app}\messenger_app.exe
OutputDir=dist
OutputBaseFilename=messenger_app-1.0.0-setup
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=lowest
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequiredOverridesAllowed=dialog

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"
Name: "autostart"; Description: "Start Messenger App on Windows login"; GroupDescription: "Startup settings:"

[Files]
Source: "dist\messenger_app\*"; DestDir: "{app}"; Flags: recursesubdirs

[Icons]
Name: "{autodesktop}\MessengerApp"; Filename: "{app}\messenger_app.exe"; WorkingDir: "{app}"
Name: "{group}\MessengerApp"; Filename: "{app}\messenger_app.exe"; WorkingDir: "{app}"
Name: "{group}\Uninstall MessengerApp"; Filename: "{uninstallexe}"

[Run]
Filename: "{app}\messenger_app.exe"; Description: "{cm:LaunchProgram,Messenger App}"; Flags: nowait postinstall skipifsilent

[Code]
procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
  begin
    // Create auto-start registry entry if user opted in
    if WizardIsTaskSelected('autostart') then
    begin
      RegWriteStringValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'MessengerApp', '{app}\messenger_app.exe');
    end;
    
    // Create desktop icon if user opted in
    if WizardIsTaskSelected('desktopicon') then
    begin
      // Desktop icon already created by [Icons] section
    end;
  end;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usUninstall then
  begin
    // Remove auto-start entry
    RegDeleteValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'MessengerApp');
  end;
end;