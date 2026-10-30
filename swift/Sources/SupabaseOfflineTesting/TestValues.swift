// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
public import Foundation
public import SupabaseOffline

// Values a test double of the app's own returns or throws in place of the client's. Their
// initializers are not public, so the package can add fields without breaking a caller; these
// factories take every field, and default the ones a test rarely sets.

/// A write waiting in the mutation queue, as `SupabaseOfflineClient.pendingMutations(table:)` lists
/// one.
///
/// - Parameters:
///   - id: Its id in the queue.
///   - table: The table it targets.
///   - operation: What it does.
///   - createdAt: When it was queued.
///   - rowKeys: The primary-key values of the rows it touches.
///   - body: The body it sends; `nil` for a delete.
public func pendingMutation(
    id: Int64,
    table: String,
    operation: MutationOperation,
    createdAt: Date,
    rowKeys: [[String: OfflineJSON]] = [],
    body: OfflineJSON? = nil
) -> PendingMutation {
    PendingMutation(id: id, table: table, operation: operation, createdAt: createdAt, rowKeys: rowKeys, body: body)
}

/// A write waiting in the mutation queue with the version it replays against, as
/// `SupabaseOfflineClient.pendingMutations(table:)` lists a guarded one.
///
/// - Parameters:
///   - id: Its id in the queue.
///   - table: The table it targets.
///   - operation: What it does.
///   - createdAt: When it was queued.
///   - rowKeys: The primary-key values of the rows it touches.
///   - body: The body it sends; `nil` for a delete.
///   - baseVersion: The row's version it replays against; `nil` when it is not guarded.
public func pendingMutation(
    id: Int64,
    table: String,
    operation: MutationOperation,
    createdAt: Date,
    rowKeys: [[String: OfflineJSON]] = [],
    body: OfflineJSON? = nil,
    baseVersion: String?
) -> PendingMutation {
    PendingMutation(
        id: id,
        table: table,
        operation: operation,
        createdAt: createdAt,
        rowKeys: rowKeys,
        body: body,
        baseVersion: baseVersion
    )
}

/// A queued write the server refused for good, as `SupabaseOfflineClient.rejectedMutations()` lists
/// one.
///
/// - Parameters:
///   - id: Its id in the queue.
///   - table: The table it targeted.
///   - operation: What it did.
///   - createdAt: When it was queued.
///   - rowKeys: The primary-key values of the rows it touches.
///   - body: The body it sent; `nil` for a delete.
///   - serverError: What the server answered; `nil` when the client refused it itself.
///   - message: Why it was refused.
///   - rejectedAt: When it was refused.
///   - isRetryable: Whether it can be queued again.
public func rejectedMutation(
    id: Int64,
    table: String,
    operation: MutationOperation,
    createdAt: Date,
    rowKeys: [[String: OfflineJSON]] = [],
    body: OfflineJSON? = nil,
    serverError: OfflineError.ServerError? = nil,
    message: String,
    rejectedAt: Date,
    isRetryable: Bool = true
) -> RejectedMutation {
    RejectedMutation(
        id: id,
        table: table,
        operation: operation,
        createdAt: createdAt,
        rowKeys: rowKeys,
        body: body,
        serverError: serverError,
        message: message,
        rejectedAt: rejectedAt,
        isRetryable: isRetryable
    )
}

/// What the server answered when it refused a request.
///
/// - Parameters:
///   - status: The HTTP status.
///   - code: The SQLSTATE or PostgREST code.
///   - message: The server's message.
///   - details: The server's details.
///   - hint: The server's hint.
public func serverError(
    status: Int,
    code: String? = nil,
    message: String,
    details: String? = nil,
    hint: String? = nil
) -> OfflineError.ServerError {
    OfflineError.ServerError(status: status, code: code, message: message, details: details, hint: hint)
}

