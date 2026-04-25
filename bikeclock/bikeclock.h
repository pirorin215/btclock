#ifndef BIKECLOCK_H
#define BIKECLOCK_H

#include <Arduino.h>
#include <bluefruit.h>
#include <TM1637Display.h>
#include <Adafruit_LittleFS.h>
#include <InternalFileSystem.h>

using namespace Adafruit_LittleFS_Namespace;

// --- GPIO Pin Definitions ---
#define LED_DIO_GPIO     4   // TM1637 DIO pin (using I2C SDA pin)
#define LED_CLK_GPIO     5   // TM1637 CLK pin (using I2C SCL pin)

// --- GPIO Pin Definitions for Switches ---
#define SWITCH_SW1_GPIO     0   // D0 (P0.02)
#define SWITCH_SW2_GPIO     1   // D1 (P0.03)
#define SWITCH_SW3_GPIO     2   // D2 (P0.04)
#define SWITCH_SW4_GPIO     3   // D3 (P0.29)
#define SWITCH_FUNC_GPIO    10  // D10 (P1.15) - Function key for mode switching

// --- HID Settings ---
#define HID_DEBOUNCE_DELAY_MS     50   // Switch debounce delay
#define HID_REPEAT_DELAY_MS      500   // Time before starting repeat
#define HID_REPEAT_INTERVAL_MS   300   // Time between repeat key strokes (slower for YouTube)

// --- HID Key Codes (Keyboard Page) ---
#define DEFAULT_SW1_KEYCODE    0x50
#define DEFAULT_SW2_KEYCODE    0x4F
#define DEFAULT_SW3_KEYCODE    0x52
#define DEFAULT_SW4_KEYCODE    0x51

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
    DISPLAY_MODE_TEST       // Test mode
};

// --- Global Variables ---
extern TM1637Display* g_display;
extern volatile uint32_t g_currentTimestamp;  // Unix timestamp
extern bool g_deviceConnected;               // BLE connection status
extern LedState g_currentLedState;           // Current LED state
extern DisplayMode g_displayMode;            // Current display mode

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

#endif // BIKECLOCK_H
