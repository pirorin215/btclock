package com.pirorin215.btclockmob.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pirorin215.btclockmob.data.ConnectionState
import com.pirorin215.btclockmob.data.DeviceSettings
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class BleViewModel(
    private val bleConnectionManager: BleConnectionManager,
    private val bleOrchestrator: BleOrchestrator
) : ViewModel() {

    // --- State exposed from BLE managers ---
    val connectionState: StateFlow<ConnectionState> = bleConnectionManager.connectionState
    val currentOperation: StateFlow<BleOperation> = bleOrchestrator.currentOperation
    val navigationEvent: SharedFlow<NavigationEvent> = bleOrchestrator.navigationEvent
    val deviceSettings: StateFlow<DeviceSettings> = bleOrchestrator.deviceSettings

    // --- BLE Operations ---
    suspend fun getSettings() = bleOrchestrator.getSettings()
    fun sendSettings() = bleOrchestrator.sendSettings()
    fun updateSettings(updater: (DeviceSettings) -> DeviceSettings) = bleOrchestrator.updateSettings(updater)


    fun sendCommand(command: String) = bleOrchestrator.sendCommand(command)

      fun forceReconnectBle() = bleConnectionManager.forceReconnect()

      fun disconnect() = bleConnectionManager.disconnect()
    fun close() = bleConnectionManager.close()

    override fun onCleared() {
        super.onCleared()
        // Cleanup if needed
    }
}