/// What a call of `SupabaseOfflineClient.syncPendingMutations(table:)` did. Its `stopReason` is the
/// one the client gives such a report: held behind other tables when `heldBehindOtherTablesCount` is
/// more than zero, else completed.
///
/// - Parameters:
///   - syncedCount: The writes sent and accepted.
///   - rejected: The writes refused for good.
///   - remainingCount: The writes still queued.
///   - alreadyOnServerCount: Of `syncedCount`, the inserts whose rows the server already held.
///   - heldBehindOtherTablesCount: Of `remainingCount`, the writes a sync of one table held back
///     behind an earlier write to another table.
public func syncReport(
    syncedCount: Int,
    rejected: [RejectedMutation] = [],
    remainingCount: Int = 0,
    alreadyOnServerCount: Int = 0,
    heldBehindOtherTablesCount: Int = 0
) -> SyncReport {
    SyncReport(
        syncedCount: syncedCount,
        rejected: rejected,
        remainingCount: remainingCount,
        alreadyOnServerCount: alreadyOnServerCount,
        heldBehindOtherTablesCount: heldBehindOtherTablesCount
    )
}

/// What a call of `SupabaseOfflineClient.syncPendingMutations(table:)` did, with the conflicts it
/// settled, dropped and parked. Its `stopReason` is the one the client gives such a report: held
/// behind other tables when `heldBehindOtherTablesCount` is more than zero, else completed.
///
/// - Parameters:
///   - syncedCount: The writes sent and accepted.
///   - rejected: The writes refused for good.
///   - remainingCount: The writes still queued.
///   - alreadyOnServerCount: Of `syncedCount`, the inserts whose rows the server already held.
///   - heldBehindOtherTablesCount: Of `remainingCount`, the writes a sync of one table held back
///     behind an earlier write to another table.
///   - conflictsResolvedCount: Of `syncedCount`, the guarded writes sent once their conflict was
///     settled.
///   - conflictsKeptServerCount: The guarded writes dropped because the server's row stands.
///   - conflicted: The guarded writes parked on the conflicted list.
public func syncReport(
    syncedCount: Int,
    rejected: [RejectedMutation] = [],
    remainingCount: Int = 0,
    alreadyOnServerCount: Int = 0,
    heldBehindOtherTablesCount: Int = 0,
    conflictsResolvedCount: Int = 0,
    conflictsKeptServerCount: Int = 0,
    conflicted: [ConflictedMutation] = []
) -> SyncReport {
    SyncReport(
        syncedCount: syncedCount,
        rejected: rejected,
        remainingCount: remainingCount,
        alreadyOnServerCount: alreadyOnServerCount,
        heldBehindOtherTablesCount: heldBehindOtherTablesCount,
        conflictsResolvedCount: conflictsResolvedCount,
        conflictsKeptServerCount: conflictsKeptServerCount,
        conflicted: conflicted
    )
}

/// What a call of `SupabaseOfflineClient.syncPendingMutations(table:)` did, and why it returned when
/// it did; every field but `stopReason`, which selects this overload, defaults to the value of a call
/// that sent nothing.
///
/// - Parameters:
///   - syncedCount: The writes sent and accepted.
///   - rejected: The writes refused for good.
///   - remainingCount: The writes still queued.
///   - alreadyOnServerCount: Of `syncedCount`, the inserts whose rows the server already held.
///   - heldBehindOtherTablesCount: Of `remainingCount`, the writes a sync of one table held back
///     behind an earlier write to another table.
///   - conflictsResolvedCount: Of `syncedCount`, the guarded writes sent once their conflict was
///     settled.
///   - conflictsKeptServerCount: The guarded writes dropped because the server's row stands.
///   - conflicted: The guarded writes parked on the conflicted list.
///   - stopReason: Why the call returned when it did; build one with
///     ``syncStopReason(kind:error:)``.
public func syncReport(
    syncedCount: Int = 0,
    rejected: [RejectedMutation] = [],
    remainingCount: Int = 0,
    alreadyOnServerCount: Int = 0,
    heldBehindOtherTablesCount: Int = 0,
    conflictsResolvedCount: Int = 0,
    conflictsKeptServerCount: Int = 0,
    conflicted: [ConflictedMutation] = [],
    stopReason: SyncStopReason
) -> SyncReport {
    SyncReport(
        syncedCount: syncedCount,
        rejected: rejected,
        remainingCount: remainingCount,
        alreadyOnServerCount: alreadyOnServerCount,
        heldBehindOtherTablesCount: heldBehindOtherTablesCount,
        conflictsResolvedCount: conflictsResolvedCount,
        conflictsKeptServerCount: conflictsKeptServerCount,
        conflicted: conflicted,
        stopReason: stopReason
    )
}

