// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOfflineTesting
import Testing
@testable import SupabaseOffline

/// Clients built over one `InMemoryStore`: the next finds what the one before it left, as an app
/// relaunched on a device does, and two never hold the store at once.
struct InMemoryStoreTests {

    /// The row the server answers every read with.
    private static let rows = #"[{"id":1,"title":"Buy milk","done":false}]"#

    @Test func aWriteQueuedBeforeCloseIsSentByTheNextClientOverTheStore() async throws {
        // Given a client over a store that queues a write with the network off, then is closed
        let store = InMemoryStore()
        let server = RecordingFakeServer(rows: Self.rows)
        let first = SupabaseOfflineClient.inMemory(config: .init(autoSync: false), server: server.server, store: store)
        try await first.disableNetwork()
        let outcome = try await first.from("todos").insert(TodoRow(id: 2, title: "Tea", done: false)).execute()
        try await first.close()

        // When the next client opens over the store and sends the queue
        let second = SupabaseOfflineClient.inMemory(config: .init(autoSync: false), server: server.server, store: store)
        let queued = try await second.pendingMutationCount()
        let report = try await second.syncPendingMutations()

        // Then it found the write queued before the close, and sent it
        #expect(outcome.isQueued)
        #expect(queued == 1)
        #expect(report.syncedCount == 1)
        #expect(server.requests(withMethod: "POST").count == 1)
        try await second.close()
    }

    @Test func theRejectedListAndTheCachedRowsSurviveAClose() async throws {
        // Given a client whose server refuses the write it queued, with the table's row cached
        let store = InMemoryStore()
        let server = RecordingFakeServer { request in
            request.method == "GET"
                ? .json(Self.rows)
                : .json(#"{"code":"42501","message":"new row violates row-level security policy"}"#, status: 403)
        }
        let first = SupabaseOfflineClient.inMemory(config: .init(autoSync: false), server: server.server, store: store)
        _ = try await first.from("todos").select().execute([TodoRow].self)
        try await first.disableNetwork()
        try await first.from("todos").insert(TodoRow(id: 2, title: "Tea", done: false)).execute()
        try await first.enableNetwork()
        let report = try await first.syncPendingMutations()
        try await first.close()

        // When the next client opens over the store, with the server gone
        server.goOffline()
        let second = SupabaseOfflineClient.inMemory(config: .init(autoSync: false), server: server.server, store: store)
        let rejected = try await second.rejectedMutations().first()
        let cached = try await second.from("todos").select(readStrategy: .cacheOnly).execute([TodoRow].self)

        // Then the refused write and the cached row are where the first client left them
        #expect(report.rejected.count == 1)
        #expect(rejected.map(\.serverError?.code) == ["42501"])
        #expect(cached.value == [TodoRow(id: 1, title: "Buy milk", done: false)])
        #expect(cached.isFromCache)
        try await second.close()
    }

    @Test func aClientOverAStoreAnotherHoldsFailsUntilThatOneCloses() async throws {
        // Given a client open over the store
        let store = InMemoryStore()
        let first = SupabaseOfflineClient.inMemory(store: store)
        #expect(try await first.pendingMutationCount() == 0)

        // When another is built over it, then one more once the first is closed
        let second = SupabaseOfflineClient.inMemory(store: store)
        let failure = await #expect(throws: OfflineError.self) { _ = try await second.pendingMutationCount() }
        try await first.close()
        let third = SupabaseOfflineClient.inMemory(store: store)

        // Then the second could not open, saying why, and the close freed the store for the third
        #expect(failure?.code == .configuration)
        #expect(failure?.message.contains("already open") == true)
        #expect(try await third.pendingMutationCount() == 0)
        try await third.close()
    }

    @Test func clientsWithoutAStoreShareNothing() async throws {
        // Given a client that queues a write with the network off, then is closed
        let server = RecordingFakeServer(rows: Self.rows)
        let first = SupabaseOfflineClient.inMemory(config: .init(autoSync: false), server: server.server)
        try await first.disableNetwork()
        try await first.from("todos").insert(TodoRow(id: 2, title: "Tea", done: false)).execute()
        try await first.close()

        // When another is built without a store
        let second = SupabaseOfflineClient.inMemory(config: .init(autoSync: false), server: server.server)

        // Then it starts empty
        #expect(try await second.pendingMutationCount() == 0)
        try await second.close()
    }
}
