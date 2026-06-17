# bikeclock_esp32 プロジェクト - Claude Codeへの指示

## ドキュメント管理ルール

- **このドキュメントには、コード変更時とビルドに関するルールのみを記載してください**
- これ以外の内容を勝手に追記しないでください
- 計画・進捗は [TODO.md](../TODO.md) を参照（フェーズ別に管理・プロジェクトルート）

## 自動ビルドルール

**重要:** このプロジェクトのコードを変更した場合、**必ずビルドを実行してください**。

### 手順

1. コードを変更する
2. **bikeclock.hのFIRMWARE_VERSION_PATCHを1つ増やす**
3. **即座にビルドを実行**: `cd /Users/yoshi/dev/Arduino/btclock/bikeclock_esp32 && sh compile.sh`
4. ビルド結果をユーザーに報告

**Claudeの応答:**
```
✅ 変更完了しました！

ビルドを実行します...
[ビルド結果を表示]
```

## ビルドコマンド

```bash
cd /Users/yoshi/dev/Arduino/btclock/bikeclock_esp32 && sh compile.sh
```

または

```bash
cd /Users/yoshi/dev/Arduino/btclock/bikeclock_esp32 && arduino-cli compile --fqbn esp32:esp32:esp32s3:CDCOnBoot=cdc bikeclock_esp32.ino
```

## プラットフォーム情報

- **種類**: arduino
- **プロジェクト名**: bikeclock_esp32
- **ボード**: ESP32-S3 SuperMini (ESP32S3FH4R2)
- **FQBN**: `esp32:esp32:esp32s3:CDCOnBoot=cdc`
- **ライブラリ**: TM1637, Adafruit MCP23X17（Phase 1/3 で導入）, NimBLE-Arduino（Phase 5/6 で導入）
