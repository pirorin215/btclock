#ifndef BIKECLOCK_H
#define BIKECLOCK_H

#include <Arduino.h>
#include <TM1637Display.h>
#include <LittleFS.h>
#include <U8g2_for_Adafruit_GFX.h>

// --- Firmware Version Information (ESP32-S3 edition) ---
// XIAO BLE 版 (1.x.x) と区別するため 2.0.0 から開始
#define FIRMWARE_VERSION_MAJOR 2
#define FIRMWARE_VERSION_MINOR 0
#define FIRMWARE_VERSION_PATCH 39

// --- GPIO Pin Definitions (ESP32-S3 SuperMini / 推奨案A) ---
// TM1637 4-digit 7-segment display
#define LED_DIO_GPIO     6   // TM1637 DIO
#define LED_CLK_GPIO     7   // TM1637 CLK

// Physical Switches
// Phase 14: SW1/SW2 を背面端子へ移動。空けた GPIO4/5 を BMI160 I2C(SDA/SCL) に再割当て。
#define SWITCH_SW1_GPIO     41  // Right Arrow (旧GPIO4 → 背面端子)
#define SWITCH_SW2_GPIO     47  // Down Arrow  (旧GPIO5 → 背面端子)
#define SWITCH_SW3_GPIO     13  // Up Arrow
#define SWITCH_SW4_GPIO     14  // Left Arrow
#define SWITCH_SW5_GPIO     35  // Enter
#define SWITCH_SW6_GPIO     38  // Back
#define SWITCH_SW7_GPIO     39  // Play/Pause
#define SWITCH_FUNC_GPIO    8   // Function / Mode / Maintenance

#define HID_DEBOUNCE_DELAY_MS     50   // Switch debounce delay

// --- HID Key Codes (Keyboard Page) ---
#define DEFAULT_SW1_KEYCODE    0x4F  // Right Arrow
#define DEFAULT_SW2_KEYCODE    0x51  // Down Arrow
#define DEFAULT_SW3_KEYCODE    0x52  // Up Arrow
#define DEFAULT_SW4_KEYCODE    0x50  // Left Arrow
#define DEFAULT_SW5_KEYCODE    0x28  // Enter
#define DEFAULT_SW6_KEYCODE    0x0224  // Back (Android)
#define DEFAULT_SW7_KEYCODE    0xCD  // Play/Pause (Consumer Page)

// Onboard RGB LED (WS2812 addressable, fixed on GPIO48)
#define ONBOARD_LED_GPIO    48

// ePaper (WeAct 2.13" BW, GxEPD2_213_B74) — 昼間視認性用
// 専用 SPI3_HOST バスを使用（SPI3_HOST）
#define EPD_CS_GPIO         1
#define EPD_DC_GPIO         2
#define EPD_RST_GPIO        3
#define EPD_BUSY_GPIO       10
#define EPD_SPI_SCK_GPIO    12   // 専用 SPI3_HOST
#define EPD_SPI_MOSI_GPIO   11   // 専用 SPI3_HOST (MISOはePaper不要)

// --- BMI160 IMU (Phase 14: 両脚スタンド検知用) ---
// GY-BMI160 (BMI160: 3軸加速度＋3軸ジャイロ)。I2C 接続（Wire.h レジスタ直接制御・外部ライブラリ不要）。
// SW1/SW2 を背面端子へ移動して空けた GPIO4/5 を I2C に再割当て。
#define IMU_SCL_GPIO       4    // SW2 から再利用
#define IMU_SDA_GPIO       5    // SW1 から再利用
#define IMU_I2C_ADDR       0x68 // BMI160 I2C アドレス (SDO→GND で固定)
// デバッグ: 生値をシリアルへ 10Hz でダンプ（Phase 14-A 生値確認用。0 で無効）
#define IMU_DEBUG_DUMP     0

