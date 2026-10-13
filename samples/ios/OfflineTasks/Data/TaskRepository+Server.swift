// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline

/// What only the server can answer: the schema's two database functions and an exact count. A
/// function and a count are never cached and never queued, so offline they fail.
extension TaskRepository {

    // --8<-- [start:rpc-no-params]
    /// Marks every task of the user done, in one call to the database function
    /// `complete_all_tasks()`. The device's lists learn of it when they are read again, or from
    /// Realtime when Live updates is on.
    func markAllDone() async throws {
        try await client.rpc("complete_all_tasks").execute()
    }
    // --8<-- [end:rpc-no-params]

    // --8<-- [start:rpc-typed]
    /// How many of the user's tasks the server holds as done, in all and among those created
    /// since `since`, from the database function `tasks_done_count`, each decoded as the number it
    /// returns: its parameters once typed, once as JSON values.
    func serverDoneCounts(since: Date) async throws -> (all: Int, since: Int) {
        // A typed value, encoded as JSON; a nil date counts every task.
        let all = try await client.rpc("tasks_done_count", params: DoneCountParameters(createdSince: nil))
            .execute(Int.self)
            .value
        // The same function, its parameter written as a JSON value.
        let recent = try await client.rpc("tasks_done_count", params: ["created_since": .string(since.formatted(.iso8601))])
            .execute(Int.self)
            .value
        return (all, recent)
    }
    // --8<-- [end:rpc-typed]

    // --8<-- [start:count]
    /// How many tasks the server holds for the user: an exact count, with no rows sent back. A
    /// count is never cached, so under the default strategy, as under any but cache-only, it asks
    /// the server, and offline it fails.
    func serverTaskCount() async throws -> Int {
        let response = try await client.from(Tables.tasks).select("id", head: true, count: .exact).execute()
        return response.count ?? 0
    }
    // --8<-- [end:count]
}

/// The parameters of `tasks_done_count`: count only the tasks created since a moment, or all.
nonisolated struct DoneCountParameters: Encodable, Sendable {
    let createdSince: Date?

    /// The function's parameter names.
    enum CodingKeys: String, CodingKey {
        case createdSince = "created_since"
    }

    func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        // Written as an explicit null, which the function reads as "every task".
        try container.encode(createdSince, forKey: .createdSince)
    }
}

extension [String: OfflineJSON] {

    /// This record decoded as `type`, as the server's JSON would be.
    nonisolated func decoded<Row: Decodable>(as type: Row.Type) throws -> Row {
        try JSONDecoder().decode(type, from: JSONEncoder().encode(self))
    }
}
