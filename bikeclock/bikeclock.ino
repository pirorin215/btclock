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

// External BLE characteristics
extern BLECharacteristic bleSwitchNotifyCharacteristic;

// Time management
unsigned long g_lastDisplayUpdate = 0;
bool g_timeSynced = false;

// Debug logging
unsigned long g_lastHeartbeat = 0;
unsigned long g_loopCount = 0;

// Switch state tracking
enum SwitchState {
    SWITCH_STATE_IDLE,
    SWITCH_STATE_PRESS,
    SWITCH_STATE_REPEAT
};

struct Switch {
    uint8_t gpio;
    uint8_t pinState;
    unsigned long lastDebounceTime;
    unsigned long pressStartTime;
    SwitchState state;
    uint8_t switchNum;
};

Switch switches[] = {
    {SWITCH_SW1_GPIO, HIGH, 0, 0, SWITCH_STATE_IDLE, 1},
    {SWITCH_SW2_GPIO, HIGH, 0, 0, SWITCH_STATE_IDLE, 2},
    {SWITCH_SW3_GPIO, HIGH, 0, 0, SWITCH_STATE_IDLE, 3},
    {SWITCH_SW4_GPIO, HIGH, 0, 0, SWITCH_STATE_IDLE, 4}
};
#define NUM_SWITCHES 4

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

    // Wait for serial console to connect
    // This allows us to see the initialization sequence
    unsigned long startTime = millis();
    while (!Serial && (millis() - startTime < 3000)) {
        ; // Wait for serial port to connect (timeout 3 seconds)
    }

    // Additional delay to ensure serial is ready
    delay(500);

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

    // Initialize switches with internal pull-up
    Serial.println("[BIKECLOCK] Initializing switches...");
    for (int i = 0; i < NUM_SWITCHES; i++) {
        pinMode(switches[i].gpio, INPUT_PULLUP);
        switches[i].pinState = HIGH;
        switches[i].lastDebounceTime = 0;
        switches[i].state = SWITCH_STATE_IDLE;
        Serial.printf("[BIKECLOCK]   SW%d: GPIO=%d\n",
                     i + 1, switches[i].gpio);
    }

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

    // Process HID switches
    processHidSwitches();

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
        Serial.printf("[HEARTBEAT v1.0.2] Uptime: %lu sec | Loops: %lu | TimeSynced: %s | Timestamp: %lu\n",
                     currentMillis / 1000,
                     g_loopCount,
                     g_timeSynced ? "YES" : "NO",
                     g_currentTimestamp);
        Serial.flush();
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

// --- Switch Functions ---
void processSwitches() {
    for (int i = 0; i < NUM_SWITCHES; i++) {
        uint8_t reading = digitalRead(switches[i].gpio);

        // Check if switch state changed (due to noise or pressing)
        if (reading != switches[i].pinState) {
            switches[i].lastDebounceTime = millis();
            switches[i].pinState = reading;
        }

        // Check if debounce delay passed
        if ((millis() - switches[i].lastDebounceTime) > SWITCH_DEBOUNCE_DELAY_MS) {
            // State machine
            switch (switches[i].state) {
                case SWITCH_STATE_IDLE:
                    // Check if switch is pressed (LOW)
                    if (reading == LOW) {
                        Serial.printf("[SWITCH] SW%d: PRESS\n", switches[i].switchNum);
                        sendSwitchNotification(switches[i].switchNum, "PRESS");
                        switches[i].state = SWITCH_STATE_PRESS;
                        switches[i].pressStartTime = millis();
                    }
                    break;

                case SWITCH_STATE_PRESS:
                    // Check for repeat (long press)
                    if (reading == LOW) {
                        if ((millis() - switches[i].pressStartTime) > SWITCH_REPEAT_DELAY_MS) {
                            Serial.printf("[SWITCH] SW%d: REPEAT (start)\n", switches[i].switchNum);
                            sendSwitchNotification(switches[i].switchNum, "REPEAT");
                            switches[i].state = SWITCH_STATE_REPEAT;
                            switches[i].pressStartTime = millis();  // Reset for repeat interval
                        }
                    } else {
                        // Switch released
                        Serial.printf("[SWITCH] SW%d: RELEASE\n", switches[i].switchNum);
                        sendSwitchNotification(switches[i].switchNum, "RELEASE");
                        switches[i].state = SWITCH_STATE_IDLE;
                    }
                    break;

                case SWITCH_STATE_REPEAT:
                    if (reading == LOW) {
                        // Continue repeating
                        if ((millis() - switches[i].pressStartTime) > SWITCH_REPEAT_INTERVAL_MS) {
                            Serial.printf("[SWITCH] SW%d: REPEAT\n", switches[i].switchNum);
                            sendSwitchNotification(switches[i].switchNum, "REPEAT");
                            switches[i].pressStartTime = millis();
                        }
                    } else {
                        // Switch released
                        Serial.printf("[SWITCH] SW%d: RELEASE\n", switches[i].switchNum);
                        sendSwitchNotification(switches[i].switchNum, "RELEASE");
                        switches[i].state = SWITCH_STATE_IDLE;
                    }
                    break;
            }
        }
    }
}

