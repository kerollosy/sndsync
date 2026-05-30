#!/bin/bash
# Build script for sndsync-server (Linux / macOS)
# Output: dist/sndsync-server.jar

set -e
# Automatically resolve the script's directory and change context
cd "$(dirname "$0")"

echo "=================================================="
echo "   Building sndsync-server (Unix)"
echo "=================================================="

# Resolve Android SDK
if [ -z "$ANDROID_HOME" ]; then
    if [ -d "$HOME/Android/Sdk" ]; then
        export ANDROID_HOME="$HOME/Android/Sdk"
    elif [ -d "/opt/android-sdk" ]; then
        export ANDROID_HOME="/opt/android-sdk"
    else
        echo "ERROR: ANDROID_HOME is not set and the SDK was not found in default locations."
        echo "Please set the ANDROID_HOME environment variable."
        exit 1
    fi
fi

ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
D8="$ANDROID_HOME/build-tools/35.0.0/d8"

# Verify dependencies
if [ ! -f "$ANDROID_JAR" ]; then
    echo "ERROR: Android platform JAR not found at: $ANDROID_JAR"
    echo "Install platform-34 via the SDK Manager."
    exit 1
fi

if [ ! -f "$D8" ]; then
    echo "ERROR: d8 compiler not found at: $D8"
    echo "Install build-tools 35.0.0 via the SDK Manager."
    exit 1
fi

rm -rf bin dist
mkdir -p bin dist

echo "[+] Compiling sndsync-server..."
javac --release 17 -cp "$ANDROID_JAR" \
    src/com/sndsync/Main.java \
    src/com/sndsync/AudioServer.java \
    src/com/sndsync/MetaServer.java \
    src/com/sndsync/FakeContext.java \
    src/com/sndsync/ActivityManager.java \
    src/com/sndsync/Workarounds.java \
    src/android/content/IContentProvider.java \
    src/android/app/ActivityThread.java \
    -d bin

echo "[+] Converting to DEX..."
CLASS_FILES=$(find bin -name "*.class")
"$D8" $CLASS_FILES --output bin --lib "$ANDROID_JAR"

echo "[+] Packaging dist/sndsync-server.jar..."
jar cf dist/sndsync-server.jar -C bin classes.dex

# Clean temporary compilation class files
rm -rf bin

SIZE=$(stat -c%s "dist/sndsync-server.jar" 2>/dev/null || stat -f%z "dist/sndsync-server.jar" 2>/dev/null || echo "?")
echo "=================================================="
echo "   Build complete!"
echo "   dist/sndsync-server.jar  ($SIZE bytes)"
echo "=================================================="