package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.resumable.MemoryResumableCache
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The client's entry points, ported from the previous standalone engine: which plugin each one
 * needs and the error it gives without it, where `from` and the `get` operators send requests, and
 * that the sync and count APIs act on the client's own queue.
 */
class SupabaseOfflineClientTest {

    private val server = RecordingPostgrestServer(answer = { okJson("""[{"id":1}]""") })

    @Test
    fun from_schema_and_table_whenPostgrestNotInstalled_throwsHelpfulError() = runTest {
        // Given — a client with no plugins
        val client = newTestOfflineClient(supabase = newTestSupabaseClient(plugins = {}))

        // When
        val error = assertFailsWith<IllegalStateException> { client.from("public", "todos") }

        // Then
        assertTrue(error.message.orEmpty().contains("Postgrest plugin is not installed"))
    }

    /**
     * The one-argument `from(table)` reads the default schema from the PostgREST plugin before the
     * engine's own guard runs, so without the plugin it fails with supabase-kt's own error (which
     * also names the missing plugin), as the previous standalone engine asserted, rather than the
     * engine's install hint. A later change is expected to route it through the same guard as
     * `from(schema, table)`.
     */
    @Test
    fun from_table_whenPostgrestNotInstalled_throws_before_the_engine_guard() = runTest {
        // Given — a client with no plugins
        val client = newTestOfflineClient(supabase = newTestSupabaseClient(plugins = {}))

        // When
        val error = assertFailsWith<IllegalStateException> { client.from("todos") }

        // Then
        val message = error.message.orEmpty()
        assertTrue(message.contains("Plugin rest not installed or not of type Postgrest"), message)
        assertFalse(message.contains("Postgrest plugin is not installed"))
    }

    @Test
    fun storage_whenStorageNotInstalled_throwsHelpfulError() = runTest {
        // Given — a client with PostgREST only
        val client = newTestOfflineClient(supabase = newTestSupabaseClient())

        // When
        val error = assertFailsWith<IllegalStateException> { client.storage("avatars") }

        // Then
        assertTrue(error.message.orEmpty().contains("Storage plugin is not installed"))
    }

    @Test
    fun storage_returnsOfflineBucketApi() = runTest {
        // Given
        val client = newTestOfflineClient(
            supabase = newTestSupabaseClient {
                install(Postgrest)
                // An in-memory resumable-upload cache: the default one needs a platform context.
                install(Storage) { resumable { cache = MemoryResumableCache() } }
            },
        )

        // When
        val bucket = client.storage("avatars")

        // Then
        assertIs<OfflineBucketApi>(bucket)
        assertEquals("avatars", bucket.bucketId)
    }

    @Test
    fun get_table_reads_that_table_in_the_default_schema() = runTest {
        // Given
        val client = newTestOfflineClient(supabase = server.supabaseClient())

        // When
        client["todos"].select(readStrategy = ReadStrategy.NetworkOnly)

        // Then — the same request from() would send
        val request = server.requests.single()
        assertEquals("GET" to "todos", request.method to request.table)
        assertEquals("public", request.acceptProfile)
    }

    @Test
    fun get_schema_and_table_reads_that_table_in_that_schema() = runTest {
        // Given
        val client = newTestOfflineClient(supabase = server.supabaseClient())

        // When
        client["reporting", "todos"].select(readStrategy = ReadStrategy.NetworkOnly)

        // Then
        val request = server.requests.single()
        assertEquals("GET" to "todos", request.method to request.table)
        assertEquals("reporting", request.acceptProfile)
    }

    @Test
    fun pendingMutationCount_counts_the_client_queue() = runTest {
        // Given — a write queued while the network is down
        val client = newTestOfflineClient(supabase = server.supabaseClient())
        server.answer = { connectivityFailure() }
        client.from("todos").insertJson(ROWS)

        // When
        val total = client.pendingMutationCount()
        val forTable = client.pendingMutationCount("todos")
        val forOtherTable = client.pendingMutationCount("projects")

        // Then
        assertEquals(1 to 1, total to forTable)
        assertEquals(0, forOtherTable)
    }

    @Test
    fun syncAllPendingMutations_replays_the_client_queue() = runTest {
        // Given — a write queued while the network is down, then the network returns
        val client = newTestOfflineClient(supabase = server.supabaseClient())
        server.answer = { connectivityFailure() }
        client.from("todos").insertJson(ROWS)
        server.answer = { okJson("[]") }
        server.requests.clear()

        // When
        val synced = client.syncAllPendingMutations()

        // Then
        assertEquals(1, synced)
        assertEquals(listOf("POST"), server.requests.map { it.method })
        assertEquals(0, client.pendingMutationCount())
    }

    @Test
    fun syncPendingMutations_replays_only_that_table() = runTest {
        // Given — one write queued for each of two tables while the network is down
        val client = newTestOfflineClient(supabase = server.supabaseClient())
        server.answer = { connectivityFailure() }
        client.from("todos").insertJson(ROWS)
        client.from("projects").insertJson(ROWS)
        server.answer = { okJson("[]") }
        server.requests.clear()

        // When
        val synced = client.syncPendingMutations("todos")

        // Then
        assertEquals(1, synced)
        assertEquals(listOf("todos"), server.requests.map { it.table })
        assertEquals(0 to 1, client.pendingMutationCount("todos") to client.pendingMutationCount("projects"))
    }

    private companion object {
        val ROWS: JsonArray = Json.parseToJsonElement("""[{"id":1}]""") as JsonArray
    }
}
