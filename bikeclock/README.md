# BikeClock - XIAO BLE Bicycle Clock

BikeClockはXIAO BLEマイコンボードと4桁7セグメントLEDディスプレイを使用した、シンプルな時計デバイスです。

> **🔋 最大の特徴**: 内蔵電池の交換が一切不要！USB給電で動作するため、ノーメンテナンスで使用できます。

## 特徴

- ✅ **ノーメンテナンス設計**: 内蔵電池交換不要、USB給電で動作
- ✅ **BLE時刻同期**: 専用スマホアプリ（開発中）で自動時刻補正
- ✅ **4桁LED表示**: TM1637使用の明るいディスプレイ（常時最大輝度）
- ✅ **シンプル接続**: USBケーブルで給電
- ✅ **常時駆動**: 給電されている間は常に起動・表示

## ハードウェア構成

| コンポーネント | 説明 |
|--------------|------|
| マイコン | Seeed Studio XIAO BLE (nRF52840) |
| 表示 | 4-Digit LED Display (TM1637) |
| 電源 | USB電源（モバイルバッテリー等） |

### ピン接続

| XIAO BLEピン | TM1637ピン |
|-------------|-----------|
| D4 (SDA) | DIO |
| D5 (SCL) | CLK |
| 5V | VCC |
| GND | GND |

### 電源接続

```
USB電源（モバイルバッテリーやUSBアダプター）
    ↓
XIAO BLE (USB-Cポート)
```

## ソフトウェア構成

```
bikeclock/
├── bikeclock.ino           # メインファイル（RTC管理 + LED表示制御）
├── bikeclock.h             # ヘッダーファイル（固定設定）
├── bikeclock_ble.ino       # BLE通信処理
├── bikeclock_ble_test.py   # BLE通信テストツール
├── compile.sh              # コンパイルスクリプト
├── upload.sh               # アップロードスクリプト
├── consolelog.sh           # シリアルコンソール監視スクリプト
├── common.sh               # 共通関数ライブラリ
├── setting.sh.example      # 設定ファイルテンプレート
├── README.md               # このファイル
└── EXTERNAL_LIBRARIES.md   # 必要なライブラリ説明
```

## 必要なライブラリ

Arduino IDEで以下のライブラリをインストールしてください：

1. **Adafruit nRF52 Bluefruit Library** (by Adafruit)
   - BLE通信用（Seeeduino XIAO BLE / nRF52840対応）
   - ライブラリマネージャで検索してインストール

2. **TM1637** (by Avishay Orpaz)
   - LED表示制御用
   - ライブラリマネージャで検索してインストール

## ビルド手順

### Arduino IDE 2.xの場合

1. **Arduino IDE 2.xを開く**

2. **XIAO BLEボードサポートを追加**（初回のみ）
   - 「File」→「Preferences」を開く
   - 「Additional Boards Manager URLs」の横にあるアイコンをクリック
   - 以下のURLを追加：
     ```
     https://files.seeedstudio.com/arduino/package_seeeduino_boards_index.json
     ```
   - 「OK」をクリック

3. **ボードをインストール**（初回のみ）
   - 左側のサイドバーにあるボードマネージャアイコンをクリック
   - 検索欄に「seeed nrf」または「xiao ble」と入力
   - 「Seeed nRF52 Boards」をインストール

4. **ライブラリをインストール**（初回のみ）
   - 左側のサイドバーにあるライブラリマネージャアイコンをクリック
   - 以下のライブラリを検索してインストール：
     - `Adafruit nRF52 Bluefruit Library` by Adafruit
     - `TM1637` by Avishay Orpaz

5. **スケッチを開く**
   - 「File」→「Open」
   - `bikeclock/bikeclock.ino` を選択

6. **ボードとポートを選択**
   - 上部のボード選択で「Seeed XIAO BLE」を選択
     - または「Seeed XIAO BLE Sense」を選択（同じチップ）
     - Arduino CLIの場合は `Seeed:nrf52:xiaoble` を使用
   - ポート選択でXIAO BLEのポートを選択

7. **書き込み**
   - 左上の右矢印ボタン（→）をクリック
   - 書き込みが完了するまで待ちます

## 使用方法

### 初回起動

