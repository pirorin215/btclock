package com.pirorin215.btclockmob.viewModel

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.pirorin215.btclockmob.BleScanServiceManager
import com.pirorin215.btclockmob.data.BleRepository
import com.pirorin215.btclockmob.data.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

import kotlinx.coroutines.flow.MutableSharedFlow // Add this import
import com.pirorin215.btclockmob.constants.TimeConstants

@SuppressLint("MissingPermission")
class BleConnectionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: BleRepository,
    private val logManager: LogManager,
    // These flows are now mutable and passed in from the ViewModel/Activity
    private val _connectionStateFlow: MutableStateFlow<ConnectionState>,
    private val _onDeviceReadyEvent: MutableSharedFlow<Unit>,
    private val _disconnectSignal: MutableSharedFlow<Unit>
) {

    val connectionState = _connectionStateFlow.asStateFlow()

    companion object {
        const val DEVICE_NAME = "BikeClock-0001"
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    // The internal _connectionState is removed, as we update the external _connectionStateFlow
    // private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    // val connectionState = _connectionState.asStateFlow() // No longer exposed

    init {
        // Initialize connection state to Disconnected to ensure clean state on app start
        _connectionStateFlow.value = ConnectionState.Disconnected
        logManager.addDebugLog("BleConnectionManager: Initialized with state=Disconnected")

        // Collect connection state from the repository
        repository.connectionState.onEach { state ->
            logManager.addDebugLog("BleConnectionManager: Repository state changed to $state")
            // Update the external flow directly
            _connectionStateFlow.value = state

            // Handle disconnection signal
            when (state) {
                is ConnectionState.Disconnected, is ConnectionState.Error -> {
                    scope.launch { _disconnectSignal.emit(Unit) }
                }
                else -> {}
            }

            when (state) {
                is ConnectionState.Connected -> {
                    logManager.addLog("Connected to device")
                    repository.requestMtu(517) // Request larger MTU for faster transfers
                }
                is ConnectionState.Disconnected -> {
                    logManager.addLog("Disconnected from device")
                }
                is ConnectionState.Error -> {
                    logManager.addLog("Connection error: ${state.message}", LogLevel.ERROR)
                    // Ensure full disconnection and cleanup
                    repository.disconnect()
                    repository.close()

                    // 【設計意図】デバイスのディープスリープ復帰を待つため、永続的に再接続を試みる
                    // - 500ms待機: disconnect()/close()のBluetoothスタックのクリーンアップ完了を待つ（必須）
                    // - その後connect()を呼ぶが、GATT接続タイムアウト（Androidシステムレベルで約30秒）まで待機
                    // - 実質的な再試行間隔: 約30秒（高頻度リトライではない）
                    // - バッテリー消費: 許容範囲内
                    scope.launch {
                        delay(com.pirorin215.btclockmob.constants.TimeConstants.RECONNECT_DELAY_MS)
                        logManager.addDebugLog("Attempting reconnection...")
                        restartScan(forceScan = true)
                    }
                }
                is ConnectionState.Pairing -> logManager.addDebugLog("Pairing with device...")
                is ConnectionState.Paired -> logManager.addDebugLog("Device paired. Connecting...")
            }
        }.launchIn(scope)

        // Collect events from the repository
        repository.events.onEach { event ->
            when (event) {
                is com.pirorin215.btclockmob.data.BleEvent.MtuChanged -> {
                    logManager.addDebugLog("MTU changed to ${event.mtu}")
                }
                is com.pirorin215.btclockmob.data.BleEvent.Ready -> {
                    logManager.addLog("Device ready")
                    logManager.addDebugLog("BleEvent.Ready: Emitting _onDeviceReadyEvent...")
                    repository.requestHighPriorityConnection() // Request faster connection interval
                    _onDeviceReadyEvent.emit(Unit) // Emit event to the external flow
                    logManager.addDebugLog("BleEvent.Ready: _onDeviceReadyEvent emitted successfully")
                }
                // Characteristic changes are handled by the viewmodel that owns the operation (BleOrchestrator)
                else -> { /* Other events can be handled here if needed */ }
            }
        }.launchIn(scope)

        // Listen for devices found by the background scanning service
        scope.launch {
            BleScanServiceManager.deviceFoundFlow.onEach { device ->
                logManager.addLog("Device found: ${device.name}")
                // Use the external connection state flow to check current state
                if (_connectionStateFlow.value is ConnectionState.Disconnected) {
                    connect(device)
                } else {
                    logManager.addDebugLog("Already connected. Skipping.")
                }
            }.launchIn(this)
        }
    }

    fun startScan() {
        logManager.addDebugLog("Manual scan initiated")
        // The actual scan is handled by BleScanService, triggered via UI/ViewModel.
        // This manager listens to the results via BleScanServiceManager.
    }

    fun restartScan(forceScan: Boolean = false) {
        if (!forceScan && _connectionStateFlow.value !is ConnectionState.Disconnected) {
            logManager.addDebugLog("Scan skipped: already connected")
            return
        }

        // 1. Try to connect to a bonded device first
        val bondedDevices = bluetoothAdapter?.bondedDevices
        val bondedBTDevice = bondedDevices?.find { it.name.equals(DEVICE_NAME, ignoreCase = true) }

        if (bondedBTDevice != null) {
            logManager.addDebugLog("Attempting bonded device connection")
            connect(bondedBTDevice)
        } else {
            // 2. If no bonded device is found, start a new scan via the service
            logManager.addDebugLog("Requesting new scan")
            scope.launch {
                BleScanServiceManager.emitRestartScan()
            }
        }
    }

    fun connect(device: BluetoothDevice) {
        logManager.addDebugLog("Connecting to device ${device.address}")
        repository.connect(device)
    }

    fun disconnect() {
        logManager.addDebugLog("Disconnect requested")
        repository.disconnect()
    }

    fun forceReconnect() {
        logManager.addLog("Force reconnect")
        scope.launch {
            disconnect()
            delay(500L) // Give a short delay for the stack to clear
            restartScan(forceScan = true)
        }
    }

    fun close() {
        repository.close()
        logManager.addDebugLog("Connection manager closed")
    }
}
