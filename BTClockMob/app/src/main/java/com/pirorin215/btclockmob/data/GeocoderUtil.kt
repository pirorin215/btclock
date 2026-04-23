package com.pirorin215.btclockmob.data

import android.content.Context
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

object GeocoderUtil {
    /**
     * Convert latitude and longitude to a readable address string.
     * Returns a string like "東京都港区六本木" or null if not found.
     */
    suspend fun getAddressFromLocation(context: Context, latitude: Double, longitude: Double): String? = withContext(Dispatchers.IO) {
        try {
            val geocoder = Geocoder(context, Locale.getDefault())
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // New API for API 33+ (Tiramisu)
                val addresses = suspendCancellableCoroutine<List<android.location.Address>?> { continuation ->
                    geocoder.getFromLocation(latitude, longitude, 1) { addresses ->
                        continuation.resume(addresses)
                    }
                }
                formatAddress(addresses?.firstOrNull())
            } else {
                // Legacy API
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                formatAddress(addresses?.firstOrNull())
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun formatAddress(address: android.location.Address?): String? {
        if (address == null) return null
        
        // getAddressLine(0) は「日本、〒305-0005 茨城県つくば市天久保２丁目１−１」のようなフルセットを返す
        val fullAddress = address.getAddressLine(0) ?: return address.featureName
        
        // 表示をスッキリさせるため、国名と郵便番号を削る
        return fullAddress
            .replace("日本、", "")
            .replace("日本", "")
            .replace(Regex("〒\\d{3}-\\d{4}\\s*"), "")
            .trim()
    }
}
