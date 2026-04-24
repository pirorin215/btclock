package com.pirorin215.btclockmob.constants

object BleTimeoutConstants {
    // 各コマンドのタイムアウト
    const val TIME_SYNC_TIMEOUT_MS = 5000L
    const val DEVICE_INFO_TIMEOUT_MS = 15000L

    // デバイス情報リトライ間隔
    const val DEVICE_INFO_RETRY_DELAY_MS = 500L
}
