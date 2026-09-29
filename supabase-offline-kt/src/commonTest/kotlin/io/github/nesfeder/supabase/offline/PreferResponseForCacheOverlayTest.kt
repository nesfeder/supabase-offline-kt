@file:OptIn(ExperimentalTime::class)

package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.db.MutationOperation
import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.time.ExperimentalTime

/**
 * Regression coverage for the "saved but empty" bug where a live `selectAsFlow` collector
 * surfaces a parent row whose own columns are correct but whose joined child rows (looked up
 * by the parent's server-assigned id) are missing, even after the save succeeded.
 *
 * Root cause: `OfflinePostgrestQueryBuilder` was patching the object_cache using the *request*
 * body, which for INSERT/UPSERT omits server-assigned columns (`id`, `created_at`, etc.).
 * The overlaid cache row decoded with `id = ""`, so a downstream
 * `childrenByParentId[parent.id]` lookup in the consumer's reactive join
 * silently missed. A tab-switch forced a fresh subscription whose network SELECT replaced
 * the cache with the full server payload, at which point the join worked.
 *
 * The fix ([preferResponseForCacheOverlay]) prefers the server response payload as the
 * overlay body when the caller asked for the row back, so live collectors see the same
 * fully-formed row the server has.
 */
class PreferResponseForCacheOverlayTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun prefers_response_when_it_is_a_non_empty_json_array() {
        // Given
        val requestBody = buildJsonArray {
            add(buildJsonObject {
                put("user_id", "u-1")
                put("external_id", 42L)
            })
        }
        val serverResponse = """[{"id":"server-assigned","user_id":"u-1","external_id":42}]"""

        // When
        val chosen = preferResponseForCacheOverlay(requestBody, serverResponse, json)

        // Then
        assertEquals(serverResponse, chosen)
    }

    @Test
    fun falls_back_to_request_body_when_response_is_empty_array() {
        // Given — the INSERT-without-select() case, where the server returns []
        val requestBody = buildJsonArray {
            add(buildJsonObject {
                put("note_id", "log-1")
                put("user_id", "u-1")
                put("project_id", "project-xyz")
            })
        }
        val emptyResponse = "[]"

        // When
        val chosen = preferResponseForCacheOverlay(requestBody, emptyResponse, json)

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
        val chosen = preferResponseForCacheOverlay(requestBody, garbageResponse, json)

        // Then
        assertEquals(requestBody.toString(), chosen)
    }

    @Test
    fun cache_overlay_using_response_preserves_server_assigned_id_for_joins() = runTest {
        // End-to-end shape-check mirroring a consumer's reactive parent/child join:
        // a live cache-less subscription (Part B: no fabricated seed) has a successful upsert
        // materialized into a real cache row using the *response* body (server-assigned id) rather
        // than the id-less request body. Downstream decode + join round-trips the id — the thing
        // that silently broke before.

        // Given
        val objectDao = FakeObjectDao()
        val store = buildStore(objectDao)
        val signature = "sig-notes-user-1"

        // Request body: the parent's upsert DTO — no id (server will assign it).
        val requestBody = buildJsonArray {
            add(buildJsonObject {
                put("user_id", "u-1")
                put("external_id", 42L)
            })
        }
        // Response body from Supabase upsert+select(): full row including the server-assigned id.
        val serverResponse =
            """[{"id":"server-log-id","user_id":"u-1","external_id":42,"notes":null}]"""

        // When
        store.patchCachedQueries(
            table = "notes",
            operation = MutationOperation.UPSERT,
            bodyJson = preferResponseForCacheOverlay(requestBody, serverResponse, json),
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

private fun PreferResponseForCacheOverlayTest.buildStore(objectDao: FakeObjectDao): OfflineStore =
    newTestOfflineStore(
        objectDao = objectDao,
    )
