// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.testdoubles.FIRST_VERSION
import io.github.nesfeder.supabase.offline.testdoubles.FakeRelationalPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.GUARDED_TABLE
import io.github.nesfeder.supabase.offline.testdoubles.MOVED_VERSION
import io.github.nesfeder.supabase.offline.testdoubles.RecordingConflictResolver
import io.github.nesfeder.supabase.offline.testdoubles.VERSION_COLUMN
import io.github.nesfeder.supabase.offline.testdoubles.deleteTask
import io.github.nesfeder.supabase.offline.testdoubles.newGuardedClient
import io.github.nesfeder.supabase.offline.testdoubles.newGuardedServer
import io.github.nesfeder.supabase.offline.testdoubles.queueWhileOffline
import io.github.nesfeder.supabase.offline.testdoubles.renameTask
import io.github.nesfeder.supabase.offline.testdoubles.taskRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The app's [ConflictResolver], set in the configuration, settles each conflict a replay finds: it
 * is shown the row as the device saw it, the local change and the server's row, and what it answers
 * is what the pass does, with a throw or a slow answer parking the write.
 */
class ConflictResolverTest {

    private val server = newGuardedServer()
    private val resolver = RecordingConflictResolver()

    /**
     * Task 1, read at [FIRST_VERSION], renamed to "mine" here while the server cannot be reached.
     */
    private suspend fun Offline.queueRename(): Long {
        server.seed(GUARDED_TABLE, taskRow(1, "theirs"))
        return queueWhileOffline(server) { renameTask(1, "mine") }
    }

    /** Task 1 as another device's rename leaves it. */
    private val renamedElsewhere: JsonObject = taskRow(1, "elsewhere", version = MOVED_VERSION)

    @Test
    fun a_row_renamed_on_both_sides_shows_the_resolver_each_side_and_the_conflicting_column() =
        runTest {
            // Given
            val client = newGuardedClient(server, resolver)
            val id = client.queueRename()
            server.replaceRow(GUARDED_TABLE, renamedElsewhere)
            resolver.answer = { ConflictResolution.Hold }

            // When
            client.syncPendingMutations()

            // Then
            assertEquals(
                MutationConflict(
                    mutationId = id,
                    table = GUARDED_TABLE,
                    operation = MutationOperation.UPDATE,
                    rowKey = keyOfTaskOne,
                    base = titledAt("theirs", FIRST_VERSION),
                    local = titled("mine"),
                    server = renamedElsewhere,
                    changedOnServer = setOf("title"),
                    conflictingColumns = setOf("title"),
                ),
                resolver.conflicts.single(),
            )
            client.close()
        }

    @Test
    fun a_row_changed_elsewhere_on_another_column_shows_no_conflicting_column() = runTest {
        // Given — another device marked the task done
        val client = newGuardedClient(server, resolver)
        client.queueRename()
        val doneElsewhere = taskRow(1, "theirs", done = true, version = MOVED_VERSION)
        server.replaceRow(GUARDED_TABLE, doneElsewhere)

        // When
        val report = client.syncPendingMutations()

        // Then — the write's one column is unchanged on the server, so nothing conflicts
        val conflict = resolver.conflicts.single()
        assertEquals(doneElsewhere, conflict.server)
        assertEquals(emptySet(), conflict.changedOnServer)
        assertEquals(emptySet(), conflict.conflictingColumns)
        assertEquals(1 to 1, report.syncedCount to report.conflictsResolvedCount)
        assertEquals(
            JsonPrimitive("mine") to JsonPrimitive(true),
            server.rows(GUARDED_TABLE).single().let { it["title"] to it["done"] },
        )
        client.close()
    }

