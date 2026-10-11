// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data.supabase

import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.nesfeder.offlinetasks.data.AppNetworkStatus
import io.github.nesfeder.offlinetasks.data.NetworkSwitchedOffException
import io.github.nesfeder.offlinetasks.data.NotSavedOnDeviceException
import io.github.nesfeder.offlinetasks.data.PhotoFile
import io.github.nesfeder.offlinetasks.data.SyncStoppedException
import io.github.nesfeder.offlinetasks.data.Tables
import io.github.nesfeder.offlinetasks.data.TaskMutationOperation
import io.github.nesfeder.offlinetasks.data.TaskMutationOutcome
import io.github.nesfeder.offlinetasks.data.TaskRefreshState
import io.github.nesfeder.offlinetasks.data.WriteNeedsConnectionException
import io.github.nesfeder.offlinetasks.testing.FakeSupabaseServer
import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.NetworkStatus
import io.github.nesfeder.supabase.offline.testing.appliedOutcome
import io.github.nesfeder.supabase.offline.testing.cacheMissException
import io.github.nesfeder.supabase.offline.testing.cachedFile
import io.github.nesfeder.supabase.offline.testing.mutationNotQueueableException
import io.github.nesfeder.supabase.offline.testing.networkDisabledException
import io.github.nesfeder.supabase.offline.testing.pendingMutation
import io.github.nesfeder.supabase.offline.testing.querySnapshot
import io.github.nesfeder.supabase.offline.testing.queuedOutcome
import io.github.nesfeder.supabase.offline.testing.rejectedMutation
import io.github.nesfeder.supabase.offline.testing.serverError
import io.github.nesfeder.supabase.offline.testing.stalledRefresh
import io.github.nesfeder.supabase.offline.testing.syncFailedException
import io.github.nesfeder.supabase.offline.testing.syncReport
import io.github.nesfeder.supabase.offline.testing.syncStatus
import io.ktor.http.Headers
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The library's values in the app's terms. Each library value is built with the testing artifact's
 * factories, as an app's own tests build them: their constructors are the library's alone.
 */
class LibraryMappingsTest {

    // --8<-- [start:factories]
    @Test
    fun a_status_whose_last_sync_stopped_says_why() {
        // Given — the library's status, built by the testing artifact
        val status =
            syncStatus(
                network = NetworkStatus.DISABLED,
                pendingMutationCount = 2,
                rejectedMutationCount = 1,
                lastSyncError = syncFailedException(httpStatus = 503, code = "PGRST002"),
            )

        // When
        val shown = status.toAppSyncStatus()

        // Then — the banner's terms: Simulate offline, two waiting, the server's answer
        assertEquals(AppNetworkStatus.Disabled, shown.network)
        assertEquals(2, shown.pendingMutationCount)
        assertEquals(1, shown.rejectedMutationCount)
        val stopped = assertIs<SyncStoppedException>(shown.lastSyncError)
        assertEquals(503, stopped.httpStatus)
        assertEquals("PGRST002", stopped.code)
        assertFalse(stopped.isConnectivityFailure)
    }
    // --8<-- [end:factories]

    @Test
    fun a_refused_write_keeps_its_times_its_rows_and_whether_it_can_be_retried() {
        // Given
        val refused =
            rejectedMutation(
                id = 7,
                table = Tables.TASKS,
                operation = MutationOperation.INSERT,
                createdAt = MADE,
                rowKeys = listOf(buildJsonObject { put("id", "task-1") }),
                body = buildJsonArray { add(buildJsonObject { put("title", "   ") }) },
                serverError = serverError(status = 400, code = "23514", message = "check"),
                message = "check",
                rejectedAt = REFUSED,
                isRetryable = false,
            )

        // When
        val shown = refused.toRejectedTaskMutation()

        // Then
        assertEquals(listOf("task-1"), shown.rowKeys)
        assertEquals("   ", shown.taskTitle)
        assertEquals(MADE, shown.createdAt)
        assertEquals(REFUSED, shown.rejectedAt)
        assertFalse(shown.isRetryable)
        assertEquals("23514", shown.serverError?.code)
    }

