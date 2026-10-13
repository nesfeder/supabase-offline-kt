// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import SupabaseOfflineCore

extension SupabaseOfflineClient {

    /// Where the client stands with the channel for ``Config/realtimeTables``:
    /// ``RealtimeStatusSequence/value`` now, or iterate it for the status now and each change.
    ///
    /// It never fails to give a value: ``RealtimeStatus/off`` before the client has opened, with
    /// no table named, and after it has closed.
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
