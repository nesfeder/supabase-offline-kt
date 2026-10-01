// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.sample.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.nesfeder.supabase.offline.sample.data.TaskRepository
import io.github.nesfeder.supabase.offline.sample.offlineTasksApplication
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

/** The badges of the navigation suite. */
class AppShellViewModel(repository: TaskRepository) : ViewModel() {

    /** The badge counts. */
    val uiState: StateFlow<AppShellUiState> =
        repository.syncStatus
            .map { AppShellUiState(it.pendingMutationCount, it.rejectedMutationCount) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppShellUiState(0, 0))

    companion object {
        /** Builds the ViewModel from the app's single repository. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { AppShellViewModel(offlineTasksApplication.repository) }
        }
    }
}
