# cycleclock プロジェクト - エージェントへの指示

## 自動ビルドルール（必須）

**重要:** Arduinoコード（`.ino`, `.cpp`, `.h`ファイル）を変更した場合、**必ず直後にビルドを実行すること。** コードを変更しただけではマイコンに書き込まれない。

### 手順

1. コードを変更する
2. **`cycleclock.h` の `FIRMWARE_VERSION_PATCH` を1つ増やす**
3. **即座にビルドを実行**: `bash compile.sh`
4. ビルド結果をユーザーに報告する（成功・失敗問わず）

### ビルド結果の報告形式

**成功時:**
- ✅ ビルド成功
- Flash使用量 / RAM使用量を表示
- 生成された ZIP アーカイブ（`build/cycleclock-v<X.Y.Z>.zip`）のパスを表示

**失敗時:**
- ❌ ビルド失敗
- エラーメッセージを表示
- 解決策を提示して修正

### 書き込み

**エージェント書込可**（2026-09-25・bikeclock/bikeclock_esp32 と同様）: ビルド成功後、エージェントが
`sh upload.sh` を実行してよい。書き込み先は開発用ポート（親 `~/dev/Arduino/AGENTS.md`
§8 の `212101`）に限る。禁止ポート（HIDEF1 / HIDPC1 / 212301）は親 §8 の保護ルールが
そのまま適用される。初回は `cp setting.sh.example setting.sh` で
`CYCLECLOCK_PORT` を設定すること。

### 既知のビルド環境要件

- Seeeduino nRF52 のツールチェーンは x86_64 バイナリのため **Rosetta 2 が必要**（2026-09-25 導入済み）
- プラットフォームのビルド後処理が `python` を要求するため `/opt/homebrew/bin/python` → `python3` の **エイリアスが必要**（2026-09-25 作成済み）
- `Adafruit_GFX&` 等を引数に取る .ino 内関数は自動プロトタイプ生成の問題があるため、**ライブラリの include は必ず `cycleclock.h` に集約する**こと（.ino に直書きしない）

## プラットフォーム情報

- **ボード**: Seeed XIAO BLE (nRF52840)
- **FQBN**: `Seeeduino:nrf52:xiaonRF52840`
- **電源**: 18650 を XIAO 裏面 BAT+/BAT- パッド直結（オンボード充電器・USB書込中も併用可）
- **表示**: WeAct 2.13" ePaper (SSD1680, GxEPD2_213_B74)
- **ライブラリ**: GxEPD2 1.6.9 / U8g2_for_Adafruit_GFX 1.8.0（~/dev/Arduino/libraries/）＋ Seeeduino nRF52 1.1.13 同梱の Bluefruit52Lib
