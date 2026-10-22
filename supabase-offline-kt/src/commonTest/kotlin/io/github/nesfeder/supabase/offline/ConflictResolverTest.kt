// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.buildOfflineClient
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryCachedFileDao
import io.github.nesfeder.supabase.offline.testdoubles.FIRST_VERSION
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeRelationalPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.GUARDED_TABLE
import io.github.nesfeder.supabase.offline.testdoubles.MOVED_VERSION
import io.github.nesfeder.supabase.offline.testdoubles.RecordingConflictResolver
import io.github.nesfeder.supabase.offline.testdoubles.VERSION_COLUMN
import io.github.nesfeder.supabase.offline.testdoubles.deleteTask
import io.github.nesfeder.supabase.offline.testdoubles.newGuardedClient
import io.github.nesfeder.supabase.offline.testdoubles.newGuardedServer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.queueWhileOffline
import io.github.nesfeder.supabase.offline.testdoubles.renameTask
import io.github.nesfeder.supabase.offline.testdoubles.taskRow
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath

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

    /** [renamedElsewhere] as a rename's replay reads it: the key, the version and the title. */
    private val renamedElsewhereAsRead: JsonObject = buildJsonObject {
        put("id", 1)
        put("title", "elsewhere")
        put(VERSION_COLUMN, MOVED_VERSION)
    }

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
                    server = renamedElsewhereAsRead,
                    changedOnServer = setOf("title"),
                    conflictingColumns = setOf("title"),
                ),
                resolver.conflicts.single(),
            )
            client.close()
        }

    @Test
    fun a_row_changed_elsewhere_on_another_column_only_is_resent_without_asking() = runTest {
        // Given — another device marked the task done
        val client = newGuardedClient(server, resolver)
        client.queueRename()
        server.replaceRow(GUARDED_TABLE, taskRow(1, "theirs", done = true, version = MOVED_VERSION))

        // When
        val report = client.syncPendingMutations()

        // Then — the write's one column is unchanged on the server: nothing to decide
        assertEquals(emptyList(), resolver.conflicts)
        assertEquals(1 to 0, report.syncedCount to report.conflictsResolvedCount)
        assertEquals("eq.$MOVED_VERSION", server.requests.last().query[VERSION_COLUMN])
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
                server =
                    buildJsonObject {
                        put("id", 1)
                        put(VERSION_COLUMN, MOVED_VERSION)
                    },
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

        // Then — parked, and the cached list shows the server's row, not the device's rename
        val parked = report.conflicted.single()
        assertEquals(id to MOVED_VERSION, parked.id to parked.serverVersion)
        assertEquals(setOf("title"), parked.conflictingColumns)
        assertEquals(0 to 0, report.syncedCount to report.remainingCount)
        assertEquals(renamedElsewhere, server.rows(GUARDED_TABLE).single())
        assertEquals(listOf(renamedElsewhere), client.cachedTasks())
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
    fun keep_local_on_a_row_gone_from_the_server_parks_the_write() = runTest {
        // Given — the resolver would resend, but there is no row to resend against
        val client = newGuardedClient(server, resolver)
        client.queueRename()
        server.deleteRow(GUARDED_TABLE, "1")
        resolver.answer = { ConflictResolution.KeepLocal }

        // When
        val report = client.syncPendingMutations()

        // Then — nothing resurrected, and the cached row stays as the device last read it
        assertNull(report.conflicted.single().serverVersion)
        assertEquals(emptyList(), server.rows(GUARDED_TABLE))
        assertEquals(listOf(taskRow(1, "theirs")), client.cachedTasks())
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

    @Test
    fun a_column_moved_to_the_value_the_write_sets_is_changed_but_not_conflicting() = runTest {
        // Given — the write renames the task and marks it done; elsewhere it was renamed too, and
        // marked done as well
        val client = newGuardedClient(server, resolver)
        server.seed(GUARDED_TABLE, taskRow(1, "theirs"))
        client.queueWhileOffline(server) {
            from(GUARDED_TABLE).update(
                buildJsonObject {
                    put("title", "mine")
                    put("done", true)
                }
            ) {
                filter { eq("id", 1) }
            }
        }
        server.replaceRow(GUARDED_TABLE, taskRow(1, "elsewhere", done = true, MOVED_VERSION))
        resolver.answer = { ConflictResolution.Hold }

        // When
        client.syncPendingMutations()

        // Then — both moved on the server, only the title disagrees
        val conflict = resolver.conflicts.single()
        assertEquals(setOf("title", "done"), conflict.changedOnServer)
        assertEquals(setOf("title"), conflict.conflictingColumns)
        client.close()
    }

    @Test
    fun a_resolver_that_throws_an_error_parks_the_write() = runTest {
        // Given — the app's resolver is not written yet
        val client = newGuardedClient(server, resolver)
        client.queueRename()
        server.replaceRow(GUARDED_TABLE, renamedElsewhere)
        resolver.answer = { TODO("the app's merge") }

        // When
        val report = client.syncPendingMutations()

        // Then — read as Hold, not thrown out of the call
        assertEquals(1, report.conflicted.size)
        assertEquals(0, client.pendingMutationCount())
        client.close()
    }

    @Test
    fun a_resolver_that_throws_an_error_on_an_automatic_pass_leaves_the_passes_running() = runTest {
        // Given — a client whose own passes run, and a resolver that is not written yet
        val client =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                config =
                    Offline.createConfig {
                        versionColumn(GUARDED_TABLE, VERSION_COLUMN)
                        conflictResolver = resolver
                    },
            )
        resolver.answer = { TODO("the app's merge") }
        server.seed(GUARDED_TABLE, taskRow(1, "theirs"), taskRow(2, "theirs too"))
        client.from(GUARDED_TABLE).select()

        // When — two conflicts, each met by a pass the network's return starts
        for (id in 1..2) {
            client.disableNetwork()
            client.renameTask(id, "mine")
            server.replaceRow(GUARDED_TABLE, taskRow(id, "elsewhere", version = MOVED_VERSION))
            client.enableNetwork()
            awaitConflicted(client, id)
        }

        // Then — both parked, and nothing left queued
        assertEquals(2, resolver.conflicts.size)
        assertEquals(0, client.pendingMutationCount())
        client.close()
    }

    @Test
    fun the_resolver_runs_on_the_librarys_dispatcher_not_the_callers() = runTest {
        // Given
        val client = newGuardedClient(server, resolver)
        client.queueRename()
        server.replaceRow(GUARDED_TABLE, renamedElsewhere)
        val caller = currentCoroutineContext()[ContinuationInterceptor]

        // When — the app syncs from its own dispatcher
        client.syncPendingMutations()

        // Then
        val seen = resolver.dispatchers.single()
        assertNotEquals(caller, seen)
        assertEquals(Dispatchers.Default, seen)
        client.close()
    }

    @Test
    fun the_installed_plugin_asks_the_configured_resolver() =
        runTest(timeout = 2.minutes) {
            // Given — a client opened as `install(Offline)` opens one, with a resolver that holds
            resolver.answer = { ConflictResolution.Hold }
            val config = Offline.createConfig {
                versionColumn(GUARDED_TABLE, VERSION_COLUMN)
                conflictResolver = resolver
                autoSync = false
            }
            val client =
                config.buildOfflineClient(
                    supabase = server.supabaseClient(),
                    queryCacheDao = FakeQueryCacheDao(),
                    cachedFileDao = InMemoryCachedFileDao(),
                    mutationDao = FakeMutationDao(),
                    dataDirectory = "/tmp".toPath(),
                    prepareFileCacheDirectories = false,
                )
            client.queueRename()
            server.replaceRow(GUARDED_TABLE, renamedElsewhere)

            // When
            client.syncPendingMutations()

            // Then — the configured resolver was asked, and its Hold parked the write
            assertEquals(1, resolver.conflicts.size)
            assertEquals(1, client.conflictedMutations().first().size)
            client.close()
        }

    @Test
    fun an_apply_with_no_columns_is_refused() {
        assertFailsWith<IllegalArgumentException> {
            ConflictResolution.apply(JsonObject(emptyMap()))
        }
    }

    /** Waits, on a real thread and at most ten seconds, until [count] writes are parked. */
    private suspend fun awaitConflicted(client: Offline, count: Int) {
        withContext(Dispatchers.Default) {
            withTimeout(10.seconds) { client.conflictedMutations().first { it.size == count } }
        }
    }

    private val keyOfTaskOne: JsonObject = buildJsonObject { put("id", "1") }

    /** [GUARDED_TABLE]'s rows as the cache alone serves them. */
    private suspend fun Offline.cachedTasks(): List<JsonObject> =
        (Json.parseToJsonElement(
                from(GUARDED_TABLE).select(readStrategy = ReadStrategy.CacheOnly()).data
            ) as JsonArray)
            .map { row -> row as JsonObject }

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
