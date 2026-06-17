/**
 * BikeClock ESP32-S3 - ePaper 表示処理（昼間視認性用）
 *
 * TM1637 7セグLEDは昼間の太陽光下で視認性が悪いため、
 * WeAct 2.13" ePaper(BW, SSD1680) を追加して併用する。
 *
 * 表示レイアウト（250x122 横長, rotation=1）:
 *
 *        ┌────────┬─────────────────────┐
 *        │   月   │      １ ２ ： ３ ４  │  時刻(logisoso62, 右寄せ)
 *        │ (曜日) │═════════════════════│  ← 横線
 *        │   15   │      ０ ０ ： １ ５  │  乗車時間(logisoso32, 右寄せ)
 *        │ (日付) │                     │
 *        └────────┴─────────────────────┘
 *          ↑縦線
 *        左欄: 曜日(上)・日付(下)を縦に表示
 *
 *   - 時刻        : 現在時刻 HH:MM（logisoso62_tn 62px, 右寄せ）
 *   - 曜日        : 漢字32px（左欄上半分）。GNU Unifont 16x16ビットマップを2倍拡大
 *   - 日付        : getDay() 数字32px（左欄下半分）
 *   - 乗車時間    : 電源ONからの経過(millisベース) HH:MM（logisoso32_tn 32px, 右寄せ）
 *                   ※時刻同期で時刻がジャンプしても継続＝実際の乗車時間
 *   - 区切り線    : 縦線(左欄/右欄)、横線(時刻/乗車時間)
 *
 * 更新戦略:
 *   - 分が変化 / 日が変化 / 初回同期 → 全画面フル更新（文字のにじみや薄化を防ぎコントラストを最大化）
 *   - 未同期 → 「時刻未同期」固定表示（乗車時間も非表示）
 *
 * ハードウェア:
 *   - 専用 SPI3_HOST バス（SPIClass epdSPI(HSPI)）を使用。
 *   - CS=1, DC=2, RST=3, BUSY=10, SCK=12, MOSI=11
 *
 * 注意: ePaper更新はブロッキング（部分~0.3s / フル~3s）だが、NimBLEは別FreeRTOS
 * タスクで稼働するためBLE時刻同期の応答性は影響しない。
 */

#include "bikeclock.h"
#include <SPI.h>
#include <GxEPD2_BW.h>
#include <U8g2_for_Adafruit_GFX.h>

// === ePaper オブジェクト（専用 SPI3_HOST バス） ===
static SPIClass epdSPI(HSPI);   // ESP32-S3: HSPI == SPI3_HOST（グローバルSPI=FSPI=SPI2と独立）
static GxEPD2_BW<GxEPD2_213_B74, GxEPD2_213_B74::HEIGHT> g_epaper(
    GxEPD2_213_B74(EPD_CS_GPIO, EPD_DC_GPIO, EPD_RST_GPIO, EPD_BUSY_GPIO));
static U8G2_FOR_ADAFRUIT_GFX u8g2Fonts;

// === 表示状態（前回描画内容のキャッシュで無駄な更新を省く） ===
static int8_t     ep_lastHr  = -1;
static int8_t     ep_lastMin = -1;
static int8_t     ep_lastDay = -1;
static EpaperView ep_lastView = EP_VIEW_NONE;   // 前回描画ビュー（ビュー切替検出・初回描画用）

// === 画面ジオメトリ（rotation=1 で 250x122 横長） ===
static const int16_t EP_W = 250;
static const int16_t EP_H = 122;
static const int16_t DIVIDER_X    = 63;   // 縦線のx（左欄幅=63px）
static const int16_t DIVIDER_Y    = 75;   // 横線のy（時刻/乗車時間の境界）
static const int16_t LINE_W       = 3;    // 区切り線の太さ
static const int16_t LEFT_MARGIN  = 4;    // 左欄文字の左端マージン
static const int16_t RIGHT_MARGIN = 8;    // 右欄の右端マージン
static const int16_t TIME_BLY     = 62;   // 時刻(logisoso62)のベースライン（上段中央）
static const int16_t RIDE_BLY     = 119;  // 乗車時間(logisoso32)のベースライン（下段中央）
static const int16_t ICON_CX      = 136;  // 経過時間アイコン(時計)の中心x
static const int16_t ICON_CY      = 104;  // 経過時間アイコン(時計)の中心y
static const int16_t ICON_R       = 12;   // 経過時間アイコン(時計)の半径

