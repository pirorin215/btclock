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
    Serial.println("[BIKECLOCK] ========================================");
    Serial.println("[BIKECLOCK] BLE Initialization");
    Serial.println("[BIKECLOCK] Firmware Version: 1.0.1 (2026-04-23)");
    Serial.println("[BIKECLOCK] BLE Service UUID: " BLE_SERVICE_UUID);
    Serial.println("[BIKECLOCK] Command UUID: " BLE_CHAR_COMMAND_UUID);
    Serial.println("[BIKECLOCK] Response UUID: " BLE_CHAR_RESPONSE_UUID);
    Serial.println("[BIKECLOCK] ========================================");

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

    // Command characteristic configuration (BIDIRECTIONAL: READ | WRITE | NOTIFY)
    // This single characteristic handles both command reception and response notification
    bleCommandCharacteristic.setProperties(CHR_PROPS_READ | CHR_PROPS_WRITE | CHR_PROPS_NOTIFY);
    bleCommandCharacteristic.setPermission(SECMODE_OPEN, SECMODE_OPEN);
    bleCommandCharacteristic.setFixedLen(32);
    bleCommandCharacteristic.setWriteCallback(onCommandWritten);
    bleCommandCharacteristic.setCccdWriteCallback(cccd_callback);

    // Note: Response characteristic is not needed anymore
    // We use the command characteristic for bidirectional communication

    // NOW call begin() for service and single characteristic
    Serial.println("[BIKECLOCK] Starting BLE Service...");
    err_t service_err = bleService.begin();
    Serial.printf("[BIKECLOCK] BLE Service begin() result: %d\n", service_err);

    // Single Bidirectional Characteristic (READ | WRITE | NOTIFY)
    Serial.println("[BIKECLOCK] Starting Command Characteristic (READ | WRITE | NOTIFY)...");
    err_t cmd_err = bleCommandCharacteristic.begin();
    Serial.printf("[BIKECLOCK] Command Characteristic begin() result: %d\n", cmd_err);
    if (cmd_err == 0) {
        Serial.println("[BIKECLOCK] ✅ Command Characteristic initialized successfully!");
        Serial.println("[BIKECLOCK] UUID: " BLE_CHAR_COMMAND_UUID);
    } else {
        Serial.println("[BIKECLOCK] ❌ Command Characteristic initialization FAILED!");
    }
    Serial.flush();
    delay(100);

    // Set up advertising
    Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
    Bluefruit.Advertising.addTxPower();
    Bluefruit.Advertising.addService(bleService);

    // Set advertising name
    Bluefruit.ScanResponse.addName();

    // Start advertising
    Bluefruit.Advertising.restartOnDisconnect(true);
    Bluefruit.Advertising.start();

    Serial.println("[BIKECLOCK] ========================================");
    Serial.println("[BIKECLOCK] ✅ BLE Initialization Complete!");
    Serial.printf("[BIKECLOCK] Device Name: %s\n", BLE_DEVICE_NAME);
    Serial.println("[BIKECLOCK] Advertising started successfully!");
    Serial.println("[BIKECLOCK] Waiting for smartphone connection...");
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
    // Send notification via Command Characteristic (bidirectional)
    bleCommandCharacteristic.notify((uint8_t*)message, strlen(message));
    Serial.printf("[BIKECLOCK] Response sent: %s\n", message);
}
