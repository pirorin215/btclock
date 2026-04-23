package com.pirorin215.btclockmob.ui.screen

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.material3.SwitchDefaults
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
import com.pirorin215.btclockmob.viewModel.MainViewModel
import com.pirorin215.btclockmob.viewModel.AppSettingsViewModel
import com.pirorin215.btclockmob.data.GoogleTasksMode

import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.pirorin215.btclockmob.R
// ...
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoogleTasksSyncSettingsScreen(
    viewModel: MainViewModel,
    appSettingsViewModel: AppSettingsViewModel,
    onBack: () -> Unit,
    onSignInIntent: suspend (Intent) -> Unit // New callback
) {
    BackHandler(onBack = onBack)
    val googleAccount by viewModel.account.collectAsState()
    val isLoadingGoogleTasks by viewModel.isLoadingGoogleTasks.collectAsState()
    val currentGoogleTodoListName by appSettingsViewModel.googleTodoListName.collectAsState()
    var googleTodoListNameText by remember(currentGoogleTodoListName) { mutableStateOf(currentGoogleTodoListName) }

    val currentGasWebhookUrl by appSettingsViewModel.gasWebhookUrl.collectAsState()
    var gasWebhookUrlText by remember(currentGasWebhookUrl) { mutableStateOf(currentGasWebhookUrl) }

    val currentEnableGoogleTaskDue by appSettingsViewModel.enableGoogleTaskDue.collectAsState()
    var enableGoogleTaskDueChecked by remember(currentEnableGoogleTaskDue) { mutableStateOf(currentEnableGoogleTaskDue) }

    val currentSyncInterval by appSettingsViewModel.googleTasksSyncIntervalMinutes.collectAsState()
    var sliderPosition by remember(currentSyncInterval) { mutableStateOf(currentSyncInterval.toFloat()) }

    val currentGoogleTasksMode by appSettingsViewModel.googleTasksMode.collectAsState()
    var selectedGoogleTasksMode by remember(currentGoogleTasksMode) { mutableStateOf(currentGoogleTasksMode) }

    val scope = rememberCoroutineScope() // Add coroutine scope

    val saveSettings = {
        appSettingsViewModel.saveGoogleTodoListName(googleTodoListNameText)
        appSettingsViewModel.saveGasWebhookUrl(gasWebhookUrlText)
        appSettingsViewModel.saveEnableGoogleTaskDue(enableGoogleTaskDueChecked)
        appSettingsViewModel.saveGoogleTasksSyncIntervalMinutes(sliderPosition.toInt())
        appSettingsViewModel.saveGoogleTasksMode(selectedGoogleTasksMode)
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.google_tasks_sync_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = saveSettings) {
                        Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.save_button))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Google Tasks Mode Toggle (一番上に移動)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "OAuth",
                    fontSize = 16.sp,
                    color = if (selectedGoogleTasksMode == GoogleTasksMode.OAUTH) {
                        androidx.compose.material3.MaterialTheme.colorScheme.primary
                    } else {
                        androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Switch(
                    checked = selectedGoogleTasksMode == GoogleTasksMode.GAS,
                    onCheckedChange = { checked ->
                        selectedGoogleTasksMode = if (checked) GoogleTasksMode.GAS else GoogleTasksMode.OAUTH
                    },
                    colors = SwitchDefaults.colors()
                )
                Text(
                    text = "GAS",
                    fontSize = 16.sp,
                    color = if (selectedGoogleTasksMode == GoogleTasksMode.GAS) {
                        androidx.compose.material3.MaterialTheme.colorScheme.primary
                    } else {
                        androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when (selectedGoogleTasksMode) {
                    GoogleTasksMode.OAUTH -> "Google Sign-Inで認証"
                    GoogleTasksMode.GAS -> "Google Apps Scriptを使用"
                },
                fontSize = 12.sp,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Google Todo List名
            OutlinedTextField(
                value = googleTodoListNameText,
                onValueChange = { googleTodoListNameText = it },
                label = { Text(stringResource(R.string.google_todo_list_name_label)) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            // GAS Webhook URL (GAS mode only)
            OutlinedTextField(
                value = gasWebhookUrlText,
                onValueChange = { gasWebhookUrlText = it },
                label = { Text("GAS Webhook URL") },
                placeholder = { Text("https://script.google.com/macros/s/...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = selectedGoogleTasksMode == GoogleTasksMode.GAS
            )
            Spacer(modifier = Modifier.height(8.dp))

            // 登録時の日付で期限を設定
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("登録時の日付で期限を設定", fontSize = 16.sp)
                Switch(
                    checked = enableGoogleTaskDueChecked,
                    onCheckedChange = { enableGoogleTaskDueChecked = it },
                    colors = SwitchDefaults.colors()
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            // 同期間隔スライダー
            Text(stringResource(R.string.google_tasks_sync_interval_minutes, sliderPosition.toInt()))
            Slider(
                value = sliderPosition,
                onValueChange = { sliderPosition = it },
                valueRange = 1f..60f,
                steps = 58, // 60 - 1 - 1 = 58 steps for integer values
                onValueChangeFinished = {
                    // Auto-save sync interval
                    appSettingsViewModel.saveGoogleTasksSyncIntervalMinutes(sliderPosition.toInt())
                }
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Google Sign-In/Out UI (OAuth mode only)
            if (selectedGoogleTasksMode == GoogleTasksMode.OAUTH) {
                if (googleAccount == null) {
                    Button(
                        onClick = {
                            scope.launch { // Use coroutine scope
                                viewModel.getGoogleSignInIntent()?.let { intent ->
                                    onSignInIntent(intent)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.sign_in_to_google_tasks))
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.currently_logged_in_account),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "${googleAccount?.displayName}",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = { viewModel.signOut() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = stringResource(R.string.sign_out_content_description))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.sign_out_button))
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = { viewModel.syncTranscriptionResultsWithGoogleTasks() },
                                    enabled = !isLoadingGoogleTasks,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Sync, contentDescription = stringResource(R.string.sync_with_google_tasks_content_description))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.sync_button))
                                }
                            }
                        }
                    }
                }
            }

            if (isLoadingGoogleTasks) {
                Spacer(modifier = Modifier.height(16.dp))
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.syncing_with_google_tasks), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
