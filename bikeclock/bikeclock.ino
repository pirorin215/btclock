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
bool g_skipBleInit = false;  // Skip BLE initialization (for factory reset/test mode)
bool g_displayingKeyCodes = false;  // Currently displaying key codes (skip time display)
uint16_t g_displayingKeyCode = 0;  // Currently displaying HID key code (0 = none)
unsigned long g_keyCodeDisplayEndTime = 0;  // When to stop displaying key code
bool g_showingCountdown = false;  // Currently showing countdown (skip time display)
unsigned long g_lastCounterMillis = 0;  // Last time internal counter was updated
unsigned long g_currentMillis = 0;  // Current time for this loop iteration
unsigned long g_startupMillis = 0;  // Startup time (for log timestamps)
LedState g_currentLedState = LED_STATE_BOOT;
DisplayMode g_displayMode = DISPLAY_MODE_TIME;  // Initial mode: time display
int g_testDisplayIndex = 1;  // Test mode display index (1-10)

// Maintenance mode state
MaintenanceState g_maintenanceState = {
    false,                     // active
    MAINTENANCE_MENU_CANCEL,    // currentMenu
    0,                         // lastInteractionMillis
    0                          // selectedMenuIndex
};

// External BLE characteristics
extern BLECharacteristic bleSwitchNotifyCharacteristic;

// --- External Functions (from bikeclock_hid.ino) ---
void setupMCP23S17();
void processHidSwitches();
void checkStartupFuncKey();
void processFunctionKey();

// --- HID Switch Definitions ---
// Defined here so they can be accessed from all .ino files
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

// Time management
unsigned long g_lastScreenMillis = 0;  // Last time screen display was updated
bool g_timeSynced = false;

// Date caching (to avoid redundant calculations)
DateCache g_dateCache = {0, 0, 0, 0, false};

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

// --- Settings Reset Function ---
void resetKeySettingsToDefaults() {
    logPrint("FACTORY_RESET", "Resetting key settings to defaults...");

    // Format entire InternalFS to completely erase all settings
    logPrint("FACTORY_RESET", "Formatting InternalFS...");
    InternalFS.format();
    logPrint("FACTORY_RESET", "InternalFS formatted successfully.");

    // Reload default settings
    for (int i = 0; i < NUM_HID_SWITCHES; i++) {
        uint16_t defaultKey;
        switch (i) {
            case 0: defaultKey = DEFAULT_SW1_KEYCODE; break;
            case 1: defaultKey = DEFAULT_SW2_KEYCODE; break;
            case 2: defaultKey = DEFAULT_SW3_KEYCODE; break;
            case 3: defaultKey = DEFAULT_SW4_KEYCODE; break;
            case 4: defaultKey = DEFAULT_SW5_KEYCODE; break;
            case 5: defaultKey = DEFAULT_SW6_KEYCODE; break;
            case 6: defaultKey = DEFAULT_SW7_KEYCODE; break;
            default: defaultKey = 0; break;
        }
        hidSwitches[i].keyCode = defaultKey;
    }

    logPrint("FACTORY_RESET", "Key settings reset to defaults:");
    for (int i = 0; i < NUM_HID_SWITCHES; i++) {
        logPrint("FACTORY_RESET", "  SW%d KeyCode: 0x%04X", i + 1, hidSwitches[i].keyCode);
    }
}

void resetToFactoryDefaults() {
    logPrint("FACTORY_RESET", "Resetting all settings to factory defaults...");

    // Reset key settings
    resetKeySettingsToDefaults();

    // Add other reset functions here as needed
    // Example: resetDisplaySettingsToDefaults();
    //          resetBluetoothSettingsToDefaults();

    logPrint("FACTORY_RESET", "Factory reset complete.");
    logPrint("FACTORY_RESET", "System will restart in 2 seconds...");

    // Give time for serial output to complete
    delay(2000);

    // System reset
    NVIC_SystemReset();
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
    // Skip all display updates if showing countdown
    if (g_showingCountdown) {
        return;
    }

    // Check if displaying HID key code
    if (g_displayingKeyCode != 0) {
        // Continue displaying key code until timeout
        if (g_currentMillis < g_keyCodeDisplayEndTime) {
            // Keep displaying the key code (already set)
            return;
        } else {
            // Timeout - clear key code display
            g_displayingKeyCode = 0;
            g_keyCodeDisplayEndTime = 0;
            logPrint("HID", "Key code display timeout - resuming normal display");
        }
    }

    // Skip display update if currently showing key codes from app
    if (g_displayingKeyCodes) {
        return;
    }

    // Skip time sync indicator in test mode
    if (!g_timeSynced && g_displayMode != DISPLAY_MODE_TEST) {
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
    // NOTE: Commented out for faster startup without USB connection
    // unsigned long startTime = millis();
    // while (!Serial && (millis() - startTime < 5000)) {
    // }

    // Initialize logging first
    setupLog();

    logPrint("BIKECLOCK", __DATE__ " " __TIME__);

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
    logPrint("INIT", "Switches OK");

    // Initialize display BEFORE checking FUNC key (so we can show countdown)
    g_display = new TM1637Display(LED_CLK_GPIO, LED_DIO_GPIO);
    g_display->setBrightness(0x0F);
    g_display->clear();
    g_display->showNumberDec(8888);
    logPrint("INIT", "Display OK");

    // Check if function key is held down during startup
    checkStartupFuncKey();

    setupLed();

    // Only initialize BLE if not in factory reset/test mode
    if (!g_skipBleInit) {
        setupBLE();
        g_lastCounterMillis = millis();
        logPrint("INIT", "Waiting for BLE...");
    } else {
        logPrint("INIT", "BLE initialization skipped (factory reset/test mode)");
        g_lastCounterMillis = millis();
    }
}

void loop() {
    // Get current time once for this loop iteration
    g_currentMillis = millis();

    // Process function key (mode switch) - must be called first
    processFunctionKey();

    // Process maintenance mode if active
    if (!processMaintenanceMode()) {
        // If maintenance mode is not active, process normal operations
        // Update onboard LED
        updateLed();

        // Process HID switches
        processHidSwitches();

        // System utilities
        updateTimestamp();

        // Display and LED update
        updateDisplayAndLedState();
    }

    delay(10);
}
