# BikeClock プロジェクト TODO

マイコン firmware（`bikeclock_esp32`）と Android アプリ（`BTClockMob`）の両方を管理するプロジェクト全体のTODO。
**Phase 0〜9 は ESP32-S3 への移植と ePaper 機能（マイコン完結）**。**Phase 10 以降は両プロジェクトにまたがる機能**（マイコン＋Android 両方の修正が必要）。コアプロトコル（UUID・コマンド・応答）は XIAO 版と互換を維持する。

---

## 🔵 次期機能（計画中）

### Phase 14 — BMI160 両脚スタンド（駐車）検知 [リスク:中]

**目的（残像問題の解決）**: BikeClock はイグニッション連動の USB 給電で、キーを抜くと電源 OFF する。ePaper は電子ペーパーの特性上**最後に描いた画面が残像として残る**ため、標準の時刻表示のまま電源が切れると「現在時刻でない噓の時刻」が残る。クロスカブを駐輪する際の**両脚スタンド（センタースタンド）を立てる「バイク後方斜め上への押し上げ動作」**を 6 軸 IMU（GY-BMI160）で検知し、**ePaper を「詳細表示（前回の乗車記録）」に切替えて保持**することで、電源 OFF 後の残像を正確な乗車記録にする。マイコン完結（アプリ変更なし）。

**決定事項 **

| 項目 | 決定内容 |
|------|---------|
| センサー | GY-BMI160（BMI160: 3軸加速度＋3軸ジャイロ）。I2C 接続。アドレス `0x68`（SDO→GND） |
| アクション | ePaper/LED 表示のみ（**マイコン完結**）。アプリ変更なし |
| 検出対象 | 駐車（スタンド立てる）のみ。乗り出しは検知しない |
| 検出後挙動 | **詳細表示**に切替 ＋ **通常の5秒オートリターンを無効化**（詳細表示を維持）。表示内容は Phase 9 詳細表示と同じだが**内部状態は別管理**（`EP_VIEW_PARKED`） |
| 誤検出解除 | 検知後に**ロードノイズ（走行振動）**を検知したら「走行中の誤検出」と判定し**時刻モードに戻る** |
| 最終形 | 駐車確定 → キー抜いて電源 OFF → ePaper に「前回の乗車記録」が残像として残る |
| 進め方 | **段階的**（生値確認→実機採取→アルゴリズム）。閾値は実機チューニングで決定 |

**ピン割り当て変更（Phase 14）**

Phase 14 では SW1/SW2 を背面端子へ移動し、空いた **GPIO 4/5 を BMI160 の I2C に再割当て** する。ピン定義は `bikeclock.h` の1箇所で切替（SW1/SW2/IMU の3定義）。**SW1=41 / SW2=47 とも strapping pin を回避**（ESP32-S3 の strapping は 0/3/45/46）。

| 用途 | 信号 | GPIO | 備考 |
|------|------|------|------|
| SW1（移動） | Right Arrow | 4 → **41** | 背面端子へ移動。GPIO 4 を BMI160 SDA に明け渡す |
| SW2（移動） | Down Arrow | 5 → **47** | 背面端子へ移動。GPIO 5 を BMI160 SCL に明け渡す |
| BMI160 | SDA | **4** | SW1 から再利用。I2C は任意 GPIO にリマップ可能 |
| BMI160 | SCL | **5** | SW2 から再利用 |
| BMI160 | VCC | 3V3 | |
| BMI160 | GND | GND | 共通化 |
| BMI160 | SDO/SA0 | GND | I2C アドレス 0x68 固定用 |

> ※ I2C は新設（ePaper=SPI3_HOST / TM1637=独自プロトコルで既存 I2C なし）。ESP32-S3 は I2C を任意 GPIO にリマップ可能。**SW2=47 は背面端子で露出しているか配線時に確認。未露出なら 42/40 等の別の非strappingピンへ（ピン定義1箇所で切替）。**
>
> ⚠ **非推奨ピン（strapping pin）について**: ESP32-S3 の strapping pin **GPIO 0/3/45/46** はリセット時にレベルがブート設定へ反映されるため、スイッチ等の入力に使うと起動時レベル次第でブート不全を起こす。本プロジェクトでは SW/I2C にこれらを回避し汎用 GPIO（4/5/41/47）を使用。ピンごとの理由は [README](bikeclock_esp32/README.md) の「非推奨ピン（strapping pin）」参照。

**ライブラリ**: `hanyazou/BMI160-Arduino`（`BMI160Gen.h`、ESP32 実績あり）を第一候補。ビルド/動作に問題あれば `Wire.h` レジスタ直接制御へフォールバック。

