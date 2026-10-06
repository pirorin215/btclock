/**
 * CycleClock - ePaper 表示処理
 *
 * bikeclock_esp32_epaper.ino (v2.0.61) から XIAO BLE (nRF52840) へ移植。
 * 変更点:
 * - 専用 SPI3_HOST バス + selectSPI → XIAO のデフォルト SPI (D8/D10)
 * - スプラッシュのプラットフォーム表記を nRF52840 に変更
 * - 詳細/OTA/QR ビューを削除(時計・未同期・スプラッシュ+通知)
 * - v0.3.2: 通知ビューを bikeclock_esp32 (Phase 10) から復活・移植
 * - v0.3.3: 低電圧警告ビュー(電池アイコン+「要充電」+電圧)を追加。
 *   優先度: 通知 > 低電圧 > 未同期 > 時計
 * - v0.3.8: FUNCキー機構をbikeclockから移植(中押しでモード切替)。
 *   優先度: モード(電池詳細/バージョン) > 通知 > 低電圧 > 未同期 > 時計
 * - v0.3.9: FUNCキーを専用GPIO(D2)に分離(v0.3.8の押下時間分離は廃止)。
 *   ビュー側の構造は変更なし
 * - v0.4.0: FUNCモードをbikeclock_esp32と同一の4モード構成へ
 *   (時計/通知/詳細/詳細大)。電池詳細・バージョンモードは廃止
 *   (電圧は詳細ビューの行+ピクトに統合・未同期画面にバージョン表示)
 * - v0.4.5: ePaper表示を180度回転(setRotation 3→1・取付向きに合わせる)
 * - v0.4.6: 日付数字をlogisoso38→46へ拡大。「日」は12pxに縮小し右端
 *   (縦線手前)・ベースライン下端に固定配置(2桁日でも数字と重ならない)
 *
 * 表示レイアウト（250x122 横長, rotation=1）:
 *
 *        ┌────────┬─────────────────────┐
 *        │   月   │      １ ２ ： ３ ４  │  時刻(logisoso62, 右寄せ)
 *        │ (曜日) │═════════════════════│  ← 横線
 *        │   15   │ ⏲ ０ ０ ： １ ５   ▯ │  乗車時間(logisoso32, 左オフセット)
 *        │ (日付) │                 [▮] │  電池ピクト(右下固定・縦型5段階)
 *        └────────┴─────────────────────┘
 *
 * スリープ画面(残画)にも同じ右下位置に電池ピクトを描く(System OFF中も見える)。
 *
 * 更新戦略: 分/日変化・ビュー切替時のみフル更新(にじみ防止)。
 * ePaper更新はブロッキング(フル~3s)だが、BLEスタックはSoftDeviceが
 * 別コンテキストで処理するため時刻同期の応答性には影響しない。
 */

#include "cycleclock.h"
// SPI / GxEPD2_BW / U8g2_for_Adafruit_GFX の include は cycleclock.h に集約
// (自動プロトタイプ生成がinclude前に関数プロトタイプを挿入する問題の回避)

// === ePaper オブジェクト（XIAO デフォルト SPI: D8=SCK, D10=MOSI） ===
static GxEPD2_BW<GxEPD2_213_B74, GxEPD2_213_B74::HEIGHT> g_epaper(
    GxEPD2_213_B74(EPD_CS_GPIO, EPD_DC_GPIO, EPD_RST_GPIO, EPD_BUSY_GPIO));
static U8G2_FOR_ADAFRUIT_GFX u8g2Fonts;

// setupEpaper()完了フラグ。完了前のBUSYピンは未初期化(浮き)のため
// epaperIdle()のBUSY判定を無効化する(起動直後の即測定を守る)。
static bool s_epaperReady = false;

// === 表示状態（前回描画内容のキャッシュで無駄な更新を省く） ===
static int8_t ep_lastHr  = -1;
static int8_t ep_lastMin = -1;
static int8_t ep_lastDay = -1;
static bool   ep_showingUnsynced = false;
static bool   ep_showingNotification = false;
static bool   ep_showingLowBatt = false;
static int8_t ep_drawnMode = -1;        // 描画済みのDisplayMode(-1=未描画・MODE復帰判定用)
static uint32_t ep_drawnNotifySeq = 0;  // 通知モードで描いた通知の受信連番

