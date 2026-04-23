# FastRecMob - Androidアプリ

FastRecデバイスと連携するAndroidアプリケーション。Kotlin + Jetpack Composeで構築されています。

## 特徴

- **BLEスキャン・接続**: 近接するFastRecデバイスの自動検出
- **データ転送**: 録音データのBLE経由での転送
- **音声認識**: Google Cloud Speech-to-Textとの統合
- **Tasks連携**: 文字起こし結果をGoogle Tasksに登録
- **バックグラウンド実行**: 常駐サービスによる自動接続
- **パラメータ設定**: デバイスパラメータの変更（振動フィードバック含む）

## 技術スタック

- **言語**: Kotlin
- **UI**: Jetpack Compose + Material3
- **非同期処理**: Kotlin Coroutines + Flow
- **通信**: Retrofit + OkHttp
- **データ永続化**: DataStore Preferences
- **グラフ**: Vico
- **クラウド**: Google Cloud Speech-to-Text
- **テスト**: JUnit 4, MockK, Kotlin Coroutines Test

## ビルド

```bash
./gradlew assembleDebug    # デバッグビルド
./gradlew assembleRelease  # リリースビルド
```

## テスト

### テスト実行方法

```bash
# 全単体テスト実行
./gradlew test

# 特定のテストクラス実行
./gradlew test --tests TranscriptionStateManagerTest

# 特定のテストメソッド実行
./gradlew test --tests "TranscriptionStateManagerTest.初期状態はIdleである"

# テストレポート確認（HTML）
open app/build/reports/tests/testDebugUnitTest/index.html
```

### テストカバレッジ

- **総テスト数**: 29
- **成功**: 29 (100%)
- **失敗**: 0
- **テスト対象クラス**:
  - TranscriptionStateManager (StateFlowの状態管理)
  - TranscriptionQueueManager (キュー操作と排他制御)
  - GroqSpeechService (API連携)
  - ExampleUnitTest (サンプルテスト)

### テスト追加ガイドライン

#### テストファイルの配置

```
/app/src/test/java/com/pirorin215/fastrecmob/
├── viewModel/transcription/
│   ├── TranscriptionStateManagerTest.kt
│   ├── TranscriptionQueueManagerTest.kt
│   └── (他のマネージャーテスト)
└── bluetooth/
    └ (BLE関連テスト)
```

#### マネージャークラスの単体テスト作成手順

1. **テストクラスを作成**
   ```kotlin
   @OptIn(ExperimentalCoroutinesApi::class)
   class YourManagerTest {
       private lateinit var manager: YourManager

       @Before
       fun setup() {
           // モックとテスト対象の初期化
       }

       @Test
       fun `テストケースの説明`() = runTest {
           // Given: 準備
           // When: 実行
           // Then: 検証
       }
   }
   ```

2. **MockKを使用したモック化**
   ```kotlin
   private lateinit var mockDependency: Dependency
   mockDependency = mockk()
   every { mockDependency.method(any()) } returns expectedResult
   ```

3. **コルーチンテスト**
   ```kotlin
   @Test
   fun `非同期処理のテスト`() = runTest {
       // StandardTestDispatcherが自動的に使用される
       val result = manager.suspendMethod()
       assertEquals(expected, result)
   }
   ```

4. **StateFlow/SharedFlowの検証**
   ```kotlin
   @Test
   fun `StateFlowの値を検証`() {
       assertEquals("初期値", manager.stateFlow.value)
   }
   ```

#### テストの命名規則

- テストメソッド: バッククォートで囲んだ日本語の説明
  - 良い例: `` `初期状態はIdleである` ``
  - 良い例: `` `キューにアイテムを追加できる` ``
- テストクラス: `{対象クラス名}Test`

#### 依存関係

build.gradle.ktsに追加済み:
```kotlin
testImplementation(libs.junit)
testImplementation("io.mockk:mockk:1.13.5")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
testImplementation("androidx.arch.core:core-testing:2.2.0")
```

## プロジェクト構成

### リファクタリング履歴

#### セットAリファクタリング (2026/02)

**目的**: コードの保守性向上と責務の分離

