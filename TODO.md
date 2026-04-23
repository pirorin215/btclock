# BTClock プロジェクト TODO

## プロジェクト概要

BTClockはXIAO BLEマイコンボードと4桁7セグメントLEDディスプレイを使用したバイク搭載用のシンプルな時計デバイスです。

- **ファームウェア**: `bikeclock/` - 完了済み ✅
- **スマホアプリ**: `BTClockMob/` - 開発中 🔄

---

## 現在のステータス

### 完了済み ✅
- [x] fastrecプロジェクトからbtclockを分離
- [x] bikeclockファームウェア完成・動作確認済み
- [x] BTClockMobベース作成（FastRecMobから移植）
- [x] パッケージ名変更（com.pirorin215.fastrecmob → com.pirorin215.btclockmob）
- [x] アプリ名変更（FastRecMob → BTClockMob）
- [x] アイコン作成（デジタル時計風デザイン）
- [x] fastrec文字列の完全削除

---

## 重要：BTClockMobの目的

**FastRecMobとの違い:**
- ❌ 録音機能なし
- ❌ 音声認識機能なし
- ❌ Google Tasks連携なし
- ❌ ファイル転送機能なし
- ❌ バッテリーレベル取得なし
- ✅ BLE接続・時刻同期のみ
- ✅ 接続/切断時の位置情報記録

---

## 優先順位別タスク

### 🔥 優先度高：不要機能の削除

#### 1. 音声認識関連の削除
- [ ] Google Cloud Speech-to-Text関連ファイルの削除
- [ ] GroqSpeechServiceの削除
- [ ] TranscriptionManager関連の削除
- [ ] 文字起こしUI画面の削除
- [ ] 依存ライブラリの削除（Google Cloud Libraries BOM等）

**削除対象ファイル:**
- `app/src/main/java/com/pirorin215/btclockmob/service/GroqSpeechService.kt`
- `app/src/main/java/com/pirorin215/btclockmob/service/SpeechToTextService.kt`
- `app/src/main/java/com/pirorin215/btclockmob/viewModel/transcription/` ディレクトリ全体
- `app/src/main/java/com/pirorin215/btclockmob/usecase/GoogleTasksUseCase.kt`
- `app/src/main/java/com/pirorin215/btclockmob/viewModel/GoogleTasks*.kt`

#### 2. Google Tasks連携の削除
- [ ] GoogleSignIn関連の削除
- [ ] Google Tasks Managerの削除
- [ ] 設定画面のGoogle Tasks関連項目削除

#### 3. ファイル転送機能の削除
- [ ] FileTransferManagerの削除
- [ ] ADPCM関連の削除
- [ ] `app/src/main/jni/` ディレクトリの削除

#### 4. 依存ライブラリの整理
**build.gradle.kts から削除:**
```kotlin
// 削除
implementation(libs.retrofit)
implementation(libs.retrofit.converter.kotlinx.serialization)
implementation(libs.okhttp)
implementation(libs.okhttp.logging.interceptor)
implementation(libs.google.play.services.auth)
implementation(platform(libs.google.cloud.libraries.bom))
implementation(libs.google.cloud.speech)
implementation(libs.kotlinx.coroutines.guava)
implementation(libs.grpc.okhttp)
implementation(libs.generativeai)
implementation(libs.composereorderable)

// Vico グラフライブラリも削除（電圧グラフ機能なし）
implementation(libs.vico.compose)
implementation(libs.vico.compose.m3)
implementation(libs.vico.core)
```

#### 5. UI画面の削除・簡素化
**削除する画面:**
- [ ] TranscriptionResultPanel（文字起こし結果画面）
- [ ] AdpcmTestScreen（ADPCMテスト画面）
- [ ] GoogleTasksSyncSettingsScreen
- [ ] LogDownloadScreen（レコーダログ画面）
- [ ] WavSaveFolderScreen（WAV保存フォルダ画面）

**残す画面:**
- [x] MainScreen（メイン画面）
- [x] DeviceHistoryScreen（デバイス履歴画面）
- [x] AppSettingsScreen（アプリ設定画面）

#### 6. デバイス履歴の修正
**削除する情報:**
- [ ] 電圧情報
- [ ] バッテリーレベル
- [ ] ファイルリスト

**残す情報:**
- [x] 接続時刻
- [x] 接続位置（緯度・経度）
- [x] 切断時刻（新規追加）
- [x] 切断位置（新規追加）

---

### 🚀 優先度中：BikeClock専用機能の実装

