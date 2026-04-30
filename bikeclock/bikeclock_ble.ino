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
    logPrint("BIKECLOCK", "CCCD updated: %u", value);
}

// HID LED callback disabled for debugging
// void keyboardLedCallback(uint16_t conn_handle, uint8_t led_bitmap) {
//     (void)conn_handle; // Unused parameter
//     logPrint("HID", "Keyboard LED state: 0x%02X", led_bitmap);
// }

void ble_central_connect(uint16_t conn_handle) {
    (void)conn_handle; // Unused parameter
    logPrint("BIKECLOCK", "Device connected");
    g_deviceConnected = true;

    // Update LED state based on time sync status
    updateLedStateBasedOnStatus();
}

void ble_central_disconnect(uint16_t conn_handle, uint8_t reason) {
    (void)conn_handle; // Unused parameter
    (void)reason; // Unused parameter
    logPrint("BIKECLOCK", "Device disconnected");
    g_deviceConnected = false;

    // Update LED state based on time sync status
    updateLedStateBasedOnStatus();
}

void onCommandWritten(uint16_t conn_hdl, BLECharacteristic* chr, uint8_t* data, uint16_t len) {
    (void)conn_hdl; // Unused parameter
    (void)chr; // Unused parameter

    if (len > 0 && len < 513) {
        char command[513];
        memcpy(command, data, len);
        command[len] = '\0';

        logPrint("BIKECLOCK", "Received command: %s", command);

        // Parse command
        if (strncmp(command, "SET:time:", 9) == 0) {
            logPrint("BIKECLOCK", "Calling handleTimeSync...");
            handleTimeSync(command);
            logPrint("BIKECLOCK", "handleTimeSync returned");
        } else if (strncmp(command, "SET:keys:", 9) == 0) {
            logPrint("BIKECLOCK", "Calling handleKeyConfig...");
            handleKeyConfig(command);
        } else if (strncmp(command, "GET:version", 11) == 0) {
            logPrint("BIKECLOCK", "Calling handleGetVersion...");
            handleGetVersion();
            logPrint("BIKECLOCK", "handleGetVersion returned");
        } else if (strncmp(command, "START_OTA", 9) == 0) {
            logPrint("BIKECLOCK", "Calling startOtaDfuMode...");
            startOtaDfuMode();
            logPrint("BIKECLOCK", "startOtaDfuMode returned");
        } else {
            logPrint("BIKECLOCK", "Unknown command: %s", command);
            sendResponse("ERROR: Unknown command");
        }
    }
}

// --- BLE Setup ---
void setupBLE() {
    logPrint("BIKECLOCK", "========================================");
    logPrint("BIKECLOCK", "BLE Initialization (HID + Custom)");
    logPrint("BIKECLOCK", "Firmware Version: %s (%s)", FIRMWARE_VERSION_STR, FIRMWARE_VERSION_DATE);
    logPrint("BIKECLOCK", "BLE Service UUID: " BLE_SERVICE_UUID);
    logPrint("BIKECLOCK", "Command UUID: " BLE_CHAR_COMMAND_UUID);
    logPrint("BIKECLOCK", "Response UUID: " BLE_CHAR_RESPONSE_UUID);
    logPrint("BIKECLOCK", "========================================");

    // Initialize LittleFS
    logPrint("BIKECLOCK", "Initializing InternalFS...");
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
    logPrint("BIKECLOCK", "Initializing HID Service...");
    bledis.setManufacturer("pirorin215");
    bledis.setModel("BikeClock Dual");
    bledis.begin();
    blehid.begin();

    // --- Initialize Custom Service (Time Sync) ---
    logPrint("BIKECLOCK", "Initializing Custom Service...");

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

    logPrint("BIKECLOCK", "========================================");
    logPrint("BIKECLOCK", "✅ BLE Initialization Complete!");
    logPrint("BIKECLOCK", "Device Name: %s", BLE_DEVICE_NAME);
    logPrint("BIKECLOCK", "HID Service: ENABLED");
    logPrint("BIKECLOCK", "Custom Service: ENABLED");
    logPrint("BIKECLOCK", "Advertising started successfully!");
    logPrint("BIKECLOCK", "Waiting for connections...");
    logPrint("BIKECLOCK", "========================================");
}

// --- Time Sync Handler ---
void handleTimeSync(const char* command) {
    logPrint("BIKECLOCK", "Processing time sync command: %s", command);

    // Parse timestamp: SET:time:<timestamp>
    const char* timestampStr = command + strlen("SET:time:");
    uint32_t timestamp = (uint32_t)atol(timestampStr);

    if (timestamp > 0) {
        g_currentTimestamp = timestamp;
        g_timeSynced = true;

        // Update LED state based on connection status
        updateLedStateBasedOnStatus();

        // Invalidate date cache
        g_dateCache.valid = false;

        int hours = getHours();
        int minutes = getMinutes();
        int seconds = getSeconds();

        logPrint("BIKECLOCK", "Time synced successfully: %02d:%02d:%02d",
                     hours, minutes, seconds);

        // Send success response
        sendResponse("OK: Time synced");

        // Update display immediately
        updateTimeDisplay();
    } else {
        logPrint("BIKECLOCK", "Invalid timestamp: %s", timestampStr);
        sendResponse("ERROR: Invalid timestamp format");
        setLedError();
    }
}

