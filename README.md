# BTClock - バイク搭載用Bluetooth時計システム

XIAO BLEマイコンボードを使用したバイク搭載用時計デバイス「BikeClock」と、それに連携するAndroidアプリ「BTClockMob」の統合システムです。

## システム概要

BTClockはバイクのイグニッション連動で動作する時計システムです。イグニッションONでデバイスが起動し、スマホアプリが自動接続して時刻同期を行います。また、物理スイッチによるHIDキーボード機能を備え、YouTube等の操作が可能です。

### 構成要素

```
┌─────────────────┐         BLE (HID + GATT)         ┌─────────────────┐
│  BikeClock      │◄─────────────────────────────────┤  BTClockMob     │
│  (Arduinoデバイス)│                                 │  (Androidアプリ) │
└─────────────────┘                                 └─────────────────┘
        │                                                   │
        ├─ 4桁LED表示                                       ├─ 自動時刻同期
        ├─ BLE通信 (HID + Custom GATT)                      ├─ 接続履歴記録
        ├─ 4つの物理スイッチ (HIDキーボード)                ├─ リモートキー設定
        └─ USB給電                                          └─ バックグラウンド実行
```

## 主な機能

### BikeClockデバイス

- ✅ **ノーメンテナンス設計**: 内蔵電池交換不要、USB給電で動作
- ✅ **4桁LED表示**: TM1637使用の明るいディスプレイ
- ✅ **BLE時刻同期**: スマホアプリで自動時刻補正
- ✅ **HIDキーボード機能**: 4つの物理スイッチでキー入力
- ✅ **キーコード設定**: アプリからスイッチのキー割り当てを変更可能

### BTClockMobアプリ

- ✅ **BLE自動接続**: 近接するBikeClockデバイスの自動検出
- ✅ **時刻同期**: BLE経由でのUnixタイムスタンプ送信
- ✅ **接続履歴**: イグニッションON/OFF（BLE接続/切断）に連動した位置情報の自動記録
- ✅ **バックグラウンド実行**: 常駐サービスによるシームレスな体験
- ✅ **リモートキー設定**: デバイスのスイッチにキーコードを割り当て

## プロジェクト構成

```
btclock/
├── bikeclock/              # Arduinoファームウェア
│   ├── bikeclock.ino       # メインファイル
│   ├── bikeclock.h         # ヘッダーファイル
│   ├── bikeclock_ble.ino   # BLE通信処理
│   └── README.md           # 詳細ドキュメント
│
├── BTClockMob/             # Androidアプリ
│   ├── app/                # メインアプリケーション
│   ├── gradle/             # Gradle設定
│   └── README.md           # 詳細ドキュメント
│
└── README.md               # このファイル
```

## 技術仕様

### BLE通信仕様

#### デバイス情報

| 項目 | 値 |
|------|-----|
| デバイス名 | `BikeClock-0001` |
| メーカー | pirorin215 |
| モデル | BikeClock Dual |

#### サービス構成

**1. HIDサービス (0x1812)** - Android OSが接続管理
- キーボード/メディアキー入力用
- 物理スイッチ操作でHID信号を送信

**2. カスタムサービス** - BTClockMobアプリが接続管理

| UUID | プロパティ | 説明 |
|------|----------|------|
| `4fafc201-1fb5-459e-8fcc-c5c9c331914c` | - | サービスUUID |
| `beb5483e-36e1-4688-b7f5-ea07361b26a0` | Read/Write/Notify | コマンド受信・レスポンス送信 |
| `beb5483e-36e1-4688-b7f5-ea07361b26a1` | Notify | スイッチ状態通知（オプション） |

#### コマンドフォーマット

**時刻同期**
```
SET:time:<unix_timestamp>
例: SET:time:1234567890
```

**キーコード設定**
```
SET:keys:HEX1,HEX2,HEX3,HEX4
例: SET:keys:50,4F,52,51
```

**成功レスポンス**
```
OK: Time synced
OK: keys updated
```

### ハードウェア構成

| コンポーネント | 説明 |
|--------------|------|
| マイコン | Seeed Studio XIAO BLE (nRF52840) |
| 表示 | 4-Digit LED Display (TM1637) |
| スイッチ | 4つのタクトスイッチ (HID入力用) |
| 電源 | USB電源（モバイルバッテリー等） |

#### ピン接続

**LEDディスプレイ (TM1637)**
| XIAO BLEピン | TM1637ピン |
|-------------|-----------|
| D4 (SDA) | DIO |
| D5 (SCL) | CLK |
| 5V | VCC |
| GND | GND |

**スイッチ**
| スイッチ | XIAO BLEピン | デフォルトキーコード |
|---------|-------------|-------------------|
| SW1 | D0 (P0.02) | 0x50 (Left Arrow) |
| SW2 | D1 (P0.03) | 0x4F (Right Arrow) |
| SW3 | D2 (P0.04) | 0x52 (Up Arrow) |
| SW4 | D3 (P0.29) | 0x51 (Down Arrow) |

## セットアップ手順

### 1. BikeClockデバイスのセットアップ

詳細は [bikeclock/README.md](./bikeclock/README.md) を参照してください。

#### 必要なライブラリ

Arduino IDEで以下のライブラリをインストール：

1. **Adafruit nRF52 Bluefruit Library** (by Adafruit)
2. **TM1637** (by Avishay Orpaz)

#### ボード設定

