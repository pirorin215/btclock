package com.pirorin215.btclockmob.viewModel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.pirorin215.btclockmob.BleScanServiceManager
import com.pirorin215.btclockmob.MainApplication // Add this import
import com.pirorin215.btclockmob.data.ConnectionState
import com.pirorin215.btclockmob.data.FileEntry
import com.pirorin215.btclockmob.data.Settings
import com.pirorin215.btclockmob.data.ThemeMode
import com.pirorin215.btclockmob.data.TranscriptionResult
import com.pirorin215.btclockmob.service.BleScanService
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// Data class for ADPCM decode test results
data class AdpcmTestResult(
    val success: Boolean,
    val filePath: String,
    val bytesRead: Int,
    val bytesExpected: Int,
    val message: String
)

@SuppressLint("MissingPermission")
class MainViewModel(
    private val application: Application,
    private val bleConnectionManager: BleConnectionManager,
    private val bleOrchestrator: BleOrchestrator,
    private val transcriptionManager: TranscriptionManager,
    private val bleSelectionManager: BleSelectionManager,
    private val googleTasksIntegration: GoogleTasksManager,
    private val locationMonitor: LocationMonitor,
    private val logManager: LogManager,
    private val transcriptionResultRepository: com.pirorin215.btclockmob.data.TranscriptionResultRepository,
    private val appSettingsRepository: com.pirorin215.btclockmob.data.AppSettingsRepository
) : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"

        init {
            try {
                System.loadLibrary("adpcm")
                Log.d(TAG, "Native library 'adpcm' loaded successfully.")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library 'adpcm': ${e.message}")
            }
        }
    }

    // --- UI State Flows ---
    val themeMode: StateFlow<ThemeMode> = appSettingsRepository.getFlow(Settings.THEME_MODE)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.SYSTEM)

    val transcriptionFontSize: StateFlow<Int> = appSettingsRepository.getFlow(Settings.TRANSCRIPTION_FONT_SIZE)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 14)

    val audioDirName: StateFlow<String> = appSettingsRepository.getFlow(Settings.AUDIO_DIR_NAME)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "BTClockRecordings")

    val transcriptionResults: StateFlow<List<TranscriptionResult>> = transcriptionResultRepository.transcriptionResultsFlow
        .map { list -> list.filter { !it.isDeletedLocally } }
        .map { list -> list.sortedByDescending { com.pirorin215.btclockmob.data.FileUtil.getTimestampFromFileName(it.fileName) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val transcriptionCount: StateFlow<Int> = transcriptionResults
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val logs = logManager.logs
    val selectedFileNames = bleSelectionManager.selectedFileNames

    // --- State exposed from orchestrator and managers ---
    val connectionState: StateFlow<ConnectionState> = bleConnectionManager.connectionState
    val currentOperation: StateFlow<BleOperation> = bleOrchestrator.currentOperation
    val navigationEvent: SharedFlow<NavigationEvent> = bleOrchestrator.navigationEvent
    val audioFileCount = transcriptionManager.audioFileCount
    val transcriptionState = transcriptionManager.transcriptionState
    val transcriptionResult = transcriptionManager.transcriptionResult
    val fileList: StateFlow<List<FileEntry>> = bleOrchestrator.fileList
    val deviceInfo: StateFlow<com.pirorin215.btclockmob.data.DeviceInfoResponse?> = bleOrchestrator.deviceInfo
    val deviceSettings: StateFlow<com.pirorin215.btclockmob.data.DeviceSettings> = bleOrchestrator.deviceSettings
    val downloadProgress: StateFlow<Int> = bleOrchestrator.downloadProgress
    val currentFileTotalSize: StateFlow<Long> = bleOrchestrator.currentFileTotalSize
    val fileTransferState: StateFlow<String> = bleOrchestrator.fileTransferState
    val transferKbps: StateFlow<Float> = bleOrchestrator.transferKbps
    val currentForegroundLocation = locationMonitor.currentForegroundLocation
    
    // --- Audio Player Manager (scoped to ViewModel) ---
    private val audioPlayerManager: AudioPlayerManager by lazy {
        AudioPlayerManager(application)
    }
    val currentlyPlayingFile: StateFlow<String?> = audioPlayerManager.currentlyPlayingFile

    // --- Google Tasks State & Methods ---
    val account: StateFlow<GoogleSignInAccount?> = googleTasksIntegration.account
    val isLoadingGoogleTasks: StateFlow<Boolean> = googleTasksIntegration.isLoadingGoogleTasks
    fun handleSignInResult(intent: Intent, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) = googleTasksIntegration.handleSignInResult(intent, onSuccess, onFailure)
    fun signOut() = googleTasksIntegration.signOut()
    suspend fun getGoogleSignInIntent(): Intent? = googleTasksIntegration.googleSignInClient.firstOrNull()?.signInIntent
    fun syncTranscriptionResultsWithGoogleTasks() = viewModelScope.launch {
        googleTasksIntegration.syncTranscriptionResultsWithGoogleTasks(audioDirName.value)
    }

    // --- Methods delegated to orchestrator and managers ---
    fun fetchFileList(extension: String = "wav") = bleOrchestrator.fetchFileList(extension)
    suspend fun getSettings() = bleOrchestrator.getSettings()
    fun sendSettings() = bleOrchestrator.sendSettings()
    fun updateSettings(updater: (com.pirorin215.btclockmob.data.DeviceSettings) -> com.pirorin215.btclockmob.data.DeviceSettings) = bleOrchestrator.updateSettings(updater)
    fun downloadFile(fileName: String) = bleOrchestrator.downloadFile(fileName)
    fun sendCommand(command: String) = bleOrchestrator.sendCommand(command)
    fun clearLogs() = logManager.clearLogs()
    fun forceReconnectBle() = bleConnectionManager.forceReconnect()
    fun toggleSelection(fileName: String) = bleSelectionManager.toggleSelection(fileName)
    fun clearSelection() = bleSelectionManager.clearSelection()

    // --- Location Monitor Delegation ---
    fun startLowPowerLocationUpdates() = locationMonitor.startLowPowerLocationUpdates()
    fun stopLowPowerLocationUpdates() = locationMonitor.stopLowPowerLocationUpdates()

    // --- Methods delegated to Transcription Manager ---
    fun resetTranscriptionState() = transcriptionManager.resetTranscriptionState()
    fun playAudioFile(transcriptionResult: TranscriptionResult) {
        viewModelScope.launch {
            val filePath = com.pirorin215.btclockmob.data.FileUtil.getAudioFilePath(application, transcriptionResult.fileName)
            if (filePath != null) {
                audioPlayerManager.play(filePath) {
                    // This callback is executed on completion.
                    // We need to switch to the viewModelScope to ensure state is updated on the main thread.
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
    fun removeTranscriptionResult(result: TranscriptionResult) = transcriptionManager.removeTranscriptionResult(result)
    fun updateTranscriptionResult(originalResult: TranscriptionResult, newTranscription: String, newNote: String?) = transcriptionManager.updateTranscriptionResult(originalResult, newTranscription, newNote)
    fun removeTranscriptionResults(fileNames: Set<String>) {
        transcriptionManager.removeTranscriptionResults(fileNames) {
            bleSelectionManager.clearSelection()
        }
    }
    fun retranscribe(result: TranscriptionResult) = transcriptionManager.retranscribe(result)
    fun addManualTranscription(text: String) = transcriptionManager.addManualTranscription(text)

    // --- ADPCM Decode Test ---
    fun testDecodeAdpcmFile(filePath: String): AdpcmTestResult {
        val result = testDecodeAdpcmNative(filePath)
        val success = result >= 0
        val message = if (success) {
            "Decoded successfully"
        } else {
            "Decode failed with error code: $result"
        }
        return AdpcmTestResult(
            success = success,
            filePath = filePath,
            bytesRead = if (success) result else 0,
            bytesExpected = java.io.File(filePath).length().toInt(),
            message = message
        )
    }

    private external fun testDecodeAdpcmNative(filePath: String): Int

    fun stopAppServices() {
        Log.d(TAG, "Stopping all app services...")
        // Stop BLE connection and release resources
        bleConnectionManager.disconnect()
        bleConnectionManager.close()
        
        // Stop the background BLE scanning service
        val serviceIntent = Intent(application, BleScanService::class.java)
        application.stopService(serviceIntent)
        Log.d(TAG, "BleScanService stopped.")

        // Stop transcription processes and release resources
        transcriptionManager.resetTranscriptionState()
        Log.d(TAG, "TranscriptionManager state reset.")

        // Stop location updates
        locationMonitor.stopLowPowerLocationUpdates()
        Log.d(TAG, "Location updates stopped.")

        // Stop audio playback and release resources
        audioPlayerManager.stop()
        audioPlayerManager.release()
        Log.d(TAG, "Audio player released.")

        logManager.addLog("All services stopped. App is shutting down.")
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayerManager.release() // Release player resources
        Log.d(TAG, "ViewModel cleared.")
    }
}