/// What a call of `SupabaseOfflineClient.syncPendingMutations(table:)` did, with the queued uploads
/// Storage refused; every field but `rejectedUploads`, which selects this overload, defaults to the
/// value of a call that sent nothing and ran to the end.
///
/// - Parameters:
///   - syncedCount: The writes sent and accepted, uploads included.
///   - rejected: The row writes refused for good.
///   - remainingCount: The writes still queued, uploads included.
///   - alreadyOnServerCount: Of `syncedCount`, the writes the server already held.
///   - heldBehindOtherTablesCount: Of `remainingCount`, the writes a sync of one table held back
///     behind an earlier write to another table.
///   - conflictsResolvedCount: Of `syncedCount`, the guarded writes sent once their conflict was
///     settled.
///   - conflictsKeptServerCount: The guarded writes dropped because the server's row stands.
///   - conflicted: The guarded writes parked on the conflicted list.
///   - stopReason: Why the call returned when it did; by default, completed.
///   - rejectedUploads: The queued uploads Storage refused for good.
public func syncReport(
    syncedCount: Int = 0,
    rejected: [RejectedMutation] = [],
    remainingCount: Int = 0,
    alreadyOnServerCount: Int = 0,
    heldBehindOtherTablesCount: Int = 0,
    conflictsResolvedCount: Int = 0,
    conflictsKeptServerCount: Int = 0,
    conflicted: [ConflictedMutation] = [],
    stopReason: SyncStopReason = syncStopReason(kind: .completed),
    rejectedUploads: [RejectedUpload]
) -> SyncReport {
    SyncReport(
        syncedCount: syncedCount,
        rejected: rejected,
        remainingCount: remainingCount,
        alreadyOnServerCount: alreadyOnServerCount,
        heldBehindOtherTablesCount: heldBehindOtherTablesCount,
        conflictsResolvedCount: conflictsResolvedCount,
        conflictsKeptServerCount: conflictsKeptServerCount,
        conflicted: conflicted,
        stopReason: stopReason,
        rejectedUploads: rejectedUploads
    )
}

/// Why a call of `SupabaseOfflineClient.syncPendingMutations(table:)` returned when it did, as
/// `SyncReport.stopReason` holds it.
///
/// - Parameters:
///   - kind: The reason, such as `SyncStopReason.Kind.networkUnavailable`.
///   - error: For `SyncStopReason.Kind.serverFailure` and `SyncStopReason.Kind.localFailure`, the
///     failure, as ``syncFailedError(message:httpStatus:code:isConnectivityFailure:)`` builds it;
///     `nil` for every other kind.
public func syncStopReason(kind: SyncStopReason.Kind, error: OfflineError? = nil) -> SyncStopReason {
    SyncStopReason(kind: kind, error: error)
}

/// Where the client stands with the queue and the network, as `SupabaseOfflineClient.syncStatus`
/// holds it; every field defaults to the state of a client that has done nothing yet and has counted
/// its queue: nothing waiting, `isQueueCounted` `true`. For the value a client starts with, before
/// its first count, give `isQueueCounted: false`, which selects the third overload.
///
/// - Parameters:
///   - network: Whether the client believes it can reach the network.
///   - pendingMutationCount: The current user's queued writes.
///   - rejectedMutationCount: The current user's rejected writes.
///   - isSyncing: Whether a sync pass is sending.
///   - lastSyncedAt: When the queue was last emptied by sending.
///   - lastSyncError: Why the last pass stopped with writes still queued, such as a
///     ``syncFailedError(message:httpStatus:code:isConnectivityFailure:)``.
public func syncStatus(
    network: NetworkStatus = .unknown,
    pendingMutationCount: Int = 0,
    rejectedMutationCount: Int = 0,
    isSyncing: Bool = false,
    lastSyncedAt: Date? = nil,
    lastSyncError: OfflineError? = nil
) -> SyncStatus {
    SyncStatus(
        network: network,
        pendingMutationCount: pendingMutationCount,
        rejectedMutationCount: rejectedMutationCount,
        isSyncing: isSyncing,
        lastSyncedAt: lastSyncedAt,
        lastSyncError: lastSyncError
    )
}

