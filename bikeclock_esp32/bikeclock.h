#ifndef BIKECLOCK_H
#define BIKECLOCK_H

#include <Arduino.h>
#include <TM1637Display.h>

// --- Firmware Version Information (ESP32-S3 edition) ---
// XIAO BLE 版 (1.x.x) と区別するため 2.0.0 から開始
#define FIRMWARE_VERSION_MAJOR 2
#define FIRMWARE_VERSION_MINOR 0
#define FIRMWARE_VERSION_PATCH 16

// --- GPIO Pin Definitions (ESP32-S3 SuperMini / 推奨案A) ---
// TM1637 4-digit 7-segment display
#define LED_DIO_GPIO     6   // TM1637 DIO
#define LED_CLK_GPIO     7   // TM1637 CLK

// Physical Switches (Phase 3)
#define SWITCH_SW1_GPIO     4   // Right Arrow
#define SWITCH_SW2_GPIO     5   // Down Arrow
#define SWITCH_SW3_GPIO     8   // Up Arrow
#define SWITCH_SW4_GPIO     9   // Left Arrow
#define SWITCH_SW5_GPIO     13  // Enter
#define SWITCH_SW6_GPIO     14  // Back
#define SWITCH_SW7_GPIO     21  // Play/Pause
#define SWITCH_FUNC_GPIO    47  // Function / Mode / Maintenance

// USB Power Sense (Phase 3.5)
#define VBUS_SENSE_GPIO     15  // USB 5V Detection (via voltage divider)

// Onboard RGB LED (WS2812 addressable, fixed on GPIO48)
#define ONBOARD_LED_GPIO    48

// ePaper (WeAct 2.13" BW, GxEPD2_213_B74) — 昼間視認性用
// 専用 SPI3_HOST バスを使用（SPI3_HOST）
#define EPD_CS_GPIO         1
#define EPD_DC_GPIO         2
#define EPD_RST_GPIO        3
#define EPD_BUSY_GPIO       10
#define EPD_SPI_SCK_GPIO    12   // 専用 SPI3_HOST
#define EPD_SPI_MOSI_GPIO   11   // 専用 SPI3_HOST (MISOはePaper不要)

// --- Display Settings ---
#define DISPLAY_UPDATE_INTERVAL_MS  1000

// --- Test Display ---
#define TEST_DISPLAY_MIN_INDEX 1
extern const int TEST_PATTERN_COUNT;

// --- Display Mode ---
enum DisplayMode {
    DISPLAY_MODE_TIME,      // HH:MM
    DISPLAY_MODE_DATE,      // MMDD
    DISPLAY_MODE_WEEKDAY,   // MON/TUE/...
    DISPLAY_MODE_TEST,      // テスト（Phase 3 で使用）
    DISPLAY_MODE_COUNT
};

// --- Date Cache (日付計算のキャッシュ) ---
struct DateCache {
    int month;
    int day;
    int weekday;
    uint32_t lastTimestamp;
    bool valid;
};

// --- Global Variables ---
extern TM1637Display* g_display;
extern volatile uint32_t g_currentTimestamp;   // JST換算のUnix timestamp
extern bool g_timeSynced;
extern DisplayMode g_displayMode;
extern unsigned long g_currentMillis;
extern unsigned long g_lastScreenMillis;
extern unsigned long g_lastCounterMillis;
extern DateCache g_dateCache;
extern unsigned long g_startupMillis;

// --- Function Prototypes ---
// 時刻計算
int getHours();
int getMinutes();
int getSeconds();
int getMonth();
int getDay();
int getWeekday();

// 表示
void updateTimeDisplay();
void updateDateDisplay();
void updateWeekdayDisplay();
void updateDisplayForCurrentMode();
void displayVersion();
void encodeStringToSegments(const char* str, uint8_t* data);

// ePaper表示（昼間視認性用）— bikeclock_esp32_epaper.ino
void setupEpaper();
void updateEpaperDisplay();

// システム
void updateTimestamp();
void updateDisplayAndLedState();

// ロギング
void setupLog();
void logPrint(const char* tag, const char* format, ...);

// --- Onboard LED State (Phase 2) ---
enum LedState {
    LED_STATE_BOOT,              // 起動: 赤固定
    LED_STATE_NO_SYNC,           // 未接続+未同期: 赤点滅(1s)
    LED_STATE_SYNCED,            // 未接続+同期: 緑固定
    LED_STATE_CONNECTED_NO_SYNC, // 接続+未同期: 青点滅(1s)
    LED_STATE_CONNECTED_SYNCED,  // 接続+同期: 青固定
    LED_STATE_ERROR              // エラー: 赤早点滅(0.2s)
};

extern LedState g_currentLedState;
extern bool g_deviceConnected;   // Phase 5 (BLE) で設定

// LED関数
void setupLed();
void updateLed();
void setLedState(LedState state);
void setLedColor(bool red, bool green, bool blue);
void updateLedStateBasedOnStatus();

// --- BLE Settings (Phase 5) ---
#define BLE_DEVICE_NAME       "BikeClock-0001"
#define BLE_SERVICE_UUID      "4fafc201-1fb5-459e-8fcc-c5c9c331914c"
#define BLE_CHAR_COMMAND_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a0"

// BLE関数プロトタイプ
void setupBLE();
void sendResponse(const char* message);
void handleTimeSync(const char* command);
void handleGetVersion();
void handleKeyConfig(const char* command);

// 設定永続化（Phase 4 で本実装、Phase 5 では空スタブ）
void loadSettings();
void saveSettings();

#endif // BIKECLOCK_H
