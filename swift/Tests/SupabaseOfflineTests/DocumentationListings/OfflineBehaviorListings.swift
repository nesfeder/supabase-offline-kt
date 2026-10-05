// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline
import SupabaseOfflineTesting
import Testing

// The listings of the Offline behavior article. scripts/check-swift-doc-listings.py keeps the
// article's copies equal to the marked sections.

/// The article's calls, made through the client an app keeps.
struct OfflineBehaviorListings {

    /// The app's client.
    let offline: SupabaseOfflineClient

    /// One read under each one-shot strategy.
    func reads() async throws -> [[Todo]] {
        // --8<-- [start:strategies]
        // The server, and the device's copy when the server cannot be reached (the default).
        let current: [Todo] = try await offline.from("todos").select().execute().value
        // The device's copy when it is under five minutes old; the server otherwise.
        let recent: [Todo] = try await offline.from("todos")
            .select(readStrategy: .cacheFirst(maxAge: .seconds(300))).execute().value
        // The device's copy only; nothing is sent.
        let saved: [Todo] = try await offline.from("todos").select(readStrategy: .cacheOnly).execute().value
        // --8<-- [end:strategies]
        return [current, recent, saved]
    }

    /// Two conditions on one column, sent together.
    func thisWeek(from start: Date, to end: Date) async throws -> [Todo] {
        // --8<-- [start:same-column]
        // Both conditions are sent, in one group, so the read means what it says.
        let thisWeek: [Todo] = try await offline.from("todos").select()
            .gte("created_at", value: start)
            .lt("created_at", value: end)
            .execute().value
        // --8<-- [end:same-column]
        return thisWeek
    }

    /// A write that must reach the server now.
    func payNow(orderID: UUID) async throws {
        // --8<-- [start:online-only]
        // Never queued: it is sent now, or it throws.
        try await offline.from("orders").update(["status": "paid"], mutationStrategy: .onlineOnly)
            .eq("id", value: orderID).execute()
        // --8<-- [end:online-only]
    }
}

/// Runs the Offline behavior article's calls against an in-memory client.
struct OfflineBehaviorListingsTests {

    @Test func eachStrategyAnswersAndBothConditionsAreSent() async throws {
        // Given a server holding one row, which records the queries it is sent
        let queries = RecordingList<String>()
        let server = FakeServer { request in
            queries.append(request.url.query(percentEncoded: false) ?? "")
            return request.method == "GET" ? .json(#"[{"id":"7f4c8e2a-1b3d-4e5f-8a9b-0c1d2e3f4a5b","title":"Tea","done":false}]"#) : .json("[]")
        }
        let offline = SupabaseOfflineClient.inMemory(config: .init(autoSync: false), server: server)
        let listings = OfflineBehaviorListings(offline: offline)

        // When the reads run, and a read with two conditions on one column
        let reads = try await listings.reads()
        _ = try await listings.thisWeek(
            from: Date(timeIntervalSince1970: 1_790_000_000),
            to: Date(timeIntervalSince1970: 1_790_604_800)
        )
        try await listings.payNow(orderID: UUID())

        // Then every read has the row, and the second query sent both conditions
        #expect(reads.map(\.count) == [1, 1, 1])
        #expect(queries.values.contains { $0.contains("created_at.gte") && $0.contains("created_at.lt") })
        try await offline.close()
    }
}
