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
unsigned long g_lastRideEventMs = 0;  // 最終振動(スイッチ導通)検出時刻
LedState g_currentLedState = LED_STATE_BOOT;
DateCache g_dateCache = {0, 0, 0, 0, 0, false};

// --- Notification (bikeclock_esp32 Phase 10 から移植) ---
volatile bool g_notificationActive = false;      // BLEコールバック(onWrite)が立てる
unsigned long g_notificationEndTime = 0;
char g_notificationApp[NOTIFY_APP_LEN] = {0};    // アプリ名(ログ用・描画未使用)
char g_notificationText[NOTIFY_TEXT_LEN] = {0};  // 通知本文
volatile uint32_t g_notificationSeq = 0;         // 受信連番(手動通知モードの再描画判定)

// --- Display Mode (FUNCキー機構・bikeclock から移植) ---
DisplayMode g_displayMode = DISPLAY_MODE_TIME;
unsigned long g_lastModeChangeMillis = 0;

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

// 閏年判定(グレゴリオ暦)
static bool isLeapYear(uint32_t year) {
    return year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
}

void getMonthDay(int* month, int* day) {
    if (g_dateCache.valid && g_dateCache.lastTimestamp == g_currentTimestamp) {
        *month = g_dateCache.month;
        *day = g_dateCache.day;
        return;
    }

    uint32_t days = getDaysSinceEpoch();
    uint32_t year = 1970;
    uint32_t daysInYear;

    while ((daysInYear = isLeapYear(year) ? 366 : 365) <= days) {
        days -= daysInYear;
        year++;
    }

    static const uint8_t days_in_month[] = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    const uint8_t dimFeb = (daysInYear == 366) ? 29 : 28;

    int m = 0;
    for (m = 0; m < 12; m++) {
        uint8_t dim = (m == 1) ? dimFeb : days_in_month[m];
        if (days < dim) break;
        days -= dim;
    }

    g_dateCache.month = m + 1;
    g_dateCache.day = days + 1;
    g_dateCache.weekday = (getDaysSinceEpoch() + 4) % 7;
    g_dateCache.year = year;
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
    int month, day;
    getMonthDay(&month, &day);   // キャッシュ命中時は即返る(月日ではなく曜日を使う)
    return g_dateCache.weekday;
}

int getYear() {
    int month, day;
    getMonthDay(&month, &day);
    return g_dateCache.year;
}

