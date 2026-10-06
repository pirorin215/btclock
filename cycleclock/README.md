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
切断 ──→ 1分で再接続されなければ System OFF (接続中は寝ない・瞬断は1分以内の再接続で継続)
起床後1分以内に接続が来なければ ──→ System OFF (不在時の誤起動対策・誤起動回数+1)
```

バイク版 `bikeclock/` と同じ寿命モデル（「乗っている間だけ通電」＝前機が8年ノーメンテだった理由）を、キーオン電源の代わりに **18650直結 + 振動ウェイク** で再現します。

## ハードウェア構成

| コンポーネント | 説明 |
|---|---|
| マイコン | Seeed XIAO BLE (nRF52840) |
| 表示 | WeAct 2.13" ePaper (SSD1680, GxEPD2_213_B74) |
| 電源 | 18650 + ソーラー補助充電（TP4056モジュール経由でXIAO裏面BATパッドへ・下記配線参照） |
| ウェイク | 振動センサー SW-18020P系(受動・静止時0消費)。開発中はタクトスイッチで代用 |

### 配線（マイコン起点マスター・このセクション1箇所にすべてを記述）

すべての外付け部品は XIAO BLE に接続されるため、**XIAO の各ピンを起点**に接続先をこの表に統一する。
ピンを変更する場合は `cycleclock.h` の define と `setupEpaper()` の `SPI.setPins()` を更新すること。

| XIAO側 | 接続先 | 備考 |
|---|---|---|
| D0 | SW-18020P（振動センサー）（他端GND） | 受動接点1つ・内部プルアップ。導通パルス=乗車イベント(非TIMEモード中は時計へ復帰) |
| D1 | （配線不要） | ePaperがMISO未使用のためSPIダミーとして内部使用 |
| D2 | FUNCキー押ボタン（他端GND） | **System OFFからの復帰ピンを兼ねる**(導通で起床・起床直後のreleaseではモードは変わらない)。クリックで時計→通知(最終受信)→詳細→詳細大を循環・10秒で時計へ自動復帰。詳細=開始/経過/現在日時/電池電圧、詳細大=日付/経過/開始〜現在+ピクト(スリープ残画と同内容) |
| D3 | （未使用） | |
| D4 | ePaper **CS** | SPIチップセレクト |
| D5 | ePaper **SDA** | SPI MOSI（`SPI.setPins`で割当） |
| D6 | WS2812B **DIN**（v0.4.16〜） | 装飾LED×1。DINは10kΩでGNDへプルダウン（必須）。VDDはXIAO 3V3（下記「WS2812B 装飾LED」参照） |
| D7 | ePaper **SCL** | SPI SCK（同上） |
| D8 | ePaper **D/C** | Data/Command選択 |
| D9 | ePaper **BUSY** | HIGH=パネル更新中 |
| D10 | ePaper **RES** | リセット |
| 3V3 / GND | ePaper **VCC** / **GND** | 3.3V給電（太字はモジュール端子印字） |
| 裏面 BAT+ / BAT− | TP4056モジュール OUT+ / OUT− | 極性注意。オンボード充電器(BQ25100)がこの経路で18650を充電 |
| USB-C | Mac（書込・シリアルログ・給電） | 接続中もオンボード充電器が18650を充電 |
| オンボード RGB LED | （内蔵・配線不要） | 状態表示・PWM調光。赤=未同期/青=接続中/緑=同期済み |

電源チェーン（TP4056のXIAO以外の配線）: ソーラーパネル +/− → **IN±**、18650 +/− → **B±**（モジュールの micro USB 端子は未使用）。充電電流は Rprog 抵抗で決まる（既定のまま・小容量パネルで不安定にならないか要観察）。組立手順の詳細: `~/www/kento/xiao-ble-solar-tp4056.html`。なお電圧監視は XIAO 内蔵の VBAT 分圧を使用するため配線追加は不要（`BATT_DIV_MULT` 校正はテスタ実測と突合）。

ePaperのSCK/MOSIはnRF52840のPSEL割当で任意GPIOに配置可能（2026-09-25 ユーザー指定の物理配線・v0.1.2以降）。

## ソフトウェア構成

| ファイル | 役割 | 移植元 |
|---|---|---|
| `cycleclock.ino` | メイン・時刻処理・スリープポリシー | bikeclock.ino |
| `cycleclock_ble.ino` | BLE(bonding必須・Just Works)・時刻同期・通知受信 | bikeclock_ble.ino |
| `cycleclock_epaper.ino` | ePaper描画(時計/未同期/スプラッシュ/通知) | bikeclock_esp32_epaper.ino |
| `cycleclock_battery.ino` | バッテリー電圧監視 | fastrec2 battery.c |
| `cycleclock_power.ino` | System OFF出入り・LED | 新規(nRF52正規API) |
| `cycleclock_ledstrip.ino` | WS2812B装飾LED(常亮/演出/シリアル調整/System OFF断電) | 新規(v0.4.16〜) |

### BLE仕様(bikeclock/bikeclock_esp32 と共通・アプリ互換)

- Service UUID: `4fafc201-1fb5-459e-8fcc-c5c9c331914c`
- Command UUID: `beb5483e-36e1-4688-b7f5-ea07361b26a0` (Read/Write/Notify・暗号化必須=bonding)
- プロトコル: `SET:time:<unix_ts>` / `GET:version` / `GET:battery`(v0.2.0以降・`OK:battery:<mV>`応答) / `NOTIFY:app=<名前>\n<本文>`(v0.3.2以降・応答なし)
- デバイス名: `BikeClock-Cycle`（アプリは `BikeClock-` 接頭辞で解決）
- ATT MTU 247・コマンド特性は可変長247B(v0.3.2〜。通知の~230Bを1回のWriteで受信)

### スマホ通知表示 (v0.3.2)

bikeclock_esp32(バイク版)と同じ仕組みを移植。アプリの通知リスナーが
`NOTIFY:app=<アプリ名>\n<本文>`(UTF-8・最大200B)を送り、ePaperに通知ビューを表示する。

- 受信(handleNotify)は文字列操作のみ(SoftDeviceコールバック文脈で安全)・描画はloop側
- アプリ名がある場合は先頭行に16pxで表示(v0.4.3・従来はログ専用だった)
- 文字数に応じフォント段階切替(10字以下48px/24字以下36px/26字以下32px/長文24px)+日本語自動折返し
- **30秒表示**後に時計(未同期なら未同期画面)へ自動復帰(esp32版と同一。
  定数 `NOTIFICATION_DISPLAY_TIMEOUT_MS`)
- 通知表示中は時計の分更新を抑制。アプリ側の設定(転送ON/OFF・最大文字数)はバイク版と共通

### バッテリー電圧監視 (v0.2.0・cycleclock_battery.ino)

- XIAO BLE 内蔵 VBAT 分圧(PIN_VBAT=P0.31 / VBAT_ENABLE=P0.14 active-LOW)を使用。外付け部品不要
- 測定時のみ分圧を接続し測定後に切断(~2μAリーク回避・System OFF予算保護)
- 60秒ごとのアイドル時キャッシュ測定(負荷直後は低めに出るため・fastrec2と同じ考え方)
- `GET:battery` はキャッシュ値のみ返す(BLEコールバックからanalogReadするとnrfxアサートで落ちるfastrec2の教訓)
- 分圧比 2.96(1510k/510k設計値・fastrec2校正値)。実機テスタと1Vでもズレがあれば `BATT_DIV_MULT` を調整
- アプリ側: 接続直後+5分ごとに取得し時系列保存(DataStore・最大10000件)。ヘッダーに最新電圧表示(3.5V未満は赤字)

### スリープポリシー(v0.4.15〜: BLE接続有無ベース)

- **「BLE に接続していない状態が1分続いたら System OFF」の1本で統一**。起動直後の
  未接続と、切断後の未再接続の両方を同じルールで扱う。時計表示も時刻同期も接続
  ありきのため、接続の無い時間に起き続ける意味がない
- **接続中は寝ない**(アプリで操作/閲覧中とみなす・v0.4.9〜。信号待ちでも接続されて
  いれば継続)
- **起動時**: 起床時刻を基点に1分。それ以内にスマホが接続しなければ System OFF し、
  誤起動回数を +1(詳細画面に表示・フラッシュ永続化)
- **切断時**: 切断時刻を基点に1分。アドバタイズは自動再開され、瞬断(乗車中の一時的な
  切れ)は1分以内の再接続で継続。再接続されなければ1分で System OFF
- 振動センサーは **System OFF からの復帰(起動)専用**(v0.4.15)。起動後の D0 パルスは
  一切扱わない(v0.4.14 までの振動による延長・検知回数カウントは廃止)
- System OFF 直前は D0/D2 の導通が解けるのを待ってから寝る。**導通が続く限り
  寝ない**。打ち切り上限は無し(v0.4.9・短絡時に打ち切って寝ると即再起床の
  再起動ループになるため廃止)
- System OFF 復帰はリセット相当=コールドスタート（時刻は失われ、BLEで再同期）
- 定数: `SLEEP_IDLE_TIMEOUT_MS`(cycleclock.h)・1分

### LED(v0.3.0〜: 短パルス方式)

状態表示はオンボードRGB LED(赤=未同期/青=接続中/緑=同期済み)。
- **常時点灯系**(BOOT/同期済み/接続同期済み): 超低デューティPWM(約1.2%)の常時薄点灯
- **点滅系**(未同期/接続未同期/エラー): ほぼ消灯で、2秒に1回だけ50msの短パルスを
  薄点灯(エラーは500ms間隔)。自作キーボード界隈の「ほぼ消えているが生きている」LED表現
- 消灯時は digital LOW/HIGH に戻してPWMを停止(スリープ電流を守る)
- 調整: `LED_DIM_PWM_VALUE`(小さく=明るい)・`LED_PULSE_MS`/`LED_PULSE_INTERVAL_MS`(cycleclock.h)

### WS2812B 装飾LED (v0.4.16)

ePaper上部の透明テープを照らす装飾用 WS2812B ×1。

```
XIAO 3V3 ───────── WS2812B VDD
GND ─────────────── WS2812B GND
XIAO D6 ──┬──────── WS2812B DIN
          └─[10kΩ]── GND（プルダウン・必須）
