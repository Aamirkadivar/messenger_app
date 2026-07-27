@echo off
setlocal enabledelayedexpansion

echo ============================================
echo Windows Messenger App - Build ^& Package
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
if exist "D:\qt\6.11.1\mingw_64" (
    set "QT_PATH=D:\qt\6.11.1\mingw_64"
    goto :found_qt
)
if exist "D:\Qt\6.8.3\mingw_64" (
    set "QT_PATH=D:\Qt\6.8.3\mingw_64"
    goto :found_qt
)
for /d %%d in (D:\Qt\6*\mingw_64) do (
    if not defined QT_PATH (
        set "QT_PATH=%%d"
        goto :found_qt
    )
)
for /d %%d in (C:\Qt\6*\mingw_64) do (
    if not defined QT_PATH (
        set "QT_PATH=%%d"
        goto :found_qt
    )
)

echo [ERROR] Qt not found
pause
exit /b 1

:found_qt
echo [OK] Qt found: %QT_PATH%
echo.

REM Find MinGW compiler (needed on PATH for CMake/make even though
REM CMAKE_PREFIX_PATH points at Qt)
set MINGW_BIN=
if exist "D:\Qt\Tools\mingw1310_64\bin\gcc.exe" set "MINGW_BIN=D:\Qt\Tools\mingw1310_64\bin"
if exist "D:\qt\Tools\mingw1310_64\bin\gcc.exe" set "MINGW_BIN=D:\qt\Tools\mingw1310_64\bin"
if defined MINGW_BIN (
    echo [OK] MinGW found: %MINGW_BIN%
    set "PATH=%MINGW_BIN%;%QT_PATH%\bin;%PATH%"
) else (
    echo [WARNING] MinGW toolchain not found in the usual locations - build may fail if gcc/mingw32-make aren't already on PATH.
    set "PATH=%QT_PATH%\bin;%PATH%"
)
echo.

REM ---- Configure CMake (only if needed) ----
if not exist "build\CMakeCache.txt" (
    echo [INFO] Running CMake configure...
    cmake -B build -G "MinGW Makefiles" -DCMAKE_PREFIX_PATH="%QT_PATH%" -DCMAKE_BUILD_TYPE=Release
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

REM Find the built executable (single-config MinGW generator puts it
REM straight in build\, but check the Release\ subfolder too in case a
REM multi-config generator was used instead)
set EXE_PATH=
if exist "build\Release\messenger_app.exe" (
    set "EXE_PATH=build\Release\messenger_app.exe"
) else if exist "build\messenger_app.exe" (
    set "EXE_PATH=build\messenger_app.exe"
) else (
    echo [ERROR] Built executable not found
    dir /s /b build\*.exe 2>nul
    pause
    exit /b 1
)

echo [OK] Executable: !EXE_PATH!
echo.

REM ---- Stage a clean install tree (installer.iss packages this, not the
REM raw build\ dir, which is full of CMake/object-file cruft) ----
echo ============================================
echo Staging dist\messenger_app ...
echo ============================================

if exist "dist\messenger_app" rmdir /s /q "dist\messenger_app"
mkdir "dist\messenger_app"
copy /y "!EXE_PATH!" "dist\messenger_app\" >nul

REM Bundle libsodium.dll - windeployqt only knows about Qt's own
REM dependencies, not this third-party one, so copy it manually from
REM wherever the CMake post-build step already placed it next to the exe.
for %%f in (build\libsodium*.dll) do (
    if exist "%%f" copy /y "%%f" "dist\messenger_app\" >nul
)

echo.
echo ============================================
echo Deploying Qt Dependencies...
echo ============================================

set "WINDEPLOYQT=%QT_PATH%\bin\windeployqt.exe"
if not exist "%WINDEPLOYQT%" (
    echo [ERROR] windeployqt not found at %WINDEPLOYQT%
    pause
    exit /b 1
)

"%WINDEPLOYQT%" --release --qmldir qml --no-translations "dist\messenger_app\messenger_app.exe"
if errorlevel 1 (
    echo [ERROR] windeployqt failed
    pause
    exit /b 1
)
echo [OK] Qt deployment complete
echo.

echo ============================================
echo Package staged at dist\messenger_app
echo ============================================
echo.
echo Next step - build the installer (requires Inno Setup, https://jrsoftware.org/isdl.php):
echo   iscc installer.iss
echo.
echo The finished installer will be at dist\messenger_app-1.0.0-setup.exe
echo.
pause
