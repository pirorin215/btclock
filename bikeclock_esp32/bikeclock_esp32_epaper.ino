/**
 * BikeClock ESP32-S3 - ePaper 表示処理（昼間視認性用）
 *
 * TM1637 7セグLEDは昼間の太陽光下で視認性が悪いため、
 * WeAct 2.13" ePaper(BW, SSD1680) を追加して併用する。
 *
 * 表示内容: 時刻(大) + 曜日(月火…) + 日付(○月○日)  [日本語はU8g2]
 * 更新戦略:
 *   - 分が変化 → 時刻帯のみ部分更新（約0.3s、低フリッカ）
 *   - 日が変化 / 初回同期 / 15分ごと → 全画面フル更新（ゴースティング対策）
 *   - 未同期 → 「時刻未同期」固定表示
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
static bool    ep_lastSynced    = false;
static bool    ep_showingUnsync = false;
static uint8_t ep_partialCount  = 0;   // 部分更新の連続回数（一定でフル更新しゴースト除去）

// === 画面ジオメトリ（rotation=1 で 250x122 横長） ===
static const int16_t EP_W = 250;
static const int16_t EP_H = 122;
static const int16_t TIME_BASELINE_Y = 60;   // 時刻数字のベースライン
static const int16_t TIME_BAND_Y     = 8;    // 部分更新ウィンドウ上端
static const int16_t TIME_BAND_H     = 70;   // 部分更新ウィンドウ高さ
static const int16_t INFO_BASELINE_Y = 106;  // 曜日/日付のベースライン

// 曜日（0=日 ... 6=土）
static const char* WEEKDAY_JP[] = {"日", "月", "火", "水", "木", "金", "土"};

// ゴースティング対策: この回数の部分更新ごとに1回フル更新
static const uint8_t PARTIAL_FULL_REFRESH_EVERY = 15;

// ====================================================================
// 描画ヘルパ
// ====================================================================

// 大きな時刻数字を描画（コロンのドット2点は矩形で手描画、_tnフォントはコロン非収録のため）
static void drawTimeDigits(int16_t centerX, int16_t baselineY, int hours, int minutes) {
    u8g2Fonts.setFont(u8g2_font_logisoso46_tn);
    u8g2Fonts.setFontMode(1);  // 1=透過（背景は自前で塗る）
    u8g2Fonts.setForegroundColor(GxEPD_BLACK);
    u8g2Fonts.setBackgroundColor(GxEPD_WHITE);

    char buf[4];
    snprintf(buf, sizeof(buf), "%02d", hours);
    int hhW = u8g2Fonts.getUTF8Width(buf);
    snprintf(buf, sizeof(buf), "%02d", minutes);
    int mmW = u8g2Fonts.getUTF8Width(buf);

    const int16_t gap = 22;  // HH と MM の間（コロンの幅）
    const int16_t totalW = hhW + gap + mmW;
    const int16_t x = centerX - totalW / 2;

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
    g_epaper.fillCircle(colX, midY - 8, 3, GxEPD_BLACK);
    g_epaper.fillCircle(colX, midY + 8, 3, GxEPD_BLACK);
}

// 曜日 + 日付（日本語）を中央揃えで描画
static void drawWeekdayDate(int16_t baselineY) {
    u8g2Fonts.setFont(u8g2_font_unifont_t_japanese3);
    u8g2Fonts.setFontMode(1);
    u8g2Fonts.setForegroundColor(GxEPD_BLACK);
    u8g2Fonts.setBackgroundColor(GxEPD_WHITE);

    char line[48];
    snprintf(line, sizeof(line), "%s曜日    %d月%d日",
             WEEKDAY_JP[getWeekday()], getMonth(), getDay());

    int w = u8g2Fonts.getUTF8Width(line);
    u8g2Fonts.setCursor((EP_W - w) / 2, baselineY);
    u8g2Fonts.print(line);
}

// 中央揃えテキスト（unifont日本語、指定色）
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

// 全画面フル更新（時刻+曜日+日付）
static void drawEpaperClockFull() {
    g_epaper.setFullWindow();
    g_epaper.firstPage();
    do {
        g_epaper.fillScreen(GxEPD_WHITE);
        drawTimeDigits(EP_W / 2, TIME_BASELINE_Y, getHours(), getMinutes());
        drawWeekdayDate(INFO_BASELINE_Y);
    } while (g_epaper.nextPage());
}

// 時刻帯のみ部分更新（高速・低フリッカ）。曜日/日付帯は更新しない。
static void drawEpaperTimePartial() {
    g_epaper.setPartialWindow(0, TIME_BAND_Y, EP_W, TIME_BAND_H);
    g_epaper.firstPage();
    do {
        g_epaper.fillRect(0, TIME_BAND_Y, EP_W, TIME_BAND_H, GxEPD_WHITE);
        drawTimeDigits(EP_W / 2, TIME_BASELINE_Y, getHours(), getMinutes());
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

    const bool justSynced   = !ep_lastSynced;
    const bool dayChanged   = (d != ep_lastDay);
    const bool timeChanged  = (h != ep_lastHr) || (m != ep_lastMin);
    const bool periodicClear = (ep_partialCount >= PARTIAL_FULL_REFRESH_EVERY);

    // 何も変わっていなければ描画スキップ
    if (!justSynced && !dayChanged && !timeChanged) {
        return;
    }

    if (justSynced || dayChanged || periodicClear) {
        // 全画面フル更新（初回/日替わり/ゴースト除去）
        drawEpaperClockFull();
        ep_partialCount = 0;
    } else {
        // 分変化のみ → 時刻帯の部分更新
        drawEpaperTimePartial();
        ep_partialCount++;
    }

    ep_lastSynced = true;
    ep_lastHr  = (int8_t)h;
    ep_lastMin = (int8_t)m;
    ep_lastDay = (int8_t)d;
}
