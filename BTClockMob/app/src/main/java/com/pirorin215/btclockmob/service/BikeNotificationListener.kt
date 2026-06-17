package com.pirorin215.btclockmob.service

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.pirorin215.btclockmob.data.AppSettingsRepository
import com.pirorin215.btclockmob.data.BleRepository
import com.pirorin215.btclockmob.data.Settings
import com.pirorin215.btclockmob.viewModel.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * スマホ通知を BikeClock の ePaper に転送する NotificationListener（Phase 11）。
 *
 * システムが「通知へのアクセス」を許可するとバインドされ、アプリプロセスが未起動なら
 * MainApplication.onCreate (startKoin) の後に起動する。Koin のシングルトン (BleRepository 等) を
 * ViewModel/Screen と同じプロセス・同じインスタンスで共有する。
 *
 * プロトコル: "NOTIFY:app=<アプリ名>\n<本文>"（UTF-8、上限200B、応答なし）。
 * マイコン側 (Phase 10) が受信して ePaper に30秒間表示する。
 */
class BikeNotificationListener : NotificationListenerService(), KoinComponent {

    companion object {
        private const val TAG = "BikeNotifListener"
        private const val MAX_BODY_BYTES = 180      // ヘッダ分を見込んだ本文上限（MTU実効244Bに安全に収める）
        private const val DEDUPE_WINDOW_MS = 3000L  // 同一通知の重複送信抑止窓
    }

    private val bleRepository: BleRepository by inject()
    private val logManager: LogManager by inject()
    private val appSettingsRepository: AppSettingsRepository by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // デバウンス用: 同一 sbn.key で (本文同一 かつ 窓内) は送らない
    private val lastSentText = HashMap<String, String>()
    private val lastSentTime = HashMap<String, Long>()

    override fun onListenerConnected() {
        logManager.addLog("通知リスナー接続")
        Log.i(TAG, "Listener connected")
    }

    override fun onListenerDisconnected() {
        logManager.addLog("通知リスナー切断")
        Log.i(TAG, "Listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            // フィルタは即座に（コルーチン外で）
            if (shouldSkip(sbn)) return

            serviceScope.launch {
                // 転送ON/OFF設定（軽量なため都度取得）
                val enabled = appSettingsRepository
                    .getFlow(Settings.NOTIFICATION_FORWARDING_ENABLED).first()
                if (!enabled) return@launch

                val notification = sbn.notification ?: return@launch
                val extras = notification.extras

                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val body = extractText(notification).orEmpty()

                // 本文構成: タイトル + 空白 + 本文。改行は空白に置換（drawWrappedTextが\nを無視するため）
                val combined = listOf(title, body)
                    .map { it.replace("\n", " ").replace("\r", " ").trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString(" ")
                if (combined.isEmpty()) return@launch

                val truncated = truncateUtf8(combined, MAX_BODY_BYTES)

                // デバウンス: 同一通知で同一本文かつ窓内は送信しない
                val key = sbn.key ?: sbn.packageName
                val now = System.currentTimeMillis()
                synchronized(lastSentText) {
                    val prevText = lastSentText[key]
                    val prevTime = lastSentTime[key] ?: 0L
                    if (prevText == truncated && now - prevTime < DEDUPE_WINDOW_MS) {
                        return@launch
                    }
                    lastSentText[key] = truncated
                    lastSentTime[key] = now
                }

                val command = "NOTIFY:app=${sbn.packageName}\n$truncated"
                logManager.addDebugLog("通知転送: ${sbn.packageName} / ${combined.take(40)}")
                bleRepository.sendCommandSerial(command)
            }
        } catch (e: Exception) {
            Log.e(TAG, "onNotificationPosted error", e)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * 転送すべきでない通知を除外する。true を返したらスキップ。
     * - 自己アプリ
     * - グループの親通知（本文なしサマリ）
     * - 常駐通知: 音楽再生 / ナビ / 通話中 等（FLAG_ONGOING_EVENT / FOREGROUND_SERVICE）
     * - ローカルのみ（システムUI用）
     */
    @Suppress("DEPRECATION")
    private fun shouldSkip(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return true
        val n = sbn.notification ?: return true
        val flags = n.flags
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return true
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return true
        if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return true
        if (flags and Notification.FLAG_LOCAL_ONLY != 0) return true
        return false
    }

    /**
     * 通知スタイルに応じて本文テキストを抽出。
     * MessagingStyle（LINE/WhatsApp/SMS）は EXTRA_TEXT が要約になるため、
     * EXTRA_MESSAGES のメッセージBundle配列から最新1件のテキストを取り出す。
     */
    @Suppress("DEPRECATION")
    private fun extractText(n: Notification): String? {
        val extras = n.extras
        // 1) MessagingStyle: 最新メッセージの text。各 Bundle は { "text", "time", "sender" } を持つ。
        val parcelables = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (!parcelables.isNullOrEmpty()) {
            var bestTime = Long.MIN_VALUE
            var bestText: String? = null
            for (p in parcelables) {
                if (p !is Bundle) continue
                val text = p.getCharSequence("text")?.toString() ?: continue
                val time = p.getLong("time", 0L)
                if (time >= bestTime) {
                    bestTime = time
                    bestText = text
                }
            }
            if (bestText != null) return bestText
        }
        // 2) 展開時本文（BIG_TEXT）
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.let { return it.toString() }
        // 3) 通常本文
        return extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    }

    /**
     * UTF-8 で maxBytes を超えないよう切り詰める（マルチバイト文字の途中で切らない）。
     */
    private fun truncateUtf8(text: String, maxBytes: Int): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return text
        var len = maxBytes
        // 末尾が UTF-8 継続バイト(0b10xxxxxx)で終わらないよう巻き戻す
        while (len > 0 && (bytes[len - 1].toInt() and 0xC0) == 0x80) len--
        // その後、先頭バイト(0b11xxxxxx)単独で残っていたら不完全なので削る
        if (len > 0 && (bytes[len - 1].toInt() and 0xC0) == 0xC0) len--
        return String(bytes, 0, len, Charsets.UTF_8)
    }
}
