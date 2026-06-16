package com.pirorin215.btclockmob

import android.app.Application
import com.pirorin215.btclockmob.data.AppSettingsRepository
import com.pirorin215.btclockmob.data.Settings
import com.pirorin215.btclockmob.di.appModule
import com.pirorin215.btclockmob.di.bleModule
import com.pirorin215.btclockmob.di.managerModule
import com.pirorin215.btclockmob.di.repositoryModule
import com.pirorin215.btclockmob.di.viewModelModule
import com.pirorin215.btclockmob.viewModel.BleConnectionManager
import com.pirorin215.btclockmob.viewModel.BleOrchestrator
import com.pirorin215.btclockmob.viewModel.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
    private val appSettingsRepository: AppSettingsRepository by inject()
    private val appScope: CoroutineScope by inject()

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
                bleModule,
                viewModelModule
            )
        }

        // Initialize core components
        logManager.addLog("Application created. Initializing core components.")

        // 接続先デバイス名(設定値)をスキャン/接続の共有シングルトンへ同期する。
        // ・初回 emission: 名前を反映するのみ（この後の初回スキャンに間に合わせる）
        // ・変更時: 強制再接続で新しい名前を即時反映（デバイス切り替えを可能にする）
        appScope.launch {
            var firstValue = true
            appSettingsRepository.getFlow(Settings.TARGET_DEVICE_NAME).collect { name ->
                val normalized = name.trim()
                if (firstValue) {
                    BleScanServiceManager.targetDeviceName = normalized
                    logManager.addDebugLog("Target device preference loaded: ${normalized.ifBlank { "(auto)" }}")
                    firstValue = false
                } else if (normalized != BleScanServiceManager.targetDeviceName) {
                    BleScanServiceManager.targetDeviceName = normalized
                    logManager.addLog("Target device changed: ${normalized.ifBlank { "(auto)" }}. Forcing reconnect.")
                    bleConnectionManager.forceReconnect()
                }
            }
        }

        // Start core components by accessing them from Koin
        // This triggers the lazy initialization
        bleConnectionManager
        bleOrchestrator
    }
}
