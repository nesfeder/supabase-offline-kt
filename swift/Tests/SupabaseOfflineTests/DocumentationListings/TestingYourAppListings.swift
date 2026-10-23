// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline
import SupabaseOfflineTesting
import SwiftUI
import Testing

// The listings of the Testing your app article, which run as tests here.
// scripts/check-swift-doc-listings.py keeps the article's copies equal to the marked sections.

/// The article's tests, as an app writes them.
struct TestingYourAppListings {

    // --8<-- [start:in-memory-test]
    @Test func aTodoAddedOfflineIsSentWhenTheNetworkIsBack() async throws {
        // Given a client whose server accepts every write, with the network turned off
        let offline = SupabaseOfflineClient.inMemory(
            config: .init(autoSync: false),
            server: FakeServer { _ in .json("[]", status: 201) }
        )
        try await offline.disableNetwork()

        // When a to-do is added, then the network comes back and the queue is sent
        let outcome = try await offline.from("todos").insert(Todo(id: UUID(), title: "Buy milk", done: false)).execute()
        try await offline.enableNetwork()
        let report = try await offline.syncPendingMutations()

        // Then the write waited on the device, and the sync sent it
        #expect(outcome.isQueued)
        #expect(report.syncedCount == 1)
        try await offline.close()
    }
    // --8<-- [end:in-memory-test]

    // --8<-- [start:refusal-test]
    @Test func aWriteRefusedWhenTheQueueIsSentIsListedWithTheServersCode() async throws {
        // Given a server that refuses every write, as a row-level security policy would
        let offline = SupabaseOfflineClient.inMemory(
            config: .init(autoSync: false),
            server: FakeServer { _ in
                .json(#"{"code":"42501","message":"new row violates row-level security policy"}"#, status: 403)
            }
        )
        try await offline.disableNetwork()
        try await offline.from("todos").insert(Todo(id: UUID(), title: "Buy milk", done: false)).execute()
        try await offline.enableNetwork()

        // When the queue is sent
        let report = try await offline.syncPendingMutations()

        // Then the write is on the rejected list, with the server's code
        #expect(report.rejected.first?.serverError?.code == "42501")
        #expect(try await offline.rejectedMutations().first().count == 1)
        try await offline.close()
    }
    // --8<-- [end:refusal-test]

    // --8<-- [start:restart-test]
    @Test func aTodoQueuedBeforeTheAppIsKilledIsSentAfterItRelaunches() async throws {
        // Given a client that queues a to-do with the network off, then is closed as the app is killed
        let store = InMemoryStore()
        let server = FakeServer { _ in .json("[]", status: 201) }
        let killed = SupabaseOfflineClient.inMemory(config: .init(autoSync: false), server: server, store: store)
        try await killed.disableNetwork()
        try await killed.from("todos").insert(Todo(id: UUID(), title: "Buy milk", done: false)).execute()
        try await killed.close()

        // When the app relaunches over the same store and sends the queue
        let relaunched = SupabaseOfflineClient.inMemory(config: .init(autoSync: false), server: server, store: store)
        let report = try await relaunched.syncPendingMutations()

        // Then the write queued before the relaunch is the one sent
        #expect(report.syncedCount == 1)
        try await relaunched.close()
    }
    // --8<-- [end:restart-test]

    // --8<-- [start:realtime-test]
    @Test func aChangeTheServerReportsReachesTheRowsOnTheDevice() async throws {
        // Given a client that follows `todos` through a fake Realtime, and one row read into the cache
        let realtime = FakeRealtime()
        let offline = SupabaseOfflineClient.inMemory(
            config: .init(realtimeTables: [RealtimeTable("todos")]),
            server: FakeServer { _ in .json(#"[{"id":"3f1e9c2a-6b4d-4e8f-9a0b-1c2d3e4f5a6b","title":"Buy milk","done":false}]"#) },
            realtime: realtime
        )
        _ = try await offline.from("todos").select().execute([Todo].self)
        for try await status in offline.realtimeStatus where status == .subscribed { break }

        // When the server reports a row inserted
        try realtime.reportInsert(
            table: "todos",
            record: ["id": .string("8c2b7d1e-0f3a-4b5c-8d6e-7f8a9b0c1d2e"), "title": .string("Call mom"), "done": .bool(false)]
        )

        // Then a live read of the device's copy yields both rows, with no request
        var titles: [String] = []
        for try await snapshot in offline.from("todos").selectAsSequence(as: [Todo].self, readStrategy: .cacheOnly) {
            titles = snapshot.result?.map(\.title) ?? []
            if titles.count == 2 { break }
        }
        #expect(titles.sorted() == ["Buy milk", "Call mom"])
        try await offline.close()
    }
    // --8<-- [end:realtime-test]

    @Test func theFactoriesBuildWhatTheClientWouldReport() {
        // --8<-- [start:factories]
        let waiting = syncStatus(network: .offline, pendingMutationCount: 2)
        let refused = rejectedMutation(
            id: 7,
            table: "todos",
            operation: .insert,
            createdAt: .now,
            serverError: serverError(status: 403, code: "42501", message: "new row violates row-level security policy"),
            message: "new row violates row-level security policy",
            rejectedAt: .now
        )
        // --8<-- [end:factories]

        // Then
        #expect(waiting.pendingMutationCount == 2)
        #expect(refused.serverError?.status == 403)
    }
}

// --8<-- [start:photo]
struct PhotoView: View {
    let offline: SupabaseOfflineClient
    let path: String
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFit()
            } else {
                Color.secondary
            }
        }
        .task {
            // The bytes, from the server or from the device's copy. An in-memory client's copy is
            // not a file, so a view that previews read them this way, not from a file URL.
            if let data = try? await offline.storage.from("photos").download(path: path) {
                image = UIImage(data: data)
            }
        }
    }
}
// --8<-- [end:photo]

/// The in-memory client's file cache, as the Testing article describes it.
struct InMemoryFileCacheTests {

    @Test func aCachedCopyIsNoFileButItsBytesAreReadThroughDownload() async throws {
        // Given an in-memory client whose server holds a photo
        let jpeg = Data([0xFF, 0xD8, 0xFF, 0xD9])
        let offline = SupabaseOfflineClient.inMemory(
            config: .init(autoSync: false),
            server: FakeServer { _ in .status(200, headers: ["Content-Type": "image/jpeg"], body: jpeg) }
        )

        // When the photo is kept, and read again offline
        let cached = try await offline.storage.from("photos").ensureCachedFile(path: "milk.jpg")
        try await offline.disableNetwork()
        let bytes = try await offline.storage.from("photos").download(path: "milk.jpg")

        // Then the kept copy has no file on disk, and download answers with its bytes
        #expect(!FileManager.default.fileExists(atPath: cached.path))
        #expect((try? Data(contentsOf: cached.url)) == nil)
        #expect(bytes == jpeg)
        try await offline.close()
    }
}

// --8<-- [start:preview]
#Preview {
    TodoList(offline: .inMemory(server: FakeServer { _ in
        .json(#"[{"id":"7f4c8e2a-1b3d-4e5f-8a9b-0c1d2e3f4a5b","title":"Buy milk","done":false}]"#)
    }))
}
// --8<-- [end:preview]
