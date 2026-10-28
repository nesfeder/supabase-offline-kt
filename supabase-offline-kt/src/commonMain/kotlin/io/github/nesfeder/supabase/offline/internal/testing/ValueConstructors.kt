// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalObjCRefinement::class)

package io.github.nesfeder.supabase.offline.internal.testing

import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.jan.supabase.storage.FileUploadResponse
import io.github.nesfeder.supabase.offline.CacheMissException
import io.github.nesfeder.supabase.offline.CachedFile
import io.github.nesfeder.supabase.offline.ConflictedMutation
import io.github.nesfeder.supabase.offline.MutationConflict
import io.github.nesfeder.supabase.offline.MutationNotQueueableException
import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.NetworkDisabledException
import io.github.nesfeder.supabase.offline.NetworkStatus
import io.github.nesfeder.supabase.offline.OfflineClientClosedException
import io.github.nesfeder.supabase.offline.OfflineInternal
import io.github.nesfeder.supabase.offline.PendingMutation
import io.github.nesfeder.supabase.offline.PendingUpload
import io.github.nesfeder.supabase.offline.QuerySnapshot
import io.github.nesfeder.supabase.offline.RealtimeStatus
import io.github.nesfeder.supabase.offline.RefreshState
import io.github.nesfeder.supabase.offline.RejectedMutation
import io.github.nesfeder.supabase.offline.RejectedUpload
import io.github.nesfeder.supabase.offline.ServerError
import io.github.nesfeder.supabase.offline.SyncFailedException
import io.github.nesfeder.supabase.offline.SyncReport
import io.github.nesfeder.supabase.offline.SyncStatus
import io.github.nesfeder.supabase.offline.SyncStopReason
import io.github.nesfeder.supabase.offline.UploadOutcome
import io.github.nesfeder.supabase.offline.internal.postgrest.ResultProvenance
import io.github.nesfeder.supabase.offline.internal.postgrest.provenancedResult
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

    /** An [OfflineClientClosedException] with no cause. */
    public fun offlineClientClosedException(): OfflineClientClosedException =
        OfflineClientClosedException(null)

    /** A [PendingMutation] with the given fields. */
    public fun pendingMutation(
        id: Long,
        table: String,
        operation: MutationOperation,
        createdAt: Instant,
        rowKeys: List<JsonObject>,
        body: JsonElement?,
    ): PendingMutation = PendingMutation(id, table, operation, createdAt, rowKeys, body, null)

    /** A [PendingMutation] with the given fields, [PendingMutation.baseVersion] included. */
    public fun pendingMutation(
        id: Long,
        table: String,
        operation: MutationOperation,
        createdAt: Instant,
        rowKeys: List<JsonObject>,
        body: JsonElement?,
        baseVersion: String?,
    ): PendingMutation =
        PendingMutation(id, table, operation, createdAt, rowKeys, body, baseVersion)

    /** A [RefreshState.Stalled] that [cause] stalled. */
    public fun stalled(cause: Throwable): RefreshState.Stalled = RefreshState.Stalled(cause)

    /** A [RealtimeStatus.Stalled] that [cause] stalled. */
    public fun stalledRealtime(cause: Throwable): RealtimeStatus.Stalled =
        RealtimeStatus.Stalled(cause)

    /** A [QuerySnapshot] with the given fields. */
    public fun querySnapshot(
        result: PostgrestResult?,
        isFromCache: Boolean,
        fetchedAt: Instant?,
        hasPendingMutations: Boolean,
        refresh: RefreshState,
    ): QuerySnapshot = QuerySnapshot(result, isFromCache, fetchedAt, hasPendingMutations, refresh)

    /**
     * A [PostgrestResult] of [data] stamped as a read the cache answered, as a one-shot read's is:
     * fetched at [fetchedAt], to the millisecond, and showing a queued write when
     * [hasPendingMutations].
     */
    public fun cachedResult(
        data: String,
        postgrest: Postgrest,
        fetchedAt: Instant,
        hasPendingMutations: Boolean,
    ): PostgrestResult =
        provenancedResult(
            data = data,
            postgrest = postgrest,
            provenance =
                ResultProvenance(
                    isFromCache = true,
                    fetchedAtMillis = fetchedAt.toEpochMilliseconds(),
                    hasPendingMutations = hasPendingMutations,
                ),
        )

    /**
     * A [SyncReport] with the given fields. Its stop reason is the one the library gives such a
     * report: [SyncStopReason.HeldBehindOtherTables] when [heldBehindOtherTablesCount] is more than
     * zero, else [SyncStopReason.Completed].
     */
    public fun syncReport(
        syncedCount: Int,
        rejected: List<RejectedMutation>,
        remainingCount: Int,
        alreadyOnServerCount: Int,
        heldBehindOtherTablesCount: Int,
    ): SyncReport =
        syncReport(
            syncedCount,
            rejected,
            remainingCount,
            alreadyOnServerCount,
            heldBehindOtherTablesCount,
            conflictsResolvedCount = 0,
            conflictsKeptServerCount = 0,
            conflicted = emptyList(),
        )

    /**
     * A [SyncReport] with the given fields, the conflict counts included. Its stop reason is the
     * one the library gives such a report: [SyncStopReason.HeldBehindOtherTables] when
     * [heldBehindOtherTablesCount] is more than zero, else [SyncStopReason.Completed].
     */
    public fun syncReport(
        syncedCount: Int,
        rejected: List<RejectedMutation>,
        remainingCount: Int,
        alreadyOnServerCount: Int,
        heldBehindOtherTablesCount: Int,
        conflictsResolvedCount: Int,
        conflictsKeptServerCount: Int,
        conflicted: List<ConflictedMutation>,
    ): SyncReport =
        syncReport(
            syncedCount,
            rejected,
            remainingCount,
            alreadyOnServerCount,
            heldBehindOtherTablesCount,
            conflictsResolvedCount,
            conflictsKeptServerCount,
            conflicted,
            stopReason =
                if (heldBehindOtherTablesCount > 0) SyncStopReason.HeldBehindOtherTables
                else SyncStopReason.Completed,
        )

    /** A [SyncReport] with the given fields, the stop reason included and no refused upload. */
    public fun syncReport(
        syncedCount: Int,
        rejected: List<RejectedMutation>,
        remainingCount: Int,
        alreadyOnServerCount: Int,
        heldBehindOtherTablesCount: Int,
        conflictsResolvedCount: Int,
        conflictsKeptServerCount: Int,
        conflicted: List<ConflictedMutation>,
        stopReason: SyncStopReason,
    ): SyncReport =
        syncReport(
            syncedCount,
            rejected,
            remainingCount,
            alreadyOnServerCount,
            heldBehindOtherTablesCount,
            conflictsResolvedCount,
            conflictsKeptServerCount,
            conflicted,
            stopReason,
            rejectedUploads = emptyList(),
        )

    /** A [SyncReport] with the given fields, the refused uploads included. */
    public fun syncReport(
        syncedCount: Int,
        rejected: List<RejectedMutation>,
        remainingCount: Int,
        alreadyOnServerCount: Int,
        heldBehindOtherTablesCount: Int,
        conflictsResolvedCount: Int,
        conflictsKeptServerCount: Int,
        conflicted: List<ConflictedMutation>,
        stopReason: SyncStopReason,
        rejectedUploads: List<RejectedUpload>,
    ): SyncReport =
        SyncReport(
            syncedCount,
            rejected,
            remainingCount,
            alreadyOnServerCount,
            heldBehindOtherTablesCount,
            conflictsResolvedCount,
            conflictsKeptServerCount,
            conflicted,
            stopReason,
            rejectedUploads,
        )

    /** A [SyncStopReason.ServerFailure] carrying [error]. */
    public fun serverFailureStop(error: SyncFailedException): SyncStopReason.ServerFailure =
        SyncStopReason.ServerFailure(error)

    /** A [SyncStopReason.LocalFailure] carrying [error]. */
    public fun localFailureStop(error: SyncFailedException): SyncStopReason.LocalFailure =
        SyncStopReason.LocalFailure(error)

    /** A [SyncStatus] with the given fields. */
    public fun syncStatus(
        network: NetworkStatus,
        pendingMutationCount: Int,
        rejectedMutationCount: Int,
        isSyncing: Boolean,
        lastSyncedAt: Instant?,
        lastSyncError: SyncFailedException?,
    ): SyncStatus =
        SyncStatus(
            network,
            pendingMutationCount,
            rejectedMutationCount,
            isSyncing,
            lastSyncedAt,
            lastSyncError,
            conflictedMutationCount = 0,
        )

    /** A [SyncStatus] with the given fields, [SyncStatus.conflictedMutationCount] included. */
    public fun syncStatus(
        network: NetworkStatus,
        pendingMutationCount: Int,
        rejectedMutationCount: Int,
        isSyncing: Boolean,
        lastSyncedAt: Instant?,
        lastSyncError: SyncFailedException?,
        conflictedMutationCount: Int,
    ): SyncStatus =
        SyncStatus(
            network,
            pendingMutationCount,
            rejectedMutationCount,
            isSyncing,
            lastSyncedAt,
            lastSyncError,
            conflictedMutationCount,
        )

    /** A [MutationConflict] with the given fields. */
    public fun mutationConflict(
        mutationId: Long,
        table: String,
        operation: MutationOperation,
        rowKey: JsonObject,
        base: JsonObject,
        local: JsonObject?,
        server: JsonObject?,
        changedOnServer: Set<String>,
        conflictingColumns: Set<String>,
    ): MutationConflict =
        MutationConflict(
            mutationId,
            table,
            operation,
            rowKey,
            base,
            local,
            server,
            changedOnServer,
            conflictingColumns,
        )

    /** A [ConflictedMutation] with the given fields. */
    public fun conflictedMutation(
        id: Long,
        table: String,
        operation: MutationOperation,
        createdAt: Instant,
        rowKeys: List<JsonObject>,
        body: JsonElement?,
        base: JsonObject,
        server: JsonObject?,
        serverVersion: String?,
        changedOnServer: Set<String>,
        conflictingColumns: Set<String>,
        message: String,
        conflictedAt: Instant,
    ): ConflictedMutation =
        ConflictedMutation(
            id,
            table,
            operation,
            createdAt,
            rowKeys,
            body,
            base,
            server,
            serverVersion,
            changedOnServer,
            conflictingColumns,
            message,
            conflictedAt,
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

    /** An [UploadOutcome.Applied] carrying [response]. */
    public fun appliedUpload(response: FileUploadResponse): UploadOutcome.Applied =
        UploadOutcome.Applied(response)

    /** An [UploadOutcome.Queued] carrying [pendingUpload]. */
    public fun queuedUpload(pendingUpload: PendingUpload): UploadOutcome.Queued =
        UploadOutcome.Queued(pendingUpload)

    /** A [PendingUpload] with the given fields. */
    public fun pendingUpload(
        id: Long,
        bucketId: String,
        path: String,
        createdAt: Instant,
        sizeBytes: Long,
        contentType: String?,
        upsert: Boolean,
    ): PendingUpload = PendingUpload(id, bucketId, path, createdAt, sizeBytes, contentType, upsert)

    /** A [RejectedUpload] with the given fields and no file on the device. */
    public fun rejectedUpload(
        id: Long,
        bucketId: String,
        path: String,
        createdAt: Instant,
        sizeBytes: Long,
        contentType: String?,
        upsert: Boolean,
        serverError: ServerError?,
        message: String,
        rejectedAt: Instant,
        isRetryable: Boolean,
    ): RejectedUpload =
        rejectedUpload(
            id,
            bucketId,
            path,
            createdAt,
            sizeBytes,
            contentType,
            upsert,
            serverError,
            message,
            rejectedAt,
            isRetryable,
            localPath = null,
        )

    /** A [RejectedUpload] with the given fields, its file on the device among them. */
    public fun rejectedUpload(
        id: Long,
        bucketId: String,
        path: String,
        createdAt: Instant,
        sizeBytes: Long,
        contentType: String?,
        upsert: Boolean,
        serverError: ServerError?,
        message: String,
        rejectedAt: Instant,
        isRetryable: Boolean,
        localPath: String?,
    ): RejectedUpload =
        RejectedUpload(
            id,
            bucketId,
            path,
            createdAt,
            sizeBytes,
            contentType,
            upsert,
            serverError,
            message,
            rejectedAt,
            isRetryable,
            localPath,
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
