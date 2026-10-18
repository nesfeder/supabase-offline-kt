// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.testdoubles.FIRST_VERSION
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeRelationalPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.GUARDED_TABLE
import io.github.nesfeder.supabase.offline.testdoubles.GUARDED_VERSION_COLUMNS
import io.github.nesfeder.supabase.offline.testdoubles.MOVED_VERSION
import io.github.nesfeder.supabase.offline.testdoubles.VERSION_COLUMN
import io.github.nesfeder.supabase.offline.testdoubles.cacheRows
import io.github.nesfeder.supabase.offline.testdoubles.enqueueUpdate
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newGuardedStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestMutationQueueReplayer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.rowWithId
import io.github.nesfeder.supabase.offline.testdoubles.taskRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A queued write to a row another queued write changes first takes, as its base, the row that
 * earlier write returned: a replay or a send at the head of the queue. A change the server reports
 * on its own — a Realtime record, `applyServerRecord` — never moves a base.
 */
class BaseForwardingTest {

    private val server =
        FakeRelationalPostgrestServer(
            versionColumns =
                mapOf(
                    GUARDED_TABLE to FakeRelationalPostgrestServer.VersionColumn(VERSION_COLUMN)
                )
        )

    @Test
    fun the_second_of_two_updates_to_one_row_replays_against_the_version_the_first_returned() =
        runTest {
            // Given — two updates of task 1, both queued against the cached version
            val store = newGuardedStore()
            server.seed(GUARDED_TABLE, taskRow(1, "theirs"))
            store.cacheRows(taskRow(1, "theirs"))
            store.mutationQueue.enqueueUpdate(GUARDED_TABLE, column("title", "mine"), rowWithId(1))
            store.mutationQueue.enqueueUpdate(GUARDED_TABLE, column("done", true), rowWithId(1))

            // When
            val report =
                newTestMutationQueueReplayer(store = store, supabase = server.supabaseClient())
                    .sync()

            // Then — the second went out at the first's new version and applied: no conflict
            val (first, second) = server.requests
            assertEquals("eq.$FIRST_VERSION", first.query[VERSION_COLUMN])
            val afterFirst = second.query.getValue(VERSION_COLUMN).removePrefix("eq.")
            assertEquals(2 to 0, report.syncedCount to report.conflictsResolvedCount)
            assertEquals(2, server.requests.size)
            val onServer = server.rows(GUARDED_TABLE).single()
            assertEquals(JsonPrimitive(true), onServer["done"])
            assertEquals(JsonPrimitive("mine"), onServer["title"])
            assertEquals(true, afterFirst != FIRST_VERSION)
        }

    @Test
    fun a_send_at_the_head_that_returns_its_row_moves_the_base_of_the_writes_after_it() = runTest {
        // Given — two queued updates of task 1 and one of task 2
        val mutationDao = FakeMutationDao()
        val store = newGuardedStore(mutationDao = mutationDao)
        store.cacheRows(taskRow(1, "theirs"), taskRow(2, "other"))
        val sent = store.mutationQueue.enqueueUpdate(GUARDED_TABLE, column("title", "a"), rowWithId(1))
        store.mutationQueue.enqueueUpdate(GUARDED_TABLE, column("title", "b"), rowWithId(1))
        store.mutationQueue.enqueueUpdate(GUARDED_TABLE, column("title", "c"), rowWithId(2))

        // When — the first leaves the queue with the row its response carried
        store.mutationQueue.completeSentMutation(
            sent,
            GUARDED_TABLE,
            listOf(taskRow(1, "a", version = MOVED_VERSION)),
        )

        // Then — only the later write to the same row moved, columns and version both
        val (sameRow, otherRow) = mutationDao.inserted
        assertEquals(MOVED_VERSION, sameRow.baseVersion)
        assertEquals("""{"title":"a","updated_at":"$MOVED_VERSION"}""", sameRow.baseJson)
        assertEquals(FIRST_VERSION, otherRow.baseVersion)
    }

    @Test
    fun a_server_record_between_enqueue_and_replay_does_not_move_a_base() = runTest {
        // Given — task 1 read into the cache, then renamed while the server cannot be reached
        val mutationDao = FakeMutationDao()
        val client =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                mutationDao = mutationDao,
                versionColumns = GUARDED_VERSION_COLUMNS,
                coordinatorScope = idleCoroutineScope(),
                closeWait = 100.milliseconds,
            )
        server.seed(GUARDED_TABLE, taskRow(1, "theirs"))
        client.from(GUARDED_TABLE).select()
        server.failsRequest = { true }
        val outcome =
            client.from(GUARDED_TABLE).update(column("title", "mine")) {
                filter { eq("id", 1) }
            }

        // When — the server reports the row at a version the device never saw the user act on
        client.applyServerRecord(GUARDED_TABLE, taskRow(1, "theirs", version = MOVED_VERSION))

        // Then — the base stays what the user saw, so the replay meets the move as a conflict
        assertIs<MutationOutcome.Queued>(outcome)
        assertEquals(FIRST_VERSION, mutationDao.inserted.single().baseVersion)
        server.failsRequest = null
        server.replaceRow(GUARDED_TABLE, taskRow(1, "theirs", version = MOVED_VERSION))
        server.recorder.requests.clear()
        val report = client.syncPendingMutations()
        assertEquals(1, report.syncedCount)
        assertEquals("eq.$FIRST_VERSION", server.requests.first { it.method == "PATCH" }.query[VERSION_COLUMN])
        client.close()
    }

    private fun column(name: String, value: String): JsonObject =
        JsonObject(mapOf(name to JsonPrimitive(value)))

    private fun column(name: String, value: Boolean): JsonObject =
        JsonObject(mapOf(name to JsonPrimitive(value)))
}