/// Where the client stands with the queue, the conflicted list and the network, as
/// `SupabaseOfflineClient.syncStatus` holds it; every field but `conflictedMutationCount`, which
/// selects this overload, defaults to the state of a client that has done nothing yet and has
/// counted its queue.
///
/// - Parameters:
///   - network: Whether the client believes it can reach the network.
///   - pendingMutationCount: The current user's queued writes.
///   - rejectedMutationCount: The current user's rejected writes.
///   - isSyncing: Whether a sync pass is sending.
///   - lastSyncedAt: When the queue was last emptied by sending.
///   - lastSyncError: Why the last pass stopped with writes still queued.
///   - conflictedMutationCount: The current user's entries on the conflicted list.
public func syncStatus(
    network: NetworkStatus = .unknown,
    pendingMutationCount: Int = 0,
    rejectedMutationCount: Int = 0,
    isSyncing: Bool = false,
    lastSyncedAt: Date? = nil,
    lastSyncError: OfflineError? = nil,
    conflictedMutationCount: Int
) -> SyncStatus {
    SyncStatus(
        network: network,
        pendingMutationCount: pendingMutationCount,
        rejectedMutationCount: rejectedMutationCount,
        isSyncing: isSyncing,
        lastSyncedAt: lastSyncedAt,
        lastSyncError: lastSyncError,
        conflictedMutationCount: conflictedMutationCount
    )
}

/// Where the client stands with the queue and the network, saying whether the queue has been
/// counted; every field but `isQueueCounted`, which selects this overload, defaults to the state of
/// a client that has done nothing yet. `syncStatus(isQueueCounted: false)` is the value a client
/// starts with, before its first count.
///
/// - Parameters:
///   - network: Whether the client believes it can reach the network.
///   - pendingMutationCount: The current user's queued writes.
///   - rejectedMutationCount: The current user's rejected writes.
///   - isSyncing: Whether a sync pass is sending.
///   - lastSyncedAt: When the queue was last emptied by sending.
///   - lastSyncError: Why the last pass stopped with writes still queued.
///   - conflictedMutationCount: The current user's entries on the conflicted list.
///   - isQueueCounted: Whether the counts have been read from the queue.
public func syncStatus(
    network: NetworkStatus = .unknown,
    pendingMutationCount: Int = 0,
    rejectedMutationCount: Int = 0,
    isSyncing: Bool = false,
    lastSyncedAt: Date? = nil,
    lastSyncError: OfflineError? = nil,
    conflictedMutationCount: Int = 0,
    isQueueCounted: Bool
) -> SyncStatus {
    SyncStatus(
        network: network,
        pendingMutationCount: pendingMutationCount,
        rejectedMutationCount: rejectedMutationCount,
        isSyncing: isSyncing,
        lastSyncedAt: lastSyncedAt,
        lastSyncError: lastSyncError,
        conflictedMutationCount: conflictedMutationCount,
        isQueueCounted: isQueueCounted
    )
}

/// A conflict a sync pass found, as `SupabaseOfflineClient.Config.conflictResolver` is asked about
/// one, for a test of the app's own resolver.
///
/// - Parameters:
///   - mutationId: The write's id in the queue.
///   - table: The table it targets.
///   - operation: `.update` or `.delete`.
///   - rowKey: The row's primary-key values, each as text.
///   - base: The columns the write sets and the version column, as the device saw them.
///   - local: The columns the write sets; `nil` for a delete.
///   - server: The server's row now, in part; `nil` when it is gone.
///   - changedOnServer: The columns the write sets that changed on the server.
///   - conflictingColumns: Of those, the ones the write sets to another value.
public func mutationConflict(
    mutationId: Int64,
    table: String,
    operation: MutationOperation,
    rowKey: [String: OfflineJSON],
    base: [String: OfflineJSON] = [:],
    local: [String: OfflineJSON]? = nil,
    server: [String: OfflineJSON]? = nil,
    changedOnServer: Set<String> = [],
    conflictingColumns: Set<String> = []
) -> MutationConflict {
    MutationConflict(
        mutationId: mutationId,
        table: table,
        operation: operation,
        rowKey: rowKey,
        base: base,
        local: local,
        server: server,
        changedOnServer: changedOnServer,
        conflictingColumns: conflictingColumns
    )
}

