/**
 * cycleclock_vibetest - 振動ウェイク感度調整用テストファームウェア
 *
 * フィールド試験(2026-10-05)で本番ファーム(cycleclock v0.4.9)の振動ウェイクが
 * ほとんど反応しなかったため、2系統のwake源を調整・比較するためのスケッチ。
 * 本番ファーム(cycleclock/)には一切触れない。
 *
 * [モード1] SW-18020P(機械式振動スイッチ) — D0導通
 *   センサーを本体から外出しして設置位置・向き・固定方法を調整する。
 *   検出経路は本番(v0.4.8以降)と同じ GPIOTE 割り込みで、1ms 級の瞬間導通を
 *   ハードラッチで拾う。「vibetest で拾えない素子・設置は本番でも拾えない」
 *   という切り分け基準に使える。
 *
 * [モード2] GY-BMI160(6軸IMU・部品台帳 seed001 ×3) — any-motion割り込み
 *   MEMS加速度の any-motion 検出で D0 に INT1 を落とす。しきい値・duration を
 *   シリアルから即時変更して実測調整できる(Bosch公式ドライバのレジスタ定義に
 *   準拠・bikeclock_esp32 のレジスタ直叩き実績コードの教訓を流用)。
 *   I2C モジュールが D4/D5 に無ければ自動でモード1へフォールバック。
 *
 * 共通動作:
 *   D0 の LOW 検出(導通 or INT1)でオンボード緑LED点灯・LOWが解けてから
 *   LED_HOLD_MS 経過で消灯(連続振動中は点きっぱなし)。フル輝度直接駆動。
 *   Serial(115200): [VIB] 300ms窓のパルス数+導通幅、[HB] 5秒ハートビート。
 *
 * 非搭載: BLE / ePaper / System OFF スリープ(調整中に寝られないため意図して省略)。
 * 電池駆動で放置すると電池を消耗するので、調整時は USB 駆動を推奨。
 *
 * 配線(テスト基板・どちらか一方を D0 へ):
 *   SW-18020P : D0 — GND(他端)
 *   GY-BMI160 : VCC→3V3, GND→GND, SDA→D3, SCL→D6, SDO→GND(0x68),
 *               INT1→D0 (オープンドレイン active low・D0内蔵プルアップでLOW検出)
 *   ※I2Cは本番統合と同一配線(D3=SDA/D6=SCL)。本番は ePaper が D4(CS)/D5(MOSI)/
 *     D7-D10 を使用し空きパッドが D3/D6 のみのため、Wire.setPins() で D3/D6 に
 *     割り当てる(vibetest で先に検証→本番へそのまま転用)。
 *     なお XIAO BLE(nRF52840) variant 既定の I2C ピンは D4/D5 で、D4/D5 は本番では
 *     ePaper CS/MOSI に衝突する。bikeclock_esp32 の IMU_SDA=5/SCL=4 は ESP32-S3 の
 *     ピン番号体系なので混同しないこと。
 *   ※I2C アドレスは SDO→GND で 0x68(bikeclock_esp32 実績・開放なら 0x69 も probe)。
 *     D4/D5 は本番 cycleclock では ePaper の CS/MOSI。テスト基板は ePaper 非接続前提。
 *
 * シリアルコマンド(BMI160モード時):
 *   t<0-255>  any-motion threshold を即時変更(例: "t20\n")・反映後レジスタ再読出しで確認
 *   d<0-3>    any-motion duration を即時変更
 *   r         レジスタ状態+加速度生値(±2g で 16384 LSB/g)を1回ダンプ
 *   h         コマンドヘルプ
 */

#include <Arduino.h>
// USB CDC(Serial)の実体はAdafruit TinyUSB Libraryにある。本番(cycleclock)は
// bluefruit.hの依存検出で間接的にリンクされるが、本スケッチはBLEを使わないため
// 明示的にincludeする(これが無いとリンカでSerial未定義になる)
#include <Adafruit_TinyUSB.h>
#include <Wire.h>

// --- 基本設定(調整時はここをいじる) ---
#define VIB_SW_GPIO          D0        // wake源入力(SW-18020P導通 or BMI160 INT1)。本番と同じD0
#define LED_HOLD_MS          1000UL    // LOWが解けてからLEDを消すまで
#define VIB_LOG_INTERVAL_MS  300UL     // パルス統計ログの間隔(連続振動での氾濫防止)
#define VIB_HEARTBEAT_MS     5000UL    // ハートビート間隔

// --- BMI160 any-motion 初期値(シリアルコマンドで実測調整可) ---
// threshold: INT_MOTION_1(0x60) へ書く生値(実効ビット幅は実測で確認・スイープ調整対象)
// duration:  INT_MOTION_0(0x5F) 下位2bit
#define BMI_THR_DEFAULT   0x14
#define BMI_DUR_DEFAULT   0

