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
    // ただし導通が続く(SW-18020P誘導導通・断線ショート等)場合にここで永久ハング
    // すると System OFF に入れず電池を食い潰すため、上限付きで待つ。
    // 導通中に寝れば即再起床(見かけ上の再起動)するが、振動が止まれば自己解決する。
    logPrint("POWER", "Sleep requested - waiting for switch release...");
    uint32_t releaseWaitStart = millis();
    while (digitalRead(WAKE_SW_GPIO) == LOW) {
        if (millis() - releaseWaitStart > WAKE_SW_RELEASE_TIMEOUT_MS) {
            logPrint("POWER", "Switch conducting for %d ms - entering System OFF anyway",
                     (int)WAKE_SW_RELEASE_TIMEOUT_MS);
            break;
        }
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
// 常時点灯系(BOOT/SYNCED/CONNECTED_SYNCED): 超低デューティPWM(約1.2%)の常時薄点灯。
// 点滅系(NO_SYNC/CONNECTED_NO_SYNC/ERROR): ほぼ消灯で、intervalごとに
// ごく短いパルス(50ms)だけ点灯(自作キーボード界隈の「ほぼ消えているが生きている」LED)。
unsigned long g_lastLedBlink = 0;
bool g_ledPulseOn = false;

void setupLed() {
    pinMode(LED_RED, OUTPUT);
    pinMode(LED_GREEN, OUTPUT);
    pinMode(LED_BLUE, OUTPUT);
    // 全消灯(common anode: HIGH=消灯)
    digitalWrite(LED_RED, HIGH);
    digitalWrite(LED_GREEN, HIGH);
    digitalWrite(LED_BLUE, HIGH);
    g_currentLedState = LED_STATE_BOOT;
    g_ledPulseOn = false;
    setLedColor(true, false, false);
}

// 1色分の点灯制御。点灯はanalogWriteの超低デューティPWMで暗色化し平均電流を削減。
// 消灯時はdigitalWriteでGPIOへ戻しPWMを確実に止める(System OFF前の全消灯も兼ねる)。
static void ledWrite(uint8_t pin, bool on) {
    if (on) {
        analogWrite(pin, LED_DIM_PWM_VALUE);
    } else {
        digitalWrite(pin, HIGH);
    }
}

void setLedColor(bool red, bool green, bool blue) {
    ledWrite(LED_RED,   red);
    ledWrite(LED_GREEN, green);
    ledWrite(LED_BLUE,  blue);
}

void setLedState(LedState state) {
    if (state == g_currentLedState) return;
    g_currentLedState = state;
    g_lastLedBlink = 0;                 // パルス位相リセット
    g_ledPulseOn = false;
    setLedColor(false, false, false);   // 一旦消灯(点滅系はこれで基本消灯となる)

    switch (state) {
        case LED_STATE_BOOT:              setLedColor(true,  false, false); break;
        case LED_STATE_SYNCED:            setLedColor(false, true,  false); break;
        case LED_STATE_CONNECTED_SYNCED:  setLedColor(false, false, true);  break;
        // 点滅系(NO_SYNC/CONNECTED_NO_SYNC/ERROR)は updateLed() のパルス駆動に委ねる
        default: break;
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

// 点滅系状態のパルス色
static void pulseColor() {
    switch (g_currentLedState) {
        case LED_STATE_NO_SYNC:           setLedColor(true,  false, false); break;
        case LED_STATE_CONNECTED_NO_SYNC: setLedColor(false, false, true);  break;
        case LED_STATE_ERROR:             setLedColor(true,  false, false); break;
        default: break;
    }
}

// loopから毎回呼ぶ。点滅系は「(interval - LED_PULSE_MS)消灯 → LED_PULSE_MS点灯」を繰り返す。
void updateLed() {
    unsigned long interval;
    switch (g_currentLedState) {
        case LED_STATE_NO_SYNC:
        case LED_STATE_CONNECTED_NO_SYNC: interval = LED_PULSE_INTERVAL_MS; break;
        case LED_STATE_ERROR:             interval = LED_ERROR_INTERVAL_MS; break;
        default: return;  // 常時点灯系は変化なし
    }

    unsigned long elapsed = g_currentMillis - g_lastLedBlink;
    if (!g_ledPulseOn) {
        if (elapsed >= interval - LED_PULSE_MS) {
            g_ledPulseOn = true;
            g_lastLedBlink = g_currentMillis;
            pulseColor();
        }
    } else {
        if (elapsed >= LED_PULSE_MS) {
            g_ledPulseOn = false;
            g_lastLedBlink = g_currentMillis;
            setLedColor(false, false, false);
        }
    }
}
