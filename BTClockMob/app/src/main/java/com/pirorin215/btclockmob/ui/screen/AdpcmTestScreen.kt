package com.pirorin215.btclockmob.ui.screen

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pirorin215.btclockmob.R
import androidx.compose.ui.res.stringResource
import com.pirorin215.btclockmob.viewModel.AdpcmTestResult
import com.pirorin215.btclockmob.viewModel.MainViewModel
import kotlinx.coroutines.launch

private const val TAG = "AdpcmTestScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdpcmTestScreen(
    viewModel: MainViewModel = viewModel(),
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var testResult by remember { mutableStateOf<AdpcmTestResult?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    // File picker for WAV files
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedUri = it
            testResult = null  // Clear previous result
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_adpcm_test)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back_button_content_description))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Description
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "ADPCMデコーダテスト",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Downloadフォルダにある任意のWAVファイルを選択して、ADPCMデコード処理をテストできます。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // File selection section
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "ファイル選択",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (selectedUri != null) {
                        Text(
                            text = "選択中: ${selectedUri?.lastPathSegment ?: "Unknown"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Button(
                        onClick = { filePickerLauncher.launch("audio/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("WAVファイルを選択")
                    }
                }
            }

            // Test section
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "デコードテスト",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            selectedUri?.let { uri ->
                                isTesting = true
                                scope.launch {
                                    try {
                                        // Copy file to cache and test decode
                                        val inputStream = context.contentResolver.openInputStream(uri)
                                        val tempFile = java.io.File(context.cacheDir, "test_adpcm.wav")
                                        tempFile.outputStream().use { output ->
                                            inputStream?.copyTo(output)
                                        }
                                        inputStream?.close()

                                        // Run ADPCM decode test
                                        val result = viewModel.testDecodeAdpcmFile(tempFile.absolutePath)
                                        testResult = result

                                        scope.launch {
                                            val message = if (result.success) {
                                                "デコード成功: ${result.bytesRead}/${result.bytesExpected} バイト"
                                            } else {
                                                "デコード失敗: ${result.message}"
                                            }
                                            snackbarHostState.showSnackbar(message)
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Test failed", e)
                                        testResult = AdpcmTestResult(
                                            success = false,
                                            filePath = uri.lastPathSegment ?: "Unknown",
                                            bytesRead = 0,
                                            bytesExpected = 0,
                                            message = "エラー: ${e.message}"
                                        )
                                        scope.launch {
                                            snackbarHostState.showSnackbar("エラー: ${e.message}")
                                        }
                                    } finally {
                                        isTesting = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = selectedUri != null && !isTesting
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        } else {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (isTesting) "テスト中..." else "デコードテスト実行")
                    }
                }
            }

            // Result section
            testResult?.let { result ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (result.success) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        }
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (result.success) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                tint = if (result.success) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (result.success) "成功" else "失敗",
                                style = MaterialTheme.typography.titleLarge,
                                color = if (result.success) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Result details
                        ResultRow("ファイル", result.filePath)
                        ResultRow("読み取りバイト数", "${result.bytesRead}")
                        ResultRow("期待バイト数", "${result.bytesExpected}")

                        if (result.bytesExpected > 0) {
                            val percentage = (result.bytesRead * 100) / result.bytesExpected
                            ResultRow("完了率", "$percentage%")
                        }

                        if (!result.success && result.message.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "エラー詳細:",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = result.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
