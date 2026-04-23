# BikeClock 外部ライブラリ

このプロジェクトでは以下の外部ライブラリを使用しています。

## 必要なライブラリ

### 1. Adafruit nRF52 Bluefruit Library

- **用途**: BLE通信（Seeeduino XIAO BLE / nRF52840対応）
- **作者**: Adafruit
- **リポジトリ**: https://github.com/adafruit/Adafruit_nRF52_Arduino
- **依存ライブラリ**:
  - Adafruit BLE Library
  - Adafruit nRF52 Core
- **ライセンス**: BSD 3-Clause
- **インストール方法**:
  1. Arduino IDEのライブラリマネージャを開く
  2. "Adafruit nRF52" を検索
  3. "Adafruit nRF52 Bluefruit Library" を選択してインストール

**重要**: Adafruit nRF52 Bluefruit Libraryは、Seeeduino XIAO BLE（nRF52840）の公式BLEライブラリです。

### 2. TM1637

- **用途**: 4桁7セグメントLEDディスプレイ制御
- **作者**: Avishay Orpaz
- **リポジトリ**: https://github.com/avishorp/TM1637
- **ライセンス**: MIT
- **インストール方法**:
  1. Arduino IDEのライブラリマネージャを開く
  2. "TM1637" を検索
  3. "by Avishay Orpaz" を選択してインストール

## ボードサポートパッケージ

### Seeeduino nRF52 Boards

- **ボード**: Seeed XIAO BLE / Seeed XIAO BLE Sense
- **メーカー**: Seeed Studio
- **URL**: https://files.seeedstudio.com/arduino/package_seeeduino_boards_index.json
- **インストール方法**:
  1. Arduino IDEの「File」→「Preferences」を開く
  2. 「Additional Boards Manager URLs」の横にあるアイコンをクリック
  3. 上記URLを追加
  4. 「OK」をクリック
  5. ボードマネージャで "Seeed nRF52 Boards" を検索してインストール

**ボード名について**:
- Arduino IDE 2.x: 「Seeed XIAO BLE」を選択
- Arduino CLI: `Seeeduino:nrf52:xiaonRF52840` を使用

## ライブラリ管理

### インストール手順

Arduino IDE 2.xの場合：

1. Arduino IDEを開く
2. 左側のサイドバーにあるライブラリマネージャアイコン（📚）をクリック
3. 検索欄にライブラリ名を入力
4. 該当するライブラリの「インストール」ボタンをクリック

### バージョン確認

各ライブラリの最新バージョンを使用してください。問題がある場合は、以下のバージョンで動作確認済みです：

- **Adafruit nRF52 Bluefruit Library**: 1.0.0 以上
- **TM1637**: 1.2.0 以上

## トラブルシューティング

### ライブラリが見つからない

1. Arduino IDEを再起動する
2. ライブラリマネージャで正確に検索する（作者名を含める）
3. 手動でZIPファイルをダウンロードしてインストールする

### コンパイルエラーが発生する

1. ボードサポートパッケージが正しくインストールされているか確認
2. ライブラリのバージョンを確認
3. Arduino IDEのバージョンを確認（2.x推奨）

### BLE関連のエラー

Adafruit nRF52 Bluefruit Libraryが正しくインストールされているか確認してください。このライブラリはSeeeduino XIAO BLE（nRF52840）専用です。

**注意**:
- ArduinoBLEはArduino製ボード（MKR WiFi 1010、Nano 33 BLEなど）用です。nRF52840には使用しません。
- NimBLE-ArduinoはESP32用のライブラリです。nRF52840には使用しません。

## Bluefruit APIの主な違い

ArduinoBLEからBluefruitへの主な変更点：

1. **インクルードファイル**: `<ArduinoBLE.h>` → `<bluefruit.h>`
2. **初期化**: `BLE.begin()` → `Bluefruit.begin()`
3. **サービス作成**: `BLEService uuid("...")` → `BLEService uuid("...")`
4. **キャラクタリスティック**:
   - `BLECharacteristic` → `BLECharacteristic`
   - プロパティ設定が異なります
5. **ポーリング**: `BLE.poll()` → `Bluefruit.poll()`
6. **コールバック**:
   - `setEventHandler(BLEConnected, callback)` → `Bluefruit.Periph.setConnectCallback(callback)`
   - コールバック関数の引数が異なります

## 関連リソース

- [Seeed XIAO BLE ドキュメント](https://wiki.seeedstudio.com/Seeed-XIAO-BLE/)
- [Adafruit nRF52 Bluefruit Library ドキュメント](https://adafruit.github.io/Adafruit_nRF52_Arduino/)
- [Adafruit Bluefruit nRF52 GitHub](https://github.com/adafruit/Adafruit_nRF52_Arduino)
- [TM1637 GitHub](https://github.com/avishorp/TM1637)

---

**最終更新**: 2026-04-22
