package com.pirorin215.btclockmob.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pirorin215.btclockmob.data.BleRepository
import com.pirorin215.btclockmob.data.ConnectionState
import com.pirorin215.btclockmob.data.LabeledFeatures
import com.pirorin215.btclockmob.data.MotionFeatures
import com.pirorin215.btclockmob.data.MotionModel
import com.pirorin215.btclockmob.data.MotionTrainingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * モーションパターン学習 ViewModel (Phase 1)。
 * 学習サンプルの蓄積状況・学習済みモデルの保持、学習・マイコン送信を行う。
 */
class MotionLearningViewModel(
    private val repository: MotionTrainingRepository,
    private val bleRepository: BleRepository
) : ViewModel() {

    data class LabelCount(val label: String, val count: Int)

    sealed class SendState {
        object Idle : SendState()
        object Sending : SendState()
        data class Success(val patterns: Int) : SendState()
        data class Error(val message: String) : SendState()
    }

    /** 蓄積済みの学習サンプル一覧 */
    val samples: StateFlow<List<LabeledFeatures>> =
        repository.samples.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 学習済みモデル（未学習時は null） */
    val model: StateFlow<MotionModel?> =
        repository.model.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val connectionState: StateFlow<ConnectionState> = bleRepository.connectionState

    /** ラベル別のサンプル数 */
    val labelCounts: StateFlow<List<LabelCount>> =
        repository.samples
            .map { list ->
                list.groupBy { it.label }
                    .map { (label, items) -> LabelCount(label, items.size) }
                    .sortedBy { it.label }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _sendState = MutableStateFlow<SendState>(SendState.Idle)
    val sendState: StateFlow<SendState> = _sendState.asStateFlow()

    /** 学習データに1件追加（採取画面から呼ばれる） */
    fun addSample(label: String, features: FloatArray?) {
        val f = features ?: return
        if (f.size != MotionFeatures.DIM) return
        viewModelScope.launch { repository.addSample(label, f.toList()) }
    }

    /** 学習してマイコンへ送信（学習は一瞬・決定的論理なので分離の意味なし・統合） */
    fun trainAndSend() {
        if (samples.value.isEmpty()) {
            _sendState.value = SendState.Error("学習データがありません。採取してください。")
            return
        }
        if (connectionState.value !is ConnectionState.Connected) {
            _sendState.value = SendState.Error("デバイスに接続されていません")
            return
        }
        _sendState.value = SendState.Sending
        viewModelScope.launch {
            val trained = MotionModel.train(samples.value)
            if (trained == null) {
                _sendState.value = SendState.Error("学習に失敗しました")
                return@launch
            }
            repository.saveModel(trained)
            val ok = bleRepository.sendMotionModel(trained)
            _sendState.value =
                if (ok) SendState.Success(trained.patternCount)
                else SendState.Error("送信に失敗しました")
        }
    }

    /** 学習データを全削除 */
    fun clearSamples() {
        viewModelScope.launch { repository.clearSamples() }
    }

    /** 指定ラベルの学習サンプルを削除し、モデルを未学習状態に戻す（再学習を促す・古い重心の誤送信防止） */
    fun deleteLabel(label: String) {
        viewModelScope.launch {
            repository.deleteSamplesByLabel(label)
            repository.clearModel()
        }
    }

    /** 学習サンプルと学習済みモデルを全削除（完全リセット） */
    fun clearAll() {
        viewModelScope.launch {
            repository.clearSamples()
            repository.clearModel()
        }
    }

    fun resetSendState() {
        _sendState.value = SendState.Idle
    }
}
