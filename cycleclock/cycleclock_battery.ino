/**
 * Battery voltage monitoring for CycleClock
 *
 * fastrec2 (XIAO BLE・Zephyr) の battery.c 方式をArduinoに移植:
 * - XIAO BLE 内蔵 VBAT 分圧: BAT+ → 分圧(1510k/510k) → P0.31 (PIN_VBAT=AIN7)
 * - 分圧スイッチ VBAT_ENABLE (P0.14): active-LOW。測定時のみLOWで接続し、
 *   測定後はHIGHへ戻す(接続しっぱなしだと~2μAリークしSystem OFF予算を圧迫)
 * - initVariant() が起動時にHIGH(切断)へ初期化済み
 *
 * 設計上の重要な分流(fastrec2の2026-09-09クラッシュ教训):
 * - SAADCへのアクセス(analogRead)は loop() 由来の updateBattery() のみから行う。
 *   BLEコールバック(SoftDeviceコンテキスト)から呼ぶとnrfxアサートでカーネルoopsに
 *   なる実績あり。GET:battery ハンドラはキャッシュ値のみ返す。
 * - 高抵抗(1M級)分圧のため、冒頭サンプルを捨てて平均で安定化。
 * - 負荷直後(BLE通信・ePaper更新直後)の測定は低めに出るため、定期的な
 *   アイドル時キャッシュ測定を表示・報告に使う(fastrec2と同じ考え方)。
 */

#include "cycleclock.h"

// 分圧比(1510k/510k=2.96・fastrec2で満充電4.15Vと整合確認済み)。
// 実機テスタ校正が必要な場合はこの値を調整する。
#define BATT_DIV_MULT     2.96f

// 測定: 8サンプル中先頭2個を捨てて平均(高抵抗源のRC安定待ち)
#define BATT_SAMPLE_COUNT   8
#define BATT_SAMPLE_DISCARD 2

static float s_battVolts = 0.0f;
static unsigned long s_lastBattMs = 0;
static bool s_battValid = false;

// loop()から定期的に呼ぶ(BLE_REFRESH間隔)。キャッシュ値を更新する。
void updateBattery() {
    if (g_currentMillis - s_lastBattMs < BATTERY_REFRESH_MS) return;
    s_lastBattMs = g_currentMillis;

    // 分圧接続 → 安定待ち → サンプル → 切断
    pinMode(VBAT_ENABLE, OUTPUT);
    digitalWrite(VBAT_ENABLE, LOW);
    delay(10);

    analogReadResolution(12);
    analogReference(AR_INTERNAL);   // 0.6V ref × 6 = フルスケール3.6V

    uint32_t sum = 0;
    int used = 0;
    for (int i = 0; i < BATT_SAMPLE_COUNT; i++) {
        uint16_t raw = analogRead(PIN_VBAT);
        if (i >= BATT_SAMPLE_DISCARD) {
            sum += raw;
            used++;
        }
    }

    digitalWrite(VBAT_ENABLE, HIGH);   // 分圧切断(待機電流保護・必須)

    if (used > 0) {
        s_battVolts = ((float)sum / used / 4095.0f) * 3.6f * BATT_DIV_MULT;
        s_battValid = true;
        logPrint("BATT", "VBAT %.2fV (raw avg %lu)", (double)s_battVolts, (unsigned long)(sum / used));
    }
}

// 純粋なキャッシュ読み出し: ADCに触らないためどの文脈(BLEコールバック含む)から
// 呼んでも安全。未測定の場合は負値を返す。
float batteryVoltageCached() {
    return s_battValid ? s_battVolts : -1.0f;
}
