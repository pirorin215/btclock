package com.pirorin215.btclockmob.viewModel

import android.content.Context
import android.util.Log
import com.pirorin215.btclockmob.data.BleRepository
import com.pirorin215.btclockmob.data.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import android.bluetooth.BluetoothGattCharacteristic
import com.pirorin215.btclockmob.viewModel.LocationMonitor
import com.pirorin215.btclockmob.data.AppSettingsRepository
import com.pirorin215.btclockmob.data.Settings
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import com.pirorin215.btclockmob.data.DeviceSettings
import com.pirorin215.btclockmob.constants.TimeConstants

import com.pirorin215.btclockmob.viewModel.LogManager
import com.pirorin215.btclockmob.LocationTracker
import com.pirorin215.btclockmob.data.DeviceHistoryRepository
import com.pirorin215.btclockmob.bluetooth.constants.BleConstants
import com.pirorin215.btclockmob.bluetooth.device.BleDeviceManager
import com.pirorin215.btclockmob.bluetooth.settings.BleSettingsManager
import com.pirorin215.btclockmob.viewModel.NavigationEvent

class BleOrchestrator(
    private val scope: CoroutineScope,
    private val context: Context,
    private val repository: BleRepository,
    private val connectionStateFlow: StateFlow<ConnectionState>,
    private val onDeviceReadyEvent: SharedFlow<Unit>,
    private val locationMonitor: LocationMonitor,
    private val appSettingsRepository: AppSettingsRepository,
    private val logManager: LogManager,
    private val disconnectSignal: SharedFlow<Unit>,
    private val locationTracker: LocationTracker,
    private val deviceHistoryRepository: DeviceHistoryRepository
) {
    companion object {
        const val TAG = "BleOrchestrator"

    }

    internal val _currentOperation = MutableStateFlow(BleOperation.IDLE)
    val currentOperation = _currentOperation.asStateFlow()

    /**
     * Primary mutex for all BLE operations
     *
     * USAGE RULES:
     * - Protects all BLE communication (commands, file transfers, settings)
     * - Shared with BleDeviceCommandManager and FileTransferManager
     * - Always use withLock { } to ensure proper release
     * - NEVER nest with other mutexes to avoid deadlocks
     *
     * DEADLOCK PREVENTION:
     * - This is the ONLY mutex for BLE operations
     * - File processing uses AtomicBoolean (isProcessingFiles) instead of mutex
     */
    internal val bleMutex = Mutex()

    private val _navigationEvent = MutableSharedFlow<NavigationEvent>()
    val navigationEvent = _navigationEvent.asSharedFlow()

    // デバイスマネージャーの初期化
    internal val bleDeviceManager by lazy {
        BleDeviceManager(
            scope = scope,
            sendCommand = { command -> sendCommand(command) },
            logManager = logManager,
            _currentOperation = _currentOperation,
            bleMutex = bleMutex
        )
    }

    // 設定マネージャーの初期化
    private val bleSettingsManager by lazy {
        BleSettingsManager(
            scope = scope,
            sendCommand = { command -> sendCommand(command) },
            logManager = logManager,
            _currentOperation = _currentOperation,
            bleMutex = bleMutex,
            _navigationEvent = _navigationEvent
        )
    }

    // --- マネージャーの委譲プロパティ ---
    val deviceSettings: StateFlow<com.pirorin215.btclockmob.data.DeviceSettings> get() = bleSettingsManager.deviceSettings


    // Periodic time sync job for BikeClock (1 minute interval)
    private var periodicTimeSyncJob: Job? = null

    // Periodic history recording job (checks every 1 minute)
    private var periodicHistoryJob: Job? = null
    private var lastRecordedLocation: android.location.Location? = null
    private var lastRecordedTime: Long = System.currentTimeMillis()
    
    // 最後に接続が確認された地点（切断時の位置特定用）
    private var lastActiveLocation: android.location.Location? = null

    // 最後に正常に接続していたかどうかのフラグ（再接続試行中の切断イベントを無視するため）
    private var wasConnected = false

    init {
        // Monitor disconnect signal
        disconnectSignal
            .onEach {
                addDebugLog("BLE disconnected")
                // 停止: 定期履歴記録
                stopPeriodicHistoryRecording()
                // 保存: 切断時（直前が接続状態だった場合のみ）
                if (wasConnected) {
                    saveConnectionHistory(isDisconnection = true)
                    wasConnected = false
                } else {
                    addDebugLog("Skipping disconnect history: not previously connected")
                }
            }
            .launchIn(scope)

        onDeviceReadyEvent
            .onEach {
                addLog("Starting initial sync")
                startFullSync()
                // Start periodic time sync job
                startPeriodicTimeSync()
                // 接続状態をマーク
                wasConnected = true
                // 保存: 接続時
                saveConnectionHistory(isDisconnection = false)
                // 開始: 定期履歴記録
                startPeriodicHistoryRecording()
            }
            .launchIn(scope)

        repository.events.onEach { event ->
            when(event) {
                is com.pirorin215.btclockmob.data.BleEvent.CharacteristicChanged -> {
                    handleCharacteristicChanged(event.characteristic, event.value)
                }
                else -> {}
            }
        }.launchIn(scope)
    }

    fun stop() {
        bleDeviceManager.stopTimeSyncJob()
        periodicTimeSyncJob?.cancel()
        periodicTimeSyncJob = null
        stopPeriodicHistoryRecording()
        addLog("オーケストレーターを停止しました")
    }

    fun addLog(message: String, level: LogLevel = LogLevel.INFO) {
        logManager.addLog(message, level)
    }

    fun addDebugLog(message: String) {
        logManager.addDebugLog(message)
    }

    fun clearLogs() {
        logManager.clearLogs()
        logManager.addLog("Logs cleared")
    }

    private fun startFullSync() {
        scope.launch {
            // BikeClockは時刻同期のみサポート
            addLog("時刻を同期中")
            val success = bleDeviceManager.syncTime(connectionStateFlow.value)

            if (!success) {
                addLog("時刻同期に失敗しました", LogLevel.ERROR)
            } else {
                addLog("時刻同期完了")
            }
        }
    }

    /**
     * Start periodic time sync job (1 minute interval)
     * BikeClock requires frequent time sync due to clock drift
     */
    private fun startPeriodicTimeSync() {
        // Cancel existing job if any
        periodicTimeSyncJob?.cancel()

        periodicTimeSyncJob = scope.launch {
            while (coroutineContext[Job.Key]?.isActive == true) {
                delay(TimeConstants.TIME_SYNC_INTERVAL_MS) // 1 minute

                // Only sync if device is connected
                if (connectionStateFlow.value is ConnectionState.Connected) {
                    addDebugLog("Starting periodic time sync")
                    val success = bleDeviceManager.syncTime(connectionStateFlow.value)
                    if (success) {
                        // 通信成功＝生存確認。位置をキャッシュしておく
                        locationTracker.getCurrentLocation().onSuccess { data ->
                            lastActiveLocation = android.location.Location("").apply {
                                latitude = data.latitude
                                longitude = data.longitude
                            }
                        }
                    } else {
                        addDebugLog("Periodic time sync failed")
                    }
                }
            }
        }
        addDebugLog("Periodic time sync job started (interval: ${TimeConstants.TIME_SYNC_INTERVAL_MS}ms)")
    }

    private fun performPostTransferSync() {
        scope.launch {
            addDebugLog("同期処理...")

            // BikeClockは時刻同期のみサポート
            val timeSyncSuccess = bleDeviceManager.syncTime(connectionStateFlow.value)
            if (!timeSyncSuccess) {
                addLog("時刻同期に失敗しました", LogLevel.ERROR)
                return@launch
            }

            addDebugLog("時刻同期完了")
        }
    }


    private fun handleCharacteristicChanged(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        // Check if this is the command characteristic (bidirectional)
        if (characteristic.uuid != UUID.fromString(BleRepository.COMMAND_UUID_STRING)) return

        when (_currentOperation.value) {
            BleOperation.SENDING_TIME -> {
                bleDeviceManager.handleResponse(value, _currentOperation.value)
            }
            BleOperation.FETCHING_SETTINGS, BleOperation.SENDING_SETTINGS -> {
                bleSettingsManager.handleResponse(value, _currentOperation.value)
            }
            else -> {
                addLog("Received data in unexpected state (${_currentOperation.value}): ${value.toString(Charsets.UTF_8)}")
            }
        }
    }

    fun sendCommand(command: String) {
        addLog("Sending command: $command")
        repository.sendCommand(command)
    }

    private fun sendAck(ackValue: ByteArray) {
        repository.sendAck(ackValue)
    }

    suspend fun getSettings() {
        bleSettingsManager.getSettings(connectionStateFlow.value)
    }

    fun sendSettings() {
        bleSettingsManager.sendSettings(connectionStateFlow.value)
    }

    fun updateSettings(updater: (com.pirorin215.btclockmob.data.DeviceSettings) -> com.pirorin215.btclockmob.data.DeviceSettings) {
        bleSettingsManager.updateSettings(updater)
    }

    /**
     * 定期的な履歴記録を開始する
     * 接続中、1km移動するか15分経過するごとに履歴を保存する
     */
    private fun startPeriodicHistoryRecording() {
        periodicHistoryJob?.cancel()
        // 最後に記録した時間を現在時刻でリセット
        lastRecordedTime = System.currentTimeMillis()
        
        periodicHistoryJob = scope.launch {
            // ジョブが有効かつ、デバイスが接続されている間のみループ
            while (coroutineContext[Job.Key]?.isActive == true && 
                   connectionStateFlow.value is ConnectionState.Connected) {
                
                delay(60000L) // 1分ごとにチェック

                // delay中に切断された可能性を考慮し、再度チェック
                if (connectionStateFlow.value !is ConnectionState.Connected) break

                val currentTime = System.currentTimeMillis()
                val timeElapsedMs = currentTime - lastRecordedTime

                // 設定値を取得
                val intervalMin = appSettingsRepository.getFlow(Settings.HISTORY_INTERVAL_MIN).first()
                val distanceM = appSettingsRepository.getFlow(Settings.HISTORY_DISTANCE_M).first()

                val intervalMs = intervalMin.toLong() * 60 * 1000

                val locationResult = locationTracker.getCurrentLocation()
                val currentLocationData = locationResult.getOrNull()

                var shouldSave = false

                // 経過時間チェック
                if (timeElapsedMs >= intervalMs) {
                    addDebugLog("Periodic history: $intervalMin minutes elapsed. Saving...")
                    shouldSave = true
                }
                // 移動距離チェック
                else if (currentLocationData != null && lastRecordedLocation != null) {
                    val currentLoc = android.location.Location("").apply {
                        latitude = currentLocationData.latitude
                        longitude = currentLocationData.longitude
                    }
                    val distance = currentLoc.distanceTo(lastRecordedLocation!!)
                    if (distance >= distanceM.toFloat()) {
                        addDebugLog("Periodic history: Moved ${distance}m (>= ${distanceM}m). Saving...")
                        shouldSave = true
                    }
                }

                if (shouldSave) {
                    saveConnectionHistory(isDisconnection = false, isPeriodic = true)
                }
            }
        }
        addDebugLog("Periodic history recording job started")
    }

    private fun stopPeriodicHistoryRecording() {
        periodicHistoryJob?.cancel()
        periodicHistoryJob = null
        lastRecordedLocation = null
        lastRecordedTime = 0L
        lastActiveLocation = null
        addDebugLog("Periodic history recording job stopped")
    }

    /**
     * 現在の位置情報を取得し、接続履歴として保存する
     */
    private fun saveConnectionHistory(isDisconnection: Boolean, isPeriodic: Boolean = false) {
        scope.launch {
            try {
                val type = when {
                    isPeriodic -> "Periodic"
                    isDisconnection -> "Disconnection"
                    else -> "Connection"
                }
                addDebugLog("Saving $type history...")

                // 座標の決定: 切断時は最後に確認された生存地点（バイクの場所）を優先する
                val (lat, lon) = if (isDisconnection && lastActiveLocation != null) {
                    addDebugLog("Using cached lastActiveLocation for disconnection")
                    Pair(lastActiveLocation?.latitude, lastActiveLocation?.longitude)
                } else {
                    val locationResult = locationTracker.getCurrentLocation()
                    val locationData = locationResult.getOrNull()
                    Pair(locationData?.latitude, locationData?.longitude)
                }
                
                // 住所を取得
                val address = if (lat != null && lon != null) {
                    com.pirorin215.btclockmob.data.GeocoderUtil.getAddressFromLocation(
                        context,
                        lat,
                        lon
                    )
                } else null

                val currentTime = System.currentTimeMillis()
                val historyEntry = com.pirorin215.btclockmob.data.DeviceHistoryEntry(
                    timestamp = currentTime,
                    latitude = lat,
                    longitude = lon,
                    isDisconnection = isDisconnection,
                    isPeriodic = isPeriodic,
                    address = address
                )
                
                deviceHistoryRepository.addEntry(historyEntry)
                
                // 最後に保存した位置と時間を更新（定期記録の判定用）
                if (lat != null && lon != null) {
                    lastRecordedLocation = android.location.Location("").apply {
                        latitude = lat
                        longitude = lon
                    }
                }
                lastRecordedTime = currentTime

                addDebugLog("$type history saved: Lat=$lat, Lon=$lon")
            } catch (e: Exception) {
                addLog("履歴保存中にエラーが発生しました: ${e.message}", LogLevel.ERROR)
            }
        }
    }
}
