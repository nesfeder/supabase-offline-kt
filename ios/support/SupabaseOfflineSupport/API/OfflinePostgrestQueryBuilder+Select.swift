import Foundation
import SupabaseOffline

public extension OfflinePostgrestQueryBuilder {

    /// Select all columns from the table with optional parameters.
    /// - Parameters:
    ///   - readStrategy: The strategy for reading data (networkFirst, cacheFirst, etc.).
    ///   - orderBy: The column to order the results by.
    ///   - ascending: Whether to order the results in ascending order.
    ///   - nullsFirst: Whether to place null values first in the order.
    ///   - referencedTable: The referenced table for foreign key relationships.
    ///   - limit: The maximum number of results to return.
    ///   - offset: The number of results to skip before starting to return results.
    ///   - count: The count option for the select operation.
    ///   - filter: A closure to apply `WHERE` conditions to the query.
    /// - Returns: A `PostgrestResult` containing the selected rows.
    func selectAll(
        readStrategy: SupabaseReadStrategy = .networkFirst,
        orderBy: String? = nil,
        ascending: Bool = true,
        nullsFirst: Bool = false,
        referencedTable: String? = nil,
        limit: Int? = nil,
        offset: Int? = nil,
        count: PostgrestCount? = nil,
        filter: ((PostgrestFilter) -> Void)? = nil
    ) async throws -> PostgrestResult {
        try await self.selectAllKt(
            readStrategy: readStrategy.toKMP(),
            orderBy: orderBy,
            ascending: ascending,
            nullsFirst: nullsFirst,
            referencedTable: referencedTable,
            limit: limit.map(KotlinInt.init(integerLiteral:)),
            offset: offset.map(KotlinInt.init(integerLiteral:)),
            count: count,
            filter: filter
        )
    }

    /// Select specific columns from the table with optional parameters.
    /// - Parameters:
    ///   - columns: A comma-separated string of columns to select.
    ///   - readStrategy: The strategy for reading data (networkFirst, cacheFirst, etc.).
    ///   - orderBy: The column to order the results by.
    ///   - ascending: Whether to order the results in ascending order.
    ///   - nullsFirst: Whether to place null values first in the order.
    ///   - referencedTable: The referenced table for foreign key relationships.
    ///   - limit: The maximum number of results to return.
    ///   - offset: The number of results to skip before starting to return results.
    ///   - count: The count option for the select operation.
    ///   - filter: A closure to apply `WHERE` conditions to the query.
    /// - Returns: A `PostgrestResult` containing the selected rows.
    func selectRaw(
        columns: String,
        readStrategy: SupabaseReadStrategy = .networkFirst,
        orderBy: String? = nil,
        ascending: Bool = true,
        nullsFirst: Bool = false,
        referencedTable: String? = nil,
        limit: Int? = nil,
        offset: Int? = nil,
        count: PostgrestCount? = nil,
        filter: ((PostgrestFilter) -> Void)? = nil
    ) async throws -> PostgrestResult {
        try await self.selectRawKt(
            columns: columns,
            readStrategy: readStrategy.toKMP(),
            orderBy: orderBy,
            ascending: ascending,
            nullsFirst: nullsFirst,
            referencedTable: referencedTable,
            limit: limit.map(KotlinInt.init(integerLiteral:)),
            offset: offset.map(KotlinInt.init(integerLiteral:)),
            count: count,
            filter: filter
        )
    }

    /// Select specific columns from the table with optional parameters.
    /// - Parameters:
    ///   - columns: An array of column names to select.
    ///   - readStrategy: The strategy for reading data (networkFirst, cacheFirst, etc.).
    ///   - orderBy: The column to order the results by.
    ///   - ascending: Whether to order the results in ascending order.
    ///   - nullsFirst: Whether to place null values first in the order.
    ///   - referencedTable: The referenced table for foreign key relationships.
    ///   - limit: The maximum number of results to return.
    ///   - offset: The number of results to skip before starting to return results.
    ///   - count: The count option for the select operation.
    ///   - filter: A closure to apply `WHERE` conditions to the query.
    /// - Returns: A `PostgrestResult` containing the selected rows.
    func selectColumns(
        columns: [String],
        readStrategy: SupabaseReadStrategy = .networkFirst,
        orderBy: String? = nil,
        ascending: Bool = true,
        nullsFirst: Bool = false,
        referencedTable: String? = nil,
        limit: Int? = nil,
        offset: Int? = nil,
        count: PostgrestCount? = nil,
        filter: ((PostgrestFilter) -> Void)? = nil
    ) async throws -> PostgrestResult {
        try await self.selectColumnsKt(
            columns: columns,
            readStrategy: readStrategy.toKMP(),
            orderBy: orderBy,
            ascending: ascending,
            nullsFirst: nullsFirst,
            referencedTable: referencedTable,
            limit: limit.map(KotlinInt.init(integerLiteral:)),
            offset: offset.map(KotlinInt.init(integerLiteral:)),
            count: count,
            filter: filter
        )
    }
}
