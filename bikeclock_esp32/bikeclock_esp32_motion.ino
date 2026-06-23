/**
 * BikeClock ESP32-S3 - モーションパターン認識（Phase 2）
 *
 * スマホで学習したモデル（ラベル別重心＋正規化パラメータ）を BLE で受信して
 * LittleFS へ保存し、リングバッファの IMU データから特徴量を抽出して推論（最近傍分類）、
 * 検出パターン名を g_detectedPattern へ格納する。
 *
 * 【重要】特徴量の定義（次元数・計算式・順序）は Android 側 MotionFeatures.kt と
 *   完全一致させること。両側でずれると学習空間と推論空間が噛み合わなくなる。
 *   次元順序: [0]acc動的RMS [1]acc動的peak [2]gyro|RMS [3]gyro|peak
 *             [4]tilt [5]jerk peak [6]grav ax [7]grav ay [8]grav az
 */
#include "bikeclock.h"
#include <math.h>

// --- Globals（bikeclock.h で extern 宣言）---
MotionPattern* g_motionPatterns = nullptr;   // ヒープ割当（setup→loadMotionModel で new）
uint8_t g_motionPatternCount = 0;
bool g_motionModelReady = false;
char g_detectedPattern[MOTION_NAME_LEN] = "";
int g_motionDisplayIndex = -1;
unsigned long g_motionDisplayEndTime = 0;
bool g_parkedDisplayActive = false;   // 駐車中は ePaper を詳細表示で維持（走行検知で解除）
unsigned long g_parkedDisplayStartMillis = 0;   // 駐車詳細表示の開始時刻（PARKED_DISPLAY_TIMEOUT_MS で自動復帰）
bool g_inferLogEnabled = false;       // 推論ログBLE送信（INFER_LOG:1 でON・精度チューニング用）
bool g_motionModelSaveRequested = false;   // モデル保存要求（BLEコールバック→loop でFlash書き込み）

// パターン名→固定番号（3グループ×3: 駐車系=0, 走行開始系=1, 停車系=2）
// indexOfLabel は i/3 でグループ番号を返す。7セグ表示・ePaper連携はグループ単位。
static const char* MOTION_LABEL_TABLE[] = {
    "駐車A", "駐車B", "駐車C",
    "走行開始A", "走行開始B", "走行開始C",
    "停車A", "停車B", "停車C"
};
#define MOTION_LABEL_COUNT (sizeof(MOTION_LABEL_TABLE) / sizeof(MOTION_LABEL_TABLE[0]))

static int indexOfLabel(const char* name) {
    for (size_t i = 0; i < MOTION_LABEL_COUNT; i++) {
        if (strcmp(name, MOTION_LABEL_TABLE[i]) == 0) return (int)(i / 3);  // グループ番号(0,1,2)
    }
    return -1;
}

// --- モデル受信状態（BLE onWrite → フレーム蓄積 → 再構築）---
static uint8_t s_recvBuf[1024];
static size_t s_recvLen = 0;

// --- 推論安定化 ---
static unsigned long s_lastInferMillis = 0;
static char s_lastCandidate[MOTION_NAME_LEN] = "";   // 直前の候補
static char s_confirmed[MOTION_NAME_LEN] = "";       // 確定済みパターン

// リトルエンディアン float 読み取り（ESP32 は LE）
static float readFloatLE(const uint8_t* buf, size_t& off) {
    float f;
    memcpy(&f, buf + off, 4);
    off += 4;
    return f;
}

// ====================================================================
// saveMotionModel — RAM 上のモデルを LittleFS /motion_model.bin へ保存
//   形式: count(1) + featMean[D] + featStd[D] + 繰返し(name[D?] + centroid[D])
// ====================================================================
void saveMotionModel() {
    File file = LittleFS.open(MOTION_MODEL_FILE_PATH, "w");
    if (!file) { logPrint("MOTION", "save failed (open)"); return; }
    file.write(&g_motionPatternCount, 1);
    for (int p = 0; p < g_motionPatternCount; p++) {
        file.write((const uint8_t*)g_motionPatterns[p].name, MOTION_NAME_LEN);
        file.write((const uint8_t*)g_motionPatterns[p].centroid, sizeof(float) * MOTION_FEAT_DIM);
    }
    file.close();
    logPrint("MOTION", "model saved (%u patterns)", g_motionPatternCount);
}

