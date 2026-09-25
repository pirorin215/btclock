/**
 * CycleClock - XIAO BLE based bicycle ePaper clock
 *
 * 動作モデル(バイク版 bikeclock と同じ寿命モデルを振動ウェイクで再現):
 * - 振動(開発中はタクトスイッチ)で System OFF からコールドスタート
 * - BLEアドバタイズ → スマホアプリ(BTClockMob)が自動接続 → 時刻同期
 * - ePaper(WeAct 2.13")に時計を表示(分変化でフル更新)
 * - 乗車終了(BLE切断)後、SLEEP_IDLE_TIMEOUT_MS で System OFF へ戻る
 * - 電源は18650をBATパッド直結(オンボード充電器でUSB充電可)
 *
 * 移植元:
 * - BLE・時刻処理: bikeclock/ (XIAO BLE版 v1.1.6)
 * - ePaper描画: bikeclock_esp32/ (v2.0.61, GxEPD2_213_B74)
 */

#include "cycleclock.h"

// --- Global Variables ---
volatile uint32_t g_currentTimestamp = 0;  // Unix timestamp (JST換算・アプリ側が+9h済みの値)
bool g_timeSynced = false;
bool g_deviceConnected = false;
unsigned long g_lastCounterMillis = 0;
unsigned long g_currentMillis = 0;
unsigned long g_startupMillis = 0;
unsigned long g_lastActivityMs = 0;
LedState g_currentLedState = LED_STATE_BOOT;
DateCache g_dateCache = {0, 0, 0, 0, 0, false};

// --- Time Helper Functions (bikeclock.ino から移植) ---
int getHours() {
    return (g_currentTimestamp % 86400) / 3600;
}

int getMinutes() {
    return (g_currentTimestamp % 3600) / 60;
}

int getSeconds() {
    return g_currentTimestamp % 60;
}

uint32_t getDaysSinceEpoch() {
    return g_currentTimestamp / 86400;
}

void getMonthDay(int* month, int* day) {
    if (g_dateCache.valid && g_dateCache.lastTimestamp == g_currentTimestamp) {
        *month = g_dateCache.month;
        *day = g_dateCache.day;
        return;
    }

    uint32_t days = getDaysSinceEpoch();
    uint32_t year = 1970;
    uint32_t days_in_year;

    while (true) {
        bool is_leap = (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0));
        days_in_year = is_leap ? 366 : 365;
        if (days < days_in_year) break;
        days -= days_in_year;
        year++;
    }

    static const uint8_t days_in_month[] = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    int m = 0;

    bool is_leap = (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0));
    uint8_t dim_feb = is_leap ? 29 : 28;

    for (m = 0; m < 12; m++) {
        uint8_t dim = (m == 1) ? dim_feb : days_in_month[m];
        if (days < dim) break;
        days -= dim;
    }

    g_dateCache.month = m + 1;
    g_dateCache.day = days + 1;
    g_dateCache.weekday = (getDaysSinceEpoch() + 4) % 7;
    g_dateCache.year = (uint32_t)year;
    g_dateCache.lastTimestamp = g_currentTimestamp;
    g_dateCache.valid = true;

    *month = g_dateCache.month;
    *day = g_dateCache.day;
}

int getMonth() {
    int month, day;
    getMonthDay(&month, &day);
    return month;
}

int getDay() {
    int month, day;
    getMonthDay(&month, &day);
    return day;
}

int getWeekday() {
    if (g_dateCache.valid && g_dateCache.lastTimestamp == g_currentTimestamp) {
        return g_dateCache.weekday;
    }
    int month, day;
    getMonthDay(&month, &day);
    return g_dateCache.weekday;
}

int getYear() {
    if (!g_dateCache.valid || g_dateCache.lastTimestamp != g_currentTimestamp) {
        int month, day;
        getMonthDay(&month, &day);
    }
    return g_dateCache.year;
}

// --- System Utilities ---
void updateTimestamp() {
    if (g_currentMillis - g_lastCounterMillis >= 1000) {
        g_currentTimestamp++;
        g_lastCounterMillis = g_currentMillis;
    }
}

// --- Logging ---
void setupLog() {
    g_startupMillis = millis();
}

