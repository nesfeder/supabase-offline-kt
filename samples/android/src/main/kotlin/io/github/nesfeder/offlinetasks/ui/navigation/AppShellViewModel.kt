// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.nesfeder.offlinetasks.data.TaskRepository
import io.github.nesfeder.offlinetasks.offlineTasksApplication
import io.github.nesfeder.offlinetasks.ui.feedback.FailureReports
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** What the navigation bar or rail shows beside the destinations. */
data class AppShellUiState(
    /** Writes waiting in the mutation queue, for the badge on Sync. */
    val pendingCount: Int,
    /** Writes the server refused and the user has not dismissed. */
    val rejectedCount: Int,
)

/** The badges of the navigation suite, and the failures the app shows. */
class AppShellViewModel(repository: TaskRepository, failures: FailureReports) : ViewModel() {

    /** Every failure a screen reports, each delivered once, to be shown in a snackbar. */
    val failures: Flow<Throwable> = failures.reported

    /** The badge counts. */
    val uiState: StateFlow<AppShellUiState> =
        repository.syncStatus
            .map { AppShellUiState(it.pendingMutationCount, it.rejectedMutationCount) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppShellUiState(0, 0))

    companion object {
        /** Builds the ViewModel from the app's single instances. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = offlineTasksApplication
                AppShellViewModel(application.repository, application.failures)
            }
        }
    }
}
