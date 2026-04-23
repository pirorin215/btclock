package com.pirorin215.btclockmob.viewModel

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.pirorin215.btclockmob.data.AppSettingsRepository
import com.pirorin215.btclockmob.data.Settings
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GoogleTasksViewModel(
    private val googleTasksIntegration: GoogleTasksManager,
    private val appSettingsRepository: AppSettingsRepository
) : ViewModel() {

    // --- Google Tasks State ---
    val account: StateFlow<GoogleSignInAccount?> = googleTasksIntegration.account
    val isLoadingGoogleTasks: StateFlow<Boolean> = googleTasksIntegration.isLoadingGoogleTasks

    val audioDirName: StateFlow<String> = appSettingsRepository.getFlow(Settings.AUDIO_DIR_NAME)
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), "BTClockRecordings")

    // --- Google Tasks Operations ---
    fun handleSignInResult(
        intent: Intent,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) = googleTasksIntegration.handleSignInResult(intent, onSuccess, onFailure)

    fun signOut() = googleTasksIntegration.signOut()

    suspend fun getGoogleSignInIntent(): Intent? =
        googleTasksIntegration.googleSignInClient.firstOrNull()?.signInIntent

    fun syncTranscriptionResultsWithGoogleTasks() = viewModelScope.launch {
        googleTasksIntegration.syncTranscriptionResultsWithGoogleTasks(audioDirName.value)
    }
}
