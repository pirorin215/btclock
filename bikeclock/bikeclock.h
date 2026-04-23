#ifndef BIKECLOCK_H
#define BIKECLOCK_H

#include <Arduino.h>
#include <bluefruit.h>
#include <TM1637Display.h>

// --- GPIO Pin Definitions ---
#define LED_DIO_GPIO     4   // TM1637 DIO pin (using I2C SDA pin)
#define LED_CLK_GPIO     5   // TM1637 CLK pin (using I2C SCL pin)

// --- BLE Settings ---
#define BLE_DEVICE_NAME       "BikeClock-0001"

// --- BLE UUIDs ---
// Service UUID (末尾をcに変更してFastRecと区別)
#define BLE_SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914c"

// Characteristic UUIDs
#define BLE_CHAR_COMMAND_UUID   "beb5483e-36e1-4688-b7f5-ea07361b26a0"  // Write: Time sync command
#define BLE_CHAR_RESPONSE_UUID  "beb5483e-36e1-4688-b7f5-ea07361b26a2"  // Notify: Response (changed last digit)

// --- Time Settings ---
#define DISPLAY_UPDATE_INTERVAL_MS  1000  // Display update interval (1 second)

// --- Global Variables ---
extern TM1637Display* g_display;
extern volatile uint32_t g_currentTimestamp;  // Unix timestamp

// --- Function Prototypes ---
void setupBLE();
void handleTimeSync(const char* command);
void updateTimeDisplay();
int getHours();
int getMinutes();
int getSeconds();

#endif // BIKECLOCK_H
