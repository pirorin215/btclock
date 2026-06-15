#!/bin/bash

# BikeClock ESP32-S3 Upload Script

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/common.sh" ]; then
    source "$SCRIPT_DIR/common.sh"
fi
if [ -f "$SCRIPT_DIR/setting.sh" ]; then
    source "$SCRIPT_DIR/setting.sh"
fi

# BikeClockポートチェック
check_bikeclock_port

# Run arduino-cli upload
echo "Uploading BikeClock (ESP32-S3) to $BIKECLOCK_PORT..."
echo "========================================"

UPLOAD_COMMAND="arduino-cli upload -p $BIKECLOCK_PORT --fqbn $BIKECLOCK_FQBN --input-dir build bikeclock_esp32.ino"

$UPLOAD_COMMAND
UPLOAD_EXIT_CODE=$?

echo "========================================"
if [ $UPLOAD_EXIT_CODE -ne 0 ]; then
    echo "Upload failed."
    exit $UPLOAD_EXIT_CODE
fi

echo ""
echo "--- アップロード成功 ---"
echo "Next: Run 'sh consolelog.sh' or 'arduino-cli monitor -p $BIKECLOCK_PORT' to view serial output"
