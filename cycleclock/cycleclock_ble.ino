/**
 * BLE Server Implementation for CycleClock (Adafruit Bluefruit)
 *
 * bikeclock_ble.ino (XIAO BLE版 v1.1.6) から流用。
 * HID・キー設定・7セグ表示を削除し、時刻同期+電池取得+通知受信(NOTIFY:)の構成。
 * v0.3.2: ATT MTU 247拡大+コマンド特性可変長化(247B)で通知の長Writeに対応。
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

void onConnect(uint16_t conn_handle) {
    (void)conn_handle;
    logPrint("BLE", "Device connected");
    g_deviceConnected = true;
    g_everConnectedThisBoot = true;   // v0.4.11: 誤起動判定用(今回の起動は正当)
    updateLedStateBasedOnStatus();
}

void onDisconnect(uint16_t conn_handle, uint8_t reason) {
    (void)conn_handle;
    logPrint("BLE", "Device disconnected (reason=%u)", reason);
    g_deviceConnected = false;
    // v0.4.15: 切断時刻をスリープ判定の基点に。1分以内の再接続なら継続
    // (瞬断対策)、再接続されなければ1分で System OFF
    g_sleepTimerStartMs = millis();
    updateLedStateBasedOnStatus();
}

void onCommandWritten(uint16_t conn_hdl, BLECharacteristic* chr, uint8_t* data, uint16_t len) {
    (void)conn_hdl;
    (void)chr;

    if (len == 0) return;   // 空Writeは無視
    if (len > BLE_CMD_MAX_LEN) {
        // バッファに収まらない過大Write: 無応答だとアプリ側はタイムアウトを
        // 待つだけのため、長さのみでもエラーとして返す
        logPrint("BLE", "Command too long: %u bytes (max %d)", len, BLE_CMD_MAX_LEN);
        sendResponse("ERROR: Command too long");
        return;
    }

    char command[BLE_CMD_MAX_LEN + 1];
    memcpy(command, data, len);
    command[len] = '\0';

    logPrint("BLE", "Received command: %s", command);

    if (strncmp(command, "SET:time:", 9) == 0) {
        handleTimeSync(command);
    } else if (strncmp(command, "GET:version", 11) == 0) {
        handleGetVersion();
    } else if (strncmp(command, "GET:battery", 11) == 0) {
        handleGetBattery();
    } else if (strncmp(command, "NOTIFY:", 7) == 0) {
        handleNotify(command);
    } else {
        logPrint("BLE", "Unknown command: %s", command);
        sendResponse("ERROR: Unknown command");
    }
}

// --- BLE Setup ---
void setupBLE() {
    logPrint("BLE", "========================================");
    logPrint("BLE", "BLE Initialization (bonding required)");
    logPrint("BLE", "Firmware Version: %d.%d.%d (%s)",
             FIRMWARE_VERSION_MAJOR, FIRMWARE_VERSION_MINOR, FIRMWARE_VERSION_PATCH, __DATE__);

    // ATT MTUを247へ拡大(デフォルト23)。アプリは通知を1回のWrite(~230B)で送るため。
    // begin()より前に設定する(SoftDeviceの接続構成に反映される)。
    // キュー長等はデフォルトのまま(BANDWIDTH系プリセットはevent_len/キューも上がり省電力に不利)。
    Bluefruit.configPrphConn(247, BLE_GAP_EVENT_LENGTH_MIN,
                             BLE_GATTS_HVN_TX_QUEUE_SIZE_DEFAULT,
                             BLE_GATTC_WRITE_CMD_TX_QUEUE_SIZE_DEFAULT);

    // 時計1台用途なので接続数1で十分(HID廃止)
    Bluefruit.begin(1, 0);

    // Just Worksペアリング(入出力機能なし)で bonding を作る
    Bluefruit.Security.setIOCaps(false, false, false);

    Bluefruit.setName(BLE_DEVICE_NAME);

    // 接続インターバル: アプリの定期同期(1分)と省電力のバランス(bikeclockと同一)
    Bluefruit.Periph.setConnInterval(12, 24);

    Bluefruit.Periph.setConnectCallback(onConnect);
    Bluefruit.Periph.setDisconnectCallback(onDisconnect);

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
    // 可変長(上限247B): 通知転送(NOTIFY:...)は~230Bの1回Writeで届く(旧: 32B固定長)
    bleCommandCharacteristic.setMaxLen(BLE_CMD_MAX_LEN);
    bleCommandCharacteristic.setFixedLen(0);
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

// --- Battery Handler ---
// キャッシュ値のみ返す(ADCには触らない・fastrec2のクラッシュ教训により
// BLEコールバックからanalogReadは禁止)。フォーマット: OK:battery:<mV>
void handleGetBattery() {
    float v = batteryVoltageCached();
    if (v < 0.0f) {
        sendResponse("ERROR: Battery not measured yet");
        return;
    }
    char resp[40];
    snprintf(resp, sizeof(resp), "OK:battery:%d", (int)(v * 1000.0f + 0.5f));
    sendResponse(resp);
}

// --- Notification Handler (bikeclock_esp32_ble.ino handleNotify から移植) ---
// NOTIFY:app=<アプリ名>\n<テキスト> — スマホ通知受信(fire-and-forget・応答なし)。
// ここ(SoftDeviceコールバック)では文字列操作とフラグ設定のみ行い、描画は
// updateEpaperDisplay() のポーリングで loop 側が行う(analogRead等のnrfx触媒は禁止)。
//   - "app=" が無ければアプリ名を空、残り全部を本文とする
//   - "\n" が無ければ "app=" 以降全部をアプリ名、本文は空
//   - 本文は 200B で切り詰め(UTF-8境界を巻き戻し、マルチバイト文字途中で切らない)
//   - "\r\n"(CRLF) の \r は除去
void handleNotify(const char* command) {
    // "NOTIFY:" の7バイトをスキップ
    const char* p = command + 7;

    // "app=" を探す
    const char* appStart = strstr(p, "app=");
    const char* textStart = "";
    const char* appEnd = p;   // アプリ名終端(デフォルト: 空文字列)

    if (appStart != nullptr) {
        appStart += 4;   // "app=" の4バイトをスキップ
        appEnd = appStart;
        // \n までがアプリ名。\r\n の \r も終端に含めない。
        while (*appEnd != '\0' && *appEnd != '\n') {
            appEnd++;
        }
        // 改行の次が本文(\r があれば1つ飛ばす)
        if (*appEnd == '\n') {
            textStart = appEnd + 1;
        } else {
            // \n 無し: アプリ名 = "app="以降全部。appEnd は '\0' を指す(本文空)
            textStart = appEnd;
        }
    } else {
        // "app=" 無し: アプリ名空、残り全部を本文
        textStart = p;
    }

    // --- アプリ名コピー(32B上限) ---
    size_t appLen = (size_t)(appEnd - appStart);
    if (appEnd > appStart && appEnd[-1] == '\r') appLen--;   // 末尾 \r 除去
    if (appLen >= NOTIFY_APP_LEN) appLen = NOTIFY_APP_LEN - 1;
    memcpy(g_notificationApp, appStart, appLen);
    g_notificationApp[appLen] = '\0';

    // --- 本文コピー(200B上限、UTF-8境界巻き戻しは切り詰め時のみ) ---
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

    // --- 通知活性化(描画は updateEpaperDisplay が検出) ---
    g_notificationEndTime = millis() + NOTIFICATION_DISPLAY_TIMEOUT_MS;
    g_notificationActive = true;
    g_notificationSeq++;   // 手動通知モード(モード2)表示中の再描画判定用

    logPrint("NOTIFY", "Received (app='%s', text=%d bytes): %s",
             g_notificationApp, (int)textLen,
             textLen > 0 ? g_notificationText : "(empty)");
}

// --- Response Helper ---
// 特性は可変長のため、メッセージ長だけをnotifyで送る(旧32B固定長時代の
// ゼロクリア+フル長送信の workaround は不要になった)。
void sendResponse(const char* message) {
    bleCommandCharacteristic.notify((const uint8_t*)message, strlen(message));
    logPrint("BLE", "Response sent: %s", message);
}
