/**
 * BikeClock - XIAO BLE based bicycle clock
 *
 * Features:
 * - BLE time synchronization with smartphone
 * - 4-digit 7-segment LED display (TM1637)
 * - Always powered from bike battery (no battery backup needed)
 * - Auto time correction on app connection
 */

#include "bikeclock.h"

// --- Global Variables ---
TM1637Display* g_display = nullptr;
volatile uint32_t g_currentTimestamp = 0;  // Unix timestamp
unsigned long g_lastMillis = 0;

// Time management
unsigned long g_lastDisplayUpdate = 0;
bool g_timeSynced = false;

// Debug logging
unsigned long g_lastHeartbeat = 0;
unsigned long g_loopCount = 0;

// --- Time Helper Functions ---
int getHours() {
    return (g_currentTimestamp % 86400) / 3600;  // UTC hours
}

int getMinutes() {
    return (g_currentTimestamp % 3600) / 60;     // UTC minutes
}

int getSeconds() {
    return g_currentTimestamp % 60;               // UTC seconds
}

// --- Main Functions ---
void setup() {
    Serial.begin(115200);

    // Short delay to let Serial initialize (no blocking wait loop)
    delay(200);

    Serial.println("");
    Serial.println("========================================");
    Serial.println("[BIKECLOCK] BikeClock starting...");
    Serial.println("[BIKECLOCK] Firmware Version: 1.0.1");
    Serial.println("[BIKECLOCK] Build Date: " __DATE__ " " __TIME__);
    Serial.println("========================================");

    // Initialize LED display (max brightness)
    Serial.println("[BIKECLOCK] Initializing display...");
    g_display = new TM1637Display(LED_CLK_GPIO, LED_DIO_GPIO);
    g_display->setBrightness(0x0F); // Maximum brightness
    Serial.println("[BIKECLOCK] Display initialized (CLK=D5, DIO=D4)");

    // Show startup pattern
    Serial.println("[BIKECLOCK] Showing startup pattern...");
    g_display->showNumberDec(8888);
    delay(1000);
    g_display->clear();

    // Initialize BLE
    Serial.println("[BIKECLOCK] Initializing BLE...");
    setupBLE();

    g_lastMillis = millis();
    g_lastHeartbeat = millis();

    Serial.println("[BIKECLOCK] Setup complete.");
    Serial.println("[BIKECLOCK] Status: Waiting for BLE connection...");
    Serial.println("[BIKECLOCK] Initial timestamp: 0 (not synced)");
    Serial.println("========================================");
    Serial.println("[BIKECLOCK] Starting main loop...");
    Serial.println("");
}

void loop() {
    g_loopCount++;

    // Update timestamp (simple tick counter)
    unsigned long currentMillis = millis();
    if (currentMillis - g_lastMillis >= 1000) {
        g_currentTimestamp++;
        g_lastMillis = currentMillis;

        // Log time every 10 seconds
        if (g_currentTimestamp % 10 == 0) {
            int hours = getHours();
            int minutes = getMinutes();
            int seconds = getSeconds();
            Serial.printf("[BIKECLOCK] Time: %02d:%02d:%02d (UTC) | JST: %02d:%02d:%02d | Timestamp: %lu | Synced: %s\n",
                         hours, minutes, seconds,
                         (hours + 9) % 24, minutes, seconds,
                         g_currentTimestamp,
                         g_timeSynced ? "YES" : "NO");
        }
    }

    // Heartbeat logging every 5 seconds
    if (currentMillis - g_lastHeartbeat >= 5000) {
        Serial.printf("[HEARTBEAT v1.0.1] Uptime: %lu sec | Loops: %lu | TimeSynced: %s | Timestamp: %lu\n",
                     currentMillis / 1000,
                     g_loopCount,
                     g_timeSynced ? "YES" : "NO",
                     g_currentTimestamp);
        g_lastHeartbeat = currentMillis;
    }

    // BLE event handling is automatic with Bluefruit library
    // No polling required

    // Display update
    if (!g_timeSynced) {
        // Time not synced yet, show "88:88" (invalid time indicator)
        static unsigned long lastBlink = 0;
        if (currentMillis - lastBlink >= 500) {
            static bool showPattern = true;
            if (showPattern) {
                g_display->showNumberDec(8888);
            } else {
                g_display->clear();
            }
            showPattern = !showPattern;
            lastBlink = currentMillis;
        }
    } else if (currentMillis - g_lastDisplayUpdate >= DISPLAY_UPDATE_INTERVAL_MS) {
        // Update time display every second
        updateTimeDisplay();
        g_lastDisplayUpdate = currentMillis;
    }

    delay(10);
}

void updateTimeDisplay() {
    // Convert to JST (UTC+9)
    int hours = (getHours() + 9) % 24;
    int minutes = getMinutes();

    // Display format: HH:MM (using colon)
    uint8_t data[] = { 0x00, 0x00, 0x00, 0x00 };

    // Calculate digits
    data[0] = g_display->encodeDigit(hours / 10);
    data[1] = g_display->encodeDigit(hours % 10) | 0x80; // Add colon
    data[2] = g_display->encodeDigit(minutes / 10);
    data[3] = g_display->encodeDigit(minutes % 10);

    g_display->setSegments(data);
}
