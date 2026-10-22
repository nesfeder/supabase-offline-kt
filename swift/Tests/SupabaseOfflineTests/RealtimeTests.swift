// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOfflineTesting
import Testing
@testable import SupabaseOffline

/// The Realtime tables a configuration names, the channel's status and switch, and the testing
/// product's fake channel, on the in-memory client.
struct RealtimeTests {

    /// The row the server answers the first read with.
    private static let rows = #"[{"id":1,"title":"Buy milk","done":false}]"#

    /// The row the changes below leave behind.
    private static let greenTea = TodoRow(id: 2, title: "Green tea", done: true)

    @Test func theNamedTablesAreFollowedAndReportedChangesReachTheCachedRows() async throws {
        // Given a client following `todos` through the fake, with the table's row cached
        let realtime = FakeRealtime()
        let server = RecordingFakeServer(rows: Self.rows)
        let client = SupabaseOfflineClient.inMemory(
            config: .init(realtimeTables: [RealtimeTable("todos")]),
            server: server.server,
            realtime: realtime
        )
        _ = try await client.from("todos").select().execute([TodoRow].self)
        #expect(await eventually { realtime.subscribedTables == ["todos"] })
        #expect(await eventually { client.realtimeStatus.value == .subscribed })

        // When the server reports an insert, an update and a delete
        try realtime.reportInsert(table: "todos", record: ["id": .int(2), "title": .string("Tea"), "done": .bool(false)])
        try realtime.reportUpdate(table: "todos", record: ["id": .int(2), "title": .string("Green tea"), "done": .bool(true)])
        try realtime.reportDelete(table: "todos", oldRecord: ["id": .int(1)])

        // Then the cached rows carry the changes, with no request for them
        let kept = try await cachedRows(of: client, until: { $0 == [Self.greenTea] })
        #expect(kept == [Self.greenTea])
        #expect(server.requests(withMethod: "GET").count == 1)
        #expect(realtime.droppedReportCount == 0)
        try await client.close()
        #expect(client.realtimeStatus.value == .off)
    }

    @Test func theSwitchLeavesTheChannelAndJoinsItAgain() async throws {
        // Given a client following two tables, one in another schema
        let realtime = FakeRealtime()
        let client = SupabaseOfflineClient.inMemory(
            config: .init(realtimeTables: [RealtimeTable("todos"), RealtimeTable("notes", schema: "private")]),
            realtime: realtime
        )
        #expect(await eventually { realtime.subscribedTables == ["todos", "private.notes"] })

        // When the subscription is turned off
        try await client.disableRealtime()
        try await client.disableRealtime()

        // Then the status reads off at once, and the channel is left
        #expect(client.realtimeStatus.value == .off)
        #expect(await eventually { realtime.subscribedTables.isEmpty })

        // When it is turned back on
        try await client.enableRealtime()

        // Then the channel is joined again
        #expect(await eventually { realtime.subscribedTables == ["todos", "private.notes"] })
        #expect(await eventually { client.realtimeStatus.value == .subscribed })
        try await client.close()
    }

    @Test func aFailedOrDroppedChannelIsReportedStalledAndJoinedAgain() async throws {
        // Given a client following `todos`, with its status recorded
        let realtime = FakeRealtime()
        let client = SupabaseOfflineClient.inMemory(config: .init(realtimeTables: [RealtimeTable("todos")]), realtime: realtime)
        let statuses = LiveRecording(client.realtimeStatus)
        #expect(await eventually { statuses.last == .subscribed })

        // When the server refuses the channel
        realtime.failChannel(message: "not in the publication")

        // Then the status says why, and the channel is joined again after a wait
        #expect(await eventually { statuses.values.values.contains { $0.stallMessage?.contains("not in the publication") == true } })
        #expect(statuses.values.values.contains { $0.stallCode == .server })
        #expect(await eventually(within: .seconds(10)) { statuses.last == .subscribed && statuses.values.values.count(where: { $0 == .subscribed }) == 2 })

        // When the socket is lost
        realtime.dropChannel()

        // Then the channel is joined once more
        #expect(await eventually(within: .seconds(10)) { statuses.values.values.count(where: { $0 == .subscribed }) == 3 })
        try await client.close()
        #expect(await statuses.endingError() == nil)
        #expect(statuses.last == .off)
    }

    @Test func aFilterTheServerCannotEvaluateFailsTheOpen() async throws {
        // Given a table filtered with an operator Realtime does not evaluate
        let filter = RealtimeFilter(column: "title", operator: .like, value: "%a%")
        let client = SupabaseOfflineClient.inMemory(config: .init(realtimeTables: [RealtimeTable("todos", filter: filter)]))

        // When the client is used
        let failure = await #expect(throws: OfflineError.self) { try await client.enableRealtime() }

        // Then every call fails with the configuration, naming the operator
        #expect(failure?.code == .configuration)
        #expect(failure?.message.contains("like") == true)
        #expect(client.realtimeStatus.value == .off)
    }

