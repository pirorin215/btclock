/**
 * BikeClock ESP32-S3 - BLE HID (Phase 6)
 *
 * NimBLE-Arduino で HID-over-GATT サービス(0x1812) を構築し、
 * 物理スイッチ SW1-SW7 の押下をキーボード/コンシューマレポートとして Android に送信する。
 * XIAO 版の BLEHidAdafruit 挙動を NimBLEHIDDevice で再現。
 *
 * サービス構成（NimBLEHIDDevice が既存サーバーに追加）:
 *   Device Info : 0x180a（manufacturer, PnP）
 *   HID         : 0x1812（Report Map, Protocol Mode, Input Reports）
 *   Battery     : 0x180f
 *
 * レポート:
 *   Report ID 1: キーボード（8バイト: modifier + reserved + 6 keys）
 *   Report ID 2: コンシューマ（2バイト: uint16 usage, リトルエンディアン）
 *
 * ※ HID Input Report は暗号化(READ_ENC)要求で生成されるため、
 *    bonding（ペアリング）が必須。ble.ino の setSecurityAuth で有効化。
 */

#include <NimBLEDevice.h>
#include <NimBLEHIDDevice.h>
#include "bikeclock.h"

// ====================================================================
// Report Map（HID Report Descriptor）
// キーボード(Report ID 1) + コンシューマ(Report ID 2)
// ====================================================================
static const uint8_t REPORT_MAP[] = {
    // === Keyboard (Report ID 1, 8 bytes: modifier + reserved + 6 keys) ===
    0x05, 0x01,        // Usage Page (Generic Desktop)
    0x09, 0x06,        // Usage (Keyboard)
    0xA1, 0x01,        // Collection (Application)
    0x85, 0x01,        //   Report ID (1)
    0x05, 0x07,        //   Usage Page (Keyboard/Keypad)
    0x19, 0xE0,        //   Usage Minimum (Left Control)
    0x29, 0xE7,        //   Usage Maximum (Right GUI)
    0x15, 0x00,        //   Logical Minimum (0)
    0x25, 0x01,        //   Logical Maximum (1)
    0x75, 0x01,        //   Report Size (1)
    0x95, 0x08,        //   Report Count (8) — modifier bits
    0x81, 0x02,        //   Input (Data, Var, Abs)
    0x95, 0x01,        //   Report Count (1)
    0x75, 0x08,        //   Report Size (8) — reserved byte
    0x81, 0x01,        //   Input (Const)
    0x95, 0x05,        //   Report Count (5)
    0x75, 0x01,        //   Report Size (1) — LED output
    0x05, 0x08,        //   Usage Page (LEDs)
    0x19, 0x01,        //   Usage Minimum (Num Lock)
    0x29, 0x05,        //   Usage Maximum (Kana)
    0x91, 0x02,        //   Output (Data, Var, Abs)
    0x95, 0x01,        //   Report Count (1)
    0x75, 0x03,        //   Report Size (3) — LED padding
    0x91, 0x01,        //   Output (Const)
    0x95, 0x06,        //   Report Count (6)
    0x75, 0x08,        //   Report Size (8) — key codes
    0x15, 0x00,        //   Logical Minimum (0)
    0x25, 0xE7,        //   Logical Maximum (231)
    0x05, 0x07,        //   Usage Page (Keyboard/Keypad)
    0x19, 0x00,        //   Usage Minimum (0)
    0x29, 0xE7,        //   Usage Maximum (231)
    0x81, 0x00,        //   Input (Data, Array)
    0xC0,              // End Collection

    // === Consumer Control (Report ID 2, 2 bytes: uint16 usage) ===
    0x05, 0x0C,        // Usage Page (Consumer)
    0x09, 0x01,        // Usage (Consumer Control)
    0xA1, 0x01,        // Collection (Application)
    0x85, 0x02,        //   Report ID (2)
    0x15, 0x00,        //   Logical Minimum (0)
    0x26, 0xFF, 0x02,  //   Logical Maximum (0x02FF) — AC Back(0x224) を含む
    0x19, 0x00,        //   Usage Minimum (0)
    0x2A, 0xFF, 0x02,  //   Usage Maximum (0x02FF)
    0x75, 0x10,        //   Report Size (16)
    0x95, 0x01,        //   Report Count (1)
    0x81, 0x00,        //   Input (Data, Array)
    0xC0               // End Collection
};

