package com.pirorin215.btclockmob.data

data class DeviceSettings(
    val deepSleepDelayMs: String = "",
    val sleepAdj: String = "",
    val batVolMin: String = "",
    val batVolMult: String = "",
    val i2sSampleRate: String = "",
    val recMaxS: String = "",
    val recMinS: String = "",
    val audioGain: String = "",
    val vibraStartupMs: String = "",
    val vibraRecStartMs: String = "",
    val vibraRecStopMs: String = "",
    val vibra: Boolean = false,
    val logAtBoot: Boolean = false,
    val chunkBurstSize: String = "",
    val deepSleepCycleMinutes: String = "",
    val useAdpcm: Boolean = false,
    val devMode: Boolean = false,
) {
    fun toIniString(): String {
        return """
            DEEP_SLEEP_DELAY_MS=$deepSleepDelayMs
            SLEEP_ADJ=$sleepAdj
            BAT_VOL_MIN=$batVolMin
            BAT_VOL_MULT=$batVolMult
            I2S_SAMPLE_RATE=$i2sSampleRate
            REC_MAX_S=$recMaxS
            REC_MIN_S=$recMinS
            AUDIO_GAIN=$audioGain
            VIBRA_STARTUP_MS=$vibraStartupMs
            VIBRA_REC_START_MS=$vibraRecStartMs
            VIBRA_REC_STOP_MS=$vibraRecStopMs
            VIBRA=$vibra
            LOG_AT_BOOT=$logAtBoot
            CHUNK_BURST_SIZE=$chunkBurstSize
            DEEP_SLEEP_CYCLE_MINUTES=$deepSleepCycleMinutes
            USE_ADPCM=$useAdpcm
            DEV_MODE=$devMode
        """.trimIndent().lines().filter { it.contains("=") && it.split("=")[1].isNotEmpty() }.joinToString("\n")
    }

    fun diff(other: DeviceSettings): String {
        val differences = mutableListOf<String>()
        if (deepSleepDelayMs != other.deepSleepDelayMs) differences.add("DEEP_SLEEP_DELAY_MS: '${other.deepSleepDelayMs}' -> '$deepSleepDelayMs'")
        if (sleepAdj != other.sleepAdj) differences.add("SLEEP_ADJ: '${other.sleepAdj}' -> '$sleepAdj'")
        if (batVolMin != other.batVolMin) differences.add("BAT_VOL_MIN: '${other.batVolMin}' -> '$batVolMin'")
        if (batVolMult != other.batVolMult) differences.add("BAT_VOL_MULT: '${other.batVolMult}' -> '$batVolMult'")
        if (i2sSampleRate != other.i2sSampleRate) differences.add("I2S_SAMPLE_RATE: '${other.i2sSampleRate}' -> '$i2sSampleRate'")
        if (recMaxS != other.recMaxS) differences.add("REC_MAX_S: '${other.recMaxS}' -> '$recMaxS'")
        if (recMinS != other.recMinS) differences.add("REC_MIN_S: '${other.recMinS}' -> '$recMinS'")
        if (audioGain != other.audioGain) differences.add("AUDIO_GAIN: '${other.audioGain}' -> '$audioGain'")
        if (vibra != other.vibra) differences.add("VIBRA: '${other.vibra}' -> '$vibra'")
        if (logAtBoot != other.logAtBoot) differences.add("LOG_AT_BOOT: '${other.logAtBoot}' -> '$logAtBoot'")
        if (chunkBurstSize != other.chunkBurstSize) differences.add("CHUNK_BURST_SIZE: '${other.chunkBurstSize}' -> '$chunkBurstSize'")
        if (deepSleepCycleMinutes != other.deepSleepCycleMinutes) differences.add("DEEP_SLEEP_CYCLE_MINUTES: '${other.deepSleepCycleMinutes}' -> '$deepSleepCycleMinutes'")
        if (useAdpcm != other.useAdpcm) differences.add("USE_ADPCM: '${other.useAdpcm}' -> '$useAdpcm'")
        if (devMode != other.devMode) differences.add("DEV_MODE: '${other.devMode}' -> '$devMode'")
        return differences.joinToString("\n")
    }

    companion object {
        fun fromIniString(ini: String): DeviceSettings {
            val properties = ini.lines().mapNotNull { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }.toMap()

            return DeviceSettings(
                deepSleepDelayMs = properties["DEEP_SLEEP_DELAY_MS"] ?: "",
                sleepAdj = properties["SLEEP_ADJ"] ?: "",
                batVolMin = properties["BAT_VOL_MIN"] ?: "",
                batVolMult = properties["BAT_VOL_MULT"] ?: "",
                i2sSampleRate = properties["I2S_SAMPLE_RATE"] ?: "",
                recMaxS = properties["REC_MAX_S"] ?: "",
                recMinS = properties["REC_MIN_S"] ?: "",
                audioGain = properties["AUDIO_GAIN"] ?: "",
                vibraStartupMs = properties["VIBRA_STARTUP_MS"] ?: "",
                vibraRecStartMs = properties["VIBRA_REC_START_MS"] ?: "",
                vibraRecStopMs = properties["VIBRA_REC_STOP_MS"] ?: "",
                vibra = properties["VIBRA"]?.toBoolean() ?: false,
                logAtBoot = properties["LOG_AT_BOOT"]?.toBoolean() ?: false,
                chunkBurstSize = properties["CHUNK_BURST_SIZE"] ?: "",
                deepSleepCycleMinutes = properties["DEEP_SLEEP_CYCLE_MINUTES"] ?: "",
                useAdpcm = properties["USE_ADPCM"]?.toBoolean() ?: false,
                devMode = properties["DEV_MODE"]?.toBoolean() ?: false,
            )
        }
    }
}