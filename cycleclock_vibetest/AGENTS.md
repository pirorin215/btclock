# cycleclock_vibetest - 振動ウェイク感度調整用テストファーム

> 親: `../AGENTS.md`(btclock)・兄弟: `../cycleclock/AGENTS.md`(本番ファーム)

## これは何

フィールド試験(2026-10-05)で本番ファーム(cycleclock v0.4.9)の振動ウェイク
(SW-18020P・D0導通)が「前輪を50cm上げて落とす」レベルでしか反応しなかったため、
wake源の調整・比較をする**一時的なテストスケッチ**。本番ファーム(cycleclock/)には
一切触れない。

## 2系統のwake源(自動判別)

起動時に I2C(D4/D5)へ BMI160 が居れば **BMI160 any-motion モード**、
居なければ **SW-18020P モード**で動く(シリアル起動ログで判別)。

- **SW-18020P(機械式)**: センサー外出しして設置位置・向き・固定方法を調整。
  検出経路は本番(v0.4.8以降)と同じ GPIOTE 割り込み → 「vibetest で拾えない
  素子・設置は本番でも拾えない」の切り分け基準に使える。
- **BMI160(GY-BMI160・部品台帳 seed001 ×3)**: MEMS加速度 any-motion 割り込み
  (TR70 と同系統の方式)。しきい値・duration をシリアルから即時変更して
  実測調整できる。レジスタ定義は Bosch 公式 BMI160_driver 準拠・
  bikeclock_esp32(bikeclock_esp32_imu.ino)の実機教訓(CMD待ち・PMU待ち)を流用。

## 配線(どちらか一方を D0 へ)

```
SW-18020P : D0 — GND(他端)
GY-BMI160 : VCC→3V3, GND→GND, SDA→D3, SCL→D6, SDO→GND(I2C 0x68),
            INT1→D0(オープンドレイン active low・D0内蔵プルアップでLOW検出)
```

- **I2C は本番統合と同一配線(D3=SDA/D6=SCL)**。本番 cycleclock は ePaper で
  D4(CS)/D5(MOSI)/D7-D10 を使用し、空きパッドは D3/D6 のみ。nRF52 の TWIM
  PSEL は任意 GPIO 可・この core の `Wire.setPins()` で D3/D6 に割り当てる
  (vibetest で検証→本番へそのまま転用)。variant 既定の SDA=D4/SCL=D5 は本番では
  ePaper CS/MOSI に衝突するため使わない。
- I2C アドレスは SDO→GND で 0x68(bikeclock_esp32 実績と同一・開放なら
  0x69 も probe する)。
- bikeclock_esp32 の IMU_SDA=5/SCL=4 は ESP32-S3 のピン番号体系。混同しない。

## 動作

- D0 の LOW 検出(導通 or INT1)でオンボード**緑 LED 点灯**、LOW が解けてから
  1 秒で消灯(連続振動中は点きっぱなし)。フル輝度直接駆動。
- Serial(115200): `[VIB]` 300ms 窓のパルス数+LOW幅(µs)、`[HB]` 5秒ハートビート。
- BMI160 の初期設定: 加速度低電力モード(CMD 0x12・ジャイロは SUSPEND)、
  ±2g / ODR 25Hz、INT1=オープンドレイン active low、non-latched
  (INT 幅は ODR 周期相当 → System OFF の DETECT 起床でも拾える見込み)。

### シリアルコマンド(BMI160 モード)

| コマンド | 意味 |
|---|---|
| `t<0-255>` | any-motion threshold を即時変更(例: `t20\n`) |
| `d<0-3>` | any-motion duration を即時変更 |
| `r` | レジスタ状態+加速度生値(±2g・16384 LSB/g 換算)をダンプ |
| `h` | ヘルプ |

- threshold の実効ビット幅は datasheet 上の断定を避け、実測スイープで調整する。
  「軽く机を叩いたら `r` で何 g 出るか」を先に見てしきい値の指標にする。

## ビルド・書込・ログ

- ビルド: `bash compile.sh`(FQBN `Seeeduino:nrf52:xiaonRF52840`)
- 書込: `sh upload.sh`(ポートは `setting.sh` の `CYCLECLOCK_PORT`・開発共用ポート
  `/dev/cu.usbmodem212101`。親 `~/dev/Arduino/AGENTS.md` §8 の保護ルール適用)
- ログ: `./consolelog.sh`
- 調整パラメータは .ino 冒頭の define(`LED_HOLD_MS`・`BMI_THR_DEFAULT` 等)

## ビルドノウハウ

- Serial(USB CDC)の実体は bluefruit.h の依存検出で間接リンクされていたため、
  BLE なしスケッチでは **`Adafruit_TinyUSB.h` の明示 include が必須**
  (無いとリンカで Serial 未定義)。
