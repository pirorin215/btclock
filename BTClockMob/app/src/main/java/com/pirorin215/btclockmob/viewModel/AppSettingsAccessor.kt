package com.pirorin215.btclockmob.viewModel

import com.pirorin215.btclockmob.data.ThemeMode
import kotlinx.coroutines.flow.StateFlow

interface AppSettingsAccessor {
    val apiKey: StateFlow<String>
    val transcriptionCacheLimit: StateFlow<Int>
    val transcriptionFontSize: StateFlow<Int>
    val audioDirName: StateFlow<String>
    val themeMode: StateFlow<ThemeMode>

    // Google Tasks properties removed - feature disabled

    fun saveApiKey(apiKey: String)
    fun saveTranscriptionCacheLimit(limit: Int)
    fun saveTranscriptionFontSize(size: Int)
    fun saveAudioDirName(name: String)
    fun saveThemeMode(mode: ThemeMode)
}