package com.pirorin215.btclockmob.battery

import android.util.Log
import com.pirorin215.btclockmob.data.DeviceHistoryEntry
import com.patrykandpatrick.vico.core.entry.entryOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 電池切れ予測結果
 */
sealed class BatteryPrediction {
    /** 残り時間（切り下げ時間） */
    data class RemainingTime(val hours: Long) : BatteryPrediction()
    /** 充電中（電圧上昇傾向） */
    object Charging : BatteryPrediction()
    /** 既に低電圧 */
    object AlreadyLow : BatteryPrediction()
    /** 計算不能 */
    object Unknown : BatteryPrediction()
}

/**
 * 電池の状態予測を行うユーティリティ
 */
object BatteryPredictor {
    private const val TAG = "BatteryPrediction"

    /**
     * 最新の放電サイクルの経過時間を計算
     */
    fun calculateCurrentCycleElapsedTime(history: List<DeviceHistoryEntry>): Long? {
        if (history.size < 2) {
            return null
        }

        // 充電サイクルに分割
        val cycles = BatteryCycleAnalyzer.splitIntoBatteryCycles(history)

        if (cycles.isEmpty()) {
            return null
        }

        // 最新のサイクル（最後のサイクル）を取得
        val currentCycle = cycles.last()

        // サイクルのエントリが2つ以上ない場合は計算できない
        if (currentCycle.entries.size < 2) {
            return null
        }

        // サイクルの開始時刻と終了時刻
        val startTimestamp = currentCycle.entries.first().timestamp
        val endTimestamp = currentCycle.entries.last().timestamp

        // 経過時間（ミリ秒）
        val elapsedMs = endTimestamp - startTimestamp

        // 時間に変換（切り捨て）
        val elapsedHours = elapsedMs / (1000 * 60 * 60)

        return elapsedHours
    }

    /**
     * 電池切れまでの残り時間を予測（サイクル分析版）
     */
    fun predictBatteryLifeFromHistory(history: List<DeviceHistoryEntry>): BatteryPrediction {
        if (history.size < 2) {
            Log.d(TAG, "Unknown: history size < 2")
            return BatteryPrediction.Unknown
        }

        // 充電サイクルに分割
        val cycles = BatteryCycleAnalyzer.splitIntoBatteryCycles(history)
        Log.d(TAG, "Cycles detected: ${cycles.size}")

        // 各サイクルの統計を計算
        val statistics = BatteryCycleAnalyzer.calculateAllCycleStatistics(cycles)
        Log.d(TAG, "Valid statistics: ${statistics.size}")

        // デバッグ用: データをCSV形式でダンプ
        dumpHistoryDataToLog(history, cycles, statistics)

        // 統計が取れない場合は従来の方法にフォールバック
        if (statistics.isEmpty()) {
            Log.d(TAG, "Falling back to old method")
            val entries = history.mapIndexed { index, entry ->
                entry.batteryVoltage?.let { entryOf(index.toFloat(), it) }
            }.filterNotNull()
            val regression = BatteryCycleAnalyzer.calculateLinearRegression(entries)
            return predictBatteryLifeFallback(history, regression)
        }

        // 現在の電圧
        val currentVoltage = history.lastOrNull()?.batteryVoltage ?: return BatteryPrediction.Unknown
        Log.d(TAG, "Current voltage: $currentVoltage")

        // 既に低電圧の場合
        if (currentVoltage <= BatteryConstants.BATTERY_CUTOFF_VOLTAGE) {
            Log.d(TAG, "AlreadyLow: $currentVoltage <= ${BatteryConstants.BATTERY_CUTOFF_VOLTAGE}")
            return BatteryPrediction.AlreadyLow
        }

        // 平均放電率を計算（V/ms）
        val avgDischargeRate = BatteryCycleAnalyzer.calculateAverageDischargeRate(statistics)
        Log.d(TAG, "Average discharge rate: $avgDischargeRate V/ms")

        if (avgDischargeRate == null) {
            Log.d(TAG, "Unknown: avgDischargeRate is null")
            return BatteryPrediction.Unknown
        }

        // 放電率が0以上（充電中または安定）の場合
        if (avgDischargeRate >= 0) {
            Log.d(TAG, "Charging: avgDischargeRate >= 0")
            return BatteryPrediction.Charging
        }

        // 電池切れまでの電圧差
        val voltageDiff = currentVoltage - BatteryConstants.BATTERY_CUTOFF_VOLTAGE

        // 残り時間（ms）= 電圧差 / 放電率の絶対値
        // 放電率は負の値（電圧減少）なので、絶対値で割る
        val remainingMs = (voltageDiff / -avgDischargeRate).toLong()

        // 切り下げで時間のみ
        val remainingHours = remainingMs / (1000 * 60 * 60)
        Log.d(TAG, "Remaining hours: $remainingHours")

        return if (remainingHours > 0) {
            BatteryPrediction.RemainingTime(remainingHours)
        } else {
            Log.d(TAG, "AlreadyLow: remainingHours <= 0")
            BatteryPrediction.AlreadyLow
        }
    }

