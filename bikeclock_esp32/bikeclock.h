#ifndef BIKECLOCK_H
#define BIKECLOCK_H

#include <Arduino.h>
#include <TM1637Display.h>
#include <LittleFS.h>

// --- Firmware Version Information (ESP32-S3 edition) ---
// XIAO BLE 版 (1.x.x) と区別するため 2.0.0 から開始
#define FIRMWARE_VERSION_MAJOR 2
#define FIRMWARE_VERSION_MINOR 0
#define FIRMWARE_VERSION_PATCH 21

// --- GPIO Pin Definitions (ESP32-S3 SuperMini / 推奨案A) ---
// TM1637 4-digit 7-segment display
#define LED_DIO_GPIO     6   // TM1637 DIO
#define LED_CLK_GPIO     7   // TM1637 CLK

// Physical Switches
#define SWITCH_SW1_GPIO     4   // Right Arrow
#define SWITCH_SW2_GPIO     5   // Down Arrow
#define SWITCH_SW3_GPIO     13  // Up Arrow
#define SWITCH_SW4_GPIO     14  // Left Arrow
#define SWITCH_SW5_GPIO     35  // Enter
#define SWITCH_SW6_GPIO     38  // Back
#define SWITCH_SW7_GPIO     39  // Play/Pause
#define SWITCH_FUNC_GPIO    8   // Function / Mode / Maintenance

#define HID_DEBOUNCE_DELAY_MS     50   // Switch debounce delay

// --- HID Key Codes (Keyboard Page) ---
#define DEFAULT_SW1_KEYCODE    0x4F  // Right Arrow
#define DEFAULT_SW2_KEYCODE    0x51  // Down Arrow
#define DEFAULT_SW3_KEYCODE    0x52  // Up Arrow
#define DEFAULT_SW4_KEYCODE    0x50  // Left Arrow
#define DEFAULT_SW5_KEYCODE    0x28  // Enter
#define DEFAULT_SW6_KEYCODE    0x0224  // Back (Android)
#define DEFAULT_SW7_KEYCODE    0xCD  // Play/Pause (Consumer Page)

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

// --- HID switch state tracking ---
enum HidSwitchState {
    HID_STATE_IDLE,
    HID_STATE_PRESS,
    HID_STATE_REPEAT
};

// --- Maintenance Mode Menu ---
enum MaintenanceMenu {
    MAINTENANCE_MENU_CANCEL,        // Cancel (normal boot / reboot)
    MAINTENANCE_MENU_TEST,          // Test mode
    MAINTENANCE_MENU_DFU,           // DFU mode (OTA)
    MAINTENANCE_MENU_FACTORY_RESET, // Factory reset
    MAINTENANCE_MENU_COUNT          // Number of menus
};

struct MaintenanceState {
    bool active;                           // Maintenance mode is active
    MaintenanceMenu currentMenu;           // Current menu selection
    unsigned long lastInteractionMillis;   // Last interaction time (for timeout)
    uint8_t selectedMenuIndex;            // Current menu index (0-based)
};

struct HidSwitch {
    uint8_t gpio;
    uint8_t pinState;
    unsigned long lastDebounceTime;
    unsigned long pressStartTime;
    HidSwitchState state;
    uint16_t keyCode;
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

#define NUM_HID_SWITCHES 7
extern HidSwitch hidSwitches[];
extern MaintenanceState g_maintenanceState;
extern bool g_showingCountdown;
extern uint16_t g_displayingKeyCode;
extern unsigned long g_keyCodeDisplayEndTime;
extern unsigned long g_lastModeChangeMillis;
#define MODE_AUTO_RETURN_TIMEOUT_MS 5000
extern int g_testDisplayIndex;

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
void updateTestDisplay();

// ePaper表示（昼間視認性用）— bikeclock_esp32_epaper.ino
void setupEpaper();
void updateEpaperDisplay();

// システム
void updateTimestamp();
void updateDisplayAndLedState();

// 物理スイッチ & メンテナンスモード
void processHidSwitches();
void processFunctionKey();
void sendHidKeyPress(uint16_t keyCode, const char* unused = NULL);
void sendHidKeyRelease(const char* unused = NULL);
void enterMaintenanceMode();
void exitMaintenanceMode();
void updateMaintenanceDisplay();
bool processMaintenanceMode();

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
#define BLE_DEVICE_NAME       "BikeClock-ESP32"
#define BLE_SERVICE_UUID      "4fafc201-1fb5-459e-8fcc-c5c9c331914c"
#define BLE_CHAR_COMMAND_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a0"

// BLE関数プロトタイプ
void setupBLE();
void deinitBLE();
void sendResponse(const char* message);
void handleTimeSync(const char* command);
void handleGetVersion();
void handleKeyConfig(const char* command);

// HID（Phase 6）— bikeclock_esp32_hid.ino
// NimBLEServer は前方宣言のみ（実体は hid.ino で使用時 include）
class NimBLEServer;
void setupHID(NimBLEServer* server);

// 設定永続化（Phase 4: LittleFS 実装）
#define KEYS_FILE_PATH "/keys.dat"
void setupFileSystem();
void loadSettings();
void saveSettings();
void resetKeySettingsToDefaults();
void resetToFactoryDefaults();

#endif // BIKECLOCK_H
