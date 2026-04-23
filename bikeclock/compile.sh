#!/bin/bash

# BikeClock Compile Script for XIAO BLE (nRF52840)

# Run arduino-cli compile and capture output, including time
COMPILE_COMMAND="arduino-cli compile --fqbn Seeeduino:nrf52:xiaonRF52840 bikeclock.ino"
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
