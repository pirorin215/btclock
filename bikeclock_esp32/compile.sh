#!/bin/bash

# BikeClock ESP32-S3 Compile Script

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/common.sh" ]; then
    source "$SCRIPT_DIR/common.sh"
fi

# Run arduino-cli compile and capture output, including time
COMPILE_COMMAND="arduino-cli compile --fqbn $BIKECLOCK_FQBN --build-path build bikeclock_esp32.ino"
echo "Compiling BikeClock (ESP32-S3)..."
echo "$COMPILE_COMMAND"
TIME_AND_COMPILE_OUTPUT=$( { time $COMPILE_COMMAND ; } 2>&1)
COMPILE_EXIT_CODE=$?

# Separate compile output from time output
COMPILE_OUTPUT=$(echo "$TIME_AND_COMPILE_OUTPUT" | sed '/^real/d; /^user/d; /^sys/d')
TIME_OUTPUT=$(echo "$TIME_AND_COMPILE_OUTPUT" | grep -E '^(real|user|sys)')

echo "$COMPILE_OUTPUT"

if [ $COMPILE_EXIT_CODE -ne 0 ]; then
    echo "Arduino compilation failed."
    exit $COMPILE_EXIT_CODE
fi

echo ""
echo "--- ビルド成功 ---"
echo "$TIME_OUTPUT"

# Extract version from bikeclock.h
VERSION_MAJOR=$(grep "#define FIRMWARE_VERSION_MAJOR" bikeclock.h | awk '{print $3}')
VERSION_MINOR=$(grep "#define FIRMWARE_VERSION_MINOR" bikeclock.h | awk '{print $3}')
VERSION_PATCH=$(grep "#define FIRMWARE_VERSION_PATCH" bikeclock.h | awk '{print $3}')
VERSION="${VERSION_MAJOR}.${VERSION_MINOR}.${VERSION_PATCH}"

# Create ZIP archive with version in filename in build directory (ESP32 output: .bin)
ZIP_FILENAME="build/bikeclock_esp32-v${VERSION}.zip"
echo ""
echo "Creating ZIP archive: ${ZIP_FILENAME}"

TEMP_DIR=$(mktemp -d)
mkdir -p "${TEMP_DIR}"

# Copy firmware binary
if [ -f build/bikeclock_esp32.ino.bin ]; then
    cp build/bikeclock_esp32.ino.bin "${TEMP_DIR}/bikeclock_esp32.ino.bin"
    zip -j "${ZIP_FILENAME}" "${TEMP_DIR}/bikeclock_esp32.ino.bin"
    echo "✅ ZIP archive created: ${ZIP_FILENAME}"
else
    echo "⚠️  build/bikeclock_esp32.ino.bin が見つかりません（ZIP作成をスキップ）"
fi

# Cleanup
rm -rf "${TEMP_DIR}"