// ====================================================================
// updateMotionSave — loop内でモデル保存(Flash書き込み)を実行
//   BLE onWriteコールバック内でFlash書き込みするとCore間cache競合でクラッシュするため、
//   parseAndStoreModel でフラグを立て、ここでloopタスクから安全に書き込む。
// ====================================================================
void updateMotionSave() {
    if (!g_motionModelSaveRequested) return;
    g_motionModelSaveRequested = false;
    saveMotionModel();
}

// ====================================================================
// loadMotionModel — 起動時に LittleFS からモデルを復元
// ====================================================================
void loadMotionModel() {
    if (!g_motionPatterns) {
        g_motionPatterns = new MotionPattern[MAX_MOTION_PATTERNS];
        if (!g_motionPatterns) { logPrint("MOTION", "alloc failed"); return; }
    }
    if (!LittleFS.exists(MOTION_MODEL_FILE_PATH)) {
        logPrint("MOTION", "no model file (first run)");
        return;
    }
    File file = LittleFS.open(MOTION_MODEL_FILE_PATH, "r");
    if (!file) { logPrint("MOTION", "load failed (open)"); return; }
    uint8_t n;
    if (file.read(&n, 1) != 1 || n > MAX_MOTION_PATTERNS) {
        file.close(); logPrint("MOTION", "load failed (count)"); return;
    }
    for (int p = 0; p < n; p++) {
        if (file.read((uint8_t*)g_motionPatterns[p].name, MOTION_NAME_LEN) != MOTION_NAME_LEN) { file.close(); return; }
        if (file.read((uint8_t*)g_motionPatterns[p].centroid, sizeof(float) * MOTION_FEAT_DIM) != sizeof(float) * MOTION_FEAT_DIM) { file.close(); return; }
    }
    file.close();
    g_motionPatternCount = n;
    g_motionModelReady = true;
    logPrint("MOTION", "model loaded: %u patterns", n);
    for (int p = 0; p < n; p++) logPrint("MOTION", "  [%s]", g_motionPatterns[p].name);
}

// ====================================================================
// parseAndStoreModel — 受信ペイロードを解析して RAM モデルを更新→保存
//   ペイロード(LE): N(1) D(1) featMean[D] featStd[D] 繰返し(nameLen(1) name centroid[D])
// ====================================================================
static void parseAndStoreModel(const uint8_t* buf, size_t len) {
    if (len < 2) { sendResponse("ERROR: motion model empty"); return; }
    size_t off = 0;
    uint8_t n = buf[off++];
    uint8_t d = buf[off++];
    logPrint("MOTION", "parse: len=%u n=%u d=%u", (unsigned)len, n, d);
    if (d != MOTION_FEAT_DIM) {
        logPrint("MOTION", "parse error: dim=%u (expected %d)", d, MOTION_FEAT_DIM);
        sendResponse("ERROR: motion model dim mismatch"); return;
    }
    if (n > MAX_MOTION_PATTERNS) {
        logPrint("MOTION", "parse error: patterns=%u > %d", n, MAX_MOTION_PATTERNS);
        sendResponse("ERROR: too many patterns"); return;
    }
    // 長さ検証
    size_t check = 2;   // N, D（featMean/featStd は廃止）
    size_t p = check;
    for (int i = 0; i < n; i++) {
        if (p >= len) {
            logPrint("MOTION", "truncated(loop): i=%d p=%u len=%u", i, (unsigned)p, (unsigned)len);
            sendResponse("ERROR: motion model truncated"); return;
        }
        uint8_t nl = buf[p];
        check += 1 + nl + (size_t)4 * d;
        p += 1 + nl + (size_t)4 * d;
    }
    if (check > len) {
        logPrint("MOTION", "truncated(final): check=%u len=%u", (unsigned)check, (unsigned)len);
        sendResponse("ERROR: motion model truncated"); return;
    }

    for (int pi = 0; pi < n; pi++) {
        uint8_t nl = buf[off++];
        size_t copyLen = (nl < MOTION_NAME_LEN - 1) ? nl : (MOTION_NAME_LEN - 1);
        memcpy(g_motionPatterns[pi].name, buf + off, copyLen);
        g_motionPatterns[pi].name[copyLen] = '\0';
        off += nl;
        for (int i = 0; i < d; i++) g_motionPatterns[pi].centroid[i] = readFloatLE(buf, off);
    }
    g_motionPatternCount = n;
    g_motionModelReady = true;

    g_motionModelSaveRequested = true;   // Flash書き込みは BLEコールバック外(loop)で実行
    sendResponse("OK: motion model stored");
    logPrint("MOTION", "model received: %u patterns", n);
    for (int pi = 0; pi < n; pi++) logPrint("MOTION", "  [%s]", g_motionPatterns[pi].name);
    // ePaper へ学習データ受信を表示（notification システム流用・5秒）
    snprintf(g_notificationApp, NOTIFY_APP_LEN, "学習データ");
    snprintf(g_notificationText, NOTIFY_TEXT_LEN, "受信: %uパターン", n);
    g_notificationEndTime = millis() + 5000UL;
    g_notificationActive = true;
    g_epaperRedrawRequested = true;
}

