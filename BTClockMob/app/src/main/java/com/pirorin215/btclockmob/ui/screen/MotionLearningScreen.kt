package com.pirorin215.btclockmob.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pirorin215.btclockmob.data.ConnectionState
import com.pirorin215.btclockmob.viewModel.MotionLearningViewModel
import com.pirorin215.btclockmob.viewModel.ImuDataCaptureViewModel

/**
 * モーションパターン学習画面（Phase 1）。
 * 蓄積した学習サンプルの確認・学習（重心計算）・マイコン送信を行う。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MotionLearningScreen(
    viewModel: MotionLearningViewModel,
    captureViewModel: ImuDataCaptureViewModel,
    onBack: () -> Unit
) {
    val samples by viewModel.samples.collectAsState()
    val labelCounts by viewModel.labelCounts.collectAsState()
    val model by viewModel.model.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val sendState by viewModel.sendState.collectAsState()

    val isConnected = connectionState is ConnectionState.Connected

    var labelToDelete by remember { mutableStateOf<MotionLearningViewModel.LabelCount?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("モーション学習") },
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
            // 接続状態
            val statusText = if (isConnected) {
                "接続中: ${(connectionState as ConnectionState.Connected).device.name ?: "BikeClock"}"
            } else {
                "未接続"
            }
            val statusColor = if (isConnected) Color(0xFF4CAF50) else Color(0xFFF44336)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.1f))
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(statusColor, shape = RoundedCornerShape(6.dp))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(statusText, color = statusColor, fontWeight = FontWeight.Bold)
                }
            }

            Text(
                "ラベルを選んでデータを採取し、学習データに蓄積します。" +
                    "パターン別の特徴量重心を学習してマイコンへ送信できます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // データ採取セクション（IMU採取と統合）
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImuCaptureSection(captureViewModel)
                }
            }

            // 学習サンプル一覧
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("学習データ: 合計 ${samples.size} 件", fontWeight = FontWeight.Bold)
                    if (labelCounts.isEmpty()) {
                        Text(
                            "（未登録。採取画面で「学習データに追加」を押してください）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        labelCounts.forEach { lc ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${lc.label}: ${lc.count} 件", style = MaterialTheme.typography.bodyMedium)
                                IconButton(onClick = { labelToDelete = lc }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Default.Delete, contentDescription = "${lc.label}を削除", tint = Color(0xFFF44336))
                                }
                            }
                        }
                    }
                }
            }

            // 学習ボタン
            Button(
                onClick = { viewModel.train() },
                modifier = Modifier.fillMaxWidth(),
                enabled = samples.isNotEmpty()
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("学習する（重心を計算）")
            }

            // 学習済みモデル情報と送信
            model?.let { m ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2196F3).copy(alpha = 0.08f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("学習済みモデル: ${m.patternCount} パターン", fontWeight = FontWeight.Bold)
                        m.labels.forEachIndexed { i, label ->
                            Text(
                                "  [$label] 重心(先頭4次元)=${m.centroids[i].take(4).map { "%.2f".format(it) }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Button(
                    onClick = { viewModel.sendToMcu() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isConnected && sendState !is MotionLearningViewModel.SendState.Sending
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("マイコンへ送信")
                }
                when (val s = sendState) {
                    is MotionLearningViewModel.SendState.Sending ->
                        Text("送信中...", style = MaterialTheme.typography.bodySmall)
                    is MotionLearningViewModel.SendState.Success ->
                        Text("✅ 送信完了: ${s.patterns} パターン", color = Color(0xFF2E7D32), style = MaterialTheme.typography.bodySmall)
                    is MotionLearningViewModel.SendState.Error ->
                        Text("⚠ ${s.message}", color = Color(0xFFF44336), style = MaterialTheme.typography.bodySmall)
                    MotionLearningViewModel.SendState.Idle -> { /* nothing */ }
                }
            }

            // 学習データ・モデル全削除
            if (samples.isNotEmpty() || model != null) {
                TextButton(onClick = { viewModel.clearAll() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("学習データとモデルを全削除")
                }
            }

            // ラベル単位削除の確認ダイアログ
            labelToDelete?.let { lc ->
                AlertDialog(
                    onDismissRequest = { labelToDelete = null },
                    title = { Text("学習データを削除") },
                    text = {
                        Text("「${lc.label}」の学習データ（${lc.count}件）を削除しますか？\n" +
                            "削除後、採取画面で再取得して「学習する」を押せば再学習できます。")
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.deleteLabel(lc.label)
                            labelToDelete = null
                        }) { Text("削除", color = Color(0xFFF44336)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { labelToDelete = null }) { Text("キャンセル") }
                    }
                )
            }
        }
    }
}
