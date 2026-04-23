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
 * Transcription provider options
 */
enum class TranscriptionProvider {
    GOOGLE,
    GROQ;

    companion object {
        fun fromString(value: String?): TranscriptionProvider {
            return try {
                valueOf(value ?: "GOOGLE")
            } catch (e: IllegalArgumentException) {
                GOOGLE
            }
        }
    }
}

/**
 * AI provider options for generating AI responses
 */
enum class AIProvider {
    GEMINI,
    GROQ;

    companion object {
        fun fromString(value: String?): AIProvider {
            return try {
                valueOf(value ?: "GEMINI")
            } catch (e: IllegalArgumentException) {
                GEMINI
            }
        }
    }
}

/**
 * Provider mode options - simplified choice between GCP and Groq
 * When GCP is selected: transcription uses Google, AI uses Gemini
 * When Groq is selected: both transcription and AI use Groq
 */
enum class ProviderMode {
    GCP,
    GROQ;

    companion object {
        fun fromString(value: String?): ProviderMode {
            return try {
                valueOf(value ?: "GCP")
            } catch (e: IllegalArgumentException) {
                GCP
            }
        }
    }
}

/**
 * Google Tasks sync mode options
 * When OAUTH is selected: use Google Sign-In for Tasks API access
 * When GAS is selected: use Google Apps Script webhook for Tasks API access
 */

/**
 * Gemini model configuration for AI response generation
 * @property version Model version (e.g., 2.0, 2.5, 3.0)
 * @property hasFlash Whether to include "flash" in the model name
 * @property hasLite Whether to include "lite" in the model name
 */
data class GeminiModel(
    val version: Float = 2.0f,
    val hasFlash: Boolean = true,
    val hasLite: Boolean = false
) {
    /**
     * Generates the model name based on configuration
     * Examples:
     * - version=2.0, hasFlash=true, hasLite=false -> "gemini-2.0-flash"
     * - version=2.0, hasFlash=true, hasLite=true -> "gemini-2.0-flash-lite"
     * - version=2.5, hasFlash=false, hasLite=false -> "gemini-2.5"
     */
    val modelName: String
        get() = buildString {
            append("gemini-")
            append(version)
            if (hasFlash) append("-flash")
            if (hasLite) append("-lite")
        }

    companion object {
        /**
         * Parse from stored string format "version:hasFlash:hasLite"
         */
        fun fromString(value: String?): GeminiModel {
            if (value.isNullOrBlank()) return GeminiModel()
            return try {
                val parts = value.split(":")
                GeminiModel(
                    version = parts.getOrNull(0)?.toFloatOrNull() ?: 2.0f,
                    hasFlash = parts.getOrNull(1)?.toBoolean() ?: true,
                    hasLite = parts.getOrNull(2)?.toBoolean() ?: false
                )
            } catch (e: Exception) {
                GeminiModel()
            }
        }
    }

    /**
     * Convert to storable string format "version:hasFlash:hasLite"
     */
    fun toStorageString(): String {
        return "$version:$hasFlash:$hasLite"
    }
}

/**
 * Default system prompt for AI assistants in this app
 * Used when no custom prompt is configured
 */
const val DEFAULT_AI_SYSTEM_PROMPT = "応答は結論のみで倒置法にしてください。"

/**
 * Centralized definition of all app settings
 */
object Settings {
    val API_KEY = SettingKey.Direct(
        stringPreferencesKey("google_cloud_api_key"),
        ""
    )

    val GROQ_API_KEY = SettingKey.Direct(
        stringPreferencesKey("groq_api_key"),
        ""
    )

    val TRANSCRIPTION_PROVIDER = SettingKey.Mapped(
        preferencesKey = stringPreferencesKey("transcription_provider"),
        defaultValue = TranscriptionProvider.GOOGLE,
        toStored = { it.name },
        fromStored = { TranscriptionProvider.fromString(it) }
    )

    val AI_PROVIDER = SettingKey.Mapped(
        preferencesKey = stringPreferencesKey("ai_provider"),
        defaultValue = AIProvider.GEMINI,
        toStored = { it.name },
        fromStored = { AIProvider.fromString(it) }
    )

