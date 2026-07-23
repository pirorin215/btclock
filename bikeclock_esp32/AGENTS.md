# bikeclock_esp32 プロジェクト - エージェントへの指示

## 自動ビルドルール（必須）

**重要:** Arduinoコード（`.ino`, `.cpp`, `.h`ファイル）を変更した場合、**必ず直後にビルドを実行すること。** コードを変更しただけではマイコンに書き込まれない。

### 手順

1. コードを変更する
2. **`bikeclock.h` の `FIRMWARE_VERSION_PATCH` を1つ増やす**
3. **即座にビルドを実行**: `bash compile.sh`
4. ビルド結果をユーザーに報告する（成功・失敗問わず）

### ビルド結果の報告形式

**成功時:**
- ✅ ビルド成功
- Flash使用量 / RAM使用量を表示
- 生成されたファームウェアバイナリ（`build/bikeclock_esp32.ino.bin`）のパスを表示

**失敗時:**
- ❌ ビルド失敗
- エラーメッセージを表示
- 解決策を提示して修正

### 書き込み

ビルドが成功したら、ユーザーが `sh upload.sh` でマイコンに書き込む。エージェントは勝手に書き込まない（デバイス接続状態に依存するため）。

## プラットフォーム情報

- **ボード**: ESP32-S3 SuperMini (ESP32S3FH4R2)
- **FQBN**: `esp32:esp32:esp32s3:CDCOnBoot=cdc,PartitionScheme=min_spiffs`（`common.sh` で定義）
- **個別デバイス名オプション**: `UNIT_NAME=Living sh compile.sh` のように指定すると BLE デバイス名 `BikeClock-<UNIT_NAME>` を埋め込める（英数字・ハイフン・アンダースコアのみ）。
