import Foundation
import SupabaseOffline

public extension OfflinePostgrestQueryBuilder {
    
    /// Perform an upsert using an array of JSON-encoded objects.
    /// - Parameters:
    ///   - jsonArray: A JSON-encoded array of objects to upsert.
    ///   - onConflict: An array of column names to use for conflict resolution. Typically used for natural keys (e.g., "serial_number").
    ///   - ignoreDuplicates: If true, ignores duplicate records instead of updating them.
    ///   - returningColumns: A comma-separated string of columns to return, or "*" to return all columns.
    ///   - count: The count option for the upsert operation.
    ///   - defaultToNull: If true, missing fields in the JSON objects will be set to null.
    ///   - mutationStrategy: The strategy for the mutation operation (queueIfOffline, onlineOnly).
    /// - Returns: A `PostgrestResult` containing the result of the upsert operation.
    func upsertJsonString(
        jsonArray: String,
        onConflict: [String]? = nil,
        ignoreDuplicates: Bool = false,
        returningColumns: String? = nil,
        count: PostgrestCount? = nil,
        defaultToNull: Bool? = nil,
        mutationStrategy: SupabaseMutationStrategy = .queueIfOffline
    ) async throws -> PostgrestResult {
        let onConflictString = onConflict?.joined(separator: ",")
        return try await self.upsertJsonStringKt(
            jsonArray: jsonArray,
            onConflict: onConflictString,
            ignoreDuplicates: ignoreDuplicates,
            returningColumns: returningColumns,
            count: count,
            defaultToNull: defaultToNull.map(KotlinBoolean.init(bool:)),
            mutationStrategy: mutationStrategy.toKMP()
        )
    }

    /// Perform an upsert using an array of `Encodable` objects.
    /// - Parameters:
    ///   - values: An array of `Encodable` objects to upsert.
    ///   - onConflict: An array of column names to use for conflict resolution. Typically used for natural keys (e.g., "serial_number").
    ///   - ignoreDuplicates: If true, ignores duplicate records instead of updating them.
    ///   - returningColumns: A comma-separated string of columns to return, or "*" to return all columns.
    ///   - count: The count option for the upsert operation.
    ///   - defaultToNull: If true, missing fields in the JSON objects will be set to null.
    ///   - mutationStrategy: The strategy for the mutation operation (queueIfOffline, onlineOnly).
    /// - Returns: A `PostgrestResult` containing the result of the upsert operation.
    func upsertMany<T: Encodable>(
        values: [T],
        onConflict: [String]? = nil,
        ignoreDuplicates: Bool = false,
        returningColumns: String? = nil,
        count: PostgrestCount? = nil,
        defaultToNull: Bool? = nil,
        mutationStrategy: SupabaseMutationStrategy = .queueIfOffline
    ) async throws -> PostgrestResult {
        let jsonArray = try encodeToJsonArray(values: values)
        return try await self.upsertJsonString(
            jsonArray: jsonArray,
            onConflict: onConflict,
            ignoreDuplicates: ignoreDuplicates,
            returningColumns: returningColumns,
            count: count,
            defaultToNull: defaultToNull,
            mutationStrategy: mutationStrategy
        )
    }

    /// Perform an upsert using a single `Encodable` object.
    /// - Parameters:
    ///   - value: An `Encodable` object to upsert.
    ///   - onConflict: An array of column names to use for conflict resolution. Typically used for natural keys (e.g., "serial_number").
    ///   - ignoreDuplicates: If true, ignores duplicate records instead of updating them.
    ///   - returningColumns: A comma-separated string of columns to return, or "*" to return all columns.
    ///   - count: The count option for the upsert operation.
    ///   - defaultToNull: If true, missing fields in the JSON object will be set to null.
    ///   - mutationStrategy: The strategy for the mutation operation (queueIfOffline, onlineOnly).
    /// - Returns: A `PostgrestResult` containing the result of the upsert operation.
    func upsertOne<T: Encodable>(
        value: T,
        onConflict: [String]? = nil,
        ignoreDuplicates: Bool = false,
        returningColumns: String? = nil,
        count: PostgrestCount? = nil,
        defaultToNull: Bool? = nil,
        mutationStrategy: SupabaseMutationStrategy = .queueIfOffline
    ) async throws -> PostgrestResult {
        try await self.upsertMany(
            values: [value],
            onConflict: onConflict,
            ignoreDuplicates: ignoreDuplicates,
            returningColumns: returningColumns,
            count: count,
            defaultToNull: defaultToNull,
            mutationStrategy: mutationStrategy
        )
    }
}
