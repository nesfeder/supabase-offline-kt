// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import SupabaseOfflineCore

extension SupabaseOfflineClient {

    /// Where the client stands with the queue and the network: ``SyncStatusSequence/value`` now, or
    /// iterate it for the status now and each change.
    ///
    /// It never fails to give a value, before the client has opened and after it has closed.
    public var syncStatus: SyncStatusSequence {
        SyncStatusSequence(
            live: liveSequence { core, yield, finish in
                core.observeSyncStatus(
                    onStatus: { status in yield(SyncStatus(core: status)) },
                    onCompletion: { failure in finish(failure.map(OfflineError.thrown(for:))) }
                )
            },
            current: { [connection] in
                connection.openedClientIfReady().map { SyncStatus(core: $0.client.currentSyncStatus()) } ?? .initial
            }
        )
    }

    /// Turns the client's network access off, as an airplane-mode switch does, until
    /// ``enableNetwork()``.
    ///
    /// While it is off nothing is sent. Reads are answered from the cache as offline, live reads
    /// report ``RefreshState/paused``, writes that may queue are queued, and sync passes send
    /// nothing. A request that must reach the server (a ``ReadStrategy/networkOnly`` read, a
    /// ``MutationStrategy/onlineOnly`` write, a function call) throws ``OfflineError/Code/networkDisabled``
    /// at once, which counts as a network failure; a request already on the wire still answers. The
    /// switch is not kept: a new client starts with access on. Calling it again changes nothing.
    public func disableNetwork() async throws {
        try await connection.call { core, complete in
            core.disableNetwork { failure in complete(coreResult(failure)) }
        }
    }

    /// Turns the client's network access back on: a waiting refresh runs, and with
    /// ``Config/autoSync`` the queued writes are sent once the network is reachable. Calling it
    /// while access is on changes nothing.
    public func enableNetwork() async throws {
        try await connection.call { core, complete in
            core.enableNetwork { failure in complete(coreResult(failure)) }
        }
    }
}
