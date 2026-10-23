// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.GUARDED_TABLE
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.cacheRows
import io.github.nesfeder.supabase.offline.testdoubles.newGuardedServer
import io.github.nesfeder.supabase.offline.testdoubles.newGuardedStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestMutationQueueReplayer
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.github.nesfeder.supabase.offline.testdoubles.rowWithId
import io.github.nesfeder.supabase.offline.testdoubles.taskRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * A replay pass reads the mutation queue once, not once per mutation it sends, so draining N
 * mutations costs O(N) rather than O(N²); and a guarded replay that forwards the bases of the
 * writes after it reads their filters and bases only, never the pending table whole.
 */
class ReplayPassCostTest {

    private val mutationDao = FakeMutationDao()
    private val store = newTestStore(mutationDao = mutationDao)

    @Test
    fun a_pass_reads_the_whole_queue_a_constant_number_of_times() = runTest {
        // Given — 200 queued mutations of one identity
        repeat(QUEUED) { index ->
            store.mutationQueue.enqueueOwnedBy(USER.storedValue) {
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
            newTestMutationQueueReplayer(
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

    @Test
    fun a_pass_of_guarded_updates_never_reads_the_pending_table_whole_to_forward_their_bases() =
        runTest {
            // Given — 200 guarded updates of one task, each queued against the cached row, so
            // every replay that applies forwards the bases of all the updates after it
            val mutationDao = FakeMutationDao()
            val store = newGuardedStore(mutationDao = mutationDao)
            val server = newGuardedServer()
            server.seed(GUARDED_TABLE, taskRow(1, "theirs"))
            store.cacheRows(taskRow(1, "theirs"))
            repeat(QUEUED) { index ->
                val base =
                    store.mutationQueue.baseFor(
                        GUARDED_TABLE,
                        rowWithId(1),
                        setOf("title"),
                        identity = null,
                    )
                store.mutationQueue.enqueueOwnedBy(USER.storedValue) {
                    enqueueUpdate(
                        GUARDED_TABLE,
                        Json.parseToJsonElement("""{"title":"$index"}"""),
                        rowWithId(1),
                        base,
                    )
                }
            }
            val readsBefore = mutationDao.pendingListReads

            // When
            val report =
                newTestMutationQueueReplayer(
                        store = store,
                        supabase = server.supabaseClient(),
                        currentIdentity = { USER },
                    )
                    .sync()

            // Then — each update applied at the version the one before it returned, with no
            // conflict; the pending table was read whole a handful of times at most, and each
            // forward read the later guarded writes' filters and bases once
            assertEquals(QUEUED to 0, report.syncedCount to report.conflictsResolvedCount)
            assertEquals(
                JsonPrimitive("${QUEUED - 1}"),
                server.rows(GUARDED_TABLE).single()["title"],
            )
            val reads = mutationDao.pendingListReads - readsBefore
            assertTrue(reads <= 4, "the pending list was read $reads times for $QUEUED updates")
            assertTrue(
                mutationDao.guardedBaseReads <= QUEUED,
                "the guarded writes were read ${mutationDao.guardedBaseReads} times for $QUEUED",
            )
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
