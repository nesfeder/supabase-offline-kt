// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOfflineTesting
import Testing
@testable import SupabaseOffline

/// Version columns, the conflict resolver and the conflicted list, on the in-memory client against
/// a fake server that moves a row between enqueue and replay.
struct ConflictTests {

    /// The row's version as the device reads it.
    private static let base = "2026-10-18T13:00:27.486123+00:00"

    /// The row's version once another device changed it.
    private static let moved = "2026-10-18T13:04:00.000001+00:00"

    /// The row's version once the resend landed.
    private static let resent = "2026-10-18T13:05:00.000002+00:00"

    @Test func aRowMovedOnTheServerReachesTheResolverAndIsResent() async throws {
        // Given the row as read, as another device leaves it, and as the resend leaves it
        let server = Self.movingServer()
        let conflicts = RecordingList<MutationConflict>()
        let client = Self.client(server: server) { conflict in
            conflicts.append(conflict)
            return .keepLocal
        }

        // When the title is changed offline, then the queue replays
        let report = try await Self.queueTitleChangeAndSync(on: client)

        // Then the resolver saw both titles clash, once, and keep-local resent at the new version
        let conflict = try #require(conflicts.values.first)
        #expect(conflicts.values.count == 1)
        #expect(conflict.conflictingColumns == ["title"])
        #expect(conflict.changedOnServer == ["title"])
        #expect(conflict.rowKey == ["id": "1"])
        #expect(conflict.operation == .update)
        #expect(conflict.base["updated_at"] == .string(Self.base))
        #expect(conflict.local == ["title": "Mine"])
        #expect(conflict.server?["title"] == "Theirs")
        let patches = server.requests(withMethod: "PATCH")
        #expect(patches.count == 2)
        #expect(patches.first?.parameter("updated_at") == "eq.\(Self.base)")
        #expect(patches.first?.header("Prefer")?.contains("return=representation") == true)
        #expect(patches.last?.parameter("updated_at") == "eq.\(Self.moved)")
        #expect(report.syncedCount == 1)
        #expect(report.conflictsResolvedCount == 1)
        #expect(report.conflictsKeptServerCount == 0)
        #expect(report.conflicted.isEmpty)
        try await client.close()
    }

    @Test func aResolverThatThrowsParksTheWriteOnTheConflictedList() async throws {
        // Given a resolver that throws
        let server = Self.movingServer()
        let client = Self.client(server: server) { _ in throw URLError(.badServerResponse) }
        let lists = LiveRecording(client.conflictedMutations())

        // When
        let report = try await Self.queueTitleChangeAndSync(on: client)

        // Then the write is held, listed with what the replay found, and counted
        let parked = try #require(report.conflicted.first)
        #expect(report.syncedCount == 0)
        #expect(parked.table == "todos")
        #expect(parked.operation == .update)
        #expect(parked.rowKeys == [["id": "1"]])
        #expect(parked.body == ["title": "Mine"])
        #expect(parked.serverVersion == Self.moved)
        #expect(parked.conflictingColumns == ["title"])
        #expect(try await client.conflictedMutations().first() == [parked])
        #expect(await eventually { lists.last == [parked] && client.syncStatus.value.conflictedMutationCount == 1 })
        #expect(server.requests(withMethod: "PATCH").count == 1)
        lists.stop()
        try await client.close()
    }

    @Test func aParkedWriteIsResolvedAgainstTheRecordedVersionOrDismissed() async throws {
        // Given two writes a resolver parked
        let server = Self.movingServer()
        let client = Self.client(server: server) { _ in .hold }
        _ = try await Self.queueTitleChangeAndSync(on: client)
        _ = try await Self.queueTitleChangeAndSync(on: client)
        let parked = try await client.conflictedMutations().first()
        #expect(parked.count == 2)
        let kept = try #require(parked.first)
        let dropped = try #require(parked.last)

        // When hold is refused, one is kept and the other dismissed
        let refusal = await #expect(throws: OfflineError.self) {
            try await client.resolveConflictedMutation(id: kept.id, resolution: .hold)
        }
        try await client.disableNetwork()
        try await client.resolveConflictedMutation(id: kept.id, resolution: .keepLocal)
        try await client.dismissConflictedMutation(id: dropped.id)

        // Then the kept write waits again, preconditioned on the version the entry recorded
        #expect(refusal?.code == .invalidRequest)
        #expect(try await client.conflictedMutations().first().isEmpty)
        let pending = try await client.pendingMutations().first()
        #expect(pending.map(\.baseVersion) == [Self.moved])
        #expect(pending.first?.body == ["title": "Mine"])
        try await client.close()
    }

    @Test func aBlankOrKeyVersionColumnFailsTheOpen() async {
        for columns in [["todos": " "], ["todos": "id"]] {
            // Given
            let client = SupabaseOfflineClient.inMemory(config: .init(versionColumns: columns))

            // When
            let error = await #expect(throws: OfflineError.self) { try await client.pendingMutationCount() }

            // Then
            #expect(error?.code == .configuration)
            await #expect(throws: Never.self) { try await client.close() }
        }
    }

    /// A server whose row moves between the device's read and its replay.
    private static func movingServer() -> RecordingFakeServer {
        RecordingFakeServer { request in
            if request.method == "PATCH" {
                return request.parameter("updated_at") == "eq.\(base)"
                    ? .json("[]")
                    : .json(#"[{"id":1,"title":"Mine","updated_at":"\#(resent)"}]"#)
            }
            if request.parameter("limit") == "1" {
                return .json(#"[{"id":1,"title":"Theirs","updated_at":"\#(moved)"}]"#)
            }
            return .json(#"[{"id":1,"title":"Milk","updated_at":"\#(base)"}]"#)
        }
    }

    /// An in-memory client of `server` with `todos` guarded by `updated_at`, replaying only when asked.
    private static func client(
        server: RecordingFakeServer,
        resolver: @escaping @Sendable (MutationConflict) async throws -> ConflictResolution
    ) -> SupabaseOfflineClient {
        SupabaseOfflineClient.inMemory(
            config: .init(autoSync: false, versionColumns: ["todos": "updated_at"], conflictResolver: resolver),
            server: server.server
        )
    }

    /// Reads the row, changes its title offline, and syncs.
    private static func queueTitleChangeAndSync(on client: SupabaseOfflineClient) async throws -> SyncReport {
        let _: [OfflineJSON] = try await client.from("todos").select().execute().value
        try await client.disableNetwork()
        let outcome = try await client.from("todos").update(["title": "Mine"]).eq("id", value: 1).execute()
        guard case .queued(let pending) = outcome else {
            Issue.record("expected a queued write, got \(outcome)")
            return try await client.syncPendingMutations()
        }
        #expect(pending.baseVersion == base)
        try await client.enableNetwork()
        return try await client.syncPendingMutations()
    }
}

extension FakeServer.Request {

    /// The first value of the query parameter `name`, decoded.
    func parameter(_ name: String) -> String? {
        queryParameters.first { $0.first == name }?.last
    }
}
