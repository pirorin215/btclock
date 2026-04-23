package com.pirorin215.btclockmob

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.pirorin215.btclockmob.data.AppSettingsRepository
import com.pirorin215.btclockmob.data.Settings
import com.pirorin215.btclockmob.service.BleScanService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class BootCompletedReceiver : BroadcastReceiver(), KoinComponent {

    private val TAG = "BootCompletedReceiver"
    private val appSettingsRepository: AppSettingsRepository by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Received BOOT_COMPLETED intent.")
            val pendingResult: PendingResult = goAsync()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            scope.launch {
                try {
                    val autoStartOnBoot = appSettingsRepository.getFlow(Settings.AUTO_START_ON_BOOT).first()

                    if (autoStartOnBoot) {
                        Log.d(TAG, "Auto-start on boot is enabled. Starting BleScanService.")
                        val serviceIntent = Intent(context, BleScanService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    } else {
                        Log.d(TAG, "Auto-start on boot is disabled. Not starting BleScanService.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in BootCompletedReceiver: ${e.message}", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