// 曜日（0=日 ... 6=土）
static const char* WEEKDAY_JP[] = {"日", "月", "火", "水", "木", "金", "土"};

// ====================================================================
// 曜日漢字 16x16 ビットマップ（GNU Unifont, MSB=左端）
//   U8g2の大きいフォントは漢字非収録(最大16px)のため、16x16ビットマップを
//   2倍拡大(32px)して描画する。現状の unifont_t_japanese3 と同じ字形。
// ====================================================================
static const uint16_t KANJI_WEEKDAY[7][16] = {
  /* 日 */ {
    0x0000, 0x1FF0, 0x1010, 0x1010, 0x1010, 0x1010, 0x1010, 0x1FF0,
    0x1010, 0x1010, 0x1010, 0x1010, 0x1010, 0x1010, 0x1FF0, 0x1010,
  },
  /* 月 */ {
    0x0000, 0x0FF8, 0x0808, 0x0808, 0x0808, 0x0FF8, 0x0808, 0x0808,
    0x0808, 0x0FF8, 0x0808, 0x0808, 0x1008, 0x1008, 0x2028, 0x4010,
  },
  /* 火 */ {
    0x0100, 0x0100, 0x0100, 0x1108, 0x1108, 0x1110, 0x2120, 0x2100,
    0x4280, 0x0280, 0x0440, 0x0440, 0x0820, 0x1010, 0x2008, 0xC006,
  },
  /* 水 */ {
    0x0100, 0x0100, 0x0100, 0x0108, 0x0108, 0x7D90, 0x05A0, 0x0940,
    0x0940, 0x1120, 0x1110, 0x2108, 0x4106, 0x8100, 0x0500, 0x0200,
  },
  /* 木 */ {
    0x0100, 0x0100, 0x0100, 0x0100, 0x7FFC, 0x0380, 0x0540, 0x0540,
    0x0920, 0x1110, 0x2108, 0x4104, 0x8102, 0x0100, 0x0100, 0x0100,
  },
  /* 金 */ {
    0x0100, 0x0100, 0x0280, 0x0440, 0x0820, 0x1010, 0x2FE8, 0xC106,
    0x0100, 0x3FF8, 0x0100, 0x1110, 0x0910, 0x0920, 0xFFFE, 0x0000,
  },
  /* 土 */ {
    0x0100, 0x0100, 0x0100, 0x0100, 0x0100, 0x0100, 0x3FF8, 0x0100,
    0x0100, 0x0100, 0x0100, 0x0100, 0x0100, 0x0100, 0xFFFE, 0x0000,
  },
};

// ====================================================================
// 乗車時間（電源ONからの経過、millisベース）
// ====================================================================
// 時刻同期(BLE)で g_currentTimestamp がジャンプしても影響しない＝実際の乗車時間。
static void getRideTime(int* hours, int* minutes) {
    unsigned long elapsedMs = millis() - g_startupMillis;
    unsigned long totalMin = elapsedMs / 60000UL;
    *hours   = (int)(totalMin / 60UL);
    *minutes = (int)(totalMin % 60UL);
}

// ====================================================================
// HIDキーコード → 人間可読名（詳細表示用）
// ====================================================================
static const struct { uint16_t code; const char* name; } KEY_NAME_TABLE[] = {
    // 矢印・編集キー
    {0x4F, "Right"}, {0x50, "Left"}, {0x51, "Down"}, {0x52, "Up"},
    {0x28, "Enter"}, {0x29, "Esc"}, {0x2A, "BS"}, {0x2B, "Tab"},
    {0x2C, "Space"}, {0x4C, "Del"},
    // Android / Consumer Page
    {0x0224, "Back"}, {0xCD, "Play/Pause"}, {0xB5, "Next"}, {0xB6, "Prev"},
    {0xE2, "Mute"}, {0xE9, "VolUp"}, {0xEA, "VolDn"},
    // 数字
    {0x1E, "1"}, {0x1F, "2"}, {0x20, "3"}, {0x21, "4"}, {0x22, "5"},
    {0x23, "6"}, {0x24, "7"}, {0x25, "8"}, {0x26, "9"}, {0x27, "0"},
};

