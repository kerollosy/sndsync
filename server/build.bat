@echo off
setlocal enabledelayedexpansion

:: Automatically switch working directory to the script's folder location
cd /d "%~dp0"

echo ==================================================
echo    Building sndsync Android Servers (Win)
echo ==================================================

:: 1. Resolve Android SDK
if not defined ANDROID_HOME (
    if exist "%LOCALAPPDATA%\Android\Sdk" (
        set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
    ) else (
        echo ERROR: ANDROID_HOME environment variable is not defined.
        echo Please point it to your local Android Sdk location.
        exit /b 1
    )
)

set "ANDROID_JAR=%ANDROID_HOME%\platforms\android-34\android.jar"
set "D8=%ANDROID_HOME%\build-tools\35.0.0\d8.bat"

:: 2. Verify Files
if not exist "%ANDROID_JAR%" (
    echo ERROR: Platform JAR not found: %ANDROID_JAR%
    echo Please install Android Sdk Platform API 34.
    exit /b 1
)

if not exist "%D8%" (
    echo ERROR: d8 build tool compiler not found: %D8%
    echo Please install Android Sdk Build-tools 35.0.0.
    exit /b 1
)

:: Clear previous builds 
if exist "bin" rd /s /q "bin"
if exist "dist" rd /s /q "dist"

mkdir "bin\audio"
mkdir "bin\meta"
mkdir "dist"

:: 3. Compile and DEX AudioServer
echo [+] Compiling AudioServer...
javac --release 17 -cp "%ANDROID_JAR%" "src\com\sndsync\AudioServer.java" -d "bin\audio"
if errorlevel 1 (
    echo ERROR: Compilation of AudioServer failed.
    exit /b 1
)

echo [+] Converting AudioServer to DEX...
call "%D8%" "bin\audio\com\sndsync\AudioServer.class" --output "bin\audio"
if errorlevel 1 (
    echo ERROR: AudioServer DEX conversion failed.
    exit /b 1
)

echo [+] Packaging AudioServer.jar -> dist\AudioServer.jar...
jar cf dist\AudioServer.jar -C "bin\audio" classes.dex
if errorlevel 1 (
    echo ERROR: AudioServer JAR packaging failed.
    exit /b 1
)


:: 4. Compile and DEX MetaServer
echo [+] Compiling MetaServer...
javac --release 17 -cp "%ANDROID_JAR%" ^
    "src\com\sndsync\MetaServer.java" ^
    "src\com\sndsync\FakeContext.java" ^
    "src\com\sndsync\ActivityManager.java" ^
    "src\com\sndsync\Workarounds.java" ^
    "src\android\content\IContentProvider.java" ^
    "src\android\app\ActivityThread.java" ^
    -d "bin\meta"
if errorlevel 1 (
    echo ERROR: Compilation of MetaServer failed.
    exit /b 1
)

echo [+] Converting MetaServer to DEX...
set "CLASS_FILES="
for /R "bin\meta" %%f in (*.class) do (
    set "CLASS_FILES=!CLASS_FILES! "%%f""
)

call "%D8%" %CLASS_FILES% --output "bin\meta" --lib "%ANDROID_JAR%"
if errorlevel 1 (
    echo ERROR: MetaServer DEX conversion failed.
    exit /b 1
)

echo [+] Packaging MetaServer.jar -> dist\MetaServer.jar...
jar cf "dist\MetaServer.jar" -C "bin\meta" classes.dex
if errorlevel 1 (
    echo ERROR: MetaServer JAR packaging failed.
    exit /b 1
)

:: Clean temporary compilation class files
rd /s /q "bin"

echo ==================================================
echo    Build successful!
echo    - dist\AudioServer.jar
echo    - dist\MetaServer.jar
echo ==================================================
endlocal