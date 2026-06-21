package com.pirorin215.btclockmob.ui.screen

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pirorin215.btclockmob.data.ConnectionState
import com.pirorin215.btclockmob.viewModel.ImuDataCaptureViewModel

private val IMU_LABELS = listOf("駐車", "解除", "走行", "カーブ", "停車", "アイドリング")

/**
 * Phase 14-B: IMU データ採取画面
 * 「データ取得」→ デバイスのリングバッファ（直近10秒）をチャンク受信 → ラベル/メモ付き CSV をシェア。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImuDataCaptureScreen(
    viewModel: ImuDataCaptureViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()

    var selectedLabel by remember { mutableStateOf(IMU_LABELS.first()) }
    var memo by remember { mutableStateOf("") }
    var labelExpanded by remember { mutableStateOf(false) }

    val isConnected = connectionState is ConnectionState.Connected

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("IMUデータ採取") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 接続ステータス
            val statusText = when (val c = connectionState) {
                is ConnectionState.Connected -> "接続中: ${c.device.name ?: "BikeClock"}"
                else -> "未接続"
            }
            val statusColor = if (isConnected) Color(0xFF4CAF50) else Color(0xFFF44336)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.1f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(statusColor, shape = RoundedCornerShape(6.dp))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(
                "デバイスのリングバッファ（直近10秒・50Hz）を取得し、CSVで持ち出します。" +
                    "取得したい動作をさせてから「データ取得」を押してください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ラベル選択（固定リスト Dropdown）
            ExposedDropdownMenuBox(
                expanded = labelExpanded,
                onExpandedChange = { labelExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("ラベル") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = labelExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = labelExpanded,
                    onDismissRequest = { labelExpanded = false }
                ) {
                    IMU_LABELS.forEach { label ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { selectedLabel = label; labelExpanded = false }
                        )
                    }
                }
            }

            // メモ
            OutlinedTextField(
                value = memo,
                onValueChange = { memo = it },
                label = { Text("メモ（任意）") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )

            // 取得ボタン
            val busy = state is ImuDataCaptureViewModel.CaptureState.Requesting ||
                state is ImuDataCaptureViewModel.CaptureState.Recording ||
                state is ImuDataCaptureViewModel.CaptureState.Receiving
            Button(
                onClick = { viewModel.requestDump(selectedLabel, memo) },
                modifier = Modifier.fillMaxWidth(),
                enabled = isConnected && !busy
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("データ取得開始")
            }

            // 進捗・結果
            when (val s = state) {
                is ImuDataCaptureViewModel.CaptureState.Requesting -> {
                    Text("デバイスに要求中...", style = MaterialTheme.typography.bodyMedium)
                }
                is ImuDataCaptureViewModel.CaptureState.Recording -> {
                    val remaining = ((1f - s.progress) * 10f).toInt().coerceAtLeast(0)
                    Column {
                        Text("録音中... 残り ${remaining} 秒", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { s.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                is ImuDataCaptureViewModel.CaptureState.Receiving -> {
                    val total = s.totalChunks.coerceAtLeast(1)
                    Column {
                        Text(
                            "受信中... ${s.receivedChunks}/${s.totalChunks} チャンク",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { s.receivedChunks.toFloat() / total },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                is ImuDataCaptureViewModel.CaptureState.Complete -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF4CAF50).copy(alpha = 0.1f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "取得完了: ${s.sampleCount} サンプル",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2E7D32)
                            )
                            if (s.missingChunks.isNotEmpty()) {
                                Text(
                                    "※欠損チャンク ${s.missingChunks.size}件あり（部分データ）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFFF9800)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            when {
                                s.saving -> Text(
                                    "ダウンロードに保存中...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                s.savedFileName != null -> Column {
                                    Text(
                                        "✅ ダウンロードに保存: ${s.savedFileName}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF2E7D32)
                                    )
                                    if (s.addedToTraining) Text(
                                        "✅ 学習データに追加済み",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF2E7D32)
                                    )
                                }
                                else -> Text(
                                    "⚠ 保存に失敗しました",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFF44336)
                                )
                            }
                        }
                    }
                }
                is ImuDataCaptureViewModel.CaptureState.Error -> {
                    Text(
                        "エラー: ${s.message}",
                        color = Color(0xFFF44336),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                ImuDataCaptureViewModel.CaptureState.Idle -> { /* 初期状態 */ }
            }

            // CSVシェアボタン（保存済みファイルを共有）
            Button(
                onClick = {
                    val uri = viewModel.savedShareUri
                    if (uri != null) {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(intent, "IMUデータを共有"))
                        }.onFailure {
                            Toast.makeText(context, "共有に失敗: ${it.message}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, "保存されたファイルがありません", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = state is ImuDataCaptureViewModel.CaptureState.Complete &&
                    (state as ImuDataCaptureViewModel.CaptureState.Complete).savedFileName != null
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("CSVをシェア")
            }
        }
    }
}
