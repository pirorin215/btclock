# BikeClock プロジェクト TODO

マイコン firmware（`bikeclock_esp32`）と Android アプリ（`BTClockMob`）の両方を管理するプロジェクト全体のTODO。

---

## 🔵 未完了

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

### WiFi OTA（Phase 7）✅（v2.0.61）
- **HTTPUpdateServer（Web OTA）** 採用: ブラウザで http://<IP>/update を開き .bin をアップロード（ArduinoOTA ではなく Web 経由を選択）
- WiFi設定は BLE `SET:wifi:<ssid>\n<pass>` で受信 → LittleFS `/wifi.dat` に保存（FW側のみ。Android 設定画面は別途）
- パーティションを `min_spiffs`（app0/app1 各1.9MB + LittleFS 128KB）へ変更（OTA スロット切替用。default からの切替は erase_flash 必須）
- メンテナンス「3OTA」: WiFi(STA)接続 → Web OTA 待ち受け（5分アイドルタイムアウト / FUNC長押し2秒でキャンセル）。書込成功で自動再起動
- OTA中表示: 7seg（Con=接続中/OTA=待ち受け/FAIL=接続失敗/noFi=未設定/STOP=キャンセル）+ LED（青/緑/赤）

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