/// A write parked on the conflicted list, as `SupabaseOfflineClient.conflictedMutations()` lists
/// one.
///
/// - Parameters:
///   - id: Its id in the queue.
///   - table: The table it targeted.
///   - operation: `.update` or `.delete`.
///   - createdAt: When it was queued.
///   - rowKeys: The row's primary-key values, each as text.
///   - body: The body it sends; `nil` for a delete.
///   - base: The columns it sets and the version column, as the device saw them.
///   - server: The server's row when the replay read it; `nil` when it was gone.
///   - serverVersion: The server row's version then; `nil` when it was gone.
///   - changedOnServer: The columns it sets that changed on the server.
///   - conflictingColumns: Of those, the ones it sets to another value.
///   - message: Why it was parked.
///   - conflictedAt: When it was parked.
public func conflictedMutation(
    id: Int64,
    table: String,
    operation: MutationOperation,
    createdAt: Date,
    rowKeys: [[String: OfflineJSON]] = [],
    body: OfflineJSON? = nil,
    base: [String: OfflineJSON] = [:],
    server: [String: OfflineJSON]? = nil,
    serverVersion: String? = nil,
    changedOnServer: Set<String> = [],
    conflictingColumns: Set<String> = [],
    message: String,
    conflictedAt: Date
) -> ConflictedMutation {
    ConflictedMutation(
        id: id,
        table: table,
        operation: operation,
        createdAt: createdAt,
        rowKeys: rowKeys,
        body: body,
        base: base,
        server: server,
        serverVersion: serverVersion,
        changedOnServer: changedOnServer,
        conflictingColumns: conflictingColumns,
        message: message,
        conflictedAt: conflictedAt
    )
}

/// One state of a live read, as `OfflinePostgrestQueryBuilder.selectAsSequence(_:as:readStrategy:)`
/// yields one.
///
/// - Parameters:
///   - result: The rows, or `nil` while nothing is known for the query.
///   - isFromCache: Whether the rows are the device's copy.
///   - fetchedAt: When they were fetched; `nil` while nothing is cached.
///   - hasPendingMutations: Whether a queued write changed a row shown.
///   - refresh: How the refresh behind the read stands.
public func querySnapshot<Value: Sendable>(
    result: Value?,
    isFromCache: Bool,
    fetchedAt: Date?,
    hasPendingMutations: Bool = false,
    refresh: RefreshState = .idle
) -> QuerySnapshot<Value> {
    QuerySnapshot(
        result: result,
        isFromCache: isFromCache,
        fetchedAt: fetchedAt,
        hasPendingMutations: hasPendingMutations,
        refresh: refresh
    )
}

/// One state of a live read that names the rows a queued write changed: `pendingRowKeys` reads the
/// keys given, and `hasPendingMutations` reads `true` exactly when there is one, as the library's own
/// snapshot does when each row changed carries its key. For a write that changed a row without a key
/// to name, build the snapshot with the other `querySnapshot` and `hasPendingMutations: true`.
///
/// - Parameters:
///   - result: The rows, or `nil` while nothing is known for the query.
///   - isFromCache: Whether the rows are the device's copy.
///   - fetchedAt: When they were fetched; `nil` while nothing is cached.
///   - pendingRowKeys: The primary keys of the rows a queued write added, changed or removed, each
///     the table's key columns, such as `["id": "a1"]`.
///   - refresh: How the refresh behind the read stands.
public func querySnapshot<Value: Sendable>(
    result: Value?,
    isFromCache: Bool,
    fetchedAt: Date?,
    pendingRowKeys: Set<[String: OfflineJSON]>,
    refresh: RefreshState = .idle
) -> QuerySnapshot<Value> {
    QuerySnapshot(
        result: result,
        isFromCache: isFromCache,
        fetchedAt: fetchedAt,
        hasPendingMutations: !pendingRowKeys.isEmpty,
        refresh: refresh,
        pendingRowKeys: pendingRowKeys
    )
}

