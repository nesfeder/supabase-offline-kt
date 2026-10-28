// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testing

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.jan.supabase.storage.FileUploadResponse
import io.github.nesfeder.supabase.offline.CacheMissException
import io.github.nesfeder.supabase.offline.CachedFile
import io.github.nesfeder.supabase.offline.ConflictResolver
import io.github.nesfeder.supabase.offline.ConflictedMutation
import io.github.nesfeder.supabase.offline.MutationConflict
import io.github.nesfeder.supabase.offline.MutationNotQueueableException
import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.NetworkDisabledException
import io.github.nesfeder.supabase.offline.NetworkStatus
import io.github.nesfeder.supabase.offline.Offline
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
import io.github.nesfeder.supabase.offline.fetchedAt
import io.github.nesfeder.supabase.offline.hasPendingMutations
import io.github.nesfeder.supabase.offline.internal.testing.ValueConstructors
import io.github.nesfeder.supabase.offline.isFromCache
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Values a test double returns or throws in place of the library's. Their constructors are internal
 * so that the library can add fields without breaking a caller; these factories take every field.
 */

/**
 * The outcome of a write the server applied, as a test double of the app's own returns it.
 *
 * @param result the server's response: `PostgrestResult(data, Headers.Empty, supabase.postgrest)`,
 *   with `supabase` a client built on a Ktor `MockEngine`, since supabase-kt's `Postgrest` needs a
 *   client with an engine.
 */
@OptIn(OfflineInternal::class)
public fun appliedOutcome(result: PostgrestResult): MutationOutcome.Applied =
    ValueConstructors.applied(result)

/**
 * The outcome of a write that waits in the mutation queue.
 *
 * @param pendingMutation the queued write.
 */
@OptIn(OfflineInternal::class)
public fun queuedOutcome(pendingMutation: PendingMutation): MutationOutcome.Queued =
    ValueConstructors.queued(pendingMutation)

/**
 * A Storage object held in the file cache, as the offline bucket reports one.
 *
 * @param path where the file is.
 * @param fetchedAt when it was downloaded.
 * @param sizeBytes its size.
 */
@OptIn(OfflineInternal::class)
public fun cachedFile(path: String, fetchedAt: Instant, sizeBytes: Long): CachedFile =
    ValueConstructors.cachedFile(path = path, fetchedAt = fetchedAt, sizeBytes = sizeBytes)

/**
 * The failure of a read that may use only the cache and found nothing there.
 *
 * @param message what the exception says.
 */
@OptIn(OfflineInternal::class)
public fun cacheMissException(
    message: String = "No valid cached data found for this query"
): CacheMissException = ValueConstructors.cacheMissException(message)

/**
 * The failure of a write the mutation queue could not hold, sent at once and refused for want of a
 * connection.
 *
 * @param cause the connectivity failure.
 * @param message what the exception says.
 */
@OptIn(OfflineInternal::class)
public fun mutationNotQueueableException(
    cause: Throwable,
    message: String = "The write could not be queued, and could not be sent",
): MutationNotQueueableException = ValueConstructors.mutationNotQueueableException(message, cause)

/**
 * The failure of a request asked for while the app had turned the library's network access off.
 *
 * @param message what the exception says.
 */
@OptIn(OfflineInternal::class)
public fun networkDisabledException(
    message: String = "The library's network access is turned off"
): NetworkDisabledException = ValueConstructors.networkDisabledException(message)

/**
 * The failure of a call on a closed client, with the library's own message and no cause, as a test
 * double of the app's own throws it after its client is closed.
 */
@OptIn(OfflineInternal::class)
public fun offlineClientClosedException(): OfflineClientClosedException =
    ValueConstructors.offlineClientClosedException()

/**
 * A write waiting in the mutation queue, as [Offline.pendingMutations] lists one.
 *
 * @param id its id in the queue.
 * @param table the table it targets.
 * @param operation what it does.
 * @param createdAt when it was queued.
 * @param rowKeys the primary-key values of the rows it touches.
 * @param body the body it sends, or `null`.
 */
