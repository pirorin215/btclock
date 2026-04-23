package com.pirorin215.btclockmob.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pirorin215.btclockmob.data.ConnectionState
import com.pirorin215.btclockmob.data.DeviceInfoResponse
import com.pirorin215.btclockmob.data.DeviceSettings
import com.pirorin215.btclockmob.data.FileEntry
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class BleViewModel(
    private val bleConnectionManager: BleConnectionManager,
    private val bleOrchestrator: BleOrchestrator,
    private val bleSelectionManager: BleSelectionManager
) : ViewModel() {

    // --- State exposed from BLE managers ---
    val connectionState: StateFlow<ConnectionState> = bleConnectionManager.connectionState
    val currentOperation: StateFlow<BleOperation> = bleOrchestrator.currentOperation
    val navigationEvent: SharedFlow<NavigationEvent> = bleOrchestrator.navigationEvent
    val deviceInfo: StateFlow<DeviceInfoResponse?> = bleOrchestrator.deviceInfo
    val deviceSettings: StateFlow<DeviceSettings> = bleOrchestrator.deviceSettings
    val selectedFileNames = bleSelectionManager.selectedFileNames

    // --- BLE Operations ---
    // fun fetchFileList(extension: String = "wav") = bleOrchestrator.fetchFileList(extension) // File transfer feature disabled

    suspend fun getSettings() = bleOrchestrator.getSettings()

    fun sendSettings() = bleOrchestrator.sendSettings()

    fun updateSettings(updater: (DeviceSettings) -> DeviceSettings) =
        bleOrchestrator.updateSettings(updater)

    fun sendCommand(command: String) = bleOrchestrator.sendCommand(command)

    fun forceReconnectBle() = bleConnectionManager.forceReconnect()

    fun toggleSelection(fileName: String) = bleSelectionManager.toggleSelection(fileName)

    fun clearSelection() = bleSelectionManager.clearSelection()

    fun disconnect() = bleConnectionManager.disconnect()

    fun close() = bleConnectionManager.close()

    override fun onCleared() {
        super.onCleared()
        // Cleanup if needed
    }
}
