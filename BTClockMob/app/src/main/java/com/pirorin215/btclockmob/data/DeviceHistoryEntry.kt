package com.pirorin215.btclockmob.data

import kotlinx.serialization.Serializable

@Serializable
data class DeviceHistoryEntry(
    val timestamp: Long,
    val latitude: Double?,
    val longitude: Double?,
    val isDisconnection: Boolean = false,
    val isPeriodic: Boolean = false,
    val address: String? = null
)
