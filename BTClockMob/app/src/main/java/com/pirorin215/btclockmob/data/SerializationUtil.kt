package com.pirorin215.btclockmob.data

import kotlinx.serialization.json.Json

/**
 * Centralized JSON serialization configuration.
 * Use this instance throughout the app for consistent JSON handling.
 */
object JsonUtil {
    /**
     * Standard JSON configuration for the app.
     * - Ignores unknown keys for forward compatibility
     * - Compact output (no pretty printing)
     * - Strict mode disabled for lenient parsing
     */
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        isLenient = true
    }

    /**
     * JSON configuration for pretty-printed output (e.g., debugging, logs).
     */
    val jsonPretty = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        isLenient = true
    }
}

/**
 * Safely decode a JSON string into a list of items.
 * Returns an empty list if decoding fails.
 */
inline fun <reified T> safeDecodeList(jsonString: String): MutableList<T> {
    return try {
        JsonUtil.json.decodeFromString<MutableList<T>>(jsonString)
    } catch (e: Exception) {
        e.printStackTrace()
        mutableListOf()
    }
}

/**
 * Safely decode a JSON string into a single object.
 * Returns null if decoding fails.
 */
inline fun <reified T> safeDecode(jsonString: String): T? {
    return try {
        JsonUtil.json.decodeFromString<T>(jsonString)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
