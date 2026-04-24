package com.pirorin215.btclockmob.data

/**
 * スイッチキーコード設定データクラス
 *
 * @param sw1KeyCode スイッチ1のキーコード
 * @param sw2KeyCode スイッチ2のキーコード
 * @param sw3KeyCode スイッチ3のキーコード
 * @param sw4KeyCode スイッチ4のキーコード
 */
data class KeyCodeSettings(
    val sw1KeyCode: Int = DEFAULT_SW1_HID,
    val sw2KeyCode: Int = DEFAULT_SW2_HID,
    val sw3KeyCode: Int = DEFAULT_SW3_HID,
    val sw4KeyCode: Int = DEFAULT_SW4_HID
) {
    companion object {
        // デフォルトのHID Usage ID (Keyboard & Consumer Page)
        const val DEFAULT_SW1_HID = 0xBC   // Media Rewind
        const val DEFAULT_SW2_HID = 0xB3   // Media Fast Forward
        const val DEFAULT_SW3_HID = 0xCD   // Media Play/Pause
        const val DEFAULT_SW4_HID = 0xB5   // Media Next Track
    }

    /**
     * スイッチ番号からキーコードを取得
     */
    fun getKeyCode(switchNum: Int): Int {
        return when (switchNum) {
            1 -> sw1KeyCode
            2 -> sw2KeyCode
            3 -> sw3KeyCode
            4 -> sw4KeyCode
            else -> -1
        }
    }

    /**
     * スイッチ番号のキーコードを設定
     */
    fun setKeyCode(switchNum: Int, keyCode: Int): KeyCodeSettings {
        return when (switchNum) {
            1 -> copy(sw1KeyCode = keyCode)
            2 -> copy(sw2KeyCode = keyCode)
            3 -> copy(sw3KeyCode = keyCode)
            4 -> copy(sw4KeyCode = keyCode)
            else -> this
        }
    }

    /**
     * デバイス送信用のコマンド文字列を生成
     * 入力値をそのまま16進数文字列として4つ並べる
     */
    fun toDeviceCommand(): String {
        return "SET:keys:%04X,%04X,%04X,%04X".format(sw1KeyCode, sw2KeyCode, sw3KeyCode, sw4KeyCode)
    }
}
