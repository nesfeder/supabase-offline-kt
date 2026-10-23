// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import SupabaseOfflineCore

/// Why a call of ``SupabaseOfflineClient/syncPendingMutations(table:)`` returned when it did, and so
/// why writes may still be queued after it: ``SyncReport/stopReason``.
///
/// Match it by ``kind``. A later release may add kinds, so a `switch` over it keeps a `default`. An
/// app may rely on this much:
/// - every report has exactly one reason, and a kind keeps the meaning described on it;
/// - ``Kind/completed`` is the only kind a call ran to the end for; every other one stopped it short;
/// - after any kind but ``Kind/completed`` and ``Kind/localDataCleared``, the writes the call did not
///   send are still queued, and a later pass sends them;
/// - ``Kind/networkUnavailable``, ``Kind/serverFailure`` and ``Kind/localFailure`` are failures, and
///   the call records the failure as ``SyncStatus/lastSyncError``; no other kind is a failure, and
///   none of them records one there.
public struct SyncStopReason: Sendable, Hashable {

    /// Which reason it is. Extensible: a later release may add kinds without breaking a `switch`.
    public struct Kind: RawRepresentable, Hashable, Sendable, CustomStringConvertible {

        /// The kind's name, such as `networkUnavailable`.
        public let rawValue: String

        /// The kind named `rawValue`; a name this release does not define matches no reason it gives.
        public init(rawValue: String) {
            self.rawValue = rawValue
        }

        /// The kind's name.
        public var description: String { rawValue }

        /// The call went through every write it found queued: each was sent, refused, parked on the
        /// conflicted list or dropped for the server's row. ``SyncReport/remainingCount`` counts only
        /// the writes queued while it ran.
        public static let completed = Kind(rawValue: "completed")

        /// A write could not reach the server, or got no answer in time, so the call stopped there.
        /// It and the writes after it stay queued, and a later pass sends them once the network is
        /// back.
        public static let networkUnavailable = Kind(rawValue: "networkUnavailable")

        /// The server answered a write with a failure that may pass, so the call stopped there: a
        /// `5xx`, `408`, `429` or `401`, PostgREST's schema cache not yet knowing the table or a
        /// column, or a `403` or `409` while an earlier write of the user, which may be the row it
        /// needs, is still queued. ``SyncStopReason/error`` carries the server's status and code. A
        /// refusal for good is not a stop: it is in ``SyncReport/rejected``, and the call goes on.
        public static let serverFailure = Kind(rawValue: "serverFailure")

        /// A failure on this device, neither the network's nor the server's, stopped the call: the
        /// local database failing, a request header the HTTP client refuses (a line break in the
        /// project key or the access token), or the pass canceled by something other than the
        /// caller or a clear. ``SyncStopReason/error`` says so, with no status.
        public static let localFailure = Kind(rawValue: "localFailure")

        /// The session did not settle (signed in, or signed out) within the call's wait, or could not
        /// be told while it restored or refreshed, so nothing more was sent: a write sent then could
        /// go out as the wrong user. The writes are sent by a pass once the session settles.
        public static let sessionNotReady = Kind(rawValue: "sessionNotReady")

        /// The session changed user while the call ran (a sign-out, a sign-in or another account),
        /// so it stopped before its next request. The new user's writes go in a later pass.
        public static let sessionChanged = Kind(rawValue: "sessionChanged")

        /// The app turned the client's network access off with
        /// ``SupabaseOfflineClient/disableNetwork()``, so nothing more was sent. The writes are sent
        /// once ``SupabaseOfflineClient/enableNetwork()`` turns it back on.
        public static let networkDisabled = Kind(rawValue: "networkDisabled")

        /// A sync of one table stopped at a write that has an earlier write of the user still queued
        /// in another table: no write is sent ahead of an earlier one.
        /// ``SyncReport/heldBehindOtherTablesCount`` counts what it held back, and a sync of every
        /// table sends them in order. Never the kind of a sync of every table.
        public static let heldBehindOtherTables = Kind(rawValue: "heldBehindOtherTables")

        /// Local data was cleared (``SupabaseOfflineClient/clearLocalData()``) while the call ran:
        /// nothing was sent after that, and the writes the clear removed are gone.
        public static let localDataCleared = Kind(rawValue: "localDataCleared")
    }

    /// Which reason it is.
    public let kind: Kind

    /// The failure, for ``Kind/serverFailure`` and ``Kind/localFailure``: an
    /// ``OfflineError/Code/syncFailed`` error, as ``SyncStatus/lastSyncError`` holds it, with the
    /// server's status and code in ``OfflineError/serverError`` when the server caused it. `nil` for
    /// every other kind.
    public var error: OfflineError? {
        failure.map { failure in
            OfflineError(
                code: failure.code,
                message: failure.message,
                serverError: failure.serverError,
                isNetworkOrConnectivityFailure: failure.isNetworkOrConnectivityFailure
            )
        }
    }

    /// What ``error`` holds: all of it but an underlying Swift error, which no sync failure has, so
    /// that two reasons are equal when their kinds and failures are.
    private let failure: Failure?

    /// Built by the package, and by the testing product's factories.
    package init(kind: Kind, error: OfflineError? = nil) {
        self.kind = kind
        self.failure = error.map { error in
            Failure(
                code: error.code,
                message: error.message,
                serverError: error.serverError,
                isNetworkOrConnectivityFailure: error.isNetworkOrConnectivityFailure
            )
        }
    }

    /// The parts of a failure that tell one from another.
    private struct Failure: Sendable, Hashable {
        let code: OfflineError.Code
        let message: String
        let serverError: OfflineError.ServerError?
        let isNetworkOrConnectivityFailure: Bool
    }
}

extension SyncStopReason {

    /// The reason the core gives a report with these counts, for one built without a reason: held
    /// behind other tables when it held writes back, else completed.
    static func implied(heldBehindOtherTablesCount: Int) -> SyncStopReason {
        SyncStopReason(kind: heldBehindOtherTablesCount > 0 ? .heldBehindOtherTables : .completed)
    }

    /// The engine's `reason`; runs on the engine's thread. A kind this release does not know reads
    /// as one no `case` matches, with its error when it carries one.
    init(core reason: BridgeSyncStopReason) {
        let kind: Kind
        switch reason.kind {
        case .completed: kind = .completed
        case .networkUnavailable: kind = .networkUnavailable
        case .serverFailure: kind = .serverFailure
        case .localFailure: kind = .localFailure
        case .sessionNotReady: kind = .sessionNotReady
        case .sessionChanged: kind = .sessionChanged
        case .networkDisabled: kind = .networkDisabled
        case .heldBehindOtherTables: kind = .heldBehindOtherTables
        case .localDataCleared: kind = .localDataCleared
        default: kind = Kind(rawValue: "unknown")
        }
        self.init(kind: kind, error: reason.error.flatMap { OfflineError.thrown(for: $0) as? OfflineError })
    }
}