// --- BMI160 I2C ピン(本番統合と同一配線) ---
// 本番 cycleclock は ePaper で D4(CS)/D5(MOSI)/D7-D10 を使用・空きパッドは D3/D6 のみ。
// nRF52 の TWIM PSEL は任意 GPIO 可・この core の Wire は setPins() を持つため
// D3/D6 に割り当てる(vibetest でこの配線のまま検証→本番へそのまま転用)
#define BMI_WIRE_SDA_GPIO  D3    // P0.29
#define BMI_WIRE_SCL_GPIO  D6    // P1.11

// --- BMI160 レジスタ(Bosch公式 BMI160_driver の bmi160_defs.h / bmi160.c 準拠) ---
#define BMI_CHIPID        0x00   // 期待値 0xD1
#define BMI_PMU_STATUS    0x03
#define BMI_ACC_DATA      0x12   // acc x,y,z 連続6B
#define BMI_ACC_CONF      0x40
#define BMI_ACC_RANGE     0x41
#define BMI_INT_ENABLE_0  0x50   // bit2:0 = any-motion x,y/z 有効
#define BMI_INT_OUT_CTRL  0x53   // bit3=INT1出力有効, bit2=INT1オープンドレイン, bit1=INT1 active level
#define BMI_INT_LATCH     0x54
#define BMI_INT_MAP_0     0x55   // bit2(INT1_SLOPE_MASK)=any-motion→INT1
#define BMI_INT_MOTION_0  0x5F   // 下位2bit=any-motion duration
#define BMI_INT_MOTION_1  0x60   // any-motion threshold
#define BMI_CMD           0x7E
#define BMI_CHIPID_VAL    0xD1
#define BMI_ACC_LSB_PER_G 16384.0f  // ±2g(ACC_RANGE=0x03)

// PMU_STATUS bits[5:4]=加速度電源状態(00=suspend 01=normal 10=low_power)
#define BMI_PMU_ACC_MASK        0x30
#define BMI_PMU_ACC_LOWPOWER    0x20

// --- ISR → loop 伝達(全てvolatile) ---
static volatile bool     s_fallPending = false;   // LOW開始(未処理)
static volatile uint32_t s_fallUs = 0;            // LOW開始時刻(LOW中のみ非0)
static volatile uint32_t s_lastPulseUs = 0;       // 最終LOW幅(参考値・ISRで測定)
static volatile uint32_t s_pulseCount = 0;        // 累計LOWパルス数(loopのみ加算)

// LOW開始(HIGH→LOW)とLOW終了(LOW→HIGH)の両エッジを拾い、幅を測る。
// SW-18020Pの1ms級導通もBMI160のINT1パルスも同一経路で扱える。
static void vibISR() {
    uint32_t now = micros();
    if (digitalRead(VIB_SW_GPIO) == LOW) {
        s_fallUs = now;
        s_fallPending = true;   // loopでLED延長・統計反映
    } else if (s_fallUs != 0) {
        s_lastPulseUs = now - s_fallUs;
        s_fallUs = 0;
    }
}

// ====================================================================
// I2C ヘルパ(BMI160・bikeclock_esp32_imu.ino の教訓を流用:
// CMD処理完了待ちを飛ばすと次のCMDが無視されERR_REGが立つ)
// ====================================================================
static uint8_t s_bmiAddr = 0;   // 0=BMI160無し(SW-18020Pモード)

static bool bmiWriteReg(uint8_t reg, uint8_t val) {
    Wire.beginTransmission(s_bmiAddr);
    Wire.write(reg);
    Wire.write(val);
    return (Wire.endTransmission() == 0);
}

static uint8_t bmiReadReg(uint8_t reg) {
    Wire.beginTransmission(s_bmiAddr);
    Wire.write(reg);
    Wire.endTransmission(false);   // repeated start
    Wire.requestFrom((int)s_bmiAddr, 1);
    if (Wire.available()) return Wire.read();
    return 0;
}

static bool bmiReadRegs(uint8_t reg, uint8_t* buf, uint8_t len) {
    Wire.beginTransmission(s_bmiAddr);
    Wire.write(reg);
    if (Wire.endTransmission(false) != 0) return false;
    Wire.requestFrom((int)s_bmiAddr, (int)len);
    for (uint8_t i = 0; i < len; i++) {
        if (!Wire.available()) return false;
        buf[i] = Wire.read();
    }
    return true;
}

// PMU_STATUS の指定ビットが指定値になるまでポーリング(CMD処理完了待ち)
static bool bmiWaitPmu(uint8_t mask, uint8_t val, uint32_t timeoutMs) {
    const uint32_t start = millis();
    while ((millis() - start) < timeoutMs) {
        if ((bmiReadReg(BMI_PMU_STATUS) & mask) == val) return true;
        delay(2);
    }
    return false;
}

