#!/bin/bash
set -e

echo "Building AudioServer..."

# Check for Android SDK
if [ -z "$ANDROID_HOME" ]; then
    if [ -d "$HOME/Android/Sdk" ]; then
        export ANDROID_HOME="$HOME/Android/Sdk"
    elif [ -d "/opt/android-sdk" ]; then
        export ANDROID_HOME="/opt/android-sdk"
    else
        echo "ERROR: ANDROID_HOME not set and SDK not found"
        echo "Please set ANDROID_HOME environment variable"
        exit 1
    fi
fi

ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
D8="$ANDROID_HOME/build-tools/35.0.0/d8"

# Verify required files exist
if [ ! -f "$ANDROID_JAR" ]; then
    echo "ERROR: Android JAR not found at: $ANDROID_JAR"
    echo "Please install Android SDK API 34"
    exit 1
fi

if [ ! -f "$D8" ]; then
    echo "ERROR: d8 tool not found at: $D8"
    echo "Please install Android build-tools 35.0.0"
    exit 1
fi

if [ ! -f "src/AudioServer.java" ]; then
    echo "ERROR: Source file not found: src/AudioServer.java"
    exit 1
fi

# Create output directory
mkdir -p bin

# Clean previous build
rm -f bin/*.class bin/classes.dex AudioServer.jar

echo "Compiling Java..."
javac -cp "$ANDROID_JAR" "src/AudioServer.java" -d "bin"

echo "Converting to DEX..."
"$D8" "bin/AudioServer.class" --output "bin"

echo "Creating JAR..."
jar cf "AudioServer.jar" -C "bin" "classes.dex"

echo ""
echo "Build successful!"
echo "JAR created: $(pwd)/AudioServer.jar"
echo "File size: $(stat -c%s "AudioServer.jar" 2>/dev/null || stat -f%z "AudioServer.jar" 2>/dev/null || echo "unknown") bytes"
echo ""