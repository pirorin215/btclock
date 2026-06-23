package com.pirorin215.btclockmob.data

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * モーション特徴量抽出（Android / マイコン共通定義）。
 *
 * 【重要】マイコン(C++)側の [bikeclock_esp32_imu] と、窓長・計算式・次元順序を完全一致させること。
 * 特徴量を変える場合は両側を同時に更新し、[MotionModel.DIM] とプロトコル(送信次元数)を整合させる。
 *
 * 入力: ImuSample列（50Hz想定）。出力: DIM次元 FloatArray。
 * フィルタは1次IIR LPF（scipy等の外部依存なし）。
 */
object MotionFeatures {

    /** 特徴量次元数（順序は extract() の戻り値に対応・固定） */
    const val DIM = 21

    /**
     * 固定スケール正規化の除数（各特徴量の物理的フルスケール想定）。
     * マイコン（bikeclock.h MOTION_FEATURE_SCALE）と完全一致させること。
     * z-score はサンプル少＋高再現性で std が過小評価され破綻するため廃止。
     *
     * デバイスはバイクへ固定取り付けを前提とし、動的acc を各軸の符号付きピーク(max/min)
     * で保持する（平均は往復動作で相殺して方向が消えるため、行き/戻りピークで方向を保持）。
     * 順序は DIM に準拠:
     *   accDynMax(x,y,z), accDynMin(x,y,z), accDynRms(x,y,z),
     *   gyroMean(x,y,z), gyroRms(x,y,z), accDynMagPeak, gyroMagPeak, tilt,
     *   gravAx, gravAy, gravAz
     */
    val FEATURE_SCALE = floatArrayOf(
        1f, 1f, 1f,           // accDyn max x/y/z（符号付きピーク・行き方向）
        1f, 1f, 1f,           // accDyn min x/y/z（符号付きピーク・戻り方向）
        1f, 1f, 1f,           // accDyn rms x/y/z（各軸の動き強度・振動）
        20f, 20f, 20f,        // gyro mean x/y/z（回転方向・符号付き）
        50f, 50f, 50f,        // gyro rms  x/y/z（各軸の回転揺らぎ）
        3f,                   // accDyn ノルムpeak（瞬間最大激しさ）
        100f,                 // gyro ノルムpeak（瞬間最大回転）
        45f,                  // tilt（鉛直からの傾き[deg]）
        1f, 1f, 1f            // grav x/y/z（姿勢）
    )

    private const val FS = 50f                     // サンプリングレート[Hz]
    /** 特徴量抽出の窓長（サンプル数）。マイコン MOTION_FEAT_WINDOW_SAMPLES と一致させること。
     *  50Hz×2秒=100。学習・推論で同一窓長にしないと特徴量空間が不一致になるので注意。 */
    const val WINDOW_SAMPLES = 100
    private const val GRAV_FC = 0.5f               // 重力抽出LPFのカットオフ[Hz]
    private val GRAV_ALPHA: Float = run {
        val dt = 1f / FS
        val rc = 1f / (2f * Math.PI.toFloat() * GRAV_FC)
        dt / (rc + dt)
    }

    /** 1次IIR ローパスフィルタ（重力成分の抽出に使用） */
    private fun lpf(input: FloatArray, alpha: Float = GRAV_ALPHA): FloatArray {
        if (input.isEmpty()) return FloatArray(0)
        val out = FloatArray(input.size)
        out[0] = input[0]
        for (i in 1 until input.size) out[i] = out[i - 1] + alpha * (input[i] - out[i - 1])
        return out
    }

