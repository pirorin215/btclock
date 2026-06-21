package com.pirorin215.btclockmob.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private val Context.motionDataStore: DataStore<Preferences> by preferencesDataStore(name = "motion_training")

/**
 * モーション学習データの永続化（DataStore + JSON）。
 * 学習サンプル群（ラベル付き特徴量）と、学習済みモデルを保持する。
 * 永続化パターンは DeviceHistoryRepository に準拠。
 */
class MotionTrainingRepository(private val context: Context) {

    companion object {
        private val SAMPLES = stringPreferencesKey("labeled_samples_json")
        private val MODEL = stringPreferencesKey("motion_model_json")
    }

    /** 蓄積済みの学習サンプル一覧 */
    val samples: Flow<List<LabeledFeatures>> = context.motionDataStore.data.map { prefs ->
        prefs[SAMPLES]?.let { JsonUtil.json.decodeFromString<List<LabeledFeatures>>(it) } ?: emptyList()
    }

    /** 学習済みモデル（未学習時は null） */
    val model: Flow<MotionModel?> = context.motionDataStore.data.map { prefs ->
        prefs[MODEL]?.let { JsonUtil.json.decodeFromString<MotionModel>(it) }
    }

    /** 学習サンプルを1件追加 */
    suspend fun addSample(label: String, features: List<Float>) {
        context.motionDataStore.edit { prefs ->
            val current = prefs[SAMPLES]
                ?.let { JsonUtil.json.decodeFromString<List<LabeledFeatures>>(it) }
                ?: emptyList()
            prefs[SAMPLES] = JsonUtil.json.encodeToString(current + LabeledFeatures(label, features))
        }
    }

    suspend fun clearSamples() {
        context.motionDataStore.edit { it.remove(SAMPLES) }
    }

    suspend fun saveModel(model: MotionModel) {
        context.motionDataStore.edit { prefs ->
            prefs[MODEL] = JsonUtil.json.encodeToString(model)
        }
    }

    suspend fun clearModel() {
        context.motionDataStore.edit { it.remove(MODEL) }
    }
}
