/**
 * BikeClock ESP32-S3 - WiFi OTA (Phase 7)
 *
 * メンテナンス「3OTA」選択時: WiFi(STA)接続 → HTTPUpdateServer 起動 → .bin 書込待ち受け。
 *   ブラウザで http://<IP>/update を開き .bin をアップロードするとファーム更新。
 *   書込成功で HTTPUpdateServer が ESP.restart() → 新ファームで通常起動。
 *
 *   WiFi設定は BLE `SET:wifi:<ssid>\n<pass>` で事前設定（/wifi.dat に保存済みであること）。
 *   未設定時・接続失敗時は 7seg にエラー表示して再起動（通常モードへ復帰）。
 *
 * 待ち受け中の操作:
 *   - FUNCキー長押し(2秒): キャンセル → 再起動
 *   - 5分間アイドル: 自動タイムアウト → 再起動
 *
 * ※ この関数は戻らない（書込成功・キャンセル・タイムアウトのいずれも ESP.restart で抜ける）。
 */

#include <WiFi.h>
#include <WebServer.h>
#include <HTTPUpdateServer.h>
#include "bikeclock.h"

// WiFi接続タイムアウト
#define OTA_WIFI_TIMEOUT_MS  15000UL
// 書込なしの自動タイムアウト（電池/熱対策）
#define OTA_IDLE_TIMEOUT_MS  (5UL * 60 * 1000)
// FUNC長押しでキャンセル
#define OTA_CANCEL_HOLD_MS   2000UL

// --- 表示ヘルパ（7seg / LED） ---

// 7seg に4文字メッセージを表示
static void showOtaMsg(const char* msg) {
    uint8_t data[4] = {0};
    encodeStringToSegments(msg, data);
    g_display->setSegments(data);
}

// FUNCキー長押し(2秒)でキャンセル判定
static bool checkOtaCancel() {
    if (digitalRead(SWITCH_FUNC_GPIO) != LOW) return false;   // 押下時のみ（INPUT_PULLUP・LOW=押下）
    unsigned long start = millis();
    while (digitalRead(SWITCH_FUNC_GPIO) == LOW) {
        if (millis() - start >= OTA_CANCEL_HOLD_MS) return true;
        delay(10);
    }
    return false;
}

void startOtaDfuMode() {
    logPrint("OTA", "========================================");
    logPrint("OTA", "Starting WiFi OTA (SoftAP) update mode");

    // --- 1. WiFi(AP) 起動 ---
    showOtaMsg("Con");
    drawEpaperOtaState("Con");

    WiFi.mode(WIFI_AP);
    // SSID: bcota, パスワードなし, チャンネル1
    bool apResult = WiFi.softAP("bcota", nullptr, 1);
    if (!apResult) {
        logPrint("OTA", "!! softAP start failed. Restarting.");
        showOtaMsg("FAIL");
        drawEpaperOtaState("FAIL");
        setLedColor(true, false, false);    // 赤
        delay(3000);
        ESP.restart();
        return;
    }

    IPAddress ip = WiFi.softAPIP();
    logPrint("OTA", "softAP started! SSID='bcota', IP=%s", ip.toString().c_str());
    Serial.println();
    Serial.println("========================================");
    Serial.printf("OTA Ready: open http://%s/update in a browser\n", ip.toString().c_str());
    Serial.println("Upload the .bin firmware to update.");
    Serial.println("FUNC long-press (2s) to cancel.");
    Serial.println("========================================");
    Serial.println();

    WebServer server(80);
    HTTPUpdateServer httpUpdater(true);                       // serial_debug=on
    httpUpdater.setup(&server, "/update", "", "");            // 認証なし

    // ルート案内（/update へ誘導）
    server.on("/", [&]() {
        char page[384];
        snprintf(page, sizeof(page),
            "<!DOCTYPE html><html><head><meta charset='utf-8'>"
            "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            "</head><body><h1>BikeClock OTA</h1>"
            "<p>Firmware %d.%d.%d</p>"
            "<p><a href='/update'>Update Firmware &rarr;</a></p>"
            "</body></html>",
            FIRMWARE_VERSION_MAJOR, FIRMWARE_VERSION_MINOR, FIRMWARE_VERSION_PATCH);
        server.send(200, "text/html", page);
    });
    server.begin();

    // --- 2. 書込待ち受けループ ---
    showOtaMsg("OTA");
    drawEpaperOtaState("OTA", ip.toString().c_str());
    unsigned long lastLedToggle = millis();
    bool ledOn = false;
    int lastStationNum = 0;
    unsigned long lastStaCheck = 0;

    while (true) {
        server.handleClient();

        // 500msごとに接続クライアント数を監視し、変化があればQRコードを切り替える
        if (millis() - lastStaCheck >= 500) {
            lastStaCheck = millis();
            int curStationNum = WiFi.softAPgetStationNum();
            if (curStationNum != lastStationNum) {
                logPrint("OTA", "softAP station count changed: %d -> %d", lastStationNum, curStationNum);
                lastStationNum = curStationNum;
                if (curStationNum > 0) {
                    drawEpaperOtaState("OTA_URL", ip.toString().c_str());
                } else {
                    drawEpaperOtaState("OTA", ip.toString().c_str());
                }
            }
        }

        // 待ち受け中表示: LED 緑点滅（400ms）
        if (millis() - lastLedToggle >= 400) {
            lastLedToggle = millis();
            ledOn = !ledOn;
            setLedColor(false, ledOn, false);
        }

        // キャンセル（FUNC長押し2秒）
        if (checkOtaCancel()) {
            logPrint("OTA", "OTA cancelled by FUNC long-press. Restarting.");
            server.stop();
            WiFi.softAPdisconnect(true);
            showOtaMsg("STOP");
            drawEpaperOtaState("STOP");
            delay(1000);
            ESP.restart();
            return;
        }

        delay(2);
    }
}    // ※ 通常ここには到達しない: 書込成功で HTTPUpdateServer が ESP.restart() を呼ぶ。