// キーコードから名称を返す。未知は "0xXXXX" 表記。
static const char* keyNameFromCode(uint16_t code) {
    for (size_t i = 0; i < sizeof(KEY_NAME_TABLE) / sizeof(KEY_NAME_TABLE[0]); i++) {
        if (KEY_NAME_TABLE[i].code == code) return KEY_NAME_TABLE[i].name;
    }
    static char hexbuf[8];
    snprintf(hexbuf, sizeof(hexbuf), "0x%04X", code);
    return hexbuf;
}

// ====================================================================
// 描画ヘルパ
// ====================================================================

// 16x16 ビットマップを scale 倍で描画（曜日漢字用。scale=2 → 32x32）。
// 左上を(x,y)とする。MSB=左端ピクセル。
static void drawKanji16x16(int16_t x, int16_t y, const uint16_t* bmp, int16_t scale) {
    for (int16_t row = 0; row < 16; row++) {
        uint16_t bits = bmp[row];
        for (int16_t col = 0; col < 16; col++) {
            if (bits & (0x8000 >> col)) {
                g_epaper.fillRect(x + col * scale, y + row * scale, scale, scale, GxEPD_BLACK);
            }
        }
    }
}

// 太字版: 各黒ピクセルの上下左右に1px追加してストロークを太くする（曜日漢字用）
static void drawKanji16x16Bold(int16_t x, int16_t y, const uint16_t* bmp, int16_t scale) {
    for (int16_t row = 0; row < 16; row++) {
        uint16_t bits = bmp[row];
        for (int16_t col = 0; col < 16; col++) {
            if (bits & (0x8000 >> col)) {
                int16_t px = x + col * scale;
                int16_t py = y + row * scale;
                g_epaper.fillRect(px, py, scale, scale, GxEPD_BLACK);
                g_epaper.fillRect(px + scale, py, 1, scale, GxEPD_BLACK);  // 右に1px膨張
                g_epaper.fillRect(px - 1, py, 1, scale, GxEPD_BLACK);      // 左に1px膨張
                g_epaper.fillRect(px, py + scale, scale, 1, GxEPD_BLACK);  // 下に1px膨張
                g_epaper.fillRect(px, py - 1, scale, 1, GxEPD_BLACK);      // 上に1px膨張
            }
        }
    }
}

// 経過時間アイコン（時計: 太い円＋太い針）。center(cx,cy), 半径r
static void drawClockIcon(int16_t cx, int16_t cy, int16_t r) {
    // 太い文字盤（2重円で2px線）
    g_epaper.drawCircle(cx, cy, r, GxEPD_BLACK);
    g_epaper.drawCircle(cx, cy, r - 1, GxEPD_BLACK);
    // 12時方向の針(上): fillRectで2px幅
    g_epaper.fillRect(cx - 1, cy - r + 4, 2, r - 3, GxEPD_BLACK);
    // 4時方向の針(右下): drawLine 3本並列で太く
    g_epaper.drawLine(cx, cy - 1, cx + (r * 2 / 3), cy + 2, GxEPD_BLACK);
    g_epaper.drawLine(cx, cy,     cx + (r * 2 / 3), cy + 3, GxEPD_BLACK);
    g_epaper.drawLine(cx, cy + 1, cx + (r * 2 / 3), cy + 4, GxEPD_BLACK);
    // 太い頭金具
    g_epaper.fillRect(cx - 2, cy - r - 3, 5, 3, GxEPD_BLACK);
    // 中心点（濃さアップ）
    g_epaper.fillCircle(cx, cy, 2, GxEPD_BLACK);
}

