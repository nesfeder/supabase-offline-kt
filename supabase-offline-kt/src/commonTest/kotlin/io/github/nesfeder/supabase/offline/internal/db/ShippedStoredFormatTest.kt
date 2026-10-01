// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.EngineJson
import io.github.nesfeder.supabase.offline.internal.postgrest.decodeFilterOrNull
import io.github.nesfeder.supabase.offline.internal.postgrest.encodeFilter
import io.github.nesfeder.supabase.offline.internal.postgrest.replaysFully
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
