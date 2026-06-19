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
import androidx.core.content.FileProvider
import com.pirorin215.btclockmob.data.ConnectionState
import com.pirorin215.btclockmob.viewModel.ImuDataCaptureViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    val isConnected = connectionState is ConnectionState.Connected || connectionState is ConnectionState.Paired

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
                is ConnectionState.Paired -> "接続中: ${c.device.name ?: "BikeClock"}"
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
                state is ImuDataCaptureViewModel.CaptureState.Receiving
            Button(
                onClick = { viewModel.requestDump() },
                modifier = Modifier.fillMaxWidth(),
                enabled = isConnected && !busy
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("データ取得")
            }

            // 進捗・結果
            when (val s = state) {
                is ImuDataCaptureViewModel.CaptureState.Requesting -> {
                    Text("デバイスに要求中...", style = MaterialTheme.typography.bodyMedium)
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

            // CSVシェアボタン（完了時のみ）
            Button(
                onClick = {
                    val csv = viewModel.generateCsv(selectedLabel, memo)
                    if (csv.isEmpty()) {
                        Toast.makeText(context, "データがありません", Toast.LENGTH_SHORT).show()
                    } else {
                        shareImuCsv(context, csv, selectedLabel)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = state is ImuDataCaptureViewModel.CaptureState.Complete
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("CSVをシェア")
            }

            // 再取得用リセット
            if (state is ImuDataCaptureViewModel.CaptureState.Complete ||
                state is ImuDataCaptureViewModel.CaptureState.Error
            ) {
                TextButton(onClick = { viewModel.reset() }, modifier = Modifier.fillMaxWidth()) {
                    Text("クリアして再取得")
                }
            }
        }
    }
}

/** CSV をキャッシュに書き出し、FileProvider 経由で ACTION_SEND 共有 */
private fun shareImuCsv(context: Context, csv: String, label: String) {
    try {
        val dir = File(context.cacheDir, "imu").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeLabel = label.ifEmpty { "capture" }
        val file = File(dir, "imu_${safeLabel}_$ts.csv")
        file.writeText(csv)
        val uri = FileProvider.getUriForFile(context, "com.pirorin215.btclockmob.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "IMUデータを共有"))
    } catch (e: Exception) {
        Toast.makeText(context, "共有に失敗: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
