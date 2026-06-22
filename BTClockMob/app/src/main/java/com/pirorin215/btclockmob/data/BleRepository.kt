package com.pirorin215.btclockmob.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.pirorin215.btclockmob.viewModel.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay // Add this import
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch // Add this import
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import com.pirorin215.btclockmob.constants.TimeConstants
import com.pirorin215.btclockmob.bluetooth.constants.BleConstants
import kotlinx.coroutines.flow.first
import kotlin.coroutines.resume

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Pairing : ConnectionState()
    data class Paired(val device: BluetoothDevice) : ConnectionState()
    data class Connected(val device: BluetoothDevice) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

// GATT接続状態（カスタムサービスのみ）
sealed class GattConnectionState {
    object Disconnected : GattConnectionState()
    object Connecting : GattConnectionState()
    data class Connected(val gatt: BluetoothGatt) : GattConnectionState()
    data class Error(val message: String) : GattConnectionState()
}

sealed class BleEvent {
    data class MtuChanged(val mtu: Int) : BleEvent()
    object ServicesDiscovered : BleEvent()
    data class CharacteristicChanged(val characteristic: BluetoothGattCharacteristic, val value: ByteArray) : BleEvent()
    object Ready : BleEvent()
    data class Error(val message: String) : BleEvent()
    data class ImuChunk(val data: ByteArray) : BleEvent()   // Phase 14-B: IMUバイナリチャンク（0xAA55マジック）
    data class InferenceLog(val line: String) : BleEvent()  // 推論ログ行（INFER:...・精度チューニング用）
}

@SuppressLint("MissingPermission")
class BleRepository(private val context: Context) {

    companion object {
        const val SERVICE_UUID_STRING = BleConstants.SERVICE_UUID_STRING
        const val COMMAND_UUID_STRING = BleConstants.COMMAND_UUID_STRING
    }

    private val TAG = "BleRepository"
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var bluetoothGatt: BluetoothGatt? = null

    var commandCharacteristic: BluetoothGattCharacteristic? = null
    var responseCharacteristic: BluetoothGattCharacteristic? = null

    // --- Flows to expose data to ViewModel ---
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()

    // GATT接続状態（カスタムサービス専用）
    private val _gattConnectionState = MutableStateFlow<GattConnectionState>(GattConnectionState.Disconnected)
    val gattConnectionState = _gattConnectionState.asStateFlow()

    private val _events = MutableSharedFlow<BleEvent>()
    val events = _events.asSharedFlow()

    // Device version information
    private val _deviceVersion = MutableStateFlow<String?>(null)
    val deviceVersion = _deviceVersion.asStateFlow()

    // For write callback synchronization
    private var pendingWriteResult: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    // 送信を1件ずつ直列化する（Phase 11: 通知転送で連続書込の取りこぼしを防ぐ）
    private val writeMutex = Mutex()

