package com.pirorin215.btclockmob.viewModel

import android.util.Log
import com.pirorin215.btclockmob.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LogLevel {
    DEBUG,   // Detailed logs for debugging
    INFO,    // Important state changes
    ERROR    // Errors only
}

class LogManager {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    // Production uses INFO, Debug builds use DEBUG
    private val currentLogLevel = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.INFO

    fun addLog(message: String, level: LogLevel = LogLevel.INFO) {
        // Only log if the level is at or above the current log level
        if (shouldLog(level)) {
            Log.d("AppLog", message)
            _logs.value = (_logs.value + message).takeLast(100)
        }
    }

    fun addDebugLog(message: String) {
        addLog(message, LogLevel.DEBUG)
    }

    fun addErrorLog(message: String) {
        addLog(message, LogLevel.ERROR)
    }

    private fun shouldLog(level: LogLevel): Boolean {
        return when (currentLogLevel) {
            LogLevel.DEBUG -> true // Log everything in debug mode
            LogLevel.INFO -> level != LogLevel.DEBUG // Skip debug logs
            LogLevel.ERROR -> level == LogLevel.ERROR // Only errors
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