    @Test
    fun a_delete_of_a_moved_row_shows_no_local_columns() = runTest {
        // Given
        val client = newGuardedClient(server, resolver)
        server.seed(GUARDED_TABLE, taskRow(1, "theirs"))
        val id = client.queueWhileOffline(server) { deleteTask(1) }
        server.replaceRow(GUARDED_TABLE, renamedElsewhere)

        // When
        val report = client.syncPendingMutations()

        // Then — by default the delete wins
        assertEquals(
            MutationConflict(
                mutationId = id,
                table = GUARDED_TABLE,
                operation = MutationOperation.DELETE,
                rowKey = keyOfTaskOne,
                base = buildJsonObject { put(VERSION_COLUMN, FIRST_VERSION) },
                local = null,
                server = renamedElsewhere,
                changedOnServer = emptySet(),
                conflictingColumns = emptySet(),
            ),
            resolver.conflicts.single(),
        )
        assertEquals(1 to 1, report.syncedCount to report.conflictsResolvedCount)
        assertEquals(emptyList(), server.rows(GUARDED_TABLE))
        client.close()
    }

    @Test
    fun an_update_of_a_row_gone_from_the_server_shows_no_server_row() = runTest {
        // Given
        val client = newGuardedClient(server, resolver)
        client.queueRename()
        server.deleteRow(GUARDED_TABLE, "1")

        // When
        val report = client.syncPendingMutations()

        // Then — by default the server stands: nothing is resurrected
        val conflict = resolver.conflicts.single()
        assertNull(conflict.server)
        assertEquals(titled("mine"), conflict.local)
        assertEquals(emptySet(), conflict.changedOnServer)
        assertEquals(0 to 1, report.syncedCount to report.conflictsKeptServerCount)
        assertEquals(emptyList(), server.rows(GUARDED_TABLE))
        client.close()
    }

    @Test
    fun keep_local_resends_the_change_against_the_servers_version() = runTest {
        // Given
        val client = newGuardedClient(server, resolver)
        client.queueRename()
        server.replaceRow(GUARDED_TABLE, renamedElsewhere)
        resolver.answer = { ConflictResolution.KeepLocal }

        // When
        val report = client.syncPendingMutations()

        // Then
        assertEquals(1 to 1, report.syncedCount to report.conflictsResolvedCount)
        assertEquals("eq.$MOVED_VERSION", server.requests.last().query[VERSION_COLUMN])
        assertEquals(JsonPrimitive("mine"), server.rows(GUARDED_TABLE).single()["title"])
        assertEquals(0, client.pendingMutationCount())
        client.close()
    }

    @Test
    fun keep_server_drops_the_change_unsent() = runTest {
        // Given
        val client = newGuardedClient(server, resolver)
        client.queueRename()
        server.replaceRow(GUARDED_TABLE, renamedElsewhere)
        resolver.answer = { ConflictResolution.KeepServer }

        // When
        val report = client.syncPendingMutations()

        // Then — one refused send and one read, nothing resent
        assertEquals(listOf("PATCH", "GET"), server.requests.map { it.method }.takeLast(2))
        assertEquals(0 to 1, report.syncedCount to report.conflictsKeptServerCount)
        assertEquals(0 to 0, report.remainingCount to report.conflicted.size)
        assertEquals(renamedElsewhere, server.rows(GUARDED_TABLE).single())
        client.close()
    }

    @Test
    fun apply_sends_the_columns_the_resolver_gives() = runTest {
        // Given
        val client = newGuardedClient(server, resolver)
        client.queueRename()
        server.replaceRow(GUARDED_TABLE, renamedElsewhere)
        resolver.answer = { ConflictResolution.apply(titled("mine and elsewhere")) }

        // When
        val report = client.syncPendingMutations()

        // Then
        assertEquals(1 to 1, report.syncedCount to report.conflictsResolvedCount)
        val resent = server.requests.last()
        assertEquals("""{"title":"mine and elsewhere"}""", resent.body)
        assertEquals("eq.$MOVED_VERSION", resent.query[VERSION_COLUMN])
        assertEquals(
            JsonPrimitive("mine and elsewhere"),
            server.rows(GUARDED_TABLE).single()["title"],
        )
        client.close()
    }

