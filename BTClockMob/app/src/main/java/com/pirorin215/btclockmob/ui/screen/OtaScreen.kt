package com.pirorin215.btclockmob.ui.screen

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pirorin215.btclockmob.viewModel.OtaViewModel
import com.pirorin215.btclockmob.viewModel.OtaState
import com.pirorin215.btclockmob.viewModel.BleConnectionManager
import kotlinx.coroutines.launch

private const val TAG = "OtaScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtaScreen(
    bleConnectionManager: BleConnectionManager,
    otaViewModel: OtaViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()

    val deviceVersion by bleConnectionManager.repositoryForOta.deviceVersion.collectAsStateWithLifecycle(initialValue = null)
    val otaState by otaViewModel.otaState.collectAsStateWithLifecycle()
    val connectionState by bleConnectionManager.connectionState.collectAsStateWithLifecycle()

    // DFU device scanning
    val dfuDevices by otaViewModel.dfuDevices.collectAsStateWithLifecycle(initialValue = emptyList())
    val isScanning by otaViewModel.isScanning.collectAsStateWithLifecycle(initialValue = false)
    val dfuDeviceConnected by otaViewModel.dfuDeviceConnected.collectAsStateWithLifecycle(initialValue = false)

    // Start/stop scanning when entering/leaving the screen
    DisposableEffect(Unit) {
        otaViewModel.startDfuDeviceScan()
        onDispose {
            otaViewModel.stopDfuDeviceScan()
        }
    }

    // OTAファイル選択ランチャー
    val otaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            Toast.makeText(context, "ファームウェアファイルを選択しました", Toast.LENGTH_SHORT).show()
            otaViewModel.startOtaUpdate(selectedUri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OTA更新") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
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
            // 接続状態カード
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "接続状態",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = when (connectionState) {
                            is com.pirorin215.btclockmob.data.ConnectionState.Connected -> "接続済み"
                            is com.pirorin215.btclockmob.data.ConnectionState.Disconnected -> "未接続"
                            is com.pirorin215.btclockmob.data.ConnectionState.Pairing -> "ペアリング中"
                            is com.pirorin215.btclockmob.data.ConnectionState.Paired -> "ペアリング済み"
                            is com.pirorin215.btclockmob.data.ConnectionState.Error -> "エラー"
                            else -> "不明"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // バージョン情報カード
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "ファームウェアバージョン",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (deviceVersion != null) {
                        Text(
                            text = "v$deviceVersion",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            text = "不明",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // DFUデバイススキャン状態
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isScanning) "DFUデバイスをスキャン中..." else "DFUデバイススキャン停止",
                    style = MaterialTheme.typography.bodyMedium
                )
                IconButton(onClick = {
                    if (isScanning) {
                        otaViewModel.stopDfuDeviceScan()
                    } else {
                        otaViewModel.startDfuDeviceScan()
                    }
                }) {
                    Icon(
                        imageVector = if (isScanning) Icons.Default.Stop else Icons.Default.Search,
                        contentDescription = if (isScanning) "停止" else "スキャン"
                    )
                }
            }

            // DFUデバイスリスト
            if (dfuDevices.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "DFUデバイス",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        dfuDevices.forEach { device ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = device.name ?: "不明",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = device.address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (dfuDeviceConnected) {
                                    Text(
                                        text = "接続済み",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Button(
                                        onClick = {
                                            otaViewModel.connectDfuDevice(device)
                                        },
                                        enabled = !dfuDeviceConnected
                                    ) {
                                        Text("接続")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // バージョン取得ボタン
            Button(
                onClick = {
                    scope.launch {
                        val success = bleConnectionManager.repositoryForOta.getVersion()
                        if (success) {
                            Toast.makeText(context, "バージョンを取得しました", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "バージョン取得に失敗しました", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = connectionState is com.pirorin215.btclockmob.data.ConnectionState.Connected
            ) {
                Text("バージョンを取得")
            }

            HorizontalDivider()

            // OTA更新セクション
            Text(
                text = "OTA更新",
                style = MaterialTheme.typography.titleMedium
            )

            // OTA状態表示
            when (val state = otaState) {
                is OtaState.Idle -> {
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = "OTA更新待機中",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                is OtaState.Connecting -> {
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "DFUモードに接続中...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                is OtaState.Transferring -> {
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = "ファームウェア転送中",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            LinearProgressIndicator(
                                progress = { state.progress / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "${state.progress}%",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }
                    }
                }
                is OtaState.Completed -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "OTA更新完了",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "デバイスが再起動します",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                is OtaState.Error -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = "エラー",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            // OTAファイル選択ボタン
            Button(
                onClick = {
                    otaLauncher.launch("*/*")
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = dfuDeviceConnected &&
                           otaState is OtaState.Idle
            ) {
                Text("ファームウェアを選択")
            }

            // リセットボタン（エラー時または完了時）
            if (otaState is OtaState.Error || otaState is OtaState.Completed) {
                OutlinedButton(
                    onClick = {
                        otaViewModel.resetState()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("リセット")
                }
            }

            // 説明テキスト
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "OTA更新について",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "• デバイスをDFUモード（AdaDFU）にしてください\n" +
                              "• メンテナンスモード → 3OTAを選択するとDFUモードになります\n" +
                              "• DFUデバイス（AdaDFU）が表示されたら接続してください\n" +
                              "• ファームウェアを選択して転送を開始します\n" +
                              "• 転送完了後、デバイスが再起動します",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