void logPrint(const char* tag, const char* format, ...) {
    unsigned long elapsed = millis() - g_startupMillis;

    Serial.printf("[%4lu.%03lu] ", elapsed / 1000, elapsed % 1000);

    if (tag != nullptr && tag[0] != '\0') {
        Serial.printf("[%s] ", tag);
    }

    char buffer[256];
    va_list args;
    va_start(args, format);
    vsnprintf(buffer, sizeof(buffer), format, args);
    va_end(args);

    Serial.println(buffer);
}

// --- ウェイクスイッチ処理 ---
// 短押し = 活動とみなしスリープタイマーをリセット
// 長押し(2秒以上) = 手動 System OFF(待機電流実測・テスト用)
static bool s_swStableState = HIGH;       // プルアップなので HIGH=未押下
static bool s_swLastReading = HIGH;
static unsigned long s_swLastDebounceMs = 0;
static unsigned long s_swPressStartMs = 0;

void processWakeSwitch() {
    bool reading = digitalRead(WAKE_SW_GPIO);

    if (reading != s_swLastReading) {
        s_swLastDebounceMs = g_currentMillis;
        s_swLastReading = reading;
    }

    if ((g_currentMillis - s_swLastDebounceMs) >= 50) {
        if (reading != s_swStableState) {
            s_swStableState = reading;
            if (s_swStableState == LOW) {
                // 押下開始
                s_swPressStartMs = g_currentMillis;
                g_lastActivityMs = g_currentMillis;
                logPrint("SW", "Wake switch pressed");
            } else {
                // 離した(長押し判定は押下中にも行うためここでは短押し確定のみ)
                logPrint("SW", "Wake switch released (short)");
            }
        } else if (s_swStableState == LOW) {
            // 押下継続: 長押しで System OFF
            if (s_swPressStartMs != 0 &&
                (g_currentMillis - s_swPressStartMs) >= WAKE_SW_LONGPRESS_MS) {
                s_swPressStartMs = 0;  // 多重発火防止
                logPrint("SW", "Long press - manual System OFF");
                enterSystemOff();      // 戻らない
            }
        }
    }
}

// --- スリープ判定 ---
void checkSleepTimeout() {
    if (g_deviceConnected) return;  // 接続中は寝ない
    if (g_currentMillis - g_lastActivityMs < SLEEP_IDLE_TIMEOUT_MS) return;

    logPrint("SLEEP", "Idle timeout (%lus) - entering System OFF",
             (unsigned long)(SLEEP_IDLE_TIMEOUT_MS / 1000));
    enterSystemOff();  // 戻らない
}

// --- Main Functions ---
void setup() {
    Serial.begin(115200);
    setupLog();

    logPrint("CYCLECLOCK", __DATE__ " " __TIME__);

    // 起動要因の判別(SoftDevice起動前=直接レジスタ読み・write-1-to-clear)
    // System OFF からの起床なら RESETREAS.OFF が立っている
    {
        uint32_t reason = NRF_POWER->RESETREAS;
        NRF_POWER->RESETREAS = 0xFFFFFFFF;
        if (reason & POWER_RESETREAS_OFF_Msk) {
            logPrint("POWER", "Wakeup from System OFF (wake switch)");
        } else if (reason & POWER_RESETREAS_RESETPIN_Msk) {
            logPrint("POWER", "Reset from RESET pin");
        }
    }

    // 電源安定待ち(電池コールドスタート直後のePaper初期化失敗回避)
    delay(500);

    // ウェイクスイッチ(内部プルアップ・導通=LOW)
    pinMode(WAKE_SW_GPIO, INPUT_PULLUP);

    setupLed();

    // ePaper初期化+スプラッシュ表示
    setupEpaper();

    // BLE初期化+アドバタイズ開始
    setupBLE();

    g_lastCounterMillis = millis();
    g_lastActivityMs = millis();

    logPrint("INIT", "Ready - waiting for BLE connection...");
}

void loop() {
    g_currentMillis = millis();

    processWakeSwitch();
    updateLed();
    updateTimestamp();
    updateEpaperDisplay();
    checkSleepTimeout();

    delay(10);
}