#### Phase 14-A — センサー導入＋生値ダンプ [リスク:低] ✅ 完了（v2.0.25）
BMI160 を I2C で読めるようにし、生値をシリアルログで確認できるようにする。検出ロジックは未実装。
- [x] `bikeclock.h`: ピン定義変更 — `SWITCH_SW1_GPIO 4→41` / `SWITCH_SW2_GPIO 5→47`（背面端子へ移動）、I2C ピン定義追加（`IMU_SDA_GPIO=4`, `IMU_SCL_GPIO=5`）。併せて `EpaperView` に `EP_VIEW_PARKED` 追加、IMU 用 extern/プロトタイプ、`IMU_DEBUG_DUMP`、`FIRMWARE_VERSION_PATCH` +1（24→25）
- [x] `bikeclock_esp32_imu.ino`（新規）: `setupIMU()`（Wire.begin＋BMI160 初期化・CHIPID 確認、接続失敗時フォールバック）/ `updateIMU()`（50Hz サンプリング＋生値読出し・g/deg/s 換算）/ `dumpIMU()`（10Hz シリアル生値、`IMU_DEBUG_DUMP` で切替）
- [x] `bikeclock_esp32.ino`: `setup()` に `setupIMU()`、`loop()` に `updateIMU()` 追加
- [x] `README.md`: BMI160 配線図・GPIO一覧・ライブラリ表・実装状況に追記
- [x] `sh compile.sh` 成功 ✅（v2.0.25, 94%）
- **設計判断**: アクセス方式は **Wire.h レジスタ直接制御** を採用（TODO 第一候補の BMI160Gen ではなく・外部ライブラリ不要・ビルド確実・Phase 14-B/C チューニングで生値を直接観察可）。サンプリング 50Hz（loop=100Hz から間引き）、生値ダンプ 10Hz、センサ未接続時 `g_imuEnabled=false` でフォールバック。最新サンプルはグローバル変数で保持（Phase 14-B のリングバッファ拡張を想定）
- **再利用**: `logPrint()`（`bikeclock_esp32.ino`）、`Wire.h`（ESP32 core 付属）
- **検証**: ビルド成功 ✅（v2.0.25, 94%）/ 実機: BMI160 認識(I2C 0x68)・静止時 az≈1g・gx,gy,gz≈0・既存機能回帰 は配線後に確認

#### Phase 14-B — スマホ経由データ採取（マイコン＋Android）[リスク:中]
PC をバイクに持って行けないため、**BTClockMob アプリ経由**で IMU データを採取する。運用: 動作後、アプリの「取得」操作でマイコンのリングバッファ（直近10秒）を受信、ラベルを付与して CSV で PC へシェア。アルゴリズム開発（14-C）の学習データ収集が目的。

**決定事項**:
| 項目 | 内容 |
|------|------|
| データ期間 | 直近10秒（リングバッファ）|
| サンプリング | 50Hz（14-A 準拠）|
| データ形式 | int16 生LSB（6軸）→ アプリ側で g/deg/s 換算。500サンプル×6×2B = 約6KB |
| ラベル | 固定リスト（駐車/解除/走行/カーブ/停車/アイドリング 等）＋自由メモ |
| 保存・持ち出し | CSV を都度シェア（Android `ACTION_SEND` で PC へ）|

**BLE プロトコル**: コマンド `IMU_DUMP`（アプリ→マイコン）でリングバッファ要求 → マイコンはチャンク分割（MTU 247B）で notify 連続送信（シーケンス番号・総チャンク数・完了マーカー付き）→ アプリで再構築

##### Phase 14-B1 — マイコン側: リングバッファ＋IMU_DUMP 転送 [リスク:中] ✅ 完了（v2.0.31）
- [x] `bikeclock_esp32_imu.ino`: リングバッファ追加（`ImuSample` int16×6軸×500・循環）。`updateIMU` で push。`updateIMU` を loop のメンテナンスブロック外へ移動（常時サンプリング）。`handleImuDump()`（状態初期化のみ・即リターン）/ `updateImuDump()`（30ms間隔・リングから古い順にチャンク構築・送信）
- [x] `bikeclock_esp32_ble.ino`: `IMU_DUMP` コマンドハンドラ追加。`sendBinary()`（バイナリ notify）でチャンク分割送信。チャンク = `[0xAA][0x55][seq][total][status] + int16×6×N`（status 0xFF=最終）
- [x] `bikeclock.h`: リングバッファ定義・プロトタイプ、`FIRMWARE_VERSION_PATCH` 30→31
- [x] `sh compile.sh` 成功 ✅（v2.0.31, フラッシュ95%, RAM 13% / リングバッファ6000B含む）
- **設計判断**: int16 で RAM 節約（float なら12KB→6KB）。常時サンプリングは検出（14-C）でも必須。**バイナリ直接方式**（0xAA55マジック）を採用 — Android側の0x00切り詰め問題をマジック判定で回避し効率最大化（28チャンク/約840ms）。送信はBLEコールバック内でブロック不可のため loop 内状態機械で時間分割（30ms間隔＋リトライ最大5回＋アプリ側seq欠損検知の3重防御）
- **検証**: ビルド成功 ✅（v2.0.31）/ 実機: nRF Connect またはアプリから `IMU_DUMP` 送信で全500サンプル受信・欠損なきこと（Phase 14-B3 採取時に確認）

