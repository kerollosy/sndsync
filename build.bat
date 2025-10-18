@echo off
setlocal enabledelayedexpansion

echo Building AudioServer...

REM Check for Android SDK
if not defined ANDROID_HOME (
    if exist "%LOCALAPPDATA%\Android\Sdk" (
        set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
    ) else (
        echo ERROR: ANDROID_HOME not set and SDK not found in default location
        echo Please set ANDROID_HOME environment variable
        exit /b 1
    )
)

set "ANDROID_JAR=%ANDROID_HOME%\platforms\android-34\android.jar"
set "D8=%ANDROID_HOME%\build-tools\35.0.0\d8.bat"

REM Verify required files exist
if not exist "%ANDROID_JAR%" (
    echo ERROR: Android JAR not found at: %ANDROID_JAR%
    echo Please install Android SDK API 34
    exit /b 1
)

if not exist "%D8%" (
    echo ERROR: d8 tool not found at: %D8%
    echo Please install Android build-tools 35.0.0
    exit /b 1
)

if not exist "src\AudioServer.java" (
    echo ERROR: Source file not found: src\AudioServer.java
    exit /b 1
)

REM Create output directories
if not exist "bin" mkdir "bin"
if not exist "lib" mkdir "lib"

REM Clean previous build
if exist "bin\*.class" del /q "bin\*.class"
if exist "bin\classes.dex" del /q "bin\classes.dex"
if exist "lib\AudioServer.jar" del /q "lib\AudioServer.jar"

echo Compiling Java...
javac -cp "%ANDROID_JAR%" "src\AudioServer.java" -d "bin"
if errorlevel 1 (
    echo ERROR: Compilation failed
    exit /b 1
)

echo Converting to DEX...
call "%D8%" "bin\AudioServer.class" --output "bin"
if errorlevel 1 (
    echo ERROR: DEX conversion failed
    exit /b 1
)

echo Creating JAR...
jar cf "lib\AudioServer.jar" -C "bin" "classes.dex"
if errorlevel 1 (
    echo ERROR: JAR creation failed
    exit /b 1
)

echo.
echo Build successful!
echo JAR created: %cd%\lib\AudioServer.jar
echo File size: 
for %%A in ("lib\AudioServer.jar") do echo   %%~zA bytes
echo.