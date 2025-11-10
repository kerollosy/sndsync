@echo off
setlocal enabledelayedexpansion

REM ---------------- CONFIG ----------------
set "PROJECT_NAME=MetaServer"
set "SRC_DIR=src"
set "OUT_BIN=bin"
set "OUT_LIB=lib"
set "OUT_JAR=%OUT_LIB%\%PROJECT_NAME%.jar"
set "API_LEVEL=34"
set "BUILD_TOOLS_VER=35.0.0"

echo Building %PROJECT_NAME%...

REM ---------------- ANDROID SDK ----------------
if not defined ANDROID_HOME (
    if exist "%LOCALAPPDATA%\Android\Sdk" (
        set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
    ) else (
        echo ERROR: ANDROID_HOME not set and SDK not found in default location
        exit /b 1
    )
)

set "ANDROID_JAR=%ANDROID_HOME%\platforms\android-%API_LEVEL%\android.jar"
set "D8=%ANDROID_HOME%\build-tools\%BUILD_TOOLS_VER%\d8.bat"

REM ---------------- VERIFY ----------------
if not exist "%ANDROID_JAR%" (
    echo ERROR: Android JAR not found at: %ANDROID_JAR%
    exit /b 1
)
if not exist "%D8%" (
    echo ERROR: d8 tool not found at: %D8%
    exit /b 1
)
if not exist "%SRC_DIR%" (
    echo ERROR: Source directory not found: %SRC_DIR%
    exit /b 1
)

REM ---------------- PREPARE OUTPUT ----------------
if not exist "%OUT_BIN%" mkdir "%OUT_BIN%"
if not exist "%OUT_LIB%" mkdir "%OUT_LIB%"
if exist "%OUT_BIN%\classes.dex" del /q "%OUT_BIN%\classes.dex"
if exist "%OUT_JAR%" del /q "%OUT_JAR%"

REM ---------------- COMPILE ----------------
echo Compiling Java sources...
set "JAVA_FILES="
for /R "%SRC_DIR%" %%f in (*.java) do (
    set "JAVA_FILES=!JAVA_FILES! "%%f""
)

if "%JAVA_FILES%"=="" (
    echo ERROR: No Java files found in %SRC_DIR%
    exit /b 1
)

javac -cp "%ANDROID_JAR%" -d "%OUT_BIN%" %JAVA_FILES% 2>javac_err.txt
if errorlevel 1 (
    echo ERROR: Compilation failed. See javac_err.txt for details.
    type javac_err.txt
    exit /b 1
)
if exist javac_err.txt del /q javac_err.txt

REM ---------------- DEX CONVERSION ----------------
echo Converting to DEX...
set "CLASS_FILES="
for /R "%OUT_BIN%" %%f in (*.class) do (
    set "CLASS_FILES=!CLASS_FILES! "%%f""
)

if "%CLASS_FILES%"=="" (
    echo ERROR: No class files found in %OUT_BIN%
    exit /b 1
)

call "%D8%" --output "%OUT_BIN%" --lib "%ANDROID_JAR%" %CLASS_FILES%
if errorlevel 1 (
    echo ERROR: DEX conversion failed.
    exit /b 1
)

if not exist "%OUT_BIN%\classes.dex" (
    echo ERROR: DEX file not created.
    exit /b 1
)

REM ---------------- JAR CREATION ----------------
echo Creating JAR...
jar cf "%OUT_JAR%" -C "%OUT_BIN%" classes.dex
if errorlevel 1 (
    echo ERROR: JAR creation failed.
    exit /b 1
)

echo.
echo Build successful!
echo JAR created: %CD%\%OUT_JAR%
for %%A in ("%OUT_JAR%") do echo File size: %%~zA bytes
echo.
endlocal
