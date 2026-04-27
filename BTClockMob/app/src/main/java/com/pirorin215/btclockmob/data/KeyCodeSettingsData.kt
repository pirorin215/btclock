package com.pirorin215.btclockmob.data

/**
 * スイッチキーコード設定データクラス
 *
 * @param sw1KeyCode スイッチ1のキーコード
 * @param sw2KeyCode スイッチ2のキーコード
 * @param sw3KeyCode スイッチ3のキーコード
 * @param sw4KeyCode スイッチ4のキーコード
 * @param sw5KeyCode スイッチ5のキーコード
 * @param sw6KeyCode スイッチ6のキーコード
 * @param sw7KeyCode スイッチ7のキーコード
 */
data class KeyCodeSettings(
    val sw1KeyCode: Int = DEFAULT_SW1_HID,
    val sw2KeyCode: Int = DEFAULT_SW2_HID,
    val sw3KeyCode: Int = DEFAULT_SW3_HID,
    val sw4KeyCode: Int = DEFAULT_SW4_HID,
    val sw5KeyCode: Int = DEFAULT_SW5_HID,
    val sw6KeyCode: Int = DEFAULT_SW6_HID,
    val sw7KeyCode: Int = DEFAULT_SW7_HID
) {
    companion object {
        // デフォルトのHID Usage ID (Keyboard & Consumer Page)
        // Arduinoファームウェア側のデフォルト設定に合わせる
        const val DEFAULT_SW1_HID = 0x4F    // Right Arrow
        const val DEFAULT_SW2_HID = 0x51    // Down Arrow
        const val DEFAULT_SW3_HID = 0x52    // Up Arrow
        const val DEFAULT_SW4_HID = 0x50    // Left Arrow
        const val DEFAULT_SW5_HID = 0x28    // Enter
        const val DEFAULT_SW6_HID = 0x0224  // Back (Android)
        const val DEFAULT_SW7_HID = 0xCD    // Play/Pause (Consumer Page)
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
            5 -> sw5KeyCode
            6 -> sw6KeyCode
            7 -> sw7KeyCode
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
            5 -> copy(sw5KeyCode = keyCode)
            6 -> copy(sw6KeyCode = keyCode)
            7 -> copy(sw7KeyCode = keyCode)
            else -> this
        }
    }

    /**
     * デバイス送信用のコマンド文字列を生成
     * 入力値をそのまま16進数文字列として7つ並べる
     */
    fun toDeviceCommand(): String {
        return "SET:keys:%04X,%04X,%04X,%04X,%04X,%04X,%04X".format(
            sw1KeyCode, sw2KeyCode, sw3KeyCode, sw4KeyCode, sw5KeyCode, sw6KeyCode, sw7KeyCode
        )
    }
}
