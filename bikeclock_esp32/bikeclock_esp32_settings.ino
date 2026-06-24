/**
 * BikeClock ESP32-S3 - 設定永続化（LittleFS）
 *
 * [Phase 4] キーコード設定を LittleFS の /keys.dat に永続化。
 *   - バイナリ形式: uint16_t × NUM_HID_SWITCHES (7) = 14 バイト
 *   - XIAO BLE 版（InternalFS）のロジックを ESP32 LittleFS に移植
 *
 * ファイル不在時は keys.ino で初期化済みのデフォルトキーコードをそのまま使用。
 * hidSwitches[] は keys.ino で定義、bikeclock.h で extern 宣言済み。
 */

#include "bikeclock.h"

// ====================================================================
// LittleFS マウント（setup() で setupBLE() の直前に呼ぶこと）
// ====================================================================
void setupFileSystem() {
    logPrint("FS", "Mounting LittleFS...");
    // true = マウント失敗/未フォーマット時に自動フォーマット（初回ブート対応）
    if (!LittleFS.begin(true)) {
        logPrint("FS", "!! LittleFS mount failed. Using default key settings (no persistence).");
        return;
    }
    logPrint("FS", "LittleFS mounted. Used: %lu / %lu bytes",
             (unsigned long)LittleFS.usedBytes(),
             (unsigned long)LittleFS.totalBytes());
}

// ====================================================================
// 設定読込: /keys.dat からキーコードを復元
// ====================================================================
void loadSettings() {
    logPrint("FS", "Loading settings from %s...", KEYS_FILE_PATH);

    if (!LittleFS.exists(KEYS_FILE_PATH)) {
        logPrint("FS", "No settings file found. Using defaults.");
        return;
    }

    File file = LittleFS.open(KEYS_FILE_PATH, "r");
    if (!file) {
        logPrint("FS", "Failed to open %s for reading. Using defaults.", KEYS_FILE_PATH);
        return;
    }

    uint16_t savedKeys[NUM_HID_SWITCHES];
    size_t expected = sizeof(savedKeys);
    size_t readBytes = file.read((uint8_t*)savedKeys, expected);
    file.close();

    if (readBytes != expected) {
        logPrint("FS", "Settings file truncated (%u/%u bytes). Using defaults.",
                 (unsigned)readBytes, (unsigned)expected);
        return;
    }

    for (int i = 0; i < NUM_HID_SWITCHES; i++) {
        hidSwitches[i].keyCode = savedKeys[i];
        logPrint("FS", "  SW%d KeyCode: 0x%04X", i + 1, hidSwitches[i].keyCode);
    }
    logPrint("FS", "Settings loaded successfully.");
}

// ====================================================================
// 設定保存: 現在のキーコードを /keys.dat に書込
// ====================================================================
void saveSettings() {
    logPrint("FS", "Saving settings to %s...", KEYS_FILE_PATH);

    // 念のため削除（XIAO版と同じ挙動）
    LittleFS.remove(KEYS_FILE_PATH);

    File file = LittleFS.open(KEYS_FILE_PATH, "w");
    if (!file) {
        logPrint("FS", "Failed to open %s for writing.", KEYS_FILE_PATH);
        return;
    }

    uint16_t keysToSave[NUM_HID_SWITCHES];
    for (int i = 0; i < NUM_HID_SWITCHES; i++) {
        keysToSave[i] = hidSwitches[i].keyCode;
    }

    size_t written = file.write((uint8_t*)keysToSave, sizeof(keysToSave));
    file.close();

    if (written == sizeof(keysToSave)) {
        logPrint("FS", "Settings saved successfully.");
    } else {
        logPrint("FS", "!! Settings save incomplete (%u/%u bytes).",
                 (unsigned)written, (unsigned)sizeof(keysToSave));
    }
}

// ====================================================================
// キー設定をデフォルトにリセット（LittleFS フォーマット + メモリ再設定）
// ====================================================================
void resetKeySettingsToDefaults() {
    logPrint("FACTORY_RESET", "Resetting key settings to defaults...");

    logPrint("FACTORY_RESET", "Formatting LittleFS...");
    if (LittleFS.format()) {
        logPrint("FACTORY_RESET", "LittleFS formatted successfully.");
    } else {
        logPrint("FACTORY_RESET", "!! LittleFS format failed.");
    }

    // メモリ上のキーコードをデフォルトに戻す
    for (int i = 0; i < NUM_HID_SWITCHES; i++) {
        uint16_t defaultKey;
        switch (i) {
            case 0: defaultKey = DEFAULT_SW1_KEYCODE; break;
            case 1: defaultKey = DEFAULT_SW2_KEYCODE; break;
            case 2: defaultKey = DEFAULT_SW3_KEYCODE; break;
            case 3: defaultKey = DEFAULT_SW4_KEYCODE; break;
            case 4: defaultKey = DEFAULT_SW5_KEYCODE; break;
            case 5: defaultKey = DEFAULT_SW6_KEYCODE; break;
            case 6: defaultKey = DEFAULT_SW7_KEYCODE; break;
            default: defaultKey = 0; break;
        }
        hidSwitches[i].keyCode = defaultKey;
    }

    logPrint("FACTORY_RESET", "Key settings reset to defaults:");
    for (int i = 0; i < NUM_HID_SWITCHES; i++) {
        logPrint("FACTORY_RESET", "  SW%d KeyCode: 0x%04X", i + 1, hidSwitches[i].keyCode);
    }
}

