// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import SupabaseOfflineCore

extension SupabaseOfflineClient {

    /// Deletes everything the client keeps on the device: cached query results, downloaded files,
    /// every queued write, which is never sent, and the rejected list.
    ///
    /// Call it when the signed-in user changes and one access-token closure serves both users: the
    /// client has one identity, so the cache and the queue would otherwise carry over. A sync pass
    /// in flight is cancelled first, live reads show nothing cached and fetch again, and the client
    /// stays usable.
    public func clearLocalData() async throws {
        try await connection.call { core, complete in
            core.clearLocalData { failure in complete(coreResult(failure)) }
        }
    }

    /// Applies a row the server reports, such as a Realtime INSERT or UPDATE record, to every cached
    /// query of `table`: the cached row with the same key is replaced, and a query that admits the
    /// row gains it.
    ///
    /// Realtime does not replay what happened while the device was disconnected, so this keeps the
    /// cache current between refreshes and never replaces them: read again after a reconnect. A
    /// record without its whole key is logged and dropped.
    ///
    /// - Parameters:
    ///   - table: The table the row belongs to.
    ///   - record: The row: anything that encodes as a JSON object, such as supabase-swift's
    ///     Realtime record.
    ///   - primaryKeys: The row's key columns; `nil` for the table's configured key, or `id` for a
    ///     table of another schema.
    ///   - schema: The table's schema; `nil` for the client's default.
    /// - Throws: ``OfflineError``: `encoding` when `record` cannot be encoded, `invalidRequest` when
    ///   it does not encode as a JSON object.
    public func applyServerRecord(
        table: String,
        record: some Encodable,
        primaryKeys: [String]? = nil,
        schema: String? = nil
    ) async throws {
        let recordText = try RowCoding.jsonText(for: record)
        try await connection.call { core, complete in
            core.applyServerRecord(table: table, record: recordText, primaryKeys: primaryKeys, schema: schema) { failure in
                complete(coreResult(failure))
            }
        }
    }

    /// Applies a row the server reports, written as JSON, such as `["id": 7, "title": "Milk"]`.
    public func applyServerRecord(
        table: String,
        record: [String: OfflineJSON],
        primaryKeys: [String]? = nil,
        schema: String? = nil
    ) async throws {
        try await applyServerRecord(table: table, record: OfflineJSON.object(record), primaryKeys: primaryKeys, schema: schema)
    }

    /// Removes a row the server reports deleted, such as a Realtime DELETE's old record, from every
    /// cached query of `table`.
    ///
    /// - Parameters:
    ///   - table: The table the row belonged to.
    ///   - oldRecord: The deleted row's values, its whole key at least.
    ///   - primaryKeys: The row's key columns; `nil` for the table's configured key, or `id` for a
    ///     table of another schema.
    ///   - schema: The table's schema; `nil` for the client's default.
    /// - Throws: ``OfflineError``: `encoding` when `oldRecord` cannot be encoded, `invalidRequest`
    ///   when it does not encode as a JSON object.
    public func applyServerDelete(
        table: String,
        oldRecord: some Encodable,
        primaryKeys: [String]? = nil,
        schema: String? = nil
    ) async throws {
        let recordText = try RowCoding.jsonText(for: oldRecord)
        try await connection.call { core, complete in
            core.applyServerDelete(table: table, oldRecord: recordText, primaryKeys: primaryKeys, schema: schema) { failure in
                complete(coreResult(failure))
            }
        }
    }

    /// Removes a row the server reports deleted, its old values written as JSON, such as `["id": 7]`.
    public func applyServerDelete(
        table: String,
        oldRecord: [String: OfflineJSON],
        primaryKeys: [String]? = nil,
        schema: String? = nil
    ) async throws {
        try await applyServerDelete(table: table, oldRecord: OfflineJSON.object(oldRecord), primaryKeys: primaryKeys, schema: schema)
    }
}
