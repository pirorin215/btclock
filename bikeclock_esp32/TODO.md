# BikeClock ESP32-S3 移植 TODO

Seeed XIAO BLE (nRF52840) 版 `bikeclock/` を **ESP32-S3 SuperMini** へ移植する作業計画。
リスクの低いもの（プラットフォーム非依存ロジック）から積み上げ、各フェーズで独立して動作確認できる粒度に分割している。

> **スコープ**: 本作業の主目的は **ファームウェア（ESP32）側の移植**。コアプロトコル（UUID・コマンド・応答）は XIAO 版と互換を維持する。ただし ESP32 版固有の追加機能（デバイス名 `BikeClock-ESP32` 化、複数デバイスの接続先選択など）に伴い、Androidアプリ `BTClockMob` も必要に応じて変更する（Phase 4 で実績あり）。

> **進捗サマリ**: Phase 0〜6 完了（時計表示・BLE時刻同期・HIDキーボード・2接続共存まで実機確認済み・v2.0.21）。**残りは Phase 7（WiFi OTA）・Phase 8（統合・調整）に加え、新機能として Phase 9〜11（ePaper 3モード連動＋通知転送）を追加**。

---

## 🔴 残タスク（未完了）

> 残る作業。優先度順。OTA は現状保留中。

### Phase 7 — WiFi OTA [リスク:中]
メンテナンスメニューの「3OTA」を ESP32 の WiFi OTA に差し替え。
- [ ] WiFi 接続（SSID/パスワード設定、接続状態表示）
- [ ] ArduinoOTA 導入（`ArduinoOTA.begin()` / `ArduinoOTA.handle()` in loop）
- [ ] または HTTPUpdateServer で Web 経由書き込み
- [ ] `startOtaDfuMode()` / `enterDfuMode()` を ESP32 OTA 起動に書き換え（現状 `MAINTENANCE_MENU_DFU` は Phase 3 のスタブ: `keys.ino` L282/339-340）
- [ ] メンテナンス「3OTA」選択時のフロー（WiFi 接続 → OTA 待ち受け）
- [ ] OTA 実行中表示（7seg / LED）
- **検証**: OTA モードで PC/スマホから無線書き込み成功、再起動で新ファームウェア

### Phase 8 — 統合 & 調整 [リスク:低]
残りのプラットフォーム依存を一括処理し、全体最適化。
- [x] `NVIC_SystemReset()` → `ESP.restart()` 全置換 — **※移植時点で `ESP.restart()` 使用済み、該当なし（実質完了）**
- [x] `BLEDfu` / Adafruit DFU 由来のコード削除 — **※移植時に除外済み、該当なし（実質完了）**
- [ ] メンテナンスメニュー各項目の動作確認（Boot/Test/OTA/Factory Reset）
- [ ] ファクトリーリセット（LittleFS.format + 再起動）動作確認
- [ ] ログ出力の整理（USB-CDC Serial）
- [ ] ディープスリープ（43μA）活用の検討（※オプション、バイク常時給電なら不要かも）
- [ ] README.md / EXTERNAL_LIBRARIES.md を ESP32 版として作成
- **検証**: 一連のユースケース（起動→アプリ接続→時刻同期→HID操作→OTA→工場リセット）を通しで確認

### Phase 9 — ePaper 3モード連動表示（マイコン単体） [リスク:中] — 実装完了（v2.0.22、実機検証待ち）
FUNCボタン短押しで 7セグLED のモード（TIME/DATE/WEEKDAY）を切り替えたとき、**ePaper の表示も連動して 3 種類に切り替わる**ようにする（現状は7セグのみ切替で ePaper は常時標準画面）。
- [x] モード連動: 7seg=TIME → ePaper 標準表示 ／ 7seg=DATE → ePaper 通知表示 ／ 7seg=WEEKDAY → ePaper 詳細表示
- [x] `updateEpaperDisplay()` を `g_displayMode` に応じた 3 描画関数のディスパッチャ化（`EpaperView` / `ep_lastView`）。モード切替時の即時再描画
- [x] 詳細表示（モードC）: 16px級フォントで ① 開始時刻＋経過時間 ② 現在日時（秒まで） ③ HIDキー設定（SW1〜7）。切替時の1回だけ描画（スナップショット）
- [x] 通知表示（モードB）のスタブ: 「通知なし」固定表示（本文受信は Phase 10）
- [x] 起動時刻（JST）を時刻同期完了時に記録（`g_startupTimeStr` / `recordStartupTime()`）。`getYear()` 追加
- [x] HIDキーコード → 人間可読名（Right/Enter 等）のマッピング（`KEY_NAME_TABLE` / `keyNameFromCode()`）
- **検証（実機）**: FUNC短押しで ePaper が 標準→通知(なし)→詳細 に切り替わる。詳細は分が変わっても更新されない。※ビルド成功（v2.0.22, 70%）
- **オートリターン**: DATE/WEEKDAY の5秒でTIME強制復帰は **維持（合意）**。モードB/C は5秒間表示後、標準に戻る

