package com.pirorin215.btclockmob.di

import android.content.Context
import com.pirorin215.btclockmob.data.AppSettingsRepository
import com.pirorin215.btclockmob.data.BleRepository
import com.pirorin215.btclockmob.data.ConnectionState
import com.pirorin215.btclockmob.data.DeviceHistoryRepository
import com.pirorin215.btclockmob.viewModel.BleConnectionManager
import com.pirorin215.btclockmob.viewModel.BleOrchestrator
import com.pirorin215.btclockmob.viewModel.LocationMonitor
import com.pirorin215.btclockmob.viewModel.LogManager
import com.pirorin215.btclockmob.LocationTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.koin.core.qualifier.named
import org.koin.dsl.module

val bleModule = module {
    // BLE Flows - 名前付きで登録して区別
    single(named("connectionStateFlow")) {
        MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    }

    single(named("onDeviceReadyEvent")) {
        MutableSharedFlow<Unit>()
    }

    single(named("disconnectSignal")) {
        MutableSharedFlow<Unit>()
    }

    single {
        BleConnectionManager(
            get<Context>(),
            get<CoroutineScope>(),
            get(),
            get(),
            get(named("connectionStateFlow")),
            get(named("onDeviceReadyEvent")),
            get(named("disconnectSignal"))
        )
    }

    single {
        val connectionManager = get<BleConnectionManager>()

        BleOrchestrator(
            scope = get<CoroutineScope>(),
            context = get<Context>(),
            repository = get(),
            connectionStateFlow = connectionManager.connectionState,
            onDeviceReadyEvent = get<MutableSharedFlow<Unit>>(named("onDeviceReadyEvent")).asSharedFlow(),
            locationMonitor = get(),
            appSettingsRepository = get(),
            logManager = get(),
            disconnectSignal = get<MutableSharedFlow<Unit>>(named("disconnectSignal")).asSharedFlow(),
            locationTracker = get(),
            deviceHistoryRepository = get()
        )
    }
}
