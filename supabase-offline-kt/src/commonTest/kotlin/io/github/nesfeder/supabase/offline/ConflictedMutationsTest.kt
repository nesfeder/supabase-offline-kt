// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.FIRST_VERSION
import io.github.nesfeder.supabase.offline.testdoubles.GUARDED_TABLE
import io.github.nesfeder.supabase.offline.testdoubles.MOVED_VERSION
import io.github.nesfeder.supabase.offline.testdoubles.RecordingConflictResolver
import io.github.nesfeder.supabase.offline.testdoubles.VERSION_COLUMN
import io.github.nesfeder.supabase.offline.testdoubles.deleteTask
import io.github.nesfeder.supabase.offline.testdoubles.newGuardedClient
import io.github.nesfeder.supabase.offline.testdoubles.newGuardedServer
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClientWithAuth
import io.github.nesfeder.supabase.offline.testdoubles.queueWhileOffline
import io.github.nesfeder.supabase.offline.testdoubles.renameTask
import io.github.nesfeder.supabase.offline.testdoubles.signInForTest
import io.github.nesfeder.supabase.offline.testdoubles.taskRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The conflicted list: a write the resolver held is listed with what the replay found, and the app
 * resends it against the server's version, sends other columns, or drops it. The list is the
 * current user's, and goes with local data.
 */
class ConflictedMutationsTest {

    private val server = newGuardedServer()
    private val holding = RecordingConflictResolver(answer = { ConflictResolution.Hold })

    /** Task 1, renamed here while the server could not be reached and elsewhere meanwhile. */
    private val renamedElsewhere: JsonObject = taskRow(1, "elsewhere", version = MOVED_VERSION)

    /** Renames task 1 to "mine" offline, renames it elsewhere, and syncs, parking the write. */
    private suspend fun OfflineClient.parkConflictingRename(): Long {
        server.seed(GUARDED_TABLE, taskRow(1, "theirs"))
        val id = queueWhileOffline(server) { renameTask(1, "mine") }
        server.replaceRow(GUARDED_TABLE, renamedElsewhere)
        syncPendingMutations()
        return id
    }

    @Test
    fun a_held_write_is_listed_with_what_the_replay_found() = runTest {
        // Given
        val client = newGuardedClient(server, holding)

        // When
        val id = client.parkConflictingRename()
        val listed = client.conflictedMutations().first().single()

        // Then
        assertEquals(
            ConflictedMutation(
                id = id,
                table = GUARDED_TABLE,
                operation = MutationOperation.UPDATE,
                createdAt = Instant.fromEpochMilliseconds(0),
                rowKeys = listOf(buildJsonObject { put("id", "1") }),
                body = titled("mine"),
                base =
                    buildJsonObject {
                        put("title", "theirs")
                        put(VERSION_COLUMN, FIRST_VERSION)
                    },
                server =
                    buildJsonObject {
                        put("id", 1)
                        put("title", "elsewhere")
                        put(VERSION_COLUMN, MOVED_VERSION)
                    },
                serverVersion = MOVED_VERSION,
                changedOnServer = setOf("title"),
                conflictingColumns = setOf("title"),
                message = listed.message,
                conflictedAt = Instant.fromEpochMilliseconds(0),
            ),
            listed,
        )
        assertTrue(listed.message.isNotBlank())
        assertEquals(0, client.pendingMutationCount())
        client.close()
    }

    @Test
    fun keep_local_queues_the_write_again_preconditioned_on_the_recorded_version() = runTest {
        // Given
        val client = newGuardedClient(server, holding)
        val id = client.parkConflictingRename()

        // When
        client.resolveConflictedMutation(id, ConflictResolution.KeepLocal)
        val requeued = client.pendingMutations().first().single()
        val report = client.syncPendingMutations()

        // Then — a new id at the tail, sent against the version the entry recorded
        assertTrue(requeued.id != id)
        assertEquals(MOVED_VERSION, requeued.baseVersion)
        assertEquals(emptyList(), client.conflictedMutations().first())
        val sent = server.requests.last()
        assertEquals("PATCH" to "eq.$MOVED_VERSION", sent.method to sent.query[VERSION_COLUMN])
        assertEquals(1, report.syncedCount)
        assertEquals(JsonPrimitive("mine"), server.rows(GUARDED_TABLE).single()["title"])
        client.close()
    }

    @Test
    fun apply_queues_the_given_columns_in_place_of_the_write() = runTest {
        // Given
        val client = newGuardedClient(server, holding)
        val id = client.parkConflictingRename()

        // When
        client.resolveConflictedMutation(id, ConflictResolution.apply(titled("mine, elsewhere")))
        client.syncPendingMutations()

        // Then
        val sent = server.requests.last()
        assertEquals("""{"title":"mine, elsewhere"}""", sent.body)
        assertEquals("eq.$MOVED_VERSION", sent.query[VERSION_COLUMN])
        assertEquals(
            JsonPrimitive("mine, elsewhere"),
            server.rows(GUARDED_TABLE).single()["title"],
        )
        client.close()
    }

