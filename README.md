# BTClock - バイク搭載用Bluetooth時計システム

XIAO BLEマイコンボードを使用したバイク搭載用時計デバイス「BikeClock」と、それに連携するAndroidアプリ「BTClockMob」の統合システムです。

## 開発の経緯・設計思想

本プロジェクトが生まれた背景や、常時電源・二次電池を一切使わないといったこだわりの設計思想については、[開発経緯ドキュメント](DEVELOPMENT_BACKGROUND.md)をご覧ください。

## システム概要

BTClockはバッテリー非搭載の時計デバイスです。スマホアプリにより時刻同期を行います。また、物理スイッチによるHIDキーボード機能があります。

### システム構成図

```
┌─────────────────┐         BLE (HID + GATT)         ┌─────────────────┐
│  BikeClock      │◄─────────────────────────────────┤  BTClockMob     │
│  (Arduinoデバイス)│                                 │  (Androidアプリ) │
└─────────────────┘                                 └─────────────────┘
        │                                                   │
        ├─ 4桁LED表示                                       ├─ 自動時刻同期
        ├─ BLE通信 (HID + Custom GATT)                      ├─ 接続履歴記録
        ├─ 8つの物理スイッチ (HIDキーボード + FUNC)          ├─ リモートキー設定
        └─ USB給電                                          └─ バックグラウンド実行
```

### 主な機能

#### BikeClockデバイス

- ✅ **ノーメンテナンス設計**: 内蔵電池交換不要、USB給電で動作
- ✅ **4桁LED表示**: TM1637使用の明るいディスプレイ（時刻/日付/曜日）
- ✅ **BLE時刻同期**: スマホアプリで自動時刻補正（JST対応）
- ✅ **HIDキーボード機能**: 7つの物理スイッチでキー入力
- ✅ **FUNCキー**: 表示モード切替（TIME/DATE/WEEKDAY）
- ✅ **キーコード設定**: アプリからスイッチのキー割り当てを変更可能
- ✅ **OTAファームウェア更新**: アプリからワイヤレスでファームウェア更新可能
- ✅ **メンテナンスモード**: 起動時の特殊操作でテストモード/DFUモード/ファクトリーリセットを実行可能
- ✅ **バージョン表示**: 起動時にファームウェアバージョンを表示

#### BTClockMobアプリ

- ✅ **BLE自動接続**: 近接するBikeClockデバイスの自動検出
- ✅ **時刻同期**: BLE経由でのUnixタイムスタンプ送信
- ✅ **接続履歴**: イグニッションON/OFF（BLE接続/切断）に連動した位置情報の自動記録
- ✅ **バックグラウンド実行**: 常駐サービスによるシームレスな体験
- ✅ **リモートキー設定**: デバイスのスイッチにキーコードを割り当て
- ✅ **OTAファームウェア更新**: アプリからファームウェアをワイヤレス更新可能

## プロジェクト構成

```
btclock/
├── bikeclock/              # Arduinoファームウェア
│   ├── bikeclock.ino       # メインファイル
│   ├── bikeclock.h         # ヘッダーファイル
│   ├── bikeclock_ble.ino   # BLE通信処理
│   ├── bikeclock_led.ino   # LED制御処理
│   └── README.md           # ファームウェア詳細ドキュメント
│
├── BTClockMob/             # Androidアプリ
│   ├── app/                # メインアプリケーション
│   ├── gradle/             # Gradle設定
│   └── README.md           # アプリ詳細ドキュメント
│
└── README.md               # このファイル（システム全体）
```

## BLE通信仕様

### デバイス情報

| 項目 | 値 |
|------|-----|
| デバイス名 | `BikeClock-0001` |
| メーカー | pirorin215 |
| モデル | BikeClock Dual |

### サービス構成

**重要**: このシステムではHIDプロファイルとカスタムGATTサービスを同時に使用します。

#### 1. HIDサービス (0x1812) - Android OSが接続管理

- キーボード/メディアキー入力用
- 物理スイッチ操作でHID信号を送信
- Androidシステムが自動的にペアリング・接続管理

#### 2. カスタムサービス - BTClockMobアプリが接続管理

| UUID | プロパティ | 説明 |
|------|----------|------|
| `4fafc201-1fb5-459e-8fcc-c5c9c331914c` | - | サービスUUID |
| `beb5483e-36e1-4688-b7f5-ea07361b26a0` | Read/Write/Notify | コマンド受信・レスポンス送信 |
| `beb5483e-36e1-4688-b7f5-ea07361b26a1` | Notify | スイッチ状態通知（オプション） |

#### 3. OTA DFUサービス - Nordic DFU

| UUID | プロパティ | 説明 |
|------|----------|------|
| `00001530-1212-efde-1523-785feabcd123` | - | Nordic DFUサービスUUID |
| `00001531-1212-efde-1523-785feabcd123` | Read/Write | DFU Control Point |
| `00001532-1212-efde-1523-785feabcd123` | Read/Write | DFU Packet |
| `00001534-1212-efde-1523-785feabcd123` | Notify | DFU Control Point Notify |