// 「次の描画を強制する」ための画面キャッシュ無効化。ビュー遷移・通知タイムアウト・
// 低電圧発報のいずれでも、直前の画面種別に関わらず再描画させる。
static void invalidateViewCache() {
    ep_lastHr = -1;
    ep_lastMin = -1;
    ep_lastDay = -1;
    ep_showingUnsynced = false;
    ep_showingLowBatt = false;
}

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
static const int16_t DAY_BLY      = 120;  // 日付(logisoso46)のベースライン（左欄下段）
static const int16_t RIDE_RIGHT_X = 206;  // 乗車時間の右端(右下の電池ピクト分オフセット)
static const int16_t ICON_CX      = 90;   // 乗車時間アイコン(時計)の中心x(乗車時間の左オフセットに合わせ左へ)
static const int16_t ICON_CY      = 104;  // 乗車時間アイコン(時計)の中心y
static const int16_t ICON_R       = 12;   // 乗車時間アイコン(時計)の半径

// ====================================================================
// 曜日漢字 16x16 ビットマップ（GNU Unifont, MSB=左端）
//   U8g2の大きいフォントは漢字非収録(最大16px)のため、16x16ビットマップを
//   3倍拡大(48px)して描画する。
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

// 曜日（0=日 ... 6=土）
static const char* WEEKDAY_JP[] = {"日", "月", "火", "水", "木", "金", "土"};

