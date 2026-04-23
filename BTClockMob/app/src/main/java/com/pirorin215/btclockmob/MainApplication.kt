package com.pirorin215.btclockmob

import android.app.Application
import com.pirorin215.btclockmob.di.appModule
import com.pirorin215.btclockmob.di.bleModule
import com.pirorin215.btclockmob.di.managerModule
import com.pirorin215.btclockmob.di.repositoryModule
import com.pirorin215.btclockmob.di.transcriptionModule
import com.pirorin215.btclockmob.di.viewModelModule
import com.pirorin215.btclockmob.viewModel.BleConnectionManager
import com.pirorin215.btclockmob.viewModel.BleOrchestrator
import com.pirorin215.btclockmob.viewModel.LogManager
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class MainApplication : Application(), KoinComponent {

    // Inject dependencies from Koin
    private val logManager: LogManager by inject()
    private val bleConnectionManager: BleConnectionManager by inject()
    private val bleOrchestrator: BleOrchestrator by inject()

    override fun onCreate() {
        super.onCreate()

        // Initialize Koin
        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@MainApplication)
            modules(
                appModule,
                repositoryModule,
                managerModule,
                transcriptionModule,
                bleModule,
                viewModelModule
            )
        }

        // Initialize core components
        logManager.addLog("Application created. Initializing core components.")

        // Start core components by accessing them from Koin
        // This triggers the lazy initialization
        bleConnectionManager
        bleOrchestrator
    }
}
