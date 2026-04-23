package com.pirorin215.btclockmob.bluetooth.device

import com.pirorin215.btclockmob.bluetooth.constants.BleConstants
import com.pirorin215.btclockmob.bluetooth.notification.BleNotificationManager
import com.pirorin215.btclockmob.data.AppSettingsRepository
import com.pirorin215.btclockmob.data.ConnectionState
import com.pirorin215.btclockmob.data.DeviceHistoryEntry
import com.pirorin215.btclockmob.data.DeviceHistoryRepository
import com.pirorin215.btclockmob.data.DeviceInfoResponse
import com.pirorin215.btclockmob.data.FileEntry
import com.pirorin215.btclockmob.data.Settings
import com.pirorin215.btclockmob.LocationTracker
import com.pirorin215.btclockmob.constants.BleTimeoutConstants
import com.pirorin215.btclockmob.constants.TimeConstants
import com.pirorin215.btclockmob.data.parseFileEntries
import com.pirorin215.btclockmob.data.toUtf8String
import com.pirorin215.btclockmob.viewModel.BleOperation
import com.pirorin215.btclockmob.viewModel.LogManager
import com.pirorin215.btclockmob.viewModel.NavigationEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/**
 * BLEデバイスの情報と操作を管理するクラス
 *
 * 役割:
 * - デバイス情報の管理と取得
 * - 時刻同期の実行
 * - ファイルリストの取得と管理
 * - ファイル削除
 *
 * @property scope コルーチンスコープ
 * @property sendCommand BLEコマンド送信関数
 * @property logManager ログマネージャー
 * @property _currentOperation 現在のBLE操作状態
 * @property bleMutex BLE操作の排他制御用ミューテックス
 * @property onFileListUpdated ファイルリスト更新時のコールバック
 * @property notificationManager 通知マネージャー
 * @property locationTracker 位置情報トラッカー
 * @property deviceHistoryRepository デバイス履歴リポジトリ
 * @property appSettingsRepository アプリ設定リポジトリ
 */
