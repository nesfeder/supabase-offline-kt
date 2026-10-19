// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline
import SupabaseOfflineTesting
import Testing

@testable import OfflineTasks

/// Conflicting changes on the in-memory client: the demo server's version precondition, Try it's
/// Make a conflict, and Keep mine and Keep theirs.
@Suite("Conflicting changes", .serialized)
struct ConflictTests {

    @Test("The demo server changes a row only while it is at the version a write names, and returns what it changed")
    func demoServerPrecondition() async throws {
        // Given: a task on the server, then changed there by another writer
        let test = TestApp()
        try await test.start()
        try await test.addTask("Milk")
        let task = try await test.task(titled: "Milk")
        let seen = try #require(await serverRow(task.id, test.server)["updated_at"])
        await test.server.changeElsewhere(task: task.id, ["title": .string("Buy oat milk")])
        let moved = try #require(await serverRow(task.id, test.server)["updated_at"])
        #expect(moved != seen)

        // When: an update preconditioned on the version seen before the change
        let stale = try await test.repository.client.from(Tables.tasks)
            .update(["title": .string("Buy milk")], mutationStrategy: .onlineOnly)
            .eq("id", value: task.id)
            .eq("updated_at", value: try #require(seen.stringValue))
            .select()
            .execute([TaskRow].self)

        // Then: no row back, and the server's row as the other writer left it
        #expect(stale.result?.isEmpty == true)
        #expect(await serverRow(task.id, test.server)["title"] == .string("Buy oat milk"))

        // When: preconditioned on the version the server has now
        let current = try await test.repository.client.from(Tables.tasks)
            .update(["title": .string("Buy milk")], mutationStrategy: .onlineOnly)
            .eq("id", value: task.id)
            .eq("updated_at", value: try #require(moved.stringValue))
            .select()
            .execute([TaskRow].self)

        // Then: changed, sent back, and at a new version
        let row = try #require(current.result?.first)
        #expect(row.title == "Buy milk")
        #expect(await serverRow(task.id, test.server)["updated_at"] != moved)
        try await test.stop()
    }

    @Test("Make a conflict holds the edit under Conflicting changes, and Keep mine sends it over the server's title")
    func makeConflictThenKeepMine() async throws {
        // Given: a task the server has, listed on the device
        let test = TestApp()
        try await test.start()
        try await test.addTask("Milk")
        let task = try await test.task(titled: "Milk")
        try await eventually("the queue is empty") { test.app.syncStatus.pendingMutationCount == 0 }

        // When: Try it → Make a conflict
        test.app.sync.run(.makeConflict, newestTaskID: task.id)

        // Then: held, with both titles, and counted on the badge
        try await eventually("the conflict is listed") { test.app.sync.conflicted.count == 1 }
        let conflict = try #require(test.app.sync.conflicted.first)
        #expect(conflict.conflictingColumns == ["title"])
        #expect(conflict.taskTitle == "Milk (this device)")
        #expect(Wording.conflictingColumns(conflict) == ["title: “Milk (elsewhere)” on the server, “Milk (this device)” on this device"])
        #expect(conflict.canKeepMine)
        try await eventually("the status counts it") { test.app.syncStatus.conflictedMutationCount == 1 }
        #expect(test.app.syncBadge == 1)
        #expect(await serverRow(task.id, test.server)["title"] == .string("Milk (elsewhere)"))
        try await eventually("Try it says so") { test.app.sync.tryItResults[.makeConflict]?.contains("Conflicting changes") == true }

        // When: Keep mine
        test.app.sync.keepMine(conflict)

        // Then: this device's title reaches the server, and nothing is left to settle
        try await eventually("the server has this device's title") {
            await serverRow(task.id, test.server)["title"] == .string("Milk (this device)")
        }
        try await eventually("the list is empty") { test.app.sync.conflicted.isEmpty }
        try await eventually("the status is clear") {
            test.app.syncStatus.conflictedMutationCount == 0 && test.app.syncStatus.pendingMutationCount == 0
        }
        try await test.stop()
    }

    @Test("Keep theirs drops the held edit, and the list shows the server's title")
    func keepTheirs() async throws {
        // Given: a conflict made through Try it
        let test = TestApp()
        try await test.start()
        try await test.addTask("Milk")
        let task = try await test.task(titled: "Milk")
        try await eventually("the queue is empty") { test.app.syncStatus.pendingMutationCount == 0 }
        test.app.sync.run(.makeConflict, newestTaskID: task.id)
        try await eventually("the conflict is listed") { test.app.sync.conflicted.count == 1 }

        // When
        test.app.sync.keepTheirs(try #require(test.app.sync.conflicted.first))

        // Then
        try await eventually("the list is empty") { test.app.sync.conflicted.isEmpty }
        _ = try await test.task(titled: "Milk (elsewhere)")
        #expect(await serverRow(task.id, test.server)["title"] == .string("Milk (elsewhere)"))
        #expect(test.app.syncStatus.pendingMutationCount == 0)
        try await test.stop()
    }

    @Test("A sync report spells out its conflict counts")
    func reportWording() {
        let report = syncReport(syncedCount: 2, conflictsResolvedCount: 1, conflictsKeptServerCount: 1)
        #expect(Wording.syncReport(report).hasSuffix("conflicts settled 1 · kept the server’s 1 · held for you 0"))
    }

    /// The server's row of task `id`.
    private func serverRow(_ id: String, _ server: DemoServer) async -> [String: OfflineJSON] {
        await server.rows(of: Tables.tasks).first { $0["id"] == .string(id) } ?? [:]
    }
}

extension OfflineJSON {

    /// The text of a string value; `nil` for any other kind.
    fileprivate var stringValue: String? {
        if case .string(let text) = self { return text }
        return nil
    }
}
