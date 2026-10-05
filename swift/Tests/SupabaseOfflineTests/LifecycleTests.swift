// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOfflineTesting
import Testing
import os
@testable import SupabaseOffline

/// Closing, clearing local data when the user changes, and rows the server reports.
struct LifecycleTests {

    /// The two rows the server holds.
    private static let serverRows = #"[{"id":1,"title":"Milk","done":false},{"id":2,"title":"Eggs","done":true}]"#

    @Test func closingFinishesEveryLiveSequenceAndLaterCallsThrowClientClosed() async throws {
        // Given every kind of live sequence being iterated
        let server = RecordingFakeServer(rows: Self.serverRows)
        let client = SupabaseOfflineClient.inMemory(server: server.server)
        let pending = LiveRecording(client.pendingMutations())
        let counts = LiveRecording(client.pendingMutationCountSequence())
        let rejected = LiveRecording(client.rejectedMutations())
        let statuses = LiveRecording(client.syncStatus)
        let snapshots = LiveRecording(client.from("todos").selectAsSequence(as: [TodoRow].self))
        #expect(
            await eventually {
                pending.last != nil && counts.last != nil && rejected.last != nil && statuses.last != nil
                    && snapshots.last?.result != nil
            }
        )

        // When
        try await client.close()

        // Then every sequence ends normally
        #expect(await pending.endingError() == nil)
        #expect(await counts.endingError() == nil)
        #expect(await rejected.endingError() == nil)
        #expect(await statuses.endingError() == nil)
        #expect(await snapshots.endingError() == nil)

        // And every later call throws the closed-client error, a new iteration included
        let calls: [(String, @Sendable () async throws -> Void)] = [
            ("pendingMutations().first()", { _ = try await client.pendingMutations().first() }),
            ("pendingMutationCount()", { _ = try await client.pendingMutationCount() }),
            ("rejectedMutations().first()", { _ = try await client.rejectedMutations().first() }),
            ("discardPendingMutation", { try await client.discardPendingMutation(id: 1) }),
            ("dismissRejectedMutation", { try await client.dismissRejectedMutation(id: 1) }),
            ("retryRejectedMutation", { try await client.retryRejectedMutation(id: 1) }),
            ("syncPendingMutations()", { _ = try await client.syncPendingMutations() }),
            ("disableNetwork()", { try await client.disableNetwork() }),
            ("enableNetwork()", { try await client.enableNetwork() }),
            ("clearLocalData()", { try await client.clearLocalData() }),
            ("applyServerRecord", { try await client.applyServerRecord(table: "todos", record: ["id": 1]) }),
            ("applyServerDelete", { try await client.applyServerDelete(table: "todos", oldRecord: ["id": 1]) }),
            ("iterating pendingMutations()", { for try await _ in client.pendingMutations() {} }),
            ("iterating syncStatus", { for try await _ in client.syncStatus {} }),
        ]
        for (name, call) in calls {
            let error = await #expect(throws: OfflineError.self, "\(name)") { try await call() }
            #expect(error?.code == .clientClosed, "\(name)")
        }

