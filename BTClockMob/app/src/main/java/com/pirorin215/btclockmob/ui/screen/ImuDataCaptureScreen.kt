package com.pirorin215.btclockmob.ui.screen

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pirorin215.btclockmob.data.ConnectionState
import com.pirorin215.btclockmob.viewModel.ImuDataCaptureViewModel

val IMU_LABELS = listOf("駐車A", "駐車B", "駐車C", "走行開始A", "走行開始B", "走行開始C", "停車A", "停車B", "停車C")

/**
 * モーション学習画面に組み込む IMU データ採取セクション。
 * 「データ取得」→ デバイスのリングバッファ（直近10秒・50Hz）をチャンク受信 →
 * ラベル付き特徴量を学習データへ追加（CSV も Downloads へ保存）。
 *
 * 接続ステータス表示は親画面（モーション学習画面）で共有するため、ここでは持たない。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImuCaptureSection(viewModel: ImuDataCaptureViewModel, selectedLabel: String) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()

    val isConnected = connectionState is ConnectionState.Connected

    var memo by remember { mutableStateOf("") }
    var selectedDelay by remember { mutableStateOf(0) }

    Text("データ採取", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

    // メモ
    OutlinedTextField(
        value = memo,
        onValueChange = { memo = it },
        label = { Text("メモ（任意）") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2
    )

    // 開始遅延（セルフタイマー）
    Text("開始遅延秒", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0, 5, 10, 15).forEach { sec ->
            FilterChip(
                selected = selectedDelay == sec,
                onClick = { selectedDelay = sec },
                label = { Text("${sec}秒") }
            )
        }
    }

    // 取得ボタン（フィールドで押しやすいよう縦幅を拡大）
    val busy = state is ImuDataCaptureViewModel.CaptureState.Requesting ||
        state is ImuDataCaptureViewModel.CaptureState.Recording ||
        state is ImuDataCaptureViewModel.CaptureState.Receiving ||
        state is ImuDataCaptureViewModel.CaptureState.Countdown
    Button(
        onClick = { viewModel.requestDump(selectedLabel, memo, selectedDelay) },
        modifier = Modifier.fillMaxWidth().height(72.dp),
        enabled = isConnected && !busy
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("データ取得開始", style = MaterialTheme.typography.titleMedium)
    }

    // 進捗・結果
    when (val s = state) {
        is ImuDataCaptureViewModel.CaptureState.Countdown -> { /* 全画面ダイアログで表示 */ }
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
    // 開始遅延カウントダウン: 全画面スプラッシュ表示
    val countdownState = state as? ImuDataCaptureViewModel.CaptureState.Countdown
    if (countdownState != null) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.88f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        selectedLabel,
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "${countdownState.remaining}",
                        color = Color.White,
                        fontSize = 200.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "秒後に採取開始",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}
