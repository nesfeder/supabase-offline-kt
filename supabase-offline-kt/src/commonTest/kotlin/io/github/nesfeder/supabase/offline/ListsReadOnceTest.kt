// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.SupabaseClient
import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.FakeRelationalPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.GUARDED_TABLE
import io.github.nesfeder.supabase.offline.testdoubles.MOVED_VERSION
import io.github.nesfeder.supabase.offline.testdoubles.RecordingConflictResolver
import io.github.nesfeder.supabase.offline.testdoubles.VERSION_COLUMN
import io.github.nesfeder.supabase.offline.testdoubles.newGuardedClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClientWithAuth
import io.github.nesfeder.supabase.offline.testdoubles.queueWhileOffline
import io.github.nesfeder.supabase.offline.testdoubles.renameTask
import io.github.nesfeder.supabase.offline.testdoubles.signInForTest
import io.github.nesfeder.supabase.offline.testdoubles.taskRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `rejectedMutationList()` and `conflictedMutationList()`: each reads its list once, and returns
 * what its flow, `rejectedMutations()` or `conflictedMutations()`, emits first, through a
 * rejection, a park, a change of user and a clear. The client's background work is idle, so only
 * the calls the test makes run.
 */
class ListsReadOnceTest {

    /** A server with a guarded table, and a child table whose rows need their parent task. */
    private val server =
        FakeRelationalPostgrestServer(
            foreignKeys =
                mapOf(NOTES to FakeRelationalPostgrestServer.ForeignKey("task_id", GUARDED_TABLE)),
            versionColumns =
                mapOf(GUARDED_TABLE to FakeRelationalPostgrestServer.VersionColumn(VERSION_COLUMN)),
        )

    private val holding = RecordingConflictResolver(answer = { ConflictResolution.Hold })

    private suspend fun signedIn(subject: String): Pair<SupabaseClient, OfflineClient> {
        val supabase = newTestSupabaseClientWithAuth(engine = server.recorder.engine)
        supabase.signInForTest(subject)
        return supabase to newGuardedClient(server, holding, supabase = supabase)
    }

    /** Queues a note for a task the server does not have, which the replay refuses for good. */
    private suspend fun OfflineClient.rejectANote(body: JsonArray = ORPHAN_NOTE): Long {
        queueWhileOffline(server) { from(NOTES).insert(body) }
        return syncPendingMutations().rejected.single().id
    }

    /** Renames task 1 offline while it is renamed elsewhere, and syncs: the resolver parks it. */
    private suspend fun OfflineClient.parkARename(): Long {
        server.seed(GUARDED_TABLE, taskRow(1, "theirs"))
        val id = queueWhileOffline(server) { renameTask(1, "mine") }
        server.replaceRow(GUARDED_TABLE, taskRow(1, "elsewhere", version = MOVED_VERSION))
        return syncPendingMutations().conflicted.single().id
    }

    /**
     * Reads each list once and from its flow, asserts the two agree, and returns the ids read once.
     */
    private suspend fun OfflineClient.listedIds(): Pair<List<Long>, List<Long>> {
        val rejected = rejectedMutationList()
        val conflicted = conflictedMutationList()
        assertEquals(rejectedMutations().first(), rejected, "the rejected list read once")
        assertEquals(conflictedMutations().first(), conflicted, "the conflicted list read once")
        return rejected.map { it.id } to conflicted.map { it.id }
    }

    @Test
    fun each_list_read_once_is_what_its_flow_emits_first() = runTest {
        // Given
        val (_, client) = signedIn(USER_A)
        val empty = client.listedIds()

        // When — a write is refused, then another parked, then local data cleared
        val rejected = client.rejectANote()
        val afterRejection = client.listedIds()
        val parked = client.parkARename()
        val afterPark = client.listedIds()
        client.clearLocalData()
        val afterClear = client.listedIds()

        // Then
        assertEquals(emptyList<Long>() to emptyList(), empty)
        assertEquals(listOf(rejected) to emptyList(), afterRejection)
        assertEquals(listOf(rejected) to listOf(parked), afterPark)
        assertEquals(emptyList<Long>() to emptyList(), afterClear)
        client.close()
    }

    @Test
    fun each_list_read_once_follows_a_dismissal_a_retry_and_a_resolution() = runTest {
        // Given — two refusals and two parked writes
        val (_, client) = signedIn(USER_A)
        val dismissed = client.rejectANote()
        val retried = client.rejectANote()
        val resolved = client.parkARename()
        val kept = client.parkARename()

        // When — one of each leaves its list; the retried write is refused again
        client.dismissRejectedMutation(dismissed)
        client.resolveConflictedMutation(resolved, ConflictResolution.KeepServer)
        val afterLeaving = client.listedIds()
        client.retryRejectedMutation(retried)
        val refusedAgain = client.syncPendingMutations().rejected.single().id
        val afterRetry = client.listedIds()

        // Then
        assertEquals(listOf(retried) to listOf(kept), afterLeaving)
        assertEquals(listOf(refusedAgain) to listOf(kept), afterRetry)
        client.close()
    }

    @Test
    fun another_user_reads_neither_list() = runTest {
        // Given — user A has a refusal and a parked write
        val (supabase, client) = signedIn(USER_A)
        val rejected = client.rejectANote()
        val parked = client.parkARename()

        // When — B signs in on the same client, then A again
        supabase.signInForTest(USER_B)
        val shownToB = client.listedIds()
        supabase.signInForTest(USER_A)
        val shownToA = client.listedIds()

        // Then
        assertEquals(emptyList<Long>() to emptyList(), shownToB)
        assertEquals(listOf(rejected) to listOf(parked), shownToA)
        client.close()
    }

    @Test
    fun a_refusal_whose_body_was_too_large_to_keep_reads_once_as_its_flow_shows_it() = runTest {
        // Given — a refused write whose body is past what the rejected list keeps
        val (_, client) = signedIn(USER_A)
        val large = buildJsonArray {
            add(
                buildJsonObject {
                    put("id", 1)
                    put("task_id", 404)
                    put("text", "x".repeat(20 * 1024))
                }
            )
        }
        client.rejectANote(large)

        // When
        val listed = client.rejectedMutationList().single()

        // Then — the flow's entry, which says it cannot be retried
        assertEquals(client.rejectedMutations().first(), listOf(listed))
        assertFalse(listed.isRetryable)
        assertNull(listed.body)
        client.close()
    }

    private companion object {
        /** A table whose rows need a [GUARDED_TABLE] row. */
        const val NOTES = "notes"
        const val USER_A = "user-a"
        const val USER_B = "user-b"

        /** A note for a task the server does not have. */
        val ORPHAN_NOTE: JsonArray = buildJsonArray {
            add(
                buildJsonObject {
                    put("id", 1)
                    put("task_id", 404)
                }
            )
        }
    }
}
