#ifndef CYCLECLOCK_H
#define CYCLECLOCK_H

#include <Arduino.h>
#include <bluefruit.h>
#include <SPI.h>
#include <GxEPD2_BW.h>
#include <U8g2_for_Adafruit_GFX.h>

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
#define FIRMWARE_VERSION_MINOR 3
#define FIRMWARE_VERSION_PATCH 9

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
#define EPD_SPI_MISO_GPIO  D1   // SPI MISO (未使用・ダミー)

// 描画前のBUSY解除待ち上限。3色パネルのフル更新はB74ドライバのBUSYタイムアウト
// (10秒)より長くなるため、直前の更新が物理的に続いている間に描画すると消える。
// 最悪値を見て30秒(通常の分更新間隔60秒では待ち自体が発生しない)。
#define EPD_BUSY_GUARD_TIMEOUT_MS  30000UL

// ウェイクスイッチ: 他端GND・内部プルアップ・導通(LOW)で System OFF から復帰
// (開発中はタクトスイッチ、最終形は SW-18020P 系振動センサー)
#define WAKE_SW_GPIO    D0

// --- Sleep Policy ---
// 「ウェイクスイッチ導通(振動パルス)がこの時間無ければ BLE接続中でもスリープする」。
// 乗車中は振動が継続的に導通を作るため起き続け、駐輪後はスマホが近くにいても
// 確実に System OFF へ入る(スマホの位置に依存しない=振動の有無がスイッチ)。
// 開発中のタクトスイッチでは「押下」が振動パルスに相当する。
#define RIDE_INACTIVITY_TIMEOUT_MS  180000UL  // 3分
#define WAKE_SW_LONGPRESS_MS    2000      // ウェイクスイッチ長押しで手動 System OFF (測定・テスト用)
#define WAKE_SW_RELEASE_TIMEOUT_MS  10000UL  // System OFF前のスイッチ解放待ち上限(導通継続時のハング防止)

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

// --- Display Mode (bikeclock の FUNC キー機構・v0.3.9 で専用GPIOに分離) ---
// FUNCキーは専用GPIO(FUNC_SW_GPIO=D2・振動センサーとは別スイッチ)。走行中の
// 振動が常にD0へ短押しパルスを入れるため「単押しでモード切替」をD0と共有すると
// 走行中に表示が切り替わり続ける。単押しUXと振動検出は排他要件なので v0.3.8 の
// 押下時間分離(0.5-2s中押し)を廃止し、bikeclock と同じく専用キーに分離した。
//   D0 短押し(振動パルス) = 乗車イベント。非TIMEモード中なら時計へ復帰
//   D0 長押し(2s)         = 手動 System OFF(既存)
//   D2 短押し             = 表示モード切替(bikeclockのFUNC短押しと同じ)
// 非TIMEモードは60秒で時計へ自動復帰(bikeclockの5sを3色パネルの低速フル更新
// に合わせ延長)。乗車イベントでも時計へ戻る二重の自己修復付き。
enum DisplayMode {
    DISPLAY_MODE_TIME,      // 時計画面(デフォルト)
    DISPLAY_MODE_BATTERY,   // 電池詳細(電圧大表示+ピクト・校正時のその場確認用)
    DISPLAY_MODE_VERSION,   // バージョン表示(スプラッシュと同一内容)
    DISPLAY_MODE_COUNT
};
#define FUNC_SW_GPIO         D2       // FUNCキー(他端GND・内部プルアップ)
#define MODE_AUTO_RETURN_MS  60000UL  // 非TIMEモードから時計への自動復帰時間
extern DisplayMode g_displayMode;
extern unsigned long g_lastModeChangeMillis;

// --- BLE Settings ---
// アプリ(BTClockMob)は "BikeClock-" 接頭辞でデバイスを解決するため、命名規則を維持する
#define BLE_DEVICE_NAME       "BikeClock-Cycle"

