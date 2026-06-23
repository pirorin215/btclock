package com.pirorin215.btclockmob.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
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

    var selectedLabel by remember { mutableStateOf(IMU_LABELS.first()) }
    var showDeleteDialog by remember { mutableStateOf(false) }

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
                "ラベルを行選択してデータを採取・蓄積し、パターン別重心を学習してマイコンへ送信できます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 学習データ（ラベル選択 + 件数）— 行選択が採取/削除の対象
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("学習データ: 合計 ${samples.size} 件", fontWeight = FontWeight.Bold)
                    Text(
                        "行を選択 → 「データ取得開始」で採取、「削除」でそのラベルを削除します。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IMU_LABELS.forEach { label ->
                        val count = labelCounts.find { it.label == label }?.count ?: 0
                        val isSelected = label == selectedLabel
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                                .clickable { selectedLabel = label }
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                            Text("$count 件", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    val selectedCount = labelCounts.find { it.label == selectedLabel }?.count ?: 0
                    if (selectedCount > 0) {
                        TextButton(
                            onClick = { showDeleteDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFF44336))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("「$selectedLabel」を削除 (${selectedCount}件)", color = Color(0xFFF44336))
                        }
                    }
                    if (samples.isNotEmpty() || model != null) {
                        TextButton(onClick = { viewModel.clearAll() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("学習データとモデルを全削除")
                        }
                    }
                }
            }

            // データ採取セクション（選択中ラベルへ採取）
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImuCaptureSection(captureViewModel, selectedLabel)
                }
            }

            // 学習してマイコンへ送信（統合ボタン：学習は一瞬・決定的論理なので分離不要）
            Button(
                onClick = { viewModel.trainAndSend() },
                modifier = Modifier.fillMaxWidth(),
                enabled = isConnected && samples.isNotEmpty() &&
                    sendState !is MotionLearningViewModel.SendState.Sending
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("学習してマイコンへ送信")
            }

            // 学習済みモデル情報（送信後に表示）
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
            }

            // 送信結果
            when (val s = sendState) {
                is MotionLearningViewModel.SendState.Sending ->
                    Text("送信中...", style = MaterialTheme.typography.bodySmall)
                is MotionLearningViewModel.SendState.Success ->
                    Text("✅ 送信完了: ${s.patterns} パターン", color = Color(0xFF2E7D32), style = MaterialTheme.typography.bodySmall)
                is MotionLearningViewModel.SendState.Error ->
                    Text("⚠ ${s.message}", color = Color(0xFFF44336), style = MaterialTheme.typography.bodySmall)
                MotionLearningViewModel.SendState.Idle -> { /* nothing */ }
            }


            // 選択中ラベル削除の確認ダイアログ
            if (showDeleteDialog) {
                val selectedCount = labelCounts.find { it.label == selectedLabel }?.count ?: 0
                AlertDialog(
                    onDismissRequest = { showDeleteDialog = false },
                    title = { Text("学習データを削除") },
                    text = {
                        Text("「$selectedLabel」の学習データ（${selectedCount}件）を削除しますか？\n" +
                            "削除後、再取得して「学習してマイコンへ送信」で再学習できます。")
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.deleteLabel(selectedLabel)
                            showDeleteDialog = false
                        }) { Text("削除", color = Color(0xFFF44336)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteDialog = false }) { Text("キャンセル") }
                    }
                )
            }
        }
    }
}
