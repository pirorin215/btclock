# cycleclock_diag - System OFF 電流問題の切り分けファーム(解決済み)

> 親: `../AGENTS.md`(btclock)・本番: `../cycleclock/AGENTS.md`(v0.4.9)
> **2026-10-06 解決。最終構成と実測値はこの文書が一次記録。本番 v0.4.10 反映の元データ。**

## 成果(2026-10-06)

**System OFF 電流: 0.5mA → 11µA(新品 XIAO 裸基準値と同等・約45分の1)**

数値の変遷:
| 構成 | 電流 |
|---|---|
| 本番 v0.4.9(ePaper 常時給電) | 0.5〜0.6mA |
| 最小ファーム+hibernate+信号 Hi-Z | 630µA(効果なし) |
| ePaper VCC 物理外し | 27µA |
| 新品 XIAO 裸(BAT のみ) | 11µA(基準値) |
| **TPS22810 導入(完全接続)** | **11µA** |

**真因**: ePaper モジュール基板(信号線プルアップ+レベルシフタ)への常時給電。
ファームウェア(hibernate・信号 Hi-Z 化)では断てず、**電源スイッチが必要だった**。

## 最終ハード構成(本番 v0.4.10 の元)

```
XIAO 3V3 ────── [1]VIN  TPS22810  VOUT[6] ────── ePaper VCC
GND ─────────── [2]GND              CT[4] ── 開放
XIAO D1 ─────── [3]EN               QOD[5] ── VOUT[6]へ直結
                  │
                  └── 100kΩ ── GND
```

- TPS22810(TI ロードスイッチ・SOT-23-6・部品台帳 seed048 ×10)
- ピン1は端面の窪み側・左列上から 1,2,3 / 右列上から 6,5,4
- EN=HIGH(D1)の間だけ ePaper 給電。Hi-Z で 100kΩプルダウンが LOW に自己保持=
  System OFF 中も OFF 維持(リーク 0.5µA typ)。QOD が VOUT を 0V 放電
- D1 は旧 MISO ダミーからの転用(SPI.setPins の MISO に D1 を渡したまま
  begin 後に pinMode(OUTPUT)+HIGH で GPIO 上書き・実機 readback 検証済み)

## ファームのシーケンス(v2.2c)

起動 →(USB 列挙待ち 2.5s)→ D1=HIGH(TPS22810 ON)→ 2s 安定待ち →
ePaper 初期化・表示 → hibernate → 3s → 信号ピン 6 本 Hi-Z 化+SPI.end →
D1 を INPUT(TPS22810 OFF)→ System OFF(ウェイクソースなし・RESET で再起動)

## 教訓(今後の参照)

- SOT-23-6 の GND 足が欠けると**中間電圧(1.25V/2.4V)**が出る(二値にならない)
- 配線品質自体がµA 級の差(27µA→11µA)を生む。µA 級を追う時は結線を見直す
- AN8008 でのµA 測定: µA 系レンジ(VΩ 端子)は負担電圧で起動電流中のマイコンが
  ブラウンアウト→**テスタ並列にショートリード(バイパス)方式**で起動を通す
- 新品 XIAO BLE は SoftDevice 未焼きの場合あり→UF2(uf2conv)で
  sd+bootloader を焼いてから通常運用
- USB 挿入中は System OFF に入れない挙動(nRF52 の USB 仕様)。
  電流測定は必ず BAT 給電で

## ビルド・書込・ログ

- `bash compile.sh` / `sh upload.sh`(ポート `setting.sh`・開発共用 212101)
- `./consolelog.sh`(起動ログは列挙待ち 2.5s 内に流れる)
- 起動〜System OFF まで約 8 秒(2.5+2+表示+3)

## 本ファームの内容(本番 v0.4.9 のコピー+3点のみ変更)

1. **無振動スリープ 3分→10秒**(`RIDE_INACTIVITY_TIMEOUT_MS`) — 測定サイクル高速化
2. **診断ログ**: 起動直後と System OFF 直前に D0/D2 のデジタル+アナログ値を出力
   - `[DIAG] boot D0=1 (1023/1023)...` — 開放なら1023近辺、半導通なら中間、導通なら0近辺
3. **`DIAG_EPAPER_HIBERNATE` スイッチ**(cycleclock.h・現状=1):
   - `1` = System OFF 直前に `g_epaper.hibernate()` を送る(**修正案の効果検証**)
   - `0` = 本番同等(**0.5mA の再現**・原因確定用)
4. バージョン表記 **v0.4.90**(未同期画面で本番と区別可)・BLE名 `BikeClock-CycleDiag`

## 切り分け手順(この順で・各ステップ10秒スリープで高速に回る)

前提: **スマホ BT オフ・USB ケーブル抜き・電池+テスタ直列**。µA レンジは
System OFF 確認後に切り替える(起動スパイクで nRF がブラウンアウトするのを避ける。
AN8008 の µA レンジは VΩ 端子共用・赤プローブ差し替え要)。

### TEST-1: hibernate=1 のまま・素子接続で測定
1. 起動→10秒→スリープ画面→System OFF(テスタは mA レンジのまま)
2. System OFF に入ったら(電流が変動しなくなったら)µA レンジへ切替
3. 値を記録

| 結果 | 判定 | 次のアクション |
|---|---|---|
| **2.4µA〜数十µA に激減** | ePaper が犯人(hibernate で解決) | 本番 v0.4.10 に hibernate 追加を反映 → 完了 |
| 数百µA〜0.5mA のまま | ePaper だけではない/別原因 | TEST-2 へ |

### TEST-2: D0 の素子配線を外して(開放)TEST-1 再実施
| 結果 | 判定 | 次のアクション |
|---|---|---|
| 激減 | 素子の半導通リークが犯人(または併発) | 素子の調整やり直し(伸ばしを一手戻す) |
| 変わらず | 素子は無罪 | TEST-3 へ |

### TEST-3: ePaper リボンケーブルも外して測定(D0 も外したまま)
- ボード単体の System OFF 電流。2.4µA 実績と大きく乖離したら XIAO 本体
  (充電 IC 等)か測定系の問題 → ボード交換/測定系見直しの検討

### 補助: シリアルログ(USB 単独・テスタと同時は不可)
`./consolelog.sh` で `[DIAG] boot ...` と `[DIAG] pre-sleep ...` の
アナログ値を確認。**D0 のアナログ値が中間(目安 200〜800/1023)なら半導通の
直接証拠**。1023 近辺なら素子はリークしていない。

## ビルド・書込・ログ

- `bash compile.sh`(hibernate の on/off は `cycleclock.h` の
  `DIAG_EPAPER_HIBERNATE` を書き換えて再ビルド)
- `sh upload.sh`(ポート `setting.sh`・開発共用 `/dev/cu.usbmodem212101`)
- `./consolelog.sh`
- 書込は起床 3 分窓(v0.4.9 書込の注意と同じ。diag 版は USB 接続で起動するので
  実質いつでも書ける=USB挿入で起動→3分以内に書込が完了すれば良い…が
  10秒で寝るので**USB挿入→即ダブルタップでブートローダ→書込**が確実)
