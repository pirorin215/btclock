package com.pirorin215.btclockmob.battery

import android.util.Log
import com.pirorin215.btclockmob.data.DeviceHistoryEntry
import com.patrykandpatrick.vico.core.entry.FloatEntry
import com.patrykandpatrick.vico.core.entry.entryOf

/**
 * 充電サイクルを表すデータクラス
 */
data class BatteryCycle(
    val startIndex: Int,
    val endIndex: Int,
    val entries: List<DeviceHistoryEntry>
)

/**
 * 充電サイクルごとの統計情報
 */
data class CycleStatistics(
    val slope: Float,           // 放電率（V/インデックス）
    val intercept: Float,       // 切片
    val msPerIndex: Double      // インデックスあたりの時間（ミリ秒）
)

/**
 * 最小二乗法で回帰直線の係数を計算 (y = ax + b)
 */
data class LinearRegression(val slope: Float, val intercept: Float)

/**
 * 電池の充電サイクルを分析するユーティリティ
 */
object BatteryCycleAnalyzer {
    private const val TAG = "BatteryCycleAnalyzer"

    /**
     * 充電イベントを検出してインデックスのリストを返す
     */
    fun detectChargeEvents(history: List<DeviceHistoryEntry>): List<Int> {
        if (history.size < 2) return emptyList()

        val rawChargeIndices = mutableListOf<Int>()

        // ステップ1: 電圧ジャンプを検出
        for (i in 1 until history.size) {
            val prevVoltage = history[i - 1].batteryVoltage ?: continue
            val currVoltage = history[i].batteryVoltage ?: continue

            // 電圧が閾値以上上昇した場合を充電イベント候補とする
            if (currVoltage - prevVoltage >= BatteryConstants.CHARGE_DETECTION_THRESHOLD) {
                rawChargeIndices.add(i)
            }
        }

        if (rawChargeIndices.isEmpty()) return emptyList()

        // ステップ2: 充電後の電圧が低い（短い充電）を除外
        val significantCharges = rawChargeIndices.filter { index ->
            val voltageAfterCharge = history[index].batteryVoltage ?: 0f
            voltageAfterCharge >= BatteryConstants.FULL_CHARGE_VOLTAGE
        }

        if (significantCharges.isEmpty()) return emptyList()

        // ステップ3: 連続する充電イベントをマージ
        val mergedCharges = mutableListOf<Int>()
        mergedCharges.add(significantCharges.first())

        for (i in 1 until significantCharges.size) {
            val prevIndex = significantCharges[i - 1]
            val currIndex = significantCharges[i]

            val timeDiff = history[currIndex].timestamp - history[prevIndex].timestamp

            // 時間差が閾値以上なら別の充電イベント
            if (timeDiff >= BatteryConstants.CHARGE_MERGE_TIME_MS) {
                mergedCharges.add(currIndex)
            }
            // 時間差が小さければマージ（より大きな電圧に達した方を採用）
            else {
                val prevVoltage = history[prevIndex].batteryVoltage ?: 0f
                val currVoltage = history[currIndex].batteryVoltage ?: 0f
                if (currVoltage > prevVoltage) {
                    mergedCharges[mergedCharges.size - 1] = currIndex
                }
            }
        }

        return mergedCharges
    }

    /**
     * 履歴データを充電サイクルごとに分割
     */
    fun splitIntoBatteryCycles(history: List<DeviceHistoryEntry>): List<BatteryCycle> {
        if (history.isEmpty()) return emptyList()

        val chargeEvents = detectChargeEvents(history)
        val cycles = mutableListOf<BatteryCycle>()

        // 充電イベントがない場合は全体を1サイクルとする
        if (chargeEvents.isEmpty()) {
            return listOf(BatteryCycle(0, history.size - 1, history))
        }

        // 最初のサイクル（開始 ～ 最初の充電イベント直前）
        if (chargeEvents.first() > 0) {
            cycles.add(
                BatteryCycle(
                startIndex = 0,
                endIndex = chargeEvents.first() - 1,
                entries = history.subList(0, chargeEvents.first())
            )
            )
        }

        // 中間のサイクル
        for (i in 0 until chargeEvents.size - 1) {
            val start = chargeEvents[i]
            val end = chargeEvents[i + 1] - 1
            if (start <= end) {
                cycles.add(
                    BatteryCycle(
                    startIndex = start,
                    endIndex = end,
                    entries = history.subList(start, end + 1)
                )
                )
            }
        }

        // 最後のサイクル（最後の充電イベント ～ 終了）
        val lastChargeIndex = chargeEvents.last()
        if (lastChargeIndex < history.size) {
            cycles.add(
                BatteryCycle(
                startIndex = lastChargeIndex,
                endIndex = history.size - 1,
                entries = history.subList(lastChargeIndex, history.size)
            )
            )
        }

        return cycles
    }

