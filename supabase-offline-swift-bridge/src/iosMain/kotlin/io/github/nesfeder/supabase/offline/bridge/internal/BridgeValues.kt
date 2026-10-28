// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge.internal

import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.nesfeder.supabase.offline.CachedFile
import io.github.nesfeder.supabase.offline.ConflictedMutation
import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.NetworkStatus
import io.github.nesfeder.supabase.offline.PendingMutation
import io.github.nesfeder.supabase.offline.PendingUpload
import io.github.nesfeder.supabase.offline.QuerySnapshot
import io.github.nesfeder.supabase.offline.RefreshState
import io.github.nesfeder.supabase.offline.RejectedMutation
import io.github.nesfeder.supabase.offline.RejectedUpload
import io.github.nesfeder.supabase.offline.ServerError
import io.github.nesfeder.supabase.offline.SyncFailedException
import io.github.nesfeder.supabase.offline.SyncReport
import io.github.nesfeder.supabase.offline.SyncStatus
import io.github.nesfeder.supabase.offline.SyncStopReason
import io.github.nesfeder.supabase.offline.UploadOutcome
import io.github.nesfeder.supabase.offline.bridge.BridgeCachedFile
import io.github.nesfeder.supabase.offline.bridge.BridgeConflictedMutation
import io.github.nesfeder.supabase.offline.bridge.BridgeErrorKind
import io.github.nesfeder.supabase.offline.bridge.BridgeMutationOperation
import io.github.nesfeder.supabase.offline.bridge.BridgeMutationResult
import io.github.nesfeder.supabase.offline.bridge.BridgeNetworkStatus
import io.github.nesfeder.supabase.offline.bridge.BridgePendingMutation
import io.github.nesfeder.supabase.offline.bridge.BridgePendingUpload
import io.github.nesfeder.supabase.offline.bridge.BridgeQueryResult
import io.github.nesfeder.supabase.offline.bridge.BridgeQuerySnapshot
import io.github.nesfeder.supabase.offline.bridge.BridgeRefreshKind
import io.github.nesfeder.supabase.offline.bridge.BridgeRefreshState
import io.github.nesfeder.supabase.offline.bridge.BridgeRejectedMutation
import io.github.nesfeder.supabase.offline.bridge.BridgeRejectedUpload
import io.github.nesfeder.supabase.offline.bridge.BridgeServerError
import io.github.nesfeder.supabase.offline.bridge.BridgeSyncReport
import io.github.nesfeder.supabase.offline.bridge.BridgeSyncStatus
import io.github.nesfeder.supabase.offline.bridge.BridgeSyncStopKind
import io.github.nesfeder.supabase.offline.bridge.BridgeSyncStopReason
import io.github.nesfeder.supabase.offline.bridge.BridgeUploadResult
import io.github.nesfeder.supabase.offline.fetchedAt
import io.github.nesfeder.supabase.offline.hasPendingMutations
import io.github.nesfeder.supabase.offline.isFromCache
import io.github.nesfeder.supabase.offline.pendingRowKeys
import kotlinx.serialization.json.JsonArray

/*
 * The library's values as the bridge's. Each library type is mapped here, once: a change to one of
 * them, such as a field added to SyncReport or SyncStatus, is answered in this file alone.
 */

/** A one-shot read's or a function call's answer. */
internal fun PostgrestResult.toBridge(): BridgeQueryResult =
    BridgeQueryResult(
        data = data,
        count = countOrNull(),
        isFromCache = isFromCache,
        fetchedAtEpochMillis = fetchedAt?.toEpochMilliseconds(),
        hasPendingMutations = hasPendingMutations,
        pendingRowKeysJson = JsonArray(pendingRowKeys.toList()).toString(),
    )

/** One emission of a live read. */
internal fun QuerySnapshot.toBridge(): BridgeQuerySnapshot =
    BridgeQuerySnapshot(
        data = result?.data,
        isFromCache = isFromCache,
        fetchedAtEpochMillis = fetchedAt?.toEpochMilliseconds(),
        hasPendingMutations = hasPendingMutations,
        refresh = refresh.toBridge(),
        pendingRowKeysJson = JsonArray(pendingRowKeys.toList()).toString(),
    )