void sendSwitchNotification(uint8_t switchNum, const char* action) {
    // Format: "SWITCH:n:ACTION"
    // Example: "SWITCH:1:PRESS"
    char message[32];
    snprintf(message, sizeof(message), "SWITCH:%d:%s", switchNum, action);
    bleSwitchNotifyCharacteristic.notify((uint8_t*)message, strlen(message));
    Serial.printf("[SWITCH] Notification sent: %s\n", message);
}

// --- HID Switch Functions ---

HidSwitch hidSwitches[] = {
    {SWITCH_SW1_GPIO, HIGH, 0, 0, HID_STATE_IDLE, DEFAULT_SW1_KEYCODE},
    {SWITCH_SW2_GPIO, HIGH, 0, 0, HID_STATE_IDLE, DEFAULT_SW2_KEYCODE},
    {SWITCH_SW3_GPIO, HIGH, 0, 0, HID_STATE_IDLE, DEFAULT_SW3_KEYCODE},
    {SWITCH_SW4_GPIO, HIGH, 0, 0, HID_STATE_IDLE, DEFAULT_SW4_KEYCODE}
};
#define NUM_HID_SWITCHES 4

void processHidSwitches() {
    for (int i = 0; i < NUM_HID_SWITCHES; i++) {
        uint8_t reading = digitalRead(hidSwitches[i].gpio);

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
                        Serial.printf("[HID] SW%d: PRESS\n", i + 1);
                        Serial.flush();
                        sendHidKeyPress(hidSwitches[i].keyCode, NULL);
                        
                        hidSwitches[i].state = HID_STATE_PRESS;
                        hidSwitches[i].pressStartTime = millis();
                    }
                    break;

                case HID_STATE_PRESS:
                    if (reading == LOW) {
                        // Check for repeat (long press)
                        if ((millis() - hidSwitches[i].pressStartTime) > HID_REPEAT_DELAY_MS) {
                            Serial.printf("[HID] SW%d: REPEAT (start)\n", i + 1);
                            Serial.flush();
                            
                            // OS側のオートリピートに頼らず、マイコン側で一旦離してまた押す
                            sendHidKeyRelease(NULL);
                            delay(10);
                            sendHidKeyPress(hidSwitches[i].keyCode, NULL);
                            
                            hidSwitches[i].state = HID_STATE_REPEAT;
                            hidSwitches[i].pressStartTime = millis();
                        }
                    } else {
                        // Switch released
                        Serial.printf("[HID] SW%d: RELEASE\n", i + 1);
                        Serial.flush();
                        sendHidKeyRelease(NULL);
                        hidSwitches[i].state = HID_STATE_IDLE;
                    }
                    break;

                case HID_STATE_REPEAT:
                    if (reading == LOW) {
                        // Continue repeating
                        if ((millis() - hidSwitches[i].pressStartTime) > HID_REPEAT_INTERVAL_MS) {
                            Serial.printf("[HID] SW%d: REPEAT\n", i + 1);
                            Serial.flush();
                            
                            // 一旦離してまた押すことで、OS側に新しいキー入力として認識させる
                            sendHidKeyRelease(NULL);
                            delay(10);
                            sendHidKeyPress(hidSwitches[i].keyCode, NULL);
                            
                            hidSwitches[i].pressStartTime = millis();
                        }
                    } else {
                        // Switch released
                        Serial.printf("[HID] SW%d: RELEASE\n", i + 1);
                        Serial.flush();
                        sendHidKeyRelease(NULL);
                        hidSwitches[i].state = HID_STATE_IDLE;
                    }
                    break;
            }
        }
    }
}