#### 7. BLEデバイス情報の変更
**変更点:**
```kotlin
// BleConnectionManager.kt
const val DEVICE_NAME = "BikeClock"  // "fastrec" から変更済み ✅

// BLE UUID（既にbikeclockファームウェアと一致）
SERVICE_UUID: "4fafc201-1fb5-459e-8fcc-c5c9c331914c"
CHARACTERISTIC_UUID: "beb5483e-36e1-4688-b7f5-ea07361b26a0"
```

#### 8. 時刻同期機能の実装
**実装内容:**
- [ ] BikeClockServiceの作成
  - [ ] BLE接続時の自動時刻同期
  - [ ] 現在時刻のUnixタイムスタンプ送信
  - [ ] `SET:time:<unix_timestamp>` コマンド送信

```kotlin
// 実装イメージ
suspend fun syncTime(device: BluetoothDevice): Boolean {
    val currentTime = System.currentTimeMillis() / 1000
    val command = "SET:time:$currentTime"
    // BLE経由で送信
}
```

#### 9. 接続切断時の位置情報記録
**実装内容:**
- [x] 接続時の位置記録（既存機能）
- [ ] 切断時の位置記録（新規追加）
- [ ] 履歴への切断位置表示

```kotlin
// BleConnectionManager.kt に追加
private fun recordDisconnectionLocation() {
    locationTracker.getCurrentLocation()?.let { location ->
        deviceHistoryRepository.addDisconnectionEntry(location)
    }
}
```

#### 10. メイン画面の簡素化
**削除するUI要素:**
- [ ] ファイル転送プログレスバー
- [ ] 録音中インジケーター
- [ ] 電圧表示
- [ ] 文字起こし結果パネル
- [ ] Google Tasks同期ボタン

**残すUI要素:**
- [x] BLE接続ステータス表示
- [x] デバイス名表示（BikeClock-0001）
- [x] 接続/切断ボタン
- [x] デバイス履歴ボタン
- [x] 設定ボタン
- [ ] 時刻同期ボタン（新規追加）

#### 11. 通知の簡素化
**削除する通知:**
- [ ] 低電圧通知
- [ ] 文字起こし完了通知
- [ ] ファイル転送通知

**残す/追加する通知:**
- [x] BLEサービス実行中通知（既存）
- [ ] 時刻同期完了通知（新規）
- [ ] 接続切断通知（新規）

---

### 🎨 優先度低：UI/UXの改善

#### 12. アプリ設定の簡素化
**削除する設定項目:**
- [ ] Google Tasks関連設定
- [ ] 文字起こし関連設定
- [ ] 録音関連設定
- [ ] 電圧関連設定
- [ ] BLEバーストサイズ設定
- [ ] Gemini API Key設定

**残す設定項目:**
- [x] テーマモード（ダーク/ライト）
- [x] OS起動時自動開始
- [ ] 時刻自動同期スイッチ（新規）

#### 13. デバイス履歴画面の修正
- [ ] グラフ表示の削除（電圧・バッテリーレベル）
- [ ] 履歴項目の簡素化
  - [ ] 接続時刻/位置
  - [ ] 切断時刻/位置
  - [ ] Google Mapsで位置表示

#### 14. BLEテストツールの統合
- [ ] `bikeclock/bikeclock_ble_test.py` の機能をアプリに統合
- [ ] 開発者用デバッグ画面の追加

---

## テストとデバッグ

### 15. テストの更新
- [ ] FastRec関連のテスト削除
- [ ] BTClock専用のテスト追加
  - [ ] 時刻同期テスト
  - [ ] BLE接続テスト

### 16. ドキュメントの更新
- [ ] README.md の更新
  - [ ] FastRec関連記述の削除
  - [ ] BTClock専用の説明に変更
- [ ] このTODO.mdの更新

---

## 依存関係のあるタスク

1. **不要機能削除** → 2. **BikeClock専用機能実装** → 3. **UI/UX改善**

---

## 進捗追跡

- **開始日**: 2026-04-23
- **ファームウェア**: 100% 完成
- **アプリ開発**: 10% ベース作成完了
- **目標**: FastRecMobからBTClockMobへの完全移行

---

## 技術的なメモ

### BLEコマンド仕様（bikeclockファームウェア）
```
時刻同期コマンド:
SET:time:<unix_timestamp>
例: SET:time:1776881773

成功レスポンス:
OK: Time synced
```

### 位置情報記録条件（FastRecMobから継承）
- 時間条件: 前回から30分以上経過
- 位置条件: 位置が類似しないこと
- 例外: 初回接続、移動検出時

---

## 次のステップ

1. まず「不要機能の削除」から開始
2. 音声認識関連ファイルを一括削除
3. ビルドして動作確認
4. 次にBikeClock専用機能を実装

---

**最終更新**: 2026-04-23
**作成者**: Claude Code + ユーザー
