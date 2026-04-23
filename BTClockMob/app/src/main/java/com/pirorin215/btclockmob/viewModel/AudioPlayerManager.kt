package com.pirorin215.btclockmob.viewModel

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.util.Log // Added import for Log
import com.pirorin215.btclockmob.adpcm.AdpcmDecoder
import com.pirorin215.btclockmob.data.FileUtil


class AudioPlayerManager(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private val _currentlyPlayingFile = MutableStateFlow<String?>(null)
    val currentlyPlayingFile: StateFlow<String?> = _currentlyPlayingFile.asStateFlow()

    private var currentTempPcmFile: File? = null

    // Helper to read WAV header
    private fun readWavHeader(file: File): Pair<Int, Short> {
        val fis = FileInputStream(file)
        try {
            val header = ByteArray(44)
            fis.read(header)

            val sampleRateBuffer = ByteBuffer.wrap(header, 24, 4).order(ByteOrder.LITTLE_ENDIAN)
            val sampleRate = sampleRateBuffer.int

            val bitDepthBuffer = ByteBuffer.wrap(header, 34, 2).order(ByteOrder.LITTLE_ENDIAN)
            val bitDepth = bitDepthBuffer.short

            return Pair(sampleRate, bitDepth)
        } finally {
            fis.close()
        }
    }

    fun play(filePath: String, onCompletion: () -> Unit) {
        if (mediaPlayer != null && mediaPlayer?.isPlaying == true) {
            stop()
        }

        var fileToPlayPath = filePath
        var originalFile: File? = null

        try {
            originalFile = File(filePath)
            val (_, bitDepth) = readWavHeader(originalFile)

            if (bitDepth.toInt() == 4) { // ADPCM file
                currentTempPcmFile = FileUtil.getTempPcmFile(context, originalFile.name)

                // 再生用のPCMファイルがすでに存在し、有効かチェック
                if (currentTempPcmFile?.exists() == true && currentTempPcmFile!!.length() > 44) { // 44はWAVヘッダの最小サイズ
                    fileToPlayPath = currentTempPcmFile!!.absolutePath
                } else {
                    // 存在しない場合はデコードを実行
                    val adpcmDecoder = AdpcmDecoder()
                    val decodeSuccess = adpcmDecoder.decodeToPCM(
                        originalFile.absolutePath,
                        currentTempPcmFile!!.absolutePath,
                        null // No progress listener for now
                    )

                    if (!decodeSuccess) {
                        // Handle decoding failure
                        _currentlyPlayingFile.value = null
                        currentTempPcmFile?.delete() // Attempt to clean up
                        currentTempPcmFile = null
                        return
                    }
                    fileToPlayPath = currentTempPcmFile!!.absolutePath
                }
            }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(fileToPlayPath)
                setOnCompletionListener {
                    Log.d("AudioPlayerManager", "Playback completed for: $fileToPlayPath")
                    onCompletion()
                }
                prepare()
                start()
                _currentlyPlayingFile.value = filePath // Still report original file playing
            }
        } catch (e: IOException) {
            _currentlyPlayingFile.value = null
            currentTempPcmFile?.delete() // Attempt to clean up
            currentTempPcmFile = null
            // Handle exception
        }
    }

    fun stop() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.reset()
            it.release()
        }
        mediaPlayer = null
        currentTempPcmFile?.delete()
        currentTempPcmFile = null
    }

    fun clearPlayingState() {
        _currentlyPlayingFile.value = null
        Log.d("AudioPlayerManager", "Playing state cleared.")
    }

    fun release() {
        stop()
        clearPlayingState()
    }
}