1. Arduino IDE 2.xを開く
2. 「File」→「Preferences」で以下のURLを追加：
   ```
   https://files.seeedstudio.com/arduino/package_seeeduino_boards_index.json
   ```
3. ボードマネージャで「Seeed nRF52 Boards」をインストール
4. 「Seeed XIAO BLE」または「Seeed XIAO BLE Sense」を選択

#### 書き込み

```bash
cd bikeclock
# 設定ファイル作成
cp setting.sh.example setting.sh
# setting.shを編集してBIKECLOCK_PORTを設定

# コンパイル
sh compile.sh

# アップロード
sh upload.sh
```

### 2. BTClockMobアプリのセットアップ

詳細は [BTClockMob/README.md](./BTClockMob/README.md) を参照してください。

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

## 使用方法

### 初回起動

1. **BikeClockの電源**: USB電源を接続
2. **LED表示**: 「8888」→「----」の変化を確認
3. **Androidでペアリング**: Bluetooth設定で「BikeClock」をペアリング
4. **BTClockMob起動**: アプリを起動してスキャン
5. **接続**: 「BikeClock-0001」をタップして接続
6. **時刻同期**: 自動的に時刻が同期されます

### 時刻表示

- **通常時**: `HH:MM` 形式で時刻を表示（24時間表記）
- **コロンの点滅**: コロン（中央の2点）は毎秒点滅

### HIDスイッチ操作

デフォルト設定：
- **SW1**: 左矢印 (YouTubeのシーク戻し)
- **SW2**: 右矢印 (YouTubeのシーク進み)
- **SW3**: 上矢印
- **SW4**: 下矢印

アプリからキー割り当てを変更可能。主なプリセット：
- **再生/一時停止** (0xCD): YouTube等の再生/停止
- **次のトラック** (0xB5): 次の曲/動画へ
- **前のトラック** (0xB6): 前の曲/動画へ
- **音量アップ** (0xE9): 音量を上げる
- **音量ダウン** (0xEA): 音量を下げる
- **ミュート** (0xE2): ミュート/ミュート解除
- **戻る (Android)** (0x0224): Androidの「戻る」ボタン

**実用例**：
- YouTube音楽をバックグラウンド再生中に、SW3に「再生/一時停止」を割り当て
- SW1/SW2でシーク操作、SW3で再生/停止、SW4で「戻る」ボタン
- スマホを操作することなく、ハンドル近くで全て操作可能

### リモートキー設定

1. BTClockMobアプリで「キー設定」画面を開く
2. 各スイッチに割り当てるキーを選択
3. 「保存」をタップ
4. デバイスに設定が送信され、フラッシュメモリに保存されます

### 接続履歴の確認

1. BTClockMobアプリの「履歴」タブを開く
2. BLE接続/切断の履歴と位置情報が表示されます
3. これによりバイクの走行履歴（いつ、どこから、どこまで走ったか）を把握できます

## 技術スタック

### BikeClock (Arduino)

- **言語**: C++
- **フレームワーク**: Arduino + Adafruit nRF52 Bluefruit
- **BLEライブラリ**: Adafruit Bluefruit
- **表示ライブラリ**: TM1637

### BTClockMob (Android)

- **言語**: Kotlin
- **UI**: Jetpack Compose + Material3
- **非同期処理**: Kotlin Coroutines + Flow
- **データ永続化**: DataStore Preferences
- **DI**: Koin
- **BLE**: Android BLE API

## BLEアーキテクチャ

このシステムでは、HIDプロファイルとカスタムGATTサービスを同時に使用します。

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

## トラブルシューティング

### BikeClock関連

#### LEDが表示されない

1. 電源接続を確認（5V, GND）
2. TM1637の接続を確認（DIO, CLK）
3. 明るさ設定を確認

#### BLE接続できない

1. XIAO BLEの電源を入れ直す
2. BTClockMobアプリを再起動する
3. 他のBLEデバイスとの干渉を確認
4. AndroidのBluetooth設定でペアリング済みか確認

#### 時刻が正しく表示されない

1. BTClockMobアプリで時刻同期を再実行
2. スマホの時刻設定を確認

### BTClockMobアプリ関連

#### 自動接続されない

1. 位置情報パーミッションを許可
2. BluetoothがONになっているか確認
3. バックグラウンド実行が許可されているか確認

#### 履歴が記録されない

1. 位置情報パーミッションを許可
2. 「常に許可」を選択

#### キー設定が反映されない

1. デバイスと接続しているか確認
2. 設定を再送信

## 開発

### BLEテストツール

開発時にBLE通信をテストするPythonツールが含まれています。

```bash
cd bikeclock
pip3 install bleak
python3 bikeclock_ble_test.py
```

### シリアルコンソール監視

```bash
cd bikeclock
./consolelog.sh
```

## ライセンス

このプロジェクトはMITライセンスの下で提供されています。

## 謝辞

- [Adafruit nRF52 Bluefruit Library](https://github.com/adafruit/Adafruit_nRF52_Arduino) - BLEライブラリ
- [TM1637](https://github.com/avishorp/TM1637) - LED表示ライブラリ
- [Seeed Studio](https://www.seeedstudio.com/) - XIAO BLEマイコンボード

---

**作成日**: 2026-04-24  
**最終更新**: 2026-04-24  
**ファームウェアバージョン**: 1.0.2  
**最終機能追加**: YouTube等の再生/停止、シーク操作、Android戻るボタン等の物理スイッチ操作に対応
