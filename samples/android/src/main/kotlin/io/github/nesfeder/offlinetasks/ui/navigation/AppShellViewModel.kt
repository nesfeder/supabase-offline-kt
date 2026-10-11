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
import io.github.nesfeder.offlinetasks.ui.feedback.Notice
import io.github.nesfeder.offlinetasks.ui.feedback.Notices
import io.github.nesfeder.offlinetasks.ui.feedback.reportingFailures
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn

/** What the navigation bar or rail shows beside the destinations. */
data class AppShellUiState(
    /** Open tasks, for the badge on Tasks; null until known. */
    val openTaskCount: Int? = null,
    /** Writes waiting in the mutation queue, for the badge on Sync. */
    val pendingCount: Int = 0,
    /** Writes the server refused and the user has not dismissed: the badge on Sync turns red. */
    val rejectedCount: Int = 0,
)

/** The badges of the navigation suite, and the failures and notices the app shows. */
class AppShellViewModel(
    repository: TaskRepository,
    failures: FailureReports,
    notices: Notices = Notices(),
) : ViewModel() {

    /** Every failure a screen reports, each delivered once, to be shown in a snackbar. */
    val failures: Flow<Throwable> = failures.reported

    /** Every notice a screen posts, each delivered once, to be shown in a snackbar. */
    val notices: Flow<Notice> = notices.posted

    /** The open-task count: at launch from the device's copy, then from the live list. */
    private val openTaskCount: Flow<Int?> = flow {
        emit(repository.openTaskCountNow())
        emitAll(
            repository.tasks().mapNotNull { snapshot ->
                snapshot.value?.count { !it.isDone }
            }
        )
    }
        .reportingFailures(failures)

    /**
     * The queue's size: read once at launch, so the badge is right at once, then from the status,
     * which follows each change to the queue shortly after it.
     */
    private val pendingCount: Flow<Int> = flow {
        emit(repository.pendingMutationCountNow())
        emitAll(repository.syncStatus.drop(1).map { it.pendingMutationCount })
    }
        .reportingFailures(failures)

    /** The badge counts. */
    val uiState: StateFlow<AppShellUiState> =
        combine(openTaskCount, pendingCount, repository.syncStatus) { open, pending, status ->
                AppShellUiState(open, pending, status.rejectedMutationCount)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppShellUiState())

    companion object {
        /** Builds the ViewModel from the app's single instances. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = offlineTasksApplication
                AppShellViewModel(application.repository, application.failures, application.notices)
            }
        }
    }
}
