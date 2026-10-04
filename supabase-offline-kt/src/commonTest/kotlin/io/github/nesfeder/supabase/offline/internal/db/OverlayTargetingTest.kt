// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.enqueueDelete
import io.github.nesfeder.supabase.offline.testdoubles.enqueueInsert
import io.github.nesfeder.supabase.offline.testdoubles.enqueueUpdate
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.github.nesfeder.supabase.offline.testdoubles.overlayCachedPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A pending UPDATE or DELETE touches a cached row only when the engine can tell the row matches its
 * filter. A condition it cannot evaluate — a pattern, containment on an array column, a column the
 * row does not carry — leaves the row as the server sent it, and is not a failure.
 */
class OverlayTargetingTest {

    private val logger = RecordingLogSink()
    private val store =
        newTestStore(mutationDao = FakeMutationDao(), engineLogger = logger.asEngineLogger())

    private fun condition(column: String, operator: String, value: String) =
        FilterDescriptor(conditions = listOf(FilterDescriptor.Condition(column, operator, value)))

    @Test
    fun a_pending_update_filtered_by_a_pattern_patches_no_row() = runTest {
        // Given — `title LIKE 'milk'` matches only an exact "milk" on the server
        val cached =
            """[{"id":1,"title":"buy milk"},{"id":2,"title":"Milk"},{"id":3,"title":"milk"}]"""
        store.mutationQueue.enqueueUpdate(
            TABLE,
            buildJsonObject { put("done", true) },
            filter = condition("title", "like", "milk"),
        )

        // When
        val overlaid = store.queryOverlay.overlayCachedPayload(TABLE, KEY, cached)

        // Then — a pattern is not evaluated, so no row is guessed at
        assertEquals(cached, overlaid)
    }

    @Test
    fun a_pending_delete_filtered_by_a_pattern_removes_no_row() = runTest {
        // Given
        val cached = """[{"id":1,"title":"Draft 2"},{"id":2,"title":"my draft"}]"""
        store.mutationQueue.enqueueDelete(TABLE, filter = condition("title", "like", "Draft"))

        // When
        val overlaid = store.queryOverlay.overlayCachedPayload(TABLE, KEY, cached)

        // Then
        assertEquals(cached, overlaid)
    }

    @Test
    fun a_pending_update_on_an_array_column_is_not_a_failure_and_other_writes_show() = runTest {
        // Given — an insert, then an update filtered with containment on an array column
        store.mutationQueue.enqueueInsert(
            TABLE,
            buildJsonArray { add(buildJsonObject { put("id", 2) }) },
        )
        store.mutationQueue.enqueueUpdate(
            TABLE,
            buildJsonObject { put("done", true) },
            filter = condition("tags", "cs", "{a}"),
        )

        // When
        val overlaid =
            store.queryOverlay.overlayCachedPayload(TABLE, KEY, """[{"id":1,"tags":["a"]}]""")

        // Then — the insert shows, the array row is untouched, and nothing was skipped as broken
        assertEquals("""[{"id":1,"tags":["a"]},{"id":2}]""", overlaid)
        assertEquals(
            emptyList(),
            logger.entries.filter { entry -> "Overlay skipped" in entry.message },
        )
    }

    @Test
    fun a_pending_update_leaves_a_row_without_the_filtered_column_alone() = runTest {
        // Given — the cached query selected only id and title
        val cached = """[{"id":1,"title":"a"}]"""
        store.mutationQueue.enqueueUpdate(
            TABLE,
            buildJsonObject { put("title", "b") },
            filter = condition("owner_id", "neq", "u-2"),
        )

        // When
        val overlaid = store.queryOverlay.overlayCachedPayload(TABLE, KEY, cached)

        // Then
        assertEquals(cached, overlaid)
    }

    @Test
    fun a_pending_update_with_neq_does_not_patch_a_null_column() = runTest {
        // Given — `owner_id <> 'u-2'` is unknown for a NULL owner, so the server leaves that row
        val cached =
            """[{"id":1,"owner_id":null,"title":"a"},{"id":2,"owner_id":"u-1","title":"a"}]"""
        store.mutationQueue.enqueueUpdate(
            TABLE,
            buildJsonObject { put("title", "b") },
            filter = condition("owner_id", "neq", "u-2"),
        )

        // When
        val overlaid =
            Json.parseToJsonElement(store.queryOverlay.overlayCachedPayload(TABLE, KEY, cached))
                as JsonArray

        // Then
        assertEquals(
            Json.parseToJsonElement(
                """[{"id":1,"owner_id":null,"title":"a"},{"id":2,"owner_id":"u-1","title":"b"}]"""
            ),
            overlaid,
        )
    }

    private companion object {
        const val TABLE = "items"
        const val KEY = "items"
    }
}
