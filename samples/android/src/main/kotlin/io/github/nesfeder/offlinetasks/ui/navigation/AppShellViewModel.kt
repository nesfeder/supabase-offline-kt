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
import io.github.nesfeder.offlinetasks.ui.feedback.awaitNetworkChange
import io.github.nesfeder.offlinetasks.ui.feedback.readingAgainAfterFailures
import io.github.nesfeder.offlinetasks.ui.feedback.reportingFailures
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn

/** What the navigation bar or rail shows beside the destinations. */
data class AppShellUiState(
    /** Open tasks, for the badge on Tasks; null until known. */
    val openTaskCount: Int? = null,
    /**
     * Writes waiting in the mutation queue, for the badge on Sync; null until the queue is counted.
     */
    val pendingCount: Int? = null,
    /** Writes the server refused and the user has not dismissed: the badge on Sync turns red. */
    val rejectedCount: Int = 0,
    /** Writes parked on a conflict, waiting for the user to keep one side: red on Sync too. */
    val conflictedCount: Int = 0,
) {
    /** The writes that need the user: refused, or parked on a conflict. */
    val needsAttentionCount: Int
        get() = rejectedCount + conflictedCount
}

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
            repository
                .tasks()
                // The Tasks screen reports a failed read; the badge reads again when it can.
                .readingAgainAfterFailures(failures = null) {
                    repository.syncStatus.awaitNetworkChange()
                }
                .mapNotNull { snapshot -> snapshot.value?.count { !it.isDone } }
        )
    }
        .reportingFailures(failures)

    /**
     * The badge counts. The status counts the queue shortly after the library opens, and says when
     * it has: until then the Sync badge shows nothing, rather than a zero that then changes.
     */
    val uiState: StateFlow<AppShellUiState> =
        combine(openTaskCount, repository.syncStatus) { open, status ->
                AppShellUiState(
                    openTaskCount = open,
                    pendingCount = status.pendingMutationCount.takeIf { status.isQueueCounted },
                    rejectedCount = status.rejectedMutationCount,
                    conflictedCount = status.conflictedMutationCount,
                )
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