// ====================================================================
// handleMotionModelFrame — BLE onWrite からモデルフレームを受信
//   フレーム: [0xAA][0x55][seq][total][status][payload]
//   seq=0 でバッファ初期化。status=0xFF(最終) でペイロード再構築→保存。
// ====================================================================
void handleMotionModelFrame(const uint8_t* data, size_t len) {
    if (len < 5) return;
    if (data[0] != MOTION_FRAME_MAGIC0 || data[1] != MOTION_FRAME_MAGIC1) return;

    uint8_t seq = data[2];
    uint8_t status = data[4];

    logPrint("MOTION", "frame: seq=%u status=0x%02X framelen=%u payload=%u",
             seq, status, (unsigned)len, (unsigned)(len - 5));

    if (seq == 0) s_recvLen = 0;

    size_t pl = len - 5;
    if (s_recvLen + pl > sizeof(s_recvBuf)) {
        logPrint("MOTION", "recv overflow (%u > %u)", (unsigned)(s_recvLen + pl), (unsigned)sizeof(s_recvBuf));
        s_recvLen = 0;
        return;
    }
    memcpy(s_recvBuf + s_recvLen, data + 5, pl);
    s_recvLen += pl;

    if (status == MOTION_FRAME_STATUS_LAST) {
        parseAndStoreModel(s_recvBuf, s_recvLen);
        s_recvLen = 0;
    }
}

