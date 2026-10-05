// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge.internal

import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.nesfeder.supabase.offline.CachedFile
import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.NetworkStatus
import io.github.nesfeder.supabase.offline.PendingMutation
import io.github.nesfeder.supabase.offline.QuerySnapshot
import io.github.nesfeder.supabase.offline.RefreshState
import io.github.nesfeder.supabase.offline.RejectedMutation
import io.github.nesfeder.supabase.offline.ServerError
import io.github.nesfeder.supabase.offline.SyncFailedException
import io.github.nesfeder.supabase.offline.SyncReport
import io.github.nesfeder.supabase.offline.SyncStatus
import io.github.nesfeder.supabase.offline.bridge.BridgeCachedFile
import io.github.nesfeder.supabase.offline.bridge.BridgeErrorKind
import io.github.nesfeder.supabase.offline.bridge.BridgeMutationOperation
import io.github.nesfeder.supabase.offline.bridge.BridgeMutationResult
import io.github.nesfeder.supabase.offline.bridge.BridgeNetworkStatus
import io.github.nesfeder.supabase.offline.bridge.BridgePendingMutation
import io.github.nesfeder.supabase.offline.bridge.BridgeQueryResult
import io.github.nesfeder.supabase.offline.bridge.BridgeQuerySnapshot
import io.github.nesfeder.supabase.offline.bridge.BridgeRefreshKind
import io.github.nesfeder.supabase.offline.bridge.BridgeRefreshState
import io.github.nesfeder.supabase.offline.bridge.BridgeRejectedMutation
import io.github.nesfeder.supabase.offline.bridge.BridgeServerError
import io.github.nesfeder.supabase.offline.bridge.BridgeSyncReport
import io.github.nesfeder.supabase.offline.bridge.BridgeSyncStatus
import io.github.nesfeder.supabase.offline.fetchedAt
import io.github.nesfeder.supabase.offline.hasPendingMutations
import io.github.nesfeder.supabase.offline.isFromCache
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
    )

/** One emission of a live read. */
internal fun QuerySnapshot.toBridge(clientClosing: Boolean): BridgeQuerySnapshot =
    BridgeQuerySnapshot(
        data = result?.data,
        isFromCache = isFromCache,
        fetchedAtEpochMillis = fetchedAt?.toEpochMilliseconds(),
        hasPendingMutations = hasPendingMutations,
        refresh = refresh.toBridge(clientClosing),
    )

/** A refresh state; a stall carries its cause as an error value. */
private fun RefreshState.toBridge(clientClosing: Boolean): BridgeRefreshState =
    when (this) {
        RefreshState.Idle -> BridgeRefreshState(BridgeRefreshKind.IDLE, null)
        RefreshState.Refreshing -> BridgeRefreshState(BridgeRefreshKind.REFRESHING, null)
        RefreshState.Paused -> BridgeRefreshState(BridgeRefreshKind.PAUSED, null)
        is RefreshState.Stalled ->
            BridgeRefreshState(BridgeRefreshKind.STALLED, bridgeErrorOf(cause, clientClosing))
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

/** A queued write. */
internal fun PendingMutation.toBridge(): BridgePendingMutation =
    BridgePendingMutation(
        id = id,
        table = table,
        operation = operation.toBridge(),
        createdAtEpochMillis = createdAt.toEpochMilliseconds(),
        rowKeysJson = JsonArray(rowKeys).toString(),
        bodyJson = body?.toString(),
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
private fun MutationOperation.toBridge(): BridgeMutationOperation =
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
    )

/** The sync status; the last sync error is the one [BridgeErrorKind.SYNC_FAILED] value. */
internal fun SyncStatus.toBridge(): BridgeSyncStatus =
    BridgeSyncStatus(
        network = network.toBridge(),
        pendingMutationCount = pendingMutationCount,
        rejectedMutationCount = rejectedMutationCount,
        isSyncing = isSyncing,
        lastSyncedAtEpochMillis = lastSyncedAt?.toEpochMilliseconds(),
        lastSyncError = lastSyncError?.let(::lastSyncErrorOf),
    )

/** [SyncStatus.lastSyncError] as a value: a [SyncFailedException], or, defensively, anything. */
private fun lastSyncErrorOf(failure: Throwable) =
    if (failure is SyncFailedException) syncFailedError(failure)
    else bridgeErrorOf(failure, clientClosing = false)

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
