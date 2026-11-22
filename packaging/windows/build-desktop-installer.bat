@echo off
rem Builds the ClinicQueue desktop app for Windows: its own window (JavaFX), its own Java, and a Setup program.
rem Run this on Windows. Needs: JDK 17 or newer on PATH, and internet for the first run (about 50 MB for JavaFX).
rem
rem   packaging\windows\build-desktop-installer.bat            -> dist\ClinicQueue-1.1.0.exe  (the Setup program)
rem   packaging\windows\build-desktop-installer.bat portable   -> dist\ClinicQueue\ClinicQueue.exe  (a folder only, no Setup)
rem
rem The Setup program needs the WiX Toolset 3.x (free): https://github.com/wixtoolset/wix3/releases
rem If WiX is missing this script still builds the portable folder and tells you what to install.
rem
rem JavaFX is downloaded once into out\javafx-... from Gluon. To use your own copy instead, download the
rem "JavaFX Windows x64 jmods" of version 21 from https://gluonhq.com/products/javafx/ , unzip it, and run:
rem     set JAVAFX_JMODS=C:\path\to\javafx-jmods-21.0.5
setlocal EnableExtensions
cd /d "%~dp0..\.."

set "JFX_VERSION=21.0.5"
set "APP_VERSION=1.1.0"
set "UPGRADE_UUID=d2552f98-4e39-4b22-b8e7-96632500a9d6"

rem ---- 1. Is a suitable JDK installed?
where javac >nul 2>nul
if errorlevel 1 goto no_jdk
where jpackage >nul 2>nul
if errorlevel 1 goto no_jdk
for /f "tokens=2" %%v in ('javac -version 2^>^&1 ^| findstr /b "javac"') do set "JV=%%v"
for /f "delims=." %%m in ("%JV%") do set "JMAJOR=%%m"
if %JMAJOR% LSS 17 goto old_jdk

rem ---- 2. Get JavaFX (the official OpenJFX build for Windows)
if defined JAVAFX_JMODS goto have_jfx
set "JFX_DIR=%CD%\out\javafx-%JFX_VERSION%"
if exist "%JFX_DIR%\unzipped" goto find_jfx
echo Downloading JavaFX %JFX_VERSION% ...
mkdir "%JFX_DIR%" 2>nul
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -UseBasicParsing 'https://download2.gluonhq.com/openjfx/%JFX_VERSION%/openjfx-%JFX_VERSION%_windows-x64_bin-jmods.zip' -OutFile '%JFX_DIR%\jmods.zip'"
if errorlevel 1 goto jfx_failed
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Force '%JFX_DIR%\jmods.zip' '%JFX_DIR%\unzipped'"
if errorlevel 1 goto jfx_failed
:find_jfx
set "JAVAFX_JMODS="
for /r "%JFX_DIR%\unzipped" %%f in (javafx.web.jmod) do if exist "%%f" set "JAVAFX_JMODS=%%~dpf"
if not defined JAVAFX_JMODS goto jfx_failed
:have_jfx
if "%JAVAFX_JMODS:~-1%"=="\" set "JAVAFX_JMODS=%JAVAFX_JMODS:~0,-1%"
if not exist "%JAVAFX_JMODS%\javafx.web.jmod" goto jfx_failed
echo Using JavaFX from %JAVAFX_JMODS%

rem ---- 3. Compile the app (including the JavaFX window) and make the jar
if exist out\classes rmdir /s /q out\classes
if exist out\jar rmdir /s /q out\jar
mkdir out\classes
mkdir out\jar
javac -d out\classes --module-path "%JAVAFX_JMODS%" --add-modules javafx.controls,javafx.web src\main\java\com\clinicqueue\*.java src\desktop\java\com\clinicqueue\*.java
if errorlevel 1 exit /b 1
xcopy /E /I /Y src\main\resources\static out\classes\static >nul
jar --create --file out\jar\clinicqueue.jar --main-class com.clinicqueue.Main -C out\classes .
if errorlevel 1 exit /b 1

rem ---- 4. The program folder with its own Java runtime and JavaFX inside (ClinicQueue.exe)
rem --arguments --desktop makes a double-click open ClinicQueue in its own window.
if exist dist\ClinicQueue rmdir /s /q dist\ClinicQueue
jpackage --type app-image --name ClinicQueue --app-version %APP_VERSION% --vendor ClinicQueue ^
  --description "Virtual queue for clinics" ^
  --input out\jar --main-jar clinicqueue.jar --main-class com.clinicqueue.Main ^
  --arguments --desktop ^
  --module-path "%JAVAFX_JMODS%" ^
  --add-modules java.desktop,jdk.httpserver,javafx.controls,javafx.web ^
  --icon packaging\windows\clinicqueue.ico ^
  --dest dist
if errorlevel 1 exit /b 1
if /i "%~1"=="portable" goto done_portable

rem ---- 5. The Setup program (needs WiX 3.x)
where candle.exe >nul 2>nul
if not errorlevel 1 goto have_wix
if not defined WIX goto no_wix
if not exist "%WIX%bin\candle.exe" goto no_wix
set "PATH=%WIX%bin;%PATH%"
:have_wix
rem Per-user install (no administrator rights), Start menu and desktop shortcuts, and it can be upgraded later.
jpackage --type exe --app-image dist\ClinicQueue --name ClinicQueue --app-version %APP_VERSION% --vendor ClinicQueue ^
  --description "Virtual queue for clinics" ^
  --win-menu --win-menu-group ClinicQueue --win-shortcut --win-per-user-install --win-dir-chooser ^
  --win-upgrade-uuid %UPGRADE_UUID% ^
  --dest dist
if errorlevel 1 exit /b 1
echo.
echo Done. Give people this file: dist\ClinicQueue-%APP_VERSION%.exe
echo (Windows SmartScreen will say "unknown publisher" because it is not code-signed: choose More info, then Run anyway.)
exit /b 0

:done_portable
echo.
echo Done. Start it with dist\ClinicQueue\ClinicQueue.exe  (copy the whole dist\ClinicQueue folder to move it)
exit /b 0

:no_wix
echo.
echo The program folder is ready: dist\ClinicQueue\ClinicQueue.exe
echo To also make the Setup program, install the WiX Toolset 3.x, open a NEW command prompt, and run this again:
echo     https://github.com/wixtoolset/wix3/releases
exit /b 1

:no_jdk
echo The JDK was not found. Install JDK 21 (or 17 or newer) and make sure its "bin" folder is on PATH.
exit /b 1

:old_jdk
echo Found Java %JV%, but JDK 17 or newer is needed.
exit /b 1

:jfx_failed
echo.
echo Could not get JavaFX automatically.
echo Download "JavaFX Windows x64 jmods" version 21 from https://gluonhq.com/products/javafx/ , unzip it, then run:
echo     set JAVAFX_JMODS=C:\path\to\the\unzipped\javafx-jmods-21.x.x
echo     packaging\windows\build-desktop-installer.bat
exit /b 1
