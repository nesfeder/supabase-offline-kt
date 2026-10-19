// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.testdoubles.FIRST_VERSION
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.GUARDED_TABLE
import io.github.nesfeder.supabase.offline.testdoubles.MOVED_VERSION
import io.github.nesfeder.supabase.offline.testdoubles.cacheRows
import io.github.nesfeder.supabase.offline.testdoubles.enqueueDelete
import io.github.nesfeder.supabase.offline.testdoubles.enqueueInsert
import io.github.nesfeder.supabase.offline.testdoubles.enqueueUpdate
import io.github.nesfeder.supabase.offline.testdoubles.enqueueUpsert
import io.github.nesfeder.supabase.offline.testdoubles.newGuardedStore
import io.github.nesfeder.supabase.offline.testdoubles.rowWithId
import io.github.nesfeder.supabase.offline.testdoubles.taskRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What a queued UPDATE or DELETE of a table with a version column records as its base: the version
 * and the written columns of the one row its filter pins, from the newest cached payload holding
 * both, and nothing whenever it cannot tell that row's version.
 */
class MutationBaseCaptureTest {

    private var now = 1_000L
    private val mutationDao = FakeMutationDao()
    private val store = newGuardedStore(nowMillis = { now }, mutationDao = mutationDao)

    @Test
    fun the_base_is_read_from_the_newest_payload_holding_the_key_and_the_version_column() =
        runTest {
            // Given — an older and a newer payload with the row, and a newest without the column
            store.cacheRows(taskRow(1, "older", version = FIRST_VERSION), cacheKey = "tasks?a")
            now = 2_000L
            store.cacheRows(taskRow(1, "newer", version = MOVED_VERSION), cacheKey = "tasks?b")
            now = 3_000L
            store.cacheRows(
                buildJsonObject {
                    put("id", 1)
                    put("title", "projected")
                },
                cacheKey = "tasks?select=id,title",
            )

            // When
            store.mutationQueue.enqueueUpdate(
                GUARDED_TABLE,
                body("""{"title":"mine"}"""),
                rowWithId(1),
            )

            // Then — the newest copy that has a version, its written columns and the version
            val queued = queued()
            assertEquals(MOVED_VERSION, queued.baseVersion)
            assertEquals(
                """{"title":"newer","updated_at":"$MOVED_VERSION"}""",
                queued.baseJson,
            )
        }

    @Test
    fun a_row_nothing_caches_records_no_base() = runTest {
        // Given — a cached payload without the row
        store.cacheRows(taskRow(2, "other"))

        // When
        store.mutationQueue.enqueueUpdate(GUARDED_TABLE, body("""{"title":"mine"}"""), rowWithId(1))

        // Then
        assertNoBase(queued())
    }

    @Test
    fun a_payload_without_the_version_column_records_no_base() = runTest {
        // Given
        store.cacheRows(
            buildJsonObject {
                put("id", 1)
                put("title", "projected")
            }
        )

        // When
        store.mutationQueue.enqueueUpdate(GUARDED_TABLE, body("""{"title":"mine"}"""), rowWithId(1))

        // Then
        assertNoBase(queued())
    }

    @Test
    fun a_composite_key_pinned_whole_records_its_rows_base() = runTest {
        // Given — a table keyed by two columns
        val composite =
            newGuardedStore(
                primaryKeys = PrimaryKeys { listOf("list_id", "user_id") },
                mutationDao = mutationDao,
            )
        composite.cacheRows(
            buildJsonObject {
                put("list_id", "l-1")
                put("user_id", "u-1")
                put("role", "viewer")
                put("updated_at", FIRST_VERSION)
            }
        )

        // When — once with both key columns pinned, once with one
        composite.mutationQueue.enqueueUpdate(
            GUARDED_TABLE,
            body("""{"role":"editor"}"""),
            FilterDescriptor(
                conditions =
                    listOf(
                        FilterDescriptor.Condition("list_id", "eq", "l-1"),
                        FilterDescriptor.Condition("user_id", "eq", "u-1"),
                    )
            ),
        )
        composite.mutationQueue.enqueueUpdate(
            GUARDED_TABLE,
            body("""{"role":"editor"}"""),
            FilterDescriptor(
                conditions = listOf(FilterDescriptor.Condition("list_id", "eq", "l-1"))
            ),
        )

        // Then
        val (whole, partial) = mutationDao.inserted
        assertEquals(FIRST_VERSION, whole.baseVersion)
        assertEquals("""{"role":"viewer","updated_at":"$FIRST_VERSION"}""", whole.baseJson)
        assertNoBase(partial)
    }

    @Test
    fun a_filter_naming_the_version_column_records_no_base() = runTest {
        // Given
        store.cacheRows(taskRow(1, "theirs"))

        // When
        store.mutationQueue.enqueueUpdate(
            GUARDED_TABLE,
            body("""{"title":"mine"}"""),
            FilterDescriptor(
                conditions =
                    listOf(
                        FilterDescriptor.Condition("id", "eq", "1"),
                        FilterDescriptor.Condition("updated_at", "eq", FIRST_VERSION),
                    )
            ),
        )

        // Then — a second top-level filter on it would be dropped on the wire
        assertNoBase(queued())
    }

    @Test
    fun a_delete_records_the_version_only() = runTest {
        // Given
        store.cacheRows(taskRow(1, "theirs"))

        // When
        store.mutationQueue.enqueueDelete(GUARDED_TABLE, rowWithId(1))

        // Then
        val queued = queued()
        assertEquals(FIRST_VERSION, queued.baseVersion)
        assertEquals("""{"updated_at":"$FIRST_VERSION"}""", queued.baseJson)
    }

    @Test
    fun an_insert_or_an_upsert_never_records_a_base() = runTest {
        // Given
        store.cacheRows(taskRow(1, "theirs"))
        val rows = Json.parseToJsonElement("""[{"id":1,"title":"mine"}]""") as JsonArray

        // When
        store.mutationQueue.enqueueInsert(GUARDED_TABLE, rows)
        store.mutationQueue.enqueueUpsert(GUARDED_TABLE, rows)

        // Then
        mutationDao.inserted.forEach(::assertNoBase)
    }

    @Test
    fun a_table_without_a_version_column_records_no_base() = runTest {
        // Given
        store.cacheRows(taskRow(1, "theirs"), table = "notes", cacheKey = "notes?select=*")

        // When
        store.mutationQueue.enqueueUpdate("notes", body("""{"title":"mine"}"""), rowWithId(1))

        // Then
        assertNoBase(queued())
    }

    @Test
    fun a_number_version_is_stored_as_its_text() = runTest {
        // Given — an integer version column
        store.cacheRows(
            buildJsonObject {
                put("id", 1)
                put("title", "theirs")
                put("updated_at", 41)
            }
        )

        // When
        store.mutationQueue.enqueueUpdate(GUARDED_TABLE, body("""{"title":"mine"}"""), rowWithId(1))

        // Then
        assertEquals("41", queued().baseVersion)
    }

    private fun queued(): MutationRow = mutationDao.inserted.single()

    private fun assertNoBase(row: MutationRow) {
        assertNull(row.baseVersion, "base version of ${row.operation}")
        assertNull(row.baseJson, "base row of ${row.operation}")
    }

    private fun body(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject
}
