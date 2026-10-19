// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import SupabaseOfflineCore

/// A conflict a sync pass found, as ``SupabaseOfflineClient/Config/conflictResolver`` is asked
/// about it: a queued update or delete of one row whose version on the server is no longer the one
/// the device saw.
public struct MutationConflict: Sendable, Hashable {

    /// The write's ``PendingMutation/id``.
    public let mutationId: Int64

    /// The table the write targets.
    public let table: String

    /// ``MutationOperation/update`` or ``MutationOperation/delete``.
    public let operation: MutationOperation

    /// The row's primary-key values as the write's filter pins them, each as text: `["id": "1"]`
    /// for `.eq("id", value: 1)`, as ``PendingMutation/rowKeys`` gives them.
    public let rowKey: [String: OfflineJSON]

    /// The columns the write sets and the version column, as the device last saw the row.
    public let base: [String: OfflineJSON]

    /// The columns the write sets, with the values it sets them to; `nil` for a delete.
    public let local: [String: OfflineJSON]?

    /// The server's row now, in part (its key, the version column, the columns the write sets and
    /// those its filter names); `nil` when it is gone or hidden from this user.
    public let server: [String: OfflineJSON]?

    /// The columns the write sets whose server value differs from ``base``'s; empty for a delete
    /// and for a row that is gone.
    public let changedOnServer: Set<String>

    /// Of ``changedOnServer``, the columns ``local`` sets to another value than the server's: where
    /// the two changes disagree.
    public let conflictingColumns: Set<String>

    /// Built by the package, and by the testing product's factories.
    package init(
        mutationId: Int64,
        table: String,
        operation: MutationOperation,
        rowKey: [String: OfflineJSON],
        base: [String: OfflineJSON],
        local: [String: OfflineJSON]?,
        server: [String: OfflineJSON]?,
        changedOnServer: Set<String>,
        conflictingColumns: Set<String>
    ) {
        self.mutationId = mutationId
        self.table = table
        self.operation = operation
        self.rowKey = rowKey
        self.base = base
        self.local = local
        self.server = server
        self.changedOnServer = changedOnServer
        self.conflictingColumns = conflictingColumns
    }
}

extension MutationConflict {

    /// The engine's `conflict`; runs on the engine's thread.
    init(core conflict: BridgeConflict) throws {
        self.init(
            mutationId: conflict.mutationId,
            table: conflict.table,
            operation: try MutationOperation(core: conflict.operation),
            rowKey: try RowCoding.decode([String: OfflineJSON].self, from: conflict.rowKeyJson),
            base: try RowCoding.decode([String: OfflineJSON].self, from: conflict.baseJson),
            local: try conflict.localJson.map { try RowCoding.decode([String: OfflineJSON].self, from: $0) },
            server: try conflict.serverJson.map { try RowCoding.decode([String: OfflineJSON].self, from: $0) },
            changedOnServer: Set(conflict.changedOnServer),
            conflictingColumns: Set(conflict.conflictingColumns)
        )
    }
}
