/**
 * WS2812B accent LED for CycleClock (v0.4.16〜)
 *
 * ePaper上部の透明テープを照らす装飾用 WS2812B ×1。DIN=D6・VDDはXIAO 3V3
 * から分岐(System OFF中も通電のため、DINは10kΩプルダウンでLOW固定)。
 *
 * 動作:
 * - BLE接続中: 常亮色(STRIP_IDLE_*・シリアルのledコマンドで調整可)の常亮
 *   (寿命モデル=乗っている/見ている間だけ光る)
 * - 起動直後: 白点滅(setupからledStripFlash・配線とはんだの確認用)
 * - 通知受信: STRIP_NOTIFY_* 点滅 / 低電圧: STRIP_LOWBATT_* 点滅(立上がりエッジ)
 * - System OFF 前: ledStripPowerDown()で消灯ラッチを送る
 *
 * シリアル調整コマンド(v0.4.18・調整段階専用・普段遣いは想定しない):
 *   led R G B       常亮色を即変更(0-255・接続なしでもプレビュー点灯)
 *   ledsave         現在の常亮色をInternalFSへ保存(再起動後も有効)
 *   leddefault      保存を消してSTRIP_IDLE_*の既定値へ戻す
 *   ledflash R G B  点滅演出をプレビュー
 *   ledinfo         現在値と保存状態を表示
 * コマンド受信のたびにスリープ判定を延長する(BLE未接続の調整作業が1分で
 * System OFFに潰されないため。入力がないまま1分経つと通常どおり寝る)
 *
 * 送信は Adafruit_NeoPixel(nRF52はNRF_PWM+EasyDMA実装・SoftDevice割込でも
 * ビット時序が壊れない)。演出はloop駆動の非ブロッキング状態機のため
 * setup()/loop()を拘束しない。
 */

#include "cycleclock.h"

static Adafruit_NeoPixel s_strip(STRIP_COUNT, STRIP_GPIO, NEO_GRB + NEO_KHZ800);
static uint8_t s_idleR = STRIP_IDLE_R;  // 常亮色(シリアルledコマンドで変更・保存可)
static uint8_t s_idleG = STRIP_IDLE_G;
static uint8_t s_idleB = STRIP_IDLE_B;
static bool s_serialPreview = false;    // ledコマンドの強制プレビュー点灯
static bool s_idleOn = false;       // 常亮の現在値(接続中 or プレビュー)
static bool s_notified = false;     // 前回のg_notificationActive(立上がり検出用)
static bool s_lowBatt = false;      // 前回のbatteryLowActive()(立上がり検出用)
static bool s_flashActive = false;  // 点滅演出進行中(常亮に優先・完了後に復帰)
static uint8_t s_flashR, s_flashG, s_flashB;
static uint8_t s_flashLeft;         // 残り点灯回数
static bool s_flashOn = false;
static unsigned long s_flashLastMs = 0;

// --- InternalFS保存(/fwake.bin と同じLittleFS・固定3バイト上書き) ---
#define LEDSTRIP_FILE "/ledstrip.bin"

// 保存済み常亮色の読み出し(ファイルを開け3バイト読めればtrue)。
// setupLedStripの復元とledinfoの報告で共用する。
static bool ledSavedColor(uint8_t* rgb) {
    using namespace Adafruit_LittleFS_Namespace;
    File f = InternalFS.open(LEDSTRIP_FILE, FILE_O_READ);
    if (!f) return false;
    bool ok = (f.read(rgb, 3) == 3);
    f.close();
    return ok;
}

// --- シリアル1行バッファ ---
static char s_cmdBuf[64];
static int s_cmdLen = 0;

// 1フレーム送信(即時・DMA完了待ち含め約0.1ms)。演出/常亮の変化時のみ呼ぶ。
static void stripSet(uint8_t r, uint8_t g, uint8_t b) {
    s_strip.setPixelColor(0, s_strip.Color(r, g, b));
    s_strip.show();
}

static void stripSetIdle() {
    if (s_idleOn) {
        stripSet(s_idleR, s_idleG, s_idleB);
    } else {
        stripSet(0, 0, 0);
    }
}

// ePaper電源が上がる前にDINをLOWに確定させるため、setupEpaper()より
// 先に呼ぶこと(VDD上電瞬間のDIN浮きによる誤点灯防止)。
// InternalFSはsetupStats()が先にbegin()済み(この順序はsetup()が保証する)。
void setupLedStrip() {
    pinMode(STRIP_GPIO, OUTPUT);
    digitalWrite(STRIP_GPIO, LOW);
    s_strip.begin();
    s_strip.show();   // 全消灯ラッチ(VDD未通電でも定義動作)

    // 保存済みの常亮色があれば既定値の代わりに復元(調整結果の反映)
    uint8_t rgb[3];
    if (ledSavedColor(rgb)) {
        s_idleR = rgb[0];
        s_idleG = rgb[1];
        s_idleB = rgb[2];
        logPrint("LED", "Restored idle color %d,%d,%d", s_idleR, s_idleG, s_idleB);
    }
}

// 点滅演出の開始(非ブロッキング・進行はupdateLedStrip())。times=0は無視。
void ledStripFlash(uint8_t r, uint8_t g, uint8_t b, uint8_t times) {
    if (times == 0) return;
    s_flashActive = true;
    s_flashR = r;
    s_flashG = g;
    s_flashB = b;
    s_flashLeft = times;
    s_flashOn = true;
    s_flashLastMs = millis();
    stripSet(r, g, b);
}

// System OFF直前: 消灯ラッチを送る。LEDはラッチした消灯状態を保持する。
void ledStripPowerDown() {
    s_flashActive = false;
    s_idleOn = false;
    stripSet(0, 0, 0);
}

