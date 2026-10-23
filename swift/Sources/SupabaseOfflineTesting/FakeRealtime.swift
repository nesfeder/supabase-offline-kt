// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
public import SupabaseOffline
import SupabaseOfflineCore

/// The server's row changes a test reports to an in-memory client, in place of a Realtime channel.
///
/// Hand one to `SupabaseOfflineClient.inMemory(…)`. The client joins a channel here as it would
/// Realtime's, for the tables `SupabaseOfflineClient.Config.realtimeTables` names, and the fake
/// answers each join at once; wait for a table in ``subscribedTables`` before reporting a change to
/// it, since nothing is replayed. Each report reaches the cached queries as
/// `SupabaseOfflineClient.applyServerRecord(table:record:primaryKeys:schema:)` and
/// `SupabaseOfflineClient.applyServerDelete(table:oldRecord:primaryKeys:schema:)` apply a record.
public final class FakeRealtime: @unchecked Sendable {

    /// The core's fake, which the in-memory client follows.
    let relay: BridgeFakeRealtime

    /// A fake with no channel joined.
    public init() {
        relay = BridgeFakeRealtime()
    }

    /// The tables the client follows now: a name alone in the default schema, `schema.table` in
    /// another; empty while no channel is joined.
    public var subscribedTables: Set<String> {
        relay.subscribedTables
    }

    /// How many reports found no joined channel following their table and were dropped.
    public var droppedReportCount: Int {
        Int(relay.droppedReportCount)
    }

    /// Reports a row of `table` inserted on the server.
    ///
    /// - Parameters:
    ///   - table: The table's name.
    ///   - record: The whole row.
    ///   - schema: The table's schema; `nil` for the default schema.
    /// - Throws: `OfflineError`: `invalidRequest` when the record is not a JSON
    ///   object.
    public func reportInsert(table: String, record: [String: OfflineJSON], schema: String? = nil) throws {
        try reported(relay.reportInsert(table: table, record: try Self.text(of: record), schema: schema))
    }

    /// Reports a row of `table` updated on the server.
    ///
    /// - Parameters:
    ///   - table: The table's name.
    ///   - record: The whole row after the update.
    ///   - schema: The table's schema; `nil` for the default schema.
    /// - Throws: `OfflineError`: `invalidRequest` when the record is not a JSON
    ///   object.
    public func reportUpdate(table: String, record: [String: OfflineJSON], schema: String? = nil) throws {
        try reported(relay.reportUpdate(table: table, record: try Self.text(of: record), schema: schema))
    }

    /// Reports a row of `table` deleted on the server.
    ///
    /// - Parameters:
    ///   - table: The table's name.
    ///   - oldRecord: The deleted row's values, at least its primary key.
    ///   - schema: The table's schema; `nil` for the default schema.
    /// - Throws: `OfflineError`: `invalidRequest` when the record is not a JSON
    ///   object.
    public func reportDelete(table: String, oldRecord: [String: OfflineJSON], schema: String? = nil) throws {
        try reported(relay.reportDelete(table: table, oldRecord: try Self.text(of: oldRecord), schema: schema))
    }

    /// Ends every joined channel as a server that refuses the joins or closes the channels would:
    /// the client's `SupabaseOfflineClient.realtimeStatus` reads `stalled`, with `message` as the
    /// cause's, and the client joins each table again after a short wait.
    public func failChannel(message: String = "The channel was refused") {
        relay.failChannel(message: message)
    }

    /// Ends every joined channel as a lost socket would: the client joins each table again after a
    /// short wait.
    public func dropChannel() {
        relay.dropChannel()
    }

    /// Ends the channel of `table` alone, as a server that refuses that table's binding would: its
    /// entry in `SupabaseOfflineClient.realtimeTableStatus` reads `stalled`, with `message` as the
    /// cause's, the other tables stay joined and keep receiving reports, and the client joins
    /// `table` again after a short wait.
    ///
    /// - Parameters:
    ///   - table: The table's name.
    ///   - message: Why the server refused it.
    ///   - schema: The table's schema; `nil` for the default schema.
    public func failTable(table: String, message: String = "The table's binding was refused", schema: String? = nil) {
        relay.failTable(table: table, message: message, schema: schema)
    }

    /// Throws the core's refusal of a report, when there is one.
    private func reported(_ failure: BridgeError?) throws {
        guard let failure else { return }
        throw offlineError(code: .invalidRequest, message: failure.message)
    }

    /// The record as JSON text.
    private static func text(of record: [String: OfflineJSON]) throws -> String {
        let data = try JSONEncoder().encode(OfflineJSON.object(record))
        guard let text = String(data: data, encoding: .utf8) else {
            throw offlineError(code: .encoding, message: "The record could not be written as JSON text")
        }
        return text
    }
}
