#!/bin/bash
# Unified Linux/macOS Build Script for sndsync-server modules

set -e

# Automatically resolve the script's directory and change context
cd "$(dirname "$0")"

echo "=================================================="
echo "   Building sndsync Android Servers (Unix)        "
echo "=================================================="

# 1. Resolve Android SDK
if [ -z "$ANDROID_HOME" ]; then
    if [ -d "$HOME/Android/Sdk" ]; then
        export ANDROID_HOME="$HOME/Android/Sdk"
    elif [ -d "/opt/android-sdk" ]; then
        export ANDROID_HOME="/opt/android-sdk"
    else
        echo "ERROR: ANDROID_HOME not set and Sdk was not found in default locations."
        echo "Please configure the ANDROID_HOME environment variable."
        exit 1
    fi
fi

ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
D8="$ANDROID_HOME/build-tools/35.0.0/d8"

# 2. Verify dependencies
if [ ! -f "$ANDROID_JAR" ]; then
    echo "ERROR: Android Platform API 34 JAR not found at: $ANDROID_JAR"
    echo "Please download platform-34 from SDK Manager."
    exit 1
fi

if [ ! -f "$D8" ]; then
    echo "ERROR: Build-tools d8 compiler not found at: $D8"
    echo "Please download build-tools version 35.0.0."
    exit 1
fi

# Clean output directories to ensure a fresh, non-conflicting build
rm -rf bin dist
mkdir -p bin/audio bin/meta dist

# 3. Compile and DEX AudioServer
echo "[+] Compiling AudioServer..."
javac --release 17 -cp "$ANDROID_JAR" src/com/audioserver/AudioServer.java -d bin/audio

echo "[+] Converting AudioServer to DEX..."
"$D8" bin/audio/com/audioserver/AudioServer.class --output bin/audio

echo "[+] Packaging AudioServer.jar -> dist/AudioServer.jar..."
jar cf dist/AudioServer.jar -C bin/audio classes.dex


# 4. Compile and DEX MetaServer
echo "[+] Compiling MetaServer components..."
javac --release 17 -cp "$ANDROID_JAR" \
    src/com/metaserver/MetaServer.java \
    src/com/metaserver/FakeContext.java \
    src/com/metaserver/ActivityManager.java \
    src/com/metaserver/Workarounds.java \
    src/android/content/IContentProvider.java \
    src/android/app/ActivityThread.java \
    -d bin/meta

echo "[+] Converting MetaServer to DEX..."
CLASS_FILES=$(find bin/meta -name "*.class")
"$D8" $CLASS_FILES --output bin/meta --lib "$ANDROID_JAR"

echo "[+] Packaging MetaServer.jar -> dist/MetaServer.jar..."
jar cf dist/MetaServer.jar -C bin/meta classes.dex

# Clean temporary compilation class files
rm -rf bin

echo "=================================================="
echo "   Build complete!"
echo "   - dist/AudioServer.jar: $(stat -c%s "dist/AudioServer.jar" 2>/dev/null || stat -f%z "dist/AudioServer.jar" 2>/dev/null || echo "OK") bytes"
echo "   - dist/MetaServer.jar: $(stat -c%s "dist/MetaServer.jar" 2>/dev/null || stat -f%z "dist/MetaServer.jar" 2>/dev/null || echo "OK") bytes"
echo "=================================================="