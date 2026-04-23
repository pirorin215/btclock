package com.pirorin215.btclockmob.di

import android.app.Application
import org.koin.android.ext.koin.androidApplication
import com.pirorin215.btclockmob.viewModel.BleConnectionManager
import com.pirorin215.btclockmob.viewModel.BleOrchestrator
import com.pirorin215.btclockmob.viewModel.BleSelectionManager
import com.pirorin215.btclockmob.viewModel.BleViewModel
import com.pirorin215.btclockmob.viewModel.DeviceHistoryViewModel
import com.pirorin215.btclockmob.viewModel.GoogleTasksManager
import com.pirorin215.btclockmob.viewModel.GoogleTasksViewModel
import com.pirorin215.btclockmob.viewModel.LocationMonitor
import com.pirorin215.btclockmob.viewModel.LogManager
import com.pirorin215.btclockmob.viewModel.MainViewModel
import com.pirorin215.btclockmob.viewModel.TranscriptionManager
import com.pirorin215.btclockmob.viewModel.TranscriptionViewModel
import com.pirorin215.btclockmob.data.AppSettingsRepository
import com.pirorin215.btclockmob.data.TranscriptionResultRepository
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val viewModelModule = module {
    viewModel {
        MainViewModel(
            androidApplication(),
            get<BleConnectionManager>(),
            get<BleOrchestrator>(),
            get<TranscriptionManager>(),
            get<BleSelectionManager>(),
            get<GoogleTasksManager>(),
            get<LocationMonitor>(),
            get<LogManager>(),
            get<TranscriptionResultRepository>(),
            get<AppSettingsRepository>()
        )
    }

    viewModel {
        TranscriptionViewModel(
            androidApplication(),
            get<TranscriptionManager>(),
            get<TranscriptionResultRepository>(),
            get<AppSettingsRepository>(),
            get<LogManager>(),
            get<BleSelectionManager>()
        )
    }

    viewModel { GoogleTasksViewModel(get<GoogleTasksManager>(), get<AppSettingsRepository>()) }

    viewModel { BleViewModel(get<BleConnectionManager>(), get<BleOrchestrator>(), get<BleSelectionManager>()) }

    viewModel { DeviceHistoryViewModel(get()) }
}
