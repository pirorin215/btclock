# BTClockMob - Androidアプリ

XIAO BLEマイコンボードを使用したバイク搭載用時計「BTClock」と連携するAndroidアプリケーション。Kotlin + Jetpack Composeで構築されています。

## 特徴

- **BLE自動接続**: 近接するBTClockデバイス（BikeClock）の自動検出とバックグラウンド接続
- **時刻同期**: BLE経由でのUnixタイムスタンプ送信による自動時刻調整（JST対応）
- **接続履歴**: バイクのイグニッションON/OFF（BLE接続/切断）に連動した位置情報の自動記録
- **バックグラウンド実行**: 常駐サービスによるシームレスな体験
- **リモートキー設定**: BikeClockデバイスの物理スイッチにHIDキーコードを割り当て可能

## 技術スタック

- **言語**: Kotlin
- **UI**: Jetpack Compose + Material3
- **非同期処理**: Kotlin Coroutines + Flow
- **データ永続化**: DataStore Preferences
- **DI**: Koin
- **テスト**: JUnit 4, MockK, Robolectric
- **BLE**: Android BLE API

## ビルド

### 前提条件

- Android Studio Hedgehog (2023.1.1) 以降
- JDK 17
- Android SDK 34+

### ビルドコマンド

```bash
# デバッグビルド
./gradlew assembleDebug

# リリースビルド
./gradlew assembleRelease

# テスト実行
./gradlew test
./gradlew connectedAndroidTest
```

### インストール

生成されたAPKをインストール：
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

または、Android Studioから直接デバイスにインストール。

## プロジェクト構成

### ディレクトリ構成

```
/app/src/main/java/com/pirorin215/btclockmob/
├── bluetooth/              # BLE通信管理
│   ├── constants/          # BLE UUID等
│   ├── device/             # 時刻同期ロジック
│   └── settings/           # デバイス設定管理
├── constants/              # 各種定数
├── data/                   # Repository, DataStore, Entity
├── service/                # ババックグラウンドサービス
├── ui/                     # UI画面 (Compose)
│   ├── screen/             # 各画面
│   └── components/         # 共通UI部品
└── viewModel/              # 状態管理・ビジネスロジック
```

## 機能詳細

### 1. BLE自動接続

近接するBikeClockデバイスを自動的に検出し、バックグラウンドで接続します。

**技術的仕組み:**
- Android BLE Scan APIを使用
- バックグラウンドスキャン対応（FOREGROUND_SERVICE_CONNECTED_DEVICE）
- デバイス名「BikeClock」でフィルタリング

**必要なパーミッション:**
- BLUETOOTH_SCAN
- BLUETOOTH_CONNECT
- ACCESS_FINE_LOCATION
- FOREGROUND_SERVICE_CONNECTED_DEVICE

### 2. 時刻同期

BikeClockデバイスにJST時刻（Unixタイムスタンプ）を送信します。

**同期タイミング:**
- 接続時（即時同期）
- 1分間隔の定期同期

**コマンドフォーマット:**
```
SET:time:<unix_timestamp>
例: SET:time:1234567890
```

**技術的仕組み:**
- Kotlin Coroutines + Flowで定期実行
- JSTタイムゾーン変換（UTC + 9時間）
- BLE GATT Write操作

### 3. 接続履歴

バイクのイグニッションON/OFF（BLE接続/切断）のタイミングで位置情報を記録します。

**保存内容:**
- 接続/切断のタイムスタンプ
- 位置情報（緯度・経度）
- イベント種別（CONNECTED/DISCONNECTED）

**設計意図:**
- BTClock（BikeClock）はバイクのイグニッションONで起動し、OFFで終了します
- この起動（接続）と終了（切断）のタイミングを全て記録することで、バイクの走行履歴（いつ、どこから、どこまで走ったか）を把握できます

**保存条件:**
- **無条件保存**: BLEの接続および切断イベントが発生するたびに、位置情報と共に履歴を保存します
- 旧来（録音レコーダー時代）の時間経過や距離によるフィルタリングは行いません

**データ永続化:**
- Room Databaseでローカル保存
- DataStore Preferencesで設定管理

### 4. キーコード設定機能

BikeClockデバイスの7つの物理スイッチに、任意のHIDキーコードを割り当て可能です。

**対応する主なキー**:
- **矢印キー**: 左(0x50)、右(0x4F)、上(0x52)、下(0x51)
- **メディアキー**: 再生/一時停止(0xCD)、次のトラック(0xB5)、前のトラック(0xB6)
- **音量操作**: 音量アップ(0xE9)、音量ダウン(0xEA)、ミュート(0xE2)
- **Androidキー**: 戻る(0x0224)、ホーム(0x0223)
- **その他**: Enter、Space、ESC等

**設定方法**:
1. アプリの「キー設定」画面を開く
2. 各スイッチに割り当てるキーをプリセットから選択、またはHEXコードで直接指定
3. 「設定をデバイスに反映する」ボタンでデバイスに送信
4. デバイスのフラッシュメモリに保存され、電源OFF後も維持されます

**技術的な仕組み**:
- HID Usage ID (Keyboard Page & Consumer Page)を直接使用
- Android KeyEventからの変換は行わず、HID Usage IDをそのまま管理
- マイコン側はキーコード値のみを保存し、キー名の管理はアプリ側で行う