### コマンドフォーマット

**時刻同期**
```
SET:time:<unix_timestamp>
例: SET:time:1234567890
```

**キーコード設定**
```
SET:keys:HEX1,HEX2,HEX3,HEX4,HEX5,HEX6,HEX7
例: SET:keys:50,4F,52,51,53,54,55
```

**バージョン情報取得**
```
GET:version
```

**成功レスポンス**
```
OK: Time synced
OK: keys updated
VERSION: 1.0.11
```

**エラーレスポンス**
```
ERROR: Invalid timestamp format
ERROR: Invalid key format
```

### 2つの独立した接続

```
BikeClock (nRF52840)
  │
  ├─ HIDサービス (0x1812)              [Android OSが接続管理]
  │   └─ スイッチ操作によるキー入力
  │
  └─ カスタムサービス                  [BTClockMobアプリが接続管理]
      └─ 時刻同期、キー設定
```

**重要**: HID接続とGATT接続は完全に独立しています。

詳細は [BTClockMob/docs/hid-custom-service-dual-implementation.md](./BTClockMob/docs/hid-custom-service-dual-implementation.md) を参照してください。

## ハードウェア構成

| コンポーネント | 説明 |
|--------------|------|
| マイコン | Seeed Studio XIAO BLE (nRF52840) |
| 表示 | 4-Digit LED Display (TM1637) |
| スイッチI/O | MCP23S17 SPI I/Oエキスパンダー |
| 操作 | 外付けスイッチユニット（8ボタン） |
| 電源 | USB電源（モバイルバッテリー等） |

**詳細なピン配線・回路図は [bikeclock/README.md](./bikeclock/README.md) を参照してください。**

## セットアップ手順

### 事前準備

#### 必要なもの

1. **BikeClockデバイス**
   - Seeed Studio XIAO BLEボード
   - 4桁7セグメントLEDディスプレイ (TM1637)
   - MCP23S17 I/Oエキスパンダー
   - 外付けスイッチユニット（8つのタクトスイッチ）
   - USB電源（モバイルバッテリー等）

2. **開発環境**
   - Arduino IDE 2.x または Arduino CLI
   - Android Studio（アプリ開発の場合）

3. **スマホ**
   - Android 8.0以上
   - Bluetooth Low Energy対応

### 1. BikeClockデバイスのセットアップ

詳細な手順は [bikeclock/README.md](./bikeclock/README.md) を参照してください。

#### 必要なライブラリ

Arduino IDEで以下のライブラリをインストール：

1. **Adafruit nRF52 Bluefruit Library** (by Adafruit)
   - BLE通信用（Seeeduino XIAO BLE / nRF52840対応）
2. **TM1637** (by Avishay Orpaz)
   - LED表示制御用
3. **Adafruit MCP23017 Arduino Library** (by Adafruit)
   - MCP23S17（SPI I/Oエキスパンダー）制御用

#### ボード設定

1. Arduino IDE 2.xを開く
2. 「File」→「Preferences」で以下のURLを追加：
   ```
   https://files.seeedstudio.com/arduino/package_seeeduino_boards_index.json
   ```
3. ボードマネージャで「Seeed nRF52 Boards」をインストール
4. 「Seeed XIAO BLE」または「Seeed XIAO BLE Sense」を選択

#### ファームウェア書き込み

```bash
cd bikeclock

# 設定ファイル作成（初回のみ）
cp setting.sh.example setting.sh
# setting.shを編集してBIKECLOCK_PORTを設定

# コンパイル
sh compile.sh

# アップロード
sh upload.sh
```

### 2. BTClockMobアプリのセットアップ

詳細な手順は [BTClockMob/README.md](./BTClockMob/README.md) を参照してください。

#### ビルド

```bash
cd BTClockMob
./gradlew assembleDebug    # デバッグビルド
./gradlew assembleRelease  # リリースビルド
```

#### インストール

生成されたAPKをインストール：
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

または、Google Playストアからリリース版をインストール（リリース時）。

### 3. ペアリング設定

1. **AndroidのBluetooth設定**を開く
2. 「BikeClock」デバイスを探してペアリング
3. **BTClockMobアプリ**を起動
4. アプリが自動的にBikeClock-0001を検出・接続

## 使用方法

### 初回起動

1. **BikeClockの電源**: USB電源を接続
2. **LED表示**: 「8888」→「----」の変化を確認
3. **BTClockMob起動**: アプリを起動（自動接続）
4. **時刻同期**: 自動的に時刻が同期されます

### 時刻表示

BikeClockのLEDディスプレイには以下のモードがあります：

- **TIMEモード**: `HH:MM` 形式で時刻表示（24時間表記、JST）
- **DATEモード**: `MMDD` 形式で日付表示
- **WEEKDAYモード**: 曜日表示（MON/TUE/WED/THU/FRI/SAT/SUN）