// --- Phase 14-B: IMU リングバッファ（直近10秒・500サンプル）＋ BLE チャンク転送 ---
// int16 生LSB（6軸）で保持（float なら12KB消費→int16 で6KBに節約）。
// updateIMU() で push。IMU_DUMP コマンドで古い順にチャンク分割して BLE notify 送信。
//   チャンク: [0xAA][0x55][seq][total][status] [int16×6×N LE]（status 0x00=継続, 0xFF=最終）
struct ImuSample {
    int16_t ax, ay, az;   // 加速度生LSB
    int16_t gx, gy, gz;   // 角速度生LSB
};   // 12B/サンプル
#define IMU_RING_BUFFER_SIZE    500                  // 50Hz × 10秒
#define IMU_SAMPLES_PER_CHUNK   18                   // チャンクあたりサンプル数（ヘッダ5B+216B=221B）
#define IMU_DUMP_INTERVAL_MS    30                   // チャンク送信間隔（NimBLE notify キュー対策）
#define IMU_DUMP_MAGIC0         0xAA                 // チャンクマジック上位
#define IMU_DUMP_MAGIC1         0x55                 // チャンクマジック下位
#define IMU_DUMP_STATUS_MORE    0x00                 // status: 継続
#define IMU_DUMP_STATUS_LAST    0xFF                 // status: 最終チャンク
#define IMU_DUMP_MAX_RETRY      5                    // チャンク送信失敗時リトライ上限
#define IMU_RECORD_DURATION_MS  10000UL              // IMU_RECORD_START: 10秒録音後に送信
#define IMU_DUMP_CHUNK_HEADER   5                    // magic(2)+seq(1)+total(1)+status(1)

// --- Phase 2: モーションパターン認識（スマホ学習モデルを受信・推論）---
// Android MotionFeatures.kt と特徴量定義(DIM/計算/順序)を完全一致させること。
#define MOTION_MODEL_FILE_PATH   "/motion_model.bin"
#define MOTION_FEAT_DIM          9                   // 特徴量次元（Android と共通）
#define MAX_MOTION_PATTERNS      12                  // 保持上限
#define MOTION_NAME_LEN          16                  // パターン名バッファ
#define MOTION_INFER_INTERVAL_MS 1000UL              // 推論周期
#define MOTION_DISTANCE_THRESH   3.0f                // 最近傍距離の閾値（正規化空間）。超過は「不明」
#define MOTION_FRAME_MAGIC0      0xAA                // モデル受信フレームマジック
#define MOTION_FRAME_MAGIC1      0x55
#define MOTION_FRAME_STATUS_LAST 0xFF
#define MOTION_DISPLAY_MS        3000UL              // 検出結果の7セグ表示時間
struct MotionPattern {
    char name[MOTION_NAME_LEN];                      // パターン名（UTF-8）
    float centroid[MOTION_FEAT_DIM];                 // 正規化空間の重心
};

// --- Display Settings ---
#define DISPLAY_UPDATE_INTERVAL_MS  1000

// --- Test Display ---
#define TEST_DISPLAY_MIN_INDEX 1
extern const int TEST_PATTERN_COUNT;

// --- Display Mode ---
enum DisplayMode {
    DISPLAY_MODE_TIME,      // HH:MM
    DISPLAY_MODE_DATE,      // MMDD
    DISPLAY_MODE_WEEKDAY,   // MON/TUE/...
    DISPLAY_MODE_TEST,      // テスト（Phase 3 で使用）
    DISPLAY_MODE_COUNT
};

// --- ePaper 表示ビュー（Phase 9: 7セグ表示モードに連動）---
//   7seg=TIME    → EP_VIEW_CLOCK（標準）
//   7seg=DATE    → EP_VIEW_NOTIFICATION（通知。Phase 9 は「通知なし」スタブ）
//   7seg=WEEKDAY → EP_VIEW_DETAIL（詳細: 開始時刻/経過/現在日時/HIDキー）
enum EpaperView {
    EP_VIEW_NONE = -1,        // 未描画（初回強制描画用の番兵）
    EP_VIEW_CLOCK,            // 標準（7seg = TIME）
    EP_VIEW_NOTIFICATION,     // 通知（7seg = DATE）
    EP_VIEW_DETAIL,           // 詳細（7seg = WEEKDAY）
    EP_VIEW_UNSYNCED,         // 未同期
    EP_VIEW_PARKED            // 駐車中（Phase 14: 詳細表示を維持・電源OFF後の残像対策。描画統合は 14-C）
};

// --- Date Cache (日付計算のキャッシュ) ---
struct DateCache {
    int year;
    int month;
    int day;
    int weekday;
    uint32_t lastTimestamp;
    bool valid;
};

