package com.pirorin215.btclockmob.viewModel

import com.pirorin215.btclockmob.data.AppSettingsRepository
import com.pirorin215.btclockmob.data.Settings
import com.pirorin215.btclockmob.data.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch // Add this import

class AppSettingsViewModelDelegate(
    private val appSettingsRepository: AppSettingsRepository,
    private val scope: CoroutineScope
) : AppSettingsAccessor {

    override val apiKey: StateFlow<String> = appSettingsRepository.getFlow(Settings.API_KEY)
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    override val transcriptionCacheLimit: StateFlow<Int> = appSettingsRepository.getFlow(Settings.TRANSCRIPTION_CACHE_LIMIT)
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 100 // Default to 100 files
        )

    override val transcriptionFontSize: StateFlow<Int> = appSettingsRepository.getFlow(Settings.TRANSCRIPTION_FONT_SIZE)
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 14 // Default to 14
        )

    override val audioDirName: StateFlow<String> = appSettingsRepository.getFlow(Settings.AUDIO_DIR_NAME)
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "BTClockRecordings" // Default directory name
        )

    override val themeMode: StateFlow<ThemeMode> = appSettingsRepository.getFlow(Settings.THEME_MODE)
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ThemeMode.SYSTEM // Default to SYSTEM
        )

    override val googleTodoListName: StateFlow<String> = appSettingsRepository.getFlow(Settings.GOOGLE_TODO_LIST_NAME)
        .stateIn(scope, SharingStarted.Eagerly, "")

    override val googleTaskTitleLength: StateFlow<Int> = appSettingsRepository.getFlow(Settings.GOOGLE_TASK_TITLE_LENGTH)
        .stateIn(scope, SharingStarted.Eagerly, 20) // Default to 20

    override val googleTasksSyncIntervalMinutes: StateFlow<Int> = appSettingsRepository.getFlow(Settings.GOOGLE_TASKS_SYNC_INTERVAL_MINUTES)
        .stateIn(scope, SharingStarted.Eagerly, 5) // Default to 5

    override fun saveApiKey(apiKey: String) {
        scope.launch { appSettingsRepository.setValue(Settings.API_KEY, apiKey) }
    }

    override fun saveTranscriptionCacheLimit(limit: Int) {
        scope.launch { appSettingsRepository.setValue(Settings.TRANSCRIPTION_CACHE_LIMIT, limit) }
    }

    override fun saveTranscriptionFontSize(size: Int) {
        scope.launch { appSettingsRepository.setValue(Settings.TRANSCRIPTION_FONT_SIZE, size) }
    }

    override fun saveAudioDirName(name: String) {
        scope.launch { appSettingsRepository.setValue(Settings.AUDIO_DIR_NAME, name) }
    }

    override fun saveThemeMode(mode: ThemeMode) {
        scope.launch { appSettingsRepository.setValue(Settings.THEME_MODE, mode) }
    }

    override fun saveGoogleTodoListName(name: String) {
        scope.launch { appSettingsRepository.setValue(Settings.GOOGLE_TODO_LIST_NAME, name) }
    }

    override fun saveGoogleTaskTitleLength(length: Int) {
        scope.launch { appSettingsRepository.setValue(Settings.GOOGLE_TASK_TITLE_LENGTH, length) }
    }

    override fun saveGoogleTasksSyncIntervalMinutes(minutes: Int) {
        scope.launch { appSettingsRepository.setValue(Settings.GOOGLE_TASKS_SYNC_INTERVAL_MINUTES, minutes) }
    }
}
