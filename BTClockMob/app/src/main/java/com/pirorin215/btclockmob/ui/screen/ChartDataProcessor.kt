package com.pirorin215.btclockmob.ui.screen

import com.pirorin215.btclockmob.data.DeviceHistoryEntry
import com.patrykandpatrick.vico.core.entry.FloatEntry
import com.patrykandpatrick.vico.core.entry.entryOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日付範囲を表すデータクラス
 */
data class DateRange(val startIndex: Int, val endIndex: Int, val date: String)

/**
 * チャート表示用のデータ処理ユーティリティ
 */
object ChartDataProcessor {
    /**
     * 履歴データから日付範囲を計算
     */
    fun calculateDateRanges(history: List<DeviceHistoryEntry>): List<DateRange> {
        if (history.isEmpty()) return emptyList()
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        return history.mapIndexed { index, entry ->
            index to dateFormat.format(Date(entry.timestamp))
        }.groupBy { it.second }
            .map { (date, entries) ->
                DateRange(entries.first().first, entries.last().first, date)
            }
            .sortedBy { it.startIndex }
    }

    /**
     * 日毎にデータを分割（連続性を保つため、境界点を重複させる）
     */
    fun splitEntriesByDate(
        history: List<DeviceHistoryEntry>,
        dateRanges: List<DateRange>,
        valueSelector: (DeviceHistoryEntry) -> Float?
    ): List<List<FloatEntry>> {
        return dateRanges.mapIndexed { dayIndex, range ->
            val entries = mutableListOf<FloatEntry>()

            // 前日の最後のポイントを含める（連続性のため）
            if (dayIndex > 0 && range.startIndex > 0) {
                val prevIndex = range.startIndex - 1
                valueSelector(history[prevIndex])?.let {
                    entries.add(entryOf(prevIndex.toFloat(), it))
                }
            }

            // この日のデータポイント
            for (i in range.startIndex..range.endIndex) {
                if (i < history.size) {
                    valueSelector(history[i])?.let {
                        entries.add(entryOf(i.toFloat(), it))
                    }
                }
            }

            // 次の日の最初のポイントを含める（連続性のため）
            if (dayIndex < dateRanges.size - 1 && range.endIndex + 1 < history.size) {
                val nextIndex = range.endIndex + 1
                valueSelector(history[nextIndex])?.let {
                    entries.add(entryOf(nextIndex.toFloat(), it))
                }
            }

            entries.toList()
        }
    }

    /**
     * 実データのみを日毎に分割（連続性を保つため、境界点を重複させる）
     */
    fun splitRealEntriesByDate(
        history: List<DeviceHistoryEntry>,
        dateRanges: List<DateRange>,
        valueSelector: (DeviceHistoryEntry) -> Float?
    ): List<List<FloatEntry>> {
        return dateRanges.mapIndexed { dayIndex, range ->
            val entries = mutableListOf<FloatEntry>()

            // 前日の最後のポイントを含める（連続性のため、実データのみ）
            if (dayIndex > 0 && range.startIndex > 0) {
                val prevIndex = range.startIndex - 1
                if (!history[prevIndex].isInterpolated) {
                    valueSelector(history[prevIndex])?.let {
                        entries.add(entryOf(prevIndex.toFloat(), it))
                    }
                }
            }

            // この日の実データポイント
            for (i in range.startIndex..range.endIndex) {
                if (i < history.size && !history[i].isInterpolated) {
                    valueSelector(history[i])?.let {
                        entries.add(entryOf(i.toFloat(), it))
                    }
                }
            }

            // 次の日の最初のポイントを含める（連続性のため、実データのみ）
            if (dayIndex < dateRanges.size - 1 && range.endIndex + 1 < history.size) {
                val nextIndex = range.endIndex + 1
                if (!history[nextIndex].isInterpolated) {
                    valueSelector(history[nextIndex])?.let {
                        entries.add(entryOf(nextIndex.toFloat(), it))
                    }
                }
            }

            entries.toList()
        }.filter { it.isNotEmpty() } // 空のシリーズを除外
    }

    /**
     * 補間データのみを抽出
     */
    fun extractInterpolatedEntries(
        history: List<DeviceHistoryEntry>,
        valueSelector: (DeviceHistoryEntry) -> Float?
    ): List<FloatEntry> {
        return history.mapIndexedNotNull { index, entry ->
            if (entry.isInterpolated) {
                valueSelector(entry)?.let { entryOf(index.toFloat(), it) }
            } else {
                null
            }
        }
    }
}
