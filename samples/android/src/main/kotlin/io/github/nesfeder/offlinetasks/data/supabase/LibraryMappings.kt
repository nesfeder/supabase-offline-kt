// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data.supabase

import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.nesfeder.offlinetasks.data.AppNetworkStatus
import io.github.nesfeder.offlinetasks.data.AppSyncStatus
import io.github.nesfeder.offlinetasks.data.PhotoFile
import io.github.nesfeder.offlinetasks.data.ReadProvenance
import io.github.nesfeder.offlinetasks.data.RejectedTaskMutation
import io.github.nesfeder.offlinetasks.data.RowsSnapshot
import io.github.nesfeder.offlinetasks.data.TaskMutationOperation
import io.github.nesfeder.offlinetasks.data.TaskMutationOutcome
import io.github.nesfeder.offlinetasks.data.TaskRefreshState
import io.github.nesfeder.offlinetasks.data.TaskServerError
import io.github.nesfeder.offlinetasks.data.TaskSyncReport
import io.github.nesfeder.offlinetasks.data.WaitingChange
import io.github.nesfeder.supabase.offline.CachedFile
import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.NetworkStatus
import io.github.nesfeder.supabase.offline.PendingMutation
import io.github.nesfeder.supabase.offline.QuerySnapshot
import io.github.nesfeder.supabase.offline.RefreshState
import io.github.nesfeder.supabase.offline.RejectedMutation
import io.github.nesfeder.supabase.offline.ServerError
import io.github.nesfeder.supabase.offline.SyncReport
import io.github.nesfeder.supabase.offline.SyncStatus
import io.github.nesfeder.supabase.offline.fetchedAt
import io.github.nesfeder.supabase.offline.hasPendingMutations
import io.github.nesfeder.supabase.offline.isFromCache
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

// The library's values in the app's own terms. The screens never see a library type, so these
// are the one place each is read; the sample's tests build them with the testing artifact.

/** This snapshot in the app's terms, its rows decoded by [rows] when there are any. */
internal fun <T> QuerySnapshot.toRowsSnapshot(rows: (PostgrestResult) -> T): RowsSnapshot<T> =
    RowsSnapshot(
        value = result?.let(rows),
        isFromCache = isFromCache,
        fetchedAt = fetchedAt,
        hasPendingMutations = hasPendingMutations,
        refresh = refresh.toTaskRefreshState(),
    )

// --8<-- [start:refresh-state]
/** Where the refresh behind a live read stands, one case for each of the library's four. */
internal fun RefreshState.toTaskRefreshState(): TaskRefreshState =
    when (this) {
        RefreshState.Idle -> TaskRefreshState.Idle // the last refresh succeeded
        RefreshState.Refreshing -> TaskRefreshState.Refreshing // asking, or about to again
        RefreshState.Paused -> TaskRefreshState.Paused // the network is known to be down
        is RefreshState.Stalled -> TaskRefreshState.Stalled(cause) // failing while online
    }
// --8<-- [end:refresh-state]

// --8<-- [start:provenance]
/** Where a one-shot read's rows came from, read from the result's three provenance fields. */
internal fun PostgrestResult.toReadProvenance(): ReadProvenance =
    ReadProvenance(
        isFromCache = isFromCache, // the device's copy, not this request's answer
        fetchedAt = fetchedAt, // when the server last answered this query
        hasPendingMutations = hasPendingMutations, // a queued write changes these rows
    )
// --8<-- [end:provenance]

// --8<-- [start:sync-banner]
/** The library's status in the app's terms; the banner, the badges and the Sync tab read it. */
internal fun SyncStatus.toAppSyncStatus(): AppSyncStatus =
    AppSyncStatus(
        network =
            when (network) {
                NetworkStatus.UNKNOWN -> AppNetworkStatus.Unknown // treated as online
                NetworkStatus.ONLINE -> AppNetworkStatus.Online
                NetworkStatus.OFFLINE -> AppNetworkStatus.Offline
                NetworkStatus.DISABLED -> AppNetworkStatus.Disabled // Simulate offline
            },
        pendingMutationCount = pendingMutationCount,
        rejectedMutationCount = rejectedMutationCount,
        isSyncing = isSyncing,
        lastSyncedAt = lastSyncedAt,
        lastSyncError = lastSyncError?.toAppFailure(LibraryCall.Sync),
    )