/** A refresh state; a stall carries its cause as an error value. */
private fun RefreshState.toBridge(): BridgeRefreshState =
    when (this) {
        RefreshState.Idle -> BridgeRefreshState(BridgeRefreshKind.IDLE, null)
        RefreshState.Refreshing -> BridgeRefreshState(BridgeRefreshKind.REFRESHING, null)
        RefreshState.Paused -> BridgeRefreshState(BridgeRefreshKind.PAUSED, null)
        is RefreshState.Stalled ->
            BridgeRefreshState(BridgeRefreshKind.STALLED, bridgeErrorOf(cause))
    }

/** A write's outcome. */
internal fun MutationOutcome.toBridge(): BridgeMutationResult =
    when (this) {
        is MutationOutcome.Applied ->
            BridgeMutationResult(isQueued = false, applied = result.toBridge(), queued = null)
        is MutationOutcome.Queued ->
            BridgeMutationResult(
                isQueued = true,
                applied = null,
                queued = pendingMutation.toBridge(),
            )
    }

/** An upload's outcome; Storage's response as JSON text. */
internal fun UploadOutcome.toBridge(): BridgeUploadResult =
    when (this) {
        is UploadOutcome.Applied ->
            BridgeUploadResult(
                isQueued = false,
                applied = encodeUploadResponse(response),
                queued = null,
            )
        is UploadOutcome.Queued ->
            BridgeUploadResult(isQueued = true, applied = null, queued = pendingUpload.toBridge())
    }

/** A queued upload. */
internal fun PendingUpload.toBridge(): BridgePendingUpload =
    BridgePendingUpload(
        id = id,
        bucketId = bucketId,
        path = path,
        createdAtEpochMillis = createdAt.toEpochMilliseconds(),
        sizeBytes = sizeBytes,
        contentType = contentType,
        upsert = upsert,
    )

/** A refused upload. */
internal fun RejectedUpload.toBridge(): BridgeRejectedUpload =
    BridgeRejectedUpload(
        id = id,
        bucketId = bucketId,
        path = path,
        createdAtEpochMillis = createdAt.toEpochMilliseconds(),
        sizeBytes = sizeBytes,
        contentType = contentType,
        upsert = upsert,
        serverError = serverError?.toBridge(),
        message = message,
        rejectedAtEpochMillis = rejectedAt.toEpochMilliseconds(),
        isRetryable = isRetryable,
    )

/** A queued write. */
internal fun PendingMutation.toBridge(): BridgePendingMutation =
    BridgePendingMutation(
        id = id,
        table = table,
        operation = operation.toBridge(),
        createdAtEpochMillis = createdAt.toEpochMilliseconds(),
        rowKeysJson = JsonArray(rowKeys).toString(),
        bodyJson = body?.toString(),
        baseVersion = baseVersion,
    )

/** A rejected write. */
internal fun RejectedMutation.toBridge(): BridgeRejectedMutation =
    BridgeRejectedMutation(
        id = id,
        table = table,
        operation = operation.toBridge(),
        createdAtEpochMillis = createdAt.toEpochMilliseconds(),
        rowKeysJson = JsonArray(rowKeys).toString(),
        bodyJson = body?.toString(),
        serverError = serverError?.toBridge(),
        message = message,
        rejectedAtEpochMillis = rejectedAt.toEpochMilliseconds(),
        isRetryable = isRetryable,
    )

/** A write parked on the conflicted list. */
internal fun ConflictedMutation.toBridge(): BridgeConflictedMutation =
    BridgeConflictedMutation(
        id = id,
        table = table,
        operation = operation.toBridge(),
        createdAtEpochMillis = createdAt.toEpochMilliseconds(),
        rowKeysJson = JsonArray(rowKeys).toString(),
        bodyJson = body?.toString(),
        baseJson = base.toString(),
        serverJson = server?.toString(),
        serverVersion = serverVersion,
        changedOnServer = changedOnServer.toList(),
        conflictingColumns = conflictingColumns.toList(),
        message = message,
        conflictedAtEpochMillis = conflictedAt.toEpochMilliseconds(),
    )

/** The server's answer to a rejected write. */
private fun ServerError.toBridge(): BridgeServerError =
    BridgeServerError(
        status = status,
        code = code,
        message = message,
        details = details,
        hint = hint,
    )

