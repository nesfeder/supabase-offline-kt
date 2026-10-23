// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(OfflineInternal::class)

package io.github.nesfeder.supabase.offline.internal.testing

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.resumable.MemoryResumableCache
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.OfflineInternal
import io.github.nesfeder.supabase.offline.ReadStrategy
import io.github.nesfeder.supabase.offline.offline
import io.github.nesfeder.supabase.offline.testdoubles.TEST_SUPABASE_KEY
import io.github.nesfeder.supabase.offline.testdoubles.TEST_SUPABASE_URL
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
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath

/**
 * The local data the testing artifact's `InMemoryStore` keeps: one client holds it at a time, a
 * client over it after an earlier one closed finds what that one left, and nothing of a failed open
 * keeps it held.
 */
class InMemoryLocalDataTest {

    @Test
    fun a_second_claim_is_refused_until_the_first_is_released() {
        // Given — one client holding the data
        val data = InMemoryLocalData()
        val first = data.claim(Clock.System)

        // When — another asks for it, then again once the first let go
        val refused = assertFailsWith<IllegalStateException> { data.claim(Clock.System) }
        first.release()
        val second = data.claim(Clock.System)

        // Then — the refusal says why, and a second release of the old claim frees nothing
        assertTrue("already open on this in-memory store" in refused.message.orEmpty())
        first.release()
        assertFailsWith<IllegalStateException> { data.claim(Clock.System) }
        second.release()
        data.claim(Clock.System).release()
    }

    @Test
    fun a_claim_whose_client_is_still_closing_says_what_it_waits_for() {
        // Given — a client whose close returned with two calls still running
        val data = InMemoryLocalData()
        val claim = data.claim(Clock.System)
        var callsInFlight = 2
        claim.markStillClosing { callsInFlight }

        // When — another client asks, then asks again once those calls ended
        val running = assertFailsWith<IllegalStateException> { data.claim(Clock.System) }
        callsInFlight = 0
        val releasing = assertFailsWith<IllegalStateException> { data.claim(Clock.System) }

        // Then
        assertTrue("2 call(s) it had in flight" in running.message.orEmpty(), "${running.message}")
        assertTrue("releasing the store now" in releasing.message.orEmpty(), "${releasing.message}")
    }

    @Test
    fun the_files_are_stamped_by_the_clock_of_the_client_holding_the_data() {
        // Given — data held by a client on one clock, then by one on another
        val data = InMemoryLocalData()
        val january = Instant.parse("2026-01-01T00:00:00Z")
        val february = Instant.parse("2026-02-01T00:00:00Z")
        val first = data.claim(FixedClock(january))
        data.fileSystem.write("/first".toPath()) { writeUtf8("a") }
        first.release()
        data.claim(FixedClock(february))

        // When
        data.fileSystem.write("/second".toPath()) { writeUtf8("b") }

        // Then — each file carries the time of the client that wrote it, and the first is kept
        val firstWrite = data.fileSystem.metadata("/first".toPath()).lastModifiedAtMillis
        val secondWrite = data.fileSystem.metadata("/second".toPath()).lastModifiedAtMillis
        assertEquals(january.toEpochMilliseconds(), firstWrite)
        assertEquals(february.toEpochMilliseconds(), secondWrite)
    }

    @Test
    fun a_client_over_kept_data_replays_the_write_the_client_before_it_queued() = runTest {
        // Given — a client over the data that caches a read, then queues a write offline
        val data = InMemoryLocalData()
        val server = RecordingServer()
        val first = server.client {
            keepLocalDataInMemory(Clock.System, data)
            autoSync = false
        }
        first.offline.from(TABLE).select()
        first.offline.disableNetwork()
        val queued =
            first.offline
                .from(TABLE)
                .insert(JsonArray(listOf(buildJsonObject { put("title", "Tea") })))
        assertIs<MutationOutcome.Queued>(queued)

        // When — it closes, and a client over the same data opens
        first.close()
        val second = server.client {
            keepLocalDataInMemory(Clock.System, data)
            autoSync = false
        }

        // Then — the next client finds the write and the cached read, and sends the write
        assertEquals(1, second.offline.pendingMutationCount())
        val cached = second.offline.from(TABLE).select(readStrategy = ReadStrategy.CacheOnly())
        assertTrue("Buy milk" in cached.data, cached.data)
        assertEquals(0, server.inserts)
        assertEquals(1, second.offline.syncPendingMutations().syncedCount)
        assertEquals(1, server.inserts)
        second.close()
    }

