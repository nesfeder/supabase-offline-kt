// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.testing

import io.github.nesfeder.offlinetasks.data.TaskMutationOutcome
import io.github.nesfeder.offlinetasks.data.TaskRepository
import io.github.nesfeder.offlinetasks.data.TaskSyncReport

/**
 * [delegate], except that adding a task and replaying the queue fail with [failure]: the failures
 * the in-memory server cannot be made to produce through a screen, such as a refusal the form
 * could not foresee.
 */
class FailingTaskRepository(delegate: TaskRepository, private val failure: Exception) :
    TaskRepository by delegate {

    override suspend fun addTask(title: String, notes: String?): TaskMutationOutcome =
        throw failure

    override suspend fun syncPendingMutations(): TaskSyncReport = throw failure
}
