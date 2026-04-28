#ifndef BIKECLOCK_H
#define BIKECLOCK_H

#include <Arduino.h>
#include <bluefruit.h>
#include <TM1637Display.h>
#include <Adafruit_LittleFS.h>
#include <InternalFileSystem.h>
#include <Adafruit_MCP23X17.h>

using namespace Adafruit_LittleFS_Namespace;

// --- Firmware Version Information ---
#define FIRMWARE_VERSION_MAJOR 1
#define FIRMWARE_VERSION_MINOR 1
#define FIRMWARE_VERSION_PATCH 6

// --- GPIO Pin Definitions ---
#define LED_DIO_GPIO     D4   // TM1637 DIO pin (Physical SDA pin)
#define LED_CLK_GPIO     D5   // TM1637 CLK pin (Physical SCL pin)

// --- GPIO Pin Definitions for SPI (MCP23S17) ---
#define MCP_SPI_MOSI_GPIO   D10  // Physical D10 - SPI MOSI (Default)
#define MCP_SPI_MISO_GPIO   D9   // Physical D9 - SPI MISO (Default)
#define MCP_SPI_SCK_GPIO    D8   // Physical D8 - SPI SCK (Default)
#define MCP_SPI_CS_GPIO     D3   // Physical D3 - SPI CS (Keep as is)

// --- MCP23S17 SPI I/O Expander Definitions ---
// SPI frequency: Lower = more noise immune, Higher = faster response
// Adjust this value if you experience noise issues (range: 100kHz - 8MHz)
#define MCP_SPI_FREQ        100000

// MCP23S17 pin assignments (GP0-GP7)
#define MCP_SW1_PIN         0   // GP0 - HID SW1
#define MCP_SW2_PIN         1   // GP1 - HID SW2
#define MCP_SW3_PIN         2   // GP2 - HID SW3
#define MCP_SW4_PIN         3   // GP3 - HID SW4
#define MCP_SW5_PIN         4   // GP4 - HID SW5
#define MCP_SW6_PIN         5   // GP5 - HID SW6
#define MCP_SW7_PIN         6   // GP6 - HID SW7
#define MCP_FUNC_PIN        7   // GP7 - FUNC key (mode switching)

// MCP23S17 Register Addresses (when IOCON.BANK = 0)
#define MCP23S17_IOCON       0x0A  // I/O Configuration Register
#define MCP23S17_GPPU        0x0C  // GPIO Pull-up Resistor Register

// --- HID Settings ---
#define HID_DEBOUNCE_DELAY_MS     50   // Switch debounce delay
#define HID_REPEAT_DELAY_MS      500   // Time before starting repeat
#define HID_REPEAT_INTERVAL_MS   300   // Time between repeat key strokes (slower for YouTube)

// --- HID Key Codes (Keyboard Page) ---
#define DEFAULT_SW1_KEYCODE    0x4F  // Right Arrow
#define DEFAULT_SW2_KEYCODE    0x51  // Down Arrow
#define DEFAULT_SW3_KEYCODE    0x52  // Up Arrow
#define DEFAULT_SW4_KEYCODE    0x50  // Left Arrow
#define DEFAULT_SW5_KEYCODE    0x28  // Enter
#define DEFAULT_SW6_KEYCODE    0x0224  // Back (Android)
#define DEFAULT_SW7_KEYCODE    0xCD  // Play/Pause (Consumer Page)
// Note: SW8 (FUNC) is not an HID key, it's for mode switching

// --- BLE Settings ---
#define BLE_DEVICE_NAME       "BikeClock-0001"

// --- BLE UUIDs ---
// Service UUID (末尾をcに変更してFastRecと区別)
#define BLE_SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914c"

// Characteristic UUIDs
#define BLE_CHAR_COMMAND_UUID   "beb5483e-36e1-4688-b7f5-ea07361b26a0"  // Write: Time sync command
#define BLE_CHAR_RESPONSE_UUID  "beb5483e-36e1-4688-b7f5-ea07361b26a2"  // Notify: Response (changed last digit)
#define BLE_CHAR_SWITCH_NOTIFY_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a1"  // Notify: Switch state (optional, for app feedback)

// --- Time Settings ---
#define DISPLAY_UPDATE_INTERVAL_MS  1000  // Display update interval (1 second)

// --- HID switch state tracking ---
enum HidSwitchState {
    HID_STATE_IDLE,
    HID_STATE_PRESS,
    HID_STATE_REPEAT
};

// --- Onboard LED state ---
enum LedState {
    LED_STATE_BOOT,              // Startup: Red solid
    LED_STATE_NO_SYNC,           // Not connected + not synced: Red blinking (1s)
    LED_STATE_SYNCED,            // Not connected + synced: Green solid
    LED_STATE_CONNECTED_NO_SYNC, // Connected + not synced: Blue blinking (1s)
    LED_STATE_CONNECTED_SYNCED,  // Connected + synced: Blue solid
    LED_STATE_ERROR              // Error: Red rapid blinking (0.2s)
};

