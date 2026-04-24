# bikeclock プロジェクト - Claude Codeへの指示

## 自動ビルドルール

**重要:** このプロジェクトのコードを変更した場合、**必ずビルドを実行してください**。

### 手順

1. コードを変更する
2. **即座にビルドを実行**: `cd /Users/yoshi/dev/Arduino/btclock/bikeclock && sh compile.sh`
3. ビルド結果をユーザーに報告

### 例

**ユーザー:** 「時刻同期のレスポンス処理を修正して」

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
arduino-cli compile --fqbn Seeeduino:nrf52:xiaonRF52840 bikeclock.ino
```

## プラットフォーム情報

- **種類**: arduino
- **プロジェクト名**: bikeclock
- **設定日**: 2026-04-23
- **ファームウェアバージョン**: 1.0.2
- **最終更新**: 2026-04-24

## 開発上の注意点

### キーコード管理

- キーコードはHID Usage ID (Keyboard Page & Consumer Page)で管理
- キーコードとキー名のマッピングはアプリ側（BTClockMob）で管理
- マイコン側はキーコード値のみをログ出力し、キー名は表示しない
- Consumer Pageキーの判定: `keyCode >= 0xE0 || keyCode == 0xCD || keyCode == 0xB5 || keyCode == 0xB6 || (keyCode >= 0x0220 && keyCode <= 0x0230)`

### BLE構成

- HIDサービス (0x1812): Android OSが接続管理
- カスタムサービス: BTClockMobアプリが接続管理
- 2つの接続は完全に独立して動作
