package com.pirorin215.btclockmob.viewModel

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.pirorin215.btclockmob.service.BleScanService
import kotlinx.coroutines.Job
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

    // Bluetooth状態変化を監視するBroadcastReceiver
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_OFF -> {
                        logManager.addDebugLog("Bluetooth turned OFF - disconnecting and cleaning up")
                        // 接続状態を強制的に切断状態に設定
                        scope.launch {
                            disconnect()
                            repository.close()
                            _connectionStateFlow.value = ConnectionState.Disconnected
                        }
                    }
                    BluetoothAdapter.STATE_ON -> {
                        logManager.addDebugLog("Bluetooth turned ON - attempting reconnection")
                        // Bluetooth ON時に再接続を試みる
                        scope.launch {
                            delay(1000L) // Bluetoothが完全に有効になるのを待つ
                            restartScan(forceScan = true)
                        }
                    }
                }
            }
        }
    }

    // Flag to track if user initiated disconnect (power off, bike turned off, etc.)
    private var userInitiatedDisconnect = false

    // Periodic job to check BleScanService health
    private var serviceCheckJob: Job? = null

    // The internal _connectionState is removed, as we update the external _connectionStateFlow
    // private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    // val connectionState = _connectionState.asStateFlow() // No longer exposed

    init {
        // Initialize connection state to Disconnected to ensure clean state on app start
        _connectionStateFlow.value = ConnectionState.Disconnected
        logManager.addDebugLog("BleConnectionManager: Initialized with state=Disconnected")

        // Bluetooth状態変化を監視するBroadcastReceiverを登録
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(bluetoothStateReceiver, filter)

        // Collect connection state from the repository
        repository.connectionState.onEach { state ->
            logManager.addDebugLog("BleConnectionManager: Repository state changed to $state")
            // Update the external flow directly
            _connectionStateFlow.value = state

            // Handle disconnection signal
            when (state) {
                is ConnectionState.Disconnected, is ConnectionState.Error -> {
                    // For normal disconnections (power off, bike turned off), set flag to prevent reconnection
                    if (state is ConnectionState.Disconnected) {
                        userInitiatedDisconnect = true
                    }
                    scope.launch { _disconnectSignal.emit(Unit) }
                }
                else -> {}
            }

            when (state) {
                is ConnectionState.Connected -> {
                    logManager.addLog("Connected to device")
                    userInitiatedDisconnect = false // Reset flag on successful connection
                    repository.requestMtu(517) // Request larger MTU for faster transfers
                }
                is ConnectionState.Disconnected -> {
                    // Clean up resources
                    repository.disconnect()
                    repository.close()

                    // Reconnect if NOT user initiated (e.g., connection error)
                    if (!userInitiatedDisconnect) {
                        scope.launch {
                            delay(com.pirorin215.btclockmob.constants.TimeConstants.RECONNECT_DELAY_MS)
                            restartScan(forceScan = true)
                        }
                    }
                }
                is ConnectionState.Error -> {
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
            BleScanServiceManager.deviceFoundFlow.collect { device ->
                logManager.addLog("Device found: ${device.name}")
                // Use the external connection state flow to check current state
                if (_connectionStateFlow.value is ConnectionState.Disconnected) {
                    connect(device)
                } else {
                    logManager.addDebugLog("Already connected. Skipping.")
                }
            }
        }

        // Start initial BLE scan after a short delay to ensure BleScanService is ready
        // This fixes the issue where BLE auto-connection doesn't work on app startup
        scope.launch {
            delay(2000L) // Wait 2 seconds for BleScanService to be fully initialized
            logManager.addDebugLog("Starting initial BLE scan...")
            restartScan()
        }

        // Start periodic service health check (1 minute interval)
        serviceCheckJob = scope.launch {
            while (coroutineContext[Job.Key]?.isActive == true) {
                delay(com.pirorin215.btclockmob.constants.TimeConstants.SERVICE_CHECK_INTERVAL_MS)
                checkAndRestartBleScanService()
            }
        }
        logManager.addDebugLog("Service health check job started (interval: ${com.pirorin215.btclockmob.constants.TimeConstants.SERVICE_CHECK_INTERVAL_MS}ms)")
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
        userInitiatedDisconnect = true // Set flag to prevent reconnection
        repository.disconnect()
    }

    fun forceReconnect() {
        logManager.addLog("Force reconnect")
        userInitiatedDisconnect = false // Reset flag to allow reconnection
        scope.launch {
            disconnect()
            delay(500L) // Give a short delay for the stack to clear
            restartScan(forceScan = true)
        }
    }

    fun close() {
        serviceCheckJob?.cancel()
        serviceCheckJob = null
        repository.close()
        // BroadcastReceiverの登録解除
        try {
            context.unregisterReceiver(bluetoothStateReceiver)
        } catch (e: IllegalArgumentException) {
            logManager.addDebugLog("BluetoothStateReceiver was not registered")
        }
        logManager.addDebugLog("Connection manager closed")
    }

    /**
     * Check if BleScanService is running and restart if necessary
     * This ensures the service survives battery optimization, crashes, or system kills
     */
    @Suppress("DEPRECATION")
    private fun checkAndRestartBleScanService() {
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val serviceName = BleScanService::class.java.name

            // Check if service is running (API 26+ uses getRunningServices with restrictions)
            val isRunning = activityManager.getRunningServices(Integer.MAX_VALUE).any { serviceInfo ->
                serviceInfo.service.className == serviceName
            }

            if (!isRunning) {
                logManager.addLog("BleScanService is not running. Restarting...", LogLevel.ERROR)
                val serviceIntent = Intent(context, BleScanService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                logManager.addDebugLog("BleScanService restart command sent")
            } else {
                logManager.addDebugLog("Service health check: BleScanService is running")
            }
        } catch (e: SecurityException) {
            logManager.addDebugLog("Service check failed: Permission denied (may be normal on some Android versions)")
        } catch (e: Exception) {
            logManager.addDebugLog("Service check failed: ${e.message}")
        }
    }
}
