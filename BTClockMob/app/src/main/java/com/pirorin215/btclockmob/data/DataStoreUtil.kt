package com.pirorin215.btclockmob.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.serialization.serializer

/**
 * Utility functions for common DataStore operations.
 * Eliminates boilerplate code in repositories.
 */

/**
 * Generic function to update a list stored in DataStore.
 * Handles JSON encoding/decoding and error recovery automatically.
 *
 * @param dataStore The DataStore instance
 * @param key The preferences key for the list
 * @param defaultValue The default value if data is corrupted
 * @param update Function to transform the current list (modifies in place)
 */
suspend inline fun <reified T : Any> updateListInDataStore(
    dataStore: DataStore<Preferences>,
    key: Preferences.Key<String>,
    defaultValue: String = "[]",
    crossinline update: (MutableList<T>) -> Unit
) {
    dataStore.edit { preferences ->
        val jsonString = preferences[key] ?: defaultValue
        val currentList = safeDecodeList<T>(jsonString)
        update(currentList)
        // Encode using ListSerializer for proper type handling
        preferences[key] = JsonUtil.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(serializer()), currentList)
    }
}

/**
 * Generic function to read a list from DataStore.
 * Returns the list or empty list if reading fails.
 */
suspend inline fun <reified T : Any> getListFromDataStore(
    dataStore: DataStore<Preferences>,
    key: Preferences.Key<String>,
    defaultValue: String = "[]"
): List<T> {
    val jsonString = dataStore.data.first()[key] ?: defaultValue
    return safeDecodeList(jsonString)
}

/**
 * Generic function to replace entire list in DataStore with a new list.
 */
suspend inline fun <reified T : Any> replaceListInDataStore(
    dataStore: DataStore<Preferences>,
    key: Preferences.Key<String>,
    newList: List<T>
) {
    dataStore.edit { preferences ->
        preferences[key] = JsonUtil.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(serializer()), newList)
    }
}

/**
 * Time-based filtering utility for lists with timestamps.
 * Filters entries to only include those within the retention period.
 */
fun <T> List<T>.filterByTimestamp(
    retentionPeriodMs: Long,
    timestampExtractor: (T) -> Long
): List<T> {
    val cutoffTime = System.currentTimeMillis() - retentionPeriodMs
    return this.filter { timestampExtractor(it) >= cutoffTime }
}

/**
 * Extension function for ByteArrayOutputStream to convert to UTF-8 string.
 * Consistent with the pattern used in BLE response handling.
 */
fun java.io.ByteArrayOutputStream.toUtf8String(trim: Boolean = true): String {
    val str = this.toString("UTF-8")
    return if (trim) str.trim() else str
}
