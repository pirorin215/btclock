/**
 * BikeClock ESP32-S3 - BMI160 IMU（Phase 14-A: センサ導入＋生値ダンプ）
 *
 * 両脚スタンド（駐車）検知用の 6軸 IMU（GY-BMI160）を I2C で読む。
 * **Wire.h レジスタ直接制御**（外部ライブラリ不要・ESP32 core 標準）。
 *
 *   setupIMU() : Wire.begin + BMI160 初期化。センサ未接続時は g_imuEnabled=false で既存機能へフォールバック
 *   updateIMU(): 50Hz(20ms) でサンプリングし最新値をグローバルへ格納。loop から毎回呼出
 *   dumpIMU()  : IMU_DEBUG_DUMP 時、10Hz で生値をシリアルへ出力（Phase 14-A 生値確認用）
 *
 * データ採取（Phase 14-B）では、ここで保持するサンプル値をリングバッファへ蓄積し、
 * BLE 経由でスマホアプリへ「直近10秒」を転送する想定（構造は拡張可能）。
 */

#include "bikeclock.h"
#include <Wire.h>

// --- BMI160 レジスタ（datasheet reference）---
#define BMI160_CHIPID      0x00   // Chip ID（期待値 0xD1）
#define BMI160_ERR_REG     0x02   // エラー状態（CMD 不正等）
#define BMI160_PMU_STATUS  0x03   // 電源状態（acc/gyr/mag の各モード）
#define BMI160_GYR_X_L     0x0C   // データ連続読出し開始（gyro x,y,z → acc x,y,z の計12B）
#define BMI160_ACC_CONF    0x40
#define BMI160_ACC_RANGE   0x41
#define BMI160_GYR_CONF    0x42
#define BMI160_GYR_RANGE   0x43
#define BMI160_CMD         0x7E
#define BMI160_CHIPID_VAL  0xD1

// PMU_STATUS(0x03) の各センサ電源状態（Bosch 公式ドライバ bmi160_defs.h 準拠）
//   bits[5:4] = 加速度(ACC)  電源状態  (00=suspend, 01=normal, 10=low_power)
//   bits[3:2] = ジャイロ(GYR) 電状態  (00=suspend, 01=normal, 10=low_power)
//   bits[1:0] = 磁気(MAG)    電源状態  (未使用)
// 注意: 旧実装は bits[1:0]=acc と誤認し、実際は MAG(常にsuspend)を見ていたため
// 加速度有効化が常に「失敗」と誤判定 → CMD 0x11 を5回連打 → 既に NORMAL の加速度へ
// の再設定で ERR_REG(0x02) が発生し加速度データ出力が停止する（実機でフリーズ確認）。
#define BMI160_PMU_ACC_MASK    0x30   // bits[5:4]
#define BMI160_PMU_ACC_NORMAL  0x10
#define BMI160_PMU_GYR_MASK    0x0C   // bits[3:2]
#define BMI160_PMU_GYR_NORMAL  0x04

// フルスケール換算定数
#define BMI160_ACC_LSB_PER_G    16384.0f    // ±2g      (ACC_RANGE=0x03)  → 2^15/2
#define BMI160_GYR_LSB_PER_DPS 131.072f    // ±250dps  (GYR_RANGE=0x03)  → 2^15/250

// サンプリング設定
#define IMU_SAMPLE_INTERVAL_MS  20    // 50Hz（loop=100Hz から間引き）
#define IMU_DUMP_INTERVAL_MS    100   // 10Hz ダンプ（シリアル溢れ防止）

// --- Global Variables ---
bool g_imuEnabled = false;
float g_imuAx = 0, g_imuAy = 0, g_imuAz = 0;   // 加速度 [g]
float g_imuGx = 0, g_imuGy = 0, g_imuGz = 0;   // 角速度 [deg/s]
unsigned long g_imuLastSampleMillis = 0;

// Phase 14-B: リングバッファ（int16 生LSB・6軸×500・循環）
ImuSample g_imuRingBuffer[IMU_RING_BUFFER_SIZE];
volatile uint16_t g_imuRingHead = 0;
volatile uint16_t g_imuRingCount = 0;

