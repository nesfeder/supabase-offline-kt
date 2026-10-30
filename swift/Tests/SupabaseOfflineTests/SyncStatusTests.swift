// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOfflineTesting
import Testing
@testable import SupabaseOffline

/// The sync status and the network switch, on the in-memory client.
struct SyncStatusTests {

    /// A row to write.
    private static let tea = TodoRow(id: 3, title: "Tea", done: false)

    @Test func turningTheNetworkOffFailsEveryRequestAtOnceAndHoldsSync() async throws {
        // Given a reachable server, and the client's network access turned off
        let server = RecordingFakeServer { _ in .status(201) }
        let client = SupabaseOfflineClient.inMemory(server: server.server)
        try await client.disableNetwork()
        try await client.disableNetwork()

        // When requests that must reach the server are made
        let read = await #expect(throws: OfflineError.self) {
            try await client.from("todos").select(readStrategy: .networkOnly).execute()
        }
        let write = await #expect(throws: OfflineError.self) {
            try await client.from("todos").insert(Self.tea, mutationStrategy: .onlineOnly).execute()
        }
        let call = await #expect(throws: OfflineError.self) {
            try await client.rpc("add_points", params: ["points": 5]).execute()
        }

        // Then each fails at once as a network failure, and nothing is sent
        for error in [read, write, call] {
            #expect(error?.code == .networkDisabled)
            #expect(error?.isNetworkOrConnectivityFailure == true)
        }
        #expect(client.syncStatus.value.network == .disabled)

        // When a write that may queue is made, and a sync asked for
        let outcome = try await client.from("todos").insert(Self.tea).execute()
        let report = try await client.syncPendingMutations()

        // Then it is queued, the sync sends nothing, and nothing goes out by itself either
        #expect(outcome.isQueued)
        #expect(report.syncedCount == 0)
        #expect(report.remainingCount == 1)
        try await Task.sleep(for: .milliseconds(300))
        #expect(server.requests.values.isEmpty)

        // When access is turned back on
        try await client.enableNetwork()

        // Then the queued write is sent by itself
        #expect(client.syncStatus.value.network == .unknown)
        #expect(await eventually { server.requests(withMethod: "POST").count == 1 })
        #expect(await eventually { client.syncStatus.value.pendingMutationCount == 0 })
        try await client.close()
    }

    @Test func aPassThatStopsSaysWhyUntilALaterOneRunsToTheEnd() async throws {
        // Given a queued write and a server that is unavailable
        let clock = ManualClock(now: Date(timeIntervalSince1970: 1_791_115_200))
        let server = RecordingFakeServer { _ in .noConnection }
        let client = SupabaseOfflineClient.inMemory(config: .init(autoSync: false), server: server.server, clock: clock)
        _ = try await client.from("todos").insert(Self.tea).execute()
        server.answer { _ in .json(#"{"code":"PGRST000","message":"unavailable","details":null,"hint":null}"#, status: 503) }

        // When a sync is refused for a while
        let refused = try await client.syncPendingMutations()

        // Then it stops, keeps the write, and the status says why
        #expect(refused.remainingCount == 1)
        #expect(refused.rejected.isEmpty)
        #expect(await eventually { client.syncStatus.value.lastSyncError?.serverError?.status == 503 })
        let serverCause = try #require(client.syncStatus.value.lastSyncError)
        #expect(serverCause.code == .syncFailed)
        #expect(serverCause.serverError?.code == "PGRST000")
        #expect(serverCause.httpStatus == 503)
        #expect(!serverCause.isNetworkOrConnectivityFailure)

        // When a sync cannot reach the server
        server.answer { _ in .noConnection }
        _ = try await client.syncPendingMutations()

        // Then the cause is the network's
        #expect(await eventually { client.syncStatus.value.lastSyncError?.isNetworkOrConnectivityFailure == true })
        #expect(client.syncStatus.value.lastSyncError?.httpStatus == nil)

        // When a sync runs to the end
        server.answer { _ in .status(201) }
        let sent = try await client.syncPendingMutations()

        // Then the cause is cleared and the time recorded
        #expect(sent.syncedCount == 1)
        let settled = SyncStatus(
            network: .unknown,
            pendingMutationCount: 0,
            rejectedMutationCount: 0,
            isSyncing: false,
            lastSyncedAt: clock.now,
            lastSyncError: nil
        )
        #expect(await eventually { client.syncStatus.value == settled })
        try await client.close()
    }

    @Test func theStatusSaysWhenItsCountsHaveBeenRead() async throws {
        // Given a client that has not opened its database yet
        let server = RecordingFakeServer { _ in .status(201) }
        let client = SupabaseOfflineClient.inMemory(config: .init(autoSync: false), server: server.server)
        let starting = client.syncStatus.value

        // When the client opens and counts the queue, then closes
        #expect(await eventually { client.syncStatus.value.isQueueCounted })
        let counted = client.syncStatus.value
        try await client.close()

        // Then the value it started with said so, with counts of zero for want of a count, and
        // from the first count on it stays counted, the last value included
        #expect(!starting.isQueueCounted)
        #expect(starting.pendingMutationCount == 0)
        #expect(counted.isQueueCounted)
        #expect(counted.pendingMutationCount == 0)
        #expect(client.syncStatus.value.isQueueCounted)
    }
}
