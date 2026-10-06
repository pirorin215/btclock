#!/opt/homebrew/bin/bash

# cycleclock_diag Upload Script for XIAO BLE (nRF52840)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/common.sh" ]; then
    source "$SCRIPT_DIR/common.sh"
fi
if [ -f "$SCRIPT_DIR/setting.sh" ]; then
    source "$SCRIPT_DIR/setting.sh"
fi

check_cycleclock_port

echo "Uploading cycleclock_diag to $CYCLECLOCK_PORT..."
echo "========================================"

arduino-cli upload -p $CYCLECLOCK_PORT --fqbn Seeeduino:nrf52:xiaonRF52840 --input-dir build cycleclock_diag.ino
UPLOAD_EXIT_CODE=$?

echo "========================================"
if [ $UPLOAD_EXIT_CODE -ne 0 ]; then
    echo "Upload failed."
    exit $UPLOAD_EXIT_CODE
fi

echo ""
echo "--- アップロード成功 ---"
echo "Next: Run './consolelog.sh' to monitor serial output"
