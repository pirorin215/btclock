/**
 * BikeClock ESP32-S3 - 物理スイッチ入力 & メンテナンスモード処理
 *
 * [Phase 3] スイッチ直接接続 & 検出
 *   - GPIO 直接入力 (内部プルアップ)
 *   - スイッチ SW1-SW7 (GPIO 4, 5, 13, 14, 35, 38, 39) の検出 (HID 送信はスタブ)
 *   - FUNC スイッチ (GPIO 8) によるモード切替と長押しによるメンテナンス遷移
 *   - メンテナンスモード内のメニュー動作と各種アクション (テストモード、再起動、OTA/Resetスタブ)
 */

#include "bikeclock.h"

// --- Global Variables ---
HidSwitch hidSwitches[] = {
    {SWITCH_SW1_GPIO, HIGH, 0, 0, HID_STATE_IDLE, DEFAULT_SW1_KEYCODE},
    {SWITCH_SW2_GPIO, HIGH, 0, 0, HID_STATE_IDLE, DEFAULT_SW2_KEYCODE},
    {SWITCH_SW3_GPIO, HIGH, 0, 0, HID_STATE_IDLE, DEFAULT_SW3_KEYCODE},
    {SWITCH_SW4_GPIO, HIGH, 0, 0, HID_STATE_IDLE, DEFAULT_SW4_KEYCODE},
    {SWITCH_SW5_GPIO, HIGH, 0, 0, HID_STATE_IDLE, DEFAULT_SW5_KEYCODE},
    {SWITCH_SW6_GPIO, HIGH, 0, 0, HID_STATE_IDLE, DEFAULT_SW6_KEYCODE},
    {SWITCH_SW7_GPIO, HIGH, 0, 0, HID_STATE_IDLE, DEFAULT_SW7_KEYCODE}
};

MaintenanceState g_maintenanceState = {
    false,                     // active
    MAINTENANCE_MENU_CANCEL,   // currentMenu
    0,                         // lastInteractionMillis
    0                          // selectedMenuIndex
};

bool g_showingCountdown = false;
uint16_t g_displayingKeyCode = 0;
unsigned long g_keyCodeDisplayEndTime = 0;
unsigned long g_lastModeChangeMillis = 0;
int g_testDisplayIndex = TEST_DISPLAY_MIN_INDEX;
volatile uint32_t g_funcClicks = 0;               // funcInputTask→loop: 未消費クリック数
volatile uint32_t g_lastFuncEdgeMs = 0;           // 最終クリック(解放)確定時刻: ePaper描画合成用
volatile bool     g_funcLongPressPending = false; // funcInputTask→loop: 長押し通知
static portMUX_TYPE g_funcMux = portMUX_INITIALIZER_UNLOCKED;

// FUNC入力デバウンスタスク(サンプリング統合方式):
//   5ms周期でレベルをサンプリングし、N回連続一致で確定 → チャタリング(押下/解放バウンス)を完全無視。
//   独立タスク(core0)のため、ePaper描画でloopTask(core1)がブロックされても
//   プリエンプト動作し、押下を取りこぼさない。
//   短押し(2秒未満)の解放でクリック計上。長押し(2秒以上)は g_funcLongPressPending で通知(クリックにはしない)。
void funcInputTask(void* arg) {
    const TickType_t SAMPLE_TICKS = pdMS_TO_TICKS(FUNC_SAMPLE_MS);
    const int STABLE_REQ = FUNC_DEBOUNCE_SAMPLES;
    int debounced = HIGH;     // 確定レベル(起動時=解放)
    int cnt = 0;              // 連続不一致カウンタ
    uint32_t pressMs = 0;
    bool longPressSignaled = false;
    for (;;) {
        int raw = digitalRead(SWITCH_FUNC_GPIO);
        if (raw == debounced) {
            cnt = 0;
        } else if (++cnt >= STABLE_REQ) {
            debounced = raw;            // 新レベルが安定 → 確定
            cnt = 0;
            if (debounced == LOW) {
                pressMs = millis();     // 押下確定
                longPressSignaled = false;
            } else if (!longPressSignaled) {
                // 解放確定: 長押しでなければクリック計上
                portENTER_CRITICAL(&g_funcMux);
                g_funcClicks++;
                g_lastFuncEdgeMs = millis();
                portEXIT_CRITICAL(&g_funcMux);
            }
        }
        // 長押し検出(保持中): main loopへ通知
        if (debounced == LOW && !longPressSignaled &&
            millis() - pressMs >= FUNC_LONGPRESS_MS) {
            longPressSignaled = true;
            g_funcLongPressPending = true;
        }
        vTaskDelay(SAMPLE_TICKS);
    }
}