**1. マジックナンバー定数化**
- 5つの定数ファイルを作成し、ハードコードされた値を一元管理
  - `TimeConstants.kt`: 時間関連の定数（同期間隔、タイムアウト等）
  - `BleTimeoutConstants.kt`: BLE操作のタイムアウト値
  - `FileTransferConstants.kt`: ファイル転送の定数
  - `LocationConstants.kt`: 位置情報関連の定数
  - `TranscriptionConstants.kt`: 文字起こし関連の定数

**2. TranscriptionManagerの分割**
- 766行のTranscriptionManagerを6つのマネージャークラスに分割
  - `TranscriptionStateManager`: StateFlowによる状態管理
  - `TranscriptionServiceManager`: サービス初期化と管理
  - `TranscriptionNotificationManager`: 通知処理
  - `TranscriptionQueueManager`: キュー管理と排他制御
  - `TranscriptionProcessor`: 文字起こし処理の実行
  - `TranscriptionResultManager`: 結果のCRUD操作

**3. BLE関連クラスの統合**
- 5つのクラスを4つの整理されたクラスに再編成
  - `BleConstants`: UUID等の定数を一元管理
  - `BleNotificationManager`: 低電圧通知ロジック
  - `BleDeviceManager`: デバイス情報と操作
  - `BleSettingsManager`: 設定管理
- 削除: `BleDeviceCommandManager.kt` (533行) - 機能を各クラスに分散

**成果**:
- コードの可読性向上: 定数の意味が明確に
- 保守性向上: 責務が明確に分離
- テスト容易性: 小さなクラス単位でテスト可能

### ディレクトリ構成

```
/app/src/main/java/com/pirorin215/fastrecmob/
├── constants/              # マジックナンバー定数化
│   ├── TimeConstants.kt
│   ├── BleTimeoutConstants.kt
│   ├── FileTransferConstants.kt
│   ├── LocationConstants.kt
│   └── TranscriptionConstants.kt
├── viewModel/
│   ├── transcription/      # TranscriptionManager分割
│   │   ├── TranscriptionStateManager.kt
│   │   ├── TranscriptionServiceManager.kt
│   │   ├── TranscriptionNotificationManager.kt
│   │   ├── TranscriptionQueueManager.kt
│   │   ├── TranscriptionProcessor.kt
│   │   └── TranscriptionResultManager.kt
│   └── MainViewModel.kt
├── bluetooth/              # BLEクラス統合
│   ├── constants/
│   │   └── BleConstants.kt
│   ├── notification/
│   │   └── BleNotificationManager.kt
│   ├── device/
│   │   └── BleDeviceManager.kt
│   └── settings/
│       └── BleSettingsManager.kt
└── (その他パッケージ)
```

## 依存関係

主なライブラリ（gradle/libs.versions.tomlで管理）：

- androidx.core.ktx
- androidx.compose.* (BOM管理)
- androidx.lifecycle.*
- retrofit2
- okhttp3
- google.cloud.speech
- kotlinx.serialization

## パーミッション

- BLUETOOTH_SCAN / BLUETOOTH_CONNECT
- ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION
- FOREGROUND_SERVICE / FOREGROUND_SERVICE_CONNECTED_DEVICE
- INTERNET
- POST_NOTIFICATIONS

## アーキテクチャ

- `MainActivity`: アプリのエントリーポイント
- `BleScanService`: バックグラウンドBLEスキャン
- `BootCompletedReceiver`: 起動時自動実行

## デバイス履歴の保存ルール

### 設計意図

- 録音利用時は毎回BLE接続発生
- 全履歴保存でデータ膨大化
- 移動検出時は紛失防止タグとして機能

### 保存条件

以下の**両方**を満たす場合は履歴を保存しない（重複除外）:

1. **時間条件**: 前回保存から30分以内
   - 定数: `TimeConstants.DEVICE_HISTORY_TIME_THRESHOLD_MS = 30分`

2. **位置条件**: 位置が類似
   - 定数: `LocationConstants.LOCATION_THRESHOLD`
   - 判定: 緯度・経度の差分二乗和が閾値未満

### 特殊ケース

- **移動検出時**: 位置非類似なら経過時間に関わらず保存
- **初回接続**: 履歴空なら無条件保存
- **位置情報なし**: 位置不完全なら「非類似」とみなす

### 実装

- クラス: `DeviceHistoryRepository.addEntry()`
- ファイル: `app/src/main/java/com/pirorin215/fastrecmob/data/DeviceHistoryRepository.kt`
- ロジック: 64行目の重複除外判定
