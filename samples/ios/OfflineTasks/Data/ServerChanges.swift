// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import Realtime
import SupabaseOffline

/// A row change the server reports, as Realtime delivers it: what the app applies to the
/// device's copy with ``TaskRepository/apply(_:)``.
nonisolated enum ServerChange: Equatable, Sendable {

    /// A row of `table` was inserted or updated; `record` is the whole row.
    case saved(table: String, record: [String: OfflineJSON])

    /// A row of `table` was deleted; `oldRecord` holds at least its key.
    case deleted(table: String, oldRecord: [String: OfflineJSON])
}

/// Where the server's row changes come from: Supabase Realtime for a project, the stand-in server
/// in demo mode.
nonisolated protocol ServerChangeSource: Sendable {

    /// The changes to `tables` from now on, until the iteration ends; it fails when the source
    /// cannot be reached or reports a change it cannot read.
    func changes(of tables: [String]) -> AsyncThrowingStream<ServerChange, any Error>
}

/// The project's row changes from Supabase Realtime, through supabase-swift, with the signed-in
/// user's token, so row-level security lets through that user's rows only.
nonisolated final class RealtimeChangeSource: ServerChangeSource {

    /// The Realtime connection; it connects when a channel subscribes.
    private let realtime: RealtimeClientV2

    /// A source for the project at `projectURL`, signing in with what `accessToken` returns.
    init(projectURL: URL, publishableKey: String, accessToken: @escaping @Sendable () async -> String?) {
        realtime = RealtimeClientV2(
            url: projectURL.appending(path: "realtime/v1"),
            options: RealtimeClientOptions(headers: ["apikey": publishableKey], accessToken: { await accessToken() })
        )
    }

    func changes(of tables: [String]) -> AsyncThrowingStream<ServerChange, any Error> {
        let realtime = realtime
        return AsyncThrowingStream { continuation in
            let channel = realtime.channel("offline-tasks")
            let listening = Task {
                do {
                    try await Self.listen(to: tables, on: channel) { continuation.yield($0) }
                    continuation.finish()
                } catch is CancellationError {
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: SampleFailure.liveUpdatesStopped)
                }
            }
            continuation.onTermination = { _ in
                listening.cancel()
                Task { await realtime.removeChannel(channel) }
            }
        }
    }

    // --8<-- [start:live-updates]
    /// Subscribes `channel` to every insert, update and delete of `tables`, and hands each change
    /// to `report` until the subscription is canceled.
    private static func listen(
        to tables: [String],
        on channel: RealtimeChannelV2,
        report: @escaping @Sendable (ServerChange) -> Void
    ) async throws {
        let streams = tables.map { table in (table, channel.postgresChange(AnyAction.self, schema: "public", table: table)) }
        try await channel.subscribeWithError()
        try await withThrowingDiscardingTaskGroup { group in
            for (table, actions) in streams {
                group.addTask {
                    for await action in actions { report(try ServerChange(action, table: table)) }
                }
            }
        }
    }
    // --8<-- [end:live-updates]
}

extension ServerChange {

    /// The change Realtime's `action` reports for `table`.
    nonisolated init(_ action: AnyAction, table: String) throws {
        switch action {
        case .insert(let insert):
            self = .saved(table: table, record: try Self.values(insert.record))
        case .update(let update):
            self = .saved(table: table, record: try Self.values(update.record))
        case .delete(let delete):
            self = .deleted(table: table, oldRecord: try Self.values(delete.oldRecord))
        }
    }

    /// `record`'s values as the offline client's JSON: the same JSON, read again.
    private nonisolated static func values(_ record: [String: AnyJSON]) throws -> [String: OfflineJSON] {
        try JSONDecoder().decode([String: OfflineJSON].self, from: JSONEncoder().encode(record))
    }
}
