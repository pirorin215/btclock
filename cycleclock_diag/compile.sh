#!/opt/homebrew/bin/bash

# cycleclock_diag Compile Script for XIAO BLE (nRF52840)
# 0.5mA問題切り分け用(本番v0.4.9のコピー+10秒スリープ+診断ログ+hibernateスイッチ)

COMPILE_COMMAND="arduino-cli compile --fqbn Seeeduino:nrf52:xiaonRF52840 --build-path build cycleclock_diag.ino"
echo "Compiling cycleclock_diag (DIAG_EPAPER_HIBERNATE=$(grep -o 'DIAG_EPAPER_HIBERNATE[ ]*[01]' cycleclock.h | grep -o '[01]$'))..."
echo $COMPILE_COMMAND
TIME_AND_COMPILE_OUTPUT=$( { time $COMPILE_COMMAND ; } 2>&1)
COMPILE_EXIT_CODE=$?

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
echo "Hex: build/cycleclock_diag.ino.hex"
