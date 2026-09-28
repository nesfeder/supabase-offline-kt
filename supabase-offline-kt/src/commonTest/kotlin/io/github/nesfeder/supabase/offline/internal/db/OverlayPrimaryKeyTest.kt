package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The overlay keeps one row per primary key, as the server does: a queued INSERT of a row the cached
 * payload already holds is not shown twice, and a queued UPSERT merges into the row with its key
 * instead of adding a second one. Rows without the key are appended as before.
 */
class OverlayPrimaryKeyTest {

    private val objectDao = FakeObjectDao()
    private val store = newTestOfflineStore(objectDao = objectDao)

    @Test
    fun a_queued_insert_of_a_row_the_payload_already_holds_is_shown_once() = runTest {
        // Given
        objectDao.seedPayload(TABLE, TABLE, """[{"id":"x","title":"from the server"}]""")

        // When
        store.enqueueInsert(table = TABLE, body = rows("""[{"id":"x","title":"from the server"}]"""))

        // Then
        assertEquals("""[{"id":"x","title":"from the server"}]""", store.cachePayloadWithOverlayFlow(TABLE, TABLE).first())
    }

    @Test
    fun a_queued_upsert_merges_into_the_row_with_its_key() = runTest {
        // Given
        objectDao.seedPayload(TABLE, TABLE, """[{"id":"x","title":"old","done":false}]""")

        // When
        store.enqueueUpsert(table = TABLE, bodyJson = rows("""[{"id":"x","title":"new"}]"""))

        // Then
        assertEquals("""[{"id":"x","title":"new","done":false}]""", store.cachePayloadWithOverlayFlow(TABLE, TABLE).first())
    }

    @Test
    fun a_queued_upsert_that_ignores_duplicates_leaves_the_row_with_its_key_alone() = runTest {
        // Given
        objectDao.seedPayload(TABLE, TABLE, """[{"id":"x","title":"old"}]""")

        // When
        store.enqueueUpsert(table = TABLE, bodyJson = rows("""[{"id":"x","title":"new"}]"""), ignoreDuplicates = true)

        // Then
        assertEquals("""[{"id":"x","title":"old"}]""", store.cachePayloadWithOverlayFlow(TABLE, TABLE).first())
    }

    @Test
    fun keyless_rows_are_still_appended() = runTest {
        // Given
        objectDao.seedPayload(TABLE, TABLE, """[{"title":"same"}]""")

        // When
        store.enqueueInsert(table = TABLE, body = rows("""[{"title":"same"}]"""))

        // Then
        assertEquals("""[{"title":"same"},{"title":"same"}]""", store.cachePayloadWithOverlayFlow(TABLE, TABLE).first())
    }

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private companion object {
        const val TABLE = "notes"
    }
}
