// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import SupabaseOfflineCore

/// Where the client stands with the channel that keeps one of ``SupabaseOfflineClient/Config/realtimeTables``
/// current, or with all of them, summed up.
public enum RealtimeStatus: Sendable {

    /// No table is named, ``SupabaseOfflineClient/disableRealtime()`` was called, or the client is
    /// closed.
    case off

    /// Tables are named and the subscription is on, but the network is known down or turned off
    /// with ``SupabaseOfflineClient/disableNetwork()``; the channel is joined once it is back.
    case paused

    /// A join is in flight.
    case subscribing

    /// Changes arrive and are applied; entered again after every rejoin, which reads the named
    /// tables again.
    case subscribed

    /// The join was refused or the channel ended while the network was believed up; `cause` says
    /// why: ``OfflineError/Code/server`` with the channel's message when the server refused or
    /// closed it (Realtime sends no HTTP status, so ``OfflineError/serverError`` is `nil`), or
    /// ``OfflineError/Code/connectivity`` when the socket could not be opened. It is tried again
    /// after a bounded wait, at most a minute apart.
    case stalled(cause: OfflineError)
}

extension RealtimeStatus: Equatable {

    /// Whether both are the same state; two stalls are equal when their causes have the same code,
    /// message and server error.
    public static func == (lhs: RealtimeStatus, rhs: RealtimeStatus) -> Bool {
        switch (lhs, rhs) {
        case (.off, .off), (.paused, .paused), (.subscribing, .subscribing), (.subscribed, .subscribed):
            return true
        case (.stalled(let left), .stalled(let right)):
            return left.code == right.code && left.message == right.message && left.serverError == right.serverError
        default:
            return false
        }
    }
}

extension RealtimeStatus {

    /// Each of `tables` with the status the core reports for it, matched by name and schema; a
    /// table the core reports nothing for reads as ``off``.
    static func byTable(_ tables: [RealtimeTable], core statuses: [BridgeRealtimeTableStatus]) -> [RealtimeTable: RealtimeStatus] {
        var byTable: [RealtimeTable: RealtimeStatus] = [:]
        for table in tables {
            let reported = statuses.first { $0.table == table.table && $0.schema == table.schema }
            byTable[table] = reported.map { RealtimeStatus(core: $0.status) } ?? .off
        }
        return byTable
    }

    /// The status the core reports; a state this release does not know reads as ``off``.
    init(core status: BridgeRealtimeStatus) {
        switch status.kind {
        case .paused: self = .paused
        case .subscribing: self = .subscribing
        case .subscribed: self = .subscribed
        case .stalled:
            let cause = status.cause.map(OfflineError.thrown(for:)) as? OfflineError
            self = .stalled(cause: cause ?? OfflineError(code: .unexpected, message: "The channel stalled for no reported reason"))
        default: self = .off
        }
    }
}
