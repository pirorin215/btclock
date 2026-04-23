package com.pirorin215.btclockmob.di

import android.app.Application
import org.koin.android.ext.koin.androidApplication
import com.pirorin215.btclockmob.viewModel.BleConnectionManager
import com.pirorin215.btclockmob.viewModel.BleOrchestrator
import com.pirorin215.btclockmob.viewModel.BleViewModel
import com.pirorin215.btclockmob.viewModel.DeviceHistoryViewModel
import com.pirorin215.btclockmob.viewModel.LocationMonitor
import com.pirorin215.btclockmob.viewModel.LogManager
import com.pirorin215.btclockmob.viewModel.MainViewModel
import com.pirorin215.btclockmob.data.AppSettingsRepository
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val viewModelModule = module {
    viewModel {
        MainViewModel(
            get<Application>(),
            get<BleConnectionManager>(),
            get<BleOrchestrator>(),
            get<LocationMonitor>(),
            get<LogManager>(),
            get<AppSettingsRepository>()
        )
    }

    viewModel { BleViewModel(get<BleConnectionManager>(), get<BleOrchestrator>()) }


    viewModel { DeviceHistoryViewModel(get()) }
}