/** A write's operation. */
internal fun MutationOperation.toBridge(): BridgeMutationOperation =
    when (this) {
        MutationOperation.INSERT -> BridgeMutationOperation.INSERT
        MutationOperation.UPSERT -> BridgeMutationOperation.UPSERT
        MutationOperation.UPDATE -> BridgeMutationOperation.UPDATE
        MutationOperation.DELETE -> BridgeMutationOperation.DELETE
    }

/** What one sync call did. */
internal fun SyncReport.toBridge(): BridgeSyncReport =
    BridgeSyncReport(
        syncedCount = syncedCount,
        rejected = rejected.map { it.toBridge() },
        remainingCount = remainingCount,
        alreadyOnServerCount = alreadyOnServerCount,
        heldBehindOtherTablesCount = heldBehindOtherTablesCount,
        conflictsResolvedCount = conflictsResolvedCount,
        conflictsKeptServerCount = conflictsKeptServerCount,
        conflicted = conflicted.map { it.toBridge() },
        stopReason = stopReason.toBridge(),
        rejectedUploads = rejectedUploads.map { it.toBridge() },
    )

/**
 * Why a sync call returned; a failure carries its error as the sync status's last error does. The
 * library's set is open, so a reason added there without a kind here reads as
 * [BridgeSyncStopKind.UNKNOWN] until it gets one.
 */
private fun SyncStopReason.toBridge(): BridgeSyncStopReason =
    when (this) {
        SyncStopReason.Completed -> BridgeSyncStopReason(BridgeSyncStopKind.COMPLETED, null)
        SyncStopReason.NetworkUnavailable ->
            BridgeSyncStopReason(BridgeSyncStopKind.NETWORK_UNAVAILABLE, null)
        is SyncStopReason.ServerFailure ->
            BridgeSyncStopReason(BridgeSyncStopKind.SERVER_FAILURE, syncFailedError(error))
        is SyncStopReason.LocalFailure ->
            BridgeSyncStopReason(BridgeSyncStopKind.LOCAL_FAILURE, syncFailedError(error))
        SyncStopReason.SessionNotReady ->
            BridgeSyncStopReason(BridgeSyncStopKind.SESSION_NOT_READY, null)
        SyncStopReason.SessionChanged ->
            BridgeSyncStopReason(BridgeSyncStopKind.SESSION_CHANGED, null)
        SyncStopReason.NetworkDisabled ->
            BridgeSyncStopReason(BridgeSyncStopKind.NETWORK_DISABLED, null)
        SyncStopReason.HeldBehindOtherTables ->
            BridgeSyncStopReason(BridgeSyncStopKind.HELD_BEHIND_OTHER_TABLES, null)
        SyncStopReason.LocalDataCleared ->
            BridgeSyncStopReason(BridgeSyncStopKind.LOCAL_DATA_CLEARED, null)
        else -> BridgeSyncStopReason(BridgeSyncStopKind.UNKNOWN, null)
    }

/** The sync status; the last sync error is the one [BridgeErrorKind.SYNC_FAILED] value. */
internal fun SyncStatus.toBridge(): BridgeSyncStatus =
    BridgeSyncStatus(
        network = network.toBridge(),
        pendingMutationCount = pendingMutationCount,
        rejectedMutationCount = rejectedMutationCount,
        isSyncing = isSyncing,
        lastSyncedAtEpochMillis = lastSyncedAt?.toEpochMilliseconds(),
        lastSyncError = lastSyncError?.let(::lastSyncErrorOf),
        conflictedMutationCount = conflictedMutationCount,
    )

/** [SyncStatus.lastSyncError] as a value: a [SyncFailedException], or, defensively, anything. */
private fun lastSyncErrorOf(failure: Throwable) =
    if (failure is SyncFailedException) syncFailedError(failure) else bridgeErrorOf(failure)

/** The network status. */
private fun NetworkStatus.toBridge(): BridgeNetworkStatus =
    when (this) {
        NetworkStatus.UNKNOWN -> BridgeNetworkStatus.UNKNOWN
        NetworkStatus.ONLINE -> BridgeNetworkStatus.ONLINE
        NetworkStatus.OFFLINE -> BridgeNetworkStatus.OFFLINE
        NetworkStatus.DISABLED -> BridgeNetworkStatus.DISABLED
    }

/** A cached Storage file. */
internal fun CachedFile.toBridge(): BridgeCachedFile =
    BridgeCachedFile(
        path = path,
        fetchedAtEpochMillis = fetchedAt.toEpochMilliseconds(),
        sizeBytes = sizeBytes,
    )
