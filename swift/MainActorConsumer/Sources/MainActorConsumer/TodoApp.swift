// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline
import SwiftUI

/// A row, `nonisolated` so the package may decode it off the main actor.
nonisolated struct Todo: Codable, Sendable, Identifiable, Equatable {
    var id: UUID
    var title: String
    var done: Bool
}

/// The app's data, on the main actor as everything here is by default.
final class TodoStore: ObservableObject {

    /// The app's one client.
    let offline: SupabaseOfflineClient

    /// The rows on screen.
    @Published private(set) var todos: [Todo] = []

    /// The banner's text, when there is one.
    @Published private(set) var banner: String?

    /// The last photo downloaded.
    @Published private(set) var photo: Data?

    init(offline: SupabaseOfflineClient) {
        self.offline = offline
    }

    /// A one-shot read, its failures told apart.
    func load() async {
        do {
            todos = try await offline.from("todos").select().eq("done", value: false).order("title").execute().value
        } catch let error as OfflineError where error.isNetworkOrConnectivityFailure {
            banner = "Offline"
        } catch {
            banner = error.localizedDescription
        }
    }

    /// A write that may wait in the queue.
    func add(title: String) async throws {
        let todo = Todo(id: UUID(), title: title, done: false)
        switch try await offline.from("todos").insert(todo).select().single().execute(Todo.self) {
        case .applied(let response):
            todos.append(response.value)
        case .queued:
            todos.append(todo)
        }
    }

    /// A Storage download, served from the device's copy offline.
    func loadPhoto(path: String) async throws {
        photo = try await offline.storage.from("photos").download(path: path)
        if let cached = try await offline.storage.from("photos").cachedFile(path: path) {
            banner = "Photo kept at \(cached.url.lastPathComponent)"
        }
    }

    /// The status sequence, followed until the task ends.
    func followStatus() async {
        do {
            for try await status in offline.syncStatus {
                banner = status.pendingMutationCount > 0 ? "\(status.pendingMutationCount) changes waiting" : nil
            }
        } catch {
            banner = nil
        }
    }

    /// Sends the queue when the app comes back to the foreground.
    func sync() async throws -> SyncReport {
        try await offline.syncPendingMutations()
    }
}

/// A list that follows a live read in a `.task`, and the status beside it.
struct TodoListView: View {

    /// The app's data.
    @ObservedObject var store: TodoStore

    /// The rows the live read shows.
    @State private var live: [Todo] = []

    var body: some View {
        List(live) { todo in
            Text(todo.title)
        }
        .overlay(alignment: .bottom) {
            if let banner = store.banner {
                Text(banner)
            }
        }
        .task {
            do {
                for try await snapshot in store.offline.from("todos").selectAsSequence(as: [Todo].self).order("title") {
                    live = snapshot.result ?? []
                }
            } catch {
                live = []
            }
        }
        .task {
            await store.followStatus()
        }
        .refreshable {
            await store.load()
        }
    }
}