/// An upload waiting in the mutation queue, as `OfflineStorage.pendingUploads()` lists one; wrap it
/// in `UploadOutcome.queued(pendingUpload:)` for an upload's outcome.
///
/// - Parameters:
///   - id: Its id in the queue.
///   - bucketId: The bucket the file goes to.
///   - path: The object path within the bucket.
///   - createdAt: When it was queued.
///   - sizeBytes: The file's size in bytes.
///   - contentType: The content type it is sent with; `nil` for none.
///   - upsert: Whether it overwrites an object already at the path.
public func pendingUpload(
    id: Int64,
    bucketId: String,
    path: String,
    createdAt: Date,
    sizeBytes: Int,
    contentType: String? = nil,
    upsert: Bool = false
) -> PendingUpload {
    PendingUpload(
        id: id,
        bucketId: bucketId,
        path: path,
        createdAt: createdAt,
        sizeBytes: sizeBytes,
        contentType: contentType,
        upsert: upsert
    )
}

/// A queued upload Storage refused for good, as `OfflineStorage.rejectedUploads()` lists one.
///
/// - Parameters:
///   - id: Its id in the queue.
///   - bucketId: The bucket the file was going to.
///   - path: The object path within the bucket.
///   - createdAt: When it was queued.
///   - sizeBytes: The file's size in bytes.
///   - contentType: The content type it was sent with; `nil` for none.
///   - upsert: Whether it overwrites an object already at the path.
///   - serverError: What Storage answered; `nil` when the client refused it itself.
///   - message: Why it was refused.
///   - rejectedAt: When it was refused.
///   - isRetryable: Whether it can be queued again.
public func rejectedUpload(
    id: Int64,
    bucketId: String,
    path: String,
    createdAt: Date,
    sizeBytes: Int,
    contentType: String? = nil,
    upsert: Bool = false,
    serverError: OfflineError.ServerError? = nil,
    message: String,
    rejectedAt: Date,
    isRetryable: Bool = true
) -> RejectedUpload {
    RejectedUpload(
        id: id,
        bucketId: bucketId,
        path: path,
        createdAt: createdAt,
        sizeBytes: sizeBytes,
        contentType: contentType,
        upsert: upsert,
        serverError: serverError,
        message: message,
        rejectedAt: rejectedAt,
        isRetryable: isRetryable,
        localPath: nil
    )
}

/// A queued upload Storage refused for good, with its bytes on the device at `localPath`, as
/// `OfflineStorage.rejectedUploads()` lists one whose file is still whole.
///
/// - Parameters:
///   - id: Its id in the queue.
///   - bucketId: The bucket the file was going to.
///   - path: The object path within the bucket.
///   - createdAt: When it was queued.
///   - sizeBytes: The file's size in bytes.
///   - contentType: The content type it was sent with; `nil` for none.
///   - upsert: Whether it overwrites an object already at the path.
///   - serverError: What Storage answered; `nil` when the client refused it itself.
///   - message: Why it was refused.
///   - rejectedAt: When it was refused.
///   - isRetryable: Whether it can be queued again.
///   - localPath: The file-system path of its bytes on the device; `nil` when they are gone.
public func rejectedUpload(
    id: Int64,
    bucketId: String,
    path: String,
    createdAt: Date,
    sizeBytes: Int,
    contentType: String? = nil,
    upsert: Bool = false,
    serverError: OfflineError.ServerError? = nil,
    message: String,
    rejectedAt: Date,
    isRetryable: Bool = true,
    localPath: String?
) -> RejectedUpload {
    RejectedUpload(
        id: id,
        bucketId: bucketId,
        path: path,
        createdAt: createdAt,
        sizeBytes: sizeBytes,
        contentType: contentType,
        upsert: upsert,
        serverError: serverError,
        message: message,
        rejectedAt: rejectedAt,
        isRetryable: isRetryable,
        localPath: localPath
    )
}

