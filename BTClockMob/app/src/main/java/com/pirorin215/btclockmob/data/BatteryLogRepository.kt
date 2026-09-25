package com.pirorin215.btclockmob.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import com.pirorin215.btclockmob.data.JsonUtil.json

private val Context.batteryLogDataStore: DataStore<Preferences> by preferencesDataStore(name = "battery_log")

/**
 * バッテリー電圧の時系列ログをDataStore(JSONリスト)で保持するリポジトリ。
 * DeviceHistoryRepositoryと同じ構成。乗車中(接続中)のみ記録が増える。
 */
class BatteryLogRepository(private val context: Context) {

    private object PreferencesKeys {
        val BATTERY_LOG_LIST = stringPreferencesKey("battery_log_list")
    }

    companion object {
        // 保持上限。5分間隔・1日1〜2時間乗車の想定で約1年分。
        private const val MAX_ENTRIES = 10000
    }

    val batteryLogFlow: Flow<List<BatteryLogEntry>> = context.batteryLogDataStore.data
        .map { preferences ->
            val jsonString = preferences[PreferencesKeys.BATTERY_LOG_LIST] ?: "[]"
            try {
                json.decodeFromString<List<BatteryLogEntry>>(jsonString)
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }

    suspend fun addEntry(entry: BatteryLogEntry) {
        updateListInDataStore<BatteryLogEntry>(context.batteryLogDataStore, PreferencesKeys.BATTERY_LOG_LIST) { currentList ->
            currentList.add(0, entry)
            // 古い側を切り捨てて容量を固定
            while (currentList.size > MAX_ENTRIES) {
                currentList.removeAt(currentList.size - 1)
            }
        }
    }

    suspend fun clearAllEntries() {
        context.batteryLogDataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.BATTERY_LOG_LIST)
        }
    }
}