```

- VDDはXIAO 3V3から直接（3.3V駆動）。カタログ範囲（3.5〜5.3V）をわずかに下回るが
  3.3V駆動は実績が多く、低輝度用途では問題なし（VIH=0.7×VDD<3.3VでGPIOとの
  論理レベルも整合）
- System OFF中もVDDに電気が来るため**DINの10kΩプルダウンは必須**（消灯ラッチは
  保持されるが、電源瞬断/リセット後のDIN浮き誤点灯を防ぐ）
- ファームは起動シーケンスの早い段階でD6をLOW出力へ確定する（`setupLedStrip()`
  を`setupEpaper()`より先に呼ぶ）。上電直後のDIN浮き誤点灯防止
- 動作: **BLE接続中だけ暖色常亮**（乗っている/見ている間だけ光る寿命モデルに一致）。
  起動直後に白点滅（配線確認用）、通知受信で品紅点滅、低電圧警告で赤点滅。
  System OFF直前は消灯フレームを送ってからピンをHi-Z化
- 送信は Adafruit_NeoPixel（nRF52はNRF_PWM+EasyDMA・SoftDevice割込で時序が壊れない）。
  演出はloop駆動の非ブロッキング状態機
- 調整（シリアルコマンド・v0.4.18・調整段階専用）: consolelog.sh を止めてから
  `arduino-cli monitor -p <PORT> --config baudrate=115200` で対話接続し、
  1行タイプしてEnterで送る。
  - `led R G B` — 常亮色を即変更（0-255・**BLE未接続でもプレビュー点灯**するため
    スマホなしで調整可。視認性の確認は `led 0 0 255` 等の純色・最大輝度も有効）
  - `ledsave` — 現在の常亮色をInternalFS（`/ledstrip.bin`）へ保存・再起動後も有効
  - `leddefault` — 保存を消して `STRIP_IDLE_*` の既定値へ戻す
  - `ledflash R G B` — 点滅演出のプレビュー / `ledinfo` — 現在値と保存状態を表示
  - コマンド受信のたびにスリープ判定を延長（未接続の調整作業が1分でSystem OFFに
    潰れない。**入力がないまま1分経つと通常どおり寝る**。本番の見た目=常亮は
    BLE接続中の状態なので、最終確認はスマホ接続で行う）
- コンパイル時の既定値: `STRIP_IDLE_R/G/B`（常亮色）・`STRIP_FLASH_TIMES`/`STRIP_FLASH_MS`
  （演出）・`STRIP_GPIO`（ピン変更）。**ledsaveでの調整が決まったら cycleclock.h へ
  転記して leddefault で保存を消す**（ドキュメント=マスターの一致維持）

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

## 実装済みの主な機能と今後(v0.2以降・TODO.md参照)

- スマホ通知のePaper表示(v0.3.2)・低電圧「要充電」表示と電池ピクト5段階(v0.3.3〜0.3.7)・
  FUNCキー機構=中押しモード切替(v0.3.8・時計/電池詳細/バージョン)
- 誤起床ガード（起床後2〜3秒のエッジ計数）は次の予定(SW-18020P本組込は2026-10-04済・todo.md参照)
