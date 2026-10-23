// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testing

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.resumable.MemoryResumableCache
import io.github.nesfeder.supabase.offline.ConflictResolution
import io.github.nesfeder.supabase.offline.ConflictResolver
import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.ReadStrategy
import io.github.nesfeder.supabase.offline.offline
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `inMemory(store = store)`: a client built over a store after an earlier one closed starts where
 * that one stopped, as an app relaunched on a device does, and two clients never hold one store at
 * once.
 */
class InMemoryStoreTest {

    private val store = InMemoryStore()

    @Test
    fun a_write_queued_before_close_replays_from_a_second_client_over_the_same_store() =
        runTest(timeout = 2.minutes) {
            // Given / When — a client queues a write offline and is closed, then another is built
            // over the same store, as the app relaunched with its network back
            val server = ScriptedServer()
            // --8<-- [start:relaunch]
            val first = server.supabaseClient { inMemory(store = store) }
            first.offline.disableNetwork()
            val queued =
                first.offline
                    .from(TABLE)
                    .insert(JsonArray(listOf(buildJsonObject { put("title", "Tea") })))
            first.close() // the app is killed

            val second = server.supabaseClient { inMemory(store = store) } // and relaunched
            // --8<-- [end:relaunch]

            // Then — nothing was sent before the close, and the relaunched client replays the
            // write by itself, emptying the queue
            assertIs<MutationOutcome.Queued>(queued)
            val synced =
                withContext(Dispatchers.Default) {
                    withTimeout(10.seconds) {
                        second.offline.syncStatus.first { status -> status.lastSyncedAt != null }
                    }
                }
            assertEquals(0, synced.pendingMutationCount)
            val sent = server.writes.single()
            assertEquals(HttpMethod.Post, sent.method)
            assertEquals("/rest/v1/$TABLE", sent.url.encodedPath)
            second.close()
        }

    @Test
    fun the_rejected_and_conflicted_lists_and_the_cache_survive_a_close() =
        runTest(timeout = 2.minutes) {
            // Given — a pass that parks a conflicted write and has another refused for good
            val server = ListsServer()
            val first = server.client()
            first.offline.from(TASKS).select()
            first.offline.disableNetwork()
            first.offline.from(TASKS).update(buildJsonObject { put("title", "Mine") }) {
                filter { eq("id", "t1") }
            }
            first.offline
                .from(NOTES)
                .insert(JsonArray(listOf(buildJsonObject { put("body", "draft") })))
            first.offline.enableNetwork()
            val report = first.offline.syncPendingMutations()
            assertEquals(1 to 1, report.conflicted.size to report.rejected.size)
            first.close()

            // When — the app relaunches over the same store, with the server gone
            server.online = false
            val second = server.client()

            // Then — both lists, their counts, and the cached rows are where the first left them
            val conflicted = second.offline.conflictedMutations().first().single()
            val rejected = second.offline.rejectedMutations().first().single()
            assertEquals(
                TASKS to MutationOperation.UPDATE,
                conflicted.table to conflicted.operation,
            )
            assertEquals(NOTES to 400, rejected.table to rejected.serverError?.status)
            val status =
                withContext(Dispatchers.Default) {
                    withTimeout(10.seconds) {
                        second.offline.syncStatus.first { status ->
                            status.conflictedMutationCount == 1 && status.rejectedMutationCount == 1
                        }
                    }
                }
            assertEquals(0, status.pendingMutationCount)
            val cached = second.offline.from(TASKS).select(readStrategy = ReadStrategy.CacheOnly())
            assertTrue("t1" in cached.data, cached.data)
            second.close()
        }

