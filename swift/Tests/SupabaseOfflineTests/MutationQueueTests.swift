// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOfflineCore
import SupabaseOfflineTesting
import Testing
@testable import SupabaseOffline

/// The mutation queue, the rejected list and sync, on the in-memory client against a fake server.
struct MutationQueueTests {

    /// A row to write.
    private static let tea = TodoRow(id: 3, title: "Tea", done: false)

    @Test func aQueuedWriteIsListedCountedAndDiscarded() async throws {
        // Given a write queued while the server is out of reach
        let server = RecordingFakeServer { _ in .noConnection }
        let client = SupabaseOfflineClient.inMemory(config: .init(autoSync: false), server: server.server)
        let lists = LiveRecording(client.pendingMutations())
        let counts = LiveRecording(client.pendingMutationCountSequence(table: "todos"))
        let outcome = try await client.from("todos").insert(Self.tea).execute()
        guard case .queued(let pending) = outcome else {
            Issue.record("expected a queued write, got \(outcome)")
            return
        }

        // When
        let listed = try await client.pendingMutations().first()
        let listedForAnotherTable = try await client.pendingMutations(table: "notes").first()
        let count = try await client.pendingMutationCount()

        // Then it is listed and counted, as the live sequences show
        #expect(listed == [pending])
        #expect(listedForAnotherTable.isEmpty)
        #expect(count == 1)
        #expect(await eventually { lists.last == [pending] && counts.last == 1 })

        // When it is discarded, and an id that is not queued is discarded
        try await client.discardPendingMutation(id: pending.id)
        try await client.discardPendingMutation(id: pending.id + 100)

        // Then it is gone and was never sent
        #expect(try await client.pendingMutations().first().isEmpty)
        #expect(try await client.pendingMutationCount(table: "todos") == 0)
        #expect(await eventually { lists.last == [] && counts.last == 0 })
        #expect(server.requests(withMethod: "POST").count == 1)
        lists.stop()
        counts.stop()
        try await client.close()
    }

    @Test func aRefusedWriteIsReportedListedRetriedAndDismissed() async throws {
        // Given a queued write the server then refuses
        let server = RecordingFakeServer { _ in .noConnection }
        let client = SupabaseOfflineClient.inMemory(config: .init(autoSync: false), server: server.server)
        let rejectedLists = LiveRecording(client.rejectedMutations())
        _ = try await client.from("todos").insert(Self.tea).execute()
        server.answer { _ in .duplicateKey }

        // When it is synced
        let report = try await client.syncPendingMutations()

        // Then the refusal is reported, not thrown, and listed with the server's account of it
        #expect(report.syncedCount == 0)
        #expect(report.remainingCount == 0)
        #expect(report.alreadyOnServerCount == 0)
        let rejected = try #require(report.rejected.first)
        #expect(report.rejected.count == 1)
        #expect(rejected.table == "todos")
        #expect(rejected.operation == .insert)
        #expect(rejected.body == [["id": 3, "title": "Tea", "done": false]])
        #expect(rejected.serverError?.status == 409)
        #expect(rejected.serverError?.code == "23505")
        #expect(rejected.serverError?.details == "Key (id)=(3) already exists.")
        #expect(rejected.isRetryable)
        #expect(try await client.rejectedMutations().first() == [rejected])
        #expect(await eventually { rejectedLists.last == [rejected] && client.syncStatus.value.rejectedMutationCount == 1 })
        #expect(try await client.pendingMutationCount() == 0)

        // When the cause is fixed and it is retried
        server.answer { _ in .status(201) }
        try await client.retryRejectedMutation(id: rejected.id)

        // Then it leaves the list and is sent again, by itself
        #expect(try await client.rejectedMutations().first().isEmpty)
        #expect(await eventually { server.requests(withMethod: "POST").count == 3 })
        #expect(await eventually { client.syncStatus.value.pendingMutationCount == 0 })

        // When another refused write is dismissed
        server.answer { _ in .noConnection }
        _ = try await client.from("todos").insert(TodoRow(id: 4, title: "Jam", done: false)).execute()
        server.answer { _ in .duplicateKey }
        let second = try #require(try await client.syncPendingMutations().rejected.first)
        try await client.dismissRejectedMutation(id: second.id)
        try await client.dismissRejectedMutation(id: second.id + 100)

        // Then the list is empty and nothing is queued
        #expect(try await client.rejectedMutations().first().isEmpty)
        #expect(await eventually { rejectedLists.last == [] && client.syncStatus.value.rejectedMutationCount == 0 })
        #expect(try await client.pendingMutationCount() == 0)
        rejectedLists.stop()
        try await client.close()
    }