    @Test func anEmptyInListFailsTheOpen() async throws {
        // Given a table filtered with an `in` of no values, which the server would refuse the join for
        let filter = RealtimeFilter(column: "id", in: [Int]())
        let client = SupabaseOfflineClient.inMemory(config: .init(realtimeTables: [RealtimeTable("todos", filter: filter)]))

        // When the client is used
        let failure = await #expect(throws: OfflineError.self) { try await client.enableRealtime() }

        // Then every call fails with the configuration, saying what `in` needs
        #expect(failure?.code == .configuration)
        #expect(failure?.message.contains("at least one") == true)
        #expect(client.realtimeStatus.value == .off)
    }

    @Test func withoutNamedTablesTheStatusIsOffAndTheSwitchDoesNothing() async throws {
        // Given a client naming no table
        let realtime = FakeRealtime()
        let client = SupabaseOfflineClient.inMemory(realtime: realtime)

        // When the switch is used either way
        try await client.disableRealtime()
        try await client.enableRealtime()

        // Then nothing is followed and the status stays off
        try await Task.sleep(for: .milliseconds(100))
        #expect(realtime.subscribedTables.isEmpty)
        #expect(client.realtimeStatus.value == .off)
        try await client.close()
    }

    @Test func aReportToATableNothingFollowsIsDroppedAndCounted() async throws {
        // Given a client following `todos` only
        let realtime = FakeRealtime()
        let client = SupabaseOfflineClient.inMemory(config: .init(realtimeTables: [RealtimeTable("todos")]), realtime: realtime)
        #expect(await eventually { realtime.subscribedTables == ["todos"] })

        // When a change of another table is reported
        try realtime.reportInsert(table: "notes", record: ["id": .int(1)])

        // Then it is dropped and counted
        #expect(await eventually { realtime.droppedReportCount == 1 })
        try await client.close()
    }

    @Test func tablesAndFiltersCrossToTheCoreByTheirRequestNames() throws {
        // Given tables with each filter form
        let equal = RealtimeTable("todos", schema: "private", filter: RealtimeFilter(column: "done", operator: .eq, value: false)).core
        let list = RealtimeFilter(column: "id", in: [1, 2]).core
        let one = RealtimeFilter(column: "id", operator: .in, value: 7).core

        // Then each crosses by its request name, the value as text
        #expect(equal.table == "todos")
        #expect(equal.schema == "private")
        #expect(equal.filter?.column == "done")
        #expect(equal.filter?.operatorName == "eq")
        #expect(equal.filter?.value == "false")
        #expect(equal.filter?.values == nil)
        #expect(list.operatorName == "in")
        #expect(list.value == nil)
        #expect(list.values == ["1", "2"])
        #expect(one.values == ["7"])
        #expect(RealtimeTable("todos").core.schema == nil)
        #expect(RealtimeTable("todos").core.filter == nil)

        // And the configuration's tables reach the core's options, none by default
        let options = try SupabaseOfflineClient.coreOptions(
            supabaseURL: URL(string: "https://example.supabase.co")!,
            supabaseKey: "key",
            config: .init(realtimeTables: [RealtimeTable("todos"), RealtimeTable("notes")])
        )
        #expect(options.realtimeTables.map(\.table) == ["todos", "notes"])
        #expect(SupabaseOfflineClient.Config().realtimeTables.isEmpty)
    }

    @Test func statusesCompareByStateAndAStallByItsCause() {
        // Given stalls with the same and with different causes
        let refused = RealtimeStatus.stalled(cause: offlineError(code: .server, message: "refused"))
        let refusedAgain = RealtimeStatus.stalled(cause: offlineError(code: .server, message: "refused"))
        let dropped = RealtimeStatus.stalled(cause: offlineError(code: .connectivity, message: "dropped"))

        // Then
        #expect(refused == refusedAgain)
        #expect(refused != dropped)
        #expect(RealtimeStatus.off == .off)
        #expect(RealtimeStatus.paused != .subscribing)
        #expect(RealtimeStatus.subscribed != refused)
    }

    /// The cached rows of `todos` once `condition` holds for them, or the last read within 5 seconds.
    private func cachedRows(of client: SupabaseOfflineClient, until condition: ([TodoRow]) -> Bool) async throws -> [TodoRow] {
        let deadline = ContinuousClock.now + .seconds(5)
        var rows: [TodoRow] = []
        repeat {
            rows = try await client.from("todos").select(readStrategy: .cacheOnly).execute([TodoRow].self).value
            if condition(rows) { break }
            try await Task.sleep(for: .milliseconds(10))
        } while ContinuousClock.now < deadline
        return rows
    }
}

extension RealtimeStatus {

    /// The stall's message, for a stalled status.
    fileprivate var stallMessage: String? {
        if case .stalled(let cause) = self { return cause.message }
        return nil
    }

    /// The stall's code, for a stalled status.
    fileprivate var stallCode: OfflineError.Code? {
        if case .stalled(let cause) = self { return cause.code }
        return nil
    }
}
