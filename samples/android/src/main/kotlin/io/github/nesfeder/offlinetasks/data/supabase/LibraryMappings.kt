// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data.supabase

import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.nesfeder.offlinetasks.data.AppNetworkStatus
import io.github.nesfeder.offlinetasks.data.AppSyncStatus
import io.github.nesfeder.offlinetasks.data.ColumnConflict
import io.github.nesfeder.offlinetasks.data.ConflictedTaskMutation
import io.github.nesfeder.offlinetasks.data.LiveUpdatesStatus
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
import io.github.nesfeder.supabase.offline.ConflictedMutation
import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.NetworkStatus
import io.github.nesfeder.supabase.offline.PendingMutation
import io.github.nesfeder.supabase.offline.QuerySnapshot
import io.github.nesfeder.supabase.offline.RealtimeStatus
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

// The library's values in the app's own terms. The screens never see a library type, so these
// are the one place each is read; the sample's tests build them with the testing artifact.

// --8<-- [start:refresh-state]
/** This snapshot in the app's terms, its rows decoded by [rows] when there are any. */
internal fun <T> QuerySnapshot.toRowsSnapshot(rows: (PostgrestResult) -> T): RowsSnapshot<T> =
    RowsSnapshot(
        value = result?.let(rows),
        isFromCache = isFromCache,
        fetchedAt = fetchedAt,
        hasPendingMutations = hasPendingMutations,
        refresh = refresh.toTaskRefreshState(),
    )

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
// --8<-- [start:realtime-status]
/**
 * The plugin's Realtime status in the app's words: off, paused for want of a user or a network,
 * connecting while the join is in flight, connected, or failed while the plugin waits to try again.
 */
internal fun RealtimeStatus.toLiveUpdatesStatus(): LiveUpdatesStatus =
    when (this) {
        RealtimeStatus.Off -> LiveUpdatesStatus.Off
        RealtimeStatus.Paused -> LiveUpdatesStatus.Paused
        RealtimeStatus.Subscribing -> LiveUpdatesStatus.Connecting
        RealtimeStatus.Subscribed -> LiveUpdatesStatus.Connected
        is RealtimeStatus.Stalled -> LiveUpdatesStatus.Failed
    }

// --8<-- [end:realtime-status]

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
        conflictedMutationCount = conflictedMutationCount,
        isSyncing = isSyncing,
        lastSyncedAt = lastSyncedAt,
        lastSyncError = lastSyncError?.toAppFailure(LibraryCall.Sync),
    )

// --8<-- [end:sync-banner]

// --8<-- [start:sync-report]
/** What one replay did, every count of it. */
internal fun SyncReport.toTaskSyncReport(): TaskSyncReport =
    TaskSyncReport(
        syncedCount = syncedCount,
        rejected = rejected.map { it.toRejectedTaskMutation() },
        remainingCount = remainingCount,
        alreadyOnServerCount = alreadyOnServerCount,
        heldBehindOtherTablesCount = heldBehindOtherTablesCount,
        conflictsResolvedCount = conflictsResolvedCount,
        conflictsKeptServerCount = conflictsKeptServerCount,
        conflicted = conflicted.map { it.toConflictedTaskMutation() },
    )

// --8<-- [end:sync-report]

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

// --8<-- [start:rejected-row]
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

/** The server's answer to a refused write, kept as data so it survives a restart. */
internal fun ServerError.toTaskServerError(): TaskServerError =
    TaskServerError(status = status, code = code, message = message, details = details, hint = hint)

// --8<-- [end:rejected-row]

// --8<-- [start:conflicted-list]
/**
 * A parked write as the Sync tab shows it: which task, and for each column both sides changed, the
 * server's value beside this device's.
 */
internal fun ConflictedMutation.toConflictedTaskMutation(): ConflictedTaskMutation {
    val mine = body as? JsonObject // the columns this device sets; null for a delete
    return ConflictedTaskMutation(
        id = id,
        table = table,
        operation = operation.toTaskMutationOperation(),
        rowKeys = rowKeys.mapNotNull { it.idOrNull() },
        taskTitle = body?.titleOrNull() ?: server?.titleOrNull(), // the server row's otherwise
        columns =
            conflictingColumns.sorted().map { column ->
                ColumnConflict(
                    column = column,
                    theirs = server?.get(column)?.asText(),
                    mine = mine?.get(column)?.asText(),
                )
            },
        changedOnServer = changedOnServer,
        createdAt = createdAt,
        conflictedAt = conflictedAt,
        // Keep mine needs the server's version, and the body for an update.
        canKeepMine =
            serverVersion != null && (operation == MutationOperation.DELETE || body != null),
    )
}

// --8<-- [end:conflicted-list]

/** A column's value as text: a string's content, a number's or a boolean's text; null for null. */
private fun JsonElement.asText(): String? =
    when (this) {
        JsonNull -> null
        is JsonPrimitive -> content
        else -> toString()
    }

/** A write's outcome without the server's answer: applied, or queued under an id. */
internal fun MutationOutcome.toTaskMutationOutcome(): TaskMutationOutcome =
    when (this) {
        is MutationOutcome.Applied -> TaskMutationOutcome.Applied
        is MutationOutcome.Queued -> TaskMutationOutcome.Queued(pendingMutation.id)
    }

// --8<-- [start:cached-file]
/** A cached file's facts in the app's terms: where it is, when it was fetched, how large. */
internal fun CachedFile.toPhotoFile(): PhotoFile = PhotoFile(path, fetchedAt, sizeBytes)

// --8<-- [end:cached-file]

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
