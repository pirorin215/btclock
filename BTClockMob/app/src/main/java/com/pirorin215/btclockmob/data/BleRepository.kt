package com.pirorin215.btclockmob.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
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
}

@SuppressLint("MissingPermission")
class BleRepository(private val context: Context) {

    companion object {
        const val SERVICE_UUID_STRING = BleConstants.SERVICE_UUID_STRING
        const val COMMAND_UUID_STRING = BleConstants.COMMAND_UUID_STRING
        const val OTA_SERVICE_UUID_STRING = BleConstants.OTA_SERVICE_UUID_STRING
        const val OTA_CONTROL_UUID_STRING = BleConstants.OTA_CONTROL_UUID_STRING
        const val OTA_PACKET_UUID_STRING = BleConstants.OTA_PACKET_UUID_STRING
    }

    private val TAG = "BleRepository"
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var bluetoothGatt: BluetoothGatt? = null

    var commandCharacteristic: BluetoothGattCharacteristic? = null
    var responseCharacteristic: BluetoothGattCharacteristic? = null

    // OTA DFU characteristics
    var otaControlCharacteristic: BluetoothGattCharacteristic? = null
    var otaPacketCharacteristic: BluetoothGattCharacteristic? = null

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

                // まずOTA DFUサービスを探す（デバイスがDFUモードかどうかを判定）
                Log.d(TAG, "Checking for OTA DFU service: $OTA_SERVICE_UUID_STRING")
                val otaService = gatt.getService(UUID.fromString(OTA_SERVICE_UUID_STRING))
                if (otaService != null) {
                    Log.d(TAG, "OTA DFU service found! Device is in DFU mode.")
                    val displayName = if (gatt.device.name.isNullOrEmpty()) "(no name)" else gatt.device.name
                    Log.d(TAG, "DFU mode detected ($displayName). Setting up OTA characteristics.")

                    // OTA Control Point
                    otaControlCharacteristic = otaService.getCharacteristic(UUID.fromString(OTA_CONTROL_UUID_STRING))
                    Log.d(TAG, "OTA Control characteristic: ${otaControlCharacteristic?.uuid}")

                    // OTA Packet
                    otaPacketCharacteristic = otaService.getCharacteristic(UUID.fromString(OTA_PACKET_UUID_STRING))
                    Log.d(TAG, "OTA Packet characteristic: ${otaPacketCharacteristic?.uuid}")

                    if (otaControlCharacteristic != null && otaPacketCharacteristic != null) {
                        Log.d(TAG, "OTA DFU service discovered successfully")
                        repositoryScope.launch { _events.emit(BleEvent.Ready) }
                    } else {
                        Log.e(TAG, "OTA DFU service found but characteristics are missing")
                        disconnect()
                    }
                    return
                }

