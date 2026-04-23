package com.pirorin215.btclockmob.viewModel

import android.content.Context
import com.pirorin215.btclockmob.LocationData
import com.pirorin215.btclockmob.LocationTracker
import com.pirorin215.btclockmob.data.LastKnownLocationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LocationMonitor(
    context: Context,
    private val scope: CoroutineScope,
    private val lastKnownLocationRepository: LastKnownLocationRepository,
    private val logManager: LogManager
) {
    private val locationTracker = LocationTracker(context)

    private val _currentForegroundLocation = MutableStateFlow<LocationData?>(null)
    val currentForegroundLocation = _currentForegroundLocation.asStateFlow()

    private var lowPowerLocationJob: Job? = null

    fun startLowPowerLocationUpdates() {
        if (lowPowerLocationJob?.isActive == true) {
            logManager.addDebugLog("Location updates already active")
            return
        }
        logManager.addLog("Starting location updates")
        lowPowerLocationJob = scope.launch {
            while (true) {
                locationTracker.getLowPowerLocation().onSuccess { locationData ->
                    _currentForegroundLocation.value = locationData
                    logManager.addDebugLog("Location updated: Lat=${locationData.latitude}, Lng=${locationData.longitude}")
                }.onFailure { e ->
                    _currentForegroundLocation.value = null // Clear stale location on failure
                    logManager.addDebugLog("Location update failed: ${e.message}")
                }
                delay(30000L) // Update every 30 seconds
            }
        }
    }

    private suspend fun saveCurrentLocationAsLastKnown() {
        locationTracker.getCurrentLocation().onSuccess { locationData ->
            lastKnownLocationRepository.saveLastKnownLocation(locationData)
            logManager.addDebugLog("Saved location")
        }.onFailure { e ->
            logManager.addDebugLog("Failed to save location: ${e.message}")
        }
    }

    suspend fun updateLocation() {
        logManager.addDebugLog("Updating location")
        saveCurrentLocationAsLastKnown()
    }

    fun stopLowPowerLocationUpdates() {
        lowPowerLocationJob?.cancel()
        lowPowerLocationJob = null
        _currentForegroundLocation.value = null // Clear location when stopping
        logManager.addLog("Stopped location updates")
    }

    fun onCleared() {
        stopLowPowerLocationUpdates()
    }
}
