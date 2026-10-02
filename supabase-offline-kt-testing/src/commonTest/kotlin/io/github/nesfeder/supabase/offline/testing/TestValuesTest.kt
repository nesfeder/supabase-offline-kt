// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testing

import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.NetworkStatus
import io.github.nesfeder.supabase.offline.isNetworkOrConnectivityFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.io.IOException

/** The factories hand back the library's own types, holding exactly what they were given. */
class TestValuesTest {

    @Test
    fun a_cached_file_holds_its_fields() {
        // Given
        val fetchedAt = Instant.parse("2026-01-01T00:00:00Z")

        // When
        val file = cachedFile(path = "/photos/one.jpg", fetchedAt = fetchedAt, sizeBytes = 7)

        // Then
        assertEquals("/photos/one.jpg", file.path)
        assertEquals(fetchedAt, file.fetchedAt)
        assertEquals(7, file.sizeBytes)
    }

    @Test
    fun the_exceptions_carry_their_message_and_cause() {
        // Given
        val offline = IOException("network down")

        // When
        val miss = cacheMissException("nothing cached")
        val notQueueable = mutationNotQueueableException(cause = offline, message = "not queued")
        val disabled = networkDisabledException("switched off")

        // Then
        assertEquals("nothing cached", miss.message)
        assertEquals("not queued", notQueueable.message)
        assertSame(offline, notQueueable.cause)
        assertTrue(notQueueable.isNetworkOrConnectivityFailure())
        assertEquals("switched off", disabled.message)
    }

    @Test
    fun the_queue_and_sync_values_hold_their_fields() {
        // Given
        val at = Instant.parse("2026-01-01T00:00:00Z")
        val conflict = serverError(status = 409, code = "23505", message = "duplicate key")

        // When
        val pending =
            pendingMutation(
                id = 3,
                table = "todos",
                operation = MutationOperation.INSERT,
                createdAt = at,
            )
        val rejected =
            rejectedMutation(
                id = 4,
                table = "todos",
                operation = MutationOperation.UPDATE,
                createdAt = at,
                serverError = conflict,
                message = "duplicate key",
                rejectedAt = at,
                isRetryable = false,
            )
        val report = syncReport(syncedCount = 2, rejected = listOf(rejected), remainingCount = 1)
        val failure = syncFailedException(isConnectivityFailure = true)
        val status =
            syncStatus(
                network = NetworkStatus.OFFLINE,
                pendingMutationCount = 1,
                lastSyncError = failure,
            )
        val stalled = stalledRefresh(IOException("network down"))
        val snapshot =
            querySnapshot(result = null, isFromCache = true, fetchedAt = at, refresh = stalled)

        // Then
        assertEquals(3, queuedOutcome(pending).pendingMutation.id)
        assertEquals(emptyList(), pending.rowKeys)
        assertEquals(409, rejected.serverError?.status)
        assertFalse(rejected.isRetryable)
        assertEquals(listOf(rejected), report.rejected)
        assertEquals(0, report.alreadyOnServerCount)
        assertEquals(NetworkStatus.OFFLINE, status.network)
        assertSame(failure, status.lastSyncError)
        assertTrue(failure.isNetworkOrConnectivityFailure())
        assertEquals(stalled, snapshot.refresh)
        assertFalse(snapshot.hasPendingMutations)
    }
}
