/**
 * BLE Server Implementation for BikeClock (Adafruit Bluefruit)
 *
 * Provides:
 * - GATT server for time synchronization
 * - Command processing (SET:time:timestamp)
 * - Response notification
 */

#include "bikeclock.h"

// --- BLE Service and Characteristic ---
// Create service with 128-bit UUID
BLEService bleService(BLE_SERVICE_UUID);

// Create characteristics
BLECharacteristic bleCommandCharacteristic(BLE_CHAR_COMMAND_UUID);
BLECharacteristic bleResponseCharacteristic(BLE_CHAR_RESPONSE_UUID);

// --- Connection State ---
bool g_deviceConnected = false;

// --- Callback Handlers ---

void cccd_callback(uint16_t conn_hdl, BLECharacteristic* chr, uint16_t value) {
    (void)conn_hdl; // Unused parameter
    (void)chr; // Unused parameter
    Serial.printf("[BIKECLOCK] CCCD updated: %u\n", value);
}

void ble_central_connect(uint16_t conn_handle) {
    (void)conn_handle; // Unused parameter
    Serial.println("[BIKECLOCK] Device connected");
    g_deviceConnected = true;
}

void ble_central_disconnect(uint16_t conn_handle, uint8_t reason) {
    (void)conn_handle; // Unused parameter
    (void)reason; // Unused parameter
    Serial.println("[BIKECLOCK] Device disconnected");
    g_deviceConnected = false;
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
        } else {
            Serial.printf("[BIKECLOCK] Unknown command: %s\n", command);
            sendResponse("ERROR: Unknown command");
        }
    }
}

// --- BLE Setup ---
void setupBLE() {
    Serial.println("[BIKECLOCK] Initializing BLE...");

    // Initialize Bluefruit
    Bluefruit.begin();

    // Set device name
    Bluefruit.setName(BLE_DEVICE_NAME);

    // Set the connection interval
    // Minimum: 12ms, Maximum: 24ms, Slave latency: 0, Supervision timeout: 200ms
    Bluefruit.Periph.setConnInterval(12, 24);

    // Set up callbacks
    Bluefruit.Periph.setConnectCallback(ble_central_connect);
    Bluefruit.Periph.setDisconnectCallback(ble_central_disconnect);

    // Configure ALL properties first, before calling begin()
    // Service configuration
    bleService.setPermission(SECMODE_OPEN, SECMODE_OPEN);
    Serial.println("[BIKECLOCK] Service permission set to OPEN/OPEN");

    // Command characteristic configuration
    // Use Read + Write only (no Notify for now)
    bleCommandCharacteristic.setProperties(CHR_PROPS_READ | CHR_PROPS_WRITE);
    bleCommandCharacteristic.setPermission(SECMODE_OPEN, SECMODE_OPEN);
    bleCommandCharacteristic.setFixedLen(32);  // Set FixedLen before begin()
    bleCommandCharacteristic.setWriteCallback(onCommandWritten);
    // No CCCD callback needed without Notify

    // Response characteristic configuration
    bleResponseCharacteristic.setProperties(CHR_PROPS_NOTIFY);
    bleResponseCharacteristic.setPermission(SECMODE_OPEN, SECMODE_OPEN);
    bleResponseCharacteristic.setFixedLen(512);
    bleResponseCharacteristic.setCccdWriteCallback(cccd_callback);

    // NOW call begin() in different order (Response first)
    Serial.println("[BIKECLOCK] Starting BLE Service...");
    err_t service_err = bleService.begin();
    Serial.printf("[BIKECLOCK] BLE Service begin() result: %d\n", service_err);

    // Skip Response Characteristic - using Command Characteristic for bidirectional communication
    Serial.println("[BIKECLOCK] Skipping Response Characteristic - using Command Characteristic for bidirectional communication");

    // Command Characteristic with Read + Write + Notify
    Serial.println("[BIKECLOCK] Starting Command Characteristic...");
    err_t cmd_err = bleCommandCharacteristic.begin();
    Serial.printf("[BIKECLOCK] Command Characteristic begin() result: %d\n", cmd_err);

    // Set initial value for command characteristic (bidirectional)
    uint8_t initValue[] = "Ready for commands";
    bleCommandCharacteristic.write(initValue, strlen((char*)initValue));

    // Set up advertising
    Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
    Bluefruit.Advertising.addTxPower();
    Bluefruit.Advertising.addService(bleService);

    // Set advertising name
    Bluefruit.ScanResponse.addName();

    // Start advertising
    Bluefruit.Advertising.restartOnDisconnect(true);
    Bluefruit.Advertising.start();

    Serial.printf("[BIKECLOCK] BLE initialized. Device name: %s\n", BLE_DEVICE_NAME);
    Serial.printf("[BIKECLOCK] Service UUID: %s\n", BLE_SERVICE_UUID);
    Serial.println("[BIKECLOCK] Advertising started");
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
    }
}

// --- Response Helper ---
void sendResponse(const char* message) {
    // Write to Command Characteristic (bidirectional)
    bleCommandCharacteristic.write((uint8_t*)message, strlen(message));
    Serial.printf("[BIKECLOCK] Response sent: %s\n", message);
}
