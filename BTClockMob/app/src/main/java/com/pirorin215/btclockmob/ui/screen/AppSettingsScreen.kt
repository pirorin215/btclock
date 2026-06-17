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
import androidx.compose.ui.platform.LocalContext
import com.pirorin215.btclockmob.bondedBikeClockDeviceNames

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.DisposableEffect
import androidx.core.app.NotificationManagerCompat
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.roundToInt

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
    val currentInterval by appSettingsViewModel.historyIntervalMin.collectAsState()
    val currentDistance by appSettingsViewModel.historyDistanceM.collectAsState()
    val currentRouteOffset by appSettingsViewModel.routeCenterOffset.collectAsState()
    val currentTargetDeviceName by appSettingsViewModel.targetDeviceName.collectAsState()
    val currentNotifFwd by appSettingsViewModel.notificationForwardingEnabled.collectAsState()

    // ペアリング済みのBikeClockデバイス候補（設定画面の選択リスト／自動選択で使用）
    val context = LocalContext.current
    val pairedDevices = remember { bondedBikeClockDeviceNames(context) }

    // 状態を管理
    var selectedThemeMode by remember(currentThemeMode) { mutableStateOf(currentThemeMode) }
    var autoStartOnBootChecked by remember(currentAutoStartOnBoot) { mutableStateOf(currentAutoStartOnBoot) }
    var selectedInterval by remember(currentInterval) { mutableStateOf(currentInterval.toFloat()) }
    var selectedDistance by remember(currentDistance) { mutableStateOf(currentDistance.toFloat()) }
    var selectedRouteOffset by remember(currentRouteOffset) { mutableStateOf(currentRouteOffset) }
    var notifFwdChecked by remember(currentNotifFwd) { mutableStateOf(currentNotifFwd) }
    // 未選択(空)のときは先頭デバイスを事前選択（アプリが実際に接続しに行く対象と一致させる）
    var targetDeviceNameInput by remember(currentTargetDeviceName, pairedDevices) {
        mutableStateOf(currentTargetDeviceName.ifBlank { pairedDevices.firstOrNull() ?: "" })
    }

    // 通知アクセス許可状態。ユーザーがシステム設定から戻った時に ON_RESUME で再評価する。
    var listenerEnabled by remember { mutableStateOf(isNotificationListenerEnabled(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                listenerEnabled = isNotificationListenerEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val saveSettings: () -> Unit = {
        appSettingsViewModel.saveThemeMode(selectedThemeMode)
        appSettingsViewModel.saveAutoStartOnBoot(autoStartOnBootChecked)
        appSettingsViewModel.saveHistoryIntervalMin(selectedInterval.roundToInt())
        appSettingsViewModel.saveHistoryDistanceM(selectedDistance.roundToInt())
        appSettingsViewModel.saveRouteCenterOffset(selectedRouteOffset)
        appSettingsViewModel.saveTargetDeviceName(targetDeviceNameInput.trim())
        appSettingsViewModel.saveNotificationForwardingEnabled(notifFwdChecked)
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
            // Target BLE device name (multi-device switching)
            Text(
                text = stringResource(R.string.target_device_name),
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (pairedDevices.isEmpty()) {
                Text(
                    text = stringResource(R.string.target_device_name_no_paired),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                pairedDevices.forEach { name ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = name == targetDeviceNameInput,
                            onClick = { targetDeviceNameInput = name }
                        )
                        Text(name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // New: Auto-start on boot setting
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.auto_start_on_boot), style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = autoStartOnBootChecked,
                    onCheckedChange = { autoStartOnBootChecked = it },
                    colors = SwitchDefaults.colors()
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // Phase 11: スマホ通知転送 ON/OFF ＋ 通知アクセス許可誘導
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.notification_forwarding), style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = notifFwdChecked,
                    onCheckedChange = { notifFwdChecked = it },
                    colors = SwitchDefaults.colors()
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (listenerEnabled) {
                Text(
                    text = stringResource(R.string.notification_access_granted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = stringResource(R.string.notification_access_not_granted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    )
                }) {
                    Text(stringResource(R.string.notification_access_permission))
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // Periodic History Interval
            Text(
                text = "${stringResource(R.string.history_interval)}: ${selectedInterval.roundToInt()}分",
                style = MaterialTheme.typography.bodyLarge
            )
            Slider(
                value = selectedInterval,
                onValueChange = { selectedInterval = it },
                valueRange = 1f..60f,
                steps = 58
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Periodic History Distance
            Text(
                text = "${stringResource(R.string.history_distance)}: ${selectedDistance.roundToInt()}m",
                style = MaterialTheme.typography.bodyLarge
            )
            Slider(
                value = selectedDistance,
                onValueChange = { selectedDistance = it },
                valueRange = 100f..5000f,
                steps = 48
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // Route Center Offset
            Text(
                text = "${stringResource(R.string.route_center_offset)}: ${selectedRouteOffset.roundToInt()}",
                style = MaterialTheme.typography.bodyLarge
            )
            Slider(
                value = selectedRouteOffset,
                onValueChange = { selectedRouteOffset = it },
                valueRange = -1000f..1000f,
                steps = 2000
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // Theme mode selection
            Text(stringResource(R.string.theme_mode), style = MaterialTheme.typography.bodyLarge)
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
                        Text(themeMode.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** 通知アクセスがこのアプリに許可されているか（Phase 11） */
private fun isNotificationListenerEnabled(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
