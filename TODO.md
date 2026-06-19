# BikeClock プロジェクト TODO

マイコン firmware（`bikeclock_esp32`）と Android アプリ（`BTClockMob`）の両方を管理するプロジェクト全体のTODO。
**Phase 0〜9 は ESP32-S3 への移植と ePaper 機能（マイコン完結）**。**Phase 10 以降は両プロジェクトにまたがる機能**（マイコン＋Android 両方の修正が必要）。コアプロトコル（UUID・コマンド・応答）は XIAO 版と互換を維持する。

---

## 🔵 次期機能（計画中）

### Phase 14 — BMI160 両脚スタンド（駐車）検知 [リスク:中]

**目的（残像問題の解決）**: BikeClock はイグニッション連動の USB 給電で、キーを抜くと電源 OFF する。ePaper は電子ペーパーの特性上**最後に描いた画面が残像として残る**ため、標準の時刻表示のまま電源が切れると「現在時刻でない噓の時刻」が残る。クロスカブを駐輪する際の**両脚スタンド（センタースタンド）を立てる「バイク後方斜め上への押し上げ動作」**を 6 軸 IMU（GY-BMI160）で検知し、**ePaper を「詳細表示（前回の乗車記録）」に切替えて保持**することで、電源 OFF 後の残像を正確な乗車記録にする。マイコン完結（アプリ変更なし）。

**決定事項 **

| 項目 | 決定内容 |
|------|---------|
| センサー | GY-BMI160（BMI160: 3軸加速度＋3軸ジャイロ）。I2C 接続。アドレス `0x68`（SDO→GND） |
| アクション | ePaper/LED 表示のみ（**マイコン完結**）。アプリ変更なし |
| 検出対象 | 駐車（スタンド立てる）のみ。乗り出しは検知しない |
| 検出後挙動 | **詳細表示**に切替 ＋ **通常の5秒オートリターンを無効化**（詳細表示を維持）。表示内容は Phase 9 詳細表示と同じだが**内部状態は別管理**（`EP_VIEW_PARKED`） |
| 誤検出解除 | 検知後に**ロードノイズ（走行振動）**を検知したら「走行中の誤検出」と判定し**時刻モードに戻る** |
| 最終形 | 駐車確定 → キー抜いて電源 OFF → ePaper に「前回の乗車記録」が残像として残る |
| 進め方 | **段階的**（生値確認→実機採取→アルゴリズム）。閾値は実機チューニングで決定 |

**ピン割り当て変更（Phase 14）**

Phase 14 では SW1/SW2 を背面端子へ移動し、空いた **GPIO 4/5 を BMI160 の I2C に再割当て** する。ピン定義は `bikeclock.h` の1箇所で切替（SW1/SW2/IMU の3定義）。**SW1=41 / SW2=47 とも strapping pin を回避**（ESP32-S3 の strapping は 0/3/45/46）。

| 用途 | 信号 | GPIO | 備考 |
|------|------|------|------|
| SW1（移動） | Right Arrow | 4 → **41** | 背面端子へ移動。GPIO 4 を BMI160 SDA に明け渡す |
| SW2（移動） | Down Arrow | 5 → **47** | 背面端子へ移動。GPIO 5 を BMI160 SCL に明け渡す |
| BMI160 | SDA | **4** | SW1 から再利用。I2C は任意 GPIO にリマップ可能 |
| BMI160 | SCL | **5** | SW2 から再利用 |
| BMI160 | VCC | 3V3 | |
| BMI160 | GND | GND | 共通化 |
| BMI160 | SDO/SA0 | GND | I2C アドレス 0x68 固定用 |

> ※ I2C は新設（ePaper=SPI3_HOST / TM1637=独自プロトコルで既存 I2C なし）。ESP32-S3 は I2C を任意 GPIO にリマップ可能。**SW2=47 は背面端子で露出しているか配線時に確認。未露出なら 42/40 等の別の非strappingピンへ（ピン定義1箇所で切替）。**
>
> ⚠ **非推奨ピン（strapping pin）について**: ESP32-S3 の strapping pin **GPIO 0/3/45/46** はリセット時にレベルがブート設定へ反映されるため、スイッチ等の入力に使うと起動時レベル次第でブート不全を起こす。本プロジェクトでは SW/I2C にこれらを回避し汎用 GPIO（4/5/41/47）を使用。ピンごとの理由は [README](bikeclock_esp32/README.md) の「非推奨ピン（strapping pin）」参照。

