/**
 * Utility Functions for BikeClock
 *
 * This file contains utility functions for maintenance mode, DFU mode,
 * and other system-level operations.
 */

#include "bikeclock.h"

// 7-segment digit patterns (0-9)
const uint8_t SEGMENT_0 = 0x3F;
const uint8_t SEGMENT_1 = 0x06;
const uint8_t SEGMENT_2 = 0x5B;
const uint8_t SEGMENT_3 = 0x4F;
const uint8_t SEGMENT_4 = 0x66;
const uint8_t SEGMENT_5 = 0x6D;
const uint8_t SEGMENT_6 = 0x7D;
const uint8_t SEGMENT_7 = 0x07;
const uint8_t SEGMENT_8 = 0x7F;
const uint8_t SEGMENT_9 = 0x6F;

/**
 * Encode a character to 7-segment pattern
 * @param c Character to encode ('0'-'9', 'A'-'Z', ' ', '-')
 * @return 7-segment pattern
 */
uint8_t encodeDigit(char c) {
    // Digits
    if (c >= '0' && c <= '9') {
        const uint8_t digits[] = {
            SEGMENT_0, SEGMENT_1, SEGMENT_2, SEGMENT_3, SEGMENT_4,
            SEGMENT_5, SEGMENT_6, SEGMENT_7, SEGMENT_8, SEGMENT_9
        };
        return digits[c - '0'];
    }

    // Letters (simplified - just show dash for now)
    return 0x40;  // Dash/minus
}

// --- 7-Segment Display Encoding ---

// External reference to SEGMENT_CHARS from bikeclock_led.ino
extern const uint8_t SEGMENT_CHARS[];

/**
 * Encode string to 7-segment data
 * Supports: digits (0-9), letters (A-Z), and space ( )
 * Example: "1BOO" -> data[] = {0x06, 0x7C, 0x3F, 0x3F}
 */
void encodeStringToSegments(const char* str, uint8_t* data) {
    for (int i = 0; i < 4 && str[i] != '\0'; i++) {
        char c = str[i];
        if (c >= '0' && c <= '9') {
            // Digit
            data[i] = encodeDigit(c);
        } else if (c >= 'A' && c <= 'Z') {
            // Uppercase letter
            data[i] = SEGMENT_CHARS[c - 'A'];
        } else if (c >= 'a' && c <= 'z') {
            // Lowercase letter (convert to uppercase)
            data[i] = SEGMENT_CHARS[c - 'a'];
        } else if (c == ' ') {
            // Space (blank)
            data[i] = 0x00;
        } else {
            // Unknown character (blank)
            data[i] = 0x00;
        }
    }
}

// --- Maintenance Mode Functions ---

/**
 * Enter maintenance mode
 * Activates maintenance mode for system configuration
 */
void enterMaintenanceMode() {
    Serial.println("[MAINTENANCE] Entering maintenance mode");
    g_maintenanceState.active = true;
    g_maintenanceState.currentMenu = MAINTENANCE_MENU_CANCEL;
    g_maintenanceState.selectedMenuIndex = 0;
    g_maintenanceState.lastInteractionMillis = millis();

    // Show initial menu
    updateMaintenanceDisplay();
}

/**
 * Exit maintenance mode
 * Deactivates maintenance mode and returns to normal operation
 */
void exitMaintenanceMode() {
    Serial.println("[MAINTENANCE] Exiting maintenance mode");
    g_maintenanceState.active = false;

    // Return to time display
    g_displayMode = DISPLAY_MODE_TIME;
    updateDisplayForCurrentMode();
}

/**
 * Update maintenance mode display
 * Shows the current maintenance menu on the 7-segment display
 */
void updateMaintenanceDisplay() {
    if (!g_maintenanceState.active) {
        return;
    }

    uint8_t data[] = { 0x00, 0x00, 0x00, 0x00 };

    // Encode menu display string
    switch (g_maintenanceState.currentMenu) {
        case MAINTENANCE_MENU_CANCEL:
            // "1BOO" (Boot)
            encodeStringToSegments("1BOO", data);
            break;

        case MAINTENANCE_MENU_TEST:
            // "2TST" (Test)
            encodeStringToSegments("2TST", data);
            break;

        case MAINTENANCE_MENU_DFU:
            // "3OTA" (OTA)
            encodeStringToSegments("3OTA", data);
            break;

        case MAINTENANCE_MENU_FACTORY_RESET:
            // "4RST" (Factory Reset)
            encodeStringToSegments("4RST", data);
            break;

        default:
            break;
    }

    g_display->setSegments(data);
}

