#!/bin/bash

# BikeClock Compile Script for XIAO BLE (nRF52840)

# Run arduino-cli compile and capture output, including time
COMPILE_COMMAND="arduino-cli compile --fqbn Seeeduino:nrf52:xiaonRF52840 --build-path build bikeclock.ino"
echo "Compiling BikeClock..."
echo $COMPILE_COMMAND
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
VERSION_MAJOR=$(grep "FIRMWARE_VERSION_MAJOR" bikeclock.h | awk '{print $3}')
VERSION_MINOR=$(grep "FIRMWARE_VERSION_MINOR" bikeclock.h | awk '{print $3}')
VERSION_PATCH=$(grep "FIRMWARE_VERSION_PATCH" bikeclock.h | awk '{print $3}')
VERSION="${VERSION_MAJOR}.${VERSION_MINOR}.${VERSION_PATCH}"

# Create ZIP archive with version in filename in build directory
ZIP_FILENAME="build/bikeclock-v${VERSION}.zip"
echo ""
echo "Creating ZIP archive: ${ZIP_FILENAME}"

# Create temporary directory structure
TEMP_DIR=$(mktemp -d)
mkdir -p "${TEMP_DIR}"

# Copy firmware file
cp build/bikeclock.ino.hex "${TEMP_DIR}/bikeclock.ino.hex"

# Create ZIP
zip -j "${ZIP_FILENAME}" "${TEMP_DIR}/bikeclock.ino.hex"

# Cleanup
rm -rf "${TEMP_DIR}"

echo "✅ ZIP archive created: ${ZIP_FILENAME}"
