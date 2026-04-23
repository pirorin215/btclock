package com.pirorin215.btclockmob.viewModel

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pirorin215.btclockmob.data.DeviceHistoryRepository
import com.pirorin215.btclockmob.data.DeviceHistoryEntry
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class DeviceHistoryViewModel(
    private val deviceHistoryRepository: DeviceHistoryRepository,
    private val application: android.app.Application
) : ViewModel() {

    /**
     * デバイス履歴エントリ（リスト表示用、降順）
     */
    val deviceHistoryEntriesForList: StateFlow<List<DeviceHistoryEntry>> =
        deviceHistoryRepository.deviceHistoryFlow
            .map { it.sortedByDescending { entry -> entry.timestamp } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    /**
     * デバイス履歴エントリ（全てのデータ、昇順 - 将来的な拡張用）
     */
    val deviceHistoryEntries: StateFlow<List<DeviceHistoryEntry>> =
        deviceHistoryRepository.deviceHistoryFlow
            .map { it.sortedBy { entry -> entry.timestamp } }
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
            
            // 住所が未取得の最近のエントリに対して住所を補完する
            fillMissingAddresses()
        }
    }

    /**
     * 住所が未取得の最近のエントリ（最大20件）に対して、住所を取得して更新する
     */
    private suspend fun fillMissingAddresses() {
        val entries = deviceHistoryEntriesForList.value
        val missingAddressEntries = entries.filter { it.address == null && it.latitude != null && it.longitude != null }
            .take(20) // 一度に大量にリクエストしないよう制限
            
        for (entry in missingAddressEntries) {
            val address = com.pirorin215.btclockmob.data.GeocoderUtil.getAddressFromLocation(
                application,
                entry.latitude!!,
                entry.longitude!!
            )
            if (address != null) {
                deviceHistoryRepository.updateEntryAddress(entry.timestamp, address)
            }
        }
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
}
