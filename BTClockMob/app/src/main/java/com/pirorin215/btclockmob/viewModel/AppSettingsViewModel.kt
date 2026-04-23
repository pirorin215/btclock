package com.pirorin215.btclockmob.viewModel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pirorin215.btclockmob.data.AppSettingsRepository
import com.pirorin215.btclockmob.data.Settings
import com.pirorin215.btclockmob.data.TranscriptionProvider
import com.pirorin215.btclockmob.data.AIProvider
import com.pirorin215.btclockmob.data.ProviderMode
import com.pirorin215.btclockmob.data.GeminiModel
import com.pirorin215.btclockmob.data.DEFAULT_AI_SYSTEM_PROMPT
import com.pirorin215.btclockmob.service.SpeechToTextService
import com.pirorin215.btclockmob.service.GroqSpeechService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted // Add this import
import kotlinx.coroutines.flow.stateIn // Add this import
import com.pirorin215.btclockmob.data.ThemeMode // Add this import

enum class ApiKeyStatus {
    VALID,
    INVALID,
    EMPTY,
    CHECKING,
    UNKNOWN_ERROR
}

enum class ModelValidationStatus {
    VALID,
    INVALID,
    CHECKING,
    NOT_CHECKED,
    ERROR
}

class AppSettingsViewModel(
    private val appSettingsRepository: AppSettingsRepository,
    private val transcriptionManager: TranscriptionManager, // Added
    private val application: Application
) : ViewModel() {

    private val _apiKeyStatus = MutableStateFlow(ApiKeyStatus.CHECKING)
    val apiKeyStatus: StateFlow<ApiKeyStatus> = _apiKeyStatus.asStateFlow()

    private val _modelValidationStatus = MutableStateFlow<ModelValidationStatus>(ModelValidationStatus.NOT_CHECKED)
    val modelValidationStatus: StateFlow<ModelValidationStatus> = _modelValidationStatus.asStateFlow()

    private val _modelValidationError = MutableStateFlow<String?>(null)
    val modelValidationError: StateFlow<String?> = _modelValidationError.asStateFlow()

    val apiKey: StateFlow<String> = appSettingsRepository.getFlow(Settings.API_KEY)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    val geminiApiKey: StateFlow<String> = appSettingsRepository.getFlow(Settings.GEMINI_API_KEY)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    val groqApiKey: StateFlow<String> = appSettingsRepository.getFlow(Settings.GROQ_API_KEY)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    val transcriptionProvider: StateFlow<TranscriptionProvider> = appSettingsRepository.getFlow(Settings.TRANSCRIPTION_PROVIDER)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = TranscriptionProvider.GOOGLE
        )

    val aiProvider: StateFlow<AIProvider> = appSettingsRepository.getFlow(Settings.AI_PROVIDER)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AIProvider.GEMINI
        )

    val providerMode: StateFlow<ProviderMode> = appSettingsRepository.getFlow(Settings.PROVIDER_MODE)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ProviderMode.GCP
        )

    val geminiModel: StateFlow<GeminiModel> = appSettingsRepository.getFlow(Settings.GEMINI_MODEL)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = GeminiModel()
        )

    val transcriptionCacheLimit: StateFlow<Int> = appSettingsRepository.getFlow(Settings.TRANSCRIPTION_CACHE_LIMIT)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 100 // Default to 100 files
        )

    val transcriptionFontSize: StateFlow<Int> = appSettingsRepository.getFlow(Settings.TRANSCRIPTION_FONT_SIZE)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 14 // Default to 14
        )

    val themeMode: StateFlow<ThemeMode> = appSettingsRepository.getFlow(Settings.THEME_MODE)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ThemeMode.SYSTEM // Default to SYSTEM
        )

    val googleTodoListName: StateFlow<String> = appSettingsRepository.getFlow(Settings.GOOGLE_TODO_LIST_NAME)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "btclock"
        )

    val googleTasksMode: StateFlow<com.pirorin215.btclockmob.data.GoogleTasksMode> = appSettingsRepository.getFlow(Settings.GOOGLE_TASKS_MODE)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = com.pirorin215.btclockmob.data.GoogleTasksMode.OAUTH
        )

    val googleTaskTitleLength: StateFlow<Int> = appSettingsRepository.getFlow(Settings.GOOGLE_TASK_TITLE_LENGTH)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 20 // Default to 20
        )

    val googleTasksSyncIntervalMinutes: StateFlow<Int> = appSettingsRepository.getFlow(Settings.GOOGLE_TASKS_SYNC_INTERVAL_MINUTES)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 5 // Default to 5 minutes
        )

    val autoStartOnBoot: StateFlow<Boolean> = appSettingsRepository.getFlow(Settings.AUTO_START_ON_BOOT)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val chunkBurstSize: StateFlow<Int> = appSettingsRepository.getFlow(Settings.CHUNK_BURST_SIZE)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 8 // Default to 8
        )

    val voltageRetryCount: StateFlow<Int> = appSettingsRepository.getFlow(Settings.VOLTAGE_RETRY_COUNT)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 3 // Default to 3
        )

    val voltageAcquisitionInterval: StateFlow<Int> = appSettingsRepository.getFlow(Settings.VOLTAGE_ACQUISITION_INTERVAL)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 100 // Default to 100ms
        )

    val lowVoltageThreshold: StateFlow<Float> = appSettingsRepository.getFlow(Settings.LOW_VOLTAGE_THRESHOLD)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 3.61f // Default to 3.61V
        )

    val lowVoltageNotifyEveryTime: StateFlow<Boolean> = appSettingsRepository.getFlow(Settings.LOW_VOLTAGE_NOTIFY_EVERY_TIME)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false // Default to first time only
        )

    val transcriptionNotificationEnabled: StateFlow<Boolean> = appSettingsRepository.getFlow(Settings.TRANSCRIPTION_NOTIFICATION_ENABLED)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false // Default to OFF
        )

    val geminiEnableGoogleSearch: StateFlow<Boolean> = appSettingsRepository.getFlow(Settings.GEMINI_ENABLE_GOOGLE_SEARCH)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true // Default to true (enable Google Search Grounding)
        )

    val geminiSystemPrompt: StateFlow<String> = appSettingsRepository.getFlow(Settings.GEMINI_SYSTEM_PROMPT)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_AI_SYSTEM_PROMPT
        )

    val gasWebhookUrl: StateFlow<String> = appSettingsRepository.getFlow(Settings.GAS_WEBHOOK_URL)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    val enableGoogleTaskDue: StateFlow<Boolean> = appSettingsRepository.getFlow(Settings.ENABLE_GOOGLE_TASK_DUE)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true // Default to true
        )

    fun saveApiKey(apiKey: String) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.API_KEY, apiKey)
        }
    }

    fun saveGeminiApiKey(apiKey: String) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.GEMINI_API_KEY, apiKey)
        }
    }

    fun saveGeminiSystemPrompt(prompt: String) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.GEMINI_SYSTEM_PROMPT, prompt)
        }
    }

    fun saveGroqApiKey(apiKey: String) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.GROQ_API_KEY, apiKey)
        }
    }

    fun saveTranscriptionProvider(provider: TranscriptionProvider) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.TRANSCRIPTION_PROVIDER, provider)
        }
    }

    fun saveAIProvider(provider: AIProvider) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.AI_PROVIDER, provider)
        }
    }

    fun saveProviderMode(mode: ProviderMode) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.PROVIDER_MODE, mode)
            // Also update the individual providers to match the mode
            when (mode) {
                ProviderMode.GCP -> {
                    appSettingsRepository.setValue(Settings.TRANSCRIPTION_PROVIDER, TranscriptionProvider.GOOGLE)
                    appSettingsRepository.setValue(Settings.AI_PROVIDER, AIProvider.GEMINI)
                }
                ProviderMode.GROQ -> {
                    appSettingsRepository.setValue(Settings.TRANSCRIPTION_PROVIDER, TranscriptionProvider.GROQ)
                    appSettingsRepository.setValue(Settings.AI_PROVIDER, AIProvider.GROQ)
                }
            }
        }
    }

    fun saveGeminiModel(model: GeminiModel) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.GEMINI_MODEL, model)
        }
    }

    fun saveTranscriptionCacheLimit(limit: Int) {
        viewModelScope.launch {
            val cacheLimit = if (limit < 1) 1 else limit
            appSettingsRepository.setValue(Settings.TRANSCRIPTION_CACHE_LIMIT, cacheLimit)
        }
    }

    fun saveTranscriptionFontSize(size: Int) {
        viewModelScope.launch {
            val fontSize = size.coerceIn(10, 24)
            appSettingsRepository.setValue(Settings.TRANSCRIPTION_FONT_SIZE, fontSize)
        }
    }

    fun saveThemeMode(themeMode: ThemeMode) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.THEME_MODE, themeMode)
        }
    }

    fun saveGoogleTodoListName(name: String) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.GOOGLE_TODO_LIST_NAME, name)
        }
    }

    fun saveGoogleTasksMode(mode: com.pirorin215.btclockmob.data.GoogleTasksMode) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.GOOGLE_TASKS_MODE, mode)
        }
    }

    fun saveGoogleTaskTitleLength(length: Int) {
        viewModelScope.launch {
            val titleLength = length.coerceIn(5, 50) // Example bounds: 5 to 50 characters
            appSettingsRepository.setValue(Settings.GOOGLE_TASK_TITLE_LENGTH, titleLength)
        }
    }

    fun saveGoogleTasksSyncIntervalMinutes(minutes: Int) {
        viewModelScope.launch {
            val interval = minutes.coerceAtLeast(1) // Ensure at least 1 minute
            appSettingsRepository.setValue(Settings.GOOGLE_TASKS_SYNC_INTERVAL_MINUTES, interval)
        }
    }

    fun saveAutoStartOnBoot(enable: Boolean) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.AUTO_START_ON_BOOT, enable)
        }
    }

    fun saveChunkBurstSize(size: Int) {
        viewModelScope.launch {
            val burstSize = size.coerceAtLeast(1) // Ensure at least 1
            appSettingsRepository.setValue(Settings.CHUNK_BURST_SIZE, burstSize)
        }
    }

    fun saveVoltageRetryCount(count: Int) {
        viewModelScope.launch {
            val retryCount = count.coerceIn(1, 10) // 1〜10回の範囲に制限
            appSettingsRepository.setValue(Settings.VOLTAGE_RETRY_COUNT, retryCount)
        }
    }

    fun saveVoltageAcquisitionInterval(interval: Int) {
        viewModelScope.launch {
            val acquisitionInterval = interval.coerceAtLeast(1) // 1ms以上に制限
            appSettingsRepository.setValue(Settings.VOLTAGE_ACQUISITION_INTERVAL, acquisitionInterval)
        }
    }

    fun saveLowVoltageThreshold(threshold: Float) {
        viewModelScope.launch {
            val safeThreshold = threshold.coerceAtLeast(0f) // 0以上に制限 (0は通知OFF)
            appSettingsRepository.setValue(Settings.LOW_VOLTAGE_THRESHOLD, safeThreshold)
        }
    }

    fun saveLowVoltageNotifyEveryTime(everyTime: Boolean) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.LOW_VOLTAGE_NOTIFY_EVERY_TIME, everyTime)
        }
    }

    fun saveTranscriptionNotificationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.TRANSCRIPTION_NOTIFICATION_ENABLED, enabled)
        }
    }

    fun saveGasWebhookUrl(url: String) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.GAS_WEBHOOK_URL, url.trim())
        }
    }

    fun saveEnableGoogleTaskDue(enable: Boolean) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.ENABLE_GOOGLE_TASK_DUE, enable)
        }
    }

    fun saveGeminiEnableGoogleSearch(enable: Boolean) {
        viewModelScope.launch {
            appSettingsRepository.setValue(Settings.GEMINI_ENABLE_GOOGLE_SEARCH, enable)
        }
    }

    private var lastCheckedApiKey: String = ""

    init {
        viewModelScope.launch {
            // Observe API key changes and trigger status check
            appSettingsRepository.getFlow(Settings.API_KEY).collect { currentApiKey ->
                if (currentApiKey != lastCheckedApiKey) {
                    // APIキーが変更されたら、検証済みステータスをリセット
                    appSettingsRepository.setValue(Settings.IS_API_KEY_VERIFIED, false)
                    lastCheckedApiKey = currentApiKey
                }
                checkApiKeyStatus()
            }
        }
    }

    fun checkApiKeyStatus() {
        viewModelScope.launch {
            _apiKeyStatus.value = ApiKeyStatus.CHECKING
            val apiKey = appSettingsRepository.getFlow(Settings.API_KEY).first() // Get current API key

            if (apiKey.isEmpty()) {
                _apiKeyStatus.value = ApiKeyStatus.EMPTY
                appSettingsRepository.setValue(Settings.IS_API_KEY_VERIFIED, false) // APIキーがない場合は未検証に
                return@launch
            }

            val isVerifiedInCache = appSettingsRepository.getFlow(Settings.IS_API_KEY_VERIFIED).first()
            if (isVerifiedInCache && apiKey == lastCheckedApiKey) { // lastCheckedApiKeyでAPIキーの変更がないことを確認
                _apiKeyStatus.value = ApiKeyStatus.VALID
                return@launch
            }

            // APIキーが変更されたか、または未検証の場合はネットワーク経由でチェック
            val speechToTextService = SpeechToTextService(application, apiKey)
            val result = speechToTextService.verifyApiKey()

            _apiKeyStatus.value = if (result.isSuccess) {
                appSettingsRepository.setValue(Settings.IS_API_KEY_VERIFIED, true)
                ApiKeyStatus.VALID
            } else {
                appSettingsRepository.setValue(Settings.IS_API_KEY_VERIFIED, false)
                // ここでエラーの種類をもう少し細かく分類することも可能だが、一旦INVALIDとする
                ApiKeyStatus.INVALID
            }
        }
    }

    /**
     * Verify if the specified Gemini model is valid
     * @param modelName The model name to verify (e.g., "gemini-2.0-flash")
     */
    suspend fun verifyGeminiModel(modelName: String): Result<Unit> {
        _modelValidationStatus.value = ModelValidationStatus.CHECKING
        _modelValidationError.value = null

        val apiKey = appSettingsRepository.getFlow(Settings.GEMINI_API_KEY).first()

        if (apiKey.isEmpty()) {
            _modelValidationStatus.value = ModelValidationStatus.ERROR
            _modelValidationError.value = "Gemini APIキーが設定されていません"
            return Result.failure(IllegalStateException("API key is empty"))
        }

        if (modelName.isEmpty()) {
            _modelValidationStatus.value = ModelValidationStatus.ERROR
            _modelValidationError.value = "モデル名が空です"
            return Result.failure(IllegalArgumentException("Model name is empty"))
        }

        return try {
            val geminiService = com.pirorin215.btclockmob.service.GeminiService(
                context = application.applicationContext,
                apiKey = apiKey,
                modelName = modelName
            )
            val result = geminiService.verifyModel(modelName)

            if (result.isSuccess) {
                _modelValidationStatus.value = ModelValidationStatus.VALID
                _modelValidationError.value = null
                Result.success(Unit)
            } else {
                val error = result.exceptionOrNull()
                val errorMessage = when {
                    error?.message?.contains("NOT_FOUND") == true ||
                    error?.message?.contains("not found") == true -> "モデル '${modelName}' は存在しません。\n別のモデルを選択してください。"
                    error?.message?.contains("API key") == true -> "APIキーに問題があります。\nモデル: ${modelName}"
                    else -> "モデル '${modelName}' の検証に失敗しました。\n${error?.message}"
                }
                _modelValidationStatus.value = ModelValidationStatus.INVALID
                _modelValidationError.value = errorMessage
                Result.failure(error ?: Exception("Unknown error"))
            }
        } catch (e: Exception) {
            val errorMessage = "モデル '${modelName}' の検証中にエラーが発生しました。\n${e.message}"
            _modelValidationStatus.value = ModelValidationStatus.ERROR
            _modelValidationError.value = errorMessage
            Result.failure(e)
        }
    }

    fun resetModelValidationStatus() {
        _modelValidationStatus.value = ModelValidationStatus.NOT_CHECKED
        _modelValidationError.value = null
    }
}


