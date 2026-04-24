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

// --- Switch Settings ---
#define SWITCH_DEBOUNCE_DELAY_MS    50   // Debounce delay
#define SWITCH_REPEAT_DELAY_MS     300   // Initial repeat delay
#define SWITCH_REPEAT_INTERVAL_MS  100   // Repeat interval

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

// --- Global Variables ---
extern TM1637Display* g_display;
extern volatile uint32_t g_currentTimestamp;  // Unix timestamp

// HID switch state tracking
enum HidSwitchState {
    HID_STATE_IDLE,
    HID_STATE_PRESS,
    HID_STATE_REPEAT
};

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
int getHours();
int getMinutes();
int getSeconds();

// HID Switch functions
void processHidSwitches();
void sendHidKeyPress(uint16_t keyCode, const char* unused = NULL);
void sendHidKeyRelease(const char* unused = NULL);

// BLE Switch notification functions (optional, for app feedback)
void processSwitches();
void sendSwitchNotification(uint8_t switchNum, const char* action);

#endif // BIKECLOCK_H