    /**
     * 最小二乗法で線形回帰を計算
     */
    fun calculateLinearRegression(entries: List<FloatEntry>): LinearRegression? {
        if (entries.size < 2) return null

        val n = entries.size
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumX2 = 0.0

        for (entry in entries) {
            val x = entry.x.toDouble()
            val y = entry.y.toDouble()
            sumX += x
            sumY += y
            sumXY += x * y
            sumX2 += x * x
        }

        val denominator = n * sumX2 - sumX * sumX
        if (denominator == 0.0) return null

        val slope = ((n * sumXY - sumX * sumY) / denominator).toFloat()
        val intercept = ((sumY - slope * sumX) / n).toFloat()

        return LinearRegression(slope, intercept)
    }

    /**
     * トレンドライン用のデータポイントを生成（始点と終点の2点）
     */
    fun createTrendLineEntries(
        entries: List<FloatEntry>,
        regression: LinearRegression
    ): List<FloatEntry> {
        if (entries.isEmpty()) return emptyList()

        val minX = entries.minOf { it.x }
        val maxX = entries.maxOf { it.x }

        val startY = regression.slope * minX + regression.intercept
        val endY = regression.slope * maxX + regression.intercept

        return listOf(
            entryOf(minX, startY),
            entryOf(maxX, endY)
        )
    }

    /**
     * 単一サイクルの統計を計算（外れ値除外あり）
     */
    fun calculateCycleStatistics(cycle: BatteryCycle): CycleStatistics? {
        if (cycle.entries.size < 2) return null

        // サイクルの継続時間をチェック
        val firstTimestamp = cycle.entries.first().timestamp
        val lastTimestamp = cycle.entries.last().timestamp
        val durationMs = lastTimestamp - firstTimestamp
        val durationHours = durationMs / (1000.0 * 60 * 60)

        // 短すぎるサイクルは除外
        if (durationHours < BatteryConstants.MIN_CYCLE_DURATION_HOURS) {
            Log.d(TAG, "Cycle too short: ${durationHours}h < ${BatteryConstants.MIN_CYCLE_DURATION_HOURS}h, skipping")
            return null
        }

        // FloatEntryに変換（サイクル内の相対インデックスを使用）
        val entries = cycle.entries.mapIndexedNotNull { relativeIndex, entry ->
            entry.batteryVoltage?.let {
                entryOf(relativeIndex.toFloat(), it)
            }
        }

        if (entries.size < 2) return null

        // 線形回帰を計算
        val regression = calculateLinearRegression(entries) ?: return null

        // 傾きが0以上（充電中または安定）の場合は無効
        if (regression.slope >= 0) {
            Log.d(TAG, "Cycle has positive slope: ${regression.slope}, skipping")
            return null
        }

        val indexDiff = cycle.entries.size - 1
        if (indexDiff <= 0) return null

        val msPerIndex = durationMs.toDouble() / indexDiff

        return CycleStatistics(
            slope = regression.slope,
            intercept = regression.intercept,
            msPerIndex = msPerIndex
        )
    }

    /**
     * 全サイクルの統計を計算
     */
    fun calculateAllCycleStatistics(cycles: List<BatteryCycle>): List<CycleStatistics> {
        return cycles.mapNotNull { calculateCycleStatistics(it) }
    }

    /**
     * 複数サイクルの統計から平均的な放電率（V/時間）を計算
     */
    fun calculateAverageDischargeRate(statistics: List<CycleStatistics>): Double? {
        if (statistics.isEmpty()) return null

        // 各サイクルの放電率（V/ms）を計算して平均化
        val dischargeRates = statistics.map { stat ->
            // slope は V/インデックス、msPerIndex は ms/インデックス
            // 放電率(V/ms) = slope / msPerIndex
            stat.slope.toDouble() / stat.msPerIndex
        }

        return dischargeRates.average()
    }
}
