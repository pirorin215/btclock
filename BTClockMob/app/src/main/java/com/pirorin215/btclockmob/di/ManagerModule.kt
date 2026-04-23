package com.pirorin215.btclockmob.di

import android.content.Context
import com.pirorin215.btclockmob.LocationTracker
import com.pirorin215.btclockmob.viewModel.BleSelectionManager
import com.pirorin215.btclockmob.viewModel.LocationMonitor
import com.pirorin215.btclockmob.viewModel.LogManager
import com.pirorin215.btclockmob.data.AppSettingsRepository
import kotlinx.coroutines.CoroutineScope
import org.koin.dsl.module

val managerModule = module {
    single { LogManager() }
    single { LocationTracker(get<Context>()) }
    single {
        LocationMonitor(
            get<Context>(),
            get<CoroutineScope>(),
            get(),
            get()
        )
    }
    single {
        BleSelectionManager(
            get()
        )
    }
}