### Phase 10 — スマホ通知受信・表示（BLE） [リスク:中]
Phase 9 の通知表示スタブを本実装し、BLE 経由でスマホからの通知を受信して ePaper に表示する（マイコン側）。
- [ ] BLE 受信ハンドラへ通知コマンド追加。プロトコル: `NOTIFY:app=<アプリ名>\n<テキスト>`（UTF-8、上限200バイト、応答なし＝ファイア＆フォーゲット）
- [ ] 通知受信時、現在のモードに関わらず **ePaper を通知表示に自動切替** し、一定時間（例: 30秒）後に **元のモード画面へ自動復帰**
- [ ] 通知表示本体: `epaper_test` の `drawWrappedText()`（u8g2_font_unifont_t_japanese3、自動折り返し）を移植。分割線なしでテキスト埋め
- [ ] 表示中モード管理: 「ユーザ選択のベースビュー」と「通知割り込みで一時表示するビュー」の分離
- **検証**: BLE で `NOTIFY:app=Test\nテスト` を送ると、標準画面にいても通知表示に切り替わり30秒で復帰する

### Phase 11 — Android 通知転送（NotificationListenerService） [リスク:中]
Phase 10 のプロトコルをアプリ側から送る。スマホの通知を BikeClock の ePaper に転送する。
- [ ] `NotificationListenerService` サブクラスを新規作成（マニフェスト宣言＋`BIND_NOTIFICATION_LISTENER_SERVICE`）
- [ ] `onNotificationPosted` でアプリ名+タイトル+テキストを取り出し `NOTIFY:` ペイロード生成 → 既存 `BleRepository.sendCommand()` で送信（DI は Koin の `KoinComponent` 経由。同一プロセス運用）
- [ ] 自己アプリ通知の除外・200バイト切り詰め・簡易デバウンス
- [ ] AppSettings に「通知アクセス」許可誘導 UI 追加（`Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`）
- **検証**: 通知アクセス許可後、LINE 等の通知で BikeClock の ePaper にアプリ名+テキストが表示される

> **通知プロトコル（Phase 10/11 共通）**: プレフィックス `NOTIFY:` ／ アプリ名 `app=<名>` ／ 区切り `\n` 1個 ／ 上限 200バイト(UTF-8) ／ 応答なし（ファイア＆フォーゲット）

---

## 決定事項（合意済み）

| 項目 | 決定内容 |
|------|---------|
| ボード | ESP32-S3 SuperMini (ESP32S3FH4R2) |
| 配置 | 新規ディレクトリ `bikeclock_esp32/`（XIAO BLE 版は温存） |
| BLE | **NimBLE-Arduino**（HID + カスタムGATT） |
| OTA | **WiFi OTA**（ArduinoOTA / HTTP 経由）で実装 |
| アプリ | **BTClockMob は必要に応じて変更**（コアプロトコルは互換維持・デバイス名は `BikeClock-ESP32`、アプリは `BikeClock-` 前方一致で検出） |

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
| 物理スイッチ | SW2 | 39 | 内部プルアップ使用 |
| 物理スイッチ | SW3 | 13 | 内部プルアップ使用 |
| 物理スイッチ | SW4 | 14 | 内部プルアップ使用 |
| 物理スイッチ | SW5 | 35 | 内部プルアップ使用 |
| 物理スイッチ | SW6 | 38 | 内部プルアップ使用 |
| 物理スイッチ | SW7 | 5 | 内部プルアップ使用 |
| 物理スイッチ | SW8 (FUNC) | 8 | 内部プルアップ使用 |

空き: GPIO 0, 9, 15, 16, 17, 18, 21, 33, 34, 36, 37, 40, 41, 45-47（0 は boot ピン注意 / Serial は USB-CDC を使用）

