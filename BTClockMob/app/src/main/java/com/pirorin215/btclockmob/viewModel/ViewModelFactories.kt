package com.pirorin215.btclockmob.viewModel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import com.pirorin215.btclockmob.data.AppSettingsRepository
import com.pirorin215.btclockmob.data.KeyCodeSettingsRepository
import com.pirorin215.btclockmob.data.LastKnownLocationRepository


// import com.pirorin215.btclockmob.viewModel.LogManager // Import LogManager once -- Keeping this if needed, but removing MainViewModel import

// MainViewModelFactory block removed

class AppSettingsViewModelFactory(
    private val application: Application,
    private val appSettingsRepository: AppSettingsRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppSettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AppSettingsViewModel(appSettingsRepository, application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class KeyCodeSettingsViewModelFactory(
    private val application: Application,
    private val keyCodeSettingsRepository: KeyCodeSettingsRepository,
    private val bleOrchestrator: BleOrchestrator
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(KeyCodeSettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return KeyCodeSettingsViewModel(application, keyCodeSettingsRepository, bleOrchestrator) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}