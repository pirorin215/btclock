package com.pirorin215.btclockmob

import com.pirorin215.btclockmob.viewModel.MainViewModel
import com.pirorin215.btclockmob.LocationTracker
import com.pirorin215.btclockmob.viewModel.AppSettingsViewModel

import android.Manifest
import android.app.Application
import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Build.VERSION_CODES
import android.os.PowerManager
import android.provider.Settings
import android.content.Context
import android.content.ComponentName
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pirorin215.btclockmob.data.AppSettingsRepository
import com.pirorin215.btclockmob.data.LastKnownLocationRepository
import com.pirorin215.btclockmob.service.BleScanService
import com.pirorin215.btclockmob.ui.screen.MainScreen
import com.pirorin215.btclockmob.ui.theme.BTClockMobTheme
import com.pirorin215.btclockmob.viewModel.BleConnectionManager // Add this import
import org.koin.android.ext.android.get
import org.koin.android.ext.android.getKoin
import org.koin.androidx.viewmodel.ext.android.viewModel

import kotlinx.coroutines.flow.MutableSharedFlow // Add this import
import kotlinx.coroutines.flow.MutableStateFlow // Add this import
import kotlinx.coroutines.launch // Add this import

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            // KoinでViewModelをインジェクション
            val mainViewModel: MainViewModel by viewModel()
            val appSettingsViewModel: AppSettingsViewModel by viewModel()

            val themeMode by mainViewModel.themeMode.collectAsState()

            BTClockMobTheme(themeMode = themeMode) {
                BleApp(
                    modifier = Modifier.fillMaxSize(),
                    mainViewModel = mainViewModel,
                    appSettingsViewModel = appSettingsViewModel
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // バックグラウンドで処理を継続させるため、Activity破棄時にサービスを停止しないように変更
        // val serviceIntent = Intent(this, BleScanService::class.java)
        // stopService(serviceIntent)
    }
}

private const val TAG = "BleApp"

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val cn = ComponentName(context, com.pirorin215.btclockmob.service.BikeNotificationListener::class.java)
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat != null && flat.contains(cn.flattenToString())
}