// Phase 14-B: IMU_DUMP 送信状態機械（loop() 内 updateImuDump() で進行）
bool g_imuDumpActive = false;
uint16_t g_imuDumpSeq = 0;
uint16_t g_imuDumpTotal = 0;
uint16_t g_imuDumpSamplesToSend = 0;
unsigned long g_imuDumpLastSend = 0;
uint8_t g_imuDumpRetry = 0;

// 未来録り（IMU_RECORD_START → 10秒後にリングバッファ送信）
bool g_imuRecordPending = false;
unsigned long g_imuRecordStartMillis = 0;

// ====================================================================
// I2C ヘルパ（レジスタ読み書き）
// ====================================================================

static bool imuWriteReg(uint8_t reg, uint8_t val) {
    Wire.beginTransmission(IMU_I2C_ADDR);
    Wire.write(reg);
    Wire.write(val);
    return (Wire.endTransmission() == 0);
}

static uint8_t imuReadReg(uint8_t reg) {
    Wire.beginTransmission(IMU_I2C_ADDR);
    Wire.write(reg);
    Wire.endTransmission(false);   // repeated start
    Wire.requestFrom((int)IMU_I2C_ADDR, 1);
    if (Wire.available()) return Wire.read();
    return 0;
}

// PMU_STATUS の指定ビットが指定値になるまでポーリング（CMD 処理完了待ち）。
// BMI160 は前の CMD 処理中に次を書くと無視されるため、各センサの NORMAL 移行を
// 確実に待つ。待たないとジャイロが起動せず常時0になる等の不具合を起こす。
static bool imuWaitPmu(uint8_t mask, uint8_t val, uint32_t timeoutMs) {
    const uint32_t start = millis();
    while ((millis() - start) < timeoutMs) {
        if ((imuReadReg(BMI160_PMU_STATUS) & mask) == val) return true;
        delay(2);
    }
    return false;
}

// CMD レジスタが 0x00 に戻るまで待つ（コマンド処理完了の確認）。
// BMI160 は前のコマンド処理中に次を書くと ERR_REG(0x02) が立ち CMD が無視されるため、
// 各 CMD の後に必ず呼ぶ（ソフトリセット直後の早すぎる書き込みが ACC 起動失敗の原因）。
static bool imuWaitCmdDone(uint32_t timeoutMs) {
    const uint32_t start = millis();
    while ((millis() - start) < timeoutMs) {
        if (imuReadReg(BMI160_CMD) == 0x00) return true;
        delay(1);
    }
    return false;
}

// センサを NORMAL モードへ（CMD 受理待ち + PMU 確認をリトライ付きで）。
// ソフトリセット直後などは最初の CMD が無視されることがあるため最大5回リトライ。
// PMU 完了待ちはジャイロ起動遷移(BMI160_GYRO_DELAY_MS=80ms)を確実にカバーするよう
// 120ms の余裕を持たせる。マスクが正しくないと完了検知できず不要なリトライが走り、
// 既に NORMAL のセンサへ再設定 CMD を連打 → ERR_REG(0x02) → データ出力停止する。
static bool imuEnableSensor(uint8_t cmd, uint8_t pmuMask, uint8_t pmuNormal) {
    for (int retry = 0; retry < 5; retry++) {
        imuWriteReg(BMI160_CMD, cmd);
        delay(2);   // CMD ラッチ待ち（連続 CMD 書込みの最小間隔を確保）
        imuWaitCmdDone(10);
        if (imuWaitPmu(pmuMask, pmuNormal, 120)) return true;
    }
    return false;
}

static bool imuReadRegs(uint8_t reg, uint8_t* buf, uint8_t len) {
    Wire.beginTransmission(IMU_I2C_ADDR);
    Wire.write(reg);
    if (Wire.endTransmission(false) != 0) return false;
    Wire.requestFrom((int)IMU_I2C_ADDR, (int)len);
    for (uint8_t i = 0; i < len; i++) {
        if (!Wire.available()) return false;
        buf[i] = Wire.read();
    }
    return true;
}

