package com.pirorin215.btclockmob.di

import android.content.Context
import com.pirorin215.btclockmob.data.AppSettingsRepository
import com.pirorin215.btclockmob.data.BatteryLogRepository
import com.pirorin215.btclockmob.data.BleRepository
import com.pirorin215.btclockmob.data.DeviceHistoryRepository
import com.pirorin215.btclockmob.data.KeyCodeSettingsRepository
import com.pirorin215.btclockmob.data.LastKnownLocationRepository
import com.pirorin215.btclockmob.data.MotionTrainingRepository
import com.pirorin215.btclockmob.data.RideLogExporter
import org.koin.dsl.module

val repositoryModule = module {
    single { AppSettingsRepository(get<Context>()) }
    single { KeyCodeSettingsRepository(get<Context>()) }
    single { BleRepository(get<Context>()) }
    single { LastKnownLocationRepository(get<Context>()) }
    single { DeviceHistoryRepository(get<Context>()) }
    single { BatteryLogRepository(get<Context>()) }
    single { MotionTrainingRepository(get<Context>()) }
    single { RideLogExporter(get<Context>(), get()) }
}
