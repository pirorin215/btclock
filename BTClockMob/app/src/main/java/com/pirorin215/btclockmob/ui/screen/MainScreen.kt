package com.pirorin215.btclockmob.ui.screen

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.pirorin215.btclockmob.data.ConnectionState
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
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
import org.koin.androidx.compose.koinViewModel
import com.pirorin215.btclockmob.viewModel.KeyCodeSettingsViewModel
import com.pirorin215.btclockmob.viewModel.KeyCodeSettingsViewModelFactory
import com.pirorin215.btclockmob.viewModel.AppSettingsViewModel
import com.pirorin215.btclockmob.viewModel.MainViewModel
import com.pirorin215.btclockmob.viewModel.DeviceHistoryViewModel
import com.pirorin215.btclockmob.R
import androidx.compose.ui.res.stringResource
import org.koin.android.ext.android.get
import kotlinx.coroutines.launch

private const val TAG = "MainScreen"

private fun openMapForEntry(context: android.content.Context, entry: com.pirorin215.btclockmob.data.DeviceHistoryEntry) {
    entry.latitude?.let { lat ->
        entry.longitude?.let { lon ->
            val mapUri = Uri.parse("geo:$lat,$lon?q=$lat,$lon")
            val mapIntent = Intent(Intent.ACTION_VIEW, mapUri)
            mapIntent.setPackage("com.google.android.apps.maps")
            if (mapIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(mapIntent)
            } else {
                val webMapUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lon")
                val webMapIntent = Intent(Intent.ACTION_VIEW, webMapUri)
                context.startActivity(webMapIntent)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun MainScreen(
    appSettingsViewModel: AppSettingsViewModel,
    historyViewModel: DeviceHistoryViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val viewModel: MainViewModel = viewModel() // ViewModel is already created and provided by compositionLocal in MainActivity's setContent
    val connectionState by viewModel.connectionState.collectAsState() // Use viewModel
    val logs: List<String> by viewModel.logs.collectAsState()

    val historyEntries by historyViewModel.deviceHistoryEntries.collectAsState()
    val entriesForList by historyViewModel.deviceHistoryEntriesForList.collectAsState()
    val homeLocation by historyViewModel.homeLocation.collectAsState()
    val isSelectionMode by historyViewModel.isSelectionMode.collectAsState()
    val selectedEntries by historyViewModel.selectedEntries.collectAsState()

    var showAppSettings by remember { mutableStateOf(false) }
    var showAppLogPanel by remember { mutableStateOf(false) }
    var showKeyCodeSettings by remember { mutableStateOf(false) }

    var showConfirmDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // KeyCodeSettingsViewModelの初期化
    val activity = context as Activity
    val keyCodeSettingsViewModel: KeyCodeSettingsViewModel = viewModel(
        factory = KeyCodeSettingsViewModelFactory(
            activity.application,
            activity.get(),
            activity.get()
        )
    )

    BackHandler(enabled = isSelectionMode || showAppSettings || showKeyCodeSettings) {
        if (showAppSettings) {
            showAppSettings = false
        } else if (showKeyCodeSettings) {
            showKeyCodeSettings = false
        } else if (isSelectionMode) {
            historyViewModel.exitSelectionMode()
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
        showAppSettings -> {
            AppSettingsScreen(appSettingsViewModel = appSettingsViewModel, onBack = { showAppSettings = false })
        }
        showKeyCodeSettings -> {
            KeyCodeSettingsScreen(
                viewModel = keyCodeSettingsViewModel,
                onBack = { showKeyCodeSettings = false }
            )
        }
        else -> {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                topBar = {
                    TopAppBar(
                        title = {
                            if (isSelectionMode) {
                                Text("${selectedEntries.size} 選択中")
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.main_screen_title))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    val statusColor = if (connectionState is ConnectionState.Connected) Color.Green else Color.Red
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .background(color = statusColor, shape = CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (connectionState is ConnectionState.Connected) stringResource(R.string.status_connected) else stringResource(R.string.status_disconnected),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            if (isSelectionMode) {
                                IconButton(onClick = { historyViewModel.exitSelectionMode() }) {
                                    Icon(Icons.Default.Close, contentDescription = "選択解除")
                                }
                            }
                        },
                        actions = {
                            if (isSelectionMode) {
                                IconButton(
                                    onClick = { showDeleteSelectedDialog = true },
                                    enabled = selectedEntries.isNotEmpty()
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "選択した項目を削除")
                                }
                            } else {
                                IconButton(
                                    onClick = {
                                        viewModel.forceReconnectBle()
                                    }
                                ) {
                                    Icon(Icons.Default.Autorenew, contentDescription = stringResource(R.string.force_reconnect_ble_content_description))
                                }
                                IconButton(
                                    onClick = { showConfirmDialog = true }
                                ) {
                                    Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.clear_history_content_description))
                                }

                                var expanded by remember { mutableStateOf(false) }
                                IconButton(onClick = { expanded = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options_content_description))
                                }
                                DropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("キー設定") },
                                        onClick = {
                                            showKeyCodeSettings = true
                                            expanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.menu_app_settings)) },
                                        onClick = {
                                            showAppSettings = true
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
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.menu_quit_app)) },
                                        leadingIcon = { Icon(Icons.Default.Stop, contentDescription = null) },
                                        onClick = {
                                            viewModel.stopAppServices()
                                            (context as? Activity)?.finish()
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    )
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (historyEntries.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(stringResource(R.string.no_device_history_yet), style = MaterialTheme.typography.bodyLarge)
                        }
                    } else {
                        // 最後に「切断」されたエントリを特定
                        val lastDisconnection = entriesForList.find { it.isDisconnection }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                DeviceHistoryHeader()
                            }

                            items(entriesForList) { entry ->
                                val isLastDisconnection = lastDisconnection?.timestamp == entry.timestamp
                                DeviceHistoryCard(
                                    entry = entry,
                                    home = homeLocation,
                                    isSelectionMode = isSelectionMode,
                                    isSelected = selectedEntries.contains(entry.timestamp),
                                    isHighlighted = isLastDisconnection,
                                    onClick = { clickedEntry ->
                                        if (isSelectionMode) {
                                            historyViewModel.toggleSelection(clickedEntry.timestamp)
                                        } else {
                                            openMapForEntry(context, clickedEntry)
                                        }
                                    },
                                    onLongClick = { longClickedEntry ->
                                        if (!isSelectionMode) {
                                            historyViewModel.enterSelectionMode(longClickedEntry.timestamp)
                                        }
                                    }
                                )
                            }
                        }

                        // 最新の駐車位置がある場合、右下に「バイクを探す」ボタンを表示
                        if (lastDisconnection != null && !isSelectionMode) {
                            ExtendedFloatingActionButton(
                                onClick = { openMapForEntry(context, lastDisconnection) },
                                icon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
                                text = { Text(stringResource(R.string.find_my_bike)) },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(16.dp),
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    // AppLogCard as an overlay at the bottom
                    if (showAppLogPanel) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .heightIn(max = 200.dp) // Limit height of the log panel
                        ) {
                            AppLogCard(
                                logs = logs,
                                onDismiss = { showAppLogPanel = false },
                                onClearLogs = { viewModel.clearLogs() }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text(stringResource(R.string.confirm_delete_title)) },
            text = { Text(stringResource(R.string.confirm_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        historyViewModel.clearHistory()
                        showConfirmDialog = false
                    }
                ) {
                    Text(stringResource(R.string.delete_button))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showConfirmDialog = false }
                ) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        )
    }

    if (showDeleteSelectedDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectedDialog = false },
            title = { Text("選択した項目を削除") },
            text = { Text("選択した${selectedEntries.size}件の履歴を削除しますか？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        historyViewModel.deleteSelectedEntries()
                        showDeleteSelectedDialog = false
                    }
                ) {
                    Text(stringResource(R.string.delete_button))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteSelectedDialog = false }
                ) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        )
    }
}
