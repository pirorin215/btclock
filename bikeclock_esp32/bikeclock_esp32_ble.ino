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

    // 設定読込（Phase 4 で本実装、今はスタブ）
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

// SET:keys:HEX,... — キー設定
// Phase 5 では受信ログ＋OK応答のみ。実際のキー更新(hidSwitches)は Phase 3、
// 永続化(saveSettings)は Phase 4 で本実装する。
void handleKeyConfig(const char* command) {
    logPrint("BLE", "SET:keys received: %s", command + 9);
    sendResponse("OK: keys updated");  // アプリ互換のためOK応答
}

// ====================================================================
// 設定永続化スタブ（Phase 4 で LittleFS 本実装に差し替え）
// ====================================================================
void loadSettings() {
    logPrint("BLE", "loadSettings: stub (Phase 4 で LittleFS 実装)");
}

void saveSettings() {
    logPrint("BLE", "saveSettings: stub (Phase 4 で LittleFS 実装)");
}