**ライブラリ**: `hanyazou/BMI160-Arduino`（`BMI160Gen.h`、ESP32 実績あり）を第一候補。ビルド/動作に問題あれば `Wire.h` レジスタ直接制御へフォールバック。

#### Phase 14-A — センサー導入＋生値ダンプ [リスク:低] ✅ 完了（v2.0.25）
BMI160 を I2C で読めるようにし、生値をシリアルログで確認できるようにする。検出ロジックは未実装。
- [x] `bikeclock.h`: ピン定義変更 — `SWITCH_SW1_GPIO 4→41` / `SWITCH_SW2_GPIO 5→47`（背面端子へ移動）、I2C ピン定義追加（`IMU_SDA_GPIO=4`, `IMU_SCL_GPIO=5`）。併せて `EpaperView` に `EP_VIEW_PARKED` 追加、IMU 用 extern/プロトタイプ、`IMU_DEBUG_DUMP`、`FIRMWARE_VERSION_PATCH` +1（24→25）
- [x] `bikeclock_esp32_imu.ino`（新規）: `setupIMU()`（Wire.begin＋BMI160 初期化・CHIPID 確認、接続失敗時フォールバック）/ `updateIMU()`（50Hz サンプリング＋生値読出し・g/deg/s 換算）/ `dumpIMU()`（10Hz シリアル生値、`IMU_DEBUG_DUMP` で切替）
- [x] `bikeclock_esp32.ino`: `setup()` に `setupIMU()`、`loop()` に `updateIMU()` 追加
- [x] `README.md`: BMI160 配線図・GPIO一覧・ライブラリ表・実装状況に追記
- [x] `sh compile.sh` 成功 ✅（v2.0.25, 94%）
- **設計判断**: アクセス方式は **Wire.h レジスタ直接制御** を採用（TODO 第一候補の BMI160Gen ではなく・外部ライブラリ不要・ビルド確実・Phase 14-B/C チューニングで生値を直接観察可）。サンプリング 50Hz（loop=100Hz から間引き）、生値ダンプ 10Hz、センサ未接続時 `g_imuEnabled=false` でフォールバック。最新サンプルはグローバル変数で保持（Phase 14-B のリングバッファ拡張を想定）
- **再利用**: `logPrint()`（`bikeclock_esp32.ino`）、`Wire.h`（ESP32 core 付属）
- **検証**: ビルド成功 ✅（v2.0.25, 94%）/ 実機: BMI160 認識(I2C 0x68)・静止時 az≈1g・gx,gy,gz≈0・既存機能回帰 は配線後に確認

#### Phase 14-B — スマホ経由データ採取（マイコン＋Android）[リスク:中]
PC をバイクに持って行けないため、**BTClockMob アプリ経由**で IMU データを採取する。運用: 動作後、アプリの「取得」操作でマイコンのリングバッファ（直近10秒）を受信、ラベルを付与して CSV で PC へシェア。アルゴリズム開発（14-C）の学習データ収集が目的。

**決定事項**:
| 項目 | 内容 |
|------|------|
| データ期間 | 直近10秒（リングバッファ）|
| サンプリング | 50Hz（14-A 準拠）|
| データ形式 | int16 生LSB（6軸）→ アプリ側で g/deg/s 換算。500サンプル×6×2B = 約6KB |
| ラベル | 固定リスト（駐車/解除/走行/カーブ/停車/アイドリング 等）＋自由メモ |
| 保存・持ち出し | CSV を都度シェア（Android `ACTION_SEND` で PC へ）|

**BLE プロトコル**: コマンド `IMU_DUMP`（アプリ→マイコン）でリングバッファ要求 → マイコンはチャンク分割（MTU 247B）で notify 連続送信（シーケンス番号・総チャンク数・完了マーカー付き）→ アプリで再構築

