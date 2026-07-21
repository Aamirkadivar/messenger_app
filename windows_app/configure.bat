@echo off
setlocal enabledelayedexpansion

echo ============================================
echo Windows Messenger App - Configure Script
echo ============================================
echo.

REM Check for Qt path
set QT_PATH=

REM First check if QT_PATH is already set
if defined QT_PATH (
    if exist "%QT_PATH%" (
        goto :found_qt
    )
)

REM Check D:\qt (lowercase) first
if exist "D:\qt\6*\mingw_64" (
    for /d %%d in (D:\qt\6*\mingw_64) do (
        set "QT_PATH=%%d"
        goto :found_qt
    )
)

REM Check D:\Qt (capitalized) - try specific version patterns first
if exist "D:\Qt\6.11.1\mingw_64" (
    set "QT_PATH=D:\Qt\6.11.1\mingw_64"
    goto :found_qt
)
if exist "D:\Qt\6.8.3\mingw_64" (
    set "QT_PATH=D:\Qt\6.8.3\mingw_64"
    goto :found_qt
)
if exist "D:\Qt\6.8.2\mingw_64" (
    set "QT_PATH=D:\Qt\6.8.2\mingw_64"
    goto :found_qt
)
if exist "D:\Qt\6.7.3\mingw_64" (
    set "QT_PATH=D:\Qt\6.7.3\mingw_64"
    goto :found_qt
)
if exist "D:\Qt\6.6.4\mingw_64" (
    set "QT_PATH=D:\Qt\6.6.4\mingw_64"
    goto :found_qt
)
if exist "D:\Qt\6.5.3\mingw_64" (
    set "QT_PATH=D:\Qt\6.5.3\mingw_64"
    goto :found_qt
)
if exist "D:\Qt\6.4.1\mingw_64" (
    set "QT_PATH=D:\Qt\6.4.1\mingw_64"
    goto :found_qt
)
if exist "D:\Qt\6.3.2\mingw_64" (
    set "QT_PATH=D:\Qt\6.3.2\mingw_64"
    goto :found_qt
)
if exist "D:\Qt\6.2.4\mingw_64" (
    set "QT_PATH=D:\Qt\6.2.4\mingw_64"
    goto :found_qt
)
if exist "D:\Qt\6.1.1\mingw_64" (
    set "QT_PATH=D:\Qt\6.1.1\mingw_64"
    goto :found_qt
)
REM Fallback to glob pattern
if exist "D:\Qt\6*\mingw_64" (
    for /d %%d in (D:\Qt\6*\mingw_64) do (
        if not defined QT_PATH (
            set "QT_PATH=%%d"
            goto :found_qt
        )
    )
)

REM Check C:\Qt
if exist "C:\Qt\6*\mingw_64" (
    for /d %%d in (C:\Qt\6*\mingw_64) do (
        if not defined QT_PATH (
            set "QT_PATH=%%d"
            goto :found_qt
        )
    )
)

REM Check %USERPROFILE%\Qt
if defined USERPROFILE (
    if exist "%USERPROFILE%\Qt\6*\mingw_64" (
        for /d %%d in (%USERPROFILE%\Qt\6*\mingw_64) do (
            if not defined QT_PATH (
                set "QT_PATH=%%d"
                goto :found_qt
            )
        )
    )
)

echo [WARNING] Qt6 MinGW not found in common paths
echo Please set QT_PATH environment variable or install Qt to:
echo   D:\Qt\6.x.x\mingw_64
echo.
echo You can also set it manually:
echo   set QT_PATH=D:\Qt\6.11.1\mingw_64
pause
exit /b 1

:found_qt
echo [OK] Qt6 found: %QT_PATH%
echo.

REM Check for CMake
where cmake >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] CMake not found. Please install CMake from https://cmake.org/download/
    pause
    exit /b 1
)
echo [OK] CMake found
echo.

REM Check for Visual Studio 2022
set VS_FOUND=0
set VS_PATH=
if exist "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" (
    set "VS_PATH=C:\Program Files\Microsoft Visual Studio\2022\Community"
    set VS_FOUND=1
) else if exist "C:\Program Files (x86)\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" (
    set "VS_PATH=C:\Program Files (x86)\Microsoft Visual Studio\2022\Community"
    set VS_FOUND=1
) else if exist "C:\Program Files\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" (
    set "VS_PATH=C:\Program Files\Microsoft Visual Studio\2022\BuildTools"
    set VS_FOUND=1
) else if exist "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" (
    set "VS_PATH=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools"
    set VS_FOUND=1
)

if !VS_FOUND! equ 1 (
    echo [OK] Visual Studio 2022 found: !VS_PATH!
    echo [INFO] Using Visual Studio generator
    set GENERATOR=Visual Studio 17 2022
    set ARCH=x64
    goto :skip_vs_config
)