**コマンドフォーマット:**
```
SET:keys:HEX1,HEX2,HEX3,HEX4,HEX5,HEX6,HEX7
例: SET:keys:50,4F,52,51,28,0224,CD
```

**デフォルト設定**（ファームウェア出荷時）:
| スイッチ | キーコード | 機能 |
|---------|----------|------|
| SW1 | 0x50 | 左矢印キー |
| SW2 | 0x4F | 右矢印キー |
| SW3 | 0x52 | 上矢印キー |
| SW4 | 0x51 | 下矢印キー |
| SW5 | 0x28 | Enter |
| SW6 | 0x0224 | Back（Android用） |
| SW7 | 0xCD | 再生/一時停止 |

### 5. バックグラウンド実行

常駐サービスによるシームレスな体験を実現します。

**技術的仕組み:**
- Foreground Service（通知表示必須）
- Notification APIで進行中通知を表示
- バッテリー最適化対応

**通知内容:**
- 接続状態
- 最後の時刻同期時刻
- サービス停止ボタン

## UI画面

### メイン画面

- **接続状態**: BikeClockデバイスとの接続状態を表示
- **時刻同期**: 最後の同期時刻を表示
- **手動同期**: 同期ボタンで即時時刻同期
- **履歴タブ**: 接続履歴一覧への遷移
- **キー設定タブ**: キーコード設定画面への遷移

### 履歴画面

- **履歴一覧**: 接続/切断履歴を時系列で表示
- **マップ表示**: 位置情報を地図で表示（オプション）
- **削除機能**: 履歴の削除

### キー設定画面

- **スイッチ選択**: SW1-SW7の各スイッチを選択
- **キー選択**: プリセットから選択、またはHEXコードで直接指定
- **保存ボタン**: 設定をデバイスに送信
- **読み込みボタン**: デバイスから現在の設定を読み込み

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

詳細な実装方法は [docs/hid-custom-service-dual-implementation.md](./docs/hid-custom-service-dual-implementation.md) を参照してください。

## パーミッション

アプリには以下のパーミッションが必要です：

| パーミッション | 用途 |
|--------------|------|
| BLUETOOTH_SCAN | BLEデバイススキャン |
| BLUETOOTH_CONNECT | BLEデバイス接続 |
| ACCESS_FINE_LOCATION | 位置情報取得（BLEスキャンに必要） |
| ACCESS_COARSE_LOCATION | 大まかな位置情報 |
| FOREGROUND_SERVICE | バックグラウンドサービス |
| FOREGROUND_SERVICE_CONNECTED_DEVICE | BLE関連のフォアグラウンドサービス |
| POST_NOTIFICATIONS | 通知表示 |

## トラブルシューティング

### 自動接続されない

1. 位置情報パーミッションを許可
   - 設定 → アプリ → パーミッション → 位置情報 → 「常に許可」
2. BluetoothがONになっているか確認
3. バックグラウンド実行が許可されているか確認
   - 設定 → アプリ → バッテリー → 「制限なし」
4. BikeClockデバイスが近くにあるか確認

### 履歴が記録されない

1. 位置情報パーミッションを許可
2. 「常に許可」を選択
3. GPSが有効になっているか確認

### キー設定が反映されない

1. デバイスと接続しているか確認
2. 設定を再送信
3. シリアルコンソールで`OK: keys updated`のログを確認

### 時刻同期がされない

1. デバイスと接続しているか確認
2. 手動同期ボタンを押してみる
3. スマホの時刻設定が自動になっているか確認

### バッテリー消費が気になる

1. 位置情報の精度を「省電力」に設定
2. 定期同期の間隔を延長（ソースコード修正）

## 開発

### テスト

```bash
# 単体テスト
./gradlew test

# UIテスト
./gradlew connectedAndroidTest

# カバレッジ
./gradlew jacocoTestReport
```

### コードスタイル

プロジェクトではktlintとdetektを使用しています：

```bash
# コードスタイルチェック
./gradlew ktlintCheck

# フォーマット
./gradlew ktlintFormat

# 静的解析
./gradlew detekt
```

### リファクタリング履歴

#### BTClockへの移行 (2026/04)

**目的**: 音声レコーダーアプリ（FastRecMob）からバイク用時計アプリ（BTClockMob）への完全移行

**1. 不要機能の削除**
- 録音データ転送機能（BLEファイル転送）の削除
- 音声認識（Google Cloud Speech-to-Text）関連の削除
- Google Tasks連携機能の削除
- 電圧グラフ表示（Vico）の削除

**2. 時刻同期の実装**
- BikeClockデバイス仕様に合わせた時刻同期コマンド（SET:time）の実装
- 定期的な時刻同期ジョブ（1分間隔）の追加

**3. デバイス履歴の最適化**
- 重複除外フィルタリングの撤廃（全ての接続/切断イベントを記録）
- 位置情報とタイムスタンプのみのシンプルな記録形式へ変更

## 関連プロジェクト

- **bikeclock**: BikeClockファームウェア
- **BTClock（ルート）**: システム全体ドキュメント

## ライセンス

このプロジェクトはMITライセンスの下で提供されています。

---

**作成日**: 2026-04-23
**最終更新**: 2026-04-26
