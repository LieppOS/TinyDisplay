#!/bin/bash
# Build and deploy SeklysMorka APK to LieppOS source tree

set -e

APK_SRC="app/build/outputs/apk/debug/app-debug.apk"
APK_DEST="/mnt/Samsung-SSD/LieppOS/vendor/lieppos/apps/TinyDisplay/TinyDisplay.apk"

# Build
echo "Building APK..."
./gradlew assembleDebug

# Check
if [ ! -f "$APK_SRC" ]; then
    echo "Error: APK not found at $APK_SRC"
    exit 1
fi

# Copy
cp "$APK_SRC" "$APK_DEST"
echo "Deployed to $APK_DEST"
