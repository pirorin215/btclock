package com.pirorin215.btclockmob.viewModel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pirorin215.btclockmob.bluetooth.switch.KeyEventSender
import com.pirorin215.btclockmob.data.KeyCodeSettings
import com.pirorin215.btclockmob.data.KeyCodeSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * キーコード設定画面用ViewModel
 *
 * 機能:
 * - キーコード設定の管理
 * - キーテスト送信
 */
class KeyCodeSettingsViewModel(
    application: Application,
    private val repository: KeyCodeSettingsRepository,
    private val bleOrchestrator: BleOrchestrator
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "KeyCodeSettingsViewModel"
    }

    // キーコード設定
    private val _keyCodeSettings = MutableStateFlow<KeyCodeSettings>(KeyCodeSettings())
    val keyCodeSettings: StateFlow<KeyCodeSettings> = _keyCodeSettings.asStateFlow()

    // テスト送信結果メッセージ
    private val _testMessage = MutableStateFlow<String?>(null)
    val testMessage: StateFlow<String?> = _testMessage.asStateFlow()

    init {
        loadSettings()
    }

    /**
     * 設定を読み込み
     */
    private fun loadSettings() {
        viewModelScope.launch {
            repository.getKeyCodeSettings().collect { settings ->
                _keyCodeSettings.value = settings
                Log.d(TAG, "Settings loaded: $settings")
            }
        }
    }

    /**
     * スイッチのキーコードを更新
     *
     * @param switchNum スイッチ番号 (1-4)
     * @param keyCode 設定するキーコード
     */
    fun updateKeyCode(switchNum: Int, keyCode: Int) {
        viewModelScope.launch {
            val currentSettings = _keyCodeSettings.value
            val newSettings = currentSettings.setKeyCode(switchNum, keyCode)
            repository.saveKeyCodeSettings(newSettings)
            Log.d(TAG, "Key code updated locally: Switch $switchNum -> $keyCode")
        }
    }

    /**
     * デバイスに設定を送信
     */
    fun saveToDevice() {
        viewModelScope.launch {
            val currentSettings = _keyCodeSettings.value
            val command = currentSettings.toDeviceCommand()
            Log.d(TAG, "Sending key config to device: $command")
            bleOrchestrator.sendCommand(command)
            _testMessage.value = "デバイスに設定を送信しました"
        }
    }

    /**
     * キーテスト送信（HID方式では使用しない）
     *
     * 注意: HID方式ではデバイス側で直接キーが送信されるため、
     * アプリからのテスト送信は不要です。
     */
    fun sendTestKeyDown(keyCode: Int) {
        _testMessage.value = "HIDモードではアプリからのテスト送信は不要です。\nスイッチを直接押してください。"
        Log.w(TAG, "sendTestKeyDown called but HID mode is enabled")
    }

    /**
     * キーテスト送信（HID方式では使用しない）
     */
    fun sendTestKeyUp(keyCode: Int) {
        _testMessage.value = "HIDモードではアプリからのテスト送信は不要です。\nスイッチを直接押してください。"
        Log.w(TAG, "sendTestKeyUp called but HID mode is enabled")
    }

    /**
     * キーテスト送信（HID方式では使用しない）
     */
    fun sendTestKeyPress(keyCode: Int) {
        _testMessage.value = "HIDモードではアプリからのテスト送信は不要です。\nスイッチを直接押してください。"
        Log.w(TAG, "sendTestKeyPress called but HID mode is enabled")
    }

    /**
     * テストメッセージをクリア
     */
    fun clearTestMessage() {
        _testMessage.value = null
    }

    /**
     * 短い遅延
     */
    private suspend fun delayMs(milliseconds: Long) {
        kotlinx.coroutines.delay(milliseconds)
    }
}
