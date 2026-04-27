/**
 * BikeClock - HID Switch & Function Key Processing
 *
 * This file handles:
 * - MCP23S17 I/O Expander initialization
 * - HID switch processing (SW1-SW7)
 * - Function key processing (SW8)
 */

// --- External Declarations ---
extern TM1637Display* g_display;
extern Adafruit_MCP23X17 mcp;
extern bool g_mcp23S17Connected;
extern bool g_skipBleInit;
extern DisplayMode g_displayMode;
extern int g_testDisplayIndex;

// HID switch array (defined in bikeclock.ino)
extern HidSwitch hidSwitches[];

// --- MCP23S17 Initialization ---
// Initialize MCP23S17 I/O expander (SPI)
void setupMCP23S17() {
    Serial.println("[MCP] Starting MCP23S17 initialization (Hardware SPI, Low Speed)...");
    Serial.printf("[MCP] SPI Frequency: %d Hz (%d kHz)\n", MCP_SPI_FREQ, MCP_SPI_FREQ / 1000);

    // Start hardware SPI
    SPI.begin();

    // Initialize MCP23S17 with default frequency (library uses 1MHz internally)
    // Note: To use custom frequency, we need to modify the library approach
    if (!mcp.begin_SPI(MCP_SPI_CS_GPIO, &SPI)) {
        Serial.println("[MCP] ERROR: MCP23S17 NOT detected!");
        g_mcp23S17Connected = false;
        return;
    }
    Serial.println("[MCP] MCP23S17 initialized successfully");

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

// --- HID Switch Processing ---
void processHidSwitches() {
    // Only process HID switches if MCP23S17 is connected
    if (!g_mcp23S17Connected) {
        return;
    }

    for (int i = 0; i < NUM_HID_SWITCHES; i++) {
        // Read from MCP23S17
        uint8_t reading = mcp.digitalRead(hidSwitches[i].gpio);

        // Only apply debounce when switch is released (LOW->HIGH transition)
        if (reading == HIGH && hidSwitches[i].pinState == LOW) {
            // Switch released - update debounce time
            hidSwitches[i].lastDebounceTime = millis();
        }
        // Always update pinState for next comparison
        hidSwitches[i].pinState = reading;

        // Skip debounce check during press to ensure quick response
        bool skipDebounce = (hidSwitches[i].state == HID_STATE_PRESS);

        if (skipDebounce || (millis() - hidSwitches[i].lastDebounceTime) > HID_DEBOUNCE_DELAY_MS) {
            // State machine
            switch (hidSwitches[i].state) {
                case HID_STATE_IDLE:
                    // Check if switch is pressed (LOW)
                    if (reading == LOW) {
                        Serial.printf("[HID] SW%d P\n", i + 1);
                        sendHidKeyPress(hidSwitches[i].keyCode, NULL);

                        // Display key code on 7-segment
                        extern uint16_t g_displayingKeyCode;
                        extern unsigned long g_keyCodeDisplayEndTime;
                        g_displayingKeyCode = hidSwitches[i].keyCode;
                        g_keyCodeDisplayEndTime = millis() + 500;  // Display for 500ms
                        g_display->showNumberDec(hidSwitches[i].keyCode);
                        Serial.printf("[HID] Displaying key code: %d\n", hidSwitches[i].keyCode);

                        hidSwitches[i].state = HID_STATE_PRESS;
                    }
                    break;

                case HID_STATE_PRESS:
                    if (reading == HIGH) {
                        // Switch released
                        Serial.printf("[HID] SW%d R\n", i + 1);
                        sendHidKeyRelease(NULL);
                        hidSwitches[i].state = HID_STATE_IDLE;
                    }
                    break;
            }
        }
    }
}

// --- Startup FUNC Key Check ---
void checkStartupFuncKey() {
    // Check if function key is held down during startup
    // Only check if MCP23S17 is connected
    delay(50); // Brief delay for pin to stabilize
    if (g_mcp23S17Connected && mcp.digitalRead(MCP_FUNC_PIN) == LOW) {
        // Function key is pressed - skip BLE initialization
        g_skipBleInit = true;
        Serial.println("[INIT] FUNC key detected at startup - BLE will be skipped...");
        unsigned long pressStartTime = millis();

        // LED & Display feedback: Show countdown
        // 7-segment: 1, 2, 3, 4, 5
        // LED colors: Red → Green → Blue → Yellow → Magenta
        while (mcp.digitalRead(MCP_FUNC_PIN) == LOW && (millis() - pressStartTime < 5000)) {
            unsigned long elapsed = millis() - pressStartTime;
            int seconds = (int)(elapsed / 1000) + 1;

            // Update 7-segment display with countdown
            g_display->showNumberDec(seconds);

            // Change LED color every second
            switch (seconds) {
                case 1:
                    setLedColor(true, false, false);  // Red
                    break;
                case 2:
                    setLedColor(false, true, false);  // Green
                    break;
                case 3:
                    setLedColor(false, false, true);  // Blue
                    break;
                case 4:
                    setLedColor(true, true, false);    // Yellow
                    break;
                case 5:
                    setLedColor(true, false, true);   // Magenta (about to reset)
                    break;
            }
            delay(100);
        }

        unsigned long pressDuration = millis() - pressStartTime;

        if (pressDuration >= 5000) {
            // Long press (5+ seconds) -> Reset settings to defaults
            // LED & Display feedback: Show "RESET" pattern
            Serial.println("[INIT] Factory reset initiated - LED feedback active");
            g_display->clear();
            for (int i = 0; i < 10; i++) {
                setLedColor(true, true, true);  // White
                g_display->showNumberDec(8888);  // Show "8888" during reset
                delay(100);
                setLedColor(false, false, false);  // Off
                g_display->clear();
                delay(100);
            }
            extern void resetToFactoryDefaults();
            resetToFactoryDefaults();
        } else {
            // Short press -> Enter test mode
            // Turn off LED and clear display
            setLedColor(false, false, false);
            g_display->clear();
            g_displayMode = DISPLAY_MODE_TEST;
            g_testDisplayIndex = 1;
            Serial.println("[INIT] FUNC key short press - TEST MODE ACTIVATED");
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
                extern void updateTestDisplay();
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
                extern void updateDisplayForCurrentMode();
                updateDisplayForCurrentMode();

                Serial.printf("[FUNC] Mode changed to: %d\n", g_displayMode);
            }
        }
    }
}
