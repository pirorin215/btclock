package com.pirorin215.btclockmob.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pirorin215.btclockmob.MainActivity
import com.pirorin215.btclockmob.R // リソースファイルが必要になります
import com.pirorin215.btclockmob.BleScanServiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

class BleScanService : Service() {

    companion object {
        const val DEVICE_NAME = "BikeClock-0001"
        const val NOTIFICATION_ID = 1 // Moved here
    }

    private val TAG = "BleScanService"
    private val CHANNEL_ID = "BleScanServiceChannel"
    private val SCAN_TIMEOUT_MS = 30000L // 30秒のスキャンタイムアウト

    private lateinit var bluetoothManager: BluetoothManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanJob: Job? = null

    // Bluetooth状態変化を監視するBroadcastReceiver
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_OFF -> {
                        Log.d(TAG, "Bluetooth turned OFF - stopping scan")
                        stopBleScan()
                    }
                    BluetoothAdapter.STATE_ON -> {
                        Log.d(TAG, "Bluetooth turned ON - starting scan")
                        startBleScan()
                    }
                }
            }
        }
    }

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER) // バッテリー消費を抑える
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES) // 全ての一致を報告
        .setMatchMode(ScanSettings.MATCH_MODE_STICKY) // 不定期なアドバタイズも検出
        .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT) // 1つのアドバタイズで報告
        .setReportDelay(0L) // 0L: no batching, report results immediately
        .build()

    private val scanFilters = listOf(
        ScanFilter.Builder().setDeviceName(DEVICE_NAME).build()
    )

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BleScanService onCreate")
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        createNotificationChannel()

        // Bluetooth状態変化を監視するBroadcastReceiverを登録
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(bluetoothStateReceiver, filter)

        // Subscribe to restart scan events
        CoroutineScope(Dispatchers.IO).launch {
            BleScanServiceManager.restartScanFlow.collect {
                Log.d(TAG, "Received restart scan signal. Restarting BLE scan.")
                startBleScan()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "BleScanService onStartCommand")

        // Android 14以降でconnectedDeviceタイプのforeground serviceには権限が必要
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val hasBluetoothConnect = checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasBluetoothScan = checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED

            if (!hasBluetoothConnect && !hasBluetoothScan) {
                Log.e(TAG, "Bluetooth permissions not granted. Cannot start foreground service.")
                // 権限がない場合はforeground serviceを開始せず、サービスを停止
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification().build()) // Moved here
        startBleScan()

        return START_STICKY // サービスが強制終了されても再起動する
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "BleScanService onDestroy")
        stopBleScan()
        stopForeground(true)
        // BroadcastReceiverの登録解除
        try {
            unregisterReceiver(bluetoothStateReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "BluetoothStateReceiver was not registered")
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // 今回はバインドサービスとして使用しない
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "Bluetooth not available or not enabled.")
            // ユーザーにBluetoothを有効にするよう促す必要がある
            return
        }

        stopBleScan() // 既存のスキャンがあれば停止

        Log.d(TAG, "Starting BLE scan in service...")
        bluetoothAdapter?.bluetoothLeScanner?.startScan(scanFilters, scanSettings, bleScanCallback)

        // Removed scan timeout job for continuous scanning
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        Log.d(TAG, "Stopping BLE scan in service.")
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(bleScanCallback)
        // Removed scanJob?.cancel() as scanJob is no longer used for timeout
    }

    private val bleScanCallback = @SuppressLint("MissingPermission") object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            Log.d(TAG, "ScanResult: Device found - ${result.device.name} (${result.device.address}), RSSI: ${result.rssi}")
            val deviceName = result.device.name ?: "(no name)"

            if (deviceName == DEVICE_NAME) {
                Log.d(TAG, "Target device '$deviceName' found! Signaling MainViewModel to connect.")
                // Stop scanning to allow the ViewModel to handle the connection.
                // The ViewModel will be responsible for restarting the scan later.
                stopBleScan()
                CoroutineScope(Dispatchers.IO).launch {
                    BleScanServiceManager.emitDeviceFound(result.device)
                }
            } else {
                Log.v(TAG, "Ignoring device: $deviceName")
            }
            }

            override fun onBatchScanResults(results: List<ScanResult>) {
            super.onBatchScanResults(results)
            Log.d(TAG, "onBatchScanResults: ${results.size} devices found.")
            // バッチスキャン結果の中からターゲットデバイスを探す
            results.forEach { result ->
                val deviceName = result.device.name ?: ""
                if (deviceName == DEVICE_NAME) {
                    Log.d(TAG, "Target device '$deviceName' found in batch! Signaling MainViewModel to connect.")
                    // Do NOT stop scan here; continue scanning for automatic re-detection
                    CoroutineScope(Dispatchers.IO).launch {
                        BleScanServiceManager.emitDeviceFound(result.device)
                    }
                    return // 1つ見つけたら処理を終了
                }
            }
            }
    } // Closing brace for bleScanCallback object.

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                            "BLE Scan Service Channel",
                            NotificationManager.IMPORTANCE_DEFAULT            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildNotification(): NotificationCompat.Builder {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            action = "SHOW_UI" // Custom action
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BTClockMob")
            .setContentText("バックグラウンドでBLEデバイスをスキャン中...")
            .setSmallIcon(R.drawable.ic_notification_alert) // Use existing notification icon
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true) // 通知を削除不可にする（フォアグラウンドサービス維持のため）
    }
} // Final closing brace for BleScanService class.