// ====================================================================
// setupIMU — BMI160 初期化（接続失敗時フォールバック）
// ====================================================================
void setupIMU() {
    Wire.begin(IMU_SDA_GPIO, IMU_SCL_GPIO);
    Wire.setClock(400000);   // Fast-mode 400kHz
    delay(50);

    // チップID 確認（センサ未接続の即時判定）
    uint8_t chipId = imuReadReg(BMI160_CHIPID);
    if (chipId != BMI160_CHIPID_VAL) {
        logPrint("IMU", "BMI160 NOT detected (chipId=0x%02X, expected 0x%02X) @ addr 0x%02X — fallback to no-IMU",
                 chipId, BMI160_CHIPID_VAL, IMU_I2C_ADDR);
        g_imuEnabled = false;
        return;
    }
    logPrint("IMU", "BMI160 detected (chipId=0x%02X) @ I2C 0x%02X (SDA=%d SCL=%d)",
             chipId, IMU_I2C_ADDR, IMU_SDA_GPIO, IMU_SCL_GPIO);

    // ソフトリセット（リセット後は SUSPEND モード＝全センサ停止になる）
    imuWriteReg(BMI160_CMD, 0xB6);
    delay(50);   // リセット完了待ち（NVM ロード・バス安定。応答再開前は CMD ポーリングが無意味なため固定待ち）

    // 電源オン: GYR → ACC の順で NORMAL に移行。
    // 各 CMD 後は PMU_STATUS で「確実に NORMAL に遷移完了」するまで待つこと
    // （ジャイロ起動遷移 ~80ms。BMI160_GYRO_DELAY_MS=80）。マスクは正しいフィールド
    // を見ること: 加速度=bits[5:4], ジャイロ=bits[3:2]（Bosch bmi160_defs.h 準拠）。
    if (!imuEnableSensor(0x15, BMI160_PMU_GYR_MASK, BMI160_PMU_GYR_NORMAL)) {
        logPrint("IMU", "WARN: GYR NORMAL failed (pmu=0x%02X)", imuReadReg(BMI160_PMU_STATUS));
    }
    if (!imuEnableSensor(0x11, BMI160_PMU_ACC_MASK, BMI160_PMU_ACC_NORMAL)) {
        logPrint("IMU", "WARN: ACC NORMAL failed (pmu=0x%02X)", imuReadReg(BMI160_PMU_STATUS));
    }

    // フルスケール（Bosch 公式値: ACCEL_RANGE_2G=0x03, GYRO_RANGE_250_DPS=0x03）
    //   ※旧実装は ACC_RANGE=0x00 は未定義値（無効）。0x03 が ±2g。
    imuWriteReg(BMI160_ACC_RANGE, 0x03);  // ±2g       → 16384 LSB/g
    imuWriteReg(BMI160_GYR_RANGE, 0x03);  // ±250deg/s → 131.072 LSB/dps

    // ODR / BWP（Bosch 公式値: ACC/GYR ODR_50HZ=0x07, BW normal=2 → 0x27）
    //   ※旧実装は acc=0x23(ODR=3 → 3.12Hz)、gyr=0x22(ODR=2 → 予約値/無効)。
    //   ジャイロ ODR が予約値だとサンプルが生成されずデータがフリーズする。
    //   acc_conf = gyr_conf = (bwp=2 << 4) | odr=7(50Hz) = 0x27
    imuWriteReg(BMI160_ACC_CONF, 0x27);  // acc: bwp=normal(avg4), 50Hz
    imuWriteReg(BMI160_GYR_CONF, 0x27);  // gyr: bwp=normal,       50Hz
    delay(2);

    g_imuEnabled = true;

    // 初期化後の状態確認ログ（acc=1, gyr=1 が normal。PMU=0x14 が両方normalの正常値）
    //   抽出: acc=bits[5:4], gyr=bits[3:2]（Bosch bmi160_defs.h: ACCEL_MSK=0x30/POS4, GYRO_MSK=0x0C/POS2）
    uint8_t pmu = imuReadReg(BMI160_PMU_STATUS);
    uint8_t err = imuReadReg(BMI160_ERR_REG);
    logPrint("IMU", "Initialized: ±2g/±250dps 50Hz | PMU=0x%02X (acc=%d gyr=%d) ERR=0x%02X",
             pmu, (pmu >> 4) & 0x03, (pmu >> 2) & 0x03, err);
}

