package com.pirorin215.btclockmob.viewModel.transcription

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.pirorin215.btclockmob.LocationData
import com.pirorin215.btclockmob.LocationTracker
import com.pirorin215.btclockmob.data.AppSettingsRepository
import com.pirorin215.btclockmob.data.Settings
import com.pirorin215.btclockmob.data.TranscriptionResult
import com.pirorin215.btclockmob.data.TranscriptionResultRepository
import com.pirorin215.btclockmob.data.FileUtil
import com.pirorin215.btclockmob.viewModel.LogManager
import com.pirorin215.btclockmob.viewModel.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

/**
 * 文字起こし結果のCRUD操作とクリーンアップを担当するマネージャークラス
 */
class TranscriptionResultManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val appSettingsRepository: AppSettingsRepository,
    private val transcriptionResultRepository: TranscriptionResultRepository,
    private val currentForegroundLocationFlow: StateFlow<LocationData?>,
    private val audioDirNameFlow: StateFlow<String>,
    private val transcriptionCacheLimitFlow: StateFlow<Int>,
    private val googleTaskTitleLengthFlow: StateFlow<Int>,
    private val googleTasksIntegration: com.pirorin215.btclockmob.viewModel.GoogleTasksManager,
    private val locationTracker: LocationTracker,
    private val logManager: LogManager,
    private val stateManager: TranscriptionStateManager
) {
    // Note: We use .first() instead of caching to ensure we always get the latest data
    // The performance impact is minimal since these operations are user-triggered
    /**
     * ローカルオーディオファイル数を更新
     */
    fun updateLocalAudioFileCount() {
        scope.launch {
            // Count files in Documents/BTClockMob/ using MediaStore API
            val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                MediaStore.Files.getContentUri("external")
            } else {
                MediaStore.Files.getContentUri("external")
            }

            val projection = arrayOf(MediaStore.Files.FileColumns._ID)
            val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ? AND " +
                    "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf(
                "${Environment.DIRECTORY_DOCUMENTS}/BTClockMob/",
                "R%.wav"
            )

            var count = 0
            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                count = cursor.count
            }

            stateManager.updateAudioFileCount(count)
            logManager.addDebugLog("Audio file count in Documents/BTClockMob/: $count")
        }
    }

    /**
     * 文字起こし結果のクリーンアップを実行
     */
    suspend fun cleanupTranscriptionResultsAndAudioFiles() = withContext(Dispatchers.IO) {
        try {
            logManager.addDebugLog("Running cleanup...")
            val limit = transcriptionCacheLimitFlow.value
            // Fetch current list from repository
            val currentTranscriptionResults = transcriptionResultRepository.transcriptionResultsFlow.first()
                .filter { !it.isDeletedLocally } // Assuming we only count non-deleted ones for limit?
                .sortedBy { it.lastEditedTimestamp } // Oldest first

            if (currentTranscriptionResults.size > limit) {
                val resultsToDelete = currentTranscriptionResults.take(currentTranscriptionResults.size - limit)
                logManager.addLog("Cleanup: deleting ${resultsToDelete.size} old results (limit: $limit)")

                // Parallel deletion with limited concurrency to avoid overwhelming the system
                resultsToDelete.map { result ->
                    async(Dispatchers.IO) {
                        // Delete from DataStore
                        transcriptionResultRepository.removeResult(result)
                        logManager.addDebugLog("Deleted result: ${result.fileName}")

                        // Delete associated audio file using MediaStore API
                        if (FileUtil.deleteAudioFile(context, result.fileName)) {
                            logManager.addDebugLog("Deleted audio: ${result.fileName}")
                        } else {
                            logManager.addLog("Failed to delete audio: ${result.fileName}", LogLevel.ERROR)
                        }
                    }
                }.awaitAll() // Wait for all deletions to complete

                logManager.addDebugLog("Cleanup complete")
            } else {
                logManager.addDebugLog("Cache within limit ($limit)")
            }
        } catch (e: Exception) {
            logManager.addLog("Cleanup error: ${e.message}", LogLevel.ERROR)
        } finally {
            updateLocalAudioFileCount() // Ensure count is updated after cleanup
        }
    }

    /**
     * 文字起こし結果を再処理
     */
    fun retranscribe(result: TranscriptionResult, processor: TranscriptionProcessor) {
        scope.launch {
            logManager.addLog("Retranscribing: ${result.fileName}")

            // Check if audio file exists using MediaStore API
            if (!FileUtil.audioFileExists(context, result.fileName)) {
                logManager.addLog("Retranscribe failed: file not found", LogLevel.ERROR)
                // Optionally update status to FAILED here if desired
                val updatedResult = result.copy(transcriptionStatus = "FAILED", transcription = "Audio file not found.")
                transcriptionResultRepository.addResult(updatedResult)
                return@launch
            }

            logManager.addDebugLog("Marking as PENDING: ${result.fileName}")
            val pendingResult = result.copy(
                transcriptionStatus = "PENDING",
                googleTaskId = null  // Clear to force new task creation on retranscription
            )
            transcriptionResultRepository.addResult(pendingResult)

            // Immediately trigger processing
            processor.processPendingTranscriptions()
        }
    }

    /**
     * 保留中の文字起こしを追加
     */
    suspend fun addPendingTranscription(
        fileName: String,
        queueManager: TranscriptionQueueManager
    ) {
        logManager.addDebugLog("Creating transcription record: $fileName")
        val existing = transcriptionResultRepository.transcriptionResultsFlow.first().find { it.fileName == fileName }
        if (existing != null) {
            logManager.addDebugLog("Record exists: $fileName (${existing.transcriptionStatus})")
            // If record is already completed or failed, it means we likely just need to trigger post-transcription logic (like file deletion).
            // This can happen if app crashed or was stopped after transcription but before deletion.
            if (existing.transcriptionStatus == "COMPLETED" || existing.transcriptionStatus == "FAILED") {
                logManager.addDebugLog("Emitting completion for processed file")
                stateManager.notifyCompletion(fileName)
            }
            // If status is PENDING, we do nothing and let normal processing flow handle it.
            return
        }

        logManager.addDebugLog("New transcription record: $fileName")

        // Detect recording type from filename
        val recordingType = when {
            fileName.startsWith("AI") && fileName.length > 2 && fileName[2].isDigit() -> "AI"
            else -> "NORMAL"
        }
        logManager.addDebugLog("Recording type: $recordingType")

        val newResult = TranscriptionResult(
            fileName = fileName,
            transcription = "", // Empty transcription initially
            locationData = null, // Location will be fetched during transcription
            transcriptionStatus = "PENDING",
            recordingType = recordingType
        )

        // Add to DataStore (for persistence across app restarts)
        transcriptionResultRepository.addResult(newResult)

        // Add to in-memory queue immediately (for fast processing without waiting for Flow propagation)
        queueManager.addToQueue(newResult)

        logManager.addDebugLog("Pending transcription saved: $fileName")
    }

    /**
     * 手動文字起こしを追加
     */
    fun addManualTranscription(text: String) {
        scope.launch {
            var locationData: LocationData? = currentForegroundLocationFlow.value
            logManager.addDebugLog("Manual transcription: ${if (locationData != null) "with location" else "no location"}")

            val timestamp = System.currentTimeMillis()
            val manualFileName = "M${FileUtil.formatTimestampForFileName(timestamp)}.txt"

            val googleTaskTitleLength = googleTaskTitleLengthFlow.first()
            val rawTitle = text.take(googleTaskTitleLength)
            val cleanTitle = rawTitle.replace("\n", "")
            val title = if (cleanTitle.isBlank()) "Manual Transcription" else cleanTitle

            val notes = if (text.length > googleTaskTitleLength) {
                text
            } else {
                null
            }

            val newResult = TranscriptionResult(
                fileName = manualFileName,
                transcription = title, // Save title as main transcription
                locationData = locationData,
                googleTaskNotes = notes // Save full transcription as notes if overflow
            )

            transcriptionResultRepository.addResult(newResult)
            logManager.addLog("Manual transcription added")

            scope.launch { cleanupTranscriptionResultsAndAudioFiles() }
        }
    }

    /**
     * 全文字起こし結果をクリア
     */
    fun clearTranscriptionResults() {
        scope.launch(Dispatchers.IO) {
            logManager.addLog("Clearing all transcriptions")
            val currentTranscriptionResults = transcriptionResultRepository.transcriptionResultsFlow.first()

            val updatedListForRepo = mutableListOf<TranscriptionResult>()

            // Parallel file deletion using coroutines
            currentTranscriptionResults.map { result ->
                async(Dispatchers.IO) {
                    val fileDeleted = FileUtil.deleteAudioFile(context, result.fileName)
                    if (fileDeleted) {
                        logManager.addDebugLog("Deleted: ${result.fileName}")
                    } else {
                        logManager.addLog("Failed to delete: ${result.fileName}", LogLevel.ERROR)
                    }

                    if (result.googleTaskId == null) {
                        // Local-only item: permanently delete now (from DataStore)
                        transcriptionResultRepository.permanentlyRemoveResult(result)
                    } else {
                        // Synced item: soft delete (mark for remote deletion during next sync)
                        updatedListForRepo.add(result.copy(isDeletedLocally = true))
                    }
                }
            }.awaitAll() // Wait for all deletions to complete

            transcriptionResultRepository.updateResults(updatedListForRepo) // Only updates for synced items

            logManager.addLog("All transcriptions cleared")
            updateLocalAudioFileCount() // Update count after deletions
        }
    }

    /**
     * 文字起こし結果を削除
     */
    fun removeTranscriptionResult(result: TranscriptionResult) {
        scope.launch(Dispatchers.IO) {
            logManager.addDebugLog("Starting deletion: ${result.fileName}, googleTaskId: ${result.googleTaskId}")

            if (result.googleTaskId != null) {
                // If synced with Google Tasks, perform a soft delete locally.
                // Actual deletion from Google Tasks and permanent local deletion will happen during sync.
                transcriptionResultRepository.removeResult(result) // This now sets isDeletedLocally = true
                logManager.addDebugLog("Soft-deleted: ${result.fileName}")
            } else {
                // If not synced with Google Tasks, permanently delete locally.
                transcriptionResultRepository.permanentlyRemoveResult(result)
                logManager.addLog("Deleted: ${result.fileName}")
            }

            // Delete audio file using MediaStore API
            val audioDeleted = FileUtil.deleteAudioFile(context, result.fileName)
            logManager.addDebugLog("Audio file deletion result for ${result.fileName}: $audioDeleted")

            if (audioDeleted) {
                logManager.addDebugLog("Audio deleted: ${result.fileName}")
                updateLocalAudioFileCount()
            } else {
                logManager.addLog("Failed to delete audio: ${result.fileName}", LogLevel.ERROR)
            }

            logManager.addDebugLog("Deletion complete: ${result.fileName}")
        }
    }

    /**
     * 文字起こし結果を更新
     */
    fun updateTranscriptionResult(originalResult: TranscriptionResult, newTranscription: String, newNote: String?) {
        scope.launch {
            // Create a new TranscriptionResult with updated transcription and notes
            val updatedResult = originalResult.copy(
                transcription = newTranscription,
                googleTaskNotes = newNote, // Update googleTaskNotes
                lastEditedTimestamp = System.currentTimeMillis()
            )
            // The addResult method now handles updates, so we just call that.
            transcriptionResultRepository.addResult(updatedResult)
            logManager.addLog("Updated: ${originalResult.fileName}")
        }
    }

    /**
     * 複数の文字起こし結果を削除
     */
    fun removeTranscriptionResults(fileNames: Set<String>, clearSelectionCallback: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            android.util.Log.d("TranscriptionResultManager", "removeTranscriptionResults called with ${fileNames.size} files: $fileNames")
            logManager.addDebugLog("Removing ${fileNames.size} transcription results")

            // Fetch current results from repository
            val currentResults = transcriptionResultRepository.transcriptionResultsFlow.first()
            android.util.Log.d("TranscriptionResultManager", "Current results count: ${currentResults.size}")

            val resultsToRemove = currentResults.filter { fileNames.contains(it.fileName) }
            android.util.Log.d("TranscriptionResultManager", "Results to remove: ${resultsToRemove.size}")

            // Batch process deletions more efficiently
            resultsToRemove.forEach { result ->
                android.util.Log.d("TranscriptionResultManager", "Processing deletion: ${result.fileName}, googleTaskId: ${result.googleTaskId}")

                if (result.googleTaskId != null) {
                    // If synced with Google Tasks, perform a soft delete locally.
                    // Actual deletion from Google Tasks and permanent local deletion will happen during sync.
                    transcriptionResultRepository.removeResult(result) // This now sets isDeletedLocally = true
                    logManager.addDebugLog("Soft-deleted: ${result.fileName}")
                } else {
                    // If not synced with Google Tasks, permanently delete locally.
                    transcriptionResultRepository.permanentlyRemoveResult(result)
                    logManager.addLog("Deleted: ${result.fileName}")
                }

                // Delete audio file using MediaStore API
                val audioDeleted = FileUtil.deleteAudioFile(context, result.fileName)
                android.util.Log.d("TranscriptionResultManager", "Audio file deletion result for ${result.fileName}: $audioDeleted")

                if (audioDeleted) {
                    logManager.addDebugLog("Audio deleted: ${result.fileName}")
                } else {
                    logManager.addLog("Failed to delete audio: ${result.fileName}", LogLevel.ERROR)
                }
            }

            clearSelectionCallback() // Clear selection after deletion
            logManager.addLog("Removed ${resultsToRemove.size} transcriptions")
            android.util.Log.d("TranscriptionResultManager", "Deletion complete")
            updateLocalAudioFileCount()
        }
    }
}
