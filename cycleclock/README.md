# CycleClock — 自転車搭載用 ePaper 時計ファームウェア

XIAO BLE (nRF52840) を使った自転車搭載用 ePaper 時計デバイス「CycleClock」のファームウェアです。Androidアプリ [`BTClockMob`](../BTClockMob/) と完全互換（アプリ側は複数BikeClock対応済み・変更不要でそのまま利用可）。

経緯・設計思想は [HISTORY.md](HISTORY.md) を参照。本 README は現在の仕様のみを記述する。

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

待機電流(System OFF 中)は **22µA**(18650 が3年以上持つ・低電圧「要充電」表示あり)。

## ハードウェア構成

| コンポーネント | 説明 |
|---|---|
| マイコン | Seeed XIAO BLE (nRF52840) |
| 表示 | WeAct 2.13" ePaper BW (SSD1680, GxEPD2_213_B74) |
| 電源 | 18650 → XIAO 裏面 BAT+/BAT− パッド直結（充電は USB 接続時にオンボード充電器が行う・ソーラー充電モジュールは不使用） |
| ウェイク | 振動センサー SW-18020P（受動・静止時0消費・バネ長を伸ばして感度調整済みの個体） |
| 電源スイッチ | **BSS138 (N-ch MOSFET・SOT-23)** で ePaper/WS2812B の GND 側(ローサイド)を統合切断 |
| 装飾LED | WS2812B ×1（ePaper 上部を照らす） |

### 配線（マイコン起点マスター・このセクション1箇所にすべてを記述）

すべての外付け部品は XIAO BLE に接続されるため、**XIAO の各ピンを起点**に接続先をこの表に統一する。
ピンを変更する場合は `cycleclock.h` の define と `setupEpaper()` の `SPI.setPins()` を更新すること。

| XIAO側 | 接続先 | 備考 |
|---|---|---|
| D0 | SW-18020P（振動センサー）（他端GND） | 受動接点1つ・内部プルアップ。System OFF からの復帰専用(起動後の D0 パルスは扱わない) |
| D1 | **BSS138 ゲート(G)** + 100kΩ で GND へプルダウン | ePaper/WS2812B 電源スイッチ制御。**D1=HIGH で ON**(プルダウンで Hi-Z 時は OFF 自己保持)。SPI の MISO PSEL に D1 を指定したまま begin 後 GPIO 出力で上書き(受信不使用のため無害) |
| D2 | FUNCキー押ボタン（他端GND） | **System OFFからの復帰ピンを兼ねる**(導通で起床・起床直後のreleaseではモードは変わらない)。クリックで時計→通知(最終受信)→詳細→詳細大を循環・10秒で時計へ自動復帰。詳細=開始/経過/現在日時/電池電圧/誤起動回数、詳細大=日付/経過/開始〜現在+ピクト(スリープ残画と同内容) |
| D3 | （未使用） | 将来の BMI160 I2C(SDA) 予約 |
| D4 | ePaper **CS** | SPIチップセレクト |
| D5 | ePaper **SDA** | SPI MOSI（`SPI.setPins`で割当） |
| D6 | WS2812B **DIN** | 装飾LED×1。DINは10kΩでGNDへプルダウン（必須）。VDD/GNDは下記「WS2812B 装飾LED」参照 |
| D7 | ePaper **SCL** | SPI SCK（同上） |
| D8 | ePaper **D/C** | Data/Command選択 |
| D9 | ePaper **BUSY** | HIGH=パネル更新中 |
| D10 | ePaper **RES** | リセット |
| 3V3 | ePaper **VCC** / WS2812B **VDD** | 3.3V給電（常時・太字はモジュール端子印字） |
| GND | **BSS138 ソース(S)** | スイッチのGND側主幹。プルダウン抵抗(100kΩ)のGND側もここへ |
| 裏面 BAT+ / BAT− | 18650 +/− 直結 | 極性注意。USB接続時にオンボード充電器(BQ25100)が充電 |
| USB-C | Mac（書込・シリアルログ・給電） | 接続中もオンボード充電器が18650を充電 |
| オンボード RGB LED | （内蔵・配線不要） | 状態表示・PWM調光。赤=未同期/青=接続中/緑=同期済み |

### 電源スイッチ: BSS138 ローサイド

ePaper モジュール基板と WS2812B は VCC/VDD が常時 3V3 に繋がったままでも数百µA を消費するため、
**GND 側を BSS138 で一括切断**します:

```
XIAO 3V3 ──┬── ePaper VCC
           └── WS2812B VDD

ePaper GND ──┐
             ├── BSS138 D (3ピン)
WS2812B GND ─┘
             
XIAO GND ──── BSS138 S (2ピン)   ← GND主幹(100kΩプルダウンのGND側もここ)
XIAO D1  ──── BSS138 G (1ピン) ──[100kΩ]── GND
```