// ====================================================================
// updateIMU — 50Hz サンプリング（loop から毎回呼出）
// ====================================================================
void updateIMU() {
    if (!g_imuEnabled) return;

    if (g_currentMillis - g_imuLastSampleMillis < IMU_SAMPLE_INTERVAL_MS) {
        return;
    }
    g_imuLastSampleMillis = g_currentMillis;

    // 0x0C から 12B 連続読出し: gx_l,gx_h, gy_l,gy_h, gz_l,gz_h, ax_l,ax_h, ay_l,ay_h, az_l,az_h
    uint8_t buf[12];
    if (!imuReadRegs(BMI160_GYR_X_L, buf, 12)) {
        return;   // I2C 読み失敗は次回リトライ
    }

    int16_t gx = (int16_t)((buf[1]  << 8) | buf[0]);
    int16_t gy = (int16_t)((buf[3]  << 8) | buf[2]);
    int16_t gz = (int16_t)((buf[5]  << 8) | buf[4]);
    int16_t ax = (int16_t)((buf[7]  << 8) | buf[6]);
    int16_t ay = (int16_t)((buf[9]  << 8) | buf[8]);
    int16_t az = (int16_t)((buf[11] << 8) | buf[10]);

    // Phase 14-B: リングバッファへ生LSBを push（常時・循環。古いデータから上書き）
    {
        ImuSample& s = g_imuRingBuffer[g_imuRingHead];
        s.ax = ax; s.ay = ay; s.az = az;
        s.gx = gx; s.gy = gy; s.gz = gz;
        g_imuRingHead = (uint16_t)((g_imuRingHead + 1) % IMU_RING_BUFFER_SIZE);
        if (g_imuRingCount < IMU_RING_BUFFER_SIZE) g_imuRingCount++;
    }

    g_imuGx = gx / BMI160_GYR_LSB_PER_DPS;
    g_imuGy = gy / BMI160_GYR_LSB_PER_DPS;
    g_imuGz = gz / BMI160_GYR_LSB_PER_DPS;
    g_imuAx = ax / BMI160_ACC_LSB_PER_G;
    g_imuAy = ay / BMI160_ACC_LSB_PER_G;
    g_imuAz = az / BMI160_ACC_LSB_PER_G;

#if IMU_DEBUG_DUMP
    dumpIMU();
#endif
}

// ====================================================================
// dumpIMU — 生値を 10Hz でシリアル出力（Phase 14-A 確認用）
// ====================================================================
void dumpIMU() {
    static unsigned long lastDump = 0;
    if (g_currentMillis - lastDump < IMU_DUMP_INTERVAL_MS) return;
    lastDump = g_currentMillis;

    logPrint("IMU", "acc[g] x=%+.3f y=%+.3f z=%+.3f | gyr[dps] x=%+.2f y=%+.2f z=%+.2f",
             g_imuAx, g_imuAy, g_imuAz, g_imuGx, g_imuGy, g_imuGz);
}

// ====================================================================
// handleImuDump — IMU_DUMP 要求受信（BLE onWrite から・状態初期化のみ）
//   ※BLEコールバック内で長時間ブロックできないため、ここでは送信状態を
//     初期化するだけ。実際のチャンク送信は loop() 内 updateImuDump() で進行。
// ====================================================================
void handleImuDump() {
    if (!g_imuEnabled || g_imuRingCount == 0) {
        sendResponse("ERROR:IMU not ready");
        logPrint("IMU", "IMU_DUMP requested but not ready (enabled=%d count=%u)",
                 g_imuEnabled ? 1 : 0, g_imuRingCount);
        return;
    }
    g_imuDumpSamplesToSend = g_imuRingCount;
    g_imuDumpTotal = (uint16_t)((g_imuDumpSamplesToSend + IMU_SAMPLES_PER_CHUNK - 1) / IMU_SAMPLES_PER_CHUNK);
    g_imuDumpSeq = 0;
    g_imuDumpRetry = 0;
    g_imuDumpLastSend = 0;   // 即送信開始
    g_imuDumpActive = true;
    logPrint("IMU", "IMU_DUMP start: %u samples -> %u chunks",
             g_imuDumpSamplesToSend, g_imuDumpTotal);
}

