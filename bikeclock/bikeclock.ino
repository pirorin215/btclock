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
Adafruit_MCP23X17 mcp;  // MCP23S17 I/O expander (SPI)
bool g_mcp23S17Connected = false;  // MCP23S17 connection status
unsigned long g_lastCounterMillis = 0;  // Last time internal counter was updated
unsigned long g_currentMillis = 0;  // Current time for this loop iteration
LedState g_currentLedState = LED_STATE_BOOT;
DisplayMode g_displayMode = DISPLAY_MODE_TIME;  // Initial mode: time display
int g_testDisplayIndex = 1;  // Test mode display index (1-10)

// External BLE characteristics
extern BLECharacteristic bleSwitchNotifyCharacteristic;

// Time management
unsigned long g_lastScreenMillis = 0;  // Last time screen display was updated
bool g_timeSynced = false;

// Date caching (to avoid redundant calculations)
DateCache g_dateCache = {0, 0, 0, 0, false};

// --- HID Switch Functions ---
HidSwitch hidSwitches[] = {
    {MCP_SW1_PIN, HIGH, 0, 0, HID_STATE_IDLE, DEFAULT_SW1_KEYCODE},
    {MCP_SW2_PIN, HIGH, 0, 0, HID_STATE_IDLE, DEFAULT_SW2_KEYCODE},
    {MCP_SW3_PIN, HIGH, 0, 0, HID_STATE_IDLE, DEFAULT_SW3_KEYCODE},
    {MCP_SW4_PIN, HIGH, 0, 0, HID_STATE_IDLE, DEFAULT_SW4_KEYCODE},
    {MCP_SW5_PIN, HIGH, 0, 0, HID_STATE_IDLE, DEFAULT_SW5_KEYCODE},
    {MCP_SW6_PIN, HIGH, 0, 0, HID_STATE_IDLE, DEFAULT_SW6_KEYCODE},
    {MCP_SW7_PIN, HIGH, 0, 0, HID_STATE_IDLE, DEFAULT_SW7_KEYCODE}
};
#define NUM_HID_SWITCHES 7

// --- Time Helper Functions ---
// g_currentTimestampは「JSTとしてのUnix timestamp」
// （アプリ側がJST日時をUnix timestampに変換した値）
int getHours() {
    return (g_currentTimestamp % 86400) / 3600;
}

int getMinutes() {
    return (g_currentTimestamp % 3600) / 60;
}

int getSeconds() {
    return g_currentTimestamp % 60;
}

// Unix timestamp to date calculation (simplified)
// g_currentTimestamp is JST-adjusted (UTC timestamp + 9 hours)
// Returns: days since 1970-01-01 (JST-based)
uint32_t getDaysSinceEpoch() {
    return g_currentTimestamp / 86400;
}

// Calculate month and day from days since epoch
void getMonthDay(int* month, int* day) {
    // Check cache first
    if (g_dateCache.valid && g_dateCache.lastTimestamp == g_currentTimestamp) {
        *month = g_dateCache.month;
        *day = g_dateCache.day;
        return;
    }

    uint32_t days = getDaysSinceEpoch();

    // Simplified calculation for years 2020-2099
    // Adjust for years since 1970
    uint32_t year = 1970;
    uint32_t days_in_year;

    // Find year
    while (true) {
        // Check for leap year (divisible by 4, except centuries not divisible by 400)
        bool is_leap = (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0));
        days_in_year = is_leap ? 366 : 365;

        if (days < days_in_year) break;
        days -= days_in_year;
        year++;
    }

    // Find month
    static const uint8_t days_in_month[] = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    int m = 0;

    // Adjust February for leap year
    bool is_leap = (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0));
    uint8_t dim_feb = is_leap ? 29 : 28;

    for (m = 0; m < 12; m++) {
        uint8_t dim = (m == 1) ? dim_feb : days_in_month[m];
        if (days < dim) break;
        days -= dim;
    }

    // Update cache
    g_dateCache.month = m + 1;  // 1-12
    g_dateCache.day = days + 1; // 1-31
    g_dateCache.weekday = (getDaysSinceEpoch() + 4) % 7;
    g_dateCache.lastTimestamp = g_currentTimestamp;
    g_dateCache.valid = true;

    *month = g_dateCache.month;
    *day = g_dateCache.day;
}

int getMonth() {
    int month, day;
    getMonthDay(&month, &day);
    return month;
}

int getDay() {
    int month, day;
    getMonthDay(&month, &day);
    return day;
}

int getWeekday() {
    // Use cache if available
    if (g_dateCache.valid && g_dateCache.lastTimestamp == g_currentTimestamp) {
        return g_dateCache.weekday;
    }
    // Otherwise calculate and cache
    int month, day;
    getMonthDay(&month, &day);
    return g_dateCache.weekday;
}

// --- HID Switch Processing ---

