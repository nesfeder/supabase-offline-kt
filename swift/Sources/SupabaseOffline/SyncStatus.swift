// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
public import Foundation
import SupabaseOfflineCore

/// Where the client stands with the current user's queued writes and the network: one value to draw
/// a sync banner from.
///
/// The value a client starts with has not counted the queue yet: its counts are `0` for want of a
/// count and ``isQueueCounted`` is `false`, until the first count lands, shortly after the client
/// opens. An app that would otherwise show "nothing waiting" for a moment waits for
/// ``isQueueCounted`` instead.
public struct SyncStatus: Sendable {

    /// Whether the client believes it can reach the network; current as soon as
    /// ``SupabaseOfflineClient/disableNetwork()`` or ``SupabaseOfflineClient/enableNetwork()`` returns.
    public let network: NetworkStatus

    /// The current user's queued writes, the uploads queued with them included
    /// (``OfflineStorage/pendingUploads()``). The three counts are read together, so a write moving
    /// from the queue to the rejected or conflicted list is counted in exactly one.
    public let pendingMutationCount: Int

    /// The current user's entries on the rejected list, refused uploads included
    /// (``OfflineStorage/rejectedUploads()``).
    public let rejectedMutationCount: Int

    /// Whether a sync pass is sending, started by the app or by the client.
    public let isSyncing: Bool

    /// When a pass, or a write sent at once, last left the current user's queue empty, by the
    /// device's clock; `nil` until it happens. Kept in memory only.
    public let lastSyncedAt: Date?

    /// The failure that last stopped a sync pass with writes still queued
    /// (``SyncStopReason/Kind/networkUnavailable``, ``SyncStopReason/Kind/serverFailure`` or
    /// ``SyncStopReason/Kind/localFailure``), an ``OfflineError/Code/syncFailed`` error with the
    /// server's status and code when the server caused it. `nil` once a later pass runs to the end or
    /// stops because the session changed user, sending empties the queue, or local data is cleared. A
    /// pass that stops because the session is not ready, network access is off or an earlier write
    /// waits in another table leaves it as it was: no newer answer came for the writes it held back.
    public let lastSyncError: OfflineError?

    /// The current user's entries on ``SupabaseOfflineClient/conflictedMutations()``.
    public let conflictedMutationCount: Int

    /// Whether the three counts have been read from the queue: `false` in the value a client starts
    /// with, where they are `0` for want of a count, and `true` from the first counted value on, the
    /// last value at ``SupabaseOfflineClient/close()`` included.
    public let isQueueCounted: Bool

    /// Built by the package, and by the testing product's factories.
    package init(
        network: NetworkStatus,
        pendingMutationCount: Int,
        rejectedMutationCount: Int,
        isSyncing: Bool,
        lastSyncedAt: Date?,
        lastSyncError: OfflineError?,
        conflictedMutationCount: Int = 0,
        isQueueCounted: Bool = true
    ) {
        self.network = network
        self.pendingMutationCount = pendingMutationCount
        self.rejectedMutationCount = rejectedMutationCount
        self.isSyncing = isSyncing
        self.lastSyncedAt = lastSyncedAt
        self.lastSyncError = lastSyncError
        self.conflictedMutationCount = conflictedMutationCount
        self.isQueueCounted = isQueueCounted
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
            && lhs.conflictedMutationCount == rhs.conflictedMutationCount
            && lhs.isQueueCounted == rhs.isQueueCounted
    }
}

extension SyncStatus {

    /// The status of a client that has done nothing yet: the network unknown, the queue not counted.
    static let initial = SyncStatus(
        network: .unknown,
        pendingMutationCount: 0,
        rejectedMutationCount: 0,
        isSyncing: false,
        lastSyncedAt: nil,
        lastSyncError: nil,
        isQueueCounted: false
    )

    /// The engine's `status`; runs on the caller's thread or the engine's.
    init(core status: BridgeSyncStatus) {
        self.init(
            network: NetworkStatus(core: status.network),
            pendingMutationCount: Int(status.pendingMutationCount),
            rejectedMutationCount: Int(status.rejectedMutationCount),
            isSyncing: status.isSyncing,
            lastSyncedAt: status.lastSyncedAtEpochMillis.map { Date(epochMilliseconds: $0.int64Value) },
            lastSyncError: status.lastSyncError.flatMap { OfflineError.thrown(for: $0) as? OfflineError },
            conflictedMutationCount: Int(status.conflictedMutationCount),
            isQueueCounted: status.isQueueCounted
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
