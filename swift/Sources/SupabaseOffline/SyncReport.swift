// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import SupabaseOfflineCore

/// What one call of ``SupabaseOfflineClient/syncPendingMutations(table:)`` did with the current
/// user's queued writes.
public struct SyncReport: Sendable, Hashable {

    /// The writes this call sent and the server accepted.
    public let syncedCount: Int

    /// The writes refused for good during this call, by the server or by the client itself; each is
    /// now on the rejected list.
    public let rejected: [RejectedMutation]

    /// The writes still queued when the call returned, of the table synced or of every table: more
    /// than zero when network access is off, the network could not be reached, or the server failed
    /// in a way that may pass (``SyncStatus/lastSyncError`` says why), or when writes wait behind
    /// other tables (``heldBehindOtherTablesCount``).
    public let remainingCount: Int

    /// Of ``syncedCount``, the inserts whose rows the server already held, because an earlier send
    /// whose answer was lost had reached it.
    public let alreadyOnServerCount: Int

    /// Of ``remainingCount``, the writes a sync of one table held back because an earlier write of
    /// the user to another table is still queued: no write is sent ahead of an earlier one, so a
    /// sync of every table sends them in order. Always `0` for a sync of every table.
    public let heldBehindOtherTablesCount: Int

    /// Of ``syncedCount``, the guarded writes whose row had moved on the server and that were sent
    /// once the conflict was settled.
    public let conflictsResolvedCount: Int

    /// The guarded writes dropped without being sent because the server's row stands
    /// (``ConflictResolution/keepServer``).
    public let conflictsKeptServerCount: Int

    /// The guarded writes parked during this call, now on
    /// ``SupabaseOfflineClient/conflictedMutations()`` unless local data was cleared since.
    public let conflicted: [ConflictedMutation]

    /// Built by the package, and by the testing product's factories.
    package init(
        syncedCount: Int,
        rejected: [RejectedMutation],
        remainingCount: Int,
        alreadyOnServerCount: Int,
        heldBehindOtherTablesCount: Int,
        conflictsResolvedCount: Int = 0,
        conflictsKeptServerCount: Int = 0,
        conflicted: [ConflictedMutation] = []
    ) {
        self.syncedCount = syncedCount
        self.rejected = rejected
        self.remainingCount = remainingCount
        self.alreadyOnServerCount = alreadyOnServerCount
        self.heldBehindOtherTablesCount = heldBehindOtherTablesCount
        self.conflictsResolvedCount = conflictsResolvedCount
        self.conflictsKeptServerCount = conflictsKeptServerCount
        self.conflicted = conflicted
    }
}

extension SyncReport {

    /// The engine's `report`; runs on the engine's thread.
    init(core report: BridgeSyncReport) throws {
        self.init(
            syncedCount: Int(report.syncedCount),
            rejected: try report.rejected.map(RejectedMutation.init(core:)),
            remainingCount: Int(report.remainingCount),
            alreadyOnServerCount: Int(report.alreadyOnServerCount),
            heldBehindOtherTablesCount: Int(report.heldBehindOtherTablesCount),
            conflictsResolvedCount: Int(report.conflictsResolvedCount),
            conflictsKeptServerCount: Int(report.conflictsKeptServerCount),
            conflicted: try report.conflicted.map(ConflictedMutation.init(core:))
        )
    }
}
