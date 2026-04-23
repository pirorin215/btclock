package com.pirorin215.btclockmob.viewModel

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pirorin215.btclockmob.data.DeviceHistoryRepository
import com.pirorin215.btclockmob.data.DeviceHistoryEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.Calendar
import java.util.TimeZone

class DeviceHistoryViewModel(
    private val deviceHistoryRepository: DeviceHistoryRepository
) : ViewModel() {

    val deviceHistoryEntries: StateFlow<List<DeviceHistoryEntry>> =
        deviceHistoryRepository.deviceHistoryFlow
            .map { it.sortedBy { entry -> entry.timestamp } } // Sort by timestamp in ascending order for the graph
            .map { normalizeAndInterpolateData(it) } // 正時に正規化して補間
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    val deviceHistoryEntriesForList: StateFlow<List<DeviceHistoryEntry>> =
        deviceHistoryRepository.deviceHistoryFlow
            .map { it.sortedByDescending { entry -> entry.timestamp } } // Sort by timestamp in descending order for the list
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    private val _homeLocation = MutableStateFlow<Location?>(null)
    val homeLocation: StateFlow<Location?> = _homeLocation.asStateFlow()

    // 選択モードの状態
    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode.asStateFlow()

    // 選択されたエントリのタイムスタンプセット
    private val _selectedEntries = MutableStateFlow<Set<Long>>(emptySet())
    val selectedEntries: StateFlow<Set<Long>> = _selectedEntries.asStateFlow()

    init {
        deviceHistoryEntries.onEach { entries ->
            _homeLocation.value = calculateHomeLocation(entries)
        }.launchIn(viewModelScope)

        // 2週間以上前のデータを削除 (14 days * 24 hours * 60 minutes * 60 seconds * 1000 ms)
        viewModelScope.launch {
            val twoWeeksMs = 14L * 24 * 60 * 60 * 1000
            deviceHistoryRepository.deleteOldEntries(twoWeeksMs)
        }
    }

    /**
     * データを正時に正規化し、空白時間を補間する
     *
     * 処理内容：
     * 1. タイムスタンプを正時（00:00, 01:00, ...23:00）に丸める
     * 2. 同じ正時のデータがある場合は平均値を計算
     * 3. 連続する正時データの間に空白がある場合、線形補間で埋める
     */
    private fun normalizeAndInterpolateData(entries: List<DeviceHistoryEntry>): List<DeviceHistoryEntry> {
        if (entries.isEmpty()) return emptyList()

        // 1. タイムスタンプを正時に丸める（四捨五入: 30分以上なら次の時間、30分未満なら現在の時間）
        val roundedEntries = entries.map { entry ->
            val calendar = Calendar.getInstance(TimeZone.getDefault()).apply {
                timeInMillis = entry.timestamp
                val minute = get(Calendar.MINUTE)
                // 30分以上なら次の時間に繰り上げ
                if (minute >= 30) {
                    add(Calendar.HOUR_OF_DAY, 1)
                }
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            entry.copy(timestamp = calendar.timeInMillis)
        }

        // 2. 同じ正時のデータをグループ化して平均値を計算
        val averagedEntries = roundedEntries
            .groupBy { it.timestamp }
            .map { (timestamp, group) ->
                val avgBatteryLevel = group.mapNotNull { it.batteryLevel }.average().toFloat().takeIf { it.isFinite() }
                val avgBatteryVoltage = group.mapNotNull { it.batteryVoltage }.average().toFloat().takeIf { it.isFinite() }
                val avgLatitude = group.mapNotNull { it.latitude }.average().takeIf { it.isFinite() }
                val avgLongitude = group.mapNotNull { it.longitude }.average().takeIf { it.isFinite() }

                DeviceHistoryEntry(
                    timestamp = timestamp,
                    latitude = avgLatitude,
                    longitude = avgLongitude,
                    batteryLevel = avgBatteryLevel,
                    batteryVoltage = avgBatteryVoltage
                )
            }
            .sortedBy { it.timestamp }

        if (averagedEntries.size < 2) return averagedEntries

        // 3. 連続する正時データの間に空白がある場合、線形補間で埋める
        val interpolatedEntries = mutableListOf<DeviceHistoryEntry>()

        for (i in 0 until averagedEntries.size - 1) {
            val current = averagedEntries[i]
            val next = averagedEntries[i + 1]

            // 現在のエントリを追加
            interpolatedEntries.add(current)

            // 時間差を計算（ミリ秒 -> 時間）
            val timeDiffMs = next.timestamp - current.timestamp
            val hoursDiff = timeDiffMs / (1000 * 60 * 60)

            // 1時間以上空いている場合は補間
            if (hoursDiff > 1) {
                for (h in 1 until hoursDiff.toInt()) {
                    val interpolatedTimestamp = current.timestamp + h * (1000 * 60 * 60)
                    val ratio = h.toFloat() / hoursDiff.toFloat()

                    // 線形補間でバッテリーレベルと電圧を計算
                    val interpolatedBatteryLevel = if (current.batteryLevel != null && next.batteryLevel != null) {
                        current.batteryLevel + (next.batteryLevel - current.batteryLevel) * ratio
                    } else null

                    val interpolatedBatteryVoltage = if (current.batteryVoltage != null && next.batteryVoltage != null) {
                        current.batteryVoltage + (next.batteryVoltage - current.batteryVoltage) * ratio
                    } else null

                    val interpolatedLatitude = if (current.latitude != null && next.latitude != null) {
                        current.latitude + (next.latitude - current.latitude) * ratio
                    } else null

                    val interpolatedLongitude = if (current.longitude != null && next.longitude != null) {
                        current.longitude + (next.longitude - current.longitude) * ratio
                    } else null

                    interpolatedEntries.add(
                        DeviceHistoryEntry(
                            timestamp = interpolatedTimestamp,
                            latitude = interpolatedLatitude,
                            longitude = interpolatedLongitude,
                            batteryLevel = interpolatedBatteryLevel,
                            batteryVoltage = interpolatedBatteryVoltage,
                            isInterpolated = true
                        )
                    )
                }
            }
        }

        // 最後のエントリを追加
        interpolatedEntries.add(averagedEntries.last())

        return interpolatedEntries
    }

    private fun calculateHomeLocation(entries: List<DeviceHistoryEntry>): Location? {
        val locations = entries.mapNotNull { entry ->
            if (entry.latitude != null && entry.longitude != null) {
                Location("").apply {
                    latitude = entry.latitude
                    longitude = entry.longitude
                }
            } else {
                null
            }
        }

        if (locations.isEmpty()) {
            return null
        }

        val clusters = mutableListOf<MutableList<Location>>()

        for (location in locations) {
            var foundCluster = false
            for (cluster in clusters) {
                val clusterCenter = getClusterCenter(cluster)
                if (location.distanceTo(clusterCenter) < 30) {
                    cluster.add(location)
                    foundCluster = true
                    break
                }
            }
            if (!foundCluster) {
                clusters.add(mutableListOf(location))
            }
        }

        if (clusters.isEmpty()) {
            return null
        }

        val largestCluster = clusters.maxByOrNull { it.size }
        return getClusterCenter(largestCluster!!)
    }

    private fun getClusterCenter(cluster: List<Location>): Location {
        if (cluster.isEmpty()) {
            // This should not happen if called correctly
            throw IllegalArgumentException("Cluster cannot be empty")
        }
        val center = Location("")
        val avgLat = cluster.map { it.latitude }.average()
        val avgLon = cluster.map { it.longitude }.average()
        center.latitude = avgLat
        center.longitude = avgLon
        return center
    }


    fun clearHistory() {
        viewModelScope.launch {
            deviceHistoryRepository.clearAllEntries()
        }
    }

    // 選択モードを開始
    fun enterSelectionMode(timestamp: Long) {
        _isSelectionMode.value = true
        _selectedEntries.value = setOf(timestamp)
    }

    // 選択モードを終了
    fun exitSelectionMode() {
        _isSelectionMode.value = false
        _selectedEntries.value = emptySet()
    }

    // エントリの選択/解除をトグル
    fun toggleSelection(timestamp: Long) {
        _selectedEntries.value = if (_selectedEntries.value.contains(timestamp)) {
            _selectedEntries.value - timestamp
        } else {
            _selectedEntries.value + timestamp
        }

        // 選択が空になったら選択モードを終了
        if (_selectedEntries.value.isEmpty()) {
            exitSelectionMode()
        }
    }

    // 選択したエントリを削除
    fun deleteSelectedEntries() {
        viewModelScope.launch {
            deviceHistoryRepository.deleteEntriesByTimestamps(_selectedEntries.value.toList())
            exitSelectionMode()
        }
    }

    class Factory(private val deviceHistoryRepository: DeviceHistoryRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(DeviceHistoryViewModel::class.java)) {
                return DeviceHistoryViewModel(deviceHistoryRepository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
