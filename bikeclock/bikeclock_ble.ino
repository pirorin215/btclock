/**
 * BLE Server Implementation for BikeClock (Adafruit Bluefruit)
 *
 * Provides:
 * - HID profile for keyboard/media key input
 * - GATT server for time synchronization
 * - Command processing (SET:time:timestamp)
 * - Response notification
 */

#include "bikeclock.h"

// --- BLE Custom Service ---
// Create service with 128-bit UUID
BLEService bleService(BLE_SERVICE_UUID);

// Create characteristics
BLECharacteristic bleCommandCharacteristic(BLE_CHAR_COMMAND_UUID);
BLECharacteristic bleResponseCharacteristic(BLE_CHAR_RESPONSE_UUID);
BLECharacteristic bleSwitchNotifyCharacteristic(BLE_CHAR_SWITCH_NOTIFY_UUID);

// HID enabled
BLEHidAdafruit blehid;
BLEDis bledis;

// --- Connection State ---
bool g_deviceConnected = false;

// --- Callback Handlers ---

void cccd_callback(uint16_t conn_hdl, BLECharacteristic* chr, uint16_t value) {
    (void)conn_hdl; // Unused parameter
    (void)chr; // Unused parameter
    Serial.printf("[BIKECLOCK] CCCD updated: %u\n", value);
}

// HID LED callback disabled for debugging
// void keyboardLedCallback(uint16_t conn_handle, uint8_t led_bitmap) {
//     (void)conn_handle; // Unused parameter
//     Serial.printf("[HID] Keyboard LED state: 0x%02X\n", led_bitmap);
// }

void ble_central_connect(uint16_t conn_handle) {
    (void)conn_handle; // Unused parameter
    Serial.println("[BIKECLOCK] Device connected");
    g_deviceConnected = true;

    // Update LED state based on time sync status
    if (g_timeSynced) {
        setLedState(LED_STATE_CONNECTED_SYNCED);
    } else {
        setLedState(LED_STATE_CONNECTED_NO_SYNC);
    }
}

void ble_central_disconnect(uint16_t conn_handle, uint8_t reason) {
    (void)conn_handle; // Unused parameter
    (void)reason; // Unused parameter
    Serial.println("[BIKECLOCK] Device disconnected");
    g_deviceConnected = false;

    // Update LED state based on time sync status
    if (g_timeSynced) {
        setLedState(LED_STATE_SYNCED);
    } else {
        setLedState(LED_STATE_NO_SYNC);
    }
}

void onCommandWritten(uint16_t conn_hdl, BLECharacteristic* chr, uint8_t* data, uint16_t len) {
    (void)conn_hdl; // Unused parameter
    (void)chr; // Unused parameter

    if (len > 0 && len < 513) {
        char command[513];
        memcpy(command, data, len);
        command[len] = '\0';

        Serial.printf("[BIKECLOCK] Received command: %s\n", command);

        // Parse command
        if (strncmp(command, "SET:time:", 9) == 0) {
            Serial.println("[BIKECLOCK] Calling handleTimeSync...");
            handleTimeSync(command);
            Serial.println("[BIKECLOCK] handleTimeSync returned");
        } else if (strncmp(command, "SET:keys:", 9) == 0) {
            Serial.println("[BIKECLOCK] Calling handleKeyConfig...");
            handleKeyConfig(command);
        } else {
            Serial.printf("[BIKECLOCK] Unknown command: %s\n", command);
            sendResponse("ERROR: Unknown command");
        }
    }
}

