package com.pirorin215.btclockmob.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.pirorin215.btclockmob.constants.TimeConstants
import com.pirorin215.btclockmob.constants.LocationConstants
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val Context.deviceHistoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "device_history")

class DeviceHistoryRepository(private val context: Context) {

    private object PreferencesKeys {
        val DEVICE_HISTORY_LIST = stringPreferencesKey("device_history_list")
    }

    companion object {
        // Constants moved to TimeConstants.kt and LocationConstants.kt
    }

    val deviceHistoryFlow: Flow<List<DeviceHistoryEntry>> = context.deviceHistoryDataStore.data
        .map { preferences ->
            val jsonString = preferences[PreferencesKeys.DEVICE_HISTORY_LIST] ?: "[]"
            try {
                JsonUtil.json.decodeFromString<List<DeviceHistoryEntry>>(jsonString)
            } catch (e: Exception) {
                // Log the error or handle it appropriately, return empty list to prevent crash
                e.printStackTrace()
                emptyList()
            }
        }

    suspend fun addEntry(entry: DeviceHistoryEntry) {
        updateListInDataStore<DeviceHistoryEntry>(context.deviceHistoryDataStore, PreferencesKeys.DEVICE_HISTORY_LIST) { currentList ->
            // BikeClock requires recording all connection/disconnection events
            // as they represent start and end of bike trips.
            // Filtering by time or location is no longer needed.
            currentList.add(0, entry)
        }
    }

    suspend fun clearAllEntries() {
        context.deviceHistoryDataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.DEVICE_HISTORY_LIST)
        }
    }

    suspend fun deleteEntriesByTimestamps(timestamps: List<Long>) {
        updateListInDataStore<DeviceHistoryEntry>(context.deviceHistoryDataStore, PreferencesKeys.DEVICE_HISTORY_LIST) { currentList ->
            val filteredList = currentList.filterNot { entry: DeviceHistoryEntry ->
                timestamps.contains(entry.timestamp)
            }.toMutableList()
            currentList.clear()
            currentList.addAll(filteredList)
        }
    }

    /**
     * 指定したタイムスタンプのエントリの住所を更新する
     */
    suspend fun updateEntryAddress(timestamp: Long, address: String) {
        updateListInDataStore<DeviceHistoryEntry>(context.deviceHistoryDataStore, PreferencesKeys.DEVICE_HISTORY_LIST) { currentList ->
            val index = currentList.indexOfFirst { entry: DeviceHistoryEntry -> entry.timestamp == timestamp }
            if (index != -1) {
                val entry = currentList[index]
                currentList[index] = entry.copy(address = address)
            }
        }
    }

    /**
     * 指定した期間より古いエントリを削除する
     * @param retentionPeriodMs 保持期間（ミリ秒）
     */
    suspend fun deleteOldEntries(retentionPeriodMs: Long) {
        updateListInDataStore<DeviceHistoryEntry>(context.deviceHistoryDataStore, PreferencesKeys.DEVICE_HISTORY_LIST) { currentList ->
            val filteredList: List<DeviceHistoryEntry> = currentList.filterByTimestamp(
                retentionPeriodMs
            ) { entry: DeviceHistoryEntry ->
                entry.timestamp
            }
            currentList.clear()
            currentList.addAll(filteredList)
        }
    }
}