// ====================================================================
// extractFeatures — リングバッファの直近 MOTION_FEAT_WINDOW_SAMPLES サンプルから21次元特徴量を抽出
//   MotionFeatures.kt（Android）と完全一致。1次IIR LPF(0.5Hz)で重力抽出。
// ====================================================================
static bool __attribute__((noinline)) extractFeatures(float out[MOTION_FEAT_DIM]) {
    // 直近 MOTION_FEAT_WINDOW_SAMPLES サンプルで特徴量計算（リングバッファ全量ではない）。
    // 短窓化で駐車動作の信号が窓を素早く支配し、検出レイテンシを下げる。
    int avail = (int)g_imuRingCount;
    if (avail < 10) return false;
    int n = (avail < MOTION_FEAT_WINDOW_SAMPLES) ? avail : MOTION_FEAT_WINDOW_SAMPLES;
    int startIdx = (int)((g_imuRingHead + IMU_RING_BUFFER_SIZE - n) % IMU_RING_BUFFER_SIZE);

    // 1次IIR LPF 定数（0.5Hz @ 50Hz）— Android GRAV_ALPHA と同一
    const float dt = 1.0f / 50.0f;
    const float rc = 1.0f / (2.0f * PI * 0.5f);
    const float alpha = dt / (rc + dt);

    // static 化でスタック消費を抑制（extractFeatures は単一スレッドからのみ呼出）
    static float gax, gay, gaz;          // 重力（IIR）
    static float daxMax, dayMax, dazMax; // 動的acc 符号付きピーク(max)
    static float daxMin, dayMin, dazMin; // 動的acc 符号付きピーク(min)
    static double sdax2, sday2, sdaz2;   // 動的acc 各軸 RMS用
    static double sgx, sgy, sgz;         // gyro 各軸 平均用
    static double sgx2, sgy2, sgz2;      // gyro 各軸 RMS用
    static double sgax, sgay, sgaz;      // 重力 各軸平均用
    static float peakDyn, peakGyro;      // ノルムpeak
    // 毎呼出で0クリア（static は初回のみ初期化のため明示）
    gax = gay = gaz = 0;
    daxMax = dayMax = dazMax = 0;
    daxMin = dayMin = dazMin = 0;
    sdax2 = sday2 = sdaz2 = 0;
    sgx = sgy = sgz = 0;
    sgx2 = sgy2 = sgz2 = 0;
    sgax = sgay = sgaz = 0;
    peakDyn = peakGyro = 0;
    bool first = true;

    for (int i = 0; i < n; i++) {
        const ImuSample& s = g_imuRingBuffer[(startIdx + i) % IMU_RING_BUFFER_SIZE];
        float ax = s.ax / BMI160_ACC_LSB_PER_G;
        float ay = s.ay / BMI160_ACC_LSB_PER_G;
        float az = s.az / BMI160_ACC_LSB_PER_G;
        float gx = s.gx / BMI160_GYR_LSB_PER_DPS;
        float gy = s.gy / BMI160_GYR_LSB_PER_DPS;
        float gz = s.gz / BMI160_GYR_LSB_PER_DPS;

        if (first) { gax = ax; gay = ay; gaz = az; first = false; }
        else { gax += alpha * (ax - gax); gay += alpha * (ay - gay); gaz += alpha * (az - gaz); }

        float dax = ax - gax, day = ay - gay, daz = az - gaz;

        // 動的acc 各軸 符号付きピーク(max/min) / RMS
        if (dax > daxMax) daxMax = dax;
        if (day > dayMax) dayMax = day;
        if (daz > dazMax) dazMax = daz;
        if (dax < daxMin) daxMin = dax;
        if (day < dayMin) dayMin = day;
        if (daz < dazMin) dazMin = daz;
        sdax2 += dax * dax; sday2 += day * day; sdaz2 += daz * daz;

        // gyro 各軸 平均/RMS
        sgx += gx; sgy += gy; sgz += gz;
        sgx2 += gx * gx; sgy2 += gy * gy; sgz2 += gz * gz;

        // 重力 各軸平均
        sgax += gax; sgay += gay; sgaz += gaz;

        // ノルムpeak
        float dm = sqrtf(dax * dax + day * day + daz * daz);
        if (dm > peakDyn) peakDyn = dm;
        float gm = sqrtf(gx * gx + gy * gy + gz * gz);
        if (gm > peakGyro) peakGyro = gm;
    }

    float mgax = (float)(sgax / n), mgay = (float)(sgay / n), mgaz = (float)(sgaz / n);
    float gmag = sqrtf(mgax * mgax + mgay * mgay + mgaz * mgaz);
    if (gmag < 1e-6f) gmag = 1e-6f;
    float ratio = fabsf(mgaz) / gmag;
    if (ratio > 1.0f) ratio = 1.0f;
    float tilt = acosf(ratio) * 180.0f / PI;

    // [0-2] accDyn max, [3-5] accDyn min, [6-8] accDyn rms,
    // [9-11] gyro mean, [12-14] gyro rms,
    // [15] accDyn ノルムpeak, [16] gyro ノルムpeak, [17] tilt, [18-20] grav
    out[0] = daxMax;  out[1] = dayMax;  out[2] = dazMax;
    out[3] = daxMin;  out[4] = dayMin;  out[5] = dazMin;
    out[6] = sqrtf((float)(sdax2 / n));  out[7] = sqrtf((float)(sday2 / n));  out[8] = sqrtf((float)(sdaz2 / n));
    out[9] = (float)(sgx / n);   out[10] = (float)(sgy / n);  out[11] = (float)(sgz / n);
    out[12] = sqrtf((float)(sgx2 / n));  out[13] = sqrtf((float)(sgy2 / n));  out[14] = sqrtf((float)(sgz2 / n));
    out[15] = peakDyn;
    out[16] = peakGyro;
    out[17] = tilt;
    out[18] = mgax;  out[19] = mgay;  out[20] = mgaz;
    return true;
}

