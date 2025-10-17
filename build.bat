@echo off
REM Audio Server Build Script (fixed)

setlocal enabledelayedexpansion

set "ANDROID_SDK=%LOCALAPPDATA%\Android\Sdk"
set "ANDROID_JAR=%ANDROID_SDK%\platforms\android-34\android.jar"
set "D8=%ANDROID_SDK%\build-tools\35.0.0\d8.bat"

REM Ensure output dirs exist
if not exist ".\bin" mkdir ".\bin"
if not exist ".\lib" mkdir ".\lib"

echo [*] Compiling Java...
javac -cp "%ANDROID_JAR%" ".\src\AudioServer.java" -d ".\bin"
if errorlevel 1 (
    echo [ERROR] Compilation failed
    pause
    exit /b 1
)
echo [OK] Compilation successful

echo [*] Converting to DEX...
if exist ".\bin\classes.dex" del ".\bin\classes.dex"

@REM Use call because %D8% is a .bat; calling it without call will not return control to this script
call "%D8%" ".\bin\AudioServer.class" --output ".\bin"
if errorlevel 1 (
    echo [ERROR] DEX conversion failed
    pause
    exit /b 1
)

echo [OK] DEX conversion successful

echo [*] Creating JAR...
if exist ".\lib\AudioServer.jar" del ".\lib\AudioServer.jar"
REM Put classes.dex at top-level of the jar
jar cvf ".\lib\AudioServer.jar" -C ".\bin" "classes.dex"
if errorlevel 1 (
    echo [ERROR] JAR creation failed
    pause
    exit /b 1
)
echo [OK] JAR created

echo.
echo [SUCCESS] Build complete!
echo [*] JAR location: %cd%\lib\AudioServer.jar
echo.
pause
