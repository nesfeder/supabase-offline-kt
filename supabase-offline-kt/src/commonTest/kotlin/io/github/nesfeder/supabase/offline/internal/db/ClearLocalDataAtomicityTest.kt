// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.cache.EnqueueOutcome
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueueReplayer
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/**
 * Clearing local data at sign-out removes the mutation queue and the caches together. A clear that
 * fails part-way — the process dies, or a delete throws — must never leave one user's pending
 * mutations where the next user would see them and send them as their own.
 */
class ClearLocalDataAtomicityTest {

    private val queryCacheDao = FakeQueryCacheDao()
    private val mutationDao = FakeMutationDao()
    private val store =
        newTestOfflineStore(queryCacheDao = queryCacheDao, mutationDao = mutationDao)

    @Test
    fun a_clear_that_fails_part_way_does_not_hand_one_users_mutations_to_the_next() = runTest {
        // Given — user A's pending edit, and a clear that fails part-way
        store.enqueueOwnedBy(USER_A.storedValue) {
            enqueueUpdate(
                TABLE,
                Json.parseToJsonElement("""{"title":"A's private edit"}"""),
                byId("1"),
            )
        }
        mutationDao.failNextDeleteAll = true
        val cleared = runCatching { store.clearLocalData() }

        // When — the app starts again, user B signs in, and a pass runs
        val next = newTestOfflineStore(queryCacheDao = queryCacheDao, mutationDao = mutationDao)
        next.identityProvider = { USER_B.storedValue }
        queryCacheDao.seedPayload(TABLE, TABLE, """[{"id":"1","title":"B's row"}]""")
        val shownToB = next.overlaidPayloadFlow(TABLE, TABLE).first()
        val server = RecordingPostgrestServer()
        MutationQueueReplayer(
                store = next,
                supabase = server.supabaseClient(),
                currentIdentity = { USER_B },
            )
            .sync()

        // Then — A's edit is neither shown to B, counted for B, nor sent as B
        assertTrue(cleared.isFailure)
        assertEquals("""[{"id":"1","title":"B's row"}]""", shownToB)
        assertEquals(0, next.pendingMutationCount())
        assertEquals(emptyList(), server.requests.map { it.body })
    }

    @Test
    fun a_write_that_began_before_local_data_was_cleared_is_not_queued_after_it() = runTest {
        // Given — a write that started, then a clear
        val startedAtTick = store.monotonicTickNow()
        store.clearLocalData()

        // When — the write reaches the mutation queue
        val outcome =
            store.enqueueOwnedBy(USER_A.storedValue, startedAtTick) {
                enqueueUpdate(TABLE, Json.parseToJsonElement("""{"title":"stale"}"""), byId("1"))
            }

        // Then — it belongs to the identity that was cleared, so it is not queued
        assertEquals(EnqueueOutcome.LocalDataCleared, outcome)
        assertEquals(0, mutationDao.countAll())
    }

    private fun byId(id: String): FilterDescriptor =
        FilterDescriptor(
            conditions =
                listOf(FilterDescriptor.Condition(column = "id", operatorId = "eq", value = id))
        )

    private companion object {
        const val TABLE = "tasks"
        val USER_A = QueueIdentity.User("user-a")
        val USER_B = QueueIdentity.User("user-b")
    }
}
