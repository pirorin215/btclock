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
    } else if (strncmp(command, "GET:features", 12) == 0) {
        handleGetFeatures();
    } else if (strncmp(command, "GET:battery", 11) == 0) {
        handleGetBattery();
    } else if (strncmp(command, "NOTIFYADD:", 10) == 0) {
        handleNotifyAdd(command);
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

// --- Features Handler (v0.4.23) ---
// アプリが接続時に能力probeする(BTClockMobはnotifychunk非対応機=旧ファームへは
// 従来の単一Writeで送るため、bikeclock_esp32等への影響はない)
void handleGetFeatures() {
    char featuresResponse[48];
    snprintf(featuresResponse, sizeof(featuresResponse), "OK:features:notifychunk:%d",
             NOTIFY_TEXT_LEN - 1);
    sendResponse(featuresResponse);
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
// 通知受信(fire-and-forget・応答なし)。SoftDeviceコールバックでは文字列操作と
// フラグ設定のみ行い、描画はupdateEpaperDisplay()のポーリングでloop側が行う。
//
// v0.4.23: 分割転送対応(MTU 247の単一Write制約を越えて長文を届けるため)。
//   1個目: NOTIFY:app=<アプリ名>/<総数>\n<本文パート1>
//   2個目以降: NOTIFYADD:<本文パートN>
//   <総数>は2〜NOTIFY_CHUNK_MAX。総数の省略(旧アプリ)や1は即表示(従来動作)。
//   組立はRAM上のスタッシングで行い、最終チャンク到着でアクティブ化する。
//   片方向プロトコル(Ack・タイムアウトなし)。途切れた組立は次のNOTIFY:到着で
//   破棄される(スタッシングは表示中の通知に影響しない)。
// その他の仕様:
//   - "app=" が無ければアプリ名を空、残り全部を本文とする
//   - "\n" が無ければ "app=" 以降全部をアプリ名、本文は空
//   - 本文は NOTIFY_TEXT_LEN-1 で切り詰め(UTF-8境界を巻き戻し、
//     マルチバイト文字途中で切らない)
//   - "\r\n"(CRLF) の \r は除去

// 分割組立のスタッシング(SoftDeviceコールバックからのみ触る)
static char s_chunkText[NOTIFY_TEXT_LEN];
static char s_chunkApp[NOTIFY_APP_LEN];
static size_t s_chunkLen = 0;
static uint8_t s_chunkRemaining = 0;

// 通知の活性化(描画はupdateEpaperDisplayが検出)。単一/組立完了の共用
static void activateNotification() {
    g_notificationEndTime = millis() + NOTIFICATION_DISPLAY_TIMEOUT_MS;
    g_notificationActive = true;
    g_notificationSeq++;   // 手動通知モード(モード2)表示中の再描画判定用

    size_t textLen = strlen(g_notificationText);
    logPrint("NOTIFY", "Received (app='%s', text=%u bytes): %s",
             g_notificationApp, (unsigned)textLen,
             textLen > 0 ? g_notificationText : "(empty)");
}

// 末尾の不完全なUTF-8を削る。切り詰め境界が文字境界に一致する場合は
// 削らない(旧実装は継続バイトを見て無条件に1文字余分に削っていた)
static size_t trimBrokenUtf8Tail(char* text, size_t len) {
    const size_t start = (len >= 4) ? len - 4 : 0;
    size_t lead = len;
    for (size_t i = len; i > start; i--) {
        if (((uint8_t)text[i - 1] & 0xC0) != 0x80) { lead = i - 1; break; }
    }
    if (lead >= len) return len;   // 末尾4Bに先頭バイトが無い(異常系)→触らない

    const uint8_t b = (uint8_t)text[lead];
    size_t expect = 1;
    if      ((b & 0xE0) == 0xC0) expect = 2;
    else if ((b & 0xF0) == 0xE0) expect = 3;
    else if ((b & 0xF8) == 0xF0) expect = 4;
    if (lead + expect == len) return len;   // 最終文字は完結している

    len = lead;   // 未完の先頭バイト以降を削る
    text[len] = '\0';
    return len;
}

void handleNotify(const char* command) {
    // "NOTIFY:" の7バイトをスキップ
    const char* p = command + 7;

    const char* appStart = strstr(p, "app=");
    const char* textStart = "";
    const char* appEnd = p;
    const char* slash = nullptr;   // アプリ名末尾の "/<総数>" (分割転送)

    if (appStart != nullptr) {
        appStart += 4;   // "app=" の4バイトをスキップ
        appEnd = appStart;
        // \n までがアプリ名
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
        // (appStartを有効なダミーにしてコピー長0を成立させる)
        appStart = p;
    }

    // --- 分割総数の解析: アプリ名末尾 "/<数字>" ---
    // パッケージ名に '/' は含まれないため最後の '/' で判定できる
    int total = 1;
    if (appStart != nullptr) {
        for (const char* q = appEnd - 1; q >= appStart; q--) {
            if (*q == '/') { slash = q; break; }
        }
        if (slash != nullptr) {
            const char* countEnd = appEnd;
            if (countEnd > slash && countEnd[-1] == '\r') countEnd--;
            int n = 0;
            for (const char* q = slash + 1; q < countEnd; q++) {
                if (*q < '0' || *q > '9') { n = 0; break; }
                n = n * 10 + (*q - '0');
            }
            if (n >= 2 && n <= NOTIFY_CHUNK_MAX) {
                total = n;
            } else if (n > 1) {
                logPrint("NOTIFY", "Chunk total %d out of range - treating as single", n);
            }
        }
    }

    // --- アプリ名コピー(32B上限・"/総数" は含めない) ---
    const char* appTerm = (slash != nullptr) ? slash : appEnd;
    size_t appLen = (size_t)(appTerm - appStart);
    if (slash == nullptr && appEnd > appStart && appEnd[-1] == '\r') appLen--;
    if (appLen >= NOTIFY_APP_LEN) appLen = NOTIFY_APP_LEN - 1;

    // --- 本文パートの長さ(バッファ上限で切り詰め) ---
    size_t textLen = strlen(textStart);
    if (textLen >= NOTIFY_TEXT_LEN) textLen = NOTIFY_TEXT_LEN - 1;

    if (total == 1) {
        // --- 単一通知(従来書式): 即アクティブ化 ---
        memcpy(g_notificationApp, appStart, appLen);
        g_notificationApp[appLen] = '\0';
        memcpy(g_notificationText, textStart, textLen);
        trimBrokenUtf8Tail(g_notificationText, textLen);
        activateNotification();
        return;
    }

    // --- 分割転送の1個目: スタッシング開始(アクティブ化はしない) ---
    memcpy(s_chunkApp, appStart, appLen);
    s_chunkApp[appLen] = '\0';
    memcpy(s_chunkText, textStart, textLen);
    s_chunkLen = textLen;
    s_chunkRemaining = (uint8_t)(total - 1);
    logPrint("NOTIFY", "Chunked notify start (part 1/%d, app='%s', %u bytes)",
             total, s_chunkApp, (unsigned)textLen);
}

// --- 分割転送の継続チャンク(v0.4.23) ---
void handleNotifyAdd(const char* command) {
    if (s_chunkRemaining == 0) {
        logPrint("NOTIFY", "NOTIFYADD without assembly - ignored");
        return;
    }

    const char* part = command + 10;   // "NOTIFYADD:" をスキップ
    size_t partLen = strlen(part);
    const size_t space = (size_t)NOTIFY_TEXT_LEN - 1 - s_chunkLen;
    if (partLen > space) partLen = space;
    memcpy(s_chunkText + s_chunkLen, part, partLen);
    s_chunkLen += partLen;
    s_chunkRemaining--;

    if (s_chunkRemaining > 0) {
        logPrint("NOTIFY", "Chunk received (%u bytes, %d parts left)",
                 (unsigned)s_chunkLen, (int)s_chunkRemaining);
        return;
    }

    // --- 組立完了: アクティブ化 ---
    s_chunkLen = trimBrokenUtf8Tail(s_chunkText, s_chunkLen);
    logPrint("NOTIFY", "Chunk assembly complete (app='%s', %u bytes)",
             s_chunkApp, (unsigned)s_chunkLen);

    memcpy(g_notificationApp, s_chunkApp, NOTIFY_APP_LEN);
    memcpy(g_notificationText, s_chunkText, s_chunkLen + 1);
    activateNotification();
}

// --- Response Helper ---
// 特性は可変長のため、メッセージ長だけをnotifyで送る(旧32B固定長時代の
// ゼロクリア+フル長送信の workaround は不要になった)。
void sendResponse(const char* message) {
    bleCommandCharacteristic.notify((const uint8_t*)message, strlen(message));
    logPrint("BLE", "Response sent: %s", message);
}
