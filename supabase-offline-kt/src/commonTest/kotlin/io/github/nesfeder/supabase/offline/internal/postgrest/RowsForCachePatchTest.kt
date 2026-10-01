// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalTime::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.nesfeder.supabase.offline.internal.db.MutationOperation
import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [rowsForCachePatch]: a write that asked for its rows back patches the cache with the server's
 * rows. Columns the server assigns, such as `id`, then reach live readers at once; without usable
 * rows in the response, the request body patches it.
 */
class RowsForCachePatchTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun prefers_response_when_it_is_a_non_empty_json_array() {
        // Given
        val requestBody = buildJsonArray {
            add(
                buildJsonObject {
                    put("user_id", "u-1")
                    put("external_id", 42L)
                }
            )
        }
        val serverResponse = """[{"id":"server-assigned","user_id":"u-1","external_id":42}]"""

        // When
        val chosen = rowsForCachePatch(requestBody, serverResponse, json)

        // Then
        assertEquals(serverResponse, chosen)
    }

    @Test
    fun keeps_the_columns_the_caller_wrote_that_a_narrow_response_leaves_out() {
        // Given — `select(Columns.list("id"))`: the response names the id only
        val requestBody = buildJsonArray {
            add(buildJsonObject { put("title", "a") })
            add(buildJsonObject { put("title", "b") })
        }
        val serverResponse = """[{"id":1},{"id":2}]"""

        // When
        val chosen = rowsForCachePatch(requestBody, serverResponse, json)

        // Then — each returned row, with the columns written in its place
        assertEquals("""[{"id":1,"title":"a"},{"id":2,"title":"b"}]""", chosen)
    }

    @Test
    fun matches_returned_rows_to_written_rows_by_key_when_fewer_came_back() {
        // Given — an upsert that ignored a duplicate returns only the row it wrote
        val requestBody = buildJsonArray {
            add(
                buildJsonObject {
                    put("id", 1)
                    put("title", "a")
                }
            )
            add(
                buildJsonObject {
                    put("id", 2)
                    put("title", "b")
                }
            )
        }
        val serverResponse = """[{"id":2,"updated_at":"t1"}]"""

        // When
        val chosen = rowsForCachePatch(requestBody, serverResponse, json)

        // Then
        assertEquals("""[{"id":2,"updated_at":"t1","title":"b"}]""", chosen)
    }

    @Test
    fun matches_returned_rows_to_written_rows_by_key_when_they_come_back_in_another_order() {
        // Given — both written rows come back, the second first
        val requestBody = buildJsonArray {
            add(
                buildJsonObject {
                    put("id", 1)
                    put("title", "a")
                }
            )
            add(
                buildJsonObject {
                    put("id", 2)
                    put("title", "b")
                }
            )
        }
        val serverResponse = """[{"id":2},{"id":1}]"""

        // When
        val chosen = rowsForCachePatch(requestBody, serverResponse, json)

        // Then — each keeps its own title
        assertEquals("""[{"id":2,"title":"b"},{"id":1,"title":"a"}]""", chosen)
    }

    @Test
    fun wraps_the_one_object_a_single_response_returns() {
        // Given — `select(); single()` returns the row as an object
        val requestBody = buildJsonArray { add(buildJsonObject { put("user_id", "u-1") }) }
        val serverResponse = """{"id":"server-assigned","user_id":"u-1"}"""

        // When
        val chosen = rowsForCachePatch(requestBody, serverResponse, json)

        // Then
        assertEquals("""[$serverResponse]""", chosen)
    }

    @Test
    fun falls_back_to_request_body_when_response_is_empty_array() {
        // Given — the INSERT-without-select() case, where the server returns []
        val requestBody = buildJsonArray {
            add(
                buildJsonObject {
                    put("note_id", "log-1")
                    put("user_id", "u-1")
                    put("project_id", "project-xyz")
                }
            )
        }
        val emptyResponse = "[]"

        // When
        val chosen = rowsForCachePatch(requestBody, emptyResponse, json)

        // Then
        assertEquals(requestBody.toString(), chosen)
    }

    @Test
    fun falls_back_to_request_body_when_response_is_malformed_json() {
        // Given
        val requestBody = buildJsonArray {
            add(buildJsonObject { put("foo", "bar") })
        }
        val garbageResponse = "not json at all"

        // When
        val chosen = rowsForCachePatch(requestBody, garbageResponse, json)

        // Then
        assertEquals(requestBody.toString(), chosen)
    }

    @Test
    fun cache_overlay_using_response_preserves_server_assigned_id_for_joins() = runTest {
        // A live reader with nothing cached gets a successful upsert as a real cache row built from
        // the response, with the id the server assigned, not from the request body, which has none.

        // Given
        val objectDao = FakeObjectDao()
        val store = buildStore(objectDao)
        val signature = "sig-notes-user-1"

        // Request body: the parent's upsert DTO — no id (server will assign it).
        val requestBody = buildJsonArray {
            add(
                buildJsonObject {
                    put("user_id", "u-1")
                    put("external_id", 42L)
                }
            )
        }
        // Response body from Supabase upsert+select(): full row including the server-assigned id.
        val serverResponse =
            """[{"id":"server-log-id","user_id":"u-1","external_id":42,"notes":null}]"""

        // When
        store.patchCachedQueries(
            table = "notes",
            operation = MutationOperation.UPSERT,
            bodyJson = rowsForCachePatch(requestBody, serverResponse, json),
            filterJson = null,
            onConflict = "user_id,external_id",
            liveSignatures = setOf(signature),
        )

        // Then
        val cached = objectDao.getRow("notes", signature)
        assertNotNull(cached)
        val decoded = json.decodeFromString<List<NoteDtoForTest>>(cached.payload)
        assertEquals("server-log-id", decoded.single().id)
        assertEquals(42L, decoded.single().externalId)
    }
}

/** Minimal mirror of a consumer's parent-row DTO, so the test depends on no consumer module. */
@Serializable
private data class NoteDtoForTest(
    @SerialName("id") val id: String = "",
    @SerialName("user_id") val userId: String,
    @SerialName("external_id") val externalId: Long,
    @SerialName("notes") val notes: String? = null,
)

private fun RowsForCachePatchTest.buildStore(objectDao: FakeObjectDao): OfflineStore =
    newTestOfflineStore(objectDao = objectDao)