// ====================================================================
// 乗車時間（起床からの経過、millisベース）
// 振動ウェイク=乗車開始なので、起動からの経過時間がそのまま乗車時間になる。
// ====================================================================
static void getRideTime(int* hours, int* minutes) {
    unsigned long totalMin = (millis() - g_startupMillis) / 60000UL;
    *hours   = (int)(totalMin / 60UL);
    *minutes = (int)(totalMin % 60UL);
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

// 16x16 ビットマップを最近傍で縮小描画（日付の「日」16→12px用）。
// src→dst の順引きで各黒ピクセルを落とさず対応付ける
static void drawKanji16x16Small(int16_t x, int16_t y, const uint16_t* bmp, int16_t size) {
    for (int16_t row = 0; row < 16; row++) {
        uint16_t bits = bmp[row];
        for (int16_t col = 0; col < 16; col++) {
            if (bits & (0x8000 >> col)) {
                g_epaper.drawPixel(x + col * size / 16, y + row * size / 16, GxEPD_BLACK);
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

// 乗車時間アイコン（時計: 太い円＋太い針）。center(cx,cy), 半径r。
static void drawClockIcon(Adafruit_GFX& gfx, int16_t cx, int16_t cy, int16_t r) {
    gfx.drawCircle(cx, cy, r, GxEPD_BLACK);
    gfx.drawCircle(cx, cy, r - 1, GxEPD_BLACK);
    gfx.fillRect(cx - 1, cy - r + 4, 2, r - 3, GxEPD_BLACK);
    gfx.drawLine(cx, cy - 1, cx + (r * 2 / 3), cy + 2, GxEPD_BLACK);
    gfx.drawLine(cx, cy,     cx + (r * 2 / 3), cy + 3, GxEPD_BLACK);
    gfx.drawLine(cx, cy + 1, cx + (r * 2 / 3), cy + 4, GxEPD_BLACK);
    gfx.fillRect(cx - 2, cy - r - 3, 5, 3, GxEPD_BLACK);
    gfx.fillCircle(cx, cy, 2, GxEPD_BLACK);
}

// 数字フォントで HH:MM を左端 x から左寄せ描画（コロンは fillCircle で2点）。
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

    // --- 日付数字 logisoso46（下段、左端配置） ---
    setFont(u8g2_font_logisoso46_tn);

    char dayBuf[4];
    snprintf(dayBuf, sizeof(dayBuf), "%d", getDay());
    int16_t dx = LEFT_MARGIN - 6;                    // 左ベアリング3px分さらに左へ（視覚的左寄せ）
    u8g2Fonts.setCursor(dx, DAY_BLY);
    u8g2Fonts.print(dayBuf);

    // 「日」は12pxに縮小し縦線の1px手前・ベースライン下端に固定配置
    // （2桁日でも数字と重ならないよう、日幅に依存しない右端固定）
    drawKanji16x16Small(DIVIDER_X - 10, DAY_BLY - 11, KANJI_WEEKDAY[0], 12);
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

// 中央揃えテキスト（unifont日本語）。scale>1 で ScaledGFX により拡大。
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

// UTF-8 先頭バイトから1文字のバイト長を得る。不正バイトは0。
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

    int16_t x = (EP_W - (prefixW + digitsW)) / 2;
    const int16_t dotY = baselineY - dotR;   // ドットは数字の下端寄り

    u8g2Fonts.setCursor(x, baselineY);
    u8g2Fonts.print(prefix);
    x += prefixW;

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

// パネルが物理更新中かどうか。BUSY=HIGHが更新中(B74極性)。
// setupEpaper()前はBUSYピンが未初期化のため「idle」を返す(起動直後の即測定を守る)。
// battery測定の負荷スパイク回避ガード(cycleclock_battery.ino)から使う。
bool epaperIdle() {
    return !s_epaperReady || digitalRead(EPD_BUSY_GPIO) == LOW;
}

// フル画面をページ単位で描画。GxEPD2 の paged-update 定型句の共通化。
// 物理更新中の新規描画はGxEPD2内部のBUSY待ち(_waitWhileBusy)が守る
// (BW版パネルのフル更新は2-3秒でB74のBUSYタイムアウト内に収まる)。
#define DRAW_PAGED(...) \
    g_epaper.setFullWindow(); \
    g_epaper.firstPage(); \
    do { \
        g_epaper.fillScreen(GxEPD_WHITE); \
        __VA_ARGS__; \
    } while (g_epaper.nextPage())

// 時計画面・スリープ画面共用の電池ピクトグラム。画面右下に固定配置(v0.3.5で
// 横型から縦型へ変更・両ビュー同一位置)。縦型は乗車時間を左へオフセットしても
// 時計アイコン+数字+ピクトが右欄176pxに収まるため(横型だと最悪幅で衝突する)。
// 5段階: 3.60-4.10Vを0.1V刻み・<3.60Vは0本の空枠・>=4.00V満枠。バーは下から積み上げ。
// 3.65V未満の警告帯では左に「!」(ePaperは点滅不可のため静的マーク)。
// 描画は毎分の時計更新・スリープ直前の残画に乗る(スリープ画面でも電池量が見える)。
static void drawBatteryPict() {
    const int lvl = batteryLevelPict();
    if (lvl < 0) return;   // 電圧未測定(起動直後の取りこぼし防御)

    const int16_t bx = 222, by = 86, bw = 18, bh = 34;
    // 本体外枠(2px) + 上部端子(下が本体に接する)
    g_epaper.fillRect(bx, by, bw, 2, GxEPD_BLACK);
    g_epaper.fillRect(bx, by + bh - 2, bw, 2, GxEPD_BLACK);
    g_epaper.fillRect(bx, by, 2, bh, GxEPD_BLACK);
    g_epaper.fillRect(bx + bw - 2, by, 2, bh, GxEPD_BLACK);
    g_epaper.fillRect(bx + 6, by - 6, 6, 6, GxEPD_BLACK);
    // 段階バー(内部 x224-238: 5本×5px+1px間隔・下から積み上げ)
    for (int i = 0; i < lvl; i++) {
        g_epaper.fillRect(bx + 2, by + bh - 7 - i * 6, bw - 4, 5, GxEPD_BLACK);
    }
    // 警告「!」(ピクトの左・縦中央)
    if (batteryWarnActive()) {
        g_epaper.fillRect(208, 97, 3, 12, GxEPD_BLACK);
        g_epaper.fillRect(208, 112, 3, 3, GxEPD_BLACK);
    }
}

// 時計画面（常にフル更新）
static void drawEpaperClock() {
    DRAW_PAGED({
        drawDividers();
        drawLeftPanel();
        // 時刻（右寄せ、上段）
        drawClockDigitsRight(g_epaper, EP_W - RIGHT_MARGIN, TIME_BLY, getHours(), getMinutes(),
                             u8g2_font_logisoso62_tn, 30, 4, 11);
        // 乗車時間（右寄せ・右下のピクト分オフセット、下段）
        int rideH, rideM;
        getRideTime(&rideH, &rideM);
        drawClockDigitsRight(g_epaper, RIDE_RIGHT_X, RIDE_BLY, rideH, rideM,
                             u8g2_font_logisoso32_tn, 14, 2, 5);
        // 乗車時間アイコン（時計）
        drawClockIcon(g_epaper, ICON_CX, ICON_CY, ICON_R);
        // 電池ピクトグラム（右下固定）
        drawBatteryPict();
    });
}

// 未同期画面: タイトル+「時刻未同期」+ バージョン。
// 同期成立までの間（アプリが近くにないと数分〜）見続ける画面のため、
// どのファームで動いているか分かるようバージョンも表示する(v0.4.0)。
static void drawEpaperUnsynced() {
    DRAW_PAGED({
        drawCenteredText("CycleClock", 38, 2);    // 実32px相当
        drawCenteredText("時刻未同期", 82, 2);     // 実32px相当
        char vbuf[20];
        snprintf(vbuf, sizeof(vbuf), "ver %d.%d.%d",
                 FIRMWARE_VERSION_MAJOR, FIRMWARE_VERSION_MINOR, FIRMWARE_VERSION_PATCH);
        drawCenteredText(vbuf, 114);              // 16px・下段
    });
}

// 通知の文字数に応じたフォントサイズと拡大倍率の段階設定
// (文字数の昇順で定義。最後は全長文をカバーする大きな値)
struct NotifyFontSetting {
    int maxChars;          // この文字数以下の場合に適用
    const uint8_t* font;   // 使用するフォント(u8g2_font_...)
    int scale;             // 拡大倍率(1〜3)
};
static const NotifyFontSetting NOTIFY_FONT_SETTINGS[] = {
    { 10,  u8g2_font_b16_t_japanese3, 3 },  // 16pxフォント3倍 48px
    { 24,  u8g2_font_b12_t_japanese3, 3 },  // 12pxフォント3倍 36px
    { 26,  u8g2_font_b16_t_japanese3, 2 },  // 16pxフォント2倍 32px
    {999,  u8g2_font_b12_t_japanese3, 2 }
};
#define NUM_NOTIFY_FONT_SETTINGS (sizeof(NOTIFY_FONT_SETTINGS) / sizeof(NOTIFY_FONT_SETTINGS[0]))

// 通知表示: 本文テキストを全画面に自動折返し描画。
// 文字数に応じてフォントサイズを段階切替（NOTIFY_FONT_SETTINGS）。
// アプリ名の常設先頭行は廃止(v0.4.11・本文エリア圧迫で4行目が切れるため)。
// 本文が空の時だけアプリ名を代わりに表示し、両方空なら「通知なし」。
// ※ BLEコールバックとページループが競合しないよう、ページループ前にローカルコピーを
//    取り、ループ内ではそのコピーを使う(paged update は各ページで再描画するため)。
static void drawEpaperNotification(const char* text) {
    static char safeText[NOTIFY_TEXT_LEN];
    strncpy(safeText, text, NOTIFY_TEXT_LEN - 1);
    safeText[NOTIFY_TEXT_LEN - 1] = '\0';

    const char* body = (safeText[0] != '\0') ? safeText : g_notificationApp;

    // 文字数でフォントサイズとスケーリングを判定
    const uint8_t* font = u8g2_font_b10_t_japanese2; // デフォルトフォールバック
    int scale = 1;
    const int n = utf8CharCount(body);
    for (size_t i = 0; i < NUM_NOTIFY_FONT_SETTINGS; i++) {
        if (n <= NOTIFY_FONT_SETTINGS[i].maxChars) {
            font = NOTIFY_FONT_SETTINGS[i].font;
            scale = NOTIFY_FONT_SETTINGS[i].scale;
            break;
        }
    }

    logPrint("NOTIFY", "Drawing: UTF8 chars=%d, scale=%dx", n, scale);

    DRAW_PAGED({
        if (body[0] != '\0') {
            if (scale > 1) {
                ScaledGFX scaledGfx(g_epaper, scale);
                u8g2Fonts.begin(scaledGfx);
                drawWrappedText(0, 0, body, font, EP_W / scale);
                u8g2Fonts.begin(g_epaper);   // 描画先を元に戻す
            } else {
                drawWrappedText(0, 0, body, font, EP_W);
            }
        } else {
            drawCenteredText("通知なし", 76, 2);
        }
    });
}

// 低電圧警告画面: 電池アイコン(残量1目盛) + 「要充電」 + 実測電圧。
// 乗車中は時計に代わって表示し、スリープ時はこの画面を残画とする。
// 電圧表示は BATT_DIV_MULT 校正(テスタ突合)のその場確認にも使える。
static void drawEpaperLowBattery() {
    const float volts = batteryVoltageCached();

    DRAW_PAGED({
        // 電池アイコン(中央上段): 外枠 + 右端子 + 内部1目盛(残量少)
        const int16_t bw = 56, bh = 28;
        const int16_t bx = (EP_W - bw - 6) / 2;   // 端子(6px)込みで中央寄せ
        const int16_t by = 22;
        g_epaper.fillRect(bx, by, bw, 2, GxEPD_BLACK);              // 上辺
        g_epaper.fillRect(bx, by + bh - 2, bw, 2, GxEPD_BLACK);     // 下辺
        g_epaper.fillRect(bx, by, 2, bh, GxEPD_BLACK);              // 左辺
        g_epaper.fillRect(bx + bw - 2, by, 2, bh, GxEPD_BLACK);     // 右辺
        g_epaper.fillRect(bx + bw, by + 9, 6, 10, GxEPD_BLACK);     // 端子
        g_epaper.fillRect(bx + 5, by + 5, 9, bh - 10, GxEPD_BLACK); // 残量1目盛

        drawCenteredText("要充電", 96, 2);                          // 16px×2=32px
        if (volts >= 0.0f) {
            char vbuf[10];
            snprintf(vbuf, sizeof(vbuf), "%.2fV", (double)volts);
            drawCenteredText(vbuf, EP_H - 4);
        }
    });
    logPrint("EPAPER", "Low battery view drawn (%.2fV)", (double)volts);
}

// 乗車開始時刻を逆算して HH:MM を得る（時刻同期後のみ有効・詳細ビュー専用）。
// 起動(振動ウェイク)=乗車開始なので 現在時刻−経過時間 が開始時刻。
// 開始が前日にまたがる場合は当日の0時以降へ折り返す(前日付は返さない)。
// ※ 走行区間(開始〜終了)の表示は drawEpaperDetailLarge の24時超え表記
//    (bikeclock_esp32と同一仕様)を使う。ここは単独の「開始」行用。
static void getRideStart(int* startH, int* startM) {
    int eh = getHours(), em = getMinutes();
    unsigned long rideMin = (millis() - g_startupMillis) / 60000UL;
    int startMin = (eh * 60 + em) - (int)rideMin;
    while (startMin < 0) startMin += 24 * 60;
    *startH = startMin / 60;
    *startM = startMin % 60;
}

// 詳細ビュー(モード3・bikeclock_esp32のdrawEpaperDetail相当):
// 開始/経過/現在日時/電池電圧。スナップショット(モード切替時に1回のみ描画)。
// bikeclock_esp32のHIDキー設定行の代わりに、cycleclock固有のバッテリー電圧を
// 表示する(ソーラー運用のその場確認用・右下にピクトも併記)。
static void drawEpaperDetail() {
    const float volts = batteryVoltageCached();

    DRAW_PAGED({
        setFont(u8g2_font_unifont_t_japanese3);

        char buf[48];
        const int16_t x = 4;
        const int16_t lh = 17;
        int16_t y = 14;

        int sh, sm;
        getRideStart(&sh, &sm);
        snprintf(buf, sizeof(buf), "開始 %02d:%02d", sh, sm);
        u8g2Fonts.setCursor(x, y);
        u8g2Fonts.print(buf);
        y += lh;

        int rh, rm;
        getRideTime(&rh, &rm);
        snprintf(buf, sizeof(buf), "経過 %d時間%02d分", rh, rm);
        u8g2Fonts.setCursor(x, y);
        u8g2Fonts.print(buf);
        y += lh;

        snprintf(buf, sizeof(buf), "現在 %04d/%02d/%02d %s %02d:%02d",
                 getYear(), getMonth(), getDay(), WEEKDAY_JP[getWeekday()],
                 getHours(), getMinutes());
        u8g2Fonts.setCursor(x, y);
        u8g2Fonts.print(buf);
        y += lh + 4;

        snprintf(buf, sizeof(buf), "電池 %.2fV", (double)volts);
        u8g2Fonts.setCursor(x, y);
        u8g2Fonts.print(buf);
        y += lh;

        // v0.4.11: 運用統計(誤起動=BT未接続のままスタンバイに入った累積回数・
        // フラッシュ永続化。v0.4.15でD0振動検知カウントは廃止・振動は起動専用)
        snprintf(buf, sizeof(buf), "誤起動%lu回", (unsigned long)g_falseWakeCount);
        u8g2Fonts.setCursor(x, y);
        u8g2Fonts.print(buf);

        drawBatteryPict();   // 右下固定(時計/詳細大と共通)
    });
}

// 詳細大ビュー(モード4・bikeclock_esp32のdrawEpaperDetailLarge相当):
// 日付+曜日 / 経過(時計アイコン+HH:MM) / 開始〜現在 を2倍拡大で3行。
// スリープ残画もこの関数で描く(同一内容・右下にピクト)。スナップショット。
static void drawEpaperDetailLarge() {
    const int scale = 2;

    // 終了時刻(現在)と乗車時間(起床からのmillis)から開始時刻を逆算。
    // 日をまたいだ場合は終端に24h超え表記(例: "22:00〜26:30") —
    // bikeclock_esp32 と同一仕様(v0.4.4: omp整理による折り返し表記への
    // 勝手な仕様変更を原状回復)
    int eh = getHours(), em = getMinutes();
    unsigned long rideSec = (millis() - g_startupMillis) / 1000UL;
    int endMin = eh * 60 + em;
    int startMin = endMin - (int)(rideSec / 60);
    if (endMin < startMin) endMin += 24 * 60;   // 日をまたいだ → 24時間超え表記
    while (startMin < 0) startMin += 24 * 60;   // 逆算で0時を割った場合の防御
    const int sh = startMin / 60, sm = startMin % 60;
    const int fh = endMin / 60, fm = endMin % 60;
    const int rh = (int)(rideSec / 3600), rm = (int)((rideSec / 60) % 60);

    DRAW_PAGED({
        ScaledGFX scaledGfx(g_epaper, scale);
        u8g2Fonts.begin(scaledGfx);
        setFont(u8g2_font_b16_t_japanese3);

        int16_t y = 10;          // ①行ベースライン(仮想座標)
        char buf[40];

        // ① 日付+曜日
        snprintf(buf, sizeof(buf), "%04d/%02d/%02d %s",
                 getYear(), getMonth(), getDay(), WEEKDAY_JP[getWeekday()]);
        u8g2Fonts.setCursor(3, y);
        u8g2Fonts.print(buf);
        y += 28;                 // → ②乗車時間行

        // ② 乗車時間: 時計アイコン + HH:MM (logisoso20=実40px相当)
        const int16_t iconR = 7;
        int16_t iconCx = 3 + iconR + 1;
        int16_t iconCy = y - 7;
        drawClockIcon(scaledGfx, iconCx, iconCy, iconR);
        drawHHMM(scaledGfx, iconCx + iconR + 3, y, rh, rm,
                 u8g2_font_logisoso20_tn, 8, 2, 3);
        y += 15;                 // → ③走行区間行

        // ③ 走行区間 (開始〜終了)
        snprintf(buf, sizeof(buf), "%02d:%02d〜%02d:%02d", sh, sm, fh, fm);
        setFont(u8g2_font_b16_t_japanese3);  // ②行(drawHHMM)で変わったフォントを戻す
        u8g2Fonts.setCursor(3, y);
        u8g2Fonts.print(buf);

        u8g2Fonts.begin(g_epaper);   // 描画先を元に戻す

        // 電池ピクトグラム(時計と同じ右下固定位置)。System OFF中もゼロ電力で
        // 保持されるため「寝ている間の電池量」が翌日まで見える
        drawBatteryPict();
    });
}

// スリープ画面: 停止時点のスナップショット。System OFF直前に1回だけ描画され、
// ゼロ電力で保持され続ける。通常の時計表示のままだと停車中に「今の時刻」と
// 勘違いされるため、日付+走行区間という明らかに時計でない形式にする
// (bikeclock_esp32 の詳細大表示と同一思想・実体は drawEpaperDetailLarge)。
// 未同期のまま寝る場合は「時刻未同期」画面を維持(スナップショット不能)。
// 低電圧時はスナップショットより「要充電」を優先して残画化する(駐輪〜翌日も
// 警告が見え続けるのが低電圧通知の主目的・充電忘れ防止)。
void drawEpaperSleep() {
    if (batteryLowActive()) {
        if (!ep_showingLowBatt) {
            drawEpaperLowBattery();
            ep_showingLowBatt = true;
        }
        return;
    }
    if (!g_timeSynced) {
        logPrint("EPAPER", "Sleep view skipped (time not synced)");
        return;
    }

    unsigned long rideSec = (millis() - g_startupMillis) / 1000UL;
    int sh, sm;
    getRideStart(&sh, &sm);
    logPrint("EPAPER", "Sleep view: ride %02d:%02d-%02d:%02d (%d min)",
             sh, sm, getHours(), getMinutes(), (int)(rideSec / 60));

    drawEpaperDetailLarge();
}

// ブートスプラッシュ（タイトル + バージョン巨大表示）
static void drawEpaperSplash() {
    DRAW_PAGED({
        drawCenteredText("CycleClock", 20);                       // 上段: タイトル
        drawVersionBig(u8g2_font_logisoso62_tn, EP_H - 28, 4);   // 中央: バージョン番号(62px)
        drawCenteredText("XIAO nRF52840", EP_H - 4);              // 下段: プラットフォーム
    });
}

// ====================================================================
// 公開API
// ====================================================================

void setupEpaper() {
    // --- ePaper電源ON(v0.4.10・電源制御ピン D1=HIGH) ---
    // SPI/ePaper初期化より先に電源を入れる。電源のないモジュールへのSPI通信は
    // BUSY不定で誤動作するため順序は厳守(cycleclock_diag 2026-10-06 の教訓)
    pinMode(EPD_POWER_GPIO, OUTPUT);
    digitalWrite(EPD_POWER_GPIO, HIGH);
    delay(EPD_POWER_STABLE_MS);

    // SPIピンをユーザー指定の物理配線へ割当(nRF52840はPSELで任意GPIO可)。
    // setPins は begin() より前に呼ぶこと(beginが保持ピンで初期化する)。
    // MISOはePaperが未使用。D1(EPD_POWER_GPIO)をPSEL上のMISOに指定したまま
    // begin後にGPIO出力で上書きする(SPIMの送信のみで受信は使わない・diag実績)
    SPI.setPins(EPD_POWER_GPIO, EPD_SPI_SCK_GPIO, EPD_SPI_MOSI_GPIO);
    SPI.begin();
    pinMode(EPD_POWER_GPIO, OUTPUT);       // SPI.beginの構成をGPIO出力で上書き
    digitalWrite(EPD_POWER_GPIO, HIGH);    // ePaper給電を維持
    // init: 第1引数を0にしてライブラリ内部の Serial 出力を停止
    g_epaper.init(0, true, 2, false);
    g_epaper.setRotation(1);  // 横長 250x122（v0.4.5: 旧rotation=3から180度回転）

    u8g2Fonts.begin(g_epaper);

    logPrint("EPAPER", "Init OK (CS=%d DC=%d RST=%d BUSY=%d, SCK=%d MOSI=%d) power@D%d",
             EPD_CS_GPIO, EPD_DC_GPIO, EPD_RST_GPIO, EPD_BUSY_GPIO,
             EPD_SPI_SCK_GPIO, EPD_SPI_MOSI_GPIO, EPD_POWER_GPIO);

    s_epaperReady = true;

    drawEpaperSplash();
}

// --- パネルをdeep sleepへ(v0.4.10) ---
// フル更新後のパネルはpower off止まりで、deep sleep(0x10)は hibernate() の
// 呼び出し時だけ送られる(GxEPD2_213_B74::hibernate())。System OFF前の
// リーク対策の第一段。次回の描画は GxEPD2 が _hibernating フラグで
// reset→再initして復帰する(起床=コールドスタートで setupEpaper() が走る)
void epaperHibernate() {
    if (!s_epaperReady) return;
    g_epaper.hibernate();
    logPrint("EPAPER", "Panel deep sleep (hibernate)");
}

// loop から毎回呼ばれる。表示すべき内容が変わった時だけ描画。
//   - 非TIMEモード → 通知(モード2)/詳細(モード3)/詳細大(モード4)
//                    (FUNCキーで切替・10秒で自動復帰)
//   - 通知活性中 → 通知ビューを1回だけ描画。分更新を抑制し、
//                  タイムアウトで通知を終了して下位ビューへ強制復帰
//   - 低電圧     → 「要充電」ビュー(通知の次を優先・3.6V以上で解除)
//   - 未同期 → 「時刻未同期」+バージョン固定
//   - 同期済 → 分/日が変わるか初回に時計を毎分フル更新
void updateEpaperDisplay() {
    // === FUNCキーで切替えた表示モードの自動復帰 ===
    // 10秒で時計へ戻る(bikeclock_esp32と同一)。
    // (旧v0.4.14までは振動でも時計へ戻していたが、v0.4.15で振動は起動専用に
    //  なったため FUNC キー操作と10秒自動復帰のみ)
    if (g_displayMode != DISPLAY_MODE_TIME &&
        g_currentMillis - g_lastModeChangeMillis >= MODE_AUTO_RETURN_MS) {
        logPrint("MODE", "Auto return to clock");
        g_displayMode = DISPLAY_MODE_TIME;
    }

    // === 非TIMEモードの描画(bikeclock_esp32と同じ4モード構成) ===
    // モード2(通知)は新着受信(g_notificationSeq)で内容を更新する。
    // モード3(詳細)/モード4(詳細大)はスナップショット1回描き。
    if (g_displayMode != DISPLAY_MODE_TIME) {
        const bool stale =
            ep_drawnMode != g_displayMode ||
            (g_displayMode == DISPLAY_MODE_NOTIFICATION &&
             ep_drawnNotifySeq != g_notificationSeq);
        if (stale) {
            switch (g_displayMode) {
                case DISPLAY_MODE_NOTIFICATION:
                    drawEpaperNotification(g_notificationText);  // 空テキストは「通知なし」
                    ep_drawnNotifySeq = g_notificationSeq;
                    logPrint("MODE", "Notification view drawn (seq %lu)",
                             (unsigned long)g_notificationSeq);
                    break;
                case DISPLAY_MODE_DETAIL:
                    drawEpaperDetail();
                    logPrint("MODE", "Detail view drawn");
                    break;
                default:  // DISPLAY_MODE_DETAIL_LARGE
                    drawEpaperDetailLarge();
                    logPrint("MODE", "Detail-large view drawn");
                    break;
            }
            ep_drawnMode = g_displayMode;
        }
        return;
    }

    // === TIMEモード(モードからの復帰直後は全ビューを強制再描画) ===
    if (ep_drawnMode != DISPLAY_MODE_TIME) {
        ep_drawnMode = DISPLAY_MODE_TIME;
        invalidateViewCache();
    }

    // === 通知表示の自動切替・自動復帰(bikeclock_esp32と同一構造) ===
    if (g_notificationActive && g_currentMillis >= g_notificationEndTime) {
        logPrint("NOTIFY", "Timeout - returning to clock");
        g_notificationActive = false;
        invalidateViewCache();   // 時計を強制再描画(低電圧時は下の分岐で要充電画面へ復帰)
    }

    if (g_notificationActive) {
        if (!ep_showingNotification) {
            drawEpaperNotification(g_notificationText);
            ep_showingNotification = true;
        }
        return;   // 通知表示中は分変化による時計更新を抑制
    }
    ep_showingNotification = false;

    // === 低電圧警告(通知の次を優先・60秒毎の電圧測定ラッチに従う) ===
    // 発報中は時計の代わりに「要充電」を表示し続ける。ソーラー充電等で
    // 3.6V以上へ回復したら解除し、キャッシュ無効化済みなので時計へ即復帰する。
    if (batteryLowActive()) {
        if (!ep_showingLowBatt) {
            invalidateViewCache();   // 解除後の時計即再描画用
            drawEpaperLowBattery();
            ep_showingLowBatt = true;
        }
        return;
    }
    ep_showingLowBatt = false;

    if (!g_timeSynced) {
        if (!ep_showingUnsynced) {
            drawEpaperUnsynced();
            ep_showingUnsynced = true;
        }
        return;
    }

    if (ep_showingUnsynced) {
        // 未同期→同期の遷移: キャッシュを無効化して即時描画
        invalidateViewCache();
    }

    const int h = getHours();
    const int m = getMinutes();
    const int d = getDay();
    if (h != ep_lastHr || m != ep_lastMin || d != ep_lastDay) {
        drawEpaperClock();
        ep_lastHr  = (int8_t)h;
        ep_lastMin = (int8_t)m;
        ep_lastDay = (int8_t)d;
    }
}
