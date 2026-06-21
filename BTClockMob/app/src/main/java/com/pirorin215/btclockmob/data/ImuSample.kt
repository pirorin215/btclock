package com.pirorin215.btclockmob.data

/** 換算済みIMUサンプル（加速度=g, ジャイロ=deg/s）。BMI160 ±2g/±250dps 換算後。 */
data class ImuSample(
    val ax: Float, val ay: Float, val az: Float,
    val gx: Float, val gy: Float, val gz: Float
)