##### Phase 14-B2 — Android 側: 採取画面＋ラベル＋CSV シェア [リスク:中] ✅ 完了（v1.1）
- [x] `ImuDataCaptureScreen`（新規）: 「データ取得」ボタン→`IMU_DUMP` 送信→チャンク受信・再構築・`LinearProgressIndicator` 進捗表示。`NotificationDebugScreen` をテンプレート
- [x] ラベル選択 UI（`ExposedDropdownMenuBox` 固定リスト6種: 駐車/解除/走行/カーブ/停車/アイドリング）＋自由メモ欄
- [x] CSV 生成（列: `timestamp_ms,ax,ay,az,gx,gy,gz` ＋ ヘッダに label/memo/firmware/sample_rate）＋ `ACTION_SEND` シェア（FileProvider、`cache-path imu/`）
- [x] メニューから画面遷移（`MainScreen` に「IMU採取」追加・`when`分岐・`BackHandler`）
- [x] `ImuDataCaptureViewModel`（新規）: `BleRepository` 直接注入、`BleEvent.ImuChunk` を `events.collect` で処理・seq順再構築・欠損検出・換算（`/16384`g, `/131.072`dps＝マイコンと同一値）・`generateCsv`
- **設計判断**: `BleRepository` をDI直接注入（Orchestrator経由よりシンプル・events Flow 直接 collect）。`onCharacteristicChanged` 冒頭でマジック `0xAA55` 判定→既存の0x00切り詰め処理をスキップ（文字列応答 OK:/ERROR:/NOTIFY: は衝突しない）。CSV はキャッシュディレクトリに書出し `ACTION_SEND` で PC へ。seq欠損時は届いた分で完了（学習データは再取得可能）。DI は Koin `ViewModelModule` へ登録
- **検証**: ビルド成功 ✅（v1.1, versionCode 2）/ 実機: バイク各動作で採取 → CSV を PC で開いて値が正しく入っている（静止時 az≈1g, gx/gy/gz≈0）（Phase 14-B3）

##### Phase 14-B3 — 実機採取（ユーザー作業）[リスク:最低]
- [ ] バイクへセンサー・デバイス取り付け。**センサーモジュールの向きを記録**（軸対応を確定）
- [ ] 各動作のデータを採取: 両脚スタンド立てる / 解除 / 走行（ロードノイズ）/ カーブ / 停車 / アイドリング（各10〜20サンプル目安）
- [ ] CSV を PC に集め、特徴量と閾値の目安を決定 → Phase 14-C のアルゴリズム確定

#### Phase 14-C — 駐車検出（機械学習ベース）✅ 完了（v2.0.39 / アプリ）
**方針転換**: 当初の閾値ベース状態機械 → **機械学習（特徴量＋距離分類）** へ変更。Phase 15 の汎用パターン認識パイプラインで駐車を検出する。
- [x] 駐車検知で ePaper を詳細表示へ切替（`g_parkedDisplayActive`）。走行検知で時計に復帰
- [x] `updateEpaperDisplay()` で駐車中は `EP_VIEW_DETAIL` を強制（`g_displayMode` に触らずオーバーライド＝5秒オートリターンに影響しない。時間経過でも戻らない）
- [x] 残像対策: キー抜き電源 OFF 後も「詳細表示（開始時刻・経過時間）」が残り、偽時刻を見せない
- [x] 開発用固定時刻 `TEST_FIXED_TIME` と IMU 生値ダンプ `IMU_DEBUG_DUMP` を無効化（本番向け）
- **残課題**: 実バイクでのガタン/駐車データ採取 → 学習 → 精度チューニング（距離閾値・サンプル追加）。手動モーションでは駐車・解除・走行を正しく判定済み（dist 1.7〜2.7 < 閾値3.0）
- **検証**: 手動モーションで 駐車→詳細表示維持 / 走行→時計復帰 を確認 ✅

---

### Phase 15 — モーションパターン学習・検出（マイコン＋Android）[リスク:中] ✅ 完了（v2.0.39 / アプリ）

スマホでモーションを学習しマイコンへモデルを送信、マイコンがリアルタイムに推論してパターンを検出・表示する。Phase 14-C（駐車検出）は本パイプラインの適用例。前段の検証（特徴量ベクトルで手動モーションA〜Dが明確分離・高再現性）を踏まえ、MLライブラリ不要の **特徴量＋距離分類（重心法）** を採用。

#### Phase 15-1 — Android 側: 学習・送信 [リスク:中] ✅ 完了
- [x] 特徴量抽出 `MotionFeatures`（9次元: acc/gyro の RMS・peak, tilt, jerk, 重力方向。1次IIR）
- [x] 学習 `MotionModel`（ラベル別重心＋z-score 正規化）
- [x] 永続化 `MotionTrainingRepository`（DataStore + JSON）
- [x] 学習画面 `MotionLearningScreen`（サンプル一覧・学習・マイコン送信・学習データとモデルの全削除）
- [x] BLE 送信 `sendMotionModel`（0xAA55フレーム・MTU毎にセグメント化）
- [x] 採取画面から「学習データに追加」連携