// --- HID switch state tracking ---
enum HidSwitchState {
    HID_STATE_IDLE,
    HID_STATE_PRESS,
    HID_STATE_REPEAT
};

// --- Maintenance Mode Menu ---
enum MaintenanceMenu {
    MAINTENANCE_MENU_CANCEL,        // Cancel (normal boot / reboot)
    MAINTENANCE_MENU_TEST,          // Test mode
    MAINTENANCE_MENU_DFU,           // DFU mode (OTA)
    MAINTENANCE_MENU_FACTORY_RESET, // Factory reset
    MAINTENANCE_MENU_COUNT          // Number of menus
};

struct MaintenanceState {
    bool active;                           // Maintenance mode is active
    MaintenanceMenu currentMenu;           // Current menu selection
    unsigned long lastInteractionMillis;   // Last interaction time (for timeout)
    uint8_t selectedMenuIndex;            // Current menu index (0-based)
};

struct HidSwitch {
    uint8_t gpio;
    uint8_t pinState;
    unsigned long lastDebounceTime;
    unsigned long pressStartTime;
    HidSwitchState state;
    uint16_t keyCode;
};

// --- Global Variables ---
extern TM1637Display* g_display;
extern volatile uint32_t g_currentTimestamp;   // JST換算のUnix timestamp
extern bool g_timeSynced;
extern DisplayMode g_displayMode;
extern unsigned long g_currentMillis;
extern unsigned long g_lastScreenMillis;
extern unsigned long g_lastCounterMillis;
extern DateCache g_dateCache;
extern unsigned long g_startupMillis;
extern char g_startupTimeStr[];   // 起動時刻(JST) "YYYY/MM/DD HH:MM"。初回時刻同期時に記録
#define EP_STARTUP_TIME_LEN 17    // "YYYY/MM/DD HH:MM" + null

#define NUM_HID_SWITCHES 7
extern HidSwitch hidSwitches[];
extern MaintenanceState g_maintenanceState;
extern bool g_showingCountdown;
extern uint16_t g_displayingKeyCode;
extern unsigned long g_keyCodeDisplayEndTime;
extern unsigned long g_lastModeChangeMillis;
#define MODE_AUTO_RETURN_TIMEOUT_MS 5000
extern int g_testDisplayIndex;

// --- スマホ通知表示（Phase 10: BLE受信→ePaper一時表示）---
// プロトコル: NOTIFY:app=<アプリ名>\n<テキスト>（UTF-8、上限200バイト、応答なし）
// 受信時に ePaper を通知表示へ自動切替し、一定時間後に元のモード（時計）へ復帰。
#define NOTIFICATION_DISPLAY_TIMEOUT_MS 30000UL  // 通知表示時間（30秒）
#define NOTIFY_APP_LEN   33    // アプリ名上限 32B + null（ログ/将来用。描画には未使用）
#define NOTIFY_TEXT_LEN  201   // 通知本文上限 200B + null

// 通知の文字数に応じたフォントサイズと拡大倍率の設定構造体
struct NotifyFontSetting {
    int maxChars;          // この文字数以下の場合に適用
    const uint8_t* font;   // 使用するフォント（u8g2_font_...）
    int scale;             // 拡大倍率（1〜3）
};

// ユーザーが簡単にフォントと拡大倍率の段階を設定・調整できる配列（文字数の昇順で定義すること）
// ※ 配列の最後は、それ以上のすべての長文をカバーするため十分に大きな文字数（例: 999）にしてください。
static const NotifyFontSetting NOTIFY_FONT_SETTINGS[] = {
    { 10,  u8g2_font_b16_t_japanese3, 3 },  // 16pxフォント3倍 48px
    { 24,  u8g2_font_b12_t_japanese3, 3 },  // 12pxフォント3倍 36px
    { 26,  u8g2_font_b16_t_japanese3, 2 },  // 16pxフォント2倍 32px
    {999,  u8g2_font_b12_t_japanese3, 2 }   //
};
#define NUM_NOTIFY_FONT_SETTINGS (sizeof(NOTIFY_FONT_SETTINGS) / sizeof(NOTIFY_FONT_SETTINGS[0]))

