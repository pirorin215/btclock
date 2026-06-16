# BikeClock ESP32-S3 移植 TODO

Seeed XIAO BLE (nRF52840) 版 `bikeclock/` を **ESP32-S3 SuperMini** へ移植する作業計画。
リスクの低いもの（プラットフォーム非依存ロジック）から積み上げ、各フェーズで独立して動作確認できる粒度に分割している。

> **スコープ**: 本作業は **ファームウェア（ESP32）側に完全に閉じる**。Androidアプリ `BTClockMob` は **一切変更しない（完全互換）**。UUID・プロトコル・デバイス名を維持すれば、アプリはそのまま接続・動作する。

---

## 決定事項（合意済み）

| 項目 | 決定内容 |
|------|---------|
| ボード | ESP32-S3 SuperMini (ESP32S3FH4R2) |
| 配置 | 新規ディレクトリ `bikeclock_esp32/`（XIAO BLE 版は温存） |
| BLE | **NimBLE-Arduino**（HID + カスタムGATT） |
| OTA | **WiFi OTA**（ArduinoOTA / HTTP 経由）で実装 |
| アプリ | **BTClockMob は変更不要**（完全互換・UUID/プロトコル/デバイス名を維持） |

### ピン割り当て（推奨案A）
| 用途 | 信号 | GPIO | 備考 |
|------|------|------|------|
| TM1637 | DIO | 6 | |
| TM1637 | CLK | 7 | |
| ePaper (Phase 2.5) | CS | 1 | |
| ePaper (Phase 2.5) | DC | 2 | |
| ePaper (Phase 2.5) | RST | 3 | |
| ePaper (Phase 2.5) | BUSY | 10 | |
| ePaper (Phase 2.5) | SCK (専用SPI3) | 12 | |
| ePaper (Phase 2.5) | MOSI (専用SPI3) | 11 | |
| オンボードLED | RGB (WS2812) | 48 | （ボード固定） |
| 物理スイッチ | SW1 | 4 | 内部プルアップ使用 |
| 物理スイッチ | SW2 | 5 | 内部プルアップ使用 |
| 物理スイッチ | SW3 | 13 | 内部プルアップ使用 |
| 物理スイッチ | SW4 | 14 | 内部プルアップ使用 |
| 物理スイッチ | SW5 | 35 | 内部プルアップ使用 |
| 物理スイッチ | SW6 | 38 | 内部プルアップ使用 |
| 物理スイッチ | SW7 | 39 | 内部プルアップ使用 |
| 物理スイッチ | SW8 (FUNC) | 8 | 内部プルアップ使用 |

空き: GPIO 0, 9, 15, 16, 17, 18, 21, 33, 34, 36, 37, 40, 41, 45-47（0 は boot ピン注意 / Serial は USB-CDC を使用）

### アプリ互換性の維持要件（必ず守る）
- デバイス名: `BikeClock-0001`
- Service UUID: `4fafc201-1fb5-459e-8fcc-c5c9c331914c`
- Command char: `beb5483e-36e1-4688-b7f5-ea07361b26a0`（READ | WRITE | NOTIFY + CCCD）
- コマンド: `SET:time:<ts>`, `SET:keys:HEX,...`, `GET:version`
- 応答: `OK:version:x.y.z`, `OK: Time synced`, `OK: keys updated`, `ERROR: ...`
- HID(0x1812) をアドバタイズに含め、appearance = HID Keyboard（OS ペアリング用）
- ※ Response/Switch UUID(`...26a2`/`...26a1`)は**アプリ未使用**。実装しなくてよい（応答は Command char の notify で行う）

---

## プラットフォーム依存の洗い出し（移行マッピング）

| 機能 | XIAO BLE (現状) | ESP32-S3 (移行先) |
|------|-----------------|-------------------|
| BLE | `<bluefruit.h>` (Adafruit) | NimBLE-Arduino |
| HID | `BLEHidAdafruit` | NimBLE HID (`BLEHIDDevice`) |
| ファイル | `InternalFS` (Adafruit LittleFS) | LittleFS (ESP32) または Preferences(NVS) |
| リセット | `NVIC_SystemReset()` | `ESP.restart()` |
| OTA | `enterOTADfu()` / `BLEDfu` | WiFi OTA (ArduinoOTA) |
| オンボードLED | 3色LED common anode (`LED_RED/GREEN/BLUE`) | GPIO48 RGB LED (WS2812, 1個) |
| SPI ピン | 固定(D8/D9/D10) | 不要 (ePaper用に専用SPI3_HOSTを任意指定) |
| TM1637 / 時刻計算 / 7seg / メンテナンス | — | **ロジックはそのまま流用** |
| スイッチ入力 | MCP23S17 経由 (SPI) | GPIO直接入力 (内部プルアップ) |

