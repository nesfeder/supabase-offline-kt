// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.sample.testing

import io.github.nesfeder.supabase.offline.sample.data.NetworkStatus
import io.github.nesfeder.supabase.offline.sample.data.Snapshot
import io.github.nesfeder.supabase.offline.sample.data.TaskRepository
import io.github.nesfeder.supabase.offline.sample.data.inmemory.InMemoryTaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope

/** An in-memory repository on this test's scheduler and virtual clock, and what drives it. */
class RepositoryFixture(
    val repository: InMemoryTaskRepository,
    /** The device's network: set it false to take the device offline. */
    val deviceOnline: MutableStateFlow<Boolean>,
    val photoFiles: FakePhotoFileStore,
) {
    /** Brings the device's network back and waits until the repository has seen it. */
    suspend fun goOnline() {
        deviceOnline.value = true
        repository.syncStatus.first { it.network == NetworkStatus.Online }
    }
}

/** A repository with no tasks on its server (or the sample tasks, with [seedSampleTasks]). */
fun TestScope.repositoryFixture(
    online: Boolean = true,
    seedSampleTasks: Boolean = false,
): RepositoryFixture {
    val deviceOnline = MutableStateFlow(online)
    val clock = TestSchedulerClock(testScheduler)
    val photoFiles = FakePhotoFileStore(clock)
    val repository =
        InMemoryTaskRepository(
            scope = backgroundScope,
            photoFiles = photoFiles,
            networkAvailability = deviceOnline,
            clock = clock,
            seedSampleTasks = seedSampleTasks,
        )
    return RepositoryFixture(repository, deviceOnline, photoFiles)
}

/** The first snapshot from [this] that [matches]; virtual time runs until one arrives. */
suspend fun <T> Flow<Snapshot<T>>.awaitSnapshot(matches: (Snapshot<T>) -> Boolean): Snapshot<T> =
    first(matches)

/** The task list once the server has answered it. */
suspend fun TaskRepository.awaitServerTasks() =
    tasks().awaitSnapshot { !it.isFromCache && it.value != null }.value.orEmpty()