#### Phase 15-2 — マイコン側: 受信・推論・表示 [リスク:中] ✅ 完了（v2.0.39）
- [x] `bikeclock_esp32_motion.ino`（新規）: モデル受信（0xAA55フレーム再構築）・LittleFS 保存（`/motion_model.bin`）・特徴量抽出（C++・Androidと完全一致）・最近傍推論（正規化＋距離閾値＋連続2回一致で安定化）
- [x] `onWrite` で先頭マジック 0xAA55 を判定しバイナリモデルフレームを受信
- [x] 検出結果を7セグに `MTnn`（パターン番号2桁）3秒表示
- [x] 起動時に LittleFS からモデルを復元

#### UX 改善 — 未来録り採取 [リスク:低] ✅ 完了
- [x] `IMU_RECORD_START`（未来録り）: 「データ取得開始」を押してから10秒録音（従来は直近10秒=過去）。録音中の進捗ゲージ表示
- [x] 取得完了時に学習データへ自動追加（「学習データに追加」ボタン廃止）
- [x] CSV をダウンロードへ自動保存（シェアボタン押下不要）
- [x] 接続状態判定を `Connected` のみに統一（`Paired` で「接続中」表示になる不具合を修正）
- **設計判断**: 特徴量定義を Android(Kotlin) とマイコン(C++) で完全一致させ、学習空間と推論空間を整合。学習はスマホ・推論はマイコン（エッジ）。距離閾値3.0（正規化空間）、推論1Hz
- **検証**: 手動モーション（駐車/解除/走行）を正しく判定（dist 1.7〜2.7 < 閾値3.0）✅ / モデル送信→受信→推論の全工程を確認 ✅

### Phase 15 関連 — 推論ログ取得（BLE・精度チューニング用）✅ 完了（v2.0.41 / アプリ）

PC をバイクに持っていけない環境で、毎推論（1Hz）の結果 [候補ラベル, dist, 特徴量9次元] を BLE 経由でスマホに送り、CSV 保存して PC で時系列分析するデバッグ機能。駐車検知の精度（体感6-7割）の原因切り分けが目的。実運用時の推論ログ取得（USBシリアルが取れない問題）を既存 BLE 経路で解決。

- [x] マイコン: `INFER_LOG:1/0` コマンドで送信ON/OFF制御（`g_inferLogEnabled`）。`updateMotionInference()` で毎推論 `INFER:<ms>,<candidate>,<dist>,<f0>..<f8>` を notify
- [x] アプリ: `BleEvent.InferenceLog` で受信、`InferenceLogViewModel` で蓄積（上限2000件）、`InferenceLogScreen` で時系列表示（dist≥3.0 を赤行＝「不明」）＋ CSV保存（Downloads）
- [x] メニュー「推論ログ」追加・Koin DI 登録
- **設計判断**: 確定ラベルではなく **候補ラベル（candidate）** を送信（操作直後の揺らぎを見える化）。dist だけでなく **特徴量9次元（正規化前）** も送信（どの特徴量が誤判定の主因かを分析）。既存の IMU 採取（Phase 14-B2）と同じ BLE notify + MediaStore パターンを流用
- **検証**: ビルド成功 ✅（ESP32 v2.0.41 / Android）/ 実機: 「10秒静止→スタンド操作→10秒静止」で dist 推移を記録し、操作後10秒で候補が「-」に転じるか（イベントが窓から外れる仮説）を確認予定

### Phase 15 関連 — 正規化を z-score → 固定スケールへ変更 ✅ 完了（v2.0.42 / アプリ）

推論ログで駐車時の dist が **18.9〜20.95**（閾値3.0の6-7倍）で一度も判定されない問題を分析。学習データと推論時の特徴量は同じ（ズレない）にもかかわらず dist が巨大になる原因は、**z-score 正規化で std が過小評価**（学習5サンプル＋高再現性で std=0.001〜0.010）され、推論時のわずかな差が z=4〜5 に膨張するため。gyroPeak は無罪（z=1.37）、主犯は accRms・重力軸。

**対策**: z-score を廃止し**固定スケール正規化**（`FEATURE_SCALE=[1,3,10,100,45,2,1,1,1]` を両側で共通定義）へ。std 推定に依存せず破綻しない。データ検証で推論 dist が **20→0.17（平均）** に劇改善、実機で検出精度が格段に向上。

- [x] Android: `MotionFeatures.FEATURE_SCALE` 追加、`MotionModel` から featMean/featStd 削除・train をスケール正規化へ、ペイロードから mean/std 削除
- [x] マイコン: `MOTION_FEATURE_SCALE`・閾値 3.0→**0.5**、`updateMotionInference` をスケール正規化へ、save/load/parse から mean/std 削除
- **設計判断**: 生特徴量（extract/extractFeatures の戻り値）は触らず、正規化は学習時（train）と推論時（updateMotionInference）の距離計算直前のみ。推論ログ・学習データは生のまま互換
- **後方互換性**: プロトコル・保存形式変更。学習し直し必須
- **検証**: ビルド成功 ✅（ESP32 v2.0.42 / Android）/ 実機: 駐車検知の検出精度が以前より格段に向上 ✅（閾値0.5は実機調整）

### Phase 16 — 信号待ちメディア自動制御（マイコン＋Android）[リスク:中]