// loopから毎回呼ぶ。演出トリガのエッジ検出と演出進行・常亮の反映。
void updateLedStrip() {
    // 通知表示の立上がりで点滅(30秒表示中は1回だけ)
    if (g_notificationActive && !s_notified) {
        ledStripFlash(STRIP_NOTIFY_R, STRIP_NOTIFY_G, STRIP_NOTIFY_B, STRIP_FLASH_TIMES);
    }
    s_notified = g_notificationActive;

    // 低電圧警告の立上がりで点滅(充電で解除されるまで再トリガしない)
    bool lowBatt = batteryLowActive();
    if (lowBatt && !s_lowBatt) {
        ledStripFlash(STRIP_LOWBATT_R, STRIP_LOWBATT_G, STRIP_LOWBATT_B, STRIP_FLASH_TIMES);
    }
    s_lowBatt = lowBatt;

    // 常亮は「BLE接続中 or シリアルプレビュー」。演出中の切替は保留し
    // 演出完了時に反映される
    if ((g_deviceConnected || s_serialPreview) != s_idleOn) {
        s_idleOn = (g_deviceConnected || s_serialPreview);
        if (!s_flashActive) stripSetIdle();
    }

    // 点滅演出の進行(on/offをSTRIP_FLASH_MS間隔で反転・残回数で完了判定)
    if (s_flashActive && millis() - s_flashLastMs >= STRIP_FLASH_MS) {
        s_flashLastMs = millis();
        if (s_flashOn) {
            s_flashOn = false;
            s_flashLeft--;
            stripSet(0, 0, 0);
        } else if (s_flashLeft > 0) {
            s_flashOn = true;
            stripSet(s_flashR, s_flashG, s_flashB);
        } else {
            s_flashActive = false;
            stripSetIdle();
        }
    }
}

// --- シリアル調整コマンド処理 ---

static void processLedStripCommand(const char* line) {
    int r = 0, g = 0, b = 0;

    if (sscanf(line, "led %d %d %d", &r, &g, &b) == 3) {
        s_idleR = constrain(r, 0, 255);
        s_idleG = constrain(g, 0, 255);
        s_idleB = constrain(b, 0, 255);
        s_serialPreview = true;   // 接続なしでも色を確認できるように強制点灯
        s_flashActive = false;    // 演出中でも常亮色の確認を優先
        s_idleOn = true;
        stripSetIdle();
        logPrint("LED", "Idle color %d,%d,%d (preview - 'ledsave' to keep)",
                 s_idleR, s_idleG, s_idleB);
        return;
    }

    if (strcmp(line, "ledsave") == 0) {
        using namespace Adafruit_LittleFS_Namespace;
        File f = InternalFS.open(LEDSTRIP_FILE, FILE_O_WRITE);
        if (f) {
            uint8_t rgb[3] = {s_idleR, s_idleG, s_idleB};
            f.seek(0);
            f.write(rgb, sizeof(rgb));
            f.close();
            logPrint("LED", "Saved idle color %d,%d,%d (survives reboot)",
                     s_idleR, s_idleG, s_idleB);
        } else {
            logPrint("LED", "WARN: cannot open %s for write", LEDSTRIP_FILE);
        }
        return;
    }

    if (strcmp(line, "leddefault") == 0) {
        InternalFS.remove(LEDSTRIP_FILE);
        s_idleR = STRIP_IDLE_R;
        s_idleG = STRIP_IDLE_G;
        s_idleB = STRIP_IDLE_B;
        s_serialPreview = true;
        s_flashActive = false;
        s_idleOn = true;
        stripSetIdle();
        logPrint("LED", "Back to default %d,%d,%d (save removed)",
                 s_idleR, s_idleG, s_idleB);
        return;
    }

    if (sscanf(line, "ledflash %d %d %d", &r, &g, &b) == 3) {
        ledStripFlash(constrain(r, 0, 255), constrain(g, 0, 255),
                      constrain(b, 0, 255), STRIP_FLASH_TIMES);
        logPrint("LED", "Flash preview %d,%d,%d x%d", constrain(r, 0, 255),
                 constrain(g, 0, 255), constrain(b, 0, 255), STRIP_FLASH_TIMES);
        return;
    }

    if (strcmp(line, "ledinfo") == 0) {
        uint8_t saved[3] = {0, 0, 0};
        bool hasSaved = ledSavedColor(saved);
        logPrint("LED", "idle=%d,%d,%d saved=%s(%d,%d,%d) preview=%s connected=%d",
                 s_idleR, s_idleG, s_idleB,
                 hasSaved ? "yes" : "no", saved[0], saved[1], saved[2],
                 s_serialPreview ? "on" : "off",
                 g_deviceConnected ? 1 : 0);
        return;
    }

    if (line[0] != '\0') {
        logPrint("LED", "Unknown: %s (led/ledsave/leddefault/ledflash/ledinfo)", line);
    }
}

// loopから毎回呼ぶ。シリアルから1行を受け取り調整コマンドとして処理する。
// 受信のたびにスリープ判定の基点を延長(BLE未接続の調整作業をSystem OFFから守る)。
void handleLedStripSerial() {
    while (Serial.available() > 0) {
        char c = (char)Serial.read();
        if (c == '\r') continue;
        g_sleepTimerStartMs = g_currentMillis;   // 調整作業中=寝ない
        if (c == '\n') {
            s_cmdBuf[s_cmdLen] = '\0';
            processLedStripCommand(s_cmdBuf);
            s_cmdLen = 0;
            continue;
        }
        if (s_cmdLen < (int)sizeof(s_cmdBuf) - 1) {
            s_cmdBuf[s_cmdLen++] = c;
        }
    }
}