// 数字のみフォント(_tn)で HH MM を「右寄せ」描画（時刻・乗車時間共用）。
// コロンは数字フォントに非収録のため fillCircle で2点を手描画。
static void drawClockDigitsRight(int16_t rightX, int16_t baselineY,
                                 int hours, int minutes,
                                 const uint8_t* font, int16_t gap,
                                 int16_t dotR, int16_t dotOff) {
    u8g2Fonts.setFont(font);
    u8g2Fonts.setFontMode(1);
    u8g2Fonts.setForegroundColor(GxEPD_BLACK);
    u8g2Fonts.setBackgroundColor(GxEPD_WHITE);

    char buf[4];
    snprintf(buf, sizeof(buf), "%02d", hours);
    int hhW = u8g2Fonts.getUTF8Width(buf);
    snprintf(buf, sizeof(buf), "%02d", minutes);
    int mmW = u8g2Fonts.getUTF8Width(buf);

    int16_t totalW = hhW + gap + mmW;
    int16_t x = rightX - totalW;

    snprintf(buf, sizeof(buf), "%02d", hours);
    u8g2Fonts.setCursor(x, baselineY);
    u8g2Fonts.print(buf);
    snprintf(buf, sizeof(buf), "%02d", minutes);
    u8g2Fonts.setCursor(x + hhW + gap, baselineY);
    u8g2Fonts.print(buf);

    int16_t asc = u8g2Fonts.getFontAscent();
    int16_t midY = baselineY - asc / 2;
    int16_t colX = x + hhW + gap / 2;
    g_epaper.fillCircle(colX, midY - dotOff, dotR, GxEPD_BLACK);
    g_epaper.fillCircle(colX, midY + dotOff, dotR, GxEPD_BLACK);
}

// 区切り線（縦線: 左欄/右欄、横線: 時刻/乗車時間）
static void drawDividers() {
    // 縦線（全高）
    g_epaper.fillRect(DIVIDER_X, 0, LINE_W, EP_H, GxEPD_BLACK);
    // 横線（縦線から右端まで）
    g_epaper.fillRect(DIVIDER_X, DIVIDER_Y, EP_W - DIVIDER_X, LINE_W, GxEPD_BLACK);
}

// 左欄: 曜日漢字(上半分) + 日付数字(下半分) を縦に配置（左端基準）
static void drawLeftPanel() {
    // --- 曜日漢字48px(3倍)（上段 0〜DIVIDER_Y の中央、左端配置） ---
    const int16_t kw = 48;  // 16x16を3倍
    int16_t kx = LEFT_MARGIN;                       // 左端
    int16_t ky = DIVIDER_Y / 2 - kw / 2;            // 上段中央(39)-24=15
    drawKanji16x16Bold(kx, ky, KANJI_WEEKDAY[getWeekday()], 3);

    // --- 日付数字 logisoso38（下段 DIVIDER_Y〜EP_H の中央、左端配置） ---
    u8g2Fonts.setFont(u8g2_font_logisoso38_tn);
    u8g2Fonts.setFontMode(1);
    u8g2Fonts.setForegroundColor(GxEPD_BLACK);
    u8g2Fonts.setBackgroundColor(GxEPD_WHITE);

    char dayBuf[4];
    snprintf(dayBuf, sizeof(dayBuf), "%d", getDay());
    int dayW = u8g2Fonts.getUTF8Width(dayBuf);
    int16_t asc = u8g2Fonts.getFontAscent();
    int16_t dx = LEFT_MARGIN - 5;                    // 日付は左端からさらに5px左（視覚的左寄せ）
    int16_t centerDownY = (DIVIDER_Y + EP_H) / 2;   // 下段中央(100)
    int16_t dBaselineY = centerDownY + asc / 2;     // 中心→ベースライン
    u8g2Fonts.setCursor(dx, dBaselineY);
    u8g2Fonts.print(dayBuf);

    // 数字の右に小さく「日」(16px)。数字の下寄りに配置
    drawKanji16x16(dx + dayW + 2, dBaselineY - 14, KANJI_WEEKDAY[0], 1);
}

