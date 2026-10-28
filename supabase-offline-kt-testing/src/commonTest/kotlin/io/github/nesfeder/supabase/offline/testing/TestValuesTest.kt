// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testing

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.jan.supabase.storage.FileUploadResponse
import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.NetworkStatus
import io.github.nesfeder.supabase.offline.RealtimeStatus
import io.github.nesfeder.supabase.offline.RefreshState
import io.github.nesfeder.supabase.offline.SyncStopReason
import io.github.nesfeder.supabase.offline.fetchedAt
import io.github.nesfeder.supabase.offline.hasPendingMutations
import io.github.nesfeder.supabase.offline.isFromCache
import io.github.nesfeder.supabase.offline.isNetworkOrConnectivityFailure
import io.github.nesfeder.supabase.offline.pendingRowKeys
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.http.Headers
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
        val closed = offlineClientClosedException()

        // Then
        assertEquals("nothing cached", miss.message)
        assertEquals("not queued", notQueueable.message)
        assertSame(offline, notQueueable.cause)
        assertTrue(notQueueable.isNetworkOrConnectivityFailure())
        assertEquals("switched off", disabled.message)
        assertEquals("This offline client is closed", closed.message)
        assertNull(closed.cause)
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
        // Given — every field distinct from its neighbors of the same type
        val key = buildJsonObject { put("id", "t1") }
        val base = buildJsonObject {
            put("title", "Buy milk")
            put("notes", "2 l")
            put("updated_at", "v1")
        }
        val local = buildJsonObject {
            put("title", "Mine")
            put("notes", "2 l")
        }
        val server = buildJsonObject {
            put("id", "t1")
            put("title", "Buy oat milk")
            put("notes", "1 l")
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
                changedOnServer = setOf("title", "notes"),
                conflictingColumns = setOf("title"),
            )
        val bare =
            mutationConflict(
                mutationId = 8,
                table = "lists",
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
        assertEquals(setOf("title", "notes"), conflict.changedOnServer)
        assertEquals(setOf("title"), conflict.conflictingColumns)
        assertEquals(8, bare.mutationId)
        assertEquals("lists", bare.table)
        assertEquals(MutationOperation.DELETE, bare.operation)
        assertEquals(JsonObject(emptyMap()), bare.base)
        assertNull(bare.local)
        assertNull(bare.server)
        assertEquals(emptySet(), bare.changedOnServer)
        assertEquals(emptySet(), bare.conflictingColumns)
    }

    @Test
    fun a_conflicted_mutation_holds_every_field() {
        // Given — every field distinct from its neighbors of the same type
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
                changedOnServer = setOf("title", "notes"),
                conflictingColumns = setOf("title"),
                message = "held by the resolver",
                conflictedAt = conflictedAt,
            )
        val bare =
            conflictedMutation(
                id = 10,
                table = "lists",
                operation = MutationOperation.DELETE,
                createdAt = conflictedAt,
                message = "gone",
                conflictedAt = createdAt,
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
        assertEquals(setOf("title", "notes"), entry.changedOnServer)
        assertEquals(setOf("title"), entry.conflictingColumns)
        assertEquals("held by the resolver", entry.message)
        assertEquals(conflictedAt, entry.conflictedAt)
        assertEquals(10, bare.id)
        assertEquals("lists", bare.table)
        assertEquals(MutationOperation.DELETE, bare.operation)
        assertEquals(conflictedAt, bare.createdAt)
        assertEquals("gone", bare.message)
        assertEquals(createdAt, bare.conflictedAt)
        assertEquals(emptyList(), bare.rowKeys)
        assertNull(bare.body)
        assertEquals(JsonObject(emptyMap()), bare.base)
        assertNull(bare.server)
        assertNull(bare.serverVersion)
        assertEquals(emptySet(), bare.changedOnServer)
        assertEquals(emptySet(), bare.conflictingColumns)
    }

    @Test
    fun the_overloads_hold_every_field_beside_the_existing_factories() {
        // Given — every field distinct from its neighbors of the same type
        val createdAt = Instant.parse("2026-10-18T13:00:00Z")
        val syncedAt = Instant.parse("2026-10-18T13:20:00Z")
        val key = buildJsonObject { put("id", "t1") }
        val body = buildJsonObject { put("title", "Mine") }
        val rejected =
            rejectedMutation(
                id = 14,
                table = "tasks",
                operation = MutationOperation.UPDATE,
                createdAt = createdAt,
                message = "refused",
                rejectedAt = syncedAt,
            )
        val parked =
            conflictedMutation(
                id = 11,
                table = "tasks",
                operation = MutationOperation.UPDATE,
                createdAt = createdAt,
                message = "held",
                conflictedAt = syncedAt,
            )
        val failure = syncFailedException(message = "stopped")

        // When
        val pending =
            pendingMutation(
                id = 12,
                table = "tasks",
                operation = MutationOperation.UPDATE,
                createdAt = createdAt,
                rowKeys = listOf(key),
                body = body,
                baseVersion = "v1",
            )
        val report =
            syncReport(
                syncedCount = 1,
                rejected = listOf(rejected),
                remainingCount = 2,
                alreadyOnServerCount = 3,
                heldBehindOtherTablesCount = 4,
                conflictsResolvedCount = 5,
                conflictsKeptServerCount = 6,
                conflicted = listOf(parked),
            )
        val status =
            syncStatus(
                network = NetworkStatus.OFFLINE,
                pendingMutationCount = 1,
                rejectedMutationCount = 2,
                isSyncing = true,
                lastSyncedAt = syncedAt,
                lastSyncError = failure,
                conflictedMutationCount = 3,
            )
        // The existing factories: pendingMutation and syncStatus cannot reach the overloads, whose
        // new parameter is required; syncReport compiles unambiguously, and Kotlin picks the
        // existing factory, the candidate with fewer unspecified defaults. Both build the same
        // value.
        val plainPending =
            pendingMutation(
                id = 13,
                table = "tasks",
                operation = MutationOperation.UPDATE,
                createdAt = createdAt,
            )
        val plainReport = syncReport(syncedCount = 1)
        val plainStatus = syncStatus()

        // Then
        assertEquals(12, pending.id)
        assertEquals("tasks", pending.table)
        assertEquals(MutationOperation.UPDATE, pending.operation)
        assertEquals(createdAt, pending.createdAt)
        assertEquals(listOf(key), pending.rowKeys)
        assertEquals(body, pending.body)
        assertEquals("v1", pending.baseVersion)
        assertEquals(1, report.syncedCount)
        assertEquals(listOf(rejected), report.rejected)
        assertEquals(2, report.remainingCount)
        assertEquals(3, report.alreadyOnServerCount)
        assertEquals(4, report.heldBehindOtherTablesCount)
        assertEquals(5, report.conflictsResolvedCount)
        assertEquals(6, report.conflictsKeptServerCount)
        assertEquals(listOf(parked), report.conflicted)
        assertEquals(NetworkStatus.OFFLINE, status.network)
        assertEquals(1, status.pendingMutationCount)
        assertEquals(2, status.rejectedMutationCount)
        assertTrue(status.isSyncing)
        assertEquals(syncedAt, status.lastSyncedAt)
        assertSame(failure, status.lastSyncError)
        assertEquals(3, status.conflictedMutationCount)
        assertNull(plainPending.baseVersion)
        assertEquals(
            0 to 0,
            plainReport.conflictsResolvedCount to plainReport.conflictsKeptServerCount,
        )
        assertEquals(emptyList(), plainReport.conflicted)
        assertEquals(0, plainStatus.conflictedMutationCount)
    }

    @Test
    fun the_upload_values_hold_their_fields() {
        // Given
        val at = Instant.fromEpochMilliseconds(1_700_000_000_000)
        val response = FileUploadResponse(id = "id-1", path = "a/b.jpg", key = "photos/a/b.jpg")

        // When
        val pending =
            pendingUpload(
                id = 7,
                bucketId = "photos",
                path = "a/b.jpg",
                createdAt = at,
                sizeBytes = 3,
            )
        val refused =
            rejectedUpload(
                id = 8,
                bucketId = "photos",
                path = "a/c.jpg",
                createdAt = at,
                sizeBytes = 4,
                contentType = "image/jpeg",
                upsert = true,
                serverError = serverError(status = 400, code = "Unauthorized", message = "no"),
                message = "no",
                rejectedAt = at,
                isRetryable = false,
            )
        val report = syncReport(syncedCount = 1, rejectedUploads = listOf(refused))

        // Then
        assertEquals(response, appliedUploadOutcome(response).response)
        assertEquals(response, appliedUploadOutcome(response).responseOrNull())
        assertEquals(pending, queuedUploadOutcome(pending).pendingUpload)
        assertTrue(queuedUploadOutcome(pending).isQueued)
        assertEquals(null, pending.contentType)
        assertFalse(pending.upsert)
        assertEquals("photos", refused.bucketId)
        assertEquals("a/c.jpg", refused.path)
        assertEquals(4, refused.sizeBytes)
        assertEquals("image/jpeg", refused.contentType)
        assertTrue(refused.upsert)
        assertEquals(400, refused.serverError?.status)
        assertFalse(refused.isRetryable)
        assertEquals(listOf(refused), report.rejectedUploads)
        assertEquals(SyncStopReason.Completed, report.stopReason)
        assertEquals(emptyList(), syncReport(stopReason = SyncStopReason.Completed).rejectedUploads)
    }

    @Test
    fun a_cached_result_reads_as_the_cache_s_at_the_time_given() {
        // Given — a client with an engine, which supabase-kt's Postgrest needs
        val supabase =
            createSupabaseClient("https://unit-test.supabase.co", "unit-test-anon-key") {
                httpEngine = MockEngine { respondOk() }
                install(Postgrest)
            }
        val fetchedAt = Instant.parse("2026-01-01T08:30:00.123Z")

        // When
        val result = cachedResult(ROWS, supabase, fetchedAt, hasPendingMutations = true)
        val clean = cachedResult(data = ROWS, supabase = supabase, fetchedAt = fetchedAt)
        val fromServer = PostgrestResult(ROWS, Headers.Empty, supabase.postgrest)

        // Then — the provenance reads back as given, and an unstamped result as supabase-kt's
        assertEquals(ROWS, result.data)
        assertTrue(result.isFromCache)
        assertEquals(fetchedAt, result.fetchedAt)
        assertTrue(result.hasPendingMutations)
        assertTrue(clean.isFromCache)
        assertEquals(fetchedAt, clean.fetchedAt)
        assertFalse(clean.hasPendingMutations)
        assertFalse(fromServer.isFromCache)
        assertNull(fromServer.fetchedAt)
    }

    @Test
    fun the_overloads_that_take_row_keys_name_those_rows_and_read_as_pending_when_there_are_any() {
        // Given
        val supabase =
            createSupabaseClient("https://unit-test.supabase.co", "unit-test-anon-key") {
                httpEngine = MockEngine { respondOk() }
                install(Postgrest)
            }
        val fetchedAt = Instant.parse("2026-01-01T08:30:00.123Z")
        val keys = setOf(buildJsonObject { put("id", "a1") })

        // When
        val snapshot = querySnapshot(null, isFromCache = true, fetchedAt, pendingRowKeys = keys)
        val noKeys = querySnapshot(null, isFromCache = true, fetchedAt, pendingRowKeys = emptySet())
        val result = cachedResult(ROWS, supabase, fetchedAt, pendingRowKeys = keys)
        val clean = cachedResult(ROWS, supabase, fetchedAt, pendingRowKeys = emptySet())
        val plain = querySnapshot(result = null, isFromCache = true, fetchedAt = fetchedAt)

        // Then
        assertEquals(keys, snapshot.pendingRowKeys)
        assertTrue(snapshot.hasPendingMutations)
        assertEquals(RefreshState.Idle, snapshot.refresh)
        assertFalse(noKeys.hasPendingMutations)
        assertEquals(keys, result.pendingRowKeys)
        assertTrue(result.hasPendingMutations)
        assertTrue(result.isFromCache)
        assertFalse(clean.hasPendingMutations)
        assertEquals(emptySet(), clean.pendingRowKeys)
        assertEquals(emptySet(), plain.pendingRowKeys)
    }

    @Test
    fun a_sync_report_carries_the_stop_reason_it_was_given_or_the_one_its_counts_imply() {
        // Given
        val failure = syncFailedException(message = "stopped", httpStatus = 503, code = "PGRST000")
        val onDevice = syncFailedException(message = "stopped on the device")

        // When
        val failed = syncReport(remainingCount = 2, stopReason = serverFailureStop(failure))
        val local = localFailureStop(onDevice)
        val disabled = syncReport(stopReason = SyncStopReason.NetworkDisabled)
        val held = syncReport(syncedCount = 0, remainingCount = 1, heldBehindOtherTablesCount = 1)

        // Then
        val reason = failed.stopReason as SyncStopReason.ServerFailure
        assertSame(failure, reason.error)
        assertEquals(2 to 0, failed.remainingCount to failed.syncedCount)
        assertSame(onDevice, local.error)
        assertEquals(SyncStopReason.NetworkDisabled, disabled.stopReason)
        assertEquals(SyncStopReason.HeldBehindOtherTables, held.stopReason)
        assertEquals(SyncStopReason.Completed, syncReport(syncedCount = 1).stopReason)
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

    private companion object {
        const val ROWS = """[{"id":1,"title":"Buy milk"}]"""
    }
}
