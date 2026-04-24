package com.pirorin215.btclockmob.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pirorin215.btclockmob.data.KeyCodeSettings
import com.pirorin215.btclockmob.viewModel.KeyCodeSettingsViewModel

private const val TAG = "KeyCodeSettingsScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyCodeSettingsScreen(
    viewModel: KeyCodeSettingsViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val keyCodeSettings by viewModel.keyCodeSettings.collectAsStateWithLifecycle()
    val testMessage by viewModel.testMessage.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("キー設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
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
            // スイッチ1
            SwitchKeyCodeCard(
                switchNum = 1,
                keyCode = keyCodeSettings.sw1KeyCode,
                onKeyCodeChange = { newKeyCode ->
                    viewModel.updateKeyCode(1, newKeyCode)
                }
            )

            // スイッチ2
            SwitchKeyCodeCard(
                switchNum = 2,
                keyCode = keyCodeSettings.sw2KeyCode,
                onKeyCodeChange = { newKeyCode ->
                    viewModel.updateKeyCode(2, newKeyCode)
                }
            )

            // スイッチ3
            SwitchKeyCodeCard(
                switchNum = 3,
                keyCode = keyCodeSettings.sw3KeyCode,
                onKeyCodeChange = { newKeyCode ->
                    viewModel.updateKeyCode(3, newKeyCode)
                }
            )

            // スイッチ4
            SwitchKeyCodeCard(
                switchNum = 4,
                keyCode = keyCodeSettings.sw4KeyCode,
                onKeyCodeChange = { newKeyCode ->
                    viewModel.updateKeyCode(4, newKeyCode)
                }
            )

            // デバイスに保存ボタン
            Button(
                onClick = { viewModel.saveToDevice() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("設定をデバイスに反映する")
            }

            // メッセージ表示
            testMessage?.let { message ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.clearTestMessage() }) {
                            Text("クリア")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwitchKeyCodeCard(
    switchNum: Int,
    keyCode: Int,
    onKeyCodeChange: (Int) -> Unit
) {
    // 表示用の16進数文字列を保持（例: 0x50）
    var keyCodeText by remember(keyCode) { mutableStateOf("0x%X".format(keyCode)) }
    var showValidationError by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    // プリセットリスト
    val presets = remember {
        listOf(
            HidKeyPreset("← 左矢印", 0x50),
            HidKeyPreset("→ 右矢印", 0x4F),
            HidKeyPreset("↑ 上矢印", 0x52),
            HidKeyPreset("↓ 下矢印", 0x51),
            HidKeyPreset("エンター", 0x28),
            HidKeyPreset("スペース", 0x2C),
            HidKeyPreset("ESC (戻る等)", 0x29),
            HidKeyPreset("戻る (Android)", 0x0224),
            HidKeyPreset("ホーム (Android)", 0x0223),
            HidKeyPreset("再生/一時停止", 0xCD),
            HidKeyPreset("次のトラック", 0xB5),
            HidKeyPreset("前のトラック", 0xB6),
            HidKeyPreset("音量アップ", 0xE9),
            HidKeyPreset("音量ダウン", 0xEA),
            HidKeyPreset("ミュート", 0xE2)
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "スイッチ $switchNum",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = getHidKeyName(keyCode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // プリセット選択 & 手入力 (Exposed Dropdown Menu)
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = keyCodeText,
                    onValueChange = { newText ->
                        keyCodeText = newText
                        // "0x" プレフィックスを考慮してパース
                        val hexValue = newText.removePrefix("0x").removePrefix("0X")
                        val parsedInt = try {
                            hexValue.toInt(16)
                        } catch (e: Exception) {
                            null
                        }

                        if (parsedInt != null && parsedInt in 0..0xFFFF) {
                            showValidationError = false
                            onKeyCodeChange(parsedInt)
                        } else {
                            showValidationError = newText.isNotEmpty()
                        }
                    },
                    label = { Text("キー設定 (名前を選択またはHEX入力)") },
                    placeholder = { Text("0x50") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    isError = showValidationError,
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                    singleLine = true
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    presets.forEach { preset ->
                        DropdownMenuItem(
                            text = {
                                Row {
                                    Text(preset.name, modifier = Modifier.weight(1f))
                                    Text(
                                        "0x%X".format(preset.code),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            },
                            onClick = {
                                keyCodeText = "0x%X".format(preset.code)
                                onKeyCodeChange(preset.code)
                                expanded = false
                                showValidationError = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                        )
                    }
                }
            }
        }
    }
}

/**
 * プリセット用データクラス
 */
data class HidKeyPreset(val name: String, val code: Int)

/**
 * HID Usage IDからキー名を取得
 *
 * @param keyCode HID Usage ID (Keyboard or Consumer Page)
 * @return キー名
 */
private fun getHidKeyName(keyCode: Int): String {
    // Keyboard Page (0x01 - 0xFF)
    val keyboardMap = mapOf(
        0x50 to "← 左矢印",
        0x4F to "→ 右矢印",
        0x52 to "↑ 上矢印",
        0x51 to "↓ 下矢印",
        0x28 to "Enter",
        0x2C to "Space",
        0x29 to "ESC (Back)",
        0x2A to "Backspace",
        0x2B to "Tab",
        0x04 to "A",
        0x05 to "B",
        0x06 to "C"
        // 必要に応じて追加
    )

    // Consumer Page (0x0100 - 0xFFFF)
    val consumerMap = mapOf(
        0x0224 to "AC Back (戻る)",
        0x0223 to "AC Home",
        0xE9 to "音量アップ",
        0xEA to "音量ダウン",
        0xE2 to "ミュート",
        0xCD to "再生/一時停止",
        0xB5 to "次へ",
        0xB6 to "前へ"
    )

    return if (keyCode > 0xFF) {
        consumerMap[keyCode] ?: "Consumer (0x%X)".format(keyCode)
    } else {
        keyboardMap[keyCode] ?: "Keyboard (0x%X)".format(keyCode)
    }
}
