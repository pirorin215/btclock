package com.pirorin215.btclockmob.data

import kotlinx.serialization.Serializable

/**
 * バッテリー電圧の時系列ログエントリ（cycleclock の GET:battery で取得）
 *
 * @param timestamp 測定時刻（Unixミリ秒）
 * @param millivolts 電池電圧（ミリボルト）
 */
@Serializable
data class BatteryLogEntry(
    val timestamp: Long,
    val millivolts: Int
)
