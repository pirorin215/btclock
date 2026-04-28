#!/bin/bash

# BikeClock Upload Script for XIAO BLE (nRF52840)

# 共通関数と設定ファイルを読み込み
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
echo "Uploading BikeClock to $BIKECLOCK_PORT..."
echo "========================================"

UPLOAD_COMMAND="arduino-cli upload -p $BIKECLOCK_PORT --fqbn Seeeduino:nrf52:xiaonRF52840 --input-dir build bikeclock.ino"

$UPLOAD_COMMAND
UPLOAD_EXIT_CODE=$?

echo "========================================"
if [ $UPLOAD_EXIT_CODE -ne 0 ]; then
    echo "Upload failed."
    exit $UPLOAD_EXIT_CODE
fi

echo ""
echo "--- アップロード成功 ---"
echo "Next: Run './consolelog.sh' to monitor serial output"
