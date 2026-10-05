// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import SupabaseOffline

/// The one thing the app says about syncing: the Tasks subtitle and the status above the tab bar.
enum SyncHeadline: Equatable {

    /// Online with nothing waiting: the status is hidden.
    case upToDate

    /// A sync just emptied the queue; shown for a moment, then ``upToDate``.
    case justSynced

    /// No network, or Simulate offline: changes are kept on the device.
    case offline(pendingCount: Int)

    /// The queue is being sent now.
    case syncing(pendingCount: Int)

    /// Changes wait while the server is in reach, between two syncs.
    case waiting(pendingCount: Int)

    /// The network is up but the server does not answer; the list shown is the saved one.
    case showingSavedData

    /// The headline for `status` and the task list's `refresh`, in order of what matters most.
    static func of(status: SyncStatus, refresh: RefreshState) -> SyncHeadline {
        let pending = status.pendingMutationCount
        if status.network == .offline || status.network == .disabled { return .offline(pendingCount: pending) }
        if status.isSyncing, pending > 0 { return .syncing(pendingCount: pending) }
        if case .stalled = refresh { return .showingSavedData }
        if pending > 0 { return .waiting(pendingCount: pending) }
        return .upToDate
    }

    /// The status line above the tab bar; `nil` when there is nothing to say.
    var banner: String? {
        switch self {
        case .upToDate:
            return nil
        case .justSynced:
            return "All changes synced"
        case .offline(let count):
            guard count > 0 else { return "Offline — changes are saved on this device" }
            return "Offline — \(Wording.count(count, "change is", "changes are")) saved on this device"
        case .syncing(let count):
            return "Syncing \(Wording.count(count, "change", "changes"))"
        case .waiting(let count):
            return "\(Wording.count(count, "change", "changes")) waiting"
        case .showingSavedData:
            return "Showing saved data — can’t reach the server"
        }
    }

    /// The second half of the Tasks subtitle, after the open count.
    var subtitle: String {
        switch self {
        case .upToDate, .justSynced: return "all synced"
        case .offline: return "offline"
        case .syncing: return "syncing…"
        case .waiting(let count): return "\(count) waiting to sync"
        case .showingSavedData: return "showing saved data"
        }
    }

    /// The symbol beside the status line.
    var systemImage: String {
        switch self {
        case .upToDate, .justSynced: return "checkmark.icloud"
        case .offline: return "icloud.slash"
        case .syncing: return "arrow.triangle.2.circlepath.icloud"
        case .waiting: return "icloud.and.arrow.up"
        case .showingSavedData: return "exclamationmark.icloud"
        }
    }
}
