// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
public import Foundation
import SupabaseOfflineCore

/// A queued write the server refused for good on replay, or one the client could no longer replay
/// exactly as it was made.
///
/// It stays on the device, across restarts, until it is dismissed with
/// ``SupabaseOfflineClient/dismissRejectedMutation(id:)`` or queued again with
/// ``SupabaseOfflineClient/retryRejectedMutation(id:)``. The list keeps the newest 100 entries; when
/// it is full the oldest go, and their writes are lost.
public struct RejectedMutation: Sendable, Hashable, Identifiable {

    /// The write's id, as it had in the queue and in ``MutationOutcome/queued(pendingMutation:)``.
    public let id: Int64

    /// The table it targeted.
    public let table: String

    /// What it did.
    public let operation: MutationOperation

    /// When it was made, by the device's clock.
    public let createdAt: Date

    /// The primary-key values of the rows it touches, as far as they could be told; empty when they
    /// could not.
    public let rowKeys: [[String: OfflineJSON]]

    /// The body it sent; `nil` for a delete, or for a body too long to keep (see ``isRetryable``).
    public let body: OfflineJSON?

    /// What the server answered; `nil` when the client refused the write itself.
    public let serverError: OfflineError.ServerError?

    /// Why it was refused, in words.
    public let message: String

    /// When it was refused, by the device's clock.
    public let rejectedAt: Date

    /// Whether ``SupabaseOfflineClient/retryRejectedMutation(id:)`` can queue it again: `false` when
    /// its body was too long to keep.
    public let isRetryable: Bool

    /// Built by the package, and by the testing product's factories.
    package init(
        id: Int64,
        table: String,
        operation: MutationOperation,
        createdAt: Date,
        rowKeys: [[String: OfflineJSON]],
        body: OfflineJSON?,
        serverError: OfflineError.ServerError?,
        message: String,
        rejectedAt: Date,
        isRetryable: Bool
    ) {
        self.id = id
        self.table = table
        self.operation = operation
        self.createdAt = createdAt
        self.rowKeys = rowKeys
        self.body = body
        self.serverError = serverError
        self.message = message
        self.rejectedAt = rejectedAt
        self.isRetryable = isRetryable
    }
}

extension RejectedMutation {

    /// The core's rejected `mutation`; runs on the core's thread. The server's text is kept as sent.
    init(core mutation: BridgeRejectedMutation) throws {
        self.init(
            id: mutation.id,
            table: mutation.table,
            operation: try MutationOperation(core: mutation.operation),
            createdAt: Date(epochMilliseconds: mutation.createdAtEpochMillis),
            rowKeys: try RowCoding.decode([[String: OfflineJSON]].self, from: mutation.rowKeysJson),
            body: try mutation.bodyJson.map { try RowCoding.decode(OfflineJSON.self, from: $0) },
            serverError: mutation.serverError.map(OfflineError.ServerError.init(core:)),
            message: mutation.message,
            rejectedAt: Date(epochMilliseconds: mutation.rejectedAtEpochMillis),
            isRetryable: mutation.isRetryable
        )
    }
}

extension OfflineError.ServerError {

    /// The core's kept `serverError`.
    init(core serverError: BridgeServerError) {
        self.init(
            status: Int(serverError.status),
            code: serverError.code,
            message: serverError.message,
            details: serverError.details,
            hint: serverError.hint
        )
    }
}
