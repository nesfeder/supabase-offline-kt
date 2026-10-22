// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.testdoubles.FIRST_VERSION
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeRelationalPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.GUARDED_TABLE
import io.github.nesfeder.supabase.offline.testdoubles.GUARDED_VERSION_COLUMNS
import io.github.nesfeder.supabase.offline.testdoubles.MOVED_VERSION
import io.github.nesfeder.supabase.offline.testdoubles.TestStore
import io.github.nesfeder.supabase.offline.testdoubles.VERSION_COLUMN
import io.github.nesfeder.supabase.offline.testdoubles.cacheRows
import io.github.nesfeder.supabase.offline.testdoubles.enqueueUpdate
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newGuardedStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestMutationQueueReplayer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineTable
import io.github.nesfeder.supabase.offline.testdoubles.rowWithId
import io.github.nesfeder.supabase.offline.testdoubles.taskRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
                mapOf(GUARDED_TABLE to FakeRelationalPostgrestServer.VersionColumn(VERSION_COLUMN))
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
        val sent =
            store.mutationQueue.enqueueUpdate(GUARDED_TABLE, column("title", "a"), rowWithId(1))
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
        assertEquals(
            "eq.$FIRST_VERSION",
            server.requests.first { it.method == "PATCH" }.query[VERSION_COLUMN],
        )
        client.close()
    }

    @Test
    fun a_send_at_the_head_with_its_row_asked_back_moves_the_base_of_a_write_queued_meanwhile() =
        runTest {
            // Given — task 1 cached; an update sent at once with select(), held on the wire while
            // a second update of the row is made, queued behind it against the cached version
            val mutationDao = FakeMutationDao()
            val store = newGuardedStore(mutationDao = mutationDao)
            server.seed(GUARDED_TABLE, taskRow(1, "theirs"))
            store.cacheRows(taskRow(1, "theirs"))
            val tasks =
                newTestOfflineTable(
                    delegate = server.supabaseClient().postgrest.from(GUARDED_TABLE),
                    store = store,
                )
            val arrived = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            server.beforeAnswer = { request ->
                if (request.method == "PATCH" && !arrived.isCompleted) {
                    arrived.complete(Unit)
                    release.await()
                }
            }
            val first =
                async(Dispatchers.Default) {
                    tasks.update(column("title", "mine")) {
                        filter { eq("id", 1) }
                        select()
                    }
                }
            withContext(Dispatchers.Default) { withTimeout(10.seconds) { arrived.await() } }
            val second = tasks.update(column("done", true)) { filter { eq("id", 1) } }
            val queuedBase = mutationDao.inserted.last().baseVersion

            // When — the first lands with its row, then the queue replays the second
            release.complete(Unit)
            assertIs<MutationOutcome.Applied>(first.await())
            val returnedVersion = server.rows(GUARDED_TABLE).single()[VERSION_COLUMN]
            val report =
                newTestMutationQueueReplayer(store = store, supabase = server.supabaseClient())
                    .sync()

            // Then — queued against the version it saw, it replayed against the one returned
            assertIs<MutationOutcome.Queued>(second)
            assertEquals(FIRST_VERSION, queuedBase)
            assertEquals(
                "eq.${(returnedVersion as JsonPrimitive).content}",
                server.requests.last().query[VERSION_COLUMN],
            )
            assertEquals(listOf("PATCH", "PATCH"), server.requests.map { it.method })
            assertEquals(1 to 0, report.syncedCount to report.conflictsResolvedCount)
        }

    @Test
    fun a_replay_moves_the_bases_of_its_own_users_writes_only() = runTest {
        // Given — on a shared device, user A renamed task 1 against the cached version, then user
        // B did, then A queued one more change of it
        val mutationDao = FakeMutationDao()
        val store = newGuardedStore(mutationDao = mutationDao)
        store.cacheRows(taskRow(1, "theirs"))
        val first = store.queueRenameOwnedBy(USER_A, "a")
        store.queueRenameOwnedBy(USER_B, "b")
        store.queueRenameOwnedBy(USER_A, "a again")

        // When — A's first write replays, with the row the server returned
        store.mutationQueue.recordReplayed(
            id = first,
            table = GUARDED_TABLE,
            returnedRows = listOf(taskRow(1, "a", version = MOVED_VERSION)),
            owner = USER_A,
        )

        // Then — B's base is still what B saw, so B's replay meets A's change as a conflict; A's
        // own later write moved on to the returned row
        val (_, bs, aAgain) = mutationDao.inserted
        assertEquals(USER_B to FIRST_VERSION, bs.owner to bs.baseVersion)
        assertEquals(USER_A to MOVED_VERSION, aAgain.owner to aAgain.baseVersion)
        assertEquals("""{"title":"a","updated_at":"$MOVED_VERSION"}""", aAgain.baseJson)
    }

    @Test
    fun a_send_at_the_head_moves_the_bases_of_its_own_users_writes_only() = runTest {
        // Given — A's rename sent at the head of the queue, with B's queued behind it
        val mutationDao = FakeMutationDao()
        val store = newGuardedStore(mutationDao = mutationDao)
        store.cacheRows(taskRow(1, "theirs"))
        val sent = store.queueRenameOwnedBy(USER_A, "a")
        store.queueRenameOwnedBy(USER_B, "b")

        // When — A's leaves the queue with the row its response carried
        store.mutationQueue.completeSentMutation(
            sent,
            GUARDED_TABLE,
            listOf(taskRow(1, "a", version = MOVED_VERSION)),
        )

        // Then
        val left = mutationDao.inserted.single()
        assertEquals(USER_B to FIRST_VERSION, left.owner to left.baseVersion)
    }

    /** Queues [owner]'s rename of task 1 to [title], against the cached row, and returns its id. */
    private suspend fun TestStore.queueRenameOwnedBy(owner: String, title: String): Long {
        val base =
            mutationQueue.baseFor(GUARDED_TABLE, rowWithId(1), setOf("title"), identity = null)
        val queued =
            mutationQueue.enqueueOwnedBy(owner) {
                enqueueUpdate(GUARDED_TABLE, column("title", title), rowWithId(1), base)
            }
        return assertIs<EnqueueOutcome.Queued>(queued).id
    }

    private fun column(name: String, value: String): JsonObject =
        JsonObject(mapOf(name to JsonPrimitive(value)))

    private fun column(name: String, value: Boolean): JsonObject =
        JsonObject(mapOf(name to JsonPrimitive(value)))

    private companion object {
        const val USER_A = "user:user-a"
        const val USER_B = "user:user-b"
    }
}