    @Test
    fun keep_server_drops_the_entry_without_sending_it() = runTest {
        // Given
        val client = newGuardedClient(server, holding)
        val id = client.parkConflictingRename()
        val requestsBefore = server.requests.size

        // When
        client.resolveConflictedMutation(id, ConflictResolution.KeepServer)
        client.syncPendingMutations()

        // Then
        assertEquals(emptyList(), client.conflictedMutations().first())
        assertEquals(0, client.pendingMutationCount())
        assertEquals(requestsBefore, server.requests.size)
        assertEquals(renamedElsewhere, server.rows(GUARDED_TABLE).single())
        client.close()
    }

    @Test
    fun dismissing_an_entry_drops_it() = runTest {
        // Given
        val client = newGuardedClient(server, holding)
        val id = client.parkConflictingRename()

        // When
        client.dismissConflictedMutation(id)

        // Then
        assertEquals(emptyList(), client.conflictedMutations().first())
        assertEquals(0, client.pendingMutationCount())
        client.close()
    }

    @Test
    fun hold_is_refused_and_the_entry_stays() = runTest {
        // Given
        val client = newGuardedClient(server, holding)
        val id = client.parkConflictingRename()

        // When
        assertFailsWith<IllegalArgumentException> {
            client.resolveConflictedMutation(id, ConflictResolution.Hold)
        }

        // Then
        assertEquals(listOf(id), client.conflictedMutations().first().map { it.id })
        client.close()
    }

    @Test
    fun apply_is_refused_for_a_parked_delete() = runTest {
        // Given — a delete of a row that moved, held by the resolver
        val client = newGuardedClient(server, holding)
        server.seed(GUARDED_TABLE, taskRow(1, "theirs"))
        val id = client.queueWhileOffline(server) { deleteTask(1) }
        server.replaceRow(GUARDED_TABLE, renamedElsewhere)
        client.syncPendingMutations()

        // When
        assertFailsWith<IllegalArgumentException> {
            client.resolveConflictedMutation(id, ConflictResolution.apply(titled("mine")))
        }

        // Then
        assertEquals(listOf(id), client.conflictedMutations().first().map { it.id })
        client.close()
    }

    @Test
    fun keep_local_is_refused_for_a_row_gone_from_the_server() = runTest {
        // Given — an update of a row deleted elsewhere, held by the resolver
        val client = newGuardedClient(server, holding)
        server.seed(GUARDED_TABLE, taskRow(1, "theirs"))
        val id = client.queueWhileOffline(server) { renameTask(1, "mine") }
        server.deleteRow(GUARDED_TABLE, "1")
        client.syncPendingMutations()

        // When
        assertFailsWith<IllegalArgumentException> {
            client.resolveConflictedMutation(id, ConflictResolution.KeepLocal)
        }

        // Then — it stays listed, and dropping it is still possible
        assertEquals(listOf(id), client.conflictedMutations().first().map { it.id })
        client.resolveConflictedMutation(id, ConflictResolution.KeepServer)
        assertEquals(emptyList(), client.conflictedMutations().first())
        assertEquals(0, client.pendingMutationCount())
        client.close()
    }

    @Test
    fun another_user_neither_sees_resolves_nor_dismisses_an_entry() = runTest {
        // Given — user A's write is parked, then user B signs in on the same client
        val supabase = newTestSupabaseClientWithAuth(engine = server.recorder.engine)
        supabase.signInForTest(USER_A)
        val client = newGuardedClient(server, holding, supabase = supabase)
        val id = client.parkConflictingRename()
        supabase.signInForTest(USER_B)

        // When — B looks, and tries A's id
        val shownToB = client.conflictedMutations().first()
        client.resolveConflictedMutation(id, ConflictResolution.KeepLocal)
        client.dismissConflictedMutation(id)

        // Then — B saw nothing and queued nothing; A still has the entry
        assertEquals(emptyList(), shownToB)
        assertEquals(0, client.pendingMutationCount())
        supabase.signInForTest(USER_A)
        assertEquals(listOf(id), client.conflictedMutations().first().map { it.id })
        client.close()
    }

    @Test
    fun clearing_local_data_empties_the_list() = runTest {
        // Given
        val client = newGuardedClient(server, holding)
        client.parkConflictingRename()

        // When
        client.clearLocalData()

        // Then
        assertEquals(emptyList(), client.conflictedMutations().first())
        assertEquals(0, client.pendingMutationCount())
        client.close()
    }

    private fun titled(title: String): JsonObject = buildJsonObject { put("title", title) }

    private companion object {
        const val USER_A = "user-a"
        const val USER_B = "user-b"
    }
}