// ====================================================================
// HID オブジェクト
// ====================================================================
static NimBLEHIDDevice*      g_pHid           = nullptr;
static NimBLECharacteristic* g_pKeyboardInput = nullptr;  // Report ID 1
static NimBLECharacteristic* g_pConsumerInput = nullptr;  // Report ID 2

// Consumer Page 判定（XIAO 版ロジックを流用）
//  - keyCode >= 0xE0            : キーボード修飾域外 → コンシューマ扱い
//  - 0xCD / 0xB5 / 0xB6         : Play-Pause / Vol+ / Vol-
//  - 0x0220 - 0x0230            : AC Back 等（Android ナビゲーション）
static bool isConsumerKey(uint16_t keyCode) {
    return keyCode >= 0xE0
        || keyCode == 0xCD
        || keyCode == 0xB5
        || keyCode == 0xB6
        || (keyCode >= 0x0220 && keyCode <= 0x0230);
}

// ====================================================================
// HID セットアップ
// 既存の NimBLEServer に HID/DeviceInfo/Battery サービスを追加する。
// ====================================================================
void setupHID(NimBLEServer* server) {
    logPrint("HID", "========================================");
    logPrint("HID", "Building HID services (NimBLEHIDDevice)");

    g_pHid = new NimBLEHIDDevice(server);
    g_pHid->setReportMap((uint8_t*)REPORT_MAP, sizeof(REPORT_MAP));
    g_pHid->setManufacturer("pirorin215");
    // PnP: src=USB(0x02), vid/pid/version（OS のデバイス認識用・任意値）
    g_pHid->setPnp(0x02, 0x02E0, 0xB10C, 0x0214);
    // HID Info: country=0(US), flags=0x02(RemotelyWakeable)
    g_pHid->setHidInfo(0x00, 0x02);

    // レポート送信先（Report ID ごとの Input Report Characteristic）
    g_pKeyboardInput = g_pHid->getInputReport(1);
    g_pConsumerInput = g_pHid->getInputReport(2);

    // HID 関連サービスを開始（カスタムGATT とは別サービス。明示 start が確実）
    g_pHid->getDeviceInfoService()->start();
    g_pHid->getHidService()->start();
    g_pHid->getBatteryService()->start();

    logPrint("HID", "✅ HID ready: Keyboard(ID1, 8B) + Consumer(ID2, 2B)");
    logPrint("HID", "========================================");
}

// ====================================================================
// HID キー送信 — sendHidKeyPress / sendHidKeyRelease の本実装
// （Phase 3 のスタブを置換。プロトタイプは bikeclock.h で不変）
// ====================================================================

// 押下: keyCode に応じてキーボード/コンシューマレポートを送信
void sendHidKeyPress(uint16_t keyCode, const char* unused) {
    (void)unused;

    if (g_pKeyboardInput == nullptr || g_pConsumerInput == nullptr) {
        logPrint("HID", "Not initialized (press ignored): 0x%04X", keyCode);
        return;
    }

    if (isConsumerKey(keyCode)) {
        // コンシューマレポート: usage ID を 16-bit リトルエンディアンで送信
        //   0x0224 (AC Back) -> {0x24, 0x02}
        //   0x00CD (Play/Pause) -> {0xCD, 0x00}
        uint8_t report[2] = {
            (uint8_t)(keyCode & 0xFF),
            (uint8_t)((keyCode >> 8) & 0xFF)
        };
        g_pConsumerInput->setValue(report, 2);
        g_pConsumerInput->notify();
        logPrint("HID", "Consumer PRESS: 0x%04X", keyCode);
    } else {
        // キーボードレポート: modifier=0, reserved=0, keyCode を key[0] にセット
        uint8_t report[8] = { 0, 0, (uint8_t)keyCode, 0, 0, 0, 0, 0 };
        g_pKeyboardInput->setValue(report, 8);
        g_pKeyboardInput->notify();
        logPrint("HID", "Keyboard PRESS: 0x%02X", keyCode);
    }
}

// 離開: キーボード/コンシューマ両方のゼロレポートを送信（安全のため両方）
void sendHidKeyRelease(const char* unused) {
    (void)unused;

    if (g_pKeyboardInput == nullptr || g_pConsumerInput == nullptr) {
        return;
    }

    uint8_t zeroKeyboard[8] = { 0 };
    g_pKeyboardInput->setValue(zeroKeyboard, 8);
    g_pKeyboardInput->notify();

    uint8_t zeroConsumer[2] = { 0 };
    g_pConsumerInput->setValue(zeroConsumer, 2);
    g_pConsumerInput->notify();

    logPrint("HID", "Key release");
}
