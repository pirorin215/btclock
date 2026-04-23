package com.pirorin215.btclockmob.viewModel

import com.pirorin215.btclockmob.LocationData
import kotlinx.coroutines.flow.StateFlow

interface LocationTracking {
    val currentForegroundLocation: StateFlow<LocationData?>
    fun startLowPowerLocationUpdates()
    fun stopLowPowerLocationUpdates()
    suspend fun updateLocation()
    fun onCleared()
}
