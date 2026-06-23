# BikeClock プロジェクト TODO

マイコン firmware（`bikeclock_esp32`）と Android アプリ（`BTClockMob`）の両方を管理するプロジェクト全体のTODO。

---

## 🔵 未完了

### Phase 16 — 信号待ちメディア自動制御（マイコン＋Android）

信号待ちなど停車中は YouTube 等のメディアを再生、走行開始で一時停止を自動化。Android の MediaSession API の `play()` / `pause()` で直接制御する（送信前に現在状態を確認し、目的状態と異なる場合のみ送信 → 反転しない）。

#### Phase 16-1 — マイコン側: 走行/停車状態のBLE送信
- [ ] 走行/停車の判定入力（シフト信号 GPIO or Phase 15 モーション推論結果）を確定
- [ ] BLE プロトコル拡張: 状態変化時に `MEDIA:state=running` / `MEDIA:state=stopped` をアプリへ notify（ファイア＆フォーゲット）。または新規キャラクタリスティックで状態通知
- [ ] デバウンス（チャタリング／一時停止の揺らぎ対策）とヒステリシス（明確な変化時のみ送信）

#### Phase 16-2 — Android 側: MediaSession 同期制御
- [ ] `MediaSessionManager.getActiveSessions()` でアクティブなメディアセッション（YouTube 等）を取得（NotificationListenerService 経由・Phase 11 権限再利用）
- [ ] BLE 受信した `MEDIA:state=` を `desiredPlaying` に変換 → 現在の `PlaybackState` と照合 → 異なる場合のみ `play()` / `pause()` を直接呼出（トグル不使用）
- [ ] 複数セッション存在時の対象選定（優先セッション設定 or 最新アクティブ）
- [ ] 設定画面に「メディア自動制御」ON/OFF トグル追加（誤動作時の無効化・停止中だけ制御等のモード切替）

#### Phase 16-3 — 実機検証（ユーザー作業）
- [ ] シフト信号取り出し配線（またはモーション検知）の取り付け
- [ ] 実走行で 信号待ち→再生 / 発進→停止 のシーケンス確認
- [ ] YouTube 無料版と Music 版それぞれで挙動確認（BG再生制限の影響）
- [ ] 誤検知（一時停車の揺らぎ等）のチューニング

### Phase 7 — WiFi OTA [リスク:中]
メンテナンスメニューの「3OTA」を ESP32 の WiFi OTA に差し替え。
- [ ] WiFi 接続（SSID/パスワード設定、接続状態表示）
- [ ] ArduinoOTA 導入（`ArduinoOTA.begin()` / `ArduinoOTA.handle()` in loop）
- [ ] または HTTPUpdateServer で Web 経由書き込み
- [ ] `startOtaDfuMode()` / `enterDfuMode()` を ESP32 OTA 起動に書き換え（現状 `MAINTENANCE_MENU_DFU` は Phase 3 のスタブ）
- [ ] メンテナンス「3OTA」選択時のフロー（WiFi 接続 → OTA 待ち受け）
- [ ] OTA 実行中表示（7seg / LED）

### Phase 8 — 統合 & 調整 [リスク:低]
- [ ] README.md / EXTERNAL_LIBRARIES.md を ESP32 版として作成

---

## ✅ 完了済み（アーカイブ）

### ESP32-S3 移植（Phase 0〜6）
Seeed XIAO BLE (nRF52840) 版 `bikeclock/` を ESP32-S3 SuperMini へ移植。
- Phase 0 — スケルトン & ビルド環境 ✅
- Phase 1 — 表示 & 時刻ロジック ✅
- Phase 2 — オンボード LED (GPIO48 RGB) ✅
- Phase 2.5 — ePaper 表示（昼間視認性）✅
- Phase 3 — スイッチ直接接続 & 検出 ✅
- Phase 4 — 設定永続化 (LittleFS) ✅
- Phase 5 — BLE カスタム GATT（時刻同期・キー設定）✅
- Phase 6 — BLE HID キーボード ✅（v2.0.21）

### ePaper・通知機能（Phase 9〜13）
- Phase 9 — ePaper 3モード連動表示 ✅（v2.0.22）
- Phase 10 — スマホ通知受信・表示（BLE・マイコン側）✅（v2.0.23）
- Phase 11 — Android 通知転送（NotificationListenerService）✅
- Phase 12 — ePaper 通知表示のフォント段階切替 ✅（v2.0.24）
- Phase 13 — 通知最大表示文字数設定・送信デバッグ画面 ✅

### モーション認識（Phase 14〜15）
- Phase 14-A — BMI160 IMU センサ導入＋生値ダンプ ✅（v2.0.25）
- Phase 14-B — スマホ経由データ採取（マイコン＋Android）✅
- Phase 14-C — 駐車検出（機械学習: 特徴量＋距離分類）✅（v2.0.39）
- Phase 15 — モーションパターン学習・検出（エッジ推論）✅（v2.0.39）
- Phase 15 関連 — 推論ログ取得（BLE・精度チューニング用）✅（v2.0.41）
- Phase 15 関連 — 正規化を z-score → 固定スケールへ変更 ✅（v2.0.42）
- Phase 15 関連 — 特徴量を方向付き18次元化（バイク固定取り付け前提・ノルム→各軸符号付き成分）＋学習採取10秒→4秒 ✅（v2.0.43）
- Phase 15 関連 — 方向特徴量を平均→符号付きピーク(max/min)へ置換（往復動作の方向相殺を解消・駐車-解除分離 0.38→0.91）✅（v2.0.44）
- Phase 15 関連 — モデル受信クラッシュ修正（Flash書き込みをBLEコールバック外へ）＋学習データ受信のePaper表示 ✅（v2.0.45）
- Phase 15 関連 — g_motionPatterns の .bss 配置重複（リングバッファ破壊）をヒープ割当で解消 ✅（v2.0.46）
- Phase 15 関連 — スタックオーバーフロー解消: updateMotionInference を独立FreeRTOSタスク(16KB)へ分離（extractFeatures のスタック肥大で loopTask 8KB超過→変数破壊・ハング） ✅（v2.0.47）
- Phase 15 関連 — extractFeatures の return true 欠落修正（全クラッシュの真因・未定義動作でスタック破壊）＋logPrintミューテックス化（マルチタスク安全） ✅（v2.0.48）
- Phase 15 関連 — ラベル構成を9ラベル3グループ(駐車A/B/C・走行開始A/B/C・停車A/B/C)に再編成・3重心でロバスト化 ✅（v2.0.49）

### その他（実質完了）
- `NVIC_SystemReset()` → `ESP.restart()` 全置換 ✅（移植時点で対応済み）
- `BLEDfu` / Adafruit DFU 由来のコード削除 ✅（移植時に除外済み）

---
