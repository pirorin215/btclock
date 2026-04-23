package com.pirorin215.btclockmob.viewModel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pirorin215.btclockmob.data.FileEntry
import com.pirorin215.btclockmob.data.TranscriptionResult
import com.pirorin215.btclockmob.data.FileUtil
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TranscriptionViewModel(
    private val application: Application,
    private val transcriptionManager: TranscriptionManager,
    private val transcriptionResultRepository: com.pirorin215.btclockmob.data.TranscriptionResultRepository,
    private val appSettingsRepository: com.pirorin215.btclockmob.data.AppSettingsRepository,
    private val logManager: LogManager,
    private val bleSelectionManager: BleSelectionManager
) : ViewModel() {

    // --- UI State Flows ---
    val transcriptionFontSize: StateFlow<Int> = appSettingsRepository.getFlow(com.pirorin215.btclockmob.data.Settings.TRANSCRIPTION_FONT_SIZE)
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), 14)

    val transcriptionResults: StateFlow<List<TranscriptionResult>> = transcriptionResultRepository.transcriptionResultsFlow
        .map { list -> list.filter { !it.isDeletedLocally } }
        .map { list -> list.sortedByDescending { FileUtil.getTimestampFromFileName(it.fileName) } }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    val transcriptionCount: StateFlow<Int> = transcriptionResults
        .map { it.size }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), 0)

    // --- State exposed from Transcription Manager ---
    val audioFileCount = transcriptionManager.audioFileCount
    val transcriptionState = transcriptionManager.transcriptionState
    val transcriptionResult = transcriptionManager.transcriptionResult

    // --- Audio Player Manager (scoped to ViewModel) ---
    private val audioPlayerManager: AudioPlayerManager by lazy {
        AudioPlayerManager(application)
    }
    val currentlyPlayingFile: StateFlow<String?> = audioPlayerManager.currentlyPlayingFile

    // --- Transcription Operations ---
    fun resetTranscriptionState() = transcriptionManager.resetTranscriptionState()

    fun playAudioFile(transcriptionResult: TranscriptionResult) {
        viewModelScope.launch {
            val filePath = FileUtil.getAudioFilePath(application, transcriptionResult.fileName)
            if (filePath != null) {
                audioPlayerManager.play(filePath) {
                    viewModelScope.launch {
                        logManager.addLog("Playback naturally completed.")
                        stopAudioFile()
                    }
                }
                logManager.addLog("Audio playback requested for: ${transcriptionResult.fileName}")
            } else {
                logManager.addLog("Error: Audio file not found for playback: ${transcriptionResult.fileName}")
            }
        }
    }

    fun stopAudioFile() {
        audioPlayerManager.stop()
        audioPlayerManager.clearPlayingState()
        logManager.addLog("Audio playback stopped.")
    }

    fun clearTranscriptionResults() = transcriptionManager.clearTranscriptionResults()

    fun removeTranscriptionResult(result: TranscriptionResult) =
        transcriptionManager.removeTranscriptionResult(result)

    fun updateTranscriptionResult(
        originalResult: TranscriptionResult,
        newTranscription: String,
        newNote: String?
    ) = transcriptionManager.updateTranscriptionResult(
        originalResult,
        newTranscription,
        newNote
    )

    fun removeTranscriptionResults(fileNames: Set<String>) {
        transcriptionManager.removeTranscriptionResults(fileNames) {
            bleSelectionManager.clearSelection()
        }
    }

    fun retranscribe(result: TranscriptionResult) = transcriptionManager.retranscribe(result)

    fun addManualTranscription(text: String) = transcriptionManager.addManualTranscription(text)

    fun clearLogs() = logManager.clearLogs()

    override fun onCleared() {
        super.onCleared()
        audioPlayerManager.release()
    }
}
