/**
 * BLE Server Implementation for CycleClock (Adafruit Bluefruit)
 *
 * bikeclock_ble.ino (XIAO BLE版 v1.1.6) から流用。
 * HID・キー設定・7セグ表示を削除し、時刻同期のみの最小構成。
 *
 * ペアリング方針(2026-09-25 決定):
 * コマンド特性を SECMODE_ENC_NO_MITM とし、初回接続時に Just Works で
 * ペアリング→bonding させる。Androidアプリ(BTClockMob)はOSペアリング済みの
 * "BikeClock-" デバイスから接続先を解決するため、bonding は必須。
 * (bikeclock_esp32 と同じ方式・アプリ側の複数デバイス対応改修と合わせて
 *  バイク/自転車の切り替えがアプリ操作なしで成立する)
 */

#include "cycleclock.h"

// --- BLE Custom Service ---
BLEService bleService(BLE_SERVICE_UUID);
BLECharacteristic bleCommandCharacteristic(BLE_CHAR_COMMAND_UUID);

// --- Adafruit OTA DFU (将来のNordic DFU用・コスト小なので維持) ---
BLEDfu bledfu;
BLEDis bledis;

// --- Callback Handlers ---

void ble_central_connect(uint16_t conn_handle) {
    (void)conn_handle;
    logPrint("BLE", "Device connected");
    g_deviceConnected = true;
    g_lastActivityMs = millis();
    updateLedStateBasedOnStatus();
}

void ble_central_disconnect(uint16_t conn_handle, uint8_t reason) {
    (void)conn_handle;
    (void)reason;
    logPrint("BLE", "Device disconnected (reason=%u)", reason);
    g_deviceConnected = false;
    g_lastActivityMs = millis();  // 切断時刻からスリープタイマーを再スタート
    updateLedStateBasedOnStatus();
}

void onCommandWritten(uint16_t conn_hdl, BLECharacteristic* chr, uint8_t* data, uint16_t len) {
    (void)conn_hdl;
    (void)chr;

    if (len > 0 && len < 128) {
        char command[128];
        memcpy(command, data, len);
        command[len] = '\0';

        logPrint("BLE", "Received command: %s", command);

        if (strncmp(command, "SET:time:", 9) == 0) {
            handleTimeSync(command);
        } else if (strncmp(command, "GET:version", 11) == 0) {
            handleGetVersion();
        } else {
            logPrint("BLE", "Unknown command: %s", command);
            sendResponse("ERROR: Unknown command");
        }
    }
}

// --- BLE Setup ---
void setupBLE() {
    logPrint("BLE", "========================================");
    logPrint("BLE", "BLE Initialization (bonding required)");
    logPrint("BLE", "Firmware Version: %d.%d.%d (%s)",
             FIRMWARE_VERSION_MAJOR, FIRMWARE_VERSION_MINOR, FIRMWARE_VERSION_PATCH, __DATE__);

    // 時計1台用途なので接続数1で十分(HID廃止)
    Bluefruit.begin(1, 0);

    // Just Worksペアリング(入出力機能なし)で bonding を作る
    Bluefruit.Security.setIOCaps(false, false, false);

    Bluefruit.setName(BLE_DEVICE_NAME);

    // 接続インターバル: アプリの定期同期(1分)と省電力のバランス(bikeclockと同一)
    Bluefruit.Periph.setConnInterval(12, 24);

    Bluefruit.Periph.setConnectCallback(ble_central_connect);
    Bluefruit.Periph.setDisconnectCallback(ble_central_disconnect);

    // IMPORTANT: BLEDfu は他サービスより先に初期化する
    bledfu.begin();

    bledis.setManufacturer("pirorin215");
    bledis.setModel("CycleClock");
    bledis.begin();

    // --- Custom Service (Time Sync) ---
    // 暗号化必須: 初回アクセスでAndroidがペアリングを開始しbondingが作られる
    bleService.setPermission(SECMODE_OPEN, SECMODE_OPEN);
    bleCommandCharacteristic.setProperties(CHR_PROPS_READ | CHR_PROPS_WRITE | CHR_PROPS_NOTIFY);
    bleCommandCharacteristic.setPermission(SECMODE_ENC_NO_MITM, SECMODE_ENC_NO_MITM);
    bleCommandCharacteristic.setFixedLen(32);
    bleCommandCharacteristic.setWriteCallback(onCommandWritten);

    bleService.begin();
    bleCommandCharacteristic.begin();

    Serial.flush();
    delay(100);

    // --- Advertising ---
    // flags(3) + 128bit Service UUID(18) = 21 bytes。スキャンレスポンスに名前。
    Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
    Bluefruit.Advertising.addService(bleService);
    Bluefruit.ScanResponse.addName();
    Bluefruit.ScanResponse.addTxPower();

    Bluefruit.Advertising.restartOnDisconnect(true);
    Bluefruit.Advertising.setInterval(32, 244);    // 20ms - 152ms
    Bluefruit.Advertising.setFastTimeout(30);      // 30 seconds
    Bluefruit.Advertising.start(0);                 // 0 = advertise forever

    logPrint("BLE", "========================================");
    logPrint("BLE", "BLE ready. Device: %s", BLE_DEVICE_NAME);
    logPrint("BLE", "Service UUID: " BLE_SERVICE_UUID);
    logPrint("BLE", "Advertising started.");
    logPrint("BLE", "========================================");
}

// --- Time Sync Handler ---
void handleTimeSync(const char* command) {
    const char* timestampStr = command + strlen("SET:time:");
    uint32_t timestamp = (uint32_t)atol(timestampStr);

    if (timestamp > 0) {
        g_currentTimestamp = timestamp;
        g_timeSynced = true;
        g_lastActivityMs = millis();

        g_dateCache.valid = false;

        logPrint("BLE", "Time synced: %02d:%02d:%02d",
                 getHours(), getMinutes(), getSeconds());

        sendResponse("OK: Time synced");
        // ePaperへの即時反映は updateEpaperDisplay() の分変化検出に任せる
        // (毎分フル更新のため、同期直後の最大1分遅延は許容)
    } else {
        logPrint("BLE", "Invalid timestamp: %s", timestampStr);
        sendResponse("ERROR: Invalid timestamp format");
        setLedError();
    }
}

// --- Version Handler ---
void handleGetVersion() {
    char versionResponse[64];
    snprintf(versionResponse, sizeof(versionResponse), "OK:version:%d.%d.%d",
             FIRMWARE_VERSION_MAJOR, FIRMWARE_VERSION_MINOR, FIRMWARE_VERSION_PATCH);
    sendResponse(versionResponse);
}

// --- Response Helper ---
void sendResponse(const char* message) {
    bleCommandCharacteristic.notify((uint8_t*)message, strlen(message));
    logPrint("BLE", "Response sent: %s", message);
}
