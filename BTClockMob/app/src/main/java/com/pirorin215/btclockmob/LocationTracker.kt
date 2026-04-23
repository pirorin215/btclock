package com.pirorin215.btclockmob

import kotlinx.serialization.Serializable
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await

@Serializable
data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long
)

class LocationTracker(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    companion object {
        private const val TAG = "LocationTracker"
    }

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): Result<LocationData> {
        if (!hasLocationPermission()) {
            return Result.failure(SecurityException("Location permission not granted"))
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)

        if (!isGpsEnabled && !isNetworkEnabled) {
            return Result.failure(IllegalStateException("Location services are disabled."))
        }

        val cancellationTokenSource = CancellationTokenSource()
        return try {
            // Use the modern getCurrentLocation API which is Coroutine-friendly
            val location = fusedLocationClient.getCurrentLocation(
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                cancellationTokenSource.token
            ).await()

            if (location != null) {
                Result.success(LocationData(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    timestamp = location.time
                ))
            } else {
                Result.failure(Exception("Failed to get location: FusedLocationProviderClient returned null."))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Location error: ${e.message}", e)
            Result.failure(e)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun getLowPowerLocation(): Result<LocationData> {
        if (!hasLocationPermission()) {
            return Result.failure(SecurityException("Location permission not granted"))
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)

        if (!isGpsEnabled && !isNetworkEnabled) {
            return Result.failure(IllegalStateException("Location services are disabled."))
        }

        val cancellationTokenSource = CancellationTokenSource()
        return try {
            val location = fusedLocationClient.getCurrentLocation(
                com.google.android.gms.location.Priority.PRIORITY_LOW_POWER,
                cancellationTokenSource.token
            ).await()

            if (location != null) {
                Result.success(LocationData(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    timestamp = location.time
                ))
            } else {
                Result.failure(Exception("Failed to get low power location: FusedLocationProviderClient returned null."))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Low power location error: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}