// ====================================================================
// updateImuDump — チャンク分割送信の進行（loop から毎回呼出）
//   30ms間隔で1チャンクを構築→ sendBinary()。リングバッファから古い順に取り出す。
//   送信失敗時は同一チャンクを最大 IMU_DUMP_MAX_RETRY 回リトライ。
//   チャンク: [MAGIC0][MAGIC1][seq][total][status] [int16×6×n LE]
// ====================================================================
void updateImuDump() {
    if (!g_imuDumpActive) return;
    if (g_currentMillis - g_imuDumpLastSend < IMU_DUMP_INTERVAL_MS) return;

    // リングバッファの最古サンプル位置（古い順に送る）
    const uint16_t startIdx = (uint16_t)((g_imuRingHead + IMU_RING_BUFFER_SIZE - g_imuRingCount) % IMU_RING_BUFFER_SIZE);
    const uint16_t offset = (uint16_t)(g_imuDumpSeq * IMU_SAMPLES_PER_CHUNK);
    const uint16_t remaining = (uint16_t)(g_imuDumpSamplesToSend - offset);
    const uint16_t n = (remaining > IMU_SAMPLES_PER_CHUNK) ? IMU_SAMPLES_PER_CHUNK : remaining;
    const bool isLast = (g_imuDumpSeq + 1 >= g_imuDumpTotal);

    // チャンク構築（最大 5 + 18*12 = 221B ≤ MTU ペイロード244B）
    uint8_t chunk[IMU_DUMP_CHUNK_HEADER + IMU_SAMPLES_PER_CHUNK * sizeof(ImuSample)];
    chunk[0] = IMU_DUMP_MAGIC0;
    chunk[1] = IMU_DUMP_MAGIC1;
    chunk[2] = (uint8_t)g_imuDumpSeq;
    chunk[3] = (uint8_t)g_imuDumpTotal;
    chunk[4] = isLast ? IMU_DUMP_STATUS_LAST : IMU_DUMP_STATUS_MORE;

    for (uint16_t i = 0; i < n; i++) {
        uint16_t idx = (uint16_t)((startIdx + offset + i) % IMU_RING_BUFFER_SIZE);
        memcpy(chunk + IMU_DUMP_CHUNK_HEADER + i * sizeof(ImuSample),
               &g_imuRingBuffer[idx], sizeof(ImuSample));
    }
    const size_t chunkLen = (size_t)(IMU_DUMP_CHUNK_HEADER + n * sizeof(ImuSample));

    if (sendBinary(chunk, chunkLen)) {
        g_imuDumpLastSend = g_currentMillis;
        g_imuDumpSeq++;
        g_imuDumpRetry = 0;
        if (isLast) {
            logPrint("IMU", "IMU_DUMP done: %u/%u chunks sent", g_imuDumpSeq, g_imuDumpTotal);
            g_imuDumpActive = false;
        }
    } else {
        // 送信失敗（キュー満杯/非接続）→ リトライ。上限超えで中断（アプリ側でseq欠損検知）
        if (++g_imuDumpRetry > IMU_DUMP_MAX_RETRY) {
            logPrint("IMU", "IMU_DUMP aborted at chunk %u (retry exhausted)", g_imuDumpSeq);
            g_imuDumpActive = false;
        }
    }
}

// ====================================================================
// handleImuRecordStart — 未来録り要求（BLE onWrite から・状態初期化のみ）
//   10秒後に updateImuRecord() が handleImuDump() を起動し、その時点の
//   リングバッファ（＝開始〜10秒のデータ）を送信する。
// ====================================================================
void handleImuRecordStart() {
    g_imuRecordPending = true;
    g_imuRecordStartMillis = g_currentMillis;
    logPrint("IMU", "IMU_RECORD_START: recording 10s...");
}

// ====================================================================
// updateImuRecord — 10秒経過で handleImuDump 起動（loop から毎回呼出）
// ====================================================================
void updateImuRecord() {
    if (!g_imuRecordPending) return;
    if (g_currentMillis - g_imuRecordStartMillis < IMU_RECORD_DURATION_MS) return;
    g_imuRecordPending = false;
    logPrint("IMU", "IMU_RECORD done, sending buffer");
    handleImuDump();
}
