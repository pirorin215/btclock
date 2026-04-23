package com.pirorin215.btclockmob.service

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Service for interacting with Groq's LLM API to generate AI responses
 * Uses Groq's fast inference platform with models like Llama 3, Mixtral, etc.
 */
class GroqLLMService(
    private val context: Context,
    private val apiKey: String
) {
    private val client: OkHttpClient
    private val gson = com.google.gson.Gson()

    // Default model: Llama 3.1 8B (fast, good for Japanese)
    private val model = "llama-3.1-8b-instant"

    init {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.NONE
        }

        client = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Generate an AI response based on the transcription text
     */
    suspend fun generateResponse(transcription: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                if (apiKey.isBlank()) {
                    throw IllegalStateException("Groq API key not set")
                }

                // Create prompt for generating a helpful response
                val systemPrompt = "あなたは小さな表示画面向けのAIアシスタントです。結論から簡潔に答えてください。" +
                    "必ず答え・結論から書き始め、前置きを省き、100文字以内で応答してください。" +
                    "必要に応じて結論の後に補足を追加できます。"

                val requestBody = JSONObject().apply {
                    put("model", model)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", systemPrompt)
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", "以下の発言内容に対して、役立つ応答を簡潔に生成してください：\n\n$transcription")
                        })
                    })
                    put("temperature", 0.7)
                    put("max_tokens", 150)
                }.toString()

                val request = Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    throw IOException("Groq API error: ${response.code} - $errorBody")
                }

                val responseBody = response.body?.string()
                    ?: throw IOException("Empty response from Groq API")

                val jsonResponse = JSONObject(responseBody)
                val choices = jsonResponse.getJSONArray("choices")
                if (choices.length() == 0) {
                    throw IOException("No response generated")
                }

                val message = choices.getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")

                Result.success(message.trim())
            } catch (e: Exception) {
                e.printStackTrace()
                Result.failure(e)
            }
        }
    }

    /**
     * Verify that the API key is valid
     */
    suspend fun verifyApiKey(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("API key is empty"))
            }
            try {
                val requestBody = JSONObject().apply {
                    put("model", model)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", "test")
                        })
                    })
                    put("max_tokens", 5)
                }.toString()

                val request = Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    throw IOException("API key validation failed: ${response.code}")
                }

                Result.success(Unit)
            } catch (e: Exception) {
                e.printStackTrace()
                Result.failure(e)
            }
        }
    }
}
