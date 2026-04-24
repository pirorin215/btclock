package com.pirorin215.btclockmob.bluetooth.switch

import android.util.Log
import android.view.KeyEvent

/**
 * スイッチの押下状態を管理するクラス
 *
 * 機能:
 * - 現在押下中のキーを追跡
 * - 重複したキーダウン/アップイベントを防止
 * - デバッグ用の状態ログ出力
 *
 * 使用例:
 * - PRESS通知時: isKeyPressed() で確認して、未押下ならキーダウン送信
 * - RELEASE通知時: isKeyPressed() で確認して、押下中ならキーアップ送信
 */
object SwitchStateManager {
    private const val TAG = "SwitchStateManager"

    // 押下中のキーコードを追跡
    private val pressedKeys = mutableSetOf<Int>()

    /**
     * 指定されたキーコードが押下中かどうかを確認
     *
     * @param keyCode Androidキーコード
     * @return 押下中の場合は true
     */
    fun isKeyPressed(keyCode: Int): Boolean {
        return pressedKeys.contains(keyCode)
    }

    /**
     * キーを押下状態に設定
     *
     * @param keyCode Androidキーコード
     */
    fun setKeyPressed(keyCode: Int) {
        pressedKeys.add(keyCode)
        logState()
    }

    /**
     * キーを解放状態に設定
     *
     * @param keyCode Androidキーコード
     */
    fun setKeyReleased(keyCode: Int) {
        pressedKeys.remove(keyCode)
        logState()
    }

    /**
     * 全てのキーを解放状態にする（初期化用）
     */
    fun clearAll() {
        pressedKeys.clear()
        Log.d(TAG, "All keys cleared")
    }

    /**
     * 押下中のキー数を取得
     *
     * @return 押下中のキーの数
     */
    fun getPressedKeyCount(): Int {
        return pressedKeys.size
    }

    /**
     * デバッグ用の状態ログ出力
     */
    private fun logState() {
        if (pressedKeys.isNotEmpty()) {
            Log.d(TAG, "Pressed keys: ${pressedKeys.joinToString()}")
        }
    }
}
