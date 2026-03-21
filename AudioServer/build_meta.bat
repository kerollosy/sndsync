@echo off
setlocal enabledelayedexpansion

echo Building MetaServer...

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

if not exist "src\com\metaserver\MetaServer.java" (
    echo ERROR: Source file not found: src\com\metaserver\MetaServer.java
    exit /b 1
)

REM Create output directories
if not exist "bin" mkdir "bin"
if not exist "lib" mkdir "lib"

REM Clean previous build
if exist "bin\classes.dex" del /q "bin\classes.dex"
if exist "lib\MetaServer.jar" del /q "lib\MetaServer.jar"

echo Compiling Java...
javac -cp "%ANDROID_JAR%" ^
    "src\com\metaserver\MetaServer.java" ^
    "src\com\metaserver\FakeContext.java" ^
    "src\com\metaserver\ActivityManager.java" ^
    "src\com\metaserver\Workarounds.java" ^
    "src\android\content\IContentProvider.java" ^
    "src\android\app\ActivityThread.java" ^
    -d "bin"
if errorlevel 1 (
    echo ERROR: Compilation failed
    exit /b 1
)

echo Converting to DEX...
set CLASS_FILES=
for /R "bin" %%f in (*.class) do (
    set "CLASS_FILES=!CLASS_FILES! "%%f""
)

if "%CLASS_FILES%"=="" (
    echo ERROR: No .class files found in bin
    exit /b 1
)

call "%D8%" %CLASS_FILES% --output "bin" --lib "%ANDROID_JAR%"
if errorlevel 1 (
    echo ERROR: DEX conversion failed
    exit /b 1
)

echo Creating JAR...
jar cf "lib\MetaServer.jar" -C "bin" "classes.dex"
if errorlevel 1 (
    echo ERROR: JAR creation failed
    exit /b 1
)

echo.
echo Build successful!
echo JAR created: %cd%\lib\MetaServer.jar
for %%A in ("lib\MetaServer.jar") do echo File size: %%~zA bytes
echo.
