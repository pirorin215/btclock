/**
 * BikeClock ESP32-S3 - BLE カスタムGATTサーバー (Phase 5)
 *
 * NimBLE-Arduino (2.x) でカスタムサービスを立て、BTClockMob（Android）と時刻同期する。
 * XIAO 版の Adafruit Bluefruit 実装を NimBLE に移植。プロトコルは完全互換。
 *
 * サービス/キャラクタリスティック:
 *   Service  : 4fafc201-1fb5-459e-8fcc-c5c9c331914c
 *   Command  : beb5483e-36e1-4688-b7f5-ea07361b26a0  (READ | WRITE | NOTIFY, 双方向)
 *
 * コマンド:
 *   SET:time:<unix_ts>   → 時刻同期
 *   SET:keys:HEX,...     → キー設定（Phase 4 で永続化、今はスタブ応答）
 *   GET:version          → ファームウェアバージョン応答
 *
 * ※ HID(0x1812) は Phase 6 で追加。今はカスタムGATTのみ。
 */

#include <NimBLEDevice.h>
#include "bikeclock.h"

// --- BLE オブジェクト ---
static NimBLEServer*         g_pServer      = nullptr;
static NimBLEService*        g_pService     = nullptr;
static NimBLECharacteristic* g_pCommandChar = nullptr;

// ====================================================================
// Server Callbacks（接続/切断）
// ====================================================================
class BikeClockServerCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo) override {
        (void)pServer;
        logPrint("BLE", "Device connected (addr=%s)",
                 std::string(connInfo.getAddress()).c_str());
        g_deviceConnected = true;
        updateLedStateBasedOnStatus();
    }

    void onDisconnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo, int reason) override {
        (void)pServer;
        (void)connInfo;
        logPrint("BLE", "Device disconnected (reason=%d)", reason);
        g_deviceConnected = false;
        updateLedStateBasedOnStatus();
        // 切断後に再アドバタイズ（アプリの自動再接続用）
        NimBLEDevice::startAdvertising();
    }
};

// ====================================================================
// Command Characteristic Callbacks（書き込み受信）
// ====================================================================
class CommandCharCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* pChar, NimBLEConnInfo& connInfo) override {
        (void)connInfo;
        std::string value = pChar->getValue();
        if (value.empty()) return;

        // null終端化して解析
        String command = String(value.c_str());
        logPrint("BLE", "Received: %s", command.c_str());

        if (command.startsWith("SET:time:")) {
            handleTimeSync(command.c_str());
        } else if (command.startsWith("SET:keys:")) {
            handleKeyConfig(command.c_str());
        } else if (command.startsWith("GET:version")) {
            handleGetVersion();
        } else {
            logPrint("BLE", "Unknown command");
            sendResponse("ERROR: Unknown command");
        }
    }

    void onSubscribe(NimBLECharacteristic* pChar, NimBLEConnInfo& connInfo, uint16_t subValue) override {
        (void)pChar;
        (void)connInfo;
        logPrint("BLE", "CCCD updated: %u (notify %s)", subValue, subValue ? "ENABLED" : "DISABLED");
    }
};

// ====================================================================
// BLE セットアップ
// ====================================================================
void setupBLE() {
    logPrint("BLE", "========================================");
    logPrint("BLE", "Initializing NimBLE (Custom GATT)");
    logPrint("BLE", "Firmware: %d.%d.%d",
             FIRMWARE_VERSION_MAJOR, FIRMWARE_VERSION_MINOR, FIRMWARE_VERSION_PATCH);
    logPrint("BLE", "Device Name: %s", BLE_DEVICE_NAME);
    logPrint("BLE", "Service UUID: " BLE_SERVICE_UUID);
    logPrint("BLE", "Command UUID: " BLE_CHAR_COMMAND_UUID);
    logPrint("BLE", "========================================");

    NimBLEDevice::init(BLE_DEVICE_NAME);

    // Server
    g_pServer = NimBLEDevice::createServer();
    g_pServer->setCallbacks(new BikeClockServerCallbacks());

    // Service
    g_pService = g_pServer->createService(BLE_SERVICE_UUID);

    // Command Characteristic（双方向: READ | WRITE | NOTIFY）
    g_pCommandChar = g_pService->createCharacteristic(
        BLE_CHAR_COMMAND_UUID,
        NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::NOTIFY
    );
    g_pCommandChar->setCallbacks(new CommandCharCallbacks());

    // Service 開始
    g_pService->start();

    // キー設定読込（Phase 4: LittleFS から復元）
    loadSettings();

    // Advertising（デバイス名 + カスタムサービス）
    // ※ デバイス名を明示的に含めないと、アプリの name フィルタ(BikeClock-0001)で
    //    検出されない。setName + enableScanResponse で scan response に名前を送る。
    NimBLEAdvertising* pAdvertising = NimBLEDevice::getAdvertising();
    pAdvertising->setName(BLE_DEVICE_NAME);
    pAdvertising->enableScanResponse(true);
    pAdvertising->addServiceUUID(BLE_SERVICE_UUID);
    pAdvertising->start();

    logPrint("BLE", "✅ BLE ready. Advertising started.");
    logPrint("BLE", "Waiting for BTClockMob connection...");
}

