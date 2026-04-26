/**
 * LED Control for BikeClock
 *
 * Handles onboard RGB LED and 7-segment LED display (TM1637).
 */

#include "bikeclock.h"

// Weekday names for display
static const char* WEEKDAY_NAMES[] = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};

// LED blink timing
unsigned long g_lastLedBlink = 0;
bool g_ledBlinkState = false;

// --- LED Setup ---
void setupLed() {
    // Initialize LED pins as outputs
    pinMode(LED_RED, OUTPUT);
    pinMode(LED_GREEN, OUTPUT);
    pinMode(LED_BLUE, OUTPUT);

    // Turn off all LEDs initially
    digitalWrite(LED_RED, HIGH);   // HIGH = OFF (common anode)
    digitalWrite(LED_GREEN, HIGH);
    digitalWrite(LED_BLUE, HIGH);

    // Set initial state
    g_currentLedState = LED_STATE_BOOT;

    Serial.println("[INIT] Onboard LED OK");
}

// --- Set LED Color ---
void setLedColor(bool red, bool green, bool blue) {
    // XIAO nRF52840 uses common anode LED
    // HIGH = OFF, LOW = ON
    digitalWrite(LED_RED, red ? LOW : HIGH);
    digitalWrite(LED_GREEN, green ? LOW : HIGH);
    digitalWrite(LED_BLUE, blue ? LOW : HIGH);
}

// --- Set LED State ---
void setLedState(LedState state) {
    if (g_currentLedState != state) {
        g_currentLedState = state;
        g_ledBlinkState = false; // Reset blink state on change
        Serial.printf("[LED] State changed: %d\n", state);
    }
}

// --- Set LED Error State ---
void setLedError() {
    Serial.println("[LED] Entering ERROR state");
    setLedState(LED_STATE_ERROR);
}

// --- Update LED State Based on Connection and Sync Status ---
void updateLedStateBasedOnStatus() {
    if (g_deviceConnected) {
        setLedState(g_timeSynced ? LED_STATE_CONNECTED_SYNCED : LED_STATE_CONNECTED_NO_SYNC);
    } else {
        setLedState(g_timeSynced ? LED_STATE_SYNCED : LED_STATE_NO_SYNC);
    }
}

// Track last LED color to avoid unnecessary updates
static bool g_lastLedRed = false;
static bool g_lastLedGreen = false;
static bool g_lastLedBlue = false;

// --- Update LED (call in loop) ---
void updateLed() {
    bool newRed = false;
    bool newGreen = false;
    bool newBlue = false;
    bool needsUpdate = false;

    switch (g_currentLedState) {
        case LED_STATE_BOOT:
            newRed = true;
            newGreen = false;
            newBlue = false;
            break;

        case LED_STATE_NO_SYNC:
            if (g_currentMillis - g_lastLedBlink >= 1000) {
                g_ledBlinkState = !g_ledBlinkState;
                g_lastLedBlink = g_currentMillis;
                needsUpdate = true;
            }
            newRed = g_ledBlinkState;
            newGreen = false;
            newBlue = false;
            break;

        case LED_STATE_SYNCED:
            newRed = false;
            newGreen = true;
            newBlue = false;
            break;

        case LED_STATE_CONNECTED_NO_SYNC:
            if (g_currentMillis - g_lastLedBlink >= 1000) {
                g_ledBlinkState = !g_ledBlinkState;
                g_lastLedBlink = g_currentMillis;
                needsUpdate = true;
            }
            newRed = false;
            newGreen = false;
            newBlue = g_ledBlinkState;
            break;

        case LED_STATE_CONNECTED_SYNCED:
            newRed = false;
            newGreen = false;
            newBlue = true;
            break;

        case LED_STATE_ERROR:
            if (g_currentMillis - g_lastLedBlink >= 200) {
                g_ledBlinkState = !g_ledBlinkState;
                g_lastLedBlink = g_currentMillis;
                needsUpdate = true;
            }
            newRed = g_ledBlinkState;
            newGreen = false;
            newBlue = false;
            break;
    }

    // Only update if color changed
    if (needsUpdate || newRed != g_lastLedRed || newGreen != g_lastLedGreen || newBlue != g_lastLedBlue) {
        setLedColor(newRed, newGreen, newBlue);
        g_lastLedRed = newRed;
        g_lastLedGreen = newGreen;
        g_lastLedBlue = newBlue;
    }
}

// --- 7-Segment LED Display Functions ---

// Segment patterns for letters (7-segment display)
// Bit layout: 0bGFEDCBA
const uint8_t SEGMENT_CHARS[] = {
    0x77, // A (0b01110111)
    0x7C, // B (0b01111100)
    0x39, // C (0b00111001)
    0x5E, // D (0b01011110)
    0x79, // E (0b01111001)
    0x71, // F (0b01110001)
    0x3D, // G (0b00111101)
    0x76, // H (0b01110110)
    0x10, // I (0b00010000) = E segment only (bottom-left)
    0x38, // J (0b00111000)
    0x00, // K (unused)
    0x38, // L (0b00111000)
    0x37, // M (0b00110111) = same as N
    0x37, // N (0b00110111)
    0x3F, // O (0b00111111)
    0x73, // P (0b01110011)
    0x00, // Q (unused)
    0x50, // R (0b01010000)
    0x6D, // S (0b01101101)
    0x78, // T (0b01111000)
    0x3E, // U (0b00111110)
    0x00, // V (unused)
    0x3E, // W (0b00111110) = same as U
    0x00, // X (unused)
    0x00, // Y (unused)
    0x00  // Z (unused)
};

