package com.pirorin215.btclockmob.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.pirorin215.btclockmob.R
import com.pirorin215.btclockmob.data.ThemeMode
import com.pirorin215.btclockmob.viewModel.AppSettingsViewModel
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults

import androidx.activity.compose.BackHandler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(appSettingsViewModel: AppSettingsViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    // stringResourceを事前に取得
    val saveButtonText = stringResource(R.string.save_button)
    val appSettingsTitle = stringResource(R.string.app_settings_title)

    // DataStoreから現在の設定値を取得
    val currentThemeMode by appSettingsViewModel.themeMode.collectAsState()
    val currentAutoStartOnBoot by appSettingsViewModel.autoStartOnBoot.collectAsState()

    // 状態を管理
    var selectedThemeMode by remember(currentThemeMode) { mutableStateOf(currentThemeMode) }
    var autoStartOnBootChecked by remember(currentAutoStartOnBoot) { mutableStateOf(currentAutoStartOnBoot) }

    val saveSettings: () -> Unit = {
        appSettingsViewModel.saveThemeMode(selectedThemeMode)
        appSettingsViewModel.saveAutoStartOnBoot(autoStartOnBootChecked)
        onBack()
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
            
            Spacer(modifier = Modifier.height(24.dp))

            // Theme mode selection
            Text(stringResource(R.string.theme_mode), fontSize = 16.sp)
            Spacer(modifier = Modifier.height(8.dp))
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
}