// --- BLE Setup ---
void setupBLE() {
    Serial.println("[BIKECLOCK] ========================================");
    Serial.println("[BIKECLOCK] BLE Initialization (HID + Custom)");
    Serial.println("[BIKECLOCK] Firmware Version: 1.0.2 (2026-04-24)");
    Serial.println("[BIKECLOCK] BLE Service UUID: " BLE_SERVICE_UUID);
    Serial.println("[BIKECLOCK] Command UUID: " BLE_CHAR_COMMAND_UUID);
    Serial.println("[BIKECLOCK] Response UUID: " BLE_CHAR_RESPONSE_UUID);
    Serial.println("[BIKECLOCK] ========================================");

    // Initialize LittleFS
    Serial.println("[BIKECLOCK] Initializing InternalFS...");
    InternalFS.begin();

    // Initialize Bluefruit with max connections
    // prph_count=2 to allow both HID and GATT connections if needed
    Bluefruit.begin(2, 0);

    // Load saved settings (keycodes) from InternalFS
    loadSettings();

    // Set device name
    Bluefruit.setName(BLE_DEVICE_NAME);

    // Set the connection interval
    // Minimum: 12ms, Maximum: 24ms, Slave latency: 0, Supervision timeout: 200ms
    Bluefruit.Periph.setConnInterval(12, 24);

    // Set up callbacks
    Bluefruit.Periph.setConnectCallback(ble_central_connect);
    Bluefruit.Periph.setDisconnectCallback(ble_central_disconnect);

    // --- Initialize HID Service ---
    Serial.println("[BIKECLOCK] Initializing HID Service...");
    bledis.setManufacturer("pirorin215");
    bledis.setModel("BikeClock Dual");
    bledis.begin();
    blehid.begin();

    // --- Initialize Custom Service (Time Sync) ---
    Serial.println("[BIKECLOCK] Initializing Custom Service...");

    // Configure ALL properties first, before calling begin()
    // Service configuration
    bleService.setPermission(SECMODE_OPEN, SECMODE_OPEN);

    // Command characteristic configuration (BIDIRECTIONAL: READ | WRITE | NOTIFY)
    bleCommandCharacteristic.setProperties(CHR_PROPS_READ | CHR_PROPS_WRITE | CHR_PROPS_NOTIFY);
    bleCommandCharacteristic.setPermission(SECMODE_OPEN, SECMODE_OPEN);
    bleCommandCharacteristic.setFixedLen(32);
    bleCommandCharacteristic.setWriteCallback(onCommandWritten);
    bleCommandCharacteristic.setCccdWriteCallback(cccd_callback);

    // Switch notification characteristic configuration
    bleSwitchNotifyCharacteristic.setProperties(CHR_PROPS_NOTIFY);
    bleSwitchNotifyCharacteristic.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
    bleSwitchNotifyCharacteristic.setFixedLen(32);

    // NOW call begin() for service and single characteristic
    bleService.begin();
    bleCommandCharacteristic.begin();
    bleSwitchNotifyCharacteristic.begin();

    Serial.flush();
    delay(100);

    // --- Set up advertising ---
    // Advertising packet: Prioritize HID and Custom Service for discovery
    Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
    Bluefruit.Advertising.addAppearance(BLE_APPEARANCE_HID_KEYBOARD);
    
    // Add both HID and Custom Service to main advertising packet
    // Total bytes: 3 (flags) + 4 (appearance) + 4 (HID 16-bit) + 18 (Custom 128-bit) = 29 bytes
    Bluefruit.Advertising.addService(blehid);
    Bluefruit.Advertising.addService(bleService);

    // Scan Response packet: Name and TX Power
    Bluefruit.ScanResponse.addName();
    Bluefruit.ScanResponse.addTxPower();

    // Start advertising
    Bluefruit.Advertising.restartOnDisconnect(true);
    Bluefruit.Advertising.setInterval(32, 244);    // 20ms to 152ms
    Bluefruit.Advertising.setFastTimeout(30);      // 30 seconds
    Bluefruit.Advertising.start(0);                 // 0 = Don't stop advertising

    Serial.println("[BIKECLOCK] ========================================");
    Serial.println("[BIKECLOCK] ✅ BLE Initialization Complete!");
    Serial.printf("[BIKECLOCK] Device Name: %s\n", BLE_DEVICE_NAME);
    Serial.println("[BIKECLOCK] HID Service: ENABLED");
    Serial.println("[BIKECLOCK] Custom Service: ENABLED");
    Serial.println("[BIKECLOCK] Advertising started successfully!");
    Serial.println("[BIKECLOCK] Waiting for connections...");
    Serial.println("[BIKECLOCK] ========================================");
}

// --- Time Sync Handler ---
void handleTimeSync(const char* command) {
    Serial.printf("[BIKECLOCK] Processing time sync command: %s\n", command);

    // Parse timestamp: SET:time:<timestamp>
    const char* timestampStr = command + strlen("SET:time:");
    uint32_t timestamp = (uint32_t)atol(timestampStr);

    if (timestamp > 0) {
        g_currentTimestamp = timestamp;
        g_timeSynced = true;

        // Update LED state based on connection status
        if (g_deviceConnected) {
            setLedState(LED_STATE_CONNECTED_SYNCED);
        } else {
            setLedState(LED_STATE_SYNCED);
        }

        int hours = getHours();
        int minutes = getMinutes();
        int seconds = getSeconds();

        Serial.printf("[BIKECLOCK] Time synced successfully: %02d:%02d:%02d\n",
                     hours, minutes, seconds);

        // Send success response
        sendResponse("OK: Time synced");

        // Update display immediately
        updateTimeDisplay();
    } else {
        Serial.printf("[BIKECLOCK] Invalid timestamp: %s\n", timestampStr);
        sendResponse("ERROR: Invalid timestamp format");
        setLedError();
    }
}

// --- Response Helper ---
void sendResponse(const char* message) {
    // Send notification via Command Characteristic (bidirectional)
    bleCommandCharacteristic.notify((uint8_t*)message, strlen(message));
    Serial.printf("[BIKECLOCK] Response sent: %s\n", message);
}

