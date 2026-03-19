@echo off
REM Audio Server Build Script (fixed for packages and D8 issue)

setlocal enabledelayedexpansion

set "ANDROID_SDK=%LOCALAPPDATA%\Android\Sdk"
set "ANDROID_JAR=%ANDROID_SDK%\platforms\android-34\android.jar"
set "D8_TOOL=%ANDROID_SDK%\build-tools\35.0.0\d8.bat"
set JAR_PATH=./lib/AudioServer.jar
set AUDIO_PORT=42222

REM Ensure output dirs exist
if not exist ".\bin" mkdir ".\bin"
if not exist ".\lib" mkdir ".\lib"

echo [*] Compiling Java...
REM Compiling with packages: the input paths must reflect the package structure
javac -cp "%ANDROID_JAR%" ".\src\com\audioserver\AudioServer.java" ".\src\com\audioserver\FakeContext.java" ".\src\android\content\IContentProvider.java" ".\src\com\audioserver\ActivityManager.java" ".\src\com\audioserver\Workarounds.java" -d ".\bin"
if errorlevel 1 (
echo [ERROR] Compilation failed
pause
exit /b 1
)
echo [OK] Compilation successful

echo [*] Converting to DEX...
if exist ".\bin\classes.dex" del ".\bin\classes.dex"

REM --- FIX: Recursively find all .class files and pass them explicitly to D8 ---
set CLASS_FILES=
REM /R .\bin recursively walks the bin directory and its subdirectories
for /R ".\bin" %%f in (*.class) do (
set "CLASS_FILES=!CLASS_FILES! "%%f""
)

if "%CLASS_FILES%"=="" (
echo [ERROR] No .class files found in .\bin or subdirectories.
pause
exit /b 1
)

echo [*] Found class files:
echo %CLASS_FILES%

REM Convert all found class files to DEX
call "%D8_TOOL%" %CLASS_FILES% --output ".\bin" --lib "%ANDROID_JAR%"
if errorlevel 1 (
echo [ERROR] DEX conversion failed
pause
exit /b 1
)

echo [OK] DEX conversion successful

echo [*] Creating JAR...
if exist ".\lib\AudioServer.jar" del ".\lib\AudioServer.jar"

REM Create the JAR containing ONLY the classes.dex file at the top level
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

echo [*] Pushing AudioServer.jar to device...
adb push %JAR_PATH% /data/local/tmp/AudioServer.jar
if errorlevel 1 (
echo [ERROR] Failed to push JAR
pause
exit /b 1
)

echo [*] Starting AudioServer in new window...
REM Using the fully-qualified class name (FQN)
adb shell "CLASSPATH=/data/local/tmp/AudioServer.jar app_process /data/local/tmp/ com.audioserver.AudioServer %AUDIO_PORT%"

endlocal