// --- Display Mode Switching ---
void updateDisplayForCurrentMode() {
    switch (g_displayMode) {
        case DISPLAY_MODE_TIME:
            updateTimeDisplay();
            break;
        case DISPLAY_MODE_DATE:
            updateDateDisplay();
            break;
        case DISPLAY_MODE_WEEKDAY:
            updateWeekdayDisplay();
            break;
        case DISPLAY_MODE_TEST:
            updateTestDisplay();
            break;
    }
}

// --- Time Display (HH:MM) ---
void updateTimeDisplay() {
    // getHours() already returns JST time
    int hours = getHours();
    int minutes = getMinutes();
    int seconds = getSeconds();

    // Display format: HH:MM (using colon)
    uint8_t data[] = { 0x00, 0x00, 0x00, 0x00 };

    // Calculate digits
    data[0] = g_display->encodeDigit(hours / 10);
    data[1] = g_display->encodeDigit(hours % 10); // Colon removed (will be added conditionally)
    data[2] = g_display->encodeDigit(minutes / 10);
    data[3] = g_display->encodeDigit(minutes % 10);

    // Light up second digit's dot (colon) when seconds are even
    if (seconds % 2 == 0) {
        data[1] |= 0x80;
    }

    g_display->setSegments(data);
}

// --- Date Display (MM/DD) ---
void updateDateDisplay() {
    int month = getMonth();
    int day = getDay();

    uint8_t data[] = { 0x00, 0x00, 0x00, 0x00 };

    // Format: MM/DD (e.g., 10/12 -> "1012", 4/8 -> " 4 8")
    data[0] = (month >= 10) ? g_display->encodeDigit(month / 10) : 0x00;
    data[1] = g_display->encodeDigit(month % 10);
    data[2] = (day >= 10) ? g_display->encodeDigit(day / 10) : 0x00;
    data[3] = g_display->encodeDigit(day % 10);

    g_display->setSegments(data);
}

// --- Weekday Display (MON/TUE/WED...) ---
void updateWeekdayDisplay() {
    int weekday = getWeekday(); // 0=Sun, 1=Mon, ..., 6=Sat
    const char* name = WEEKDAY_NAMES[weekday];

    uint8_t data[] = { 0x00, 0x00, 0x00, 0x00 };

    // Display: "MON " (left-aligned, 3 letters)
    data[0] = SEGMENT_CHARS[name[0] - 'A'];
    data[1] = SEGMENT_CHARS[name[1] - 'A'];
    data[2] = SEGMENT_CHARS[name[2] - 'A'];
    data[3] = 0x00; // 4th digit is blank

    g_display->setSegments(data);
}

// --- Test Display Mode ---
void updateTestDisplay() {
    uint8_t data[] = { 0x00, 0x00, 0x00, 0x00 };

    switch (g_testDisplayIndex) {
        case 1: // 0123
            data[0] = g_display->encodeDigit(0);
            data[1] = g_display->encodeDigit(1);
            data[2] = g_display->encodeDigit(2);
            data[3] = g_display->encodeDigit(3);
            break;

        case 2: // 4567
            data[0] = g_display->encodeDigit(4);
            data[1] = g_display->encodeDigit(5);
            data[2] = g_display->encodeDigit(6);
            data[3] = g_display->encodeDigit(7);
            break;

        case 3: // 89 (blank, blank, 8, 9)
            data[0] = 0x00;
            data[1] = 0x00;
            data[2] = g_display->encodeDigit(8);
            data[3] = g_display->encodeDigit(9);
            break;

        case 4: // Sunday
            data[0] = SEGMENT_CHARS['S' - 'A'];
            data[1] = SEGMENT_CHARS['U' - 'A'];
            data[2] = SEGMENT_CHARS['N' - 'A'];
            data[3] = 0x00;
            break;

        case 5: // Monday
            data[0] = SEGMENT_CHARS['M' - 'A'];
            data[1] = SEGMENT_CHARS['O' - 'A'];
            data[2] = SEGMENT_CHARS['N' - 'A'];
            data[3] = 0x00;
            break;

        case 6: // Tuesday
            data[0] = SEGMENT_CHARS['T' - 'A'];
            data[1] = SEGMENT_CHARS['U' - 'A'];
            data[2] = SEGMENT_CHARS['E' - 'A'];
            data[3] = 0x00;
            break;

        case 7: // Wednesday
            data[0] = SEGMENT_CHARS['W' - 'A'];
            data[1] = SEGMENT_CHARS['E' - 'A'];
            data[2] = SEGMENT_CHARS['D' - 'A'];
            data[3] = 0x00;
            break;

        case 8: // Thursday
            data[0] = SEGMENT_CHARS['T' - 'A'];
            data[1] = SEGMENT_CHARS['H' - 'A'];
            data[2] = SEGMENT_CHARS['U' - 'A'];
            data[3] = 0x00;
            break;

        case 9: // Friday
            data[0] = SEGMENT_CHARS['F' - 'A'];
            data[1] = SEGMENT_CHARS['R' - 'A'];
            data[2] = SEGMENT_CHARS['I' - 'A'];
            data[3] = 0x00;
            break;

        case 10: // Saturday
            data[0] = SEGMENT_CHARS['S' - 'A'];
            data[1] = SEGMENT_CHARS['A' - 'A'];
            data[2] = SEGMENT_CHARS['T' - 'A'];
            data[3] = 0x00;
            break;
    }

    g_display->setSegments(data);
}
