/**
 * Power Management for CycleClock
 *
 * System OFF(待機電流~μA級)への出入りと、オンボードRGB LED制御。
 *
 * System OFF からの復帰:
 *   nRF52840 は GPIO の DETECT 信号(SENSE設定)で System OFF から復帰できる。
 *   復帰はリセット相当のコールドスタート=バイク版(bikeclock)の
 *   「キーオン→コールドブート→BLE同期」と同じ起動モデル。
 *   ウェイク素子(タクトスイッチ/SW-18020P)は受動素子のため静止時0消費。
 *
 * SoftDevice(Bluefruit)有効時は NRF_POWER->SYSTEMOFF 直接書き込みではなく
 * sd_power_system_off() を使う必要がある。
 */

#include "cycleclock.h"

// --- System OFF ---
void enterSystemOff() {
    // 押下されたまま System OFF に入ると DETECT が即成立して即時起床してしまう
    // (見かけ上「停止→再起動」になる)。離されるのを待ってから寝る。
    logPrint("POWER", "Sleep requested - waiting for switch release...");
    while (digitalRead(WAKE_SW_GPIO) == LOW) {
        delay(10);
    }
    delay(100);  // チャタリング解放分のマージン

    // 停車中の表示を「停止時点のスナップショット」に切り替える
    // (通常時計のままだと停車中に今の時刻と勘違いされるため・ゼロ電力で保持される)
    drawEpaperSleep();

    logPrint("POWER", "Entering System OFF (wake on %d LOW)", WAKE_SW_GPIO);
    Serial.flush();
    delay(50);

    // LED全消灯(コモンアノード: HIGH=消灯)
    pinMode(LED_RED, OUTPUT);
    pinMode(LED_GREEN, OUTPUT);
    pinMode(LED_BLUE, OUTPUT);
    digitalWrite(LED_RED, HIGH);
    digitalWrite(LED_GREEN, HIGH);
    digitalWrite(LED_BLUE, HIGH);

    // ウェイクピンを SENSE LOW(導通=LOW)で構成して System OFF に入る。
    // PIN_CNF は System OFF 中も保持され、導通が DETECT 信号を発生させる。
    // (内部プルアップは残るため、開放時0消費・導通時のみ瞬時電流が流れる)
    nrf_gpio_cfg_sense_input(g_ADigitalPinMap[WAKE_SW_GPIO],
                             NRF_GPIO_PIN_PULLUP, NRF_GPIO_PIN_SENSE_LOW);

    // 戻らない: 復帰はリセット相当のコールドスタート
    sd_power_system_off();

    // 万一戻った場合(ハード異常)は手動リセット
    delay(1000);
    NVIC_SystemReset();
}

// --- Onboard LED (XIAO BLE 共通アノードRGB: HIGH=消灯) ---
unsigned long g_lastLedBlink = 0;
bool g_ledBlinkState = false;

void setupLed() {
    pinMode(LED_RED, OUTPUT);
    pinMode(LED_GREEN, OUTPUT);
    pinMode(LED_BLUE, OUTPUT);
    setLedState(LED_STATE_BOOT);
}

void setLedColor(bool red, bool green, bool blue) {
    digitalWrite(LED_RED,   red   ? LOW : HIGH);
    digitalWrite(LED_GREEN, green ? LOW : HIGH);
    digitalWrite(LED_BLUE,  blue  ? LOW : HIGH);
}

void setLedState(LedState state) {
    if (state == g_currentLedState) return;
    g_currentLedState = state;
    g_lastLedBlink = 0;  // 点滅位相リセット

    switch (state) {
        case LED_STATE_BOOT:              setLedColor(true,  false, false); break;
        case LED_STATE_NO_SYNC:           setLedColor(true,  false, false); break;
        case LED_STATE_SYNCED:            setLedColor(false, true,  false); break;
        case LED_STATE_CONNECTED_NO_SYNC: setLedColor(false, false, true);  break;
        case LED_STATE_CONNECTED_SYNCED:  setLedColor(false, false, true);  break;
        case LED_STATE_ERROR:             setLedColor(true,  false, false); break;
    }
}

void setLedError() {
    setLedState(LED_STATE_ERROR);
}

void updateLedStateBasedOnStatus() {
    if (g_deviceConnected) {
        setLedState(g_timeSynced ? LED_STATE_CONNECTED_SYNCED : LED_STATE_CONNECTED_NO_SYNC);
    } else {
        setLedState(g_timeSynced ? LED_STATE_SYNCED : LED_STATE_NO_SYNC);
    }
}

void updateLed() {
    bool blink = false;
    unsigned long interval = 1000;

    switch (g_currentLedState) {
        case LED_STATE_NO_SYNC:
            blink = true;
            setLedColor(g_ledBlinkState, false, false);
            break;
        case LED_STATE_CONNECTED_NO_SYNC:
            blink = true;
            setLedColor(false, false, g_ledBlinkState);
            break;
        case LED_STATE_ERROR:
            blink = true;
            interval = 200;
            setLedColor(g_ledBlinkState, false, false);
            break;
        default:
            return;  // 点灯固定状態は何もしない
    }

    if (blink && g_currentMillis - g_lastLedBlink >= interval) {
        g_ledBlinkState = !g_ledBlinkState;
        g_lastLedBlink = g_currentMillis;
    }
}
