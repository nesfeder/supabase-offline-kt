// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOfflineCore

/// The local data of in-memory clients, kept from one client to the next, for a test of what an
/// app does after a restart: the cache, the mutation queue with the rejected and conflicted lists,
/// and the file cache.
///
/// Hand the same store to each client's `SupabaseOfflineClient.inMemory(…, store:)`. A client
/// built over it after an earlier one's `SupabaseOfflineClient.close()` finds what that one left:
/// the writes still queued, which it replays, the rejected and conflicted writes, the cached rows,
/// and the cached files. Nothing a close does clears the store;
/// `SupabaseOfflineClient.clearLocalData()` does. What a client keeps only while it runs starts
/// over, as after a relaunch: the status's `lastSyncedAt` and `lastSyncError`, the switches
/// `SupabaseOfflineClient.disableNetwork()` and `SupabaseOfflineClient.disableRealtime()`, and the
/// waits between failed passes.
///
/// One client at a time: a client built over a store another client holds, open or still closing,
/// throws `OfflineError` `configuration` from every call, since both would replay the same queued
/// writes. Close the first client before building the next.
public final class InMemoryStore: @unchecked Sendable {

    /// The core's store, which the in-memory clients keep their data in.
    let relay: BridgeInMemoryStore

    /// An empty store.
    public init() {
        relay = BridgeInMemoryStore()
    }
}
