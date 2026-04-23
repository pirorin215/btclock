package com.pirorin215.btclockmob.service

import android.content.Context
import com.pirorin215.btclockmob.data.DEFAULT_AI_SYSTEM_PROMPT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Service for interacting with Gemini API via REST API
 * This bypasses SDK limitations and allows direct HTTP calls
 */
class GeminiRestService(
    private val context: Context,
    private val apiKey: String,
    private val modelName: String = "gemini-2.0-flash",
    private val enableGoogleSearch: Boolean = true,
    private val systemPrompt: String = DEFAULT_AI_SYSTEM_PROMPT
) {
    private val client: OkHttpClient by lazy {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * Generate an AI response using REST API
     */
    suspend fun generateResponse(transcription: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                // Build the request body
                val requestBody = buildRequestBody(transcription)

                // Create HTTP request
                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey")
                    .post(requestBody)
                    .build()

                // Execute request
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    throw IOException("HTTP ${response.code}: $errorBody")
                }

                val responseBody = response.body?.string() ?: "No response"
                val text = parseResponse(responseBody)

                Result.success(text)
            } catch (e: Exception) {
                e.printStackTrace()
                Result.failure(e)
            }
        }
    }

    /**
     * Build the request body for the API call
     */
    private fun buildRequestBody(transcription: String): okhttp3.RequestBody {
        val json = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            // Combine system prompt with user input
                            val prompt = "$systemPrompt\n\n$transcription"
                            put("text", prompt)
                        })
                    })
                })
            })

            // Add Google Search grounding if enabled
            if (enableGoogleSearch) {
                put("tools", JSONArray().apply {
                    put(JSONObject().apply {
                        put("google_search", JSONObject())
                    })
                })
            }
        }

        return json.toString().toRequestBody(JSON_MEDIA_TYPE)
    }

    /**
     * Parse the API response
     */
    private fun parseResponse(responseBody: String): String {
        val json = JSONObject(responseBody)

        // Extract the text from the response
        val candidates = json.optJSONArray("candidates")
        if (candidates != null && candidates.length() > 0) {
            val firstCandidate = candidates.getJSONObject(0)
            val content = firstCandidate.optJSONObject("content")
            if (content != null) {
                val parts = content.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    val firstPart = parts.getJSONObject(0)
                    val text = firstPart.optString("text")
                    if (text.isNotEmpty()) {
                        // Check if grounding was used
                        val groundingMetadata = firstCandidate.optJSONObject("groundingMetadata")
                        val searchEntryPoint = firstCandidate.optJSONObject("searchEntryPoint")

                        // Add Google Search indicator if grounding was actually used
                        val groundingIndicator = if (enableGoogleSearch &&
                            (groundingMetadata != null || searchEntryPoint != null)) {
                            "\n\n🔍 (Google検索を使用)"
                        } else if (enableGoogleSearch) {
                            "\n\n🤖 (AI応答)"
                        } else {
                            ""
                        }

                        return "$text$groundingIndicator"
                    }
                }
            }
        }

        return "応答が生成されませんでした"
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
                val testRequestBody = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", "test")
                                })
                            })
                        })
                    })
                }

                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey")
                    .post(testRequestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(IOException("API key validation failed: ${response.code}"))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Result.failure(e)
            }
        }
    }

    /**
     * Verify that a specific model name is valid
     */
    suspend fun verifyModel(modelName: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("API key is empty"))
            }
            if (modelName.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Model name is empty"))
            }
            try {
                val testRequestBody = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", "test")
                                })
                            })
                        })
                    })
                }

                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey")
                    .post(testRequestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(IOException("Model validation failed: ${response.code}"))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Result.failure(e)
            }
        }
    }

    /**
     * Check if Google Search Grounding is enabled
     */
    fun isGoogleSearchEnabled(): Boolean = enableGoogleSearch

    companion object {
        /**
         * List of known valid Gemini model versions
         * Users can select versions in 0.5 increments, but not all may be valid
         */
        val KNOWN_VERSIONS = listOf(1.0f, 1.5f, 2.0f, 2.5f)
    }
}
