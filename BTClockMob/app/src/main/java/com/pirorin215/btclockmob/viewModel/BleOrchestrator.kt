package com.pirorin215.btclockmob.viewModel

import android.content.Context
import android.util.Log
import com.pirorin215.btclockmob.data.BleRepository
import com.pirorin215.btclockmob.data.ConnectionState
import com.pirorin215.btclockmob.data.TranscriptionResult
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
import com.pirorin215.btclockmob.data.DeviceInfoResponse
import com.pirorin215.btclockmob.data.FileEntry
import com.pirorin215.btclockmob.constants.TimeConstants

import com.pirorin215.btclockmob.viewModel.LogManager
import com.pirorin215.btclockmob.LocationTracker
import com.pirorin215.btclockmob.data.DeviceHistoryRepository
import com.pirorin215.btclockmob.bluetooth.constants.BleConstants
import com.pirorin215.btclockmob.bluetooth.notification.BleNotificationManager
import com.pirorin215.btclockmob.bluetooth.device.BleDeviceManager
import com.pirorin215.btclockmob.bluetooth.settings.BleSettingsManager
import com.pirorin215.btclockmob.viewModel.NavigationEvent

class BleOrchestrator(
    private val scope: CoroutineScope,
    private val context: Context,
    private val repository: BleRepository,
    private val connectionStateFlow: StateFlow<ConnectionState>,
    private val onDeviceReadyEvent: SharedFlow<Unit>,
    private val transcriptionManager: TranscriptionManager,
    private val locationMonitor: LocationMonitor,
    private val appSettingsRepository: AppSettingsRepository,
    private val bleSelectionManager: BleSelectionManager,
    private val transcriptionResults: StateFlow<List<TranscriptionResult>>,
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
     * - TranscriptionManager uses separate mutexes (independent from BLE)
     * - File processing uses AtomicBoolean (isProcessingFiles) instead of mutex
     */
    internal val bleMutex = Mutex()

    private val _navigationEvent = MutableSharedFlow<NavigationEvent>()
    val navigationEvent = _navigationEvent.asSharedFlow()

    // 通知マネージャーの初期化
    private val notificationManager by lazy {
        BleNotificationManager(
            context = context,
            appSettingsRepository = appSettingsRepository,
            logManager = logManager
        )
    }

    // デバイスマネージャーの初期化
    internal val bleDeviceManager by lazy {
        BleDeviceManager(
            scope = scope,
            sendCommand = { command -> sendCommand(command) },
            logManager = logManager,
            _currentOperation = _currentOperation,
            bleMutex = bleMutex,
            onFileListUpdated = { /* BikeClockはファイル処理を行わない */ },
            notificationManager = notificationManager,
            locationTracker = locationTracker,
            deviceHistoryRepository = deviceHistoryRepository,
            appSettingsRepository = appSettingsRepository
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
    val fileList: StateFlow<List<com.pirorin215.btclockmob.data.FileEntry>> get() = bleDeviceManager.fileList
    val deviceInfo: StateFlow<com.pirorin215.btclockmob.data.DeviceInfoResponse?> get() = bleDeviceManager.deviceInfo
    val deviceSettings: StateFlow<com.pirorin215.btclockmob.data.DeviceSettings> get() = bleSettingsManager.deviceSettings

    private val fileTransferManager by lazy {
        FileTransferManager(
            context = context,
            scope = scope,
            repository = repository,
            transcriptionManager = transcriptionManager,
            audioDirNameFlow = appSettingsRepository.getFlow(Settings.AUDIO_DIR_NAME)
                .stateIn(
                    scope = scope,
                    started = SharingStarted.WhileSubscribed(5000),
                    initialValue = "BTClockRecordings"
                ),
            bleMutex = bleMutex,
            logManager = logManager,
            sendCommandCallback = { command -> sendCommand(command) },
            sendAckCallback = { ackValue -> sendAck(ackValue) },
            _currentOperation = _currentOperation,
            bleDeviceManager = bleDeviceManager,
            _connectionState = connectionStateFlow,
            disconnectSignal = disconnectSignal,
            appSettingsRepository = appSettingsRepository
        )
    }

    val downloadProgress: StateFlow<Int> get() = fileTransferManager.downloadProgress
    val currentFileTotalSize: StateFlow<Long> get() = fileTransferManager.currentFileTotalSize
    val fileTransferState: StateFlow<String> get() = fileTransferManager.fileTransferState
    val transferKbps: StateFlow<Float> get() = fileTransferManager.transferKbps

    // AtomicBoolean to prevent concurrent file processing
    // This is safer than Mutex as it avoids potential deadlock with bleMutex
    private val isProcessingFiles = AtomicBoolean(false)

    // Retry job for file list fetch when device is busy (recording)
    private var fileListRetryJob: Job? = null

    // Periodic time sync job for BikeClock (1 minute interval)
    private var periodicTimeSyncJob: Job? = null

    init {
        // Monitor disconnect signal to cancel ongoing retry
        disconnectSignal
            .onEach {
                addDebugLog("BLE disconnected, canceling file list retry")
                fileListRetryJob?.cancel()
                fileListRetryJob = null
            }
            .launchIn(scope)

        onDeviceReadyEvent
            .onEach {
                addLog("Starting initial sync")
                startFullSync()
                // Start periodic time sync job
                startPeriodicTimeSync()
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
        fileListRetryJob?.cancel()
        fileListRetryJob = null
        periodicTimeSyncJob?.cancel()
        periodicTimeSyncJob = null
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
                    if (!success) {
                        addDebugLog("Periodic time sync failed")
                    }
                }
            }
        }
        addDebugLog("Periodic time sync job started (interval: ${TimeConstants.TIME_SYNC_INTERVAL_MS}ms)")
    }

    /**
     * デバイスが録音中の場合、短いインターバルでファイルリスト取得をリトライする
     * マイクロコントローラは非アクティブ後にディープスリープに入るため、
     * 録音終了後の短いウィンドウで頻繁にリトライし、ディープスリープ前に試行する
     * BLE切断時（ディープスリープに入った場合）はリトライを停止
     */
    private fun startFileListRetry() {
        // 既存のリトライジョブをキャンセル
        fileListRetryJob?.cancel()

        fileListRetryJob = scope.launch {
            var retryCount = 0
            val maxRetries = TimeConstants.BLE_MAX_RETRIES // 合計30秒（5秒 * 6）
            val retryDelay = TimeConstants.BLE_RETRY_DELAY_MS // 5秒

            while (retryCount < maxRetries) {
                // まだ接続されているか確認（デバイスがディープスリープに入った可能性）
                if (connectionStateFlow.value !is ConnectionState.Connected) {
                    addLog("リトライ中にBLE切断を検出、停止します")
                    break
                }

                delay(retryDelay)
                retryCount++
                addDebugLog("ファイルリスト取得をリトライ中 ($retryCount/$maxRetries)")

                val success = bleDeviceManager.fetchFileList(connectionStateFlow.value)
                if (success) {
                    addLog("リトライ $retryCount 回目でファイルリストの取得に成功しました")
                    break
                }
            }

            if (retryCount >= maxRetries && connectionStateFlow.value is ConnectionState.Connected) {
                addLog("$maxRetries 回のリトライ後にファイルリストの取得に失敗しました", LogLevel.ERROR)
            }

            fileListRetryJob = null
        }
    }

    private fun performPostTransferSync() {
        scope.launch {
            addDebugLog("転送後同期処理...")

            // BikeClockは時刻同期のみサポート
            val timeSyncSuccess = bleDeviceManager.syncTime(connectionStateFlow.value)
            if (!timeSyncSuccess) {
                addLog("時刻同期に失敗しました", LogLevel.ERROR)
                return@launch
            }

            addDebugLog("時刻同期完了")
        }
    }

    private fun checkForNewWavFilesAndProcess() {
        scope.launch {
            // 並行実行を防ぐためAtomicBooleanを使用
            // 内部操作で使用されるbleMutexとのデッドロックリスクを回避
            if (!isProcessingFiles.compareAndSet(false, true)) {
                addDebugLog("ファイル処理が既に進行中です")
                return@launch
            }
            try {
                bleDeviceManager.stopTimeSyncJob()

                val currentWavFilesOnMicrocontroller = bleDeviceManager.fileList.value.filter { it.name.endsWith(".wav", ignoreCase = true) }
                val transcribedFileNames = this@BleOrchestrator.transcriptionResults.value.map { it.fileName }.toSet()

                // 未文字起こしファイル（ダウンロード対象）
                val filesToDownload = currentWavFilesOnMicrocontroller.filter { fileEntry ->
                    !transcribedFileNames.contains(fileEntry.name)
                }

                // 文字起こし済みだがマイコンに残っているファイル（削除対象）
                // 前回削除に失敗した可能性があるため、再度削除を試行する
                val filesToDeleteOnly = currentWavFilesOnMicrocontroller.filter { fileEntry ->
                    transcribedFileNames.contains(fileEntry.name)
                }

                if (filesToDeleteOnly.isNotEmpty()) {
                    addLog("${filesToDeleteOnly.size} 件の文字起こし済みファイルをデバイスから削除中")
                    for (fileEntry in filesToDeleteOnly) {
                        addDebugLog("削除中: ${fileEntry.name}")
                        fileTransferManager.deleteFileAndUpdateList(fileEntry.name)
                    }
                }

                if (filesToDownload.isEmpty()) {
                    addDebugLog("新しいファイルはありません")
                    performPostTransferSync()
                    return@launch
                }

                addLog("${filesToDownload.size} 件の新しいWAVファイルを処理中")

                for (fileEntry in filesToDownload) {
                    addDebugLog("処理中: ${fileEntry.name}")

                    val downloadSuccess = fileTransferManager.downloadFile(fileEntry.name)

                    if (downloadSuccess) {
                        addDebugLog("ダウンロード成功、デバイスから削除中")
                        fileTransferManager.deleteFileAndUpdateList(fileEntry.name)
                    } else {
                        addLog("ダウンロード失敗: ${fileEntry.name}", LogLevel.ERROR)
                    }
                }

                addDebugLog("ファイル処理完了")
                performPostTransferSync()

            } finally {
                isProcessingFiles.set(false)
            }
        }
    }

    private fun handleCharacteristicChanged(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        // Check if this is the command characteristic (bidirectional)
        if (characteristic.uuid != UUID.fromString(BleRepository.COMMAND_UUID_STRING)) return

        when (_currentOperation.value) {
            BleOperation.FETCHING_FILE_LIST, BleOperation.FETCHING_DEVICE_INFO, BleOperation.SENDING_TIME -> {
                bleDeviceManager.handleResponse(value, _currentOperation.value)
            }
            BleOperation.FETCHING_SETTINGS, BleOperation.SENDING_SETTINGS -> {
                bleSettingsManager.handleResponse(value, _currentOperation.value)
            }
            BleOperation.DOWNLOADING_FILE, BleOperation.DELETING_FILE -> {
                fileTransferManager.handleCharacteristicChanged(characteristic, value)
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

    fun fetchFileList(extension: String) {
        scope.launch {
            bleDeviceManager.fetchFileList(connectionStateFlow.value, extension)
        }
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

    fun downloadFile(fileName: String) {
        scope.launch {
            fileTransferManager.downloadFile(fileName)
        }
    }

    fun toggleSelection(fileName: String) {
        bleSelectionManager.toggleSelection(fileName)
    }

    fun clearSelection() {
        bleSelectionManager.clearSelection()
    }
}