// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import io.github.nesfeder.supabase.offline.testdoubles.FakeResumableCache
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.forbiddenRestException
import io.github.nesfeder.supabase.offline.testdoubles.httpResponse
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ResponseException
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The public classifiers tell a caller what kind of failure it holds: the network's, the server's
 * with its status, or one the library reports itself.
 */
class FailureClassificationTest {

    @Test
    fun a_write_that_could_not_be_queued_or_sent_is_a_connectivity_failure() = runTest {
        // Given — offline, and an update the queue cannot hold: it has no filter
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val offline = newTestOfflineClient(supabase = server.supabaseClient())

        // When
        val failure = runCatching {
            offline.from("todos").update(buildJsonObject { put("done", true) })
        }
            .exceptionOrNull()

        // Then — the library's own exception, with the network's failure underneath
        assertTrue(failure is MutationNotQueueableException, "$failure")
        assertTrue(failure.isNetworkOrConnectivityFailure())
        assertNull(failure.httpStatusOrNull())
        offline.close()
    }

    @Test
    fun a_cache_miss_is_not_a_connectivity_failure() = runTest {
        // Given
        val offline = newTestOfflineClient()

        // When
        val failure = runCatching {
            offline.from("todos").select(readStrategy = ReadStrategy.CacheOnly())
        }
            .exceptionOrNull()

        // Then
        assertTrue(failure is CacheMissException, "$failure")
        assertFalse(failure.isNetworkOrConnectivityFailure())
        offline.close()
    }

    @Test
    fun the_status_of_a_postgrest_error_and_of_a_storage_error_is_read() = runTest {
        // Given — PostgREST refusing a write, and Storage answering that an object is missing
        val server = RecordingPostgrestServer(answer = { postgrestError(HttpStatusCode.Forbidden) })
        val offline = newTestOfflineClient(supabase = server.supabaseClient())
        val storage =
            newTestSupabaseClient(
                    engine =
                        MockEngine { respond("""{"error":"not_found"}""", HttpStatusCode.NotFound) }
                ) {
                    install(Storage) { resumable { cache = FakeResumableCache() } }
                }
                .storage

        // When
        val postgrestFailure = runCatching {
            offline.from("todos").insert(buildJsonArrayOfOne(), MutationStrategy.OnlineOnly)
        }
            .exceptionOrNull()
        val storageFailure = runCatching {
            storage.from("avatars").downloadAuthenticated("me.png")
        }
            .exceptionOrNull()

        // Then
        assertEquals(403, postgrestFailure?.httpStatusOrNull())
        assertEquals(404, storageFailure?.httpStatusOrNull(), "$storageFailure")
        assertFalse(storageFailure?.isNetworkOrConnectivityFailure() ?: true)
        offline.close()
    }

    @Test
    fun the_status_is_read_through_causes_and_from_a_ktor_response_failure() = runTest {
        // Given — a Storage refusal the app wrapped, and a Ktor response failure, bare and wrapped
        val wrappedRefusal = IllegalStateException("upload failed", forbiddenRestException())
        val ktorFailure = ResponseException(httpResponse(HttpStatusCode.Conflict), "conflict")
        val wrappedKtorFailure = IllegalStateException("sync failed", ktorFailure)

        // Then
        assertEquals(403, wrappedRefusal.httpStatusOrNull())
        assertEquals(409, ktorFailure.httpStatusOrNull())
        assertEquals(409, wrappedKtorFailure.httpStatusOrNull())
        assertNull(IllegalStateException("no response").httpStatusOrNull())
    }

    @Test
    fun a_failure_with_a_status_further_down_its_chain_is_not_a_connectivity_failure() = runTest {
        // Given — I/O failures raised around a Storage refusal and around a sync's failure
        val aroundStorage = IOException("upload interrupted", forbiddenRestException())
        val aroundSync =
            IOException(
                "sync interrupted",
                SyncFailedException(
                    message = "The server answered 503",
                    httpStatus = 503,
                    code = null,
                    isConnectivityFailure = false,
                ),
            )

        // Then — each carries the server's status, so neither is the network's failure
        assertEquals(403, aroundStorage.httpStatusOrNull())
        assertFalse(aroundStorage.isNetworkOrConnectivityFailure())
        assertEquals(503, aroundSync.httpStatusOrNull())
        assertFalse(aroundSync.isNetworkOrConnectivityFailure())
    }

    private fun buildJsonArrayOfOne(): JsonArray = buildJsonArray {
        add(buildJsonObject { put("id", 1) })
    }
}
