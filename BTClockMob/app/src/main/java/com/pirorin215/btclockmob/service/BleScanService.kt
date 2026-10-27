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
import com.pirorin215.btclockmob.bondedBikeClockDeviceNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

class BleScanService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1 // Moved here
    }

    private val TAG = "BleScanService"
    private val CHANNEL_ID = "BleScanServiceChannel"
    private val SCAN_TIMEOUT_MS = 30000L // 30秒のスキャンタイムアウト

    private lateinit var bluetoothManager: BluetoothManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanJob: Job? = null
    // 接続を受け付けるデバイス名の集合。
    // ユーザー選択あり → その1台のみ / 未選択 → ペアリング済みBikeClock全台
    // (bikeclock=バイク / cycleclock=自転車 の切り替えをアプリ操作なしで成立させる)
    private var acceptedNames: Set<String> = emptySet()

    // --- スキャン開始のレート制限対策（自転車レーダー gardia_radar の RadarScanService と同一の防御） ---
    // RSSI接続スキップ後の「1秒ごとの再スキャン」がAndroidのレート制限(30秒間5回の
    // startScan)に当たると、startScanが沈黙失敗してスキャン停止のまま発見できなくなり、
    // 「近づいても二度と再接続されない」状態になる(v1.4.1で発生)。これを防ぐため
    // スキャン開始を6秒間隔に制限し、onScanFailed時は指数バックオフで再試行する。
    private var scanRetryJob: Job? = null
    private val SCAN_RETRY_INTERVAL_MS = 10_000L // スキャン失敗時の再試行間隔(定周期)
    private var lastScanStartTime = 0L
    private val MIN_SCAN_INTERVAL_MS = 6000L // Android制限(30秒間5回)に対する安全マージン
    // startScanが受理されてスキャン中か。stopScanは「登録済みコールバック」に対してのみ
    // 呼ぶ必要がある: 未登録状態でstopScanするとBluetoothスタックがアプリのスキャン登録を
    // unregisterし("onError(): Unregistering the app")、以後のstartScanがすべて
    // error 2(APPLICATION_REGISTRATION_FAILED)になる(Pixel 10で実測・v1.4.4/v1.4.5で発生)
    private var isScanning = false

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
        // 全マッチ報告(自転車レーダー gardia_radar と同一)。
        // FIRST_MATCH は「フィルタに最初にマッチした1回だけ報告」のため、RSSI接続フィルタで
        // スキップし続けると以後の発見が報告されず「近づいても二度と再接続されない」状態になる(v1.4.2で発生)。
        // ALL_MATCHES ならアドバタイズのたびに報告され、近づいたときのRSSI判定で再接続できる。
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        .setMatchMode(ScanSettings.MATCH_MODE_STICKY) // 不定期なアドバタイズも検出
        .build()

    /**
     * 接続候補デバイス名のリストからスキャンフィルタを構築する。
     * ScanFilterはリスト化するとOR条件になるため、複数台のBikeClockを
     * ハードウェアフィルタのまま待ち受けることができる。
     */
    private fun buildScanFilters(names: List<String>): List<ScanFilter> {
        return names.filter { it.isNotBlank() }
            .map { ScanFilter.Builder().setDeviceName(it).build() }
    }

    /**
     * 接続候補デバイス名を解決する。
     * - ユーザー選択(preferred)があればその1台のみ(従来動作を維持)
     * - 未選択ならペアリング済みの全 "BikeClock-" デバイス
     *   (どれが広告していても接続=乗り物の切り替えが自動)
     * - ペアリング済みが1台も無ければ空(呼び出し元でスキャン中止)
     */
    private fun resolveAcceptedNames(preferred: String): List<String> {
        val p = preferred.trim()
        if (p.isNotBlank()) return listOf(p)
        return bondedBikeClockDeviceNames(this)
    }

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
        scanRetryJob?.cancel()
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

    /**
     * スキャン開始（スロットル付き・v1.4.2）。
     * MIN_SCAN_INTERVAL_MS(6秒)以内の開始要求は待機してから実際に開始する。
     * gardia_radar の RadarScanService と同一の方式。
     */
    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "Bluetooth not available or not enabled.")
            // ユーザーにBluetoothを有効にするよう促す必要がある
            return
        }

        // スキャン開始間隔の制限（Androidの "scanning too frequently" を回避）
        val now = System.currentTimeMillis()
        val elapsed = now - lastScanStartTime
        if (elapsed < MIN_SCAN_INTERVAL_MS) {
            val waitMs = MIN_SCAN_INTERVAL_MS - elapsed
            Log.w(TAG, "Scan throttled - waiting ${waitMs}ms (min interval=${MIN_SCAN_INTERVAL_MS}ms)")
            scanRetryJob?.cancel()
            scanRetryJob = CoroutineScope(Dispatchers.IO).launch {
                delay(waitMs)
                doStartBleScan()
            }
            return
        }

        doStartBleScan()
    }

    /**
     * スキャン開始本体。stopScanはstopBleScan内のガード(isScanning)により
     * スキャン実行中のときしか呼ばれない。
     */
    @SuppressLint("MissingPermission")
    private fun doStartBleScan() {
        lastScanStartTime = System.currentTimeMillis()
        scanRetryJob?.cancel()
        stopBleScan()

        // 接続先を解決: ユーザー選択があればその1台、未選択ならペアリング済みの全BikeClockデバイス。
        // スキャン開始時に解決することで、起動時の設定読み込みレースを回避する。
        val names = resolveAcceptedNames(BleScanServiceManager.targetDeviceName)
        if (names.isEmpty()) {
            Log.w(TAG, "No target BikeClock device available (not paired / not selected). Skipping scan.")
            return
        }
        acceptedNames = names.toSet()
        Log.d(TAG, "Starting BLE scan in service... (accepted names: $names)")
        bluetoothAdapter?.bluetoothLeScanner?.startScan(buildScanFilters(names), scanSettings, bleScanCallback)
        isScanning = true

        // Removed scan timeout job for continuous scanning
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        // 未登録状態でのstopScanを禁止(stopBleScanの全呼び出し元はこのガードを通る)
        if (!isScanning) return
        Log.d(TAG, "Stopping BLE scan in service.")
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(bleScanCallback)
        isScanning = false
    }

    private val bleScanCallback = @SuppressLint("MissingPermission") object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            Log.d(TAG, "ScanResult: Device found - ${result.device.name} (${result.device.address}), RSSI: ${result.rssi}")
            val deviceName = result.device.name ?: "(no name)"

            if (deviceName in acceptedNames) {
                Log.d(TAG, "Target device '$deviceName' found! Signaling MainViewModel to connect.")
                // Stop scanning to allow the ViewModel to handle the connection.
                // The ViewModel will be responsible for restarting the scan later.
                stopBleScan()
                CoroutineScope(Dispatchers.IO).launch {
                    BleScanServiceManager.emitDeviceFound(result.device, result.rssi)
                }
            } else {
                Log.v(TAG, "Ignoring device: $deviceName")
            }
            }

            override fun onBatchScanResults(results: List<ScanResult>) {
            super.onBatchScanResults(results)
            Log.d(TAG, "onBatchScanResults: ${results.size} devices found.")
            // バッチスキャン結果の中から接続候補デバイスを探す
            results.forEach { result ->
                val deviceName = result.device.name ?: ""
                if (deviceName in acceptedNames) {
                    Log.d(TAG, "Target device '$deviceName' found in batch! Signaling MainViewModel to connect.")
                    // Do NOT stop scan here; continue scanning for automatic re-detection
                    CoroutineScope(Dispatchers.IO).launch {
                        BleScanServiceManager.emitDeviceFound(result.device, result.rssi)
                    }
                    return // 1つ見つけたら処理を終了
                }
            }
            }

            override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "Scan failed with error code: $errorCode")
            isScanning = false

            // 定周期(10秒)で再試行(gardia_radar の RadarScanService と同一方式)
            scanRetryJob?.cancel()
            scanRetryJob = CoroutineScope(Dispatchers.IO).launch {
                delay(SCAN_RETRY_INTERVAL_MS)
                startBleScan()
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
