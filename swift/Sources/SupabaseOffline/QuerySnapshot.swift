// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
public import Foundation
import SupabaseOfflineCore

/// One state of a live read: its rows with the user's queued writes applied, where they came from,
/// and how the refresh behind them stands. Each field is true of its own snapshot, so a change to
/// any of them yields a new one.
public struct QuerySnapshot<Value: Sendable>: Sendable {

    /// The rows decoded as the type asked for; `nil` while nothing is known for the query, an empty
    /// list when the server returned no rows.
    public let result: Value?

    /// Whether the rows are the device's copy rather than this read's answer from the server:
    /// `true` until its refresh succeeds, always under ``SnapshotStrategy/cacheOnly``, and again
    /// once a write marks them stale.
    public let isFromCache: Bool

    /// When the rows were fetched from the server, by the device's clock; never the time of a later
    /// local change applied to them.
    public let fetchedAt: Date?

    /// Whether a queued write added, changed or removed a row shown.
    public let hasPendingMutations: Bool

    /// How the refresh behind the read stands.
    public let refresh: RefreshState

    init(result: Value?, isFromCache: Bool, fetchedAt: Date?, hasPendingMutations: Bool, refresh: RefreshState) {
        self.result = result
        self.isFromCache = isFromCache
        self.fetchedAt = fetchedAt
        self.hasPendingMutations = hasPendingMutations
        self.refresh = refresh
    }
}

extension QuerySnapshot: Equatable where Value: Equatable {}

extension QuerySnapshot {

    /// The core's `snapshot`, its rows decoded by `decode`; runs on the core's thread.
    init(core snapshot: BridgeQuerySnapshot, decode: (String) throws -> Value) throws {
        self.init(
            result: try snapshot.data.map(decode),
            isFromCache: snapshot.isFromCache,
            fetchedAt: snapshot.fetchedAtEpochMillis.map { Date(epochMilliseconds: $0.int64Value) },
            hasPendingMutations: snapshot.hasPendingMutations,
            refresh: RefreshState(core: snapshot.refresh)
        )
    }
}

/// How the refresh behind a live read stands.
public enum RefreshState: Sendable {

    /// No refresh is running or waiting: the last one succeeded, or the read does not refresh.
    case idle

    /// A refresh is running, or is about to be tried again soon after a failure.
    case refreshing

    /// The refresh waits for the network, which is known to be unavailable; nothing is failing.
    case paused

    /// The refresh keeps failing while the network is believed up; `cause` is the failure that
    /// stalled it, kept while later ones differ. It is still tried, at most a minute apart.
    case stalled(cause: OfflineError)
}

extension RefreshState: Equatable {

    /// Whether both are the same state; two stalls are equal when their causes have the same code,
    /// message and server error.
    public static func == (lhs: RefreshState, rhs: RefreshState) -> Bool {
        switch (lhs, rhs) {
        case (.idle, .idle), (.refreshing, .refreshing), (.paused, .paused):
            return true
        case (.stalled(let left), .stalled(let right)):
            return left.code == right.code && left.message == right.message && left.serverError == right.serverError
        default:
            return false
        }
    }
}

extension RefreshState {

    /// The state the core reports; a state this release does not know reads as ``refreshing``.
    init(core state: BridgeRefreshState) {
        switch state.kind {
        case .idle: self = .idle
        case .paused: self = .paused
        case .stalled:
            let cause = state.cause.map(OfflineError.thrown(for:)) as? OfflineError
            self = .stalled(cause: cause ?? OfflineError(code: .unexpected, message: "The refresh stalled for no reported reason"))
        default: self = .refreshing
        }
    }
}