        // And the status still answers, with its last value
        #expect(client.syncStatus.value.pendingMutationCount == 0)
        #expect(!client.syncStatus.value.isSyncing)
    }

    @Test func theStatusAfterCloseIsTheLastOneNotAFreshOne() async throws {
        // Given a status no fresh client has
        let client = SupabaseOfflineClient.inMemory()
        try await client.disableNetwork()
        #expect(client.syncStatus.value.network == .disabled)

        // When
        try await client.close()

        // Then
        #expect(client.syncStatus.value.network == .disabled, "after close: \(client.syncStatus.value)")
    }

    @Test func clearingLocalDataAtSignOutSendsNothingOfThatUserUnderTheNextOnesToken() async throws {
        // Given user A's cached rows, queued write and rejected write, on a client that syncs by itself
        let session = OSAllocatedUnfairLock<String?>(initialState: "token-a")
        let network = FakeNetworkAvailability()
        let server = RecordingFakeServer(rows: Self.serverRows)
        let client = SupabaseOfflineClient.inMemory(
            config: .init(accessToken: { session.withLock { $0 } }, networkAvailability: network.makeStream),
            server: server.server
        )
        #expect(await eventually { network.streamCount == 1 })
        network.report(true)
        _ = try await client.from("todos").select().execute([TodoRow].self)
        server.goOffline()
        _ = try await client.from("todos").insert(TodoRow(id: 3, title: "Tea", done: false)).execute()
        server.answer { _ in .duplicateKey }
        _ = try await client.syncPendingMutations()
        server.goOffline()
        _ = try await client.from("todos").insert(TodoRow(id: 4, title: "Jam", done: false)).execute()
        #expect(try await client.pendingMutationCount() == 1)
        #expect(try await client.rejectedMutations().first().count == 1)

        // When A signs out, the app clears local data, and only then does B sign in
        session.withLock { $0 = nil }
        try await client.clearLocalData()
        let sentBeforeB = server.requests.values.count
        session.withLock { $0 = "token-b" }
        server.answer { request in request.method == "GET" ? .json(#"[{"id":7,"title":"Bread","done":false}]"#) : .status(201) }
        network.report(false)
        network.report(true)
        let report = try await client.syncPendingMutations()
        try await Task.sleep(for: .milliseconds(300))

        // Then nothing of A's is left, and nothing of A's goes out under B's token
        #expect(report.syncedCount == 0)
        #expect(report.remainingCount == 0)
        #expect(try await client.pendingMutationCount() == 0)
        #expect(try await client.rejectedMutations().first().isEmpty)
        let cached = await #expect(throws: OfflineError.self) {
            try await client.from("todos").select(readStrategy: .cacheOnly).execute([TodoRow].self)
        }
        #expect(cached?.code == .cacheMiss)
        #expect(
            await eventually {
                let status = client.syncStatus.value
                return status.pendingMutationCount == 0 && status.rejectedMutationCount == 0 && status.lastSyncError == nil
            }
        )
        #expect(server.requests.values.dropFirst(sentBeforeB).allSatisfy { $0.method == "GET" })

        // And the client is still usable, as user B
        let rows = try await client.from("todos").select().execute([TodoRow].self)
        #expect(rows.value == [TodoRow(id: 7, title: "Bread", done: false)])
        #expect(server.requests.values.last?.header("Authorization") == "Bearer token-b")
        try await client.close()
    }

    @Test func rowsTheServerReportsReachALiveRead() async throws {
        // Given a live read showing the server's rows
        let server = RecordingFakeServer(rows: Self.serverRows)
        let client = SupabaseOfflineClient.inMemory(server: server.server)
        let snapshots = LiveRecording(client.from("todos").selectAsSequence(as: [TodoRow].self).order("id"))
        let milk = TodoRow(id: 1, title: "Milk", done: false)
        let eggs = TodoRow(id: 2, title: "Eggs", done: true)
        #expect(await eventually { snapshots.last?.result == [milk, eggs] })

        // When the server reports a new row, a changed row and a deleted row
        try await client.applyServerRecord(table: "todos", record: TodoRow(id: 3, title: "Tea", done: false))
        try await client.applyServerRecord(table: "todos", record: ["id": 1, "title": "Oat milk", "done": false])
        try await client.applyServerDelete(table: "todos", oldRecord: ["id": 2])

        // Then the live read shows them
        let expected = [TodoRow(id: 1, title: "Oat milk", done: false), TodoRow(id: 3, title: "Tea", done: false)]
        #expect(await eventually { snapshots.last?.result == expected }, "last: \(String(describing: snapshots.last))")
        #expect(snapshots.last?.hasPendingMutations == false)
        snapshots.stop()
        try await client.close()
    }

    @Test func aServerRecordThatIsNotAnObjectIsRefused() async throws {
        // Given
        let client = SupabaseOfflineClient.inMemory(server: FakeServer())

        // When
        let record = await #expect(throws: OfflineError.self) {
            try await client.applyServerRecord(table: "todos", record: OfflineJSON.array([1, 2]))
        }
        let delete = await #expect(throws: OfflineError.self) {
            try await client.applyServerDelete(table: "todos", oldRecord: "id")
        }

        // Then
        #expect(record?.code == .invalidRequest)
        #expect(delete?.code == .invalidRequest)
        try await client.close()
    }
}
