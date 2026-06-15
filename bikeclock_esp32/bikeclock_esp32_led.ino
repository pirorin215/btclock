/**
 * BikeClock ESP32-S3 - オンボードRGB LED制御 (Phase 2)
 *
 * XIAO 版 bikeclock_led.ino の「状態→色・点滅」ロジックを移植。
 * 違い: XIAO 版は common anode 3色LED (digitalWrite) →
 *       ESP32-S3 SuperMini は GPIO48 の WS2812 RGB LED 1個 (Adafruit NeoPixel)。
 *
 * 状態遷移は BLE(BLE接続) と時刻同期に連動:
 *   未接続+未同期 = 赤点滅 / 未接続+同期 = 緑固定
 *   接続+未同期   = 青点滅 / 接続+同期   = 青固定
 *   起動 = 赤固定 / エラー = 赤早点滅
 */

#include <Adafruit_NeoPixel.h>
#include "bikeclock.h"

// LED輝度（0-255）。WS2812は明るすぎるので低めに設定（目視適正・消費電力抑える）
#define LED_BRIGHTNESS 32

// GPIO48 の RGB LED 1個（NEO_GRB: Green-Red-Byte 順、800kHz）
Adafruit_NeoPixel g_strip(1, ONBOARD_LED_GPIO, NEO_GRB + NEO_KHZ800);

// --- 状態変数 ---
LedState g_currentLedState = LED_STATE_BOOT;
bool g_deviceConnected = false;   // Phase 5 (BLE) で true に設定

// 点滅タイミング
static unsigned long g_lastLedBlink = 0;
static bool g_ledBlinkState = false;

// 前回の色（不要な更新を回避）
static bool g_lastLedRed = false;
static bool g_lastLedGreen = false;
static bool g_lastLedBlue = false;

// --- セットアップ ---
void setupLed() {
    g_strip.begin();
    g_strip.setBrightness(LED_BRIGHTNESS);
    g_strip.clear();
    g_strip.show();
    g_currentLedState = LED_STATE_BOOT;
    logPrint("INIT", "Onboard RGB LED OK (GPIO48, NeoPixel, brightness=%d)", LED_BRIGHTNESS);
}

// --- 色設定（bool → RGB） ---
void setLedColor(bool red, bool green, bool blue) {
    g_strip.setPixelColor(0, g_strip.Color(
        red   ? 255 : 0,
        green ? 255 : 0,
        blue  ? 255 : 0));
    g_strip.show();
}

// --- 状態設定 ---
void setLedState(LedState state) {
    if (g_currentLedState != state) {
        g_currentLedState = state;
        g_ledBlinkState = false;  // 点滅状態リセット
        logPrint("LED", "State changed: %d", (int)state);
    }
}

// --- 接続/同期状態からLED状態を決定 ---
void updateLedStateBasedOnStatus() {
    if (g_deviceConnected) {
        setLedState(g_timeSynced ? LED_STATE_CONNECTED_SYNCED : LED_STATE_CONNECTED_NO_SYNC);
    } else {
        setLedState(g_timeSynced ? LED_STATE_SYNCED : LED_STATE_NO_SYNC);
    }
}

// --- LED更新（loop から呼ぶ） ---
void updateLed() {
    bool newRed = false;
    bool newGreen = false;
    bool newBlue = false;
    bool needsUpdate = false;

    switch (g_currentLedState) {
        case LED_STATE_BOOT:
            newRed = true;
            break;

        case LED_STATE_NO_SYNC:
            if (g_currentMillis - g_lastLedBlink >= 1000) {
                g_ledBlinkState = !g_ledBlinkState;
                g_lastLedBlink = g_currentMillis;
                needsUpdate = true;
            }
            newRed = g_ledBlinkState;
            break;

        case LED_STATE_SYNCED:
            newGreen = true;
            break;

        case LED_STATE_CONNECTED_NO_SYNC:
            if (g_currentMillis - g_lastLedBlink >= 1000) {
                g_ledBlinkState = !g_ledBlinkState;
                g_lastLedBlink = g_currentMillis;
                needsUpdate = true;
            }
            newBlue = g_ledBlinkState;
            break;

        case LED_STATE_CONNECTED_SYNCED:
            newBlue = true;
            break;

        case LED_STATE_ERROR:
            if (g_currentMillis - g_lastLedBlink >= 200) {
                g_ledBlinkState = !g_ledBlinkState;
                g_lastLedBlink = g_currentMillis;
                needsUpdate = true;
            }
            newRed = g_ledBlinkState;
            break;
    }

    // 色が変化したときだけ更新
    if (needsUpdate || newRed != g_lastLedRed || newGreen != g_lastLedGreen || newBlue != g_lastLedBlue) {
        setLedColor(newRed, newGreen, newBlue);
        g_lastLedRed = newRed;
        g_lastLedGreen = newGreen;
        g_lastLedBlue = newBlue;
    }
}
