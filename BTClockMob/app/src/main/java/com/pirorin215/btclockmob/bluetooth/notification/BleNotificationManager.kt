package com.pirorin215.btclockmob.bluetooth.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.pirorin215.btclockmob.MainActivity
import com.pirorin215.btclockmob.R
import com.pirorin215.btclockmob.bluetooth.constants.BleConstants
import com.pirorin215.btclockmob.data.AppSettingsRepository
import com.pirorin215.btclockmob.data.Settings
import com.pirorin215.btclockmob.viewModel.LogManager
import kotlinx.coroutines.flow.first

/**
 * BLEデバイスの通知を管理するクラス
 *
 * 役割:
 * - 低電圧通知チャンネルの作成
 * - 低電圧チェックと通知の発行
 *
 * @property context アプリケーションコンテキスト
 * @property appSettingsRepository アプリ設定リポジトリ
 * @property logManager ログマネージャー
 */
class BleNotificationManager(
    private val context: Context,
    private val appSettingsRepository: AppSettingsRepository,
    private val logManager: LogManager
) {
    // 低電圧通知済みフラグ（初回のみ通知の場合に使用）
    private var hasNotifiedLowVoltage = false

    init {
        createLowVoltageNotificationChannel()
    }

    /**
     * 低電圧通知チャンネルを作成する
     * Android O以降で必要な通知チャンネルの初期化
     */
    private fun createLowVoltageNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BleConstants.LOW_VOLTAGE_CHANNEL_ID,
                "低電圧警告",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "バッテリー電圧が低下した際の警告通知"
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            logManager.addLog("低電圧通知チャンネルを作成しました")
        }
    }

    /**
     * 低電圧をチェックし、必要に応じて通知を発行する
     *
     * @param voltage 現在の電圧（V）
     */
    suspend fun checkAndNotifyLowVoltage(voltage: Float) {
        val threshold = appSettingsRepository.getFlow(Settings.LOW_VOLTAGE_THRESHOLD).first()
        val notifyEveryTime = appSettingsRepository.getFlow(Settings.LOW_VOLTAGE_NOTIFY_EVERY_TIME).first()

        // しきい値が0の場合は通知OFF
        if (threshold <= 0f) {
            return
        }

        // 電圧がしきい値以上なら通知フラグをリセット
        if (voltage >= threshold) {
            hasNotifiedLowVoltage = false
            return
        }

        // 初回のみ通知モードで既に通知済みなら何もしない
        if (!notifyEveryTime && hasNotifiedLowVoltage) {
            logManager.addLog("低電圧検知 (${voltage}V) ですが、初回のみ通知モードで既に通知済みのためスキップします")
            return
        }

        // 通知を発行
        sendLowVoltageNotification(voltage, threshold)
        hasNotifiedLowVoltage = true
        logManager.addLog("低電圧通知を送信しました: ${voltage}V < ${threshold}V")
    }

    /**
     * 低電圧通知を送信する
     *
     * @param voltage 現在の電圧（V）
     * @param threshold 通知しきい値（V）
     */
    private fun sendLowVoltageNotification(voltage: Float, threshold: Float) {
        val notificationIntent = Intent(context, MainActivity::class.java).apply {
            action = "SHOW_UI"
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, BleConstants.LOW_VOLTAGE_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.low_voltage_notification_title))
            .setContentText(context.getString(R.string.low_voltage_notification_text, voltage, threshold))
            .setSmallIcon(R.drawable.ic_notification_alert)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(BleConstants.LOW_VOLTAGE_NOTIFICATION_ID, notification)
    }

    /**
     * 通知フラグをリセットする
     * デバイス再接続時などに使用
     */
    fun resetNotificationFlag() {
        hasNotifiedLowVoltage = false
        logManager.addLog("低電圧通知フラグをリセットしました")
    }
}