    private val gattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "Successfully connected to $deviceAddress")
                    _connectionState.value = ConnectionState.Connected(gatt.device)
                    _gattConnectionState.value = GattConnectionState.Connected(gatt)
                    // MTU request should be initiated by the ViewModel after connection.
                    // For now, we discover services directly. A delay might be needed.
                    repositoryScope.launch {
                        delay(TimeConstants.SERVICE_DISCOVERY_DELAY_MS) // Recommended delay before service discovery
                        val initiated = gatt.discoverServices()
                        if (!initiated) {
                            Log.e(TAG, "Failed to initiate service discovery.")
                            disconnect()
                        }
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, "Successfully disconnected from $deviceAddress")
                    close() // Close the GATT client.
                    _connectionState.value = ConnectionState.Disconnected
                    _gattConnectionState.value = GattConnectionState.Disconnected
                }
            } else {
                Log.e(TAG, "onConnectionStateChange error: status=$status for $deviceAddress")
                close()
                _connectionState.value = ConnectionState.Error("GATT Error $status")
                _gattConnectionState.value = GattConnectionState.Error("GATT Error $status")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "MTU changed to $mtu")
                repositoryScope.launch { _events.emit(BleEvent.MtuChanged(mtu)) }
            } else {
                Log.w(TAG, "MTU change failed, status: $status")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered successfully.")

                // カスタムサービスを探索
                Log.d(TAG, "Checking for custom service.")
                val service = gatt.getService(UUID.fromString(SERVICE_UUID_STRING))
                if (service == null) {
                    Log.e(TAG, "Custom service (${SERVICE_UUID_STRING}) not found.")
                    disconnect()
                    return
                }

                // Single bidirectional characteristic (READ | WRITE | NOTIFY)
                commandCharacteristic = service.getCharacteristic(UUID.fromString(COMMAND_UUID_STRING))

                if (commandCharacteristic == null) {
                    Log.e(TAG, "Command characteristic not found")
                    disconnect()
                    return
                }
                Log.d(TAG, "Command characteristic found: ${commandCharacteristic?.uuid}")

                // Use same characteristic for responses
                responseCharacteristic = commandCharacteristic

                // Enable notifications for the characteristic
                gatt.setCharacteristicNotification(responseCharacteristic, true)
                val descriptor = responseCharacteristic?.getDescriptor(UUID.fromString(BleConstants.CCCD_UUID_STRING))
                if (descriptor != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                    Log.d(TAG, "Writing descriptor to enable notifications for command characteristic.")
                } else {
                    // CCCDがない場合でも接続を継続
                    Log.w(TAG, "CCCD descriptor not found. Continuing without notification support.")
                    repositoryScope.launch { _events.emit(BleEvent.Ready) }
                }
            } else {
                Log.w(TAG, "Service discovery failed with status $status")
                disconnect()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Descriptor written successfully for ${descriptor?.characteristic?.uuid}")
                Log.d(TAG, "Normal mode: Repository is ready.")
                repositoryScope.launch { _events.emit(BleEvent.Ready) }
            } else {
                Log.e(TAG, "Descriptor write failed with status $status")
                disconnect()
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            // Phase 14-B: IMUバイナリチャンク（マジック 0xAA55）は既存の文字列処理（0x00切り詰め）の前で別ルートへ。
            //   IMU生値に0x00が頻出するため文字列化すると壊れる。マジックで安全に識別（既存のASCII応答 OK:/ERROR:/NOTIFY: は衝突しない）。
            if (value.size >= 5 &&
                (value[0].toInt() and 0xFF) == 0xAA &&
                (value[1].toInt() and 0xFF) == 0x55) {
                repositoryScope.launch { _events.emit(BleEvent.ImuChunk(value)) }
                return
            }
            // ByteArrayを文字列に変換する際、null終端までを正しく処理
            val nullIndex = value.indexOf(0)
            val cleanValue = if (nullIndex >= 0) {
                value.copyOfRange(0, nullIndex)
            } else {
                value
            }
            val response = cleanValue.toString(Charsets.UTF_8)
            Log.d(TAG, "Characteristic ${characteristic.uuid} changed, value: $response")

            // Check if this is a version response
            if (response.startsWith("OK:version:")) {
                val version = response.substringAfter("OK:version:")
                Log.d(TAG, "Device version: $version")
                _deviceVersion.value = version
            }

            // 推論ログ（INFER:...）は専用イベントへ分離（精度チューニング用・毎秒到着するため CharacteristicChanged と混ぜない）
            if (response.startsWith("INFER:")) {
                repositoryScope.launch { _events.emit(BleEvent.InferenceLog(response)) }
                return
            }

            repositoryScope.launch {
                _events.emit(BleEvent.CharacteristicChanged(characteristic, cleanValue))
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Characteristic write failed for ${characteristic?.uuid} with status $status")
                repositoryScope.launch { _events.emit(BleEvent.Error("Write failed with status $status"))}

                // Notify pending write of failure
                pendingWriteResult?.complete(false)
                pendingWriteResult = null
            } else {
                // Notify pending write of success
                pendingWriteResult?.complete(true)
                pendingWriteResult = null
            }
        }
    }

    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                val previousBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)

                Log.d(TAG, "Bond state changed for device ${device?.address}: ${previousBondState} -> ${bondState}")

                when (bondState) {
                    BluetoothDevice.BOND_BONDED -> {
                        Log.d(TAG, "Device bonded: ${device?.address}")
                        _connectionState.value = ConnectionState.Paired(device!!)
                        connectGatt(device)
                        context.unregisterReceiver(this)
                    }
                    BluetoothDevice.BOND_NONE -> {
                        Log.e(TAG, "Bonding failed or was cancelled for device ${device?.address}")
                        _connectionState.value = ConnectionState.Error("Bonding failed")
                        context.unregisterReceiver(this)
                    }
                    BluetoothDevice.BOND_BONDING -> {
                        Log.d(TAG, "Bonding with device ${device?.address}...")
                        _connectionState.value = ConnectionState.Pairing
                    }
                }
            }
        }
    }

    fun connect(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to device ${device.name} (${device.address}), bond state: ${device.bondState}")

        // 通常モード：ペアリング処理
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> {
                _connectionState.value = ConnectionState.Paired(device)
                connectGatt(device)
            }
            BluetoothDevice.BOND_NONE -> {
                Log.d(TAG, "Device not bonded. Starting bonding process.")
                _connectionState.value = ConnectionState.Pairing
                val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(bondStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(bondStateReceiver, filter)
                }

                if (!device.createBond()) {
                    Log.e(TAG, "Failed to start bonding.")
                    _connectionState.value = ConnectionState.Error("Failed to start bonding")
                    context.unregisterReceiver(bondStateReceiver)
                }
            }
            BluetoothDevice.BOND_BONDING -> {
                Log.d(TAG, "Device is already bonding.")
                _connectionState.value = ConnectionState.Pairing
            }
        }
    }

    private fun connectGatt(device: BluetoothDevice) {
        Log.d(TAG, "Proceeding with GATT connection to ${device.address}")
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting from device")
        try {
            context.unregisterReceiver(bondStateReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered, which is fine.
        }
        bluetoothGatt?.disconnect()
    }

    fun close() {
        Log.d(TAG, "Closing GATT connection")
        try {
            context.unregisterReceiver(bondStateReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered, which is fine.
        }
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    fun requestMtu(mtu: Int): Boolean {
        Log.d(TAG, "Requesting MTU of $mtu")
        return bluetoothGatt?.requestMtu(mtu) ?: false
    }

    fun requestHighPriorityConnection(): Boolean {
        val success = bluetoothGatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        Log.d(TAG, "Requesting high priority connection successful: $success")
        return success ?: false
    }

    fun sendCommand(command: String): Boolean {
        val characteristic = commandCharacteristic
        if (characteristic == null) {
            Log.e(TAG, "Command characteristic not found")
            return false
        }
        Log.d(TAG, "Sending command: $command")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt?.writeCharacteristic(
                characteristic,
                command.toByteArray(Charsets.UTF_8),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.value = command.toByteArray(Charsets.UTF_8)
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            bluetoothGatt?.writeCharacteristic(characteristic) ?: false
        }
    }

    /**
     * 送信を1件ずつ直列化する版（Phase 11: 通知転送用）。
     * Write Request（応答あり）は前の書込完了前に次を投げると失敗するため、
     * Mutex で呼び出しを順序付けし、onCharacteristicWrite の完了を待ってから次へ進む。
     * 既存 [sendCommand] は UI 由来の呼び出し元に影響させないためそのまま残す。
     */
    suspend fun sendCommandSerial(command: String): Boolean = writeMutex.withLock {
        val characteristic = commandCharacteristic
        if (characteristic == null) {
            Log.e(TAG, "Command characteristic not found")
            return@withLock false
        }
        Log.d(TAG, "Sending command (serial): $command")

        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        pendingWriteResult = deferred

        val initiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt?.writeCharacteristic(
                characteristic,
                command.toByteArray(Charsets.UTF_8),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.value = command.toByteArray(Charsets.UTF_8)
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            bluetoothGatt?.writeCharacteristic(characteristic) ?: false
        }

        if (!initiated) {
            pendingWriteResult = null
            return@withLock false
        }

        // 書込完了コールバックを待つ（2秒タイムアウト: デバイス応答遅延で永続ブロックを防ぐ）
        val ok = withTimeoutOrNull(TimeConstants.SERIAL_WRITE_TIMEOUT_MS) { deferred.await() } ?: false
        if (pendingWriteResult === deferred) pendingWriteResult = null
        ok
    }

    internal fun sendAck(ackValue: ByteArray): Boolean {
        val characteristic = commandCharacteristic
        if (characteristic == null) {
            Log.e(TAG, "ACK characteristic not found")
            return false
        }
        // Log.d(TAG, "Sending ACK: ${ackValue.toString(Charsets.UTF_8)}") // ACK is very frequent, so logging is disabled.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt?.writeCharacteristic(
                characteristic,
                ackValue,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.value = ackValue
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            bluetoothGatt?.writeCharacteristic(characteristic) ?: false
        }
    }

    /**
     * Get device firmware version
     * Sends GET:version command and waits for response
     */
    fun getVersion(): Boolean {
        return sendCommand(BleConstants.CMD_GET_VERSION)
    }

    /**
     * 学習済みモーションモデルをマイコンへ送信（セグメント化バイナリ）。
     * COMMAND特性へ [0xAA][0x55][seq][total][status][payload] フレームを順次書き込む。
     * Write Request（応答あり）のため writeMutex で直列化し、各フレームの onCharacteristicWrite 完了を待つ。
     * ※ 受信側（マイコン onWrite で再構築 → LittleFS 保存）は Phase 2 で対応。
     */
    suspend fun sendMotionModel(model: com.pirorin215.btclockmob.data.MotionModel): Boolean = writeMutex.withLock {
        val characteristic = commandCharacteristic
        if (characteristic == null) {
            Log.e(TAG, "Command characteristic not found")
            return@withLock false
        }
        val payload = buildMotionModelPayload(model)
        val frames = chunkFrames(payload)
        Log.d(TAG, "Sending motion model: ${model.patternCount} patterns, ${payload.size}B in ${frames.size} frames")

        for ((idx, frame) in frames.withIndex()) {
            val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
            pendingWriteResult = deferred
            val initiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bluetoothGatt?.writeCharacteristic(
                    characteristic,
                    frame,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.value = frame
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                bluetoothGatt?.writeCharacteristic(characteristic) ?: false
            }
            if (!initiated) {
                pendingWriteResult = null
                return@withLock false
            }
            val ok = withTimeoutOrNull(TimeConstants.SERIAL_WRITE_TIMEOUT_MS) { deferred.await() } ?: false
            if (pendingWriteResult === deferred) pendingWriteResult = null
            if (!ok) {
                Log.e(TAG, "motion model frame ${idx + 1}/${frames.size} failed")
                return@withLock false
            }
        }
        Log.d(TAG, "Motion model sent successfully")
        true
    }

    /** モデルを送信ペイロード(リトルエンディアン)へシリアライズ */
    private fun buildMotionModelPayload(model: com.pirorin215.btclockmob.data.MotionModel): ByteArray {
        val dim = com.pirorin215.btclockmob.data.MotionFeatures.DIM
        val nameBytes = model.labels.map { it.toByteArray(Charsets.UTF_8) }
        var size = 2                              // N, D
        for (nb in nameBytes) size += 1 + nb.size + 4 * dim
        val bb = java.nio.ByteBuffer.allocate(size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.put(model.patternCount.toByte())
        bb.put(dim.toByte())
        for (i in model.labels.indices) {
            val nb = nameBytes[i]
            bb.put(nb.size.toByte())
            bb.put(nb)
            for (v in model.centroids[i]) bb.putFloat(v)
        }
        return bb.array()
    }

    /** ペイロードを BLEフレーム [0xAA][0x55][seq][total][status][chunk] に分割 */
    private fun chunkFrames(payload: ByteArray): List<ByteArray> {
        val chunkSize = 180   // MTUネゴ後を想定しつつ余裕を持つ
        val total = (payload.size + chunkSize - 1) / chunkSize
        check(total in 1..255) { "motion model too large: ${payload.size}B / $total frames" }
        val frames = ArrayList<ByteArray>(total)
        var seq = 0
        var off = 0
        while (off < payload.size) {
            val len = minOf(chunkSize, payload.size - off)
            val status: Byte = if (off + len >= payload.size) 0xFF.toByte() else 0x00
            val frame = ByteArray(5 + len)
            frame[0] = 0xAA.toByte(); frame[1] = 0x55.toByte()
            frame[2] = seq.toByte(); frame[3] = total.toByte(); frame[4] = status
            System.arraycopy(payload, off, frame, 5, len)
            frames.add(frame)
            off += len; seq++
        }
        return frames
    }

}