// --8<-- [end:sync-banner]

/** What one replay did, every count of it. */
internal fun SyncReport.toTaskSyncReport(): TaskSyncReport =
    TaskSyncReport(
        syncedCount = syncedCount,
        rejected = rejected.map { it.toRejectedTaskMutation() },
        remainingCount = remainingCount,
        alreadyOnServerCount = alreadyOnServerCount,
        heldBehindOtherTablesCount = heldBehindOtherTablesCount,
    )

// --8<-- [start:waiting-change]
/** A queued write as a row of the Sync tab: what it does, to which row, and since when. */
internal fun PendingMutation.toWaitingChange(): WaitingChange =
    WaitingChange(
        id = id,
        table = table,
        operation = operation.toTaskMutationOperation(),
        createdAt = createdAt,
        rowKey = rowKeys.firstOrNull()?.idOrNull(),
        taskTitle = body?.titleOrNull(), // null for a delete
    )
// --8<-- [end:waiting-change]

// --8<-- [start:rejected-list]
/** A refused write as the Sync tab shows it: when it was made and refused, and why. */
internal fun RejectedMutation.toRejectedTaskMutation(): RejectedTaskMutation =
    RejectedTaskMutation(
        id = id,
        table = table,
        operation = operation.toTaskMutationOperation(),
        rowKeys = rowKeys.mapNotNull { it.idOrNull() },
        taskTitle = body?.titleOrNull(),
        // Null when the library refused it itself: it could no longer be replayed as made.
        serverError = serverError?.toTaskServerError(),
        message = message,
        rejectedAt = rejectedAt,
        createdAt = createdAt,
        isRetryable = isRetryable, // false when its body was too long to keep
    )
// --8<-- [end:rejected-list]

/** The server's answer to a refused write, kept as data so it survives a restart. */
internal fun ServerError.toTaskServerError(): TaskServerError =
    TaskServerError(status = status, code = code, message = message, details = details, hint = hint)

/** A write's outcome without the server's answer: applied, or queued under an id. */
internal fun MutationOutcome.toTaskMutationOutcome(): TaskMutationOutcome =
    when (this) {
        is MutationOutcome.Applied -> TaskMutationOutcome.Applied
        is MutationOutcome.Queued -> TaskMutationOutcome.Queued(pendingMutation.id)
    }

/** A cached file's facts in the app's terms. */
internal fun CachedFile.toPhotoFile(): PhotoFile = PhotoFile(path, fetchedAt, sizeBytes)

/** The `id` values in the row keys of these queued writes. */
internal fun List<PendingMutation>.rowIds(): Set<String> =
    flatMapTo(HashSet()) { mutation -> mutation.rowKeys.mapNotNull { it.idOrNull() } }

private fun MutationOperation.toTaskMutationOperation(): TaskMutationOperation =
    when (this) {
        MutationOperation.INSERT -> TaskMutationOperation.Insert
        MutationOperation.UPSERT -> TaskMutationOperation.Upsert
        MutationOperation.UPDATE -> TaskMutationOperation.Update
        MutationOperation.DELETE -> TaskMutationOperation.Delete
    }

/** The `id` of one row key, when it has one. */
private fun JsonObject.idOrNull(): String? = get(ID)?.jsonPrimitive?.contentOrNull

/** The `title` a write's body sets: its first row's for an insert, its value for an update. */
private fun JsonElement.titleOrNull(): String? {
    val row = (this as? JsonArray)?.firstOrNull() ?: this
    return (row as? JsonObject)?.get(TITLE)?.jsonPrimitive?.contentOrNull
}