##### Phase 14-B1 — マイコン側: リングバッファ＋IMU_DUMP 転送 [リスク:中] ※実装待ち
- [ ] `bikeclock_esp32_imu.ino`: リングバッファ追加（int16・500サンプル×6軸・循環）。`updateIMU` で push。`updateIMU` を loop のメンテナンスブロック外へ移動（常時サンプリング）
- [ ] `bikeclock_esp32_ble.ino`: `IMU_DUMP` コマンドハンドラ。リングバッファをチャンク分割 notify 送信（シーケンス番号・完了マーカー）
- [ ] `bikeclock.h`: リングバッファ定義・プロトタイプ、`FIRMWARE_VERSION_PATCH` +1
- [ ] `sh compile.sh` 成功
- **設計判断**: int16 で RAM 節約（float なら12KB）。常時サンプリングは検出（14-C）でも必須。チャンク送信の信頼性はシーケンス番号で担保
- **検証**: nRF Connect / アプリから `IMU_DUMP` 送信で全500サンプル受信・欠損なし

##### Phase 14-B2 — Android 側: 採取画面＋ラベル＋CSV シェア [リスク:中] ※実装待ち
- [ ] `ImuDataCaptureScreen`（新規）: 「データ取得」ボタン→`IMU_DUMP` 送信→チャンク受信・再構築・進捗表示
- [ ] ラベル選択 UI（固定リスト Dropdown）＋自由メモ欄
- [ ] CSV 生成（列: `timestamp_ms, ax,ay,az, gx,gy,gz` ＋ ヘッダに label/memo）＋ `ACTION_SEND` シェア（FileProvider）
- [ ] メニューから画面遷移
- **設計判断**: BLE 受信は BleRepository 経由（notify コールバックで蓄積・完了待ち）。CSV はキャッシュディレクトリに書き出して共有
- **検証**: バイク各動作で採取 → CSV を PC で開いて値が正しく入っている

##### Phase 14-B3 — 実機採取（ユーザー作業）[リスク:最低]
- [ ] バイクへセンサー・デバイス取り付け。**センサーモジュールの向きを記録**（軸対応を確定）
- [ ] 各動作のデータを採取: 両脚スタンド立てる / 解除 / 走行（ロードノイズ）/ カーブ / 停車 / アイドリング（各10〜20サンプル目安）
- [ ] CSV を PC に集め、特徴量と閾値の目安を決定 → Phase 14-C のアルゴリズム確定

#### Phase 14-C — 検出アルゴリズム実装 [リスク:中] ※採取後、別セッション
- [ ] `updateParkDetection()` 状態機械: IDLE → 押し上げパターン検知 → PARKED（詳細表示切替・オートリターン無効）/ PARKED → ロードノイズ検知 → IDLE（時刻モード復帰）
- [ ] `EP_VIEW_PARKED` 描画（`drawEpaperDetail()` 再利用）と `g_parkedActive` オーバーライドを `updateEpaperDisplay()` へ組込。優先順位: 通知 > 駐車 > 未同期 > 通常
- [ ] `updateDisplayAndLedState()`: `g_parkedActive` 中は DATE/WEEKDAY の5秒オートリターンをスキップ
- [ ] 閾値を `bikeclock.h` の定数として定義（Phase B の値）
- **設計判断**: 検出特徴量（実機採取で確定・概ね以下を想定）— 押し上げ＝垂直軸加速度の正負ピーク＋ピッチ角速度ピーク（持続0.5〜2秒）/ ロードノイズ＝高周波の継続振動で誤検出判定 / カーブ＝ロール角速度が持続的で時間スケールが異なり弁別
- **検証**: 押し上げで詳細表示切替＋維持 / キー OFF 後の残像が詳細表示になること / 走行中の誤検出が時刻モードへ戻ること

---

## 🟡 保留（後回し）

> ESP32 移植の残作業。現状の運用に支障がないため後回し。OTA は保留中。

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

---

## ✅ 完了済み（アーカイブ）

> 実装完了済み。参考記録として残す。学び（⚠）は再発防止用。

### Phase 0〜6 — ESP32-S3 移植

Seeed XIAO BLE (nRF52840) 版 `bikeclock/` を ESP32-S3 SuperMini へ移植。リスクの低いもの（プラットフォーム非依存ロジック）から積み上げ、各フェーズで独立して動作確認できる粒度に分割して実施。

#### グループ1: 時計としてアプリと繋がる（最優先）

##### Phase 0 — スケルトン & ビルド環境 [リスク:最低] ✅ 完了
- [x] `bikeclock_esp32.ino`（空の setup/loop）、`bikeclock.h`、`compile.sh` / `upload.sh` / `consolelog.sh` / `common.sh` / `setting.sh.example` 作成
- [x] FQBN 確定: `esp32:esp32:esp32s3:CDCOnBoot=cdc` / esp32 core 3.3.8 / `CLAUDE.md`（ビルドルール）作成
- **検証**: `sh compile.sh` 成功 ✅

