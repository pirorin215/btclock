package com.pirorin215.btclockmob.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * キーイベント注入用アクセシビリティサービス
 *
 * 機能:
 * - メディアキーの送信（再生/停止、次/前のトラック）
 *
 * 注意:
 * - ユーザーが設定→アクセシビリティで有効にする必要がある
 * - Androidのセキュリティ制限により、メディアキーのみサポート
 * - 方向キーなどの他のキーはセキュリティ上送信できない
 */
class KeyInjectorService : AccessibilityService() {

    companion object {
        private const val TAG = "KeyInjectorService"

        @Volatile
        private var instance: KeyInjectorService? = null

        /**
         * サービスインスタンスを取得
         */
        fun getInstance(): KeyInjectorService? = instance

        /**
         * サービスが有効かどうかを確認
         */
        fun isEnabled(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "KeyInjectorService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // イベントは使用しない
    }

    override fun onInterrupt() {
        Log.d(TAG, "KeyInjectorService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "KeyInjectorService destroyed")
    }

    /**
     * メディアキーコードを送信
     *
     * @param keyCode メディアキーコード（再生/停止、次/前など）
     * @return 成功した場合は true
     */
    fun sendMediaKey(keyCode: Int): Boolean {
        Log.d(TAG, "Sending media key: keyCode=$keyCode")

        return try {
            // KEY_EVENT インテントを送信
            val downIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            }
            val upIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, keyCode))
            }

            sendBroadcast(downIntent)
            Thread.sleep(50)
            sendBroadcast(upIntent)

            Log.d(TAG, "Media key sent successfully: keyCode=$keyCode")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send media key", e)
            false
        }
    }

    /**
     * キーダウンイベントを注入
     *
     * @param keyCode Androidキーコード
     * @return 成功した場合は true
     */
    fun injectKeyDown(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_REWIND -> sendMediaKey(keyCode)
            else -> {
                Log.w(TAG, "Key code not supported (only media keys are supported): keyCode=$keyCode")
                false
            }
        }
    }

    /**
     * キーアップイベントを注入
     *
     * @param keyCode Androidキーコード
     * @return 成功した場合は true
     */
    fun injectKeyUp(keyCode: Int): Boolean {
        // メディアキーではinjectKeyDownで既にアップイベントも送信済み
        return true
    }
}
