package com.pirorin215.btclockmob.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.pirorin215.btclockmob.R
import com.pirorin215.btclockmob.data.ThemeMode
import com.pirorin215.btclockmob.data.TranscriptionProvider
import com.pirorin215.btclockmob.data.AIProvider
import com.pirorin215.btclockmob.data.ProviderMode
import com.pirorin215.btclockmob.data.GeminiModel
import com.pirorin215.btclockmob.viewModel.AppSettingsViewModel
import com.pirorin215.btclockmob.viewModel.ModelValidationStatus
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import kotlin.math.roundToInt

import androidx.activity.compose.BackHandler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(appSettingsViewModel: AppSettingsViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val coroutineScope = rememberCoroutineScope()

    // stringResourceを事前に取得
    val saveButtonText = stringResource(R.string.save_button)
    val appSettingsTitle = stringResource(R.string.app_settings_title)

    // DataStoreから現在の設定値を取得
    val currentApiKey by appSettingsViewModel.apiKey.collectAsState()
    val currentGeminiApiKey by appSettingsViewModel.geminiApiKey.collectAsState()
    val currentGroqApiKey by appSettingsViewModel.groqApiKey.collectAsState()
    val currentTranscriptionProvider by appSettingsViewModel.transcriptionProvider.collectAsState()
    val currentAIProvider by appSettingsViewModel.aiProvider.collectAsState()
    val currentProviderMode by appSettingsViewModel.providerMode.collectAsState()
    val currentGeminiModel by appSettingsViewModel.geminiModel.collectAsState()
    val currentTranscriptionCacheLimit by appSettingsViewModel.transcriptionCacheLimit.collectAsState()
    val currentFontSize by appSettingsViewModel.transcriptionFontSize.collectAsState()
    val currentThemeMode by appSettingsViewModel.themeMode.collectAsState()
    // Google Tasks removed - feature disabled
    val currentAutoStartOnBoot by appSettingsViewModel.autoStartOnBoot.collectAsState()
    val currentChunkBurstSize by appSettingsViewModel.chunkBurstSize.collectAsState()
    val currentVoltageRetryCount by appSettingsViewModel.voltageRetryCount.collectAsState()
    val currentVoltageAcquisitionInterval by appSettingsViewModel.voltageAcquisitionInterval.collectAsState()
    val currentLowVoltageThreshold by appSettingsViewModel.lowVoltageThreshold.collectAsState()
    val currentLowVoltageNotifyEveryTime by appSettingsViewModel.lowVoltageNotifyEveryTime.collectAsState()
    val currentTranscriptionNotificationEnabled by appSettingsViewModel.transcriptionNotificationEnabled.collectAsState()
    val currentGeminiEnableGoogleSearch by appSettingsViewModel.geminiEnableGoogleSearch.collectAsState()
    val currentGeminiSystemPrompt by appSettingsViewModel.geminiSystemPrompt.collectAsState()
    val modelValidationStatus by appSettingsViewModel.modelValidationStatus.collectAsState()
    val modelValidationError by appSettingsViewModel.modelValidationError.collectAsState()

    // TextFieldの状態を管理
    var apiKeyText by remember(currentApiKey) { mutableStateOf(currentApiKey) }
    var geminiApiKeyText by remember(currentGeminiApiKey) { mutableStateOf(currentGeminiApiKey) }
    var groqApiKeyText by remember(currentGroqApiKey) { mutableStateOf(currentGroqApiKey) }
    var selectedTranscriptionProvider by remember(currentTranscriptionProvider) { mutableStateOf(currentTranscriptionProvider) }
    var selectedAIProvider by remember(currentAIProvider) { mutableStateOf(currentAIProvider) }
    var selectedProviderMode by remember(currentProviderMode) { mutableStateOf(currentProviderMode) }
    var selectedGeminiModel by remember(currentGeminiModel) { mutableStateOf(currentGeminiModel) }
    var transcriptionCacheLimitText by remember(currentTranscriptionCacheLimit) { mutableStateOf(currentTranscriptionCacheLimit.toString()) }
    var fontSizeSliderValue by remember(currentFontSize) { mutableStateOf(currentFontSize.toFloat()) }
    var selectedThemeMode by remember(currentThemeMode) { mutableStateOf(currentThemeMode) }
    // Google Tasks TextField removed - feature disabled
    var autoStartOnBootChecked by remember(currentAutoStartOnBoot) { mutableStateOf(currentAutoStartOnBoot) }
    var chunkBurstSizeText by remember(currentChunkBurstSize) { mutableStateOf(currentChunkBurstSize.toString()) }
    var voltageRetryCountText by remember(currentVoltageRetryCount) { mutableStateOf(currentVoltageRetryCount.toString()) }
    var voltageAcquisitionIntervalText by remember(currentVoltageAcquisitionInterval) { mutableStateOf(currentVoltageAcquisitionInterval.toString()) }
    var lowVoltageThresholdText by remember(currentLowVoltageThreshold) { mutableStateOf(if (currentLowVoltageThreshold == 0f) "" else currentLowVoltageThreshold.toString()) }
    var lowVoltageNotifyEveryTimeChecked by remember(currentLowVoltageNotifyEveryTime) { mutableStateOf(currentLowVoltageNotifyEveryTime) }
    var transcriptionNotificationEnabledChecked by remember(currentTranscriptionNotificationEnabled) { mutableStateOf(currentTranscriptionNotificationEnabled) }
    var geminiSystemPromptText by remember(currentGeminiSystemPrompt) { mutableStateOf(currentGeminiSystemPrompt) }

    // ダイアログ用state
    var showErrorDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var showSavingDialog by remember { mutableStateOf(false) }

    // saveAllSettingsを先に定義
    val saveAllSettings = {
        appSettingsViewModel.saveApiKey(apiKeyText)
        appSettingsViewModel.saveGeminiApiKey(geminiApiKeyText)
        appSettingsViewModel.saveGroqApiKey(groqApiKeyText)
        appSettingsViewModel.saveProviderMode(selectedProviderMode)
        appSettingsViewModel.saveGeminiModel(selectedGeminiModel)
        val transcriptionCacheLimit = transcriptionCacheLimitText.toIntOrNull() ?: 100
        appSettingsViewModel.saveTranscriptionCacheLimit(transcriptionCacheLimit)
        appSettingsViewModel.saveTranscriptionFontSize(fontSizeSliderValue.roundToInt())
        appSettingsViewModel.saveThemeMode(selectedThemeMode)
        // Google Tasks settings removed - feature disabled
        appSettingsViewModel.saveAutoStartOnBoot(autoStartOnBootChecked)
        val chunkBurstSize = chunkBurstSizeText.toIntOrNull() ?: 8
        appSettingsViewModel.saveChunkBurstSize(chunkBurstSize)
        val voltageRetryCount = voltageRetryCountText.toIntOrNull() ?: 3
        appSettingsViewModel.saveVoltageRetryCount(voltageRetryCount)
        val voltageAcquisitionInterval = voltageAcquisitionIntervalText.toIntOrNull() ?: 100
        appSettingsViewModel.saveVoltageAcquisitionInterval(voltageAcquisitionInterval)
        val lowVoltageThreshold = lowVoltageThresholdText.toFloatOrNull() ?: 0f
        appSettingsViewModel.saveLowVoltageThreshold(lowVoltageThreshold)
        appSettingsViewModel.saveLowVoltageNotifyEveryTime(lowVoltageNotifyEveryTimeChecked)
        appSettingsViewModel.saveTranscriptionNotificationEnabled(transcriptionNotificationEnabledChecked)
        appSettingsViewModel.saveGeminiSystemPrompt(geminiSystemPromptText)
        onBack()
    }

    val saveSettings: () -> Unit = {
        // まず全設定を保存（APIキーもここで保存される）
        saveAllSettings()

        // GCPモードでGemini APIキーがある場合のみモデル検証（警告用）
        if (selectedProviderMode == ProviderMode.GCP && geminiApiKeyText.isNotBlank()) {
            showSavingDialog = true
            coroutineScope.launch {
                val result = appSettingsViewModel.verifyGeminiModel(selectedGeminiModel.modelName)
                showSavingDialog = false

                if (result.isFailure) {
                    // 保存済みだが、モデル検証に失敗した場合は警告を表示
                    val modelName = selectedGeminiModel.modelName
                    errorMessage = "✅ 設定は保存されましたが、\n\nモデル '${modelName}' の接続テストに失敗しました。\n\n考えられる原因：\n• ネットワーク環境の問題\n• GCPでAPIが有効化されていない\n• 一時的なサーバーの混雑\n\nこれらを確認の上、別のモデルを選択してください。"
                    showErrorDialog = true
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(appSettingsTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = saveSettings) {
                        Icon(Icons.Filled.Check, contentDescription = saveButtonText)
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Provider Mode Selection - 一番上に移動
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "GCP",
                    fontSize = 16.sp,
                    color = if (selectedProviderMode == ProviderMode.GCP) {
                        androidx.compose.material3.MaterialTheme.colorScheme.primary
                    } else {
                        androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Switch(
                    checked = selectedProviderMode == ProviderMode.GROQ,
                    onCheckedChange = { checked ->
                        selectedProviderMode = if (checked) ProviderMode.GROQ else ProviderMode.GCP
                    },
                    colors = SwitchDefaults.colors()
                )
                Text(
                    text = "Groq",
                    fontSize = 16.sp,
                    color = if (selectedProviderMode == ProviderMode.GROQ) {
                        androidx.compose.material3.MaterialTheme.colorScheme.primary
                    } else {
                        androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when (selectedProviderMode) {
                    ProviderMode.GCP -> "文字起こし: Google、AI応答: Gemini"
                    ProviderMode.GROQ -> "文字起こし: Whisper、AI応答: Llama 3.1"
                },
                fontSize = 12.sp,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = apiKeyText,
                onValueChange = { apiKeyText = it },
                label = { Text(stringResource(R.string.google_api_key_label)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedProviderMode == ProviderMode.GCP
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = geminiApiKeyText,
                onValueChange = { geminiApiKeyText = it },
                label = { Text(stringResource(R.string.gemini_api_key_label)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedProviderMode == ProviderMode.GCP
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = groqApiKeyText,
                onValueChange = { groqApiKeyText = it },
                label = { Text("Groq API Key") },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedProviderMode == ProviderMode.GROQ
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Gemini System Prompt (editable text area)
            Text(
                text = "AIシステムプロンプト（Gemini用）",
                style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = geminiSystemPromptText,
                onValueChange = { geminiSystemPromptText = it },
                label = { Text("システムプロンプト") },
                placeholder = { Text("AIへの指示を入力...") },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedProviderMode == ProviderMode.GCP,
                minLines = 3,
                maxLines = 6
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Gemini Model Selection (only show when GCP is selected)
            if (selectedProviderMode == ProviderMode.GCP) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Geminiモデル", fontSize = 16.sp)
                    Text(
                        text = selectedGeminiModel.modelName,
                        fontSize = 14.sp,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                // Version stepper and checkboxes
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Version stepper ([-] [value] [+])
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val newVersion = ((selectedGeminiModel.version - 0.5f) * 2).toInt() / 2f
                                if (newVersion >= 0.5f) {
                                    selectedGeminiModel = selectedGeminiModel.copy(version = newVersion)
                                }
                            },
                            modifier = Modifier.size(40.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "減らす", modifier = Modifier.size(20.dp))
                        }

                        Text(
                            text = String.format("%.1f", selectedGeminiModel.version),
                            fontSize = 16.sp,
                            modifier = Modifier.width(50.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )

                        OutlinedButton(
                            onClick = {
                                val newVersion = ((selectedGeminiModel.version + 0.5f) * 2).toInt() / 2f
                                if (newVersion <= 10f) {
                                    selectedGeminiModel = selectedGeminiModel.copy(version = newVersion)
                                }
                            },
                            modifier = Modifier.size(40.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "増やす", modifier = Modifier.size(20.dp))
                        }
                    }

                    // Flash checkbox
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = selectedGeminiModel.hasFlash,
                            onCheckedChange = { checked ->
                                selectedGeminiModel = selectedGeminiModel.copy(
                                    hasFlash = checked,
                                    // flashがオフになったらliteもオフにする
                                    hasLite = if (!checked) false else selectedGeminiModel.hasLite
                                )
                            }
                        )
                        Text(
                            text = "flash",
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }

                    // Lite checkbox (flashが有効な時のみ選択可能)
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = selectedGeminiModel.hasLite,
                            onCheckedChange = { checked ->
                                // flashが有効な時のみliteを有効にできる
                                if (selectedGeminiModel.hasFlash) {
                                    selectedGeminiModel = selectedGeminiModel.copy(hasLite = checked)
                                }
                            },
                            enabled = selectedGeminiModel.hasFlash
                        )
                        Text(
                            text = "lite",
                            modifier = Modifier.padding(start = 4.dp),
                            color = if (selectedGeminiModel.hasFlash) {
                                androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                            } else {
                                androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                // Google Search Grounding toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Google検索グラウンディング",
                            fontSize = 16.sp
                        )
                        Text(
                            text = "最新情報に基づいた回答を生成",
                            fontSize = 12.sp,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = currentGeminiEnableGoogleSearch,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                appSettingsViewModel.saveGeminiEnableGoogleSearch(checked)
                            }
                        },
                        colors = SwitchDefaults.colors()
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            OutlinedTextField(
                value = transcriptionCacheLimitText, // Renamed
                onValueChange = { transcriptionCacheLimitText = it },
                label = { Text(stringResource(R.string.transcription_retention_count)) }, // Updated text
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            // Google Tasks TextField removed - feature disabled
            OutlinedTextField(
                value = chunkBurstSizeText,
                onValueChange = { chunkBurstSizeText = it },
                label = { Text(stringResource(R.string.ble_burst_size_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = voltageRetryCountText,
                onValueChange = { voltageRetryCountText = it },
                label = { Text(stringResource(R.string.voltage_retry_count_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = voltageAcquisitionIntervalText,
                onValueChange = { voltageAcquisitionIntervalText = it },
                label = { Text(stringResource(R.string.voltage_acquisition_interval_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = lowVoltageThresholdText,
                onValueChange = { lowVoltageThresholdText = it },
                label = { Text(stringResource(R.string.low_voltage_threshold_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.low_voltage_notify_every_time), fontSize = 16.sp)
                Switch(
                    checked = lowVoltageNotifyEveryTimeChecked,
                    onCheckedChange = { lowVoltageNotifyEveryTimeChecked = it },
                    colors = SwitchDefaults.colors()
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            
            // Transcription notification setting
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.transcription_notification_enabled), fontSize = 16.sp)
                Switch(
                    checked = transcriptionNotificationEnabledChecked,
                    onCheckedChange = { transcriptionNotificationEnabledChecked = it },
                    colors = SwitchDefaults.colors()
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            // New: Auto-start on boot setting
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.auto_start_on_boot), fontSize = 16.sp)
                Switch(
                    checked = autoStartOnBootChecked,
                    onCheckedChange = { autoStartOnBootChecked = it },
                    colors = SwitchDefaults.colors()
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Font size slider
            Text(stringResource(R.string.transcription_font_size, fontSizeSliderValue.roundToInt()))
            Slider(
                value = fontSizeSliderValue,
                onValueChange = { fontSizeSliderValue = it },
                valueRange = 10f..24f,
                steps = 13, // (24 - 10) / 1 = 14 steps, so 13 intermediate points
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Theme mode selection
            Text(stringResource(R.string.theme_mode), fontSize = 16.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ThemeMode.values().forEach { themeMode ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (themeMode == selectedThemeMode),
                            onClick = { selectedThemeMode = themeMode },
                            colors = RadioButtonDefaults.colors()
                        )
                        Text(themeMode.name, fontSize = 14.sp)
                    }
                }
            }
        }
    }

    // Model validation error dialog
    if (showErrorDialog) {
        AlertDialog(
            onDismissRequest = { showErrorDialog = false },
            title = { Text("モデルの検証エラー") },
            text = { Text(errorMessage) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showErrorDialog = false
                    }
                ) {
                    Text("OK")
                }
            }
        )
    }

    // Saving progress dialog
    if (showSavingDialog) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("モデルを検証中") },
            text = { Text("選択されたモデルが存在するか確認しています...") },
            confirmButton = { }
        )
    }
}