// --- Display Mode ---
enum DisplayMode {
    DISPLAY_MODE_TIME,      // Time display (HH:MM)
    DISPLAY_MODE_DATE,      // Date display (MMDD)
    DISPLAY_MODE_WEEKDAY,   // Weekday display (MON/TUE/...)
    DISPLAY_MODE_TEST,      // Test mode
    DISPLAY_MODE_COUNT      // Number of display modes (for bounds checking)
};

// --- Test Mode Constants ---
#define TEST_DISPLAY_MIN_INDEX 1
extern const int TEST_PATTERN_COUNT;  // Automatically calculated from TEST_PATTERNS array

// --- Maintenance Mode Menu ---
enum MaintenanceMenu {
    MAINTENANCE_MENU_CANCEL,        // Cancel (normal boot)
    MAINTENANCE_MENU_TEST,          // Test mode
    MAINTENANCE_MENU_DFU,           // DFU mode
    MAINTENANCE_MENU_FACTORY_RESET, // Factory reset
    MAINTENANCE_MENU_COUNT          // Number of menus
};

struct MaintenanceState {
    bool active;                           // Maintenance mode is active
    MaintenanceMenu currentMenu;           // Current menu selection
    unsigned long lastInteractionMillis;   // Last interaction time (for timeout)
    uint8_t selectedMenuIndex;            // Current menu index (0-based)
};

// --- Global Variables ---
extern TM1637Display* g_display;
extern volatile uint32_t g_currentTimestamp;  // Unix timestamp
extern bool g_deviceConnected;               // BLE connection status
extern LedState g_currentLedState;           // Current LED state
extern DisplayMode g_displayMode;            // Current display mode
extern unsigned long g_currentMillis;        // Current time for this loop iteration
extern bool g_mcp23S17Connected;             // MCP23S17 connection status
extern bool g_skipBleInit;                   // Skip BLE initialization (for factory reset/test mode)
extern bool g_displayingKeyCodes;            // Currently displaying key codes (skip time display)
extern uint16_t g_displayingKeyCode;         // Currently displaying HID key code (0 = none)
extern unsigned long g_keyCodeDisplayEndTime; // When to stop displaying key code
extern unsigned long g_startupMillis;        // Startup time (for log timestamps)

// Maintenance mode
extern MaintenanceState g_maintenanceState;  // Maintenance mode state

// Date cache structure
struct DateCache {
    int month;
    int day;
    int weekday;
    uint32_t lastTimestamp;
    bool valid;
};

extern DateCache g_dateCache;

// Display mode timeout
extern unsigned long g_lastModeChangeMillis;  // Last time display mode was changed
#define MODE_AUTO_RETURN_TIMEOUT_MS 5000      // Auto-return to time mode after 5 seconds

struct HidSwitch {
    uint8_t gpio;
    uint8_t pinState;
    unsigned long lastDebounceTime;
    unsigned long pressStartTime;
    HidSwitchState state;
    uint16_t keyCode;
};

// --- Function Prototypes ---
void setupBLE();
void handleTimeSync(const char* command);
void handleKeyConfig(const char* command);
void handleGetVersion();
void loadSettings();
void saveSettings();
void updateTimeDisplay();
void updateDisplayForCurrentMode();
void updateDateDisplay();
void updateWeekdayDisplay();
void updateTestDisplay();
int getHours();
int getMinutes();
int getSeconds();
int getMonth();
int getDay();
int getWeekday();

// Function key processing
void processFunctionKey();

// 7-segment display encoding
void encodeStringToSegments(const char* str, uint8_t* data);  // Encode string to 7-segment data

// Version display
void displayVersion();  // Display firmware version on 7-segment LED

// System utilities
void updateTimestamp();
void updateDisplayAndLedState();

// HID Switch functions
void processHidSwitches();
void sendHidKeyPress(uint16_t keyCode, const char* unused = NULL);
void sendHidKeyRelease(const char* unused = NULL);

// Onboard LED functions
void setupLed();
void updateLed();
void setLedState(LedState state);
void setLedColor(bool red, bool green, bool blue);
void setLedError();  // Set LED to error state
void updateLedStateBasedOnStatus();  // Update LED state based on connection and sync status

// Maintenance mode functions
void enterMaintenanceMode();
void exitMaintenanceMode();
void updateMaintenanceDisplay();
bool processMaintenanceMode();

// Logging functions
void setupLog();
void logPrint(const char* tag, const char* format, ...);
void logPrintRaw(const char* format, ...);
#define logPrintln(format, ...) logPrint("", format, ##__VA_ARGS__)

// DFU mode functions
void enterDfuMode();
void startOtaDfuMode();

#endif // BIKECLOCK_H
