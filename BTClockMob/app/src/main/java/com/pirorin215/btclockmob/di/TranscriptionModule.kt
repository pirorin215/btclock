package com.pirorin215.btclockmob.di

import android.content.Context
import com.pirorin215.btclockmob.data.AppSettingsRepository
import com.pirorin215.btclockmob.data.Settings
import com.pirorin215.btclockmob.data.TranscriptionResultRepository
import com.pirorin215.btclockmob.viewModel.GoogleTasksManager
import com.pirorin215.btclockmob.viewModel.LocationMonitor
import com.pirorin215.btclockmob.viewModel.LogManager
import com.pirorin215.btclockmob.viewModel.TranscriptionManager
import com.pirorin215.btclockmob.LocationTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import org.koin.dsl.module

val transcriptionModule = module {
    single {
        TranscriptionManager(
            get<Context>(),
            get<CoroutineScope>(),
            get(),
            get(),
            get<LocationMonitor>().currentForegroundLocation,
            get<AppSettingsRepository>().getFlow(Settings.AUDIO_DIR_NAME).stateIn(
                get<CoroutineScope>(),
                SharingStarted.WhileSubscribed(5000),
                "BTClockRecordings"
            ),
            get<AppSettingsRepository>().getFlow(Settings.TRANSCRIPTION_CACHE_LIMIT).stateIn(
                get<CoroutineScope>(),
                SharingStarted.WhileSubscribed(5000),
                100
            ),
            get(),
            get<AppSettingsRepository>().getFlow(Settings.GOOGLE_TASK_TITLE_LENGTH).stateIn(
                get<CoroutineScope>(),
                SharingStarted.WhileSubscribed(5000),
                20
            ),
            get(),
            get()
        )
    }
}
