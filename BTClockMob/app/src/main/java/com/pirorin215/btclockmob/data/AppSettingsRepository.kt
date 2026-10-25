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
     * 空 = 未選択。実行時にOSペアリング済みの全 "BikeClock-" デバイスが接続候補となり、
     * 実際に広告しているデバイスへ自動接続する（バイク/自転車の切り替えがアプリ操作不要）。
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

    /**
     * 乗降ログの diary-loc 書き出し ON/OFF。
     * ON で乗降イベント(接続/切断/定期履歴)のたびに SAF ツリー
     * (Syncthing 共有の diary-loc)直下 cycleclock/ride-YYYY-MM-DD.jsonl へ
     * 1行追記する(Mac mini 日記向け)。デフォルト OFF で既存動作に影響させない。
     */
    val RIDE_LOG_ENABLED = SettingKey.Direct(
        booleanPreferencesKey("ride_log_enabled"),
        false
    )

    /** 乗降ログの書き出し先 SAF ツリー URI（空 = 未選択） */
    val RIDE_LOG_TREE_URI = SettingKey.Direct(
        stringPreferencesKey("ride_log_tree_uri"),
        ""
    )

    /**
     * RSSI リンク品質管理（自転車レーダー gardia_radar と同一の仕組み）。
     * 通信品質に基づいて BLE 接続と切断を制御する:
     * - 接続時: スキャン発見時の RSSI が RSSI_CONNECT_THRESHOLD 未満なら接続をスキップ
     *   （遠い位置からの接続で弱いリンクに張り付くのを防ぐ・再スキャンを継続）
     * - 接続中: RSSI_READ_INTERVAL_MS ごとに readRemoteRssi() で測定し、
     *   RSSI_DISCONNECT_THRESHOLD 未満が RSSI_DISCONNECT_COUNT 回連続したら切断
     *   （スマホの置き忘れ死リンクでのバッテリー枯渇防止・切断後は自動再接続で待機）
     * RSSI_DISCONNECT_ENABLED = false で接続中の監視のみ OFF
     * （接続時フィルタはしきい値比較なので常時有効）
     */
    val RSSI_DISCONNECT_ENABLED = SettingKey.Direct(
        booleanPreferencesKey("rssi_disconnect_enabled"),
        false
    )

    val RSSI_DISCONNECT_THRESHOLD = SettingKey.Direct(
        intPreferencesKey("rssi_disconnect_threshold"),
        -90 // -90dBm
    )

    val RSSI_DISCONNECT_COUNT = SettingKey.Direct(
        intPreferencesKey("rssi_disconnect_count"),
        3 // 3回連続で切断（15秒）
    )

    val RSSI_CONNECT_THRESHOLD = SettingKey.Direct(
        intPreferencesKey("rssi_connect_threshold"),
        -80 // -80dBm（接続時のしきい値）
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
