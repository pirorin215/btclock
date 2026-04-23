package com.pirorin215.btclockmob.bluetooth.device

import com.pirorin215.btclockmob.bluetooth.constants.BleConstants
import com.pirorin215.btclockmob.data.ConnectionState
import com.pirorin215.btclockmob.constants.BleTimeoutConstants
import com.pirorin215.btclockmob.constants.TimeConstants
import com.pirorin215.btclockmob.data.toUtf8String
import com.pirorin215.btclockmob.viewModel.BleOperation
import com.pirorin215.btclockmob.viewModel.LogManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * BLEデバイスの操作を管理するクラス
 *
 * 役割:
 * - 時刻同期の実行
 *
 * @property scope コルーチンスコープ
 * @property sendCommand BLEコマンド送信関数
 * @property logManager ログマネージャー
 * @property _currentOperation 現在のBLE操作状態
 * @property bleMutex BLE操作の排他制御用ミューテックス
 */
class BleDeviceManager(
    private val scope: CoroutineScope,
    private val sendCommand: (String) -> Unit,
    private val logManager: LogManager,
    private val _currentOperation: MutableStateFlow<BleOperation>,
    private val bleMutex: Mutex
) {
    // --- 内部プロパティ ---
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
     * 1分ごとにベストエフォートで時刻同期を行う
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
     * レスポンスを処理する
     *
     * @param value 受信したデータ
     * @param operation 現在のBLE操作
     */
    fun handleResponse(value: ByteArray, operation: BleOperation) {
        // バイトをバッファに結合（文字列化せずにバイトのまま追加）
        rawResponseBuffer.write(value)

        when (operation) {
            BleOperation.SENDING_TIME -> {
                val response = rawResponseBuffer.toUtf8String()
                if (response.startsWith(BleConstants.RESPONSE_OK_TIME)) {
                    currentCommandCompletion?.complete(Pair(true, null))
                    rawResponseBuffer.reset()
                } else if (response.startsWith(BleConstants.RESPONSE_ERROR + BleConstants.COMMAND_SEPARATOR)) {
                    currentCommandCompletion?.complete(Pair(false, response))
                    rawResponseBuffer.reset()
                }
            }
            else -> {
                // 他の操作は別のマネージャーで処理
            }
        }
    }
}
