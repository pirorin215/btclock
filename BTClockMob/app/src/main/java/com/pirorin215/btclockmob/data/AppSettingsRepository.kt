package com.pirorin215.btclockmob.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// DataStoreのインスタンスをContextの拡張プロパティとして定義
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

/**
 * Generic setting key with type safety
 * @param T The type of the setting value
 */
sealed class SettingKey<T> {
    abstract val defaultValue: T

    /**
     * Direct mapping for primitive types (String, Int, Boolean, Float)
     */
    class Direct<T> internal constructor(
        internal val preferencesKey: Preferences.Key<T>,
        override val defaultValue: T
    ) : SettingKey<T>()

    /**
     * Mapped type for complex types that need conversion (e.g., ThemeMode)
     */
    class Mapped<T, R> internal constructor(
        internal val preferencesKey: Preferences.Key<R>,
        override val defaultValue: T,
        internal val toStored: (T) -> R,
        internal val fromStored: (R) -> T
    ) : SettingKey<T>()
}

/**
 * Centralized definition of all app settings
 */
object Settings {
    val THEME_MODE = SettingKey.Mapped(
        preferencesKey = stringPreferencesKey("theme_mode"),
        defaultValue = ThemeMode.SYSTEM,
        toStored = { it.name },
        fromStored = { ThemeMode.valueOf(it) }
    )

    val AUTO_START_ON_BOOT = SettingKey.Direct(
        booleanPreferencesKey("auto_start_on_boot"),
        false
    )

    val HISTORY_INTERVAL_MIN = SettingKey.Direct(
        intPreferencesKey("history_interval_min"),
        15
    )

    val HISTORY_DISTANCE_M = SettingKey.Direct(
        intPreferencesKey("history_distance_m"),
        1000
    )

    val ROUTE_CENTER_OFFSET = SettingKey.Direct(
        floatPreferencesKey("route_center_offset"),
        0f
    )

    val ROUTE_LABEL_FONT_SIZE = SettingKey.Direct(
        floatPreferencesKey("route_label_font_size"),
        18f
    )
}

class AppSettingsRepository(private val context: Context) {

    /**
     * Generic method to get a Flow for any setting
     */
    fun <T> getFlow(key: SettingKey<T>): Flow<T> {
        return when (key) {
            is SettingKey.Direct -> {
                context.dataStore.data.map { preferences ->
                    @Suppress("UNCHECKED_CAST")
                    (preferences[key.preferencesKey] as? T) ?: key.defaultValue
                }
            }
            is SettingKey.Mapped<T, *> -> {
                context.dataStore.data.map { preferences ->
                    @Suppress("UNCHECKED_CAST")
                    val stored = preferences[key.preferencesKey as Preferences.Key<Any>]
                    if (stored != null) {
                        (key.fromStored as (Any) -> T)(stored)
                    } else {
                        key.defaultValue
                    }
                }
            }
        }
    }

    /**
     * Generic method to set a value for any setting
     */
    suspend fun <T> setValue(key: SettingKey<T>, value: T) {
        when (key) {
            is SettingKey.Direct -> {
                context.dataStore.edit { preferences ->
                    @Suppress("UNCHECKED_CAST")
                    preferences[key.preferencesKey as Preferences.Key<Any>] = value as Any
                }
            }
            is SettingKey.Mapped<T, *> -> {
                context.dataStore.edit { preferences ->
                    @Suppress("UNCHECKED_CAST")
                    val stored = (key.toStored as (T) -> Any)(value)
                    preferences[key.preferencesKey as Preferences.Key<Any>] = stored
                }
            }
        }
    }

}
