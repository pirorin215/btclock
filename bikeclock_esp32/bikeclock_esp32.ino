/**
 * BikeClock ESP32-S3 - Bicycle Clock for ESP32-S3 SuperMini
 *
 * [Phase 1] 表示 & 時刻ロジック
 *   - 時刻計算（getHours/getMonthDay/曜日 等）: XIAO 版から移植（完全非依存）
 *   - TM1637 表示（TIME/DATE/WEEKDAY/version）
 *   - タイムスタンプ更新（millis ベース）
 *   - 未同期時の「8888 点滅」
 *
 * LED/物理スイッチ/BLE は後のフェーズで追加。
 */

#include "bikeclock.h"

// === テスト設定 ===
// 1: 固定時刻で表示確認（時刻/日付/曜日計算の検証）
// 0: 未同期モード（8888 点滅）— Phase 5 で BLE 時刻同期が実装されるまでの実運用時は 0 にする
#define TEST_FIXED_TIME 1

// --- Global Variables ---
TM1637Display* g_display = nullptr;
volatile uint32_t g_currentTimestamp = 0;  // JST換算のUnix timestamp
bool g_timeSynced = false;
DisplayMode g_displayMode = DISPLAY_MODE_TIME;
unsigned long g_currentMillis = 0;
unsigned long g_lastScreenMillis = 0;
unsigned long g_lastCounterMillis = 0;
DateCache g_dateCache = {0, 0, 0, 0, false};
unsigned long g_startupMillis = 0;

// --- ロギング ---
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

// ====================================================================
// 時刻計算（XIAO 版 bikeclock.ino から移植、JST 換算ロジック）
// ====================================================================

int getHours() {
    return (g_currentTimestamp % 86400) / 3600;
}

int getMinutes() {
    return (g_currentTimestamp % 3600) / 60;
}

int getSeconds() {
    return g_currentTimestamp % 60;
}

// g_currentTimestamp は JST-adjusted（JST時刻をそのままUnix epoch換算した値）
uint32_t getDaysSinceEpoch() {
    return g_currentTimestamp / 86400;
}

// 日付（月・日）の計算
void getMonthDay(int* month, int* day) {
    // キャッシュ確認
    if (g_dateCache.valid && g_dateCache.lastTimestamp == g_currentTimestamp) {
        *month = g_dateCache.month;
        *day = g_dateCache.day;
        return;
    }

    uint32_t days = getDaysSinceEpoch();
    uint32_t year = 1970;
    uint32_t days_in_year;

    // 年を特定
    while (true) {
        bool is_leap = (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0));
        days_in_year = is_leap ? 366 : 365;
        if (days < days_in_year) break;
        days -= days_in_year;
        year++;
    }

    // 月を特定
    static const uint8_t days_in_month[] = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    int m = 0;
    bool is_leap = (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0));
    uint8_t dim_feb = is_leap ? 29 : 28;

    for (m = 0; m < 12; m++) {
        uint8_t dim = (m == 1) ? dim_feb : days_in_month[m];
        if (days < dim) break;
        days -= dim;
    }

    // キャッシュ更新（weekday: 1970-01-01 は木曜日 = 4）
    g_dateCache.month = m + 1;
    g_dateCache.day = days + 1;
    g_dateCache.weekday = (getDaysSinceEpoch() + 4) % 7;
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

// ====================================================================
// システム
// ====================================================================

// タイムスタンプ更新（1秒ごとに+1）
void updateTimestamp() {
    if (g_currentMillis - g_lastCounterMillis >= 1000) {
        g_currentTimestamp++;
        g_lastCounterMillis = g_currentMillis;
    }
}

// 表示状態更新（カウントダウン、キーコード表示、オートリターン対応）
void updateDisplayAndLedState() {
    // カウントダウン表示中は表示更新をスキップ
    if (g_showingCountdown) {
        return;
    }

    // キーコード表示中の処理
    if (g_displayingKeyCode != 0) {
        if (g_currentMillis < g_keyCodeDisplayEndTime) {
            return; // 表示継続中
        } else {
            g_displayingKeyCode = 0;
            g_keyCodeDisplayEndTime = 0;
            logPrint("HID", "Key code display timeout - resuming normal display");
        }
    }

    // オートリターン (DATE / WEEKDAY モードで 5秒無操作で TIME に戻る)
    if ((g_displayMode == DISPLAY_MODE_DATE || g_displayMode == DISPLAY_MODE_WEEKDAY) &&
        (g_currentMillis - g_lastModeChangeMillis >= MODE_AUTO_RETURN_TIMEOUT_MS)) {
        logPrint("MODE", "Auto-returning to time mode (timeout)");
        g_displayMode = DISPLAY_MODE_TIME;
        g_lastModeChangeMillis = g_currentMillis;
        updateDisplayForCurrentMode();
        updateLedStateBasedOnStatus();
        return;
    }

    if (!g_timeSynced && g_displayMode != DISPLAY_MODE_TEST) {
        // 未同期: 8888 点滅
        static unsigned long lastBlink = 0;
        if (g_currentMillis - lastBlink >= 500) {
            static bool showPattern = true;
            if (showPattern) {
                g_display->showNumberDec(8888);
            } else {
                g_display->clear();
            }
            showPattern = !showPattern;
            lastBlink = g_currentMillis;
        }
        updateLedStateBasedOnStatus();
    } else {
        // 同期済みまたはテストモード: 現在モードで表示（1秒ごと更新）
        updateLedStateBasedOnStatus();
        if (g_currentMillis - g_lastScreenMillis >= DISPLAY_UPDATE_INTERVAL_MS) {
            updateDisplayForCurrentMode();
            g_lastScreenMillis = g_currentMillis;
        }
    }
}