// --- HID Send Functions ---
void sendHidKeyPress(uint16_t keyCode, const char* unused) {
    // キーコードのみをログ出力（キー名はアプリ側で管理）
    Serial.printf("[HID] Key sent: 0x%04X (%d)\n", keyCode, keyCode);
    Serial.flush();

    // Keyboard Page: 0x00-0xFF
    // Consumer Page: 0x0C00-0x0CFF (or 0x0000-0xFFFF with high byte set)
    // 0xE2 (226) and similar codes > 0x7F are typically Consumer Page
    if (keyCode >= 0xE0 || keyCode == 0xCD || keyCode == 0xB5 || keyCode == 0xB6 || (keyCode >= 0x0220 && keyCode <= 0x0230)) {
        // Consumer Page (0x0C) - Media keys, AC Back, etc.
        blehid.consumerKeyPress(keyCode);
    } else {
        // Keyboard Page (0x01) - Standard keys, Arrows, ESC, etc.
        uint8_t keycodes[6] = { (uint8_t)keyCode, 0, 0, 0, 0, 0 };
        blehid.keyboardReport(0, keycodes);
    }
}

void sendHidKeyRelease(const char* unused) {
    Serial.println("[HID] Key release");
    Serial.flush();
    
    // 両方のリリースを発行
    blehid.consumerKeyRelease();
    
    uint8_t keycodes[6] = { 0, 0, 0, 0, 0, 0 };
    blehid.keyboardReport(0, keycodes);
}

// --- Settings Management ---
extern HidSwitch hidSwitches[];
#define NUM_HID_SWITCHES 4

void loadSettings() {
    Serial.println("[BIKECLOCK] Loading settings from InternalFS...");
    File file(InternalFS);
    
    if (file.open("/keys.dat", FILE_O_READ)) {
        uint16_t savedKeys[NUM_HID_SWITCHES];
        if (file.read((uint8_t*)savedKeys, sizeof(savedKeys)) == sizeof(savedKeys)) {
            for (int i = 0; i < NUM_HID_SWITCHES; i++) {
                hidSwitches[i].keyCode = savedKeys[i];
                Serial.printf("[BIKECLOCK]   SW%d KeyCode: 0x%04X\n", i + 1, hidSwitches[i].keyCode);
            }
            Serial.println("[BIKECLOCK] Settings loaded successfully.");
        }
        file.close();
    } else {
        Serial.println("[BIKECLOCK] No settings file found. Using defaults.");
    }
    Serial.flush();
}

void saveSettings() {
    Serial.println("[BIKECLOCK] Saving settings to InternalFS...");
    InternalFS.remove("/keys.dat");
    File file(InternalFS);
    
    if (file.open("/keys.dat", FILE_O_WRITE)) {
        uint16_t keysToSave[NUM_HID_SWITCHES];
        for (int i = 0; i < NUM_HID_SWITCHES; i++) {
            keysToSave[i] = hidSwitches[i].keyCode;
        }
        file.write((uint8_t*)keysToSave, sizeof(keysToSave));
        file.close();
        Serial.println("[BIKECLOCK] Settings saved successfully.");
    } else {
        Serial.println("[BIKECLOCK] Failed to open settings file for writing.");
    }
    Serial.flush();
}

void handleKeyConfig(const char* command) {
    // Format: SET:keys:HEX1,HEX2,HEX3,HEX4
    // Example: SET:keys:50,4F,52,51
    const char* keysStr = command + 9; // Skip "SET:keys:"
    Serial.printf("[BIKECLOCK] Full command received: %s\n", command);
    Serial.printf("[BIKECLOCK] Parsing key config string: \"%s\"\n", keysStr);
    
    char temp[64]; // バッファサイズを念のため拡張
    strncpy(temp, keysStr, sizeof(temp));
    temp[sizeof(temp)-1] = '\0';
    
    char* token = strtok(temp, ",");
    int i = 0;
    while (token != NULL && i < NUM_HID_SWITCHES) {
        // 文字列のトリム（空白除去）
        while(isspace(*token)) token++;
        
        hidSwitches[i].keyCode = (uint16_t)strtoul(token, NULL, 16);
        Serial.printf("[BIKECLOCK]   SW%d (token: \"%s\") -> Parsed KeyCode: 0x%04X\n", i + 1, token, hidSwitches[i].keyCode);
        token = strtok(NULL, ",");
        i++;
    }
    
    if (i == NUM_HID_SWITCHES) {
        saveSettings();
        sendResponse("OK: keys updated");
        Serial.println("[BIKECLOCK] ✅ All 4 keys updated and saved.");
    } else {
        Serial.printf("[BIKECLOCK] ❌ Error: Only %d keys parsed. Expected %d.\n", i, NUM_HID_SWITCHES);
        sendResponse("ERROR: Invalid key format");
    }
    Serial.flush();
}
