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
        logPrint("BLE", "Device connected (addr=%s, total=%d)",
                 std::string(connInfo.getAddress()).c_str(),
                 pServer->getConnectedCount());
        // HID 接続 / GATT(アプリ) 接続を区別せず、接続有無で管理。
        // 2接続（HID + アプリ）同時もこの1フラグで「接続中」を表す。
        g_deviceConnected = true;
        updateLedStateBasedOnStatus();
    }

    void onDisconnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo, int reason) override {
        (void)connInfo;
        logPrint("BLE", "Device disconnected (reason=%d, remaining=%d)",
                 reason, pServer->getConnectedCount());
        // 残接続が0になった時だけ未接続扱い（2接続の片方切断でLEDが乱れないよう）
        if (pServer->getConnectedCount() == 0) {
            g_deviceConnected = false;
            updateLedStateBasedOnStatus();
        }
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

        // Phase 2: モデルフレーム（バイナリ 0xAA55...）は先頭マジックで判別
        if (value.size() >= 5 &&
            (uint8_t)value[0] == MOTION_FRAME_MAGIC0 &&
            (uint8_t)value[1] == MOTION_FRAME_MAGIC1) {
            handleMotionModelFrame(reinterpret_cast<const uint8_t*>(value.data()), value.size());
            return;
        }

        // null終端化して解析
        String command = String(value.c_str());
        logPrint("BLE", "Received: %s", command.c_str());

        if (command.startsWith("SET:time:")) {
            handleTimeSync(command.c_str());
        } else if (command.startsWith("SET:keys:")) {
            handleKeyConfig(command.c_str());
        } else if (command.startsWith("GET:version")) {
            handleGetVersion();
        } else if (command.startsWith("NOTIFY:")) {
            // Phase 10: スマホ通知受信（fire-and-forget：応答しない）
            handleNotify(command.c_str());
        } else if (command.startsWith("IMU_RECORD_START")) {
            // 未来録り: 10秒録音してからリングバッファ送信
            handleImuRecordStart();
        } else if (command.startsWith("IMU_DUMP")) {
            // Phase 14-B: リングバッファ（直近10秒）のチャンク転送要求
            handleImuDump();
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

    // ATT MTU を拡大（Phase 10: 通知本文 200B を1パケットで受信するため）
    // NimBLE デフォルト MTU 23B（ペイロード20B）では長文通知が送れない。
    // 247B は ESP32 で安定して使える上限。Android 側は接続時にサーバー提示 MTU を採用。
    NimBLEDevice::setMTU(247);
    logPrint("BLE", "ATT MTU set to %d", NimBLEDevice::getMTU());

    // セキュリティ（Phase 6: HID は暗号化必須のため bonding を有効化）
    // Just Works（パスキーUI不要）/ bonding 有効 / SC 有効 / MITM 無し。
    // Android が HID としてペアリング・ボンディングする際の鍵交換に使用。
    NimBLEDevice::setSecurityAuth(true, false, true);
    NimBLEDevice::setSecurityIOCap(BLE_HS_IO_NO_INPUT_OUTPUT);  // Just Works

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

    // HID サービス群追加（Phase 6: HID 0x1812 + DeviceInfo + Battery をこのサーバーに）
    setupHID(g_pServer);

    // キー設定読込（Phase 4: LittleFS から復元）
    loadSettings();

    // Advertising（デバイス名 + カスタムサービス + HID）
    // ※ デバイス名を明示的に含めないと、アプリの name フィルタ(BikeClock- 前方一致)で
    //    検出されない。setName + enableScanResponse で scan response に名前を送る。
    // ※ Phase 6: appearance=HID Keyboard + HIDサービスUUID(0x1812) を含め、
    //    Android システムが HID デバイスとして認識・ペアリングするよう促す。
    NimBLEAdvertising* pAdvertising = NimBLEDevice::getAdvertising();
    pAdvertising->setName(BLE_DEVICE_NAME);
    pAdvertising->setAppearance(0x03C1);  // HID Generic Keyboard (NimBLEHIDDevice.h: HID_KEYBOARD)
    pAdvertising->addServiceUUID(BLE_SERVICE_UUID);    // カスタムGATT（アプリ時刻同期）
    pAdvertising->addServiceUUID((uint16_t)0x1812);     // HID サービス（OS ペアリング用）
    pAdvertising->enableScanResponse(true);
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

// Phase 14-B: バイナリ notify（IMUチャンク送信）。送信成功で true。
// NimBLE の notify() は接続がない等で false を返す。呼び出し側（updateImuDump）で
// リトライ判定する。IMUチャンクは 0xAA55 マジックで始まり、アプリ側はこれを
// 既存の文字列応答と区別してバイナリ処理する。
bool sendBinary(const uint8_t* data, size_t len) {
    if (g_pCommandChar == nullptr || !g_deviceConnected) return false;
    g_pCommandChar->setValue(data, len);
    return g_pCommandChar->notify();
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
        recordStartupTime();   // 初回同期時に起動時刻(JST)を記録（Phase 9 詳細表示用）
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

// NOTIFY:app=<アプリ名>\n<テキスト> — スマホ通知受信（Phase 10）
// ファイア＆フォーゲット（応答なし）。受信時刻を記録し ePaper を通知表示へ切替。
//   - "app=" が無ければアプリ名を空、残り全部を本文とする
//   - "\n" が無ければ "app=" 以降全部をアプリ名、本文は空
//   - 本文は 200B で切り詰め（UTF-8 境界を巻き戻し、マルチバイト文字途中で切れないようにする）
//   - "\r\n"(CRLF) の \r は除去
void handleNotify(const char* command) {
    // "NOTIFY:" の7バイトをスキップ
    const char* p = command + 7;

    // "app=" を探す
    const char* appStart = strstr(p, "app=");
    const char* textStart = "";
    const char* appEnd = p;   // アプリ名終端（デフォルト: 空文字列）

    if (appStart != nullptr) {
        appStart += 4;   // "app=" の4バイトをスキップ
        appEnd = appStart;
        // \n までがアプリ名。\r\n の \r も終端に含めない。
        while (*appEnd != '\0' && *appEnd != '\n') {
            appEnd++;
        }
        // 改行の次が本文（\r があれば1つ飛ばす）
        if (*appEnd == '\n') {
            textStart = appEnd + 1;
        } else {
            // \n 無し: アプリ名 = "app="以降全部。appEnd は '\0' を指す（本文空）
            textStart = appEnd;
        }
    } else {
        // "app=" 無し: アプリ名空、残り全部を本文
        textStart = p;
    }

    // --- アプリ名コピー（32B上限）---
    size_t appLen = (size_t)(appEnd - appStart);
    if (appEnd > appStart && appEnd[-1] == '\r') appLen--;   // 末尾 \r 除去
    if (appLen >= NOTIFY_APP_LEN) appLen = NOTIFY_APP_LEN - 1;
    memcpy(g_notificationApp, appStart, appLen);
    g_notificationApp[appLen] = '\0';

    // --- 本文コピー（200B上限、UTF-8境界巻き戻しは切り詰め時のみ）---
    size_t textLen = strlen(textStart);
    bool truncated = (textLen >= NOTIFY_TEXT_LEN);
    if (truncated) {
        textLen = NOTIFY_TEXT_LEN - 1;
    }
    memcpy(g_notificationText, textStart, textLen);
    g_notificationText[textLen] = '\0';

    if (truncated) {
        // 切り詰めが発生した時のみ、末尾の不完全なUTF-8バイトを削る
        while (textLen > 0 && (g_notificationText[textLen - 1] & 0xC0) == 0x80) {
            textLen--;
        }
        if (textLen > 0 && (g_notificationText[textLen - 1] & 0xC0) == 0xC0) {
            textLen--;
        }
        g_notificationText[textLen] = '\0';
    }

    // --- 通知活性化（タイムスタンプは描画側で millis() を使わず g_currentMillis を使うが、
    //     onWrite は BLE タスクのため millis() を直接使用。loop 側は g_currentMillis で比較）---
    g_notificationEndTime = millis() + NOTIFICATION_DISPLAY_TIMEOUT_MS;
    g_notificationActive = true;
    g_epaperRedrawRequested = true;

    logPrint("NOTIFY", "Received (app='%s', text=%d bytes): %s",
             g_notificationApp, (int)textLen,
             textLen > 0 ? g_notificationText : "(empty)");
}

// BLEの deinit 処理（シャットダウン用）
void deinitBLE() {
    logPrint("BLE", "Deinitializing NimBLE stack...");
    NimBLEDevice::deinit(true);
}
