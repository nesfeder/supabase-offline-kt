// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import SupabaseOfflineCore

/// How a conflict is settled: what ``SupabaseOfflineClient/Config/conflictResolver`` returns, and
/// what ``SupabaseOfflineClient/resolveConflictedMutation(id:resolution:)`` takes.
///
/// Without a resolver, a moved row is settled with ``keepLocal`` and an update of a row that is
/// gone with ``keepServer``. More cases may be added in a minor release: the app only passes one
/// in.
public enum ConflictResolution: Sendable, Hashable {

    /// Sends the write again, preconditioned on the server's row as it is now; a row that is gone
    /// cannot take it, so the write is parked instead.
    case keepLocal

    /// Drops the write without sending it; the server's row stands, and the cache shows it.
    case keepServer

    /// Sends `columns`, at least one, as the update's body instead of the write's own; for a
    /// delete, or a row that is gone, the write is parked instead.
    case apply(columns: [String: OfflineJSON])

    /// Parks the write on ``SupabaseOfflineClient/conflictedMutations()`` for the app to settle
    /// later.
    case hold
}

extension ConflictResolution {

    /// The decision as the engine takes it.
    var coreDecision: BridgeConflictDecision {
        switch self {
        case .keepLocal: return .keepLocal
        case .keepServer: return .keepServer
        case .apply: return .apply
        case .hold: return .hold
        }
    }

    /// The columns of ``apply(columns:)`` as JSON text; `nil` for the other cases.
    ///
    /// - Throws: ``OfflineError``: `encoding` when the columns cannot be encoded.
    func coreColumnsJSON() throws -> String? {
        guard case .apply(let columns) = self else { return nil }
        return try RowCoding.jsonText(for: columns)
    }
}
