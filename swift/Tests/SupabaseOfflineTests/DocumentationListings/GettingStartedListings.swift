// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline
import SupabaseOfflineTesting
import SwiftUI
import Testing

// The listings of the Getting started article, the site's Swift page and the README's Swift quick
// start. The pages show the marked sections; scripts/check-swift-doc-listings.py keeps their copies
// equal to them.

// --8<-- [start:row]
nonisolated struct Todo: Codable, Sendable, Identifiable {
    var id: UUID
    var title: String
    var done: Bool
}
// --8<-- [end:row]

/// The article's calls, made through the client an app keeps.
struct GettingStartedListings {

    /// The app's client.
    let offline: SupabaseOfflineClient

    /// The app's supabase-swift client, for Auth.
    let supabase: FakeSupabaseSwiftClient

    /// The client as an app makes it, its user's token taken from supabase-swift's session.
    static func makeClient(supabase: FakeSupabaseSwiftClient) -> SupabaseOfflineClient {
        // --8<-- [start:client]
        let offline = SupabaseOfflineClient(
            supabaseURL: URL(string: "https://your-project.supabase.co")!,
            supabaseKey: "your-publishable-key",
            config: .init(accessToken: { try? await supabase.auth.session.accessToken })
        )
        // --8<-- [end:client]
        return offline
    }

    // --8<-- [start:sign-out]
    func signOut() async throws {
        // Send the user's queued writes while the closure still returns their token. Offline this
        // returns at once with the writes still queued, and the clear below drops them.
        try await offline.syncPendingMutations()
        // Forget the user's rows and queued writes before Auth can return another user's token.
        try await offline.clearLocalData()
        try await supabase.auth.signOut()
    }
    // --8<-- [end:sign-out]

    /// The first read: from the server when it answers, from the device's copy when it does not.
    func firstRead() async throws -> [Todo] {
        // --8<-- [start:first-read]
        let todos: [Todo] = try await offline.from("todos")
            .select()
            .eq("done", value: false)
            .order("title")
            .execute()
            .value
        // --8<-- [end:first-read]
        return todos
    }

    /// The first write, which waits on the device while the network is out of reach.
    func firstWrite() async throws -> MutationOutcome<Void> {
        // --8<-- [start:queued-write]
        let todo = Todo(id: UUID(), title: "Buy milk", done: false)
        let outcome = try await offline.from("todos").insert(todo).execute()
        // .applied: the server has the row. .queued(let pending): the row waits on the device,
        // reads show it already, and the client sends it when the network is back.
        // --8<-- [end:queued-write]
        return outcome
    }
}

// --8<-- [start:live-read]
struct TodoList: View {
    let offline: SupabaseOfflineClient
    @State private var todos: [Todo] = []

    var body: some View {
        List(todos) { todo in
            Text(todo.title)
        }
        .task {
            // Each snapshot is the rows now: the device's copy at once, then the server's, then
            // again after every write, sync or refresh that changes them. Leaving the view ends it.
            do {
                for try await snapshot in offline.from("todos").selectAsSequence(as: [Todo].self).order("title") {
                    todos = snapshot.result ?? []
                }
            } catch {
                // The read ended with an error it cannot recover from, such as a decoding failure.
            }
        }
    }
}
// --8<-- [end:live-read]

/// Runs the Getting started article's calls against an in-memory client.
struct GettingStartedListingsTests {

    @Test func theFirstReadAndWriteWorkOnlineAndOffline() async throws {
        // Given a server holding one row, reached until the network is turned off
        let row = #"[{"id":"7f4c8e2a-1b3d-4e5f-8a9b-0c1d2e3f4a5b","title":"Buy milk","done":false}]"#
        let server = FakeServer { request in
            request.method == "GET" ? .json(row) : .json("[]", status: 201)
        }
        let offline = SupabaseOfflineClient.inMemory(config: .init(autoSync: false), server: server)
        let listings = GettingStartedListings(offline: offline, supabase: FakeSupabaseSwiftClient())

        // When the app reads online, then reads and writes offline
        let online = try await listings.firstRead()
        try await offline.disableNetwork()
        let offlineRead = try await listings.firstRead()
        let outcome = try await listings.firstWrite()

        // Then the offline read is the cached copy, and the write waits in the queue
        #expect(online.map(\.title) == ["Buy milk"])
        #expect(offlineRead.map(\.title) == ["Buy milk"])
        #expect(outcome.isQueued)

        // When the user signs out
        try await listings.signOut()

        // Then nothing of theirs is left on the device
        #expect(try await offline.pendingMutationCount() == 0)
        try await offline.close()
    }
}
