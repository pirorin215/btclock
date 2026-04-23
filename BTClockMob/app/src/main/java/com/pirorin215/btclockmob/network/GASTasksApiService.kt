package com.pirorin215.btclockmob.network

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * Request body for GAS webhook
 */
@Serializable
data class GASAddTaskRequest(
    val action: String = "addTask",
    val taskListName: String,
    val title: String,
    val notes: String? = null,
    val isCompleted: Boolean = false,
    val due: String? = null // RFC3339 format due date (optional)
)

/**
 * Response from GAS webhook
 */
@Serializable
data class GASAddTaskResponse(
    val success: Boolean,
    val taskId: String? = null,
    val error: String? = null
)

/**
 * API service for Google Apps Script webhook
 * GAS webhook URL is dynamically provided, so we use @Url annotation
 */
interface GASTasksApiService {
    @POST
    suspend fun addTask(
        @Url url: String,
        @Body request: GASAddTaskRequest
    ): GASAddTaskResponse
}