extern volatile bool g_notificationActive;      // 通知表示中フラグ
extern unsigned long g_notificationEndTime;     // 通知表示の終了時刻（millis()）
extern char g_notificationApp[];                // アプリ名
extern char g_notificationText[];               // 通知本文
extern volatile bool g_epaperRedrawRequested;   // ePaper 強制再描画要求（ビュー変更検出用）

// --- BMI160 IMU（Phase 14: 両脚スタンド検知用）---
extern bool g_imuEnabled;        // BMI160 接続・有効フラグ（false で既存機能へフォールバック）
// 直近のサンプル値（g/deg/s 換算済み）。Phase 14-B でリングバッファ履歴へ拡張予定。
extern float g_imuAx, g_imuAy, g_imuAz;       // 加速度 [g]
extern float g_imuGx, g_imuGy, g_imuGz;       // 角速度 [deg/s]
extern unsigned long g_imuLastSampleMillis;   // 最終サンプリング時刻（millis()）

// Phase 14-B: リングバッファ（int16 生LSB・6軸×500サンプル・循環）
extern ImuSample g_imuRingBuffer[IMU_RING_BUFFER_SIZE];  // 6000B
extern volatile uint16_t g_imuRingHead;                  // 次書込位置（0..499）
extern volatile uint16_t g_imuRingCount;                 // 有効サンプル数（0..500）
// Phase 14-B: IMU_DUMP 送信状態機械（loop() 内 updateImuDump() で進行）
extern bool g_imuDumpActive;             // 送信中フラグ
extern uint16_t g_imuDumpSeq;            // 次送信チャンク番号
extern uint16_t g_imuDumpTotal;          // 総チャンク数
extern uint16_t g_imuDumpSamplesToSend;  // 送信対象サンプル数（= 採取時点の count）
extern unsigned long g_imuDumpLastSend;  // 最終チャンク送信時刻（millis()）
extern uint8_t g_imuDumpRetry;           // 現チャンクのリトライ回数
extern bool g_imuRecordPending;            // IMU_RECORD_START: 10秒録音待機中
extern unsigned long g_imuRecordStartMillis; // 録音開始時刻

// Phase 2: モーション認識（bikeclock_esp32_motion.ino）
extern MotionPattern g_motionPatterns[MAX_MOTION_PATTERNS];  // 学習済みパターン
extern uint8_t g_motionPatternCount;                         // パターン数
extern float g_motionFeatMean[MOTION_FEAT_DIM];              // z-score 正規化パラメータ
extern float g_motionFeatStd[MOTION_FEAT_DIM];
extern bool g_motionModelReady;                              // モデル受信済み
extern char g_detectedPattern[MOTION_NAME_LEN];              // 直近の検出パターン名（空=不明）
extern int g_motionDisplayIndex;                             // 7セグ表示中のパターン番号（-1=無効）
extern unsigned long g_motionDisplayEndTime;                 // 7セグ表示の終了時刻
extern bool g_parkedDisplayActive;                          // 駐車中: ePaperを詳細表示で維持（走行検知で解除）

// --- Function Prototypes ---
// 時刻計算
int getHours();
int getMinutes();
int getSeconds();
int getMonth();
int getDay();
int getWeekday();
int getYear();

// 表示
void updateTimeDisplay();
void updateDateDisplay();
void updateWeekdayDisplay();
void updateDisplayForCurrentMode();
void displayVersion();
void encodeStringToSegments(const char* str, uint8_t* data);
void updateTestDisplay();

// ePaper表示（昼間視認性用）— bikeclock_esp32_epaper.ino
void setupEpaper();
void updateEpaperDisplay();

// システム
void updateTimestamp();
void updateDisplayAndLedState();
void recordStartupTime();   // 初回時刻同期時に起動時刻(JST)を g_startupTimeStr へ記録

