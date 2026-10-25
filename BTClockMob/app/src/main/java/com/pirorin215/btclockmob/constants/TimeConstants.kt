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

    // 送信シリアライズ（Phase 11: Write Request 完了待ちのタイムアウト）
    const val SERIAL_WRITE_TIMEOUT_MS = 2000L // 2秒

    // 死リンク検知: 時刻同期の連続失敗この回数でGATTリンク切断と判定する
    // （範囲外切断では onConnectionStateChange が飛ばされ Connected のまま
    //   ゾンビ化するため・2回=約2分）
    const val SYNC_FAIL_LIMIT = 2

    // RSSI リンク品質管理: 接続中のRSSI読み取り間隔（自転車レーダー gardia_radar と同じ5秒）
    const val RSSI_READ_INTERVAL_MS = 5000L
}
