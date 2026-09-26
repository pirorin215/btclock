/**
 * CycleClock - ePaper 表示処理
 *
 * bikeclock_esp32_epaper.ino (v2.0.61) から XIAO BLE (nRF52840) へ移植。
 * 変更点:
 * - 専用 SPI3_HOST バス + selectSPI → XIAO のデフォルト SPI (D8/D10)
 * - スプラッシュのプラットフォーム表記を nRF52840 に変更
 * - 通知/詳細/OTA/QR ビューを削除(時計・未同期・スプラッシュの3ビューのみ)
 *
 * 表示レイアウト（250x122 横長, rotation=3）:
 *
 *        ┌────────┬─────────────────────┐
 *        │   月   │      １ ２ ： ３ ４  │  時刻(logisoso62, 右寄せ)
 *        │ (曜日) │═════════════════════│  ← 横線
 *        │   15   │      ０ ０ ： １ ５  │  乗車時間(logisoso32, 右寄せ)
 *        │ (日付) │                     │
 *        └────────┴─────────────────────┘
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

// === 表示状態（前回描画内容のキャッシュで無駄な更新を省く） ===
static int8_t ep_lastHr  = -1;
static int8_t ep_lastMin = -1;
static int8_t ep_lastDay = -1;
static bool   ep_showingUnsynced = false;

// === 画面ジオメトリ（rotation=3 で 250x122 横長） ===
static const int16_t EP_W = 250;
static const int16_t EP_H = 122;
static const int16_t DIVIDER_X    = 63;   // 縦線のx（左欄幅=63px）
static const int16_t DIVIDER_Y    = 75;   // 横線のy（時刻/乗車時間の境界）
static const int16_t LINE_W       = 3;    // 区切り線の太さ
static const int16_t LEFT_MARGIN  = 4;    // 左欄文字の左端マージン
static const int16_t RIGHT_MARGIN = 8;    // 右欄の右端マージン
static const int16_t TIME_BLY     = 62;   // 時刻(logisoso62)のベースライン（上段中央）
static const int16_t RIDE_BLY     = 119;  // 乗車時間(logisoso32)のベースライン（下段中央）
static const int16_t ICON_CX      = 136;  // 乗車時間アイコン(時計)の中心x
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

// 描画前にパネルが実際にidle(BUSY解除)になるのを待つ。
// 現状の3色パネルはフル更新がGxEPD2_213_B74のBUSYタイムアウト(10秒固定)より
// 長く、_waitWhileBusyは10秒で諦めて制御を返した後もパネルは物理更新を続けて
// いる。その間に送ったページデータ/リフレッシュコマンドはコントローラに
// 無視される(実害: 分更新直後にスリープ画面を描こうとして消える)。通常の
// 60秒間隔の更新では待ちは発生しない(即idle)。
// B74のBUSY極性はHIGH=更新中。上限は3色パネルのフル更新最悪値を想定した30秒。
static void waitPanelIdle() {
    if (digitalRead(EPD_BUSY_GPIO) == LOW) return;  // 即idle・無待ち
    uint32_t t0 = millis();
    logPrint("EPAPER", "Panel busy - waiting before draw...");
    while (digitalRead(EPD_BUSY_GPIO) == HIGH) {
        if (millis() - t0 > EPD_BUSY_GUARD_TIMEOUT_MS) {
            logPrint("EPAPER", "Panel still busy after %d ms - drawing anyway",
                     (int)EPD_BUSY_GUARD_TIMEOUT_MS);
            break;
        }
        delay(10);
    }
    delay(20);  // BUSY解除直後のコマンド受付マージン
}

// フル画面をページ単位で描画。GxEPD2 の paged-update 定型句の共通化。
#define DRAW_PAGED(...) \
    waitPanelIdle(); \
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
        // 乗車時間アイコン（時計）
        drawClockIcon(g_epaper, ICON_CX, ICON_CY, ICON_R);
    });
}

// 未同期画面
static void drawEpaperUnsynced() {
    DRAW_PAGED({
        drawCenteredText("CycleClock", 54, 2);       // 実32px相当（中央上寄り）
        drawCenteredText("時刻未同期", 98, 2);        // 実32px相当（中央下寄り）
    });
}

// スリープ画面: 停止時点のスナップショット。System OFF直前に1回だけ描画され、
// ゼロ電力で保持され続ける。通常の時計表示のままだと停車中に「今の時刻」と
// 勘違いされるため、日付+走行区間という明らかに時計でない形式にする
// (bikeclock_esp32 の詳細大表示と同一思想)。
//   ① 日付+曜日           2026/09/26 土
//   ② 乗車時間(時計アイコン)    １２：３４
//   ③ 走行区間             10:00〜12:34  (日跨ぎは終端が24時超え表記)
// 未同期のまま寝る場合は「時刻未同期」画面を維持(スナップショット不能)。
void drawEpaperSleep() {
    if (!g_timeSynced) {
        logPrint("EPAPER", "Sleep view skipped (time not synced)");
        return;
    }

    // 終了時刻(現在)と乗車時間(起床からのmillis)から開始時刻を逆算
    int eh = getHours(), em = getMinutes();
    unsigned long rideSec = (millis() - g_startupMillis) / 1000UL;
    int endMin = eh * 60 + em;
    int startMin = endMin - (int)(rideSec / 60);
    if (endMin < startMin) endMin += 24 * 60;   // 日をまたいだ → 24時間超え表記
    while (startMin < 0) startMin += 24 * 60;   // 逆算で0時を割った場合の防御
    const int sh = startMin / 60, sm = startMin % 60;
    const int fh = endMin / 60, fm = endMin % 60;
    const int rh = (int)(rideSec / 3600), rm = (int)((rideSec / 60) % 60);

    logPrint("EPAPER", "Sleep view: ride %d:%02d-%02d:%02d (%d min)",
             sh, sm, fh, fm, (int)(rideSec / 60));

    const int scale = 2;

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
    });
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
    // SPIピンをユーザー指定の物理配線へ割当(nRF52840はPSELで任意GPIO可)。
    // setPins は begin() より前に呼ぶこと(beginが保持ピンで初期化する)。
    // MISOはePaperが未使用だがAPI上必要なため空きピンD1をダミー割当。
    SPI.setPins(EPD_SPI_MISO_GPIO, EPD_SPI_SCK_GPIO, EPD_SPI_MOSI_GPIO);
    SPI.begin();
    // ダミーMISOピンの浮き入力を確定させる(System OFF時の入力バッファ漏れ対策)
    pinMode(EPD_SPI_MISO_GPIO, INPUT_PULLDOWN);
    // init: 第1引数を0にしてライブラリ内部の Serial 出力を停止
    g_epaper.init(0, true, 2, false);
    g_epaper.setRotation(3);  // 横長 250x122

    u8g2Fonts.begin(g_epaper);

    logPrint("EPAPER", "Init OK (CS=%d DC=%d RST=%d BUSY=%d, SCK=%d MOSI=%d)",
             EPD_CS_GPIO, EPD_DC_GPIO, EPD_RST_GPIO, EPD_BUSY_GPIO,
             EPD_SPI_SCK_GPIO, EPD_SPI_MOSI_GPIO);

    drawEpaperSplash();
}

// loop から毎回呼ばれる。表示すべき内容が変わった時だけ描画。
//   - 未同期 → 「時刻未同期」固定
//   - 同期済 → 分/日が変わるか初回に時計を毎分フル更新
void updateEpaperDisplay() {
    if (!g_timeSynced) {
        if (!ep_showingUnsynced) {
            drawEpaperUnsynced();
            ep_showingUnsynced = true;
        }
        return;
    }

    if (ep_showingUnsynced) {
        // 未同期→同期の遷移: キャッシュを無効化して即時描画
        ep_lastHr = -1;
        ep_lastMin = -1;
        ep_lastDay = -1;
        ep_showingUnsynced = false;
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
