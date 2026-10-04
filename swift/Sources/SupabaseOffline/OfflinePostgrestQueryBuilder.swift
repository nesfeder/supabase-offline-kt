// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT

extension SupabaseOfflineClient {

    /// The table `table` in the client's default schema: reads and writes, as supabase-swift's
    /// `from(_:)` offers them, with the cache and the mutation queue behind them.
    public func from(_ table: String) -> OfflinePostgrestQueryBuilder {
        OfflinePostgrestQueryBuilder(client: self, table: table, schema: nil)
    }

    /// The table `table` in `schema`. A table of another schema is cached apart from the default
    /// schema's table of the same name, and a write to it is never queued: it is sent at once.
    public func from(schema: String, table: String) -> OfflinePostgrestQueryBuilder {
        OfflinePostgrestQueryBuilder(client: self, table: table, schema: schema)
    }
}

/// One table's reads and writes: the start of every PostgREST chain.
///
/// Each verb takes its offline choice as a parameter with a default, so a chain written for
/// supabase-swift reads the same: `from("todos").select().eq("done", value: false).execute()`.
public struct OfflinePostgrestQueryBuilder: Sendable {

    /// The client the requests go through.
    let client: SupabaseOfflineClient

    /// The table.
    public let table: String

    /// The table's schema; `nil` for the client's default.
    public let schema: String?

    init(client: SupabaseOfflineClient, table: String, schema: String?) {
        self.client = client
        self.table = table
        self.schema = schema
    }

    /// A one-shot read of `columns` (`*`, or a list such as `id,title,author:authors(name)`).
    ///
    /// - Parameters:
    ///   - head: Whether to fetch no rows, only the ``OfflineResponse/count``.
    ///   - count: How to count the matching rows; a counted read goes to the server under every
    ///     strategy but ``ReadStrategy/cacheOnly(maxAge:)``.
    ///   - readStrategy: Where the rows come from.
    public func select(
        _ columns: String = "*",
        head: Bool = false,
        count: Count? = nil,
        readStrategy: ReadStrategy = .networkFirst
    ) -> OfflineSelectRequestBuilder {
        var shape = RequestShape()
        shape.count = count
        return OfflineSelectRequestBuilder(
            target: self,
            columns: columns,
            isHead: head,
            readStrategy: readStrategy,
            shape: shape
        )
    }

    /// A live read of `columns`, decoded as `type`: a sequence of ``QuerySnapshot`` values that
    /// changes with every local write, sync, refresh and server record that touches its rows. The
    /// Android library's `selectAsFlow`.
    ///
    /// Chain filters and transforms onto it, then iterate it: each iteration subscribes, and
    /// leaving the loop or cancelling its task ends the subscription. Iterating keeps the client
    /// open.
    public func selectAsSequence<Value: Decodable & Sendable>(
        _ columns: String = "*",
        as type: Value.Type = Value.self,
        readStrategy: SnapshotStrategy = .cacheFirstThenNetwork
    ) -> QuerySnapshotSequence<Value> {
        QuerySnapshotSequence(target: self, columns: columns, readStrategy: readStrategy, shape: RequestShape())
    }

    /// Inserts `values`: one row, or an array of rows.
    ///
    /// A `nil` property of a `Codable` row is left out of its JSON, so the column takes its
    /// default; pass ``OfflineJSON`` to send an explicit `null`.
    ///
    /// - Parameters:
    ///   - defaultToNull: Whether a column some rows leave out is `null` in them (`true`) or its
    ///     default (`false`), when rows of different columns go in one request.
    ///   - mutationStrategy: Whether the write may wait in the mutation queue.
    public func insert(
        _ values: some Encodable,
        defaultToNull: Bool = true,
        mutationStrategy: MutationStrategy = .queueIfOffline
    ) -> OfflineMutationRequestBuilder {
        write(.insert, body: Self.bodyText(values), mutationStrategy: mutationStrategy, defaultToNull: defaultToNull)
    }

