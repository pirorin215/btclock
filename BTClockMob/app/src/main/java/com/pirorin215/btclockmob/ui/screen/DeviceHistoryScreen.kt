package com.pirorin215.btclockmob.ui.screen

import android.content.Intent
import android.net.Uri
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
import org.koin.androidx.compose.koinViewModel
import com.pirorin215.btclockmob.R
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

    BackHandler(onBack = {
        if (isSelectionMode) {
            viewModel.exitSelectionMode()
        } else {
            onBackClick()
        }
    })

    var showConfirmDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSelectionMode) {
                        Text("${selectedEntries.size} 選択中")
                    } else {
                        Text(stringResource(R.string.device_history_title))
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
                        isHighlighted = false,
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
