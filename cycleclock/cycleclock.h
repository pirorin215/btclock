#ifndef CYCLECLOCK_H
#define CYCLECLOCK_H

#include <Arduino.h>
#include <bluefruit.h>
#include <SPI.h>
#include <GxEPD2_BW.h>
#include <U8g2_for_Adafruit_GFX.h>
#include <Adafruit_LittleFS.h>
#include <InternalFileSystem.h>   // Seeeduino版のInternalFS定義(bondingもこれを使用)

// 注意: Arduinoの自動プロトタイプ生成(nRF52コアのレガシープリプロセッサ)が
// .ino内関数のプロトタイプを最初の.ino位置=各.inoのincludeより前に挿入する
// ため、Adafruit_GFX等の型を参照する関数が通るように全ライブラリを
// このヘッダでincludeする(bikeclock.hと同じ構成)。

// --- Battery Monitoring ---
#define BATTERY_REFRESH_MS  60000UL  // 電池電圧のキャッシュ測定間隔(fastrec2と同じ60秒)

// 低電圧警告(「要充電」ePaper表示)の閾値。トリガ3.5V以下・解除3.6V以上の
// ヒステリシスで、測定値が境界付近で揃った時の表示切替チラつきを防ぐ。
// 3.5Vは18650の実用下限(満充電4.2V・残量約1割目安)。
#define BATT_LOW_THRESHOLD_V   3.50f
#define BATT_LOW_CLEAR_V       3.60f

// 残量表示(ピクト・GET:battery)用の中央値フィルタ窓。60秒測定×5=5分。
// 負荷dip等の単発ノイズを打ち消し「残量」として正しい弛緩電圧に寄せる
// (放電は物理的にゆっくりなので、窓内の急な下落はノイズ扱いでよい)。
#define BATT_MEDIAN_WINDOW     5

// 時計画面の電池ピクトグラム(5段階)と警告「!」の閾値。
// 3.60-4.10Vを0.1V刻みの5段階で表示(<3.60Vは0本の空枠・>=4.00Vで満枠)。
// 3.65V未満で警告「!」を追加(解除は3.70V以上のヒステリシス)。
#define BATT_PICT_MIN_V        3.60f
#define BATT_PICT_STEP_V       0.10f
#define BATT_WARN_THRESHOLD_V  3.65f
#define BATT_WARN_CLEAR_V      3.70f

// --- Firmware Version Information ---
#define FIRMWARE_VERSION_MAJOR 0
#define FIRMWARE_VERSION_MINOR 4
#define FIRMWARE_VERSION_PATCH 11

// --- GPIO Pin Definitions (XIAO BLE) ---
// ePaper: WeAct 2.13" (SSD1680)
// モジュール端子との対応(2026-09-25 ユーザー指定の物理配線):
//   BUSY→D9, D/C→D8, SCL→D7, RES→D10, CS→D4, SDA→D5
//   SCL/SDAはSPI(SCK/MOSI)。nRF52840はSPIピンをPSELで任意GPIOに割当可能 →
//   setupEpaper() の SPI.setPins() で反映。MISOはePaperが未使用のため空きピンD1をダミー割当。
#define EPD_CS_GPIO     D4   // ePaper CS  (モジュール印字: CS)
#define EPD_DC_GPIO     D8   // ePaper DC  (モジュール印字: D/C)
#define EPD_RST_GPIO    D10  // ePaper RST (モジュール印字: RES)
#define EPD_BUSY_GPIO   D9   // ePaper BUSY(モジュール印字: BUSY)
#define EPD_SPI_SCK_GPIO   D7   // SPI SCK  (モジュール印字: SCL)
#define EPD_SPI_MOSI_GPIO  D5   // SPI MOSI (モジュール印字: SDA)
// v0.4.10: ePaper電源スイッチ(TPS22810 EN)制御ピン。旧MISOダミーD1を転用。
// TPS22810(SOT-23-6: 1=VIN/3V3, 2=GND, 3=EN/D1+100kΩプルダウン, 4=CT開放,
// 5=QOD→VOUT直結, 6=VOUT/ePaper VCC)でePaper給電をGPIO制御する。
// EN=HIGHの間だけ給電・Hi-ZでプルダウンがLOWに自己保持=System OFF中もOFF維持
// (リーク0.5µA typ・QODがVOUTを0V放電・残画はパネルが保持)。
// 実測: System OFF電流 0.5mA(v0.4.9)→11µA(v0.4.10・2026-10-06 cycleclock_diagで実証)
#define EPD_POWER_GPIO     D1
#define EPD_POWER_STABLE_MS 100UL   // EN=HIGH後のePaper電源安定待ち(起動時に1回)

// ウェイクスイッチ: 他端GND・内部プルアップ・導通(LOW)で System OFF から復帰
// (開発中はタクトスイッチ、最終形は SW-18020P 系振動センサー)
#define WAKE_SW_GPIO    D0

