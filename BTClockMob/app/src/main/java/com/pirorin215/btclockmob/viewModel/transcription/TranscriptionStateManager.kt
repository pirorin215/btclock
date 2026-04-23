package com.pirorin215.btclockmob.viewModel.transcription

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 文字起こしの状態管理を担当するマネージャークラス
 * StateFlowの公開と状態更新を担当
 */
class TranscriptionStateManager(
    private val scope: CoroutineScope
) {
    // Transcription state
    private val _transcriptionState = MutableStateFlow("Idle")
    val transcriptionState: StateFlow<String> = _transcriptionState.asStateFlow()

    // Latest transcription result
    private val _transcriptionResult = MutableStateFlow<String?>(null)
    val transcriptionResult: StateFlow<String?> = _transcriptionResult.asStateFlow()

    // Transcription completion flow for file completion notification
    private val _transcriptionCompletedFlow = MutableSharedFlow<String>()
    val transcriptionCompletedFlow: SharedFlow<String> = _transcriptionCompletedFlow.asSharedFlow()

    // Audio file count
    private val _audioFileCount = MutableStateFlow(0)
    val audioFileCount: StateFlow<Int> = _audioFileCount.asStateFlow()

    /**
     * 文字起こし状態を更新
     */
    fun updateTranscriptionState(state: String) {
        _transcriptionState.value = state
    }

    /**
     * 文字起こし結果を更新
     */
    fun updateTranscriptionResult(result: String?) {
        _transcriptionResult.value = result
    }

    /**
     * ファイル処理完了を通知
     */
    fun notifyCompletion(fileName: String) {
        scope.launch {
            _transcriptionCompletedFlow.emit(fileName)
        }
    }

    /**
     * オーディオファイル数を更新
     */
    fun updateAudioFileCount(count: Int) {
        _audioFileCount.value = count
    }

    /**
     * 状態をリセット
     */
    fun resetState() {
        _transcriptionState.value = "Idle"
        _transcriptionResult.value = null
    }
}
