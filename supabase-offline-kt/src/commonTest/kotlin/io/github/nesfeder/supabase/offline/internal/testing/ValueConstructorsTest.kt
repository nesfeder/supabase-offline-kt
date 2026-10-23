// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(OfflineInternal::class)

package io.github.nesfeder.supabase.offline.internal.testing

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.OfflineInternal
import io.github.nesfeder.supabase.offline.SyncStopReason
import io.github.nesfeder.supabase.offline.fetchedAt
import io.github.nesfeder.supabase.offline.hasPendingMutations
import io.github.nesfeder.supabase.offline.isFromCache
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

/**
 * The constructors behind the testing artifact's factories, where they do more than construct: a
 * cached result's provenance, and a report's stop reason, the one given or the one the library
 * gives a report with those counts.
 */
class ValueConstructorsTest {

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
