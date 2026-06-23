package com.pirorin215.btclockmob.ui.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pirorin215.btclockmob.data.ConnectionState
import com.pirorin215.btclockmob.viewModel.InferenceLogViewModel

private const val DIST_THRESH = 3.0f    // マイコン MOTION_DISTANCE_THRESH と同一（超過=不明）
private const val VISIBLE_TAIL = 200    // 画面に表示する最新件数（古い分はスクロール）

/**
 * 推論ログ取得画面（駐車検知の精度チューニング用）。
 * 開始で毎秒（1Hz）の推論結果 [候補, dist, 特徴量] を記録。dist が閾値3.0を超えると赤く表示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InferenceLogScreen(
    viewModel: InferenceLogViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()

    val isConnected = connectionState is ConnectionState.Connected
    val isLogging = state is InferenceLogViewModel.LogState.Logging

    val listState = rememberLazyListState()

    // 新着時に最下行へ自動スクロール（最新を追従）
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            val visible = entries.size.coerceAtMost(VISIBLE_TAIL)
            listState.animateScrollToItem(visible - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("推論ログ") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isLogging) viewModel.stop()   // 記録中に戻るなら停止
                        onBack()
                    }) {
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
        ) {
            // --- ヘッダ部（ステータス + 操作）---
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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
                        modifier = Modifier.padding(12.dp),
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
                    "開始すると毎秒（1Hz）の推論結果 [候補, dist, 特徴量] を記録します。" +
                        "駐車操作の前後で dist がどう変わるかを見て精度チューニングに使います。" +
                        "dist ≥ 3.0（赤行）は「不明」= どのラベルからも遠い状態です。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 開始/停止ボタン
                Button(
                    onClick = { if (isLogging) viewModel.stop() else viewModel.start() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isConnected,
                    colors = if (isLogging) ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                             else ButtonDefaults.buttonColors()
                ) {
                    Icon(if (isLogging) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isLogging) "停止" else "開始")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.clear() },
                        modifier = Modifier.weight(1f),
                        enabled = entries.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("クリア")
                    }
                    OutlinedButton(
                        onClick = {
                            val name = viewModel.saveToDownloads()
                            val msg = name?.let { "保存しました: $it" } ?: "保存に失敗（ログが空？）"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = entries.isNotEmpty()
                    ) {
                        Icon(Icons.Default.SaveAlt, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("CSV保存")
                    }
                }

                (state as? InferenceLogViewModel.LogState.Error)?.let {
                    Text("エラー: ${it.message}", color = Color(0xFFF44336), style = MaterialTheme.typography.bodyMedium)
                }

                Text(
                    "${entries.size} 件" + if (isLogging) "（記録中）" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            HorizontalDivider()

            // --- ログ時系列（最新 VISIBLE_TAIL 件）---
            val visible = entries.takeLast(VISIBLE_TAIL)
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(visible) { e ->
                    val overThresh = e.dist >= DIST_THRESH
                    val rowColor = if (overThresh) Color(0xFFFFCDD2).copy(alpha = 0.4f) else Color.Transparent
                    val distColor = if (overThresh) Color(0xFFC62828) else MaterialTheme.colorScheme.onSurface
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(rowColor, shape = RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${e.ms}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(64.dp)
                        )
                        Text(
                            e.candidate.ifBlank { "-" },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = if (overThresh) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.width(52.dp)
                        )
                        Text(
                            "%.2f".format(e.dist),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = distColor,
                            modifier = Modifier.width(48.dp)
                        )
                        // 特徴量の先頭4つを簡易表示（accDynMaxX,accDynMaxY,accDynMaxZ,accDynMinX）
                        Text(
                            e.features.take(4).joinToString(" ") { "%.2f".format(it) },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