// Initialize MCP23S17 I/O expander (SPI)
void setupMCP23S17() {
    Serial.println("[MCP] Starting MCP23S17 initialization (Hardware SPI, Low Speed)...");

    // Start hardware SPI
    SPI.begin();

    // Use a very low SPI speed (100kHz) for maximum reliability
    // The Adafruit library uses a default frequency, but we can try to force it via begin_SPI if supported, 
    // or rely on the chip's stability at lower voltage.
    if (!mcp.begin_SPI(MCP_SPI_CS_GPIO, &SPI)) {
        Serial.println("[MCP] ERROR: MCP23S17 NOT detected!");
        g_mcp23S17Connected = false;
        return;
    }

    // Configure all pins as inputs with pull-up
    for (int i = 0; i < 8; i++) {
        mcp.pinMode(i, INPUT_PULLUP);
    }
    delay(200); // Give plenty of time for pull-ups to rise

    // Communication Verification: Must not be 0x00 if nothing is pressed
    Serial.println("[MCP] Verifying connection...");
    uint8_t pinValues = 0;
    int retry = 0;
    while (retry < 10) {
        pinValues = 0;
        for (int i = 0; i < 8; i++) {
            if (mcp.digitalRead(i)) pinValues |= (1 << i);
        }
        
        if (pinValues != 0x00) break; // Found something!
        
        Serial.println("[MCP] Still reading 0x00, retrying...");
        delay(100);
        retry++;
    }

    if (pinValues == 0x00) {
        Serial.println("[MCP] FATAL ERROR: All pins read as LOW. Communication is dead.");
        g_mcp23S17Connected = false;
        return;
    }

    Serial.printf("[MCP] Connection verified! Initial state: 0x%02X\n", pinValues);

    // Synchronize initial hardware state simply
    for (int i = 0; i < NUM_HID_SWITCHES; i++) {
        hidSwitches[i].pinState = (pinValues >> hidSwitches[i].gpio) & 0x01;
        hidSwitches[i].state = HID_STATE_IDLE;
        hidSwitches[i].lastDebounceTime = millis();
    }

    g_mcp23S17Connected = true;
    Serial.println("[MCP] MCP23S17 initialized successfully");
}
void processHidSwitches() {
    // Only process HID switches if MCP23S17 is connected
    if (!g_mcp23S17Connected) {
        return;
    }

    for (int i = 0; i < NUM_HID_SWITCHES; i++) {
        // Read from MCP23S17
        uint8_t reading = mcp.digitalRead(hidSwitches[i].gpio);

        // Check if switch state changed (due to noise or pressing)
        if (reading != hidSwitches[i].pinState) {
            hidSwitches[i].lastDebounceTime = millis();
            hidSwitches[i].pinState = reading;
        }

        // Check if debounce delay passed
        if ((millis() - hidSwitches[i].lastDebounceTime) > HID_DEBOUNCE_DELAY_MS) {
            // State machine
            switch (hidSwitches[i].state) {
                case HID_STATE_IDLE:
                    // Check if switch is pressed (LOW)
                    if (reading == LOW) {
                        Serial.printf("[HID] SW%d P\n", i + 1);
                        sendHidKeyPress(hidSwitches[i].keyCode, NULL);

                        hidSwitches[i].state = HID_STATE_PRESS;
                        hidSwitches[i].pressStartTime = millis();
                    }
                    break;

                case HID_STATE_PRESS:
                    if (reading == LOW) {
                        // Check for repeat (long press)
                        if ((millis() - hidSwitches[i].pressStartTime) > HID_REPEAT_DELAY_MS) {
                            // OS側のオートリピートに頼らず、マイコン側で一旦離してまた押す
                            sendHidKeyRelease(NULL);
                            delay(10);
                            sendHidKeyPress(hidSwitches[i].keyCode, NULL);

                            hidSwitches[i].state = HID_STATE_REPEAT;
                            hidSwitches[i].pressStartTime = millis();
                        }
                    } else {
                        // Switch released
                        // Only send release if it was actually pressed
                        if (hidSwitches[i].state == HID_STATE_PRESS || hidSwitches[i].state == HID_STATE_REPEAT) {
                            Serial.printf("[HID] SW%d R\n", i + 1);
                            sendHidKeyRelease(NULL);
                        }
                        hidSwitches[i].state = HID_STATE_IDLE;
                    }
                    break;

                case HID_STATE_REPEAT:
                    if (reading == LOW) {
                        // Continue repeating
                        if ((millis() - hidSwitches[i].pressStartTime) > HID_REPEAT_INTERVAL_MS) {
                            // 一旦離してまた押すことで、OS側に新しいキー入力として認識させる
                            sendHidKeyRelease(NULL);
                            delay(10);
                            sendHidKeyPress(hidSwitches[i].keyCode, NULL);

                            hidSwitches[i].pressStartTime = millis();
                        }
                    } else {
                        // Switch released
                        // Only send release if it was actually pressed
                        if (hidSwitches[i].state == HID_STATE_PRESS || hidSwitches[i].state == HID_STATE_REPEAT) {
                            Serial.printf("[HID] SW%d R\n", i + 1);
                            sendHidKeyRelease(NULL);
                        }
                        hidSwitches[i].state = HID_STATE_IDLE;
                    }
                    break;
            }
        }
    }
}