// 中央揃えテキスト（unifont日本語、未同期/スプラッシュ用）
static void drawCenteredText(const char* text, int16_t baselineY) {
    u8g2Fonts.setFont(u8g2_font_unifont_t_japanese3);
    u8g2Fonts.setFontMode(1);
    u8g2Fonts.setForegroundColor(GxEPD_BLACK);
    u8g2Fonts.setBackgroundColor(GxEPD_WHITE);
    int w = u8g2Fonts.getUTF8Width(text);
    u8g2Fonts.setCursor((EP_W - w) / 2, baselineY);
    u8g2Fonts.print(text);
}

// 日本語自動折返し描画（epaper_test.ino から移植、Phase 10 通知表示用）
// UTF-8 を1文字ずつ描画し、maxWidth を超えると改行する。分割線なしでテキスト埋め。
static void drawWrappedText(int16_t x, int16_t y, const char* text,
                            const uint8_t* font, uint16_t fg, uint16_t bg,
                            int16_t maxWidth) {
    u8g2Fonts.setFont(font);
    u8g2Fonts.setFontMode(1);
    u8g2Fonts.setForegroundColor(fg);
    u8g2Fonts.setBackgroundColor(bg);

    int16_t cursorX = x;
    int16_t lineHeight = u8g2Fonts.getFontAscent() - u8g2Fonts.getFontDescent() + 6;
    int16_t cursorY = y + u8g2Fonts.getFontAscent() + 4;

    const char* p = text;
    while (*p) {
        uint8_t len = 0;
        if ((*p & 0x80) == 0) len = 1;
        else if ((*p & 0xE0) == 0xC0) len = 2;
        else if ((*p & 0xF0) == 0xE0) len = 3;
        else if ((*p & 0xF8) == 0xF0) len = 4;
        else { p++; continue; }   // 不正なバイトは読み飛ばし

        char buf[5] = {0};
        strncpy(buf, p, len);
        int16_t charWidth = u8g2Fonts.getUTF8Width(buf);

        if (cursorX + charWidth > x + maxWidth) {
            cursorX = x;
            cursorY += lineHeight;
        }
        u8g2Fonts.setCursor(cursorX, cursorY);
        u8g2Fonts.print(buf);
        cursorX += charWidth;
        p += len;
    }
}

// バージョン番号 "ver major.minor.patch" を画面中央に描画。
// 「ver」は24pxフォント、番号は巨大数字フォント(_tn)。ピリオドは数字の下端寄り。
// （数字フォントは英字・ピリオド非収録のため「ver」は別フォント、ピリオドは fillCircle）
static void drawVersionBig(const uint8_t* font, int16_t baselineY, int16_t dotR) {
    u8g2Fonts.setFontMode(1);
    u8g2Fonts.setForegroundColor(GxEPD_BLACK);
    u8g2Fonts.setBackgroundColor(GxEPD_WHITE);

    // --- 巨大数字フォント: 各番号の幅 ---
    u8g2Fonts.setFont(font);
    char buf[4];
    snprintf(buf, sizeof(buf), "%d", FIRMWARE_VERSION_MAJOR);
    int16_t majW = u8g2Fonts.getUTF8Width(buf);
    snprintf(buf, sizeof(buf), "%d", FIRMWARE_VERSION_MINOR);
    int16_t minW = u8g2Fonts.getUTF8Width(buf);
    snprintf(buf, sizeof(buf), "%d", FIRMWARE_VERSION_PATCH);
    int16_t patW = u8g2Fonts.getUTF8Width(buf);

    const int16_t dotSlot = dotR * 2 + 8;   // ピリオド区画幅（ドット+両側余白、14から8に縮小）
    const int16_t digitsW = majW + dotSlot + minW + dotSlot + patW;

    // --- 「ver」プレフィックス(34px)の幅 ---
    static const char prefix[] = "ver";
    u8g2Fonts.setFont(u8g2_font_logisoso34_tf);
    const int16_t prefixW = u8g2Fonts.getUTF8Width(prefix);
    const int16_t gap = 0;  // ver と数字の間の固定余白

    // --- 全体中央寄せ開始x ---
    int16_t x = (EP_W - (prefixW + gap + digitsW)) / 2;

    // --- 点のy（数字の下端寄り。ベースライン - 半径 で数字の底に接する） ---
    const int16_t dotY = baselineY - dotR;

    // --- 「ver」描画（34px、ベースライン揃え） ---
    u8g2Fonts.setCursor(x, baselineY);
    u8g2Fonts.print(prefix);
    x += prefixW + gap;

    // --- 番号描画（巨大フォント） ---
    u8g2Fonts.setFont(font);

    snprintf(buf, sizeof(buf), "%d", FIRMWARE_VERSION_MAJOR);
    u8g2Fonts.setCursor(x, baselineY);
    u8g2Fonts.print(buf);
    x += majW + dotSlot / 2;
    g_epaper.fillCircle(x, dotY, dotR, GxEPD_BLACK);
    x += dotSlot / 2;

    snprintf(buf, sizeof(buf), "%d", FIRMWARE_VERSION_MINOR);
    u8g2Fonts.setCursor(x, baselineY);
    u8g2Fonts.print(buf);
    x += minW + dotSlot / 2;
    g_epaper.fillCircle(x, dotY, dotR, GxEPD_BLACK);
    x += dotSlot / 2;

    snprintf(buf, sizeof(buf), "%d", FIRMWARE_VERSION_PATCH);
    u8g2Fonts.setCursor(x, baselineY);
    u8g2Fonts.print(buf);
}

