// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline
import SupabaseOfflineTesting
import Testing

// The listings of the Coming from supabase-swift article. scripts/check-swift-doc-listings.py keeps
// the article's copies equal to the marked sections.

/// The article's calls, made through the client an app keeps.
struct ComingFromSupabaseSwiftListings {

    /// The app's client.
    let offline: SupabaseOfflineClient

    /// Each call as supabase-swift writes it, with only its receiver changed.
    func sameCalls(todo: Todo, photoPath: String) async throws -> [Todo] {
        // --8<-- [start:same-calls]
        let todos: [Todo] = try await offline.from("todos").select().eq("done", value: false)
            .order("title").execute().value
        try await offline.from("todos").insert(todo).execute()
        try await offline.from("todos").update(["done": true]).eq("id", value: todo.id).execute()
        try await offline.from("todos").delete().eq("id", value: todo.id).execute()
        let photo = try await offline.storage.from("photos").download(path: photoPath)
        try await offline.storage.from("photos").upload(photoPath, data: photo, options: .init(contentType: "image/jpeg"))
        // --8<-- [end:same-calls]
        return todos
    }

    /// A write whose row the app reads back.
    func insertedRow(todo: Todo) async throws -> Todo {
        // --8<-- [start:outcome]
        let saved: Todo
        switch try await offline.from("todos").insert(todo).select().single().execute(Todo.self) {
        case .applied(let response):
            saved = response.value  // the row as the server stored it
        case .queued:
            saved = todo  // the row as sent; reads show it already, and a sync sends it
        }
        // --8<-- [end:outcome]
        return saved
    }
}

/// Runs the Coming from supabase-swift article's calls against an in-memory client.
struct ComingFromSupabaseSwiftListingsTests {

    @Test func theSameCallsRunAndAQueuedWriteKeepsTheRowSent() async throws {
        // Given a server that answers every read and write
        let todo = Todo(id: UUID(), title: "Buy milk", done: false)
        let row = #"{"id":"\#(todo.id.uuidString.lowercased())","title":"Buy milk","done":false}"#
        let server = FakeServer { request in
            if request.url.path().hasPrefix("/storage/") {
                return request.method == "GET"
                    ? .status(200, headers: ["Content-Type": "image/jpeg"], body: Data([0xFF, 0xD8]))
                    : .json(#"{"Id":"5b6c","Key":"photos/milk.jpg"}"#)
            }
            if request.headers.contains(where: { $0.value.contains("vnd.pgrst.object") }) { return .json(row, status: 201) }
            return .json("[\(row)]", status: request.method == "GET" ? 200 : 201)
        }
        let offline = SupabaseOfflineClient.inMemory(config: .init(autoSync: false), server: server)
        let listings = ComingFromSupabaseSwiftListings(offline: offline)

        // When the calls run online, then a write is made offline
        let todos = try await listings.sameCalls(todo: todo, photoPath: "milk.jpg")
        let applied = try await listings.insertedRow(todo: todo)
        try await offline.disableNetwork()
        let queued = try await listings.insertedRow(todo: Todo(id: UUID(), title: "Tea", done: false))

        // Then each answered as the server did, and the queued write kept the row sent
        #expect(todos.map(\.title) == ["Buy milk"])
        #expect(applied.id == todo.id)
        #expect(queued.title == "Tea")
        try await offline.close()
    }
}
