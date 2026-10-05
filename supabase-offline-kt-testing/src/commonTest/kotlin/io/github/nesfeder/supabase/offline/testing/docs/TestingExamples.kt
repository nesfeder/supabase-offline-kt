// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testing.docs

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.NetworkStatus
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.QuerySnapshot
import io.github.nesfeder.supabase.offline.SyncStatus
import io.github.nesfeder.supabase.offline.offline
import io.github.nesfeder.supabase.offline.testing.inMemory
import io.github.nesfeder.supabase.offline.testing.pendingMutation
import io.github.nesfeder.supabase.offline.testing.querySnapshot
import io.github.nesfeder.supabase.offline.testing.queuedOutcome
import io.github.nesfeder.supabase.offline.testing.syncStatus
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The documentation site's testing page includes the sections of this file between the `--8<--`
// markers, so every Kotlin sample there compiles. Nothing here runs.

/** A client for a test, built as the app builds its own. */
internal fun inMemoryTestClient(testClock: Clock): SupabaseClient {
    // --8<-- [start:in-memory-client]
    val supabase =
        createSupabaseClient("https://test.supabase.co", "test-key") {
            httpEngine = MockEngine { request ->
                respond(
                    "[]",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            // A failed request is not retried, so a read the cache cannot answer fails at once
            install(Postgrest) { maxRetries = 0 }
            install(Offline) { inMemory(clock = testClock) }
        }
    // --8<-- [end:in-memory-client]
    return supabase
}

/** A test that takes the client offline and checks a write waits. */
internal fun aWriteMadeOfflineIsQueued(supabase: SupabaseClient) = runTest {
    // --8<-- [start:offline-test]
    // Given: the library's network access off, as airplane mode would turn it off
    supabase.offline.disableNetwork()

    // When
    val outcome =
        supabase.offline
            .from("todos")
            .insert(
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("id", "t-1")
                            put("title", "Tea")
                        }
                    )
                )
            )

    // Then: the write waits in the queue, and nothing was sent
    assertIs<MutationOutcome.Queued>(outcome)
    assertEquals(1, supabase.offline.pendingMutationCount())
    // --8<-- [end:offline-test]
}

/** Values for an app's own test double of a repository built on the library. */
internal fun testDoubleValues(
    supabase: SupabaseClient
): Triple<MutationOutcome, SyncStatus, QuerySnapshot> {
    // --8<-- [start:factories]
    val queued =
        queuedOutcome(
            pendingMutation(
                id = 1,
                table = "todos",
                operation = MutationOperation.INSERT,
                createdAt = Instant.fromEpochMilliseconds(0),
            )
        )
    val offlineWithTwoWaiting =
        syncStatus(network = NetworkStatus.OFFLINE, pendingMutationCount = 2)
    val cachedRows =
        querySnapshot(
            result = PostgrestResult("[]", Headers.Empty, supabase.postgrest),
            isFromCache = true,
            fetchedAt = Instant.fromEpochMilliseconds(0),
        )
    // --8<-- [end:factories]
    return Triple(queued, offlineWithTwoWaiting, cachedRows)
}