    val PROVIDER_MODE = SettingKey.Mapped(
        preferencesKey = stringPreferencesKey("provider_mode"),
        defaultValue = ProviderMode.GCP,
        toStored = { it.name },
        fromStored = { ProviderMode.fromString(it) }
    )

    val GEMINI_API_KEY = SettingKey.Direct(
        stringPreferencesKey("gemini_api_key"),
        ""
    )

    val GEMINI_MODEL = SettingKey.Mapped(
        preferencesKey = stringPreferencesKey("gemini_model"),
        defaultValue = GeminiModel(),
        toStored = { it.toStorageString() },
        fromStored = { GeminiModel.fromString(it) }
    )

    val GEMINI_SYSTEM_PROMPT = SettingKey.Direct(
        stringPreferencesKey("gemini_system_prompt"),
        DEFAULT_AI_SYSTEM_PROMPT
    )

    val TRANSCRIPTION_CACHE_LIMIT = SettingKey.Direct(
        intPreferencesKey("transcription_cache_limit"),
        100
    )

    val AUDIO_DIR_NAME = SettingKey.Direct(
        stringPreferencesKey("audio_dir_name"),
        "BTClockRecordings"
    )

    val TRANSCRIPTION_FONT_SIZE = SettingKey.Direct(
        intPreferencesKey("transcription_font_size"),
        14
    )

    val THEME_MODE = SettingKey.Mapped(
        preferencesKey = stringPreferencesKey("theme_mode"),
        defaultValue = ThemeMode.SYSTEM,
        toStored = { it.name },
        fromStored = { ThemeMode.valueOf(it) }
    )

    val IS_API_KEY_VERIFIED = SettingKey.Direct(
        booleanPreferencesKey("is_api_key_verified"),
        false
    )

    // Google Tasks settings removed - feature disabled

    val AUTO_START_ON_BOOT = SettingKey.Direct(
        booleanPreferencesKey("auto_start_on_boot"),
        false
    )

    val CHUNK_BURST_SIZE = SettingKey.Direct(
        intPreferencesKey("chunk_burst_size"),
        8
    )

    val VOLTAGE_RETRY_COUNT = SettingKey.Direct(
        intPreferencesKey("voltage_retry_count"),
        5
    )

    val VOLTAGE_ACQUISITION_INTERVAL = SettingKey.Direct(
        intPreferencesKey("voltage_acquisition_interval"),
        1000
    )

    val LOW_VOLTAGE_THRESHOLD = SettingKey.Direct(
        floatPreferencesKey("low_voltage_threshold"),
        3.61f
    )

    val LOW_VOLTAGE_NOTIFY_EVERY_TIME = SettingKey.Direct(
        booleanPreferencesKey("low_voltage_notify_every_time"),
        false
    )

    val TRANSCRIPTION_NOTIFICATION_ENABLED = SettingKey.Direct(
        booleanPreferencesKey("transcription_notification_enabled"),
        false
    )

    val GAS_WEBHOOK_URL = SettingKey.Direct(
        stringPreferencesKey("gas_webhook_url"),
        ""
    )

    val ENABLE_GOOGLE_TASK_DUE = SettingKey.Direct(
        booleanPreferencesKey("enable_google_task_due"),
        true // Default to true (enable due date)
    )

    val GEMINI_ENABLE_GOOGLE_SEARCH = SettingKey.Direct(
        booleanPreferencesKey("gemini_enable_google_search"),
        true // Default to true (enable Google Search Grounding)
    )
}

class AppSettingsRepository(private val context: Context) {

    /**
     * Generic method to get a Flow for any setting
     *
     * Usage example:
     * ```kotlin
     * val apiKeyFlow = repository.getFlow(Settings.API_KEY)
     * val chunkSizeFlow = repository.getFlow(Settings.CHUNK_BURST_SIZE)
     * ```
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
     *
     * Usage example:
     * ```kotlin
     * repository.setValue(Settings.API_KEY, "new-api-key")
     * repository.setValue(Settings.CHUNK_BURST_SIZE, 16)
     * ```
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