// BMI160 IMU（Phase 14）— bikeclock_esp32_imu.ino
void setupIMU();      // Wire.begin + BMI160 初期化（接続失敗時 g_imuEnabled=false でフォールバック）
void updateIMU();     // 50Hz(20ms) サンプリング + 生値読出し + リングバッファ push（loop から毎回呼出）
void dumpIMU();       // 生値シリアルダンプ（IMU_DEBUG_DUMP フラグで切替）
void handleImuDump();      // Phase 14-B: IMU_DUMP 要求受信（送信状態を初期化・即リターン）
void updateImuDump();      // Phase 14-B: loop内でチャンク分割送信を進行（30ms間隔・リトライ付き）
void handleImuRecordStart(); // 未来録り要求受信（10秒後に handleImuDump を起動）
void updateImuRecord();      // loop内で10秒経過を監視し handleImuDump へ

// モーションパターン認識（Phase 2）— bikeclock_esp32_motion.ino
void loadMotionModel();                 // LittleFS からモデルをロード（起動時）
void handleMotionModelFrame(const uint8_t* data, size_t len);  // BLE onWrite からモデルフレーム受信
void updateMotionInference();           // loop から定周期で推論（結果を g_detectedPattern へ）

// 物理スイッチ & メンテナンスモード
void processHidSwitches();
void processFunctionKey();
void sendHidKeyPress(uint16_t keyCode, const char* unused = NULL);
void sendHidKeyRelease(const char* unused = NULL);
void enterMaintenanceMode();
void exitMaintenanceMode();
void updateMaintenanceDisplay();
bool processMaintenanceMode();

// ロギング
void setupLog();
void logPrint(const char* tag, const char* format, ...);

// --- Onboard LED State (Phase 2) ---
enum LedState {
    LED_STATE_BOOT,              // 起動: 赤固定
    LED_STATE_NO_SYNC,           // 未接続+未同期: 赤点滅(1s)
    LED_STATE_SYNCED,            // 未接続+同期: 緑固定
    LED_STATE_CONNECTED_NO_SYNC, // 接続+未同期: 青点滅(1s)
    LED_STATE_CONNECTED_SYNCED,  // 接続+同期: 青固定
    LED_STATE_ERROR              // エラー: 赤早点滅(0.2s)
};

extern LedState g_currentLedState;
extern bool g_deviceConnected;   // Phase 5 (BLE) で設定

// LED関数
void setupLed();
void updateLed();
void setLedState(LedState state);
void setLedColor(bool red, bool green, bool blue);
void updateLedStateBasedOnStatus();

// --- BLE Settings (Phase 5) ---
// デバイス名: ビルドフラグ -DUNIT_NAME=<name> でデバイス個別に指定可能。
//   未指定             → "BikeClock-ESP32"  (従来互換・テスト用)
//   -DUNIT_NAME=Living → "BikeClock-Living"
//   -DUNIT_NAME=0002   → "BikeClock-0002"   (xiao版 BikeClock-0001 と同形式)
// Androidアプリは "BikeClock-" 前方一致で複数デバイスを認識するため、
// 複数台のESP32-S3を別名で運用すれば設定画面で切り替えられる。
#define _BC_STR_INNER(s) #s
#define _BC_STR(s)       _BC_STR_INNER(s)
#ifdef UNIT_NAME
  #define BLE_DEVICE_NAME  "BikeClock-" _BC_STR(UNIT_NAME)
#else
  #define BLE_DEVICE_NAME  "BikeClock-ESP32"
#endif
#define BLE_SERVICE_UUID      "4fafc201-1fb5-459e-8fcc-c5c9c331914c"
#define BLE_CHAR_COMMAND_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a0"

// BLE関数プロトタイプ
void setupBLE();
void deinitBLE();
void sendResponse(const char* message);
bool sendBinary(const uint8_t* data, size_t len);   // Phase 14-B: バイナリ notify（IMUチャンク送信）
void handleTimeSync(const char* command);
void handleGetVersion();
void handleKeyConfig(const char* command);
void handleNotify(const char* command);   // Phase 10: NOTIFY:app=...\n<本文>（fire-and-forget）

// HID（Phase 6）— bikeclock_esp32_hid.ino
// NimBLEServer は前方宣言のみ（実体は hid.ino で使用時 include）
class NimBLEServer;
void setupHID(NimBLEServer* server);

// 設定永続化（Phase 4: LittleFS 実装）
#define KEYS_FILE_PATH "/keys.dat"
void setupFileSystem();
void loadSettings();
void saveSettings();
void resetKeySettingsToDefaults();
void resetToFactoryDefaults();

#endif // BIKECLOCK_H