@OptIn(OfflineInternal::class)
public fun pendingMutation(
    id: Long,
    table: String,
    operation: MutationOperation,
    createdAt: Instant,
    rowKeys: List<JsonObject> = emptyList(),
    body: JsonElement? = null,
): PendingMutation =
    ValueConstructors.pendingMutation(id, table, operation, createdAt, rowKeys, body)

/**
 * A write waiting in the mutation queue with the version it replays against, as
 * [Offline.pendingMutations] lists a write to a table with a version column.
 *
 * @param id its id in the queue.
 * @param table the table it targets.
 * @param operation what it does.
 * @param createdAt when it was queued.
 * @param rowKeys the primary-key values of the rows it touches.
 * @param body the body it sends, or `null`.
 * @param baseVersion the row's version the replay is preconditioned on, or `null` for none.
 */
@OptIn(OfflineInternal::class)
public fun pendingMutation(
    id: Long,
    table: String,
    operation: MutationOperation,
    createdAt: Instant,
    rowKeys: List<JsonObject> = emptyList(),
    body: JsonElement? = null,
    baseVersion: String?,
): PendingMutation =
    ValueConstructors.pendingMutation(id, table, operation, createdAt, rowKeys, body, baseVersion)

/**
 * The state of a refresh that keeps failing.
 *
 * @param cause the failure that stalled the refresh.
 */
@OptIn(OfflineInternal::class)
public fun stalledRefresh(cause: Throwable): RefreshState.Stalled = ValueConstructors.stalled(cause)

/**
 * The state of a Realtime channel whose join was refused or that ended, which the client joins
 * again.
 *
 * @param cause what ended the channel.
 */
@OptIn(OfflineInternal::class)
public fun stalledRealtime(cause: Throwable): RealtimeStatus.Stalled =
    ValueConstructors.stalledRealtime(cause)

/**
 * One emission of a reactive read, as `selectAsFlow` hands one out.
 *
 * @param result the rows, or `null` while nothing is known for the query; build one as for
 *   [appliedOutcome].
 * @param isFromCache whether the rows are the device's copy.
 * @param fetchedAt when they were fetched, or `null` while nothing is cached.
 * @param hasPendingMutations whether a queued write changed a row shown.
 * @param refresh how the refresh behind the read stands.
 */
@OptIn(OfflineInternal::class)
public fun querySnapshot(
    result: PostgrestResult?,
    isFromCache: Boolean,
    fetchedAt: Instant?,
    hasPendingMutations: Boolean = false,
    refresh: RefreshState = RefreshState.Idle,
): QuerySnapshot =
    ValueConstructors.querySnapshot(result, isFromCache, fetchedAt, hasPendingMutations, refresh)

/**
 * The result of a one-shot read the cache answered, as a fake of the app's own repository returns
 * it: [PostgrestResult.isFromCache] reads `true`, [PostgrestResult.fetchedAt] the time given, to
 * the millisecond, and [PostgrestResult.hasPendingMutations] the flag given. The library stamps a
 * read's result in headers PostgREST never sends, and this one carries the same stamp; a result
 * built with `PostgrestResult(data, Headers.Empty, supabase.postgrest)` carries none, and reads as
 * a result supabase-kt returned itself.
 *
 * @param data the rows, as the JSON text a read returns.
 * @param supabase a client with `Postgrest` installed, built on a Ktor `MockEngine`, since
 *   supabase-kt's `Postgrest` needs a client with an engine.
 * @param fetchedAt when the rows were fetched from the server.
 * @param hasPendingMutations whether a queued write changed a row shown.
 */
@OptIn(OfflineInternal::class)
public fun cachedResult(
    data: String,
    supabase: SupabaseClient,
    fetchedAt: Instant,
    hasPendingMutations: Boolean = false,
): PostgrestResult =
    ValueConstructors.cachedResult(data, supabase.postgrest, fetchedAt, hasPendingMutations)