    @Test
    fun hold_parks_the_write_on_the_conflicted_list() = runTest {
        // Given
        val client = newGuardedClient(server, resolver)
        val id = client.queueRename()
        server.replaceRow(GUARDED_TABLE, renamedElsewhere)
        resolver.answer = { ConflictResolution.Hold }

        // When
        val report = client.syncPendingMutations()

        // Then
        val parked = report.conflicted.single()
        assertEquals(id to MOVED_VERSION, parked.id to parked.serverVersion)
        assertEquals(setOf("title"), parked.conflictingColumns)
        assertEquals(0 to 0, report.syncedCount to report.remainingCount)
        assertEquals(renamedElsewhere, server.rows(GUARDED_TABLE).single())
        client.close()
    }

    @Test
    fun a_resolver_that_throws_parks_the_write() = runTest {
        // Given
        val client = newGuardedClient(server, resolver)
        client.queueRename()
        server.replaceRow(GUARDED_TABLE, renamedElsewhere)
        resolver.answer = { throw IllegalStateException("the app's merge failed") }

        // When
        val report = client.syncPendingMutations()

        // Then
        assertEquals(1, report.conflicted.size)
        assertEquals(0, client.pendingMutationCount())
        assertEquals(renamedElsewhere, server.rows(GUARDED_TABLE).single())
        client.close()
    }

    @Test
    fun a_resolver_slower_than_the_bound_parks_the_write() = runTest {
        // Given
        val client = newGuardedClient(server, resolver, conflictPolicyTimeout = 50.milliseconds)
        client.queueRename()
        server.replaceRow(GUARDED_TABLE, renamedElsewhere)
        resolver.answer = { awaitCancellation() }

        // When
        val report = client.syncPendingMutations()

        // Then
        assertEquals(MOVED_VERSION, report.conflicted.single().serverVersion)
        assertEquals(0, client.pendingMutationCount())
        client.close()
    }

    @Test
    fun apply_to_a_row_gone_from_the_server_parks_the_write() = runTest {
        // Given
        val client = newGuardedClient(server, resolver)
        client.queueRename()
        server.deleteRow(GUARDED_TABLE, "1")
        resolver.answer = { ConflictResolution.apply(titled("merged")) }

        // When
        val report = client.syncPendingMutations()

        // Then — nothing resurrected
        assertNull(report.conflicted.single().serverVersion)
        assertEquals(emptyList(), server.rows(GUARDED_TABLE))
        client.close()
    }

    @Test
    fun an_integer_version_reaches_the_resolver_as_the_server_sent_it() = runTest {
        // Given — a version counted up by a trigger
        val counting = newGuardedServer(FakeRelationalPostgrestServer.VersionKind.COUNTER)
        val client = newGuardedClient(counting, resolver)
        counting.seed(GUARDED_TABLE, counted("theirs", 7))
        client.queueWhileOffline(counting) { renameTask(1, "mine") }
        counting.replaceRow(GUARDED_TABLE, counted("elsewhere", 8))

        // When
        val report = client.syncPendingMutations()

        // Then
        val conflict = resolver.conflicts.single()
        assertEquals(JsonPrimitive(7), conflict.base[VERSION_COLUMN])
        assertEquals(JsonPrimitive(8), conflict.server?.get(VERSION_COLUMN))
        assertEquals("eq.8", counting.requests.last().query[VERSION_COLUMN])
        assertEquals(1, report.conflictsResolvedCount)
        client.close()
    }

    private val keyOfTaskOne: JsonObject = buildJsonObject { put("id", "1") }

    private fun titled(title: String): JsonObject = buildJsonObject { put("title", title) }

    private fun titledAt(title: String, version: String): JsonObject = buildJsonObject {
        put("title", title)
        put(VERSION_COLUMN, version)
    }

    private fun counted(title: String, version: Int): JsonObject = buildJsonObject {
        put("id", 1)
        put("title", title)
        put("done", false)
        put(VERSION_COLUMN, version)
    }
}