**目的**: 信号待ちなど**停車中は YouTube 等のメディアを再生**、**走行開始で一時停止**を自動化する。バイクは走行中に音声が聞こえず（走行中の再生は道交法違反かつ危険）、信号解除時にスマホを操作してから発進するのも危険 — この一連の再生/停止をデバイスが肩代わりする。時計デバイスが「スマホのメディア状態を同期制御する」へ領域を拡張する機能。

**問題（トグルの罠）**: Bluetooth HID のメディアキー `MEDIA_PLAY_PAUSE` は **トグル動作のみ**で、「再生しろ／停止しろ」という**状態指定のメディアキーは存在しない**。実状態と送信状態が1回でもズレると、以後「走行で再生・停車で停止」という逆転現象が起きる。**HIDキー送信方式では原理的に解決不能**（送信側をどれだけ頑張っても受信側の現在状態が分からないため）。

**解決策（視点の転換）**: HIDキー送信を廃止し、**Androidアプリから MediaSession API で直接 `play()` / `pause()` を叩く**。送信前に現在の再生状態（`PlaybackState.STATE_PLAYING`）を確認し、**目的状態と異なる場合のみ送信** → 同じ状態なら何もしないので、何度繰り返しても絶対に反転しない。

**決定事項**:

| 項目 | 内容 |
|------|------|
| 走行/停車の検知 | **シフトインジケータ信号線**を第一候補（確実・低遅延）。フォールバックで Phase 15 のモーション推論（走行パターン判定）を流用可 |
| 制御方式 | **HIDメディアキー送信を廃止** → MediaSession API 直接制御（`play()` / `pause()` の状態指定）。トグル不使用 |
| 通信 | BLE GATT で走行/停車状態をアプリへ通知（既存プロトコル拡張・コマンド追加）|
| 状態同期 | アプリ側で `MediaSessionManager.getActiveSessions()` の `PlaybackState` を確認 → 目的状態と異なる場合のみ `transportControls.play()` / `.pause()` |
| 必要権限 | NotificationListenerService — **Phase 11 で既に実装・許可済み（再利用可）**。MediaSession 取得も同一サービス経由で可能 |
| 対象アプリ | YouTube / YouTube Music / 音楽アプリ全般（MediaSession を公開するアプリなら何でも）|
| 制約 | YouTube **無料版はバックグラウンド再生不可**（画面ON・フォアグラウンド前提＝バイクマウント想定なら問題なし）。常時再生なら YouTube Music / Premium 推奨 |

#### Phase 16-1 — マイコン側: 走行/停車状態のBLE送信 [リスク:低〜中]
- [ ] 走行/停車の判定入力（シフト信号 GPIO or Phase 15 モーション推論結果）を確定
- [ ] BLE プロトコル拡張: 状態変化時に `MEDIA:state=running` / `MEDIA:state=stopped` をアプリへ notify（ファイア＆フォーゲット）。または新規キャラクタリスティックで状態通知
- [ ] デバウンス（チャタリング／一時停止の揺らぎ対策）とヒステリシス（明確な変化時のみ送信）
- **検証**: 走行開始/停車のタイミングで状態通知が1回ずつ届くこと（nRF Connect またはアプリで確認）

#### Phase 16-2 — Android 側: MediaSession 同期制御 [リスク:中]
- [ ] `MediaSessionManager.getActiveSessions()` でアクティブなメディアセッション（YouTube 等）を取得（NotificationListenerService 経由・Phase 11 権限再利用）
- [ ] BLE 受信した `MEDIA:state=` を `desiredPlaying` に変換 → 現在の `PlaybackState` と照合 → 異なる場合のみ `play()` / `pause()` を直接呼出（トグル不使用）
- [ ] 複数セッション存在時の対象選定（優先セッション設定 or 最新アクティブ）
- [ ] 設定画面に「メディア自動制御」ON/OFF トグル追加（誤動作時の無効化・停止中だけ制御等のモード切替）
- **設計判断**: トグル（`MEDIA_PLAY_PAUSE`）は一切使わず `play()` / `pause()` 個別呼出で状態ズレを構造的に排除。HID サービス（Phase 6）は他の矢印/Enter キーで残存、メディアキーだけ本方式へ置換
- **検証**: 停車→再生 / 走行→停止 を繰り返しても反転しないこと。手動で再生/停止を変えても次回の同期で正状態に収束すること

#### Phase 16-3 — 実機検証（ユーザー作業）[リスク:最低]
- [ ] シフト信号取り出し配線（またはモーション検知）の取り付け
- [ ] 実走行で 信号待ち→再生 / 発進→停止 のシーケンス確認
- [ ] YouTube 無料版と Music 版それぞれで挙動確認（BG再生制限の影響）
- [ ] 誤検知（一時停車の揺らぎ等）のチューニング

## 🟡 保留（後回し）

> ESP32 移植の残作業。現状の運用に支障がないため後回し。OTA は保留中。

