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
 * - 負荷直後(BLE通信・ePaper更新直後)の測定は低めに出るため、パネルBUSY中は
 *   測定自体をスキップする(epaperIdle()ガード・実機2026-09-29で増強: スリープ
 *   残画のピクトが時計より低く描かれる原因は更新中測定のキャッシュ汚染だった)。
 * - 残量表示(v0.3.7)は直近BATT_MEDIAN_WINDOWサンプル(5分)の中央値。ピクトが
 *   表現すべきは「任意タイミングの電圧」ではなく「残量」=弛緩電圧であり、
 *   単発の負荷dipはノイズとして無視する。実際の放電はゆっくりなので
 *   実変更には5分以内に追従する。raw値はログで確認できる。
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
static bool s_firstPending = true;   // 起動直後に即測定(初回GET:batteryに間に合わせる)
static bool s_lowBattActive = false; // 低電圧警告ラッチ(ヒステリシスで解除)
static bool s_warnActive = false;    // 警告「!」ラッチ(時計画面のピクト横に表示)
static float s_rawHist[BATT_MEDIAN_WINDOW]; // raw測定履歴(古い順・中央値フィルタ用)
static int   s_rawHistLen = 0;

// 挿入ソートで中央値を取る(n<=5なので単純実装で十分)。
static float battMedian(const float* v, int n) {
    float tmp[BATT_MEDIAN_WINDOW];
    memcpy(tmp, v, sizeof(float) * n);
    for (int i = 1; i < n; i++) {
        float key = tmp[i];
        int j = i - 1;
        while (j >= 0 && tmp[j] > key) {
            tmp[j + 1] = tmp[j];
            j--;
        }
        tmp[j + 1] = key;
    }
    return tmp[n / 2];
}

// loop()から定期的に呼ぶ(BATTERY_REFRESH間隔)。キャッシュ値を更新する。
void updateBattery() {
    // パネル物理更新中の測定は禁止: ePaperの負荷スパイクで数十〜数百mV低めに
    // 落ちる(fastrec2の「アイドル時キャッシュ測定」教訓。安電源の電流制限下では
    // 特に大きく出る)。BUSY=HIGHは更新中(B74極性・BW版フル更新は2-3秒)。
    // スキップ中はs_lastBattMsを進めないので、idleに戻り次第すぐ測る。
    if (!epaperIdle()) return;

    // 初回(起動直後)は即測定。以降は60秒間隔。
    if (!s_firstPending && g_currentMillis - s_lastBattMs < BATTERY_REFRESH_MS) return;
    s_firstPending = false;
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
        float raw = ((float)sum / used / 4095.0f) * 3.6f * BATT_DIV_MULT;

        // raw履歴を更新(古い順・最大BATT_MEDIAN_WINDOW件)し中央値を表示値にする
        if (s_rawHistLen < BATT_MEDIAN_WINDOW) {
            s_rawHist[s_rawHistLen++] = raw;
        } else {
            memmove(s_rawHist, s_rawHist + 1, sizeof(float) * (BATT_MEDIAN_WINDOW - 1));
            s_rawHist[BATT_MEDIAN_WINDOW - 1] = raw;
        }
        s_battVolts = battMedian(s_rawHist, s_rawHistLen);
        s_battValid = true;
        logPrint("BATT", "VBAT %.2fV (raw %.2fV, %d samples)",
                 (double)s_battVolts, (double)raw, s_rawHistLen);

        bool prev = s_lowBattActive;
        if (s_battVolts <= BATT_LOW_THRESHOLD_V) {
            s_lowBattActive = true;
        } else if (s_battVolts >= BATT_LOW_CLEAR_V) {
            s_lowBattActive = false;
        }
        if (s_lowBattActive != prev) {
            logPrint("BATT", "Low battery warning %s", s_lowBattActive ? "ON" : "OFF");
        }

        bool prevWarn = s_warnActive;
        if (s_battVolts < BATT_WARN_THRESHOLD_V) {
            s_warnActive = true;
        } else if (s_battVolts >= BATT_WARN_CLEAR_V) {
            s_warnActive = false;
        }
        if (s_warnActive != prevWarn) {
            logPrint("BATT", "Battery warn mark %s", s_warnActive ? "ON" : "OFF");
        }
    }
}

// 純粋なキャッシュ読み出し: ADCに触らないためどの文脈(BLEコールバック含む)から
// 呼んでも安全。未測定の場合は負値を返す。
float batteryVoltageCached() {
    return s_battValid ? s_battVolts : -1.0f;
}

// 低電圧警告ラッチの状態。updateBattery() の測定結果からのみ変化するため
// どの文脈から呼んでも安全(ePaperのビュー判定・スリープ画面で使用)。
bool batteryLowActive() {
    return s_lowBattActive;
}

// 警告「!」ラッチの状態(3.65V未満でON・3.70V以上でOFF)。時計画面の
// 電池ピクトグラム横の注意表示に使う。
bool batteryWarnActive() {
    return s_warnActive;
}

// 電池ピクトグラムの段階数(0-5)。3.60-4.10Vを0.1V刻みで5段階。
// 未測定は-1(描画しない)。<3.60Vは0本の空枠、>=4.00Vは満枠。
int batteryLevelPict() {
    if (!s_battValid) return -1;
    if (s_battVolts < BATT_PICT_MIN_V) return 0;
    int lvl = (int)((s_battVolts - BATT_PICT_MIN_V) / BATT_PICT_STEP_V) + 1;
    if (lvl > 5) lvl = 5;
    return lvl;
}