// ====================================================================
// ファクトリーリセット（全設定初期化 → 再起動）
//   メンテナンスモードの FACTORY_RESET 項目から呼ばれる
// ====================================================================
void resetToFactoryDefaults() {
    logPrint("FACTORY_RESET", "Resetting all settings to factory defaults...");

    resetKeySettingsToDefaults();

    logPrint("FACTORY_RESET", "Factory reset complete.");
    logPrint("FACTORY_RESET", "System will restart in 2 seconds...");

    Serial.flush();
    delay(2000);

    ESP.restart();
}

// ====================================================================
// WiFi設定（Phase 7: OTA用・LittleFS の /wifi.dat に保存）
//   形式: [ssid_len(1)][ssid][pass_len(1)][pass]
//   ssid: 最大32バイト、pass: 最大63バイト（空パス=オープンAP可）
// ====================================================================
bool hasWifiConfig() {
    return LittleFS.exists(WIFI_FILE_PATH);
}

// WiFi設定読込（成功=true・未設定/破損時は false）
bool loadWifiConfig(String& ssid, String& pass) {
    ssid = "";
    pass = "";

    if (!LittleFS.exists(WIFI_FILE_PATH)) {
        logPrint("FS", "No WiFi config found.");
        return false;
    }

    File file = LittleFS.open(WIFI_FILE_PATH, "r");
    if (!file) {
        logPrint("FS", "Failed to open %s for reading.", WIFI_FILE_PATH);
        return false;
    }

    uint8_t ssidLen = 0, passLen = 0;
    if (file.read(&ssidLen, 1) != 1 || ssidLen == 0 || ssidLen > WIFI_SSID_MAX_LEN) {
        logPrint("FS", "WiFi config corrupt (ssidLen=%u).", ssidLen);
        file.close();
        return false;
    }
    uint8_t ssidBuf[WIFI_SSID_MAX_LEN + 1];
    if (file.read(ssidBuf, ssidLen) != ssidLen) {
        logPrint("FS", "WiFi config truncated (ssid).");
        file.close();
        return false;
    }
    if (file.read(&passLen, 1) != 1 || passLen > WIFI_PASS_MAX_LEN) {
        logPrint("FS", "WiFi config corrupt (passLen=%u).", passLen);
        file.close();
        return false;
    }
    uint8_t passBuf[WIFI_PASS_MAX_LEN + 1];
    if (passLen > 0 && file.read(passBuf, passLen) != passLen) {
        logPrint("FS", "WiFi config truncated (pass).");
        file.close();
        return false;
    }
    file.close();

    ssidBuf[ssidLen] = '\0';
    passBuf[passLen] = '\0';
    ssid = String((const char*)ssidBuf);
    pass = String((const char*)passBuf);
    logPrint("FS", "WiFi config loaded: SSID='%s'", ssid.c_str());
    return true;
}

// WiFi設定保存（ssid 必須・pass は空可）
void saveWifiConfig(const char* ssid, const char* pass) {
    size_t ssidLen = strlen(ssid);
    size_t passLen = pass ? strlen(pass) : 0;

    if (ssidLen == 0 || ssidLen > WIFI_SSID_MAX_LEN || passLen > WIFI_PASS_MAX_LEN) {
        logPrint("FS", "!! Invalid WiFi config (ssidLen=%u passLen=%u). Not saved.",
                 (unsigned)ssidLen, (unsigned)passLen);
        return;
    }

    logPrint("FS", "Saving WiFi config to %s (SSID='%s')...", WIFI_FILE_PATH, ssid);
    LittleFS.remove(WIFI_FILE_PATH);

    File file = LittleFS.open(WIFI_FILE_PATH, "w");
    if (!file) {
        logPrint("FS", "Failed to open %s for writing.", WIFI_FILE_PATH);
        return;
    }

    uint8_t ssidLenB = (uint8_t)ssidLen;
    uint8_t passLenB = (uint8_t)passLen;
    file.write(&ssidLenB, 1);
    file.write((const uint8_t*)ssid, ssidLen);
    file.write(&passLenB, 1);
    file.write((const uint8_t*)pass, passLen);
    file.close();

    logPrint("FS", "WiFi config saved.");
}
