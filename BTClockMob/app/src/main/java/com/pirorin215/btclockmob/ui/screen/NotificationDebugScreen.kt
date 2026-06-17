package com.pirorin215.btclockmob.ui.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pirorin215.btclockmob.data.ConnectionState
import com.pirorin215.btclockmob.viewModel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationDebugScreen(
    mainViewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val connectionState by mainViewModel.connectionState.collectAsState()
    val maxCharsSetting by mainViewModel.notificationMaxChars.collectAsState()

    var appName by remember { mutableStateOf("com.whatsapp") }
    var title by remember { mutableStateOf("山田 太郎") }
    var body by remember { mutableStateOf("これはテスト通知です。") }

    // 送信コマンドのプレビュー生成用
    val combinedText = remember(title, body) {
        listOf(title, body)
            .map { it.replace("\n", " ").replace("\r", " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    val maxBytes = 180
    val maxChars = maxCharsSetting
    val truncatedText = remember(combinedText, maxChars) {
        // 1) 文字数制限と「続きあり」記号の付与
        val limitedText = if (combinedText.length > maxChars) {
            combinedText.take(maxChars - 1) + "＞"
        } else {
            combinedText
        }
        // 2) バイト数制限での最終切り詰め
        truncateUtf8(limitedText, maxBytes)
    }

    val previewCommand = remember(appName, truncatedText) {
        "NOTIFY:app=$appName\n$truncatedText"
    }

    val commandBytesSize = remember(previewCommand) {
        previewCommand.toByteArray(Charsets.UTF_8).size
    }

    // 接続状態
    val isConnected = connectionState is ConnectionState.Connected

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("通知送信デバッグ") },
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
            // 接続ステータス表示
            val statusText = if (isConnected) "接続中: ${(connectionState as ConnectionState.Connected).device.name}" else "未接続"
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
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // ePaper プレビュー風カード
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("BikeClock ePaper 表示プレビュー", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${truncatedText.length} 文字",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2.5f) // ePaper のアスペクト比を模擬
                    .border(2.dp, Color.Black, RoundedCornerShape(8.dp)),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White,
                    contentColor = Color.Black
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                ) {
                    // ヘッダー: アプリ名
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                    ) {
                        Icon(
                            Icons.Default.Notifications,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = appName.ifEmpty { "System Notification" },
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    
                    HorizontalDivider(color = Color.Black, thickness = 1.dp)
                    
                    // 本文表示
                    Text(
                        text = truncatedText.ifEmpty { "（表示するテキストがありません）" },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                            fontFamily = FontFamily.SansSerif
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 6.dp),
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // プリセット選択
            Text("アプリプリセット", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "WhatsApp" to "com.whatsapp",
                    "LINE" to "jp.naver.line.android",
                    "Gmail" to "com.google.android.gm",
                    "カスタム" to "com.example.notification"
                ).forEach { (label, pkg) ->
                    val selected = appName == pkg
                    OutlinedButton(
                        onClick = { appName = pkg },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                        ),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        Text(label, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // 入力フォーム
            OutlinedTextField(
                value = appName,
                onValueChange = { appName = it },
                label = { Text("アプリパッケージ名") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("通知タイトル") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("通知本文") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )

            // プレビューカード
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "送信コマンド（デバッグ用）",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = previewCommand,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(4.dp))
                            .padding(8.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "本文: ${truncatedText.toByteArray(Charsets.UTF_8).size} / $maxBytes bytes",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (combinedText.toByteArray(Charsets.UTF_8).size > maxBytes) Color(0xFFFF9800) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "総サイズ: $commandBytesSize bytes",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val isOverLimit = combinedText.length > maxChars || combinedText.toByteArray(Charsets.UTF_8).size > maxBytes
                    if (isOverLimit) {
                        val reason = if (combinedText.length > maxChars) "${maxChars}文字" else "${maxBytes}B"
                        Text(
                            text = "※本文が上限（$reason）を超えるため切り詰められます",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFF9800),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // 送信ボタン
            Button(
                onClick = {
                    if (!isConnected) {
                        Toast.makeText(context, "デバイスに接続されていません", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    try {
                        mainViewModel.sendCommand(previewCommand)
                        Toast.makeText(context, "通知コマンドを送信しました", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "送信失敗: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = isConnected && appName.isNotEmpty() && (title.isNotEmpty() || body.isNotEmpty())
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("通知を送信")
            }
        }
    }
}

/**
 * UTF-8 で maxBytes を超えないよう切り詰める（マルチバイト文字の途中で切らない）。
 */
private fun truncateUtf8(text: String, maxBytes: Int): String {
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return text
    var len = maxBytes
    // 末尾が UTF-8 継続バイト(0b10xxxxxx)で終わらないよう巻き戻す
    while (len > 0 && (bytes[len - 1].toInt() and 0xC0) == 0x80) len--
    // その後、先頭バイト(0b11xxxxxx)単独で残っていたら不完全なので削る
    if (len > 0 && (bytes[len - 1].toInt() and 0xC0) == 0xC0) len--
    return String(bytes, 0, len, Charsets.UTF_8)
}