// ====================================================================
// Response 送信（Command char の notify）
// ====================================================================
void sendResponse(const char* message) {
    if (g_pCommandChar != nullptr) {
        g_pCommandChar->setValue(message);
        g_pCommandChar->notify();
        logPrint("BLE", "Response: %s", message);
    }
}

// ====================================================================
// コマンドハンドラ
// ====================================================================

// SET:time:<unix_timestamp> — 時刻同期
void handleTimeSync(const char* command) {
    const char* tsStr = command + 9;  // "SET:time:" の長さ
    uint32_t timestamp = (uint32_t)atol(tsStr);

    if (timestamp > 0) {
        g_currentTimestamp = timestamp;
        g_timeSynced = true;
        g_dateCache.valid = false;  // 日付キャッシュ無効化

        updateLedStateBasedOnStatus();
        logPrint("BLE", "Time synced: %02d:%02d:%02d",
                 getHours(), getMinutes(), getSeconds());

        sendResponse("OK: Time synced");
        updateTimeDisplay();  // 即時表示更新
    } else {
        logPrint("BLE", "Invalid timestamp: %s", tsStr);
        sendResponse("ERROR: Invalid timestamp format");
    }
}

// GET:version — ファームウェアバージョン応答
void handleGetVersion() {
    char buf[64];
    snprintf(buf, sizeof(buf), "OK:version:%d.%d.%d",
             FIRMWARE_VERSION_MAJOR, FIRMWARE_VERSION_MINOR, FIRMWARE_VERSION_PATCH);
    sendResponse(buf);
    logPrint("BLE", "Version: %d.%d.%d",
             FIRMWARE_VERSION_MAJOR, FIRMWARE_VERSION_MINOR, FIRMWARE_VERSION_PATCH);
}

// SET:keys:HEX,HEX,... — キー設定（Phase 4: 永続化対応）
// 形式: SET:keys:50,4F,52,51,28,0224,CD （7個の16進キーコード、カンマ区切り）
// XIAO 版と同一プロトコル・応答。成功時は hidSwitches を更新して saveSettings()。
void handleKeyConfig(const char* command) {
    const char* keysStr = command + 9;  // "SET:keys:" の長さ
    logPrint("BLE", "SET:keys received: %s", keysStr);

    // 作業用バッファにコピー（strtok は文字列を破壊するため）
    char temp[80];
    strncpy(temp, keysStr, sizeof(temp) - 1);
    temp[sizeof(temp) - 1] = '\0';

    char* token = strtok(temp, ",");
    int i = 0;
    while (token != NULL && i < NUM_HID_SWITCHES) {
        while (isspace((unsigned char)*token)) token++;  // 前後の空白をスキップ
        hidSwitches[i].keyCode = (uint16_t)strtoul(token, NULL, 16);
        logPrint("BLE", "  SW%d -> 0x%04X", i + 1, hidSwitches[i].keyCode);
        token = strtok(NULL, ",");
        i++;
    }

    if (i == NUM_HID_SWITCHES) {
        saveSettings();
        sendResponse("OK: keys updated");
        logPrint("BLE", "All %d keys updated and saved.", NUM_HID_SWITCHES);
    } else {
        logPrint("BLE", "Error: only %d keys parsed. Expected %d.", i, NUM_HID_SWITCHES);
        sendResponse("ERROR: Invalid key format");
    }
}

// ※ loadSettings()/saveSettings() は bikeclock_esp32_settings.ino（Phase 4）に実装

// BLEの deinit 処理（シャットダウン用）
void deinitBLE() {
    logPrint("BLE", "Deinitializing NimBLE stack...");
    NimBLEDevice::deinit(true);
}
