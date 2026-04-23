package com.pirorin215.btclockmob.constants

object BleTimeoutConstants {
    // 各コマンドのタイムアウト
    const val TIME_SYNC_TIMEOUT_MS = 5000L
    const val DEVICE_INFO_TIMEOUT_MS = 15000L
    const val FILE_LIST_TIMEOUT_MS = 15000L
    const val SETTINGS_GET_TIMEOUT_MS = 15000L

    // 設定送信後の遅延
    const val SETTINGS_SEND_DELAY_MS = 500L

    // デバイス情報リトライ間隔
    const val DEVICE_INFO_RETRY_DELAY_MS = 500L
}
