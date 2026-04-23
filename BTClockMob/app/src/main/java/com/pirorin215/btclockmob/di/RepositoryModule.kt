package com.pirorin215.btclockmob.di

import android.content.Context
import com.pirorin215.btclockmob.data.AppSettingsRepository
import com.pirorin215.btclockmob.data.BleRepository
import com.pirorin215.btclockmob.data.DeviceHistoryRepository
import com.pirorin215.btclockmob.data.LastKnownLocationRepository
import com.pirorin215.btclockmob.data.TranscriptionResultRepository
import org.koin.dsl.module

val repositoryModule = module {
    single { AppSettingsRepository(get<Context>()) }
    single { BleRepository(get<Context>()) }
    single { TranscriptionResultRepository(get<Context>()) }
    single { LastKnownLocationRepository(get<Context>()) }
    single { DeviceHistoryRepository(get<Context>()) }
}
