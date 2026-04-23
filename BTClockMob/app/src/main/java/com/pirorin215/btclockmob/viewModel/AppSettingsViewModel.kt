package com.pirorin215.btclockmob.viewModel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pirorin215.btclockmob.data.AppSettingsRepository
import com.pirorin215.btclockmob.data.Settings
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import com.pirorin215.btclockmob.data.ThemeMode

class AppSettingsViewModel(
    private val appSettingsRepository: AppSettingsRepository,
    private val application: Application
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = appSettingsRepository.getFlow(Settings.THEME_MODE)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ThemeMode.SYSTEM // Default to SYSTEM
        )

    val autoStartOnBoot: StateFlow<Boolean> = appSettingsRepository.getFlow(Settings.AUTO_START_ON_BOOT)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val historyIntervalMin: StateFlow<Int> = appSettingsRepository.getFlow(Settings.HISTORY_INTERVAL_MIN)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 15
        )

    val historyDistanceM: StateFlow<Int> = appSettingsRepository.getFlow(Settings.HISTORY_DISTANCE_M)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 1000
        )

    fun saveThemeMode(themeMode: ThemeMode) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.THEME_MODE, themeMode)
        }
    }

    fun saveAutoStartOnBoot(enable: Boolean) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.AUTO_START_ON_BOOT, enable)
        }
    }

    fun saveHistoryIntervalMin(minutes: Int) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.HISTORY_INTERVAL_MIN, minutes)
        }
    }

    fun saveHistoryDistanceM(meters: Int) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.HISTORY_DISTANCE_M, meters)
        }
    }
}