---

## フェーズ構成（優先順位順）

> **実行順序 = グループ1 → グループ2**。フェーズ番号は内容のID（飛び飛びになるが、記述は実行順に並べてある）。各フェーズ完了ごとにビルド＆実機確認し、TODO をチェックしてから次へ。

### ===== グループ1: 時計としてアプリと繋がる（最優先） =====

#### Phase 0 — スケルトン & ビルド環境 [リスク:最低] ✅ 完了
空のプロジェクトを ESP32-S3 向けにビルドできる状態を作る。
- [x] `bikeclock_esp32.ino`（空の setup/loop のみ）、`bikeclock.h`（最小）作成
- [x] `compile.sh` / `upload.sh` / `consolelog.sh` / `common.sh` / `setting.sh.example` を作成
  - FQBN 確定: `esp32:esp32:esp32s3:CDCOnBoot=cdc`
- [x] `CLAUDE.md`（ビルドルール）作成
- [x] arduino-cli に esp32 core 確認 → **3.3.8 インストール済み**（要追加作業なし）
- [x] `.gitignore` に `bikeclock_esp32/setting.sh` 追加
- **検証**: `sh compile.sh` 成功（308820バイト, 23%, 25.8s）✅

#### Phase 1 — 表示 & 時刻ロジック [リスク:低] ✅ 完了
**完全にプラットフォーム非依存**のロジックを XIAO 版から丸ごと移植。
- [x] `bikeclock.h`: ピン定義（Phase 0 済み）+ TM1637 include、DisplayMode/DateCache/プロトタイプ追加
- [x] `bikeclock_esp32.ino`: タイムスタンプ更新・時刻計算（`getHours/getMonthDay/曜日`）を移植
- [x] `bikeclock_esp32_display.ino`（新規）: TM1637 表示（TIME/DATE/WEEKDAY/version）、7seg エンコード
- [x] `displayVersion()` 移植、起動時バージョン表示
- [x] ファームウェアバージョン 2.0.0 → 2.0.1
- [x] 未同期時の「8888 点滅」表示（`TEST_FIXED_TIME=0` で確認可能）
- **検証**: ビルド成功 ✅ / 実機表示確認 ✅（数字表示・秒点滅 確認済み）

#### Phase 2 — オンボード LED（GPIO48 RGB） [リスク:低] ✅ 完了
XIAO 版の 3 色 common anode LED から、ESP32-S3 の **WS2812 RGB LED 1個** へ差し替え。
- [x] LED 駆動ライブラリ選定（Adafruit NeoPixel 1.15.5）
- [x] `bikeclock_esp32_led.ino`（新規）: `setupLed/setLedColor/updateLed/updateLedStateBasedOnStatus` を NeoPixel 1px 制御で実装
- [x] `LedState`（BOOT/NO_SYNC/SYNCED/CONNECTED_*/ERROR）の色・点滅パターン維持（XIAO 版ロジック流用）
- [x] `setup()` に `setupLed()` + `updateLedStateBasedOnStatus()`、`loop()` に `updateLed()` 追加
- [x] ファームウェアバージョン 2.0.1 → 2.0.2
- **検証**: ビルド成功 ✅ / 実機LED確認 ✅（起動後 緑固定 = SYNCED 状態 確認済み）

#### Phase 2.5 — ePaper 表示（昼間視認性） [リスク:低〜中] ✅ 完了
TM1637 7セグLEDは昼間の太陽光下で視認性が悪いため、WeAct 2.13" ePaper(BW) を追加して併用。時刻ソース・BLE・アプリ互換性はそのまま。
- [x] `bikeclock_esp32_epaper.ino`（新規）: GxEPD2_213_B74(BW) + U8g2_for_Adafruit_GFX（日本語）
- [x] 専用 SPI3_HOST バス（`SPIClass epdSPI(HSPI)`）。`epdSPI.begin()` → `epd2.selectSPI()` → `init()` の順で専用ピン確保
- [x] ピン割当: CS=1, DC=2, RST=3, BUSY=10, SCK=12, MOSI=11（SPI3_HOSTバスを使用）
- [x] 表示内容: 時刻(大, `logisoso46_tn`+手描画コロン) + 曜日(月曜日…) + 日付(○月○日)（`unifont_t_japanese3`）
- [x] 更新戦略: 時刻/日付変化/同期時に全画面フル更新を行い、文字のにじみや薄化を防止 / 未同期→「時刻未同期」
- [x] `setup()` に `setupEpaper()`（ブートスプラッシュ）、`loop()` に `updateEpaperDisplay()` 追加
- [x] ファームウェアバージョン 2.0.4 → 2.0.5
- **検証**: ビルド成功 ✅ / 実機ePaper表示確認（要配線）
  - 配線: ePaper(VCC→3V3, GND→GND, CS→1, DC→2, RES→3, BUSY→10, SCL/SCK→12, SDA/MOSI→11)

