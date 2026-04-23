package com.pirorin215.btclockmob.constants

object FileTransferConstants {
    // 削除リトライ
    const val MAX_DELETE_RETRIES = 3
    const val DELETE_RETRY_DELAY_MS = 1000L

    // パケットタイムアウト
    const val PACKET_TIMEOUT_MS = 30000L

    // チャンク
    const val DEFAULT_CHUNK_BURST_SIZE = 8
}