/**
 * What a call of [Offline.syncPendingMutations] did. Its [SyncReport.stopReason] is the one the
 * library gives such a report: [SyncStopReason.HeldBehindOtherTables] when
 * [heldBehindOtherTablesCount] is more than zero, else [SyncStopReason.Completed].
 *
 * @param syncedCount the writes sent and accepted.
 * @param rejected the writes the server refused for good.
 * @param remainingCount the writes still queued.
 * @param alreadyOnServerCount of [syncedCount], the inserts whose rows the server already held.
 * @param heldBehindOtherTablesCount of [remainingCount], the writes a call for one table held back
 *   behind an earlier write to another table.
 */
@OptIn(OfflineInternal::class)
public fun syncReport(
    syncedCount: Int,
    rejected: List<RejectedMutation> = emptyList(),
    remainingCount: Int = 0,
    alreadyOnServerCount: Int = 0,
    heldBehindOtherTablesCount: Int = 0,
): SyncReport =
    ValueConstructors.syncReport(
        syncedCount,
        rejected,
        remainingCount,
        alreadyOnServerCount,
        heldBehindOtherTablesCount,
    )

/**
 * What a call of [Offline.syncPendingMutations] did, with the conflicts its replay met; a test sets
 * the conflict fields it reads, and the others keep the values of a pass that met none. Its
 * [SyncReport.stopReason] is the one the library gives such a report:
 * [SyncStopReason.HeldBehindOtherTables] when [heldBehindOtherTablesCount] is more than zero, else
 * [SyncStopReason.Completed].
 *
 * @param syncedCount the writes sent and accepted.
 * @param rejected the writes the server refused for good.
 * @param remainingCount the writes still queued.
 * @param alreadyOnServerCount of [syncedCount], the writes the server already held.
 * @param heldBehindOtherTablesCount of [remainingCount], the writes a call for one table held back
 *   behind an earlier write to another table.
 * @param conflictsResolvedCount of [syncedCount], the conflicts settled and sent.
 * @param conflictsKeptServerCount the writes dropped so the server's row stands.
 * @param conflicted the writes parked on the conflicted list.
 */
@OptIn(OfflineInternal::class)
public fun syncReport(
    syncedCount: Int,
    rejected: List<RejectedMutation> = emptyList(),
    remainingCount: Int = 0,
    alreadyOnServerCount: Int = 0,
    heldBehindOtherTablesCount: Int = 0,
    conflictsResolvedCount: Int = 0,
    conflictsKeptServerCount: Int = 0,
    conflicted: List<ConflictedMutation> = emptyList(),
): SyncReport =
    ValueConstructors.syncReport(
        syncedCount,
        rejected,
        remainingCount,
        alreadyOnServerCount,
        heldBehindOtherTablesCount,
        conflictsResolvedCount,
        conflictsKeptServerCount,
        conflicted,
    )

/**
 * What a call of [Offline.syncPendingMutations] did, and why it returned when it did; every field
 * but [stopReason], which selects this overload, defaults to the value of a call that sent nothing.
 * Build a failure's reason with [serverFailureStop] or [localFailureStop]; the others are objects,
 * such as [SyncStopReason.NetworkUnavailable].
 *
 * @param syncedCount the writes sent and accepted.
 * @param rejected the writes the server refused for good.
 * @param remainingCount the writes still queued.
 * @param alreadyOnServerCount of [syncedCount], the writes the server already held.
 * @param heldBehindOtherTablesCount of [remainingCount], the writes a call for one table held back
 *   behind an earlier write to another table.
 * @param conflictsResolvedCount of [syncedCount], the conflicts settled and sent.
 * @param conflictsKeptServerCount the writes dropped so the server's row stands.
 * @param conflicted the writes parked on the conflicted list.
 * @param stopReason why the call returned when it did.
 */
@OptIn(OfflineInternal::class)
public fun syncReport(
    syncedCount: Int = 0,
    rejected: List<RejectedMutation> = emptyList(),
    remainingCount: Int = 0,
    alreadyOnServerCount: Int = 0,
    heldBehindOtherTablesCount: Int = 0,
    conflictsResolvedCount: Int = 0,
    conflictsKeptServerCount: Int = 0,
    conflicted: List<ConflictedMutation> = emptyList(),
    stopReason: SyncStopReason,
): SyncReport =
    ValueConstructors.syncReport(
        syncedCount,
        rejected,
        remainingCount,
        alreadyOnServerCount,
        heldBehindOtherTablesCount,
        conflictsResolvedCount,
        conflictsKeptServerCount,
        conflicted,
        stopReason,
    )

