# btclock プロジェクト - エージェントへの指示

> 親ルール: `~/dev/Arduino/AGENTS.md`（Arduino 標準ルール）の適用下。
> シリアルポート・書込の前に §8「シリアルポート対応表」を必読。

## プロジェクト構成

このプロジェクトはファームウェア（bikeclock / bikeclock_esp32 / cycleclock）で構成されています。各ファームウェアのビルド・書き込みルールは各フォルダの AGENTS.md を参照してください。

- **bikeclock/**: XIAO BLE (nRF52840) 向けファームウェア → [bikeclock/AGENTS.md](bikeclock/AGENTS.md)
- **bikeclock_esp32/**: ESP32-S3 向けファームウェア → [bikeclock_esp32/AGENTS.md](bikeclock_esp32/AGENTS.md)
- **cycleclock/**: 自転車用 ePaper 時計（XIAO BLE + 18650 + 振動ウェイク・System OFF）→ [cycleclock/AGENTS.md](cycleclock/AGENTS.md)
