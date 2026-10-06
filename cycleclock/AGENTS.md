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

**エージェント書込可**: ビルド成功後、エージェントが
`sh upload.sh` を実行してよい。書き込み先は開発用ポート（親 `~/dev/Arduino/AGENTS.md`
§8 の `212101`）に限る。禁止ポート（HIDEF1 / HIDPC1 / 212301）は親 §8 の保護ルールが
そのまま適用される。初回は `cp setting.sh.example setting.sh` で
`CYCLECLOCK_PORT` を設定すること。

### コーディング上の注意

- ライブラリの include は必ず `cycleclock.h` に集約する（.ino に直書きしない）。
  `Adafruit_GFX&` 等を引数に取る関数が .ino にあると自動プロトタイプ生成で壊れるため

## プラットフォーム情報

- **ボード**: Seeed XIAO BLE (nRF52840)
- **FQBN**: `Seeeduino:nrf52:xiaonRF52840`

ハード構成・配線の詳細は `README.md`、TPS22810 ePaper 電源スイッチの経緯・実測は `../cycleclock_diag/AGENTS.md` を参照。
