package com.pirorin215.btclockmob.viewModel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pirorin215.btclockmob.data.AppSettingsRepository
import com.pirorin215.btclockmob.data.Settings
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import com.pirorin215.btclockmob.data.ThemeMode

class AppSettingsViewModel(
    private val appSettingsRepository: AppSettingsRepository,
    private val application: Application
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = appSettingsRepository.getFlow(Settings.THEME_MODE)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ThemeMode.SYSTEM // Default to SYSTEM
        )

    val autoStartOnBoot: StateFlow<Boolean> = appSettingsRepository.getFlow(Settings.AUTO_START_ON_BOOT)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val historyIntervalMin: StateFlow<Int> = appSettingsRepository.getFlow(Settings.HISTORY_INTERVAL_MIN)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 15
        )

    val historyDistanceM: StateFlow<Int> = appSettingsRepository.getFlow(Settings.HISTORY_DISTANCE_M)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 1000
        )

    val routeCenterOffset: StateFlow<Float> = appSettingsRepository.getFlow(Settings.ROUTE_CENTER_OFFSET)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0f
        )

    val routeLabelFontSize: StateFlow<Float> = appSettingsRepository.getFlow(Settings.ROUTE_LABEL_FONT_SIZE)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 18f
        )

    /** スマホ通知のBikeClock転送 ON/OFF（Phase 11） */
    val notificationForwardingEnabled: StateFlow<Boolean> =
        appSettingsRepository.getFlow(Settings.NOTIFICATION_FORWARDING_ENABLED)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = true
            )

    val notificationMaxChars: StateFlow<Int> =
        appSettingsRepository.getFlow(Settings.NOTIFICATION_MAX_CHARS)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = Settings.DEFAULT_NOTIFICATION_MAX_CHARS
            )

    /** 乗降ログの diary-loc 書き出し ON/OFF */
    val rideLogEnabled: StateFlow<Boolean> = appSettingsRepository.getFlow(Settings.RIDE_LOG_ENABLED)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    /** 乗降ログの書き出し先 SAF ツリー URI */
    val rideLogTreeUri: StateFlow<String> = appSettingsRepository.getFlow(Settings.RIDE_LOG_TREE_URI)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    // --- RSSI リンク品質管理（自転車レーダー gardia_radar と同一の仕組み）---

    /** 接続中のRSSI自動切断 ON/OFF */
    val rssiDisconnectEnabled: StateFlow<Boolean> = appSettingsRepository.getFlow(Settings.RSSI_DISCONNECT_ENABLED)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    /** 接続時のRSSIしきい値（スキャン時の判定・これ未満は接続しない） */
    val rssiConnectThreshold: StateFlow<Int> = appSettingsRepository.getFlow(Settings.RSSI_CONNECT_THRESHOLD)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = -80
        )

    /** 切断時のRSSIしきい値（接続後の監視・これ未満で切断カウントを増やす） */
    val rssiDisconnectThreshold: StateFlow<Int> = appSettingsRepository.getFlow(Settings.RSSI_DISCONNECT_THRESHOLD)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = -90
        )

    /** 切断判定回数（しきい値未満の連続回数・5秒ごとにチェック） */
    val rssiDisconnectCount: StateFlow<Int> = appSettingsRepository.getFlow(Settings.RSSI_DISCONNECT_COUNT)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 3
        )

    fun saveThemeMode(themeMode: ThemeMode) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.THEME_MODE, themeMode)
        }
    }

    fun saveAutoStartOnBoot(enable: Boolean) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.AUTO_START_ON_BOOT, enable)
        }
    }

    fun saveHistoryIntervalMin(minutes: Int) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.HISTORY_INTERVAL_MIN, minutes)
        }
    }

    fun saveHistoryDistanceM(meters: Int) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.HISTORY_DISTANCE_M, meters)
        }
    }

    fun saveRouteCenterOffset(offset: Float) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.ROUTE_CENTER_OFFSET, offset)
        }
    }

    fun saveRouteLabelFontSize(size: Float) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.ROUTE_LABEL_FONT_SIZE, size)
        }
    }

    fun saveNotificationForwardingEnabled(enable: Boolean) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.NOTIFICATION_FORWARDING_ENABLED, enable)
        }
    }

    fun saveNotificationMaxChars(maxChars: Int) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.NOTIFICATION_MAX_CHARS, maxChars)
        }
    }

    fun saveRideLogEnabled(enable: Boolean) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.RIDE_LOG_ENABLED, enable)
        }
    }

    fun saveRideLogTreeUri(uri: String) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.RIDE_LOG_TREE_URI, uri)
        }
    }

    fun saveRssiDisconnectEnabled(enable: Boolean) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.RSSI_DISCONNECT_ENABLED, enable)
        }
    }

    fun saveRssiConnectThreshold(threshold: Int) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.RSSI_CONNECT_THRESHOLD, threshold)
        }
    }

    fun saveRssiDisconnectThreshold(threshold: Int) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.RSSI_DISCONNECT_THRESHOLD, threshold)
        }
    }

    fun saveRssiDisconnectCount(count: Int) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.RSSI_DISCONNECT_COUNT, count)
        }
    }

    /** 乗降ログ: デバイス履歴(位置付き)を過去分として一括書き出す */
    suspend fun exportRideHistory(): Int {
        val repository = com.pirorin215.btclockmob.data.DeviceHistoryRepository(application)
        val entries = repository.deviceHistoryFlow.first()
        val exporter = com.pirorin215.btclockmob.data.RideLogExporter(application, appSettingsRepository)
        return exporter.exportHistory(entries)
    }
}