// ※ sendHidKeyPress/Release は bikeclock_esp32_hid.ino（Phase 6）に実装

// --- スイッチの初期化 ---
void setupSwitches() {
    logPrint("SW", "Initializing physical switches (GPIO direct input)...");
    
    // 各スイッチピンを INPUT_PULLUP に設定
    for (int i = 0; i < NUM_HID_SWITCHES; i++) {
        pinMode(hidSwitches[i].gpio, INPUT_PULLUP);
    }
    pinMode(SWITCH_FUNC_GPIO, INPUT_PULLUP);
    xTaskCreatePinnedToCore(funcInputTask, "funcIn", 4096, NULL, 1, NULL, 0);  // core0で独立動作(ePaper描画ブロックに影響されない)
    
    delay(10); // プルアップ上昇時間待ち
    
    // スイッチの初期状態を読み込む
    for (int i = 0; i < NUM_HID_SWITCHES; i++) {
        hidSwitches[i].pinState = digitalRead(hidSwitches[i].gpio);
        hidSwitches[i].state = HID_STATE_IDLE;
        hidSwitches[i].lastDebounceTime = millis();
    }
    
    logPrint("SW", "Switches initialized successfully");
}

// --- HIDスイッチ処理 ---
void processHidSwitches() {
    // メンテナンスモード中はHID処理をスキップ
    if (g_maintenanceState.active) {
        return;
    }

    for (int i = 0; i < NUM_HID_SWITCHES; i++) {
        uint8_t reading = digitalRead(hidSwitches[i].gpio);

        // 離された瞬間（LOW->HIGH transition）にチャタリング防止タイマーを更新
        if (reading == HIGH && hidSwitches[i].pinState == LOW) {
            hidSwitches[i].lastDebounceTime = millis();
        }
        
        hidSwitches[i].pinState = reading;

        // 押された瞬間は応答速度を高めるためにデバウンスをスキップ
        bool skipDebounce = (hidSwitches[i].state == HID_STATE_PRESS);

        if (skipDebounce || (millis() - hidSwitches[i].lastDebounceTime) > HID_DEBOUNCE_DELAY_MS) {
            switch (hidSwitches[i].state) {
                case HID_STATE_IDLE:
                    if (reading == LOW) {
                        logPrint("HID", "SW%d Pressed (KeyCode: 0x%04X)", i + 1, hidSwitches[i].keyCode);
                        sendHidKeyPress(hidSwitches[i].keyCode, NULL);

                        // 7セグメントディスプレイに押されたキーコードを表示
                        g_displayingKeyCode = hidSwitches[i].keyCode;
                        g_keyCodeDisplayEndTime = millis() + 500; // 500ms
                        g_display->showNumberDec(hidSwitches[i].keyCode);

                        hidSwitches[i].state = HID_STATE_PRESS;
                    }
                    break;

                case HID_STATE_PRESS:
                    if (reading == HIGH) {
                        logPrint("HID", "SW%d Released", i + 1);
                        sendHidKeyRelease(NULL);
                        hidSwitches[i].state = HID_STATE_IDLE;
                    }
                    break;
            }
        }
    }
}

// --- 共通カウントダウン関数 ---
bool performCountdown(int startSeconds, int totalSeconds) {
    unsigned long pressStartTime = millis();

    while (digitalRead(SWITCH_FUNC_GPIO) == LOW && (millis() - pressStartTime < (totalSeconds * 1000UL))) {
        unsigned long elapsed = millis() - pressStartTime;
        int secondsLeft = startSeconds - (int)(elapsed / 1000);

        if (secondsLeft > 0) {
            // 7セグメントにカウントダウン表示
            g_display->showNumberDec(secondsLeft);

            // LED点滅で進捗フィードバック
            bool ledOn = ((elapsed / 200) % 2) == 0;
            if (secondsLeft <= 2) {
                // ラスト2秒：赤高速点滅
                setLedColor(ledOn, false, false);
            } else if (secondsLeft == 3) {
                // 3秒：黄点滅（赤＋緑）
                setLedColor(ledOn, ledOn, false);
            } else {
                // 4秒以上：緑点滅
                setLedColor(false, ledOn, false);
            }
        }
        delay(50);
    }

    // カウントダウン中にボタンが離されたか判定
    if (digitalRead(SWITCH_FUNC_GPIO) == HIGH) {
        return false; // 中断
    }

    return true; // 完了
}

