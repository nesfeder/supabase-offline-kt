// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
public import Foundation
import SupabaseOfflineCore

/// A queued write parked because its row moved on the server and the conflict was not settled
/// during the replay.
///
/// It stays on the device, across restarts, until it is settled with
/// ``SupabaseOfflineClient/resolveConflictedMutation(id:resolution:)`` or dismissed with
/// ``SupabaseOfflineClient/dismissConflictedMutation(id:)``. The list keeps the newest 100 entries.
public struct ConflictedMutation: Sendable, Hashable, Identifiable {

    /// The write's id, the ``PendingMutation/id`` it had while it waited.
    public let id: Int64

    /// The table the write targeted.
    public let table: String

    /// ``MutationOperation/update`` or ``MutationOperation/delete``.
    public let operation: MutationOperation

    /// When the write was made, by the device's clock.
    public let createdAt: Date

    /// The row's primary-key values as the write's filter pins them, each as text, as
    /// ``PendingMutation/rowKeys`` gives them.
    public let rowKeys: [[String: OfflineJSON]]

    /// The body the write sends; `nil` for a delete, or one too large to keep (which
    /// ``ConflictResolution/keepLocal`` cannot resend).
    public let body: OfflineJSON?

    /// The columns the write sets and the version column, as the device last saw the row; empty
    /// when they could not be kept.
    public let base: [String: OfflineJSON]

    /// The server's row when the replay read it, in part; `nil` when it was gone, or too large to
    /// keep.
    public let server: [String: OfflineJSON]?

    /// The server row's version then, as text, which a write resolved from the list is
    /// preconditioned on; `nil` when the row was gone.
    public let serverVersion: String?

    /// The columns the write sets whose server value differed from ``base``'s.
    public let changedOnServer: Set<String>

    /// Of ``changedOnServer``, those the write sets to another value than the server's.
    public let conflictingColumns: Set<String>

    /// Why it was parked, in words.
    public let message: String

    /// When it was parked, by the device's clock.
    public let conflictedAt: Date

    /// Built by the package, and by the testing product's factories.
    package init(
        id: Int64,
        table: String,
        operation: MutationOperation,
        createdAt: Date,
        rowKeys: [[String: OfflineJSON]],
        body: OfflineJSON?,
        base: [String: OfflineJSON],
        server: [String: OfflineJSON]?,
        serverVersion: String?,
        changedOnServer: Set<String>,
        conflictingColumns: Set<String>,
        message: String,
        conflictedAt: Date
    ) {
        self.id = id
        self.table = table
        self.operation = operation
        self.createdAt = createdAt
        self.rowKeys = rowKeys
        self.body = body
        self.base = base
        self.server = server
        self.serverVersion = serverVersion
        self.changedOnServer = changedOnServer
        self.conflictingColumns = conflictingColumns
        self.message = message
        self.conflictedAt = conflictedAt
    }
}

extension ConflictedMutation {

    /// The engine's conflicted `mutation`; runs on the engine's thread.
    init(core mutation: BridgeConflictedMutation) throws {
        self.init(
            id: mutation.id,
            table: mutation.table,
            operation: try MutationOperation(core: mutation.operation),
            createdAt: Date(epochMilliseconds: mutation.createdAtEpochMillis),
            rowKeys: try RowCoding.decode([[String: OfflineJSON]].self, from: mutation.rowKeysJson),
            body: try mutation.bodyJson.map { try RowCoding.decode(OfflineJSON.self, from: $0) },
            base: try RowCoding.decode([String: OfflineJSON].self, from: mutation.baseJson),
            server: try mutation.serverJson.map { try RowCoding.decode([String: OfflineJSON].self, from: $0) },
            serverVersion: mutation.serverVersion,
            changedOnServer: Set(mutation.changedOnServer),
            conflictingColumns: Set(mutation.conflictingColumns),
            message: mutation.message,
            conflictedAt: Date(epochMilliseconds: mutation.conflictedAtEpochMillis)
        )
    }
}