#include <esp_system.h>

// --- Reset Reason ---
const char* getResetReasonText(esp_reset_reason_t reason) {
    switch (reason) {
        case ESP_RST_UNKNOWN:   return "Unknown";
        case ESP_RST_POWERON:   return "Power On";
        case ESP_RST_EXT:       return "External Pin";
        case ESP_RST_SW:        return "Software";
        case ESP_RST_PANIC:     return "Panic/Crash";
        case ESP_RST_INT_WDT:   return "Interrupt WDT";
        case ESP_RST_TASK_WDT:  return "Task WDT";
        case ESP_RST_WDT:       return "Other WDT";
        case ESP_RST_DEEPSLEEP: return "Deep Sleep";
        case ESP_RST_BROWNOUT:  return "Brownout";
        case ESP_RST_SDIO:      return "SDIO";
        default:                return "Other";
    }
}
const char* g_resetReasonStr = "Unknown";

// ====================================================================
// setup / loop
// ====================================================================

void setup() {
    // 再起動理由を最初に取得
    g_resetReasonStr = getResetReasonText(esp_reset_reason());

    Serial.begin(115200);
    // USB-CDC のブロッキングを防止 (1msでタイムアウトさせる = 非ブロッキング)
    // 0だと逆に無限待ちになる環境があるため 1 に設定
    Serial.setTxTimeoutMs(1);
    delay(500);  // USB-CDC の準備待ち (少し余裕を持たせる)

    setupLog();
    logPrint("INIT", "Reset Reason: %s", g_resetReasonStr);

    Serial.println();
    Serial.println("========================================");
    Serial.println("BikeClock ESP32-S3 SuperMini");
    Serial.printf("Firmware Version: %d.%d.%d\n",
                 FIRMWARE_VERSION_MAJOR, FIRMWARE_VERSION_MINOR, FIRMWARE_VERSION_PATCH);
    Serial.printf("Reset Reason: %s\n", g_resetReasonStr);
    Serial.println("[Phase 3] Display + LED + BLE + ePaper + Switches");
    Serial.println("========================================");

    // TM1637 初期化
    g_display = new TM1637Display(LED_CLK_GPIO, LED_DIO_GPIO);
    g_display->setBrightness(0x0F);
    g_display->clear();
    logPrint("INIT", "Display OK (CLK=%d, DIO=%d)", LED_CLK_GPIO, LED_DIO_GPIO);

    // 物理スイッチ初期化（Phase 3）
    extern void setupSwitches();
    setupSwitches();

    // オンボードRGB LED 初期化（Phase 2）
    setupLed();

    // ePaper 初期化（昼間視認性用、Phase 2.5）— ブートスプラッシュ表示
    setupEpaper();

    // バージョン表示（1秒）
    logPrint("INIT", "Displaying firmware version...");
    displayVersion();
    delay(1000);

    // ファイルシステム初期化（Phase 4: LittleFS。setupBLE 内の loadSettings より先にマウント）
    setupFileSystem();

    // BLE 初期化（Phase 5）
    setupBLE();

#if TEST_FIXED_TIME
    // テスト用固定時刻: 2026-06-15(月) 12:34:56 JST換算
    g_currentTimestamp = 1781526896UL;
    g_timeSynced = true;
    logPrint("TEST", "Fixed time mode: 2026-06-15(Mon) 12:34:56 (ts=%lu)", (unsigned long)g_currentTimestamp);
    logPrint("TEST", "  -> getHours=%d getMinutes=%d getSeconds=%d", getHours(), getMinutes(), getSeconds());
    logPrint("TEST", "  -> month=%d day=%d weekday=%d(0=Sun)", getMonth(), getDay(), getWeekday());
#else
    g_timeSynced = false;
    logPrint("INIT", "Unsynced mode (8888 blink)");
#endif

    g_lastCounterMillis = millis();
    g_lastScreenMillis = millis();

    // LED状態を接続/同期状況に合わせる（Phase 2）
    updateLedStateBasedOnStatus();

    logPrint("INIT", "Setup complete");
}

void loop() {
    g_currentMillis = millis();

    // 最初にファンクションキー（モード切替）の処理
    processFunctionKey();

    // メンテナンスモードの処理
    if (!processMaintenanceMode()) {
        // メンテナンスモードがアクティブでない場合のみ、通常処理を行う
        updateLed();
        processHidSwitches();
        updateTimestamp();
        updateDisplayAndLedState();
        updateEpaperDisplay();
    }

    delay(10);
}