// --- FUNCキー（モード切替）の処理 ---
// 入力検出(デバウンス＋長押し判定)は funcInputTask が担う。ここではイベント消費のみ。
void processFunctionKey() {
    // === 長押し(>=2秒)通知 → メンテナンスカウントダウン ===
    if (g_funcLongPressPending) {
        g_funcLongPressPending = false;
        portENTER_CRITICAL(&g_funcMux);
        g_funcClicks = 0;                          // 長押し中のクリックは無効
        portEXIT_CRITICAL(&g_funcMux);
        g_showingCountdown = true;
        logPrint("FUNC", "Long press detected - starting countdown");

        bool completed = performCountdown(3, 3);
        if (completed) {
            logPrint("FUNC", "Maintenance mode triggered successfully");
            g_display->showNumberDec(0000);         // "0000" 表示
            setLedColor(true, false, false);        // 赤LED
            delay(500);
            enterMaintenanceMode();
        } else {
            logPrint("FUNC", "Countdown aborted by user release");
            updateDisplayForCurrentMode();          // 中断時は通常表示へ復帰
            updateLedStateBasedOnStatus();
        }
        g_showingCountdown = false;
        return;
    }

    // === クリック消費（funcInputTaskが計上した解放クリック。連打分はまとめて処理）===
    if (g_funcClicks == 0) return;

    portENTER_CRITICAL(&g_funcMux);
    uint32_t clicks = g_funcClicks;
    g_funcClicks = 0;
    portEXIT_CRITICAL(&g_funcMux);
    if (clicks == 0) return;

    logPrint("FUNC", "Draining %lu FUNC click(s)", (unsigned long)clicks);
    for (uint32_t i = 0; i < clicks; i++) {
        if (g_maintenanceState.active) {
            // メンテナンス中: メニュー切替
            g_maintenanceState.selectedMenuIndex++;
            if (g_maintenanceState.selectedMenuIndex >= MAINTENANCE_MENU_COUNT) {
                g_maintenanceState.selectedMenuIndex = 0;
            }
            g_maintenanceState.currentMenu = static_cast<MaintenanceMenu>(g_maintenanceState.selectedMenuIndex);
            g_maintenanceState.lastInteractionMillis = millis();
            updateMaintenanceDisplay();
        } else if (g_displayMode == DISPLAY_MODE_TEST) {
            // テスト表示パターンの切り替え
            g_testDisplayIndex++;
            if (g_testDisplayIndex > TEST_PATTERN_COUNT) {
                g_testDisplayIndex = TEST_DISPLAY_MIN_INDEX;
            }
        } else {
            // 通常表示: FUNCモードを1つ進める（FUNC_MODE_TABLE が循環順の唯一の正）
            int curFuncIdx = 0;
            for (int j = 0; j < FUNC_MODE_COUNT; j++) {
                if (FUNC_MODE_TABLE[j].segDisplay == g_displayMode) {
                    curFuncIdx = j;
                    break;
                }
            }
            int nextFuncIdx = (curFuncIdx + 1) % FUNC_MODE_COUNT;
            g_displayMode = FUNC_MODE_TABLE[nextFuncIdx].segDisplay;
            g_lastModeChangeMillis = g_currentMillis;
        }
    }

    // バースト後の共通処理: 7セグ即時更新 + オーバーライド解除
    if (!g_maintenanceState.active) {
        if (g_displayMode == DISPLAY_MODE_TEST) {
            updateTestDisplay();
            logPrint("TEST", "Display pattern cycle: %d", g_testDisplayIndex);
        } else {
            // 駐車中表示中でもFUNC押下は尊重: 駐車表示を解除し普段通りに切替
            if (g_parkedDisplayActive) {
                g_parkedDisplayActive = false;
                g_epaperRedrawRequested = true;
                logPrint("MOTION", "FUNC pressed - exit parked display");
            }
            // 通知表示中でもFUNC押下は尊重: 通知を終了し普段通りに切替
            if (g_notificationActive) {
                g_notificationActive = false;
                g_notificationEndTime = 0;
                g_epaperRedrawRequested = true;
                logPrint("NOTIFY", "FUNC pressed - dismiss notification");
            }
            updateDisplayForCurrentMode();
            logPrint("FUNC", "Mode changed to: %d", g_displayMode);
        }
    }
}

