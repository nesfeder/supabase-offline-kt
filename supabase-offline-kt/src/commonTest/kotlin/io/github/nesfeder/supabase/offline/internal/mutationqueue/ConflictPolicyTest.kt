// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.nesfeder.supabase.offline.testdoubles.FakeRelationalPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.GUARDED_CACHE_KEY
import io.github.nesfeder.supabase.offline.testdoubles.GUARDED_TABLE
import io.github.nesfeder.supabase.offline.testdoubles.MOVED_VERSION
import io.github.nesfeder.supabase.offline.testdoubles.RecordingConflictPolicy
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.VERSION_COLUMN
import io.github.nesfeder.supabase.offline.testdoubles.cacheRows
import io.github.nesfeder.supabase.offline.testdoubles.enqueueUpdate
import io.github.nesfeder.supabase.offline.testdoubles.newGuardedStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestMutationQueueReplayer
import io.github.nesfeder.supabase.offline.testdoubles.overlaidPayloadFlow
import io.github.nesfeder.supabase.offline.testdoubles.rowWithId
import io.github.nesfeder.supabase.offline.testdoubles.taskRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The conflict policy's seam: a decision that throws or does not come in time parks the write, a
 * decision that does not fit the case parks it too, and the other decisions do what they say.
 */
class ConflictPolicyTest {

    private val store = newGuardedStore()
    private val server =
        FakeRelationalPostgrestServer(
            versionColumns =
                mapOf(GUARDED_TABLE to FakeRelationalPostgrestServer.VersionColumn(VERSION_COLUMN))
        )
    private val logs = RecordingLogSink()
    private val policy = RecordingConflictPolicy()

    /** Task 1 renamed here while another device renamed it too. */
    private suspend fun queueConflictingRename() {
        server.seed(GUARDED_TABLE, taskRow(1, "theirs"))
        store.cacheRows(taskRow(1, "theirs"))
        store.mutationQueue.enqueueUpdate(GUARDED_TABLE, titled("mine"), rowWithId(1))
        server.replaceRow(GUARDED_TABLE, taskRow(1, "elsewhere", version = MOVED_VERSION))
    }

    private suspend fun sync(): SyncReport =
        newTestMutationQueueReplayer(
                store = store,
                supabase = server.supabaseClient(),
                engineLogger = logs.asEngineLogger(),
                conflictPolicy = policy,
                conflictPolicyTimeout = 50.milliseconds,
            )
            .sync()

    @Test
    fun a_policy_that_throws_parks_the_write_and_logs_only_the_table_and_the_failure_type() =
        runTest {
            // Given
            queueConflictingRename()
            policy.answer = { throw IllegalStateException("the app's resolver broke: $SECRET") }

            // When
            val report = sync()

            // Then
            assertEquals(1, report.conflicted.size)
            assertEquals(0, store.pendingMutationCount())
            assertEquals(JsonPrimitive("elsewhere"), server.rows(GUARDED_TABLE).single()["title"])
            val messages = logs.entries.map { entry -> entry.message }
            assertTrue(messages.any { "IllegalStateException" in it && GUARDED_TABLE in it })
            assertFalse(messages.any { SECRET in it || MOVED_VERSION in it }, "$messages")
        }

    @Test
    fun a_policy_that_does_not_decide_in_time_parks_the_write() = runTest {
        // Given
        queueConflictingRename()
        policy.answer = { awaitCancellation() }

        // When
        val report = sync()

        // Then
        val parked = report.conflicted.single()
        assertEquals(MOVED_VERSION, parked.serverVersion)
        assertEquals(listOf("title"), parked.conflictingColumns)
        assertEquals(0, store.pendingMutationCount())
    }

    @Test
    fun applying_columns_to_a_row_that_is_gone_parks_the_write() = runTest {
        // Given
        queueConflictingRename()
        server.deleteRow(GUARDED_TABLE, "1")
        policy.answer = { ConflictDecision.Apply(titled("merged")) }

        // When
        val report = sync()

        // Then — nothing resurrected
        val parked = report.conflicted.single()
        assertEquals(null, parked.serverVersion)
        assertEquals(emptyList(), server.rows(GUARDED_TABLE))
    }

    @Test
    fun applying_columns_to_a_moved_row_sends_them_against_its_version() = runTest {
        // Given
        queueConflictingRename()
        policy.answer = { ConflictDecision.Apply(titled("merged")) }

        // When
        val report = sync()

        // Then
        assertEquals(1 to 1, report.syncedCount to report.conflictsResolvedCount)
        val resent = server.requests.last()
        assertEquals("""{"title":"merged"}""", resent.body)
        assertEquals("eq.$MOVED_VERSION", resent.query[VERSION_COLUMN])
        assertEquals(JsonPrimitive("merged"), server.rows(GUARDED_TABLE).single()["title"])
    }

    @Test
    fun keeping_the_servers_row_drops_the_write_and_caches_the_servers_row() = runTest {
        // Given
        queueConflictingRename()
        policy.answer = { ConflictDecision.KeepServer }

        // When
        val report = sync()

        // Then
        assertEquals(0 to 1, report.syncedCount to report.conflictsKeptServerCount)
        assertEquals(0, store.pendingMutationCount())
        val cached =
            store.queryOverlay.overlaidPayloadFlow(GUARDED_TABLE, GUARDED_CACHE_KEY).first()
        val row = (Json.parseToJsonElement(cached.orEmpty()) as JsonArray).single() as JsonObject
        assertEquals(JsonPrimitive("elsewhere"), row["title"])
        assertEquals(JsonPrimitive(MOVED_VERSION), row[VERSION_COLUMN])
    }

    @Test
    fun a_policy_may_read_the_queue_while_it_decides() = runTest {
        // Given — a policy that takes the queue's lock, as an app resolver reading counts would
        queueConflictingRename()
        var counted: Int? = null
        policy.answer = { case ->
            counted =
                store.mutationQueue.holdingQueueLock {
                    store.mutationQueue.allPendingMutations().size
                }
            DefaultConflictPolicy.decide(case)
        }

        // When — bounded in real time, so a decision under the lock fails rather than hangs
        val report = withContext(Dispatchers.Default) { withTimeout(10.seconds) { sync() } }

        // Then — it read the write as still queued, and the pass went on to send it
        assertEquals(1, counted)
        assertEquals(1 to 1, report.syncedCount to report.conflictsResolvedCount)
    }

    @Test
    fun clearing_local_data_while_the_policy_decides_cuts_the_wait_and_parks_nothing() = runTest {
        // Given — a policy that would take its whole bound
        queueConflictingRename()
        val asked = CompletableDeferred<Unit>()
        policy.answer = {
            asked.complete(Unit)
            awaitCancellation()
        }
        val replayer =
            newTestMutationQueueReplayer(
                store = store,
                supabase = server.supabaseClient(),
                conflictPolicy = policy,
            )
        val running = async(Dispatchers.Default) { replayer.sync() }
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { asked.await() } }

        // When — a clear, which cancels the pass, keeping the queue here
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { replayer.clearLocalData {} } }
        val report =
            withContext(Dispatchers.Default) { withTimeout(10.seconds) { running.await() } }

        // Then — the pass ended at once, and the write was left queued, not parked
        assertEquals(SyncStop.LocalDataCleared, report.stop)
        assertEquals(1, store.pendingMutationCount())
        assertEquals(emptyList(), store.mutationQueue.conflictedList.conflicts())
    }

    private fun titled(title: String): JsonObject =
        JsonObject(mapOf("title" to JsonPrimitive(title)))

    private companion object {
        const val SECRET = "s3cret-row-value"
    }
}
