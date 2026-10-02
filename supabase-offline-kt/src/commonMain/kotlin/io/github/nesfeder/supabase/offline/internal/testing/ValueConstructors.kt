// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalObjCRefinement::class)

package io.github.nesfeder.supabase.offline.internal.testing

import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.nesfeder.supabase.offline.CacheMissException
import io.github.nesfeder.supabase.offline.CachedFile
import io.github.nesfeder.supabase.offline.MutationNotQueueableException
import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.NetworkDisabledException
import io.github.nesfeder.supabase.offline.NetworkStatus
import io.github.nesfeder.supabase.offline.OfflineInternal
import io.github.nesfeder.supabase.offline.PendingMutation
import io.github.nesfeder.supabase.offline.QuerySnapshot
import io.github.nesfeder.supabase.offline.RefreshState
import io.github.nesfeder.supabase.offline.RejectedMutation
import io.github.nesfeder.supabase.offline.ServerError
import io.github.nesfeder.supabase.offline.SyncFailedException
import io.github.nesfeder.supabase.offline.SyncReport
import io.github.nesfeder.supabase.offline.SyncStatus
import io.github.nesfeder.supabase.offline.isNetworkOrConnectivityFailure
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The internal constructors of the public value types, for the factories of the
 * `supabase-offline-kt-testing` artifact; the library keeps the constructors themselves internal,
 * so it can add fields without breaking a caller. Each function takes the constructor's own
 * parameters.
 */
@OfflineInternal
@HiddenFromObjC
public object ValueConstructors {

    /** A [MutationOutcome.Applied] carrying [result]. */
    public fun applied(result: PostgrestResult): MutationOutcome.Applied =
        MutationOutcome.Applied(result)

    /** A [MutationOutcome.Queued] carrying [pendingMutation]. */
    public fun queued(pendingMutation: PendingMutation): MutationOutcome.Queued =
        MutationOutcome.Queued(pendingMutation)

    /** A [CachedFile] with the given fields. */
    public fun cachedFile(path: String, fetchedAt: Instant, sizeBytes: Long): CachedFile =
        CachedFile(path = path, fetchedAt = fetchedAt, sizeBytes = sizeBytes)

    /** A [CacheMissException] with [message]. */
    public fun cacheMissException(message: String): CacheMissException = CacheMissException(message)

    /** A [MutationNotQueueableException] with [message] and [cause]. */
    public fun mutationNotQueueableException(
        message: String,
        cause: Throwable,
    ): MutationNotQueueableException = MutationNotQueueableException(message, cause)

    /** A [NetworkDisabledException] with [message]. */
    public fun networkDisabledException(message: String): NetworkDisabledException =
        NetworkDisabledException(message)

    /** A [PendingMutation] with the given fields. */
    public fun pendingMutation(
        id: Long,
        table: String,
        operation: MutationOperation,
        createdAt: Instant,
        rowKeys: List<JsonObject>,
        body: JsonElement?,
    ): PendingMutation = PendingMutation(id, table, operation, createdAt, rowKeys, body)

    /** A [RefreshState.Stalled] whose last failure was [cause]. */
    public fun stalled(cause: Throwable): RefreshState.Stalled = RefreshState.Stalled(cause)

    /** A [QuerySnapshot] with the given fields. */
    public fun querySnapshot(
        result: PostgrestResult?,
        isFromCache: Boolean,
        fetchedAt: Instant?,
        hasPendingMutations: Boolean,
        refresh: RefreshState,
    ): QuerySnapshot = QuerySnapshot(result, isFromCache, fetchedAt, hasPendingMutations, refresh)

    /** A [SyncReport] with the given fields. */
    public fun syncReport(
        syncedCount: Int,
        rejected: List<RejectedMutation>,
        remainingCount: Int,
        alreadyOnServerCount: Int,
    ): SyncReport = SyncReport(syncedCount, rejected, remainingCount, alreadyOnServerCount)

    /** A [SyncStatus] with the given fields. */
    public fun syncStatus(
        network: NetworkStatus,
        pendingMutationCount: Int,
        rejectedMutationCount: Int,
        isSyncing: Boolean,
        lastSyncedAt: Instant?,
        lastSyncError: Throwable?,
    ): SyncStatus =
        SyncStatus(
            network,
            pendingMutationCount,
            rejectedMutationCount,
            isSyncing,
            lastSyncedAt,
            lastSyncError,
        )

    /** A [RejectedMutation] with the given fields. */
    public fun rejectedMutation(
        id: Long,
        table: String,
        operation: MutationOperation,
        createdAt: Instant,
        rowKeys: List<JsonObject>,
        body: JsonElement?,
        serverError: ServerError?,
        message: String,
        rejectedAt: Instant,
        isRetryable: Boolean,
    ): RejectedMutation =
        RejectedMutation(
            id,
            table,
            operation,
            createdAt,
            rowKeys,
            body,
            serverError,
            message,
            rejectedAt,
            isRetryable,
        )

    /** A [ServerError] with the given fields. */
    public fun serverError(
        status: Int,
        code: String?,
        message: String,
        details: String?,
        hint: String?,
    ): ServerError = ServerError(status, code, message, details, hint)

    /**
     * A [SyncFailedException] with [message], [httpStatus] and [code]; [isConnectivityFailure] is
     * what [isNetworkOrConnectivityFailure] answers for it.
     */
    public fun syncFailedException(
        message: String,
        httpStatus: Int?,
        code: String?,
        isConnectivityFailure: Boolean,
    ): SyncFailedException = SyncFailedException(message, httpStatus, code, isConnectivityFailure)
}