##### Phase 1 — 表示 & 時刻ロジック [リスク:低] ✅ 完了
- [x] `bikeclock.h`: ピン定義 + TM1637 include、DisplayMode/DateCache/プロトタイプ追加
- [x] `bikeclock_esp32.ino`: タイムスタンプ更新・時刻計算を移植、`bikeclock_esp32_display.ino`（TM1637 表示・7seg）
- [x] 起動時バージョン表示、未同期時の「8888 点滅」、ファームウェア 2.0.0 → 2.0.1
- **検証**: ビルド成功 ✅ / 実機表示確認 ✅

##### Phase 2 — オンボード LED（GPIO48 RGB） [リスク:低] ✅ 完了
- [x] Adafruit NeoPixel 1.15.5 で WS2812 RGB LED 1px 制御（`bikeclock_esp32_led.ino`）
- [x] `LedState`（BOOT/NO_SYNC/SYNCED/CONNECTED_*/ERROR）の色・点滅パターン維持
- **検証**: ビルド成功 ✅ / 実機LED確認 ✅（起動後 緑固定 = SYNCED）

##### Phase 2.5 — ePaper 表示（昼間視認性） [リスク:低〜中] ✅ 完了
- [x] `bikeclock_esp32_epaper.ino`: GxEPD2_213_B74(BW) + U8g2_for_Adafruit_GFX（日本語）
- [x] 専用 SPI3_HOST バス（CS=1, DC=2, RST=3, BUSY=10, SCK=12, MOSI=11）
- [x] 表示: 時刻(大)+曜日+日付、毎分フル更新でにじみ防止 / 未同期→「時刻未同期」
- **検証**: ビルド成功 ✅ / 実機ePaper表示確認（要配線: VCC→3V3, GND→GND, CS→1, DC→2, RES→3, BUSY→10, SCL/SCK→12, SDA/MOSI→11）

> ⚠ **学び**: GxEPD2 はデフォルトでグローバル `SPI`(FSPI=SPI2) を使う。別ホストを使う場合は `epd2.selectSPI(SPIClass&, SPISettings)` で差替え、かつ `init()` 前に自前で `epdSPI.begin(カスタムピン)` を呼ぶ（`init()` 内の `begin()` は既起動なら即returnしピンを保持するため、ESP32-S3 core 3.3.8 で確認済み）。

##### Phase 5 — BLE カスタム GATT（時刻同期・キー設定） [リスク:中] ✅ 完了
- [x] NimBLE-Arduino 2.5.0 / `bikeclock_esp32_ble.ino`: NimBLEServer + Service(`4fafc201`) + Command char(`beb5483e-...26a0`, R/W/N)
- [x] `SET:time:` / `SET:keys:` / `GET:version` 処理、`sendResponse()`（notify）、接続/切断/Write/CCCD コールバック
- [x] アドバタイズ: デバイス名 + カスタムサービス + 切断時再アドバタイズ
- **検証**: ビルド成功 ✅ / **BTClockMob 接続・時刻同期 成功** ✅

> ⚠ **学び**: NimBLE のアドバタイズはデフォルトでデバイス名を含まない。アプリの name フィルタで検出されるよう `setName()` + `enableScanResponse(true)` が必須（Bluefruit とは異なる点）。

#### グループ2: 物理スイッチ・HID・OTA

##### Phase 3 — スイッチ直接接続 & 検出 [リスク:低] ✅ 完了
- [x] スイッチピン(GPIO 4,39,13,14,35,38,5, FUNC=8)を `INPUT_PULLUP` でセットアップ
- [x] デバウンス付き `processHidSwitches()` / `processFunctionKey()`（GPIO直読み）
- [x] FUNC (GPIO 8) 長押し → メンテナンスモード遷移
- **検証**: ビルド成功 ✅（HID送信は当時スタブ → Phase 6 で本実装）

##### Phase 4 — 設定永続化（LittleFS） [リスク:低] ✅ 完了
- [x] `<LittleFS.h>` で `/keys.dat` 読み書き（uint16_t × 7 のバイナリ）
- [x] `loadSettings()` / `saveSettings()` / `resetKeySettingsToDefaults()` / `resetToFactoryDefaults()`
- **検証**: ビルド成功 ✅（キー設定保存→復元、ファクトリーリセットの最終実機確認は Phase 8 で）

