// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.OfflineJson
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The strings a queued write stores in `filter_json` and `delta_json` are read back by the engine
 * long after they were written — across app updates, onto whatever engine version is installed
 * then. These cases pin both directions against the literal strings in [ShippedDatabaseFixture]:
 * the engine still decodes what an earlier install wrote, and still writes the same bytes.
 */
class ShippedStoredFormatTest {

    @Test
    fun stored_filter_descriptors_decode_to_the_filters_that_were_queued() {
        // Given
        val updateFilterJson = ShippedDatabaseFixture.UPDATE_FILTER_JSON
        val deleteFilterJson = ShippedDatabaseFixture.DELETE_FILTER_JSON

        // When
        val updateFilter = FilterEngine.decodeOrNull(updateFilterJson, OfflineJson)
        val deleteFilter = FilterEngine.decodeOrNull(deleteFilterJson, OfflineJson)

        // Then — both decode, and both still replay in full
        assertEquals(ShippedDatabaseFixture.UPDATE_FILTER, updateFilter)
        assertEquals(ShippedDatabaseFixture.DELETE_FILTER, deleteFilter)
        assertTrue(FilterEngine.replaysFully(ShippedDatabaseFixture.UPDATE_FILTER))
        assertTrue(FilterEngine.replaysFully(ShippedDatabaseFixture.DELETE_FILTER))
    }

    @Test
    fun stored_delta_declarations_decode_to_the_declarations_that_were_queued() {
        // Given
        val deltaJson = ShippedDatabaseFixture.UPDATE_DELTA_JSON

        // When
        val deltas = decodeNumericDeltaColumns(deltaJson, OfflineJson)

        // Then
        assertEquals(ShippedDatabaseFixture.UPDATE_DELTAS, deltas)
    }

    @Test
    fun the_engine_still_writes_the_stored_formats_byte_for_byte() {
        // Given
        val updateFilter = ShippedDatabaseFixture.UPDATE_FILTER
        val deleteFilter = ShippedDatabaseFixture.DELETE_FILTER
        val deltas = ShippedDatabaseFixture.UPDATE_DELTAS

        // When
        val encodedUpdateFilter = FilterEngine.encode(updateFilter, OfflineJson)
        val encodedDeleteFilter = FilterEngine.encode(deleteFilter, OfflineJson)
        val encodedDeltas = encodeNumericDeltaColumns(deltas, OfflineJson)

        // Then
        assertEquals(ShippedDatabaseFixture.UPDATE_FILTER_JSON, encodedUpdateFilter)
        assertEquals(ShippedDatabaseFixture.DELETE_FILTER_JSON, encodedDeleteFilter)
        assertEquals(ShippedDatabaseFixture.UPDATE_DELTA_JSON, encodedDeltas)
    }

    @Test
    fun stored_operation_names_are_the_ones_the_syncer_dispatches_on() {
        // Given / When
        val operations = ShippedDatabaseFixture.MUTATION_ROWS.map { it.operation }.toSet()

        // Then
        assertEquals(setOf("INSERT", "UPSERT", "UPDATE", "DELETE"), operations)
        assertEquals(
            setOf(
                MutationOperation.INSERT,
                MutationOperation.UPSERT,
                MutationOperation.UPDATE,
                MutationOperation.DELETE,
            ),
            operations,
        )
    }
}
