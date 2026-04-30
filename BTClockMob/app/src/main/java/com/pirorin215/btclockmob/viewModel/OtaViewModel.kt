package com.pirorin215.btclockmob.viewModel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pirorin215.btclockmob.data.BleRepository
import com.pirorin215.btclockmob.bluetooth.constants.BleConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

/**
 * OTA状態を表すシールドクラス
 */
sealed class OtaState {
    object Idle : OtaState()
    object Connecting : OtaState()
    data class Transferring(val progress: Int) : OtaState()
    object Completed : OtaState()
    data class Error(val message: String) : OtaState()
}

/**
 * OTA更新を管理するViewModel
 */
class OtaViewModel(
    private val context: Context,
    private val bleConnectionManager: BleConnectionManager
) : ViewModel() {

    private val TAG = "OtaViewModel"

    private val _otaState = MutableStateFlow<OtaState>(OtaState.Idle)
    val otaState = _otaState.asStateFlow()

    /**
     * OTA更新を実行
     * @param firmwareUri ファームウェアファイルのURI
     */
    fun startOtaUpdate(firmwareUri: Uri) {
        viewModelScope.launch {
            try {
                _otaState.value = OtaState.Connecting
                Log.d(TAG, "Starting OTA update")

                // ファームウェアファイルを読み込み
                val firmwareData = readFirmwareFile(firmwareUri)
                Log.d(TAG, "Firmware file loaded: ${firmwareData.size} bytes")

                _otaState.value = OtaState.Transferring(0)

                // OTA開始コマンドを送信
                // 注意: 既にDFUモードに接続している場合、startOta()は失敗する可能性があります
                // ユーザーは手動でDFUモードデバイスに再接続する必要があります
                val success = withContext(Dispatchers.IO) {
                    bleConnectionManager.repositoryForOta.startOta()
                }

                if (!success) {
                    _otaState.value = OtaState.Error("Failed to start OTA. Make sure you're connected to the device first.")
                    Log.e(TAG, "Failed to start OTA mode")
                    return@launch
                }

                Log.d(TAG, "OTA start command sent, waiting for DFU mode...")

                // デバイスがDFUモードに入るのを待機
                // BikeClockは "9999" を表示してからリセットする
                delay(3000)

                Log.d(TAG, "Starting firmware transfer...")

                // ファームウェア転送
                val totalSize = firmwareData.size
                var transferredSize = 0

                // 分割して転送（20バイト/パケット）
                val packetSize = 20
                var offset = 0

                while (offset < totalSize) {
                    val remainingLength = totalSize - offset
                    val packetLength = minOf(packetSize, remainingLength)

                    val success = withContext(Dispatchers.IO) {
                        bleConnectionManager.repositoryForOta.sendOtaPacket(firmwareData, offset, packetLength)
                    }

                    if (!success) {
                        _otaState.value = OtaState.Error("Failed to send firmware data at offset $offset")
                        Log.e(TAG, "Failed to send firmware packet at offset $offset")
                        return@launch
                    }

                    offset += packetLength
                    transferredSize = offset

                    // 進捗を更新
                    val progress = (transferredSize * 100) / totalSize
                    _otaState.value = OtaState.Transferring(progress)

                    Log.d(TAG, "Transferred: $transferredSize / $totalSize bytes ($progress%)")
                }

                // 転送完了、アクティベートコマンドを送信
                Log.d(TAG, "Firmware transfer completed. Activating...")

                val activateSuccess = withContext(Dispatchers.IO) {
                    bleConnectionManager.repositoryForOta.activateOta()
                }

                if (!activateSuccess) {
                    _otaState.value = OtaState.Error("Failed to activate firmware")
                    Log.e(TAG, "Failed to activate firmware")
                    return@launch
                }

                _otaState.value = OtaState.Completed
                Log.d(TAG, "OTA update completed successfully")

            } catch (e: Exception) {
                Log.e(TAG, "OTA update failed", e)
                _otaState.value = OtaState.Error("OTA update failed: ${e.message}")
            }
        }
    }

    /**
     * ファームウェアファイルを読み込む
     * @param uri ファイルのURI
     * @return ファームウェアデータ
     */
    private suspend fun readFirmwareFile(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Failed to open firmware file")

        inputStream.use {
            it.readBytes()
        }
    }

    /**
     * OTA状態をリセット
     */
    fun resetState() {
        _otaState.value = OtaState.Idle
    }
}
