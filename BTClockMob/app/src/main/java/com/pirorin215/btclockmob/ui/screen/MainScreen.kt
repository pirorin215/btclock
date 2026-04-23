package com.pirorin215.btclockmob.ui.screen

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.pirorin215.btclockmob.data.ConnectionState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pirorin215.btclockmob.viewModel.AppSettingsViewModel
import com.pirorin215.btclockmob.viewModel.BleOperation
import com.pirorin215.btclockmob.service.BleScanService
import com.pirorin215.btclockmob.viewModel.MainViewModel
// import com.pirorin215.btclockmob.viewModel.DeviceStatusViewModel // Removed
import com.pirorin215.btclockmob.R
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch

private const val TAG = "MainScreen"

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun MainScreen(
    appSettingsViewModel: AppSettingsViewModel
) {
    val context = LocalContext.current
    val viewModel: MainViewModel = viewModel() // ViewModel is already created and provided by compositionLocal in MainActivity's setContent
    val connectionState by viewModel.connectionState.collectAsState() // Use viewModel
    val deviceInfo by viewModel.deviceInfo.collectAsState() // Use viewModel
    val logs: List<String> by viewModel.logs.collectAsState()
    val fileList by viewModel.fileList.collectAsState()
    val fileTransferState by viewModel.fileTransferState.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val currentFileTotalSize by viewModel.currentFileTotalSize.collectAsState()
    val currentOperation by viewModel.currentOperation.collectAsState()
    val transferKbps by viewModel.transferKbps.collectAsState()
    val transcriptionState by viewModel.transcriptionState.collectAsState()
    val transcriptionResult by viewModel.transcriptionResult.collectAsState()

    var showLogs by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAppSettings by remember { mutableStateOf(false) }
    var showTodoDetailScreen by remember { mutableStateOf<String?>(null) } // New state for TodoDetailScreen visibility and todoId

    var showLogDownloadScreen by remember { mutableStateOf(false) }
    var showAppLogPanel by remember { mutableStateOf(false) } // New state for AppLogCard visibility
    var showGoogleTasksSyncSettings by remember { mutableStateOf(false) } // New state for GoogleTasksSyncSettingsScreen
    var showDeviceHistoryScreen by remember { mutableStateOf(false) }
    var showWavSaveFolderDialog by remember { mutableStateOf(false) } // New state for WAV save folder dialog
    var showAdpcmTestScreen by remember { mutableStateOf(false) } // New state for AdpcmTestScreen

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val isRefreshing by viewModel.isLoadingGoogleTasks.collectAsState()

    val googleSignInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { intent ->
                viewModel.handleSignInResult(intent, onSuccess = {
                    scope.launch { snackbarHostState.showSnackbar("Google サインイン成功！") }
                }, onFailure = { e ->
                    scope.launch { snackbarHostState.showSnackbar("Google サインイン失敗: ${e.message}") }
                })
            }
        } else {
            scope.launch { snackbarHostState.showSnackbar("Google サインインキャンセルされました。") }
        }
    }

    LaunchedEffect(fileTransferState) {
        if (fileTransferState.startsWith("Success")) {
            scope.launch {
                snackbarHostState.showSnackbar("ファイル保存完了: ${fileTransferState.substringAfter("Success: ")}")
            }
        } else if (fileTransferState.startsWith("Error")) {
            scope.launch {
                snackbarHostState.showSnackbar("エラー: ${fileTransferState.substringAfter("Error: ")}")
            }
        }
    }

    // Observe lifecycle events to start/stop low power location updates
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                Log.d(TAG, "ON_RESUME: Starting low power location updates.")
                viewModel.startLowPowerLocationUpdates()
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                Log.d(TAG, "ON_PAUSE: Stopping low power location updates.")
                viewModel.stopLowPowerLocationUpdates()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    when {
        showSettings -> {
            SettingsScreen(viewModel = viewModel, onBack = { showSettings = false })
        }
        showAppSettings -> {
            AppSettingsScreen(appSettingsViewModel = appSettingsViewModel, onBack = { showAppSettings = false })
        }

        showLogDownloadScreen -> {
            LogDownloadScreen(viewModel = viewModel, onBack = { showLogDownloadScreen = false })
        }
        showGoogleTasksSyncSettings -> {
            GoogleTasksSyncSettingsScreen(
                viewModel = viewModel,
                appSettingsViewModel = appSettingsViewModel,
                onBack = { showGoogleTasksSyncSettings = false },
                onSignInIntent = { intent -> googleSignInLauncher.launch(intent) } // Provide new callback
            )
        }
        showDeviceHistoryScreen -> {
            DeviceHistoryScreen(onBackClick = { showDeviceHistoryScreen = false })
        }
        showAdpcmTestScreen -> {
            AdpcmTestScreen(onBack = { showAdpcmTestScreen = false })
        }
        else -> {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                topBar = {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.main_screen_title))
                                Spacer(modifier = Modifier.width(8.dp))
                                val statusColor = if (connectionState is ConnectionState.Connected) Color.Green else Color.Red
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .background(color = statusColor, shape = CircleShape)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (connectionState is ConnectionState.Connected) stringResource(R.string.status_connected) else stringResource(R.string.status_disconnected),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                IconButton(
                                    onClick = {
                                        viewModel.stopAppServices()
                                        (context as? Activity)?.finish()
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.stop_app_content_description))
                                }
                                Spacer(modifier = Modifier.width(24.dp))
                                IconButton(
                                    onClick = {
                                        viewModel.forceReconnectBle()
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Autorenew, contentDescription = stringResource(R.string.force_reconnect_ble_content_description))
                                }
                                Spacer(modifier = Modifier.width(24.dp))
                                IconButton(
                                    onClick = {
                                        showDeviceHistoryScreen = true
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.ShowChart, contentDescription = stringResource(R.string.show_device_history_content_description))
                                }
                            }
                        },
                        actions = {
                            var expanded by remember { mutableStateOf(false) }
                            IconButton(onClick = { expanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options_content_description))
                            }
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_app_settings)) },
                                    onClick = {
                                        showAppSettings = true
                                        expanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_google_tasks_sync_settings)) },
                                    onClick = {
                                        showGoogleTasksSyncSettings = true
                                        expanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_recorder_settings)) },
                                    onClick = {
                                        showSettings = true
                                        expanded = false
                                    },
                                    enabled = connectionState is ConnectionState.Connected
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_recorder_log)) },
                                    onClick = {
                                        showLogDownloadScreen = true
                                        expanded = false
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_device_history)) },
                                    onClick = {
                                        showDeviceHistoryScreen = true
                                        expanded = false
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_wav_save_folder)) },
                                    onClick = {
                                        showWavSaveFolderDialog = true
                                        expanded = false
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_adpcm_test)) },
                                    onClick = {
                                        showAdpcmTestScreen = true
                                        expanded = false
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_app_log)) },
                                    onClick = {
                                        showAppLogPanel = !showAppLogPanel // Toggle visibility
                                        expanded = false
                                    }
                                )

                            }
                        }
                    )
                }
            ) { innerPadding ->
                val apiKeyStatus by appSettingsViewModel.apiKeyStatus.collectAsState()

                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.syncTranscriptionResultsWithGoogleTasks() },
                    modifier = Modifier.fillMaxSize().padding(innerPadding)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        ApiKeyWarningCard(
                            apiKeyStatus = apiKeyStatus,
                            onNavigateToSettings = { showAppSettings = true }
                        )
                        SummaryInfoCard(deviceInfo = deviceInfo)
                        // Move FileDownloadSection above TranscriptionResultPanel
                        FileDownloadSection(
                            fileList = fileList,
                            fileTransferState = fileTransferState,
                            downloadProgress = downloadProgress,
                            totalFileSize = currentFileTotalSize,
                            isBusy = currentOperation != BleOperation.IDLE,
                            transferKbps = transferKbps,
                            onDownloadClick = { viewModel.downloadFile(it) }
                        )
                        // TranscriptionResultPanel now takes flexible space
                        TranscriptionResultScreen(viewModel = viewModel, appSettingsViewModel = appSettingsViewModel, onBack = { })
                    }
                    // AppLogCard as an overlay at the bottom
                    if (showAppLogPanel) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .heightIn(max = 200.dp) // Limit height of the log panel
                        ) {
                            AppLogCard(
                                logs = logs,
                                onDismiss = { showAppLogPanel = false },
                                onClearLogs = { viewModel.clearLogs() }
                            )
                        }
                    }

                    // WAV Save Folder Dialog
                    if (showWavSaveFolderDialog) {
                        AlertDialog(
                            onDismissRequest = { showWavSaveFolderDialog = false },
                            title = {
                                Text(stringResource(R.string.wav_save_folder_title))
                            },
                            text = {
                                Column {
                                    Text(
                                        stringResource(R.string.wav_save_folder_path),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "※ファイルマネージャーアプリなどで上記パスを開くとWAVファイルを確認できます。",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = { showWavSaveFolderDialog = false }
                                ) {
                                    Text(stringResource(R.string.close))
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