##### Phase 6 — BLE HID キーボード [リスク:高] ★技術的に最難関 ✅ 完了
- [x] NimBLE で HID-over-GATT サービス(0x1812)構築（`NimBLEHIDDevice` + Report Map 自前定義）
- [x] キーボードレポート（Report ID 1, 8B）/ コンシューマキー（Report ID 2, 2B: Back/再生 等）送信
- [x] アドバタイズに HID(0x1812) + appearance = HID Keyboard(0x03C1) 追加
- [x] ボンディング対応 — `setSecurityAuth(true,false,true)` + Just Works（HID Input Report が `READ_ENC` で生成されるため bonding 必須）
- [x] `sendHidKeyPress/Release` 本実装（新規 `bikeclock_esp32_hid.ino`）
- [x] HID 接続 と GATT 接続 の **2接続共存** 確認
- **検証**: ビルド成功 ✅（v2.0.21, 70%）/ **実機確認 ✅**: SW1-7 で矢印/Enter/Back/再生が入力され、同時に BTClockMob の時刻同期も維持（2接続共存確認）

> ⚠ **学び**: `NimBLEHIDDevice` は既存 `NimBLEServer` に HID/DeviceInfo/Battery サービスを追加するため、カスタムGATT と同一サーバーで共存できる（2接続問題は構造的に解決）。Report Map は NimBLE では自前必須（Bluefruit は自動生成）。コンシューマ AC Back(0x224) は Logical Max `0x02FF` / 16-bit LE 送信でカバー。

### Phase 9 — ePaper 3モード連動表示 [リスク:中] ✅ 完了（v2.0.22）
FUNCボタン短押しで 7セグLED のモード（TIME/DATE/WEEKDAY）を切り替えたとき、**ePaper の表示も連動して 3 種類に切り替わる**ようにする。
- [x] モード連動: 7seg=TIME → ePaper 標準表示 ／ 7seg=DATE → ePaper 通知表示 ／ 7seg=WEEKDAY → ePaper 詳細表示
- [x] `updateEpaperDisplay()` を `g_displayMode` に応じた 3 描画関数のディスパッチャ化（`EpaperView` / `ep_lastView`）。モード切替時の即時再描画
- [x] 詳細表示（モードC）: 16px級フォントで ① 開始時刻＋経過時間 ② 現在日時（秒まで） ③ HIDキー設定（SW1〜7）。切替時の1回だけ描画（スナップショット）
- [x] 通知表示（モードB）のスタブ: 「通知なし」固定表示（本文受信は Phase 10）
- [x] 起動時刻（JST）を時刻同期完了時に記録（`g_startupTimeStr` / `recordStartupTime()`）。`getYear()` 追加
- [x] HIDキーコード → 人間可読名（Right/Enter 等）のマッピング（`KEY_NAME_TABLE` / `keyNameFromCode()`）
- **検証**: 実機確認 ✅ FUNC短押しで ePaper が 標準→通知(なし)→詳細 に切り替わる。詳細は分が変わっても更新されない（スナップショット）
- **オートリターン**: DATE/WEEKDAY の5秒でTIME強制復帰は **維持（合意）**。モードB/C は5秒間表示後、標準に戻る

### Phase 10 — スマホ通知受信・表示（BLE・マイコン側） [リスク:中] ✅ 完了（v2.0.23）
Phase 9 の通知表示スタブを本実装し、BLE 経由でスマホからの通知を受信して ePaper に表示する（マイコン側）。
- [x] BLE 受信ハンドラへ通知コマンド追加。プロトコル: `NOTIFY:app=<アプリ名>\n<テキスト>`（UTF-8、上限200バイト、応答なし＝ファイア＆フォーゲット）
- [x] 通知受信時、現在のモードに関わらず **ePaper を通知表示に自動切替** し、一定時間（30秒）後に **元のモード画面へ自動復帰**
- [x] 通知表示本体: `epaper_test` の `drawWrappedText()`（u8g2_font_unifont_t_japanese3、自動折り返し）を移植。分割線なしでテキスト埋め
- [x] 表示中モード管理: 「ユーザ選択のベースビュー」と「通知割り込みで一時表示するビュー」の分離（`g_notificationActive` オーバーライド方式）
- **設計判断**: 通知タイムアウト後は常に時計(TIME)へ復帰（DATE/WEEKDAY はリセット）／未同期中も通知優先／レイアウトは本文のみ（アプリ名非表示）／MTU 247B 拡大
- **検証**: ビルド成功 ✅（実機検証は Phase 11 で Android から送信時、または nRF Connect で `NOTIFY:app=Test\nテスト` 送信で確認予定）

