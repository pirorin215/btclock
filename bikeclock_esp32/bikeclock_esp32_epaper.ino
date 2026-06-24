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
 *
 * 更新戦略:
 *   - 分/日変化・初回同期 → フル更新（文字のにじみ防止・コントラスト最大化）
 *   - 未同期 → 「時刻未同期」固定表示
 *
 * ハードウェア: 専用 SPI3_HOST バス(CS=1,DC=2,RST=3,BUSY=10,SCK=12,MOSI=11)。
 * ePaper更新はブロッキング(部分~0.3s/フル~3s)だが、NimBLEは別FreeRTOSタスクで
 * 稼働するためBLE時刻同期の応答性には影響しない。
 */

#include "bikeclock.h"
#include <SPI.h>
#include <GxEPD2_BW.h>
#include <U8g2_for_Adafruit_GFX.h>
#include "qrcode.h"

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
// millisベースなので時刻同期(BLE)で g_currentTimestamp がジャンプしても影響しない＝実際の乗車時間。
static void getRideTime(int* hours, int* minutes) {
    unsigned long totalMin = (millis() - g_startupMillis) / 60000UL;
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

// フォント選択とePaper向け共通設定（黒字/白地/ブレンド）。全描画で同一のため共通化。
static void setFont(const uint8_t* font) {
    u8g2Fonts.setFont(font);
    u8g2Fonts.setFontMode(1);
    u8g2Fonts.setForegroundColor(GxEPD_BLACK);
    u8g2Fonts.setBackgroundColor(GxEPD_WHITE);
}

// 16x16 ビットマップを scale 倍で描画（曜日漢字用）。左上(x,y), MSB=左端。
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

// 太字版: 各黒ピクセルから上下左右に1px膨張させてストロークを太くする（曜日漢字用）
static void drawKanji16x16Bold(int16_t x, int16_t y, const uint16_t* bmp, int16_t scale) {
    for (int16_t row = 0; row < 16; row++) {
        uint16_t bits = bmp[row];
        for (int16_t col = 0; col < 16; col++) {
            if (bits & (0x8000 >> col)) {
                int16_t px = x + col * scale;
                int16_t py = y + row * scale;
                g_epaper.fillRect(px, py, scale, scale, GxEPD_BLACK);
                g_epaper.fillRect(px + scale, py, 1, scale, GxEPD_BLACK);
                g_epaper.fillRect(px - 1, py, 1, scale, GxEPD_BLACK);
                g_epaper.fillRect(px, py + scale, scale, 1, GxEPD_BLACK);
                g_epaper.fillRect(px, py - 1, scale, 1, GxEPD_BLACK);
            }
        }
    }
}

// 経過時間アイコン（時計: 太い円＋太い針）。center(cx,cy), 半径r。
// gfx: 描画先（g_epaper 直接 / ScaledGFX 拡大描画。スケールは gfx 側で吸収）。
static void drawClockIcon(Adafruit_GFX& gfx, int16_t cx, int16_t cy, int16_t r) {
    // 太い文字盤（2重円で2px線）
    gfx.drawCircle(cx, cy, r, GxEPD_BLACK);
    gfx.drawCircle(cx, cy, r - 1, GxEPD_BLACK);
    // 12時方向の針(上): fillRectで2px幅
    gfx.fillRect(cx - 1, cy - r + 4, 2, r - 3, GxEPD_BLACK);
    // 4時方向の針(右下): drawLine 3本並列で太く
    gfx.drawLine(cx, cy - 1, cx + (r * 2 / 3), cy + 2, GxEPD_BLACK);
    gfx.drawLine(cx, cy,     cx + (r * 2 / 3), cy + 3, GxEPD_BLACK);
    gfx.drawLine(cx, cy + 1, cx + (r * 2 / 3), cy + 4, GxEPD_BLACK);
    // 太い頭金具
    gfx.fillRect(cx - 2, cy - r - 3, 5, 3, GxEPD_BLACK);
    // 中心点（濃さアップ）
    gfx.fillCircle(cx, cy, 2, GxEPD_BLACK);
}

// 数字フォントで HH:MM を左端 x から左寄せ描画（コロンは fillCircle で2点）。
// gfx は u8g2Fonts.begin() の描画先と一致させること。
static void drawHHMM(Adafruit_GFX& gfx, int16_t x, int16_t baselineY,
                     int hours, int minutes,
                     const uint8_t* font, int16_t gap,
                     int16_t dotR, int16_t dotOff) {
    setFont(font);

    char buf[4];
    snprintf(buf, sizeof(buf), "%02d", hours);
    int hhW = u8g2Fonts.getUTF8Width(buf);

    u8g2Fonts.setCursor(x, baselineY);
    u8g2Fonts.print(buf);

    int16_t asc = u8g2Fonts.getFontAscent();
    int16_t midY = baselineY - asc / 2;
    int16_t colX = x + hhW + gap / 2;
    gfx.fillCircle(colX, midY - dotOff, dotR, GxEPD_BLACK);
    gfx.fillCircle(colX, midY + dotOff, dotR, GxEPD_BLACK);

    snprintf(buf, sizeof(buf), "%02d", minutes);
    u8g2Fonts.setCursor(x + hhW + gap, baselineY);
    u8g2Fonts.print(buf);
}

// 数字のみフォント(_tn)で HH MM を右寄せ描画（時刻・乗車時間共用）。drawHHMM に委譲。
static void drawClockDigitsRight(Adafruit_GFX& gfx, int16_t rightX, int16_t baselineY,
                                 int hours, int minutes,
                                 const uint8_t* font, int16_t gap,
                                 int16_t dotR, int16_t dotOff) {
    u8g2Fonts.setFont(font);
    char buf[4];
    snprintf(buf, sizeof(buf), "%02d", hours);
    int hhW = u8g2Fonts.getUTF8Width(buf);
    snprintf(buf, sizeof(buf), "%02d", minutes);
    int mmW = u8g2Fonts.getUTF8Width(buf);
    int16_t totalW = hhW + gap + mmW;
    drawHHMM(gfx, rightX - totalW, baselineY, hours, minutes, font, gap, dotR, dotOff);
}

// 区切り線（縦線: 左欄/右欄、横線: 時刻/乗車時間）
static void drawDividers() {
    g_epaper.fillRect(DIVIDER_X, 0, LINE_W, EP_H, GxEPD_BLACK);
    g_epaper.fillRect(DIVIDER_X, DIVIDER_Y, EP_W - DIVIDER_X, LINE_W, GxEPD_BLACK);
}

// 左欄: 曜日漢字(上半分) + 日付数字(下半分) を縦に配置（左端基準）
static void drawLeftPanel() {
    // --- 曜日漢字48px(3倍)（上段中央、左端配置） ---
    const int16_t kw = 48;  // 16x16を3倍
    int16_t kx = LEFT_MARGIN;
    int16_t ky = DIVIDER_Y / 2 - kw / 2;
    drawKanji16x16Bold(kx, ky, KANJI_WEEKDAY[getWeekday()], 3);

    // --- 日付数字 logisoso38（下段中央、左端配置） ---
    setFont(u8g2_font_logisoso38_tn);

    char dayBuf[4];
    snprintf(dayBuf, sizeof(dayBuf), "%d", getDay());
    int dayW = u8g2Fonts.getUTF8Width(dayBuf);
    int16_t asc = u8g2Fonts.getFontAscent();
    int16_t dx = LEFT_MARGIN - 5;                    // 数字は左端からさらに5px左（視覚的左寄せ）
    int16_t dBaselineY = (DIVIDER_Y + EP_H) / 2 + asc / 2;
    u8g2Fonts.setCursor(dx, dBaselineY);
    u8g2Fonts.print(dayBuf);

    // 数字の右に小さく「日」(16px)。数字の下寄りに配置
    drawKanji16x16(dx + dayW + 2, dBaselineY - 14, KANJI_WEEKDAY[0], 1);
}

// 拡大描画用の Adafruit_GFX ラッパークラス
class ScaledGFX : public Adafruit_GFX {
private:
    Adafruit_GFX& _realGfx;
    int16_t _scale;

public:
    ScaledGFX(Adafruit_GFX& realGfx, int16_t scale)
        : Adafruit_GFX(realGfx.width() / scale, realGfx.height() / scale),
          _realGfx(realGfx), _scale(scale) {}

    void drawPixel(int16_t x, int16_t y, uint16_t color) override {
        _realGfx.fillRect(x * _scale, y * _scale, _scale, _scale, color);
    }

    void drawFastHLine(int16_t x, int16_t y, int16_t w, uint16_t color) override {
        _realGfx.fillRect(x * _scale, y * _scale, w * _scale, _scale, color);
    }

    void drawFastVLine(int16_t x, int16_t y, int16_t h, uint16_t color) override {
        _realGfx.fillRect(x * _scale, y * _scale, _scale, h * _scale, color);
    }

    void startWrite() override { _realGfx.startWrite(); }
    void endWrite() override { _realGfx.endWrite(); }
};

// 中央揃えテキスト（unifont日本語）。
// scale>1 で ScaledGFX により拡大（scale=2 → 実32px相当）。baselineY は実ピクセル座標
// （拡大時は内部で仮想座標 baselineY/scale に変換）。スプラッシュ/OTA は scale=1 のまま。
static void drawCenteredText(const char* text, int16_t baselineY, int16_t scale = 1) {
    setFont(u8g2_font_unifont_t_japanese3);
    int w = u8g2Fonts.getUTF8Width(text);
    if (scale == 1) {
        u8g2Fonts.setCursor((EP_W - w) / 2, baselineY);
        u8g2Fonts.print(text);
        return;
    }
    ScaledGFX scaledGfx(g_epaper, scale);
    u8g2Fonts.begin(scaledGfx);
    u8g2Fonts.setCursor((EP_W / scale - w) / 2, baselineY / scale);
    u8g2Fonts.print(text);
    u8g2Fonts.begin(g_epaper);   // 描画先を元に戻す
}

// UTF-8先頭バイトから1文字のバイト長(1-4)を返す。不正バイトは0。
static uint8_t utf8Len(const char* p) {
    if ((*p & 0x80) == 0) return 1;
    if ((*p & 0xE0) == 0xC0) return 2;
    if ((*p & 0xF0) == 0xE0) return 3;
    if ((*p & 0xF8) == 0xF0) return 4;
    return 0;
}

// UTF-8 文字列の文字数（バイト数ではない）。通知フォントサイズの段階切替で使用。
static int utf8CharCount(const char* text) {
    int count = 0;
    for (const char* p = text; *p; ) {
        uint8_t len = utf8Len(p);
        if (!len) { p++; continue; }
        count++;
        p += len;
    }
    return count;
}

// 日本語自動折返し描画（通知表示用）。UTF-8 を1文字ずつ描画し、maxWidth を超えると改行。
static void drawWrappedText(int16_t x, int16_t y, const char* text,
                            const uint8_t* font, int16_t maxWidth) {
    setFont(font);

    int16_t cursorX = x;
    int16_t lineHeight = u8g2Fonts.getFontAscent() - u8g2Fonts.getFontDescent() + 6;
    int16_t cursorY = y + u8g2Fonts.getFontAscent() + 4;

    const char* p = text;
    while (*p) {
        uint8_t len = utf8Len(p);
        if (!len) { p++; continue; }   // 不正バイトは読み飛ばし

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
// 「ver」は34pxフォント、番号は巨大数字フォント。ピリオドは数字フォントが非収録のため fillCircle。
static void drawVersionBig(const uint8_t* font, int16_t baselineY, int16_t dotR) {
    const int parts[] = { FIRMWARE_VERSION_MAJOR, FIRMWARE_VERSION_MINOR, FIRMWARE_VERSION_PATCH };
    setFont(font);

    char buf[4];
    int16_t width[3];
    for (int i = 0; i < 3; i++) {
        snprintf(buf, sizeof(buf), "%d", parts[i]);
        width[i] = u8g2Fonts.getUTF8Width(buf);
    }

    const int16_t dotSlot = dotR * 2 + 8;   // ピリオド区画幅（ドット+両側余白）
    const int16_t digitsW = width[0] + dotSlot + width[1] + dotSlot + width[2];

    // 「ver」プレフィックス(34px)の幅
    static const char prefix[] = "ver";
    u8g2Fonts.setFont(u8g2_font_logisoso34_tf);
    const int16_t prefixW = u8g2Fonts.getUTF8Width(prefix);

    // 全体中央寄せ開始x
    int16_t x = (EP_W - (prefixW + digitsW)) / 2;
    const int16_t dotY = baselineY - dotR;   // ドットは数字の下端寄り

    // 「ver」描画（34px、ベースライン揃え）
    u8g2Fonts.setCursor(x, baselineY);
    u8g2Fonts.print(prefix);
    x += prefixW;

    // 番号描画（巨大フォント）。末尾以外はピリオドを挟む。
    u8g2Fonts.setFont(font);
    for (int i = 0; i < 3; i++) {
        snprintf(buf, sizeof(buf), "%d", parts[i]);
        u8g2Fonts.setCursor(x, baselineY);
        u8g2Fonts.print(buf);
        x += width[i];
        if (i < 2) {
            x += dotSlot / 2;
            g_epaper.fillCircle(x, dotY, dotR, GxEPD_BLACK);
            x += dotSlot / 2;
        }
    }
}

// ====================================================================
// 画面描画
// ====================================================================

// フル画面をページ単位で描画。GxEPD2 の paged-update 定型句の共通化。
// マクロ化しているのは、関数テンプレートだと Arduino の自動プロトタイプ生成が
// 壊れるため（テンプレート仮引数型の前方宣言を勝手に出力してしまう）。
#define DRAW_PAGED(...) \
    g_epaper.setFullWindow(); \
    g_epaper.firstPage(); \
    do { \
        g_epaper.fillScreen(GxEPD_WHITE); \
        __VA_ARGS__; \
    } while (g_epaper.nextPage())

// 時計画面（常にフル更新）
static void drawEpaperClock() {
    DRAW_PAGED({
        drawDividers();
        drawLeftPanel();
        // 時刻（右寄せ、上段）
        drawClockDigitsRight(g_epaper, EP_W - RIGHT_MARGIN, TIME_BLY, getHours(), getMinutes(),
                             u8g2_font_logisoso62_tn, 30, 4, 11);
        // 乗車時間（右寄せ、下段）
        int rideH, rideM;
        getRideTime(&rideH, &rideM);
        drawClockDigitsRight(g_epaper, EP_W - RIGHT_MARGIN, RIDE_BLY, rideH, rideM,
                             u8g2_font_logisoso32_tn, 14, 2, 5);
        // 経過時間アイコン（時計）
        drawClockIcon(g_epaper, ICON_CX, ICON_CY, ICON_R);
    });
}

// 通知表示: 本文テキストを全画面に自動折返し描画。
// 文字数に応じてフォントサイズを段階切替（NOTIFY_FONT_SETTINGS）。
//   12字以下 → 16px / 13〜40字 → 12px / 41字以上 → 10px
// ※ onWrite(BLEタスク) と競合しないよう、ページループ前にローカルコピーを取り、
//    ループ内ではそのコピーを使う（paged update は各ページで再描画するため）。
static void drawEpaperNotification(const char* text) {
    static char safeText[NOTIFY_TEXT_LEN];
    strncpy(safeText, text, NOTIFY_TEXT_LEN - 1);
    safeText[NOTIFY_TEXT_LEN - 1] = '\0';

    // 文字数でフォントサイズとスケーリングを判定
    const uint8_t* font = u8g2_font_b10_t_japanese2; // デフォルトフォールバック
    int scale = 1;
    const int n = utf8CharCount(safeText);
    for (size_t i = 0; i < NUM_NOTIFY_FONT_SETTINGS; i++) {
        if (n <= NOTIFY_FONT_SETTINGS[i].maxChars) {
            font = NOTIFY_FONT_SETTINGS[i].font;
            scale = NOTIFY_FONT_SETTINGS[i].scale;
            break;
        }
    }

    int baseSize = (font == u8g2_font_b16_t_japanese3) ? 16 : ((font == u8g2_font_b12_t_japanese3) ? 12 : 10);
    logPrint("NOTIFY", "Text: '%s', UTF8 chars: %d, selected font size: %dpx, scale: %dx (effective: %dpx)",
             safeText, n, baseSize, scale, baseSize * scale);

    DRAW_PAGED({
        if (safeText[0] != '\0') {
            if (scale > 1) {
                ScaledGFX scaledGfx(g_epaper, scale);
                u8g2Fonts.begin(scaledGfx);
                drawWrappedText(0, 0, safeText, font, EP_W / scale);
                u8g2Fonts.begin(g_epaper); // 元に戻す
            } else {
                drawWrappedText(0, 0, safeText, font, EP_W);
            }
        } else {
            drawCenteredText("通知なし", 76, 2);
        }
    });
}

// 詳細表示（モードC）: 開始時刻 / 経過時間 / 現在日時 / HIDキー設定。スナップショット（1回のみ描画）。
static void drawEpaperDetail() {
    DRAW_PAGED({
        setFont(u8g2_font_unifont_t_japanese3);

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
    });
}

// 詳細大表示（モード4）: 日付＋曜日 / 経過 / 開始〜現在 を2倍拡大で3行表示。
//   ②経過はlogisoso20(実40px)・①③はb16(実32px)。
// 日付は画面幅(250px)の制約でスラッシュ短縮形 "2026/06/24 水" とし32pxに収める。
// スナップショット（FUNC切替時に1回のみ描画・以降更新なし）。
static void drawEpaperDetailLarge() {
    const int scale = 2;

    DRAW_PAGED({
        ScaledGFX scaledGfx(g_epaper, scale);
        u8g2Fonts.begin(scaledGfx);
        setFont(u8g2_font_b16_t_japanese3);

        int16_t y = 10;          // 1行目ベースライン（②経過行を大きく取るため上詰め）
        char buf[40];

        // ① 開始日付＋曜日（起動時刻の日付。日をまたいでも開始日。例: "2026/06/24 水"）
        if (g_startupTimeStr[0]) {
            snprintf(buf, sizeof(buf), "%.10s %s", g_startupTimeStr, WEEKDAY_JP[g_startupWeekday]);
        } else {
            snprintf(buf, sizeof(buf), "%04d/%02d/%02d %s",
                     getYear(), getMonth(), getDay(), WEEKDAY_JP[getWeekday()]);
        }
        u8g2Fonts.setCursor(3, y);
        u8g2Fonts.print(buf);
        y += 28;                 // → ②経過行

        // ② 経過時間（電源ONから、millisベース）: 時計アイコン + HH:MM
        // logisoso20(実40px相当)。モード1の日付数字(logisoso38=実38px)と同サイズ。
        int rh, rm;
        getRideTime(&rh, &rm);
        const int16_t iconR = 7;
        int16_t iconCx = 3 + iconR + 1;     // 左マージン + 半径
        int16_t iconCy = y - 7;             // 行中央（ベースライン上）
        drawClockIcon(scaledGfx, iconCx, iconCy, iconR);
        drawHHMM(scaledGfx, iconCx + iconR + 3, y, rh, rm,
                 u8g2_font_logisoso20_tn, 8, 2, 3);
        y += 15;                 // → ③開始〜現在

        // ③ 開始〜現在（日をまたぐと終端を24時超えで表示。例: "22:00〜26:30"）
        const char* st = g_startupTimeStr[0] ? (g_startupTimeStr + 11) : "--:--";
        int eh = getHours(), em = getMinutes();
        if (g_startupTimeStr[0]) {
            int sh = (g_startupTimeStr[11] - '0') * 10 + (g_startupTimeStr[12] - '0');
            int sm = (g_startupTimeStr[14] - '0') * 10 + (g_startupTimeStr[15] - '0');
            int endMin = eh * 60 + em;
            if (endMin < sh * 60 + sm) endMin += 24 * 60;   // 日をまたいだ → 24時間超え表示
            eh = endMin / 60;
            em = endMin % 60;
        }
        snprintf(buf, sizeof(buf), "%s〜%02d:%02d", st, eh, em);
        setFont(u8g2_font_b16_t_japanese3);  // ②行(drawHHMM)で変更されたフォントをb16に戻す
        u8g2Fonts.setCursor(3, y);
        u8g2Fonts.print(buf);

        u8g2Fonts.begin(g_epaper);   // 描画先を元に戻す
    });
}

// 未同期画面
static void drawEpaperUnsynced() {
    DRAW_PAGED({
        drawCenteredText("BikeClock", 54, 2);       // 実32px相当（中央上寄り）
        drawCenteredText("時刻未同期", 98, 2);       // 実32px相当（中央下寄り）
    });
}

extern const char* g_resetReasonStr;

// ブートスプラッシュ（タイトル + バージョン巨大表示）
static void drawEpaperSplash() {
    DRAW_PAGED({
        drawCenteredText("BikeClock", 20);                       // 上段: タイトル
        drawVersionBig(u8g2_font_logisoso62_tn, EP_H - 28, 4);   // 中央: バージョン番号(62px)
        // 下段: プラットフォーム + 再起動理由
        char buf[64];
        snprintf(buf, sizeof(buf), "ESP32-S3 (%s)", g_resetReasonStr);
        drawCenteredText(buf, EP_H - 4);
    });
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
    g_epaper.setRotation(3);  // 横長 250x122

    u8g2Fonts.begin(g_epaper);

    logPrint("EPAPER", "Init OK (CS=%d DC=%d RST=%d BUSY=%d SCK=%d MOSI=%d, SPI3_HOST)",
             EPD_CS_GPIO, EPD_DC_GPIO, EPD_RST_GPIO, EPD_BUSY_GPIO,
             EPD_SPI_SCK_GPIO, EPD_SPI_MOSI_GPIO);

    drawEpaperSplash();
}

// 7セグ表示モード → ePaper表示ビュー の対応（FUNC_MODE_TABLE から導出・唯一の正）
static EpaperView displayModeToEpaperView(DisplayMode mode) {
    for (int i = 0; i < FUNC_MODE_COUNT; i++) {
        if (FUNC_MODE_TABLE[i].segDisplay == mode) {
            return FUNC_MODE_TABLE[i].epaperView;
        }
    }
    return EP_VIEW_CLOCK;  // TEST / その他は標準
}

// loop から毎回呼ばれる。表示すべきビューまたは内容が変わった時だけ描画。
//   - 未同期       → 「時刻未同期」固定
//   - 標準(CLOCK)  → 分/日が変わるかビュー切替時に毎分フル更新
//   - 通知/詳細    → ビューが切り替わった瞬間の1回だけ描画（スナップショット）
void updateEpaperDisplay() {
    // FUNC連打中は最終モードが確定するまで描画を遅延（連打の無駄な再描画を抑制）
    if (g_currentMillis - g_lastFuncEdgeMs < EPAPER_FUNC_COALESCE_MS) {
        return;
    }
    // === 通知表示の自動切替・自動復帰 ===
    // 「ユーザ選択のベースビュー(g_displayMode)」を触らず、通知を優先オーバーライド。

    // 通知タイムアウト判定: 期限切れで通知を終了し、ベースを時計(TIME)へ強制復帰
    if (g_notificationActive && g_currentMillis >= g_notificationEndTime) {
        logPrint("NOTIFY", "Timeout - returning to clock");
        g_notificationActive = false;
        g_epaperRedrawRequested = true;
        // ベースが DATE/WEEKDAY/SECONDS なら時計に戻す（合意: 通知終了で常に標準時計へ）
        if (g_displayMode == DISPLAY_MODE_DATE || g_displayMode == DISPLAY_MODE_WEEKDAY ||
            g_displayMode == DISPLAY_MODE_SECONDS) {
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
    EpaperView effective = target;
    if (g_parkedDisplayActive) effective = EP_VIEW_DETAIL_LARGE;   // 駐車中は詳細大(モード4)で維持

    // 標準ビュー: 分/日変化でも更新（時計は毎分更新）
    if (effective == EP_VIEW_CLOCK) {
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
    if (effective != ep_lastView) {
        if (effective == EP_VIEW_NOTIFICATION) {
            drawEpaperNotification(g_notificationText);
        } else if (effective == EP_VIEW_DETAIL_LARGE) {
            drawEpaperDetailLarge();
        } else {  // EP_VIEW_DETAIL
            drawEpaperDetail();
        }
        ep_lastView = effective;
    }
}

void drawEpaperOtaState(const char* state, const char* ipStr) {
    enum OtaState { CON, FAIL, NOFI, STOP, OTA, OTA_URL, OTHER };
    OtaState st = OTHER;
    if      (strcmp(state, "Con")     == 0) st = CON;
    else if (strcmp(state, "FAIL")    == 0) st = FAIL;
    else if (strcmp(state, "noFi")    == 0) st = NOFI;
    else if (strcmp(state, "STOP")    == 0) st = STOP;
    else if (strcmp(state, "OTA")     == 0) st = OTA;
    else if (strcmp(state, "OTA_URL") == 0) st = OTA_URL;

    DRAW_PAGED({
        switch (st) {
        case CON:  drawCenteredText("WiFi 起動中...", 65); break;
        case FAIL: drawCenteredText("接続失敗", 65); break;
        case NOFI: drawCenteredText("WiFi 未設定", 65); break;
        case STOP: drawCenteredText("キャンセルされました", 65); break;
        case OTA: case OTA_URL: {
            // 左側: QRコード（ローカルのQRCodeライブラリ）
            QRCode qrcode;
            uint8_t qrcodeData[qrcode_getBufferSize(3)];
            char urlBuf[64];
            snprintf(urlBuf, sizeof(urlBuf), "http://%s", ipStr);
            // OTA=Wifi自動接続用 / OTA_URL=URLアクセス用
            qrcode_initText(&qrcode, qrcodeData, 3, ECC_MEDIUM,
                            st == OTA ? "WIFI:S:bcota;T:NOPASS;;" : urlBuf);

            const int16_t qrX = 10, qrY = 17, scale = 3;
            for (uint8_t y = 0; y < qrcode.size; y++) {
                for (uint8_t x = 0; x < qrcode.size; x++) {
                    if (qrcode_getModule(&qrcode, x, y)) {
                        g_epaper.fillRect(qrX + x * scale, qrY + y * scale, scale, scale, GxEPD_BLACK);
                    }
                }
            }

            // 右側: テキスト表示
            const int16_t textX = 110;

            // 1. タイトル
            setFont(u8g2_font_unifont_t_japanese3);
            u8g2Fonts.setCursor(textX, 26);
            u8g2Fonts.print("BikeClock OTA");

            // 2〜4. 案内 / 接続後案内 / URL
            setFont(u8g2_font_b12_t_japanese3);
            u8g2Fonts.setCursor(textX, 52);
            u8g2Fonts.print(st == OTA ? "QRでWiFi自動接続" : "QRでページを開く");
            u8g2Fonts.setCursor(textX, 70);
            u8g2Fonts.print(st == OTA ? "接続後ブラウザで以下へ" : "またはブラウザで以下へ");
            u8g2Fonts.setCursor(textX, 90);
            u8g2Fonts.print(urlBuf);

            // 5. 終了方法
            setFont(u8g2_font_b10_t_japanese2);
            u8g2Fonts.setCursor(textX, 112);
            u8g2Fonts.print("FUNC長押し(2秒)で終了");
            break;
        }
        default: break;
        }
    });
}
