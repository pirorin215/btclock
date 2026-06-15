/**
 * BikeClock ESP32-S3 - ePaper 表示処理（昼間視認性用）
 *
 * TM1637 7セグLEDは昼間の太陽光下で視認性が悪いため、
 * WeAct 2.13" ePaper(BW, SSD1680) を追加して併用する。
 *
 * 表示レイアウト（250x122 横長, rotation=1）:
 *
 *        ┌────────────────────────────────┐
 *        │                                │
 *        │         １ ２ ： ３ ４          │  時刻(logisoso62_tn, 62px)
 *        │                                │
 *        │   １５ 月          ００：１５   │  下段(32px統一): 日付+曜日(左) / 乗車時間(右)
 *        └────────────────────────────────┘
 *
 *   - 時刻        : 現在時刻 HH:MM（logisoso62_tn 62px）
 *   - 日付+曜日   : getDay()(数字32px) + 曜日漢字(32px)  例: "15 月" = 15日・月曜
 *                   ※漢字は GNU Unifont 16x16 ビットマップ(KANJI_WEEKDAY)を2倍拡大描画。
 *                     U8g2収録の大きいフォントは漢字非対応のため、自前ビットマップで実現。
 *   - 乗車時間    : 電源ONからの経過(millisベース) HH:MM（logisoso32_tn 32px）
 *                   ※時刻同期で時刻がジャンプしても継続＝実際の乗車時間
 *
 * 更新戦略:
 *   - 分が変化 → 全画面部分更新（高速・低フリッカ。時刻帯も下段も同時更新）
 *   - 日が変化 / 初回同期 / 15分ごと → 全画面フル更新（ゴースティング対策）
 *   - 未同期 → 「時刻未同期」固定表示（乗車時間も非表示）
 *
 * ハードウェア:
 *   - 専用 SPI3_HOST バス（SPIClass epdSPI(HSPI)）。MCP23S17(Phase3)の既定SPI2と完全分離。
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
static int8_t  ep_lastHr        = -1;
static int8_t  ep_lastMin       = -1;
static int8_t  ep_lastDay       = -1;
static int8_t  ep_lastRideMin   = -1;   // 乗車時間の分（変化検出用）
static bool    ep_lastSynced    = false;
static bool    ep_showingUnsync = false;
static uint8_t ep_partialCount  = 0;   // 部分更新の連続回数（一定でフル更新しゴースト除去）

// === 画面ジオメトリ（rotation=1 で 250x122 横長） ===
static const int16_t EP_W = 250;
static const int16_t EP_H = 122;
static const int16_t TIME_BASELINE_Y = 66;    // 時刻(logisoso62)のベースライン
static const int16_t INFO_BASELINE_Y = 116;   // 下段(logisoso32)のベースライン
static const int16_t MARGIN_X        = 10;    // 下段の左右マージン

// 曜日（0=日 ... 6=土）
static const char* WEEKDAY_JP[] = {"日", "月", "火", "水", "木", "金", "土"};

// ゴースティング対策: この回数の部分更新ごとに1回フル更新
static const uint8_t PARTIAL_FULL_REFRESH_EVERY = 15;

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

// 数字のみフォント(_tn)で HH MM を「中央揃え」描画。
// コロンは数字フォントに非収録のため fillCircle で2点を手描画。
static void drawClockDigitsCenter(int16_t centerX, int16_t baselineY,
                                  int hours, int minutes,
                                  const uint8_t* font, int16_t gap,
                                  int16_t dotR, int16_t dotOff) {
    u8g2Fonts.setFont(font);
    u8g2Fonts.setFontMode(1);  // 1=透過（背景は自前で塗る）
    u8g2Fonts.setForegroundColor(GxEPD_BLACK);
    u8g2Fonts.setBackgroundColor(GxEPD_WHITE);

    char buf[4];
    snprintf(buf, sizeof(buf), "%02d", hours);
    int hhW = u8g2Fonts.getUTF8Width(buf);
    snprintf(buf, sizeof(buf), "%02d", minutes);
    int mmW = u8g2Fonts.getUTF8Width(buf);

    int16_t totalW = hhW + gap + mmW;
    int16_t x = centerX - totalW / 2;

    snprintf(buf, sizeof(buf), "%02d", hours);
    u8g2Fonts.setCursor(x, baselineY);
    u8g2Fonts.print(buf);
    snprintf(buf, sizeof(buf), "%02d", minutes);
    u8g2Fonts.setCursor(x + hhW + gap, baselineY);
    u8g2Fonts.print(buf);

    // コロン: 数字の上下中央に2つのドット
    int16_t asc = u8g2Fonts.getFontAscent();
    int16_t midY = baselineY - asc / 2;
    int16_t colX = x + hhW + gap / 2;
    g_epaper.fillCircle(colX, midY - dotOff, dotR, GxEPD_BLACK);
    g_epaper.fillCircle(colX, midY + dotOff, dotR, GxEPD_BLACK);
}

// 数字のみフォント(_tn)で HH MM を「右寄せ」描画（乗車時間用）。
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

// 下段描画: 左「<日付> <曜日漢字>」(例: 15 月) / 右「乗車時間 HH:MM」(例: 00:15)
static void drawInfoLine(int16_t baselineY) {
    int rideH, rideM;
    getRideTime(&rideH, &rideM);

    // --- 左: 「15 月」 ---
    // 日付の数字を logisoso32_tn(32px) で描画
    u8g2Fonts.setFont(u8g2_font_logisoso32_tn);
    u8g2Fonts.setFontMode(1);
    u8g2Fonts.setForegroundColor(GxEPD_BLACK);
    u8g2Fonts.setBackgroundColor(GxEPD_WHITE);

    char dayBuf[4];
    snprintf(dayBuf, sizeof(dayBuf), "%d", getDay());
    int dayW = u8g2Fonts.getUTF8Width(dayBuf);
    int16_t asc = u8g2Fonts.getFontAscent();  // 漢字の垂直位置合わせ用

    u8g2Fonts.setCursor(MARGIN_X, baselineY);
    u8g2Fonts.print(dayBuf);

    // 曜日漢字を32px(2倍)で描画。数字の上端に合わせる。
    drawKanji16x16(MARGIN_X + dayW + 4, baselineY - asc,
                   KANJI_WEEKDAY[getWeekday()], 2);

    // --- 右: 乗車時間 HH:MM（logisoso32_tn、コロン手描画） ---
    drawClockDigitsRight(EP_W - MARGIN_X, baselineY, rideH, rideM,
                         u8g2_font_logisoso32_tn, 14, 2, 5);
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

// ====================================================================
// 画面描画（フル / 部分 / 未同期 / スプラッシュ）
// ====================================================================

// 全画面フル更新（時刻 + 下段）。初回/日替わり/ゴースト除去用。
static void drawEpaperClockFull() {
    g_epaper.setFullWindow();
    g_epaper.firstPage();
    do {
        g_epaper.fillScreen(GxEPD_WHITE);
        drawClockDigitsCenter(EP_W / 2, TIME_BASELINE_Y, getHours(), getMinutes(),
                              u8g2_font_logisoso62_tn, 30, 4, 11);
        drawInfoLine(INFO_BASELINE_Y);
    } while (g_epaper.nextPage());
}

// 全画面部分更新（高速・低フリッカ）。分変化時に時刻帯も下段も同時更新。
static void drawEpaperClockPartial() {
    g_epaper.setPartialWindow(0, 0, EP_W, EP_H);
    g_epaper.firstPage();
    do {
        g_epaper.fillRect(0, 0, EP_W, EP_H, GxEPD_WHITE);
        drawClockDigitsCenter(EP_W / 2, TIME_BASELINE_Y, getHours(), getMinutes(),
                              u8g2_font_logisoso62_tn, 30, 4, 11);
        drawInfoLine(INFO_BASELINE_Y);
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

// ブートスプラッシュ（タイトル + バージョン）
static void drawEpaperSplash() {
    g_epaper.setFullWindow();
    g_epaper.firstPage();
    do {
        g_epaper.fillScreen(GxEPD_WHITE);
        drawCenteredText("BikeClock", EP_H / 2 - 14);
        char ver[24];
        snprintf(ver, sizeof(ver), "v%d.%d.%d (ESP32-S3)",
                 FIRMWARE_VERSION_MAJOR, FIRMWARE_VERSION_MINOR, FIRMWARE_VERSION_PATCH);
        drawCenteredText(ver, EP_H / 2 + 14);
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
    // init: initial=true で全画面クリア(白)。reset_duration=2ms（サンプル準拠）
    g_epaper.init(115200, true, 2, false);
    g_epaper.setRotation(1);  // 横長 250x122

    u8g2Fonts.begin(g_epaper);

    logPrint("EPAPER", "Init OK (CS=%d DC=%d RST=%d BUSY=%d SCK=%d MOSI=%d, SPI3_HOST)",
             EPD_CS_GPIO, EPD_DC_GPIO, EPD_RST_GPIO, EPD_BUSY_GPIO,
             EPD_SPI_SCK_GPIO, EPD_SPI_MOSI_GPIO);
    logPrint("EPAPER", "Partial update: %s",
             g_epaper.epd2.hasFastPartialUpdate ? "YES" : "NO");

    drawEpaperSplash();
}

// loop から毎回呼ばれる。内容が変わった時だけ実際の描画を行う（通常は何もしない）。
void updateEpaperDisplay() {
    if (!g_timeSynced) {
        if (!ep_showingUnsync) {
            drawEpaperUnsynced();
            ep_showingUnsync = true;
            ep_lastSynced = false;  // 次回同期時にフル更新させる
        }
        return;
    }

    // 同期済み
    const int h = getHours();
    const int m = getMinutes();
    const int d = getDay();
    int rideH, rideM;
    getRideTime(&rideH, &rideM);

    const bool justSynced    = !ep_lastSynced;
    const bool dayChanged    = (d != ep_lastDay);
    const bool timeChanged   = (h != ep_lastHr) || (m != ep_lastMin);
    const bool rideChanged   = (rideM != ep_lastRideMin);
    const bool periodicClear = (ep_partialCount >= PARTIAL_FULL_REFRESH_EVERY);

    // 何も変わっていなければ描画スキップ（時刻の分 or 乗車時間の分 が変わった時のみ）
    if (!justSynced && !dayChanged && !timeChanged && !rideChanged) {
        return;
    }

    if (justSynced || dayChanged || periodicClear) {
        // 全画面フル更新（初回/日替わり/ゴースト除去）
        drawEpaperClockFull();
        ep_partialCount = 0;
    } else {
        // 分変化のみ → 全画面部分更新
        drawEpaperClockPartial();
        ep_partialCount++;
    }

    ep_lastSynced  = true;
    ep_lastHr      = (int8_t)h;
    ep_lastMin     = (int8_t)m;
    ep_lastDay     = (int8_t)d;
    ep_lastRideMin = (int8_t)rideM;
}
