@echo off
setlocal enabledelayedexpansion

echo ============================================
echo Windows Messenger App - Build \& Package
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

echo [ERROR] Qt not found
pause
exit /b 1

:found_qt
echo [OK] Qt found: %QT_PATH%
echo [OK] qmlpath: %QT_PATH%\bin\qmlscene
echo.

REM ---- Configure CMake (only if needed) ----
if not exist "build\CMakeCache.txt" (
    echo [INFO] Running CMake configure...
    cmake -B build -DCMAKE_PREFIX_PATH="%QT_PATH%" -DCMAKE_BUILD_TYPE=Release
    if errorlevel 1 (
        echo [ERROR] CMake configure failed
        pause
        exit /b 1
    )
) else (
    echo [INFO] CMake already configured
)
echo.

REM ---- Build ----
echo ============================================
echo Starting Build...
echo ============================================
echo.

cmake --build build --config Release --parallel
if errorlevel 1 (
    echo [ERROR] Build failed
    pause
    exit /b 1
)

echo.
echo ============================================
echo Build Complete!
echo ============================================
echo.

REM Find the built executable
set EXE_PATH=
if exist "build\Release\messenger_app.exe" (
    set EXE_PATH=build\Release\messenger_app.exe
) else if exist "build\messenger_app.exe" (
    set EXE_PATH=build\messenger_app.exe
) else (
    echo [WARNING] Executable not found
    dir /s /b *.exe 2>nul | findstr -i messenger_app
    goto :deploy
)

echo [OK] Executable: !EXE_PATH!
echo.

REM ---- Deploy Qt dependencies ----
:deploy
echo ============================================
echo Deploying Qt Dependencies...
echo ============================================

set WINDEPLOYQT="%QT_PATH%\bin\windeployqt.exe"
if exist "!WINDEPLOYQT!" (
    if exist "!EXE_PATH!" (
        call :!WINDEPLOYQT! "!EXE_PATH!" --dir deploy --release --no-webkit2 --no-system-d32-headers --no-qml-debug
        echo [OK] Qt deployment complete
    ) else (
        echo [WARNING] windeployqt not found at !WINDEPLOYQT!
        echo         Manual: windeployqt --release --dir deploy messenger_app.exe
    )
) else (
    echo [INFO] Skipping windeployqt (not found)
)

echo.
echo ============================================
echo Next Steps:
echo ============================================
echo 1. Test the app: build\Release\messenger_app.exe
echo 2. Create installer with Inno Setup (installer.iss) or NSIS
echo 3. Run: iscc installer.iss   (Inno Setup)
echo    or makensis installer.nsi  (NSIS)
echo.
pause