/// Where an upload stored its object, as a bucket's `upload(_:data:options:)` returns it; wrap it in
/// `UploadOutcome.applied(response:)` for the outcome of an upload made with a strategy.
///
/// - Parameters:
///   - id: The object's id.
///   - path: The object's path in the bucket.
///   - key: The object's key: the bucket and the path.
public func fileUploadResponse(id: String?, path: String, key: String?) -> OfflineBucketApi.FileUploadResponse {
    OfflineBucketApi.FileUploadResponse(id: id, path: path, key: key)
}

/// A Storage object's copy in the file cache, as a bucket's `cachedFile(path:)` or
/// `ensureCachedFile(path:isPublic:)` returns one.
///
/// - Parameters:
///   - path: Where the copy is.
///   - fetchedAt: When it was downloaded.
///   - sizeBytes: Its size in bytes.
public func cachedFile(path: String, fetchedAt: Date, sizeBytes: Int) -> CachedFile {
    CachedFile(path: path, fetchedAt: fetchedAt, sizeBytes: sizeBytes)
}

/// The progress of a download into the file cache, as a bucket's
/// `ensureCachedFile(path:isPublic:onProgress:)` reports it.
///
/// - Parameters:
///   - bytesReceived: The bytes received so far.
///   - totalBytes: The object's size; `nil` when the server gave none.
public func downloadProgress(bytesReceived: Int, totalBytes: Int?) -> DownloadProgress {
    DownloadProgress(bytesReceived: bytesReceived, totalBytes: totalBytes)
}

/// The answer to a one-shot read, an applied write or a function call, as `execute` returns one;
/// wrap it in `MutationOutcome.applied(result:)` for a write.
///
/// - Parameters:
///   - value: The decoded body.
///   - data: The body as sent; by default, empty.
///   - count: The total a count asked for.
///   - isFromCache: Whether the rows are the device's copy.
///   - fetchedAt: When the rows were fetched; `nil` for an answer straight from the server.
///   - hasPendingMutations: Whether a queued write changed one of the rows.
public func offlineResponse<Value: Sendable>(
    value: Value,
    data: Data = Data(),
    count: Int? = nil,
    isFromCache: Bool = false,
    fetchedAt: Date? = nil,
    hasPendingMutations: Bool = false
) -> OfflineResponse<Value> {
    OfflineResponse(
        value: value,
        data: data,
        count: count,
        isFromCache: isFromCache,
        fetchedAt: fetchedAt,
        hasPendingMutations: hasPendingMutations
    )
}

/// The answer to a one-shot read that names the rows a queued write changed: `pendingRowKeys` reads
/// the keys given, and `hasPendingMutations` reads `true` exactly when there is one.
///
/// - Parameters:
///   - value: The decoded body.
///   - data: The body as sent; by default, empty.
///   - count: The total a count asked for.
///   - isFromCache: Whether the rows are the device's copy.
///   - fetchedAt: When the rows were fetched; `nil` for an answer straight from the server.
///   - pendingRowKeys: The primary keys of the rows a queued write added, changed or removed, each
///     the table's key columns.
public func offlineResponse<Value: Sendable>(
    value: Value,
    data: Data = Data(),
    count: Int? = nil,
    isFromCache: Bool = false,
    fetchedAt: Date? = nil,
    pendingRowKeys: Set<[String: OfflineJSON]>
) -> OfflineResponse<Value> {
    OfflineResponse(
        value: value,
        data: data,
        count: count,
        isFromCache: isFromCache,
        fetchedAt: fetchedAt,
        hasPendingMutations: !pendingRowKeys.isEmpty,
        pendingRowKeys: pendingRowKeys
    )
}

/// The answer to a one-shot read the cache answered, as a fake of the app's own repository returns
/// one: `isFromCache` reads `true`, `fetchedAt` the time given, and `hasPendingMutations` the flag
/// given.
///
/// - Parameters:
///   - value: The decoded rows.
///   - data: The body as the cache holds it; by default, empty.
///   - count: The total a count asked for.
///   - fetchedAt: When the rows were fetched from the server.
///   - hasPendingMutations: Whether a queued write changed one of the rows.
public func cachedResponse<Value: Sendable>(
    value: Value,
    data: Data = Data(),
    count: Int? = nil,
    fetchedAt: Date,
    hasPendingMutations: Bool = false
) -> OfflineResponse<Value> {
    offlineResponse(
        value: value,
        data: data,
        count: count,
        isFromCache: true,
        fetchedAt: fetchedAt,
        hasPendingMutations: hasPendingMutations
    )
}