1. **電源接続**: XIAO BLEに5V電源を接続
2. **確認**: LEDが「8888」を表示してから「----」に変わります
3. **スマホアプリ**: 専用アプリを起動
4. **スキャン**: アプリで「スキャン」をタップ
5. **接続**: `BikeClock-0001` をタップして接続
6. **時刻同期**: 自動的に時刻が同期されます

### 時刻表示

- **通常時**: `HH:MM` 形式で時刻を表示（12時間または24時間表記）
- **コロンの点滅**: コロン（中央の2点）は毎秒点滅します

### 再同期方法

時刻がずれている場合は、以下の手順で再同期します：

1. 専用アプリを起動
2. 「スキャン」をタップ
3. `BikeClock-0001` をタップして接続
4. 自動的に時刻が同期されます

### BLEテストツール

開発時にBLE通信をテストするためのPythonツールが含まれています。

**依存ライブラリのインストール**
```bash
pip3 install bleak
```

**テスト実行**
```bash
cd bikeclock
python3 bikeclock_ble_test.py
```

このツールは以下のテストを実行します：
- BLEデバイスのスキャン
- BikeClock-0001 への接続
- サービスとキャラクタリスティックの確認
- 時刻同期コマンドの送信

## BLE仕様

### デバイス名

```
BikeClock-0001
```

### サービスUUID

```
4fafc201-1fb5-459e-8fcc-c5c9c331914c
```

### キャラクタリスティック

| UUID | プロパティ | 説明 |
|-----|----------|------|
| beb5483e-36e1-4688-b7f5-ea07361b26a0 | Write | 時刻同期コマンド受信 |
| beb5483e-36e1-4688-b7f5-ea07361b26a1 | Notify | レスポンス送信 |

### コマンドフォーマット

**時刻同期コマンド**
```
SET:time:<unix_timestamp>
例: SET:time:1234567890
```

**成功レスポンス**
```
OK: Time synced
```

**エラーレスポンス**
```
ERROR: Invalid timestamp format
```

## 表示形式

24時間表記で時刻を表示します（RTC設定に依存）。

## トラブルシューティング

### LEDが表示されない

1. 電源接続を確認（5V, GND）
2. TM1637の接続を確認（DIO, CLK）
3. 明るさ設定を確認

### BLE接続できない

1. XIAO BLEの電源を入れ直す
2. 専用アプリを再起動する
3. 他のBLEデバイスとの干渉を確認

### 時刻が正しく表示されない

1. 時刻同期を再実行する
2. スマホの時刻設定を確認する

## 開発

### ビルドコマンド（CLI）

**重要**: 最初に `setting.sh` の設定が必要です

```bash
# 1. 設定ファイルの作成（初回のみ）
cp setting.sh.example setting.sh

# 2. 設定ファイルを編集して BIKECLOCK_PORT を設定
vi setting.sh
# 設定例: BIKECLOCK_PORT="/dev/cu.usbmodem2101"
```

```bash
# コンパイル
./compile.sh

# アップロード（setting.sh からポートを読み込み）
./upload.sh
```

### シリアルコンソール監視

デバイスのログをリアルタイムで監視するには、`consolelog.sh`を使用します：

1. **設定ファイルの作成**（初回のみ）
   ```bash
   cp setting.sh.example setting.sh
   ```

2. **ポート設定**
   ```bash
   # setting.shを編集してBIKECLOCK_PORTを設定
   vi setting.sh

   # 設定例：
   BIKECLOCK_PORT="/dev/cu.usbmodem2101"
   ```

3. **コンソール監視開始**
   ```bash
   ./consolelog.sh
   ```

`consolelog.sh`は以下の機能を提供します：
- デバイスの自動検出（接続/切断を監視）
- アップロード中は自動的に一時停止
- 再接続時に自動的にコンソールを再開
- Ctrl+C で終了

## 関連プロジェクト

- **BTClockMob**: btclock専用スマホアプリ（開発中）
- **FastRec**: 録音レコーダーデバイス（元プロジェクト）

## ライセンス

このプロジェクトはMITライセンスの下で提供されています。

## 謝辞

- [Adafruit nRF52 Bluefruit Library](https://github.com/adafruit/Adafruit_nRF52_Arduino) - BLEライブラリ
- [TM1637](https://github.com/avishorp/TM1637) - LED表示ライブラリ
- [Seeed Studio](https://www.seeedstudio.com/) - XIAO BLEマイコンボード

---

**作成日**: 2026-04-22
**最終更新**: 2026-04-23
