package com.pirorin215.btclockmob.constants

object TimeConstants {
    // 時刻同期（BikeClockは1分に1回同期）
    const val TIME_SYNC_INTERVAL_MS = 60000L // 1分 (1 * 60 * 1000)

    // 位置情報更新
    const val LOCATION_UPDATE_INTERVAL_MS = 30000L // 30秒

    // BLEリトライ
    const val BLE_RETRY_DELAY_MS = 5000L // 5秒
    const val BLE_MAX_RETRIES = 6

    // スキャン/接続
    const val SERVICE_DISCOVERY_DELAY_MS = 600L
    const val RECONNECT_DELAY_MS = 500L
    const val FORCE_RECONNECT_DELAY_MS = 500L

    // サービス監視
    const val SERVICE_CHECK_INTERVAL_MS = 60000L // 1分
}
