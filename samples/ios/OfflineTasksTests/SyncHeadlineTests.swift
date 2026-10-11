// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import SupabaseOffline
import SupabaseOfflineTesting
import Testing

@testable import OfflineTasks

/// What the app says about syncing, from the client's status built with the testing product.
@Suite("The sync headline")
struct SyncHeadlineTests {

    @Test("Offline wins over everything else, with the count of changes kept")
    func offline() {
        let status = syncStatus(network: .disabled, pendingMutationCount: 3, isSyncing: true)
        let headline = SyncHeadline.of(status: status, refresh: .paused)
        #expect(headline == .offline(pendingCount: 3))
        #expect(headline.banner == "Offline — 3 changes are saved on this device")
        #expect(SyncHeadline.offline(pendingCount: 1).banner == "Offline — 1 change is saved on this device")
        #expect(SyncHeadline.offline(pendingCount: 0).banner == "Offline — changes are saved on this device")
    }

    @Test("Online: syncing, then a stalled list, then changes waiting, then nothing to say")
    func online() {
        let syncing = syncStatus(network: .online, pendingMutationCount: 2, isSyncing: true)
        #expect(SyncHeadline.of(status: syncing, refresh: .idle).banner == "Syncing 2 changes")
        let stalled = RefreshState.stalled(cause: offlineError(code: .connectivity, message: "No route"))
        let waiting = syncStatus(network: .online, pendingMutationCount: 1)
        #expect(SyncHeadline.of(status: waiting, refresh: stalled) == .showingSavedData)
        #expect(SyncHeadline.of(status: waiting, refresh: .idle).banner == "1 change waiting")
        #expect(SyncHeadline.of(status: syncStatus(network: .online), refresh: .idle).banner == nil)
    }
}
