// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
public import Foundation
public import SupabaseOffline

// Values a test double of the app's own returns or throws in place of the client's. Their
// initializers are not public, so the package can add fields without breaking a caller; these
// factories take every field, and default the ones a test rarely sets.

/// A write waiting in the mutation queue, as ``SupabaseOfflineClient/pendingMutations(table:)`` lists
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

/// A queued write the server refused for good, as ``SupabaseOfflineClient/rejectedMutations()`` lists
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

/// What a call of ``SupabaseOfflineClient/syncPendingMutations(table:)`` did.
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

/// Where the client stands with the queue and the network, as ``SupabaseOfflineClient/syncStatus``
/// holds it; every field defaults to the state of a client that has done nothing yet.
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

/// One state of a live read, as ``OfflinePostgrestQueryBuilder/selectAsSequence(_:as:readStrategy:)``
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

/// The answer to a one-shot read, an applied write or a function call, as `execute` returns one;
/// wrap it in ``MutationOutcome/applied(result:)`` for a write.
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

/// The failure of a read that may use only the cache and found nothing there:
/// ``OfflineError/Code/cacheMiss``.
public func cacheMissError(message: String = "No valid cached data found for this query") -> OfflineError {
    OfflineError(code: .cacheMiss, message: message)
}

/// The failure of a write the mutation queue could not hold, sent at once and refused for want of a
/// connection: ``OfflineError/Code/mutationNotQueueable``, a network failure.
public func mutationNotQueueableError(
    message: String = "The write could not be queued, and could not be sent"
) -> OfflineError {
    OfflineError(code: .mutationNotQueueable, message: message, isNetworkOrConnectivityFailure: true)
}

/// The failure of a request made while the app had turned the client's network access off:
/// ``OfflineError/Code/networkDisabled``, a network failure.
public func networkDisabledError(message: String = "The client's network access is turned off") -> OfflineError {
    OfflineError(code: .networkDisabled, message: message, isNetworkOrConnectivityFailure: true)
}

/// Why a sync pass stopped with writes still queued, as ``SyncStatus/lastSyncError`` reports it:
/// ``OfflineError/Code/syncFailed``.
///
/// - Parameters:
///   - message: What the error says.
///   - httpStatus: The server's HTTP status; `nil` when it did not answer.
///   - code: The server's error code, kept only with a status.
///   - isConnectivityFailure: Whether the network caused it, which is what
///     ``OfflineError/isNetworkOrConnectivityFailure`` answers for it.
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

/// Any other failure the client throws, such as a server's refusal (``OfflineError/Code/server``
/// with its ``serverError(status:code:message:details:hint:)``) or a connectivity failure.
///
/// - Parameters:
///   - code: What failed.
///   - message: What the error says.
///   - serverError: The server's refusal, when it answered.
///   - isNetworkOrConnectivityFailure: Whether the network failed rather than the server; always
///     `true` for ``OfflineError/Code/connectivity``, as the client reports it.
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
