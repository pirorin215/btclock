/**
 * LED Control for BikeClock
 *
 * Handles onboard RGB LED and 7-segment LED display (TM1637).
 */

#include "bikeclock.h"

// Weekday names for display
static const char* WEEKDAY_NAMES[] = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};

// Segment patterns for letters (7-segment display)
// Bit layout: 0bGFEDCBA
const uint8_t SEGMENT_CHARS[] = {
    0x77, // A
    0x7C, // B
    0x39, // C
    0x5E, // D
    0x79, // E
    0x71, // F
    0x3D, // G
    0x74, // H
    0x10, // I
    0x1E, // J
    0x75, // K
    0x38, // L
    0x37, // M
    0x54, // N
    0x5C, // O
    0x73, // P
    0x67, // Q
    0x50, // R
    0x6C, // S
    0x78, // T
    0x1C, // U
    0x3E, // V
    0x2A, // W
    0x76, // X
    0x6E, // Y
    0x1B  // Z
};

// Test mode display patterns
static const char* TEST_PATTERNS[] = {
    "1234",  // 1
    "4567",  // 2
    " 89",   // 3
    "SUN",   // 4
    "MON",   // 5
    "TUE",   // 6
    "WED",   // 7
    "THU",   // 8
    "FRI",   // 9
    "SAT",   // 10
    "ABCD",  // 11
    "EFGH",  // 12
    "IJKL",  // 13
    "MNOP",  // 14
    "QRST",  // 15
    "UVWX",  // 16
    "YZ",    // 17
};
const int TEST_PATTERN_COUNT = sizeof(TEST_PATTERNS) / sizeof(TEST_PATTERNS[0]);

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

    logPrint("INIT", "Onboard LED OK");
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
        logPrint("LED", "State changed: %d", state);
    }
}

// --- Set LED Error State ---
void setLedError() {
    logPrint("LED", "Entering ERROR state");
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
    encodeStringToSegments(name, data);

    g_display->setSegments(data);
}

// --- Version Display Function ---
void displayVersion() {
    uint8_t data[] = { 0x00, 0x00, 0x00, 0x00 };

    // Display version
    data[0] = g_display->encodeDigit(FIRMWARE_VERSION_MAJOR) | 0x80;  // メジャー + 小数点
    data[1] = g_display->encodeDigit(FIRMWARE_VERSION_MINOR);           // マイナー
    data[2] = g_display->encodeDigit(FIRMWARE_VERSION_PATCH / 10);       // パッチ（十の位）
    data[3] = g_display->encodeDigit(FIRMWARE_VERSION_PATCH % 10);       // パッチ（一の位）

    g_display->setSegments(data);
}

// --- Encode String to 7-Segment Data ---
// str: Input string (e.g., "SUN", "ABCD", "1234")
// data: Output array (4 elements) - will be filled with segment data
// Supported characters: A-Z, 0-9, space (0x00 for unknown chars)
void encodeStringToSegments(const char* str, uint8_t* data) {
    // Initialize with blanks
    for (int i = 0; i < 4; i++) {
        data[i] = 0x00;
    }

    // Process each character (max 4)
    for (int i = 0; i < 4 && str[i] != '\0'; i++) {
        char c = str[i];

        if (c >= 'A' && c <= 'Z') {
            // Uppercase letters
            data[i] = SEGMENT_CHARS[c - 'A'];
        } else if (c >= 'a' && c <= 'z') {
            // Lowercase letters - convert to uppercase
            data[i] = SEGMENT_CHARS[c - 'a'];
        } else if (c >= '0' && c <= '9') {
            // Digits
            data[i] = g_display->encodeDigit(c - '0');
        } else if (c == ' ') {
            // Space - blank
            data[i] = 0x00;
        } else {
            // Unknown character - blank
            data[i] = 0x00;
        }
    }
}

// --- Test Display Mode ---
void updateTestDisplay() {
    // Convert to 0-based index and ensure bounds
    int index = (g_testDisplayIndex - 1) % TEST_PATTERN_COUNT;

    // Encode and display
    uint8_t data[4] = {0};
    encodeStringToSegments(TEST_PATTERNS[index], data);
    g_display->setSegments(data);
}
