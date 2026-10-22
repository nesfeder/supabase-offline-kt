// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.internal.EngineJson
import io.github.nesfeder.supabase.offline.internal.mutationqueue.StoredConflictList
import io.github.nesfeder.supabase.offline.internal.mutationqueue.StoredRejectionList
import io.github.nesfeder.supabase.offline.internal.postgrest.decodeFilterOrNull
import io.github.nesfeder.supabase.offline.internal.postgrest.encodeFilter
import io.github.nesfeder.supabase.offline.internal.postgrest.replaysFully
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

/**
 * The strings a queued write stores in `filter_json` are read back by the engine long after they
 * were written — across app updates, onto whatever engine version is installed then. These cases
 * pin both directions against the literal strings in [ShippedDatabaseFixture]: the engine still
 * decodes what an earlier install wrote, and still writes the same bytes.
 */
class ShippedStoredFormatTest {

    @Test
    fun stored_filter_descriptors_decode_to_the_filters_that_were_queued() {
        // Given
        val updateFilterJson = ShippedDatabaseFixture.UPDATE_FILTER_JSON
        val deleteFilterJson = ShippedDatabaseFixture.DELETE_FILTER_JSON

        // When
        val updateFilter = decodeFilterOrNull(updateFilterJson, EngineJson)
        val deleteFilter = decodeFilterOrNull(deleteFilterJson, EngineJson)

        // Then — both decode, and both still replay in full
        assertEquals(ShippedDatabaseFixture.UPDATE_FILTER, updateFilter)
        assertEquals(ShippedDatabaseFixture.DELETE_FILTER, deleteFilter)
        assertTrue(replaysFully(ShippedDatabaseFixture.UPDATE_FILTER))
        assertTrue(replaysFully(ShippedDatabaseFixture.DELETE_FILTER))
    }

    @Test
    fun the_engine_still_writes_the_stored_formats_byte_for_byte() {
        // Given
        val updateFilter = ShippedDatabaseFixture.UPDATE_FILTER
        val deleteFilter = ShippedDatabaseFixture.DELETE_FILTER

        // When
        val encodedUpdateFilter = encodeFilter(updateFilter, EngineJson)
        val encodedDeleteFilter = encodeFilter(deleteFilter, EngineJson)

        // Then
        assertEquals(ShippedDatabaseFixture.UPDATE_FILTER_JSON, encodedUpdateFilter)
        assertEquals(ShippedDatabaseFixture.DELETE_FILTER_JSON, encodedDeleteFilter)
    }

    @Test
    fun a_stored_conflicted_list_decodes_to_the_entries_that_were_parked() {
        // Given — a list as this build writes it
        val payload =
            """{"version":1,"entries":[{"id":3,"table":"todos","operation":"UPDATE",""" +
                """"bodyJson":"{\"done\":true}","filterJson":${JsonPrimitive(ShippedDatabaseFixture.UPDATE_FILTER_JSON)},""" +
                """"createdAtMs":1,"baseVersion":"2026-10-18T13:00:27.486123+00:00",""" +
                """"serverVersion":"2026-10-18T13:05:00+00:00","changedOnServer":["done"],""" +
                """"conflictingColumns":["done"],"message":"moved","conflictedAtMs":2}]}"""

        // When
        val list = EngineJson.decodeFromString(StoredConflictList.serializer(), payload)

        // Then — the queued filter still decodes, and the versions read back verbatim
        val entry = list.entries.single()
        assertEquals(1, list.version)
        assertEquals(
            ShippedDatabaseFixture.UPDATE_FILTER,
            decodeFilterOrNull(entry.filterJson, EngineJson),
        )
        assertEquals("2026-10-18T13:00:27.486123+00:00", entry.baseVersion)
        assertEquals("2026-10-18T13:05:00+00:00", entry.serverVersion)
        assertEquals(EngineJson.encodeToString(StoredConflictList.serializer(), list), payload)
    }

    @Test
    fun a_stored_rejected_list_decodes_to_the_entries_that_were_refused_with_their_base() {
        // Given — a list as this build writes it, and one as 0.2.0 wrote it, with no base
        val payload = ShippedDatabaseFixture.REJECTED_LIST_JSON
        val fromEarlierBuild = ShippedDatabaseFixture.REJECTED_LIST_JSON_WITHOUT_BASE

        // When
        val list = EngineJson.decodeFromString(StoredRejectionList.serializer(), payload)
        val earlier =
            EngineJson.decodeFromString(StoredRejectionList.serializer(), fromEarlierBuild)

        // Then — the base reads back verbatim and re-queues with the write; the earlier build's
        // entry reads back with none; and both still encode as they were written
        val entry = list.entries.single()
        assertEquals(1, list.version)
        assertEquals("2026-10-18T13:00:27.486123+00:00", entry.baseVersion)
        assertEquals(
            """{"done":false,"updated_at":"2026-10-18T13:00:27.486123+00:00"}""",
            entry.baseJson,
        )
        val requeued = assertNotNull(entry.toRequeuedRow(createdAtMs = 3, owner = null))
        assertEquals(entry.baseVersion to entry.baseJson, requeued.baseVersion to requeued.baseJson)
        assertEquals(
            ShippedDatabaseFixture.UPDATE_FILTER,
            decodeFilterOrNull(requeued.filterJson, EngineJson),
        )
        assertEquals(null to null, earlier.entries.single().let { it.baseVersion to it.baseJson })
        assertEquals(EngineJson.encodeToString(StoredRejectionList.serializer(), list), payload)
        assertEquals(
            EngineJson.encodeToString(StoredRejectionList.serializer(), earlier),
            fromEarlierBuild,
        )
    }

    @Test
    fun stored_operation_names_are_the_ones_the_replayer_dispatches_on() {
        // Given / When
        val operations = ShippedDatabaseFixture.MUTATION_ROWS.map { it.operation }.toSet()

        // Then
        assertEquals(setOf("INSERT", "UPSERT", "UPDATE", "DELETE"), operations)
        assertEquals(
            setOf(
                MutationOperation.INSERT.storedName,
                MutationOperation.UPSERT.storedName,
                MutationOperation.UPDATE.storedName,
                MutationOperation.DELETE.storedName,
            ),
            operations,
        )
    }
}