    @Test
    fun a_file_cached_by_one_client_is_served_by_the_next_offline() = runTest {
        // Given — a photo downloaded into the file cache, then the client closed
        val server = ScriptedServer(objectBytes = PHOTO)
        val first = server.supabaseClient { inMemory(store = store) }
        val downloaded = first.offline.storage.from(BUCKET).ensureCachedFile(PHOTO_PATH)
        first.close()

        // When — the next client reads it with the server gone
        server.online = false
        val second = server.supabaseClient { inMemory(store = store) }
        val photos = second.offline.storage.from(BUCKET)

        // Then — the file and its record are still there
        assertEquals(downloaded.path, assertNotNull(photos.cachedFile(PHOTO_PATH)).path)
        assertContentEquals(PHOTO, photos.downloadAuthenticated(PHOTO_PATH))
        second.close()
    }

    @Test
    fun a_second_client_over_a_store_another_holds_is_refused_until_that_one_closes() = runTest {
        // Given — one client open over the store
        val server = ScriptedServer()
        val first = server.supabaseClient { inMemory(store = store) }

        // When — another is built over it, then again once the first is closed
        val failure =
            assertFailsWith<IllegalStateException> {
                server.supabaseClient { inMemory(store = store) }
            }
        first.close()
        val second = server.supabaseClient { inMemory(store = store) }

        // Then — the refusal says why, and the close freed the store
        assertTrue("already open" in failure.message.orEmpty(), "unclear: ${failure.message}")
        assertEquals(0, second.offline.pendingMutationCount())
        second.close()
    }

    @Test
    fun clients_without_a_store_share_nothing() = runTest {
        // Given — a client that queues a write, then closes
        val server = ScriptedServer()
        val first = server.supabaseClient { inMemory() }
        first.offline.disableNetwork()
        first.offline.from(TABLE).insert(JsonArray(listOf(buildJsonObject { put("title", "Tea") })))
        first.close()

        // When — another client is built without a store
        val second = server.supabaseClient { inMemory() }

        // Then — it starts empty
        assertEquals(0, second.offline.pendingMutationCount())
        second.close()
    }

    /**
     * A server whose replayed update of `tasks` meets a row moved since the device read it, and
     * whose insert into `notes` it refuses for good; while [online] is `false`, every request fails
     * at the transport.
     */
    private inner class ListsServer {
        var online: Boolean = true

        /** A client over [store] that parks every conflict and replays only when asked. */
        fun client(): SupabaseClient =
            createSupabaseClient("https://unit-test.supabase.co", "unit-test-anon-key") {
                httpEngine = MockEngine { request ->
                    if (!online) throw IOException("network down")
                    val json =
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    when {
                        request.url.encodedPath.endsWith("/$NOTES") ->
                            respond(REFUSAL, HttpStatusCode.BadRequest, json)
                        request.method == HttpMethod.Patch -> respond("[]", headers = json)
                        request.url.parameters["id"] == "eq.t1" ->
                            respond(MOVED_ROW, headers = json)
                        else -> respond(READ_ROW, headers = json)
                    }
                }
                install(Postgrest) { maxRetries = 0 }
                install(Storage) { resumable { cache = MemoryResumableCache() } }
                install(Offline) {
                    inMemory(store = store)
                    autoSync = false
                    versionColumn(TASKS, "updated_at")
                    conflictResolver = ConflictResolver { ConflictResolution.Hold }
                }
            }
    }

    private companion object {
        const val TABLE = "todos"
        const val TASKS = "tasks"
        const val NOTES = "notes"
        const val READ_ROW =
            """[{"id":"t1","title":"Buy milk","updated_at":"2026-10-18T13:00:27.486123+00:00"}]"""
        const val MOVED_ROW =
            """[{"id":"t1","title":"Oat milk","updated_at":"2026-10-18T13:04:00.000001+00:00"}]"""
        const val REFUSAL =
            """{"code":"23502","details":null,"hint":null,"message":"null value in column"}"""
        const val BUCKET = "photos"
        const val PHOTO_PATH = "tasks/one.jpg"
        val PHOTO: ByteArray = "a photo".encodeToByteArray()
    }
}