/// The answer to a one-shot read the cache answered that names the rows a queued write changed:
/// `isFromCache` reads `true`, `fetchedAt` the time given, `pendingRowKeys` the keys given, and
/// `hasPendingMutations` `true` exactly when there is one.
///
/// - Parameters:
///   - value: The decoded rows.
///   - data: The body as the cache holds it; by default, empty.
///   - count: The total a count asked for.
///   - fetchedAt: When the rows were fetched from the server.
///   - pendingRowKeys: The primary keys of the rows a queued write added, changed or removed, each
///     the table's key columns.
public func cachedResponse<Value: Sendable>(
    value: Value,
    data: Data = Data(),
    count: Int? = nil,
    fetchedAt: Date,
    pendingRowKeys: Set<[String: OfflineJSON]>
) -> OfflineResponse<Value> {
    offlineResponse(
        value: value,
        data: data,
        count: count,
        isFromCache: true,
        fetchedAt: fetchedAt,
        pendingRowKeys: pendingRowKeys
    )
}

/// The failure of a read that may use only the cache and found nothing there:
/// `OfflineError.Code.cacheMiss`.
public func cacheMissError(message: String = "No valid cached data found for this query") -> OfflineError {
    OfflineError(code: .cacheMiss, message: message)
}

/// The failure of a write the mutation queue could not hold, sent at once and refused for want of a
/// connection: `OfflineError.Code.mutationNotQueueable`, a network failure.
public func mutationNotQueueableError(
    message: String = "The write could not be queued, and could not be sent"
) -> OfflineError {
    OfflineError(code: .mutationNotQueueable, message: message, isNetworkOrConnectivityFailure: true)
}

/// The failure of a request made while the app had turned the client's network access off:
/// `OfflineError.Code.networkDisabled`, a network failure.
public func networkDisabledError(message: String = "The client's network access is turned off") -> OfflineError {
    OfflineError(code: .networkDisabled, message: message, isNetworkOrConnectivityFailure: true)
}

/// Why a sync pass stopped with writes still queued, as `SyncStatus.lastSyncError` reports it:
/// `OfflineError.Code.syncFailed`.
///
/// - Parameters:
///   - message: What the error says.
///   - httpStatus: The server's HTTP status; `nil` when it did not answer.
///   - code: The server's error code, kept only with a status.
///   - isConnectivityFailure: Whether the network caused it, which is what
///     `OfflineError.isNetworkOrConnectivityFailure` answers for it.
public func syncFailedError(
    message: String = "The sync pass stopped with writes still queued",
    httpStatus: Int? = nil,
    code: String? = nil,
    isConnectivityFailure: Bool = false
) -> OfflineError {
    OfflineError(
        code: .syncFailed,
        message: message,
        serverError: httpStatus.map { OfflineError.ServerError(status: $0, code: code, message: message, details: nil, hint: nil) },
        isNetworkOrConnectivityFailure: isConnectivityFailure
    )
}

/// Any other failure the client throws, such as a server's refusal (`OfflineError.Code.server`
/// with its ``serverError(status:code:message:details:hint:)``) or a connectivity failure.
///
/// - Parameters:
///   - code: What failed.
///   - message: What the error says.
///   - serverError: The server's refusal, when it answered.
///   - isNetworkOrConnectivityFailure: Whether the network failed rather than the server; always
///     `true` for `OfflineError.Code.connectivity`, as the client reports it.
public func offlineError(
    code: OfflineError.Code,
    message: String,
    serverError: OfflineError.ServerError? = nil,
    isNetworkOrConnectivityFailure: Bool = false
) -> OfflineError {
    OfflineError(
        code: code,
        message: message,
        serverError: serverError,
        isNetworkOrConnectivityFailure: isNetworkOrConnectivityFailure || code == .connectivity
    )
}
