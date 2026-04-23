package com.pirorin215.btclockmob.usecase

import android.app.Application
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.pirorin215.btclockmob.data.AppSettingsRepository
import com.pirorin215.btclockmob.data.FileUtil
import com.pirorin215.btclockmob.data.Settings
import com.pirorin215.btclockmob.data.Task
import com.pirorin215.btclockmob.data.TaskList
import com.pirorin215.btclockmob.data.TranscriptionResult
import com.pirorin215.btclockmob.data.TranscriptionResultRepository
import com.pirorin215.btclockmob.network.GoogleTasksApiService
import com.pirorin215.btclockmob.network.RetrofitClient
import com.pirorin215.btclockmob.network.GASTasksApiService
import com.pirorin215.btclockmob.network.GASRetrofitClient
import com.pirorin215.btclockmob.network.GASAddTaskRequest
import com.pirorin215.btclockmob.viewModel.LogManager
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class GoogleTasksUseCase(
    private val application: Application,
    private val appSettingsRepository: AppSettingsRepository,
    private val transcriptionResultRepository: TranscriptionResultRepository,
    private val context: Context,
    private val scope: CoroutineScope, // Added CoroutineScope
    private val logManager: LogManager // Added LogManager
) {
    private val _account = MutableStateFlow<GoogleSignInAccount?>(null)
    val account: StateFlow<GoogleSignInAccount?> = _account.asStateFlow()

    private val _isLoadingGoogleTasks = MutableStateFlow(false)
    val isLoadingGoogleTasks: StateFlow<Boolean> = _isLoadingGoogleTasks.asStateFlow()

    val googleSignInClient: GoogleSignInClient

    private var taskListId: String? = null
    private val tasksScope = "https://www.googleapis.com/auth/tasks"

    private val apiService: GoogleTasksApiService by lazy {
        RetrofitClient.create {
            val account = _account.value ?: throw IllegalStateException("User not signed in for Google Tasks API.")
            GoogleAuthUtil.getToken(application, account.account!!, "oauth2:$tasksScope")
        }
    }

    private val gasApiService: GASTasksApiService by lazy {
        GASRetrofitClient.create()
    }

    init {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(tasksScope))
            .build()
        googleSignInClient = GoogleSignIn.getClient(application, gso)

        _account.value = GoogleSignIn.getLastSignedInAccount(application)
        if (_account.value != null) {
            logManager.addLog("Signed in to Google Tasks: ${_account.value?.displayName}")
        }

        // Observe changes to the Google Todo list name and clear the cached taskListId
        appSettingsRepository.getFlow(Settings.GOOGLE_TODO_LIST_NAME).onEach {
            taskListId = null // Clear cached taskListId so it's re-fetched
            logManager.addDebugLog("Google task list cache cleared")
        }.launchIn(scope)
    }

    private suspend fun createGoogleTaskList(listName: String): String? {
        if (_account.value == null || listName.isBlank()) return null
        val taskList = TaskList(id = "", title = listName) // ID is ignored for creation
        return try {
            val newTaskList = apiService.createTaskList(taskList)
            logManager.addLog("Created new Google Task List: '${newTaskList.title}' (ID: ${newTaskList.id})")
            newTaskList.id
        } catch (e: Exception) {
            logManager.addLog("Error creating Google Task List '$listName': ${e.message}")
            null
        }
    }

    private suspend fun getTaskListId(): String? {
        if (taskListId != null) return taskListId

        val listName = appSettingsRepository.getFlow(Settings.GOOGLE_TODO_LIST_NAME).first()
        if (listName.isBlank()) {
            logManager.addLog("Google Todo List Name is blank. Using '@default'.")
            taskListId = "@default" // Cache the default
            return "@default"
        }
        return try {
            val taskListsResponse = apiService.getTaskLists()
            var foundList = taskListsResponse.items.find { it.title == listName }

            if (foundList == null && listName != "@default") {
                logManager.addLog("Google Task List '$listName' not found. Attempting to create it.")
                val newTaskListId = createGoogleTaskList(listName)
                if (newTaskListId != null) {
                    taskListId = newTaskListId
                    return newTaskListId
                } else {
                    logManager.addLog("Failed to create Google Task List '$listName'. Falling back to default or first available.")
                }
            }

            taskListId = foundList?.id ?: taskListsResponse.items.firstOrNull()?.id // Fallback to first list if user-defined not found/created
            taskListId
        } catch (e: Exception) {
            logManager.addLog("Error getting or creating task lists: ${e.message}")
            null
        }
    }

    /**
     * Calculate due date for today in JST timezone as UTC midnight in RFC3339 format
     * This matches the format required by Google Tasks API
     */
    private fun calculateDueForToday(): String? {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            LocalDate.now(ZoneId.of("Asia/Tokyo"))
                .atStartOfDay()
                .atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        } else {
            val jst = java.util.TimeZone.getTimeZone("Asia/Tokyo")
            val cal = java.util.Calendar.getInstance(jst)
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)

            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            sdf.format(cal.time)
        }
    }

    private suspend fun addGoogleTask(title: String, notes: String?, isCompleted: Boolean, due: String?): Task? {
        if (_account.value == null || title.isBlank()) return null
        val currentTaskListId = taskListId ?: getTaskListId() ?: return null
        val status = if (isCompleted) "completed" else "needsAction"
        val task = Task(title = title, notes = notes, status = status, due = due)
        return try {
            val createdTask = apiService.createTask(currentTaskListId, task)
            logManager.addLog("Added new Google Task: $title")
            createdTask
        } catch (e: Exception) {
            logManager.addLog("Error adding Google Task '$title': ${e.message}")
            null
        }
    }

    /**
     * Add a task via Google Apps Script webhook
     * This method bypasses OAuth and uses a user-provided GAS webhook URL
     */
    private suspend fun addTaskViaGAS(title: String, notes: String?, isCompleted: Boolean, due: String?): String? {
        val gasWebhookUrl = appSettingsRepository.getFlow(Settings.GAS_WEBHOOK_URL).first()
        if (gasWebhookUrl.isBlank()) {
            logManager.addLog("GAS Webhook URL is not configured.")
            return null
        }

        val taskListName = appSettingsRepository.getFlow(Settings.GOOGLE_TODO_LIST_NAME).first()
        val request = GASAddTaskRequest(
            taskListName = taskListName,
            title = title,
            notes = notes,
            isCompleted = isCompleted,
            due = due // Add due date
        )

        return try {
            val response = gasApiService.addTask(gasWebhookUrl, request)
            if (response.success) {
                logManager.addLog("Added task via GAS: $title (ID: ${response.taskId})")
                response.taskId
            } else {
                logManager.addLog("Failed to add task via GAS: ${response.error}")
                null
            }
        } catch (e: Exception) {
            logManager.addLog("Error adding task via GAS: ${e.message}")
            null
        }
    }

    suspend fun syncTranscriptionResultsWithGoogleTasks(audioDirName: String) {
        // Get the sync mode (OAUTH or GAS)
        val googleTasksMode = appSettingsRepository.getFlow(Settings.GOOGLE_TASKS_MODE).first()
        val useGAS = googleTasksMode == com.pirorin215.btclockmob.data.GoogleTasksMode.GAS

        // Check if due date is enabled
        val enableDue = appSettingsRepository.getFlow(Settings.ENABLE_GOOGLE_TASK_DUE).first()

        // Validate that required credentials are available
        if (useGAS) {
            val gasWebhookUrl = appSettingsRepository.getFlow(Settings.GAS_WEBHOOK_URL).first()
            if (gasWebhookUrl.isBlank()) {
                logManager.addLog("GAS mode selected but webhook URL is not configured.")
                return
            }
        } else {
            if (_account.value == null) {
                logManager.addLog("OAuth mode selected but not signed in to Google.")
                return
            }
        }

        _isLoadingGoogleTasks.value = true
        logManager.addLog("Starting Google Tasks synchronization... (mode: ${if (useGAS) "GAS" else "OAuth"}, due: ${if (enableDue) "enabled" else "disabled"})")

        try {
            val localResults = transcriptionResultRepository.transcriptionResultsFlow.first()
            val updatedResults = mutableListOf<TranscriptionResult>()

            for (localResult in localResults) {
                // Skip locally deleted items, do not sync deletion to Google Tasks
                if (localResult.isDeletedLocally) {
                    updatedResults.add(localResult)
                    continue
                }

                // Only add new tasks (googleTaskId == null), skip existing tasks
                if (localResult.googleTaskId == null) {
                    // This is a new item, add it to Google Tasks
                    logManager.addLog("Local result '${localResult.fileName}' has no Google Task ID. Adding to Google Tasks.")

                    // Calculate due date if enabled
                    val dueTime = if (enableDue) calculateDueForToday() else null

                    val taskId = if (useGAS) {
                        // Use GAS webhook
                        addTaskViaGAS(
                            title = localResult.transcription,
                            notes = localResult.googleTaskNotes,
                            isCompleted = localResult.isCompleted,
                            due = dueTime
                        )
                    } else {
                        // Use OAuth
                        val addedTask = addGoogleTask(
                            title = localResult.transcription,
                            notes = localResult.googleTaskNotes,
                            isCompleted = localResult.isCompleted,
                            due = dueTime
                        )
                        addedTask?.id
                    }

                    if (taskId != null) {
                        updatedResults.add(
                            localResult.copy(
                                googleTaskId = taskId,
                                googleTaskUpdated = java.time.Instant.now().toString(),
                                googleTaskDue = dueTime,
                                lastEditedTimestamp = System.currentTimeMillis()
                            )
                        )
                        logManager.addLog("Added local result '${localResult.fileName}' to Google Tasks. New Google ID: $taskId")
                    } else {
                        logManager.addLog("Failed to add local result '${localResult.fileName}' to Google Tasks. Will retry on next sync.")
                        updatedResults.add(localResult)
                    }
                } else {
                    // This item already exists on Google Tasks - skip (no updates)
                    updatedResults.add(localResult)
                }
            }

            // Update the local database with the new state
            transcriptionResultRepository.updateResults(updatedResults)

            logManager.addLog("Google Tasks synchronization completed.")

        } catch (e: Exception) {
            logManager.addLog("Error during Google Tasks synchronization: ${e.message}")
        } finally {
            _isLoadingGoogleTasks.value = false
        }
    }

    fun handleSignInResult(intent: Intent, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(intent)
            _account.value = task.getResult(ApiException::class.java)
            logManager.addLog("Google Sign-In successful for: ${_account.value?.displayName}")
            onSuccess()
        } catch (e: ApiException) {
            logManager.addLog("Google Sign-In failed: ${e.statusCode} - ${e.message}")
            onFailure(e)
        }
    }

    fun signOut() {
        googleSignInClient.signOut().addOnCompleteListener {
            _account.value = null
            taskListId = null
            logManager.addLog("Signed out from Google Tasks.")
        }
    }
}