/**
 * Process maintenance mode
 * Returns true if maintenance mode is still active
 * Returns false if maintenance mode is exited (action executed or cancelled)
 */
bool processMaintenanceMode() {
    if (!g_maintenanceState.active) {
        return false;
    }

    unsigned long currentMillis = millis();

    // Flash on-board LED in maintenance mode (red flash, 500ms period)
    if ((currentMillis / 250) % 2 == 0) {
        setLedColor(true, false, false);  // Red flash
    } else {
        setLedColor(false, false, false);  // Off
    }

    // Check for 3-second timeout
    if (currentMillis - g_maintenanceState.lastInteractionMillis >= 3000) {
        // Execute selected menu action
        Serial.printf("[MAINTENANCE] Menu %d selected (3 second timeout)\n",
                     g_maintenanceState.selectedMenuIndex + 1);

        switch (g_maintenanceState.currentMenu) {
            case MAINTENANCE_MENU_CANCEL:
                Serial.println("[MAINTENANCE] Action: Cancel (normal boot)");
                g_maintenanceState.active = false;
                return false;  // Exit maintenance mode, continue normal boot

            case MAINTENANCE_MENU_TEST:
                Serial.println("[MAINTENANCE] Action: Enter test mode");
                g_displayMode = DISPLAY_MODE_TEST;
                extern int g_testDisplayIndex;
                g_testDisplayIndex = 1;
                g_maintenanceState.active = false;
                return false;  // Exit maintenance mode

            case MAINTENANCE_MENU_DFU:
                Serial.println("[MAINTENANCE] Action: Enter OTA DFU mode");
                g_display->showNumberDec(3333);  // Show "3333"
                delay(1000);
                startOtaDfuMode();  // Enter Adafruit OTA DFU mode
                break;  // Won't reach here

            case MAINTENANCE_MENU_FACTORY_RESET:
                Serial.println("[MAINTENANCE] Action: Factory reset");
                g_maintenanceState.active = false;
                resetToFactoryDefaults();  // This will reset the system
                break;  // Won't reach here

            default:
                break;
        }
    }

    return true;  // Still in maintenance mode
}

// --- DFU Mode Functions ---

/**
 * Enter DFU (Device Firmware Update) mode
 * Resets the device and starts the Nordic DFU bootloader
 */
void enterDfuMode() {
    Serial.println("[DFU] ========================================");
    Serial.println("[DFU] Entering DFU Mode for OTA update");
    Serial.println("[DFU] ========================================");

    // Visual feedback: Show "DFU" on display
    if (g_display != nullptr) {
        uint8_t data[] = {
            SEGMENT_CHARS['D' - 'A'],
            SEGMENT_CHARS['F' - 'A'],
            SEGMENT_CHARS['U' - 'A'],
            0x00
        };
        g_display->setSegments(data);
    }

    // Flash LED to indicate DFU mode
    for (int i = 0; i < 5; i++) {
        setLedColor(true, false, false);  // Red
        delay(100);
        setLedColor(false, false, false); // Off
        delay(100);
    }

    Serial.println("[DFU] Resetting to Nordic DFU bootloader...");
    Serial.flush();

    // Enter OTA DFU mode using Adafruit's function
    // This will restart the device into bootloader mode
    // The bootloader will advertise as "DfuTarg" for OTA updates
    ::enterOTADfu();

    // This line won't be reached due to reset
}

/**
 * Start OTA DFU mode
 * Called from BLE command to initiate DFU mode for OTA update
 */
void startOtaDfuMode() {
    Serial.println("[OTA] ========================================");
    Serial.println("[OTA] Starting OTA DFU Mode");
    Serial.println("[OTA] ========================================");

    // Visual feedback: Show "9999" on display
    if (g_display != nullptr) {
        uint8_t data[] = {
            SEGMENT_9,
            SEGMENT_9,
            SEGMENT_9,
            SEGMENT_9
        };
        g_display->setSegments(data);
    }

    // Flash LED to indicate DFU mode starting
    for (int i = 0; i < 3; i++) {
        setLedColor(true, false, false);  // Red
        delay(200);
        setLedColor(false, false, false); // Off
        delay(200);
    }

    Serial.println("[OTA] Disconnecting BLE and entering DFU mode...");
    Serial.flush();

    // Disconnect BLE client
    if (Bluefruit.connected()) {
        uint16_t conn_hdl = Bluefruit.connHandle();
        Bluefruit.disconnect(conn_hdl);
        delay(1000);  // Wait for disconnection to complete
    }

    // Enter DFU mode
    enterDfuMode();
}