    /**
     * 21次元特徴量を抽出する。サンプル不足時は null。
     *
     * デバイスはバイクへ固定取り付けを前提とし、動的acc の各軸を符号付きピーク(max/min)
     * で保持する。平均は往復動作（スタンド上げ等）で相殺して方向が消えるため、行き/戻り
     * のピークで「斜め上後ろ」等の固有動作方向を活かす。
     * 次元順序:
     *  0-2 : acc 動的成分 各軸max(符号付き), 3-5: acc 動的成分 各軸min(符号付き),
     *  6-8 : acc 動的成分 各軸RMS(各軸の動き強度),
     *  9-11: gyro 各軸平均(符号付き=回転方向), 12-14: gyro 各軸RMS(各軸の回転揺らぎ),
     *  15  : acc 動的成分 ノルムpeak(瞬間最大激しさ), 16: gyro ノルムpeak(瞬間最大回転),
     *  17  : tilt(鉛直からの傾き[deg]・平均),
     *  18-20: grav ax/ay/az 平均(姿勢方向)
     */
    fun extract(samples: List<ImuSample>): FloatArray? {
        if (samples.size < 10) return null
        val n = samples.size
        val ax = FloatArray(n) { samples[it].ax }
        val ay = FloatArray(n) { samples[it].ay }
        val az = FloatArray(n) { samples[it].az }
        val gx = FloatArray(n) { samples[it].gx }
        val gy = FloatArray(n) { samples[it].gy }
        val gz = FloatArray(n) { samples[it].gz }

        // 重力(LPF) と 動的成分(HPF = original - LPF)
        val gax = lpf(ax); val gay = lpf(ay); val gaz = lpf(az)
        val dax = FloatArray(n) { ax[it] - gax[it] }
        val day = FloatArray(n) { ay[it] - gay[it] }
        val daz = FloatArray(n) { az[it] - gaz[it] }

        // 動的acc 各軸 符号付きピーク(max/min=動き方向) / RMS(各軸の動き強度)
        // 平均は往復動作（スタンド上げ等）で相殺して方向が消えるためピークで保持
        var daxMax = dax[0]; var dayMax = day[0]; var dazMax = daz[0]
        var daxMin = dax[0]; var dayMin = day[0]; var dazMin = daz[0]
        var sdax2 = 0.0; var sday2 = 0.0; var sdaz2 = 0.0
        for (i in 0 until n) {
            if (dax[i] > daxMax) daxMax = dax[i]
            if (day[i] > dayMax) dayMax = day[i]
            if (daz[i] > dazMax) dazMax = daz[i]
            if (dax[i] < daxMin) daxMin = dax[i]
            if (day[i] < dayMin) dayMin = day[i]
            if (daz[i] < dazMin) dazMin = daz[i]
            sdax2 += dax[i] * dax[i]; sday2 += day[i] * day[i]; sdaz2 += daz[i] * daz[i]
        }
        val accDynRmsX = sqrt((sdax2 / n).toFloat())
        val accDynRmsY = sqrt((sday2 / n).toFloat())
        val accDynRmsZ = sqrt((sdaz2 / n).toFloat())

        // gyro 各軸 平均(符号付き=回転方向) / RMS(各軸の回転揺らぎ)
        var sgx = 0.0; var sgy = 0.0; var sgz = 0.0
        var sgx2 = 0.0; var sgy2 = 0.0; var sgz2 = 0.0
        for (i in 0 until n) {
            sgx += gx[i]; sgy += gy[i]; sgz += gz[i]
            sgx2 += gx[i] * gx[i]; sgy2 += gy[i] * gy[i]; sgz2 += gz[i] * gz[i]
        }
        val gyroMeanX = (sgx / n).toFloat()
        val gyroMeanY = (sgy / n).toFloat()
        val gyroMeanZ = (sgz / n).toFloat()
        val gyroRmsX = sqrt((sgx2 / n).toFloat())
        val gyroRmsY = sqrt((sgy2 / n).toFloat())
        val gyroRmsZ = sqrt((sgz2 / n).toFloat())

        // 動的acc / gyro ノルムpeak（瞬間最大激しさ・回転）
        var peakDyn = 0f
        var peakGyro = 0f
        for (i in 0 until n) {
            val dm = sqrt(dax[i] * dax[i] + day[i] * day[i] + daz[i] * daz[i])
            if (dm > peakDyn) peakDyn = dm
            val gm = sqrt(gx[i] * gx[i] + gy[i] * gy[i] + gz[i] * gz[i])
            if (gm > peakGyro) peakGyro = gm
        }

        // 重力ベクトル平均 → 傾き
        var tgax = 0.0; var tgay = 0.0; var tgaz = 0.0
        for (i in 0 until n) { tgax += gax[i]; tgay += gay[i]; tgaz += gaz[i] }
        val mgax = (tgax / n).toFloat()
        val mgay = (tgay / n).toFloat()
        val mgaz = (tgaz / n).toFloat()
        val gmag = sqrt(mgax * mgax + mgay * mgay + mgaz * mgaz).coerceAtLeast(1e-6f)
        val ratio = (abs(mgaz) / gmag).toDouble().coerceIn(0.0, 1.0)
        val tilt = (acos(ratio) * 180.0 / Math.PI).toFloat()

        return floatArrayOf(
            daxMax, dayMax, dazMax,
            daxMin, dayMin, dazMin,
            accDynRmsX, accDynRmsY, accDynRmsZ,
            gyroMeanX, gyroMeanY, gyroMeanZ,
            gyroRmsX, gyroRmsY, gyroRmsZ,
            peakDyn, peakGyro,
            tilt,
            mgax, mgay, mgaz
        )
    }
}