// --- Sleep Policy ---
// 「ウェイクスイッチ導通(振動パルス)がこの時間無ければ System OFF へ入る」。
// 乗車中は振動が継続的に導通を作るため起き続ける。
// BLE接続中はアプリ操作中とみなしてスリープせず、切断後に無振動3分が
// 経過済みなら直ちに System OFF へ入る(v0.4.9)。
// 開発中のタクトスイッチでは「押下」が振動パルスに相当する。
#define RIDE_INACTIVITY_TIMEOUT_MS  180000UL  // 3分
// 振動パルス延長ログの最小間隔。SW-18020Pは振動中に毎秒多数の導通パルスを出すため
// ログだけレート制限する(タイマー延長自体は全パルスで行う)。
#define WAKE_PULSE_LOG_INTERVAL_MS  1000

// --- 誤起動・D0短絡の統計(v0.4.11・cycleclock_stats.ino) ---
// 誤起動: BT接続されないままスタンバイに入った回数(不在時の誤起動の観測用)。
//   System OFFはRAMを保持しないため内部フラッシュ(InternalFS/LittleFS)へ永続化する。
//   BT接続ありのスタンバイで0にリセット(接続された=正当な起動)。
// D0短絡: BT接続中にD0が導通した回数(processWakeSwitchのデバウンス確定=
//   「[SW] Wake switch pressed」ログと同タイミングでカウント)。
//   RAMのみ(スタンバイ入りでリセット=今回の乗車セッションの値)。

// --- LED dimming ---
// XIAO BLEのRGB LEDはcommon anode(HIGH=消灯)。
// 常時点灯系(BOOT/同期済み): analogWriteの超低デューティPWMで暗色化。
// 点滅系(未同期/エラー): ほぼ消灯にしておき、一定間隔のごく短いパルスだけで
// 生存を知らせる(自作キーボード界隈の「ほぼ消えているLED」手法)。平均電流はほぼゼロ。
// LED_DIM_PWM_VALUE: 255=消灯。(255-値)/255 が点灯デューティ → 252は約1.2%点灯。
#define LED_DIM_PWM_VALUE       252
#define LED_PULSE_MS            50     // 点滅系のパルス幅
#define LED_PULSE_INTERVAL_MS   2000   // 通常点滅間隔
#define LED_ERROR_INTERVAL_MS   500    // エラー時の点滅間隔

// --- Display Mode (bikeclock_esp32 と同一の4モード構成・v0.4.0) ---
// FUNCキー(D2)クリックでモード1〜4を循環する。bikeclock_esp32のFUNC_MODE_TABLE
// と同じ構成(ePaper単独・7セグなし)。cycleclock独自としてバッテリー情報
// (時計画面右下ピクト+詳細ビューの電圧行)を追加している。
//   モード1 時計     : 標準時計(曜日/日付/時刻/乗車時間/電池ピクト)
//   モード2 通知     : 最終受信通知(未受信なら「通知なし」)。新着で内容更新
//   モード3 詳細     : 開始/経過/現在日時/電池電圧 (スナップショット1回描き)
//   モード4 詳細大   : 日付+曜日/経過/開始〜現在 (スリープ残画と同内容+ピクト)
// 非TIMEモードは10秒で時計へ自動復帰(bikeclock_esp32と同一)。
// 乗車イベント(振動)でも時計へ戻る二重の自己修復付き。
// 通知・低電圧の自動ビューはモード表示に優先しない(bikeclock_esp32では通知が
// 一時オーバーライドだが、cycleclockは通知もFUNCモード化して統一)。
enum DisplayMode {
    DISPLAY_MODE_TIME,         // モード1: 時計
    DISPLAY_MODE_NOTIFICATION, // モード2: 通知(最終受信・手動選択可)
    DISPLAY_MODE_DETAIL,       // モード3: 詳細
    DISPLAY_MODE_DETAIL_LARGE, // モード4: 詳細大
    DISPLAY_MODE_COUNT
};
#define FUNC_SW_GPIO         D2       // FUNCキー(他端GND・内部プルアップ)
#define MODE_AUTO_RETURN_MS  10000UL  // 非TIMEモードから時計への自動復帰時間(bikeclock_esp32と同一)
extern DisplayMode g_displayMode;
extern unsigned long g_lastModeChangeMillis;
extern volatile uint32_t g_notificationSeq;  // 通知受信連番(手動通知モードの再描画判定)

// --- BLE Settings ---
// アプリ(BTClockMob)は "BikeClock-" 接頭辞でデバイスを解決するため、命名規則を維持する
#define BLE_DEVICE_NAME       "BikeClock-Cycle"

// コマンド特性の最大長。アプリは通知を "NOTIFY:app=...\n本文" の1回のWrite(最大~230B)で
// 送るため、ATT MTU 247(実効244B)と合わせて可変長でこの長さまで受ける。
#define BLE_CMD_MAX_LEN       247