    @Test
    fun a_waiting_write_is_a_row_with_its_title_and_its_time() {
        // Given
        val waiting =
            pendingMutation(
                id = 3,
                table = Tables.TASKS,
                operation = MutationOperation.UPDATE,
                createdAt = MADE,
                rowKeys = listOf(buildJsonObject { put("id", "task-2") }),
                body = buildJsonObject { put("title", "Buy milk") },
            )

        // When
        val row = waiting.toWaitingChange()

        // Then
        assertEquals(TaskMutationOperation.Update, row.operation)
        assertEquals("task-2", row.rowKey)
        assertEquals("Buy milk", row.taskTitle)
        assertEquals(MADE, row.createdAt)
    }

    @Test
    fun a_sync_report_keeps_every_count() {
        // When
        val report =
            syncReport(
                    syncedCount = 3,
                    remainingCount = 2,
                    alreadyOnServerCount = 1,
                    heldBehindOtherTablesCount = 2,
                )
                .toTaskSyncReport()

        // Then
        assertEquals(3, report.syncedCount)
        assertEquals(2, report.remainingCount)
        assertEquals(1, report.alreadyOnServerCount)
        assertEquals(2, report.heldBehindOtherTablesCount)
        assertTrue(report.rejected.isEmpty())
    }

    @Test
    fun a_stalled_snapshot_keeps_its_cause_and_its_provenance() {
        // Given
        val cause = IOException("no route")
        val snapshot =
            querySnapshot(
                result = null,
                isFromCache = true,
                fetchedAt = MADE,
                hasPendingMutations = true,
                refresh = stalledRefresh(cause),
            )

        // When
        val shown = snapshot.toRowsSnapshot { "rows" }

        // Then — nothing known yet, from the device, stalled on the network
        assertNull(shown.value)
        assertTrue(shown.isFromCache)
        assertEquals(MADE, shown.fetchedAt)
        assertTrue(shown.hasPendingMutations)
        assertEquals(cause, assertIs<TaskRefreshState.Stalled>(shown.refresh).cause)
    }

    @Test
    fun a_cached_file_keeps_its_path_its_time_and_its_size() {
        assertEquals(
            PhotoFile("/cache/a.jpg", MADE, 1_024),
            cachedFile("/cache/a.jpg", MADE, 1_024).toPhotoFile(),
        )
    }

    @Test
    fun outcomes_say_applied_or_queued_with_the_queues_id() {
        // Given
        val server = FakeSupabaseServer()
        val supabase = server.supabaseClient()
        val result = PostgrestResult("[]", Headers.Empty, supabase.postgrest)

        // Then
        assertEquals(TaskMutationOutcome.Applied, appliedOutcome(result).toTaskMutationOutcome())
        assertEquals(
            TaskMutationOutcome.Queued(9),
            queuedOutcome(pendingMutation(9, Tables.TASKS, MutationOperation.INSERT, MADE))
                .toTaskMutationOutcome(),
        )
        runBlocking { supabase.close() }
    }

    @Test
    fun each_of_the_librarys_failures_has_its_own_kind() {
        // Then — one kind each, so each has its own sentence on screen
        assertIs<NetworkSwitchedOffException>(
            networkDisabledException().toAppFailure(LibraryCall.Read)
        )
        assertIs<NotSavedOnDeviceException>(cacheMissException().toAppFailure(LibraryCall.Read))
        val notQueueable =
            assertIs<WriteNeedsConnectionException>(
                mutationNotQueueableException(IOException("offline"), "A grouped delete")
                    .toAppFailure(LibraryCall.Write(Tables.TASKS, TaskMutationOperation.Delete, null))
            )
        assertEquals("A grouped delete", notQueueable.libraryMessage)
        val offline =
            assertIs<SyncStoppedException>(
                syncFailedException(isConnectivityFailure = true).toAppFailure(LibraryCall.Sync)
            )
        assertTrue(offline.isConnectivityFailure)
    }

    private companion object {
        val MADE: Instant = Instant.parse("2026-10-01T09:00:00Z")
        val REFUSED: Instant = Instant.parse("2026-10-01T09:05:00Z")
    }
}
