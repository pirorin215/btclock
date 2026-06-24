package com.pirorin215.btclockmob.ui.screen

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.pirorin215.btclockmob.data.ConnectionState
import com.pirorin215.btclockmob.bondedBikeClockDeviceNames
import com.pirorin215.btclockmob.resolveTargetDeviceName
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.pirorin215.btclockmob.viewModel.AppSettingsViewModel
import com.pirorin215.btclockmob.viewModel.MainViewModel
import com.pirorin215.btclockmob.viewModel.DeviceHistoryViewModel
import com.pirorin215.btclockmob.viewModel.ImuDataCaptureViewModel
import com.pirorin215.btclockmob.viewModel.MotionLearningViewModel
import com.pirorin215.btclockmob.viewModel.InferenceLogViewModel
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

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@SuppressLint("MissingPermission")
@Composable
fun MainScreen(
    mainViewModel: MainViewModel = koinViewModel(),
    appSettingsViewModel: AppSettingsViewModel = koinViewModel(),
    historyViewModel: DeviceHistoryViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val connectionState by mainViewModel.connectionState.collectAsState()
    val targetDevicePref by appSettingsViewModel.targetDeviceName.collectAsState()
    val logs: List<String> by mainViewModel.logs.collectAsState()

    val historyEntries by historyViewModel.deviceHistoryEntries.collectAsState()
    val entriesForList by historyViewModel.filteredHistoryEntries.collectAsState()
    val isFilterConnectionOnly by historyViewModel.isFilterConnectionOnly.collectAsState()
    val isGroupByDay by historyViewModel.isGroupByDay.collectAsState()
    val homeLocation by historyViewModel.homeLocation.collectAsState()
    val isSelectionMode by historyViewModel.isSelectionMode.collectAsState()
    val selectedEntries by historyViewModel.selectedEntries.collectAsState()
    val routeCenterOffset by appSettingsViewModel.routeCenterOffset.collectAsState()
    val routeLabelFontSize by appSettingsViewModel.routeLabelFontSize.collectAsState()

    var showAppSettings by remember { mutableStateOf(false) }
    var showAppLogPanel by remember { mutableStateOf(false) }
    var showKeyCodeSettings by remember { mutableStateOf(false) }
    var showNotificationDebug by remember { mutableStateOf(false) }
    var showMotionLearning by remember { mutableStateOf(false) }
    var showInferenceLog by remember { mutableStateOf(false) }

    var showConfirmDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }
    var showRouteDialogDate by remember { mutableStateOf<String?>(null) }
    var showDeleteDateDialog by remember { mutableStateOf<String?>(null) }
    
    // 現在選択されている日のエントリを動的に取得（フィルター変更に追従）
    val routeEntries = remember(entriesForList, showRouteDialogDate) {
        if (showRouteDialogDate == null) emptyList()
        else entriesForList.filter { 
            java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault()).format(java.util.Date(it.timestamp)) == showRouteDialogDate 
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // KeyCodeSettingsViewModelの初期化 (Koin)
    val keyCodeSettingsViewModel: KeyCodeSettingsViewModel = koinViewModel()
    val imuDataCaptureViewModel: ImuDataCaptureViewModel = koinViewModel()
    val motionLearningViewModel: MotionLearningViewModel = koinViewModel()
    val inferenceLogViewModel: InferenceLogViewModel = koinViewModel()

    BackHandler(enabled = isSelectionMode || showAppSettings || showKeyCodeSettings || showNotificationDebug || showMotionLearning || showInferenceLog) {
        if (showAppSettings) {
            showAppSettings = false
        } else if (showKeyCodeSettings) {
            showKeyCodeSettings = false
        } else if (showNotificationDebug) {
            showNotificationDebug = false
        } else if (showMotionLearning) {
            showMotionLearning = false
        } else if (showInferenceLog) {
            showInferenceLog = false
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
                mainViewModel.startLowPowerLocationUpdates()
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                Log.d(TAG, "ON_PAUSE: Stopping low power location updates.")
                mainViewModel.stopLowPowerLocationUpdates()
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
        showNotificationDebug -> {
            NotificationDebugScreen(
                mainViewModel = mainViewModel,
                onBack = { showNotificationDebug = false }
            )
        }
        showMotionLearning -> {
            MotionLearningScreen(
                viewModel = motionLearningViewModel,
                captureViewModel = imuDataCaptureViewModel,
                onBack = { showMotionLearning = false }
            )
        }
        showInferenceLog -> {
            InferenceLogScreen(
                viewModel = inferenceLogViewModel,
                onBack = { showInferenceLog = false }
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
                                Box {
                                    val pairedDevices = remember { bondedBikeClockDeviceNames(context) }
                                    var showDeviceMenu by remember { mutableStateOf(false) }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(stringResource(R.string.main_screen_title))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        val statusColor = if (connectionState is ConnectionState.Connected) Color.Green else Color.Red
                                        Box(
                                            modifier = Modifier
                                                .size(12.dp)
                                                .background(color = statusColor, shape = CircleShape)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        val disconnectedText = stringResource(R.string.status_disconnected)
                                        // 接続中は接続デバイス名、未接続時は接続ターゲット名を表示。
                                        // ターゲット = 設定で選んだ名前（未選択なら先頭BikeClockデバイスを自動使用）。
                                        // ドット色で接続状態を示す（緑=接続中／赤=未接続）。
                                        val targetName = remember(targetDevicePref, connectionState) {
                                            resolveTargetDeviceName(targetDevicePref, context)
                                        }
                                        val headerName = (connectionState as? ConnectionState.Connected)?.device?.name
                                            ?.takeIf { it.isNotBlank() }
                                            ?: targetName
                                        // ペアリング済みが2台以上なら、BT名タップでターゲット切替（ドロップダウン）
                                        val switchable = pairedDevices.size >= 2
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.clickable(enabled = switchable) { showDeviceMenu = true }
                                        ) {
                                            Text(
                                                text = headerName.ifBlank { disconnectedText },
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            if (switchable) {
                                                Icon(
                                                    Icons.Filled.ArrowDropDown,
                                                    contentDescription = "デバイス切り替え",
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = showDeviceMenu,
                                        onDismissRequest = { showDeviceMenu = false }
                                    ) {
                                        pairedDevices.forEach { name ->
                                            DropdownMenuItem(
                                                text = { Text(name) },
                                                onClick = {
                                                    appSettingsViewModel.saveTargetDeviceName(name)
                                                    showDeviceMenu = false
                                                }
                                            )
                                        }
                                    }
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
                                        mainViewModel.forceReconnectBle()
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
                                        text = { Text("通知デバッグ") },
                                        onClick = {
                                            showNotificationDebug = true
                                            expanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("モーション学習") },
                                        onClick = {
                                            showMotionLearning = true
                                            expanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("推論ログ") },
                                        onClick = {
                                            showInferenceLog = true
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
                                            mainViewModel.stopAppServices()
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
                Column(
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
                        // 各日付の展開状態を管理
                        var expandedDate by remember { mutableStateOf<String?>(null) }
                        var showCalendar by remember { mutableStateOf(false) }

                        // フィルター操作（選択モード以外で表示）
                        if (!isSelectionMode) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = isFilterConnectionOnly,
                                    onClick = { historyViewModel.toggleFilterConnectionOnly() },
                                    label = { Text("接続/切断のみ") },
                                    leadingIcon = if (isFilterConnectionOnly) {
                                        { Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                                    } else null
                                )

                                FilterChip(
                                    selected = showCalendar,
                                    onClick = { showCalendar = !showCalendar },
                                    label = { Text("カレンダー") },
                                    leadingIcon = if (showCalendar) {
                                        { Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                                    } else {
                                        { Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                                    }
                                )
                            }
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            // 最後に「切断」されたエントリを特定
                            val lastDisconnection = entriesForList.find { it.isDisconnection }

                            Column(modifier = Modifier.fillMaxSize()) {
                                val groupedEntries = entriesForList.groupBy { entry ->
                                    java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault()).format(java.util.Date(entry.timestamp))
                                }

                                if (showCalendar) {
                                    ActivityCalendar(
                                        activeDates = groupedEntries.keys,
                                        onDateClick = { date ->
                                            if (groupedEntries.containsKey(date)) {
                                                expandedDate = date
                                                // スクロール処理
                                                val keys = groupedEntries.keys.toList()
                                                val index = keys.indexOf(date)
                                                if (index != -1) {
                                                    scope.launch {
                                                        listState.animateScrollToItem(index)
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }

                                Box(modifier = Modifier.weight(1f)) {
                                    LazyColumn(
                                        state = listState,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        groupedEntries.forEach { (date, entries) ->
                                            val isExpanded = expandedDate == date
                                            
                                            stickyHeader {
                                                DateHeader(
                                                    date = date,
                                                    entries = entries,
                                                    isExpanded = isExpanded,
                                                    onToggleExpand = {
                                                        expandedDate = if (isExpanded) null else date
                                                    },
                                                    onShowRoute = {
                                                        showRouteDialogDate = date
                                                    },
                                                    onDeleteDate = { showDeleteDateDialog = date },
                                                    canDelete = !isSelectionMode

                                                )
                                            }
                                            
                                            if (isExpanded) {
                                                items(entries) { entry ->
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
                            }
                        }
                    }

                    // AppLogCard as an overlay at the bottom
                    if (showAppLogPanel) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp) // Limit height of the log panel
                        ) {
                            AppLogCard(
                                logs = logs,
                                onDismiss = { showAppLogPanel = false },
                                onClearLogs = { mainViewModel.clearLogs() }
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
    showDeleteDateDialog?.let { date ->
        AlertDialog(
            onDismissRequest = { showDeleteDateDialog = null },
            title = { Text(stringResource(R.string.confirm_delete_date_title)) },
            text = { Text(stringResource(R.string.confirm_delete_date_message, date)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        historyViewModel.deleteEntriesByDate(date)
                        showDeleteDateDialog = null
                    }
                ) {
                    Text(stringResource(R.string.delete_button))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDateDialog = null }
                ) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        )
    }

    if (showRouteDialogDate != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showRouteDialogDate = null },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            var selectedPoint by remember { mutableStateOf<com.pirorin215.btclockmob.data.DeviceHistoryEntry?>(null) }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // ヘッダー
                    TopAppBar(
                        title = { Text("$showRouteDialogDate の移動ルート") },
                        navigationIcon = {
                            IconButton(onClick = { showRouteDialogDate = null }) {
                                Icon(Icons.Default.Close, contentDescription = "閉じる")
                            }
                        },
                        actions = {
                            FilterChip(
                                selected = isFilterConnectionOnly,
                                onClick = { historyViewModel.toggleFilterConnectionOnly() },
                                label = { Text("接続/切断のみ") },
                                leadingIcon = if (isFilterConnectionOnly) {
                                    { Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                                } else null,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    )

                    Box(modifier = Modifier.weight(1f)) {
                        // 地図（ルート）表示
                        RouteVisualizer(
                            entries = routeEntries,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            centerOffset = routeCenterOffset,
                            initialFontSize = routeLabelFontSize,
                            onFontSizeChanged = { newSize ->
                                appSettingsViewModel.saveRouteLabelFontSize(newSize)
                            },
                            onPointSelected = { point ->
                                selectedPoint = point
                            }
                        )

                        // 凡例
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp)
                                .background(
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                    MaterialTheme.shapes.small
                                )
                                .padding(8.dp)
                        ) {
                            Column {
                                LegendItem(Color.Green, "開始")
                                LegendItem(Color.Red, "終了")
                                LegendItem(Color(0xFF4CAF50), "接続")
                                LegendItem(Color(0xFFFF9800), "切断")
                                LegendItem(Color(0xFF2196F3), "記録")
                            }
                        }

                        // 選択されたポイントの情報表示
                        selectedPoint?.let { point ->
                            Card(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(16.dp)
                                    .fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(point.timestamp))
                                    val type = when {
                                        point.isPeriodic -> "記録"
                                        point.isDisconnection -> "切断"
                                        else -> "接続"
                                    }
                                    
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "$time [$type]",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        IconButton(onClick = { selectedPoint = null }) {
                                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    
                                    if (point.address != null) {
                                        Text(
                                            text = point.address,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                    
                                    Text(
                                        text = "Lat: ${point.latitude}, Lon: ${point.longitude}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    )
                                    
                                    Spacer(modifier = Modifier.height(8.dp))
                                    
                                    Button(
                                        onClick = { openMapForEntry(context, point) },
                                        modifier = Modifier.align(Alignment.End),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Google Mapで開く")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape)
                .border(1.dp, Color.White, CircleShape)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall)
    }
}
