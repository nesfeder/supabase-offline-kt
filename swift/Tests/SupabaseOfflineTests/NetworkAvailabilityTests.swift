// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOfflineTesting
import Testing
@testable import SupabaseOffline

/// The app's own connectivity signal: a hint the status reports and reconnections follow, never a
/// gate on the app's requests.
struct NetworkAvailabilityTests {

    /// A row to write.
    private static let tea = TodoRow(id: 3, title: "Tea", done: false)

    @Test func theStatusFollowsAWriteFromOfflineToOnline() async throws {
        // Given a client whose network the test reports, and a server out of reach
        let network = FakeNetworkAvailability()
        let clock = ManualClock(now: Date(timeIntervalSince1970: 1_791_115_200))
        let server = RecordingFakeServer { _ in .noConnection }
        let client = SupabaseOfflineClient.inMemory(
            config: .init(networkAvailability: network.makeStream),
            server: server.server,
            clock: clock
        )
        let statuses = LiveRecording(client.syncStatus)
        #expect(await eventually { network.streamCount == 1 && statuses.last?.network == .unknown })

        // When the device goes offline and a write is made
        network.report(false)
        #expect(await eventually { statuses.last?.network == .offline })
        let outcome = try await client.from("todos").insert(Self.tea).execute()

        // Then it waits, counted
        #expect(outcome.isQueued)
        #expect(await eventually { statuses.last == syncStatus(network: .offline, pendingMutationCount: 1) })

        // When the device comes back online, with the server reachable
        server.answer { _ in .status(201) }
        network.report(true)

        // Then the write is sent by itself and the status says so
        let synced = syncStatus(network: .online, lastSyncedAt: clock.now)
        #expect(await eventually { statuses.last == synced }, "last status: \(String(describing: statuses.last))")
        #expect(client.syncStatus.value == synced)
        #expect(server.requests(withMethod: "POST").count == 2)
        statuses.stop()
        try await client.close()
    }

    @Test func aSignalSayingOfflineIsAHintAndRequestsAreStillTried() async throws {
        // Given a device reported offline, and a server that answers
        let network = FakeNetworkAvailability()
        let server = RecordingFakeServer(rows: #"[{"id":1,"title":"Milk","done":false}]"#)
        let client = SupabaseOfflineClient.inMemory(config: .init(networkAvailability: network.makeStream), server: server.server)
        #expect(await eventually { network.streamCount == 1 })
        network.report(false)
        #expect(await eventually { client.syncStatus.value.network == .offline })

        // When
        let rows = try await client.from("todos").select(readStrategy: .networkOnly).execute([TodoRow].self)

        // Then the request was sent and answered
        #expect(rows.value == [TodoRow(id: 1, title: "Milk", done: false)])
        #expect(!rows.isFromCache)
        #expect(server.requests.values.count == 1)
        try await client.close()
    }

    @Test func closingTheClientFinishesItsStream() async throws {
        // Given a client reading the app's signal
        let network = FakeNetworkAvailability()
        let client = SupabaseOfflineClient.inMemory(config: .init(networkAvailability: network.makeStream))
        #expect(await eventually { network.streamCount == 1 })
        network.report(false)
        #expect(await eventually { client.syncStatus.value.network == .offline })

        // When
        try await client.close()

        // Then it reads the stream no more
        #expect(await eventually { network.endedStreamCount == 1 }, "the client still reads the app's stream after close")
    }

    @Test func clientsMadeInTurnFromOneConfigEachGetASignal() async throws {
        // Given one configuration, kept for every client the app makes
        let network = FakeNetworkAvailability()
        let config = SupabaseOfflineClient.Config(networkAvailability: network.makeStream)
        let first = SupabaseOfflineClient.inMemory(config: config)
        #expect(await eventually { network.streamCount == 1 })
        network.report(false)
        #expect(await eventually { first.syncStatus.value.network == .offline })

        // When the first is closed and the next made from the same configuration
        try await first.close()
        let second = SupabaseOfflineClient.inMemory(config: config)

        // Then the next has a live signal of its own
        #expect(await eventually { network.streamCount == 2 })
        #expect(network.endedStreamCount == 1)
        network.report(false)
        #expect(await eventually { second.syncStatus.value.network == .offline })
        network.report(true)
        #expect(await eventually { second.syncStatus.value.network == .online })
        try await second.close()
    }

    @Test func aStreamThatEndsLeavesItsLastValueInForce() async throws {
        // Given a client told the device is offline
        let network = FakeNetworkAvailability()
        let client = SupabaseOfflineClient.inMemory(config: .init(networkAvailability: network.makeStream))
        #expect(await eventually { network.streamCount == 1 })
        network.report(false)
        #expect(await eventually { client.syncStatus.value.network == .offline })

        // When the app's stream ends by itself
        network.finish()
        #expect(await eventually { network.endedStreamCount == 1 })
        try await Task.sleep(for: .milliseconds(200))

        // Then its last report stands
        #expect(client.syncStatus.value.network == .offline)
        try await client.close()
    }
}
