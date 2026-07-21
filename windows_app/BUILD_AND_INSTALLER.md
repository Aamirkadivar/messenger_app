# Windows Messenger App - Build & Installer Guide

## Prerequisites

### 1. Visual Studio Build Tools
Install from: https://visualstudio.microsoft.com/visual-cpp-build-tools/

During installation, select:
- "Desktop development with C++"
- Windows 10 SDK or Windows 11 SDK

### 2. CMake
Download from: https://cmake.org/download/
- Install for all users
- Add CMake to system PATH

### 3. Ninja
Download from: https://github.com/ninja-build/ninja/releases
- Extract to `C:\ninja`
- Add to system PATH

### 4. vcpkg
```powershell
git clone https://github.com/microsoft/vcpkg.git C:\vcpkg
C:\vcpkg\bootstrap-vcpkg.bat
```

Install required dependencies:
```powershell
C:\vcpkg\vcpkg install libsodium:x64-windows openssl:x64-windows curl:x64-windows sqlite3:x64-windows
```

### 5. Qt 6.x with MSVC2022 64-bit
Download Qt Installer from: https://www.qt.io/download-qt-installer

During installation:
- Select Qt 6.x version
- Under MSVC 2022, select **64-bit** support
- Select Qt modules:
  - **Qt Core** (default)
  - **Qt Gui** (default)
  - **Qt Qml** (required)
  - **Qt Quick** (required)
  - **Qt Network** (default)
  - **Qt Sql** (required)
  - **Qt WebSockets** (required)

Example install path: `C:\Qt\6.8.3\msvc2022_64`

### 6. Inno Setup (for installer creation)
Download from: https://jrsoftware.org/isdl.php

## Build Process

### Option A: Automated Build (Recommended)

Run the automated build script:
```powershell
cd windows_app
.\build_and_package.bat
```

This script will:
1. Check for required build tools
2. Find your Qt installation
3. Configure with CMake
4. Build the Release binary
5. Deploy Qt dependencies
6. Generate installer (if Inno Setup or NSIS is installed)

### Option B: Manual Build

#### Step 1: Configure environment
```powershell
:: For Visual Studio BuildTools
call "C:\Program Files\Microsoft Visual Studio\18\BuildTools\Common7\Tools\VsDevCmd.bat" -no_logo -arch=amd64

:: Or for Visual Studio Community
call "C:\Program Files\Microsoft Visual Studio\18\Community\Common7\Tools\VsDevCmd.bat" -no_logo -arch=amd64
```

#### Step 2: Set Qt path
```powershell
set QT_PATH=C:\Qt\6.8.3\msvc2022_64
```

#### Step 3: Configure with CMake
```powershell
cd windows_app
mkdir build && cd build
cmake -S .. -B . -G "Ninja" ^
    -DCMAKE_TOOLCHAIN_FILE=C:\vcpkg\scripts\buildsystems\vcpkg.cmake ^
    -DCMAKE_BUILD_TYPE=Release ^
    -DVCPKG_TARGET_TRIPLET=x64-windows ^
    -DCMAKE_PREFIX_PATH="%QT_PATH%" ^
    -DQt6_DIR="%QT_PATH%\lib\cmake\Qt6" ^
    -DQt6_QMAKE_EXECUTABLE="%QT_PATH%\bin\qmake.exe"
```

#### Step 4: Build
```powershell
cmake --build . --config Release
```

#### Step 5: Deploy dependencies
```powershell
cd ..
mkdir dist\messenger_app
copy build\Release\messenger_app.exe dist\messenger_app\
windeployqt6 --dir dist\messenger_app dist\messenger_app\messenger_app.exe
```

#### Step 6: Create installer

**With Inno Setup:**
```powershell
cd windows_app
"C:\Program Files (x86)\Inno Setup 6\ISCC.exe" installer.iss
```

**With NSIS:**
```powershell
cd windows_app
makensis installer.nsi
```

## Output

After successful build and packaging:
- **Portable package**: `windows_app\dist\messenger_app\`
- **Installer**: `windows_app\dist\messenger_app-1.0.0-setup.exe`

## Troubleshooting

### CMake configuration fails
- Ensure Qt is installed with all required modules
- Verify vcpkg dependencies are installed
- Check that MSVC environment is activated

### Build fails with linker errors
- Ensure all vcpkg packages are installed: `libsodium`, `openssl`, `curl`, `sqlite3`
- Run `C:\vcpkg\vcpkg integrate install` to fix

### Application won't run after build
- Run `windeployqt6` to copy all Qt DLLs
- Ensure MSVC runtime redistributable is installed
- Check that all vcpkg DLLs are in the output directory

### Installer creation fails
- Install Inno Setup: https://jrsoftware.org/isdl.php
- Or install NSIS: https://sourceforge.net/projects/nsis/

## File Structure

```
windows_app/
├── BUILD_AND_INSTALLER.md    ← This file
├── build_and_package.bat     ← Automated build script
├── installer.iss             ← Inno Setup script
├── installer.nsi             ← NSIS script (auto-generated)
├── CMakeLists.txt            ← Main build configuration
├── configure.bat             ← Quick configure script
├── vcpkg.json                ← vcpkg dependencies
├── src/                      ← Source code
├── qml/                      ← QML files
├── resources/                ← Resources
└── dist/                     ← Build output (created after build)
    ├── messenger_app\        ← Portable package
    └── messenger_app-1.0.0-setup.exe  ← Installer