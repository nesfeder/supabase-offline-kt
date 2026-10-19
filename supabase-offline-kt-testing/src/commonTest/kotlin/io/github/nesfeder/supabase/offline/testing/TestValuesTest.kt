// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testing

import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.NetworkStatus
import io.github.nesfeder.supabase.offline.RealtimeStatus
import io.github.nesfeder.supabase.offline.isNetworkOrConnectivityFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
        val held = syncReport(syncedCount = 0, remainingCount = 2, heldBehindOtherTablesCount = 2)
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
        assertEquals(0, report.heldBehindOtherTablesCount)
        assertEquals(2, held.heldBehindOtherTablesCount)
        assertEquals(NetworkStatus.OFFLINE, status.network)
        assertSame(failure, status.lastSyncError)
        assertTrue(failure.isNetworkOrConnectivityFailure())
        assertEquals(stalled, snapshot.refresh)
        assertFalse(snapshot.hasPendingMutations)
    }

    @Test
    fun a_mutation_conflict_holds_every_field() {
        // Given
        val key = buildJsonObject { put("id", "t1") }
        val base = buildJsonObject {
            put("title", "Buy milk")
            put("updated_at", "v1")
        }
        val local = buildJsonObject { put("title", "Mine") }
        val server = buildJsonObject {
            put("id", "t1")
            put("title", "Buy oat milk")
            put("updated_at", "v2")
        }

        // When
        val conflict =
            mutationConflict(
                mutationId = 7,
                table = "tasks",
                operation = MutationOperation.UPDATE,
                rowKey = key,
                base = base,
                local = local,
                server = server,
                changedOnServer = setOf("title"),
                conflictingColumns = setOf("title"),
            )
        val bare =
            mutationConflict(
                mutationId = 8,
                table = "tasks",
                operation = MutationOperation.DELETE,
                rowKey = key,
            )

        // Then
        assertEquals(7, conflict.mutationId)
        assertEquals("tasks", conflict.table)
        assertEquals(MutationOperation.UPDATE, conflict.operation)
        assertEquals(key, conflict.rowKey)
        assertEquals(base, conflict.base)
        assertEquals(local, conflict.local)
        assertEquals(server, conflict.server)
        assertEquals(setOf("title"), conflict.changedOnServer)
        assertEquals(setOf("title"), conflict.conflictingColumns)
        assertEquals(JsonObject(emptyMap()), bare.base)
        assertNull(bare.local)
        assertNull(bare.server)
        assertEquals(emptySet(), bare.changedOnServer)
        assertEquals(emptySet(), bare.conflictingColumns)
    }

    @Test
    fun a_conflicted_mutation_holds_every_field() {
        // Given
        val createdAt = Instant.parse("2026-10-18T13:00:00Z")
        val conflictedAt = Instant.parse("2026-10-18T13:10:00Z")
        val key = buildJsonObject { put("id", "t1") }
        val body = buildJsonObject { put("title", "Mine") }
        val base = buildJsonObject {
            put("title", "Buy milk")
            put("updated_at", "v1")
        }
        val server = buildJsonObject {
            put("id", "t1")
            put("title", "Buy oat milk")
            put("updated_at", "v2")
        }

        // When
        val entry =
            conflictedMutation(
                id = 9,
                table = "tasks",
                operation = MutationOperation.UPDATE,
                createdAt = createdAt,
                rowKeys = listOf(key),
                body = body,
                base = base,
                server = server,
                serverVersion = "v2",
                changedOnServer = setOf("title"),
                conflictingColumns = setOf("title"),
                message = "held by the resolver",
                conflictedAt = conflictedAt,
            )
        val bare =
            conflictedMutation(
                id = 10,
                table = "tasks",
                operation = MutationOperation.DELETE,
                createdAt = createdAt,
                message = "gone",
                conflictedAt = conflictedAt,
            )

        // Then
        assertEquals(9, entry.id)
        assertEquals("tasks", entry.table)
        assertEquals(MutationOperation.UPDATE, entry.operation)
        assertEquals(createdAt, entry.createdAt)
        assertEquals(listOf(key), entry.rowKeys)
        assertEquals(body, entry.body)
        assertEquals(base, entry.base)
        assertEquals(server, entry.server)
        assertEquals("v2", entry.serverVersion)
        assertEquals(setOf("title"), entry.changedOnServer)
        assertEquals(setOf("title"), entry.conflictingColumns)
        assertEquals("held by the resolver", entry.message)
        assertEquals(conflictedAt, entry.conflictedAt)
        assertEquals(emptyList(), bare.rowKeys)
        assertNull(bare.body)
        assertEquals(JsonObject(emptyMap()), bare.base)
        assertNull(bare.server)
        assertNull(bare.serverVersion)
        assertEquals(emptySet(), bare.changedOnServer)
        assertEquals(emptySet(), bare.conflictingColumns)
    }

    @Test
    fun the_overloads_carry_the_conflict_fields_and_the_old_factories_leave_them_empty() {
        // Given
        val at = Instant.parse("2026-10-18T13:00:00Z")
        val parked =
            conflictedMutation(
                id = 11,
                table = "tasks",
                operation = MutationOperation.UPDATE,
                createdAt = at,
                message = "held",
                conflictedAt = at,
            )

        // When
        val pending =
            pendingMutation(
                id = 12,
                table = "tasks",
                operation = MutationOperation.UPDATE,
                createdAt = at,
                baseVersion = "v1",
            )
        val report =
            syncReport(
                syncedCount = 3,
                remainingCount = 1,
                alreadyOnServerCount = 1,
                heldBehindOtherTablesCount = 1,
                conflictsResolvedCount = 2,
                conflictsKeptServerCount = 1,
                conflicted = listOf(parked),
            )
        val status = syncStatus(pendingMutationCount = 1, conflictedMutationCount = 4)
        val plainPending =
            pendingMutation(
                id = 13,
                table = "tasks",
                operation = MutationOperation.UPDATE,
                createdAt = at,
            )
        val plainReport = syncReport(syncedCount = 1)
        val plainStatus = syncStatus()

        // Then
        assertEquals("v1", pending.baseVersion)
        assertEquals(12, pending.id)
        assertEquals(3, report.syncedCount)
        assertEquals(1, report.remainingCount)
        assertEquals(1, report.alreadyOnServerCount)
        assertEquals(1, report.heldBehindOtherTablesCount)
        assertEquals(2, report.conflictsResolvedCount)
        assertEquals(1, report.conflictsKeptServerCount)
        assertEquals(listOf(parked), report.conflicted)
        assertEquals(1, status.pendingMutationCount)
        assertEquals(4, status.conflictedMutationCount)
        assertNull(plainPending.baseVersion)
        assertEquals(
            0 to 0,
            plainReport.conflictsResolvedCount to plainReport.conflictsKeptServerCount,
        )
        assertEquals(emptyList(), plainReport.conflicted)
        assertEquals(0, plainStatus.conflictedMutationCount)
    }

    @Test
    fun the_stalled_realtime_status_carries_its_cause() {
        // Given / When
        val cause = IOException("socket lost")
        val stalled = stalledRealtime(cause)

        // Then
        assertSame(cause, stalled.cause)
        assertTrue(stalled is RealtimeStatus.Stalled)
    }
}
