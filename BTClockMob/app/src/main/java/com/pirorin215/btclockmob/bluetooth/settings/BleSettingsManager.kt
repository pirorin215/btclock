package com.pirorin215.btclockmob.bluetooth.settings

import com.pirorin215.btclockmob.bluetooth.constants.BleConstants
import com.pirorin215.btclockmob.constants.BleTimeoutConstants
import com.pirorin215.btclockmob.data.ConnectionState
import com.pirorin215.btclockmob.data.DeviceSettings
import com.pirorin215.btclockmob.viewModel.BleOperation
import com.pirorin215.btclockmob.viewModel.LogManager
import com.pirorin215.btclockmob.viewModel.NavigationEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * BLEデバイスの設定を管理するクラス
 *
 * 役割:
 * - 設定の取得
 * - 設定の送信
 * - 設定の更新
 * - レスポンスの処理
 *
 * @property scope コルーチンスコープ
 * @property sendCommand BLEコマンド送信関数
 * @property logManager ログマネージャー
 * @property _currentOperation 現在のBLE操作状態
 * @property bleMutex BLE操作の排他制御用ミューテックス
 * @property _navigationEvent ナビゲーションイベントフロー
 */
class BleSettingsManager(
    private val scope: CoroutineScope,
    private val sendCommand: (String) -> Unit,
    private val logManager: LogManager,
    private val _currentOperation: MutableStateFlow<BleOperation>,
    private val bleMutex: Mutex,
    private val _navigationEvent: MutableSharedFlow<NavigationEvent>
) {
    // --- 状態管理 ---
    /**
     * デバイス設定
     */
    private val _deviceSettings = MutableStateFlow(DeviceSettings())
    val deviceSettings = _deviceSettings.asStateFlow()

    // --- 内部プロパティ ---
    private val responseBuffer = StringBuilder()
    private var currentSettingsCommandCompletion: CompletableDeferred<Pair<Boolean, String?>>? = null

    // --- 公開メソッド ---

    /**
     * デバイスから設定を取得する
     *
     * @param connectionState 現在の接続状態
     * @return 成功時true、失敗時false
     */
    suspend fun getSettings(connectionState: ConnectionState): Boolean {
        if (connectionState !is ConnectionState.Connected) {
            logManager.addLog("接続されていないため設定を取得できません")
            return false
        }

        return bleMutex.withLock {
            if (_currentOperation.value != BleOperation.IDLE) {
                logManager.addLog("設定を取得できません: ${_currentOperation.value} 実行中")
                return@withLock false
            }

            try {
                _currentOperation.value = BleOperation.FETCHING_SETTINGS
                responseBuffer.clear()
                logManager.addLog("デバイスから設定を要求中...")

                val commandCompletion = CompletableDeferred<Pair<Boolean, String?>>()
                currentSettingsCommandCompletion = commandCompletion

                sendCommand(BleConstants.CMD_GET_SETTINGS)

                val (success, _) = withTimeoutOrNull(BleTimeoutConstants.SETTINGS_GET_TIMEOUT_MS) {
                    commandCompletion.await()
                } ?: Pair(false, "タイムアウト")

                if (success) {
                    logManager.addLog("GET:setting_ini コマンドが成功しました")
                } else {
                    logManager.addLog("GET:setting_ini コマンドが失敗またはタイムアウトしました")
                }
                success
            } catch (e: Exception) {
                logManager.addLog("設定取得エラー: ${e.message}")
                false
            } finally {
                _currentOperation.value = BleOperation.IDLE
                currentSettingsCommandCompletion = null
            }
        }
    }

    /**
     * デバイスへ設定を送信する
     *
     * @param connectionState 現在の接続状態
     */
    fun sendSettings(connectionState: ConnectionState) {
        if (_currentOperation.value != BleOperation.IDLE || connectionState !is ConnectionState.Connected) {
            logManager.addLog("設定を送信できません: ビジー状態または未接続")
            return
        }
        val settings = _deviceSettings.value
        logManager.addLog("現在のデバイス設定を送信中: $settings")
        _currentOperation.value = BleOperation.SENDING_SETTINGS
        val iniString = settings.toIniString()
        logManager.addLog("デバイスへ送信する設定:\n$iniString")
        val command = "${BleConstants.CMD_SEND_SETTINGS}:$iniString"
        sendCommand(command)
        scope.launch {
            // 元のBleSettingsManagerと同様、コマンド送信後にナビゲーション
            // 将来的にはSET:setting_iniへの応答を確認してからナビゲートすることを検討
            delay(BleTimeoutConstants.SETTINGS_SEND_DELAY_MS) // BLEコマンド送信完了のための小さな遅延
            _navigationEvent.emit(NavigationEvent.NavigateBack)
            _currentOperation.value = BleOperation.IDLE // 送信後に操作状態をリセット
        }
    }

    /**
     * デバイス設定を更新する
     *
     * @param updater 設定を更新する関数
     */
    fun updateSettings(updater: (DeviceSettings) -> DeviceSettings) {
        _deviceSettings.value = updater(_deviceSettings.value)
        logManager.addLog("デバイス設定を更新しました")
    }

    /**
     * レスポンスを処理する
     *
     * @param value 受信したデータ
     * @param operation 現在のBLE操作
     */
    fun handleResponse(value: ByteArray, operation: BleOperation) {
        when (operation) {
            BleOperation.FETCHING_SETTINGS -> {
                responseBuffer.append(value.toString(Charsets.UTF_8))

                scope.launch {
                    delay(200)

                    if (_currentOperation.value == BleOperation.FETCHING_SETTINGS) {
                        val settingsString = responseBuffer.toString().trim()
                        logManager.addLog("受信したリモート設定 (GET:setting_ini): $settingsString")

                        if (settingsString.startsWith(BleConstants.RESPONSE_ERROR + BleConstants.COMMAND_SEPARATOR)) {
                            logManager.addLog("GET:setting_ini エラー応答: $settingsString")
                            currentSettingsCommandCompletion?.complete(Pair(false, settingsString))
                        } else {
                            try {
                                val remoteSettings = DeviceSettings.fromIniString(settingsString)
                                _deviceSettings.value = remoteSettings
                                logManager.addLog("リモート設定をローカル状態に適用しました: $remoteSettings")
                                currentSettingsCommandCompletion?.complete(Pair(true, null))
                            } catch (e: Exception) {
                                logManager.addLog("設定解析エラー (GET:setting_ini): ${e.message}")
                                currentSettingsCommandCompletion?.complete(Pair(false, e.message))
                            }
                        }
                        _currentOperation.value = BleOperation.IDLE
                        responseBuffer.clear()
                    }
                }
            }
            // SENDING_SETTINGSには元々直接的なhandleResponseロジックがない
            // コマンド送信後にナビゲートして遅延後に操作状態をリセットするだけ
            // 応答が必要な場合はここで処理すること
            else -> {
                // 他の操作は別のマネージャーで処理
            }
        }
    }

    /**
     * ナビゲーションイベントフローを公開する
     */
    val navigationEvent = _navigationEvent.asSharedFlow()
}
