# bikeclock プロジェクト - Claude Codeへの指示

## ドキュメント管理ルール

- **このドキュメントには、コード変更時とビルドに関するルールのみを記載してください**
- これ以外の内容を勝手に追記しないでください

## 自動ビルドルール

**重要:** このプロジェクトのコードを変更した場合、**必ずビルドを実行してください**。

### 手順

1. コードを変更する
2. **bikeclock.hのPATCHバージョンを1つ増やす**
3. **即座にビルドを実行**: `cd /Users/yoshi/dev/Arduino/btclock/bikeclock && sh compile.sh`
4. ビルド結果をユーザーに報告

**Claudeの応答:**
```
✅ 変更完了しました！

ビルドを実行します...
[ビルド結果を表示]
```

## ビルドコマンド

```bash
cd /Users/yoshi/dev/Arduino/btclock/bikeclock && sh compile.sh
```

または

```bash
cd /Users/yoshi/dev/Arduino/btclock/bikeclock && arduino-cli compile --fqbn Seeeduino:nrf52:xiaonRF52840 bikeclock.ino
```

## プラットフォーム情報

- **種類**: arduino
- **プロジェクト名**: bikeclock
