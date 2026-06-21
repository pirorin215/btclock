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
MotionPattern g_motionPatterns[MAX_MOTION_PATTERNS];
uint8_t g_motionPatternCount = 0;
float g_motionFeatMean[MOTION_FEAT_DIM];
float g_motionFeatStd[MOTION_FEAT_DIM];
bool g_motionModelReady = false;
char g_detectedPattern[MOTION_NAME_LEN] = "";

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
    file.write((const uint8_t*)g_motionFeatMean, sizeof(g_motionFeatMean));
    file.write((const uint8_t*)g_motionFeatStd, sizeof(g_motionFeatStd));
    for (int p = 0; p < g_motionPatternCount; p++) {
        file.write((const uint8_t*)g_motionPatterns[p].name, MOTION_NAME_LEN);
        file.write((const uint8_t*)g_motionPatterns[p].centroid, sizeof(float) * MOTION_FEAT_DIM);
    }
    file.close();
    logPrint("MOTION", "model saved (%u patterns)", g_motionPatternCount);
}

// ====================================================================
// loadMotionModel — 起動時に LittleFS からモデルを復元
// ====================================================================
void loadMotionModel() {
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
    if (file.read((uint8_t*)g_motionFeatMean, sizeof(g_motionFeatMean)) != sizeof(g_motionFeatMean)) { file.close(); return; }
    if (file.read((uint8_t*)g_motionFeatStd, sizeof(g_motionFeatStd)) != sizeof(g_motionFeatStd)) { file.close(); return; }
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
    size_t check = 2 + (size_t)2 * 4 * d;
    size_t p = check;   // featMean/featStd の後から（N,D + mean + std のサイズ）
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

    for (int i = 0; i < d; i++) g_motionFeatMean[i] = readFloatLE(buf, off);
    for (int i = 0; i < d; i++) g_motionFeatStd[i] = readFloatLE(buf, off);
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

    saveMotionModel();
    sendResponse("OK: motion model stored");
    logPrint("MOTION", "model received: %u patterns", n);
    for (int pi = 0; pi < n; pi++) logPrint("MOTION", "  [%s]", g_motionPatterns[pi].name);
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
// extractFeatures — リングバッファの直近データから9次元特徴量を抽出
//   MotionFeatures.kt（Android）と完全一致。1次IIR LPF(0.5Hz)で重力抽出。
// ====================================================================
static bool extractFeatures(float out[MOTION_FEAT_DIM]) {
    int n = (int)g_imuRingCount;
    if (n < 10) return false;
    int startIdx = (int)((g_imuRingHead + IMU_RING_BUFFER_SIZE - n) % IMU_RING_BUFFER_SIZE);

    // 1次IIR LPF 定数（0.5Hz @ 50Hz）— Android GRAV_ALPHA と同一
    const float dt = 1.0f / 50.0f;
    const float rc = 1.0f / (2.0f * PI * 0.5f);
    const float alpha = dt / (rc + dt);

    float gax = 0, gay = 0, gaz = 0;          // 重力（IIR）
    float prevDax = 0, prevDay = 0, prevDaz = 0;
    double sumDyn = 0, sumGyro = 0, sumGax = 0, sumGay = 0, sumGaz = 0;
    float peakDyn = 0, peakGyro = 0, peakJerk = 0;
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
        float dm = sqrtf(dax * dax + day * day + daz * daz);
        sumDyn += dm; if (dm > peakDyn) peakDyn = dm;
        sumGax += gax; sumGay += gay; sumGaz += gaz;

        float gm = sqrtf(gx * gx + gy * gy + gz * gz);
        sumGyro += gm; if (gm > peakGyro) peakGyro = gm;

        if (i > 0) {
            float jx = dax - prevDax, jy = day - prevDay, jz = daz - prevDaz;
            float jm = sqrtf(jx * jx + jy * jy + jz * jz);
            if (jm > peakJerk) peakJerk = jm;
        }
        prevDax = dax; prevDay = day; prevDaz = daz;
    }

    float accRms = sqrtf((float)(sumDyn / n));
    float gyroRms = sqrtf((float)(sumGyro / n));
    float mgax = (float)(sumGax / n), mgay = (float)(sumGay / n), mgaz = (float)(sumGaz / n);
    float gmag = sqrtf(mgax * mgax + mgay * mgay + mgaz * mgaz);
    if (gmag < 1e-6f) gmag = 1e-6f;
    float ratio = fabsf(mgaz) / gmag;
    if (ratio > 1.0f) ratio = 1.0f;
    float tilt = acosf(ratio) * 180.0f / PI;

    out[0] = accRms;   out[1] = peakDyn;
    out[2] = gyroRms;  out[3] = peakGyro;
    out[4] = tilt;     out[5] = peakJerk;
    out[6] = mgax;     out[7] = mgay;     out[8] = mgaz;
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

    float feat[MOTION_FEAT_DIM];
    if (!extractFeatures(feat)) return;

    // z-score 正規化
    float norm[MOTION_FEAT_DIM];
    for (int i = 0; i < MOTION_FEAT_DIM; i++) {
        float sd = g_motionFeatStd[i];
        if (sd < 1e-6f) sd = 1e-6f;
        norm[i] = (feat[i] - g_motionFeatMean[i]) / sd;
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

    if (strcmp(candidate, s_lastCandidate) == 0) {
        if (strcmp(candidate, s_confirmed) != 0) {
            strncpy(s_confirmed, candidate, MOTION_NAME_LEN - 1);
            s_confirmed[MOTION_NAME_LEN - 1] = '\0';
            strncpy(g_detectedPattern, s_confirmed, MOTION_NAME_LEN - 1);
            g_detectedPattern[MOTION_NAME_LEN - 1] = '\0';
            logPrint("MOTION", "detected: %s (dist=%.2f)",
                     g_detectedPattern[0] ? g_detectedPattern : "(unknown)", dist);
        }
    } else {
        strncpy(s_lastCandidate, candidate, MOTION_NAME_LEN - 1);
        s_lastCandidate[MOTION_NAME_LEN - 1] = '\0';
    }
}