**FUNCキー（SW8）**を押すたびにモードが切り替わります。

### HIDスイッチ操作

外付けスイッチユニットのSW1-SW7で接続先デバイス（スマホ/タブレット/PC）を操作できます。

#### デフォルト設定

外付けスイッチユニットには8つのスイッチがあります：
- **SW1-SW7**: HIDキーボード機能（矢印、Enter、Back、再生/停止等）
- **SW8**: FUNCキー（表示モード切替）

> **詳細**: デフォルトキーコード、キー割り当て変更方法、使用例は [bikeclock/README.md](./bikeclock/README.md) を参照してください。

### 接続履歴の確認

BTClockMobアプリの「履歴」タブで確認できます：

1. BLE接続/切断の履歴
2. 位置情報（いつ、どこから、どこまで走ったか）
3. バイクの走行履歴として活用可能

### OTAファームウェア更新

BTClockMobアプリからBikeClockデバイスのファームウェアをワイヤレス更新できます。

#### 更新手順

1. BTClockMobアプリでデバイスに接続
2. 「OTA」タブをタップ
3. ファームウェアファイル（.zip）を選択
4. 「更新開始」をタップ
5. 更新完了まで待機（約1〜2分）

> **注意**: 更新中はデバイスとの接続が切れます。更新完了後、自動的に再接続されます。

### メンテナンスモード

起動時に特殊操作を行うことで、メンテナンスモードに入ることができます。

#### メンテナンスモードの起動方法

1. BikeClockの電源を入れる
2. 起動ロゴ表示中（約3秒間）に**FUNCキー（SW8）を長押し**
3. メンテナンスメニューが表示される
4. **FUNCキー**でメニュー切替、**SW1**で決定

#### メンテナンスメニュー

| メニュー | 説明 |
|---------|------|
| **CANCEL** | メンテナンスモードをキャンセル（通常起動） |
| **TEST** | テストモード（全LED点灯、キーテスト等） |
| **DFU** | DFUモード（OTA更新用） |
| **FACTORY** | ファクトリーリセット（設定を初期化） |

> **注意**: DFUモードはOTA更新時のみ使用してください。通常はアプリからの更新が推奨されます。

## 技術スタック

### BikeClock (Arduino)

- **言語**: C++
- **フレームワーク**: Arduino + Adafruit nRF52 Bluefruit
- **BLEライブラリ**: Adafruit Bluefruit
- **表示ライブラリ**: TM1637
- **I/Oエキスパンダー**: Adafruit MCP23X17

### BTClockMob (Android)

- **言語**: Kotlin
- **UI**: Jetpack Compose + Material3
- **非同期処理**: Kotlin Coroutines + Flow
- **データ永続化**: DataStore Preferences
- **DI**: Koin
- **BLE**: Android BLE API
- **OTA DFU**: Nordic DFU Library

## トラブルシューティング

### システム全体

#### BLE接続できない

1. BikeClockの電源を入れ直す
2. AndroidのBluetooth設定でペアリング済みか確認
3. BTClockMobアプリを再起動
4. 他のBLEデバイスとの干渉を確認

#### 時刻が正しく表示されない

1. BTClockMobアプリで時刻同期を再実行
2. スマホの時刻設定を確認（自動設定がONになっているか）

#### OTA更新が失敗する

1. デバイスの電源を入れ直す
2. スマホとデバイスを近づける（ BLE通信距離を短くする）
3. ファームウェアファイルが正しいか確認
4. デバイスをDFUモードで再起動（メンテナンスモード→DFU選択）

### BikeClockデバイス関連

詳細なトラブルシューティングは [bikeclock/README.md](./bikeclock/README.md) を参照してください。

### BTClockMobアプリ関連

詳細なトラブルシューティングは [BTClockMob/README.md](./BTClockMob/README.md) を参照してください。

## 開発

### BLEテストツール（Arduino側）

開発時にBLE通信をテストするPythonツールが含まれています。

```bash
cd bikeclock
pip3 install bleak
python3 bikeclock_ble_test.py
```

### シリアルコンソール監視（Arduino側）

```bash
cd bikeclock
./consolelog.sh
```

## ライセンス

このプロジェクトはMITライセンスの下で提供されています。

## 謝辞

- [Adafruit nRF52 Bluefruit Library](https://github.com/adafruit/Adafruit_nRF52_Arduino) - BLEライブラリ
- [Adafruit MCP23017 Arduino Library](https://github.com/adafruit/Adafruit-MCP23017-Arduino-Library) - MCP23S17制御ライブラリ
- [TM1637](https://github.com/avishorp/TM1637) - LED表示ライブラリ
- [Seeed Studio](https://www.seeedstudio.com/) - XIAO BLEマイコンボード

---

**作成日**: 2026-04-22
**最終更新**: 2026-05-01
**ファームウェアバージョン**: 1.0.11
