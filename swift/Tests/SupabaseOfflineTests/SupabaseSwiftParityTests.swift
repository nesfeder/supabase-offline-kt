// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOfflineTesting
import Testing
import SupabaseOffline

/// Calls written the way an app moving from supabase-swift writes them, with only the receiver
/// changed: this file must compile against the public API alone (no `@testable` import), and the
/// test runs it against a fake server so every chain is also sent.
struct SupabaseSwiftParityTests {

    /// A row as an app declares it, decoded off the main actor.
    struct Todo: Codable, Sendable, Equatable {
        let id: Int
        let title: String
        let done: Bool
        let createdAt: Date

        enum CodingKeys: String, CodingKey {
            case id, title, done
            case createdAt = "created_at"
        }
    }

    /// A row the app inserts.
    struct NewTodo: Encodable, Sendable {
        let id: Int
        let title: String
        let done: Bool
    }

    /// The arguments of `add_points`.
    struct AddPoints: Encodable, Sendable {
        let userID: Int
        let points: Int

        enum CodingKeys: String, CodingKey {
            case userID = "user_id"
            case points
        }
    }

    /// A row as the server sends it.
    private static let rowJSON = #"{"id":1,"title":"Milk","done":false,"created_at":"2026-10-07T09:30:00.123456+00:00"}"#

    /// Answers as PostgREST would: an object for a single-row read, a count for a function, rows for
    /// any other read, and the written rows for a write.
    private static let server = FakeServer { request in
        if request.url.path().hasPrefix("/rest/v1/rpc/") { return .json("3") }
        if request.method == "HEAD" { return .status(200, headers: ["Content-Range": "0-0/1"]) }
        if request.headers.contains(where: { $0.value.contains("vnd.pgrst.object") }) { return .json(rowJSON) }
        return .json("[\(rowJSON)]", status: request.method == "GET" ? 200 : 201, headers: ["Content-Range": "0-0/1"])
    }

    @Test func supabaseSwiftChainsCompileAndRun() async throws {
        let db = SupabaseOfflineClient.inMemory(server: Self.server)
        let id = 1
        let newTodo = NewTodo(id: 2, title: "Tea", done: false)

        // Reads, as supabase-swift writes them
        let todos: [Todo] = try await db.from("todos").select().eq("done", value: false)
            .order("created_at", ascending: false).execute().value
        let one: Todo = try await db.from("todos").select("id, title, done, created_at").eq("id", value: id).single()
            .execute().value
        let counted = try await db.from("todos").select("*", head: true, count: .exact).execute().count
        let filtered: [Todo] = try await db.from("todos").select()
            .neq("title", value: "x").gt("id", value: 0).gte("id", value: 1).lt("id", value: 9).lte("id", value: 8)
            .like("title", pattern: "M%").ilike("title", pattern: "m%").match("title", pattern: "^M").imatch("title", pattern: "^m")
            .is("deleted_at", value: nil).in("id", values: [1, 2, 3]).notIn("id", values: [4])
            .contains("tags", value: ["swift", "ios"]).containedBy("tags", value: ["swift", "ios", "macos"])
            .overlaps("tags", value: ["ios"]).rangeAdjacent("slot", range: "(1,5)")
            .likeAllOf("title", patterns: ["%i%", "%l%"]).iLikeAnyOf("notes", patterns: ["%a%"])
            .textSearch("content", query: "swift ios", config: "english", type: .websearch)
            .not("status", operator: .eq, value: "archived").or("done.eq.true,id.gt.3")
            .match(["priority": 1]).filter("status", operator: "neq", value: "deleted")
            .limit(20).range(from: 0, to: 9).execute().value

        // Writes, as supabase-swift writes them
        try await db.from("todos").insert(newTodo).execute()
        try await db.from("todos").insert([newTodo]).execute()
        try await db.from("todos").upsert(newTodo, onConflict: "id", ignoreDuplicates: true).execute()
        try await db.from("todos").update(["done": true]).eq("id", value: id).execute()
        try await db.from("todos").delete().eq("id", value: id).execute()

        // Function calls, as supabase-swift writes them
        try await db.rpc("add_points", params: AddPoints(userID: id, points: 5)).execute()
        let open: Int = try await db.rpc("count_open").execute().value

        // What changes: a write's rows are read through its outcome
        let inserted: [Todo]
        switch try await db.from("todos").insert(newTodo).select().execute([Todo].self) {
        case .applied(let result): inserted = result.value
        case .queued: inserted = []
        }

        // New: the offline choices, a live read and the error codes
        let cached: [Todo] = try await db.from("todos").select(readStrategy: .cacheFirst(maxAge: .seconds(60))).execute().value
        var live: [Todo]?
        for try await snapshot in db.from("todos").selectAsSequence(as: [Todo].self).eq("done", value: false) {
            live = snapshot.result
            if snapshot.refresh == .idle, live != nil { break }
        }
        try await db.from("todos").update(["notes": nil], mutationStrategy: .onlineOnly).eq("id", value: id).execute()
        do {
            _ = try await db.from("todos").select(readStrategy: .cacheOnly).eq("id", value: 99).execute([Todo].self)
        } catch OfflineError.cacheMiss {
            // nothing cached for this query
        }

        // Then
        let milk = Todo(id: 1, title: "Milk", done: false, createdAt: Date(timeIntervalSince1970: 1_791_365_400.123456))
        #expect(todos.map(\.id) == [1])
        #expect(one.id == milk.id && abs(one.createdAt.timeIntervalSince(milk.createdAt)) < 0.000_001)
        #expect(counted == 1)
        #expect(filtered.count == 1)
        #expect(open == 3)
        #expect(inserted.map(\.id) == [1])
        #expect(cached.map(\.id) == [1])
        #expect(live?.map(\.id) == [1])
        try await db.close()
    }
}