    /**
     * 電池切れまでの残り時間を予測（フォールバック版）
     */
    private fun predictBatteryLifeFallback(
        history: List<DeviceHistoryEntry>,
        regression: LinearRegression?
    ): BatteryPrediction {
        if (regression == null || history.size < 2) return BatteryPrediction.Unknown

        // 傾きが0以上なら充電中または安定
        if (regression.slope >= 0) return BatteryPrediction.Charging

        // 現在の電圧が既に低電圧の場合
        val currentVoltage = history.lastOrNull()?.batteryVoltage ?: return BatteryPrediction.Unknown
        if (currentVoltage <= BatteryConstants.BATTERY_CUTOFF_VOLTAGE) return BatteryPrediction.AlreadyLow

        // y = ax + b で y = BATTERY_CUTOFF_VOLTAGE となる x を求める
        // x = (BATTERY_CUTOFF_VOLTAGE - b) / a
        val targetX = (BatteryConstants.BATTERY_CUTOFF_VOLTAGE - regression.intercept) / regression.slope

        // 現在のインデックス（最後のデータポイント）
        val currentX = (history.size - 1).toFloat()

        // 予測インデックスが現在より過去なら既に電池切れ
        if (targetX <= currentX) return BatteryPrediction.AlreadyLow

        // インデックス差分を時間に変換
        // 最初と最後のデータポイントの時刻差からインデックスあたりの時間を計算
        val firstTimestamp = history.first().timestamp
        val lastTimestamp = history.last().timestamp
        val timeDiffMs = lastTimestamp - firstTimestamp
        val indexDiff = history.size - 1

        if (indexDiff <= 0) return BatteryPrediction.Unknown

        val msPerIndex = timeDiffMs.toDouble() / indexDiff
        val remainingIndexes = targetX - currentX
        val remainingMs = (remainingIndexes * msPerIndex).toLong()

        // 切り下げで時間のみ
        val remainingHours = remainingMs / (1000 * 60 * 60)

        return BatteryPrediction.RemainingTime(remainingHours)
    }

    /**
     * デバッグ用: 履歴データをCSV形式でログ出力
     */
    private fun dumpHistoryDataToLog(
        history: List<DeviceHistoryEntry>,
        cycles: List<BatteryCycle>,
        statistics: List<CycleStatistics>
    ) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        // 全履歴データをCSV形式で出力
        Log.d("BatteryData", "=== HISTORY DATA CSV ===")
        Log.d("BatteryData", "Index,Timestamp,DateTime,Voltage,BatteryLevel,Latitude,Longitude,IsInterpolated")
        history.forEachIndexed { index, entry ->
            val dateTime = dateFormat.format(Date(entry.timestamp))
            val voltage = entry.batteryVoltage ?: ""
            val level = entry.batteryLevel ?: ""
            val lat = entry.latitude ?: ""
            val lon = entry.longitude ?: ""
            Log.d("BatteryData", "$index,${entry.timestamp},$dateTime,$voltage,$level,$lat,$lon,${entry.isInterpolated}")
        }

        // 充電イベントの情報
        Log.d("BatteryData", "=== CHARGE EVENTS ===")
        val chargeEvents = BatteryCycleAnalyzer.detectChargeEvents(history)
        chargeEvents.forEach { index ->
            val prevVoltage = history.getOrNull(index - 1)?.batteryVoltage ?: 0f
            val currVoltage = history.getOrNull(index)?.batteryVoltage ?: 0f
            val jump = currVoltage - prevVoltage
            Log.d("BatteryData", "Charge event at index $index: ${prevVoltage}V -> ${currVoltage}V (jump: ${jump}V)")
        }

        // サイクル情報
        Log.d("BatteryData", "=== BATTERY CYCLES ===")
        Log.d("BatteryData", "CycleIndex,StartIndex,EndIndex,DataPoints,StartVoltage,EndVoltage,VoltageDrop,DurationMs")
        cycles.forEachIndexed { cycleIndex, cycle ->
            val startVoltage = cycle.entries.firstOrNull()?.batteryVoltage ?: 0f
            val endVoltage = cycle.entries.lastOrNull()?.batteryVoltage ?: 0f
            val voltageDrop = startVoltage - endVoltage
            val durationMs = if (cycle.entries.size > 1) {
                cycle.entries.last().timestamp - cycle.entries.first().timestamp
            } else {
                0L
            }
            Log.d("BatteryData", "$cycleIndex,${cycle.startIndex},${cycle.endIndex},${cycle.entries.size},$startVoltage,$endVoltage,$voltageDrop,$durationMs")
        }

        // 統計情報
        Log.d("BatteryData", "=== CYCLE STATISTICS ===")
        Log.d("BatteryData", "CycleIndex,Slope(V/index),Intercept,MsPerIndex,DischargeRate(V/ms),DischargeRate(V/hour)")
        statistics.forEachIndexed { index, stat ->
            val dischargeRatePerMs = stat.slope.toDouble() / stat.msPerIndex
            val dischargeRatePerHour = dischargeRatePerMs * 1000 * 60 * 60
            Log.d("BatteryData", "$index,${stat.slope},${stat.intercept},${stat.msPerIndex},$dischargeRatePerMs,$dischargeRatePerHour")
        }

        // 平均統計
        if (statistics.isNotEmpty()) {
            val avgDischargeRate = BatteryCycleAnalyzer.calculateAverageDischargeRate(statistics)
            val avgDischargeRatePerHour = avgDischargeRate?.let { it * 1000 * 60 * 60 }
            Log.d("BatteryData", "=== AVERAGE STATISTICS ===")
            Log.d("BatteryData", "Average discharge rate: $avgDischargeRate V/ms ($avgDischargeRatePerHour V/hour)")
        }
    }
}