// --- Version Handler ---
void handleGetVersion() {
    logPrint("BIKECLOCK", "Processing GET:version command");

    // ファームウェアバージョンを返す（ヘッダーで定義されたバージョンを使用）
    char versionResponse[64];
    snprintf(versionResponse, sizeof(versionResponse), "OK:version:%s", FIRMWARE_VERSION_STR);
    sendResponse(versionResponse);

    logPrint("BIKECLOCK", "Version response sent: %s", FIRMWARE_VERSION_STR);
}

// --- Response Helper ---
void sendResponse(const char* message) {
    // Send notification via Command Characteristic (bidirectional)
    bleCommandCharacteristic.notify((uint8_t*)message, strlen(message));
    logPrint("BIKECLOCK", "Response sent: %s", message);
}

// --- HID Send Functions ---
void sendHidKeyPress(uint16_t keyCode, const char* unused) {
    // キーコードのみをログ出力（キー名はアプリ側で管理）
    logPrint("HID", "Key sent: 0x%04X (%d)", keyCode, keyCode);
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
    logPrint("HID", "Key release");
    Serial.flush();
    
    // 両方のリリースを発行
    blehid.consumerKeyRelease();
    
    uint8_t keycodes[6] = { 0, 0, 0, 0, 0, 0 };
    blehid.keyboardReport(0, keycodes);
}

// --- Settings Management ---
extern HidSwitch hidSwitches[];
// NUM_HID_SWITCHES is defined in bikeclock.ino

void loadSettings() {
    logPrint("BIKECLOCK", "Loading settings from InternalFS...");
    File file(InternalFS);
    
    if (file.open("/keys.dat", FILE_O_READ)) {
        uint16_t savedKeys[NUM_HID_SWITCHES];
        if (file.read((uint8_t*)savedKeys, sizeof(savedKeys)) == sizeof(savedKeys)) {
            for (int i = 0; i < NUM_HID_SWITCHES; i++) {
                hidSwitches[i].keyCode = savedKeys[i];
                logPrint("BIKECLOCK", "  SW%d KeyCode: 0x%04X", i + 1, hidSwitches[i].keyCode);
            }
            logPrint("BIKECLOCK", "Settings loaded successfully.");
        }
        file.close();
    } else {
        logPrint("BIKECLOCK", "No settings file found. Using defaults.");
    }
    Serial.flush();
}

void saveSettings() {
    logPrint("BIKECLOCK", "Saving settings to InternalFS...");
    InternalFS.remove("/keys.dat");
    File file(InternalFS);
    
    if (file.open("/keys.dat", FILE_O_WRITE)) {
        uint16_t keysToSave[NUM_HID_SWITCHES];
        for (int i = 0; i < NUM_HID_SWITCHES; i++) {
            keysToSave[i] = hidSwitches[i].keyCode;
        }
        file.write((uint8_t*)keysToSave, sizeof(keysToSave));
        file.close();
        logPrint("BIKECLOCK", "Settings saved successfully.");
    } else {
        logPrint("BIKECLOCK", "Failed to open settings file for writing.");
    }
    Serial.flush();
}

void handleKeyConfig(const char* command) {
    // Format: SET:keys:HEX1,HEX2,HEX3,HEX4
    // Example: SET:keys:50,4F,52,51
    const char* keysStr = command + 9; // Skip "SET:keys:"
    logPrint("BIKECLOCK", "Full command received: %s", command);
    logPrint("BIKECLOCK", "Parsing key config string: \"%s\"", keysStr);
    
    char temp[64]; // バッファサイズを念のため拡張
    strncpy(temp, keysStr, sizeof(temp));
    temp[sizeof(temp)-1] = '\0';
    
    char* token = strtok(temp, ",");
    int i = 0;
    while (token != NULL && i < NUM_HID_SWITCHES) {
        // 文字列のトリム（空白除去）
        while(isspace(*token)) token++;
        
        hidSwitches[i].keyCode = (uint16_t)strtoul(token, NULL, 16);
        logPrint("BIKECLOCK", "  SW%d (token: \"%s\") -> Parsed KeyCode: 0x%04X", i + 1, token, hidSwitches[i].keyCode);
        token = strtok(NULL, ",");
        i++;
    }
    
    if (i == NUM_HID_SWITCHES) {
        saveSettings();
        sendResponse("OK: keys updated");
        logPrint("BIKECLOCK", "✅ All 4 keys updated and saved.");

        // Visual feedback on 7-segment display
        // Show each key code in sequence (300ms each)
        extern TM1637Display* g_display;
        extern bool g_displayingKeyCodes;

        if (g_display != nullptr) {
            logPrint("BIKECLOCK", "Showing visual feedback on display...");

            // Set flag to skip time display during key code show
            g_displayingKeyCodes = true;

            for (int j = 0; j < NUM_HID_SWITCHES; j++) {
                logPrint("BIKECLOCK", "Displaying SW%d: 0x%04X (%d)", j + 1, hidSwitches[j].keyCode, hidSwitches[j].keyCode);
                g_display->showNumberDec(hidSwitches[j].keyCode);  // Show key code as decimal
                delay(300);  // Display for 300ms
            }

            // Clear flag to resume time display
            g_displayingKeyCodes = false;

            logPrint("BIKECLOCK", "Visual feedback complete.");
        }
    } else {
        logPrint("BIKECLOCK", "❌ Error: Only %d keys parsed. Expected %d.", i, NUM_HID_SWITCHES);
        sendResponse("ERROR: Invalid key format");
    }
    Serial.flush();
}
