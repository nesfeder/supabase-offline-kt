// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import SupabaseOfflineCore

/// A table the client keeps current through Realtime, named in ``SupabaseOfflineClient/Config/realtimeTables``.
///
/// The client joins one channel for every named table while the subscription is on and the
/// network is not known down, applies each insert, update and delete as
/// ``SupabaseOfflineClient/applyServerRecord(table:record:primaryKeys:schema:)-(_,Encodable,_,_)`` and
/// ``SupabaseOfflineClient/applyServerDelete(table:oldRecord:primaryKeys:schema:)-(_,Encodable,_,_)``
/// do, and reads the named tables again on every join, since Realtime replays nothing. The table
/// must be in the project's `supabase_realtime` publication, and row-level security decides which
/// rows reach the channel.
public struct RealtimeTable: Sendable, Hashable {

    /// The table's name.
    public let table: String

    /// Its schema; `nil` for ``SupabaseOfflineClient/Config/defaultSchema``.
    public let schema: String?

    /// One condition the server evaluates before sending a change; `nil` for every row the user
    /// may read.
    public let filter: RealtimeFilter?

    /// Names a table, in `schema` or the default one, with at most one filter.
    public init(_ table: String, schema: String? = nil, filter: RealtimeFilter? = nil) {
        self.table = table
        self.schema = schema
        self.filter = filter
    }
}

/// One condition Realtime evaluates on the server before it sends a change of a ``RealtimeTable``:
/// a change of a row that does not meet it is not sent.
///
/// Realtime evaluates `eq`, `neq`, `gt`, `gte`, `lt`, `lte` and `in`; a table whose filter names
/// another operator fails the client's open with ``OfflineError/Code/configuration``. Row-level
/// security applies as well, and narrows a table to the user's rows without a filter.
public struct RealtimeFilter: Sendable, Hashable {

    /// The operators Realtime evaluates.
    static let evaluatedOperators: Set<FilterOperator> = [.eq, .neq, .gt, .gte, .lt, .lte, .in]

    /// The column compared.
    public let column: String

    /// The comparison.
    public let `operator`: FilterOperator

    /// What the column is compared with: one value, or the list of `in`.
    let value: FilterNode.ConditionValue

    /// Compares `column` with one value; ``FilterOperator/in`` with one value is a list of one.
    public init(column: String, operator: FilterOperator, value: some OfflineFilterValue) {
        self.column = column
        self.operator = `operator`
        let text = value.offlineFilterText
        self.value = `operator` == .in ? .list([text]) : .single(text)
    }

    /// Keeps the rows whose `column` is one of `values`: the ``FilterOperator/in`` comparison.
    public init(column: String, in values: [some OfflineFilterValue]) {
        self.column = column
        self.operator = .in
        self.value = .list(values.map(\.offlineFilterText))
    }
}

extension RealtimeTable {

    /// The table as the core takes it.
    var core: BridgeRealtimeTable {
        BridgeRealtimeTable(table: table, schema: schema, filter: filter?.core)
    }
}

extension RealtimeFilter {

    /// The filter as the core takes it: the operator by its request name, the value as text.
    var core: BridgeRealtimeFilter {
        switch value {
        case .single(let text):
            return BridgeRealtimeFilter(column: column, operatorName: `operator`.rawValue, value: text, values: nil)
        case .list(let texts):
            return BridgeRealtimeFilter(column: column, operatorName: `operator`.rawValue, value: nil, values: texts)
        case .range(let lower, let upper):
            return BridgeRealtimeFilter(column: column, operatorName: `operator`.rawValue, value: "(\(lower),\(upper))", values: nil)
        }
    }
}