// ====================================================================
// 画面描画
// ====================================================================

// 時計画面（常にフル更新）
static void drawEpaperClock() {
    g_epaper.setFullWindow();
    g_epaper.firstPage();
    do {
        g_epaper.fillScreen(GxEPD_WHITE);
        drawDividers();
        drawLeftPanel();
        // 時刻（右寄せ、上段）
        drawClockDigitsRight(EP_W - RIGHT_MARGIN, TIME_BLY, getHours(), getMinutes(),
                             u8g2_font_logisoso62_tn, 30, 4, 11);
        // 乗車時間（右寄せ、下段）
        int rideH, rideM;
        getRideTime(&rideH, &rideM);
        drawClockDigitsRight(EP_W - RIGHT_MARGIN, RIDE_BLY, rideH, rideM,
                             u8g2_font_logisoso32_tn, 14, 2, 5);
        // 経過時間アイコン（時計）
        drawClockIcon(ICON_CX, ICON_CY, ICON_R);
    } while (g_epaper.nextPage());
}

// 通知表示（Phase 10 本実装）: 本文テキストを全画面に自動折返し描画（分割線なし）
// アプリ名は非表示（ユーザー選択: 本文のみレイアウト）。
// ※ onWrite(BLEタスク) と競合しないよう、ページループ前にローカルコピーを取り、
//    ループ内ではそのコピーを使う（GxEPD2 の paged update は各ページで再描画するため）。
static void drawEpaperNotification(const char* text) {
    static char safeText[NOTIFY_TEXT_LEN];
    strncpy(safeText, text, NOTIFY_TEXT_LEN - 1);
    safeText[NOTIFY_TEXT_LEN - 1] = '\0';

    g_epaper.setFullWindow();
    g_epaper.firstPage();
    do {
        g_epaper.fillScreen(GxEPD_WHITE);
        if (safeText[0] != '\0') {
            drawWrappedText(0, 0, safeText, u8g2_font_unifont_t_japanese3,
                            GxEPD_BLACK, GxEPD_WHITE, EP_W);
        } else {
            drawCenteredText("通知なし", EP_H / 2);
        }
    } while (g_epaper.nextPage());
}

