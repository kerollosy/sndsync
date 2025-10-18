@echo off
REM Audio Server Run Script

setlocal enabledelayedexpansion

set JAR_PATH=./MetaServer.jar

echo [*] Pushing MetaServer.jar to device...
adb push %JAR_PATH% /data/local/tmp/MetaServer.jar
if errorlevel 1 (
    echo [ERROR] Failed to push JAR
    pause
    exit /b 1
)

echo [*] Starting MetaServer in new window...
adb shell "CLASSPATH=/data/local/tmp/MetaServer.jar app_process /data/local/tmp/ com.myproject.MetaServer"

pause