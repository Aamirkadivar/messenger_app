@echo off
setlocal enabledelayedexpansion

echo ============================================
echo Windows Messenger App - Build Script
echo ============================================
echo.

REM Find Qt path
set QT_PATH=

if defined QT_PATH (
    if exist "%QT_PATH%" (
        goto :found_qt
    )
)

if exist "D:\Qt\6.11.1\mingw_64" (
    set "QT_PATH=D:\Qt\6.11.1\mingw_64"
    goto :found_qt
)
if exist "D:\Qt\6.8.3\mingw_64" (
    set "QT_PATH=D:\Qt\6.8.3\mingw_64"
    goto :found_qt
)
if exist "D:\Qt\6*\mingw_64" (
    for /d %%d in (D:\Qt\6*\mingw_64) do (
        if not defined QT_PATH (
            set "QT_PATH=%%d"
            goto :found_qt
        )
    )
)
if exist "C:\Qt\6*\mingw_64" (
    for /d %%d in (C:\Qt\6*\mingw_64) do (
        if not defined QT_PATH (
            set "QT_PATH=%%d"
            goto :found_qt
        )
    )
)

echo [ERROR] Qt not found at D:\Qt\6.x.x\mingw_64
pause
exit /b 1

:found_qt
echo [OK] Qt found: %QT_PATH%
echo.

REM Check if CMake was configured
if not exist "build\build.ninja" (
    if exist "build\CMakeCache.txt" (
        echo [INFO] Project already configured, skipping configuration
    ) else (
        echo [ERROR] Project not configured. Please run configure.bat first.
        pause
        exit /b 1
    )
)

REM Check for Visual Studio
set VS_FOUND=0
for %%p in (
    "C:\Program Files\Microsoft Visual Studio\2022\Community"
    "C:\Program Files (x86)\Microsoft Visual Studio\2022\Community"
    "C:\Program Files\Microsoft Visual Studio\2022\BuildTools"
    "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools"
) do (
    if not "!VS_FOUND!"=="1" (
        if exist "%%p\VC\Auxiliary\Build\vcvars64.bat" (
            set "VS_FOUND=1"
            set "VS_PATH=%%p"
        )
    )
)

echo ============================================
echo Starting Build...
echo ============================================
echo.

if "!VS_FOUND!"=="1" (
    echo [INFO] Using Visual Studio MSBuild
    echo.
    
    :: Set up Visual Studio environment
    call "!VS_PATH!\VC\Auxiliary\Build\vcvars64.bat"
    
    :: Check for msbuild
    where msbuild >nul 2>&1
    if %errorlevel% neq 0 (
        echo [ERROR] msbuild not found in PATH
        pause
        exit /b 1
    )
    
    echo Building with MSBuild (Release)...
    msbuild build\WindowsMessenger.sln /p:Configuration=Release /m
    
    if %errorlevel% neq 0 (
        echo [ERROR] Build failed with MSBuild
        pause
        exit /b %errorlevel%
    )
) else (
    echo [INFO] Using MinGW make
    echo.
    
    echo Building with make (Release)...
    cmake --build build --config Release
    
    if %errorlevel% neq 0 (
        echo [ERROR] Build failed with make
        pause
        exit /b %errorlevel%
    )
)

echo.
echo ============================================
echo Build Complete!
echo ============================================
echo.

REM Check if executable was created
if exist "build\release\WindowsMessenger.exe" (
    echo [OK] Release executable: build\release\WindowsMessenger.exe
) else if exist "build\WindowsMessenger.exe" (
    echo [OK] Executable: build\WindowsMessenger.exe
) else (
    echo [WARNING] Executable not found in expected location
    echo Checking build directory...
    dir /s /b *.exe 2>nul | findstr -i messenger
)

echo.
echo To deploy the app, run: windeployqt build\release\WindowsMessenger.exe
echo.
pause