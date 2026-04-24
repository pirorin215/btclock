package com.pirorin215.btclockmob.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// DataStoreのインスタンスをContextの拡張プロパティとして定義
private val Context.keyCodeDataStore: DataStore<Preferences> by preferencesDataStore(name = "keycode_settings")

/**
 * キーコード設定リポジトリ
 *
 * 機能:
 * - 4つのスイッチのキーコード設定を保存・読み込み
 * - DataStoreを使用した永続化
 */
class KeyCodeSettingsRepository(private val context: Context) {

    companion object {
        private val SW1_KEYCODE = intPreferencesKey("sw1_keycode")
        private val SW2_KEYCODE = intPreferencesKey("sw2_keycode")
        private val SW3_KEYCODE = intPreferencesKey("sw3_keycode")
        private val SW4_KEYCODE = intPreferencesKey("sw4_keycode")
    }

    /**
     * キーコード設定のFlowを取得
     *
     * @return KeyCodeSettingsのFlow
     */
    fun getKeyCodeSettings(): Flow<KeyCodeSettings> {
        return context.keyCodeDataStore.data.map { preferences ->
            KeyCodeSettings(
                sw1KeyCode = preferences[SW1_KEYCODE] ?: KeyCodeSettings.DEFAULT_SW1_HID,
                sw2KeyCode = preferences[SW2_KEYCODE] ?: KeyCodeSettings.DEFAULT_SW2_HID,
                sw3KeyCode = preferences[SW3_KEYCODE] ?: KeyCodeSettings.DEFAULT_SW3_HID,
                sw4KeyCode = preferences[SW4_KEYCODE] ?: KeyCodeSettings.DEFAULT_SW4_HID
            )
        }
    }

    /**
     * キーコード設定を保存
     *
     * @param settings 保存する設定
     */
    suspend fun saveKeyCodeSettings(settings: KeyCodeSettings) {
        context.keyCodeDataStore.edit { preferences ->
            preferences[SW1_KEYCODE] = settings.sw1KeyCode
            preferences[SW2_KEYCODE] = settings.sw2KeyCode
            preferences[SW3_KEYCODE] = settings.sw3KeyCode
            preferences[SW4_KEYCODE] = settings.sw4KeyCode
        }
    }

    /**
     * 特定のスイッチのキーコードを更新
     *
     * @param switchNum スイッチ番号 (1-4)
     * @param keyCode 設定するキーコード
     */
    suspend fun updateKeyCode(switchNum: Int, keyCode: Int) {
        context.keyCodeDataStore.edit { preferences ->
            when (switchNum) {
                1 -> preferences[SW1_KEYCODE] = keyCode
                2 -> preferences[SW2_KEYCODE] = keyCode
                3 -> preferences[SW3_KEYCODE] = keyCode
                4 -> preferences[SW4_KEYCODE] = keyCode
            }
        }
    }
}