    @Test
    fun a_file_cached_by_one_client_is_served_by_the_next_over_kept_data() = runTest {
        // Given — a client over the data that downloads a file into its cache, then closes
        val data = InMemoryLocalData()
        val server = RecordingServer()
        val first = server.client { keepLocalDataInMemory(Clock.System, data) }
        val downloaded = first.offline.storage.from(BUCKET).ensureCachedFile(PHOTO_PATH)
        first.close()

        // When — a client over the same data reads it, with the server gone
        server.online = false
        val second = server.client { keepLocalDataInMemory(Clock.System, data) }
        val photos = second.offline.storage.from(BUCKET)
        val cached = photos.cachedFile(PHOTO_PATH)
        val bytes = photos.downloadAuthenticated(PHOTO_PATH)

        // Then — the file and its record are where the first left them
        assertEquals(downloaded.path, cached?.path)
        assertContentEquals(PHOTO, bytes)
        second.close()
    }

    @Test
    fun clients_without_kept_data_each_start_empty() = runTest {
        // Given — a client whose data is its own, which queues a write offline and closes
        val server = RecordingServer()
        val first = server.client { keepLocalDataInMemory(Clock.System) }
        first.offline.disableNetwork()
        first.offline.from(TABLE).insert(JsonArray(listOf(buildJsonObject { put("title", "Tea") })))
        first.close()

        // When — another client of the same kind opens
        val second = server.client { keepLocalDataInMemory(Clock.System) }

        // Then — it shares nothing with the first
        assertEquals(0, second.offline.pendingMutationCount())
        second.close()
    }

    @Test
    fun a_client_over_data_another_holds_is_refused_until_that_one_closes() = runTest {
        // Given
        val data = InMemoryLocalData()
        val server = RecordingServer()
        val first = server.client { keepLocalDataInMemory(Clock.System, data) }

        // When
        val failure =
            assertFailsWith<IllegalStateException> {
                server.client { keepLocalDataInMemory(Clock.System, data) }
            }
        first.close()

        // Then — the refusal says why, and the first client's close frees the data
        assertTrue("already open" in failure.message.orEmpty(), "unclear: ${failure.message}")
        val second = server.client { keepLocalDataInMemory(Clock.System, data) }
        assertEquals(0, second.offline.pendingMutationCount())
        second.close()
    }

    @Test
    fun a_client_that_fails_to_open_lets_go_of_the_data() = runTest {
        // Given — a configuration that cannot open: a table to follow, and no Realtime plugin
        val data = InMemoryLocalData()
        val server = RecordingServer()
        assertFailsWith<IllegalStateException> {
            server.client {
                keepLocalDataInMemory(Clock.System, data)
                realtime { table(TABLE) }
            }
        }

        // When — a client that can open asks for the data
        val next = server.client { keepLocalDataInMemory(Clock.System, data) }

        // Then — it opens over it
        assertEquals(0, next.offline.pendingMutationCount())
        next.close()
    }

    /** A clock that always reads [now]. */
    private class FixedClock(private val now: Instant) : Clock {
        override fun now(): Instant = now
    }

    /**
     * A server that answers every read with one row and every Storage download with [PHOTO], and
     * counts the inserts it receives; while [online] is `false`, every request fails at the
     * transport.
     */
    private class RecordingServer {
        var inserts: Int = 0

        var online: Boolean = true

        fun client(offline: Offline.Config.() -> Unit): SupabaseClient =
            createSupabaseClient(TEST_SUPABASE_URL, TEST_SUPABASE_KEY) {
                httpEngine = MockEngine { request ->
                    if (!online) throw IOException("network down")
                    if (request.url.encodedPath.startsWith("/storage/")) {
                        return@MockEngine respond(
                            content = PHOTO,
                            headers =
                                headersOf(
                                    HttpHeaders.ContentType,
                                    ContentType.Application.OctetStream.toString(),
                                ),
                        )
                    }
                    val written = request.method == HttpMethod.Post
                    if (written) inserts++
                    respond(
                        content = if (written) "[]" else """[{"id":1,"title":"Buy milk"}]""",
                        status = if (written) HttpStatusCode.Created else HttpStatusCode.OK,
                        headers =
                            headersOf(
                                HttpHeaders.ContentType,
                                ContentType.Application.Json.toString(),
                            ),
                    )
                }
                install(Postgrest) { maxRetries = 0 }
                // supabase-kt's own default keeps resumable uploads in the platform's settings
                install(Storage) { resumable { cache = MemoryResumableCache() } }
                install(Offline, offline)
            }
    }

    private companion object {
        const val TABLE = "todos"
        const val BUCKET = "photos"
        const val PHOTO_PATH = "tasks/one.jpg"
        val PHOTO: ByteArray = "a photo".encodeToByteArray()
    }
}