// ====================================================================
// setupBMI160 — any-motion INT1 設定(接続無しは false でフォールバック)
// ====================================================================
bool setupBMI160() {
    Wire.setPins(BMI_WIRE_SDA_GPIO, BMI_WIRE_SCL_GPIO);
    Wire.begin();
    Wire.setClock(400000);
    delay(50);

    // SDO=GND→0x68(bikeclock_esp32実績)・開放→0x69。両アドレスをprobe
    const uint8_t addrs[2] = { 0x68, 0x69 };
    for (int i = 0; i < 2; i++) {
        s_bmiAddr = addrs[i];
        if (bmiReadReg(BMI_CHIPID) == BMI_CHIPID_VAL) break;
        s_bmiAddr = 0;
    }
    if (s_bmiAddr == 0) {
        Wire.end();
        return false;
    }
        Serial.printf("[IMU] BMI160 detected @ 0x%02X (SDA=D3 SCL=D6 INT1=D0)\n", s_bmiAddr);

    // ソフトリセット(全センサSUSPENDに戻る)
    bmiWriteReg(BMI_CMD, 0xB6);
    delay(50);

    // 加速度を低電力モードへ(any-motion判定はODR 25Hzのサンプルで行われる)
    bmiWriteReg(BMI_CMD, 0x12);
    delay(2);
    if (!bmiWaitPmu(BMI_PMU_ACC_MASK, BMI_PMU_ACC_LOWPOWER, 120)) {
        Serial.printf("[IMU] WARN: ACC low-power failed (pmu=0x%02X)\n", bmiReadReg(BMI_PMU_STATUS));
    }
    // ジャイロはSUSPENDのまま(wake検知に不要)

    bmiWriteReg(BMI_ACC_RANGE, 0x03);   // ±2g → 16384 LSB/g
    bmiWriteReg(BMI_ACC_CONF,  0x26);   // ODR=25Hz(0x06) + bwp=平均フィルタ(low-power時の解釈)

    // INT1: オープンドレイン+active low → D0内蔵プルアップで「導通LOW」と同一回路モデル。
    // 0x53 = 出力有効(0x08) | オープンドレイン(0x04) | active low(bit1=0)
    bmiWriteReg(BMI_INT_OUT_CTRL, 0x0C);
    bmiWriteReg(BMI_INT_LATCH, 0x00);   // non-latched(INT幅はODR周期相当=1ms問題の心配なし)
    bmiWriteReg(BMI_INT_MAP_0, 0x04);   // any-motion(slope)→INT1
    bmiWriteReg(BMI_INT_ENABLE_0, 0x07); // any-motion x/y/z 有効
    bmiWriteReg(BMI_INT_MOTION_0, BMI_DUR_DEFAULT);
    bmiWriteReg(BMI_INT_MOTION_1, BMI_THR_DEFAULT);

    delay(5);
    Serial.printf("[IMU] any-motion ready: thr=0x%02X dur=%d (t/d コマンドで実測調整)\n",
                  bmiReadReg(BMI_INT_MOTION_1), bmiReadReg(BMI_INT_MOTION_0) & 0x03);
    return true;
}

// ====================================================================
// シリアルコマンド処理(しきい値・duration の実測調整+レジスタダンプ)
// ====================================================================
static void bmiSetThreshold(uint8_t thr) {
    if (!s_bmiAddr) return;
    bmiWriteReg(BMI_INT_MOTION_1, thr);
    Serial.printf("[IMU] threshold -> 0x%02X (readback 0x%02X)\n", thr, bmiReadReg(BMI_INT_MOTION_1));
}

static void bmiSetDuration(uint8_t dur) {
    if (!s_bmiAddr) return;
    uint8_t cur = bmiReadReg(BMI_INT_MOTION_0) & ~0x03;
    bmiWriteReg(BMI_INT_MOTION_0, cur | (dur & 0x03));
    Serial.printf("[IMU] duration -> %d (readback %d)\n", dur, bmiReadReg(BMI_INT_MOTION_0) & 0x03);
}