// --- メンテナンスモード ---
void enterMaintenanceMode() {
    logPrint("MAINTENANCE", "Entering maintenance mode");
    g_maintenanceState.active = true;
    g_maintenanceState.currentMenu = MAINTENANCE_MENU_CANCEL;
    g_maintenanceState.selectedMenuIndex = 0;
    g_maintenanceState.lastInteractionMillis = millis();

    updateMaintenanceDisplay();
}

void exitMaintenanceMode() {
    logPrint("MAINTENANCE", "Exiting maintenance mode");
    g_maintenanceState.active = false;

    // 通常の時計表示に戻る
    g_displayMode = DISPLAY_MODE_TIME;
    updateDisplayForCurrentMode();
    updateLedStateBasedOnStatus();
}

void updateMaintenanceDisplay() {
    if (!g_maintenanceState.active) {
        return;
    }

    uint8_t data[] = { 0x00, 0x00, 0x00, 0x00 };

    switch (g_maintenanceState.currentMenu) {
        case MAINTENANCE_MENU_CANCEL:
            encodeStringToSegments("1BOO", data);
            break;

        case MAINTENANCE_MENU_TEST:
            encodeStringToSegments("2TST", data);
            break;

        case MAINTENANCE_MENU_DFU:
            encodeStringToSegments("3OTA", data);
            break;

        case MAINTENANCE_MENU_FACTORY_RESET:
            encodeStringToSegments("4RST", data);
            break;

        default:
            break;
    }

    g_display->setSegments(data);
}

bool processMaintenanceMode() {
    if (!g_maintenanceState.active) {
        return false;
    }

    unsigned long currentMillis = millis();

    // メンテナンスモード中はLEDを赤点滅 (500ms周期)
    if ((currentMillis / 250) % 2 == 0) {
        setLedColor(true, false, false);
    } else {
        setLedColor(false, false, false);
    }

    // 3秒間無操作でメニューアクション実行
    if (currentMillis - g_maintenanceState.lastInteractionMillis >= 3000) {
        logPrint("MAINTENANCE", "Menu %d selected (3s timeout)", g_maintenanceState.selectedMenuIndex + 1);

        switch (g_maintenanceState.currentMenu) {
            case MAINTENANCE_MENU_CANCEL:
                logPrint("MAINTENANCE", "Action: Reboot system");
                // 1111 表示で再起動を示す
                for (int i = 0; i < 3; i++) {
                    g_display->showNumberDec(1111);
                    delay(200);
                    g_display->clear();
                    delay(200);
                }
                logPrint("MAINTENANCE", "System restarting now...");
                Serial.flush();
                ESP.restart(); // 再起動
                break;

            case MAINTENANCE_MENU_TEST:
                logPrint("MAINTENANCE", "Action: Enter test mode");
                g_displayMode = DISPLAY_MODE_TEST;
                g_testDisplayIndex = TEST_DISPLAY_MIN_INDEX;
                g_maintenanceState.active = false;
                updateTestDisplay();
                updateLedStateBasedOnStatus();
                return false;

            case MAINTENANCE_MENU_DFU:
                logPrint("MAINTENANCE", "Action: Enter OTA DFU mode (Stub for Phase 3)");
                g_display->showNumberDec(3333);
                delay(3000);
                ESP.restart();
                break;

            case MAINTENANCE_MENU_FACTORY_RESET:
                logPrint("MAINTENANCE", "Action: Factory reset");
                g_display->showNumberDec(4444);  // 視覚フィードバック
                delay(1000);
                resetToFactoryDefaults();  // LittleFS消去 + デフォルト復元 + 再起動
                break;

            default:
                break;
        }
    }

    return true;
}
