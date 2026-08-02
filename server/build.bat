@echo off
setlocal enabledelayedexpansion

:: Build script for sndsync-server (Windows)
:: Output: dist\sndsync-server.jar

cd /d "%~dp0"

echo ==================================================
echo    Building sndsync-server (Windows)
echo ==================================================

:: Resolve Android SDK
if not defined ANDROID_HOME (
    if exist "%LOCALAPPDATA%\Android\Sdk" (
        set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
    ) else (
        echo ERROR: ANDROID_HOME is not set and the SDK was not found in default locations.
        echo Please set the ANDROID_HOME environment variable.
        exit /b 1
    )
)

set "ANDROID_JAR=%ANDROID_HOME%\platforms\android-34\android.jar"
set "D8=%ANDROID_HOME%\build-tools\35.0.0\d8.bat"

:: Verify dependencies
if not exist "%ANDROID_JAR%" (
    echo ERROR: Android platform JAR not found at: %ANDROID_JAR%
    echo Install platform-34 via the SDK Manager.
    exit /b 1
)

if not exist "%D8%" (
    echo ERROR: d8 compiler not found at: %D8%
    echo Install build-tools 35.0.0 via the SDK Manager.
    exit /b 1
)

if exist "bin"  rd /s /q "bin"
if exist "dist" rd /s /q "dist"
mkdir "bin"
mkdir "dist"

echo [+] Compiling sndsync-server...
javac --release 17 -cp "%ANDROID_JAR%" ^
    "src\com\sndsync\Main.java" ^
    "src\com\sndsync\AudioServer.java" ^
    "src\com\sndsync\MetaServer.java" ^
    "src\com\sndsync\FakeContext.java" ^
    "src\com\sndsync\ActivityManager.java" ^
    "src\com\sndsync\Workarounds.java" ^
    "src\android\content\IContentProvider.java" ^
    "src\android\app\ActivityThread.java" ^
    -d "bin"
if errorlevel 1 (
    echo ERROR: Compilation failed.
    exit /b 1
)

echo [+] Converting to DEX...
set "CLASS_FILES="
for /R "bin" %%f in (*.class) do (
    set "CLASS_FILES=!CLASS_FILES! "%%f""
)

call "%D8%" %CLASS_FILES% --output "bin" --lib "%ANDROID_JAR%"
if errorlevel 1 (
    echo ERROR: DEX conversion failed.
    exit /b 1
)

echo [+] Packaging dist\sndsync-server.jar...
jar cf "dist\sndsync-server.jar" -C "bin" classes.dex
if errorlevel 1 (
    echo ERROR: JAR packaging failed.
    exit /b 1
)

:: Clean temporary compilation class files
rd /s /q "bin"

echo ==================================================
echo    Build complete!
echo    dist\sndsync-server.jar
echo ==================================================
endlocal