> **学び**: GxEPD2 はデフォルトでグローバル `SPI`(FSPI=SPI2) を使う。別ホストを使う場合は `epd2.selectSPI(SPIClass&, SPISettings)` で差替え、かつ `init()` 前に自前で `epdSPI.begin(カスタムピン)` を呼ぶ（`init()` 内の `begin()` は既起動なら即returnしピンを保持するため、ESP32-S3 core 3.3.8 で確認済み）。

#### Phase 5 — BLE カスタム GATT（時刻同期・キー設定） [リスク:中] ✅ 完了
NimBLE でカスタムサービスを立て、**BTClockMob と時刻同期できる**状態を目指す（HID はまだ）。
- [x] NimBLE-Arduino 2.5.0（インストール済み）導入
- [x] `bikeclock_esp32_ble.ino`（新規）: NimBLEServer + Service(`4fafc201`) + Command char(`beb5483e-...26a0`, R/W/N)
- [x] 接続/切断（ServerCallbacks）/ Write・CCCD（CharCallbacks）コールバック
- [x] `SET:time:` / `SET:keys:` / `GET:version` コマンド処理
- [x] `sendResponse()`（Command char の notify）
- [x] アドバタイズ: デバイス名 `BikeClock-0001`（`setName`+`enableScanResponse`）+ カスタムサービス + 切断時再アドバタイズ
- [x] `g_deviceConnected` / `g_timeSynced` 連携、LED 状態遷移
- [x] `loadSettings/saveSettings` は Phase 4 で LittleFS 本実装済み。`SET:keys` で hidSwitches 更新＋保存
- [x] ファームウェアバージョン 2.0.2 → 2.0.4
- **検証**: ビルド成功 ✅ / **BTClockMob 接続・時刻同期 成功** ✅（Time synced、現在時刻表示確認済み）

> **学び**: NimBLE のアドバタイズはデフォルトでデバイス名を含まない。アプリの name フィルタで検出されるよう `setName()` + `enableScanResponse(true)` が必須（Bluefruit とは異なる点）。

### ===== グループ2: 物理スイッチ・HID・OTA =====

#### Phase 3 — スイッチ直接接続 & 検出 [リスク:低] ✅ 完了
入力検出まで。HID 送信は Phase 6 で繋ぐため、ここではログ出力スタブ。
- [x] スイッチピン(GPIO 4, 5, 8, 13, 14, 35, 38, 39)を `INPUT_PULLUP` でセットアップ
- [x] チャタリング防止（デバウンス）を考慮した入力読み取りロジックの実装
- [x] `processHidSwitches()` / `processFunctionKey()` ロジック移植（GPIO直読みに変更）
- [x] `sendHidKeyPress/Release` は **ログのみのスタブ**（BLE HID 未実装のため）
- [x] FUNC (GPIO 8) 長押し → メンテナンスモード遷移（DFU 項目は Phase 7 で仮表示）
- **検証**: ビルド成功 ✅ / 実機検証用コード実装完了（ログ、カウントダウン、テストモード、1BOO〜4RST遷移動作確認可能）

#### Phase 4 — 設定永続化（LittleFS） [リスク:低] ✅ 完了
`InternalFS` → ESP32 LittleFS。Phase 5 のスタブを本実装に差し替え。
- [x] `<LittleFS.h>` で `/keys.dat` 読み書き
- [x] `loadSettings()` / `saveSettings()` を File API に合わせて書き換え（Phase 5 のスタブと置換）
- [x] `resetKeySettingsToDefaults()` のフォーマット処理を `LittleFS.format()` に
- [x] ファイル不存在時はデフォルトキーコードを使用（現状ロジック踏襲）
- **検証**: ビルド成功 ✅ / キー設定保存→再起動で復元、ファクトリーリセットで初期化（要実機確認）

