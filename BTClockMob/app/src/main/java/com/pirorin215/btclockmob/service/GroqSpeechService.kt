package com.pirorin215.btclockmob.service

import android.content.Context
import com.pirorin215.btclockmob.data.FileUtil
import com.pirorin215.btclockmob.adpcm.AdpcmDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class GroqSpeechService(private val context: Context, private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.NONE
        })
        .build()

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    suspend fun transcribeFile(filePath: String): Result<String> {
        return withContext(Dispatchers.IO) {
            var tempPcmFile: File? = null
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    return@withContext Result.failure(Exception("File not found: $filePath"))
                }

                // If the audio is 4-bit ADPCM, decode it to 16-bit PCM first
                val audioFileToTranscribe: File

                // Check if it's ADPCM by reading the format tag
                val header = file.readBytes()
                val audioFormat = ByteArray(2)
                System.arraycopy(header, 20, audioFormat, 0, 2)
                val formatTag = (audioFormat[0].toInt() and 0xFF) or ((audioFormat[1].toInt() and 0xFF) shl 8)

                // IMA ADPCM format tag is 0x0011
                if (formatTag == 0x0011) {
                    tempPcmFile = FileUtil.getTempPcmFile(context, file.name)
                    val adpcmDecoder = AdpcmDecoder()
                    val decodeSuccess = adpcmDecoder.decodeToPCM(
                        file.absolutePath,
                        tempPcmFile.absolutePath,
                        null
                    )

                    if (!decodeSuccess) {
                        return@withContext Result.failure(Exception("ADPCM to PCM decoding failed for ${file.name}"))
                    }
                    audioFileToTranscribe = tempPcmFile
                } else {
                    audioFileToTranscribe = file
                }

                // Groq Whisper API expects multipart/form-data with the audio file
                val audioBytes = audioFileToTranscribe.readBytes()

                // Create multipart request body
                val requestBody = okhttp3.MultipartBody.Builder()
                    .setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("file", audioFileToTranscribe.name,
                        audioBytes.toRequestBody("audio/wav".toMediaType())
                    )
                    .addFormDataPart("model", "whisper-large-v3-turbo")
                    .addFormDataPart("language", "ja")
                    .addFormDataPart("prompt", "これは短い音声メモです。")
                    .addFormDataPart("response_format", "text")
                    .build()

                val request = Request.Builder()
                    .url("https://api.groq.com/openai/v1/audio/transcriptions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    return@withContext Result.failure(Exception("Groq API error: ${response.code} - $errorBody"))
                }

                val transcription = response.body?.string() ?: ""
                val trimmed = transcription.trim()

                if (trimmed.isNotBlank()) {
                    if (isHallucination(trimmed)) {
                        Result.success("")
                    } else {
                        Result.success(trimmed)
                    }
                } else {
                    Result.failure(Exception("Transcription result is empty."))
                }

            } catch (e: Exception) {
                e.printStackTrace()
                Result.failure(e)
            } finally {
                tempPcmFile?.delete()
            }
        }
    }

    suspend fun verifyApiKey(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            if (apiKey.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("API key is empty."))
            }

            try {
                // Create a minimal valid WAV file (1ms of silence at 16kHz)
                val sampleRate = 16000
                val numSamples = sampleRate / 1000 // 1ms
                val dataSize = numSamples * 2 // 16-bit = 2 bytes per sample

                // WAV header (44 bytes)
                val header = ByteArray(44)
                val headerBuffer = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)

                // RIFF header
                headerBuffer.put(0, 'R'.code.toByte())
                headerBuffer.put(1, 'I'.code.toByte())
                headerBuffer.put(2, 'F'.code.toByte())
                headerBuffer.put(3, 'F'.code.toByte())
                headerBuffer.putInt(4, 36 + dataSize) // File size
                headerBuffer.put(8, 'W'.code.toByte())
                headerBuffer.put(9, 'A'.code.toByte())
                headerBuffer.put(10, 'V'.code.toByte())
                headerBuffer.put(11, 'E'.code.toByte())

                // fmt chunk
                headerBuffer.put(12, 'f'.code.toByte())
                headerBuffer.put(13, 'm'.code.toByte())
                headerBuffer.put(14, 't'.code.toByte())
                headerBuffer.put(15, ' '.code.toByte())
                headerBuffer.putInt(16, 16) // fmt chunk size
                headerBuffer.put(20, 1) // Audio format (PCM)
                headerBuffer.put(21, 0)
                headerBuffer.put(22, 1) // Number of channels (mono)
                headerBuffer.put(23, 0)
                headerBuffer.putInt(24, sampleRate) // Sample rate
                headerBuffer.putInt(28, sampleRate * 2) // Byte rate
                headerBuffer.put(32, 2) // Block align
                headerBuffer.put(33, 0)
                headerBuffer.put(34, 16) // Bits per sample
                headerBuffer.put(35, 0)

                // data chunk
                headerBuffer.put(36, 'd'.code.toByte())
                headerBuffer.put(37, 'a'.code.toByte())
                headerBuffer.put(38, 't'.code.toByte())
                headerBuffer.put(39, 'a'.code.toByte())
                headerBuffer.putInt(40, dataSize) // Data size

                // Add silence data
                val audioData = ByteArray(dataSize)
                val wavFile = header + audioData

                val requestBody = okhttp3.MultipartBody.Builder()
                    .setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("file", "test.wav",
                        wavFile.toRequestBody("audio/wav".toMediaType())
                    )
                    .addFormDataPart("model", "whisper-large-v3-turbo")
                    .addFormDataPart("language", "en")
                    .addFormDataPart("prompt", "This is a short voice note. If you cannot hear anything, do not output anything.")
                    .addFormDataPart("response_format", "text")
                    .build()

                val request = Request.Builder()
                    .url("https://api.groq.com/openai/v1/audio/transcriptions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    when (response.code) {
                        401 -> Result.failure(Exception("API key authentication failed."))
                        403 -> Result.failure(Exception("API key authentication failed."))
                        else -> Result.failure(Exception("API request failed: ${response.code} - $errorBody"))
                    }
                } else {
                    Result.success(Unit)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                Result.failure(e)
            }
        }
    }

    companion object {
        private val HALLUCINATION_PHRASES = listOf(
            "ご視聴ありがとうございました",
            "視聴ありがとうございました",
            "ご視聴いただきありがとうございました",
            "チャンネル登録",
            "高評価",
            "チャンネル登録よろしくお願いします",
            "高評価よろしくお願いします",
            "Thanks for watching",
            "Thank you for watching",
            "Please subscribe",
            "Subtitles by",
            "Subtitle by",
            "Transcription",
            "transcription",
            "Caption",
            "caption"
        )

        internal fun isHallucination(text: String): Boolean {
            val normalized = text.trim()
                .lowercase()
                .replace(Regex("^[（(]"), "")
                .replace(Regex("[）)]$"), "")
                .replace(Regex("[。．.！!？\\?]+$"), "")

            if (normalized.isEmpty()) return false
            // Hallucinations are typically short boilerplate phrases
            if (normalized.length > 60) return false

            return HALLUCINATION_PHRASES.any { phrase ->
                val normalizedPhrase = phrase.lowercase()
                    .replace(Regex("[。．.！!？\\?]+$"), "")

                // Check for exact match or if the output is just a slightly modified version of the boilerplate
                if (normalized == normalizedPhrase) return@any true

                // For English common hallucinations, they often appear at the start or end of the text
                if (phrase.any { it in 'a'..'z' }) {
                    normalized.contains(normalizedPhrase) && normalized.length < normalizedPhrase.length + 20
                } else {
                    // For Japanese, we are more careful with "contains"
                    normalized.contains(normalizedPhrase) && normalized.length < normalizedPhrase.length + 10
                }
            }
        }
    }
}
