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
    const val DIM = 9

    /**
     * 固定スケール正規化の除数（各特徴量の物理的フルスケール想定）。
     * マイコン（bikeclock.h MOTION_FEATURE_SCALE）と完全一致させること。
     * z-score はサンプル少＋高再現性で std が過小評価され破綻するため廃止。
     * 順序は DIM に準拠: accRms, accPeak, gyroRms, gyroPeak, tilt, jerkPk, gravAx, gravAy, gravAz
     */
    val FEATURE_SCALE = floatArrayOf(1f, 3f, 10f, 100f, 45f, 2f, 1f, 1f, 1f)

    private const val FS = 50f                     // サンプリングレート[Hz]
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
     * 9次元特徴量を抽出する。サンプル不足時は null。
     * 次元順序:
     *  0: acc 動的成分 RMS, 1: acc 動的成分 peak,
     *  2: gyro |ω| RMS, 3: gyro |ω| peak,
     *  4: tilt(鉛直からの傾き[deg]・平均),
     *  5: acc jerk peak(動的加速度差分の大きさピーク=衝撃),
     *  6: grav ax 平均, 7: grav ay 平均, 8: grav az 平均(姿勢方向)
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

        // acc 動的マグニチュード RMS / peak
        var sumDyn = 0.0
        var peakDyn = 0f
        for (i in 0 until n) {
            val m = sqrt(dax[i] * dax[i] + day[i] * day[i] + daz[i] * daz[i])
            sumDyn += m.toDouble()
            if (m > peakDyn) peakDyn = m
        }
        val accRms = sqrt((sumDyn / n).toFloat())
        val accPeak = peakDyn

        // gyro マグニチュード RMS / peak
        var sumGyro = 0.0
        var peakGyro = 0f
        for (i in 0 until n) {
            val m = sqrt(gx[i] * gx[i] + gy[i] * gy[i] + gz[i] * gz[i])
            sumGyro += m.toDouble()
            if (m > peakGyro) peakGyro = m
        }
        val gyroRms = sqrt((sumGyro / n).toFloat())
        val gyroPeak = peakGyro

        // 重力ベクトル平均 → 傾き
        var tgax = 0.0; var tgay = 0.0; var tgaz = 0.0
        for (i in 0 until n) { tgax += gax[i]; tgay += gay[i]; tgaz += gaz[i] }
        val mgax = (tgax / n).toFloat()
        val mgay = (tgay / n).toFloat()
        val mgaz = (tgaz / n).toFloat()
        val gmag = sqrt(mgax * mgax + mgay * mgay + mgaz * mgaz).coerceAtLeast(1e-6f)
        val ratio = (abs(mgaz) / gmag).toDouble().coerceIn(0.0, 1.0)
        val tilt = (acos(ratio) * 180.0 / Math.PI).toFloat()

        // jerk = 動的加速度差分の大きさピーク
        var peakJerk = 0f
        for (i in 1 until n) {
            val jx = dax[i] - dax[i - 1]
            val jy = day[i] - day[i - 1]
            val jz = daz[i] - daz[i - 1]
            val m = sqrt(jx * jx + jy * jy + jz * jz)
            if (m > peakJerk) peakJerk = m
        }

        return floatArrayOf(
            accRms, accPeak,
            gyroRms, gyroPeak,
            tilt, peakJerk,
            mgax, mgay, mgaz
        )
    }
}
