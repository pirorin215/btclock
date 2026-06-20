# BikeClock ESP32-S3 ファームウェア

ESP32-S3 SuperMini を使ったバイク搭載用時計デバイス「BikeClock」のファームウェアです。
XIAO BLE (nRF52840) 版 [`bikeclock/`](../bikeclock/) の **ESP32-S3 移植版** で、Androidアプリ [`BTClockMob`](../BTClockMob/) とは完全互換（アプリ側は変更不要）です。

> **実装状況**: フェーズ別に段階開発中。進捗は [TODO.md](../TODO.md) を参照。
> ハードウェア構成・配線・BLE仕様は最終形で確定しているため、本ファイルに記載します。

---

## XIAO BLE 版との違い（移行ポイント）

| 項目 | XIAO BLE 版 | ESP32-S3 版（本プロジェクト） |
|------|-------------|------------------------------|
| MCU | nRF52840 (Cortex-M4) | ESP32-S3 (Xtensa LX7 dual, 240MHz) |
| BLE | Adafruit Bluefruit | **NimBLE-Arduino** |
| WiFi | 非対応 | **対応**（OTA で使用） |
| ファイル保存 | InternalFS (LittleFS) | LittleFS (ESP32) |
| OTA | Nordic DFU | **WiFi OTA** |
| オンボードLED | 3色LED (common anode) | **GPIO48 RGB LED (WS2812, 1個)** |
| 通信プロトコル | 同じ（UUID/コマンド/デバイス名） | **同じ**（アプリ互換） |

---

## システム構成

```
┌──────────────────────┐        BLE (HID + GATT)        ┌──────────────────────┐
│  BikeClock (ESP32-S3)│◄──────────────────────────────►│  BTClockMob (Android)│
└──────────────────────┘                                └──────────────────────┘
            │                                                        │
            ├─ 4桁7セグLED表示 (TM1637)                              ├─ 自動時刻同期
            ├─ GPIO直接接続で 8スイッチ読取 (内部プルアップ)          ├─ リモートキー設定
            │   (SW1-SW7: HID, SW8: FUNC)                            ├─ 接続履歴（位置情報）
            ├─ BLE HIDキーボード + カスタムGATT                       └─ バックグラウンド実行
            ├─ オンボードRGB LED (状態表示, GPIO48)                   （※アプリは XIAO 版と共通）
            └─ USB給電 (5V)
```

> HID接続（Android OS 管理）と GATT接続（BTClockMob 管理）は **2つの独立した接続** で動作します。

---

## ハードウェア構成

### 使用コンポーネント

