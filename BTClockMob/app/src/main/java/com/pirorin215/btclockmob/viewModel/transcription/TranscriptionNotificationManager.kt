package com.pirorin215.btclockmob.viewModel.transcription

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.pirorin215.btclockmob.MainActivity
import com.pirorin215.btclockmob.R
import com.pirorin215.btclockmob.data.AppSettingsRepository
import com.pirorin215.btclockmob.data.Settings
import com.pirorin215.btclockmob.viewModel.LogManager
import kotlinx.coroutines.flow.first

/**
 * 文字起こしの通知処理を担当するマネージャークラス
 * 通知チャネル作成と通知送信を担当
 */
class TranscriptionNotificationManager(
    private val context: Context,
    private val appSettingsRepository: AppSettingsRepository,
    private val logManager: LogManager
) {
    companion object {
        const val TRANSCRIPTION_CHANNEL_ID = "TranscriptionChannel"
        const val TRANSCRIPTION_NOTIFICATION_ID = 2002
    }

    private var notificationIdCounter = TRANSCRIPTION_NOTIFICATION_ID

    /**
     * 通知チャンネルを作成
     */
    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                TRANSCRIPTION_CHANNEL_ID,
                "文字起こし通知",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "文字起こし完了時の通知"
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 文字起こし完了通知を送信
     */
    suspend fun sendTranscriptionNotification(transcriptionText: String) {
        val isEnabled = appSettingsRepository.getFlow(Settings.TRANSCRIPTION_NOTIFICATION_ENABLED).first()
        if (!isEnabled) {
            return
        }

        val notificationIntent = Intent(context, MainActivity::class.java).apply {
            action = "SHOW_UI"
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, TRANSCRIPTION_CHANNEL_ID)
            .setContentTitle(transcriptionText.take(50))
            .setSmallIcon(R.drawable.ic_notification_transcription)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationIdCounter++, notification)
        logManager.addDebugLog("Notification sent")
    }
}
