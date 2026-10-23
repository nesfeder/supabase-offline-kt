// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data.supabase

import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.nesfeder.offlinetasks.data.AppNetworkStatus
import io.github.nesfeder.offlinetasks.data.ClientUnavailableException
import io.github.nesfeder.offlinetasks.data.ColumnConflict
import io.github.nesfeder.offlinetasks.data.LiveUpdatesStatus
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
import io.github.nesfeder.supabase.offline.RealtimeStatus
import io.github.nesfeder.supabase.offline.testing.appliedOutcome
import io.github.nesfeder.supabase.offline.testing.cacheMissException
import io.github.nesfeder.supabase.offline.testing.cachedFile
import io.github.nesfeder.supabase.offline.testing.conflictedMutation
import io.github.nesfeder.supabase.offline.testing.mutationNotQueueableException
import io.github.nesfeder.supabase.offline.testing.networkDisabledException
import io.github.nesfeder.supabase.offline.testing.offlineClientClosedException
import io.github.nesfeder.supabase.offline.testing.pendingMutation
import io.github.nesfeder.supabase.offline.testing.querySnapshot
import io.github.nesfeder.supabase.offline.testing.queuedOutcome
import io.github.nesfeder.supabase.offline.testing.rejectedMutation
import io.github.nesfeder.supabase.offline.testing.serverError
import io.github.nesfeder.supabase.offline.testing.stalledRealtime
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
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
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
                    .toAppFailure(
                        LibraryCall.Write(Tables.TASKS, TaskMutationOperation.Delete, null)
                    )
            )
        assertEquals("A grouped delete", notQueueable.libraryMessage)
        val offline =
            assertIs<SyncStoppedException>(
                syncFailedException(isConnectivityFailure = true).toAppFailure(LibraryCall.Sync)
            )
        assertTrue(offline.isConnectivityFailure)
        assertIs<ClientUnavailableException>(
            offlineClientClosedException().toAppFailure(LibraryCall.Read)
        )
    }

    @Test
    fun an_illegal_state_other_than_the_closed_client_is_reported_as_it_is() {
        // Given — the library's failure when local data is cleared while a file downloads, an
        // IllegalStateException of its own that says nothing about the client
        val cleared = IllegalStateException("Local data was cleared while the file was downloading")

        // When
        val failure = cleared.toAppFailure(LibraryCall.Storage("u-1/photo.png"))

        // Then — not taken for a closed client
        assertSame(cleared, failure)
    }

    @Test
    fun a_conflicting_write_shows_both_sides_of_each_column_it_conflicts_on() {
        // Given — a title edit parked: the server changed the title and the notes meanwhile
        val parked =
            conflictedMutation(
                id = 12,
                table = Tables.TASKS,
                operation = MutationOperation.UPDATE,
                createdAt = MADE,
                rowKeys = listOf(buildJsonObject { put("id", "task-1") }),
                body = buildJsonObject { put("title", "Buy milk") },
                base = buildJsonObject { put("title", "Milk") },
                server =
                    buildJsonObject {
                        put("id", "task-1")
                        put("title", "Buy oat milk")
                        put("notes", JsonNull)
                    },
                serverVersion = "2026-10-01T09:04:00.000001+00:00",
                changedOnServer = setOf("title", "notes"),
                conflictingColumns = setOf("title"),
                message = "moved",
                conflictedAt = REFUSED,
            )

        // When
        val shown = parked.toConflictedTaskMutation()

        // Then — the device's title names it, and the one conflicting column has both values
        assertEquals("Buy milk", shown.taskTitle)
        assertEquals(listOf("task-1"), shown.rowKeys)
        assertEquals(
            listOf(ColumnConflict("title", theirs = "Buy oat milk", mine = "Buy milk")),
            shown.columns,
        )
        assertEquals(setOf("title", "notes"), shown.changedOnServer)
        assertEquals(MADE, shown.createdAt)
        assertEquals(REFUSED, shown.conflictedAt)
        assertTrue(shown.canKeepMine)
    }

    @Test
    fun a_conflicting_write_whose_row_is_gone_can_only_keep_the_servers_side() {
        // Given — an edit parked on a row gone from the server
        val parked =
            conflictedMutation(
                id = 13,
                table = Tables.TASKS,
                operation = MutationOperation.UPDATE,
                createdAt = MADE,
                body = buildJsonObject { put("title", "Mine") },
                server = null,
                serverVersion = null,
                message = "gone",
                conflictedAt = REFUSED,
            )

        // When
        val shown = parked.toConflictedTaskMutation()

        // Then — named by its own title, with nothing on the server to show beside it
        assertEquals(TaskMutationOperation.Update, shown.operation)
        assertEquals("Mine", shown.taskTitle)
        assertTrue(shown.columns.isEmpty())
        assertFalse(shown.canKeepMine)
    }

    @Test
    fun a_reports_conflict_counts_reach_the_app() {
        // When
        val report =
            syncReport(
                    syncedCount = 2,
                    remainingCount = 0,
                    conflictsResolvedCount = 1,
                    conflictsKeptServerCount = 1,
                    conflicted =
                        listOf(
                            conflictedMutation(
                                id = 4,
                                table = Tables.TASKS,
                                operation = MutationOperation.UPDATE,
                                createdAt = MADE,
                                body = buildJsonObject { put("title", "Mine") },
                                serverVersion = "v2",
                                message = "held",
                                conflictedAt = REFUSED,
                            )
                        ),
                )
                .toTaskSyncReport()

        // Then
        assertEquals(1, report.conflictsResolvedCount)
        assertEquals(1, report.conflictsKeptServerCount)
        assertEquals(listOf(4L), report.conflicted.map { it.id })
        assertEquals("Mine", report.conflicted.single().taskTitle)
    }

    @Test
    fun the_status_counts_the_conflicting_writes() {
        // Given
        val status = syncStatus(network = NetworkStatus.ONLINE, conflictedMutationCount = 2)

        // When
        val shown = status.toAppSyncStatus()

        // Then
        assertEquals(2, shown.conflictedMutationCount)
    }

    private companion object {
        val MADE: Instant = Instant.parse("2026-10-01T09:00:00Z")
        val REFUSED: Instant = Instant.parse("2026-10-01T09:05:00Z")
    }

    @Test
    fun the_realtime_status_maps_onto_the_apps_five_words() {
        assertEquals(LiveUpdatesStatus.Off, RealtimeStatus.Off.toLiveUpdatesStatus())
        assertEquals(LiveUpdatesStatus.Paused, RealtimeStatus.Paused.toLiveUpdatesStatus())
        assertEquals(LiveUpdatesStatus.Connecting, RealtimeStatus.Subscribing.toLiveUpdatesStatus())
        assertEquals(LiveUpdatesStatus.Connected, RealtimeStatus.Subscribed.toLiveUpdatesStatus())
        assertEquals(
            LiveUpdatesStatus.Failed,
            stalledRealtime(IllegalStateException("refused")).toLiveUpdatesStatus(),
        )
    }
}
