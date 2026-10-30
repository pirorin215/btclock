package com.pirorin215.btclockmob

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object BleScanServiceManager {
    /**
     * BikeClockファミリーのデバイス名接頭辞。
     * デバイス名をハードコードせず、この接頭辞でOSペアリング情報から対象を絞り込む（唯一の固定規約）。
     */
    const val DEVICE_NAME_PREFIX = "BikeClock-"

    /**
     * BLEスキャンが実行中か(BleScanService が更新・health check が参照)。
     * 「サービスは生きているがスキャンだけ停止」の沈黙を未接続時に検知して
     * 再開要求する(v1.4.9以降は接続がスキャン発見経由のみのため重要)。
     */
    @Volatile
    var isScanRunning: Boolean = false

    /**
     * 接続候補デバイスの発見通知。Pair の second はスキャン時の RSSI (dBm)。
     * BleConnectionManager が RSSI 接続しきい値フィルタに使う
     * （自転車レーダー gardia_radar と同一の仕組み）。
     */
    private val _deviceFoundFlow = MutableSharedFlow<Pair<BluetoothDevice, Int>>(extraBufferCapacity = 1)
    val deviceFoundFlow: SharedFlow<Pair<BluetoothDevice, Int>> = _deviceFoundFlow.asSharedFlow()

    private val _restartScanFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val restartScanFlow: SharedFlow<Unit> = _restartScanFlow.asSharedFlow()

    suspend fun emitDeviceFound(device: BluetoothDevice, rssi: Int) {
        _deviceFoundFlow.emit(Pair(device, rssi))
    }

    suspend fun emitRestartScan() {
        _restartScanFlow.emit(Unit)
    }
}

/**
 * OSとペアリング済み(bonded)の "BikeClock-" デバイス名を昇順で返す。
 */
@SuppressLint("MissingPermission")
fun bondedBikeClockDeviceNames(context: Context): List<String> {
    return runCatching {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter?.bondedDevices
            ?.mapNotNull { it.name }
            ?.filter { it.startsWith(BleScanServiceManager.DEVICE_NAME_PREFIX) }
            ?.sorted()
            ?: emptyList()
    }.getOrDefault(emptyList())
}
