# CycleClock — 自転車搭載用 ePaper 時計ファームウェア

XIAO BLE (nRF52840) を使った自転車搭載用 ePaper 時計デバイス「CycleClock」のファームウェアです。Androidアプリ [`BTClockMob`](../BTClockMob/) と完全互換（アプリ側は複数BikeClock対応済み・変更不要でそのまま利用可）。

## システム概要

```
振動(スイッチ導通) ──→ System OFF から復帰(コールドスタート)
                          │
                          ├─ ePaper スプラッシュ表示
                          ├─ BLE アドバタイズ開始 ("BikeClock-Cycle")
                          │
スマホ(BTClockMob常駐) ──→ 自動接続・ペアリング(Just Works初回のみ)
                          ├─ 時刻同期 (SET:time: / 毎分自動補正)
                          └─ ePaper 時計表示 (分変化でフル更新・乗車時間も表示)
                          │
振動(スイッチ導通)が3分止まる ──→ System OFF (BLE接続中でも切断して寝る)
```

バイク版 `bikeclock/` と同じ寿命モデル（「乗っている間だけ通電」＝前機が8年ノーメンテだった理由）を、キーオン電源の代わりに **18650直結 + 振動ウェイク** で再現します。

## ハードウェア構成

| コンポーネント | 説明 |
|---|---|
| マイコン | Seeed XIAO BLE (nRF52840) |
| 表示 | WeAct 2.13" ePaper (SSD1680, GxEPD2_213_B74) |
| 電源 | 18650 → XIAO 裏面 BAT+/BAT- パッド直結（オンボード充電器・USBから充電可） |
| ウェイク | 振動センサー SW-18020P系(受動・静止時0消費)。開発中はタクトスイッチで代用 |

### 配線（GPIOマスター）

ePaper モジュールの端子印字は **BUSY / D/C / SCL / GND / RES / CS / SDA / VCC** です。モジュール印字順に対応を示します(2026-09-25 ユーザー指定の物理配線・v0.1.2以降):

| ePaper側の印字 | XIAO側のピン | 備考 |
|---|---|---|
| BUSY | D9 | 更新中のBusy信号 |
| D/C | D8 | Data/Command選択 |
| SCL | D7 | SPI クロック(SPI.setPinsで割当) |
| GND | GND | グラウンド |
| RES | D10 | リセット |
| CS | D4 | SPI チップセレクト |
| SDA | D5 | SPI データ(MOSI・SPI.setPinsで割当) |
| VCC | 3V3 | 3.3V給電 |

> SPIのSCK/MOSIはnRF52840のPSEL割当で任意GPIOに配置可能。MISOはePaperが未使用のため
> 空きピンD0にダミー割当(配線不要)。ピンを変える際は `cycleclock.h` のdefineと
> `SPI.setPins()` を更新すること。

| XIAOその他 | 接続先 | 備考 |
|---|---|---|
| D0 | 振動センサー/タクトスイッチ | 他端GND・内部プルアップ・導通(LOW)でSystem OFF復帰 |
| D2, D3, D6 | 空き | (D1はSPI MISOダミーとして内部的に使用・配線不要) |
| 裏面 BAT+/BAT- | 18650 +/− | 極性注意。充電はUSB-Cから自動 |
| オンボードRGB LED | 状態表示 | PWM調光で暗色化・赤=未同期/青=接続中/緑=同期済み |

## ソフトウェア構成

| ファイル | 役割 | 移植元 |
|---|---|---|
| `cycleclock.ino` | メイン・時刻処理・スリープポリシー | bikeclock.ino |
| `cycleclock_ble.ino` | BLE(bonding必須・Just Works)・時刻同期 | bikeclock_ble.ino |
| `cycleclock_epaper.ino` | ePaper描画(時計/未同期/スプラッシュ) | bikeclock_esp32_epaper.ino |
| `cycleclock_power.ino` | System OFF出入り・LED | 新規(nRF52正規API) |

### BLE仕様(bikeclock/bikeclock_esp32 と共通・アプリ互換)

- Service UUID: `4fafc201-1fb5-459e-8fcc-c5c9c331914c`
- Command UUID: `beb5483e-36e1-4688-b7f5-ea07361b26a0` (Read/Write/Notify・暗号化必須=bonding)
- プロトコル: `SET:time:<unix_ts>` / `GET:version` / `GET:battery`(v0.2.0以降・`OK:battery:<mV>`応答)
- デバイス名: `BikeClock-Cycle`（アプリは `BikeClock-` 接頭辞で解決）

