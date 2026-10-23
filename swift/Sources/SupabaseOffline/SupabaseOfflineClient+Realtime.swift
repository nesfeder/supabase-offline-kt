// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import SupabaseOfflineCore

extension SupabaseOfflineClient {

    /// Where the client stands with the channels for ``Config/realtimeTables``, summed up as one
    /// value: ``RealtimeStatusSequence/value`` now, or iterate it for the status now and each change.
    ///
    /// It reads ``RealtimeStatus/stalled(cause:)`` while any table does, with the cause of the first
    /// named, otherwise ``RealtimeStatus/subscribing`` while any join is in flight, and
    /// ``RealtimeStatus/subscribed`` once every table is joined; ``realtimeTableStatus`` says which.
    /// It never fails to give a value: ``RealtimeStatus/off`` before the client has opened, with no
    /// table named, and after it has closed.
    public var realtimeStatus: RealtimeStatusSequence {
        RealtimeStatusSequence(
            live: liveSequence { core, yield, finish in
                core.observeRealtimeStatus(
                    onStatus: { status in yield(RealtimeStatus(core: status)) },
                    onCompletion: { failure in finish(failure.map(OfflineError.thrown(for:))) }
                )
            },
            current: { [connection] in
                connection.openedClientIfReady().map { RealtimeStatus(core: $0.client.currentRealtimeStatus()) } ?? .off
            }
        )
    }

    /// Where each of ``Config/realtimeTables`` stands, keyed by the table as named:
    /// ``RealtimeTableStatusSequence/value`` now, or iterate it for the statuses now and each change.
    ///
    /// Each table is followed on a channel of its own, so a table the server refuses, such as one
    /// whose filter names a column a migration renamed, reads ``RealtimeStatus/stalled(cause:)``
    /// alone and is joined again on its own schedule, while the others stay
    /// ``RealtimeStatus/subscribed``; ``realtimeStatus`` sums them up. It never fails to give a value:
    /// every named table reads ``RealtimeStatus/off`` before the client has opened and after it has
    /// closed, and it is empty with no table named.
    public var realtimeTableStatus: RealtimeTableStatusSequence {
        let tables = realtimeTables
        return RealtimeTableStatusSequence(
            live: liveSequence { core, yield, finish in
                core.observeRealtimeTableStatuses(
                    onStatuses: { statuses in yield(RealtimeStatus.byTable(tables, core: statuses)) },
                    onCompletion: { failure in finish(failure.map(OfflineError.thrown(for:))) }
                )
            },
            current: { [connection] in
                RealtimeStatus.byTable(
                    tables,
                    core: connection.openedClientIfReady().map { $0.client.currentRealtimeTableStatuses() } ?? []
                )
            }
        )
    }

    /// Leaves the Realtime channel and joins none until ``enableRealtime()``; ``realtimeStatus``
    /// reads ``RealtimeStatus/off`` once this returns.
    ///
    /// The switch is not kept: a new client starts with the subscription on. Calling it again
    /// changes nothing. The client learns who the user is only from ``Config/accessToken``, and
    /// joins the channel with the token it returns at the join: call this before signing in or
    /// out, and ``enableRealtime()`` once the next token is in place, so the channel is joined
    /// again as that user; with no table named it does nothing.
    ///
    /// - Throws: ``OfflineError``: `clientClosed` once the client is closed.
    public func disableRealtime() async throws {
        try await connection.call { core, complete in
            core.disableRealtime { failure in complete(coreResult(failure)) }
        }
    }

    /// Joins the channel again after ``disableRealtime()``, as soon as the network allows, which
    /// reads the named tables again. Calling it while the subscription is on changes nothing.
    ///
    /// - Throws: ``OfflineError``: `clientClosed` once the client is closed.
    public func enableRealtime() async throws {
        try await connection.call { core, complete in
            core.enableRealtime { failure in complete(coreResult(failure)) }
        }
    }
}
