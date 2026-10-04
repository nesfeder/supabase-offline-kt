// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
public import Foundation
import SupabaseOfflineCore

/// Where the client stands with the current user's queued writes and the network: one value to draw
/// a sync banner from.
public struct SyncStatus: Sendable {

    /// Whether the client believes it can reach the network; current as soon as
    /// ``SupabaseOfflineClient/disableNetwork()`` or ``SupabaseOfflineClient/enableNetwork()`` returns.
    public let network: NetworkStatus

    /// The current user's queued writes. The two counts are read together, so a write moving from the
    /// queue to the rejected list is counted in exactly one.
    public let pendingMutationCount: Int

    /// The current user's entries on the rejected list.
    public let rejectedMutationCount: Int

    /// Whether a sync pass is sending, started by the app or by the client.
    public let isSyncing: Bool

    /// When a pass, or a write sent at once, last left the current user's queue empty, by the
    /// device's clock; `nil` until it happens. Kept in memory only.
    public let lastSyncedAt: Date?

    /// Why the last pass stopped with writes still queued, an ``OfflineError/Code/syncFailed`` error
    /// with the server's status and code when the server caused it; `nil` once a later pass runs to
    /// the end, sending empties the queue, or local data is cleared.
    public let lastSyncError: OfflineError?

    /// Built by the package, and by the testing product's factories.
    package init(
        network: NetworkStatus,
        pendingMutationCount: Int,
        rejectedMutationCount: Int,
        isSyncing: Bool,
        lastSyncedAt: Date?,
        lastSyncError: OfflineError?
    ) {
        self.network = network
        self.pendingMutationCount = pendingMutationCount
        self.rejectedMutationCount = rejectedMutationCount
        self.isSyncing = isSyncing
        self.lastSyncedAt = lastSyncedAt
        self.lastSyncError = lastSyncError
    }
}

extension SyncStatus: Equatable {

    /// Whether both say the same; two sync errors are equal when their codes, messages, server
    /// errors and network flags are.
    public static func == (lhs: SyncStatus, rhs: SyncStatus) -> Bool {
        lhs.network == rhs.network
            && lhs.pendingMutationCount == rhs.pendingMutationCount
            && lhs.rejectedMutationCount == rhs.rejectedMutationCount
            && lhs.isSyncing == rhs.isSyncing
            && lhs.lastSyncedAt == rhs.lastSyncedAt
            && lhs.lastSyncError?.code == rhs.lastSyncError?.code
            && lhs.lastSyncError?.message == rhs.lastSyncError?.message
            && lhs.lastSyncError?.serverError == rhs.lastSyncError?.serverError
            && lhs.lastSyncError?.isNetworkOrConnectivityFailure == rhs.lastSyncError?.isNetworkOrConnectivityFailure
    }
}

extension SyncStatus {

    /// The status of a client that has done nothing yet: the network unknown, nothing queued.
    static let initial = SyncStatus(
        network: .unknown,
        pendingMutationCount: 0,
        rejectedMutationCount: 0,
        isSyncing: false,
        lastSyncedAt: nil,
        lastSyncError: nil
    )

    /// The engine's `status`; runs on the caller's thread or the engine's.
    init(core status: BridgeSyncStatus) {
        self.init(
            network: NetworkStatus(core: status.network),
            pendingMutationCount: Int(status.pendingMutationCount),
            rejectedMutationCount: Int(status.rejectedMutationCount),
            isSyncing: status.isSyncing,
            lastSyncedAt: status.lastSyncedAtEpochMillis.map { Date(epochMilliseconds: $0.int64Value) },
            lastSyncError: status.lastSyncError.flatMap { OfflineError.thrown(for: $0) as? OfflineError }
        )
    }
}

/// Whether the client believes it can reach the network: a hint, never a gate. The set is closed:
/// no release adds a case.
public enum NetworkStatus: Sendable, Hashable, CaseIterable {

    /// Nothing has said yet; treated as online.
    case unknown

    /// The network is reachable.
    case online

    /// The network is not reachable; requests the app makes are still tried.
    case offline

    /// The app turned the client's network access off with ``SupabaseOfflineClient/disableNetwork()``.
    case disabled
}

extension NetworkStatus {

    /// The engine's `status`; a status this release does not know reads as ``unknown``.
    init(core status: BridgeNetworkStatus) {
        switch status {
        case .online: self = .online
        case .offline: self = .offline
        case .disabled: self = .disabled
        default: self = .unknown
        }
    }
}