// 詳細表示（モードC）: 開始時刻 / 経過時間 / 現在日時 / HIDキー設定
// 16px級フォントで情報密度高く。切替時の1回だけ描画（スナップショット）。
static void drawEpaperDetail() {
    u8g2Fonts.setFont(u8g2_font_unifont_t_japanese3);
    u8g2Fonts.setFontMode(1);
    u8g2Fonts.setForegroundColor(GxEPD_BLACK);
    u8g2Fonts.setBackgroundColor(GxEPD_WHITE);

    g_epaper.setFullWindow();
    g_epaper.firstPage();
    do {
        g_epaper.fillScreen(GxEPD_WHITE);

        char buf[48];
        const int16_t x = 4;
        const int16_t col2 = 128;   // HIDキー2列目のx
        const int16_t lh = 17;      // 行高
        int16_t y = 14;

        // ① 開始時刻（起動時刻/JST）
        snprintf(buf, sizeof(buf), "開始 %s",
                 g_startupTimeStr[0] ? g_startupTimeStr : "----/--/-- --:--");
        u8g2Fonts.setCursor(x, y);
        u8g2Fonts.print(buf);
        y += lh;

        // ② 経過時間（電源ONから、millisベース）
        int rh, rm;
        getRideTime(&rh, &rm);
        snprintf(buf, sizeof(buf), "経過 %d時間%02d分", rh, rm);
        u8g2Fonts.setCursor(x, y);
        u8g2Fonts.print(buf);
        y += lh;

        // ③ 現在日時（秒まで。スナップショットなので以降更新されない）
        snprintf(buf, sizeof(buf), "現在 %04d/%02d/%02d %s %02d:%02d:%02d",
                 getYear(), getMonth(), getDay(), WEEKDAY_JP[getWeekday()],
                 getHours(), getMinutes(), getSeconds());
        u8g2Fonts.setCursor(x, y);
        u8g2Fonts.print(buf);

        // 区切り線（現在日時とHIDキーの間）
        g_epaper.drawLine(x, y + 6, EP_W - x, y + 6, GxEPD_BLACK);

        // ④ HIDキー設定（2列×4行、SW7は最終行単独）
        y += lh + 2;
        for (int i = 0; i < NUM_HID_SWITCHES; i++) {
            int col = i % 2;
            int row = i / 2;
            snprintf(buf, sizeof(buf), "SW%d %s", i + 1, keyNameFromCode(hidSwitches[i].keyCode));
            u8g2Fonts.setCursor(x + col * col2, y + row * lh);
            u8g2Fonts.print(buf);
        }
    } while (g_epaper.nextPage());
}

// 未同期画面
static void drawEpaperUnsynced() {
    g_epaper.setFullWindow();
    g_epaper.firstPage();
    do {
        g_epaper.fillScreen(GxEPD_WHITE);
        drawCenteredText("BikeClock", EP_H / 2 - 14);
        drawCenteredText("時刻未同期", EP_H / 2 + 14);
    } while (g_epaper.nextPage());
}

extern const char* g_resetReasonStr;

// ブートスプラッシュ（タイトル + バージョン巨大表示）
static void drawEpaperSplash() {
    g_epaper.setFullWindow();
    g_epaper.firstPage();
    do {
        g_epaper.fillScreen(GxEPD_WHITE);
        drawCenteredText("BikeClock", 20);                       // 上段: タイトル
        drawVersionBig(u8g2_font_logisoso62_tn, EP_H - 28, 4);   // 中央: バージョン番号(62px)
        
        // 下段: プラットフォーム + 再起動理由
        char buf[64];
        snprintf(buf, sizeof(buf), "ESP32-S3 (%s)", g_resetReasonStr);
        drawCenteredText(buf, EP_H - 4);
    } while (g_epaper.nextPage());
}

// ====================================================================
// 公開API
// ====================================================================

void setupEpaper() {
    // 先に専用ピンで SPI3_HOST バスを起動（init内のbegin()は既起動で即return→ピン保持される）
    epdSPI.begin(EPD_SPI_SCK_GPIO, -1 /*MISO不要*/, EPD_SPI_MOSI_GPIO, -1);
    // GxEPD2 が使う SPI 実体を専用バスへ差替え（selectSPI は GxEPD2_EPD.h に定義）
    g_epaper.epd2.selectSPI(epdSPI, SPISettings(4000000, MSBFIRST, SPI_MODE0));
    // init: 第1引数を0にしてライブラリ内部の Serial 出力を停止
    g_epaper.init(0, true, 2, false);
    g_epaper.setRotation(1);  // 横長 250x122

    u8g2Fonts.begin(g_epaper);

    logPrint("EPAPER", "Init OK (CS=%d DC=%d RST=%d BUSY=%d SCK=%d MOSI=%d, SPI3_HOST)",
             EPD_CS_GPIO, EPD_DC_GPIO, EPD_RST_GPIO, EPD_BUSY_GPIO,
             EPD_SPI_SCK_GPIO, EPD_SPI_MOSI_GPIO);

    drawEpaperSplash();
}