### Phase 11 — Android 通知転送（NotificationListenerService） [リスク:中] ✅ 完了
Phase 10 のプロトコルをアプリ側から送る。スマホの通知を BikeClock の ePaper に転送する。
- [x] `NotificationListenerService` サブクラスを新規作成（マニフェスト宣言＋`BIND_NOTIFICATION_LISTENER_SERVICE`）
- [x] `onNotificationPosted` でアプリ名+タイトル+テキストを取り出し `NOTIFY:` ペイロード生成 → `BleRepository.sendCommandSerial()` で送信（DI は Koin の `KoinComponent` 経由。同一プロセス運用）
- [x] 自己アプリ通知の除外・200バイト切り詰め・簡易デバウンス
- [x] AppSettings に「通知アクセス」許可誘導 UI 追加（`Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`）
- **設計判断**: 本文=タイトル＋本文（空白区切り）／転送対象=メッセージ系のみ（音楽/ナビ/通話等の常駐通知は除外）／MessagingStyle は EXTRA_MESSAGES から最新メッセージを抽出／連続書込の取りこぼし防止に Mutex シリアライズ（`sendCommandSerial`）／3秒デバウンス
- **検証**: ビルド成功 ✅（実機検証は通知アクセス許可後、LINE 等の通知で ePaper 表示を確認予定）

### Phase 12 — ePaper 通知表示のフォント段階切替（マイコン側） [リスク:低] ✅ 完了（v2.0.24）
通知本文の文字数に応じて ePaper のフォントサイズを自動切替し、短い通知ほど大きく表示する（マイコン側）。
- [x] `NOTIFY_FONT_SETTINGS` 配列で文字数→フォント/倍率の段階を定義（`bikeclock.h`）。10字以下→16px×3 / 24字以下→12px×3 / 26字以下→16px×2 / それ以上→12px×2
- [x] `ScaledGFX` ラッパークラスで小フォントをピクセル単位で拡大描画（`bikeclock_esp32_epaper.ino`）
- [x] `utf8CharCount()` でバイト数ではなく文字数をカウントして段階を判定
- [x] BLE 受信ハンドラの UTF-8 境界巻き戻しを「切り詰め発生時のみ」実行するよう最適化（`bikeclock_esp32_ble.ino`）
- **設計判断**: フォント倍率は u8g2 のピクセル拡大（ScaledGFX）で実現／段階設定は配列で一元管理し調整容易／200バイト未満の本文は巻き戻し処理を省略
- **検証**: ビルド成功 ✅（実機検証は Phase 13 のデバッグ画面から各文字数の通知を送信してフォント切替を確認予定）

### Phase 13 — 通知最大表示文字数設定・送信デバッグ画面（Android 側） [リスク:低] ✅ 完了
アプリ側で通知の最大表示文字数を制御して ePaper 視認性を最適化し、開発用の送信デバッグ画面を追加する。
- [x] 通知転送時に文字数制限で切り詰め、超過時は末尾に「＞」を付与（`BikeNotificationListener`）。2段階切り詰め: 文字数制限→「＞」付与→180B UTF-8境界切り詰め
- [x] `DEFAULT_NOTIFICATION_MAX_CHARS`（=47）定数でデフォルト値を一元管理（`Settings`）
- [x] AppSettingsScreen に最大文字数スライダーを追加（10〜100字、通知転送ON時のみ有効）
- [x] メニューから遷移する通知送信デバッグ画面（`NotificationDebugScreen`）を追加。アプリ名プリセット・ePaper プレビュー・送信コマンド/バイト数確認・BLE 送信
- **設計判断**: 47字は実機調整による最適値（最小フォントでも ePaper に収まる範囲）／文字数設定はアプリ側、フォント切替はマイコン側（Phase 12）で役割分担
- **検証**: ビルド成功 ✅（実機検証は各文字数の通知で ePaper 表示・フォント切替を確認予定）

---
