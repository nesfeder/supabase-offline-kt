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

    /// The writes still queued when the call returned.
    public let remainingCount: Int

    /// Of ``syncedCount``, the inserts whose rows the server already held, because an earlier send
    /// whose answer was lost had reached it.
    public let alreadyOnServerCount: Int

    /// Built by the package, and by the testing product's factories.
    package init(syncedCount: Int, rejected: [RejectedMutation], remainingCount: Int, alreadyOnServerCount: Int) {
        self.syncedCount = syncedCount
        self.rejected = rejected
        self.remainingCount = remainingCount
        self.alreadyOnServerCount = alreadyOnServerCount
    }
}

extension SyncReport {

    /// The core's `report`; runs on the core's thread.
    init(core report: BridgeSyncReport) throws {
        self.init(
            syncedCount: Int(report.syncedCount),
            rejected: try report.rejected.map(RejectedMutation.init(core:)),
            remainingCount: Int(report.remainingCount),
            alreadyOnServerCount: Int(report.alreadyOnServerCount)
        )
    }
}
