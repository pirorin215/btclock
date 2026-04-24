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

// --- HID Switch Functions ---
HidSwitch hidSwitches[] = {
    {SWITCH_SW1_GPIO, HIGH, 0, 0, HID_STATE_IDLE, DEFAULT_SW1_KEYCODE},
    {SWITCH_SW2_GPIO, HIGH, 0, 0, HID_STATE_IDLE, DEFAULT_SW2_KEYCODE},
    {SWITCH_SW3_GPIO, HIGH, 0, 0, HID_STATE_IDLE, DEFAULT_SW3_KEYCODE},
    {SWITCH_SW4_GPIO, HIGH, 0, 0, HID_STATE_IDLE, DEFAULT_SW4_KEYCODE}
};
#define NUM_HID_SWITCHES 4

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
    //unsigned long startTime = millis();
    //while (!Serial && (millis() - startTime < 3000)) {
    //    ; // Wait for serial port to connect (timeout 3 seconds)
    //}

    Serial.println("[BIKECLOCK v1.0.2] " __DATE__ " " __TIME__);

    g_display = new TM1637Display(LED_CLK_GPIO, LED_DIO_GPIO);
    g_display->setBrightness(0x0F);
    g_display->clear();
    g_display->showNumberDec(8888);
    Serial.println("[INIT] Display OK");

    setupBLE();

    for (int i = 0; i < NUM_HID_SWITCHES; i++) {
        pinMode(hidSwitches[i].gpio, INPUT_PULLUP);
        hidSwitches[i].pinState = HIGH;
        hidSwitches[i].lastDebounceTime = 0;
        hidSwitches[i].state = HID_STATE_IDLE;
    }
    Serial.println("[INIT] Switches OK");

    g_lastMillis = millis();
    g_lastHeartbeat = millis();
    Serial.println("[INIT] Waiting for BLE...");
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
    }

    // Heartbeat & time log every 10 seconds
    if (currentMillis - g_lastHeartbeat >= 10000) {
        int hours = getHours();
        int minutes = getMinutes();
        int seconds = getSeconds();
        Serial.printf("[HB] %02d:%02d:%02d | Up:%lus | L:%lu | %c\n",
                     (hours + 9) % 24, minutes, seconds,
                     currentMillis / 1000,
                     g_loopCount,
                     g_timeSynced ? 'S' : '-');
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
                        Serial.printf("[HID] SW%d R\n", i + 1);
                        sendHidKeyRelease(NULL);
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
                        Serial.printf("[HID] SW%d R\n", i + 1);
                        sendHidKeyRelease(NULL);
                        hidSwitches[i].state = HID_STATE_IDLE;
                    }
                    break;
            }
        }
    }
}