/**
 * What a call of [Offline.syncPendingMutations] did, with the queued uploads Storage refused; every
 * field but [rejectedUploads], which selects this overload, defaults to the value of a call that
 * sent nothing and ran to the end.
 *
 * @param syncedCount the writes sent and accepted, uploads included.
 * @param rejected the row writes the server refused for good.
 * @param remainingCount the writes still queued, uploads included.
 * @param alreadyOnServerCount of [syncedCount], the writes the server already held.
 * @param heldBehindOtherTablesCount of [remainingCount], the writes a call for one table held back
 *   behind an earlier write to another table.
 * @param conflictsResolvedCount of [syncedCount], the conflicts settled and sent.
 * @param conflictsKeptServerCount the writes dropped so the server's row stands.
 * @param conflicted the writes parked on the conflicted list.
 * @param stopReason why the call returned when it did.
 * @param rejectedUploads the queued uploads Storage refused for good.
 */
@OptIn(OfflineInternal::class)
public fun syncReport(
    syncedCount: Int = 0,
    rejected: List<RejectedMutation> = emptyList(),
    remainingCount: Int = 0,
    alreadyOnServerCount: Int = 0,
    heldBehindOtherTablesCount: Int = 0,
    conflictsResolvedCount: Int = 0,
    conflictsKeptServerCount: Int = 0,
    conflicted: List<ConflictedMutation> = emptyList(),
    stopReason: SyncStopReason = SyncStopReason.Completed,
    rejectedUploads: List<RejectedUpload>,
): SyncReport =
    ValueConstructors.syncReport(
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

/**
 * The reason of a sync call the server stopped with a failure that may pass, such as a `503`.
 *
 * @param error the failure, as [SyncStatus.lastSyncError] would hold it: build it with
 *   [syncFailedException], giving its `httpStatus`.
 */
@OptIn(OfflineInternal::class)
public fun serverFailureStop(error: SyncFailedException): SyncStopReason.ServerFailure =
    ValueConstructors.serverFailureStop(error)

/**
 * The reason of a sync call a failure on the device stopped, such as the local database failing.
 *
 * @param error the failure, as [SyncStatus.lastSyncError] would hold it: build it with
 *   [syncFailedException], with no `httpStatus`.
 */
@OptIn(OfflineInternal::class)
public fun localFailureStop(error: SyncFailedException): SyncStopReason.LocalFailure =
    ValueConstructors.localFailureStop(error)

/**
 * Where the library stands with the queue and the network, as [Offline.syncStatus] holds it; every
 * field defaults to the state of a client that has done nothing yet.
 *
 * @param network whether the library believes it can reach the network.
 * @param pendingMutationCount the current user's queued writes.
 * @param rejectedMutationCount the current user's rejected writes.
 * @param isSyncing whether a sync pass is running.
 * @param lastSyncedAt when the queue was last emptied by sending, or `null`.
 * @param lastSyncError why the last pass stopped with writes still queued, or `null`.
 */
@OptIn(OfflineInternal::class)
public fun syncStatus(
    network: NetworkStatus = NetworkStatus.UNKNOWN,
    pendingMutationCount: Int = 0,
    rejectedMutationCount: Int = 0,
    isSyncing: Boolean = false,
    lastSyncedAt: Instant? = null,
    lastSyncError: SyncFailedException? = null,
): SyncStatus =
    ValueConstructors.syncStatus(
        network,
        pendingMutationCount,
        rejectedMutationCount,
        isSyncing,
        lastSyncedAt,
        lastSyncError,
    )

/**
 * Where the library stands with the queue and the network, the conflicted list included; every
 * field but [conflictedMutationCount], which selects this overload, defaults to the state of a
 * client that has done nothing yet.
 *
 * @param network whether the library believes it can reach the network.
 * @param pendingMutationCount the current user's queued writes.
 * @param rejectedMutationCount the current user's rejected writes.
 * @param isSyncing whether a sync pass is running.
 * @param lastSyncedAt when the queue was last emptied by sending, or `null`.
 * @param lastSyncError why the last pass stopped with writes still queued, or `null`.
 * @param conflictedMutationCount the current user's writes on the conflicted list.
 */
@OptIn(OfflineInternal::class)
public fun syncStatus(
    network: NetworkStatus = NetworkStatus.UNKNOWN,
    pendingMutationCount: Int = 0,
    rejectedMutationCount: Int = 0,
    isSyncing: Boolean = false,
    lastSyncedAt: Instant? = null,
    lastSyncError: SyncFailedException? = null,
    conflictedMutationCount: Int,
): SyncStatus =
    ValueConstructors.syncStatus(
        network,
        pendingMutationCount,
        rejectedMutationCount,
        isSyncing,
        lastSyncedAt,
        lastSyncError,
        conflictedMutationCount,
    )

/**
 * A conflict a replay found, as a [ConflictResolver] is shown one, for a test of the app's own
 * resolver.
 *
 * @param mutationId the write's id in the queue.
 * @param table the table it targets.
 * @param operation [MutationOperation.UPDATE] or [MutationOperation.DELETE].
 * @param rowKey the primary-key values of the row.
 * @param base the columns the write sets and the version column, as the device last saw the row.
 * @param local the columns the write sets, with their values; `null` for a DELETE.
 * @param server the server's row as the replay read it, in part; `null` when it is gone.
 * @param changedOnServer the columns the write sets whose server value differs from [base]'s.
 * @param conflictingColumns of [changedOnServer], those [local] sets to another value.
 */
@OptIn(OfflineInternal::class)
public fun mutationConflict(
    mutationId: Long,
    table: String,
    operation: MutationOperation,
    rowKey: JsonObject,
    base: JsonObject = JsonObject(emptyMap()),
    local: JsonObject? = null,
    server: JsonObject? = null,
    changedOnServer: Set<String> = emptySet(),
    conflictingColumns: Set<String> = emptySet(),
): MutationConflict =
    ValueConstructors.mutationConflict(
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

/**
 * A write parked on the conflicted list, as [Offline.conflictedMutations] lists one.
 *
 * @param id its id in the queue.
 * @param table the table it targeted.
 * @param operation [MutationOperation.UPDATE] or [MutationOperation.DELETE].
 * @param createdAt when it was queued.
 * @param rowKeys the primary-key values of the row it touches.
 * @param body the body it sends, or `null`.
 * @param base the columns the write sets and the version column, as the device last saw the row.
 * @param server the server's row as the replay read it, in part, or `null`.
 * @param serverVersion the server row's version as text, or `null` when the row was gone.
 * @param changedOnServer the columns the write sets whose server value differed from [base]'s.
 * @param conflictingColumns of [changedOnServer], those the write sets to another value.
 * @param message why it was parked.
 * @param conflictedAt when it was parked.
 */
@OptIn(OfflineInternal::class)
public fun conflictedMutation(
    id: Long,
    table: String,
    operation: MutationOperation,
    createdAt: Instant,
    rowKeys: List<JsonObject> = emptyList(),
    body: JsonElement? = null,
    base: JsonObject = JsonObject(emptyMap()),
    server: JsonObject? = null,
    serverVersion: String? = null,
    changedOnServer: Set<String> = emptySet(),
    conflictingColumns: Set<String> = emptySet(),
    message: String,
    conflictedAt: Instant,
): ConflictedMutation =
    ValueConstructors.conflictedMutation(
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

/**
 * A queued write the server refused for good, as [Offline.rejectedMutations] lists one.
 *
 * @param id its id in the queue.
 * @param table the table it targeted.
 * @param operation what it did.
 * @param createdAt when it was queued.
 * @param rowKeys the primary-key values of the rows it touches.
 * @param body the body it sent, or `null`.
 * @param serverError what the server answered, or `null` when the library refused it.
 * @param message why it was refused.
 * @param rejectedAt when it was refused.
 * @param isRetryable whether it can be queued again.
 */
@OptIn(OfflineInternal::class)
public fun rejectedMutation(
    id: Long,
    table: String,
    operation: MutationOperation,
    createdAt: Instant,
    rowKeys: List<JsonObject> = emptyList(),
    body: JsonElement? = null,
    serverError: ServerError? = null,
    message: String,
    rejectedAt: Instant,
    isRetryable: Boolean = true,
): RejectedMutation =
    ValueConstructors.rejectedMutation(
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

/**
 * The outcome of an upload Storage stored, as a test double of the app's own returns it.
 *
 * @param response Storage's response: `FileUploadResponse(id, path, key)`.
 */
@OptIn(OfflineInternal::class)
public fun appliedUploadOutcome(response: FileUploadResponse): UploadOutcome.Applied =
    ValueConstructors.appliedUpload(response)

/**
 * The outcome of an upload that waits in the mutation queue.
 *
 * @param pendingUpload the queued upload.
 */
@OptIn(OfflineInternal::class)
public fun queuedUploadOutcome(pendingUpload: PendingUpload): UploadOutcome.Queued =
    ValueConstructors.queuedUpload(pendingUpload)

/**
 * An upload waiting in the mutation queue, as `OfflineStorage.pendingUploads` lists one.
 *
 * @param id its id in the queue.
 * @param bucketId the bucket the file goes to.
 * @param path the object path within the bucket.
 * @param createdAt when it was queued.
 * @param sizeBytes the file's size.
 * @param contentType the content type it is sent with, or `null` for none.
 * @param upsert whether it overwrites an object already at the path.
 */
@OptIn(OfflineInternal::class)
public fun pendingUpload(
    id: Long,
    bucketId: String,
    path: String,
    createdAt: Instant,
    sizeBytes: Long,
    contentType: String? = null,
    upsert: Boolean = false,
): PendingUpload =
    ValueConstructors.pendingUpload(id, bucketId, path, createdAt, sizeBytes, contentType, upsert)

/**
 * A queued upload Storage refused for good, as `OfflineStorage.rejectedUploads` lists one.
 *
 * @param id its id in the queue.
 * @param bucketId the bucket the file was going to.
 * @param path the object path within the bucket.
 * @param createdAt when it was queued.
 * @param sizeBytes the file's size.
 * @param contentType the content type it was sent with, or `null` for none.
 * @param upsert whether it overwrites an object already at the path.
 * @param serverError what Storage answered, or `null` when the library refused it.
 * @param message why it was refused.
 * @param rejectedAt when it was refused.
 * @param isRetryable whether it can be queued again.
 */
@OptIn(OfflineInternal::class)
public fun rejectedUpload(
    id: Long,
    bucketId: String,
    path: String,
    createdAt: Instant,
    sizeBytes: Long,
    contentType: String? = null,
    upsert: Boolean = false,
    serverError: ServerError? = null,
    message: String,
    rejectedAt: Instant,
    isRetryable: Boolean = true,
): RejectedUpload =
    ValueConstructors.rejectedUpload(
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
    )

/**
 * What the server answered when it refused a write.
 *
 * @param status the HTTP status.
 * @param code the SQLSTATE or PostgREST code, or `null`.
 * @param message the server's message.
 * @param details the server's details, or `null`.
 * @param hint the server's hint, or `null`.
 */
@OptIn(OfflineInternal::class)
public fun serverError(
    status: Int,
    code: String? = null,
    message: String,
    details: String? = null,
    hint: String? = null,
): ServerError = ValueConstructors.serverError(status, code, message, details, hint)

/**
 * Why a sync pass stopped with writes still queued, as [SyncStatus.lastSyncError] reports it.
 *
 * @param message what the exception says.
 * @param httpStatus the server's HTTP status, or `null` when it did not answer.
 * @param code the server's error code, or `null`.
 * @param isConnectivityFailure whether the network caused it, which is what
 *   `isNetworkOrConnectivityFailure()` answers for it.
 */
@OptIn(OfflineInternal::class)
public fun syncFailedException(
    message: String = "The sync pass stopped with writes still queued",
    httpStatus: Int? = null,
    code: String? = null,
    isConnectivityFailure: Boolean = false,
): SyncFailedException =
    ValueConstructors.syncFailedException(message, httpStatus, code, isConnectivityFailure)
