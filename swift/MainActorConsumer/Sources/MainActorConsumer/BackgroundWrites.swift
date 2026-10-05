// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline

/// A patch body, `nonisolated` because an actor of the app's own encodes it: under main-actor
/// default isolation every value type the package encodes or decodes off the main actor needs it.
nonisolated struct TodoPatch: Encodable, Sendable {
    var done: Bool
}

/// A Realtime record, `nonisolated` because a `@Sendable` callback hands it to the package.
nonisolated struct TodoRecord: Codable, Sendable {
    var id: UUID
    var title: String
    var done: Bool
}

/// An app's own filter value.
nonisolated struct TodoID: OfflineFilterValue {

    /// The id.
    var raw: UUID

    /// The id as PostgREST reads it.
    var filterText: String { raw.filterText }
}

/// Writes made off the main actor, as an app's own actor makes them.
actor TodoRepository {

    /// The app's one client.
    let offline: SupabaseOfflineClient

    init(offline: SupabaseOfflineClient) {
        self.offline = offline
    }

    /// A patch written from this actor.
    func markDone(id: UUID) async throws {
        try await offline.from("todos").update(TodoPatch(done: true)).eq("id", value: TodoID(raw: id)).execute()
    }

    /// Hands each record a Realtime-style callback reports to the package, from that callback.
    nonisolated func follow(_ subscribe: (@escaping @Sendable (TodoRecord) -> Void) -> Void) {
        subscribe { [offline] record in
            Task {
                try? await offline.applyServerRecord(table: "todos", record: record)
            }
        }
    }
}
