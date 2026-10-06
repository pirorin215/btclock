/**
 * cycleclock_diag v2.2d - ePaper連続更新+EN(D1)監視
 *
 * v0.4.10 本番で発見した「スプラッシュ(1回目のフル更新)は成功するが
 * 2回目以降のフル更新がすべて Busy Timeout で失敗する」問題の切り分け。
 * 疑い: SPI の MISO PSEL に D1(=TPS22810 EN)を渡したまま運用する構成で、
 * SPI トランザクション間に SPIM がピン構成を操作して D1 の HIGH が失われ
 * EN が瞬断→ePaper が断電リセットされる。
 *
 * 動作:
 *   起動 → D1=HIGH(TPS22810 ON)→2s→ePaper初期化
 *        → 5秒ごとに再表示(カウンタ表示)×4回 = 連続フル更新の再現
 *        → 表示の合間も1秒ごとに D1 readback をログ(落ちた瞬間を捉える)
 *        → 4回表示したら TPS OFF せず HOLD(ログ観察継続)
 *
 * 見方:
 *   - 表示2回目以降で [EPD] update#N timeout が出る → 本番と同じ症状の再現
 *   - D1 readback=0 の行が出る → EN 瞬断説の確定(→ v0.4.11 で MISO 切断)
 *   - D1 が 1 のまま timeout → 別原因(電流能力・パネル側)
 */

#include <Arduino.h>
#include <Adafruit_TinyUSB.h>
#include <SPI.h>
#include <GxEPD2_BW.h>

#define EPD_CS_GPIO    D4
#define EPD_DC_GPIO    D8
#define EPD_RST_GPIO   D10
#define EPD_BUSY_GPIO  D9
#define EPD_SCK_GPIO   D7
#define EPD_MOSI_GPIO  D5
#define EPD_POWER_GPIO D1

#define EPD_POWER_STABLE_MS 2000UL

static GxEPD2_BW<GxEPD2_213_B74, GxEPD2_213_B74::HEIGHT> g_epaper(
    GxEPD2_213_B74(EPD_CS_GPIO, EPD_DC_GPIO, EPD_RST_GPIO, EPD_BUSY_GPIO));

static unsigned long g_bootMs = 0;
static void dlog(const char* format, ...) {
    char buf[200];
    va_list args;
    va_start(args, format);
    vsnprintf(buf, sizeof(buf), format, args);
    va_end(args);
    Serial.printf("[%5lu ms] %s\n", millis() - g_bootMs, buf);
}

// 表示更新。成功/失敗を返す(Busy Timeoutの検出=display完了後にBUSYピンが
// まだHIGHなら失敗扱い。GxEPD2内部のタイムアウトはSerialに"Busy Timeout!"を
// 出すだけで例外にはならないため、ここでBUSY状態を直接確認)
static void showCounter(int n) {
    dlog("[EPD] update#%d start (D1=%d BUSY=%d)", n,
         digitalRead(EPD_POWER_GPIO), digitalRead(EPD_BUSY_GPIO));
    unsigned long t0 = millis();
    g_epaper.setFullWindow();
    g_epaper.firstPage();
    do {
        g_epaper.fillScreen(GxEPD_WHITE);
        g_epaper.setTextColor(GxEPD_BLACK);
        g_epaper.setTextSize(3);
        g_epaper.setCursor(20, 30);
        g_epaper.printf("ECHO %d", n);
        g_epaper.setTextSize(1);
        g_epaper.setCursor(20, 80);
        g_epaper.print("continuous update test");
    } while (g_epaper.nextPage());
    dlog("[EPD] update#%d done in %lu ms (D1=%d BUSY=%d)", n, millis() - t0,
         digitalRead(EPD_POWER_GPIO), digitalRead(EPD_BUSY_GPIO));
}

void setup() {
    Serial.begin(115200);
    delay(2500);   // USB CDC列挙待ち
    g_bootMs = millis();

    pinMode(LED_RED, OUTPUT);
    pinMode(LED_GREEN, OUTPUT);
    pinMode(LED_BLUE, OUTPUT);
    digitalWrite(LED_RED, HIGH);
    digitalWrite(LED_GREEN, HIGH);
    digitalWrite(LED_BLUE, HIGH);
    digitalWrite(LED_RED, LOW);   // 赤点灯=動作中

    dlog("=== cycleclock_diag v2.2d (multi-update + D1 monitor) ===");
    dlog(__DATE__ " " __TIME__);

    pinMode(EPD_POWER_GPIO, OUTPUT);
    digitalWrite(EPD_POWER_GPIO, HIGH);
    dlog("TPS22810 EN=HIGH (D1 readback=%d)", digitalRead(EPD_POWER_GPIO));
    delay(EPD_POWER_STABLE_MS);

    SPI.setPins(EPD_POWER_GPIO, EPD_SCK_GPIO, EPD_MOSI_GPIO);
    SPI.begin();
    pinMode(EPD_POWER_GPIO, OUTPUT);       // begin後のGPIO上書き(diag実績)
    digitalWrite(EPD_POWER_GPIO, HIGH);
    dlog("[EPD] SPI started + D1 re-driven (readback=%d)", digitalRead(EPD_POWER_GPIO));
    g_epaper.init(0, true, 2, false);
    g_epaper.setRotation(1);
    dlog("[EPD] init done (D1=%d)", digitalRead(EPD_POWER_GPIO));

    // 連続フル更新×4(5秒間隔)+合間のD1監視(1秒間隔)
    for (int n = 1; n <= 4; n++) {
        showCounter(n);
        for (int s = 0; s < 5; s++) {
            delay(1000);
            dlog("[MON] idle D1=%d BUSY=%d", digitalRead(EPD_POWER_GPIO), digitalRead(EPD_BUSY_GPIO));
        }
    }

    dlog("=== test done - HOLDING (power stays ON) ===");
}

void loop() {
    static uint32_t n = 0;
    if (++n % 10 == 0) {
        dlog("[MON] hold D1=%d BUSY=%d", digitalRead(EPD_POWER_GPIO), digitalRead(EPD_BUSY_GPIO));
    }
    delay(100);
}