### Phase 7 — WiFi OTA [リスク:中]
メンテナンスメニューの「3OTA」を ESP32 の WiFi OTA に差し替え。
- [ ] WiFi 接続（SSID/パスワード設定、接続状態表示）
- [ ] ArduinoOTA 導入（`ArduinoOTA.begin()` / `ArduinoOTA.handle()` in loop）
- [ ] または HTTPUpdateServer で Web 経由書き込み
- [ ] `startOtaDfuMode()` / `enterDfuMode()` を ESP32 OTA 起動に書き換え（現状 `MAINTENANCE_MENU_DFU` は Phase 3 のスタブ: `keys.ino` L282/339-340）
- [ ] メンテナンス「3OTA」選択時のフロー（WiFi 接続 → OTA 待ち受け）
- [ ] OTA 実行中表示（7seg / LED）
- **検証**: OTA モードで PC/スマホから無線書き込み成功、再起動で新ファームウェア

### Phase 8 — 統合 & 調整 [リスク:低]
残りのプラットフォーム依存を一括処理し、全体最適化。
- [x] `NVIC_SystemReset()` → `ESP.restart()` 全置換 — **※移植時点で `ESP.restart()` 使用済み、該当なし（実質完了）**
- [x] `BLEDfu` / Adafruit DFU 由来のコード削除 — **※移植時に除外済み、該当なし（実質完了）**
- [ ] メンテナンスメニュー各項目の動作確認（Boot/Test/OTA/Factory Reset）
- [ ] ファクトリーリセット（LittleFS.format + 再起動）動作確認
- [ ] ログ出力の整理（USB-CDC Serial）
- [ ] ディープスリープ（43μA）活用の検討（※オプション、バイク常時給電なら不要かも）
- [ ] README.md / EXTERNAL_LIBRARIES.md を ESP32 版として作成
- **検証**: 一連のユースケース（起動→アプリ接続→時刻同期→HID操作→OTA→工場リセット）を通しで確認

---

## ✅ 完了済み（アーカイブ）

> 実装完了済み。参考記録として残す。学び（⚠）は再発防止用。

### Phase 0〜6 — ESP32-S3 移植

Seeed XIAO BLE (nRF52840) 版 `bikeclock/` を ESP32-S3 SuperMini へ移植。リスクの低いもの（プラットフォーム非依存ロジック）から積み上げ、各フェーズで独立して動作確認できる粒度に分割して実施。

#### グループ1: 時計としてアプリと繋がる（最優先）

##### Phase 0 — スケルトン & ビルド環境 [リスク:最低] ✅ 完了
- [x] `bikeclock_esp32.ino`（空の setup/loop）、`bikeclock.h`、`compile.sh` / `upload.sh` / `consolelog.sh` / `common.sh` / `setting.sh.example` 作成
- [x] FQBN 確定: `esp32:esp32:esp32s3:CDCOnBoot=cdc` / esp32 core 3.3.8 / `CLAUDE.md`（ビルドルール）作成
- **検証**: `sh compile.sh` 成功 ✅

##### Phase 1 — 表示 & 時刻ロジック [リスク:低] ✅ 完了
- [x] `bikeclock.h`: ピン定義 + TM1637 include、DisplayMode/DateCache/プロトタイプ追加
- [x] `bikeclock_esp32.ino`: タイムスタンプ更新・時刻計算を移植、`bikeclock_esp32_display.ino`（TM1637 表示・7seg）
- [x] 起動時バージョン表示、未同期時の「8888 点滅」、ファームウェア 2.0.0 → 2.0.1
- **検証**: ビルド成功 ✅ / 実機表示確認 ✅

##### Phase 2 — オンボード LED（GPIO48 RGB） [リスク:低] ✅ 完了
- [x] Adafruit NeoPixel 1.15.5 で WS2812 RGB LED 1px 制御（`bikeclock_esp32_led.ino`）
- [x] `LedState`（BOOT/NO_SYNC/SYNCED/CONNECTED_*/ERROR）の色・点滅パターン維持
- **検証**: ビルド成功 ✅ / 実機LED確認 ✅（起動後 緑固定 = SYNCED）

##### Phase 2.5 — ePaper 表示（昼間視認性） [リスク:低〜中] ✅ 完了
- [x] `bikeclock_esp32_epaper.ino`: GxEPD2_213_B74(BW) + U8g2_for_Adafruit_GFX（日本語）
- [x] 専用 SPI3_HOST バス（CS=1, DC=2, RST=3, BUSY=10, SCK=12, MOSI=11）
- [x] 表示: 時刻(大)+曜日+日付、毎分フル更新でにじみ防止 / 未同期→「時刻未同期」
- **検証**: ビルド成功 ✅ / 実機ePaper表示確認（要配線: VCC→3V3, GND→GND, CS→1, DC→2, RES→3, BUSY→10, SCL/SCK→12, SDA/MOSI→11）

> ⚠ **学び**: GxEPD2 はデフォルトでグローバル `SPI`(FSPI=SPI2) を使う。別ホストを使う場合は `epd2.selectSPI(SPIClass&, SPISettings)` で差替え、かつ `init()` 前に自前で `epdSPI.begin(カスタムピン)` を呼ぶ（`init()` 内の `begin()` は既起動なら即returnしピンを保持するため、ESP32-S3 core 3.3.8 で確認済み）。

