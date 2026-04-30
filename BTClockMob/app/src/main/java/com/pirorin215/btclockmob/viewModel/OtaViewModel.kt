package com.pirorin215.btclockmob.viewModel

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelUuid
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
import java.util.UUID

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

    // DFU device scanning
    private val _dfuDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val dfuDevices = _dfuDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    private val _dfuDeviceConnected = MutableStateFlow(false)
    val dfuDeviceConnected = _dfuDeviceConnected.asStateFlow()

    // Nordic DFU Service UUID
    private val DFU_SERVICE_UUID = UUID.fromString("00001530-1212-EFDE-1523-785FEABCD123")
    private val DFU_CONTROL_POINT_UUID = UUID.fromString("00001531-1212-EFDE-1523-785FEABCD123")
    private val DFU_PACKET_UUID = UUID.fromString("00001532-1212-EFDE-1523-785FEABCD123")

    private var dfuGatt: BluetoothGatt? = null
    private var dfuControlPointCharacteristic: BluetoothGattCharacteristic? = null
    private var dfuPacketCharacteristic: BluetoothGattCharacteristic? = null

    private val bluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    private val bluetoothLeScanner by lazy {
        bluetoothManager.adapter.bluetoothLeScanner
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            Log.d(TAG, "Found device: ${device.name} (${device.address})")

            // Check if device name starts with "AdaDFU" or has Nordic DFU service
            if (device.name?.startsWith("AdaDFU") == true ||
                result.scanRecord?.serviceUuids?.contains(ParcelUuid(DFU_SERVICE_UUID)) == true) {

                Log.d(TAG, "Found DFU device: ${device.name} (${device.address})")

                // Add to list if not already present
                _dfuDevices.value = _dfuDevices.value.toMutableList().apply {
                    if (!any { it.address == device.address }) {
                        add(device)
                    }
                }
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            Log.d(TAG, "Batch scan results: ${results.size} devices")
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            _isScanning.value = false
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "DFU device connected: $deviceAddress")
                    _dfuDeviceConnected.value = true
                    // Discover services
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, "DFU device disconnected: $deviceAddress")
                    _dfuDeviceConnected.value = false
                }
            } else {
                Log.e(TAG, "DFU connection error: $status")
                _dfuDeviceConnected.value = false
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "DFU services discovered")

                // Find DFU service
                val dfuService = gatt.getService(DFU_SERVICE_UUID)
                if (dfuService != null) {
                    Log.d(TAG, "DFU service found")

                    // Get characteristics
                    dfuControlPointCharacteristic = dfuService.getCharacteristic(DFU_CONTROL_POINT_UUID)
                    dfuPacketCharacteristic = dfuService.getCharacteristic(DFU_PACKET_UUID)

                    if (dfuControlPointCharacteristic != null && dfuPacketCharacteristic != null) {
                        Log.d(TAG, "DFU characteristics found")
                    } else {
                        Log.e(TAG, "DFU characteristics not found")
                        Log.e(TAG, "  Control point: $dfuControlPointCharacteristic")
                        Log.e(TAG, "  Packet: $dfuPacketCharacteristic")
                    }
                } else {
                    Log.e(TAG, "DFU service not found")
                }
            } else {
                Log.e(TAG, "Service discovery failed: $status")
            }
        }
    }

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

    /**
     * DFUデバイスのスキャンを開始
     */
    @SuppressLint("MissingPermission")
    fun startDfuDeviceScan() {
        Log.d(TAG, "Starting DFU device scan")
        _dfuDevices.value = emptyList()
        _isScanning.value = true

        try {
            bluetoothLeScanner.startScan(scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scan", e)
            _isScanning.value = false
        }
    }

    /**
     * DFUデバイスのスキャンを停止
     */
    @SuppressLint("MissingPermission")
    fun stopDfuDeviceScan() {
        Log.d(TAG, "Stopping DFU device scan")
        _isScanning.value = false

        try {
            bluetoothLeScanner.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop scan", e)
        }
    }

    /**
     * DFUデバイスに接続
     */
    @SuppressLint("MissingPermission")
    fun connectDfuDevice(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to DFU device: ${device.name} (${device.address})")

        try {
            dfuGatt = device.connectGatt(context, false, gattCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to DFU device", e)
            _dfuDeviceConnected.value = false
        }
    }

    /**
     * DFUデバイスから切断
     */
    fun disconnectDfuDevice() {
        Log.d(TAG, "Disconnecting from DFU device")
        dfuGatt?.close()
        dfuGatt = null
        dfuControlPointCharacteristic = null
        dfuPacketCharacteristic = null
        _dfuDeviceConnected.value = false
    }

    override fun onCleared() {
        super.onCleared()
        stopDfuDeviceScan()
        disconnectDfuDevice()
    }
}
