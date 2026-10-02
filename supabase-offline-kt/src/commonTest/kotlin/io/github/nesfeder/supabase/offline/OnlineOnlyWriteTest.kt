// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * An `OnlineOnly` write is sent and returned as it is: the cached queries are not patched with it.
 */
class OnlineOnlyWriteTest {

    private val server =
        RecordingPostgrestServer(
            answer = { request -> okJson(if (request.method == "GET") ROWS else "") }
        )
    private val items =
        newTestOfflineClient(
                supabase = server.supabaseClient(),
                coordinatorScope = idleCoroutineScope(),
            )
            .from(TABLE)

    @Test
    fun an_online_only_write_leaves_the_cached_queries_as_they_were() = runTest {
        // Given
        items.select(readStrategy = ReadStrategy.NetworkOnly)

        // When
        val outcome =
            items.insert(
                Json.parseToJsonElement("""[{"id":2,"title":"b"}]""") as JsonArray,
                mutationStrategy = MutationStrategy.OnlineOnly,
            )

        // Then — applied, and the cache shows the list as the server last sent it
        assertEquals(true, outcome is MutationOutcome.Applied)
        assertEquals(ROWS, items.select(readStrategy = ReadStrategy.CacheOnly()).data)
    }

    private companion object {
        const val TABLE = "items"
        const val ROWS = """[{"id":1,"title":"a"}]"""
    }
}