### バッテリー電圧監視 (v0.2.0・cycleclock_battery.ino)

- XIAO BLE 内蔵 VBAT 分圧(PIN_VBAT=P0.31 / VBAT_ENABLE=P0.14 active-LOW)を使用。外付け部品不要
- 測定時のみ分圧を接続し測定後に切断(~2μAリーク回避・System OFF予算保護)
- 60秒ごとのアイドル時キャッシュ測定(負荷直後は低めに出るため・fastrec2と同じ考え方)
- `GET:battery` はキャッシュ値のみ返す(BLEコールバックからanalogReadするとnrfxアサートで落ちるfastrec2の教訓)
- 分圧比 2.96(1510k/510k設計値・fastrec2校正値)。実機テスタと1Vでもズレがあれば `BATT_DIV_MULT` を調整
- アプリ側: 接続直後+5分ごとに取得し時系列保存(DataStore・最大10000件)。ヘッダーに最新電圧表示(3.5V未満は赤字)

### スリープポリシー(v0.3.0〜: 振動ベース)

- **「ウェイクスイッチ導通(振動パルス)が3分無ければ System OFF」— BLE接続中でも**。
  接続中の場合は先に切断してから寝る(アプリは切断として履歴記録)
- 乗車中は振動が継続するため起き続け、駐輪後は**スマホが近くにいても**確実に眠る
  (スマホの位置に依存しない=振動の有無がスイッチ)。開発中のタクトスイッチでは
  「押下」が振動パルスに相当し、押すたびに3分タイマーが延長される
- ウェイクスイッチ(D0)長押し2秒で手動 System OFF（待機電流実測用）
- System OFF 復帰はリセット相当=コールドスタート（時刻は失われ、BLEで再同期）
- 定数: `RIDE_INACTIVITY_TIMEOUT_MS`(cycleclock.h)

### LED(v0.3.0〜: 短パルス方式)

状態表示はオンボードRGB LED(赤=未同期/青=接続中/緑=同期済み)。
- **常時点灯系**(BOOT/同期済み/接続同期済み): 超低デューティPWM(約1.2%)の常時薄点灯
- **点滅系**(未同期/接続未同期/エラー): ほぼ消灯で、2秒に1回だけ50msの短パルスを
  薄点灯(エラーは500ms間隔)。自作キーボード界隈の「ほぼ消えているが生きている」LED表現
- 消灯時は digital LOW/HIGH に戻してPWMを停止(スリープ電流を守る)
- 調整: `LED_DIM_PWM_VALUE`(小さく=明るい)・`LED_PULSE_MS`/`LED_PULSE_INTERVAL_MS`(cycleclock.h)

## 必要ライブラリ

| ライブラリ | バージョン | 場所 |
|---|---|---|
| GxEPD2 | 1.6.9 | ~/dev/Arduino/libraries/GxEPD2 |
| U8g2_for_Adafruit_GFX | 1.8.0 | ~/dev/Arduino/libraries/U8g2_for_Adafruit_GFX |
| Bluefruit52Lib | (core同梱) | Seeeduino nRF52 1.1.13 |

ボード: Seeeduino nRF52 Boards 1.1.13 / FQBN `Seeeduino:nrf52:xiaonRF52840`

## ビルド・書き込み

```sh
bash compile.sh          # ビルド (build/cycleclock-v0.1.0.zip 生成)
cp setting.sh.example setting.sh  # 初回のみ・CYCLECLOCK_PORTを設定
sh upload.sh             # 書き込み
sh consolelog.sh         # シリアルログ監視
```

## 初回セットアップ(ユーザー・一度だけ)

1. ファームウェア書込後、Android の Bluetooth 設定で `BikeClock-Cycle` をペアリング（Just Works・画面操作のみ）
2. BTClockMob アプリの設定で接続先デバイスを**未選択のまま**にする（バイク側 ESP32 との自動切り替えが有効になる）

以降はアプリに触れず、バイクのイグニッションON / 自転車の振動のどちらでも自動接続・時刻同期される。

## 実装予定(v0.2以降・TODO.md参照)

- SW-18020P 本組込 + 誤起床ガード（起床後2秒のエッジ計数）
- 低電圧通知（fastrec2 方式: P0.31 内蔵分圧 + P0.14 分圧スイッチ → ePaperへ「要充電」表示）
