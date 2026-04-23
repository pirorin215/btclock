# BTClockMob プロジェクト - Claude Codeへの指示

## 自動ビルドルール

**重要:** このプロジェクトのコードを変更した場合、**必ずビルドを実行してください**。

### 手順

1. コードを変更する
2. **即座にビルドを実行**: `cd /Users/yoshi/dev/Arduino/btclock/BTClockMob && ./gradlew assembleDebug`
3. ビルド結果をユーザーに報告

### 例

**ユーザー:** 「時刻同期のタイムアウト時間を変更して」

**Claudeの応答:**
```
✅ 変更完了しました！

ビルドを実行します...
[ビルド結果を表示]
```

## ビルドコマンド

```bash
cd /Users/yoshi/dev/Arduino/btclock/BTClockMob && ./gradlew assembleDebug
```

## プラットフォーム情報

- **種類**: android
- **プロジェクト名**: BTClockMob
- **設定日**: 2026-04-23