### アプリ互換性の維持要件（必ず守る）
- デバイス名: `BikeClock-ESP32`（XIAO版 `BikeClock-0001` と区別。アプリは `BikeClock-` 前方一致で検出・接続先選択可能）
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
| HID | `BLEHidAdafruit` | NimBLE HID (`NimBLEHIDDevice`) |
| ファイル | `InternalFS` (Adafruit LittleFS) | LittleFS (ESP32) または Preferences(NVS) |
| リセット | `NVIC_SystemReset()` | `ESP.restart()` |
| OTA | `enterOTADfu()` / `BLEDfu` | WiFi OTA (ArduinoOTA) |
| オンボードLED | 3色LED common anode (`LED_RED/GREEN/BLUE`) | GPIO48 RGB LED (WS2812, 1個) |
| SPI ピン | 固定(D8/D9/D10) | 不要 (ePaper用に専用SPI3_HOSTを任意指定) |
| TM1637 / 時刻計算 / 7seg / メンテナンス | — | **ロジックはそのまま流用** |
| スイッチ入力 | MCP23S17 経由 (SPI) | GPIO直接入力 (内部プルアップ) |

---

## ✅ 完了済みフェーズ（アーカイブ）

> 実装完了済み。参考記録として残す。学び（⚠）は再発防止用。

### グループ1: 時計としてアプリと繋がる（最優先）

#### Phase 0 — スケルトン & ビルド環境 [リスク:最低] ✅ 完了
- [x] `bikeclock_esp32.ino`（空の setup/loop）、`bikeclock.h`、`compile.sh` / `upload.sh` / `consolelog.sh` / `common.sh` / `setting.sh.example` 作成
- [x] FQBN 確定: `esp32:esp32:esp32s3:CDCOnBoot=cdc` / esp32 core 3.3.8 / `CLAUDE.md`（ビルドルール）作成
- **検証**: `sh compile.sh` 成功 ✅

#### Phase 1 — 表示 & 時刻ロジック [リスク:低] ✅ 完了
- [x] `bikeclock.h`: ピン定義 + TM1637 include、DisplayMode/DateCache/プロトタイプ追加
- [x] `bikeclock_esp32.ino`: タイムスタンプ更新・時刻計算を移植、`bikeclock_esp32_display.ino`（TM1637 表示・7seg）
- [x] 起動時バージョン表示、未同期時の「8888 点滅」、ファームウェア 2.0.0 → 2.0.1
- **検証**: ビルド成功 ✅ / 実機表示確認 ✅

#### Phase 2 — オンボード LED（GPIO48 RGB） [リスク:低] ✅ 完了
- [x] Adafruit NeoPixel 1.15.5 で WS2812 RGB LED 1px 制御（`bikeclock_esp32_led.ino`）
- [x] `LedState`（BOOT/NO_SYNC/SYNCED/CONNECTED_*/ERROR）の色・点滅パターン維持
- **検証**: ビルド成功 ✅ / 実機LED確認 ✅（起動後 緑固定 = SYNCED）

#### Phase 2.5 — ePaper 表示（昼間視認性） [リスク:低〜中] ✅ 完了
- [x] `bikeclock_esp32_epaper.ino`: GxEPD2_213_B74(BW) + U8g2_for_Adafruit_GFX（日本語）
- [x] 専用 SPI3_HOST バス（CS=1, DC=2, RST=3, BUSY=10, SCK=12, MOSI=11）
- [x] 表示: 時刻(大)+曜日+日付、毎分フル更新でにじみ防止 / 未同期→「時刻未同期」
- **検証**: ビルド成功 ✅ / 実機ePaper表示確認（要配線: VCC→3V3, GND→GND, CS→1, DC→2, RES→3, BUSY→10, SCL/SCK→12, SDA/MOSI→11）

> ⚠ **学び**: GxEPD2 はデフォルトでグローバル `SPI`(FSPI=SPI2) を使う。別ホストを使う場合は `epd2.selectSPI(SPIClass&, SPISettings)` で差替え、かつ `init()` 前に自前で `epdSPI.begin(カスタムピン)` を呼ぶ（`init()` 内の `begin()` は既起動なら即returnしピンを保持するため、ESP32-S3 core 3.3.8 で確認済み）。