// --- Notification (bikeclock_esp32 Phase 10 から移植) ---
#define NOTIFICATION_DISPLAY_TIMEOUT_MS 30000UL  // 通知表示時間(bikeclock_esp32と同一)
#define NOTIFY_APP_LEN   33    // アプリ名上限 32B + null(ログ+本文空時の代替表示)
#define NOTIFY_TEXT_LEN  201   // 通知本文上限 200B + null

// --- BLE UUIDs (bikeclock / bikeclock_esp32 と共通・アプリ互換) ---
#define BLE_SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914c"
#define BLE_CHAR_COMMAND_UUID   "beb5483e-36e1-4688-b7f5-ea07361b26a0"  // Read/Write/Notify: 時刻同期コマンド

// --- Onboard LED state ---
enum LedState {
    LED_STATE_BOOT,              // 起動直後: 赤点灯
    LED_STATE_NO_SYNC,           // 未接続+未同期: 赤点滅(2s)
    LED_STATE_SYNCED,            // 未接続+同期済: 緑点灯
    LED_STATE_CONNECTED_NO_SYNC, // 接続中+未同期: 青点滅(2s)
    LED_STATE_CONNECTED_SYNCED,  // 接続中+同期済: 青点灯
    LED_STATE_ERROR              // エラー: 赤点滅(0.5s)
};

// --- Date cache structure ---
struct DateCache {
    int month;
    int day;
    int weekday;
    int year;
    uint32_t lastTimestamp;
    bool valid;
};

// --- チャタリング除去つきスイッチ入力(cycleclock.ino で使用) ---
// .ino関数の引数に自作型を使うと自動プロトタイプ生成(型定義より前に挿入される)が
// 壊れるため、このヘッダで定義する(Adafruit_GFX等をここに集約するのと同じ理由)。
struct DebouncedSwitch {
    bool stable;                    // デバウス確定後の安定値(プルアップなのでHIGH=未押下)
    bool lastReading;               // 前回の生読み取り値
    unsigned long lastDebounceMs;   // 最後に読み取りが変化した時刻
};

// --- Global Variables ---
extern volatile uint32_t g_currentTimestamp;  // JST Unix timestamp (アプリがJST換算で送信)
extern bool g_deviceConnected;                // BLE接続状態
extern bool g_timeSynced;                     // 時刻同期済み
extern LedState g_currentLedState;
extern unsigned long g_currentMillis;         // loop冒頭で更新される現在時刻
extern unsigned long g_startupMillis;         // 起動時刻(ログタイムスタンプ・乗車時間の基点)
extern unsigned long g_lastRideEventMs;       // 最終振動検出時刻(無振動スリープ判定の基準・BLE接続中は判定停止)
extern uint32_t g_falseWakeCount;             // 誤起動回数(BT未接続のままスタンバイ=永続化)
extern uint32_t g_d0ShortCount;               // 今セッションのD0短絡回数(BT接続中の連続導通)
extern bool g_everConnectedThisBoot;          // 今回の起動で一度でもBT接続されたか
extern DateCache g_dateCache;

// --- Notification (BLE受信→ePaper通知表示) ---
extern volatile bool g_notificationActive;    // 通知表示中フラグ(BLEコールバックが立てる)
extern unsigned long g_notificationEndTime;   // 通知表示の終了時刻(millis())
extern char g_notificationApp[];              // アプリ名(ログ用・本文空の時の代替表示)
extern char g_notificationText[];             // 通知本文
// --- Function Prototypes ---

// cycleclock_stats.ino
void setupStats();              // 起動時: 誤起動回数をフラッシュから読み出し
void commitStatsAtSleep();      // enterSystemOffから: 誤起動判定+フラッシュ書込み
// cycleclock.ino
void updateTimestamp();
int getHours();
int getMinutes();
int getSeconds();
int getMonth();
int getDay();
int getWeekday();
int getYear();
void processWakeSwitch();
void processFuncKey();
void checkSleepTimeout();

// cycleclock_ble.ino
void setupBLE();
void handleTimeSync(const char* command);
void handleGetVersion();
void handleNotify(const char* command);
void sendResponse(const char* message);

// cycleclock_epaper.ino
void setupEpaper();
void updateEpaperDisplay();
void drawEpaperSleep();
bool epaperIdle();
void epaperHibernate();   // v0.4.10: パネルdeep sleep(System OFF前のリーク対策)

// cycleclock_power.ino
void enterSystemOff();
void setupLed();
void updateLed();
void setLedState(LedState state);
void setLedError();
void updateLedStateBasedOnStatus();

// cycleclock_battery.ino
void updateBattery();
float batteryVoltageCached();
bool batteryLowActive();
bool batteryWarnActive();
int  batteryLevelPict();

// Logging
void setupLog();
void logPrint(const char* tag, const char* format, ...);

#endif // CYCLECLOCK_H