@Composable
fun BleApp(
    modifier: Modifier = Modifier,
    mainViewModel: MainViewModel,
    appSettingsViewModel: AppSettingsViewModel
) { // Updated signature
    val context = LocalContext.current
    val activity = context as Activity

    // Permission handling
    val requiredPermissions = remember {
        val basePermissions = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            basePermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            basePermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        Log.d(TAG, "Required Permissions: ${basePermissions.joinToString()}")
        basePermissions
    }

    // 権限チェックとリクエスト
    var permissionsChecked by remember { mutableStateOf(false) }

    // 権限リクエストlauncher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        Log.d(TAG, "Permission result: all granted = $allGranted, details: $permissions")
        if (allGranted) {
            permissionsChecked = true
        }
    }

    LaunchedEffect(Unit) {
        val missingPermissions = requiredPermissions.filter { permission ->
            context.checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            Log.d(TAG, "Missing permissions: ${missingPermissions.joinToString()}")
            permissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            Log.d(TAG, "All permissions granted. Starting BleScanService.")
            permissionsChecked = true
        }
    }

    // 権限が付与されたらサービスを起動
    LaunchedEffect(permissionsChecked) {
        if (permissionsChecked) {
            val serviceIntent = Intent(context, BleScanService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }

    // Android 12以降でバックグラウンド許可とバッテリ最適化のチェック
    var showBatteryDialog by remember { mutableStateOf(false) }
    var isBatteryOptimized by remember { mutableStateOf(false) }
    var showLocationDialog by remember { mutableStateOf(false) }
    var isLocationNotAlways by remember { mutableStateOf(false) }
    var showNotificationAccessDialog by remember { mutableStateOf(false) }
    var isNotificationAccessGranted by remember { mutableStateOf(false) }

    LaunchedEffect(permissionsChecked) {
        if (permissionsChecked) {
            // バッテリ最適化チェック（Android 12以降）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

                // バッテリ最適化の確認
                // isIgnoringBatteryOptimizations() = true → 最適化されて「いる」＝制限なし
                // isIgnoringBatteryOptimizations() = false → 最適化されて「いない」＝制限あり
                val packageName = context.packageName
                val isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(packageName)
                isBatteryOptimized = !isIgnoringBatteryOptimizations

                Log.d(TAG, "Battery optimization check: ignoring=$isIgnoringBatteryOptimizations, optimized=$isBatteryOptimized")

                // バッテリ最適化がある場合、ダイアログを表示
                if (isBatteryOptimized) {
                    Log.d(TAG, "Battery optimization detected, showing dialog")
                    showBatteryDialog = true
                } else {
                    Log.d(TAG, "No battery optimization, dialog not shown")
                }
            }

            // 位置情報「常に許可」チェック（Android 10以降）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fineLocationGranted = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED

                val backgroundLocationGranted = context.checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED

                // バックグラウンド権限がない場合のみ警告表示
                isLocationNotAlways = !(fineLocationGranted && backgroundLocationGranted)

                Log.d(TAG, "Location permission check: fine=$fineLocationGranted, background=$backgroundLocationGranted, not_always=$isLocationNotAlways")

                if (isLocationNotAlways) {
                    Log.d(TAG, "Location permission not set to 'Always', showing dialog")
                    showLocationDialog = true
                }
            }

            // 通知アクセス（NotificationListenerService）チェック
            isNotificationAccessGranted = isNotificationListenerEnabled(context)
            if (!isNotificationAccessGranted) {
                Log.d(TAG, "Notification listener permission not granted, showing dialog")
                showNotificationAccessDialog = true
            }
        }
    }

    // バックグラウンド許可ダイアログ
    if (showBatteryDialog) {
        BatteryOptimizationDialog(
            isBatteryOptimized = isBatteryOptimized,
            onDismiss = { showBatteryDialog = false },
            onOpenSettings = {
                try {
                    val packageName = context.packageName
                    // アプリ詳細設定画面へ（そこからバッテリ最適化設定にアクセス可能）
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    Log.d(TAG, "Opening app settings for package: $packageName")
                    activity.startActivity(intent)
                    Log.d(TAG, "Settings started successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open settings: ${e.message}")
                    e.printStackTrace()
                }
                showBatteryDialog = false
            }
        )
    }

    // 位置情報権限ダイアログ
    if (showLocationDialog) {
        LocationPermissionDialog(
            onDismiss = { showLocationDialog = false },
            onOpenSettings = {
                try {
                    val packageName = context.packageName
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    Log.d(TAG, "Opening app location settings for package: $packageName")
                    activity.startActivity(intent)
                    Log.d(TAG, "Location settings started successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open location settings: ${e.message}")
                    e.printStackTrace()
                }
                showLocationDialog = false
            }
        )
    }

    // 通知アクセス権限ダイアログ
    if (showNotificationAccessDialog) {
        NotificationAccessPermissionDialog(
            onDismiss = { showNotificationAccessDialog = false },
            onOpenSettings = {
                try {
                    val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    Log.d(TAG, "Opening notification listener settings")
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open notification listener settings: ${e.message}")
                    e.printStackTrace()
                }
                showNotificationAccessDialog = false
            }
        )
    }

    // Main Screen Logic
    MainScreen(mainViewModel = mainViewModel, appSettingsViewModel = appSettingsViewModel) // Updated call site
}

@Composable
fun LocationPermissionDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(imageVector = Icons.Filled.Warning, contentDescription = "警告")
        },
        title = {
            Text("位置情報権限の設定が必要です")
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "位置情報権限が「常に許可」に設定されていません。\n\n" +
                            "このアプリはバックグラウンドで位置情報を取得するため、" +
                            "「常に許可」設定が必要です。",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "下のボタンから「アプリ権限」設定を開き、" +
                            "位置情報を「常に許可」に変更してください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text("アプリ権限を開く")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("後で")
            }
        }
    )
}

@Composable
fun BatteryOptimizationDialog(
    isBatteryOptimized: Boolean,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(imageVector = Icons.Filled.Warning, contentDescription = "警告")
        },
        title = {
            Text("バックグラウンド処理の許可が必要です")
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                if (isBatteryOptimized) {
                    Text(
                        "バッテリ最適化が有効になっています。\n\n" +
                                "このアプリはバックグラウンドで動作するため、" +
                                "バッテリ使用量を制限しないように設定してください。\n\n" +
                                "「最適化しない」を選択してください。",
                                style = MaterialTheme.typography.bodyMedium
                        )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "設定画面で許可を与えると、\n" +
                            "再インストール後も安定して動作します。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text("設定を開く")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("後で")
            }
        }
    )
}

@Composable
fun NotificationAccessPermissionDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(imageVector = Icons.Filled.Warning, contentDescription = "警告")
        },
        title = {
            Text("通知アクセス権限の設定が必要です")
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "このアプリがバックグラウンドで強制終了されずに動作し続けるためには、" +
                            "「通知アクセス権」の許可が必要です。\n\n" +
                            "※通知の読み取りなどは行いません。常時起動を保証するためのシステム機能として利用します。",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "下のボタンから設定画面を開き、「BTClockMob」の通知アクセスを許可してください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text("設定を開く")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("後で")
            }
        }
    )
}