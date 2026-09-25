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

// --- Firmware Version Information ---
#define FIRMWARE_VERSION_MAJOR 0
#define FIRMWARE_VERSION_MINOR 2
#define FIRMWARE_VERSION_PATCH 0

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

// ウェイクスイッチ: 他端GND・内部プルアップ・導通(LOW)で System OFF から復帰
// (開発中はタクトスイッチ、最終形は SW-18020P 系振動センサー)
#define WAKE_SW_GPIO    D0

// --- Sleep Policy ---
#define SLEEP_IDLE_TIMEOUT_MS   300000UL  // BLE未接続が5分続いたら System OFF
#define WAKE_SW_LONGPRESS_MS    2000      // ウェイクスイッチ長押しで手動 System OFF (測定・テスト用)

// --- BLE Settings ---
// アプリ(BTClockMob)は "BikeClock-" 接頭辞でデバイスを解決するため、命名規則を維持する
#define BLE_DEVICE_NAME       "BikeClock-Cycle"

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
extern unsigned long g_lastActivityMs;        // 最終ユーザ活動時刻(スリープ判定用)
extern DateCache g_dateCache;

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
void checkSleepTimeout();

// cycleclock_ble.ino
void setupBLE();
void handleTimeSync(const char* command);
void handleGetVersion();
void sendResponse(const char* message);

// cycleclock_epaper.ino
void setupEpaper();
void updateEpaperDisplay();
void drawEpaperSleep();

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

// Logging
void setupLog();
void logPrint(const char* tag, const char* format, ...);

#endif // CYCLECLOCK_H