    @Test func aServersTextIsKeptVerbatimOnTheRejectedList() async throws {
        // Given a refusal whose text holds words the package clears from its own messages
        let server = RecordingFakeServer { _ in .noConnection }
        let client = SupabaseOfflineClient.inMemory(config: .init(autoSync: false), server: server.server)
        _ = try await client.from("todos").insert(Self.tea).execute()
        server.answer { _ in
            .json(
                #"{"code":"23503","message":"insert on bridge refused by kotlin trigger","details":"see konan bridge","hint":"Kotlin hint"}"#,
                status: 409
            )
        }

        // When
        let reported = try #require(try await client.syncPendingMutations().rejected.first)
        let listed = try #require(try await client.rejectedMutations().first().first)

        // Then the server's own words reach the app as sent
        for rejected in [reported, listed] {
            #expect(rejected.serverError?.code == "23503")
            #expect(rejected.serverError?.message == "insert on bridge refused by kotlin trigger")
            #expect(rejected.serverError?.details == "see konan bridge")
            #expect(rejected.serverError?.hint == "Kotlin hint")
        }
        try await client.close()
    }

    @Test func aRefusedWriteWhoseBodyWasNotKeptCannotBeRetried() async throws {
        // Given a refused write whose body is too long to keep on the rejected list
        let server = RecordingFakeServer { _ in .noConnection }
        let client = SupabaseOfflineClient.inMemory(config: .init(autoSync: false), server: server.server)
        _ = try await client.from("todos").insert(TodoRow(id: 5, title: String(repeating: "x", count: 17_000), done: false)).execute()
        server.answer { _ in .duplicateKey }
        let rejected = try #require(try await client.syncPendingMutations().rejected.first)

        // When
        let error = await #expect(throws: OfflineError.self) {
            try await client.retryRejectedMutation(id: rejected.id)
        }

        // Then
        #expect(!rejected.isRetryable)
        #expect(rejected.body == nil)
        #expect(error?.code == .invalidRequest)
        #expect(try await client.rejectedMutations().first() == [rejected])
        try await client.close()
    }

    @Test func aSyncReportsWhatItSentAndWhatRemains() async throws {
        // Given a queued update
        let server = RecordingFakeServer { _ in .noConnection }
        let client = SupabaseOfflineClient.inMemory(config: .init(autoSync: false), server: server.server)
        _ = try await client.from("todos").update(["done": true]).eq("id", value: 3).execute()

        // When a sync runs offline, then online
        let offline = try await client.syncPendingMutations()
        server.answer { _ in .status(204) }
        let online = try await client.syncPendingMutations(table: "todos")

        // Then the first returns at once and keeps it, the second sends it
        #expect(offline.syncedCount == 0)
        #expect(offline.remainingCount == 1)
        #expect(offline.rejected.isEmpty)
        #expect(online.syncedCount == 1)
        #expect(online.remainingCount == 0)
        #expect(online.rejected.isEmpty)
        #expect(server.requests.values.last?.method == "PATCH")
        #expect(try server.requests.values.last?.jsonBody == ["done": true])
        #expect(try await client.pendingMutationCount() == 0)
        try await client.close()
    }
}