##### Phase 5 — BLE カスタム GATT（時刻同期・キー設定） [リスク:中] ✅ 完了
- [x] NimBLE-Arduino 2.5.0 / `bikeclock_esp32_ble.ino`: NimBLEServer + Service(`4fafc201`) + Command char(`beb5483e-...26a0`, R/W/N)
- [x] `SET:time:` / `SET:keys:` / `GET:version` 処理、`sendResponse()`（notify）、接続/切断/Write/CCCD コールバック
- [x] アドバタイズ: デバイス名 + カスタムサービス + 切断時再アドバタイズ
- **検証**: ビルド成功 ✅ / **BTClockMob 接続・時刻同期 成功** ✅

> ⚠ **学び**: NimBLE のアドバタイズはデフォルトでデバイス名を含まない。アプリの name フィルタで検出されるよう `setName()` + `enableScanResponse(true)` が必須（Bluefruit とは異なる点）。

#### グループ2: 物理スイッチ・HID・OTA

##### Phase 3 — スイッチ直接接続 & 検出 [リスク:低] ✅ 完了
- [x] スイッチピン(GPIO 4,39,13,14,35,38,5, FUNC=8)を `INPUT_PULLUP` でセットアップ
- [x] デバウンス付き `processHidSwitches()` / `processFunctionKey()`（GPIO直読み）
- [x] FUNC (GPIO 8) 長押し → メンテナンスモード遷移
- **検証**: ビルド成功 ✅（HID送信は当時スタブ → Phase 6 で本実装）

##### Phase 4 — 設定永続化（LittleFS） [リスク:低] ✅ 完了
- [x] `<LittleFS.h>` で `/keys.dat` 読み書き（uint16_t × 7 のバイナリ）
- [x] `loadSettings()` / `saveSettings()` / `resetKeySettingsToDefaults()` / `resetToFactoryDefaults()`
- **検証**: ビルド成功 ✅（キー設定保存→復元、ファクトリーリセットの最終実機確認は Phase 8 で）

##### Phase 6 — BLE HID キーボード [リスク:高] ★技術的に最難関 ✅ 完了
- [x] NimBLE で HID-over-GATT サービス(0x1812)構築（`NimBLEHIDDevice` + Report Map 自前定義）
- [x] キーボードレポート（Report ID 1, 8B）/ コンシューマキー（Report ID 2, 2B: Back/再生 等）送信
- [x] アドバタイズに HID(0x1812) + appearance = HID Keyboard(0x03C1) 追加
- [x] ボンディング対応 — `setSecurityAuth(true,false,true)` + Just Works（HID Input Report が `READ_ENC` で生成されるため bonding 必須）
- [x] `sendHidKeyPress/Release` 本実装（新規 `bikeclock_esp32_hid.ino`）
- [x] HID 接続 と GATT 接続 の **2接続共存** 確認
- **検証**: ビルド成功 ✅（v2.0.21, 70%）/ **実機確認 ✅**: SW1-7 で矢印/Enter/Back/再生が入力され、同時に BTClockMob の時刻同期も維持（2接続共存確認）

> ⚠ **学び**: `NimBLEHIDDevice` は既存 `NimBLEServer` に HID/DeviceInfo/Battery サービスを追加するため、カスタムGATT と同一サーバーで共存できる（2接続問題は構造的に解決）。Report Map は NimBLE では自前必須（Bluefruit は自動生成）。コンシューマ AC Back(0x224) は Logical Max `0x02FF` / 16-bit LE 送信でカバー。

### Phase 9 — ePaper 3モード連動表示 [リスク:中] ✅ 完了（v2.0.22）
FUNCボタン短押しで 7セグLED のモード（TIME/DATE/WEEKDAY）を切り替えたとき、**ePaper の表示も連動して 3 種類に切り替わる**ようにする。
- [x] モード連動: 7seg=TIME → ePaper 標準表示 ／ 7seg=DATE → ePaper 通知表示 ／ 7seg=WEEKDAY → ePaper 詳細表示
- [x] `updateEpaperDisplay()` を `g_displayMode` に応じた 3 描画関数のディスパッチャ化（`EpaperView` / `ep_lastView`）。モード切替時の即時再描画
- [x] 詳細表示（モードC）: 16px級フォントで ① 開始時刻＋経過時間 ② 現在日時（秒まで） ③ HIDキー設定（SW1〜7）。切替時の1回だけ描画（スナップショット）
- [x] 通知表示（モードB）のスタブ: 「通知なし」固定表示（本文受信は Phase 10）
- [x] 起動時刻（JST）を時刻同期完了時に記録（`g_startupTimeStr` / `recordStartupTime()`）。`getYear()` 追加
- [x] HIDキーコード → 人間可読名（Right/Enter 等）のマッピング（`KEY_NAME_TABLE` / `keyNameFromCode()`）
- **検証**: 実機確認 ✅ FUNC短押しで ePaper が 標準→通知(なし)→詳細 に切り替わる。詳細は分が変わっても更新されない（スナップショット）
- **オートリターン**: DATE/WEEKDAY の5秒でTIME強制復帰は **維持（合意）**。モードB/C は5秒間表示後、標準に戻る

