// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.enqueueInsert
import io.github.nesfeder.supabase.offline.testdoubles.enqueueUpsert
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.github.nesfeder.supabase.offline.testdoubles.overlaidPayloadFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * The overlay keeps one row per primary key where the row may already be on the server: an INSERT
 * that replayed, or whose send may have reached the server, is not shown twice, and a queued UPSERT
 * merges into the row with its key instead of adding a second one. An INSERT never sent is always
 * shown. Rows without the key are appended as before. The key is taken to be `id` for every table.
 */
class OverlayPrimaryKeyTest {

    private val queryCacheDao = FakeQueryCacheDao()
    private val mutationDao = FakeMutationDao()
    private val store = newTestStore(queryCacheDao = queryCacheDao, mutationDao = mutationDao)

    @Test
    fun a_replayed_insert_of_a_row_the_payload_already_holds_is_shown_once() = runTest {
        // Given
        queryCacheDao.seedPayload(TABLE, TABLE, """[{"id":"x","title":"from the server"}]""")

        // When — an insert already replayed, kept for queries fetched before it
        store.mutationQueue.enqueueInsert(
            table = TABLE,
            body = rows("""[{"id":"x","title":"from the server"}]"""),
        )
        store.mutationQueue.recordReplayed(id = mutationDao.inserted.single().id, table = TABLE)

        // Then
        assertEquals(
            """[{"id":"x","title":"from the server"}]""",
            store.queryOverlay.overlaidPayloadFlow(TABLE, TABLE).first(),
        )
    }

    @Test
    fun a_queued_upsert_merges_into_the_row_with_its_key() = runTest {
        // Given
        queryCacheDao.seedPayload(TABLE, TABLE, """[{"id":"x","title":"old","done":false}]""")

        // When
        store.mutationQueue.enqueueUpsert(
            table = TABLE,
            bodyJson = rows("""[{"id":"x","title":"new"}]"""),
        )

        // Then
        assertEquals(
            """[{"id":"x","title":"new","done":false}]""",
            store.queryOverlay.overlaidPayloadFlow(TABLE, TABLE).first(),
        )
    }

    @Test
    fun a_queued_upsert_that_ignores_duplicates_leaves_the_row_with_its_key_alone() = runTest {
        // Given
        queryCacheDao.seedPayload(TABLE, TABLE, """[{"id":"x","title":"old"}]""")

        // When
        store.mutationQueue.enqueueUpsert(
            table = TABLE,
            bodyJson = rows("""[{"id":"x","title":"new"}]"""),
            ignoreDuplicates = true,
        )

        // Then
        assertEquals(
            """[{"id":"x","title":"old"}]""",
            store.queryOverlay.overlaidPayloadFlow(TABLE, TABLE).first(),
        )
    }

    @Test
    fun a_never_sent_insert_is_shown_even_when_a_row_shares_its_id() = runTest {
        // Given — a table keyed on (list_id, id), whose ids repeat across lists
        queryCacheDao.seedPayload(TABLE, TABLE, """[{"list_id":"A","id":1,"title":"a1"}]""")

        // When — a new row in list B with id 1, not sent yet
        store.mutationQueue.enqueueInsert(
            table = TABLE,
            body = rows("""[{"list_id":"B","id":1,"title":"b1"}]"""),
        )

        // Then — nothing on the server can be it, so it is shown
        assertEquals(
            """[{"list_id":"A","id":1,"title":"a1"},{"list_id":"B","id":1,"title":"b1"}]""",
            store.queryOverlay.overlaidPayloadFlow(TABLE, TABLE).first(),
        )
    }

    @Test
    fun keyless_rows_are_still_appended() = runTest {
        // Given
        queryCacheDao.seedPayload(TABLE, TABLE, """[{"title":"same"}]""")

        // When
        store.mutationQueue.enqueueInsert(table = TABLE, body = rows("""[{"title":"same"}]"""))

        // Then
        assertEquals(
            """[{"title":"same"},{"title":"same"}]""",
            store.queryOverlay.overlaidPayloadFlow(TABLE, TABLE).first(),
        )
    }

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private companion object {
        const val TABLE = "notes"
    }
}
