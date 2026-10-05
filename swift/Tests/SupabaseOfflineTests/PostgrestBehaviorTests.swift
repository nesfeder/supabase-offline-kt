// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOfflineCore
import SupabaseOfflineTesting
import Testing
@testable import SupabaseOffline

/// Reads and writes on the in-memory client against a fake server: the real cache, overlay and
/// mutation queue, seen through the Swift chain.
struct PostgrestBehaviorTests {

    /// The two rows the server holds.
    private static let serverRows = #"[{"id":1,"title":"Milk","done":false},{"id":2,"title":"Eggs","done":true}]"#

    /// The rows `serverRows` decodes to.
    private static let decodedServerRows = [
        TodoRow(id: 1, title: "Milk", done: false), TodoRow(id: 2, title: "Eggs", done: true),
    ]

    @Test func aListReadOfflineIsServedFromTheCacheWithItsFetchTime() async throws {
        // Given rows read once while online, at a known time
        let server = RecordingFakeServer(rows: Self.serverRows)
        let fetchedAt = Date(timeIntervalSince1970: 1_791_115_200)
        let client = SupabaseOfflineClient.inMemory(server: server.server, clock: ManualClock(now: fetchedAt))
        let online = try await client.from("todos").select().execute([TodoRow].self)

        // When the server can no longer be reached
        server.goOffline()
        let offline = try await client.from("todos").select().execute([TodoRow].self)
        let cacheOnly = try await client.from("todos").select(readStrategy: .cacheOnly).execute([TodoRow].self)

        // Then
        #expect(online.value == Self.decodedServerRows)
        #expect(!online.isFromCache)
        #expect(offline.value == Self.decodedServerRows)
        #expect(offline.isFromCache)
        #expect(offline.fetchedAt == fetchedAt)
        #expect(!offline.hasPendingMutations)
        #expect(cacheOnly.value == Self.decodedServerRows)
        #expect(server.requests(withMethod: "GET").count == 2)
        try await client.close()
    }

    @MainActor
    @Test func aQueuedWriteShowsInALiveReadAtOnce() async throws {
        // Given a live read showing the server's rows
        let server = RecordingFakeServer(rows: Self.serverRows)
        let client = SupabaseOfflineClient.inMemory(server: server.server)
        let snapshots = RecordingList<QuerySnapshot<[TodoRow]>>()
        let reading = Task {
            for try await snapshot in client.from("todos").selectAsSequence(as: [TodoRow].self).order("id") {
                snapshots.append(snapshot)
            }
        }
        #expect(await eventually { snapshots.values.last?.result == Self.decodedServerRows && snapshots.values.last?.refresh == .idle })

        // When a write is made with the server out of reach
        server.goOffline()
        let outcome = try await client.from("todos").insert(TodoRow(id: 3, title: "Tea", done: false)).execute()

        // Then it is queued, and the live read shows it with the pending flag
        guard case .queued(let pending) = outcome else {
            Issue.record("expected a queued write, got \(outcome)")
            reading.cancel()
            return
        }
        #expect(outcome.isQueued)
        #expect(outcome.result == nil)
        #expect(pending.table == "todos")
        #expect(pending.operation == .insert)
        #expect(pending.body == [["id": 3, "title": "Tea", "done": false]])
        #expect(pending.rowKeys == [["id": 3]])
        #expect(try await client.rawPendingMutationCount() == 1)
        let shown = await eventually {
            snapshots.values.last?.result == Self.decodedServerRows + [TodoRow(id: 3, title: "Tea", done: false)]
                && snapshots.values.last?.hasPendingMutations == true
        }
        #expect(shown, "last snapshot: \(String(describing: snapshots.values.last))")
        reading.cancel()
        try await client.close()
    }

    @Test func aWriteTheServerRefusesThrowsItsCodeAndLeavesTheQueue() async throws {
        // Given a server that refuses a duplicate key
        let server = RecordingFakeServer { _ in
            .json(
                #"{"code":"23505","message":"duplicate key value violates unique constraint \"bridge\"","details":"Key (id)=(1) already exists.","hint":"Kotlin hint"}"#,
                status: 409
            )
        }
        let client = SupabaseOfflineClient.inMemory(server: server.server)

        // When
        let error = await #expect(throws: OfflineError.self) {
            try await client.from("todos").insert(TodoRow(id: 1, title: "Milk", done: false)).execute()
        }