// 7セグ表示モード → ePaper表示ビュー の対応
static EpaperView displayModeToEpaperView(DisplayMode mode) {
    switch (mode) {
        case DISPLAY_MODE_DATE:    return EP_VIEW_NOTIFICATION;
        case DISPLAY_MODE_WEEKDAY: return EP_VIEW_DETAIL;
        default:                   return EP_VIEW_CLOCK;  // TIME / TEST / その他は標準
    }
}

// loop から毎回呼ばれる。表示すべきビューまたは内容が変わった時だけ描画。
//   - 未同期       → 「時刻未同期」固定
//   - 標準(CLOCK)  → 分/日が変わるかビュー切替時に毎分フル更新
//   - 通知/詳細    → ビューが切り替わった瞬間の1回だけ描画（スナップショット）
void updateEpaperDisplay() {
    // === Phase 10: 通知表示の自動切替・自動復帰 ===
    // 「ユーザ選択のベースビュー(g_displayMode)」を触らず、通知を優先オーバーライド。

    // 通知タイムアウト判定: 期限切れで通知を終了し、ベースを時計(TIME)へ強制復帰
    if (g_notificationActive && g_currentMillis >= g_notificationEndTime) {
        logPrint("NOTIFY", "Timeout - returning to clock");
        g_notificationActive = false;
        g_epaperRedrawRequested = true;
        // ベースが DATE/WEEKDAY なら時計に戻す（合意: 通知終了で常に標準時計へ）
        if (g_displayMode == DISPLAY_MODE_DATE || g_displayMode == DISPLAY_MODE_WEEKDAY) {
            g_displayMode = DISPLAY_MODE_TIME;
            g_lastModeChangeMillis = g_currentMillis;
        }
    }

    // 強制再描画要求の消化（新着通知／タイムアウト復帰でビュー切替を確実にする）
    if (g_epaperRedrawRequested) {
        ep_lastView = EP_VIEW_NONE;
        g_epaperRedrawRequested = false;
    }

    // 通知表示中（未同期状態より優先。合意: 現在のモードに関わらず通知を表示）
    if (g_notificationActive) {
        if (ep_lastView != EP_VIEW_NOTIFICATION) {
            drawEpaperNotification(g_notificationText);
            ep_lastView = EP_VIEW_NOTIFICATION;
        }
        return;
    }

    // 未同期: 固定画面
    if (!g_timeSynced) {
        if (ep_lastView != EP_VIEW_UNSYNCED) {
            drawEpaperUnsynced();
            ep_lastView = EP_VIEW_UNSYNCED;
        }
        return;
    }

    const EpaperView target = displayModeToEpaperView(g_displayMode);

    // 標準ビュー: 分/日変化でも更新（時計は毎分更新）
    if (target == EP_VIEW_CLOCK) {
        const int h = getHours();
        const int m = getMinutes();
        const int d = getDay();
        const bool viewChanged = (ep_lastView != EP_VIEW_CLOCK);
        if (viewChanged || h != ep_lastHr || m != ep_lastMin || d != ep_lastDay) {
            drawEpaperClock();
            ep_lastView = EP_VIEW_CLOCK;
            ep_lastHr  = (int8_t)h;
            ep_lastMin = (int8_t)m;
            ep_lastDay = (int8_t)d;
        }
        return;
    }

    // 通知/詳細ビュー: ビューが変わった時だけ描画（スナップショット1回）
    // 通知ビュー(DATE手動切替時): 直近の通知本文があれば再表示、なければ「通知なし」
    if (target != ep_lastView) {
        if (target == EP_VIEW_NOTIFICATION) {
            drawEpaperNotification(g_notificationText);
        } else {  // EP_VIEW_DETAIL
            drawEpaperDetail();
        }
        ep_lastView = target;
    }
}
