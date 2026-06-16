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
     * ユーザーが設定で選んだ接続先デバイス名（preferred）。
     * 空 = 未選択で、実行時にペアリング済みの先頭デバイスを自動使用する（resolveTargetDeviceName で解決）。
     * MainApplication が AppSettingsRepository の値をここへ同期する。
     */
    @Volatile
    var targetDeviceName: String = ""

    private val _deviceFoundFlow = MutableSharedFlow<BluetoothDevice>(extraBufferCapacity = 1)
    val deviceFoundFlow: SharedFlow<BluetoothDevice> = _deviceFoundFlow.asSharedFlow()

    private val _restartScanFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val restartScanFlow: SharedFlow<Unit> = _restartScanFlow.asSharedFlow()

    suspend fun emitDeviceFound(device: BluetoothDevice) {
        _deviceFoundFlow.emit(device)
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

/**
 * 接続先デバイス名を解決する。
 * - preferred(ユーザー選択)が空でなければそれを使用
 * - 空なら ペアリング済みの先頭 "BikeClock-" デバイスを自動使用
 * - 該当が無ければ空文字（呼び出し元でスキャン中止など）
 */
@SuppressLint("MissingPermission")
fun resolveTargetDeviceName(preferred: String, context: Context): String {
    val p = preferred.trim()
    if (p.isNotBlank()) return p
    return bondedBikeClockDeviceNames(context).firstOrNull() ?: ""
}
