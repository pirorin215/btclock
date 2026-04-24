# BTClockMob - Androidアプリ

XIAO BLEマイコンボードを使用したバイク搭載用時計「BTClock」と連携するAndroidアプリケーション。Kotlin + Jetpack Composeで構築されています。

## 特徴

- **BLE自動接続**: 近接するBTClockデバイス（BikeClock）の自動検出とバックグラウンド接続
- **時刻同期**: BLE経由でのUnixタイムスタンプ送信による自動時刻調整
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

## ビルド

```bash
./gradlew assembleDebug    # デバッグビルド
./gradlew assembleRelease  # リリースビルド
```

## プロジェクト構成

### キーコード設定機能

BikeClockデバイスの4つの物理スイッチに、任意のHIDキーコードを割り当て可能です。

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

### ディレクトリ構成

```
/app/src/main/java/com/pirorin215/btclockmob/
├── bluetooth/              # BLE通信管理
│   ├── constants/          # BLE UUID等
│   ├── device/             # 時刻同期ロジック
│   └── settings/           # デバイス設定管理
├── constants/              # 各種定数
├── data/                   # Repository, DataStore, Entity
├── service/                # バックグラウンドサービス
├── ui/                     # UI画面 (Compose)
└── viewModel/              # 状態管理・ビジネスロジック
```

## デバイス履歴の保存ルール

### 設計意図

- BTClock（BikeClock）はバイクのイグニッションONで起動し、OFFで終了します。
- この起動（接続）と終了（切断）のタイミングを全て記録することで、バイクの走行履歴（いつ、どこから、どこまで走ったか）を把握できます。

### 保存条件

- **無条件保存**: BLEの接続および切断イベントが発生するたびに、位置情報と共に履歴を保存します。
- 旧来（録音レコーダー時代）の時間経過や距離によるフィルタリングは行いません。

## パーミッション

- BLUETOOTH_SCAN / BLUETOOTH_CONNECT
- ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION
- FOREGROUND_SERVICE / FOREGROUND_SERVICE_CONNECTED_DEVICE
- POST_NOTIFICATIONS
