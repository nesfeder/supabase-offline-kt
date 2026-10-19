// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testing

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.nesfeder.supabase.offline.ConflictResolution
import io.github.nesfeder.supabase.offline.ConflictResolver
import io.github.nesfeder.supabase.offline.MutationConflict
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.offline
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Staging a server-side change between enqueue and replay with nothing but a Ktor `MockEngine`: the
 * guarded write finds no row at the device's version, the read by key finds the row moved, and the
 * app's resolver settles it. The documentation's testing page quotes its two marked sections.
 */
class ConflictStagingTest {

    @Test
    fun a_row_moved_on_the_server_reaches_the_resolver_and_is_resent() = runTest {
        // Given — the row as read, as another device leaves it, and as the resend leaves it
        // --8<-- [start:stage-conflict-rows]
        val asRead = """[{"id":"t1","title":"Buy milk","updated_at":"$BASE"}]"""
        val moved = """[{"id":"t1","title":"Buy oat milk","updated_at":"$MOVED"}]"""
        val resent = """[{"id":"t1","title":"Mine","updated_at":"$RESENT"}]"""
        val conflicts = mutableListOf<MutationConflict>()
        val patches = mutableListOf<HttpRequestData>()
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        // --8<-- [end:stage-conflict-rows]

        // When — the title is changed offline, then the queue replays
        // --8<-- [start:stage-conflict]
        val supabase =
            createSupabaseClient("https://unit-test.supabase.co", "unit-test-anon-key") {
                httpEngine = MockEngine { request ->
                    val rows =
                        when {
                            request.method == HttpMethod.Patch -> {
                                patches += request
                                val atBase = request.url.parameters["updated_at"] == "eq.$BASE"
                                if (atBase) "[]" else resent // no row at the device's version
                            }
                            request.url.parameters["id"] == "eq.t1" -> moved // read by key
                            else -> asRead
                        }
                    respond(rows, headers = json)
                }
                install(Postgrest) { maxRetries = 0 }
                install(Offline) {
                    inMemory()
                    autoSync = false // the call below is the pass that replays
                    versionColumn("tasks", "updated_at")
                    conflictResolver = ConflictResolver { conflict ->
                        conflicts += conflict
                        ConflictResolution.KeepLocal
                    }
                }
            }
        supabase.offline.from("tasks").select()
        supabase.offline.disableNetwork()
        supabase.offline.from("tasks").update(buildJsonObject { put("title", "Mine") }) {
            filter { eq("id", "t1") }
        }
        supabase.offline.enableNetwork()
        val report = supabase.offline.syncPendingMutations()
        // --8<-- [end:stage-conflict]

        // Then — the resolver saw both titles clash, and KeepLocal resent at the new version
        assertEquals(setOf("title"), conflicts.single().conflictingColumns)
        assertEquals(2, patches.size)
        val guarded = patches.first()
        assertTrue(
            ENCODED_BASE in guarded.url.encodedQuery,
            "got ${guarded.url.encodedQuery}",
        )
        assertTrue("return=representation" in guarded.headers["Prefer"].orEmpty())
        assertEquals("eq.$MOVED", patches.last().url.parameters["updated_at"])
        assertEquals(1 to 1, report.syncedCount to report.conflictsResolvedCount)
        supabase.close()
    }

    private companion object {
        const val BASE = "2026-10-18T13:00:27.486123+00:00"
        const val MOVED = "2026-10-18T13:04:00.000001+00:00"
        const val RESENT = "2026-10-18T13:05:00.000002+00:00"

        /** [BASE] as Ktor writes it in the query string: `:` and `+` percent-encoded. */
        const val ENCODED_BASE = "updated_at=eq.2026-10-18T13%3A00%3A27.486123%2B00%3A00"
    }
}
