// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * A cached payload's age is the age of what the server last said. A local write patched into it, or
 * a payload made of local writes alone, does not make it fresh for a read with a cache duration.
 */
class OverlayFreshnessTest {

    /** How far behind the real clock the store stamps what it writes. */
    private var storeClockLag: Duration = Duration.ZERO

    private val server =
        RecordingPostgrestServer(
            answer = { request ->
                when (request.method) {
                    "GET" ->
                        okJson(
                            if (request.query["list_id"] == "eq.A")
                                """[{"id":1,"list_id":"A","title":"old"}]"""
                            else "[]"
                        )
                    else -> okJson("")
                }
            }
        )
    private val items =
        newTestOfflineClient(
                supabase = server.supabaseClient(),
                store =
                    newTestOfflineStore(
                        nowMillis = { (Clock.System.now() - storeClockLag).toEpochMilliseconds() }
                    ),
                coordinatorScope = idleCoroutineScope(),
            )
            .from(TABLE)

    private fun list(name: String): SelectRequestBuilder.() -> Unit = {
        filter { eq("list_id", name) }
    }

    private fun readsSent(): Int = server.requests.count { request -> request.method == "GET" }

    @Test
    fun a_local_write_does_not_make_a_day_old_payload_fresh() = runTest {
        // Given — list A fetched a day ago
        storeClockLag = 1.days
        items.select(readStrategy = ReadStrategy.NetworkOnly, request = list("A"))
        storeClockLag = Duration.ZERO

        // When — a write is patched into it now, then a read accepts five minutes of age
        items.update(Json.parseToJsonElement("""{"title":"new"}""")) { filter { eq("id", 1) } }
        val readsBefore = readsSent()
        items.select(
            readStrategy = ReadStrategy.CacheFirst(maxAge = 5.minutes),
            request = list("A"),
        )

        // Then — the day-old rows were not served as fresh
        assertEquals(readsBefore + 1, readsSent())
    }

    @Test
    fun a_payload_made_of_local_writes_alone_is_not_fresh() = runTest {
        // Given — list B observed but never fetched
        coroutineScope {
            val reader = launch {
                items
                    .selectAsFlow(readStrategy = FlowReadStrategy.CacheOnly, request = list("B"))
                    .collect {}
            }
            testScheduler.runCurrent()

            // When — a write into B creates its payload, then a read accepts five minutes of age
            items.insert(
                Json.parseToJsonElement("""[{"id":2,"list_id":"B","title":"b"}]""") as JsonArray
            )
            testScheduler.runCurrent()
            reader.cancel()
        }
        val readsBefore = readsSent()
        items.select(
            readStrategy = ReadStrategy.CacheFirst(maxAge = 5.minutes),
            request = list("B"),
        )

        // Then — the server was asked; the local rows alone were not taken as the list
        assertEquals(readsBefore + 1, readsSent())
    }

    private companion object {
        const val TABLE = "items"
    }
}
