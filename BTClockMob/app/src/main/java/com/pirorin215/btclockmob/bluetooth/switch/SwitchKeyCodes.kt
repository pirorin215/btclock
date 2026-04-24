package com.pirorin215.btclockmob.bluetooth.switch

import android.view.KeyEvent

/**
 * スイッチ番号に対応するAndroidキーコードの定義
 *
 * 各スイッチの機能割り当て:
 * - SW1: 左矢印（YouTube等で戻る）
 * - SW2: 右矢印（YouTube等で進む）
 * - SW3: 再生/一時停止
 * - SW4: 次のトラック（または任意の機能）
 */
object SwitchKeyCodes {
    const val SW1_KEYCODE = KeyEvent.KEYCODE_DPAD_LEFT          // 左矢印
    const val SW2_KEYCODE = KeyEvent.KEYCODE_DPAD_RIGHT         // 右矢印
    const val SW3_KEYCODE = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE  // 再生/一時停止
    const val SW4_KEYCODE = KeyEvent.KEYCODE_MEDIA_NEXT        // 次のトラック

    /**
     * スイッチ番号からキーコードを取得
     *
     * @param switchNum スイッチ番号 (1-4)
     * @return 対応するキーコード。無効な番号の場合は KEYCODE_UNKNOWN
     */
    fun getKeyCode(switchNum: Int): Int {
        return when (switchNum) {
            1 -> SW1_KEYCODE
            2 -> SW2_KEYCODE
            3 -> SW3_KEYCODE
            4 -> SW4_KEYCODE
            else -> KeyEvent.KEYCODE_UNKNOWN
        }
    }

    /**
     * キーコードからスイッチ番号を取得（逆引き）
     *
     * @param keyCode Androidキーコード
     * @return スイッチ番号 (1-4)。該当しない場合は null
     */
    fun getSwitchNum(keyCode: Int): Int? {
        return when (keyCode) {
            SW1_KEYCODE -> 1
            SW2_KEYCODE -> 2
            SW3_KEYCODE -> 3
            SW4_KEYCODE -> 4
            else -> null
        }
    }
}
