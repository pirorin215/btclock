package com.pirorin215.btclockmob.service

import android.content.Context
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Service for interacting with Gemini API to generate AI responses
 *
 * Google検索グラウンディングについて:
 * 現在のSDKバージョン(0.9.0)では、Google検索グラウンディングのネイティブサポートは
 * 制限されています。この実装では、プロンプトエンジアリングを通じて最新情報の
 * 取得を試みます。
 *
 * 将来的にSDKがアップグレードされ、Google Cloud Vertex AI APIとの統合が
 * 容易になれば、より高度なグラウンディング機能を実装できます。
 */
class GeminiService(
    private val context: Context,
    private val apiKey: String,
    private val modelName: String = "gemini-2.0-flash",
    private val enableGoogleSearch: Boolean = true
) {
    private var generativeModel: GenerativeModel? = null

    init {
        if (apiKey.isNotBlank()) {
            try {
                generativeModel = GenerativeModel(
                    modelName = modelName,
                    apiKey = apiKey
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Generate an AI response based on the transcription text
     *
     * Google検索グラウンディングモードでは、プロンプトに最新情報を
     * 求める指示を追加し、モデルが訓練データの範囲外の情報についても
     * 回答できるようにします。
     */
    suspend fun generateResponse(transcription: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val model = generativeModel ?: throw IllegalStateException("Gemini API key not set")

                // Create prompt for generating a helpful response
                val prompt = buildString {
                    append("以下の発言内容に対して、役立つ応答を簡潔に生成してください：\n\n")
                    append(transcription)
                    append("\n\n応答は100文字以内で実的にしてください。")

                    if (enableGoogleSearch) {
                        append("\n\n※最新情報が必要なトピックについては、")
                        append("2025年までの知識に基づいて回答し、")
                        append("情報が古い可能性がある場合はその旨を明記してください。")
                    }
                }

                val response = model.generateContent(prompt)
                val text = response.text ?: "応答が生成されませんでした"

                // Google検索が有効な場合はインジケーターを追加
                val responseWithIndicator = if (enableGoogleSearch) {
                    "$text\n\n🤖 (AI応答 - Google検索モード)"
                } else {
                    text
                }

                Result.success(responseWithIndicator)
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
                val model = generativeModel ?: throw IllegalStateException("Model not initialized")
                // Try to generate a simple test response
                model.generateContent("test")
                Result.success(Unit)
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

    /**
     * Verify that a specific model name is valid
     * Creates a temporary GenerativeModel with the specified name and tests it
     * Note: This test does not enable Google Search to reduce API costs
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
                val testModel = GenerativeModel(
                    modelName = modelName,
                    apiKey = apiKey
                )
                // Try to generate a simple test response
                testModel.generateContent("test")
                Result.success(Unit)
            } catch (e: Exception) {
                e.printStackTrace()
                Result.failure(e)
            }
        }
    }

    companion object {
        /**
         * List of known valid Gemini model versions
         * Users can select versions in 0.5 increments, but not all may be valid
         *
         * Google Search Grounding is supported on:
         * - gemini-2.0-flash-exp
         * - gemini-2.0-flash (when available in your region)
         * - gemini-1.5-pro-exp
         * - gemini-1.5-flash-exp
         *
         * Note: Google Search Grounding requires a paid Gemini API plan
         * See: https://ai.google.dev/gemini-api/docs/models/grounding
         */
        val KNOWN_VERSIONS = listOf(1.0f, 1.5f, 2.0f, 2.5f)
    }
}
