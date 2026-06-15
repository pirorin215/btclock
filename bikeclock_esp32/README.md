# BikeClock ESP32-S3 ファームウェア

ESP32-S3 SuperMini を使ったバイク搭載用時計デバイス「BikeClock」のファームウェアです。
XIAO BLE (nRF52840) 版 [`bikeclock/`](../bikeclock/) の **ESP32-S3 移植版** で、Androidアプリ [`BTClockMob`](../BTClockMob/) とは完全互換（アプリ側は変更不要）です。

> **実装状況**: フェーズ別に段階開発中。進捗は [TODO.md](./TODO.md) を参照。
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
            ├─ MCP23S17 経由で 8スイッチ読取                         ├─ リモートキー設定
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
| 表示 | 4-Digit 7セグメントLEDディスプレイ (TM1637) |
| スイッチI/O | MCP23S17 SPI I/Oエキスパンダー（28pin DIP/SOIC） |
| 操作 | 外付けスイッチユニット（8ボタン：SW1-SW7 + FUNC） |
| ステータスLED | SuperMini オンボード RGB LED（GPIO48, WS2812）※配線不要 |
| 電源 | USB電源（5V） |

### ESP32-S3 SuperMini のピン割り当て（推奨案A）

| GPIO | 用途 | 接続先 |
|:----:|------|--------|
| **GPIO 4** | SPI MOSI | MCP23S17 #13 (SI) |
| **GPIO 5** | SPI MISO | MCP23S17 #14 (SO) |
| **GPIO 6** | TM1637 DIO | TM1637 DIO |
| **GPIO 7** | TM1637 CLK | TM1637 CLK |
| **GPIO 8** | SPI SCK | MCP23S17 #12 (SCK) |
| **GPIO 9** | SPI CS | MCP23S17 #11 (CS) |
| **GPIO 48** | オンボード RGB LED | （ボード内蔵・配線不要） |
| **3V3** | 3.3V 電源 | MCP23S17 VDD / RESET |
| **5V (VIN)** | 5V 電源 | TM1637 VCC |
| **GND** | グラウンド | 全デバイス共通 |

> 空きGPIO: 0, 1, 2, 3, 10（GPIO 0 は boot ピンなので入力用途では注意）
> ESP32-S3 は SPI ピンを任意の GPIO にマッピングできるため、上記以外のピンでも設定変更で対応可能（`SPI.begin(SCK, MISO, MOSI, SS)`）。

---

## 配線図

### 全体接続イメージ

```
                     ESP32-S3 SuperMini
                  ┌─────────────────────┐
           USB ───┤ USB-CDC (書込/ログ)  │
          (5V) ───┤ 5V (VIN)         3V3├──┬───── 3.3V ──→ MCP23S17 VDD(#9), RESET(#18)
                 │                   5V├──┼───── 5V   ──→ TM1637 VCC
          (GND)───┤ GND              GND├──┴───── GND  ──→ 全デバイス共通GND
                 │                     │
                 │  GPIO4 (MOSI)       │─────────────────→ MCP23S17 #13 (SI)
                 │  GPIO5 (MISO)       │←───────────────── MCP23S17 #14 (SO)
                 │  GPIO8 (SCK)        │─────────────────→ MCP23S17 #12 (SCK)
                 │  GPIO9 (CS)         │─────────────────→ MCP23S17 #11 (CS)
                 │                     │
                 │  GPIO6 (DIO)        │───────→ TM1637 DIO
                 │  GPIO7 (CLK)        │───────→ TM1637 CLK
                 │                     │
                 │  GPIO48 (RGB LED)   │  ※ボード内蔵（配線不要）
                 └─────────────────────┘
```

### ① ESP32-S3 SuperMini ↔ MCP23S17（SPI）

| SuperMini | MCP23S17 ピン | 信号 | 備考 |
|:---------:|:-------------:|------|------|
| GPIO 4 | #13 (SI) | SPI MOSI | マスター→スレーブ |
| GPIO 5 | #14 (SO) | SPI MISO | スレーブ→マスター |
| GPIO 8 | #12 (SCK) | SPI SCK | クロック |
| GPIO 9 | #11 (CS) | SPI CS | チップセレクト |
| 3V3 | #9 (VDD) | 3.3V 電源 | |
| 3V3 | #18 (RESET) | リセット | HIGH で動作（3.3V に接続） |
| GND | #10 (VSS) | GND | |
| GND | #15, #16, #17 (A0, A1, A2) | ハードウェアアドレス | **全て GND に落としてアドレス 0 に設定** |
| (未接続) | #19, #20 (INTB, INTA) | 割込 | 本ファームでは未使用（オープン可） |

> **注意**: MCP23S17 は 3.3V 駆動。ESP32-S3（3.3Vロジック）と直接接続でき、レベル変換不要です。

### ② ESP32-S3 SuperMini ↔ TM1637（4桁7セグLED）

| SuperMini | TM1637 | 信号 |
|:---------:|:------:|------|
| GPIO 6 | DIO | LED Data |
| GPIO 7 | CLK | LED Clock |
| 5V (VIN) | VCC | 5V 電源（明るさ重視なら5V推奨） |
| GND | GND | GND |

