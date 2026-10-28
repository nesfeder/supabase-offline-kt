// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(OfflineInternal::class)

package io.github.nesfeder.supabase.offline.internal.testing

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.FileUploadResponse
import io.github.nesfeder.supabase.offline.OfflineInternal
import io.github.nesfeder.supabase.offline.RefreshState
import io.github.nesfeder.supabase.offline.SyncStopReason
import io.github.nesfeder.supabase.offline.fetchedAt
import io.github.nesfeder.supabase.offline.hasPendingMutations
import io.github.nesfeder.supabase.offline.isFromCache
import io.github.nesfeder.supabase.offline.pendingRowKeys
import io.github.nesfeder.supabase.offline.testdoubles.TEST_SUPABASE_KEY
import io.github.nesfeder.supabase.offline.testdoubles.TEST_SUPABASE_URL
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The constructors behind the testing artifact's factories, where they do more than construct: a
 * cached result's provenance, and a report's stop reason, the one given or the one the library
 * gives a report with those counts.
 */
class ValueConstructorsTest {

    @Test
    fun the_upload_values_hold_what_they_were_given() {
        // Given
        val at = Instant.fromEpochMilliseconds(1_700_000_000_000)
        val response = FileUploadResponse(id = "id-1", path = "a.jpg", key = "photos/a.jpg")
        val error = ValueConstructors.serverError(400, "Unauthorized", "no", null, null)

        // When
        val pending =
            ValueConstructors.pendingUpload(1, "photos", "a.jpg", at, 3, "image/png", true)
        val refused =
            ValueConstructors.rejectedUpload(
                2,
                "photos",
                "b.jpg",
                at,
                4,
                null,
                false,
                error,
                "no",
                at,
                true,
            )
        val report =
            ValueConstructors.syncReport(
                1,
                emptyList(),
                0,
                0,
                0,
                0,
                0,
                emptyList(),
                SyncStopReason.Completed,
                rejectedUploads = listOf(refused),
            )

        // Then
        assertEquals(response, ValueConstructors.appliedUpload(response).response)
        assertEquals(pending, ValueConstructors.queuedUpload(pending).pendingUpload)
        assertEquals(listOf(1L, 3L), listOf(pending.id, pending.sizeBytes))
        assertEquals("image/png", pending.contentType)
        assertTrue(pending.upsert)
        assertEquals(listOf("photos", "b.jpg"), listOf(refused.bucketId, refused.path))
        assertEquals(error, refused.serverError)
        assertTrue(refused.isRetryable)
        assertEquals(listOf(refused), report.rejectedUploads)
        assertEquals(
            emptyList(),
            ValueConstructors.syncReport(
                    1,
                    emptyList(),
                    0,
                    0,
                    0,
                    0,
                    0,
                    emptyList(),
                    SyncStopReason.Completed,
                )
                .rejectedUploads,
        )
    }

    @Test
    fun a_cached_result_reads_as_the_cache_s_with_the_time_and_flag_given() {
        // Given — a client with an engine, which supabase-kt's Postgrest needs
        val postgrest =
            createSupabaseClient(TEST_SUPABASE_URL, TEST_SUPABASE_KEY) {
                    httpEngine = MockEngine { respondOk() }
                    install(Postgrest)
                }
                .postgrest
        val fetchedAt = Instant.parse("2026-01-01T08:30:00.123Z")

        // When
        val pending = ValueConstructors.cachedResult(ROWS, postgrest, fetchedAt, true)
        val clean = ValueConstructors.cachedResult(ROWS, postgrest, fetchedAt, false)

        // Then — the provenance reads back as given, and the rows are the data given
        assertEquals(ROWS, pending.data)
        assertTrue(pending.isFromCache)
        assertEquals(fetchedAt, pending.fetchedAt)
        assertTrue(pending.hasPendingMutations)
        assertTrue(clean.isFromCache)
        assertFalse(clean.hasPendingMutations)
    }

    @Test
    fun a_snapshot_and_a_cached_result_built_with_row_keys_name_those_rows() {
        // Given
        val postgrest =
            createSupabaseClient(TEST_SUPABASE_URL, TEST_SUPABASE_KEY) {
                    httpEngine = MockEngine { respondOk() }
                    install(Postgrest)
                }
                .postgrest
        val fetchedAt = Instant.parse("2026-01-01T08:30:00.123Z")
        val keys = setOf(buildJsonObject { put("id", 1) }, buildJsonObject { put("id", "a\nb") })

        // When
        val named =
            ValueConstructors.querySnapshot(null, true, fetchedAt, true, RefreshState.Idle, keys)
        val unnamed =
            ValueConstructors.querySnapshot(null, true, fetchedAt, true, RefreshState.Idle)
        val result = ValueConstructors.cachedResult(ROWS, postgrest, fetchedAt, true, keys)
        val withoutKeys = ValueConstructors.cachedResult(ROWS, postgrest, fetchedAt, true)

        // Then — the keys read back whole, a control character in a value included
        assertEquals(keys, named.pendingRowKeys)
        assertEquals(emptySet(), unnamed.pendingRowKeys)
        assertEquals(keys, result.pendingRowKeys)
        assertTrue(result.hasPendingMutations)
        assertEquals(emptySet(), withoutKeys.pendingRowKeys)
    }

    @Test
    fun a_report_built_without_a_stop_reason_has_the_one_the_library_gives_its_counts() {
        // Given / When
        val sent = ValueConstructors.syncReport(1, emptyList(), 0, 0, 0)
        val held = ValueConstructors.syncReport(0, emptyList(), 2, 0, 2)
        val parked = ValueConstructors.syncReport(1, emptyList(), 0, 0, 0, 1, 1, emptyList())

        // Then
        assertEquals(SyncStopReason.Completed, sent.stopReason)
        assertEquals(SyncStopReason.HeldBehindOtherTables, held.stopReason)
        assertEquals(SyncStopReason.Completed, parked.stopReason)
    }

    @Test
    fun a_report_and_a_failure_reason_hold_what_they_were_given() {
        // Given
        val failure = ValueConstructors.syncFailedException("stopped", 503, "PGRST000", false)
        val onDevice = ValueConstructors.syncFailedException("stopped", null, null, false)

        // When
        val server = ValueConstructors.serverFailureStop(failure)
        val local = ValueConstructors.localFailureStop(onDevice)
        val report =
            ValueConstructors.syncReport(0, emptyList(), 3, 0, 0, 0, 0, emptyList(), server)

        // Then
        assertSame(failure, server.error)
        assertSame(onDevice, local.error)
        assertSame(server, report.stopReason)
        assertEquals(3, report.remainingCount)
    }

    private companion object {
        const val ROWS = """[{"id":1,"title":"Buy milk"}]"""
    }
}