static void bmiDump() {
    if (!s_bmiAddr) {
        Serial.println("[IMU] BMI160 not connected (SW-18020P mode)");
        return;
    }
    Serial.printf("[IMU] reg: pmu=0x%02X conf=0x%02X range=0x%02X intEn=0x%02X outCtrl=0x%02X "
                  "map=0x%02X thr=0x%02X dur=%d\n",
                  bmiReadReg(BMI_PMU_STATUS), bmiReadReg(BMI_ACC_CONF), bmiReadReg(BMI_ACC_RANGE),
                  bmiReadReg(BMI_INT_ENABLE_0), bmiReadReg(BMI_INT_OUT_CTRL),
                  bmiReadReg(BMI_INT_MAP_0), bmiReadReg(BMI_INT_MOTION_1),
                  bmiReadReg(BMI_INT_MOTION_0) & 0x03);
    uint8_t buf[6];
    if (bmiReadRegs(BMI_ACC_DATA, buf, 6)) {
        float ax = (int16_t)((buf[1] << 8) | buf[0]) / BMI_ACC_LSB_PER_G;
        float ay = (int16_t)((buf[3] << 8) | buf[2]) / BMI_ACC_LSB_PER_G;
        float az = (int16_t)((buf[5] << 8) | buf[4]) / BMI_ACC_LSB_PER_G;
        Serial.printf("[IMU] acc[g] x=%+.3f y=%+.3f z=%+.3f\n", ax, ay, az);
    }
}

static void processSerialCommand() {
    static char line[32];
    static uint8_t len = 0;
    while (Serial.available()) {
        char c = (char)Serial.read();
        if (c == '\n' || c == '\r') {
            if (len == 0) continue;
            line[len] = '\0';
            len = 0;
            if (line[0] == 't') {
                int v = atoi(line + 1);
                if (v >= 0 && v <= 255) bmiSetThreshold((uint8_t)v);
            } else if (line[0] == 'd') {
                int v = atoi(line + 1);
                if (v >= 0 && v <= 3) bmiSetDuration((uint8_t)v);
            } else if (line[0] == 'r') {
                bmiDump();
            } else if (line[0] == 'h') {
                Serial.println("[CMD] t<0-255>=threshold  d<0-3>=duration  r=dump  h=help");
            }
            continue;
        }
        if (len < sizeof(line) - 1) {
            line[len++] = c;
        } else {
            len = 0;   // 溢れは破棄
        }
    }
}

// ====================================================================
// setup / loop
// ====================================================================
void setup() {
    Serial.begin(115200);

    // 緑LED消灯(common anode: HIGH=消灯)
    pinMode(LED_GREEN, OUTPUT);
    digitalWrite(LED_GREEN, HIGH);

    bool bmi = setupBMI160();
    pinMode(VIB_SW_GPIO, INPUT_PULLUP);
    attachInterrupt(digitalPinToInterrupt(VIB_SW_GPIO), vibISR, CHANGE);

    // プルアップ安定待ち(直後の配線チャージで仮パルスを拾わないため)
    delay(300);

    Serial.println();
    Serial.println("[INIT] cycleclock_vibetest " __DATE__ " " __TIME__);
    Serial.printf("[INIT] mode: %s. D0 LOW -> LED on +1s. Serial 115200 (h=help)\n",
                  bmi ? "BMI160 any-motion" : "SW-18020P (BMI160 not found)");
}

void loop() {
    uint32_t now = millis();

    // --- LOW開始の処理(カウントとLED延長) ---
    static uint32_t s_lastEventMs = 0;
    if (s_fallPending) {
        s_fallPending = false;
        s_pulseCount++;
        s_lastEventMs = now;
    }

    // --- LED: LOW中 or 最終パルスからLED_HOLD_MS以内で点灯 ---
    bool ledOn = (digitalRead(VIB_SW_GPIO) == LOW) ||
                 (now - s_lastEventMs < LED_HOLD_MS);
    static bool s_ledOn = false;
    if (ledOn != s_ledOn) {
        s_ledOn = ledOn;
        digitalWrite(LED_GREEN, ledOn ? LOW : HIGH);  // common anode
    }

    // --- パルス統計ログ(300ms窓・窓内パルスが無ければ出さない) ---
    static uint32_t s_lastLogMs = 0;
    static uint32_t s_reportedCount = 0;
    if (now - s_lastLogMs >= VIB_LOG_INTERVAL_MS) {
        s_lastLogMs = now;
        uint32_t total = s_pulseCount;
        uint32_t lastWidth = s_lastPulseUs;
        uint32_t delta = total - s_reportedCount;
        s_reportedCount = total;
        if (delta > 0) {
            if (lastWidth > 0) {
                Serial.printf("[VIB] +%lu pulses (total=%lu, last width=%lu us)\n",
                              (unsigned long)delta, (unsigned long)total,
                              (unsigned long)lastWidth);
            } else {
                Serial.printf("[VIB] +%lu pulses (total=%lu)\n",
                              (unsigned long)delta, (unsigned long)total);
            }
        }
    }

    // --- ハートビート(生存確認・窓ログが静かでも累計はわかる) ---
    static uint32_t s_lastHbMs = 0;
    if (now - s_lastHbMs >= VIB_HEARTBEAT_MS) {
        s_lastHbMs = now;
        Serial.printf("[HB] alive, total=%lu pulses\n", (unsigned long)s_pulseCount);
    }

    processSerialCommand();
    delay(5);   // LED消灯判定の応答性優先で短め
}