#### Phase 5 — BLE カスタム GATT（時刻同期・キー設定） [リスク:中] ✅ 完了
- [x] NimBLE-Arduino 2.5.0 / `bikeclock_esp32_ble.ino`: NimBLEServer + Service(`4fafc201`) + Command char(`beb5483e-...26a0`, R/W/N)
- [x] `SET:time:` / `SET:keys:` / `GET:version` 処理、`sendResponse()`（notify）、接続/切断/Write/CCCD コールバック
- [x] アドバタイズ: デバイス名 + カスタムサービス + 切断時再アドバタイズ
- **検証**: ビルド成功 ✅ / **BTClockMob 接続・時刻同期 成功** ✅

> ⚠ **学び**: NimBLE のアドバタイズはデフォルトでデバイス名を含まない。アプリの name フィルタで検出されるよう `setName()` + `enableScanResponse(true)` が必須（Bluefruit とは異なる点）。

### グループ2: 物理スイッチ・HID・OTA

#### Phase 3 — スイッチ直接接続 & 検出 [リスク:低] ✅ 完了
- [x] スイッチピン(GPIO 4,39,13,14,35,38,5, FUNC=8)を `INPUT_PULLUP` でセットアップ
- [x] デバウンス付き `processHidSwitches()` / `processFunctionKey()`（GPIO直読み）
- [x] FUNC (GPIO 8) 長押し → メンテナンスモード遷移
- **検証**: ビルド成功 ✅（HID送信は当時スタブ → Phase 6 で本実装）

#### Phase 4 — 設定永続化（LittleFS） [リスク:低] ✅ 完了
- [x] `<LittleFS.h>` で `/keys.dat` 読み書き（uint16_t × 7 のバイナリ）
- [x] `loadSettings()` / `saveSettings()` / `resetKeySettingsToDefaults()` / `resetToFactoryDefaults()`
- **検証**: ビルド成功 ✅（キー設定保存→復元、ファクトリーリセットの最終実機確認は Phase 8 で）

#### Phase 6 — BLE HID キーボード [リスク:高] ★技術的に最難関 ✅ 完了
- [x] NimBLE で HID-over-GATT サービス(0x1812)構築（`NimBLEHIDDevice` + Report Map 自前定義）
- [x] キーボードレポート（Report ID 1, 8B）/ コンシューマキー（Report ID 2, 2B: Back/再生 等）送信
- [x] アドバタイズに HID(0x1812) + appearance = HID Keyboard(0x03C1) 追加
- [x] ボンディング対応 — `setSecurityAuth(true,false,true)` + Just Works（HID Input Report が `READ_ENC` で生成されるため bonding 必須）
- [x] `sendHidKeyPress/Release` 本実装（新規 `bikeclock_esp32_hid.ino`）
- [x] HID 接続 と GATT 接続 の **2接続共存** 確認
- **検証**: ビルド成功 ✅（v2.0.21, 70%）/ **実機確認 ✅**: SW1-7 で矢印/Enter/Back/再生が入力され、同時に BTClockMob の時刻同期も維持（2接続共存確認）

> ⚠ **学び**: `NimBLEHIDDevice` は既存 `NimBLEServer` に HID/DeviceInfo/Battery サービスを追加するため、カスタムGATT と同一サーバーで共存できる（2接続問題は構造的に解決）。Report Map は NimBLE では自前必須（Bluefruit は自動生成）。コンシューマ AC Back(0x224) は Logical Max `0x02FF` / 16-bit LE 送信でカバー。

---

## 進め方

1. **グループ1（Phase 0→1→2→2.5→5）は完了**。グループ2（Phase 3→4→6）も完了。**残り Phase 7（OTA）→ Phase 8（統合・調整）**。
2. **1 フェーズずつ**実装。各フェーズの完了で `sh compile.sh` が通り、実機で検証項目をクリアしてから次へ。
3. 進捗はこのファイルのチェックボックス `[ ] → [x]` を更新して可視化。
4. 各フェーズ開始時に「Phase N をやる」と宣言し、TODO の該当項目に取り掛かる。
5. 詰まったら、そのフェーズ内で調査 → 必要なら設計見直し。次のフェーズには進まない。

## メモ・懸念

- **WiFi と BLE の同時使用**: ESP32-S3 は 2.4GHz 共有アンテナ。OTA(WiFi) 使用時のみ WiFi ON、通常は BLE 専用で消費電力を抑える設計が無難（Phase 7 で考慮）。
- **電源**: 現状は USB 給電（5V）前提。ディープスリープは本プロジェクトの要件次第（バイク常時給電なら不要）。
- **Phase 6 (HID) のボンディング**: Just Works + bonding で実機動作確認済み。ペアリング後の再接続（ボンディング維持）の長期安定性は運用で継続確認。
