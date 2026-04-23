package com.pirorin215.btclockmob.viewModel

import android.content.Context
import com.pirorin215.btclockmob.LocationData
import com.pirorin215.btclockmob.LocationTracker
import com.pirorin215.btclockmob.data.AppSettingsRepository
import com.pirorin215.btclockmob.data.TranscriptionResult
import com.pirorin215.btclockmob.data.TranscriptionResultRepository
import com.pirorin215.btclockmob.viewModel.transcription.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * 文字起こし機能の統合マネージャークラス
 * 6つのサブマネージャーを統合し、既存APIを維持する
 */
class TranscriptionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val appSettingsRepository: AppSettingsRepository,
    private val transcriptionResultRepository: TranscriptionResultRepository,
    private val currentForegroundLocationFlow: StateFlow<LocationData?>,
    private val audioDirNameFlow: StateFlow<String>,
    private val transcriptionCacheLimitFlow: StateFlow<Int>,
    private val logManager: LogManager,
    private val googleTaskTitleLengthFlow: StateFlow<Int>,
    private val googleTasksIntegration: GoogleTasksManager,
    private val locationTracker: LocationTracker
) {
    companion object {
        const val TRANSCRIPTION_CHANNEL_ID = "TranscriptionChannel"
        const val TRANSCRIPTION_NOTIFICATION_ID = 2002
    }

    // サブマネージャーの初期化
    private val stateManager = TranscriptionStateManager(scope)
    private val serviceManager = TranscriptionServiceManager(context, scope, appSettingsRepository, logManager)
    private val notificationManager = TranscriptionNotificationManager(context, appSettingsRepository, logManager)
    private val queueManager = TranscriptionQueueManager(logManager)

    private val processor = TranscriptionProcessor(
        context = context,
        scope = scope,
        appSettingsRepository = appSettingsRepository,
        transcriptionResultRepository = transcriptionResultRepository,
        currentForegroundLocationFlow = currentForegroundLocationFlow,
        audioDirNameFlow = audioDirNameFlow,
        googleTaskTitleLengthFlow = googleTaskTitleLengthFlow,
        googleTasksIntegration = googleTasksIntegration,
        locationTracker = locationTracker,
        logManager = logManager,
        stateManager = stateManager,
        serviceManager = serviceManager,
        notificationManager = notificationManager,
        queueManager = queueManager
    )

    private val resultManager = TranscriptionResultManager(
        context = context,
        scope = scope,
        appSettingsRepository = appSettingsRepository,
        transcriptionResultRepository = transcriptionResultRepository,
        currentForegroundLocationFlow = currentForegroundLocationFlow,
        audioDirNameFlow = audioDirNameFlow,
        transcriptionCacheLimitFlow = transcriptionCacheLimitFlow,
        googleTaskTitleLengthFlow = googleTaskTitleLengthFlow,
        googleTasksIntegration = googleTasksIntegration,
        locationTracker = locationTracker,
        logManager = logManager,
        stateManager = stateManager
    )

    init {
        notificationManager.createNotificationChannel()
        serviceManager.initializeServices()

        audioDirNameFlow
            .onEach {
                logManager.addDebugLog("Audio directory changed")
                resultManager.updateLocalAudioFileCount()
            }
            .launchIn(scope)

        // アプリ起動時に古いキャッシュを整理
        scope.launch {
            cleanupTranscriptionResultsAndAudioFiles()
        }
    }

    // ========== 公開API（既存コードとの互換性維持）==========

    // StateFlowの公開
    val transcriptionState: StateFlow<String> = stateManager.transcriptionState
    val transcriptionResult: StateFlow<String?> = stateManager.transcriptionResult
    val transcriptionCompletedFlow = stateManager.transcriptionCompletedFlow
    val audioFileCount: StateFlow<Int> = stateManager.audioFileCount

    // サービスへのアクセス（テスト等で使用されている可能性）
    var speechToTextService: com.pirorin215.btclockmob.service.SpeechToTextService?
        get() = serviceManager.speechToTextService
        private set(_) {}

    var groqSpeechService: com.pirorin215.btclockmob.service.GroqSpeechService?
        get() = serviceManager.groqSpeechService
        private set(_) {}

    var groqLLMService: com.pirorin215.btclockmob.service.GroqLLMService?
        get() = serviceManager.groqLLMService
        private set(_) {}

    var geminiService: com.pirorin215.btclockmob.service.GeminiRestService?
        get() = serviceManager.geminiService
        private set(_) {}

    /**
     * 保留中の文字起こしを処理
     */
    fun processPendingTranscriptions() {
        processor.processPendingTranscriptions()
    }

    /**
     * 文字起こし結果のクリーンアップ
     */
    suspend fun cleanupTranscriptionResultsAndAudioFiles() {
        resultManager.cleanupTranscriptionResultsAndAudioFiles()
    }

    /**
     * 文字起こしを再実行
     */
    fun retranscribe(result: TranscriptionResult) {
        resultManager.retranscribe(result, processor)
    }

    /**
     * 保留中の文字起こしを追加
     */
    suspend fun addPendingTranscription(fileName: String) {
        resultManager.addPendingTranscription(fileName, queueManager)
    }

    /**
     * 手動文字起こしを追加
     */
    fun addManualTranscription(text: String) {
        resultManager.addManualTranscription(text)
    }

    /**
     * 全文字起こし結果をクリア
     */
    fun clearTranscriptionResults() {
        resultManager.clearTranscriptionResults()
    }

    /**
     * 文字起こし結果を削除
     */
    fun removeTranscriptionResult(result: TranscriptionResult) {
        resultManager.removeTranscriptionResult(result)
    }

    /**
     * 文字起こし結果を更新
     */
    fun updateTranscriptionResult(originalResult: TranscriptionResult, newTranscription: String, newNote: String?) {
        resultManager.updateTranscriptionResult(originalResult, newTranscription, newNote)
    }

    /**
     * 複数の文字起こし結果を削除
     */
    fun removeTranscriptionResults(fileNames: Set<String>, clearSelectionCallback: () -> Unit) {
        resultManager.removeTranscriptionResults(fileNames, clearSelectionCallback)
    }

    /**
     * ローカルオーディオファイル数を更新
     */
    fun updateLocalAudioFileCount() {
        resultManager.updateLocalAudioFileCount()
    }

    /**
     * 文字起こし状態をリセット
     */
    fun resetTranscriptionState() {
        stateManager.resetState()
    }

    /**
     * クリーンアップ
     */
    fun onCleared() {
        // Placeholder for future cleanup if needed
    }
}