- **D1=HIGH → ON**(ePaper/WS2812B に電流が流れる)/ **D1=LOW・Hi-Z → OFF**(プルダウンが OFF を自己保持)。System OFF 直前にファームが D1 を Hi-Z 化するため、寝ている間は ePaper/LED が完全切断
- BSS138 は SOT-23。**ピンは 1=G・2=S・3=D**(1・2 が同じ辺に並び 3 が反対側の単独リード)。はんだ前にテスタのダイオードモードで確認(赤=D・黒=S で 0.5〜0.7V 導通=ボディダイオード)
- ePaper の GND は**この 1 本だけ**を BSS138 へ(他に GND 経路が残るとスイッチが無効化される)

単体試験の手順・AO3401A(P-ch・ハイサイド)への移行手順: `~/www/kento/bss138-epaper-lowside-switch.html`

ePaperのSCK/MOSIはnRF52840のPSEL割当で任意GPIOに配置可能。
電圧監視は XIAO 内蔵の VBAT 分圧を使用するため配線追加は不要（`BATT_DIV_MULT` 校正はテスタ実測と突合）。

## ソフトウェア構成

| ファイル | 役割 |
|---|---|
| `cycleclock.ino` | メイン・時刻処理・スリープポリシー |
| `cycleclock_ble.ino` | BLE(bonding必須・Just Works)・時刻同期・通知受信 |
| `cycleclock_epaper.ino` | ePaper描画(時計/未同期/スプラッシュ/通知)・電源スイッチ制御 |
| `cycleclock_battery.ino` | バッテリー電圧監視 |
| `cycleclock_power.ino` | System OFF出入り(ePaper/LED電源切断込み)・LED |
| `cycleclock_ledstrip.ino` | WS2812B装飾LED(常亮/演出/シリアル調整) |
| `cycleclock_stats.ino` | 誤起動回数統計(InternalFS永続化) |

### BLE仕様(bikeclock/bikeclock_esp32 と共通・アプリ互換)

- Service UUID: `4fafc201-1fb5-459e-8fcc-c5c9c331914c`
- Command UUID: `beb5483e-36e1-4688-b7f5-ea07361b26a0` (Read/Write/Notify・暗号化必須=bonding)
- プロトコル: `SET:time:<unix_ts>` / `GET:version` / `GET:battery`(`OK:battery:<mV>`応答) / `NOTIFY:app=<名前>\n<本文>`(応答なし)
- デバイス名: `BikeClock-Cycle`（アプリは `BikeClock-` 接頭辞で解決）
- ATT MTU 247・コマンド特性は可変長247B(通知の~230Bを1回のWriteで受信)

### スマホ通知表示

アプリの通知リスナーが `NOTIFY:app=<アプリ名>\n<本文>`(UTF-8・最大200B)を送り、ePaperに通知ビューを表示する。

- 受信(handleNotify)は文字列操作のみ(SoftDeviceコールバック文脈で安全)・描画はloop側
- 本文のみを全画面描画(アプリ名は本文が空の時の代替表示のみ)
- 文字数に応じフォント段階切替(10字以下48px/24字以下36px/26字以下32px/長文24px)+日本語自動折返し
- **30秒表示**後に時計(未同期なら未同期画面)へ自動復帰(定数 `NOTIFICATION_DISPLAY_TIMEOUT_MS`)
- 通知表示中は時計の分更新を抑制。アプリ側の設定(転送ON/OFF・最大文字数)はバイク版と共通

### バッテリー電圧監視

- XIAO BLE 内蔵 VBAT 分圧(PIN_VBAT=P0.31 / VBAT_ENABLE=P0.14 active-LOW)を使用。外付け部品不要
- 測定時のみ分圧を接続し測定後に切断(~2μAリーク回避・System OFF予算保護)
- 60秒ごとのアイドル時キャッシュ測定(負荷直後は低めに出るため)
- `GET:battery` はキャッシュ値のみ返す(BLEコールバックからanalogReadしない)
- 分圧比 2.96(1510k/510k設計値)。実機テスタと1Vでもズレがあれば `BATT_DIV_MULT` を調整
- アプリ側: 接続直後+5分ごとに取得し時系列保存(DataStore・最大10000件)。ヘッダーに最新電圧表示(3.5V未満は赤字)

### スリープポリシー(BLE接続有無ベース)

- **「BLE に接続していない状態が1分続いたら System OFF」の1本で統一**。起動直後の
  未接続と、切断後の未再接続の両方を同じルールで扱う