// --- System Utilities ---
// 経過秒を一括加算し、ミリ秒端数は次回へ繰り越す。ePaperのフル更新や
// BLE処理でloopが詰まっても時計の遅れが蓄積しない(旧実装は1呼び出し最大+1秒で、
// 更新のたびに数秒ずつ遅れ、end=current式で端数も毎秒捨てていた)。
void updateTimestamp() {
    unsigned long elapsed = g_currentMillis - g_lastCounterMillis;
    if (elapsed >= 1000) {
        unsigned long sec = elapsed / 1000;
        g_currentTimestamp += (uint32_t)sec;
        g_lastCounterMillis += sec * 1000;   // 端数を繰り越す
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

// --- スイッチ入力のチャタリング除去(D0ウェイク/FUNC共通) ---
// 50ms安定ではじめて変化を確定させ、確定エッジ(押下/解放)検出時のみtrueを返す。
static bool debounceEdge(DebouncedSwitch& sw, int pin) {
    bool reading = digitalRead(pin);
    if (reading != sw.lastReading) {
        sw.lastDebounceMs = g_currentMillis;
        sw.lastReading = reading;
    }
    if (g_currentMillis - sw.lastDebounceMs >= 50 && reading != sw.stable) {
        sw.stable = reading;
        return true;
    }
    return false;
}

// --- ウェイクスイッチ処理(D0・振動センサー) ---
// 押下(導通) = 振動パルス相当の活動。スリープタイマーをリセットし、
//   非TIMEモード表示中なら時計(主画面)へ戻す
// (1ms級パルスの取りこぼし対策はGPIOTE検出 processWakePulse() が担当。
//  手動で寝る経路は無く、無振動3分放置で System OFF に入る)
static DebouncedSwitch s_wakeSw = { HIGH, HIGH, 0 };

void processWakeSwitch() {
    if (!debounceEdge(s_wakeSw, WAKE_SW_GPIO)) return;
    if (s_wakeSw.stable == LOW) {
        // 押下開始 = 振動パルス検出(スリープタイマーをリセット)
        g_lastRideEventMs = g_currentMillis;
        logPrint("SW", "Wake switch pressed (ride event)");
    } else {
        // 離した = 乗車イベント。乗車中は時計が主画面なので非TIMEモードなら戻す
        if (g_displayMode != DISPLAY_MODE_TIME) {
            g_displayMode = DISPLAY_MODE_TIME;
            logPrint("SW", "Ride event - return to clock");
        } else {
            logPrint("SW", "Wake switch released (short)");
        }
    }
}

// --- 振動パルス検出(v0.4.8・GPIOTE割り込み) ---
// SW-18020Pの導通パルスは1ms級の瞬間導通で、System OFFからの復帰はDETECT(ハード)が
// 拾えるが起床後はソフト検出のみ。50msデバウンス(v0.4.6以前)もloop毎のレベル
// ポーリング(v0.4.7)でも短すぎて取りこぼすため、GPIOTEチャネルのFALLINGイベントで
// 検出する。イベントはハードがラッチするためパルス終了後も検出は失われない。
// ISRはフラグを立てるだけにし、タイマー延長とログ出力はloop側で行う
// (連続振動でのログ氾濫防止に間隔制限)。
static volatile bool s_pulseDetected = false;
static unsigned long s_lastPulseLogMs = 0;

static void wakePulseISR() {
    s_pulseDetected = true;
}

void processWakePulse() {
    if (!s_pulseDetected) return;
    s_pulseDetected = false;

    g_lastRideEventMs = g_currentMillis;
    if (g_currentMillis - s_lastPulseLogMs >= WAKE_PULSE_LOG_INTERVAL_MS) {
        s_lastPulseLogMs = g_currentMillis;
        logPrint("SW", "D0 pulse - sleep timer extended");
    }
}

// --- FUNCキー処理(v0.3.9・専用GPIO=D2) ---
// 短押し(クリック)で表示モードを順送り。bikeclockのFUNCキーと同じ。
// D0(振動)と分離済みのため走行中の振動で誤発動しない。長押しは未使用
// (bikeclockのメンテナンスメニューモードへの拡張余地)。
// v0.4.1: D2もSystem OFFからのウェイクピンのため、FUNC押下で起床した直後の
// releaseはモード切替としない(起こすための押下と切替操作を分離する)。
static DebouncedSwitch s_funcKey = { HIGH, HIGH, 0 };
static bool s_fkIgnoreFirstRelease = false;  // FUNC押下で起床した場合の初回release無視

void processFuncKey() {
    if (!debounceEdge(s_funcKey, FUNC_SW_GPIO)) return;
    if (s_funcKey.stable != HIGH) return;   // 押下開始では何もしない(解放でクリック確定)

    if (s_fkIgnoreFirstRelease) {
        // FUNCキー押下でSystem OFFから起床した場合の「離した」。
        // 起床操作自体はモード切替としない
        s_fkIgnoreFirstRelease = false;
        logPrint("FUNC", "Wake release ignored");
        return;
    }
    // 離した(クリック確定)でモードを順送り
    g_displayMode = (DisplayMode)((g_displayMode + 1) % DISPLAY_MODE_COUNT);
    g_lastModeChangeMillis = g_currentMillis;
    logPrint("FUNC", "Mode changed to %d", (int)g_displayMode);
}

// --- スリープ判定 ---
// 振動系の導通(乗車中の振動/タクト押下)が RIDE_INACTIVITY_TIMEOUT_MS 無ければ
// System OFF に入る。「振動がある=乗っている」は物理的事実なので、スマホの位置に
// 依存せず確実に眠る。逆に振動が続く限り(信号待ち含む)起き続ける。
// BLE接続中はアプリで操作/閲覧中とみなし判定自体を停止する(v0.4.9)。切断後は
// 無振動3分が既に経過していれば直ちに System OFF へ入る。
void checkSleepTimeout() {
    if (g_deviceConnected) return;   // BLE接続中はスリープしない
    if (g_currentMillis - g_lastRideEventMs < RIDE_INACTIVITY_TIMEOUT_MS) return;

    logPrint("SLEEP", "No vibration for %lu min - entering System OFF",
             (unsigned long)(RIDE_INACTIVITY_TIMEOUT_MS / 60000));
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

    // ウェイクスイッチ(内部プルアップ・導通=LOW)とFUNCキー(同結線)
    pinMode(WAKE_SW_GPIO, INPUT_PULLUP);
    pinMode(FUNC_SW_GPIO, INPUT_PULLUP);

    // 振動パルスはGPIOTE割り込みで検出(v0.4.8)。パルスは1ms級でloopポーリングでは
    // 取りこぼすため、エッジイベントをハードラッチさせる
    attachInterrupt(digitalPinToInterrupt(WAKE_SW_GPIO), wakePulseISR, FALLING);

    // FUNCキー押下での起床(v0.4.1): RESETREASではD0/D2の判別ができないため
    // 起動直後のピン読みで推定する。押したまま起床しているので初回releaseを
    // モード切替にしない(processFuncKey の s_fkIgnoreFirstRelease)
    if (digitalRead(FUNC_SW_GPIO) == LOW) {
        s_fkIgnoreFirstRelease = true;
        logPrint("POWER", "Wakeup via FUNC key");
    }

    // 電池電圧を即測定(アプリの初回GET:batteryに間に合わせる)
    g_currentMillis = millis();
    updateBattery();

    setupLed();

    // ePaper初期化+スプラッシュ表示
    setupEpaper();

    // BLE初期化+アドバタイズ開始
    setupBLE();

    g_lastCounterMillis = millis();
    g_lastRideEventMs = millis();   // 起動=直前の振動で起床したとみなす

    logPrint("INIT", "Ready - waiting for BLE connection...");
}

void loop() {
    g_currentMillis = millis();

    processWakeSwitch();
    processWakePulse();
    processFuncKey();
    updateLed();
    updateTimestamp();
    updateBattery();
    updateEpaperDisplay();
    checkSleepTimeout();

    delay(10);
}