### ③ MCP23S17 ↔ スイッチユニット（8ボタン）

スイッチは **GPAポート（#21〜#28ピン）** を使用し、各スイッチのもう片側を **GND** に接続します（MCP23S17 の内部プルアップで HIGH、押下で LOW）。

| MCP23S17 ピン | ポート | ボタン | デフォルトキー割り当て |
|:------------:|:------:|:------:|----------------------|
| #21 | GPA0 | SW1 | 右矢印 (0x4F) |
| #22 | GPA1 | SW2 | 下矢印 (0x51) |
| #23 | GPA2 | SW3 | 上矢印 (0x52) |
| #24 | GPA3 | SW4 | 左矢印 (0x50) |
| #25 | GPA4 | SW5 | Enter (0x28) |
| #26 | GPA5 | SW6 | Back (0x0224) |
| #27 | GPA6 | SW7 | 再生/一時停止 (0xCD) |
| #28 | GPA7 | **SW8 (FUNC)** | 表示モード切替（HIDキーではない） |

> **注**: キー割り当ては BTClockMob アプリから変更可能です（`SET:keys:` コマンド）。
> GPBポート（#1〜#8）は本プロジェクトでは未使用です。

### ④ 電源接続

```
USB 5V給電
   │
   ├─→ SuperMini 5V (VIN)   … SuperMini 本体駆動（内蔵レギュレータで 3.3V 生成）
   ├─→ TM1637 VCC           … 5V駆動
   │
   └─(SuperMini 3V3ピンから)─→ MCP23S17 VDD, RESET   … 3.3V駆動

   全デバイスの GND を共通化（必須）
```

### 配線時の注意点

- **共通GND**: 全デバイスの GND を必ず1点に束ねてください。GND 不共通は通信不安定の主因です。
- **MCP23S17 の電圧**: 3.3V 駆動（**5V をかけないこと**）。ESP32-S3 と同じ 3.3V 系なので直接接続できます。
- **TM1637 の電圧**: 5V 推奨（明るさ）。3.3V でも動作しますが暗くなります。
- **MCP23S17 の A0/A1/A2**: 全て GND に落としてハードウェアアドレス = `000`（SPI ではデバイスID `0x00`〜`0x07`）。配線忘れると通信できません。
- **GPIO 0**: boot ピンのため、起動時のレベルに注意。本構成では未使用推奨。
- **GPIO 19/20**: USB D-/D+ に接続されているため使用禁止（USB-CDC/書込に影響）。
- **GPIO 26〜32**: 内蔵フラッシュに接続されており使用不可。

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
| **Adafruit MCP23X17** (Adafruit) | MCP23S17 I/Oエキスパンダー | Phase 3 |
| **NimBLE-Arduino** (h2zero) | BLE (HID + GATT) | Phase 5/6 |
| **Adafruit NeoPixel** (Adafruit) | オンボードRGB LED (GPIO48) | Phase 2 |
| **ArduinoOTA** | WiFi OTA（arduino-esp32 付属） | Phase 7 |
| **LittleFS** | 設定保存（arduino-esp32 付属） | Phase 4 |

```bash
arduino-cli lib install "TM1637" "Adafruit MCP23X17" "NimBLE-Arduino" "Adafruit NeoPixel"
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

フェーズ別に段階開発中。現在 **Phase 0（ビルド環境）完了**。

| フェーズ | 内容 | 状態 |
|---------|------|:----:|
| 0 | スケルトン & ビルド環境 | ✅ |
| 1 | 表示 & 時刻ロジック | ⏳ |
| 2 | オンボードLED (GPIO48 RGB) | |
| 5 | BLE カスタムGATT（時刻同期） | |
| 3 | MCP23S17 & スイッチ検出 | |
| 4 | 設定永続化 (LittleFS) | |
| 6 | BLE HIDキーボード | |
| 7 | WiFi OTA | |
| 8 | 統合 & 調整 | |

詳細は [TODO.md](./TODO.md) を参照。

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
- **表示**: TM1637
- **I/Oエキスパンダー**: Adafruit MCP23X17 (MCP23S17, SPI)
- **LED**: Adafruit NeoPixel（オンボードRGB LED, GPIO48）
- **ファイル**: LittleFS (ESP32)
- **OTA**: ArduinoOTA (WiFi)

## 謝辞

- [NimBLE-Arduino](https://github.com/h2zero/NimBLE-Arduino) - ESP32 BLE ライブラリ
- [TM1637](https://github.com/avishorp/TM1637) - LED表示ライブラリ
- [Adafruit MCP23X17](https://github.com/adafruit/Adafruit-MCP23X17) - I/Oエキスパンダーライブラリ
- [Adafruit NeoPixel](https://github.com/adafruit/Adafruit_NeoPixel) - RGB LED ライブラリ
- [Espressif arduino-esp32](https://github.com/espressif/arduino-esp32) - ESP32 Arduino core

---

**作成日**: 2026-06-15
**ファームウェアバージョン**: 2.0.0 (Phase 0)
**対象ボード**: ESP32-S3 SuperMini