class BleDeviceManager(
    private val scope: CoroutineScope,
    private val sendCommand: (String) -> Unit,
    private val logManager: LogManager,
    private val _currentOperation: MutableStateFlow<BleOperation>,
    private val bleMutex: Mutex,
    private val onFileListUpdated: () -> Unit,
    private val notificationManager: BleNotificationManager,
    private val locationTracker: LocationTracker,
    private val deviceHistoryRepository: DeviceHistoryRepository,
    private val appSettingsRepository: AppSettingsRepository
) {
    // --- 状態管理 ---
    /**
     * デバイス情報
     */
    private val _deviceInfo = MutableStateFlow<DeviceInfoResponse?>(null)
    val deviceInfo = _deviceInfo.asStateFlow()

    /**
     * ファイルリスト
     */
    private val _fileList = MutableStateFlow<List<FileEntry>>(emptyList())
    val fileList = _fileList.asStateFlow()

    // --- 内部プロパティ ---
    private val json = Json { ignoreUnknownKeys = true }
    private val rawResponseBuffer = java.io.ByteArrayOutputStream()
    private var currentCommandCompletion: CompletableDeferred<Pair<Boolean, String?>>? = null
    private var timeSyncJob: Job? = null

    // --- 公開メソッド ---

    /**
     * 時刻同期を実行する
     *
     * @param connectionState 現在の接続状態
     * @return 成功時true、失敗時false
     */
    suspend fun syncTime(connectionState: ConnectionState): Boolean {
        if (connectionState !is ConnectionState.Connected) {
            logManager.addLog("接続されていないため時刻同期を実行できません")
            return false
        }

        return bleMutex.withLock {
            if (_currentOperation.value != BleOperation.IDLE) {
                logManager.addLog("時刻同期を実行できません: ${_currentOperation.value} 実行中")
                return@withLock false
            }

            try {
                _currentOperation.value = BleOperation.SENDING_TIME
                rawResponseBuffer.reset()
                val timeCompletion = CompletableDeferred<Pair<Boolean, String?>>()
                currentCommandCompletion = timeCompletion

                val currentTimestampSec = System.currentTimeMillis() / 1000
                val timeCommand = "${BleConstants.CMD_TIME_SYNC}:$currentTimestampSec"
                logManager.addLog("時刻同期コマンドを送信中: $timeCommand")
                sendCommand(timeCommand)

                val (timeSyncSuccess, _) = withTimeoutOrNull(BleTimeoutConstants.TIME_SYNC_TIMEOUT_MS) {
                    timeCompletion.await()
                } ?: Pair(false, "タイムアウト")

                if (timeSyncSuccess) {
                    logManager.addLog("時刻同期が成功しました")
                } else {
                    logManager.addLog("時刻同期が失敗またはタイムアウトしました")
                }
                timeSyncSuccess
            } catch (e: Exception) {
                logManager.addLog("時刻同期中にエラーが発生しました: ${e.message}")
                false
            } finally {
                _currentOperation.value = BleOperation.IDLE
                currentCommandCompletion = null
            }
        }
    }

    /**
     * 定期時刻同期ジョブを開始する
     * 5分ごとにベストエフォートで時刻同期を行う
     */
    fun startTimeSyncJob() {
        timeSyncJob?.cancel()
        timeSyncJob = scope.launch {
            while (true) {
                delay(TimeConstants.TIME_SYNC_INTERVAL_MS)
                if (_currentOperation.value == BleOperation.IDLE) {
                    // tryLockを使用して、他の操作をブロックしないようにする
                    if (bleMutex.tryLock()) {
                        try {
                            if (_currentOperation.value == BleOperation.IDLE) {
                                val periodicTimestampSec = System.currentTimeMillis() / 1000
                                val periodicTimeCommand = "${BleConstants.CMD_TIME_SYNC}:$periodicTimestampSec"
                                logManager.addLog("定期時刻同期コマンド送信 (ベストエフォート): $periodicTimeCommand")
                                sendCommand(periodicTimeCommand)
                                // 【設計意図】定期時刻同期はベストエフォートで動作します
                                // - 応答を待たずにコマンドを送信するだけ（他の操作をブロックしない）
                                // - デバイスが受信するか不明だが、5分後に再試行される
                                // - 確実な同期が必要場合は syncTime() を明示的に呼び出す
                            }
                        } finally {
                            bleMutex.unlock()
                        }
                    } else {
                        logManager.addLog("定期時刻同期をスキップ: 他の操作が実行中です")
                    }
                }
            }
        }
    }

    /**
     * 定期時刻同期ジョブを停止する
     */
    fun stopTimeSyncJob() {
        timeSyncJob?.cancel()
        timeSyncJob = null
    }

    /**
     * デバイス情報を取得する
     *
     * @param connectionState 現在の接続状態
     * @param retryCount リトライ回数（デフォルト3回）
     * @return 成功時true、失敗時false
     */
    suspend fun fetchDeviceInfo(connectionState: ConnectionState, retryCount: Int = 3): Boolean {
        if (connectionState !is ConnectionState.Connected) {
            logManager.addLog("接続されていないためデバイス情報を取得できません")
            return false
        }

        return bleMutex.withLock {
            if (_currentOperation.value != BleOperation.IDLE) {
                logManager.addLog("デバイス情報を取得できません: ${_currentOperation.value} 実行中")
                return@withLock false
            }

            try {
                _currentOperation.value = BleOperation.FETCHING_DEVICE_INFO
                logManager.addLog("デバイス情報を要求中 (リトライ回数: $retryCount)...")

                val deviceInfoResponses = mutableListOf<DeviceInfoResponse>()

                // 指定回数だけ取得を試行
                repeat(retryCount) { attemptIndex ->
                    rawResponseBuffer.reset()

                    val commandCompletion = CompletableDeferred<Pair<Boolean, String?>>()
                    currentCommandCompletion = commandCompletion

                    sendCommand(BleConstants.CMD_GET_INFO)

                    val (success, _) = withTimeoutOrNull(BleTimeoutConstants.DEVICE_INFO_TIMEOUT_MS) {
                        commandCompletion.await()
                    } ?: Pair(false, "タイムアウト")

                    if (success && _deviceInfo.value != null) {
                        deviceInfoResponses.add(_deviceInfo.value!!)
                        logManager.addLog("GET:info 試行 ${attemptIndex + 1}/$retryCount 成功 (電圧: ${_deviceInfo.value!!.batteryVoltage}V)")
                    } else {
                        logManager.addLog("GET:info 試行 ${attemptIndex + 1}/$retryCount 失敗またはタイムアウト")
                    }

                    // 最後の試行でなければ待機
                    if (attemptIndex < retryCount - 1) {
                        delay(BleTimeoutConstants.DEVICE_INFO_RETRY_DELAY_MS)
                    }
                }

                // 応答が1つ以上あれば、最も高い電圧を持つものを選択
                if (deviceInfoResponses.isNotEmpty()) {
                    val bestResponse = deviceInfoResponses.maxByOrNull { it.batteryVoltage }
                    _deviceInfo.value = bestResponse

                    // デバイス履歴に保存
                    val locationResult = locationTracker.getCurrentLocation()
                    val locationData = locationResult.getOrNull()
                    val historyEntry = DeviceHistoryEntry(
                        timestamp = System.currentTimeMillis(),
                        latitude = locationData?.latitude,
                        longitude = locationData?.longitude,
                        batteryLevel = bestResponse!!.batteryLevel,
                        batteryVoltage = bestResponse.batteryVoltage
                    )
                    deviceHistoryRepository.addEntry(historyEntry)

                    // 低電圧チェックと通知
                    notificationManager.checkAndNotifyLowVoltage(bestResponse.batteryVoltage)

                    logManager.addLog("最良の電圧を選択: ${bestResponse.batteryVoltage}V (${deviceInfoResponses.size}回の試行から)")
                    true
                } else {
                    logManager.addLog("全てのGET:info 試行が失敗しました")
                    false
                }
            } catch (e: Exception) {
                logManager.addLog("デバイス情報取得エラー: ${e.message}")
                false
            } finally {
                _currentOperation.value = BleOperation.IDLE
                currentCommandCompletion = null
            }
        }
    }

    /**
     * ファイルリストを取得する
     *
     * @param connectionState 現在の接続状態
     * @param extension ファイル拡張子（デフォルト: "wav"）
     * @param triggerCallback ファイルリスト更新時のコールバックをトリガーするか（デフォルト: true）
     * @return 成功時true、失敗時false
     */
    suspend fun fetchFileList(
        connectionState: ConnectionState,
        extension: String = "wav",
        triggerCallback: Boolean = true
    ): Boolean {
        if (connectionState !is ConnectionState.Connected) {
            logManager.addLog("接続されていないためファイルリストを取得できません")
            return false
        }

        return bleMutex.withLock {
            if (_currentOperation.value != BleOperation.IDLE) {
                logManager.addLog("ファイルリストを取得できません: ${_currentOperation.value} 実行中")
                return@withLock false
            }

            try {
                _currentOperation.value = BleOperation.FETCHING_FILE_LIST
                rawResponseBuffer.reset()
                val command = "${BleConstants.CMD_GET_FILE_LIST}:$extension"
                logManager.addLog("ファイルリストを要求中 ($command)...")

                val commandCompletion = CompletableDeferred<Pair<Boolean, String?>>()
                currentCommandCompletion = commandCompletion

                sendCommand(command)

                val (success, _) = withTimeoutOrNull(BleTimeoutConstants.FILE_LIST_TIMEOUT_MS) {
                    commandCompletion.await()
                } ?: Pair(false, "タイムアウト")

                if (success) {
                    logManager.addLog("$command 完了")
                    if (extension == "wav" && triggerCallback) {
                        onFileListUpdated()
                    }
                } else {
                    logManager.addLog("$command 失敗またはタイムアウト")
                }
                success
            } catch (e: Exception) {
                logManager.addLog("ファイルリスト取得エラー: ${e.message}")
                false
            } finally {
                _currentOperation.value = BleOperation.IDLE
                currentCommandCompletion = null
            }
        }
    }

    /**
     * ファイルリストからファイルを削除する
     *
     * @param fileName 削除するファイル名
     * @param triggerCallback ファイルリスト更新時のコールバックをトリガーするか（デフォルト: true）
     */
    fun removeFileFromList(fileName: String, triggerCallback: Boolean = true) {
        _fileList.value = _fileList.value.filterNot { it.name == fileName }
        logManager.addLog("ローカルファイルリストから '$fileName' を削除しました")
        if (triggerCallback) {
            onFileListUpdated()
        }
    }

    /**
     * レスポンスを処理する
     *
     * @param value 受信したデータ
     * @param operation 現在のBLE操作
     */
    fun handleResponse(value: ByteArray, operation: BleOperation) {
        // バイトをバッファに結合（文字列化せずにバイトのまま追加）
        rawResponseBuffer.write(value)

        when (operation) {
            BleOperation.FETCHING_DEVICE_INFO -> {
                // 終了判定のために文字列化してチェック（簡易的なチェック）
                val currentBufferAsString = rawResponseBuffer.toUtf8String()

                if (currentBufferAsString.isNotEmpty() && currentBufferAsString.endsWith(BleConstants.JSON_END_MARKER)) {
                    logManager.addLog("生のDeviceInfo JSON: $currentBufferAsString")
                    try {
                        val parsedResponse = json.decodeFromString<DeviceInfoResponse>(currentBufferAsString)
                        _deviceInfo.value = parsedResponse
                        currentCommandCompletion?.complete(Pair(true, null))
                    } catch (e: Exception) {
                        logManager.addLog("DeviceInfo解析エラー: ${e.message}")
                        currentCommandCompletion?.complete(Pair(false, e.message))
                    }
                } else if (currentBufferAsString.startsWith(BleConstants.RESPONSE_ERROR + BleConstants.COMMAND_SEPARATOR)) {
                    logManager.addLog("GET:info エラー応答: $currentBufferAsString")
                    currentCommandCompletion?.complete(Pair(false, currentBufferAsString))
                }
            }
            BleOperation.FETCHING_FILE_LIST -> {
                val currentBufferAsString = rawResponseBuffer.toUtf8String()

                // 空の配列チェック
                if (currentBufferAsString == BleConstants.EMPTY_ARRAY) {
                    _fileList.value = emptyList()
                    currentCommandCompletion?.complete(Pair(true, null))
                    return
                }

                if (currentBufferAsString.endsWith(BleConstants.ARRAY_END_MARKER)) {
                    try {
                        _fileList.value = parseFileEntries(currentBufferAsString)
                        logManager.addLog("FileList解析完了. 件数: ${_fileList.value.size}")
                        currentCommandCompletion?.complete(Pair(true, null))
                    } catch (e: Exception) {
                        logManager.addLog("FileList解析エラー: ${e.message}")
                        currentCommandCompletion?.complete(Pair(false, e.message))
                    }
                } else if (currentBufferAsString.startsWith(BleConstants.RESPONSE_ERROR + BleConstants.COMMAND_SEPARATOR)) {
                    logManager.addLog("GET:ls エラー応答: $currentBufferAsString")
                    _fileList.value = emptyList()
                    currentCommandCompletion?.complete(Pair(false, currentBufferAsString))
                }
            }
            BleOperation.SENDING_TIME -> {
                val response = rawResponseBuffer.toUtf8String()
                if (response.startsWith(BleConstants.RESPONSE_OK_TIME)) {
                    currentCommandCompletion?.complete(Pair(true, null))
                    rawResponseBuffer.reset()
                } else if (response.startsWith(BleConstants.RESPONSE_ERROR + BleConstants.COMMAND_SEPARATOR)) {
                    currentCommandCompletion?.complete(Pair(false, response))
                    rawResponseBuffer.reset()
                } else {
                    logManager.addLog("SET:time 予期しない応答: $response")
                }
            }
            else -> {
                // 他の操作は別のマネージャーで処理
            }
        }
    }
}
