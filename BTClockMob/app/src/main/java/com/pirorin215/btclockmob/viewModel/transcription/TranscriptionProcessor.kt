package com.pirorin215.btclockmob.viewModel.transcription

import android.content.Context
import com.pirorin215.btclockmob.LocationData
import com.pirorin215.btclockmob.LocationTracker
import com.pirorin215.btclockmob.data.AppSettingsRepository
import com.pirorin215.btclockmob.data.Settings
import com.pirorin215.btclockmob.data.TranscriptionProvider
import com.pirorin215.btclockmob.data.AIProvider
import com.pirorin215.btclockmob.data.TranscriptionResult
import com.pirorin215.btclockmob.data.TranscriptionResultRepository
import com.pirorin215.btclockmob.data.FileUtil
import com.pirorin215.btclockmob.viewModel.LogManager
import com.pirorin215.btclockmob.viewModel.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * 文字起こし処理の実行を担当するマネージャークラス
 * doTranscription, processPendingTranscriptionsを担当
 */
class TranscriptionProcessor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val appSettingsRepository: AppSettingsRepository,
    private val transcriptionResultRepository: TranscriptionResultRepository,
    private val currentForegroundLocationFlow: StateFlow<LocationData?>,
    private val audioDirNameFlow: StateFlow<String>,
    private val googleTaskTitleLengthFlow: StateFlow<Int>,
    private val googleTasksIntegration: com.pirorin215.btclockmob.viewModel.GoogleTasksManager,
    private val locationTracker: LocationTracker,
    private val logManager: LogManager,
    private val stateManager: TranscriptionStateManager,
    private val serviceManager: TranscriptionServiceManager,
    private val notificationManager: TranscriptionNotificationManager,
    private val queueManager: TranscriptionQueueManager
) {
    /**
     * Mutex for transcription processing
     *
     * USAGE RULES:
     * - Protects sequential transcription processing
     * - Prevents concurrent transcription operations
     * - Independent from BLE operations (no deadlock risk with bleMutex)
     * - Only used in processPendingTranscriptions()
     */
    private val transcriptionMutex = Mutex()

    /**
     * 単一の文字起こしを実行
     */
    suspend fun doTranscription(resultToProcess: TranscriptionResult) {
        logManager.addDebugLog("Starting transcription: ${resultToProcess.fileName}")
        val filePath = FileUtil.getAudioFilePath(context, resultToProcess.fileName)
        if (filePath == null) {
            logManager.addLog("Transcription failed: file not found", LogLevel.ERROR)
            val errorResult = resultToProcess.copy(
                transcription = "文字起こしエラー: ファイルが見つかりません",
                transcriptionStatus = "FAILED"
            )
            transcriptionResultRepository.addResult(errorResult)
            return
        }
        stateManager.updateTranscriptionState("Transcribing ${File(filePath).name}")

        // Detect recording type from filename (AI*.wav vs R*.wav)
        val recordingType = when {
            resultToProcess.fileName.startsWith("AI") && resultToProcess.fileName.length > 2 && resultToProcess.fileName[2].isDigit() -> "AI"
            else -> "NORMAL"
        }
        logManager.addDebugLog("Recording type detected: $recordingType")

        // Get current transcription provider
        val provider = appSettingsRepository.getFlow(Settings.TRANSCRIPTION_PROVIDER).first()
        val currentService = serviceManager.getCurrentService(provider)

        val locationData = currentForegroundLocationFlow.value ?: run {
            logManager.addDebugLog("Foreground location not available, using low-power")
            locationTracker.getLowPowerLocation().getOrNull()
        }
        if (locationData != null) {
            logManager.addDebugLog("Location: Lat=${locationData.latitude}, Lng=${locationData.longitude}")
        }

        if (currentService == null) {
            val errorMessage = "APIキーが設定されていません。設定画面で入力してください。"
            stateManager.updateTranscriptionState("Error: $errorMessage")
            logManager.addLog("Transcription failed: API key not set", LogLevel.ERROR)
            val errorResult = resultToProcess.copy(
                transcription = "文字起こしエラー: $errorMessage",
                locationData = locationData ?: resultToProcess.locationData,
                transcriptionStatus = "FAILED",
                recordingType = recordingType
            )
            transcriptionResultRepository.addResult(errorResult)
            return
        }

        val providerName = serviceManager.getProviderName(provider)
        logManager.addDebugLog("Calling $providerName speech service...")
        val result = when (provider) {
            TranscriptionProvider.GOOGLE -> (currentService as com.pirorin215.btclockmob.service.SpeechToTextService).transcribeFile(filePath)
            TranscriptionProvider.GROQ -> (currentService as com.pirorin215.btclockmob.service.GroqSpeechService).transcribeFile(filePath)
        }
        logManager.addDebugLog("$providerName speech service completed")

        result.onSuccess { fullTranscription ->
            handleTranscriptionSuccess(fullTranscription, resultToProcess, recordingType, locationData)
        }.onFailure { error ->
            handleTranscriptionFailure(error, resultToProcess, recordingType, locationData)
        }
    }

    private suspend fun handleTranscriptionSuccess(
        fullTranscription: String,
        resultToProcess: TranscriptionResult,
        recordingType: String,
        locationData: LocationData?
    ) {
        val googleTaskTitleLength = googleTaskTitleLengthFlow.first()

        val rawTitle = fullTranscription.take(googleTaskTitleLength)
        val cleanTitle = rawTitle.replace("\n", "")

        // 空文字（ハルシネーション）の場合はエラーとして扱う
        if (cleanTitle.isBlank()) {
            logManager.addLog("Transcription failed: Empty result (likely hallucination)", LogLevel.ERROR)
            val errorResult = resultToProcess.copy(
                transcription = "文字起こしエラー",
                locationData = locationData ?: resultToProcess.locationData,
                transcriptionStatus = "FAILED",
                recordingType = recordingType
            )
            transcriptionResultRepository.addResult(errorResult)
            stateManager.notifyCompletion(resultToProcess.fileName)
            return
        }

        val title = cleanTitle

        // For AI mode, process with selected AI provider
        var aiResponse: String? = null
        var finalNotes: String? = null
        var notificationText = fullTranscription  // Default notification text

        if (recordingType == "AI") {
            val aiProvider = appSettingsRepository.getFlow(Settings.AI_PROVIDER).first()
            logManager.addDebugLog("AI mode detected, calling $aiProvider service...")

            val aiResult = when (aiProvider) {
                AIProvider.GEMINI -> serviceManager.geminiService?.generateResponse(fullTranscription)
                AIProvider.GROQ -> serviceManager.groqLLMService?.generateResponse(fullTranscription)
            }

            aiResult?.onSuccess { response ->
                aiResponse = response
                finalNotes = response  // AI response goes to notes
                notificationText = response  // Show AI response in notification
                logManager.addDebugLog("$aiProvider response received: ${response.take(50)}...")
            }?.onFailure { error ->
                logManager.addLog("$aiProvider API failed: ${error.message}", LogLevel.ERROR)
                // AI失敗を明示的に記録
                aiResponse = null
                val errorMessage = "AI応答の生成に失敗しました: ${error.message}"
                finalNotes = "$errorMessage\n\n【文字起こし】\n$fullTranscription"
                notificationText = "⚠️ AI応答の生成に失敗しました\n$title"
            }
        } else {
            // Normal mode: use transcription as notes if overflow
            finalNotes = if (fullTranscription.length > googleTaskTitleLength) {
                fullTranscription
            } else {
                null
            }
        }

        stateManager.updateTranscriptionResult(title)
        logManager.addLog("Transcribed: '$title' (type: $recordingType)")
        val newResult = resultToProcess.copy(
            transcription = title,
            googleTaskNotes = finalNotes,
            recordingType = recordingType,
            aiResponse = aiResponse,
            locationData = locationData ?: resultToProcess.locationData,
            transcriptionStatus = "COMPLETED"
        )
        transcriptionResultRepository.addResult(newResult)
        // Send notification (AI response or transcription)
        notificationManager.sendTranscriptionNotification(notificationText)
        // Immediately sync with Google Tasks after successful transcription
        googleTasksIntegration.syncTranscriptionResultsWithGoogleTasks(audioDirNameFlow.value)
        // Notify that processing for this file is complete
        stateManager.notifyCompletion(resultToProcess.fileName)
    }

    private suspend fun handleTranscriptionFailure(
        error: Throwable,
        resultToProcess: TranscriptionResult,
        recordingType: String,
        locationData: LocationData?
    ) {
        val errorMessage = error.message ?: "不明なエラー"
        val isNetworkError = isTransientNetworkError(error)
        val isApiKeyError = errorMessage.contains("API key authentication failed") || errorMessage.contains("API key is not set")

        val status = if (isNetworkError && !isApiKeyError) {
            "PENDING" // Keep PENDING for transient network errors to enable retry
        } else {
            "FAILED" // Mark as FAILED for permanent errors
        }

        val displayMessage = if (isApiKeyError) {
            "文字起こしエラー: APIキーに問題がある可能性があります。設定画面をご確認ください。詳細: $errorMessage"
        } else if (isNetworkError) {
            "文字起こし中... (通信エラーにより再試行します)"
        } else {
            "文字起こしエラー: $errorMessage"
        }

        stateManager.updateTranscriptionState(
            if (isNetworkError) "Waiting to retry: ${resultToProcess.fileName}" else "Error: $displayMessage"
        )
        stateManager.updateTranscriptionResult(null)
        logManager.addLog("Transcription failed: $errorMessage (status: $status)", LogLevel.ERROR)
        val errorResult = resultToProcess.copy(
            transcription = displayMessage,
            locationData = locationData ?: resultToProcess.locationData,
            transcriptionStatus = status,
            recordingType = recordingType
        )
        transcriptionResultRepository.addResult(errorResult)

        // Only sync and emit completion for non-retryable errors
        if (status == "FAILED") {
            // Immediately sync with Google Tasks after failure to create a task indicating error
            googleTasksIntegration.syncTranscriptionResultsWithGoogleTasks(audioDirNameFlow.value)
            // Notify that processing for this file is complete (even on failure)
            stateManager.notifyCompletion(resultToProcess.fileName)
        }
    }

    /**
     * 保留中の文字起こしを処理
     */
    fun processPendingTranscriptions() {
        scope.launch {
            if (!transcriptionMutex.tryLock()) {
                logManager.addDebugLog("Transcription already in progress")
                return@launch
            }
            try {
                while(true) {
                    // Short delay to allow multiple files to queue up
                    delay(com.pirorin215.btclockmob.constants.TranscriptionConstants.QUEUE_CHECK_DELAY_MS)

                    // First, check in-memory queue (instant, no Flow propagation delay)
                    val queuedResult = queueManager.getNextFromQueue()

                    if (queuedResult != null) {
                        logManager.addDebugLog("Processing from queue: ${queuedResult.fileName}")
                        doTranscription(queuedResult)
                        continue // Immediately check for next item
                    }

                    // If queue is empty, check DataStore Flow (for items added before app restart or from other sources)
                    val resultFromDataStore = transcriptionResultRepository.transcriptionResultsFlow.first()
                        .filter { it.transcriptionStatus == "PENDING" }
                        .minByOrNull { com.pirorin215.btclockmob.data.FileUtil.getTimestampFromFileName(it.fileName) ?: Long.MAX_VALUE }

                    if (resultFromDataStore != null) {
                        logManager.addDebugLog("Processing from DataStore: ${resultFromDataStore.fileName}")
                        doTranscription(resultFromDataStore)
                        continue
                    }

                    // Both queue and DataStore are empty
                    logManager.addDebugLog("No more pending transcriptions")
                    break
                }
                logManager.addDebugLog("All transcriptions processed")
                stateManager.updateTranscriptionState("Idle")
            } finally {
                transcriptionMutex.unlock()
                // Cleanup will be called by the main manager
            }
        }
    }

    /**
     * 一時的なネットワークエラーかどうかを判定
     */
    private fun isTransientNetworkError(error: Throwable): Boolean {
        val errorMessage = error.message?.lowercase() ?: ""

        // Check exception type for known transient network errors
        when (error) {
            is java.net.UnknownHostException -> return true
            is java.net.ConnectException -> return true
            is java.net.SocketTimeoutException -> return true
            is java.io.IOException -> {
                // Only treat IOException as transient if it's network-related
                return errorMessage.contains("connection") ||
                       errorMessage.contains("network") ||
                       errorMessage.contains("resolve host") ||
                       errorMessage.contains("timeout")
            }
        }

        // Check error message for network-related keywords
        return errorMessage.contains("unable to resolve host") ||
               errorMessage.contains("connection refused") ||
               errorMessage.contains("failed to connect") ||
               errorMessage.contains("network") && errorMessage.contains("unreachable")
    }
}
