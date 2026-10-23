// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(OfflineInternal::class)

package io.github.nesfeder.supabase.offline.internal.testing

import io.github.nesfeder.supabase.offline.OfflineInternal
import io.github.nesfeder.supabase.offline.SyncStopReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The constructors the testing artifact's factories call build a report's stop reason as the
 * library would: the one given, or the one the library gives a report with those counts.
 */
class ValueConstructorsTest {

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
}