// --- Function Key Processing (Mode Switch) ---
void processFunctionKey() {
    // Only process FUNC key if MCP23S17 is connected
    if (!g_mcp23S17Connected) {
        return;
    }

    static unsigned long lastDebounce = 0;
    static bool lastState = HIGH;
    static bool debouncedState = HIGH;

    // Read from MCP23S17 GP7
    bool reading = mcp.digitalRead(MCP_FUNC_PIN);

    // Detect state change
    if (reading != lastState) {
        lastDebounce = millis();
        lastState = reading;
    }

    // After debounce delay
    if ((millis() - lastDebounce) > HID_DEBOUNCE_DELAY_MS && reading != debouncedState) {
        debouncedState = reading;

        // Only process on press (LOW)
        if (debouncedState == LOW) {
            Serial.println("[FUNC] SW8 pressed - Mode change triggered");
            if (g_displayMode == DISPLAY_MODE_TEST) {
                // Test mode: cycle through test displays
                g_testDisplayIndex++;
                if (g_testDisplayIndex > 10) {
                    g_testDisplayIndex = 1;
                }
                updateTestDisplay();
                Serial.printf("[TEST] Display %d\n", g_testDisplayIndex);
            } else {
                // Normal mode: switch display mode (cycle through TIME, DATE, WEEKDAY)
                if (g_displayMode >= DISPLAY_MODE_WEEKDAY) {
                    g_displayMode = DISPLAY_MODE_TIME;
                } else {
                    g_displayMode = (DisplayMode)(g_displayMode + 1);
                }

                // Update display immediately
                updateDisplayForCurrentMode();

                Serial.printf("[FUNC] Mode changed to: %d\n", g_displayMode);
            }
        }
    }
}

// --- System Utilities ---

// Update timestamp (simple tick counter)
void updateTimestamp() {
    if (g_currentMillis - g_lastCounterMillis >= 1000) {
        g_currentTimestamp++;
        g_lastCounterMillis = g_currentMillis;
    }
}

// Update display and LED state based on time sync and connection status
void updateDisplayAndLedState() {
    if (!g_timeSynced) {
        // Time not synced yet, show "88:88" (invalid time indicator)
        static unsigned long lastBlink = 0;
        if (g_currentMillis - lastBlink >= 500) {
            static bool showPattern = true;
            if (showPattern) {
                g_display->showNumberDec(8888);
            } else {
                g_display->clear();
            }
            showPattern = !showPattern;
            lastBlink = g_currentMillis;
        }

        // Update LED state based on connection status
        updateLedStateBasedOnStatus();
    } else {
        // Time synced - update LED state based on connection status
        updateLedStateBasedOnStatus();

        if (g_currentMillis - g_lastScreenMillis >= DISPLAY_UPDATE_INTERVAL_MS) {
            // Update display according to current mode
            updateDisplayForCurrentMode();
            g_lastScreenMillis = g_currentMillis;
        }
    }
}

// --- Main Functions ---
void setup() {
    Serial.begin(115200);

    // Wait for serial console to connect
    // This allows us to see the initialization sequence
    unsigned long startTime = millis();
    while (!Serial && (millis() - startTime < 5000)) {
    }

    Serial.println("[BIKECLOCK] " __DATE__ " " __TIME__);

    // Wait for power to stabilize and MCP23S17 to wake up properly
    // Especially important when power is supplied via USB/Ignition
    delay(500); 

    // Initialize MCP23S17 I/O expander (SPI)
    setupMCP23S17();

    // Initialize switch states
    for (int i = 0; i < NUM_HID_SWITCHES; i++) {
        hidSwitches[i].pinState = HIGH;
        hidSwitches[i].lastDebounceTime = 0;
        hidSwitches[i].state = HID_STATE_IDLE;
    }
    Serial.println("[INIT] Switches OK");

    // Check if function key is held down during startup -> Test mode
    // Only check if MCP23S17 is connected
    delay(50); // Brief delay for pin to stabilize
    if (g_mcp23S17Connected && mcp.digitalRead(MCP_FUNC_PIN) == LOW) {
        // Function key is pressed -> Enter test mode
        g_displayMode = DISPLAY_MODE_TEST;
        g_testDisplayIndex = 1;
        Serial.println("[INIT] TEST MODE ACTIVATED");
    }

    g_display = new TM1637Display(LED_CLK_GPIO, LED_DIO_GPIO);
    g_display->setBrightness(0x0F);
    g_display->clear();
    g_display->showNumberDec(8888);
    Serial.println("[INIT] Display OK");

    setupLed();

    setupBLE();

    g_lastCounterMillis = millis();
    Serial.println("[INIT] Waiting for BLE...");
}

void loop() {
    // Get current time once for this loop iteration
    g_currentMillis = millis();

    // Update onboard LED
    updateLed();

    // Process function key (mode switch)
    processFunctionKey();

    // Process HID switches
    processHidSwitches();

    // System utilities
    updateTimestamp();

    // Display and LED update
    updateDisplayAndLedState();

    delay(10);
}