                // OTA DFUサービスが見つからない場合は通常モード：カスタムサービスを探索
                Log.d(TAG, "OTA DFU service not found. Checking for custom service.")
                // Store characteristics
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
                Log.d(TAG, "Descriptor written successfully. Repository is ready.")
                repositoryScope.launch { _events.emit(BleEvent.Ready) }
            } else {
                Log.e(TAG, "Descriptor write failed with status $status")
                disconnect()
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
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

        // DFUモード（AdaDFU）の場合はペアリングをスキップ
        // 注意: サービスディスカバリでOTAサービスの有無を最終判定
        val isDfuMode = device.name == BleConstants.DFU_DEVICE_NAME
        if (isDfuMode) {
            Log.d(TAG, "DFU mode detected. Skipping pairing, connecting directly.")
            _connectionState.value = ConnectionState.Connected(device)
            connectGatt(device)
            return
        }

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
            ) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.value = command.toByteArray(Charsets.UTF_8)
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            bluetoothGatt?.writeCharacteristic(characteristic) ?: false
        }
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
            ) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.value = ackValue
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            bluetoothGatt?.writeCharacteristic(characteristic) ?: false
        }
    }

    // --- OTA Functions ---

    /**
     * Get device firmware version
     * Sends GET:version command and waits for response
     */
    fun getVersion(): Boolean {
        return sendCommand(BleConstants.CMD_GET_VERSION)
    }

    /**
     * Start OTA DFU mode
     * Sends START_DFU command (0x01) to OTA control point
     */
    fun startOta(): Boolean {
        val characteristic = otaControlCharacteristic
        if (characteristic == null) {
            Log.e(TAG, "OTA control characteristic not found")
            Log.e(TAG, "  otaControlCharacteristic = $otaControlCharacteristic")
            Log.e(TAG, "  otaPacketCharacteristic = $otaPacketCharacteristic")
            return false
        }

        Log.d(TAG, "Starting OTA DFU mode")
        Log.d(TAG, "  Control characteristic: ${characteristic.uuid}")
        Log.d(TAG, "  Write type: ${characteristic.writeType}")
        Log.d(TAG, "  Permissions: ${characteristic.permissions}")

        // Nordic DFU START_DFU command: 0x01
        val command = byteArrayOf(0x01.toByte())
        Log.d(TAG, "  Command: ${command.joinToString { "0x%02X" }}")

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = bluetoothGatt?.writeCharacteristic(
                characteristic,
                command,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            Log.d(TAG, "  Write result (API 33+): $result")
            result == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.value = command
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            val result = bluetoothGatt?.writeCharacteristic(characteristic)
            Log.d(TAG, "  Write result (legacy): $result")
            result ?: false
        }
    }

    /**
     * Activate OTA and reset device
     * Sends ACTIVATE_DFU command (0x04) to OTA control point
     * This tells the device to apply the new firmware and restart
     */
    fun activateOta(): Boolean {
        val characteristic = otaControlCharacteristic
        if (characteristic == null) {
            Log.e(TAG, "OTA control characteristic not found for activation")
            return false
        }

        Log.d(TAG, "Activating OTA and resetting device")

        // Nordic DFU ACTIVATE_DFU command: 0x04
        val command = byteArrayOf(0x04.toByte())
        Log.d(TAG, "  Activate command: ${command.joinToString { "0x%02X" }}")

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = bluetoothGatt?.writeCharacteristic(
                characteristic,
                command,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            Log.d(TAG, "  Activate write result (API 33+): $result")
            result == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.value = command
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            val result = bluetoothGatt?.writeCharacteristic(characteristic)
            Log.d(TAG, "  Activate write result (legacy): $result")
            result ?: false
        }
    }

    /**
     * Send OTA firmware packet
     * Transfers firmware data in chunks (max 20 bytes per packet for Nordic DFU)
     *
     * @param data Full firmware data
     * @param offset Starting offset in data array
     * @param length Number of bytes to transfer
     */
    suspend fun sendOtaPacket(data: ByteArray, offset: Int, length: Int): Boolean {
        val characteristic = otaPacketCharacteristic
        if (characteristic == null) {
            Log.e(TAG, "OTA packet characteristic not found")
            return false
        }

        // Split data into chunks (max 20 bytes per packet for Nordic DFU)
        val chunkSize = 20
        var currentOffset = offset

        while (currentOffset < offset + length) {
            val remainingLength = (offset + length) - currentOffset
            val packetLength = minOf(chunkSize, remainingLength)

            val packet = data.copyOfRange(currentOffset, currentOffset + packetLength)

            // Create a CompletableDeferred to wait for write callback
            val writeResult = kotlinx.coroutines.CompletableDeferred<Boolean>()
            pendingWriteResult = writeResult

            // Queue the write operation
            val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bluetoothGatt?.writeCharacteristic(
                    characteristic,
                    packet,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.value = packet
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                bluetoothGatt?.writeCharacteristic(characteristic) ?: false
            }

            if (!queued) {
                pendingWriteResult = null
                Log.e(TAG, "Failed to queue OTA packet at offset $currentOffset")
                return false
            }

            // Wait for write callback with timeout
            val success = try {
                writeResult.await()
            } catch (e: Exception) {
                Log.e(TAG, "Write operation failed at offset $currentOffset", e)
                false
            }

            if (!success) {
                Log.e(TAG, "Failed to send OTA packet at offset $currentOffset")
                return false
            }

            currentOffset += packetLength

            // Small delay between packets to give the device time to process
            delay(10)

            // Additional check every 100 packets to ensure connection is still alive
            if ((currentOffset / chunkSize) % 100 == 0) {
                if (bluetoothGatt == null) {
                    Log.e(TAG, "GATT connection lost during OTA transfer at offset $currentOffset")
                    return false
                }
            }
        }

        return true
    }

}
