package com.pirorin215.btclockmob.data

import kotlinx.serialization.Serializable

@Serializable
data class DeviceHistoryEntry(
    val timestamp: Long,
    val latitude: Double?,
    val longitude: Double?,
    val batteryLevel: Float?,
    val batteryVoltage: Float?,
    val isInterpolated: Boolean = false
)
