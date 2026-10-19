// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.nesfeder.supabase.offline.testdoubles.GUARDED_TABLE
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.RecordedRequest
import io.github.nesfeder.supabase.offline.testdoubles.cacheRows
import io.github.nesfeder.supabase.offline.testdoubles.enqueueDelete
import io.github.nesfeder.supabase.offline.testdoubles.enqueueUpdate
import io.github.nesfeder.supabase.offline.testdoubles.newGuardedStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestMutationQueueReplayer
import io.github.nesfeder.supabase.offline.testdoubles.rowWithId
import io.github.nesfeder.supabase.offline.testdoubles.taskRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * What a guarded replay puts on the wire: the stored filter, a top-level `eq` on the version column
 * at the base, sent verbatim and percent-encoded by Ktor, and a request for the rows back. A write
 * with no base goes out exactly as before.
 */
class GuardedReplayRequestTest {

    private val store = newGuardedStore()

    @Test
    fun a_guarded_update_carries_its_filter_the_version_precondition_and_asks_for_its_rows() =
        runTest {
            // Given — a cached row whose version carries a +00:00 offset
            store.cacheRows(taskRow(1, "theirs", version = BASE))
            store.mutationQueue.enqueueUpdate(GUARDED_TABLE, body, rowWithId(1))
            val server =
                RecordingPostgrestServer(
                    answer = {
                        okJson(JsonArray(listOf(taskRow(1, "mine", version = NEXT))).toString())
                    }
                )

            // When
            newTestMutationQueueReplayer(store = store, supabase = server.supabaseClient()).sync()

            // Then
            val request = server.requests.single()
            assertEquals(
                RecordedRequest(
                    method = "PATCH",
                    table = GUARDED_TABLE,
                    query =
                        mapOf(
                            "id" to "eq.1",
                            "updated_at" to "eq.$BASE",
                            "select" to "id,updated_at,title",
                        ),
                    prefer = "return=representation",
                    contentProfile = "public",
                    acceptProfile = null,
                    body = """{"title":"mine"}""",
                ),
                request,
            )
            assertTrue(
                ENCODED_PRECONDITION in request.encodedQuery.orEmpty(),
                "got ${request.encodedQuery}",
            )
        }

    @Test
    fun a_guarded_delete_carries_its_filter_and_the_version_precondition() = runTest {
        // Given
        store.cacheRows(taskRow(1, "theirs", version = BASE))
        store.mutationQueue.enqueueDelete(GUARDED_TABLE, rowWithId(1))
        val server =
            RecordingPostgrestServer(
                answer = {
                    okJson(JsonArray(listOf(taskRow(1, "theirs", version = BASE))).toString())
                }
            )

        // When
        newTestMutationQueueReplayer(store = store, supabase = server.supabaseClient()).sync()

        // Then
        val request = server.requests.single()
        assertEquals(
            RecordedRequest(
                method = "DELETE",
                table = GUARDED_TABLE,
                query =
                    mapOf("id" to "eq.1", "updated_at" to "eq.$BASE", "select" to "id,updated_at"),
                prefer = "return=representation",
                contentProfile = "public",
                acceptProfile = null,
                body = "",
            ),
            request,
        )
        assertTrue(ENCODED_PRECONDITION in request.encodedQuery.orEmpty())
    }

    @Test
    fun a_write_with_no_base_is_sent_as_it_always_was() = runTest {
        // Given — nothing cached for the row, so no base was recorded
        store.mutationQueue.enqueueUpdate(GUARDED_TABLE, body, rowWithId(1))
        store.mutationQueue.enqueueDelete(GUARDED_TABLE, rowWithId(1))
        val server = RecordingPostgrestServer()

        // When
        newTestMutationQueueReplayer(store = store, supabase = server.supabaseClient()).sync()

        // Then — the unguarded wire form, byte for byte
        assertEquals(
            listOf(
                RecordedRequest(
                    method = "PATCH",
                    table = GUARDED_TABLE,
                    query = mapOf("id" to "eq.1"),
                    prefer = "return=minimal",
                    contentProfile = "public",
                    acceptProfile = null,
                    body = """{"title":"mine"}""",
                ),
                RecordedRequest(
                    method = "DELETE",
                    table = GUARDED_TABLE,
                    query = mapOf("id" to "eq.1"),
                    prefer = "return=minimal",
                    contentProfile = "public",
                    acceptProfile = null,
                    body = "",
                ),
            ),
            server.requests,
        )
    }

    private val body = Json.parseToJsonElement("""{"title":"mine"}""")

    private companion object {
        const val BASE = "2026-10-18T13:00:27.486123+00:00"
        const val NEXT = "2026-10-18T13:05:00.000002+00:00"

        /** [BASE] as Ktor writes it in the query string: `:` and `+` percent-encoded. */
        const val ENCODED_PRECONDITION = "updated_at=eq.2026-10-18T13%3A00%3A27.486123%2B00%3A00"
    }
}
