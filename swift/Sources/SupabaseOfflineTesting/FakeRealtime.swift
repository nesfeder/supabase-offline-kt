// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
public import SupabaseOffline
import SupabaseOfflineCore

/// The server's row changes a test reports to an in-memory client, in place of a Realtime channel.
///
/// Hand one to `SupabaseOfflineClient.inMemory(…)`. The client joins a channel here as it would
/// Realtime's, for the tables `SupabaseOfflineClient.Config.realtimeTables` names, and the fake
/// answers each join at once. ``subscribedTables`` says which tables the client follows as soon as
/// it opens a channel, and ``joinCount`` moves once the client has handled the join: it has
/// reported `RealtimeStatus.subscribed` and asked for the named tables to be read again, since
/// nothing is replayed. Wait for ``joinCount`` before reporting a change, or the join may be
/// handled after it. Each report reaches the cached queries as
/// `SupabaseOfflineClient.applyServerRecord(table:record:primaryKeys:schema:)` and
/// `SupabaseOfflineClient.applyServerDelete(table:oldRecord:primaryKeys:schema:)` apply a record.
///
/// The test's `FakeServer` plays the server, and the fake only the channel: the client reads the
/// named tables from the server on every join, and reads a query again after a change that met a
/// read in flight or that it cannot apply to the query exactly. So the server must answer with the
/// rows as they are after each change the test reports; one that still answers with the rows from
/// before has those reads take the change back out of the cache.
///
/// A report reaches the client as Realtime would send it: an insert or an update whose record does
/// not meet the table's `RealtimeFilter` is dropped, as the server evaluates the filter before it
/// sends, and a delete is not, as Realtime sends a delete to every subscriber of the table.
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

    /// How many joins the client has handled, every channel counted: it has reported the channel
    /// subscribed and asked for the named tables to be read again, though those reads may still be
    /// in flight. Wait for it to move, after the client opens and after each ``failChannel(message:)``,
    /// ``dropChannel()`` or switch back on, before reporting a change.
    public var joinCount: Int {
        Int(relay.joinCount)
    }

    /// How many reports were dropped: those that found no joined channel following their table, and
    /// the inserts and updates whose record does not meet the table's `RealtimeFilter`.
    public var droppedReportCount: Int {
        Int(relay.droppedReportCount)
    }

    /// Reports a row of `table` inserted on the server; dropped when `record` does not meet the
    /// table's `RealtimeFilter`.
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

    /// Reports a row of `table` updated on the server; dropped when `record`, the row after the
    /// update, does not meet the table's `RealtimeFilter`.
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

    /// Reports a row of `table` deleted on the server, whatever the table's `RealtimeFilter`.
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

    /// Ends the joined channel as a server that refuses the join or closes the channel would: the
    /// client's `SupabaseOfflineClient.realtimeStatus` reads `stalled`, with `message` as the
    /// cause's, and the client joins again after a short wait.
    public func failChannel(message: String = "The channel was refused") {
        relay.failChannel(message: message)
    }

    /// Ends the joined channel as a lost socket would: the client joins again after a short wait.
    public func dropChannel() {
        relay.dropChannel()
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