| コンポーネント | 説明 |
|--------------|------|
| マイコン | **ESP32-S3 SuperMini** (ESP32S3FH4R2: 4MB Flash, 2MB PSRAM) ※[ピンアウト・仕様参考](https://www.espboards.dev/esp32/esp32-s3-super-mini/) |
| 表示 | 4-Digit 7セグメントLEDディスプレイ (TM1637) + WeAct 2.13" ePaper |
| スイッチI/O | 不要（ESP32-S3の豊富にあるGPIOに直接接続） |
| 操作 | 外付けスイッチユニット（8ボタン：SW1-SW7 + FUNC） |
| ステータスLED | SuperMini オンボード RGB LED（GPIO48, WS2812）※配線不要 |
| 電源 | USB電源（5V） |

### GPIO 一覧（マスター）

**凡例**: ✅ 空き（拡張に使用可能） ／ 🔵 使用中（現ファームウェアで割当済み） ／ ⚠️ 注意（制限あり） ／ 🚫 使用禁止（ハード占有・ブート障害）

#### 表面ピン

| GPIO | 状態 | 用途 / 接続先 | 備考 |
|:----:|:----:|--------------|------|
| 0 | 🚫 | （BOOT ボタン） | **strapping pin** |
| 1 | 🔵 | ePaper CS | SPI3_HOST 専用バス |
| 2 | 🔵 | ePaper DC | SPI3_HOST 専用バス |
| 3 | 🔵 | ePaper RST | ⚠️ **strapping pin** プルアップ推奨 |
| 4 | 🔵 | BMI160 IMU SCL | I2C |
| 5 | 🔵 | BMI160 IMU SDA | I2C |
| 6 | 🔵 | TM1637 DIO | |
| 7 | 🔵 | TM1637 CLK | |
| 8 | 🔵 | スイッチ SW8 | FUNCキー |
| 9 | ✅ | — | **空き**（汎用入出力可） |
| 10 | 🔵 | ePaper BUSY | SPI3_HOST 専用バス |
| 11 | 🔵 | ePaper MOSI (DIN) | SPI3_HOST 専用バス |
| 12 | 🔵 | ePaper SCK (CLK) | SPI3_HOST 専用バス |
| 13 | 🔵 | スイッチ SW3 | 上矢印 (0x52) |

#### 背面ピン

| GPIO | 状態 | 用途 / 接続先 | 備考 |
|:----:|:----:|--------------|------|
| 14 | 🔵 | スイッチ SW4 | 左矢印 (0x50) |
| 15 | ✅ | — | **空き** |
| 16 | ✅ | — | **空き** |
| 17 | ✅ | — | **空き** |
| 18 | ✅ | — | **空き** |
| 19 | 🚫 | — | **USB D-** |
| 20 | 🚫 | — | **USB D+** |
| 21 | ✅ | — | **空き** |
| 26〜32  | 🚫 | — | 内蔵 Flash/PSRAM 占有（ボード未露出） |
| 33 | ✅ | — | **空き** |
| 34 | ⚠️  | — | **入力専用**（出力不可） |
| 35 | 🔵 | スイッチ SW5 | Enter (0x28) |
| 36 | ⚠️  | — | **入力専用**（出力不可） |
| 37 | ⚠️  | — | **入力専用**（出力不可） |
| 38 | 🔵 | スイッチ SW6 | Back (0x0224) |
| 39 | 🔵 | スイッチ SW7 | 再生/一時停止 (0xCD) |
| 40 | ✅ | — | **空き** |
| 41 | 🔵 | スイッチ SW1 | 右矢印 (0x4F) |
| 42 | ✅ | — | **空き** |
| 45 | 🚫 | — | **strapping pin** |
| 46 | 🚫 | — | **strapping pin** |
| 47 | 🔵 | スイッチ SW2 | 下矢印 (0x51) |
| 48 | 🔵 | オンボード RGB LED | 内蔵WS2812 |

#### 新規割り当て時の注意

---

## 配線図

### スイッチユニット（8ボタン）

スイッチは GPIO に直接接続し、もう片側を **GND** に接続します。マイコン内部でプルアップ（`INPUT_PULLUP`）を有効化するため、外付けプルアップ抵抗は不要です（押下=`LOW` / オープン=`HIGH`）。

GPIO とデフォルトキー割り当て（SW1=右矢印 … SW7=再生/停止、SW8=FUNC）は「GPIO 一覧（マスター）」を参照。キー割り当ては BTClockMob アプリから変更可能です（`SET:keys:` コマンド）。

### BMI160 IMU（GY-BMI160）— Phase 14 駐車検知用

両脚スタンド（センタースタンド）を立てる動作を検知する 6軸 IMU。I2C 接続（**Wire.h レジスタ直接制御・追加ライブラリ不要**）。

> 未接続でもファームウェアは動作します（`g_imuEnabled=false` で既存機能へフォールバック）。生値はシリアルログへ 10Hz で出力（`sh consolelog.sh`、`bikeclock.h` の `IMU_DEBUG_DUMP` フラグで切替）。静止時は `az≈+1g`、`gx,gy,gz≈0` が正常値。

### 電源接続

```
USB 5V給電
   │
   ├─→ SuperMini 5V (VIN)   … SuperMini 本体駆動（内蔵レギュレータで 3.3V 生成）
   └─→ TM1637 VCC           … 5V駆動

   全デバイス・スイッチの GND を共通化（必須）
```

### 配線時の注意点

- **共通GND**: 全デバイスおよび各スイッチの GND を必ず1点に束ねてください。GND 不共通は動作不安定やチャタリングの主因となります。
- **TM1637 の電圧**: 5V 推奨（明るさ）。3.3V でも動作しますが暗くなります。

---

## BLE通信仕様（XIAO 版と完全互換）

### デバイス情報

| 項目 | 値 |
|------|-----|
| デバイス名 | `BikeClock-0001` |
| メーカー | pirorin215 |
| モデル | BikeClock ESP32 |

### サービス構成

HIDプロファイル と カスタムGATTサービス を同時に使用します。

#### 1. HIDサービス (0x1812) — Android OS が接続管理
- キーボード/メディアキー入力用
- Android システムが自動的にペアリング・接続管理

#### 2. カスタムサービス — BTClockMob アプリが接続管理

| UUID | プロパティ | 説明 |
|------|----------|------|
| `4fafc201-1fb5-459e-8fcc-c5c9c331914c` | - | サービスUUID |
| `beb5483e-36e1-4688-b7f5-ea07361b26a0` | Read/Write/Notify | コマンド受信・レスポンス送信（双方向） |

> ※ XIAO 版にあった Response UUID (`...26a2`) / Switch UUID (`...26a1`) は **アプリ側で未使用のため実装しません**。応答は Command キャラクタリスティックの notify で行います。

### コマンドフォーマット

```
SET:time:<unix_timestamp>          # 時刻同期（JST換算のUnix timestamp）
SET:keys:HEX1,HEX2,...,HEX7        # キーコード設定（7個）
GET:version                        # ファームウェアバージョン取得
```

**応答例:**
```
OK: Time synced
OK: keys updated
OK:version:2.0.0
ERROR: Invalid timestamp format
```

---

## セットアップ手順

### 必要なライブラリ

Arduino IDE / arduino-cli で以下をインストール：

| ライブラリ | 用途 | 導入フェーズ |
|-----------|------|-------------|
| **TM1637** (Avishay Orpaz) | 4桁7セグLED表示 | Phase 1 |
| **NimBLE-Arduino** (h2zero) | BLE (HID + GATT) | Phase 5/6 |
| **Adafruit NeoPixel** (Adafruit) | オンボードRGB LED (GPIO48) | Phase 2 |
| **GxEPD2** / **U8g2** | ePaper 描画 | Phase 2.5 |
| **ArduinoOTA** | WiFi OTA（arduino-esp32 付属） | Phase 7 |
| **LittleFS** | 設定保存（arduino-esp32 付属） | Phase 4 |
| **Wire** | BMI160 IMU I2C（レジスタ直接制御・arduino-esp32 付属・インストール不要） | Phase 14 |

```bash
arduino-cli lib install "TM1637" "NimBLE-Arduino" "Adafruit NeoPixel" "GxEPD2" "U8g2"
```

### ボード設定

1. esp32 core（esp32:esp32）をインストール済みであること（確認済: 3.3.8）
2. ボード: **ESP32S3 Dev Module**（`esp32:esp32:esp32s3`）
3. FQBN: `esp32:esp32:esp32s3:CDCOnBoot=cdc`
   - USB CDC On Boot = **Enabled**（USBケーブル1本でログ確認・書込）
   - Flash Size = 4MB
   - Partition Scheme = Default（OTA導入時に変更、Phase 7）

### ファームウェア書き込み

```bash
cd bikeclock_esp32

# 設定ファイル作成（初回のみ）
cp setting.sh.example setting.sh
# setting.sh を編集して BIKECLOCK_PORT を設定 (例: /dev/cu.usbmodem101)

# コンパイル
sh compile.sh

# アップロード（BOOTボタン操作不要・USB-CDCで自動リセット）
sh upload.sh

# シリアルログ監視
sh consolelog.sh
```

---

## 使用方法

### 表示モード（FUNCキー SW8 で切替）

| モード | 表示内容 | 例 |
|--------|---------|-----|
| TIME | `HH:MM`（24時間, JST） | `12:34` |
| DATE | `MMDD` | `0615` |
| WEEKDAY | 曜日（3文字） | `SUN` |

- FUNCキー短押し: モード切替（5秒後に自動的に TIME に復帰）
- FUNCキー長押し（3秒）: メンテナンスモード（Boot/Test/OTA/Factory Reset）

### 起動シーケンス

1. USB給電 → 起動
2. ファームウェアバージョン表示（例 `2.0.0`）
3. 時刻未同期時: `8888` 点滅
4. BTClockMob 接続で時刻同期 → 現在時刻表示
5. （HID有効後）スイッチ操作で Android を操作

---

## 実装状況

フェーズ別に段階開発中。現在 **Phase 13（通知フォント切替・送信デバッグ画面）まで完了**、Phase 14-A（BMI160 IMU 生値ダンプ）実装済み。

| フェーズ | 内容 | 状態 |
|---------|------|:----:|
| 0 | スケルトン & ビルド環境 | ✅ |
| 1 | 表示 & 時刻ロジック | ✅ |
| 2 | オンボードLED (GPIO48 RGB) | ✅ |
| 2.5 | ePaper 表示 (昼間視認性用) | ✅ |
| 5 | BLE カスタムGATT（時刻同期） | ✅ |
| 3 | スイッチ直接接続 & 検出 | ✅ |
| 4 | 設定永続化 (LittleFS) | ✅ |
| 6 | BLE HIDキーボード | ✅ |
| 9 | ePaper 3モード連動表示 | ✅ |
| 10-13 | スマホ通知受信・フォント切替・デバッグ画面 | ✅ |
| 14-A | BMI160 IMU センサ導入＋生値ダンプ | ✅ |
| 7 | WiFi OTA | |
| 8 | 統合 & 調整 | |

詳細は [TODO.md](../TODO.md) を参照。

---

## トラブルシューティング

### コンパイルできない
1. esp32 core（`esp32:esp32`）がインストール済みか確認
2. 必要ライブラリがインストール済みか確認（フェーズに対応するもの）
3. FQBN が `esp32:esp32:esp32s3:CDCOnBoot=cdc` か確認

### シリアルログが見えない
1. USB-CDC が有効か（FQBN の `CDCOnBoot=cdc`）
2. ポートが正しいか（`/dev/cu.usbmodem*`）
3. ボードの USB ケーブルがデータ通信対応か（充電専用ケーブル不可）

### BLE接続できない
1. 電源を入れ直す
2. Android の Bluetooth 設定で `BikeClock-0001` をペアリング（HID 用）
3. BTClockMob アプリを再起動（GATT 接続用）
4. 他の BLE デバイスとの干渉を確認

### TM1637 が表示されない
1. 5V/3.3V 電源の接続確認
2. DIO(GPIO6) / CLK(GPIO7) の配線確認
3. GND 共通化の確認

---

## 技術スタック

- **言語**: C++ (Arduino)
- **MCU**: ESP32-S3 (arduino-esp32 core)
- **BLE**: NimBLE-Arduino
- **表示**: TM1637 + ePaper (GxEPD2)
- **LED**: Adafruit NeoPixel（オンボードRGB LED, GPIO48）
- **ファイル**: LittleFS (ESP32)
- **OTA**: ArduinoOTA (WiFi)

## 謝辞

- [NimBLE-Arduino](https://github.com/h2zero/NimBLE-Arduino) - ESP32 BLE ライブラリ
- [TM1637](https://github.com/avishorp/TM1637) - LED表示ライブラリ
- [GxEPD2](https://github.com/ZinggJM/GxEPD2) - ePaper ディスプレイライブラリ
- [U8g2](https://github.com/olikraus/u8g2) - フォント・グラフィックライブラリ
- [Adafruit NeoPixel](https://github.com/adafruit/Adafruit_NeoPixel) - RGB LED ライブラリ
- [Espressif arduino-esp32](https://github.com/espressif/arduino-esp32) - ESP32 Arduino core

---

**作成日**: 2026-06-15
**ファームウェアバージョン**: 2.0.0 (Phase 0)
**対象ボード**: ESP32-S3 SuperMini
