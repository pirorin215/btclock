package com.pirorin215.btclockmob.viewModel

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
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

                        // Enable notifications on Control Point
                        enableDfuControlPointNotifications(gatt)
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

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "onDescriptorWrite - status: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "DFU Control Point notifications enabled successfully")
            } else {
                Log.e(TAG, "Failed to enable DFU Control Point notifications: $status")
            }
        }
    }

    /**
     * OTA更新を実行（Nordic DFUプロトコル）
     * @param firmwareUri ファームウェアファイルのURI
     */
    fun startOtaUpdate(firmwareUri: Uri) {
        viewModelScope.launch {
            try {
                _otaState.value = OtaState.Connecting
                Log.d(TAG, "Starting Nordic DFU update")

                // DFUデバイスに接続しているか確認
                if (!_dfuDeviceConnected.value || dfuGatt == null) {
                    _otaState.value = OtaState.Error("DFUデバイスに接続してください")
                    Log.e(TAG, "DFU device not connected")
                    return@launch
                }

                // ファームウェアファイルを読み込み
                val firmwareData = readFirmwareFile(firmwareUri)
                Log.d(TAG, "Firmware file loaded: ${firmwareData.size} bytes")

                _otaState.value = OtaState.Transferring(0)

                // Nordic DFUプロトコルでファームウェア転送
                val success = performNordicDfuUpdate(firmwareData)

                if (success) {
                    _otaState.value = OtaState.Completed
                    Log.d(TAG, "Nordic DFU update completed successfully")
                } else {
                    _otaState.value = OtaState.Error("DFU update failed")
                    Log.e(TAG, "Nordic DFU update failed")
                }

            } catch (e: Exception) {
                Log.e(TAG, "OTA update failed", e)
                _otaState.value = OtaState.Error("OTA update failed: ${e.message}")
            }
        }
    }

    /**
     * Nordic DFUプロトコルでファームウェア更新を実行
     */
    @SuppressLint("MissingPermission")
    private suspend fun performNordicDfuUpdate(firmwareData: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            // Wait for notifications to be enabled
            delay(500)

            // 1. Start DFUコマンド（OpCode 0x01）を送信
            Log.d(TAG, "Sending Start DFU command")
            val startDfuCommand = byteArrayOf(0x01, 0x00) // 0x01 = Start DFU, 0x00 = Application
            if (!writeDfuControlPoint(startDfuCommand)) {
                Log.e(TAG, "Failed to send Start DFU command")
                return@withContext false
            }
            delay(100)

            // 2. ファームウェアサイズをPacket Characteristicに送信（リトルエンディアン）
            Log.d(TAG, "Sending firmware size: ${firmwareData.size}")
            val sizeBytes = byteArrayOf(
                (firmwareData.size and 0xFF).toByte(),
                ((firmwareData.size shr 8) and 0xFF).toByte(),
                ((firmwareData.size shr 16) and 0xFF).toByte(),
                ((firmwareData.size shr 24) and 0xFF).toByte()
            )
            if (!writeDfuPacket(sizeBytes)) {
                Log.e(TAG, "Failed to send firmware size")
                return@withContext false
            }
            delay(100)

            // 3. Receive Firmwareコマンド（OpCode 0x03）を送信
            Log.d(TAG, "Sending Receive Firmware command")
            val receiveFirmwareCommand = byteArrayOf(0x03, 0x00) // 0x03 = Receive Firmware, 0x00 = Application
            if (!writeDfuControlPoint(receiveFirmwareCommand)) {
                Log.e(TAG, "Failed to send Receive Firmware command")
                return@withContext false
            }
            delay(100)

            // 4. ファームウェアデータをPacket Characteristicに分割して送信
            Log.d(TAG, "Starting firmware transfer...")
            val totalSize = firmwareData.size
            var offset = 0
            val packetSize = 20 // Nordic DFUは20バイト/パケット

            while (offset < totalSize) {
                val remainingLength = totalSize - offset
                val packetLength = minOf(packetSize, remainingLength)
                val packet = firmwareData.sliceArray(offset until offset + packetLength)

                if (!writeDfuPacket(packet)) {
                    Log.e(TAG, "Failed to send firmware packet at offset $offset")
                    return@withContext false
                }

                offset += packetLength

                // 進捗を更新
                val progress = (offset * 100) / totalSize
                _otaState.value = OtaState.Transferring(progress)

                if (offset % 1000 == 0) {
                    Log.d(TAG, "Transferred: $offset / $totalSize bytes ($progress%)")
                }

                delay(10) // 少し待機してデータを処理させる
            }

            Log.d(TAG, "Firmware transfer completed")

            // 5. Validateコマンド（OpCode 0x02）を送信
            Log.d(TAG, "Sending Validate command")
            val validateCommand = byteArrayOf(0x02) // 0x02 = Validate
            if (!writeDfuControlPoint(validateCommand)) {
                Log.e(TAG, "Failed to send Validate command")
                return@withContext false
            }
            delay(500)

            // 6. Activate & Resetコマンド（OpCode 0x04）を送信
            Log.d(TAG, "Sending Activate & Reset command")
            val activateCommand = byteArrayOf(0x04) // 0x04 = Activate & Reset
            if (!writeDfuControlPoint(activateCommand)) {
                Log.e(TAG, "Failed to send Activate & Reset command")
                return@withContext false
            }

            Log.d(TAG, "Nordic DFU update completed successfully")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Nordic DFU update failed", e)
            false
        }
    }

    /**
     * DFU Control Pointに書き込む
     */
    @SuppressLint("MissingPermission")
    private fun writeDfuControlPoint(data: ByteArray): Boolean {
        val characteristic = dfuControlPointCharacteristic
        if (characteristic == null) {
            Log.e(TAG, "DFU Control Point characteristic is null")
            return false
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = dfuGatt?.writeCharacteristic(
                characteristic,
                data,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            result == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.value = data
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            dfuGatt?.writeCharacteristic(characteristic) ?: false
        }
    }

    /**
     * DFU Packetに書き込む
     */
    @SuppressLint("MissingPermission")
    private fun writeDfuPacket(data: ByteArray): Boolean {
        val characteristic = dfuPacketCharacteristic
        if (characteristic == null) {
            Log.e(TAG, "DFU Packet characteristic is null")
            return false
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = dfuGatt?.writeCharacteristic(
                characteristic,
                data,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            )
            result == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.value = data
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            dfuGatt?.writeCharacteristic(characteristic) ?: false
        }
    }

    /**
     * Enable notifications on DFU Control Point
     */
    @SuppressLint("MissingPermission")
    private fun enableDfuControlPointNotifications(gatt: BluetoothGatt) {
        val characteristic = dfuControlPointCharacteristic
        if (characteristic == null) {
            Log.e(TAG, "DFU Control Point characteristic is null")
            return
        }

        // Get CCCD
        val cccd = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
        if (cccd == null) {
            Log.e(TAG, "CCCD not found for DFU Control Point")
            return
        }

        // Enable notifications (0x01, 0x00)
        val value = byteArrayOf(0x01, 0x00)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, value)
        } else {
            cccd.value = value
            gatt.writeDescriptor(cccd)
        }

        Log.d(TAG, "Enabling DFU Control Point notifications")
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