// コマンド特性の最大長。アプリは通知を "NOTIFY:app=...\n本文" の1回のWrite(最大~230B)で
// 送るため、ATT MTU 247(実効244B)と合わせて可変長でこの長さまで受ける。
#define BLE_CMD_MAX_LEN       247

// --- Notification (bikeclock_esp32 Phase 10 から移植) ---
#define NOTIFICATION_DISPLAY_TIMEOUT_MS 60000UL  // 通知表示時間。esp32版は30秒だが、現行3色
                                                 // パネルのフル更新に>10秒かかるため60秒とする
#define NOTIFY_APP_LEN   33    // アプリ名上限 32B + null(ログ/将来用。描画には未使用)
#define NOTIFY_TEXT_LEN  201   // 通知本文上限 200B + null

// 通知の文字数に応じたフォントサイズと拡大倍率の設定構造体
struct NotifyFontSetting {
    int maxChars;          // この文字数以下の場合に適用
    const uint8_t* font;   // 使用するフォント(u8g2_font_...)
    int scale;             // 拡大倍率(1〜3)
};

// フォントと拡大倍率の段階設定(文字数の昇順で定義。最後は全長文をカバーする大きな値)
static const NotifyFontSetting NOTIFY_FONT_SETTINGS[] = {
    { 10,  u8g2_font_b16_t_japanese3, 3 },  // 16pxフォント3倍 48px
    { 24,  u8g2_font_b12_t_japanese3, 3 },  // 12pxフォント3倍 36px
    { 26,  u8g2_font_b16_t_japanese3, 2 },  // 16pxフォント2倍 32px
    {999,  u8g2_font_b12_t_japanese3, 2 }
};
#define NUM_NOTIFY_FONT_SETTINGS (sizeof(NOTIFY_FONT_SETTINGS) / sizeof(NOTIFY_FONT_SETTINGS[0]))

// --- BLE UUIDs (bikeclock / bikeclock_esp32 と共通・アプリ互換) ---
#define BLE_SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914c"
#define BLE_CHAR_COMMAND_UUID   "beb5483e-36e1-4688-b7f5-ea07361b26a0"  // Read/Write/Notify: 時刻同期コマンド

// --- Time Settings ---
#define DISPLAY_UPDATE_INTERVAL_MS  1000  // (内部処理用・ePaper描画は分変化トリガ)

// --- Onboard LED state ---
enum LedState {
    LED_STATE_BOOT,              // 起動直後: 赤点灯
    LED_STATE_NO_SYNC,           // 未接続+未同期: 赤点滅(1s)
    LED_STATE_SYNCED,            // 未接続+同期済: 緑点灯
    LED_STATE_CONNECTED_NO_SYNC, // 接続中+未同期: 青点滅(1s)
    LED_STATE_CONNECTED_SYNCED,  // 接続中+同期済: 青点灯
    LED_STATE_ERROR              // エラー: 赤高速点滅(0.2s)
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

// --- Global Variables ---
extern volatile uint32_t g_currentTimestamp;  // JST Unix timestamp (アプリがJST換算で送信)
extern bool g_deviceConnected;                // BLE接続状態
extern bool g_timeSynced;                     // 時刻同期済み
extern LedState g_currentLedState;
extern unsigned long g_currentMillis;         // loop冒頭で更新される現在時刻
extern unsigned long g_startupMillis;         // 起動時刻(ログタイムスタンプ・乗車時間の基点)
extern unsigned long g_lastRideEventMs;       // 最終振動検出時刻(スリープ判定の唯一の基準)
extern DateCache g_dateCache;

// --- Notification (BLE受信→ePaper通知表示) ---
extern volatile bool g_notificationActive;    // 通知表示中フラグ(BLEコールバックが立てる)
extern unsigned long g_notificationEndTime;   // 通知表示の終了時刻(millis())
extern char g_notificationApp[];              // アプリ名(ログ用)
extern char g_notificationText[];             // 通知本文

// --- Function Prototypes ---

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