REM Check for Visual Studio 2019
if exist "C:\Program Files\Microsoft Visual Studio\2019\Community\VC\Auxiliary\Build\vcvars64.bat" (
    set "VS_PATH=C:\Program Files\Microsoft Visual Studio\2019\Community"
    set VS_FOUND=1
) else if exist "C:\Program Files (x86)\Microsoft Visual Studio\2019\Community\VC\Auxiliary\Build\vcvars64.bat" (
    set "VS_PATH=C:\Program Files (x86)\Microsoft Visual Studio\2019\Community"
    set VS_FOUND=1
) else if exist "C:\Program Files\Microsoft Visual Studio\2019\BuildTools\VC\Auxiliary\Build\vcvars64.bat" (
    set "VS_PATH=C:\Program Files\Microsoft Visual Studio\2019\BuildTools"
    set VS_FOUND=1
) else if exist "C:\Program Files (x86)\Microsoft Visual Studio\2019\BuildTools\VC\Auxiliary\Build\vcvars64.bat" (
    set "VS_PATH=C:\Program Files (x86)\Microsoft Visual Studio\2019\BuildTools"
    set VS_FOUND=1
)

if !VS_FOUND! equ 1 (
    echo [OK] Visual Studio 2019 found: !VS_PATH!
    echo [INFO] Using Visual Studio generator
    set GENERATOR=Visual Studio 16 2019
    set ARCH=x64
    goto :skip_vs_config
)

if defined VS_PATH (
    echo [OK] Visual Studio 2019 found: !VS_PATH!
    echo [INFO] Using Visual Studio generator
    set GENERATOR=Visual Studio 16 2019
    set ARCH=x64
    goto :skip_vs_config
)

echo [INFO] No Visual Studio found, using MinGW Make
set GENERATOR=MinGW Make
set ARCH=
goto :skip_vs_config

:skip_vs_config
echo.

REM Clean previous build
if exist build (
    echo Removing old build directory...
    rmdir /s /q build
)

echo.
echo Configuring with CMake...
echo   Generator: !GENERATOR!
echo   Qt Path: !QT_PATH!
echo.

if "!GENERATOR!"=="Visual Studio 17 2022" (
    cmake -S . -B build -G "!GENERATOR!" -A !ARCH! ^
        -DCMAKE_PREFIX_PATH="!QT_PATH!" ^
        -DQt6_DIR="!QT_PATH!\lib\cmake\Qt6" ^
        -DQt6_Core_DIR="!QT_PATH!\lib\cmake\Qt6Core" ^
        -DQt6_Qml_DIR="!QT_PATH!\lib\cmake\Qt6Qml" ^
        -DQt6_Quick_DIR="!QT_PATH!\lib\cmake\Qt6Quick" ^
        -DQt6_Network_DIR="!QT_PATH!\lib\cmake\Qt6Network" ^
        -DQt6_Sql_DIR="!QT_PATH!\lib\cmake\Qt6Sql"
) else if "!GENERATOR!"=="Visual Studio 16 2019" (
    cmake -S . -B build -G "!GENERATOR!" -A !ARCH! ^
        -DCMAKE_PREFIX_PATH="!QT_PATH!" ^
        -DQt6_DIR="!QT_PATH!\lib\cmake\Qt6" ^
        -DQt6_Core_DIR="!QT_PATH!\lib\cmake\Qt6Core" ^
        -DQt6_Qml_DIR="!QT_PATH!\lib\cmake\Qt6Qml" ^
        -DQt6_Quick_DIR="!QT_PATH!\lib\cmake\Qt6Quick" ^
        -DQt6_Network_DIR="!QT_PATH!\lib\cmake\Qt6Network" ^
        -DQt6_Sql_DIR="!QT_PATH!\lib\cmake\Qt6Sql"
) else (
    cmake -S . -B build -G "MinGW Make" ^
        -DCMAKE_PREFIX_PATH="!QT_PATH!" ^
        -DQt6_DIR="!QT_PATH!\lib\cmake\Qt6" ^
        -DQt6_Core_DIR="!QT_PATH!\lib\cmake\Qt6Core" ^
        -DQt6_Qml_DIR="!QT_PATH!\lib\cmake\Qt6Qml" ^
        -DQt6_Quick_DIR="!QT_PATH!\lib\cmake\Qt6Quick" ^
        -DQt6_Network_DIR="!QT_PATH!\lib\cmake\Qt6Network" ^
        -DQt6_Sql_DIR="!QT_PATH!\lib\cmake\Qt6Sql" ^
        -DCMAKE_BUILD_TYPE=RelWithDebInfo
)

if %errorlevel% neq 0 (
    echo [ERROR] CMake configuration failed
    pause
    exit /b %errorlevel%
)
echo [OK] CMake configuration successful!
echo.
echo To build: run build.bat
echo.
pause