// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/**
 * A replay pass reads the mutation queue once, not once per mutation it sends, so draining N
 * mutations costs O(N) rather than O(N²).
 */
class ReplayPassCostTest {

    private val mutationDao = FakeMutationDao()
    private val store = newTestOfflineStore(mutationDao = mutationDao)

    @Test
    fun a_pass_reads_the_whole_queue_a_constant_number_of_times() = runTest {
        // Given — 200 queued mutations of one identity
        repeat(QUEUED) { index ->
            store.enqueueOwnedBy(USER.storedValue) {
                enqueueUpdate(
                    TABLE,
                    Json.parseToJsonElement("""{"title":"$index"}"""),
                    byId("$index"),
                )
            }
        }
        val readsBefore = mutationDao.pendingListReads

        // When
        val report =
            MutationQueueReplayer(
                    store = store,
                    supabase = RecordingPostgrestServer().supabaseClient(),
                    currentIdentity = { USER },
                )
                .sync()

        // Then — every mutation went out, and the pending list was read a handful of times
        assertEquals(QUEUED, report.syncedCount)
        val reads = mutationDao.pendingListReads - readsBefore
        assertTrue(reads <= 4, "the pending list was read $reads times for $QUEUED mutations")
    }

    private fun byId(id: String): FilterDescriptor =
        FilterDescriptor(
            conditions =
                listOf(FilterDescriptor.Condition(column = "id", operatorId = "eq", value = id))
        )

    private companion object {
        const val TABLE = "tasks"
        const val QUEUED = 200
        val USER = QueueIdentity.User("user-a")
    }
}
