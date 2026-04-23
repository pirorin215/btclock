package com.pirorin215.btclockmob.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.pirorin215.btclockmob.R
import androidx.core.content.FileProvider
import com.pirorin215.btclockmob.data.FileUtil
import com.pirorin215.btclockmob.data.TranscriptionResult
import com.pirorin215.btclockmob.viewModel.MainViewModel
import com.pirorin215.btclockmob.viewModel.AppSettingsViewModel
import com.pirorin215.btclockmob.ui.theme.PendingBackgroundColor
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TranscriptionResultPanel(viewModel: MainViewModel, appSettingsViewModel: AppSettingsViewModel, modifier: Modifier = Modifier) {
    val transcriptionResults by viewModel.transcriptionResults.collectAsState()
    val scope = rememberCoroutineScope()
    var showDeleteAllConfirmDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedConfirmDialog by remember { mutableStateOf(false) }
    val fontSize by viewModel.transcriptionFontSize.collectAsState()
    val selectedFileNames by viewModel.selectedFileNames.collectAsState()
    val currentlyPlayingFile by viewModel.currentlyPlayingFile.collectAsState()

    var selectedResultForDetail by remember { mutableStateOf<TranscriptionResult?>(null) }
    val audioDirName by viewModel.audioDirName.collectAsState()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    val listState = rememberLazyListState()
    var previousResultCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(transcriptionResults.size) {
        if (transcriptionResults.size > previousResultCount) {
            listState.animateScrollToItem(0)
        }
        previousResultCount = transcriptionResults.size
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isSelectionMode = selectedFileNames.isNotEmpty()
                    val transcriptionCount by viewModel.transcriptionCount.collectAsState()
                    val audioFileCount by viewModel.audioFileCount.collectAsState()

                    Text(
                        stringResource(R.string.transcription_audio_stats, transcriptionCount, audioFileCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(onClick = {
                        if (isSelectionMode) {
                            showDeleteSelectedConfirmDialog = true
                        } else {
                            showDeleteAllConfirmDialog = true
                        }
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(if (isSelectionMode) R.string.delete_selected_content_description else R.string.clear_all_content_description))
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                if (transcriptionResults.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.no_data_message))
                        }
                    }
                } else {
                    items(items = transcriptionResults, key = { it.fileName }) { result ->
                        val isSelected = selectedFileNames.contains(result.fileName)
                        val isSelectionMode = selectedFileNames.isNotEmpty()

                        val backgroundColor = when {
                            isSelected -> MaterialTheme.colorScheme.primaryContainer
                            result.transcriptionStatus == "PENDING" -> PendingBackgroundColor
                            result.transcriptionStatus == "FAILED" -> MaterialTheme.colorScheme.errorContainer
                            result.googleTaskId != null -> MaterialTheme.colorScheme.surfaceVariant
                            else -> MaterialTheme.colorScheme.surface
                        }
                        val contentColor = when {
                            isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                            result.transcriptionStatus == "PENDING" -> MaterialTheme.colorScheme.onSurfaceVariant
                            result.transcriptionStatus == "FAILED" -> MaterialTheme.colorScheme.onErrorContainer
                            result.googleTaskId != null -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onSurface
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(backgroundColor)
                                .combinedClickable(
                                    onClick = {
                                        if (isSelectionMode) {
                                            viewModel.toggleSelection(result.fileName)
                                        } else {
                                            selectedResultForDetail = result
                                        }
                                    },
                                    onLongClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        if (!isSelectionMode) {
                                            viewModel.toggleSelection(result.fileName)
                                        }
                                    }
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val dateTimeInfo = FileUtil.getRecordingDateTimeInfo(result.fileName)
                            Column(modifier = Modifier.padding(horizontal = 8.dp).width(80.dp)) {
                                Text(text = dateTimeInfo.date, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = contentColor)
                                Text(text = dateTimeInfo.time, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = contentColor)
                            }
                            Text(
                                text = result.transcription,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSize.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(end = 16.dp),
                                color = contentColor
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
            selectedResultForDetail?.let { result ->
                val audioFilePath = FileUtil.getAudioFilePath(context, result.fileName)
                val isPlayingAudioFile = remember(currentlyPlayingFile, audioFilePath) {
                    derivedStateOf { currentlyPlayingFile == audioFilePath }
                }
                TranscriptionDetailBottomSheet(
                    result = result,
                    fontSize = fontSize,
                    audioFileExists = FileUtil.audioFileExists(context, result.fileName),
                    audioDirName = audioDirName,
                    isPlaying = isPlayingAudioFile.value,
                    onPlay = { transcriptionResult ->
                        viewModel.playAudioFile(transcriptionResult)
                    },
                    onStop = { viewModel.stopAudioFile() },
                    onDelete = { transcriptionResult ->
                        scope.launch {
                            viewModel.removeTranscriptionResult(transcriptionResult)
                            selectedResultForDetail = null
                        }
                    },
                    onSave = { originalResult, newText, newNote ->
                        scope.launch {
                            viewModel.updateTranscriptionResult(originalResult, newText, newNote)
                            selectedResultForDetail = null
                        }
                    },
                    onRetranscribe = { transcriptionResult ->
                        scope.launch {
                            viewModel.retranscribe(transcriptionResult)
                            selectedResultForDetail = null
                        }
                    },
                    onDismiss = { selectedResultForDetail = null }
                )
            }

            if (showDeleteAllConfirmDialog) {
                AlertDialog(
                    onDismissRequest = { showDeleteAllConfirmDialog = false },
                    title = { Text(stringResource(R.string.clear_all_dialog_title)) },
                    text = { Text(stringResource(R.string.clear_all_dialog_message)) },
                    confirmButton = {
                        Button(onClick = {
                            scope.launch {
                                viewModel.clearTranscriptionResults()
                                showDeleteAllConfirmDialog = false
                            }
                        }) { Text(stringResource(R.string.delete_button)) }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { showDeleteAllConfirmDialog = false }) { Text(stringResource(R.string.cancel_button)) }
                    }
                )
            }
            if (showDeleteSelectedConfirmDialog) {
                AlertDialog(
                    onDismissRequest = { showDeleteSelectedConfirmDialog = false },
                    title = { Text(stringResource(R.string.delete_selected_dialog_title)) },
                    text = { Text(stringResource(R.string.delete_selected_dialog_message, selectedFileNames.size)) },
                    confirmButton = {
                        Button(onClick = {
                            scope.launch {
                                viewModel.removeTranscriptionResults(selectedFileNames)
                                showDeleteSelectedConfirmDialog = false
                            }
                        }) { Text(stringResource(R.string.delete_button)) }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { showDeleteSelectedConfirmDialog = false }) { Text(stringResource(R.string.cancel_button)) }
                    }
                )
            }
        }
    }
}
