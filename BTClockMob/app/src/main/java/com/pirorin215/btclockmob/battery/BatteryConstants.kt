package com.pirorin215.btclockmob.battery

/**
 * 電池関連の定数
 */
object BatteryConstants {
    /** 電池切れとみなす電圧 (V) */
    const val BATTERY_CUTOFF_VOLTAGE = 3.60f

    /** 充電検出の閾値 (V) - この値以上の上昇を充電とみなす */
    const val CHARGE_DETECTION_THRESHOLD = 0.15f

    /** フル充電とみなす電圧 (V) - この電圧以上を「本当の充電」とみなす */
    const val FULL_CHARGE_VOLTAGE = 4.0f

    /** 充電イベントのマージ時間 (ms) - この時間以内の充電をまとめる */
    const val CHARGE_MERGE_TIME_MS = 6 * 60 * 60 * 1000L // 6時間

    /** 最小サイクル長 (時間) */
    const val MIN_CYCLE_DURATION_HOURS = 10
}