    /// Inserts `values` written as JSON, such as `["title": "Milk", "notes": nil]`: one object, or
    /// an array of them.
    public func insert(
        _ values: OfflineJSON,
        defaultToNull: Bool = true,
        mutationStrategy: MutationStrategy = .queueIfOffline
    ) -> OfflineMutationRequestBuilder {
        write(.insert, body: Self.bodyText(values), mutationStrategy: mutationStrategy, defaultToNull: defaultToNull)
    }

    /// Inserts `values`, or merges each into the row it conflicts with on `onConflict`'s columns
    /// (the primary key when `nil`); with `ignoreDuplicates`, a conflicting row is left as it is.
    public func upsert(
        _ values: some Encodable,
        onConflict: String? = nil,
        ignoreDuplicates: Bool = false,
        defaultToNull: Bool = true,
        mutationStrategy: MutationStrategy = .queueIfOffline
    ) -> OfflineMutationRequestBuilder {
        write(
            .upsert,
            body: Self.bodyText(values),
            mutationStrategy: mutationStrategy,
            defaultToNull: defaultToNull,
            onConflict: onConflict,
            ignoresDuplicates: ignoreDuplicates
        )
    }

    /// Upserts `values` written as JSON: one object, or an array of them.
    public func upsert(
        _ values: OfflineJSON,
        onConflict: String? = nil,
        ignoreDuplicates: Bool = false,
        defaultToNull: Bool = true,
        mutationStrategy: MutationStrategy = .queueIfOffline
    ) -> OfflineMutationRequestBuilder {
        write(
            .upsert,
            body: Self.bodyText(values),
            mutationStrategy: mutationStrategy,
            defaultToNull: defaultToNull,
            onConflict: onConflict,
            ignoresDuplicates: ignoreDuplicates
        )
    }

    /// Sets the columns `values` encodes on the rows the filters that follow match.
    ///
    /// A `nil` property is left out, so that column keeps its value; to clear a column, pass
    /// ``OfflineJSON``: `update(["notes": nil])`. Queueing needs a filter the queue can record
    /// exactly, such as `eq` on the key; any other update is sent at once.
    public func update(
        _ values: some Encodable,
        mutationStrategy: MutationStrategy = .queueIfOffline
    ) -> OfflineMutationRequestBuilder {
        write(.update, body: Self.bodyText(values), mutationStrategy: mutationStrategy)
    }

    /// Sets `values` on the rows the filters that follow match; a `nil` value sends `null`.
    public func update(
        _ values: [String: OfflineJSON],
        mutationStrategy: MutationStrategy = .queueIfOffline
    ) -> OfflineMutationRequestBuilder {
        write(.update, body: Self.bodyText(values), mutationStrategy: mutationStrategy)
    }

    /// Deletes the rows the filters that follow match. Queueing needs a filter the queue can record
    /// exactly, such as `eq` on the key; any other delete is sent at once.
    public func delete(mutationStrategy: MutationStrategy = .queueIfOffline) -> OfflineMutationRequestBuilder {
        write(.delete, body: nil, mutationStrategy: mutationStrategy)
    }

    /// A write of `operation` on this table.
    private func write(
        _ operation: MutationOperation,
        body: Result<String, any Error>?,
        mutationStrategy: MutationStrategy,
        defaultToNull: Bool = true,
        onConflict: String? = nil,
        ignoresDuplicates: Bool = false
    ) -> OfflineMutationRequestBuilder {
        OfflineMutationRequestBuilder(
            target: self,
            operation: operation,
            body: body,
            mutationStrategy: mutationStrategy,
            defaultsToNull: defaultToNull,
            onConflict: onConflict,
            ignoresDuplicates: ignoresDuplicates
        )
    }

    /// `values` as JSON text, encoded now, on the caller's actor, or why it could not be.
    static func bodyText(_ values: some Encodable) -> Result<String, any Error> {
        Result { try RowCoding.jsonText(for: values) }
    }

    /// The description fields every request on this table carries: the table and its schema.
    var targetFields: [String: OfflineJSON] {
        var fields: [String: OfflineJSON] = ["table": .string(table)]
        if let schema { fields["schema"] = .string(schema) }
        return fields
    }
}
