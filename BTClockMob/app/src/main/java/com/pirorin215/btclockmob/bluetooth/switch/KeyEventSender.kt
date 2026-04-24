package com.pirorin215.btclockmob.bluetooth.switch

import android.content.Context
import android.util.Log

/**
 * KeyEventSender - HID方式では不要（デバイス側で直接HID送信）
 *
 * 注意:
 * - HID方式ではキーイベント送信はデバイス側で行われる
 * - このクラスはカスタムサービス方式でのみ使用
 * - 現在はHID方式を採用しているため使用しない
 */
object KeyEventSender {
    private const val TAG = "KeyEventSender"

    /**
     * KeyEventSenderの初期化（HID方式では何もしない）
     *
     * @param context アプリケーションコンテキスト
     */
    fun initialize(context: Context) {
        Log.d(TAG, "KeyEventSender initialized (HID mode - no-op)")
    }

    /**
     * HID方式では使用しない
     */
    suspend fun sendKeyDown(keyCode: Int): Boolean {
        Log.w(TAG, "sendKeyDown called but HID mode is enabled - ignoring")
        return false
    }

    /**
     * HID方式では使用しない
     */
    suspend fun sendKeyUp(keyCode: Int): Boolean {
        Log.w(TAG, "sendKeyUp called but HID mode is enabled - ignoring")
        return false
    }
}
