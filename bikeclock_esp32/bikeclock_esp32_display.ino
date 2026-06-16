/**
 * BikeClock ESP32-S3 - TM1637 表示処理
 *
 * XIAO 版 bikeclock_led.ino の表示部分を移植（ロジックは完全非依存）。
 *   - TIME (HH:MM) / DATE (MMDD) / WEEKDAY (MON...) 表示
 *   - バージョン表示
 *   - 7セグメント文字エンコーディング
 */

#include "bikeclock.h"

// 曜日名（0=Sun ... 6=Sat）
static const char* WEEKDAY_NAMES[] = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};

// 7セグメント文字パターン (A-Z)、ビット配置 0bGFEDCBA
const uint8_t SEGMENT_CHARS[] = {
    0x77, // A
    0x7C, // B
    0x39, // C
    0x5E, // D
    0x79, // E
    0x71, // F
    0x3D, // G
    0x74, // H
    0x10, // I
    0x1E, // J
    0x75, // K
    0x38, // L
    0x37, // M
    0x54, // N
    0x5C, // O
    0x73, // P
    0x67, // Q
    0x50, // R
    0x6C, // S
    0x78, // T
    0x1C, // U
    0x3E, // V
    0x2A, // W
    0x76, // X
    0x6E, // Y
    0x1B  // Z
};

// テストパターン（Phase 3 のテストモードで使用）
static const char* TEST_PATTERNS[] = {
    "1234", "4567", " 89", "SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT",
    "ABCD", "EFGH", "IJKL", "MNOP", "QRST", "UVWX", "YZ"
};
const int TEST_PATTERN_COUNT = sizeof(TEST_PATTERNS) / sizeof(TEST_PATTERNS[0]);

// --- モード別表示のディスパッチ ---
void updateDisplayForCurrentMode() {
    switch (g_displayMode) {
        case DISPLAY_MODE_TIME:
            updateTimeDisplay();
            break;
        case DISPLAY_MODE_DATE:
            updateDateDisplay();
            break;
        case DISPLAY_MODE_WEEKDAY:
            updateWeekdayDisplay();
            break;
        case DISPLAY_MODE_TEST:
            // Phase 3（物理スイッチ直接接続 + FUNCキー）で実装
            break;
        default:
            break;
    }
}

// --- 時刻表示 HH:MM ---
void updateTimeDisplay() {
    int hours = getHours();
    int minutes = getMinutes();
    int seconds = getSeconds();

    uint8_t data[] = { 0x00, 0x00, 0x00, 0x00 };
    data[0] = g_display->encodeDigit(hours / 10);
    data[1] = g_display->encodeDigit(hours % 10);
    data[2] = g_display->encodeDigit(minutes / 10);
    data[3] = g_display->encodeDigit(minutes % 10);

    // 偶数秒で2桁目のドット（コロン相当）を点灯
    if (seconds % 2 == 0) {
        data[1] |= 0x80;
    }

    g_display->setSegments(data);
}

// --- 日付表示 MMDD ---
void updateDateDisplay() {
    int month = getMonth();
    int day = getDay();

    uint8_t data[] = { 0x00, 0x00, 0x00, 0x00 };
    data[0] = (month >= 10) ? g_display->encodeDigit(month / 10) : 0x00;
    data[1] = g_display->encodeDigit(month % 10);
    data[2] = (day >= 10) ? g_display->encodeDigit(day / 10) : 0x00;
    data[3] = g_display->encodeDigit(day % 10);

    g_display->setSegments(data);
}

// --- 曜日表示 MON/TUE/... ---
void updateWeekdayDisplay() {
    int weekday = getWeekday();  // 0=Sun ... 6=Sat
    const char* name = WEEKDAY_NAMES[weekday];

    uint8_t data[] = { 0x00, 0x00, 0x00, 0x00 };
    encodeStringToSegments(name, data);
    g_display->setSegments(data);
}

// --- バージョン表示 ---
// 表示形式: [MAJOR].[MINOR][PATCH十の位][PATCH一の位]
// 例: 2.0.1 -> "2.001"
void displayVersion() {
    uint8_t data[] = { 0x00, 0x00, 0x00, 0x00 };
    data[0] = g_display->encodeDigit(FIRMWARE_VERSION_MAJOR) | 0x80;  // メジャー + 小数点
    data[1] = g_display->encodeDigit(FIRMWARE_VERSION_MINOR);
    data[2] = g_display->encodeDigit(FIRMWARE_VERSION_PATCH / 10);
    data[3] = g_display->encodeDigit(FIRMWARE_VERSION_PATCH % 10);
    g_display->setSegments(data);
}

// --- 文字列 → 7セグメントエンコード ---
// str: 入力文字列（例: "SUN", "ABCD"）
// data: 出力配列（4要素）
// 対応文字: A-Z, a-z, 0-9, space（未対応は空白）
void encodeStringToSegments(const char* str, uint8_t* data) {
    for (int i = 0; i < 4; i++) {
        data[i] = 0x00;
    }
    for (int i = 0; i < 4 && str[i] != '\0'; i++) {
        char c = str[i];
        if (c >= 'A' && c <= 'Z') {
            data[i] = SEGMENT_CHARS[c - 'A'];
        } else if (c >= 'a' && c <= 'z') {
            data[i] = SEGMENT_CHARS[c - 'a'];
        } else if (c >= '0' && c <= '9') {
            data[i] = g_display->encodeDigit(c - '0');
        } else {
            data[i] = 0x00;  // space等は空白
        }
    }
}