#### Phase 6 — BLE HID キーボード [リスク:高] ★技術的に最難関
NimBLE HID を実装し、物理スイッチで Android を操作できるようにする。
- [ ] NimBLE で HID-over-GATT サービス(0x1812)構築
- [ ] キーボードレポート（`keyboardReport` 相当）送信
- [ ] コンシューマキー（メディアキー/Back 等）送信
- [ ] アドバタイズに HID + **appearance = HID Keyboard** 追加
- [ ] ボンディング（ペアリング）対応 — Android OS が HID として認識・ペアリング
- [ ] `sendHidKeyPress/Release` のスタブを本実装に置換
- [ ] HID 接続 と GATT 接続 の **2つの独立接続の共存** 確認
- **検証**: Android の Bluetooth 設定で `BikeClock-0001` をペアリング → スイッチ操作で矢印/Enter/再生等が入力される。同時に BTClockMob の時刻同期も維持されるか

#### Phase 7 — WiFi OTA [リスク:中]
メンテナンスメニューの「3OTA」を ESP32 の WiFi OTA に差し替え。
- [ ] WiFi 接続（SSID/パスワード設定、接続状態表示）
- [ ] ArduinoOTA 導入（`ArduinoOTA.begin()` / `ArduinoOTA.handle()` in loop）
- [ ] または HTTPUpdateServer で Web 経由書き込み
- [ ] `startOtaDfuMode()` / `enterDfuMode()` を ESP32 OTA 起動に書き換え
- [ ] メンテナンス「3OTA」選択時のフロー（WiFi 接続 → OTA 待ち受け）
- [ ] OTA 実行中表示（7seg / LED）
- **検証**: OTA モードで PC/スマホから無線書き込み成功、再起動で新ファームウェア

#### Phase 8 — 統合 & 調整 [リスク:低]
残りのプラットフォーム依存を一括処理し、全体最適化。
- [ ] `NVIC_SystemReset()` → `ESP.restart()` 全置換
- [ ] メンテナンスメニュー各項目の動作確認（Boot/Test/OTA/Factory Reset）
- [ ] ファクトリーリセット（LittleFS.format + 再起動）動作確認
- [ ] `BLEDfu` / Adafruit DFU 由来のコード削除
- [ ] ログ出力の整理（USB-CDC Serial）
- [ ] ディープスリープ（43μA）活用の検討（※オプション、バイク常時給電なら不要かも）
- [ ] README.md / EXTERNAL_LIBRARIES.md を ESP32 版として作成
- **検証**: 一連のユースケース（起動→アプリ接続→時刻同期→HID操作→OTA→工場リセット）を通しで確認

---

## 進め方

1. **グループ1（Phase 0→1→2→5）を先に完成**させ「時計としてアプリと時刻同期できる」状態を作る。その後 **グループ2（Phase 3→4→6→7→8）** で HID・OTA 等を積み上げる。
2. **1 フェーズずつ**実装。各フェーズの完了で `sh compile.sh` が通り、実機で検証項目をクリアしてから次へ。
3. 進捗はこのファイルのチェックボックス `[ ] → [x]` を更新して可視化。
4. 各フェーズ開始時に「Phase N をやる」と宣言し、TODO の該当項目に取り掛かる。
5. 詰まったら、そのフェーズ内で調査 → 必要なら設計見直し。次のフェーズには進まない。

## メモ・懸念

- **Phase 6 (HID) が最大のリスク**: NimBLE での HID とカスタム GATT の 2 接続共存、ボンディング挙動は実機検証が必須。XIAO 版 Bluefruit と Android のペアリング挙動を再現できるか要確認。
- **Phase 5 → Phase 4 の依存**: Phase 5 では `loadSettings/saveSettings` を空スタブにし、`SET:keys` はメモリ保持のみ。Phase 4 で LittleFS 実装後にスタブを差し替える。時刻同期（Phase 5 の主目的）は永続化不要なので問題ない。
- **WiFi と BLE の同時使用**: ESP32-S3 は 2.4GHz 共有アンテナ。OTA(WiFi) 使用時のみ WiFi ON、通常は BLE 専用で消費電力を抑える設計が無難。
- **電源**: 現状は USB 給電（5V）前提。ディープスリープは本プロジェクトの要件次第（バイク常時給電なら不要）。
