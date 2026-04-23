package com.pirorin215.btclockmob.viewModel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pirorin215.btclockmob.BleScanServiceManager
import com.pirorin215.btclockmob.service.BleScanService
import com.pirorin215.btclockmob.data.ConnectionState
import com.pirorin215.btclockmob.data.Settings
import com.pirorin215.btclockmob.data.ThemeMode
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
class MainViewModel(
    private val application: Application,
    private val bleConnectionManager: BleConnectionManager,
    private val bleOrchestrator: BleOrchestrator,
    private val bleSelectionManager: BleSelectionManager,
    private val locationMonitor: LocationMonitor,
    private val logManager: LogManager,
    private val appSettingsRepository: com.pirorin215.btclockmob.data.AppSettingsRepository
) : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
    }

    // --- UI State Flows ---
    val themeMode: StateFlow<ThemeMode> = appSettingsRepository.getFlow(Settings.THEME_MODE)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.SYSTEM)

    val logs = logManager.logs

    // --- State exposed from orchestrator and managers ---
    val connectionState: StateFlow<ConnectionState> = bleConnectionManager.connectionState
    val currentOperation: StateFlow<BleOperation> = bleOrchestrator.currentOperation
    val navigationEvent: SharedFlow<NavigationEvent> = bleOrchestrator.navigationEvent
    val deviceInfo: StateFlow<com.pirorin215.btclockmob.data.DeviceInfoResponse?> = bleOrchestrator.deviceInfo
    val deviceSettings: StateFlow<com.pirorin215.btclockmob.data.DeviceSettings> = bleOrchestrator.deviceSettings
    val currentForegroundLocation = locationMonitor.currentForegroundLocation

    // --- Methods delegated to orchestrator and managers ---
    // fun fetchFileList(extension: String = "wav") = bleOrchestrator.fetchFileList(extension) // File transfer feature disabled
    suspend fun getSettings() = bleOrchestrator.getSettings()
    fun sendSettings() = bleOrchestrator.sendSettings()
    fun updateSettings(updater: (com.pirorin215.btclockmob.data.DeviceSettings) -> com.pirorin215.btclockmob.data.DeviceSettings) = bleOrchestrator.updateSettings(updater)
    fun sendCommand(command: String) = bleOrchestrator.sendCommand(command)
    fun clearLogs() = logManager.clearLogs()
    fun forceReconnectBle() = bleConnectionManager.forceReconnect()
    // fun toggleSelection(fileName: String) = bleSelectionManager.toggleSelection(fileName) // File transfer feature disabled
    // fun clearSelection() = bleSelectionManager.clearSelection() // File transfer feature disabled

    // --- Location Monitor Delegation ---
    fun startLowPowerLocationUpdates() = locationMonitor.startLowPowerLocationUpdates()
    fun stopLowPowerLocationUpdates() = locationMonitor.stopLowPowerLocationUpdates()

    fun stopAppServices() {
        Log.d(TAG, "Stopping all app services...")
        // Stop BLE connection and release resources
        bleConnectionManager.disconnect()
        bleConnectionManager.close()

        // Stop the background BLE scanning service
        val serviceIntent = Intent(application, BleScanService::class.java)
        application.stopService(serviceIntent)
        Log.d(TAG, "BleScanService stopped.")

        // Stop location updates
        locationMonitor.stopLowPowerLocationUpdates()
        Log.d(TAG, "Location updates stopped.")

        logManager.addLog("All services stopped. App is shutting down.")
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel cleared.")
    }
}