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

    /**
     * 接続先BLEデバイス名（ユーザー選択）。
     * 空 = 未選択。実行時にOSペアリング済みの先頭 "BikeClock-" デバイスを自動使用する。
     * デバイス名はハードコードせず、接頭辞 "BikeClock-" のルールのみで運用する。
     */
    val TARGET_DEVICE_NAME = SettingKey.Direct(
        stringPreferencesKey("target_device_name"),
        ""
    )

    /**
     * スマホ通知のBikeClock転送 ON/OFF（Phase 11）。
     * NotificationListenerService が onNotificationPosted でこの設定を参照する。
     * default = true（許可されていれば転送する）。
     */
    val NOTIFICATION_FORWARDING_ENABLED = SettingKey.Direct(
        booleanPreferencesKey("notification_forwarding_enabled"),
        true
    )

    /** 通知の最大表示文字数のデフォルト値（実機調整による最適値） */
    const val DEFAULT_NOTIFICATION_MAX_CHARS = 47

    /**
     * 通知の最大文字数（Phase 11 拡張）。
     * ePaperの視認性を考慮し、この文字数で切り詰め、末尾に「＞」を付与する。
     */
    val NOTIFICATION_MAX_CHARS = SettingKey.Direct(
        intPreferencesKey("notification_max_chars"),
        DEFAULT_NOTIFICATION_MAX_CHARS
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