### Phase 10 — スマホ通知受信・表示（BLE・マイコン側） [リスク:中] ✅ 完了（v2.0.23）
Phase 9 の通知表示スタブを本実装し、BLE 経由でスマホからの通知を受信して ePaper に表示する（マイコン側）。
- [x] BLE 受信ハンドラへ通知コマンド追加。プロトコル: `NOTIFY:app=<アプリ名>\n<テキスト>`（UTF-8、上限200バイト、応答なし＝ファイア＆フォーゲット）
- [x] 通知受信時、現在のモードに関わらず **ePaper を通知表示に自動切替** し、一定時間（30秒）後に **元のモード画面へ自動復帰**
- [x] 通知表示本体: `epaper_test` の `drawWrappedText()`（u8g2_font_unifont_t_japanese3、自動折り返し）を移植。分割線なしでテキスト埋め
- [x] 表示中モード管理: 「ユーザ選択のベースビュー」と「通知割り込みで一時表示するビュー」の分離（`g_notificationActive` オーバーライド方式）
- **設計判断**: 通知タイムアウト後は常に時計(TIME)へ復帰（DATE/WEEKDAY はリセット）／未同期中も通知優先／レイアウトは本文のみ（アプリ名非表示）／MTU 247B 拡大
- **検証**: ビルド成功 ✅（実機検証は Phase 11 で Android から送信時、または nRF Connect で `NOTIFY:app=Test\nテスト` 送信で確認予定）

### Phase 11 — Android 通知転送（NotificationListenerService） [リスク:中] ✅ 完了
Phase 10 のプロトコルをアプリ側から送る。スマホの通知を BikeClock の ePaper に転送する。
- [x] `NotificationListenerService` サブクラスを新規作成（マニフェスト宣言＋`BIND_NOTIFICATION_LISTENER_SERVICE`）
- [x] `onNotificationPosted` でアプリ名+タイトル+テキストを取り出し `NOTIFY:` ペイロード生成 → `BleRepository.sendCommandSerial()` で送信（DI は Koin の `KoinComponent` 経由。同一プロセス運用）
- [x] 自己アプリ通知の除外・200バイト切り詰め・簡易デバウンス
- [x] AppSettings に「通知アクセス」許可誘導 UI 追加（`Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`）
- **設計判断**: 本文=タイトル＋本文（空白区切り）／転送対象=メッセージ系のみ（音楽/ナビ/通話等の常駐通知は除外）／MessagingStyle は EXTRA_MESSAGES から最新メッセージを抽出／連続書込の取りこぼし防止に Mutex シリアライズ（`sendCommandSerial`）／3秒デバウンス
- **検証**: ビルド成功 ✅（実機検証は通知アクセス許可後、LINE 等の通知で ePaper 表示を確認予定）

### Phase 12 — ePaper 通知表示のフォント段階切替（マイコン側） [リスク:低] ✅ 完了（v2.0.24）
通知本文の文字数に応じて ePaper のフォントサイズを自動切替し、短い通知ほど大きく表示する（マイコン側）。
- [x] `NOTIFY_FONT_SETTINGS` 配列で文字数→フォント/倍率の段階を定義（`bikeclock.h`）。10字以下→16px×3 / 24字以下→12px×3 / 26字以下→16px×2 / それ以上→12px×2
- [x] `ScaledGFX` ラッパークラスで小フォントをピクセル単位で拡大描画（`bikeclock_esp32_epaper.ino`）
- [x] `utf8CharCount()` でバイト数ではなく文字数をカウントして段階を判定
- [x] BLE 受信ハンドラの UTF-8 境界巻き戻しを「切り詰め発生時のみ」実行するよう最適化（`bikeclock_esp32_ble.ino`）
- **設計判断**: フォント倍率は u8g2 のピクセル拡大（ScaledGFX）で実現／段階設定は配列で一元管理し調整容易／200バイト未満の本文は巻き戻し処理を省略
- **検証**: ビルド成功 ✅（実機検証は Phase 13 のデバッグ画面から各文字数の通知を送信してフォント切替を確認予定）

### Phase 13 — 通知最大表示文字数設定・送信デバッグ画面（Android 側） [リスク:低] ✅ 完了
アプリ側で通知の最大表示文字数を制御して ePaper 視認性を最適化し、開発用の送信デバッグ画面を追加する。
- [x] 通知転送時に文字数制限で切り詰め、超過時は末尾に「＞」を付与（`BikeNotificationListener`）。2段階切り詰め: 文字数制限→「＞」付与→180B UTF-8境界切り詰め
- [x] `DEFAULT_NOTIFICATION_MAX_CHARS`（=47）定数でデフォルト値を一元管理（`Settings`）
- [x] AppSettingsScreen に最大文字数スライダーを追加（10〜100字、通知転送ON時のみ有効）
- [x] メニューから遷移する通知送信デバッグ画面（`NotificationDebugScreen`）を追加。アプリ名プリセット・ePaper プレビュー・送信コマンド/バイト数確認・BLE 送信
- **設計判断**: 47字は実機調整による最適値（最小フォントでも ePaper に収まる範囲）／文字数設定はアプリ側、フォント切替はマイコン側（Phase 12）で役割分担
- **検証**: ビルド成功 ✅（実機検証は各文字数の通知で ePaper 表示・フォント切替を確認予定）

---
