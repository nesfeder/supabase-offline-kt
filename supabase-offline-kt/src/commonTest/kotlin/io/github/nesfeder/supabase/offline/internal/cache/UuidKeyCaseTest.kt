// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

import io.github.nesfeder.supabase.offline.internal.db.MutationOperation
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.mutationqueue.DefaultPrimaryKeys
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryAdmission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/**
 * A key that is a UUID names the same row whatever the letter case it is written in, as a Postgres
 * `uuid` compares it: a client that writes UUIDs in capitals (as Swift's `uuidString` does) sees
 * neither a row twice nor its own edit miss the row the server sent in lower case.
 */
class UuidKeyCaseTest {

    private val overlay =
        MutationOverlay(
            json = Json,
            admission = QueryAdmission.EVERY_ROW,
            primaryKeys = DefaultPrimaryKeys,
            mayAlreadyHold = { true },
        )

    private fun mutation(operation: String, body: String, filter: String? = null) =
        MutationRow(
            table = TABLE,
            operation = operation,
            bodyJson = body,
            filterJson = filter,
            createdAtMs = 0L,
            syncedAtMs = null,
        )

    @Test
    fun a_row_written_in_capitals_and_sent_back_in_lower_case_is_shown_once() {
        // Given — the server's copy of a row the client inserted with a capitalised key
        val cached = """[{"id":"$LOWER","title":"a"}]"""

        // When — the insert, which the server already holds, is overlaid
        val shown =
            overlay.overlay(
                cached,
                listOf(mutation(MutationOperation.INSERT, """[{"id":"$UPPER","title":"a"}]""")),
            )

        // Then
        assertEquals(cached, shown)
    }

    @Test
    fun an_upsert_keyed_in_capitals_replaces_the_row_sent_in_lower_case() {
        // Given
        val cached = """[{"id":"$LOWER","title":"a"}]"""

        // When
        val shown =
            overlay.overlay(
                cached,
                listOf(mutation(MutationOperation.UPSERT, """[{"id":"$UPPER","title":"b"}]""")),
            )

        // Then — one row, changed as the upsert wrote it
        assertEquals("""[{"id":"$UPPER","title":"b"}]""", shown)
    }

    @Test
    fun an_update_filtered_by_a_capitalised_key_changes_the_row_sent_in_lower_case() {
        // Given
        val cached = """[{"id":"$LOWER","title":"a"}]"""
        val byKey =
            FilterEngine.encode(
                FilterDescriptor(
                    conditions = listOf(FilterDescriptor.Condition("id", "eq", UPPER))
                ),
                Json,
            )

        // When
        val shown =
            overlay.overlay(
                cached,
                listOf(mutation(MutationOperation.UPDATE, """{"title":"b"}""", byKey)),
            )

        // Then
        assertEquals("""[{"id":"$LOWER","title":"b"}]""", shown)
    }

    private companion object {
        const val TABLE = "notes"
        const val LOWER = "3f1c2d4e-5a6b-4c7d-8e9f-0a1b2c3d4e5f"
        const val UPPER = "3F1C2D4E-5A6B-4C7D-8E9F-0A1B2C3D4E5F"
    }
}
