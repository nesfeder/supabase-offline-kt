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

// --8<-- [start:off-main-actor]
nonisolated struct TodoPatch: Encodable, Sendable {
    var done: Bool
}

actor TodoRepository {
    let offline: SupabaseOfflineClient

    init(offline: SupabaseOfflineClient) {
        self.offline = offline
    }

    func markDone(id: UUID) async throws {
        try await offline.from("todos").update(TodoPatch(done: true)).eq("id", value: id).execute()
    }
}
// --8<-- [end:off-main-actor]

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
        // Send the user's queued writes while they are signed in. Offline this returns at once,
        // with the writes still queued, and the clear below drops them.
        try await offline.syncPendingMutations()
        // Nothing is sent from here until the next user is signed in.
        try await offline.disableNetwork()
        // Sign out, so the access-token closure returns nil, and clear even if that throws:
        // supabase-swift ends the session on the device before it tells the server.
        do {
            try await supabase.auth.signOut()
        } catch {
            try await offline.clearLocalData()
            throw error
        }
        try await offline.clearLocalData()
    }

    func didSignIn() async throws {
        // The next user's token is live: open live reads fetch again with it.
        try await offline.enableNetwork()
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

        // When an actor of the app's own writes a patch, offline
        try await TodoRepository(offline: offline).markDone(id: UUID())

        // Then it waits in the queue
        #expect(try await offline.pendingMutationCount() == 1)
        try await offline.close()
    }

    // Each step pulls the live read's snapshots until the one it waits for, rather than polling for
    // it against a deadline: a step waits as long as the read takes on a busy simulator, and a read
    // that fails throws out of the test where it does. The time limit bounds a read that never shows
    // what a step waits for.
    @Test(.timeLimit(.minutes(1))) func aSignOutWithALiveReadOpenLeavesNothingOfTheUserForTheNext() async throws {
        // Given user A signed in, with a live read of A's rows open, on a server that answers by token
        let supabase = FakeSupabaseSwiftClient(signedInWith: "user-a-token")
        let tokens = RecordingList<String>()
        let server = FakeServer { request in
            guard request.method == "GET" else { return .json("[]", status: 201) }
            let authorization = request.headers["Authorization"] ?? ""
            tokens.append(String(authorization.split(separator: " ").last ?? ""))
            if authorization.hasSuffix("user-a-token") {
                return .json(#"[{"id":"7f4c8e2a-1b3d-4e5f-8a9b-0c1d2e3f4a5b","title":"A's errand","done":false}]"#)
            }
            if authorization.hasSuffix("user-b-token") {
                return .json(#"[{"id":"0b9d6c3e-2a1f-4e7d-9c8b-1a2b3c4d5e6f","title":"B's errand","done":false}]"#)
            }
            return .json("[]")
        }
        let offline = SupabaseOfflineClient.inMemory(
            config: .init(accessToken: { try? await supabase.auth.session.accessToken }, autoSync: false),
            server: server
        )
        let shown = RecordingList<[String]>()
        do {
            // The read's subscription ends when `live` goes, at the end of this block
            var live = offline.from("todos").selectAsSequence(as: [Todo].self).makeAsyncIterator()
            let showedA = try await pull(&live, until: ["A's errand"], recordingIn: shown)
            try #require(showedA, "shown: \(shown.values), fetched with: \(tokens.values)")
            let listings = GettingStartedListings(offline: offline, supabase: supabase)

            // When A signs out as the article shows, the live read still open
            try await listings.signOut()
            let fetchedBeforeB = tokens.values.count
            let showedNothing = try await pull(&live, until: [], recordingIn: shown)
            try #require(showedNothing, "shown: \(shown.values)")
            let cleared = shown.values.count - 1

            // And B signs in
            supabase.auth.signIn(accessToken: "user-b-token")
            try await listings.didSignIn()

            // Then the open live read shows B's rows, nothing was fetched with A's token after the
            // sign-out, and nothing of A's is shown or kept
            let showedB = try await pull(&live, until: ["B's errand"], recordingIn: shown)
            try #require(showedB, "shown: \(shown.values), fetched with: \(tokens.values)")
            #expect(tokens.values.dropFirst(fetchedBeforeB).allSatisfy { $0 == "user-b-token" }, "\(tokens.values)")
            #expect(shown.values.dropFirst(cleared).allSatisfy { !$0.contains("A's errand") }, "\(shown.values)")
        }
        try await offline.disableNetwork()
        let kept = try await offline.from("todos").select(readStrategy: .cacheOnly).execute([Todo].self).value
        #expect(kept.map(\.title) == ["B's errand"])
        try await offline.close()
    }

    /// Pulls `live`'s snapshots, recording the titles each one shows in `shown`, until one shows
    /// `titles`: `false` when the read ends first. A read that fails throws its error here.
    private func pull(
        _ live: inout QuerySnapshotSequence<[Todo]>.Iterator,
        until titles: [String],
        recordingIn shown: RecordingList<[String]>
    ) async throws -> Bool {
        while let snapshot = try await live.next() {
            let showing = (snapshot.result ?? []).map(\.title)
            shown.append(showing)
            if showing == titles { return true }
        }
        return false
    }
}