- **接続中は寝ない**(アプリで操作/閲覧中とみなす)
- **起動時**: 起床時刻を基点に1分。それ以内にスマホが接続しなければ System OFF し、
  誤起動回数を +1(詳細画面に表示・InternalFS の `/fwake.bin` に永続化。BLE接続が
  あった起動の後に寝るとき 0 にリセット)
- **切断時**: 切断時刻を基点に1分。アドバタイズは自動再開され、瞬断は1分以内の
  再接続で継続。再接続されなければ1分で System OFF
- System OFF 直前の処理: ePaper スリープ残画描画 → パネル hibernate → 信号ピン Hi-Z 化 →
  **D1 を Hi-Z(BSS138 OFF=ePaper/WS2812B 完全断電)** → D0/D2 の導通が解けるのを待って寝る。
  **導通が続く限り寝ない**(打ち切り上限なし)
- 振動センサーは **System OFF からの復帰(起動)専用**
- System OFF 復帰はリセット相当=コールドスタート（時刻は失われ、BLEで再同期）
- 定数: `SLEEP_IDLE_TIMEOUT_MS`(cycleclock.h)・1分

### 状態表示LED

オンボードRGB LED(赤=未同期/青=接続中/緑=同期済み)。
- **常時点灯系**(BOOT/同期済み/接続同期済み): 超低デューティPWM(約1.2%)の常時薄点灯
- **点滅系**(未同期/接続未同期/エラー): ほぼ消灯で、2秒に1回だけ50msの短パルスを
  薄点灯(エラーは500ms間隔)
- 消灯時は digital LOW/HIGH に戻してPWMを停止(スリープ電流を守る)
- 調整: `LED_DIM_PWM_VALUE`(小さく=明るい)・`LED_PULSE_MS`/`LED_PULSE_INTERVAL_MS`(cycleclock.h)

### WS2812B 装飾LED

ePaper上部の透明テープを照らす装飾用 WS2812B ×1。

```
XIAO 3V3 ────────────── WS2812B VDD
（GND側）─ BSS138 D へ   WS2812B GND  ← ePaper GND と同じノード(BSS138で統合切断)
XIAO D6 ──┬───────────── WS2812B DIN
          └─[10kΩ]── GND（プルダウン・必須）
```

- VDDはXIAO 3V3から直接（3.3V駆動。カタログ範囲(3.5〜5.3V)をわずかに下回るが
  低輝度用途で問題なし。VIH=0.7×VDD<3.3VでGPIOとの論理レベルも整合）
- **GND は ePaper と一緒に BSS138 の配下**。System OFF で一緒に切断されるため待機リークなし
- **DINの10kΩプルダウンは必須**: System OFF 中(GND浮き)や給電開始直後の DIN 浮き誤点灯を防ぐ
- ファームは起動シーケンスの早い段階でD6をLOW出力へ確定する（`setupLedStrip()`
  を`setupEpaper()`より先に呼ぶ）
- 動作: **BLE接続中だけ暖色常亮**（乗っている/見ている間だけ光る寿命モデルに一致）。
  起動直後に白点滅（配線確認用）、通知受信で品紅点滅、低電圧警告で赤点滅。
  System OFF直前は消灯フレームを送ってからピンをHi-Z化
- 送信は Adafruit_NeoPixel（nRF52はNRF_PWM+EasyDMA・SoftDevice割込で時序が壊れない）。
  演出はloop駆動の非ブロッキング状態機
- 調整（シリアルコマンド・調整段階専用）: consolelog.sh を止めてから
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
| Adafruit_NeoPixel | 1.15.5 | ~/dev/Arduino/libraries/Adafruit_NeoPixel |
| Bluefruit52Lib / Adafruit_LittleFS / InternalFileSytem | (core同梱) | Seeeduino nRF52 1.1.13 |

ボード: Seeeduino nRF52 Boards 1.1.13 / FQBN `Seeeduino:nrf52:xiaonRF52840`

## ビルド・書き込み

```sh
bash compile.sh          # ビルド (build/cycleclock-v<版>.zip 生成)
cp setting.sh.example setting.sh  # 初回のみ・CYCLECLOCK_PORTを設定
sh upload.sh             # 書き込み
sh consolelog.sh         # シリアルログ監視
```

## 初回セットアップ(ユーザー・一度だけ)

1. ファームウェア書込後、Android の Bluetooth 設定で `BikeClock-Cycle` をペアリング（Just Works・画面操作のみ）
2. BTClockMob アプリの設定で接続先デバイスを**未選択のまま**にする（バイク側 ESP32 との自動切り替えが有効になる）

以降はアプリに触れず、バイクのイグニッションON / 自転車の振動のどちらでも自動接続・時刻同期される。
