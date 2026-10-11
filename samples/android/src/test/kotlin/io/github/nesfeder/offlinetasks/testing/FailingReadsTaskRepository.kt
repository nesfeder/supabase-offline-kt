// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.testing

import io.github.nesfeder.offlinetasks.data.PhotoListSnapshot
import io.github.nesfeder.offlinetasks.data.RejectedTaskMutation
import io.github.nesfeder.offlinetasks.data.RowsSnapshot
import io.github.nesfeder.offlinetasks.data.TaskListSnapshot
import io.github.nesfeder.offlinetasks.data.TaskQuery
import io.github.nesfeder.offlinetasks.data.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/**
 * [delegate], except that every read shows its first loaded value and then fails with [failure], as
 * a read does when a later row cannot be decoded: the failure a screen must report, keeping what it
 * showed, rather than crash on. Once [failing] is false, a read started again is [delegate]'s.
 */
class FailingReadsTaskRepository(
    private val delegate: TaskRepository,
    private val failure: Exception,
) : TaskRepository by delegate {

    /** Whether a read started now fails after its first value. */
    val failing = MutableStateFlow(true)

    override fun tasks(query: TaskQuery): Flow<TaskListSnapshot> =
        loadedThenFailing(delegate.tasks(query))

    override fun task(id: String): Flow<TaskListSnapshot> = loadedThenFailing(delegate.task(id))

    override fun photos(taskId: String): Flow<PhotoListSnapshot> =
        loadedThenFailing(delegate.photos(taskId))

    override fun allPhotos(): Flow<PhotoListSnapshot> = loadedThenFailing(delegate.allPhotos())

    override fun photoPage(count: Int): Flow<PhotoListSnapshot> =
        loadedThenFailing(delegate.photoPage(count))

    override fun rejectedMutations(): Flow<List<RejectedTaskMutation>> = flow {
        emit(delegate.rejectedMutations().first())
        throw failure
    }

    override fun pendingMutationCount(table: String?): Flow<Int> = flow {
        emit(delegate.pendingMutationCount(table).first())
        throw failure
    }

    /** [read]'s first value with rows in it, then [failure]. */
    private fun <T> loadedThenFailing(read: Flow<RowsSnapshot<T>>): Flow<RowsSnapshot<T>> = flow {
        if (!failing.value) {
            emitAll(read)
            return@flow
        }
        emit(read.first { it.value != null })
        throw failure
    }
}
