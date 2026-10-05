// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
public import Foundation
import SupabaseOfflineCore

/// One write waiting in the mutation queue.
public struct PendingMutation: Sendable, Hashable, Identifiable {

    /// The write's id in the queue: it survives a restart, and a refusal on replay keeps it.
    /// Writes are sent in id order.
    public let id: Int64

    /// The table it writes to.
    public let table: String

    /// What it does.
    public let operation: MutationOperation

    /// When it was made, by the device's clock.
    public let createdAt: Date

    /// The primary-key values of the rows it touches, as far as they can be told from its body and
    /// its filter; empty when they cannot.
    public let rowKeys: [[String: OfflineJSON]]

    /// The body it sends; `nil` for a delete.
    public let body: OfflineJSON?

    init(
        id: Int64,
        table: String,
        operation: MutationOperation,
        createdAt: Date,
        rowKeys: [[String: OfflineJSON]],
        body: OfflineJSON?
    ) {
        self.id = id
        self.table = table
        self.operation = operation
        self.createdAt = createdAt
        self.rowKeys = rowKeys
        self.body = body
    }
}

extension PendingMutation {

    /// The core's queued `mutation`; runs on the core's thread.
    init(core mutation: BridgePendingMutation) throws {
        self.init(
            id: mutation.id,
            table: mutation.table,
            operation: try MutationOperation(core: mutation.operation),
            createdAt: Date(epochMilliseconds: mutation.createdAtEpochMillis),
            rowKeys: try RowCoding.decode([[String: OfflineJSON]].self, from: mutation.rowKeysJson),
            body: try mutation.bodyJson.map { try RowCoding.decode(OfflineJSON.self, from: $0) }
        )
    }
}

/// What a queued write does, in PostgREST's words. The set is closed: no release adds a case.
public enum MutationOperation: String, Sendable, Hashable, CaseIterable {

    /// Inserts rows.
    case insert

    /// Inserts rows, or merges them into the rows they conflict with.
    case upsert

    /// Sets columns on the rows its filter matches.
    case update

    /// Deletes the rows its filter matches.
    case delete
}

extension MutationOperation {

    /// The core's `operation`.
    init(core operation: BridgeMutationOperation) throws {
        switch operation {
        case .insert: self = .insert
        case .upsert: self = .upsert
        case .update: self = .update
        case .delete_: self = .delete
        default:
            throw OfflineError(code: .unexpected, message: "A queued write is of a kind this release does not know")
        }
    }
}