        // Then
        #expect(error?.code == .server)
        #expect(error?.serverError?.status == 409)
        #expect(error?.serverError?.code == "23505")
        #expect(error?.serverError?.details == "Key (id)=(1) already exists.")
        #expect(error?.serverError?.hint == "Kotlin hint")
        #expect(error?.message == #"duplicate key value violates unique constraint "bridge""#)
        #expect(error?.serverError?.message == #"duplicate key value violates unique constraint "bridge""#)
        #expect(error?.httpStatus == 409)
        #expect(try await client.rawPendingMutationCount() == 0)
        try await client.close()
    }

    @Test func anAppliedWriteReturnsTheRowsItAskedFor() async throws {
        // Given
        let server = RecordingFakeServer { _ in .json(#"[{"id":3,"title":"Tea","done":false}]"#, status: 201) }
        let client = SupabaseOfflineClient.inMemory(server: server.server)

        // When
        let outcome = try await client.from("todos").insert(TodoRow(id: 3, title: "Tea", done: false))
            .select().execute([TodoRow].self)

        // Then
        #expect(!outcome.isQueued)
        #expect(outcome.result?.value == [TodoRow(id: 3, title: "Tea", done: false)])
        #expect(server.requests.values.first?.queryParameters.first == ["select", "*"])
        try await client.close()
    }

    @Test func aCacheOnlyReadOfNothingCachedIsACacheMissAndSendsNothing() async throws {
        // Given
        let server = RecordingFakeServer(rows: Self.serverRows)
        let client = SupabaseOfflineClient.inMemory(server: server.server)

        // When
        let oneShot = await #expect(throws: OfflineError.self) {
            try await client.from("todos").select(readStrategy: .cacheOnly).execute([TodoRow].self)
        }
        let live = await #expect(throws: OfflineError.self) {
            for try await _ in client.from("todos").selectAsSequence(as: [TodoRow].self, readStrategy: .cacheOnly).single() {}
        }

        // Then
        #expect(oneShot?.code == .cacheMiss)
        #expect(live?.code == .cacheMiss)
        #expect(server.requests.values.isEmpty)
        try await client.close()
    }

    @Test func rowsThatDoNotDecodeFailAsDecoding() async throws {
        // Given a server whose rows are not `TodoRow`s
        let server = RecordingFakeServer(rows: #"[{"id":"one","title":"Milk"}]"#)
        let client = SupabaseOfflineClient.inMemory(server: server.server)

        // When
        let error = await #expect(throws: OfflineError.self) {
            try await client.from("todos").select().execute([TodoRow].self)
        }

        // Then
        #expect(error?.code == .decoding)
        #expect(error?.underlyingError is DecodingError)
        try await client.close()
    }

    @Test func aLiveSnapshotThatDoesNotDecodeEndsTheReadAndItsSubscription() async throws {
        // Given
        let server = RecordingFakeServer(rows: #"[{"id":"one","title":"Milk"}]"#)
        let client = SupabaseOfflineClient.inMemory(server: server.server)
        let ends = RecordingList<String?>()
        let read = client.from("todos").selectAsSequence(as: [TodoRow].self, readStrategy: .networkFirstThenCache)
            .recordingEnds(in: ends)

        // When
        let error = await #expect(throws: OfflineError.self) {
            for try await _ in read {}
        }

        // Then
        #expect(error?.code == .decoding)
        #expect(await eventually { ends.values == [BridgeErrorKind.cancelled.name] })
        try await client.close()
    }

    @Test func cancelingAReadThrowsCancellationAndLeavingALiveReadEndsItsSubscription() async throws {
        // Given a server that holds every request until the test lets it go
        let gate = Gate()
        let server = RecordingFakeServer { _ in
            await gate.wait()
            return .json("[]")
        }
        let client = SupabaseOfflineClient.inMemory(server: server.server)

        // When a one-shot read is canceled while the server holds it
        let reading = Task { try await client.from("todos").select().execute([TodoRow].self) }
        #expect(await eventually { server.requests.values.count == 1 })
        reading.cancel()
        let result = await reading.result

        // Then
        #expect(throws: CancellationError.self) { try result.get() }

        // When a live read's loop is left after its first snapshot
        let ends = RecordingList<String?>()
        for try await snapshot in client.from("todos").selectAsSequence(as: [TodoRow].self).recordingEnds(in: ends) {
            #expect(snapshot.result == nil)
            break
        }

        // Then
        #expect(await eventually { ends.values == [BridgeErrorKind.cancelled.name] })
        await gate.open()
        try await client.close()
    }

    @Test func cancelingTheTaskOfALiveReadEndsItsSubscription() async throws {
        // Given
        let client = SupabaseOfflineClient.inMemory(server: RecordingFakeServer(rows: Self.serverRows).server)
        let ends = RecordingList<String?>()
        let snapshots = RecordingList<QuerySnapshot<[TodoRow]>>()
        let reading = Task {
            for try await snapshot in client.from("todos").selectAsSequence(as: [TodoRow].self).recordingEnds(in: ends) {
                snapshots.append(snapshot)
            }
        }
        #expect(await eventually { !snapshots.values.isEmpty })

        // When
        reading.cancel()

        // Then
        #expect(await eventually { ends.values == [BridgeErrorKind.cancelled.name] })
        try await client.close()
    }

    @Test func aHeadReadCountsWithoutRows() async throws {
        // Given a server that reports a total
        let server = RecordingFakeServer { _ in .status(200, headers: ["Content-Range": "*/42"]) }
        let client = SupabaseOfflineClient.inMemory(server: server.server)

        // When
        let response = try await client.from("todos").select(head: true, count: .exact).eq("done", value: false).execute()

        // Then
        #expect(response.count == 42)
        #expect(server.requests.values.first?.method == "HEAD")
        try await client.close()
    }

    @Test func aFilterThatCannotBeSentFailsBeforeAnythingIsSent() async throws {
        // Given
        let server = RecordingFakeServer(rows: Self.serverRows)
        let client = SupabaseOfflineClient.inMemory(server: server.server)

        // When
        let read = await #expect(throws: OfflineError.self) {
            try await client.from("todos").select().or("done.eq.true,").execute()
        }
        let live = await #expect(throws: OfflineError.self) {
            for try await _ in client.from("todos").selectAsSequence(as: [TodoRow].self).rangeLt("slot", range: "[1,5)") {}
        }

        // Then
        #expect(read?.code == .invalidRequest)
        #expect(live?.code == .invalidRequest)
        #expect(server.requests.values.isEmpty)
        try await client.close()
    }

    @Test func aRangeOnOneColumnIsReadAsOneAndGroup() async throws {
        // Given
        let server = RecordingFakeServer(rows: Self.serverRows)
        let client = SupabaseOfflineClient.inMemory(server: server.server)

        // When
        _ = try await client.from("todos").select().gte("id", value: 1).lt("id", value: 3).execute([TodoRow].self)

        // Then
        #expect(server.requests.values.first?.queryParameters == [["and", "(id.gte.1,id.lt.3)"], ["select", "*"]])
        try await client.close()
    }

    @Test func anOnlineOnlyWriteOfflineFailsAsAConnectivityFailureAndIsNotQueued() async throws {
        // Given
        let client = SupabaseOfflineClient.inMemory(server: FakeServer())

        // When
        let error = await #expect(throws: OfflineError.self) {
            try await client.from("todos").update(["done": true], mutationStrategy: .onlineOnly).eq("id", value: 1).execute()
        }

        // Then
        #expect(error?.code == .connectivity)
        #expect(error?.isNetworkOrConnectivityFailure == true)
        #expect(try await client.rawPendingMutationCount() == 0)
        try await client.close()
    }

    @Test func aFunctionCallDecodesItsResult() async throws {
        // Given
        let server = RecordingFakeServer { _ in .json("7") }
        let client = SupabaseOfflineClient.inMemory(server: server.server)

        // When
        let response = try await client.rpc("add_points", params: ["points": 5]).execute(Int.self)

        // Then
        #expect(response.value == 7)
        #expect(server.requests.values.first?.method == "POST")
        #expect(try server.requests.values.first?.jsonBody == ["points": 5])
        try await client.close()
    }

    // MARK: - Where a live read's rows came from

    @Test func aLiveReadsRefreshedRowsAreTheServersWithTheirFetchTime() async throws {
        // Given
        let fetchedAt = Date(timeIntervalSince1970: 1_791_115_200)
        let client = SupabaseOfflineClient.inMemory(
            server: RecordingFakeServer(rows: Self.serverRows).server,
            clock: ManualClock(now: fetchedAt)
        )
        let snapshots = RecordingList<QuerySnapshot<[TodoRow]>>()

        // When
        let reading = Task {
            for try await snapshot in client.from("todos").selectAsSequence(as: [TodoRow].self) {
                snapshots.append(snapshot)
            }
        }

        // Then
        let refreshed = await eventually {
            snapshots.values.last.map {
                $0.result == Self.decodedServerRows && !$0.isFromCache && $0.fetchedAt == fetchedAt && $0.refresh == .idle
            } ?? false
        }
        #expect(refreshed, "last snapshot: \(String(describing: snapshots.values.last))")
        reading.cancel()
        try await client.close()
    }

    @Test func aLiveReadWhoseRefreshKeepsFailingShowsTheCachedRowsAndStalls() async throws {
        // Given rows read once, at a known time
        let server = RecordingFakeServer(rows: Self.serverRows)
        let clock = ManualClock(now: Date(timeIntervalSince1970: 1_791_115_200))
        let client = SupabaseOfflineClient.inMemory(server: server.server, clock: clock)
        _ = try await client.from("todos").select().execute([TodoRow].self)

        // When the server fails every later request while the network is up
        server.answer { _ in .json(#"{"code":"XX000","message":"internal failure","details":null,"hint":null}"#, status: 500) }
        clock.advance(by: .seconds(60))
        let snapshots = RecordingList<QuerySnapshot<[TodoRow]>>()
        let reading = Task {
            for try await snapshot in client.from("todos").selectAsSequence(as: [TodoRow].self) {
                snapshots.append(snapshot)
            }
        }

        // Then
        let stalled = await eventually(within: .seconds(20)) {
            guard let last = snapshots.values.last, case .stalled = last.refresh else { return false }
            return true
        }
        #expect(stalled, "snapshots: \(snapshots.values.map(\.refresh))")
        let last = try #require(snapshots.values.last)
        #expect(last.result == Self.decodedServerRows)
        #expect(last.isFromCache)
        #expect(last.fetchedAt == Date(timeIntervalSince1970: 1_791_115_200))
        guard case .stalled(let cause) = last.refresh else { return }
        #expect(cause.code == .server)
        #expect(cause.serverError?.status == 500)
        #expect(cause.serverError?.code == "XX000")
        #expect(cause.message == "internal failure")
        reading.cancel()
        try await client.close()
    }

    // MARK: - Values a list or group would send differently

    @Test func aListValueWithAQuoteOrBackslashIsRefusedAndNothingIsSent() async throws {
        // Given
        let server = RecordingFakeServer(rows: Self.serverRows)
        let client = SupabaseOfflineClient.inMemory(server: server.server)

        // When
        let inList = await #expect(throws: OfflineError.self) {
            try await client.from("todos").select().in("code", values: [#"a"b"#, "c"]).execute()
        }
        let notInList = await #expect(throws: OfflineError.self) {
            try await client.from("todos").select().notIn("path", values: [#"x\y"#]).execute()
        }

        // Then
        for error in [inList, notInList] {
            #expect(error?.code == .invalidRequest)
            #expect(error?.message.contains("double quote or a backslash") == true)
            #expect(!namesTheRuntime(error?.message ?? ""))
        }
        #expect(inList?.message.contains(#"a"b"#) == false)
        #expect(server.requests.values.isEmpty)
        try await client.close()
    }

    @Test func aBackslashInAnOrTextIsRefusedAndNothingIsSent() async throws {
        // Given
        let server = RecordingFakeServer(rows: Self.serverRows)
        let client = SupabaseOfflineClient.inMemory(server: server.server)

        // When
        let error = await #expect(throws: OfflineError.self) {
            try await client.from("todos").select().or(#"path.eq.x\y,done.eq.true"#).execute()
        }

        // Then
        #expect(error?.code == .invalidRequest)
        #expect(error?.message.contains(#""path""#) == true)
        #expect(server.requests.values.isEmpty)
        try await client.close()
    }

    @Test func aQuoteInAQuotedOrValueIsRefusedAndNothingIsSent() async throws {
        // Given
        let server = RecordingFakeServer(rows: Self.serverRows)
        let client = SupabaseOfflineClient.inMemory(server: server.server)

        // When
        let error = await #expect(throws: OfflineError.self) {
            try await client.from("todos").select().or(#"title.eq."say \"zqx\"",done.eq.true"#).execute()
        }

        // Then
        #expect(error?.code == .invalidRequest)
        #expect(error?.message.contains("zqx") == false)
        #expect(error?.message.contains(#""title""#) == true)
        #expect(server.requests.values.isEmpty)
        try await client.close()
    }

    @Test func aBackslashInAPairFoldedIntoAGroupIsRefusedAndNothingIsSent() async throws {
        // Given
        let server = RecordingFakeServer(rows: Self.serverRows)
        let client = SupabaseOfflineClient.inMemory(server: server.server)

        // When
        let error = await #expect(throws: OfflineError.self) {
            try await client.from("todos").select().match("code", pattern: #"^\d+$"#).neq("code", value: "0").execute()
        }

        // Then
        #expect(error?.code == .invalidRequest)
        #expect(error?.message.contains(#""code""#) == true)
        #expect(server.requests.values.isEmpty)
        try await client.close()
    }

    @Test func aQuoteOrBackslashIsSentRightAtTheTopLevelAndWhenTheValueIsQuoted() async throws {
        // Given
        let server = RecordingFakeServer(rows: "[]")
        let client = SupabaseOfflineClient.inMemory(server: server.server)

        // When: single top-level values, sent as written
        _ = try await client.from("todos").select().eq("title", value: #"say "hi" \ x"#).match("code", pattern: #"^\d+$"#)
            .execute()
        // When: values a list or group quotes, because they hold one of ,.:()
        _ = try await client.from("todos").select().in("title", values: [#"a "b", c"#, #"d\e.f"#]).execute()
        _ = try await client.from("todos").select().or(#"title.eq."a \"b\", c",done.eq.true"#).execute()

        // Then
        let queries = server.requests.values.map(\.queryParameters)
        #expect(queries == [
            [["title", #"eq.say "hi" \ x"#], ["code", #"match.^\d+$"#], ["select", "*"]],
            [["title", #"in.("a \"b\", c","d\\e.f")"#], ["select", "*"]],
            [["or", #"(title.eq."a \"b\", c",done.eq.true)"#], ["select", "*"]],
        ])
        try await client.close()
    }

    @Test func aPatternListWrittenAsTextIsSentAsPostgresReadsIt() async throws {
        // Given
        let server = RecordingFakeServer(rows: "[]")
        let client = SupabaseOfflineClient.inMemory(server: server.server)

        // When: an escaped backslash and an escaped quote in plain elements of an array literal
        _ = try await client.from("todos").select().or(#"code.like(any).{a\\b,c}"#).execute()
        _ = try await client.from("todos").select().filter("code", operator: "like(any)", value: #"{a\\b,c}"#).execute()
        _ = try await client.from("todos").select().filter("code", operator: "like(any)", value: #"{a\"b,c}"#).execute()

        // Then: each is sent as written, so Postgres reads `a\b` and `a"b`, as written
        #expect(server.requests.values.map(\.queryParameters) == [
            [["or", #"(code.like(any).{a\\b,c})"#], ["select", "*"]],
            [["code", #"like(any).{a\\b,c}"#], ["select", "*"]],
            [["code", #"like(any).{a\"b,c}"#], ["select", "*"]],
        ])
        try await client.close()
    }

    @Test func aRefusedValueInAnEmbeddedPairIsNamedByTheColumnAsWritten() async throws {
        // Given
        let server = RecordingFakeServer(rows: "[]")
        let client = SupabaseOfflineClient.inMemory(server: server.server)

        // When
        let error = await #expect(throws: OfflineError.self) {
            try await client.from("books").select().neq("authors.name", value: #"a\b"#).neq("authors.name", value: "c")
                .execute()
        }

        // Then
        #expect(error?.code == .invalidRequest)
        #expect(error?.message.contains(#""authors.name""#) == true)
        #expect(server.requests.values.isEmpty)
        try await client.close()
    }

    @Test func aTokenWithALineBreakFailsWithoutQuotingItsHeader() async throws {
        // Given an access token that ends in a line break, which no HTTP header may carry
        let server = RecordingFakeServer(rows: "[]")
        let token = "eyJhbGciOi.secret-token-tail\n"
        let client = SupabaseOfflineClient.inMemory(config: .init(accessToken: { token }), server: server.server)

        // When
        let error = await #expect(throws: OfflineError.self) {
            try await client.from("todos").select(readStrategy: .networkOnly).execute()
        }

        // Then
        #expect(error?.code == .invalidRequest)
        let message = error?.message ?? ""
        #expect(!message.contains("Bearer"), "\(message)")
        #expect(!message.contains("secret"), "\(message)")
        #expect(!message.contains("eyJ"), "\(message)")
        #expect(!namesTheRuntime(message), "\(message)")
        #expect(server.requests.values.isEmpty)
        try await client.close()
    }

    // MARK: - Embedded columns

    @Test func twoConditionsOnAnEmbeddedColumnAreSentInThatTablesOwnGroup() async throws {
        // Given
        let server = RecordingFakeServer(rows: "[]")
        let client = SupabaseOfflineClient.inMemory(server: server.server)

        // When
        _ = try await client.from("books").select("title,authors(name,born)")
            .gte("authors.born", value: 1900).lt("authors.born", value: 2000).eq("title", value: "Dune").execute()

        // Then
        #expect(server.requests.values.first?.queryParameters == [
            ["authors.and", "(born.gte.1900,born.lt.2000)"], ["title", "eq.Dune"], ["select", "title,authors(name,born)"],
        ])
        try await client.close()
    }
}
