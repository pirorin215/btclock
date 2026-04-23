package com.pirorin215.btclockmob.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pirorin215.btclockmob.data.DeviceSettings
import com.pirorin215.btclockmob.viewModel.MainViewModel
import com.pirorin215.btclockmob.viewModel.BleOperation
import com.pirorin215.btclockmob.viewModel.NavigationEvent
import android.util.Log

import androidx.activity.compose.BackHandler

import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
// ...
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    val scope = rememberCoroutineScope() // Add this line

    val settings: DeviceSettings? by viewModel.deviceSettings.collectAsState()
    val operation: BleOperation by viewModel.currentOperation.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.getSettings()
    }

    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { event ->
            when (event) {
                is NavigationEvent.NavigateBack -> onBack()
            }
        }
    }

    LaunchedEffect(settings) { // Trigger when settings changes
        settings?.let {
            Log.d("SettingsScreen", "Settings updated in UI: $it")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("レコーダ設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Button(
                        onClick = { scope.launch { viewModel.getSettings() } },
                        enabled = operation == BleOperation.IDLE
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Settings")
                        Spacer(Modifier.width(8.dp))
                        Text("取得")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { viewModel.sendSettings() },
                        enabled = operation == BleOperation.IDLE
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send Settings")
                        Spacer(Modifier.width(8.dp))
                        Text("送信")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            if (operation == BleOperation.FETCHING_SETTINGS && settings == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                settings?.let {
                    SettingsList(
                        modifier = Modifier.weight(1f),
                        settings = it,
                        onSettingChange = { updatedSettings ->
                            viewModel.updateSettings { updatedSettings }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsList(
    modifier: Modifier = Modifier,
    settings: DeviceSettings,
    onSettingChange: (DeviceSettings) -> Unit
) {
    LazyColumn(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { SettingTextField("Deep Sleep Delay (ms)", settings.deepSleepDelayMs, KeyboardType.Number) { onSettingChange(settings.copy(deepSleepDelayMs = it)) } }
        item { SettingTextField("Sleep Adj", settings.sleepAdj, KeyboardType.Decimal) { onSettingChange(settings.copy(sleepAdj = it)) } }
        item { SettingTextField("Min Battery Voltage", settings.batVolMin, KeyboardType.Decimal) { onSettingChange(settings.copy(batVolMin = it)) } }
        item { SettingTextField("Battery Voltage Multiplier", settings.batVolMult, KeyboardType.Decimal) { onSettingChange(settings.copy(batVolMult = it)) } }
        item { SettingTextField("I2S Sample Rate", settings.i2sSampleRate, KeyboardType.Number) { onSettingChange(settings.copy(i2sSampleRate = it)) } }
        item { SettingTextField("Max Record Time (s)", settings.recMaxS, KeyboardType.Number) { onSettingChange(settings.copy(recMaxS = it)) } }
        item { SettingTextField("Min Record Time (s)", settings.recMinS, KeyboardType.Number) { onSettingChange(settings.copy(recMinS = it)) } }
        item { SettingTextField("Audio Gain", settings.audioGain, KeyboardType.Decimal) { onSettingChange(settings.copy(audioGain = it)) } }
        item { SettingSwitch("Vibration Enabled", settings.vibra) { onSettingChange(settings.copy(vibra = it)) } }
        item { SettingSwitch("Log at Boot", settings.logAtBoot) { onSettingChange(settings.copy(logAtBoot = it)) } }
        item { SettingTextField("Deep Sleep Cycle (minutes)", settings.deepSleepCycleMinutes, KeyboardType.Number) { onSettingChange(settings.copy(deepSleepCycleMinutes = it)) } }
        item { SettingSwitch("Use ADPCM", settings.useAdpcm) { onSettingChange(settings.copy(useAdpcm = it)) } }
        item { SettingSwitch("Dev Mode", settings.devMode) { onSettingChange(settings.copy(devMode = it)) } }

    }
}

@Composable
fun SettingTextField(label: String, value: String, keyboardType: KeyboardType, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
    )
}

@Composable
fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}