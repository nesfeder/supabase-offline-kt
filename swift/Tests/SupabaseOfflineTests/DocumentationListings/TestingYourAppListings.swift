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

// --8<-- [start:preview]
#Preview {
    TodoList(offline: .inMemory(server: FakeServer { _ in
        .json(#"[{"id":"7f4c8e2a-1b3d-4e5f-8a9b-0c1d2e3f4a5b","title":"Buy milk","done":false}]"#)
    }))
}
// --8<-- [end:preview]
