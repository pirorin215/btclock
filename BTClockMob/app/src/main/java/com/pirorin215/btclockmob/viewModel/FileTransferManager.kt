package com.pirorin215.btclockmob.viewModel

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.pirorin215.btclockmob.data.BleRepository
import com.pirorin215.btclockmob.data.ConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import com.pirorin215.btclockmob.data.AppSettingsRepository
import com.pirorin215.btclockmob.data.Settings
import com.pirorin215.btclockmob.constants.FileTransferConstants
import com.pirorin215.btclockmob.bluetooth.device.BleDeviceManager

class FileTransferManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: BleRepository,
    private val transcriptionManager: TranscriptionManager,
    private val audioDirNameFlow: StateFlow<String>,
    private val bleMutex: Mutex,
    private val logManager: LogManager,
    private val sendCommandCallback: (String) -> Unit,
    private val sendAckCallback: (ByteArray) -> Unit,
    private val _currentOperation: MutableStateFlow<BleOperation>,
    private val bleDeviceManager: BleDeviceManager,
    private val _connectionState: StateFlow<ConnectionState>,
    private val disconnectSignal: SharedFlow<Unit>,
    private val appSettingsRepository: AppSettingsRepository // Inject AppSettingsRepository
) {

    private var chunkBurstSize: Int = com.pirorin215.btclockmob.constants.FileTransferConstants.DEFAULT_CHUNK_BURST_SIZE // Default value, will be updated from settings

    init {
        scope.launch {
            appSettingsRepository.getFlow(Settings.CHUNK_BURST_SIZE).collect { newChunkBurstSize ->
                chunkBurstSize = newChunkBurstSize
                logManager.addDebugLog("Chunk burst size: $chunkBurstSize")
            }
        }
        scope.launch {
            disconnectSignal.collect {
                logManager.addDebugLog("Disconnect during transfer")
                cancelOngoingTransferDueToDisconnect()
            }
        }

    }

    companion object {
        // Constants moved to FileTransferConstants.kt, but kept here for backward compatibility
        const val MAX_DELETE_RETRIES = FileTransferConstants.MAX_DELETE_RETRIES
        const val DELETE_RETRY_DELAY_MS = FileTransferConstants.DELETE_RETRY_DELAY_MS
        const val PACKET_TIMEOUT_MS = FileTransferConstants.PACKET_TIMEOUT_MS
    }

    private var chunkCounter = 0

    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    private val _currentFileTotalSize = MutableStateFlow(0L)
    val currentFileTotalSize: StateFlow<Long> = _currentFileTotalSize.asStateFlow()

    private val _fileTransferState = MutableStateFlow("Idle")
    val fileTransferState: StateFlow<String> = _fileTransferState.asStateFlow()

    private val _transferKbps = MutableStateFlow(0.0f)
    val transferKbps: StateFlow<Float> = _transferKbps.asStateFlow()

    private var currentDownloadingFileName: String? = null
    private var _transferStartTime = 0L
    private var sortedChunks = sortedMapOf<Int, ByteArray>()
    private var currentCommandCompletion: CompletableDeferred<Pair<Boolean, String?>>? = null
    private var currentDeleteCompletion: CompletableDeferred<Boolean>? = null
    private var downloadTimeoutJob: Job? = null

    fun cancelOngoingTransferDueToDisconnect() {
        if (_currentOperation.value == BleOperation.DOWNLOADING_FILE || _currentOperation.value == BleOperation.DELETING_FILE) {
            logManager.addLog("Transfer cancelled: connection lost", LogLevel.ERROR)
            downloadTimeoutJob?.cancel()
            // Complete with failure to unblock any waiting coroutines
            currentCommandCompletion?.complete(Pair(false, "Connection Lost"))
            currentDeleteCompletion?.complete(false)
        }
    }

    fun resetFileTransferMetrics() {
        _downloadProgress.value = 0
        _currentFileTotalSize.value = 0L
        _transferKbps.value = 0.0f
        _transferStartTime = 0L
        sortedChunks.clear()
    }

    private fun saveFile(data: ByteArray): String? {
        val fileName = currentDownloadingFileName ?: "downloaded_file_${System.currentTimeMillis()}.bin"
        return try {
            if (fileName.startsWith("log.", ignoreCase = true)) {
                val outputStream: OutputStream?
                val uri: Uri?
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }

                    val resolver = context.contentResolver
                    uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    outputStream = uri?.let { resolver.openOutputStream(it) }
                } else {
                    @Suppress("DEPRECATION")
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!downloadsDir.exists()) {
                        downloadsDir.mkdirs()
                    }
                    val file = File(downloadsDir, fileName)
                    uri = Uri.fromFile(file)
                    outputStream = FileOutputStream(file)
                }

                outputStream?.use { stream ->
                    stream.write(data)
                } ?: throw Exception("Failed to get output stream for URI.")

                logManager.addLog("Log file saved: $fileName")
                return uri?.toString()

            } else {
                // Save to Documents/BTClockMob/ using MediaStore API
                val outputStream: OutputStream?
                val uri: Uri?
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/BTClockMob")
                    }

                    val resolver = context.contentResolver
                    uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
                    outputStream = uri?.let { resolver.openOutputStream(it) }
                } else {
                    @Suppress("DEPRECATION")
                    val btclockDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                        "BTClockMob"
                    )
                    if (!btclockDir.exists()) {
                        btclockDir.mkdirs()
                    }
                    val file = File(btclockDir, fileName)
                    uri = Uri.fromFile(file)
                    outputStream = FileOutputStream(file)
                }

                outputStream?.use { stream ->
                    stream.write(data)
                } ?: throw Exception("Failed to get output stream for audio file.")

                logManager.addDebugLog("Audio saved to Documents/BTClockMob/: $fileName")
                return uri?.toString()
            }
        } catch (e: Exception) {
            logManager.addLog("File save error: ${e.message}", LogLevel.ERROR)
            _fileTransferState.value = "Error: ${e.message}"
            null
        }
    }

    fun handleCharacteristicChanged(
        characteristic: android.bluetooth.BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        // Check if this is the command characteristic (bidirectional)
        if (characteristic.uuid != UUID.fromString(BleRepository.COMMAND_UUID_STRING)) return

        when (_currentOperation.value) {
            BleOperation.DOWNLOADING_FILE -> {
                val filePath = handleFileDownloadDataInternal(value)
                if (filePath != null) {
                    currentCommandCompletion?.complete(Pair(true, filePath))
                }
            }
            BleOperation.DELETING_FILE -> {
                val response = value.toString(Charsets.UTF_8).trim()
                logManager.addDebugLog("Delete response: $response")
                if (response.startsWith("OK: File")) {
                    currentDeleteCompletion?.complete(true)
                } else {
                    logManager.addLog("Delete failed: $response", LogLevel.ERROR)
                    currentDeleteCompletion?.complete(false)
                }
            }
            else -> {
                // Not for us
            }
        }
    }

    private fun resetAndStartDownloadTimeoutTimer() {
        downloadTimeoutJob?.cancel()
        downloadTimeoutJob = scope.launch {
            delay(PACKET_TIMEOUT_MS)
            if (isActive) {
                logManager.addLog("File download timed out: No data received for ${PACKET_TIMEOUT_MS}ms.")
                currentCommandCompletion?.complete(Pair(false, null))
            }
        }
    }

    // Note: This function is always called within bleMutex.withLock context
    // No additional synchronization needed
    private fun handleFileDownloadDataInternal(value: ByteArray): String? {
        resetAndStartDownloadTimeoutTimer() // Reset timer on any received data

        when (_fileTransferState.value) {
            "WaitingForStart" -> {
                if (value.contentEquals("START".toByteArray())) {
                    logManager.addDebugLog("Transfer started")
                    sendAckCallback("START_ACK".toByteArray(Charsets.UTF_8))
                    _fileTransferState.value = "Downloading"
                    _transferStartTime = System.currentTimeMillis()
                    sortedChunks.clear()
                    _downloadProgress.value = 0
                    chunkCounter = 0 // Reset chunk counter for ACK mechanism
                } else {
                    logManager.addLog("Transfer start failed", LogLevel.ERROR)
                    downloadTimeoutJob?.cancel()
                    currentCommandCompletion?.complete(Pair(false, null))
                }
            }
            "Downloading" -> {
                if (value.contentEquals("EOF".toByteArray())) {
                    logManager.addDebugLog("Transfer complete, reconstructing file")
                    downloadTimeoutJob?.cancel() // Success, cancel timeout

                    val totalSize = sortedChunks.values.sumOf { it.size }
                    val reconstructedFile = ByteArray(totalSize)
                    var currentPosition = 0
                    sortedChunks.values.forEach { chunk ->
                        System.arraycopy(chunk, 0, reconstructedFile, currentPosition, chunk.size)
                        currentPosition += chunk.size
                    }

                    logManager.addDebugLog("File reconstructed: $totalSize bytes, ${sortedChunks.size} chunks")
                    return saveFile(reconstructedFile)

                } else if (value.toString(Charsets.UTF_8).startsWith("ERROR:")) {
                    val errorMessage = value.toString(Charsets.UTF_8)
                    logManager.addLog("Transfer error: $errorMessage", LogLevel.ERROR)
                    downloadTimeoutJob?.cancel() // Error, cancel timeout
                    currentCommandCompletion?.complete(Pair(false, null))
                } else {
                    if (value.size < 4) {
                        logManager.addDebugLog("Ignoring short packet")
                        return null
                    }
                    // Extract chunk index (Little Endian)
                    val chunkIndex = (value[0].toInt() and 0xFF) or
                            ((value[1].toInt() and 0xFF) shl 8) or
                            ((value[2].toInt() and 0xFF) shl 16) or
                            ((value[3].toInt() and 0xFF) shl 24)

                    val data = value.drop(4).toByteArray()
                    sortedChunks[chunkIndex] = data

                    _downloadProgress.value = sortedChunks.values.sumOf { it.size }
                    val elapsedTime = (System.currentTimeMillis() - _transferStartTime) / 1000.0f
                    if (elapsedTime > 0) {
                        _transferKbps.value = (_downloadProgress.value / 1024.0f) / elapsedTime
                    }

                    // The ACK mechanism is based on the number of packets received in a burst,
                    // not the chunk index itself. So we keep this logic.
                    chunkCounter++
                    if (chunkCounter >= chunkBurstSize) {
                        sendAckCallback("ACK".toByteArray(Charsets.UTF_8))
                        chunkCounter = 0
                    }
                }
            }
        }
        return null
    }

    suspend fun downloadFile(fileName: String): Boolean {
        if (_connectionState.value !is ConnectionState.Connected) {
            logManager.addLog("Download failed: not connected", LogLevel.ERROR)
            return false
        }

        var downloadSuccess = false

        bleMutex.withLock {
            if (_currentOperation.value != BleOperation.IDLE) {
                logManager.addDebugLog("Download skipped: operation in progress")
                return@withLock
            }

            var downloadResult: Pair<Boolean, String?>? = null
            try {
                _currentOperation.value = BleOperation.DOWNLOADING_FILE
                _fileTransferState.value = "WaitingForStart"
                currentDownloadingFileName = fileName
                currentCommandCompletion = CompletableDeferred()

                val fileEntry = bleDeviceManager.fileList.value.find { it.name == fileName }
                val fileSize = fileEntry?.size ?: 0L
                _currentFileTotalSize.value = fileSize

                logManager.addLog("Downloading: $fileName (${fileSize} bytes)")
                resetAndStartDownloadTimeoutTimer()
                sendCommandCallback("GET:file:$fileName:$chunkBurstSize")

                downloadResult = currentCommandCompletion!!.await()

            } catch (e: Exception) {
                logManager.addLog("Download error: ${e.message}", LogLevel.ERROR)
                downloadResult = Pair(false, null)
            } finally {
                downloadTimeoutJob?.cancel()
                _currentOperation.value = BleOperation.IDLE
                _fileTransferState.value = "Idle"
                currentDownloadingFileName = null
                resetFileTransferMetrics()
                logManager.addDebugLog("Download completed for $fileName")
            }

            val (success, savedFilePath) = downloadResult ?: Pair(false, null)
            if (success && savedFilePath != null) {
                downloadSuccess = true // Mark as success for the return value
                if (fileName.startsWith("log.", ignoreCase = true)) {
                    logManager.addLog("Log downloaded: $fileName")
                } else if (fileName.endsWith(".wav", ignoreCase = true)) {
                    // File is saved to Documents/BTClockMob/, use fileName directly
                    // For MediaStore URIs, we use fileName instead of File object
                    transcriptionManager.addPendingTranscription(fileName)
                    logManager.addDebugLog("Queued for transcription: $fileName")

                    // Now trigger transcription processing (record is guaranteed to exist)
                    transcriptionManager.processPendingTranscriptions()

                    scope.launch { transcriptionManager.cleanupTranscriptionResultsAndAudioFiles() }
                    transcriptionManager.updateLocalAudioFileCount()

                    // Deletion is now handled by the orchestrator after transcription.
                }
            } else {
                logManager.addLog("Download failed: $fileName", LogLevel.ERROR)
                downloadSuccess = false
                // To prevent immediate retry loops on persistent device errors, the orchestrator will handle the next sync.
            }
        }
        return downloadSuccess
    }


    internal suspend fun deleteFileAndUpdateList(fileName: String) {
        if (_connectionState.value !is ConnectionState.Connected) {
            logManager.addLog("Delete failed: not connected", LogLevel.ERROR)
            return
        }

        bleMutex.withLock {
            if (_currentOperation.value != BleOperation.IDLE) {
                logManager.addDebugLog("Delete skipped: operation in progress")
                return@withLock
            }

            var success = false
            try {
                _currentOperation.value = BleOperation.DELETING_FILE
                for (i in 0..MAX_DELETE_RETRIES) {
                    currentDeleteCompletion = CompletableDeferred()
                    logManager.addDebugLog("Deleting: $fileName (attempt ${i + 1}/${MAX_DELETE_RETRIES + 1})")

                    sendCommandCallback("DEL:file:$fileName")

                    success = try {
                        withTimeout(10000L) {
                            currentDeleteCompletion!!.await()
                        }
                    } catch (e: TimeoutCancellationException) {
                        logManager.addLog("Delete timeout: $fileName", LogLevel.ERROR)
                        false
                    }

                    if (success) {
                        logManager.addLog("Deleted from device: $fileName")
                        // Instead of re-fetching the list, remove it from the local state
                        bleDeviceManager.removeFileFromList(fileName, triggerCallback = false)
                        transcriptionManager.updateLocalAudioFileCount()
                        break
                    } else if (i < MAX_DELETE_RETRIES) {
                        logManager.addDebugLog("Retrying delete: $fileName")
                        delay(DELETE_RETRY_DELAY_MS)
                    }
                }
            } finally {
                if (!success) {
                    logManager.addLog("Delete failed after retries: $fileName", LogLevel.ERROR)
                }
                _currentOperation.value = BleOperation.IDLE
                logManager.addDebugLog("Delete operation finished for $fileName")
            }
        }
    }
}