// ====================================================================
// updateMotionInference — loop から定周期で推論（最近傍分類）
//   特徴量 → z-score 正規化 → 各 centroid との距離 → 最近傍。
//   連続2回同一候補で確定。確定パターンが変化した時だけログ出力。
// ====================================================================
void updateMotionInference() {
    if (!g_motionModelReady || g_motionPatternCount == 0) return;
    if (g_currentMillis - s_lastInferMillis < MOTION_INFER_INTERVAL_MS) return;
    s_lastInferMillis = g_currentMillis;

    static float feat[MOTION_FEAT_DIM];
    if (!extractFeatures(feat)) return;

    // スケール正規化（z-score 廃止・サンプル少での std 過小評価対策）
    static float norm[MOTION_FEAT_DIM];
    for (int i = 0; i < MOTION_FEAT_DIM; i++) {
        norm[i] = feat[i] / MOTION_FEATURE_SCALE[i];
    }

    // 最近傍
    int best = -1;
    float bestDist = 1e9f;
    for (int p = 0; p < g_motionPatternCount; p++) {
        float d2 = 0;
        for (int i = 0; i < MOTION_FEAT_DIM; i++) {
            float diff = norm[i] - g_motionPatterns[p].centroid[i];
            d2 += diff * diff;
        }
        if (d2 < bestDist) { bestDist = d2; best = p; }
    }
    float dist = sqrtf(bestDist);

    const char* candidate = (best >= 0 && dist < MOTION_DISTANCE_THRESH)
                            ? g_motionPatterns[best].name : "";

    // 推論ログをBLE送信（INFER_LOG:1 で有効化時のみ・毎推論=1Hz）。スマホで時系列記録→CSV分析。
    //   形式: INFER:<ms>,<candidate>,<dist>,<f0>..<f20>（candidate は空を"-"で送信）
    if (g_inferLogEnabled) {
        static char logBuf[256];   // static: スタック消費抑制（loop単独スレッドで安全）
        snprintf(logBuf, sizeof(logBuf),
                 "INFER:%lu,%s,%.2f,"
                 "%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,"
                 "%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,"
                 "%.3f,%.3f,%.2f,%.3f,%.3f,%.3f",
                 (unsigned long)g_currentMillis,
                 candidate[0] ? candidate : "-",
                 dist,
                 feat[0], feat[1], feat[2], feat[3], feat[4], feat[5], feat[6],
                 feat[7], feat[8], feat[9], feat[10], feat[11], feat[12], feat[13],
                 feat[14], feat[15], feat[16], feat[17], feat[18], feat[19], feat[20]);
        sendResponse(logBuf);
    }

    if (strcmp(candidate, s_lastCandidate) == 0) {
        if (strcmp(candidate, s_confirmed) != 0) {
            strncpy(s_confirmed, candidate, MOTION_NAME_LEN - 1);
            s_confirmed[MOTION_NAME_LEN - 1] = '\0';
            strncpy(g_detectedPattern, s_confirmed, MOTION_NAME_LEN - 1);
            g_detectedPattern[MOTION_NAME_LEN - 1] = '\0';
            logPrint("MOTION", "detected: %s (dist=%.2f)",
                     g_detectedPattern[0] ? g_detectedPattern : "(unknown)", dist);
            // 7セグへ3秒表示（パターン番号。unknown は表示しない）
            int idx = indexOfLabel(s_confirmed);
            if (idx >= 0) {
                g_motionDisplayIndex = idx;
                g_motionDisplayEndTime = g_currentMillis + MOTION_DISPLAY_MS;
            }
            // ePaper 駐車表示連携：駐車系(idx=0)で詳細表示へ、走行開始系(idx=1)で時計へ戻す
            if (idx == 0 && !g_parkedDisplayActive) {
                g_parkedDisplayActive = true;
                g_parkedDisplayStartMillis = g_currentMillis;
                g_epaperRedrawRequested = true;
                logPrint("MOTION", "parked -> ePaper detail");
            } else if (idx == 1 && g_parkedDisplayActive) {
                g_parkedDisplayActive = false;
                g_epaperRedrawRequested = true;
                logPrint("MOTION", "riding -> ePaper clock");
            }
        }
    } else {
        strncpy(s_lastCandidate, candidate, MOTION_NAME_LEN - 1);
        s_lastCandidate[MOTION_NAME_LEN - 1] = '\0';
    }
}

// ====================================================================
// motionTask — updateMotionInference を独立 FreeRTOS タスク(16KB)で実行
//   extractFeatures のスタック使用量大→loopTask(8KB)でスタックオーバーフロー。
//   専用タスクで分離。logPrint はミューテックス保護でデッドロック回避。
// ====================================================================
void motionTask(void* arg) {
    (void)arg;
    while (true) {
        updateMotionInference();
        vTaskDelay(pdMS_TO_TICKS(100));
    }
}
