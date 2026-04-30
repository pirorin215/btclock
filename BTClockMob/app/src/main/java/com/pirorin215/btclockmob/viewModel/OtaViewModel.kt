package com.pirorin215.btclockmob.viewModel

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pirorin215.btclockmob.service.DfuService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import no.nordicsemi.android.dfu.DfuProgressListener
import no.nordicsemi.android.dfu.DfuProgressListenerAdapter
import no.nordicsemi.android.dfu.DfuServiceInitiator
import no.nordicsemi.android.dfu.DfuServiceListenerHelper
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

    private var selectedDevice: BluetoothDevice? = null

    // Nordic DFU Service UUID
    private val DFU_SERVICE_UUID = UUID.fromString("00001530-1212-EFDE-1523-785FEABCD123")

    private val bluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    private val bluetoothLeScanner by lazy {
        bluetoothManager.adapter.bluetoothLeScanner
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device

            // Check if device name starts with "AdaDFU" or has Nordic DFU service
            @SuppressLint("MissingPermission")
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

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            _isScanning.value = false
        }
    }

    private val dfuProgressListener: DfuProgressListener = object : DfuProgressListenerAdapter() {
        override fun onDeviceConnecting(deviceAddress: String) {
            Log.d(TAG, "DFU Device connecting: $deviceAddress")
            _otaState.value = OtaState.Connecting
        }

        override fun onDfuProcessStarting(deviceAddress: String) {
            Log.d(TAG, "DFU Process starting: $deviceAddress")
            _otaState.value = OtaState.Transferring(0)
        }

        override fun onEnablingDfuMode(deviceAddress: String) {
            Log.d(TAG, "Enabling DFU mode: $deviceAddress")
        }

        override fun onProgressChanged(
            deviceAddress: String,
            percent: Int,
            speed: Float,
            avgSpeed: Float,
            currentPart: Int,
            partsTotal: Int
        ) {
            _otaState.value = OtaState.Transferring(percent)
        }

        override fun onDfuCompleted(deviceAddress: String) {
            Log.d(TAG, "DFU Completed: $deviceAddress")
            _otaState.value = OtaState.Completed
            _dfuDeviceConnected.value = false
            selectedDevice = null
        }

        override fun onDfuAborted(deviceAddress: String) {
            Log.d(TAG, "DFU Aborted: $deviceAddress")
            _otaState.value = OtaState.Idle
            _dfuDeviceConnected.value = false
        }

        override fun onError(deviceAddress: String, error: Int, errorType: Int, message: String?) {
            Log.e(TAG, "DFU Error: $message ($error)")
            _otaState.value = OtaState.Error(message ?: "Unknown error ($error)")
            _dfuDeviceConnected.value = false
        }
    }

    init {
        DfuServiceListenerHelper.registerProgressListener(context, dfuProgressListener)
    }

    /**
     * OTA更新を実行
     * @param firmwareUri ファームウェアファイルのURI
     */
    @SuppressLint("MissingPermission")
    fun startOtaUpdate(firmwareUri: Uri) {
        val device = selectedDevice
        if (device == null) {
            _otaState.value = OtaState.Error("DFUデバイスを選択してください")
            return
        }

        Log.d(TAG, "Starting DFU update for ${device.name} (${device.address})")

        val initiator = DfuServiceInitiator(device.address)
            .setDeviceName(device.name ?: "Unknown")
            .setKeepBond(false)
            .setForceDfu(false)
            .setPacketsReceiptNotificationsEnabled(true)
            .setPacketsReceiptNotificationsValue(DfuServiceInitiator.DEFAULT_PRN_VALUE)
            .setPrepareDataObjectDelay(300L)
            .setUnsafeExperimentalButtonlessServiceInSecureDfuEnabled(true)

        // Android 8.0+ requires notification channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            DfuServiceInitiator.createDfuNotificationChannel(context)
        }

        // URIからファイルを指定して開始
        // .binファイルの場合はTYPE_APPLICATIONを指定
        initiator.setBinOrHex(no.nordicsemi.android.dfu.DfuBaseService.TYPE_APPLICATION, firmwareUri)
        initiator.start(context, DfuService::class.java)
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
     * DFUデバイスに接続（選択）
     */
    @SuppressLint("MissingPermission")
    fun connectDfuDevice(device: BluetoothDevice) {
        Log.d(TAG, "Selected DFU device: ${device.name} (${device.address})")
        selectedDevice = device
        _dfuDeviceConnected.value = true
        stopDfuDeviceScan()
    }

    /**
     * DFUデバイスから切断（選択解除）
     */
    fun disconnectDfuDevice() {
        Log.d(TAG, "Deselected DFU device")
        selectedDevice = null
        _dfuDeviceConnected.value = false
    }

    override fun onCleared() {
        super.onCleared()
        DfuServiceListenerHelper.unregisterProgressListener(context, dfuProgressListener)
        stopDfuDeviceScan()
    }
}
