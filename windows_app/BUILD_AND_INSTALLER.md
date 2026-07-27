# Windows Messenger App - Build & Installer Guide

This documents the toolchain actually used and verified for this project:
Qt's bundled MinGW compiler, not MSVC/Ninja/vcpkg.

## Prerequisites

### 1. Qt 6.x with MinGW 64-bit
Download the Qt Online Installer from: https://www.qt.io/download-qt-installer

During installation, select:
- Qt 6.x (this project was built and tested against 6.11.1)
- The **MinGW 64-bit** component (installs both Qt and a matching MinGW
  compiler under `Qt\Tools\mingw*_64`) - do not use the MSVC component
- Qt modules: **Qt Quick**, **Qt Quick Controls 2**, **Qt Network**,
  **Qt Sql**, **Qt WebSockets**

Typical install layout:
- Qt libraries: `D:\Qt\6.11.1\mingw_64` (or wherever you installed it)
- MinGW compiler: `D:\Qt\Tools\mingw1310_64\bin`

### 2. CMake
Download from: https://cmake.org/download/ - add it to your PATH.

### 3. libsodium (for E2EE)
Installed via vcpkg, vendored into this project as `vcpkg_installed/` -
already present in the repo, no separate setup needed. If it's ever missing,
CMake will print a warning and encryption features will be disabled rather
than failing the build.

### 4. Inno Setup (for building the installer)
Download from: https://jrsoftware.org/isdl.php - only needed for the final
"create installer.exe" step, not for building/running the app itself.

## Build Process

### Option A: Automated build & package (recommended)

```powershell
cd windows_app
.\build_and_package.bat
```

This script:
1. Locates your Qt + MinGW installation (checks the common `D:\Qt` / `D:\qt`
   / `C:\Qt` locations)
2. Configures CMake with the MinGW Makefiles generator (only if `build\` isn't
   already configured)
3. Builds a Release binary
4. Stages a clean `dist\messenger_app\` folder with just the exe and
   `libsodium.dll` (not the raw `build\` dir, which is full of CMake/object-file
   cruft you don't want to ship)
5. Runs `windeployqt` to bundle the Qt runtime DLLs and QML plugin modules
   into that same folder

At that point `dist\messenger_app\` is a complete, self-contained, portable
copy of the app - you can zip it up and run it on another machine as-is, or
continue to the installer step below.

### Option B: Manual build

```powershell
cd windows_app
set QT_PATH=D:\Qt\6.11.1\mingw_64
set PATH=D:\Qt\Tools\mingw1310_64\bin;%QT_PATH%\bin;%PATH%

cmake -B build -G "MinGW Makefiles" -DCMAKE_PREFIX_PATH="%QT_PATH%" -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release --parallel

mkdir dist\messenger_app
copy build\messenger_app.exe dist\messenger_app\
copy build\libsodium*.dll dist\messenger_app\
windeployqt --release --qmldir qml --no-translations dist\messenger_app\messenger_app.exe
```

### Create the installer

Requires Inno Setup (see Prerequisites above):

```powershell
cd windows_app
iscc installer.iss
```

(If `iscc` isn't on your PATH, run it via its full path instead, typically
`"C:\Program Files (x86)\Inno Setup 6\ISCC.exe" installer.iss`.)

## Output

- **Portable package**: `windows_app\dist\messenger_app\` - runs standalone,
  no installer needed, just copy the whole folder
- **Installer**: `windows_app\dist\messenger_app-1.0.0-setup.exe`

The installer (`installer.iss`) lets the user choose a desktop icon and
launch-on-login, and doesn't require admin rights (Inno Setup's
`PrivilegesRequired=lowest` + `{autopf}` combination installs per-user under
`%LocalAppData%\Programs` by default, or per-machine under Program Files if
the user explicitly chooses to elevate at install time).

## Troubleshooting

### CMake can't find Qt
- Double check `CMAKE_PREFIX_PATH` points at the Qt **MinGW** kit
  (`...\6.11.1\mingw_64`), not an MSVC kit - the two aren't interchangeable
  and CMake will fail to locate `Qt6Config.cmake` if they're mismatched with
  the compiler actually on PATH.

### Build fails with "gcc not found" / "mingw32-make not found"
- Make sure `Qt\Tools\mingw*_64\bin` is on PATH *before* you run CMake -
  CMake needs to find the matching MinGW compiler, not just Qt's libraries.

### Application won't run after packaging (missing DLL errors)
- Re-run the `windeployqt` step - it needs to be run against the exe in its
  **final** location (`dist\messenger_app\messenger_app.exe`), since it scans
  that binary's actual imports and QML usage to decide what to copy.
- `libsodium.dll` isn't a Qt dependency, so `windeployqt` won't add it -
  `build_and_package.bat` copies it manually from `build\`; if you're doing a
  manual build, make sure you copy it too.

### Installer creation fails
- Install Inno Setup: https://jrsoftware.org/isdl.php
- Make sure `dist\messenger_app\` exists and is populated (run the build step
  above first) - `installer.iss` packages that folder's contents, it doesn't
  build the app itself.

## File Structure

```
windows_app/
├── BUILD_AND_INSTALLER.md    ← This file
├── build_and_package.bat     ← Automated build + packaging script
├── installer.iss             ← Inno Setup script
├── CMakeLists.txt            ← Main build configuration
├── configure.bat             ← Quick configure script
├── vcpkg.json                ← vcpkg dependency manifest (libsodium)
├── src/                      ← Source code
├── qml/                      ← QML files
├── resources/                ← Resources
└── dist/                     ← Build output (created after build, gitignored)
    ├── messenger_app\        ← Portable package
    └── messenger_app-1.0.0-setup.exe  ← Installer
```
