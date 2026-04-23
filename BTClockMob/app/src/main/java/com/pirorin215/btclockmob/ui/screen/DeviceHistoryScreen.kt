package com.pirorin215.btclockmob.ui.screen

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import com.pirorin215.btclockmob.R
import com.pirorin215.btclockmob.battery.BatteryPrediction
import com.pirorin215.btclockmob.viewModel.DeviceHistoryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceHistoryScreen(
    onBackClick: () -> Unit,
    viewModel: DeviceHistoryViewModel = koinViewModel()
) {
    val entries by viewModel.deviceHistoryEntries.collectAsState()
    val entriesForList by viewModel.deviceHistoryEntriesForList.collectAsState()
    val homeLocation by viewModel.homeLocation.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    val selectedEntries by viewModel.selectedEntries.collectAsState()
    val context = LocalContext.current

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // チャートでタッチされている位置に対応するタイムスタンプを保持
    var highlightedTimestamp by remember { mutableStateOf<Long?>(null) }

    BackHandler(onBack = {
        if (isSelectionMode) {
            viewModel.exitSelectionMode()
        } else {
            onBackClick()
        }
    })

    var showConfirmDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }

    // 電池残り時間を計算
    val batteryPrediction = remember(entries) {
        predictBatteryLifeFromHistory(entries)
    }

    val predictionText = remember(batteryPrediction) {
        when (batteryPrediction) {
            is BatteryPrediction.RemainingTime -> {
                val totalHours = batteryPrediction.hours
                val days = totalHours / 24
                val hours = totalHours % 24
                if (days > 0) {
                    "残り${days}日${hours}時間"
                } else {
                    "残り${totalHours}時間"
                }
            }
            else -> null
        }
    }

    // 最新サイクルの経過時間を計算
    val currentCycleElapsedHours = remember(entries) {
        calculateCurrentCycleElapsedTime(entries)
    }

    val elapsedTimeText = remember(currentCycleElapsedHours) {
        currentCycleElapsedHours?.let { totalHours ->
            val days = totalHours / 24
            val hours = totalHours % 24
            if (days > 0) {
                "経過${days}日${hours}時間"
            } else {
                "経過${totalHours}時間"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isSelectionMode) {
                            Text("${selectedEntries.size} 選択中")
                        } else {
                            Column {
                                Text(stringResource(R.string.device_history_title))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    elapsedTimeText?.let { text ->
                                        Text(
                                            text = text,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                    predictionText?.let { text ->
                                        Text(
                                            text = text,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isSelectionMode) {
                            viewModel.exitSelectionMode()
                        } else {
                            onBackClick()
                        }
                    }) {
                        Icon(Icons.Filled.ArrowBack, stringResource(R.string.back_button_content_description))
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        // 選択モード時は削除ボタンを表示
                        IconButton(
                            onClick = { showDeleteSelectedDialog = true },
                            enabled = selectedEntries.isNotEmpty()
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "選択した項目を削除")
                        }
                    } else {
                        // 通常モード時は全削除ボタンを表示
                        IconButton(onClick = { showConfirmDialog = true }) {
                            Icon(Icons.Filled.Delete, stringResource(R.string.clear_history_content_description))
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.no_device_history_yet), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // 固定部分: チャート
                DeviceHistoryChart(
                    history = entries,
                    onDataPointClicked = { timestamp ->
                        Log.d("DeviceHistoryScreen", "onDataPointClicked received timestamp: $timestamp")
                        // チャートのデータは正規化されているので、完全に合致しない場合は一つ前のポイントとして処理
                        val index = entriesForList.indexOfFirst { it.timestamp <= timestamp }
                        Log.d("DeviceHistoryScreen", "Found index in entriesForList: $index, entriesForList.size: ${entriesForList.size}")
                        if (index >= 0) {
                            // ハイライトするタイムスタンプを更新
                            highlightedTimestamp = entriesForList[index].timestamp
                            Log.d("DeviceHistoryScreen", "Scrolling to item at position: ${index + 1}")
                            coroutineScope.launch {
                                // +1 to account for the header item
                                // ドラッグ中は即座にスクロール（アニメーションなし）
                                listState.scrollToItem(index + 1)
                                Log.d("DeviceHistoryScreen", "Scrolled to item")
                            }
                        } else {
                            highlightedTimestamp = null
                            Log.d("DeviceHistoryScreen", "Index not found (index < 0)")
                        }
                    },
                    onTouchEnd = {
                        // タッチが終了したらハイライトを解除
                        highlightedTimestamp = null
                    }
                )

                // スクロール可能部分: リスト
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
                    DeviceHistoryCard(
                        entry = entry,
                        home = homeLocation,
                        isSelectionMode = isSelectionMode,
                        isSelected = selectedEntries.contains(entry.timestamp),
                        isHighlighted = highlightedTimestamp == entry.timestamp,
                        onClick = { clickedEntry ->
                            if (isSelectionMode) {
                                // 選択モード時はトグル
                                viewModel.toggleSelection(clickedEntry.timestamp)
                            } else {
                                // 通常モード時は地図を開く
                                clickedEntry.latitude?.let { lat ->
                                    clickedEntry.longitude?.let { lon ->
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
                        },
                        onLongClick = { longClickedEntry ->
                            // 長押しで選択モードに入る
                            if (!isSelectionMode) {
                                viewModel.enterSelectionMode(longClickedEntry.timestamp)
                            }
                        }
                    )
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
                        viewModel.clearHistory()
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
                        viewModel.deleteSelectedEntries()
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
